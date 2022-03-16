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
	"flag"
	"io/ioutil"
	"os"
	"testing"

	. "github.com/onsi/ginkgo"
	. "github.com/onsi/gomega"

	. "github.com/MakeNowJust/heredoc/dot"
	kthrclient "github.com/everpeace/kube-throttler/pkg/generated/clientset/versioned"
	kubethrottler "github.com/everpeace/kube-throttler/pkg/scheduler_plugin"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/labels"
	scheduler "k8s.io/kubernetes/cmd/kube-scheduler/app"
	"k8s.io/kubernetes/cmd/kube-scheduler/app/options"

	"k8s.io/client-go/kubernetes"
	"k8s.io/client-go/tools/clientcmd"
)

const (
	SchedulerName = "kube-throttler-e2e"
	ThrottlerName = "kube-throttler"
	DefaultNs     = "default"
)

var (
	kubeConfigPath                  string
	pauseImage                      string
	schedulerConfigPath             string
	schedulerContext, stopScheduler = context.WithCancel(context.Background())
	kthrCli                         *kthrclient.Clientset
	k8sCli                          *kubernetes.Clientset

	everything = metav1.ListOptions{LabelSelector: labels.Everything().String()}
)

func init() {
	flag.StringVar(&kubeConfigPath, "kubeconfig", "", "kubeconfig path")
	flag.StringVar(&pauseImage, "pause-image", "", "pause image")
}

func TestIntegration(t *testing.T) {
	RegisterFailHandler(Fail)
	RunSpecs(t, "Integration Suite")
}

var _ = BeforeSuite(func() {
	flag.Parse()
	Expect(kubeConfigPath).NotTo(BeEmpty())
	Expect(pauseImage).NotTo(BeEmpty())
	restConfig, err := clientcmd.BuildConfigFromFlags("", kubeConfigPath)
	Expect(err).NotTo(HaveOccurred())
	kthrCli = kthrclient.NewForConfigOrDie(restConfig)
	k8sCli = kubernetes.NewForConfigOrDie(restConfig)
	mustStartKubeThrottler()
})

var _ = AfterSuite(func() {
	stopScheduler()
	if schedulerConfigPath != "" {
		Expect(os.Remove(schedulerConfigPath)).NotTo(HaveOccurred())
	}
})

func mustStartKubeThrottler() {
	schedulerConfigFile, err := ioutil.TempFile("", SchedulerName+"-config-*.yaml")
	Expect(err).NotTo(HaveOccurred())
	schedulerConfigPath = schedulerConfigFile.Name()
	_, err = schedulerConfigFile.Write([]byte(
		Df(`
			apiVersion: kubescheduler.config.k8s.io/v1beta3
			kind: KubeSchedulerConfiguration
			leaderElection:
			  leaderElect: false
			clientConnection:
			  kubeconfig: %s
			# podInitialBackoffSeconds: 1
			# podMaxBackoffSeconds: 1
			percentageOfNodesToScore: 100
			profiles:
			- schedulerName: %s
			  plugins:
			    multiPoint:
			      enabled:
			      - name: kube-throttler
			  pluginConfig:
			  - name: kube-throttler
			    args:
			      name: %s
			      targetSchedulerName: %s
			      kubeconfig: %s
			      controllerThrediness: 64
			      numKeyMutex: 128
		`,
			kubeConfigPath,                               // clientConnection.kubeconfig
			SchedulerName,                                // prifiles[0].scedulerName
			ThrottlerName, SchedulerName, kubeConfigPath, // profiles[0].pluginConfig[0].args
		),
	))
	Expect(err).NotTo(HaveOccurred())
	schedulerConfigFile.Close()

	opts := options.NewOptions()
	opts.ConfigFile = schedulerConfigPath
	cc, sched, err := scheduler.Setup(
		schedulerContext,
		opts,
		scheduler.WithPlugin(kubethrottler.PluginName, kubethrottler.NewPlugin),
	)
	Expect(err).NotTo(HaveOccurred())

	go func() {
		_ = scheduler.Run(schedulerContext, cc, sched)
	}()
	// logs.GlogSetter("2")
}
