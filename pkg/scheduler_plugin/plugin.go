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

package scheduler_plugin

import (
	"context"
	"fmt"
	"strings"
	"time"

	schedulev1alpha1 "github.com/everpeace/kube-throttler/pkg/apis/schedule/v1alpha1"
	"github.com/everpeace/kube-throttler/pkg/controllers"
	scheduleclient "github.com/everpeace/kube-throttler/pkg/generated/clientset/versioned"
	scheduleinformers "github.com/everpeace/kube-throttler/pkg/generated/informers/externalversions"
	"github.com/pkg/errors"
	v1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/apimachinery/pkg/util/clock"
	utilruntime "k8s.io/apimachinery/pkg/util/runtime"
	"k8s.io/client-go/informers"
	"k8s.io/client-go/kubernetes"
	"k8s.io/client-go/tools/clientcmd"
	"k8s.io/klog/v2"
	"k8s.io/kubernetes/pkg/scheduler/framework"
)

const (
	// PluginName of the plugin used in the plugin registry and configurations.
	PluginName = "kube-throttler"
)

type KubeThrottler struct {
	fh                 framework.Handle
	throttleCtr        *controllers.ThrottleController
	clusterThrottleCtr *controllers.ClusterThrottleController
}

var _ framework.PreFilterPlugin = &KubeThrottler{}
var _ framework.EnqueueExtensions = &KubeThrottler{}
var _ framework.ReservePlugin = &KubeThrottler{}

func (p *KubeThrottler) Name() string {
	return PluginName
}

// NewPlugin initializes a new plugin and returns it.
func NewPlugin(configuration runtime.Object, fh framework.Handle) (framework.Plugin, error) {
	ctx := context.TODO()

	kubeThrottlerArgs, err := DecodePluginArgs(configuration)
	if err != nil {
		return nil, err
	}

	restConfig, err := clientcmd.BuildConfigFromFlags("", kubeThrottlerArgs.KubeConifg)
	if err != nil {
		return nil, err
	}

	scheduleClientset := scheduleclient.NewForConfigOrDie(restConfig)
	scheduleInformerFactory := scheduleinformers.NewSharedInformerFactory(scheduleClientset, 5*time.Minute)
	throttleInformer := scheduleInformerFactory.Schedule().V1alpha1().Throttles()
	clusterthrottleInformer := scheduleInformerFactory.Schedule().V1alpha1().ClusterThrottles()

	// we don't use informerFactory in frameworkHandle
	// because podInformer in the frameworkHandle does not have indexer
	// that is too inefficient to list pod in some namespace.
	// see: https://github.com/kubernetes/kubernetes/blob/v1.20.5/pkg/scheduler/scheduler.go#L671
	clientset := kubernetes.NewForConfigOrDie(restConfig)
	informerFactory := informers.NewSharedInformerFactory(clientset, 5*time.Minute)
	podInformer := informerFactory.Core().V1().Pods()
	namespaceInformer := informerFactory.Core().V1().Namespaces()

	throttleController := controllers.NewThrottleController(
		kubeThrottlerArgs.Name,
		kubeThrottlerArgs.TargetSchedulerName,
		kubeThrottlerArgs.ReconcileTemporaryThresholdInterval,
		*scheduleClientset,
		throttleInformer,
		podInformer,
		clock.RealClock{},
		kubeThrottlerArgs.ControllerThrediness,
		kubeThrottlerArgs.NumKeyMutex,
	)
	clusterthrottleController := controllers.NewClusterThrottleController(
		kubeThrottlerArgs.Name,
		kubeThrottlerArgs.TargetSchedulerName,
		kubeThrottlerArgs.ReconcileTemporaryThresholdInterval,
		*scheduleClientset,
		clusterthrottleInformer,
		podInformer,
		namespaceInformer,
		clock.RealClock{},
		kubeThrottlerArgs.ControllerThrediness,
		kubeThrottlerArgs.NumKeyMutex,
	)

	scheduleInformerFactory.Start(ctx.Done())
	scheduleInformerFactory.WaitForCacheSync(ctx.Done())
	syncResults := scheduleInformerFactory.WaitForCacheSync(ctx.Done())
	for informer, ok := range syncResults {
		if !ok {
			return nil, errors.Errorf("failed to wait for caches to sync: informer=%v", informer)
		}
		klog.InfoS("Informer cache synched", "Informer", fmt.Sprintf("%v", informer))
	}

	informerFactory.Start(ctx.Done())
	syncResults = informerFactory.WaitForCacheSync(ctx.Done())
	for informer, ok := range syncResults {
		if !ok {
			return nil, errors.Errorf("failed to wait for caches to sync: informer=%v", informer)
		}
		klog.InfoS("Informer cache synched", "Informer", fmt.Sprintf("%v", informer))
	}

	if err := throttleController.Start(context.Background().Done()); err != nil {
		return nil, err
	}
	if err := clusterthrottleController.Start(context.Background().Done()); err != nil {
		return nil, err
	}

	pl := KubeThrottler{
		fh:                 fh,
		throttleCtr:        throttleController,
		clusterThrottleCtr: clusterthrottleController,
	}

	return &pl, nil
}

