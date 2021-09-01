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

package resourcelist

import (
	"testing"

	. "github.com/onsi/ginkgo"
	. "github.com/onsi/gomega"

	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/resource"
)

func TestResourceList(t *testing.T) {
	RegisterFailHandler(Fail)
	RunSpecs(t, "ResourceList Suite")
}

func q(str string) resource.Quantity {
	return resource.MustParse(str)
}

var (
	zero      = q("0")
	one       = q("1")
	two       = q("2")
	minus_one = q("-1")
	minus_two = q("-2")
)

var _ = Describe("PodResourceRequestList", func() {
	Context("only with containers", func() {
		It("should sum up resource requests", func() {
			p := &corev1.Pod{
				Spec: corev1.PodSpec{
					Containers: []corev1.Container{{
						Resources: corev1.ResourceRequirements{
							Requests: corev1.ResourceList{
								"n1": one,
							},
						},
					}, {
						Resources: corev1.ResourceRequirements{
							Requests: corev1.ResourceList{
								"n1": one,
							},
						},
					}},
				},
			}

			r := PodRequestResourceList(p)

			Expect(r.EqualTo(ResourceList(corev1.ResourceList{
				"n1": two,
			}))).Should(Equal(true))
		})
	})

	Context("with init containers", func() {
		It("sets max(max(resources of initContainers), total resources of containers)", func() {
			p := &corev1.Pod{
				Spec: corev1.PodSpec{
					InitContainers: []corev1.Container{{
						Resources: corev1.ResourceRequirements{
							Requests: corev1.ResourceList{
								"n1": one,
							},
						},
					}, {
						Resources: corev1.ResourceRequirements{
							Requests: corev1.ResourceList{
								"n2": two,
							},
						},
					}},
					Containers: []corev1.Container{{
						Resources: corev1.ResourceRequirements{
							Requests: corev1.ResourceList{
								"n1": one,
							},
						},
					}, {
						Resources: corev1.ResourceRequirements{
							Requests: corev1.ResourceList{
								"n1": one,
							},
						},
					}},
				},
			}

			r := PodRequestResourceList(p)

			Expect(r.EqualTo(ResourceList(corev1.ResourceList{
				"n1": two,
				"n2": two,
			}))).Should(Equal(true))
		})
	})
})

