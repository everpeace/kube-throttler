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

	"github.com/everpeace/kube-throttler/pkg/apis/schedule/v1alpha1"
	. "github.com/onsi/gomega"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/labels"
)

func MakeClusterThrottle(name string) *clusterThrottleWrapper {
	return newClusterThrottleWrapper().Name(name).ThrottlerName(ThrottlerName)
}

type clusterThrottleWrapper struct {
	v1alpha1.ClusterThrottle
}

func newClusterThrottleWrapper() *clusterThrottleWrapper {
	return &clusterThrottleWrapper{ClusterThrottle: v1alpha1.ClusterThrottle{}}
}

func (w *clusterThrottleWrapper) Obj() *v1alpha1.ClusterThrottle {
	return &w.ClusterThrottle
}

func (w *clusterThrottleWrapper) Namespace(ns string) *clusterThrottleWrapper {
	w.ObjectMeta.Namespace = ns
	return w
}

func (w *clusterThrottleWrapper) Name(ns string) *clusterThrottleWrapper {
	w.ObjectMeta.Name = ns
	return w
}

func (w *clusterThrottleWrapper) ThrottlerName(throttlerName string) *clusterThrottleWrapper {
	w.Spec.ThrottlerName = throttlerName
	return w
}

func (w *clusterThrottleWrapper) Threshold(ra v1alpha1.ResourceAmount) *clusterThrottleWrapper {
	w.Spec.Threshold = ra
	return w
}

func (w *clusterThrottleWrapper) ThresholdPod(n int) *clusterThrottleWrapper {
	w.Spec.Threshold.ResourceCounts = &v1alpha1.ResourceCounts{Pod: n}
	return w
}

func (w *clusterThrottleWrapper) ThresholdCpu(qty string) *clusterThrottleWrapper {
	if w.Spec.Threshold.ResourceRequests == nil {
		w.Spec.Threshold.ResourceRequests = corev1.ResourceList{}
	}
	w.Spec.Threshold.ResourceRequests["cpu"] = resource.MustParse(qty)
	return w
}

func (w *clusterThrottleWrapper) Selector(ns, podKey, podVal string) *clusterThrottleWrapper {
	w.Spec.Selector.SelecterTerms = []v1alpha1.ClusterThrottleSelectorTerm{{
		NamespaceSelector: metav1.LabelSelector{MatchLabels: map[string]string{`kubernetes.io/metadata.name`: ns}},
		ThrottleSelectorTerm: v1alpha1.ThrottleSelectorTerm{
			PodSelector: metav1.LabelSelector{MatchLabels: map[string]string{podKey: podVal}},
		},
	}}
	return w
}

func (w *clusterThrottleWrapper) Selectors(nsKey, nsVal, podKey, podVal string) *clusterThrottleWrapper {
	w.Spec.Selector.SelecterTerms = []v1alpha1.ClusterThrottleSelectorTerm{{
		NamespaceSelector: metav1.LabelSelector{MatchLabels: map[string]string{nsKey: nsVal}},
		ThrottleSelectorTerm: v1alpha1.ThrottleSelectorTerm{
			PodSelector: metav1.LabelSelector{MatchLabels: map[string]string{podKey: podVal}},
		},
	}}
	return w
}

type ClusterThrottleStatusMatcher struct {
	name                string
	calculatedThreshold *v1alpha1.ResourceAmount
	usedPod             *int
	usedCpuReq          *string
	isPodThrottled      *bool
	isCpuThrottled      *bool
}

type ClusterThrottleStatusMatcherOption func(m *ClusterThrottleStatusMatcher)

type clusterThrottleStatusMatcherOptions struct{}

var ClthrOpts = clusterThrottleStatusMatcherOptions{}

func (opts clusterThrottleStatusMatcherOptions) WithCalculatedThreshold(ra v1alpha1.ResourceAmount) ClusterThrottleStatusMatcherOption {
	return func(m *ClusterThrottleStatusMatcher) {
		m.calculatedThreshold = &ra
	}
}

