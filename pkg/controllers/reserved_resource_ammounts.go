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
	schedulev1alpha1 "github.com/everpeace/kube-throttler/pkg/apis/schedule/v1alpha1"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/utils/keymutex"
)

// pod's namespacedname --> ResourceAmount of the pod
type podResourceAmountMap map[types.NamespacedName]schedulev1alpha1.ResourceAmount

func (c podResourceAmountMap) add(pod *corev1.Pod) {
	nn := types.NamespacedName{Namespace: pod.Namespace, Name: pod.Name}
	c[nn] = schedulev1alpha1.ResourceAmountOfPod(pod)
}

func (c podResourceAmountMap) remove(pod *corev1.Pod) {
	nn := types.NamespacedName{Namespace: pod.Namespace, Name: pod.Name}
	delete(c, nn)
}

func (c podResourceAmountMap) totalResoruceAmount() schedulev1alpha1.ResourceAmount {
	result := &schedulev1alpha1.ResourceAmount{}
	for _, ra := range c {
		result.Add(ra)
	}
	return *result
}

type reservedResourceAmounts struct {
	// [Cluster]Throttle Name -> podResourceAmountMap
	cache     map[types.NamespacedName]podResourceAmountMap
	keyMutext keymutex.KeyMutex
}

func newReservedResourceAmounts() *reservedResourceAmounts {
	return &reservedResourceAmounts{
		cache:     map[types.NamespacedName]podResourceAmountMap{},
		keyMutext: keymutex.NewHashed(0),
	}
}

func (c *reservedResourceAmounts) addPod(nn types.NamespacedName, pod *corev1.Pod) {
	c.keyMutext.LockKey(nn.String())
	defer func() {
		_ = c.keyMutext.UnlockKey(nn.String())
	}()

	if _, ok := c.cache[nn]; !ok {
		c.cache[nn] = podResourceAmountMap{}
	}

	c.cache[nn].add(pod)
}

func (c *reservedResourceAmounts) removePod(nn types.NamespacedName, pod *corev1.Pod) {
	c.keyMutext.LockKey(nn.String())
	defer func() {
		_ = c.keyMutext.UnlockKey(nn.String())
	}()

	if _, ok := c.cache[nn]; !ok {
		c.cache[nn] = podResourceAmountMap{}
	}

	c.cache[nn].remove(pod)
}

func (c *reservedResourceAmounts) reservedResourceAmount(nn types.NamespacedName) schedulev1alpha1.ResourceAmount {
	podResourceAmountMap, ok := c.cache[nn]
	if !ok {
		return schedulev1alpha1.ResourceAmount{}
	}
	return podResourceAmountMap.totalResoruceAmount()
}
