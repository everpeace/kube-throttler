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
	utilruntime "k8s.io/apimachinery/pkg/util/runtime"
	"k8s.io/apimachinery/pkg/util/sets"
	corev1informer "k8s.io/client-go/informers/core/v1"
	"k8s.io/client-go/tools/cache"
	"k8s.io/client-go/util/workqueue"
	"k8s.io/klog/v2"
	"k8s.io/utils/clock"

	apiequality "k8s.io/apimachinery/pkg/api/equality"
)

type ClusterThrottleController struct {
	ControllerBase
	metricsRecorder         *ClusterThrottleMetricsRecorder
	namespaceInformer       corev1informer.NamespaceInformer
	clusterthrottleInformer scheduleinformer.ClusterThrottleInformer
}

func NewClusterThrottleController(
	throttlerName, targetSchedulerName string,
	reconcileTemporaryThresholdInterval time.Duration,
	scheduleClient scheduleclientset.Clientset,
	clusterthrottleInformer scheduleinformer.ClusterThrottleInformer,
	podInformer corev1informer.PodInformer,
	namespaceInformer corev1informer.NamespaceInformer,
	clock clock.Clock,
	thrediness int,
	numKeyMutex int,
) *ClusterThrottleController {
	controllerName := "ClusterThrottleController"
	c := &ClusterThrottleController{
		ControllerBase: ControllerBase{
			name:                controllerName,
			threadiness:         thrediness,
			targetKind:          "ClusterThrottle",
			throttlerName:       throttlerName,
			targetSchedulerName: targetSchedulerName,
			scheduleClientset:   scheduleClient,
			podInformer:         podInformer,
			cache:               newReservedResourceAmounts(numKeyMutex),
			clock:               clock,
			workqueue:           workqueue.NewNamedRateLimitingQueue(workqueue.DefaultControllerRateLimiter(), controllerName),
		},
		metricsRecorder:         NewClusterThrottleMetricsRecorder(),
		namespaceInformer:       namespaceInformer,
		clusterthrottleInformer: clusterthrottleInformer,
	}
	c.reconcileFunc = c.reconcile
	c.mustSetupEventHandler()
	return c
}

