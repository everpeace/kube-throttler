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
	"time"

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
)

type ThrottleController struct {
	throttlerName                       string
	reconcileTemporaryThresholdInterval time.Duration

	scheduleClientset scheduleclientset.Clientset
	podInformer       corev1informer.PodInformer
	throttleInformer  scheduleinformer.ThrottleInformer
	cache             *reservedResourceAmounts

	clock     clock.Clock
	workqueue workqueue.RateLimitingInterface
}

func NewThrottleController(
	throttlerName string,
	throttleInformer scheduleinformer.ThrottleInformer,
	podInformer corev1informer.PodInformer,
	clock clock.Clock,
) *ThrottleController {
	c := &ThrottleController{
		throttlerName:    throttlerName,
		podInformer:      podInformer,
		throttleInformer: throttleInformer,
		cache:            newReservedResourceAmounts(),
		clock:            clock,
		workqueue:        workqueue.NewNamedRateLimitingQueue(workqueue.DefaultControllerRateLimiter(), "Throttles"),
	}
	c.setupEventHandler()
	return c
}

func (c *ThrottleController) reconcile(key string) error {
	ctx := context.Background()
	namespace, name, err := cache.SplitMetaNamespaceKey(key)
	if err != nil {
		utilruntime.HandleError(fmt.Errorf("invalid resource key: %s", key))
		return err
	}

	thr, err := c.scheduleClientset.ScheduleV1alpha1().Throttles(namespace).Get(ctx, name, metav1.GetOptions{})
	if err != nil {
		if apierrors.IsNotFound(err) {
			utilruntime.HandleError(fmt.Errorf("throttle '%s' in work queue no longer exists", key))
			return nil
		}
		return err
	}

	affectedPods, err := c.affectedPods(thr)
	if err != nil {
		return err
	}

	used := schedulev1alpha1.ResourceAmount{}
	for _, p := range affectedPods {
		used.Add(schedulev1alpha1.ResourceAmountOfPod(p))
	}
	thr.Status.Used = used

	thr.Status.CalculatedThreshold = thr.Spec.CurrentThreshold(c.clock.Now())

	thr.Status.Throttled = thr.Status.CalculatedThreshold.Threshold.IsThrottled(thr.Status.Used)
	if thr, err = c.scheduleClientset.ScheduleV1alpha1().Throttles(namespace).UpdateStatus(ctx, thr, metav1.UpdateOptions{}); err != nil {
		utilruntime.HandleError(errors.Wrapf(err, "failed to update throttle '%s' status", key))
		return err
	}

	if len(thr.Spec.TemporaryThresholdOverrides) > 0 {
		go func(_key string) {
			<-c.clock.After(c.reconcileTemporaryThresholdInterval)
			c.enqueueThrottle(_key)
		}(key)
	}

	return nil
}

func (c *ThrottleController) isResponsibleFor(thr *schedulev1alpha1.Throttle) bool {
	return c.throttlerName == thr.Spec.ThrottlerName
}

func (c *ThrottleController) shouldCountIn(pod *corev1.Pod) bool {
	return isScheduled(pod) && isNotFinished(pod)
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
		match, err := thr.Spec.MatchesToPod(pod)
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
		match, err := throttle.Spec.MatchesToPod(pod)
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
		c.cache.addPod(types.NamespacedName{Namespace: thr.Namespace, Name: thr.Name}, pod)
	}
	return nil
}

func (c *ThrottleController) UnReserve(pod *v1.Pod) error {
	throttles, err := c.affectedThrottles(pod)
	if err != nil {
		return err
	}
	for _, thr := range throttles {
		c.cache.removePod(types.NamespacedName{Namespace: thr.Namespace, Name: thr.Name}, pod)
	}
	return nil
}

func (c *ThrottleController) CheckThrottled(pod *v1.Pod) ([]schedulev1alpha1.Throttle, []schedulev1alpha1.Throttle, error) {
	throttles, err := c.affectedThrottles(pod)
	if err != nil {
		return nil, nil, err
	}
	alreadyThrottled := []schedulev1alpha1.Throttle{}
	insufficient := []schedulev1alpha1.Throttle{}
	for _, thr := range throttles {
		checkStatus := thr.CheckThrottledFor(pod, c.cache.reservedResourceAmount(types.NamespacedName{Namespace: thr.Namespace, Name: thr.Name}))
		switch checkStatus {
		case schedulev1alpha1.CheckThrottleStatusActive:
			alreadyThrottled = append(alreadyThrottled, *thr)
		case schedulev1alpha1.CheckThrottleStatusInsufficient:
			insufficient = append(insufficient, *thr)
		}
	}
	return alreadyThrottled, insufficient, nil
}

func (c *ThrottleController) setupEventHandler() {
	c.throttleInformer.Informer().AddEventHandler(cache.ResourceEventHandlerFuncs{
		AddFunc:    func(obj interface{}) { c.enqueueThrottle(obj) },
		UpdateFunc: func(oldObj, newObj interface{}) { c.enqueueThrottle(newObj) },
		DeleteFunc: func(obj interface{}) { c.enqueueThrottle(obj) },
	})

	c.podInformer.Informer().AddEventHandler(cache.ResourceEventHandlerFuncs{
		UpdateFunc: func(oldObj, newObj interface{}) {
			oldPod := oldObj.(*corev1.Pod)
			newPod := newObj.(*corev1.Pod)

			// observe the pod is now scheduled.  controller should unreserve it.
			if !isScheduled(oldPod) && isScheduled(newPod) {
				if err := c.UnReserve(newPod); err != nil {
					utilruntime.HandleError(errors.Wrapf(err, "fail to unreserve pod '%s'", newPod.Namespace+"/"+newPod.Name))
				}
			}

			throttleNames := sets.NewString()
			throttlesForOld, err := c.affectedThrottles(oldPod)
			if err != nil {
				utilruntime.HandleError(errors.Wrapf(err, "fail to get affected throttles for pod '%s'", oldPod.Namespace+"/"+oldPod.Name))
				return
			}
			for _, thr := range throttlesForOld {
				throttleNames.Insert(types.NamespacedName{Namespace: thr.Namespace, Name: thr.Name}.String())
			}

			throttlesForNew, err := c.affectedThrottles(newPod)
			if err != nil {
				utilruntime.HandleError(errors.Wrapf(err, "fail to get affected throttles for pod '%s'", newPod.Namespace+"/"+newPod.Name))
				return
			}
			for _, thr := range throttlesForNew {
				throttleNames.Insert(types.NamespacedName{Namespace: thr.Namespace, Name: thr.Name}.String())
			}

			for _, key := range throttleNames {
				c.enqueueThrottle(key)
			}
		},
	})
}

func (c *ThrottleController) Start(threadiness int, stopCh <-chan struct{}) error {
	defer utilruntime.HandleCrash()
	defer c.workqueue.ShutDown()

	klog.InfoS("Starting throttle controller", "name", c.throttlerName)
	klog.Info("Waiting for informer caches to sync")
	if ok := cache.WaitForCacheSync(stopCh, c.throttleInformer.Informer().HasSynced, c.podInformer.Informer().HasSynced); !ok {
		return errors.Errorf("failed to wait for caches to sync")
	}

	klog.Info("Starting throttle controller workers", "threadiness", threadiness)
	// Launch two workers to process Foo resources
	for i := 0; i < threadiness; i++ {
		go wait.Until(c.runWorker, time.Second, stopCh)
	}

	klog.Info("Started throttle controller workers", "threadiness", threadiness)
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
