// Licensed to Shingo Omura under one or more contributor
// license agreements. See the NOTICE file distributed with
// this work for additional information regarding copyright
// ownership. Shingo Omura licenses this file to you under
// the Apache License, Version 2.0 (the "License"); you may
// not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package controllers

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/everpeace/kube-throttler/pkg/apis/schedule/v1alpha1"
	schedulev1alpha1 "github.com/everpeace/kube-throttler/pkg/apis/schedule/v1alpha1"
	scheduleclientset "github.com/everpeace/kube-throttler/pkg/generated/clientset/versioned"
	scheduleinformer "github.com/everpeace/kube-throttler/pkg/generated/informers/externalversions/schedule/v1alpha1"
	"github.com/pkg/errors"
	corev1 "k8s.io/api/core/v1"
	v1 "k8s.io/api/core/v1"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/labels"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/apimachinery/pkg/util/clock"
	utilruntime "k8s.io/apimachinery/pkg/util/runtime"
	"k8s.io/apimachinery/pkg/util/sets"
	"k8s.io/apimachinery/pkg/util/wait"
	corev1informer "k8s.io/client-go/informers/core/v1"
	"k8s.io/client-go/tools/cache"
	"k8s.io/client-go/util/workqueue"
	"k8s.io/klog/v2"

	apiequality "k8s.io/apimachinery/pkg/api/equality"
)

type ThrottleController struct {
	throttlerName                       string
	targetSchedulerName                 string
	reconcileTemporaryThresholdInterval time.Duration

	metricsRecorder *ThrottleMetricsRecorder

	scheduleClientset scheduleclientset.Clientset
	podInformer       corev1informer.PodInformer
	throttleInformer  scheduleinformer.ThrottleInformer
	cache             *reservedResourceAmounts

	clock     clock.Clock
	workqueue workqueue.RateLimitingInterface
}

func NewThrottleController(
	throttlerName, targetSchedulerName string,
	reconcileTemporaryThresholdInterval time.Duration,
	scheduleClient scheduleclientset.Clientset,
	throttleInformer scheduleinformer.ThrottleInformer,
	podInformer corev1informer.PodInformer,
	clock clock.Clock,
) *ThrottleController {
	c := &ThrottleController{
		throttlerName:                       throttlerName,
		targetSchedulerName:                 targetSchedulerName,
		reconcileTemporaryThresholdInterval: reconcileTemporaryThresholdInterval,
		metricsRecorder:                     NewThrottleMetricsRecorder(),
		scheduleClientset:                   scheduleClient,
		podInformer:                         podInformer,
		throttleInformer:                    throttleInformer,
		cache:                               newReservedResourceAmounts(),
		clock:                               clock,
		workqueue:                           workqueue.NewNamedRateLimitingQueue(workqueue.DefaultControllerRateLimiter(), "ThrottleController"),
	}
	c.setupEventHandler()
	return c
}

