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

	. "github.com/onsi/gomega"
	corev1 "k8s.io/api/core/v1"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

func MustCreateNamespace(ctx context.Context, ns *corev1.Namespace) *corev1.Namespace {
	var err error
	created, err := k8sCli.CoreV1().Namespaces().Create(ctx, ns, metav1.CreateOptions{})
	Expect(err).NotTo(HaveOccurred())
	return created
}

func MustDeleteNs(ctx context.Context, ns string) {
	Expect(
		k8sCli.CoreV1().Namespaces().Delete(ctx, ns, metav1.DeleteOptions{}),
	).NotTo(HaveOccurred())
	Eventually(func(g Gomega) {
		_, err := k8sCli.CoreV1().Namespaces().Get(ctx, ns, metav1.GetOptions{})
		g.Expect(err).To(HaveOccurred())
		g.Expect(apierrors.IsNotFound(err)).Should(BeTrue())
	}, 10*time.Second).Should(Succeed())
}

type namespaceWrapper struct {
	corev1.Namespace
}

func MakeNamespace(name string) *namespaceWrapper {
	return &namespaceWrapper{
		Namespace: corev1.Namespace{ObjectMeta: metav1.ObjectMeta{Name: name, Labels: map[string]string{}}},
	}
}

func (w *namespaceWrapper) Label(key, val string) *namespaceWrapper {
	w.Labels[key] = val
	return w
}

func (w *namespaceWrapper) Obj() *corev1.Namespace {
	return &w.Namespace
}
