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
	"time"

	"github.com/pkg/errors"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

type ThrottleSpecBase struct {
	// +kubebuilder:validation:Required
	ThrottlerName string `json:"throttlerName,omitempty"`
	// +kubebuilder:validation:Required
	Threshold ResourceAmount `json:"threshold,omitempty"`

	TemporaryThresholdOverrides []TemporaryThresholdOverride `json:"temporaryThresholdOverrides,omitempty"`
}

func (b ThrottleSpecBase) NextOverrideHappensIn(now time.Time) (*time.Duration, error) {
	var nextHappenAfter *time.Duration
	updateIfNeeded := func(d time.Duration) {
		if nextHappenAfter == nil || *nextHappenAfter > d {
			nextHappenAfter = &d
		}
	}
	for _, o := range b.TemporaryThresholdOverrides {
		beginTime, err := o.BeginTime()
		if err != nil {
			continue
		}
		if beginTime.After(now) {
			updateIfNeeded(beginTime.Sub(now))
		}

		endTime, err := o.EndTime()
		if err != nil {
			continue
		}
		if endTime.After(now) {
			updateIfNeeded(endTime.Sub(now))
		}
	}

	return nextHappenAfter, nil
}

func (b ThrottleSpecBase) CalculateThreshold(now time.Time) CalculatedThreshold {
	calculated := CalculatedThreshold{
		CalculatedAt: metav1.Time{Time: now},
		Threshold:    b.Threshold,
	}

	activeFound := false
	overrideResult := ResourceAmount{
		ResourceRequests: corev1.ResourceList{},
	}
	errMessages := []string{}
	for i, o := range b.TemporaryThresholdOverrides {
		isActive, err := o.IsActive(now)
		if err != nil {
			errMessages = append(errMessages, errors.Wrapf(err, "index %d", i).Error())
			continue
		}
		if isActive {
			activeFound = true
			// merging active overrides
			// the first override will take effect for each resource count/request
			if overrideResult.ResourceCounts == nil && o.Threshold.ResourceCounts != nil {
				overrideResult.ResourceCounts = o.Threshold.ResourceCounts.DeepCopy()
			}
			for rn, rq := range o.Threshold.ResourceRequests {
				if _, ok := overrideResult.ResourceRequests[rn]; !ok {
					overrideResult.ResourceRequests[rn] = rq
				}
			}
		}
	}
	if activeFound {
		calculated.Threshold = overrideResult
	}

	if len(errMessages) > 0 {
		calculated.Messages = errMessages
		return calculated
	}

	return calculated
}

type ThrottleSpec struct {
	ThrottleSpecBase `json:",inline"`
	Selector         ThrottleSelector `json:"selector,omitempty"`
}

type ThrottleStatus struct {
	CalculatedThreshold CalculatedThreshold       `json:"calculatedThreshold,omitempty"`
	Throttled           IsResourceAmountThrottled `json:"throttled,omitempty"`
	Used                ResourceAmount            `json:"used,omitempty"`
}

type CheckThrottleStatus string

var (
	CheckThrottleStatusNotThrottled                CheckThrottleStatus = "not-throttled"
	CheckThrottleStatusActive                      CheckThrottleStatus = "active"
	CheckThrottleStatusInsufficient                CheckThrottleStatus = "insufficient"
	CheckThrottleStatusPodRequestsExceedsThreshold CheckThrottleStatus = "pod-requests-exceeds-threshold"
)

func (thr Throttle) CheckThrottledFor(pod *corev1.Pod, reservedResourceAmount ResourceAmount, isThrottledOnEqual bool) CheckThrottleStatus {
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
	if threshold.IsThrottled(alreadyUsed, true).IsThrottledFor(pod) {
		return CheckThrottleStatusActive
	}

	used := ResourceAmount{}.Add(thr.Status.Used).Add(ResourceAmountOfPod(pod)).Add(reservedResourceAmount)
	if threshold.IsThrottled(used, isThrottledOnEqual).IsThrottledFor(pod) {
		return CheckThrottleStatusInsufficient
	}

	return CheckThrottleStatusNotThrottled
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