func (c *ThrottleController) reconcile(key string) error {
	klog.V(2).InfoS("Reconciling Throttle", "Throttle", key)
	ctx := context.Background()
	namespace, name, err := cache.SplitMetaNamespaceKey(key)
	if err != nil {
		utilruntime.HandleError(fmt.Errorf("invalid resource key: %s", key))
		return err
	}

	thr, err := c.scheduleClientset.ScheduleV1alpha1().Throttles(namespace).Get(ctx, name, metav1.GetOptions{})
	if err != nil {
		if apierrors.IsNotFound(err) {
			return nil
		}
		return err
	}

	affectedPods, err := c.affectedPods(thr)
	klog.V(2).Infof("Throttle %s/%s affects to %d pods", thr.Namespace, thr.Name, len(affectedPods))
	if err != nil {
		return err
	}

	used := schedulev1alpha1.ResourceAmount{}
	for _, p := range affectedPods {
		used = used.Add(schedulev1alpha1.ResourceAmountOfPod(p))
	}

	newStatus := thr.Status.DeepCopy()
	newStatus.Used = used
	calculatedThreshold := thr.Spec.CalculateThreshold(c.clock.Now())
	if !apiequality.Semantic.DeepEqual(thr.Status.CalculatedThreshold.Threshold, calculatedThreshold.Threshold) ||
		!apiequality.Semantic.DeepEqual(thr.Status.CalculatedThreshold.Messages, calculatedThreshold.Messages) {
		klog.V(2).InfoS("New calculatedThreshold will take effect",
			"Throttle", thr.Namespace+"/"+thr.Name,
			"CalculatedAt", calculatedThreshold.CalculatedAt,
			"Threshold", calculatedThreshold.Threshold,
			"Message", strings.Join(calculatedThreshold.Messages, ","),
		)
		newStatus.CalculatedThreshold = calculatedThreshold
	}
	newStatus.Throttled = newStatus.CalculatedThreshold.Threshold.IsThrottled(newStatus.Used, true)

	if !apiequality.Semantic.DeepEqual(thr.Status, *newStatus) {
		klog.V(2).InfoS("Updating status", "Throttle", thr.Namespace+"/"+thr.Name)
		thr.Status = *newStatus
		c.metricsRecorder.recordThrottleMetrics(thr)
		if thr, err = c.scheduleClientset.ScheduleV1alpha1().Throttles(namespace).UpdateStatus(ctx, thr, metav1.UpdateOptions{}); err != nil {
			utilruntime.HandleError(errors.Wrapf(err, "failed to update Throttle '%s' status", key))
			return err
		}
	} else {
		c.metricsRecorder.recordThrottleMetrics(thr)
		klog.V(2).InfoS("No need to update status", "Throttle", thr.Namespace+"/"+thr.Name)
	}

	// Once status is updated, affected pods is safe to un-reserve from reserved resoruce amount cache
	for _, p := range affectedPods {
		c.UnReserveOnThrottle(p, thr)
	}

	if len(thr.Spec.TemporaryThresholdOverrides) > 0 {
		go func(_thr *v1alpha1.Throttle) {
			klog.V(3).Infof("Reconciling after duration", "Throttle", thr.Namespace+"/"+thr.Name, "After", c.reconcileTemporaryThresholdInterval)
			<-c.clock.After(c.reconcileTemporaryThresholdInterval)
			c.enqueueThrottle(_thr)
		}(thr)
	}

	return nil
}

func (c *ThrottleController) isResponsibleFor(thr *schedulev1alpha1.Throttle) bool {
	return c.throttlerName == thr.Spec.ThrottlerName
}

func (c *ThrottleController) shouldCountIn(pod *corev1.Pod) bool {
	return pod.Spec.SchedulerName == c.targetSchedulerName && isScheduled(pod) && isNotFinished(pod)
}

func (c *ThrottleController) affectedPods(thr *schedulev1alpha1.Throttle) ([]*v1.Pod, error) {
	pods, err := c.podInformer.Lister().Pods(thr.Namespace).List(labels.Everything())
	if err != nil {
		return nil, err
	}

	affectedPods := []*v1.Pod{}
	for _, pod := range pods {
		if !(c.shouldCountIn(pod)) {
			continue
		}
		match, err := thr.Spec.Selector.MatchesToPod(pod)
		if err != nil {
			return nil, err
		}
		if match {
			affectedPods = append(affectedPods, pod)
		}
	}
	return affectedPods, nil
}

func (c *ThrottleController) affectedThrottles(pod *v1.Pod) ([]*schedulev1alpha1.Throttle, error) {
	throttles, err := c.throttleInformer.Lister().Throttles(pod.Namespace).List(labels.Everything())
	if err != nil {
		return nil, err
	}

	affectedThrottles := []*schedulev1alpha1.Throttle{}
	for _, throttle := range throttles {
		if !c.isResponsibleFor(throttle) {
			continue
		}
		match, err := throttle.Spec.Selector.MatchesToPod(pod)
		if err != nil {
			return nil, err
		}
		if match {
			affectedThrottles = append(affectedThrottles, throttle)
		}
	}

	return affectedThrottles, nil
}