func (opts clusterThrottleStatusMatcherOptions) WithUsedPod(n int) ClusterThrottleStatusMatcherOption {
	return func(m *ClusterThrottleStatusMatcher) {
		m.usedPod = &n
	}
}

func (opts clusterThrottleStatusMatcherOptions) WithUsedCpuReq(qty string) ClusterThrottleStatusMatcherOption {
	return func(m *ClusterThrottleStatusMatcher) {
		m.usedCpuReq = &qty
	}
}

func (opts clusterThrottleStatusMatcherOptions) WithPodThrottled(b bool) ClusterThrottleStatusMatcherOption {
	return func(m *ClusterThrottleStatusMatcher) {
		m.isPodThrottled = &b
	}
}

func (opts clusterThrottleStatusMatcherOptions) WithCpuThrottled(b bool) ClusterThrottleStatusMatcherOption {
	return func(m *ClusterThrottleStatusMatcher) {
		m.isCpuThrottled = &b
	}
}

func ClusterThottleHasStatus(
	ctx context.Context,
	name string,
	opts ...ClusterThrottleStatusMatcherOption,
) func(g Gomega) {
	m := &ClusterThrottleStatusMatcher{name: name}
	for _, opt := range opts {
		opt(m)
	}
	return func(g Gomega) {
		got, err := kthrCli.ScheduleV1alpha1().ClusterThrottles().Get(ctx, m.name, metav1.GetOptions{})
		g.Expect(err).NotTo(HaveOccurred())
		if m.calculatedThreshold != nil {
			g.Expect(got.Status.CalculatedThreshold.Threshold).Should(BeEquivalentTo(*m.calculatedThreshold))
		}
		if m.usedPod != nil {
			g.Expect(got.Status.Used.ResourceCounts).Should(BeEquivalentTo(&v1alpha1.ResourceCounts{Pod: *m.usedPod}))
		}
		if m.usedCpuReq != nil {
			g.Expect(got.Status.Used.ResourceRequests).Should(SatisfyAll(
				HaveLen(1),
				WithTransform(GetQtyStr("cpu"), Equal(*m.usedCpuReq)),
			))
		}
		if m.isPodThrottled != nil {
			g.Expect(got.Status.Throttled.ResourceCounts.Pod).Should(Equal(*m.isPodThrottled))
		}
		if m.isCpuThrottled != nil {
			g.Expect(got.Status.Throttled.ResourceRequests).Should(SatisfyAll(
				HaveLen(1),
				WithTransform(GetResourceIsThrottled("cpu"), Equal(*m.isCpuThrottled)),
			))
		}
	}
}

func MustDeleteAllClusterThrottlesInNs(ctx context.Context) {
	Expect(
		kthrCli.ScheduleV1alpha1().ClusterThrottles().DeleteCollection(
			ctx, metav1.DeleteOptions{}, metav1.ListOptions{LabelSelector: labels.Everything().String()},
		),
	).NotTo(HaveOccurred())
	Eventually(func(g Gomega) {
		thrs, err := kthrCli.ScheduleV1alpha1().ClusterThrottles().List(ctx, metav1.ListOptions{LabelSelector: labels.Everything().String()})
		g.Expect(err).NotTo(HaveOccurred())
		g.Expect(thrs.Items).Should(HaveLen(0))
	}).Should(Succeed())
}

func MustCreateClusterThrottle(ctx context.Context, thr *v1alpha1.ClusterThrottle) *v1alpha1.ClusterThrottle {
	created, err := kthrCli.ScheduleV1alpha1().ClusterThrottles().Create(ctx, thr, metav1.CreateOptions{})
	Expect(err).NotTo(HaveOccurred())
	return created
}

func AsyncClusterThrottles(thrs []*v1alpha1.ClusterThrottle, f func(*v1alpha1.ClusterThrottle) func(g Gomega)) func(g Gomega) {
	return func(g Gomega) {
		for _, thr := range thrs {
			f(thr)(g)
		}
	}
}
