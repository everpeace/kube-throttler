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
	"strings"

	schedulev1alpha1 "github.com/everpeace/kube-throttler/pkg/apis/schedule/v1alpha1"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/apimachinery/pkg/util/sets"
	"k8s.io/klog/v2"
	"k8s.io/utils/keymutex"
)

type reservedResourceAmounts struct {
	// [Cluster]Throttle Name -> podResourceAmountMap
	cache    map[types.NamespacedName]podResourceAmountMap
	keyMutex keymutex.KeyMutex
}

func newReservedResourceAmounts(n int) *reservedResourceAmounts {
	return &reservedResourceAmounts{
		cache:    map[types.NamespacedName]podResourceAmountMap{},
		keyMutex: keymutex.NewHashed(n),
	}
}

func (c *reservedResourceAmounts) addPod(nn types.NamespacedName, pod *corev1.Pod) bool {
	c.keyMutex.LockKey(nn.String())
	defer func() {
		_ = c.keyMutex.UnlockKey(nn.String())
	}()

	if _, ok := c.cache[nn]; !ok {
		c.cache[nn] = podResourceAmountMap{}
	}

	klog.V(5).InfoS("reservedResourceAmounts.addPod", "Pod", pod.Namespace+"/"+pod.Name, "NamespacedName", nn.String(), "Cache", c.cache)
	return c.cache[nn].add(pod)
}

func (c *reservedResourceAmounts) removePod(nn types.NamespacedName, pod *corev1.Pod) bool {
	c.keyMutex.LockKey(nn.String())
	defer func() {
		_ = c.keyMutex.UnlockKey(nn.String())
	}()

	if _, ok := c.cache[nn]; !ok {
		c.cache[nn] = podResourceAmountMap{}
	}

	removed := c.cache[nn].remove(pod)
	klog.V(5).InfoS("reservedResourceAmounts.removePod", "Pod", pod.Namespace+"/"+pod.Name, "NamespacedName", nn.String(), "Removed", removed, "Cache", c.cache)
	return removed
}

func (c *reservedResourceAmounts) moveThrottleAssignmentForPods(pod *corev1.Pod, fromThrs map[types.NamespacedName]struct{}, toThrs map[types.NamespacedName]struct{}) {
	removedNNs := []string{}
	for nn := range fromThrs {
		_ = c.removePod(nn, pod)
		removedNNs = append(removedNNs, nn.String())
	}
	addedNNs := []string{}
	for nn := range toThrs {
		_ = c.addPod(nn, pod)
		addedNNs = append(addedNNs, nn.String())
	}
	if len(removedNNs) > 0 || len(addedNNs) > 0 {
		klog.V(2).InfoS(
			"Moved (Cluster)Throttle Assignment For Pod In Reservation",
			"Pod", pod.Namespace+"/"+pod.Name,
			"FromThrottles", strings.Join(removedNNs, ","),
			"ToThrottles", strings.Join(addedNNs, ","),
		)
	}
}

func (c *reservedResourceAmounts) reservedResourceAmount(nn types.NamespacedName) (schedulev1alpha1.ResourceAmount, sets.String) {
	c.keyMutex.LockKey(nn.String())
	defer func() {
		_ = c.keyMutex.UnlockKey(nn.String())
	}()
	podResourceAmountMap, ok := c.cache[nn]
	if !ok {
		return schedulev1alpha1.ResourceAmount{}, sets.NewString()
	}
	return podResourceAmountMap.totalResoruceAmount()
}

// pod's namespacedname --> ResourceAmount of the pod
type podResourceAmountMap map[types.NamespacedName]schedulev1alpha1.ResourceAmount

func (c podResourceAmountMap) add(pod *corev1.Pod) bool {
	nn := types.NamespacedName{Namespace: pod.Namespace, Name: pod.Name}
	_, existed := c[nn]
	c[nn] = schedulev1alpha1.ResourceAmountOfPod(pod)
	return !existed
}

func (c podResourceAmountMap) remove(pod *corev1.Pod) bool {
	return c.removeByNN(types.NamespacedName{Namespace: pod.Namespace, Name: pod.Name})
}

func (c podResourceAmountMap) removeByNN(nn types.NamespacedName) bool {
	_, ok := c[nn]
	delete(c, nn)
	return ok
}

func (c podResourceAmountMap) totalResoruceAmount() (schedulev1alpha1.ResourceAmount, sets.String) {
	result := schedulev1alpha1.ResourceAmount{}
	nns := sets.NewString()
	for nn, ra := range c {
		nns.Insert(nn.String())
		result = result.Add(ra)
	}
	return result, nns
}