func (c *ThrottleController) Reserve(pod *v1.Pod) error {
	throttles, err := c.affectedThrottles(pod)
	if err != nil {
		return err
	}
	for _, thr := range throttles {
		nn := types.NamespacedName{Namespace: thr.Namespace, Name: thr.Name}
		c.cache.addPod(nn, pod)
		reserved := c.cache.reservedResourceAmount(nn)
		klog.V(3).InfoS("Pod is reserved for affected throttle", "Pod", pod.Namespace+"/"+pod.Name, "Throttle", thr.Name, "CurrentReservedAmount", reserved)
	}
	if len(throttles) > 0 {
		klog.V(2).InfoS("Pod is reserved for affected throttles", "Pod", pod.Namespace+"/"+pod.Name, "#Throttles", len(throttles))
	}
	return nil
}

func (c *ThrottleController) moveThrottleAssignmentForPodsInReservation(
	fromPod *corev1.Pod,
	fromThrs []*schedulev1alpha1.Throttle,
	toPod *corev1.Pod,
	toThrs []*schedulev1alpha1.Throttle,
) {
	fromThrNNs := []types.NamespacedName{}
	for _, thr := range fromThrs {
		fromThrNNs = append(fromThrNNs, types.NamespacedName{Namespace: thr.Namespace, Name: thr.Name})
	}
	toThrNNs := []types.NamespacedName{}
	for _, thr := range toThrs {
		toThrNNs = append(toThrNNs, types.NamespacedName{Namespace: thr.Namespace, Name: thr.Name})
	}
	c.cache.moveThrottleAssignmentForPods(fromPod, fromThrNNs, toPod, toThrNNs)
}

func (c *ThrottleController) UnReserveOnThrottle(pod *v1.Pod, thr *schedulev1alpha1.Throttle) {
	nn := types.NamespacedName{Namespace: thr.Namespace, Name: thr.Name}
	removed := c.cache.removePod(nn, pod)
	reserved := c.cache.reservedResourceAmount(nn)
	if removed {
		klog.V(3).InfoS("Pod is un-reserved for affected throttle", "Pod", pod.Namespace+"/"+pod.Name, "Throttle", thr.Name, "CurrentReservedAmount", reserved)
	}
}

func (c *ThrottleController) UnReserve(pod *v1.Pod) error {
	throttles, err := c.affectedThrottles(pod)
	if err != nil {
		return err
	}
	for _, thr := range throttles {
		c.UnReserveOnThrottle(pod, thr)
	}
	if len(throttles) > 0 {
		klog.V(2).InfoS("Pod is un-reserved for affected throttles", "Pod", pod.Namespace+"/"+pod.Name, "#Throttles", len(throttles))
	}
	return nil
}

