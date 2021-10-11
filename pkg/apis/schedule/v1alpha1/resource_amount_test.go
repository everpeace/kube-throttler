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
	. "github.com/onsi/ginkgo"
	. "github.com/onsi/gomega"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/resource"
)

var resourceAmountSpec = Describe("ResourceAmount.IsThrottled", func() {
	var testee ResourceAmount
	When("Empty", func() {
		BeforeEach(func() {
			testee = ResourceAmount{}
		})
		It("should not be throttled at all for any used ResourceCounts", func() {
			for _, b := range []bool{false, true} {
				Expect(testee.IsThrottled(ResourceAmount{
					ResourceCounts: &ResourceCounts{
						Pod: 3,
					},
				}, b)).Should(Equal(
					IsResourceAmountThrottled{
						ResourceCounts: IsResourceCountThrottled{
							Pod: false,
						},
					},
				))
			}
		})
		It("should not be throttled at all for any used ResourceRequests", func() {
			for _, b := range []bool{false, true} {
				Expect(testee.IsThrottled(ResourceAmount{
					ResourceRequests: corev1.ResourceList{
						"r1": resource.MustParse("1000"),
					},
				}, b)).Should(Equal(
					IsResourceAmountThrottled{},
				))
			}
		})
	})
	When("Both ResourceCount and ResourceRequests is not nil", func() {
		resourceRequestsFalse := map[corev1.ResourceName]bool{
			"r1": false,
			"r2": false,
		}
		BeforeEach(func() {
			testee = ResourceAmount{
				ResourceCounts: &ResourceCounts{Pod: 3},
				ResourceRequests: corev1.ResourceList{
					"r1": resource.MustParse("10"),
					"r2": resource.MustParse("20"),
				},
			}
		})
		It("should be throttled for used resourcecounts to its thresold", func() {
			for _, b := range []bool{false, true} {
				Expect(testee.IsThrottled(ResourceAmount{
					ResourceCounts: &ResourceCounts{Pod: 2},
				}, b)).Should(Equal(
					IsResourceAmountThrottled{
						ResourceCounts:   IsResourceCountThrottled{Pod: false},
						ResourceRequests: resourceRequestsFalse,
					},
				))
			}

			Expect(testee.IsThrottled(ResourceAmount{
				ResourceCounts: &ResourceCounts{Pod: 3},
			}, false)).Should(Equal(
				IsResourceAmountThrottled{
					ResourceCounts:   IsResourceCountThrottled{Pod: false},
					ResourceRequests: resourceRequestsFalse,
				},
			))
			Expect(testee.IsThrottled(ResourceAmount{
				ResourceCounts: &ResourceCounts{Pod: 3},
			}, true)).Should(Equal(
				IsResourceAmountThrottled{
					ResourceCounts:   IsResourceCountThrottled{Pod: true},
					ResourceRequests: resourceRequestsFalse,
				},
			))

			for _, b := range []bool{false, true} {
				Expect(testee.IsThrottled(ResourceAmount{
					ResourceCounts: &ResourceCounts{Pod: 4},
				}, b)).Should(Equal(
					IsResourceAmountThrottled{
						ResourceCounts:   IsResourceCountThrottled{Pod: true},
						ResourceRequests: resourceRequestsFalse,
					},
				))
			}
		})
		It("should be throttled for used resourcerequests in threshold that are greater or equal to its threshold", func() {
			for _, b := range []bool{false, true} {
				Expect(testee.IsThrottled(ResourceAmount{
					ResourceRequests: corev1.ResourceList{
						"r1": resource.MustParse("1"),
						"r2": resource.MustParse("2"),
					},
				}, b)).Should(Equal(
					IsResourceAmountThrottled{
						ResourceRequests: resourceRequestsFalse,
					},
				))
			}

			Expect(testee.IsThrottled(ResourceAmount{
				ResourceRequests: corev1.ResourceList{
					"r1": resource.MustParse("10"),
					"r2": resource.MustParse("20"),
				},
			}, false)).Should(Equal(
				IsResourceAmountThrottled{
					ResourceRequests: map[corev1.ResourceName]bool{
						"r1": false,
						"r2": false,
					},
				},
			))
			Expect(testee.IsThrottled(ResourceAmount{
				ResourceRequests: corev1.ResourceList{
					"r1": resource.MustParse("10"),
					"r2": resource.MustParse("20"),
				},
			}, true)).Should(Equal(
				IsResourceAmountThrottled{
					ResourceRequests: map[corev1.ResourceName]bool{
						"r1": true,
						"r2": true,
					},
				},
			))
			for _, b := range []bool{false, true} {
				Expect(testee.IsThrottled(ResourceAmount{
					ResourceRequests: corev1.ResourceList{
						"r1": resource.MustParse("11"),
						"r2": resource.MustParse("22"),
					},
				}, b)).Should(Equal(
					IsResourceAmountThrottled{
						ResourceRequests: map[corev1.ResourceName]bool{
							"r1": true,
							"r2": true,
						},
					},
				))
			}
			Expect(testee.IsThrottled(ResourceAmount{
				ResourceRequests: corev1.ResourceList{
					"r1": resource.MustParse("1"),
					"r2": resource.MustParse("20"),
				},
			}, false)).Should(Equal(
				IsResourceAmountThrottled{
					ResourceRequests: map[corev1.ResourceName]bool{
						"r1": false,
						"r2": false,
					},
				},
			))
			Expect(testee.IsThrottled(ResourceAmount{
				ResourceRequests: corev1.ResourceList{
					"r1": resource.MustParse("1"),
					"r2": resource.MustParse("20"),
				},
			}, true)).Should(Equal(
				IsResourceAmountThrottled{
					ResourceRequests: map[corev1.ResourceName]bool{
						"r1": false,
						"r2": true,
					},
				},
			))
		})
		It("should not be throttled for used resourcerequests not in its threshold", func() {
			for _, b := range []bool{false, true} {
				Expect(testee.IsThrottled(ResourceAmount{
					ResourceRequests: corev1.ResourceList{
						"r3": resource.MustParse("3000"),
					},
				}, b)).Should(Equal(
					IsResourceAmountThrottled{
						ResourceRequests: resourceRequestsFalse,
					},
				))
			}
		})
	})
})

