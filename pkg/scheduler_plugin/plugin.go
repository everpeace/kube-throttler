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
	v1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/apimachinery/pkg/util/clock"
	"k8s.io/kubernetes/pkg/scheduler/framework"
)

const (
	// Name of the plugin used in the plugin registry and configurations.
	Name = "KubeThrottler"
)

type KubeThrottler struct {
	fh          framework.Handle
	throttleCtr *controllers.ThrottleController
}

var _ framework.PreFilterPlugin = &KubeThrottler{}
var _ framework.ReservePlugin = &KubeThrottler{}

func (p *KubeThrottler) Name() string {
	return Name
}

// New initializes a new plugin and returns it.
func New(_ runtime.Object, fh framework.Handle) (framework.Plugin, error) {
	scheduleClientset := scheduleclient.NewForConfigOrDie(nil)
	scheduleInformerFactory := scheduleinformers.NewSharedInformerFactory(scheduleClientset, 5*time.Minute)
	throttleInformer := scheduleInformerFactory.Schedule().V1alpha1().Throttles()
	podInformer := fh.SharedInformerFactory().Core().V1().Pods()

	throttleController := controllers.NewThrottleController(
		"", // TODO
		throttleInformer,
		podInformer,
		clock.RealClock{},
	)

	if err := throttleController.Start(4, context.Background().Done()); err != nil {
		return nil, err
	}

	pl := KubeThrottler{
		fh:          fh,
		throttleCtr: throttleController,
	}

	return &pl, nil
}

func (pl *KubeThrottler) PreFilter(
	ctx context.Context,
	state *framework.CycleState,
	pod *v1.Pod,
) *framework.Status {
	thrActive, thrInsufficient, err := pl.throttleCtr.CheckThrottled(pod)
	if err != nil {
		return framework.NewStatus(framework.Error, err.Error())
	}

	if len(thrActive) == 0 && len(thrInsufficient) == 0 {
		return framework.NewStatus(framework.Success)
	}

	reasons := []string{}
	if len(thrActive) != 0 {
		reasons = append(reasons, fmt.Sprintf("throttle[%s]=%s", schedulev1alpha1.CheckThrottleStatusActive, strings.Join(throttleNames(thrActive), ",")))
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
		errs = append(errs, err.Error())
	}

	if len(errs) != 0 {
		return framework.NewStatus(framework.Error, errs...)
	}
	return framework.NewStatus(framework.Success)
}

func (pl *KubeThrottler) Unreserve(
	ctx context.Context,
	state *framework.CycleState,
	pod *v1.Pod,
	node string,
) {
	// TODO: log error
	// How to handle error if it happened??
	_ = pl.throttleCtr.UnReserve(pod)
}

func (p *KubeThrottler) PreFilterExtensions() framework.PreFilterExtensions {
	return nil
}

func throttleNames(thrs []schedulev1alpha1.Throttle) []string {
	names := make([]string, len(thrs))
	for i, thr := range thrs {
		names[i] = types.NamespacedName{Namespace: thr.Namespace, Name: thr.Name}.String()
	}
	return names
}