func (c *ThrottleController) CheckThrottled(pod *v1.Pod, isThrottledOnEqual bool) ([]schedulev1alpha1.Throttle, []schedulev1alpha1.Throttle, []schedulev1alpha1.Throttle, error) {
	throttles, err := c.affectedThrottles(pod)
	if err != nil {
		return nil, nil, nil, err
	}
	affected := []schedulev1alpha1.Throttle{}
	alreadyThrottled := []schedulev1alpha1.Throttle{}
	insufficient := []schedulev1alpha1.Throttle{}
	for _, thr := range throttles {
		affected = append(affected, *thr)
		reserved := c.cache.reservedResourceAmount(types.NamespacedName{Namespace: thr.Namespace, Name: thr.Name})
		checkStatus := thr.CheckThrottledFor(
			pod,
			reserved,
			isThrottledOnEqual,
		)
		klog.V(3).InfoS("CheckThrottled result",
			"Throttle", thr.Name,
			"Pod", pod.Namespace+"/"+pod.Name,
			"Result", checkStatus,
			"RequestedByPod", schedulev1alpha1.ResourceAmountOfPod(pod),
			"UsedInThrottle", thr.Status.Used,
			"ReservedInScheduler", reserved,
			"AmountForCheck", schedulev1alpha1.ResourceAmount{}.Add(thr.Status.Used).Add(schedulev1alpha1.ResourceAmountOfPod(pod)).Add(reserved),
			"Threashold", thr.Status.CalculatedThreshold.Threshold,
		)
		switch checkStatus {
		case schedulev1alpha1.CheckThrottleStatusActive:
			alreadyThrottled = append(alreadyThrottled, *thr)
		case schedulev1alpha1.CheckThrottleStatusInsufficient:
			insufficient = append(insufficient, *thr)
		}
	}
	return alreadyThrottled, insufficient, affected, nil
}

func (c *ThrottleController) setupEventHandler() {
	c.throttleInformer.Informer().AddEventHandler(cache.ResourceEventHandlerFuncs{
		AddFunc: func(obj interface{}) {
			thr := obj.(*v1alpha1.Throttle)
			if !c.isResponsibleFor(thr) {
				return
			}
			klog.V(4).InfoS("Add event", "Throttle", thr.Namespace+"/"+thr.Name)
			c.enqueueThrottle(thr)
		},
		UpdateFunc: func(oldObj, newObj interface{}) {
			thr := newObj.(*v1alpha1.Throttle)
			if !c.isResponsibleFor(thr) {
				return
			}
			klog.V(4).InfoS("Update event", "Throttle", thr.Namespace+"/"+thr.Name)
			c.enqueueThrottle(thr)
		},
		DeleteFunc: func(obj interface{}) {
			thr := obj.(*v1alpha1.Throttle)
			if !c.isResponsibleFor(thr) {
				return
			}
			klog.V(4).InfoS("Add event", "Throttle", thr.Namespace+"/"+thr.Name)
			c.enqueueThrottle(thr)
		},
	})

	c.podInformer.Informer().AddEventHandler(cache.ResourceEventHandlerFuncs{
		AddFunc: func(obj interface{}) {
			pod := obj.(*corev1.Pod)
			if !c.shouldCountIn(pod) {
				return
			}
			klog.V(4).InfoS("Add event", "Pod", pod.Namespace+"/"+pod.Name)

			throttles, err := c.affectedThrottles(pod)
			if err != nil {
				utilruntime.HandleError(errors.Wrapf(err, "Failed to get affected throttles for pod '%s'", pod.Namespace+"/"+pod.Name))
				return
			}

			klog.V(4).InfoS("Reconciling Throttles", "Pod", pod.Namespace+"/"+pod.Name, "#Throttles", len(throttles))
			for _, thr := range throttles {
				c.enqueueThrottle(thr)
			}
		},
		UpdateFunc: func(oldObj, newObj interface{}) {
			oldPod := oldObj.(*corev1.Pod)
			newPod := newObj.(*corev1.Pod)
			if !c.shouldCountIn(oldPod) && !c.shouldCountIn(newPod) {
				return
			}
			klog.V(4).InfoS("Update event", "Pod", newPod.Namespace+"/"+newPod.Name)

			throttleNames := sets.NewString()
			throttlesForOld, err := c.affectedThrottles(oldPod)
			if err != nil {
				utilruntime.HandleError(errors.Wrapf(err, "fail to get affected throttles for pod '%s'", oldPod.Namespace+"/"+oldPod.Name))
				return
			}
			throttlesForNew, err := c.affectedThrottles(newPod)
			if err != nil {
				utilruntime.HandleError(errors.Wrapf(err, "fail to get affected throttles for pod '%s'", newPod.Namespace+"/"+newPod.Name))
				return
			}

			if isScheduled(oldPod) && isScheduled(newPod) {
				c.moveThrottleAssignmentForPodsInReservation(oldPod, throttlesForOld, newPod, throttlesForNew)
			}

			for _, thr := range throttlesForOld {
				throttleNames.Insert(types.NamespacedName{Namespace: thr.Namespace, Name: thr.Name}.String())
			}
			for _, thr := range throttlesForNew {
				throttleNames.Insert(types.NamespacedName{Namespace: thr.Namespace, Name: thr.Name}.String())
			}

			klog.V(4).InfoS("Reconciling Throttles", "Pod", newPod.Namespace+"/"+newPod.Name, "Throttles", strings.Join(throttleNames.List(), ","))
			for _, key := range throttleNames.List() {
				c.enqueueThrottle(cache.ExplicitKey(key))
			}
		},
		DeleteFunc: func(obj interface{}) {
			pod := obj.(*corev1.Pod)
			if !c.shouldCountIn(pod) {
				return
			}
			klog.V(4).InfoS("Delete event", "Pod", pod.Namespace+"/"+pod.Name)
			// observe the deleted pod is now scheduled. controller should unreserve it.
			if isScheduled(pod) {
				if err := c.UnReserve(pod); err != nil {
					utilruntime.HandleError(errors.Wrapf(err, "Failed to unreserve pod '%s'", pod.Namespace+"/"+pod.Name))
				}
			}

			throttles, err := c.affectedThrottles(pod)
			if err != nil {
				utilruntime.HandleError(errors.Wrapf(err, "Failed to get affected throttles for pod '%s'", pod.Namespace+"/"+pod.Name))
				return
			}

			klog.V(4).InfoS("Reconciling Throttles", "Pod", pod.Namespace+"/"+pod.Name, "#Throttles", len(throttles))
			for _, thr := range throttles {
				c.enqueueThrottle(thr)
			}
		},
	})
}