func (c *ClusterThrottleController) reconcile(key string) error {
	klog.V(2).InfoS("Reconciling ClusterThrottle", "ClusterThrottle", key)
	ctx := context.Background()
	now := c.clock.Now()

	_, name, err := cache.SplitMetaNamespaceKey(key)
	if err != nil {
		utilruntime.HandleError(fmt.Errorf("invalid resource key: %s", key))
		return err
	}

	thr, err := c.clusterthrottleInformer.Lister().Get(name)
	if err != nil {
		if apierrors.IsNotFound(err) {
			return nil
		}
		return err
	}

	affectedNonTerminatedPods, affectedTerminatedPods, err := c.affectedPods(thr)
	if err != nil {
		return err
	}
	if len(affectedNonTerminatedPods)+len(affectedTerminatedPods) > 0 {
		klog.V(2).InfoS(
			"Affected pods detected",
			"ClusterThrottle", thr.Namespace+"/"+thr.Name,
			"#AffectedPods(NonTerminated)", len(affectedNonTerminatedPods),
			"#AffectedPods(Terminated)", len(affectedTerminatedPods),
		)
	}

	used := schedulev1alpha1.ResourceAmount{}
	for _, p := range affectedNonTerminatedPods {
		used = used.Add(schedulev1alpha1.ResourceAmountOfPod(p))
	}
	newStatus := thr.Status.DeepCopy()
	newStatus.Used = used
	calculatedThreshold := thr.Spec.CalculateThreshold(now)
	if !apiequality.Semantic.DeepEqual(thr.Status.CalculatedThreshold.Threshold, calculatedThreshold.Threshold) ||
		!apiequality.Semantic.DeepEqual(thr.Status.CalculatedThreshold.Messages, calculatedThreshold.Messages) {
		klog.V(2).InfoS("New calculatedThreshold will take effect",
			"ClusterThrottle", thr.Namespace+"/"+thr.Name,
			"CalculatedAt", calculatedThreshold.CalculatedAt,
			"Threshold", calculatedThreshold.Threshold,
			"Message", strings.Join(calculatedThreshold.Messages, ","),
		)
		newStatus.CalculatedThreshold = calculatedThreshold
	}
	newStatus.Throttled = newStatus.CalculatedThreshold.Threshold.IsThrottled(newStatus.Used, true)

	unreserveAffectedPods := func() (schedulev1alpha1.ResourceAmount, sets.Set[string]) {
		// Once status is updated, affected pods is safe to un-reserve from reserved resoruce amount cache
		// We make sure to un-reserve terminated pods too here because it misses to unreserve terminated pods
		// when reconcile is rate-limitted
		unreservedPods := []string{}
		for _, p := range append(affectedNonTerminatedPods, affectedTerminatedPods...) {
			unreserved := c.UnReserveOnClusterThrottle(p, thr)
			if unreserved {
				unreservedPods = append(unreservedPods, p.Namespace+"/"+p.Name)
			}
		}
		if len(unreservedPods) > 0 {
			klog.V(2).InfoS(
				"Pods are un-reserved for ClusterThrottle",
				"ClusterThrottle", thr.Namespace+"/"+thr.Name,
				"#Pods", len(unreservedPods),
				"Pods", strings.Join(unreservedPods, ","),
			)
		}
		return c.cache.reservedResourceAmount(types.NamespacedName{Namespace: thr.Namespace, Name: thr.Name})
	}

	if !apiequality.Semantic.DeepEqual(thr.Status, *newStatus) {
		thr.Status = *newStatus
		c.metricsRecorder.recordClusterThrottleMetrics(thr)

		klog.V(2).InfoS("Updating status",
			"ClusterThrottle", thr.Namespace+"/"+thr.Name,
			"Used", thr.Status.Used,
			"Throttled", thr.Status.Throttled,
			"Threshold", thr.Status.CalculatedThreshold.Threshold,
			"CalculatedAt", thr.Status.CalculatedThreshold.CalculatedAt,
			"Message", strings.Join(thr.Status.CalculatedThreshold.Messages, ","),
		)

		if thr, err = c.scheduleClientset.ScheduleV1alpha1().ClusterThrottles().UpdateStatus(ctx, thr, metav1.UpdateOptions{}); err != nil {
			utilruntime.HandleError(errors.Wrapf(err, "failed to update ClusterThrottle '%s' status", key))
			return err
		}

		reservedAmt, reservedPodNNs := unreserveAffectedPods()
		klog.V(2).InfoS("Status updated successfully",
			"ClusterThrottle", thr.Namespace+"/"+thr.Name,
			"Used", thr.Status.Used,
			"Throttled", thr.Status.Throttled,
			"CalculatedAt", thr.Status.CalculatedThreshold.CalculatedAt,
			"Threshold", thr.Status.CalculatedThreshold.Threshold,
			"Message", strings.Join(thr.Status.CalculatedThreshold.Messages, ","),
			"ReservedAmountInScheduler", reservedAmt,
			"ReservedPodsInScheduler", strings.Join(sets.List(reservedPodNNs), ","),
		)
	} else {
		c.metricsRecorder.recordClusterThrottleMetrics(thr)
		reservedAmt, reservedPodNNs := unreserveAffectedPods()
		klog.V(2).InfoS("No need to update status",
			"ClusterThrottle", thr.Namespace+"/"+thr.Name,
			"Threshold", thr.Status.CalculatedThreshold.Threshold,
			"CalculatedAt", thr.Status.CalculatedThreshold.CalculatedAt,
			"Message", strings.Join(thr.Status.CalculatedThreshold.Messages, ","),
			"Used", thr.Status.Used,
			"Throttled", thr.Status.Throttled,
			"ReservedAmountInScheduler", reservedAmt,
			"ReservedPodsInScheduler", strings.Join(sets.List(reservedPodNNs), ","),
		)
	}

	nextOverrideHappensIn, err := thr.Spec.NextOverrideHappensIn(now)
	if err != nil {
		return err
	}
	if nextOverrideHappensIn != nil {
		klog.V(3).InfoS("Reconciling after duration", "ClusterThrottle", thr.Namespace+"/"+thr.Name, "After", nextOverrideHappensIn)
		c.enqueueAfter(thr, *nextOverrideHappensIn)
	}

	return nil
}

func (c *ClusterThrottleController) isResponsibleFor(thr *schedulev1alpha1.ClusterThrottle) bool {
	return c.throttlerName == thr.Spec.ThrottlerName
}

func (c *ClusterThrottleController) shouldCountIn(pod *corev1.Pod) bool {
	return pod.Spec.SchedulerName == c.targetSchedulerName && isScheduled(pod)
}

