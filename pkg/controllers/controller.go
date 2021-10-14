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
	"time"

	scheduleclientset "github.com/everpeace/kube-throttler/pkg/generated/clientset/versioned"
	"k8s.io/apimachinery/pkg/util/clock"
	utilruntime "k8s.io/apimachinery/pkg/util/runtime"
	"k8s.io/apimachinery/pkg/util/wait"
	corev1informer "k8s.io/client-go/informers/core/v1"
	"k8s.io/client-go/tools/cache"
	"k8s.io/client-go/util/workqueue"
	"k8s.io/klog/v2"
)

type ControllerBase struct {
	targetKind          string
	name                string
	throttlerName       string
	targetSchedulerName string

	scheduleClientset scheduleclientset.Clientset
	podInformer       corev1informer.PodInformer
	cache             *reservedResourceAmounts

	clock     clock.Clock
	workqueue workqueue.RateLimitingInterface

	reconcileFunc func(key string) error
}

func (c *ControllerBase) Start(threadiness int, stopCh <-chan struct{}) error {
	klog.InfoS(fmt.Sprintf("Starting %s", c.name), "name", c.throttlerName, "threadiness", threadiness)

	// Launch  workers to process Foo resources
	for i := 0; i < threadiness; i++ {
		go wait.Until(c.runWorker, time.Second, stopCh)
	}

	klog.InfoS(fmt.Sprintf("Started %s workers", c.name), "name", c.throttlerName, "threadiness", threadiness)
	return nil
}

func (c *ControllerBase) enqueueAfter(obj interface{}, duration time.Duration) {
	var key string
	var err error
	if key, err = cache.MetaNamespaceKeyFunc(obj); err != nil {
		utilruntime.HandleError(err)
		return
	}
	c.workqueue.AddAfter(key, duration)
}

func (c *ControllerBase) enqueue(obj interface{}) {
	var key string
	var err error
	if key, err = cache.MetaNamespaceKeyFunc(obj); err != nil {
		utilruntime.HandleError(err)
		return
	}
	c.workqueue.Add(key)
}

func (c *ControllerBase) runWorker() {
	for c.processNextWorkItem() {
	}
}

func (c *ControllerBase) processNextWorkItem() bool {
	obj, shutdown := c.workqueue.Get()

	if shutdown {
		return false
	}

	err := func(obj interface{}) error {
		defer c.workqueue.Done(obj)
		var key string
		var ok bool

		if key, ok = obj.(string); !ok {
			c.workqueue.Forget(obj)
			utilruntime.HandleError(fmt.Errorf("expected string in workqueue but got %#v", obj))
			return nil
		}
		if err := c.reconcileFunc(key); err != nil {
			c.workqueue.AddRateLimited(key)
			return fmt.Errorf("error reconciling '%s': %s, requeuing", key, err.Error())
		}
		// get queued again until another change happens.
		c.workqueue.Forget(obj)
		klog.InfoS("Successfully reconciled", c.targetKind, key)
		return nil
	}(obj)

	if err != nil {
		utilruntime.HandleError(err)
		return true
	}

	return true
}
