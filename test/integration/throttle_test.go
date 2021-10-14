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

package integration

import (
	"context"
	"fmt"

	"github.com/everpeace/kube-throttler/pkg/apis/schedule/v1alpha1"
	. "github.com/onsi/ginkgo"
	. "github.com/onsi/gomega"

	corev1 "k8s.io/api/core/v1"
)

var _ = Describe("Throttle Test", func() {
	ctx := context.Background()
	throttleKey := "throttle"
	throttleName := "test-throttle"

	AfterEach(func() {
		MustDeleteAllThrottlesInNs(ctx, DefaultNs)
		MustDeleteAllPodsInNs(ctx, DefaultNs)
	})

	When("Pod resource usage is within its threshold", func() {
		var thr *v1alpha1.Throttle
		var pod *corev1.Pod
		BeforeEach(func() {
			thr = MustCreateThrottle(ctx,
				MakeThrottle(DefaultNs, throttleName).PodSelector(throttleKey, throttleName).
					ThresholdPod(2).
					ThresholdCpu("1").
					Obj(),
			)
			pod = MustCreatePod(ctx, MakePod(DefaultNs, "pod", "500m").Label(throttleKey, throttleName).Obj())
		})
		It("should schedule successfully", func() {
			Eventually(AsyncAll(
				WakeupBackoffPod(ctx),
				PodIsScheduled(ctx, DefaultNs, pod.Name),
				ThrottleHasStatus(
					ctx, DefaultNs, thr.Name,
					ThOpts.WithCalculatedThreshold(thr.Spec.Threshold),
					ThOpts.WithUsedPod(1), ThOpts.WithUsedCpuReq("500m"),
					ThOpts.WithPodThrottled(false), ThOpts.WithCpuThrottled(false),
				),
			)).Should(Succeed())
		})
	})

	When("Pod resource usages exceeds threshold", func() {
		var thr *v1alpha1.Throttle
		BeforeEach(func() {
			thr = MustCreateThrottle(ctx,
				MakeThrottle(DefaultNs, throttleName).PodSelector(throttleKey, throttleName).
					ThresholdPod(2).
					ThresholdCpu("1").
					Obj(),
			)
		})
		Context("ResourceCount", func() {
			var pod1 *corev1.Pod
			var pod2 *corev1.Pod
			var pod3 *corev1.Pod
			BeforeEach(func() {
				pod1 = MustCreatePod(ctx, MakePod(DefaultNs, "pod1", "100m").Label(throttleKey, throttleName).Obj())
				pod2 = MustCreatePod(ctx, MakePod(DefaultNs, "pod2", "100m").Label(throttleKey, throttleName).Obj())
				pod3 = MustCreatePod(ctx, MakePod(DefaultNs, "pod3", "100m").Label(throttleKey, throttleName).Obj())
			})
			It("should not schedule pod3", func() {
				Eventually(AsyncAll(
					WakeupBackoffPod(ctx),
					PodIsScheduled(ctx, DefaultNs, pod1.Name),
					PodIsScheduled(ctx, DefaultNs, pod2.Name),
					ThrottleHasStatus(
						ctx, DefaultNs, thr.Name,
						ThOpts.WithCalculatedThreshold(thr.Spec.Threshold),
						ThOpts.WithUsedPod(2), ThOpts.WithUsedCpuReq("200m"),
						ThOpts.WithPodThrottled(true), ThOpts.WithCpuThrottled(false),
					),
					MustPodFailedScheduling(ctx, DefaultNs, pod3.Name, v1alpha1.CheckThrottleStatusActive),
				)).Should(Succeed())
				Consistently(PodIsNotScheduled(ctx, DefaultNs, pod3.Name)).Should(Succeed())
			})
		})
		Context("ResourceRequest (active)", func() {
			var pod1 *corev1.Pod
			var pod2 *corev1.Pod
			BeforeEach(func() {
				pod1 = MustCreatePod(ctx, MakePod(DefaultNs, "pod1", "1").Label(throttleKey, throttleName).Obj())
				pod2 = MustCreatePod(ctx, MakePod(DefaultNs, "pod2", "500m").Label(throttleKey, throttleName).Obj())
			})
			It("should not schedule pod3", func() {
				Eventually(AsyncAll(
					WakeupBackoffPod(ctx),
					PodIsScheduled(ctx, DefaultNs, pod1.Name),
					ThrottleHasStatus(
						ctx, DefaultNs, thr.Name,
						ThOpts.WithCalculatedThreshold(thr.Spec.Threshold),
						ThOpts.WithUsedPod(1), ThOpts.WithUsedCpuReq("1"),
						ThOpts.WithPodThrottled(false), ThOpts.WithCpuThrottled(true),
					),
					MustPodFailedScheduling(ctx, DefaultNs, pod2.Name, v1alpha1.CheckThrottleStatusActive),
				)).Should(Succeed())
				Consistently(PodIsNotScheduled(ctx, DefaultNs, pod2.Name)).Should(Succeed())
			})
		})
		Context("ResourceRequest (insufficient)", func() {
			var pod1 *corev1.Pod
			var pod2 *corev1.Pod
			BeforeEach(func() {
				pod1 = MustCreatePod(ctx, MakePod(DefaultNs, "pod1", "900m").Label(throttleKey, throttleName).Obj())
				pod2 = MustCreatePod(ctx, MakePod(DefaultNs, "pod2", "500m").Label(throttleKey, throttleName).Obj())
			})
			It("should not schedule pod3", func() {
				Eventually(AsyncAll(
					WakeupBackoffPod(ctx),
					PodIsScheduled(ctx, DefaultNs, pod1.Name),
					ThrottleHasStatus(
						ctx, DefaultNs, thr.Name,
						ThOpts.WithCalculatedThreshold(thr.Spec.Threshold),
						ThOpts.WithUsedPod(1), ThOpts.WithUsedCpuReq("900m"),
						ThOpts.WithPodThrottled(false), ThOpts.WithCpuThrottled(false),
					),
					MustPodFailedScheduling(ctx, DefaultNs, pod2.Name, v1alpha1.CheckThrottleStatusInsufficient),
				)).Should(Succeed())
				Consistently(PodIsNotScheduled(ctx, DefaultNs, pod2.Name)).Should(Succeed())
			})
		})
	})

	When("Many pods are created at once", func() {
		var thr *v1alpha1.Throttle
		var scheduled = make([]*corev1.Pod, 20)
		var pending *corev1.Pod
		BeforeEach(func() {
			thr = MustCreateThrottle(ctx,
				MakeThrottle(DefaultNs, throttleName).PodSelector(throttleKey, throttleName).
					ThresholdCpu("1").
					Obj(),
			)
			for i := range scheduled {
				scheduled[i] = MustCreatePod(ctx, MakePod(DefaultNs, fmt.Sprintf("pod-%d", i), "50m").Label(throttleKey, throttleName).Obj())
			}
			pending = MustCreatePod(ctx, MakePod(DefaultNs, "pod-20", "50m").Label(throttleKey, throttleName).Obj())
		})
		It("should throttle correctly", func() {
			Eventually(AsyncAll(
				WakeupBackoffPod(ctx),
				AsyncPods(scheduled, func(p *corev1.Pod) func(g Gomega) { return PodIsScheduled(ctx, DefaultNs, p.Name) }),
				ThrottleHasStatus(
					ctx, DefaultNs, thr.Name,
					ThOpts.WithCalculatedThreshold(thr.Spec.Threshold),
					ThOpts.WithUsedPod(20), ThOpts.WithUsedCpuReq("1"),
					ThOpts.WithPodThrottled(false), ThOpts.WithCpuThrottled(true),
				),
				// "kube-throttler reports the throttle is active" means "all the scheduled pods are un-reserved"
				MustPodFailedScheduling(ctx, DefaultNs, pending.Name, v1alpha1.CheckThrottleStatusActive),
			)).Should(Succeed())
			Consistently(PodIsNotScheduled(ctx, DefaultNs, pending.Name)).Should(Succeed())
		})
	})
})
