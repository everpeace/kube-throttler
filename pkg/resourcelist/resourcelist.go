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
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/resource"
)

type ResourceList corev1.ResourceList

func PodRequestResourceList(pod *corev1.Pod) ResourceList {
	icRes := ResourceList{}
	for _, c := range pod.Spec.InitContainers {
		icRes.SetMax(ResourceList(c.Resources.Requests))
	}

	cRes := ResourceList{}
	for _, c := range pod.Spec.Containers {
		cRes.Add(ResourceList(c.Resources.Requests))
	}

	cRes.SetMax(icRes)

	// If Overhead is being utilized, add to the total requests for the pod
	if pod.Spec.Overhead != nil {
		cRes.Add(ResourceList(pod.Spec.Overhead))
	}

	return cRes
}

func (lhs ResourceList) Add(rhs ResourceList) {
	for name, qrhs := range rhs {
		qlhs := lhs[name]
		qlhs.Add(qrhs)
		lhs[name] = qlhs
	}
}

func (lhs ResourceList) Sub(rhs ResourceList) {
	for name, qrhs := range rhs {
		qlhs := lhs[name]
		qlhs.Sub(qrhs)
		lhs[name] = qlhs
	}
}

func (lhs ResourceList) GreaterOrEqual(rhs ResourceList) bool {
	for name, qrhs := range rhs {
		if qlhs, ok := lhs[name]; !ok {
			return false
		} else if qlhs.Cmp(qrhs) < 0 {
			return false
		}
	}

	return true
}

func (lhs ResourceList) SetMax(rhs ResourceList) {
	for rName, rQuant := range rhs {
		if _, ok := lhs[rName]; ok {
			lhs[rName] = quantityMax(lhs[rName], rQuant)
			continue
		}
		lhs[rName] = rhs[rName]
	}
}

func (lhs ResourceList) SetMin(rhs ResourceList) {
	for rName, rQuant := range rhs {
		if _, ok := lhs[rName]; ok {
			lhs[rName] = quantityMin(lhs[rName], rQuant)
		}
	}

	for lName := range lhs {
		if _, ok := rhs[lName]; !ok {
			delete(lhs, lName)
		}
	}
}

func (lhs ResourceList) EqualTo(rhs ResourceList) bool {
	equalTo := func(r1, r2 ResourceList) bool {
		for n, q := range r1 {
			if q.Cmp(r2[n]) != 0 {
				return false
			}
		}
		return true
	}

	return equalTo(lhs, rhs) && equalTo(rhs, lhs)
}

func quantityMax(qx, qy resource.Quantity) resource.Quantity {
	cmp := qx.Cmp(qy)
	switch {
	case cmp > 0:
		return qx
	case cmp < 0:
		return qy
	default:
		return qx
	}
}

func quantityMin(qx, qy resource.Quantity) resource.Quantity {
	cmp := qx.Cmp(qy)
	switch {
	case cmp < 0:
		return qx
	case cmp > 0:
		return qy
	default:
		return qx
	}
}
