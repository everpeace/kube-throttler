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
	"fmt"
	goruntime "runtime"
	"time"

	"k8s.io/apimachinery/pkg/runtime"
	fwkruntime "k8s.io/kubernetes/pkg/scheduler/framework/runtime"
)

var (
	DefaultReconcileTemporaryThresholdInterval = 15 * time.Second
)

type KubeThrottlerPluginArgs struct {
	Name                                string        `json:"name"`
	KubeConifg                          string        `json:"kubeconfig"`
	ReconcileTemporaryThresholdInterval time.Duration `json:"reconcileTemporaryThresholdInterval"`
	TargetSchedulerName                 string        `json:"targetSchedulerName"`
	ControllerThrediness                int           `json:"controllerThrediness"`
	NumKeyMutex                         int           `json:"numKeyMutex"`
}

func DecodePluginArgs(configuration runtime.Object) (*KubeThrottlerPluginArgs, error) {
	args := &KubeThrottlerPluginArgs{}
	if err := fwkruntime.DecodeInto(configuration, &args); err != nil {
		return nil, fmt.Errorf("Failed to decode into %s PluginConfig", PluginName)
	}
	if args.Name == "" {
		return nil, fmt.Errorf("Name must not be empty")
	}
	if args.TargetSchedulerName == "" {
		return nil, fmt.Errorf("TargetSchedulerName must not be empty")
	}
	if args.ReconcileTemporaryThresholdInterval == 0 {
		args.ReconcileTemporaryThresholdInterval = DefaultReconcileTemporaryThresholdInterval
	}
	if args.ControllerThrediness == 0 {
		args.ControllerThrediness = goruntime.NumCPU()
	}
	return args, nil
}
