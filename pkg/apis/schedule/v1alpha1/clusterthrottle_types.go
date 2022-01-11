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
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

type ClusterThrottleSpec struct {
	ThrottleSpecBase `json:",inline"`
	Selector         ClusterThrottleSelector `json:"selector,omitempty"`
}

func (thr ClusterThrottle) CheckThrottledFor(pod *corev1.Pod, reservedResourceAmount ResourceAmount, isThrottledOnEqual bool) CheckThrottleStatus {
	threshold := thr.Spec.Threshold
	if !thr.Status.CalculatedThreshold.CalculatedAt.Time.IsZero() {
		threshold = thr.Status.CalculatedThreshold.Threshold
	}

	if threshold.IsThrottled(ResourceAmountOfPod(pod), false).IsThrottledFor(pod) {
		return CheckThrottleStatusPodRequestsExceedsThreshold
	}

	if thr.Status.Throttled.IsThrottledFor(pod) {
		return CheckThrottleStatusActive
	}

	alreadyUsed := ResourceAmount{}.Add(thr.Status.Used).Add(reservedResourceAmount)
	if threshold.IsThrottled(alreadyUsed, isThrottledOnEqual).IsThrottledFor(pod) {
		return CheckThrottleStatusActive
	}

	used := ResourceAmount{}.Add(thr.Status.Used).Add(ResourceAmountOfPod(pod)).Add(reservedResourceAmount)
	if threshold.IsThrottled(used, isThrottledOnEqual).IsThrottledFor(pod) {
		return CheckThrottleStatusInsufficient
	}

	return CheckThrottleStatusNotThrottled
}

// +genclient
// +genclient:nonNamespaced
// +k8s:deepcopy-gen:interfaces=k8s.io/apimachinery/pkg/runtime.Object
// +kubebuilder:resource:categories=kube-throttler,scope=Cluster,shortName=clthr;clthrs
// +kubebuilder:subresource:status
// +kubebuilder:printcolumn:name=throttled,JSONPath=.status.throttled,format=byte,type=string
// +kubebuilder:printcolumn:name=calculatedThreshold,JSONPath=.status.calculatedThreshold.threshold,format=byte,type=string,priority=1
// +kubebuilder:printcolumn:name=calculatedAt,JSONPath=.status.calculatedThreshold.calculatedAt,format=date,type=date,priority=1

type ClusterThrottle struct {
	metav1.TypeMeta   `json:",inline"`
	metav1.ObjectMeta `json:"metadata,omitempty"`

	Spec   ClusterThrottleSpec `json:"spec,omitempty"`
	Status ThrottleStatus      `json:"status,omitempty"`
}

// +k8s:deepcopy-gen:interfaces=k8s.io/apimachinery/pkg/runtime.Object

type ClusterThrottleList struct {
	metav1.TypeMeta `json:",inline"`
	metav1.ListMeta `json:"metadata,omitempty"`
	Items           []ClusterThrottle `json:"items"`
}
