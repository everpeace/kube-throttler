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

	"github.com/google/go-cmp/cmp"
	. "github.com/onsi/ginkgo"
	. "github.com/onsi/gomega"
	v1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

var _ = Describe("ThrottleSpecBase.CalculateThreshold", func() {
	const NOW = "2006-01-02T15:04:05Z"
	var now time.Time
	var t ThrottleSpecBase
	var threshold ResourceAmount
	var override1, override2, erroredOverride TemporaryThresholdOverride
	equalToWithCmp := func(expected CalculatedThreshold) func(actual CalculatedThreshold) bool {
		return func(actual CalculatedThreshold) bool {
			return cmp.Equal(actual, expected)
		}
	}
	BeforeEach(func() {
		var err error
		now, err = time.Parse(time.RFC3339, NOW)
		Expect(err).NotTo(HaveOccurred())
		threshold = ResourceAmount{
			ResourceCounts: &ResourceCounts{
				Pod: 0,
			},
			ResourceRequests: map[v1.ResourceName]resource.Quantity{
				v1.ResourceCPU: resource.MustParse("1"),
			},
		}
		override1 = TemporaryThresholdOverride{
			Begin: now.Add(time.Minute * -1).Format(time.RFC3339),
			End:   now.Add(time.Minute * 1).Format(time.RFC3339),
			Threshold: ResourceAmount{
				ResourceCounts: &ResourceCounts{
					Pod: 2,
				},
				ResourceRequests: map[v1.ResourceName]resource.Quantity{
					v1.ResourceCPU: resource.MustParse("2"),
				},
			},
		}
		override2 = TemporaryThresholdOverride{
			Begin: now.Add(time.Minute * -1).Format(time.RFC3339),
			End:   now.Add(time.Minute * 1).Format(time.RFC3339),
			Threshold: ResourceAmount{
				ResourceCounts: &ResourceCounts{
					Pod: 3,
				},
				ResourceRequests: map[v1.ResourceName]resource.Quantity{
					v1.ResourceCPU:    resource.MustParse("3"),
					v1.ResourceMemory: resource.MustParse("3"),
				},
			},
		}
		erroredOverride = TemporaryThresholdOverride{Begin: "error", End: "error"}
	})
	When("No active Spec.TmporaryThresholdOverrides exist", func() {
		BeforeEach(func() {
			t = ThrottleSpecBase{
				ThrottlerName: "dummy",
				Threshold:     threshold,
			}
		})
		It("should return spec.threshold", func() {
			Expect(t.CalculateThreshold(now)).Should(Satisfy(equalToWithCmp(CalculatedThreshold{
				Threshold:    threshold,
				CalculatedAt: metav1.Time{Time: now},
			})))
		})
	})
	When("Single active Spec.TemporaryThresholdOverrides exists", func() {
		BeforeEach(func() {
			t = ThrottleSpecBase{
				ThrottlerName:               "dummy",
				Threshold:                   threshold,
				TemporaryThresholdOverrides: []TemporaryThresholdOverride{override1},
			}
		})
		It("should return the active one's threshold", func() {
			Expect(t.CalculateThreshold(now)).Should(Satisfy(equalToWithCmp(CalculatedThreshold{
				Threshold:    override1.Threshold,
				CalculatedAt: metav1.Time{Time: now},
			})))
		})
	})
	When("Multiple active Spec.TemporaryThresholdOverrides exists", func() {
		BeforeEach(func() {
			t = ThrottleSpecBase{
				ThrottlerName:               "dummy",
				Threshold:                   threshold,
				TemporaryThresholdOverrides: []TemporaryThresholdOverride{override1, override2},
			}
		})
		It("should return merged active ones' threshold", func() {
			Expect(t.CalculateThreshold(now)).Should(Satisfy(equalToWithCmp(CalculatedThreshold{
				// merged (first match wins for eace ResourceCounts/ResourceRequests)
				Threshold: ResourceAmount{
					ResourceCounts: &ResourceCounts{
						Pod: 2,
					},
					ResourceRequests: map[v1.ResourceName]resource.Quantity{
						v1.ResourceCPU:    resource.MustParse("2"),
						v1.ResourceMemory: resource.MustParse("3"),
					},
				},
				CalculatedAt: metav1.Time{Time: now},
			})))
		})
	})
	When("Including some error Spec.TemporaryThresholdOverrides exist", func() {
		BeforeEach(func() {
			t = ThrottleSpecBase{
				ThrottlerName:               "dummy",
				Threshold:                   threshold,
				TemporaryThresholdOverrides: []TemporaryThresholdOverride{override1, erroredOverride},
			}
		})
		It("should skip the errored override", func() {
			Expect(t.CalculateThreshold(now)).Should(Satisfy(equalToWithCmp(CalculatedThreshold{
				Threshold:    override1.Threshold,
				CalculatedAt: metav1.Time{Time: now},
				Messages: []string{
					`index 1: Failed to parse Begin: parsing time "error" as "2006-01-02T15:04:05Z07:00": cannot parse "error" as "2006"`,
				},
			})))
		})
	})
})
