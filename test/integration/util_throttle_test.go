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
	"time"

	"github.com/everpeace/kube-throttler/pkg/apis/schedule/v1alpha1"
	. "github.com/onsi/gomega"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/labels"
)

func MakeThrottle(namespace, name string) *throttleWrapper {
	return newThrottleWrapper().Namespace(namespace).Name(name).ThrottlerName(ThrottlerName)
}

type throttleWrapper struct {
	v1alpha1.Throttle
}

func newThrottleWrapper() *throttleWrapper {
	return &throttleWrapper{Throttle: v1alpha1.Throttle{}}
}

func (w *throttleWrapper) Obj() *v1alpha1.Throttle {
	return &w.Throttle
}

func (w *throttleWrapper) Namespace(ns string) *throttleWrapper {
	w.ObjectMeta.Namespace = ns
	return w
}

func (w *throttleWrapper) Name(ns string) *throttleWrapper {
	w.ObjectMeta.Name = ns
	return w
}

func (w *throttleWrapper) ThrottlerName(throttlerName string) *throttleWrapper {
	w.Spec.ThrottlerName = throttlerName
	return w
}

func (w *throttleWrapper) Threshold(ra v1alpha1.ResourceAmount) *throttleWrapper {
	w.Spec.Threshold = ra
	return w
}

func (w *throttleWrapper) ThresholdPod(n int) *throttleWrapper {
	w.Spec.Threshold.ResourceCounts = &v1alpha1.ResourceCounts{Pod: n}
	return w
}

func (w *throttleWrapper) ThresholdCpu(qty string) *throttleWrapper {
	if w.Spec.Threshold.ResourceRequests == nil {
		w.Spec.Threshold.ResourceRequests = corev1.ResourceList{}
	}
	w.Spec.Threshold.ResourceRequests["cpu"] = resource.MustParse(qty)
	return w
}

func (w *throttleWrapper) PodSelector(key, val string) *throttleWrapper {
	w.Spec.Selector.SelecterTerms = []v1alpha1.ThrottleSelectorTerm{{
		PodSelector: metav1.LabelSelector{MatchLabels: map[string]string{key: val}},
	}}
	return w
}

type ThrottleStatusMatcher struct {
	namespace           string
	name                string
	calculatedThreshold *v1alpha1.ResourceAmount
	usedPod             *int
	usedCpuReq          *string
	isPodThrottled      *bool
	isCpuThrottled      *bool
}
type ThrottleStatusMatcherOption func(m *ThrottleStatusMatcher)

type throttleStatusMatcherOptions struct{}

var ThOpts = throttleStatusMatcherOptions{}

func (opts throttleStatusMatcherOptions) WithCalculatedThreshold(ra v1alpha1.ResourceAmount) ThrottleStatusMatcherOption {
	return func(m *ThrottleStatusMatcher) {
		m.calculatedThreshold = &ra
	}
}

func (opts throttleStatusMatcherOptions) WithUsedPod(n int) ThrottleStatusMatcherOption {
	return func(m *ThrottleStatusMatcher) {
		m.usedPod = &n
	}
}

func (opts throttleStatusMatcherOptions) WithUsedCpuReq(qty string) ThrottleStatusMatcherOption {
	return func(m *ThrottleStatusMatcher) {
		m.usedCpuReq = &qty
	}
}

func (opts throttleStatusMatcherOptions) WithPodThrottled(b bool) ThrottleStatusMatcherOption {
	return func(m *ThrottleStatusMatcher) {
		m.isPodThrottled = &b
	}
}

func (opts throttleStatusMatcherOptions) WithCpuThrottled(b bool) ThrottleStatusMatcherOption {
	return func(m *ThrottleStatusMatcher) {
		m.isCpuThrottled = &b
	}
}

func ThrottleHasStatus(
	ctx context.Context,
	namespace, name string,
	opts ...ThrottleStatusMatcherOption,
) func(g Gomega) {
	m := &ThrottleStatusMatcher{namespace: namespace, name: name}
	for _, opt := range opts {
		opt(m)
	}
	return func(g Gomega) {
		got, err := kthrCli.ScheduleV1alpha1().Throttles(m.namespace).Get(ctx, m.name, metav1.GetOptions{})
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

func GetQtyStr(n corev1.ResourceName) func(map[corev1.ResourceName]resource.Quantity) string {
	return func(m map[corev1.ResourceName]resource.Quantity) string {
		q, ok := m[n]
		if ok {
			return q.String()
		}
		return ""
	}
}

func GetResourceIsThrottled(n corev1.ResourceName) func(map[corev1.ResourceName]bool) bool {
	return func(m map[corev1.ResourceName]bool) bool {
		q, ok := m[n]
		if ok {
			return q
		}
		return false
	}
}

func MustDeleteAllThrottlesInNs(ctx context.Context, ns string) {
	Expect(
		kthrCli.ScheduleV1alpha1().Throttles(ns).DeleteCollection(
			ctx, metav1.DeleteOptions{}, metav1.ListOptions{LabelSelector: labels.Everything().String()},
		),
	).NotTo(HaveOccurred())
	Eventually(func(g Gomega) {
		thrs, err := kthrCli.ScheduleV1alpha1().Throttles(ns).List(ctx, metav1.ListOptions{LabelSelector: labels.Everything().String()})
		g.Expect(err).NotTo(HaveOccurred())
		g.Expect(thrs.Items).Should(HaveLen(0))
	}, 10*time.Second).Should(Succeed())
}

func MustCreateThrottle(ctx context.Context, thr *v1alpha1.Throttle) *v1alpha1.Throttle {
	created, err := kthrCli.ScheduleV1alpha1().Throttles(thr.Namespace).Create(ctx, thr, metav1.CreateOptions{})
	Expect(err).NotTo(HaveOccurred())
	return created
}