func (pl *KubeThrottler) PreFilter(
	ctx context.Context,
	state *framework.CycleState,
	pod *v1.Pod,
) *framework.Status {
	thrActive, thrInsufficient, thrPodRequestsExceeds, thrAffected, err := pl.throttleCtr.CheckThrottled(pod, false)
	if err != nil {
		return framework.NewStatus(framework.Error, err.Error())
	}
	klog.V(2).InfoS("PreFilter: throttle check result",
		"Pod", pod.Namespace+"/"+pod.Name,
		"#ActiveThrottles", len(thrActive),
		"#InsufficientThrottles", len(thrInsufficient),
		"#PodRequestsExceedsThresholdThrottles", len(thrPodRequestsExceeds),
		"#AffectedThrottles", len(thrAffected),
	)

	clthrActive, clthrInsufficient, clthrPodRequestsExceeds, clThrAffected, err := pl.clusterThrottleCtr.CheckThrottled(pod, false)
	if err != nil {
		return framework.NewStatus(framework.Error, err.Error())
	}
	klog.V(2).InfoS("PreFilter: clusterthrottle check result",
		"Pod", pod.Namespace+"/"+pod.Name,
		"#ActiveClusterThrottles", len(clthrActive),
		"#InsufficientClusterThrottles", len(clthrInsufficient),
		"#PodRequestsExceedsThresholdClusterThrottles", len(clthrPodRequestsExceeds),
		"#AffectedClusterThrottles", len(clThrAffected),
	)

	if len(thrActive)+len(thrInsufficient)+len(thrPodRequestsExceeds)+
		len(clthrActive)+len(clthrInsufficient)+len(clthrPodRequestsExceeds) == 0 {
		return framework.NewStatus(framework.Success)
	}

	reasons := []string{}
	if len(clthrPodRequestsExceeds) > 0 {
		reasons = append(reasons, fmt.Sprintf("clusterthrottle[%s]=%s", schedulev1alpha1.CheckThrottleStatusPodRequestsExceedsThreshold, strings.Join(clusterThrottleNames(clthrPodRequestsExceeds), ",")))
	}
	if len(thrPodRequestsExceeds) > 0 {
		reasons = append(reasons, fmt.Sprintf("throttle[%s]=%s", schedulev1alpha1.CheckThrottleStatusPodRequestsExceedsThreshold, strings.Join(throttleNames(thrPodRequestsExceeds), ",")))
	}
	if len(clthrPodRequestsExceeds)+len(thrPodRequestsExceeds) > 0 {
		pl.fh.EventRecorder().Eventf(
			pod, nil,
			v1.EventTypeWarning,
			"ResourceRequestsExceedsThrottleThreshold",
			pl.Name(),
			"It won't be scheduled unless decreasing resource requests or increasing ClusterThrottle/Throttle threshold because its resource requests exceeds their thresholds: %s",
			strings.Join(
				append(clusterThrottleNames(clthrPodRequestsExceeds), throttleNames(thrPodRequestsExceeds)...),
				",",
			),
		)
	}
	if len(clthrActive) > 0 {
		reasons = append(reasons, fmt.Sprintf("clusterthrottle[%s]=%s", schedulev1alpha1.CheckThrottleStatusActive, strings.Join(clusterThrottleNames(clthrActive), ",")))
	}
	if len(thrActive) > 0 {
		reasons = append(reasons, fmt.Sprintf("throttle[%s]=%s", schedulev1alpha1.CheckThrottleStatusActive, strings.Join(throttleNames(thrActive), ",")))
	}
	if len(clthrInsufficient) != 0 {
		reasons = append(reasons, fmt.Sprintf("clusterthrottle[%s]=%s", schedulev1alpha1.CheckThrottleStatusInsufficient, strings.Join(clusterThrottleNames(clthrInsufficient), ",")))
	}
	if len(thrInsufficient) != 0 {
		reasons = append(reasons, fmt.Sprintf("throttle[%s]=%s", schedulev1alpha1.CheckThrottleStatusInsufficient, strings.Join(throttleNames(thrInsufficient), ",")))
	}
	return framework.NewStatus(framework.UnschedulableAndUnresolvable, reasons...)
}