var _ = Describe("ResourceList", func() {
	Describe("Add", func() {
		When("resource sets of lhs and rhs are not equal", func() {
			It("resultant resource sets are merged", func() {
				lhs := ResourceList(corev1.ResourceList{
					"n1": zero,
					"n2": one,
					"n3": one,
				})
				rhs := ResourceList(corev1.ResourceList{
					"n2": zero,
					"n3": one,
					"n4": two,
				})

				lhs.Add(rhs)

				Expect(lhs.EqualTo(ResourceList(corev1.ResourceList{
					"n1": zero,
					"n2": one,
					"n3": two,
					"n4": two,
				}))).Should(Equal(true))
			})
		})

		When("resource sets of lhs and rhs are equal", func() {
			It("just adds resource quantities", func() {
				lhs := ResourceList(corev1.ResourceList{
					"n1": zero,
					"n2": zero,
					"n3": one,
					"n4": one,
				})
				rhs := ResourceList(corev1.ResourceList{
					"n1": zero,
					"n2": one,
					"n3": zero,
					"n4": one,
				})

				lhs.Add(rhs)

				Expect(lhs.EqualTo(ResourceList(corev1.ResourceList{
					"n1": zero,
					"n2": one,
					"n3": one,
					"n4": two,
				})))
			})
		})
	})

	Describe("Sub", func() {
		When("resource sets of lhs and rhs are not equal", func() {
			It("resultant resource sets are merged", func() {
				lhs := ResourceList(corev1.ResourceList{
					"n1": one,
					"n2": one,
					"n3": one,
				})
				rhs := ResourceList(corev1.ResourceList{
					"n2": zero,
					"n3": one,
					"n4": two,
				})

				lhs.Sub(rhs)

				Expect(lhs.EqualTo(ResourceList(corev1.ResourceList{
					"n1": one,
					"n2": one,
					"n3": zero,
					"n4": minus_two,
				}))).Should(Equal(true))
			})
		})

		When("resource sets of lhs and rhs are equal", func() {
			It("just subtracts resource quantities", func() {
				lhs := ResourceList(corev1.ResourceList{
					"n1": zero,
					"n2": zero,
					"n3": one,
					"n4": one,
				})
				rhs := ResourceList(corev1.ResourceList{
					"n1": zero,
					"n2": one,
					"n3": zero,
					"n4": one,
				})

				lhs.Sub(rhs)

				Expect(lhs.EqualTo(ResourceList(corev1.ResourceList{
					"n1": zero,
					"n2": minus_one,
					"n3": one,
					"n4": zero,
				}))).Should(Equal(true))
			})
		})
	})

	Describe("GreaterOrEqual", func() {
		When("resource kinds are equal and lhs is greater than rhs", func() {
			It("should return true", func() {
				lhs := ResourceList(corev1.ResourceList{
					"n1": one,
					"n2": two,
					"n3": two,
				})
				rhs := ResourceList(corev1.ResourceList{
					"n1": one,
					"n2": one,
					"n3": two,
				})

				Expect(lhs.GreaterOrEqual(rhs)).Should(BeTrue())
			})
		})
		When("resource kinds are not equal and lhs is greater than rhs", func() {
			It("should return true", func() {
				lhs := ResourceList(corev1.ResourceList{
					"n1": one,
					"n2": two,
					"n3": two,
				})
				rhs := ResourceList(corev1.ResourceList{
					"n1": one,
					"n3": two,
				})

				Expect(lhs.GreaterOrEqual(rhs)).Should(BeTrue())
			})
		})
		When("resource kinds are equal and lhs is not greater than rhs", func() {
			It("should return false", func() {
				lhs := ResourceList(corev1.ResourceList{
					"n1": one,
					"n2": one,
					"n3": two,
				})
				rhs := ResourceList(corev1.ResourceList{
					"n1": one,
					"n2": two,
					"n3": one,
				})

				Expect(lhs.GreaterOrEqual(rhs)).Should(BeFalse())
			})
		})
		When("resource kinds are not equal and lhs is not greater than rhs", func() {
			It("should return false", func() {
				lhs := ResourceList(corev1.ResourceList{
					"n1": one,
					"n2": two,
					"n3": one,
				})
				rhs := ResourceList(corev1.ResourceList{
					"n1": one,
					"n3": two,
				})

				Expect(lhs.GreaterOrEqual(rhs)).Should(BeFalse())
			})
		})
		When("resource kinds are equal and lhs is equal to rhs", func() {
			It("should return true", func() {
				lhs := ResourceList(corev1.ResourceList{
					"n1": one,
					"n2": two,
					"n3": one,
				})
				rhs := ResourceList(corev1.ResourceList{
					"n1": one,
					"n2": two,
					"n3": one,
				})

				Expect(lhs.GreaterOrEqual(rhs)).Should(BeTrue())
			})
		})
		When("resource kinds are not equal and lhs is equal to rhs", func() {
			It("should return true", func() {
				lhs := ResourceList(corev1.ResourceList{
					"n1": one,
					"n2": two,
					"n3": one,
				})
				rhs := ResourceList(corev1.ResourceList{
					"n1": one,
					"n3": one,
				})

				Expect(lhs.GreaterOrEqual(rhs)).Should(BeTrue())
			})
		})
	})

	Describe("setMax", func() {
		When("resource sets of lhs and rhs are not equal", func() {
			It("resultant resource sets are merged even if its quantity is zero", func() {
				lhs := ResourceList(corev1.ResourceList{
					"n1": one,
					"n2": two,
					"n3": two,
				})
				rhs := ResourceList(corev1.ResourceList{
					"n2": one,
					"n3": two,
					"n4": zero,
				})

				lhs.SetMax(rhs)

				Expect(lhs.EqualTo(ResourceList(corev1.ResourceList{
					"n1": one,
					"n2": two,
					"n3": two,
					"n4": zero,
				}))).Should(Equal(true))
			})
		})

		When("resource sets of lhs and rhs are equal", func() {
			It("just takes max resource quantities", func() {
				lhs := ResourceList(corev1.ResourceList{
					"n1": one,
					"n2": one,
					"n3": two,
					"n4": two,
				})
				rhs := ResourceList(corev1.ResourceList{
					"n1": one,
					"n2": two,
					"n3": one,
					"n4": two,
				})

				lhs.SetMax(rhs)

				Expect(lhs.EqualTo(ResourceList(corev1.ResourceList{
					"n1": one,
					"n2": two,
					"n3": two,
					"n4": two,
				}))).Should(Equal(true))
			})
		})
	})

	Describe("setMin", func() {
		When("resource sets of lhs and rhs are not equal", func() {
			It("resultant resource sets are minimum of intersection", func() {
				lhs := ResourceList(corev1.ResourceList{
					"n1": one,
					"n2": two,
					"n3": one,
				})
				rhs := ResourceList(corev1.ResourceList{
					"n2": one,
					"n3": two,
					"n4": one,
				})

				lhs.SetMin(rhs)

				Expect(lhs.EqualTo(ResourceList(corev1.ResourceList{
					"n2": one,
					"n3": one,
				}))).Should(Equal(true))
			})
		})

		When("resource sets of lhs and rhs are equal", func() {
			It("takes minimum resource quantities even if its quantity is zero", func() {
				lhs := ResourceList(corev1.ResourceList{
					"n1": one,
					"n2": two,
					"n3": zero,
					"n4": two,
				})
				rhs := ResourceList(corev1.ResourceList{
					"n1": two,
					"n2": zero,
					"n3": two,
					"n4": one,
				})

				lhs.SetMin(rhs)

				Expect(rhs.EqualTo(ResourceList(corev1.ResourceList{
					"n1": one,
					"n2": zero,
					"n3": zero,
					"n4": one,
				})))
			})
		})
	})
})
