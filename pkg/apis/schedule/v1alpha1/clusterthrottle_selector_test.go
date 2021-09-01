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
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

var clusterThrottleSelecterSpec = Describe("ClusterThrottleSelector.MatchesPod", func() {
	var testee ClusterThrottleSelector

	Describe("Empty selector", func() {
		testLabel := map[string]string{
			"test": "test",
		}
		BeforeEach(func() {
			testee = ClusterThrottleSelector{}
		})
		It("should match no pods", func() {
			matched, err := testee.MatchesToPod(mkPod("test", "test").WithLabels(testLabel).Pod, mkNamespace("test", testLabel))
			Expect(err).ShouldNot(HaveOccurred())
			Expect(matched).Should(BeFalse())
		})
	})
	Describe("Multiple SelectorTerms", func() {
		test1Label := map[string]string{
			"test1": "test1",
		}
		test2Label := map[string]string{
			"test2": "test2",
		}
		BeforeEach(func() {
			testee = ClusterThrottleSelector{
				SelecterTerms: []ClusterThrottleSelectorTerm{{
					NamespaceSelector: metav1.LabelSelector{
						MatchLabels: test1Label,
					},
					ThrottleSelectorTerm: ThrottleSelectorTerm{
						PodSelector: metav1.LabelSelector{
							MatchLabels: test1Label,
						},
					},
				}, {
					NamespaceSelector: metav1.LabelSelector{
						MatchLabels: test2Label,
					},
					ThrottleSelectorTerm: ThrottleSelectorTerm{
						PodSelector: metav1.LabelSelector{
							MatchLabels: test2Label,
						},
					},
				}},
			}
		})
		It("should be evaluated in OR-ed", func() {
			var matched bool
			var err error
			matched, err = testee.MatchesToPod(mkPod("test1", "test1").WithLabels(test1Label).Pod, mkNamespace("test1", test1Label))
			Expect(err).ShouldNot(HaveOccurred())
			Expect(matched).Should(BeTrue())
			matched, err = testee.MatchesToPod(mkPod("test2", "test2").WithLabels(test2Label).Pod, mkNamespace("test2", test2Label))
			Expect(err).ShouldNot(HaveOccurred())
			Expect(matched).Should(BeTrue())

			matched, err = testee.MatchesToPod(mkPod("test1", "test2").WithLabels(test2Label).Pod, mkNamespace("test1", test1Label))
			Expect(err).ShouldNot(HaveOccurred())
			Expect(matched).Should(BeFalse())
		})
	})
})

var clusterThrottleSelecterTermSpec = Describe("ClusterthrottleSelectorTerm.MatchesPod", func() {
	var testee ClusterThrottleSelectorTerm
	testLabel := map[string]string{
		"test": "test",
	}

	Describe("Empty ClusterThrottleSelectorTerm", func() {
		BeforeEach(func() {
			testee = ClusterThrottleSelectorTerm{}
		})
		It("should match any namespace and pods", func() {
			var matched bool
			var err error
			matched, err = testee.MatchesToPod(mkPod("test1", "test1").WithLabels(testLabel).Pod, mkNamespace("test1", testLabel))
			Expect(err).ShouldNot(HaveOccurred())
			Expect(matched).Should(BeTrue())

			matched, err = testee.MatchesToPod(mkPod("test1", "test1").Pod, mkNamespace("test1", nil))
			Expect(err).ShouldNot(HaveOccurred())
			Expect(matched).Should(BeTrue())
		})
	})
})