var isResourceAmountThrottledSpec = Describe("IsResourceAmountThrottled.IsThrottled", func() {
	var testee IsResourceAmountThrottled
	When("ResourceCounts is throttled", func() {
		BeforeEach(func() {
			testee = IsResourceAmountThrottled{
				ResourceCounts: IsResourceCountThrottled{Pod: true},
			}
		})
		It("should return true for any pod", func() {
			Expect(testee.IsThrottledFor(mkPod("test", "test").Pod)).Should(BeTrue())
		})
	})
	When("ResourceCount is not throttled, and ResourceRequests defines r1 and r2", func() {
		BeforeEach(func() {
			testee = IsResourceAmountThrottled{
				ResourceRequests: map[corev1.ResourceName]bool{
					"r1": false,
					"r2": true,
				},
			}
		})
		It("should be true pod requesting positive amount of throttled resource", func() {
			Expect(testee.IsThrottledFor(mkPod("test", "test").WithRequests(corev1.ResourceList{
				"r2": resource.MustParse("0"),
			}).Pod)).Should(BeFalse())
			Expect(testee.IsThrottledFor(mkPod("test", "test").WithRequests(corev1.ResourceList{
				"r2": resource.MustParse("1"),
			}).Pod)).Should(BeTrue())

			Expect(testee.IsThrottledFor(mkPod("test", "test").WithRequests(corev1.ResourceList{
				"r1": resource.MustParse("1000"),
			}).Pod)).Should(BeFalse())
			Expect(testee.IsThrottledFor(mkPod("test", "test").WithRequests(corev1.ResourceList{
				"r3": resource.MustParse("1000"),
			}).Pod)).Should(BeFalse())
		})

	})
})