func (c *ClusterThrottleController) affectedPods(thr *schedulev1alpha1.ClusterThrottle) ([]*v1.Pod, []*v1.Pod, error) {
	pods := []*corev1.Pod{}
	nsMap := map[string]*corev1.Namespace{}
	nss, err := c.namespaceInformer.Lister().List(labels.Everything())
	if err != nil {
		return nil, nil, err
	}

	for _, ns := range nss {
		match, err := thr.Spec.Selector.MatchesToNamespace(ns)
		if err != nil {
			return nil, nil, err
		}
		if !match {
			continue
		}

		nsMap[ns.Name] = ns
		podsInNs, err := c.podInformer.Lister().Pods(ns.Name).List(labels.Everything())
		if err != nil {
			return nil, nil, err
		}
		pods = append(pods, podsInNs...)
	}

	nonterminatedPods := []*v1.Pod{}
	terminatedPods := []*v1.Pod{}
	for _, pod := range pods {
		if !(c.shouldCountIn(pod)) {
			continue
		}

		match, err := thr.Spec.Selector.MatchesToPod(pod, nsMap[pod.Namespace])
		if err != nil {
			return nil, nil, err
		}
		if !match {
			continue
		}
		if isNotFinished(pod) {
			nonterminatedPods = append(nonterminatedPods, pod)
		} else {
			terminatedPods = append(terminatedPods, pod)
		}
	}
	return nonterminatedPods, terminatedPods, nil
}

func (c *ClusterThrottleController) affectedClusterThrottles(pod *v1.Pod) ([]*schedulev1alpha1.ClusterThrottle, error) {
	ns, err := c.namespaceInformer.Lister().Get(pod.Namespace)
	if err != nil {
		return nil, err
	}

	throttles, err := c.clusterthrottleInformer.Lister().List(labels.Everything())
	if err != nil {
		return nil, err
	}

	affectedClusterThrottles := []*schedulev1alpha1.ClusterThrottle{}
	for _, throttle := range throttles {
		if !c.isResponsibleFor(throttle) {
			continue
		}
		match, err := throttle.Spec.Selector.MatchesToPod(pod, ns)
		if err != nil {
			return nil, err
		}
		if match {
			affectedClusterThrottles = append(affectedClusterThrottles, throttle)
		}
	}

	return affectedClusterThrottles, nil
}

func (c *ClusterThrottleController) Reserve(pod *v1.Pod) error {
	throttles, err := c.affectedClusterThrottles(pod)
	if err != nil {
		return err
	}
	reservedThrNNs := []string{}
	for _, thr := range throttles {
		reserved := c.ReserveOnClusterThrottle(pod, thr)
		if reserved {
			reservedThrNNs = append(reservedThrNNs, thr.Namespace+"/"+thr.Name)
		}
	}
	if len(reservedThrNNs) > 0 {
		klog.V(2).InfoS(
			"Pod is reserved for affected clusterthrottles",
			"Pod", pod.Namespace+"/"+pod.Name,
			"#ClusterThrottles", len(reservedThrNNs),
			"ClusterThrottles", strings.Join(reservedThrNNs, ","),
		)
	}
	return nil
}

func (c *ClusterThrottleController) ReserveOnClusterThrottle(pod *v1.Pod, thr *schedulev1alpha1.ClusterThrottle) bool {
	nn := types.NamespacedName{Namespace: thr.Namespace, Name: thr.Name}
	added := c.cache.addPod(nn, pod)
	reservedAmt, reservedPodNNs := c.cache.reservedResourceAmount(nn)
	if added {
		klog.V(3).InfoS(
			"Pod is reserved for affected clusterthrottle",
			"Pod", pod.Namespace+"/"+pod.Name,
			"ClusterThrottle", thr.Name,
			"CurrentReservedAmount", reservedAmt,
			"CurrentReservedPods", strings.Join(sets.List(reservedPodNNs), ","),
		)
	}
	return added
}

func (c *ClusterThrottleController) UnReserve(pod *v1.Pod) error {
	throttles, err := c.affectedClusterThrottles(pod)
	if err != nil {
		return err
	}
	unReservedThrNNs := []string{}
	for _, thr := range throttles {
		unreserved := c.UnReserveOnClusterThrottle(pod, thr)
		if unreserved {
			unReservedThrNNs = append(unReservedThrNNs, thr.Namespace+"/"+thr.Name)
		}
	}
	if len(throttles) > 0 {
		klog.V(2).InfoS(
			"Pod is un-reserved for affected clusterthrottles",
			"Pod", pod.Namespace+"/"+pod.Name,
			"#ClusterThrottles", len(unReservedThrNNs),
			"ClusterThrottles", strings.Join(unReservedThrNNs, ","),
		)
	}
	return nil
}

