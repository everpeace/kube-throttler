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
	"fmt"

	"github.com/spf13/cobra"
)

var (
	// Version of the program.  It will be injected at build time.
	Version string
	// Revision of the program.  It will be injected at build time.
	Revision string
)

// versionCmd represents the version command
var versionCmd = &cobra.Command{
	Use:   "version",
	Short: "Print Version",
	Long:  `Print version.`,
	Run: func(cmd *cobra.Command, args []string) {
		fmt.Println(VersionString())
	},
}

func init() {
	rootCmd.AddCommand(versionCmd)
}

func VersionString() string {
	return fmt.Sprintf(`{"Version": "%s", "Revision": "%s"}
`, Version, Revision)
}
