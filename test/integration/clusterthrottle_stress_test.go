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

var _ = Describe("Clusterthrottle Stress Test", func() {
	ctx := context.Background()

	var nClThr = 50
	var nNs = 10
	var nPodsInNs = 10
	var totalCpuReq = fmt.Sprintf("%dm", nNs*nPodsInNs)
	thrName := func(i int) string { return fmt.Sprintf("clthr-%d", i) }
	nsName := func(i int) string { return fmt.Sprintf("ns-%d", i) }
	podName := func(i int) string { return fmt.Sprintf("pod-%d", i) }

	Context(fmt.Sprintf("%d clusterthrottle x (%d pod x %d namespace)", nClThr, nPodsInNs, nNs), func() {
		nsKey, nsVal := "targetns", "true"
		thrKey, thrVal := "clthr-target", "true"

		clthrs := make([]*v1alpha1.ClusterThrottle, nClThr)
		nss := make([]*corev1.Namespace, nNs)
		pods := make([]*corev1.Pod, nNs*nPodsInNs)

		AfterEach(func() {
			MustDeleteAllClusterThrottlesInNs(ctx)
			for _, ns := range nss {
				MustDeleteAllPodsInNs(ctx, ns.Name)
				MustDeleteNs(ctx, ns.Name)
			}
		})

		BeforeEach(func() {
			for i := range clthrs {
				clthrs[i] = MustCreateClusterThrottle(ctx,
					MakeClusterThrottle(thrName(i)).Selectors(nsKey, nsVal, thrKey, thrVal).
						ThresholdPod(nNs*nPodsInNs).
						ThresholdCpu(totalCpuReq).
						Obj(),
				)
			}
			for i := range nss {
				nss[i] = MustCreateNamespace(ctx, MakeNamespace(nsName(i)).Label(nsKey, nsVal).Obj())
				for j := 0; j < nPodsInNs; j++ {
					pods[i*nPodsInNs+j] = MustCreatePod(ctx, MakePod(nss[i].Name, podName(j), "1m").Label(thrKey, thrVal).Obj())
				}
			}
		})
		It("works correctly", func() {
			Eventually(AsyncAll(
				WakeupBackoffPod(ctx),
				AsyncPods(pods, func(p *corev1.Pod) func(g Gomega) { return PodIsScheduled(ctx, p.Namespace, p.Name) }),
				AsyncClusterThrottles(clthrs, func(thr *v1alpha1.ClusterThrottle) func(g Gomega) {
					return ClusterThottleHasStatus(ctx, thr.Name,
						ClthrOpts.WithCalculatedThreshold(thr.Spec.Threshold),
						ClthrOpts.WithUsedPod(nNs*nPodsInNs),
						ClthrOpts.WithUsedCpuReq(totalCpuReq),
						ClthrOpts.WithPodThrottled(true), ClthrOpts.WithCpuThrottled(true),
					)
				}),
			)).Should(Succeed())
		})
	})
})