func (c *ThrottleController) Start(threadiness int, stopCh <-chan struct{}) error {
	klog.InfoS("Starting ThrottleController", "name", c.throttlerName)
	if ok := cache.WaitForCacheSync(
		stopCh,
		c.throttleInformer.Informer().HasSynced,
		c.podInformer.Informer().HasSynced,
	); !ok {
		return errors.Errorf("failed to wait for caches to sync")
	}
	klog.InfoS("Informer caches are synced")

	// Launch  workers to process Foo resources
	for i := 0; i < threadiness; i++ {
		go wait.Until(c.runWorker, time.Second, stopCh)
	}

	klog.InfoS("Started ThrottleController workers", "threadiness", threadiness)
	return nil
}

func (c *ThrottleController) enqueueThrottle(obj interface{}) {
	var key string
	var err error
	if key, err = cache.MetaNamespaceKeyFunc(obj); err != nil {
		utilruntime.HandleError(err)
		return
	}
	c.workqueue.Add(key)
}

func (c *ThrottleController) runWorker() {
	for c.processNextWorkItem() {
	}
}

func (c *ThrottleController) processNextWorkItem() bool {
	obj, shutdown := c.workqueue.Get()

	if shutdown {
		return false
	}

	err := func(obj interface{}) error {
		defer c.workqueue.Done(obj)
		var key string
		var ok bool

		if key, ok = obj.(string); !ok {
			c.workqueue.Forget(obj)
			utilruntime.HandleError(fmt.Errorf("expected string in workqueue but got %#v", obj))
			return nil
		}
		if err := c.reconcile(key); err != nil {
			c.workqueue.AddRateLimited(key)
			return fmt.Errorf("error reconciling '%s': %s, requeuing", key, err.Error())
		}
		// get queued again until another change happens.
		c.workqueue.Forget(obj)
		klog.InfoS("Successfully reconciled", "Throttle", key)
		return nil
	}(obj)

	if err != nil {
		utilruntime.HandleError(err)
		return true
	}

	return true
}
