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

package controllers

import (
	"fmt"
	"sync"

	. "github.com/onsi/ginkgo"
	// . "github.com/onsi/gomega"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/types"
)

var _ = Describe("ReservedResourceAmounts", func() {
	n := 2000
	pod := func(i int) *corev1.Pod {
		return &corev1.Pod{
			ObjectMeta: metav1.ObjectMeta{
				Name: fmt.Sprintf("pod-%d", i),
			},
			Spec: corev1.PodSpec{
				Containers: []corev1.Container{},
			},
		}
	}
	It("should be threadsafe across different throttle's namespacenames", func() {
		r := newReservedResourceAmounts(1024)
		add := &sync.WaitGroup{}
		add.Add(n)
		for i := 0; i < n; i++ {
			go func(j int) {
				r.addPod(
					types.NamespacedName{Name: fmt.Sprintf("%d", j)},
					pod(j),
				)
				add.Done()
			}(i)
		}
		remove := &sync.WaitGroup{}
		remove.Add(n)
		for i := 0; i < n; i++ {
			go func(j int) {
				r.removePod(
					types.NamespacedName{Name: fmt.Sprintf("%d", j)},
					pod(j),
				)
				remove.Done()
			}(i)
		}
		add.Wait()
		remove.Wait()
	})
	It("should be threadsafe on specific throttle's namespacedname", func() {
		r := newReservedResourceAmounts(1024)
		add := &sync.WaitGroup{}
		add.Add(n)
		for i := 0; i < n; i++ {
			go func(j int) {
				r.addPod(
					types.NamespacedName{Name: "test"},
					pod(j),
				)
				add.Done()
			}(i)
		}
		remove := &sync.WaitGroup{}
		remove.Add(n)
		for i := 0; i < n; i++ {
			go func(j int) {
				r.removePod(
					types.NamespacedName{Name: "test"},
					pod(j),
				)
				remove.Done()
			}(i)
		}
		add.Wait()
		remove.Wait()
	})
})
