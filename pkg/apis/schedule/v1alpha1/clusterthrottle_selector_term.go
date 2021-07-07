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
	corev1 "k8s.io/api/core/v1"
	"k8s.io/component-helpers/scheduling/corev1/nodeaffinity"
)

type ClusterThrottleSelectorTerm struct {
	ThrottleSelectorTerm `json:",inline"`
	NamespaceSelector    []corev1.NodeSelectorRequirement `json:"namespaceSelector"`
}

func (t ClusterThrottleSelectorTerm) MatchesToPod(pod *corev1.Pod, ns *corev1.Namespace) (bool, error) {
	// check namespace match first
	nss, err := nodeaffinity.NewNodeSelector(&corev1.NodeSelector{
		NodeSelectorTerms: []corev1.NodeSelectorTerm{{
			MatchExpressions: t.NamespaceSelector,
		}},
	})
	if err != nil {
		return false, err
	}
	if !nss.Match(&corev1.Node{ObjectMeta: ns.ObjectMeta}) {
		return false, nil
	}

	match, err := t.ThrottleSelectorTerm.MatchesToPod(pod)
	if err != nil {
		return false, err
	}

	return match, nil
}
