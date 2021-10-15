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
	"time"

	"github.com/everpeace/kube-throttler/pkg/apis/schedule/v1alpha1"
	. "github.com/onsi/gomega"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/labels"
	st "k8s.io/kubernetes/pkg/scheduler/testing"
	"k8s.io/utils/pointer"
)

func MakePod(namespace, name string, cpuReq string) *st.PodWrapper {
	w := st.MakePod().Namespace(namespace).Name(name).SchedulerName(SchedulerName).Container(pauseImage)
	w.Spec.Containers[0].Resources.Requests = corev1.ResourceList{
		"cpu": resource.MustParse(cpuReq),
	}
	w.Spec.TerminationGracePeriodSeconds = pointer.Int64Ptr(0)
	return w
}

func MustCreatePod(ctx context.Context, pod *corev1.Pod) *corev1.Pod {
	var err error
	created, err := k8sCli.CoreV1().Pods(pod.Namespace).Create(ctx, pod, metav1.CreateOptions{})
	Expect(err).NotTo(HaveOccurred())
	return created
}

func MustDeleteAllPodsInNs(ctx context.Context, ns string) {
	Expect(
		k8sCli.CoreV1().Pods(ns).DeleteCollection(
			ctx, metav1.DeleteOptions{}, metav1.ListOptions{LabelSelector: labels.Everything().String()},
		),
	).NotTo(HaveOccurred())
	Eventually(func(g Gomega) {
		pods, err := k8sCli.CoreV1().Pods(ns).List(ctx, metav1.ListOptions{LabelSelector: labels.Everything().String()})
		g.Expect(err).NotTo(HaveOccurred())
		g.Expect(pods.Items).Should(HaveLen(0))
	}, 10*time.Second).Should(Succeed())
}

func MustPodFailedScheduling(ctx context.Context, ns, n string, throttleStatus v1alpha1.CheckThrottleStatus) func(g Gomega) {
	return func(g Gomega) {
		events, err := k8sCli.CoreV1().Events(ns).List(ctx, metav1.ListOptions{
			FieldSelector: fmt.Sprintf("involvedObject.name=%s,reason=FailedScheduling", n),
		})
		g.Expect(err).NotTo(HaveOccurred())
		g.Expect(events.Items).Should(ContainElement(WithTransform(
			func(e corev1.Event) string { return e.Message },
			ContainSubstring(string(throttleStatus)),
		)))
	}
}

func PodIsScheduled(ctx context.Context, namespace, name string) func(g Gomega) {
	return func(g Gomega) {
		got, err := k8sCli.CoreV1().Pods(namespace).Get(ctx, name, metav1.GetOptions{})
		g.Expect(err).NotTo(HaveOccurred())
		g.Expect(got.Spec.NodeName).ShouldNot(BeEmpty())
	}
}

func PodIsNotScheduled(ctx context.Context, namespace, name string) func(g Gomega) {
	return func(g Gomega) {
		got, err := k8sCli.CoreV1().Pods(namespace).Get(ctx, name, metav1.GetOptions{})
		g.Expect(err).NotTo(HaveOccurred())
		g.Expect(got.Spec.NodeName).Should(BeEmpty())
	}
}

func AsyncPods(pods []*corev1.Pod, f func(*corev1.Pod) func(g Gomega)) func(g Gomega) {
	return func(g Gomega) {
		for _, pod := range pods {
			f(pod)(g)
		}
	}
}

func WakeupBackoffPod(ctx context.Context) func(g Gomega) {
	return func(g Gomega) {
		// updating node resource stimulates the scheduler and it wakes up pods in backoff queue
		nodes, err := k8sCli.CoreV1().Nodes().List(ctx, everything)
		g.Expect(err).NotTo(HaveOccurred())
		Expect(nodes.Items).NotTo(BeEmpty())
		node := &nodes.Items[0]
		if node.Status.Allocatable == nil {
			node.Status.Allocatable = corev1.ResourceList{}
		}
		if q, ok := node.Status.Allocatable["kube-throttler-e2e-test-dummy"]; ok {
			q.Add(resource.MustParse("1"))
			node.Status.Allocatable["kube-throttler-e2e-test-dummy"] = q
		} else {
			node.Status.Allocatable["kube-throttler-e2e-test-dummy"] = resource.MustParse("0")
		}
		_, err = k8sCli.CoreV1().Nodes().UpdateStatus(ctx, node, metav1.UpdateOptions{})
		g.Expect(err).NotTo(HaveOccurred())
	}
}
