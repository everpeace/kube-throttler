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

import corev1 "k8s.io/api/core/v1"

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

type IsResourceCountThrottled struct {
	Pod bool `json:"pod,omitempty"`
}