func (c *ClusterThrottleController) UnReserveOnClusterThrottle(pod *v1.Pod, thr *schedulev1alpha1.ClusterThrottle) bool {
	nn := types.NamespacedName{Namespace: thr.Namespace, Name: thr.Name}
	removed := c.cache.removePod(nn, pod)
	reservedAmt, reservedPodNNs := c.cache.reservedResourceAmount(nn)
	if removed {
		klog.V(3).InfoS(
			"Pod is un-reserved for affected clusterthrottle",
			"Pod", pod.Namespace+"/"+pod.Name,
			"ClusterThrottle", thr.Name,
			"CurrentReservedAmount", reservedAmt,
			"CurrentReservedPods", strings.Join(sets.List(reservedPodNNs), ","),
		)
	}
	return removed
}

func (c *ClusterThrottleController) CheckThrottled(
	pod *v1.Pod,
	isThrottledOnEqual bool,
) (
	[]schedulev1alpha1.ClusterThrottle,
	[]schedulev1alpha1.ClusterThrottle,
	[]schedulev1alpha1.ClusterThrottle,
	[]schedulev1alpha1.ClusterThrottle,
	error,
) {
	throttles, err := c.affectedClusterThrottles(pod)
	if err != nil {
		return nil, nil, nil, nil, err
	}
	affected := []schedulev1alpha1.ClusterThrottle{}
	alreadyThrottled := []schedulev1alpha1.ClusterThrottle{}
	insufficient := []schedulev1alpha1.ClusterThrottle{}
	podRequestsExceedsThreshold := []schedulev1alpha1.ClusterThrottle{}
	for _, thr := range throttles {
		affected = append(affected, *thr)
		reservedAmt, reservedPodNNs := c.cache.reservedResourceAmount(types.NamespacedName{Namespace: thr.Namespace, Name: thr.Name})
		checkStatus := thr.CheckThrottledFor(
			pod,
			reservedAmt,
			isThrottledOnEqual,
		)
		klog.V(3).InfoS("CheckThrottled result",
			"ClusterThrottle", thr.Name,
			"Pod", pod.Namespace+"/"+pod.Name,
			"Result", checkStatus,
			"Threashold", thr.Status.CalculatedThreshold.Threshold,
			"RequestedByPod", schedulev1alpha1.ResourceAmountOfPod(pod),
			"UsedInClusterThrottle", thr.Status.Used,
			"ReservedAmountInScheduler", reservedAmt,
			"ReservedPodsInScheduler", strings.Join(sets.List(reservedPodNNs), ","),
			"AmountForCheck", schedulev1alpha1.ResourceAmount{}.Add(thr.Status.Used).Add(schedulev1alpha1.ResourceAmountOfPod(pod)).Add(reservedAmt),
		)
		switch checkStatus {
		case schedulev1alpha1.CheckThrottleStatusActive:
			alreadyThrottled = append(alreadyThrottled, *thr)
		case schedulev1alpha1.CheckThrottleStatusInsufficient:
			insufficient = append(insufficient, *thr)
		case schedulev1alpha1.CheckThrottleStatusPodRequestsExceedsThreshold:
			podRequestsExceedsThreshold = append(podRequestsExceedsThreshold, *thr)
		}
	}
	return alreadyThrottled, insufficient, podRequestsExceedsThreshold, affected, nil
}

