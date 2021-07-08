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

package v1alpha1

import (
	rl "github.com/everpeace/kube-throttler/pkg/resourcelist"
	corev1 "k8s.io/api/core/v1"
)

type ResourceAmount struct {
	ResourceCounts   *ResourceCounts     `json:"resourceCounts,omitempty"`
	ResourceRequests corev1.ResourceList `json:"resourceRequests,omitempty"`
}

type ResourceCounts struct {
	Pod int `json:"pod,omitempty"`
}

type IsResourceAmountThrottled struct {
	ResourceCounts   IsResourceCountThrottled     `json:"resourceCounts,omitempty"`
	ResourceRequests map[corev1.ResourceName]bool `json:"resourceRequests,omitempty"`
}

func (t IsResourceAmountThrottled) IsThrottledFor(pod *corev1.Pod) bool {
	if t.ResourceCounts.Pod {
		return true
	}
	podResourceAmount := ResourceAmountOfPod(pod)
	for rn := range podResourceAmount.ResourceRequests {
		throttled, ok := t.ResourceRequests[rn]
		if !ok {
			continue
		}
		if throttled {
			return true
		}
	}
	return false
}

type IsResourceCountThrottled struct {
	Pod bool `json:"pod,omitempty"`
}

func ResourceAmountOfPod(pod *corev1.Pod) ResourceAmount {
	return ResourceAmount{
		ResourceCounts:   &ResourceCounts{Pod: 1},
		ResourceRequests: corev1.ResourceList(rl.PodRequestResourceList(pod)),
	}
}

func (a ResourceCounts) Add(b ResourceCounts) ResourceCounts {
	a.Pod += b.Pod
	return a
}

func (a ResourceCounts) Sub(b ResourceCounts) ResourceCounts {
	a.Pod -= b.Pod
	if a.Pod < 0 {
		a.Pod = 0
	}
	return a
}

func (a ResourceAmount) Add(b ResourceAmount) ResourceAmount {
	if a.ResourceCounts == nil {
		if b.ResourceCounts != nil {
			a.ResourceCounts = b.ResourceCounts.DeepCopy()
		}
	} else {
		if b.ResourceCounts != nil {
			a.ResourceCounts.Add(*b.ResourceCounts)
		}
	}
	rl.ResourceList(a.ResourceRequests).Add(rl.ResourceList(b.ResourceRequests))
	return a
}

func (a ResourceAmount) Sub(b ResourceAmount) ResourceAmount {
	if a.ResourceCounts != nil && b.ResourceCounts != nil {
		a.ResourceCounts.Sub(*b.ResourceCounts)
	}
	rl.ResourceList(a.ResourceRequests).Sub(rl.ResourceList(b.ResourceRequests))
	return a
}

func (threshold ResourceAmount) IsThrottled(used ResourceAmount) IsResourceAmountThrottled {
	result := IsResourceAmountThrottled{}
	if threshold.ResourceCounts != nil && used.ResourceCounts != nil {
		result.ResourceCounts.Pod = threshold.ResourceCounts.Pod <= used.ResourceCounts.Pod
	}

	for rn, qt := range threshold.ResourceRequests {
		if qu, ok := used.ResourceRequests[rn]; ok {
			result.ResourceRequests[rn] = qt.Cmp(qu) >= 0
		} else {
			result.ResourceRequests[rn] = false
		}
	}

	return result
}
