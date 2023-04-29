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

package cmd

import (
	"github.com/spf13/cobra"

	kubethrottler "github.com/everpeace/kube-throttler/pkg/scheduler_plugin"
	"k8s.io/component-base/logs"
	"k8s.io/kubernetes/cmd/kube-scheduler/app"
)

func kubeSchedulerCmd() *cobra.Command {
	command := app.NewSchedulerCommand(
		app.WithPlugin(kubethrottler.PluginName, kubethrottler.NewPlugin),
	)
	command.Short = "run kube-scheduler with kube-throttler plugin (need to enable 'KubeThrottler' plugin in config)"
	// TODO: once we switch everything over to Cobra commands, we can go back to calling
	// utilflag.InitFlags() (by removing its pflag.Parse() call). For now, we have to set the
	// normalize func and add the go flag set by hand.
	// utilflag.InitFlags()
	logs.InitLogs()
	defer logs.FlushLogs()
	return command
}

func init() {
	rootCmd.AddCommand(kubeSchedulerCmd())
}
