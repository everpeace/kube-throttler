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
	"fmt"
	"reflect"

	schedulev1alpha1 "github.com/everpeace/kube-throttler/pkg/apis/schedule/v1alpha1"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/klog/v2"
	"k8s.io/utils/keymutex"
)

type reservedResourceAmounts struct {
	// [Cluster]Throttle Name -> podResourceAmountMap
	cache    map[types.NamespacedName]podResourceAmountMap
	keyMutex keymutex.KeyMutex
}

func newReservedResourceAmounts() *reservedResourceAmounts {
	return &reservedResourceAmounts{
		cache:    map[types.NamespacedName]podResourceAmountMap{},
		keyMutex: keymutex.NewHashed(0),
	}
}

func (c *reservedResourceAmounts) addPod(nn types.NamespacedName, pod *corev1.Pod) {
	c.keyMutex.LockKey(nn.String())
	defer func() {
		_ = c.keyMutex.UnlockKey(nn.String())
	}()

	if _, ok := c.cache[nn]; !ok {
		c.cache[nn] = podResourceAmountMap{}
	}

	c.cache[nn].add(pod)
	klog.V(5).InfoS("reservedResourceAmounts.addPod", "Pod", pod.Namespace+"/"+pod.Name, "NamespacedName", nn.String(), "Cache", c.cache)
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

func (c *reservedResourceAmounts) moveThrottleAssignmentForPods(fromPod *corev1.Pod, fromThrs []types.NamespacedName, toPod *corev1.Pod, toThrs []types.NamespacedName) {
	fromThrSet := map[types.NamespacedName]struct{}{}
	toThrSet := map[types.NamespacedName]struct{}{}
	for _, nn := range fromThrs {
		fromThrSet[nn] = struct{}{}
	}
	for _, nn := range toThrs {
		toThrSet[nn] = struct{}{}
		delete(fromThrSet, nn)
	}
	for _, nn := range fromThrs {
		delete(toThrSet, nn)
	}
	if v5 := klog.V(5); v5.Enabled() {
		v5.InfoS(
			"reservedResourceAmounts.moveThrottleAssignmentForPods",
			"FromPod", fromPod.Namespace+"/"+fromPod.Name,
			"FromThrNNs", fmt.Sprint(reflect.ValueOf(fromThrSet).MapKeys()),
			"TromPod", toPod.Namespace+"/"+toPod.Name,
			"ToThrNNs", fmt.Sprint(reflect.ValueOf(toThrSet).MapKeys()),
		)
	}
	for nn := range toThrSet {
		c.addPod(nn, toPod)
	}
	for nn := range fromThrSet {
		c.removePod(nn, toPod)
	}
}

func (c *reservedResourceAmounts) reservedResourceAmount(nn types.NamespacedName) schedulev1alpha1.ResourceAmount {
	c.keyMutex.LockKey(nn.String())
	defer func() {
		_ = c.keyMutex.UnlockKey(nn.String())
	}()
	podResourceAmountMap, ok := c.cache[nn]
	if !ok {
		return schedulev1alpha1.ResourceAmount{}
	}
	return podResourceAmountMap.totalResoruceAmount()
}

// pod's namespacedname --> ResourceAmount of the pod
type podResourceAmountMap map[types.NamespacedName]schedulev1alpha1.ResourceAmount

func (c podResourceAmountMap) add(pod *corev1.Pod) {
	nn := types.NamespacedName{Namespace: pod.Namespace, Name: pod.Name}
	c[nn] = schedulev1alpha1.ResourceAmountOfPod(pod)
}

func (c podResourceAmountMap) remove(pod *corev1.Pod) bool {
	return c.removeByNN(types.NamespacedName{Namespace: pod.Namespace, Name: pod.Name})
}

func (c podResourceAmountMap) removeByNN(nn types.NamespacedName) bool {
	_, ok := c[nn]
	delete(c, nn)
	return ok
}

func (c podResourceAmountMap) totalResoruceAmount() schedulev1alpha1.ResourceAmount {
	result := schedulev1alpha1.ResourceAmount{}
	for _, ra := range c {
		result = result.Add(ra)
	}
	return result
}