func (pl *KubeThrottler) Reserve(
	ctx context.Context,
	state *framework.CycleState,
	pod *v1.Pod,
	node string,
) *framework.Status {
	errs := []string{}
	err := pl.throttleCtr.Reserve(pod)
	if err != nil {
		errs = append(errs, errors.Wrapf(err, "Failed to reserve pod=%s/%s in ThrottleController", pod.Namespace, pod.Name).Error())
	}
	err = pl.clusterThrottleCtr.Reserve(pod)
	if err != nil {
		errs = append(errs, errors.Wrapf(err, "Failed to reserve pod=%s/%s in ClusterThrottleController", pod.Namespace, pod.Name).Error())
	}

	if len(errs) != 0 {
		return framework.NewStatus(framework.Error, errs...)
	}
	klog.V(2).InfoS("Reserve: pod is reserved", "pod", pod.Namespace+"/"+pod.Name)
	return framework.NewStatus(framework.Success)
}

func (pl *KubeThrottler) Unreserve(
	ctx context.Context,
	state *framework.CycleState,
	pod *v1.Pod,
	node string,
) {
	// FIXME: How to handle error if it happened??
	err := pl.throttleCtr.UnReserve(pod)
	if err != nil {
		utilruntime.HandleError(errors.Wrapf(err, "Failed to unreserve pod %s/%s in ThrottleController", pod.Namespace, pod.Name))
	}
	pod.GetName()
	err = pl.clusterThrottleCtr.UnReserve(pod)
	if err != nil {
		utilruntime.HandleError(errors.Wrapf(err, "Failed to unreserve pod %s/%s in ClusterThrottleController", pod.Namespace, pod.Name))
	}

	klog.V(2).InfoS("Unreserve: pod is unreserved", "pod", pod.Namespace+"/"+pod.Name)
}

func (p *KubeThrottler) PreFilterExtensions() framework.PreFilterExtensions {
	return nil
}

func (p *KubeThrottler) EventsToRegister() []framework.ClusterEvent {
	throttlesGVK := framework.GVK(fmt.Sprintf("throttles.%v.%v", schedulev1alpha1.SchemeGroupVersion.Version, schedulev1alpha1.SchemeGroupVersion.Group))
	clusterthrottlesGVK := framework.GVK(fmt.Sprintf("clusterthrottles.%v.%v", schedulev1alpha1.SchemeGroupVersion.Version, schedulev1alpha1.SchemeGroupVersion.Group))
	return []framework.ClusterEvent{{
		Resource:   framework.Node,
		ActionType: framework.All,
	}, {
		Resource:   framework.Pod,
		ActionType: framework.All,
	}, {
		Resource:   throttlesGVK,
		ActionType: framework.All,
	}, {
		Resource:   clusterthrottlesGVK,
		ActionType: framework.All,
	}}
}

func throttleNames(objs []schedulev1alpha1.Throttle) []string {
	names := make([]string, len(objs))
	for i, obj := range objs {
		names[i] = types.NamespacedName{Namespace: obj.GetNamespace(), Name: obj.GetName()}.String()
	}
	return names
}

func clusterThrottleNames(objs []schedulev1alpha1.ClusterThrottle) []string {
	names := make([]string, len(objs))
	for i, obj := range objs {
		names[i] = types.NamespacedName{Namespace: obj.GetNamespace(), Name: obj.GetName()}.String()
	}
	return names
}