// mustSetupEventHandler sets up event handlers. If something wrong happens, it will panic.
func (c *ClusterThrottleController) mustSetupEventHandler() {
	_, err := c.namespaceInformer.Informer().AddEventHandler(cache.ResourceEventHandlerFuncs{})
	if err != nil {
		panic(fmt.Errorf("failed to add event handler in namespace informer: %w", err))
	}
	_, err = c.clusterthrottleInformer.Informer().AddEventHandler(cache.ResourceEventHandlerFuncs{
		AddFunc: func(obj interface{}) {
			thr := obj.(*v1alpha1.ClusterThrottle)
			if !c.isResponsibleFor(thr) {
				return
			}

			klog.V(4).InfoS("Add event", "ClusterThrottle", thr.Name)
			c.enqueue(thr)
		},
		UpdateFunc: func(oldObj, newObj interface{}) {
			thr := newObj.(*v1alpha1.ClusterThrottle)
			if !c.isResponsibleFor(thr) {
				return
			}
			klog.V(4).InfoS("Update event", "ClusterThrottle", thr.Name)
			c.enqueue(thr)
		},
		DeleteFunc: func(obj interface{}) {
			thr := obj.(*v1alpha1.ClusterThrottle)
			if !c.isResponsibleFor(thr) {
				return
			}
			klog.V(4).InfoS("Delete event", "ClusterThrottle", thr.Name)
			c.enqueue(thr)
		},
	})
	if err != nil {
		panic(fmt.Errorf("failed to add event handler in cluster throttle informer: %w", err))
	}

	_, err = c.podInformer.Informer().AddEventHandler(cache.ResourceEventHandlerFuncs{
		AddFunc: func(obj interface{}) {
			pod := obj.(*corev1.Pod)
			if !c.shouldCountIn(pod) {
				return
			}
			klog.V(4).InfoS("Add event", "Pod", pod.Namespace+"/"+pod.Name)

			throttles, err := c.affectedClusterThrottles(pod)
			if err != nil {
				utilruntime.HandleError(errors.Wrapf(err, "Failed to get affected clusterthrottles for pod '%s'", pod.Namespace+"/"+pod.Name))
				return
			}

			klog.V(4).InfoS("Reconciling ClusterThrottles", "Pod", pod.Namespace+"/"+pod.Name, "#ClusterThrottles", len(throttles))
			for _, thr := range throttles {
				c.enqueue(thr)
			}
		},
		UpdateFunc: func(oldObj, newObj interface{}) {
			oldPod := oldObj.(*corev1.Pod)
			newPod := newObj.(*corev1.Pod)
			if !c.shouldCountIn(oldPod) && !c.shouldCountIn(newPod) {
				return
			}
			klog.V(4).InfoS("Update event", "Pod", newPod.Namespace+"/"+newPod.Name)

			throttlesForOld, err := c.affectedClusterThrottles(oldPod)
			if err != nil {
				utilruntime.HandleError(errors.Wrapf(err, "fail to get affected clusterthrottles for pod '%s'", oldPod.Namespace+"/"+oldPod.Name))
				return
			}
			throttlesForNew, err := c.affectedClusterThrottles(newPod)
			if err != nil {
				utilruntime.HandleError(errors.Wrapf(err, "fail to get affected clusterthrottles for pod '%s'", newPod.Namespace+"/"+newPod.Name))
				return
			}

			// calc symmetric difference
			// to handle throttle assignment change
			throttleNNs := map[types.NamespacedName]struct{}{}
			throttleNNsForOld := map[types.NamespacedName]struct{}{}
			throttleNNsForNew := map[types.NamespacedName]struct{}{}
			throttleNNsCommon := map[types.NamespacedName]struct{}{}
			for _, thr := range throttlesForOld {
				nn := types.NamespacedName{Namespace: thr.Namespace, Name: thr.Name}
				throttleNNs[nn] = struct{}{}
				throttleNNsForOld[nn] = struct{}{}
			}
			for _, thr := range throttlesForNew {
				nn := types.NamespacedName{Namespace: thr.Namespace, Name: thr.Name}
				throttleNNs[nn] = struct{}{}
				throttleNNsForNew[nn] = struct{}{}
			}
			for nn := range throttleNNs {
				_, inNew := throttleNNsForNew[nn]
				_, inOld := throttleNNsForOld[nn]
				if inOld && inNew {
					throttleNNsCommon[nn] = struct{}{}
				}
			}
			for nn := range throttleNNsCommon {
				delete(throttleNNsForOld, nn)
			}
			for nn := range throttleNNsCommon {
				delete(throttleNNsForNew, nn)
			}
			// handle throttle assignment chnage
			if len(throttleNNsForOld) > 0 || len(throttleNNsForNew) > 0 {
				c.cache.moveThrottleAssignmentForPods(newPod, throttleNNsForOld, throttleNNsForNew)
			}

			// reconcile
			for nn := range throttleNNs {
				klog.V(4).InfoS("Enqueue clusterthrottle for pod update", "Throttle", nn.String(), "Pod", newPod.Namespace+"/"+newPod.Name)
				c.enqueue(cache.ExplicitKey(nn.String()))
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

			throttles, err := c.affectedClusterThrottles(pod)
			if err != nil {
				utilruntime.HandleError(errors.Wrapf(err, "Failed to get affected clusterthrottles for pod '%s'", pod.Namespace+"/"+pod.Name))
				return
			}

			klog.V(4).InfoS("Reconciling ClusterThrottles", "Pod", pod.Namespace+"/"+pod.Name, "#ClusterThrottles", len(throttles))
			for _, thr := range throttles {
				c.enqueue(thr)
			}
		},
	})
	if err != nil {
		panic(fmt.Errorf("failed to add event handler in pod informer: %w", err))
	}
}
