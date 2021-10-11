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

package v1alpha1

import (
	"time"

	. "github.com/onsi/ginkgo"
	. "github.com/onsi/gomega"
)

var _ = Describe("TemporaryThresholdOverride", func() {
	var testee TemporaryThresholdOverride
	var begin, end string
	var beginTime, endTime time.Time
	BeforeEach(func() {
		var err error
		begin = "2021-08-04T10:00:00Z"
		beginTime, err = time.Parse(time.RFC3339, begin)
		Expect(err).ShouldNot(HaveOccurred())
		end = "2021-08-05T10:00:00Z"
		endTime, err = time.Parse(time.RFC3339, end)
		Expect(err).ShouldNot(HaveOccurred())
	})
	Describe("IsActive", func() {
		When("Empty Begin/End", func() {
			It("should always active", func() {
				testee = TemporaryThresholdOverride{}
				Expect(testee.IsActive(time.Time{})).Should(BeTrue())
				Expect(testee.IsActive(beginTime)).Should(BeTrue())
				Expect(testee.IsActive(endTime)).Should(BeTrue())
			})
		})
		When("Begin only", func() {
			It("should active at Begin <= t", func() {
				testee = TemporaryThresholdOverride{
					Begin: begin,
				}
				Expect(testee.IsActive(beginTime.Add(-1 * time.Second))).Should(BeFalse())
				Expect(testee.IsActive(beginTime)).Should(BeTrue())
				Expect(testee.IsActive(beginTime.Add(1 * time.Second))).Should(BeTrue())
				Expect(testee.IsActive(beginTime.Add(65535 * time.Hour))).Should(BeTrue())
			})
		})
		When("End only", func() {
			It("should active at t <= End", func() {
				testee = TemporaryThresholdOverride{
					End: end,
				}
				Expect(testee.IsActive(endTime.Add(-65535 * time.Hour))).Should(BeTrue())
				Expect(testee.IsActive(endTime.Add(-1 * time.Second))).Should(BeTrue())
				Expect(testee.IsActive(endTime)).Should(BeTrue())
				Expect(testee.IsActive(endTime.Add(1 * time.Second))).Should(BeFalse())
			})
		})
		When("Begin and End", func() {
			It("should active at Begin <= t <= End", func() {
				testee = TemporaryThresholdOverride{
					Begin: begin,
					End:   end,
				}
				Expect(testee.IsActive(beginTime.Add(-1 * time.Second))).Should(BeFalse())
				Expect(testee.IsActive(beginTime)).Should(BeTrue())
				Expect(testee.IsActive(beginTime.Add(1 * time.Second))).Should(BeTrue())
				Expect(testee.IsActive(endTime.Add(-1 * time.Second))).Should(BeTrue())
				Expect(testee.IsActive(endTime)).Should(BeTrue())
				Expect(testee.IsActive(endTime.Add(1 * time.Second))).Should(BeFalse())
			})
		})
		When("Begin/End failed to parse", func() {
			It("should raise error", func() {
				var err error
				testee = TemporaryThresholdOverride{
					Begin: "not-time",
				}
				_, err = testee.IsActive(time.Time{})
				Expect(err).Should(HaveOccurred())

				testee = TemporaryThresholdOverride{
					End: "not-time",
				}
				_, err = testee.IsActive(time.Time{})
				Expect(err).Should(HaveOccurred())
			})
		})
	})
})
