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
	"sync"

	schedulev1alpha1 "github.com/everpeace/kube-throttler/pkg/apis/schedule/v1alpha1"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/apimachinery/pkg/util/sets"
	"k8s.io/klog/v2"
	"k8s.io/utils/keymutex"
)

type reservedResourceAmounts struct {
	// This protects concurrent accesses to cache itself
	sync.RWMutex

	// This protects concurrent accesses on the same (Cluster)Throttle's key
	keyMutex keymutex.KeyMutex

	// [Cluster]Throttle Name -> podResourceAmountMap
	cache map[types.NamespacedName]podResourceAmountMap
}

func newReservedResourceAmounts(n int) *reservedResourceAmounts {
	return &reservedResourceAmounts{
		cache:    map[types.NamespacedName]podResourceAmountMap{},
		keyMutex: keymutex.NewHashed(n),
	}
}

func (c *reservedResourceAmounts) getPodResourceAmountMap(nn types.NamespacedName) podResourceAmountMap {
	c.RLock()
	if m, ok := c.cache[nn]; ok {
		c.RUnlock()
		return m
	}
	c.RUnlock()

	c.Lock()
	defer c.Unlock()
	if _, ok := c.cache[nn]; !ok {
		c.cache[nn] = podResourceAmountMap{}
	}
	return c.cache[nn]
}

func (c *reservedResourceAmounts) addPod(nn types.NamespacedName, pod *corev1.Pod) bool {
	c.keyMutex.LockKey(nn.String())
	defer func() {
		_ = c.keyMutex.UnlockKey(nn.String())
	}()

	m := c.getPodResourceAmountMap(nn)
	added := m.add(pod)

	klog.V(5).InfoS("reservedResourceAmounts.addPod", "Pod", pod.Namespace+"/"+pod.Name, "NamespacedName", nn.String(), "Cache", m)
	return added
}

func (c *reservedResourceAmounts) removePod(nn types.NamespacedName, pod *corev1.Pod) bool {
	c.keyMutex.LockKey(nn.String())
	defer func() {
		_ = c.keyMutex.UnlockKey(nn.String())
	}()

	m := c.getPodResourceAmountMap(nn)
	removed := m.remove(pod)

	klog.V(5).InfoS("reservedResourceAmounts.removePod", "Pod", pod.Namespace+"/"+pod.Name, "NamespacedName", nn.String(), "Removed", removed, "Cache", m)
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

func (c *reservedResourceAmounts) reservedResourceAmount(nn types.NamespacedName) (schedulev1alpha1.ResourceAmount, sets.Set[string]) {
	c.keyMutex.LockKey(nn.String())
	defer func() {
		_ = c.keyMutex.UnlockKey(nn.String())
	}()
	podResourceAmountMap, ok := c.cache[nn]
	if !ok {
		return schedulev1alpha1.ResourceAmount{}, sets.New[string]()
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

func (c podResourceAmountMap) totalResoruceAmount() (schedulev1alpha1.ResourceAmount, sets.Set[string]) {
	result := schedulev1alpha1.ResourceAmount{}
	nns := sets.New[string]()
	for nn, ra := range c {
		nns.Insert(nn.String())
		result = result.Add(ra)
	}
	return result, nns
}
