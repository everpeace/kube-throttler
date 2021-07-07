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

type ThrottleSelectorTerm struct {
	PodSelector []corev1.NodeSelectorRequirement `json:"podSelector"`
}

type ThrottleSpecBase struct {
	// +kubebuilder:validation:Required
	ThrottlerName string `json:"throttlerName,omitempty"`
	// +kubebuilder:validation:Required
	Threshold ResourceAmount `json:"threshold,omitempty"`

	TemporaryThresholdOverrides []TemporaryThresholdOverride `json:"temporaryThresholdOverrides,omitempty"`
}

type ThrottleSpec struct {
	ThrottleSpecBase `json:",inline"`
	// +kubebuilder:validation:Required
	Selector []ThrottleSelectorTerm `json:"selector,omitempty"`
}

type ThrottleStatus struct {
	CalculatedThreshold CalculatedThreshold       `json:"calculatedThreshold,omitempty"`
	Throttled           IsResourceAmountThrottled `json:"throttled,omitempty"`
	Used                ResourceAmount            `json:"used,omitempty"`
}

// +genclient
// +k8s:deepcopy-gen:interfaces=k8s.io/apimachinery/pkg/runtime.Object
// +kubebuilder:resource:categories=kube-throttler,shortName=thr;thrs
// +kubebuilder:subresource:status
// +kubebuilder:printcolumn:name=throttled,JSONPath=.status.throttled,format=byte,type=string
// +kubebuilder:printcolumn:name=calculatedThreshold,JSONPath=.status.calculatedThreshold.threshold,format=byte,type=string,priority=1
// +kubebuilder:printcolumn:name=calculatedAt,JSONPath=.status.calculatedThreshold.calculatedAt,format=date,type=date,priority=1

type Throttle struct {
	metav1.TypeMeta   `json:",inline"`
	metav1.ObjectMeta `json:"metadata,omitempty"`

	Spec   ThrottleSpec   `json:"spec,omitempty"`
	Status ThrottleStatus `json:"status,omitempty"`
}

// +k8s:deepcopy-gen:interfaces=k8s.io/apimachinery/pkg/runtime.Object

type ThrottleList struct {
	metav1.TypeMeta `json:",inline"`
	metav1.ListMeta `json:"metadata,omitempty"`
	Items           []Throttle `json:"items"`
}
