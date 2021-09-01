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
	"github.com/everpeace/kube-throttler/pkg/apis/schedule/v1alpha1"
	"github.com/prometheus/client_golang/prometheus"
	corev1 "k8s.io/api/core/v1"
)

type MetricsRecorder struct {
}

func (m *MetricsRecorder) recordResourceCounts(g *prometheus.GaugeVec, rc *v1alpha1.ResourceCounts) {
	if rc == nil {
		g.WithLabelValues("pod").Set(0.0)
	} else {
		g.WithLabelValues("pod").Set(float64(rc.Pod))
	}
}

func (m *MetricsRecorder) recordResourceRequests(g *prometheus.GaugeVec, rr corev1.ResourceList) {
	for r, q := range rr {
		switch r {
		case corev1.ResourceCPU:
			g.WithLabelValues(string(r)).Set(float64(q.MilliValue()))
		default:
			g.WithLabelValues(string(r)).Set(float64(q.Value()))
		}
	}
}

func (m *MetricsRecorder) recordIsResourceCountThrottled(g *prometheus.GaugeVec, rc v1alpha1.IsResourceCountThrottled) {
	if rc.Pod {
		g.WithLabelValues("pod").Set(float64(1))
	} else {
		g.WithLabelValues("pod").Set(float64(0))
	}
}

func (m *MetricsRecorder) recordIsResourceRequestsThrottled(g *prometheus.GaugeVec, rr map[corev1.ResourceName]bool) {
	if rr == nil {
		return
	}
	for r, throttled := range rr {
		if throttled {
			g.WithLabelValues(string(r)).Set(float64(1))
		} else {
			g.WithLabelValues(string(r)).Set(float64(0))
		}
	}
}
