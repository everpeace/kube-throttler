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

	"github.com/pkg/errors"
)

type TemporaryThresholdOverride struct {
	Begin string `json:"begin"`
	End   string `json:"end"`
	// +kubebuilder:validation:Required
	Threshold ResourceAmount `json:"threshold"`
}

func (o TemporaryThresholdOverride) BeginTime() (time.Time, error) {
	var err error
	var beginTime time.Time
	if o.Begin != "" {
		beginTime, err = time.Parse(time.RFC3339, o.Begin)
		if err != nil {
			return beginTime, errors.Wrap(err, "Failed to parse Begin")
		}
	}
	return beginTime, nil
}

func (o TemporaryThresholdOverride) EndTime() (time.Time, error) {
	var endTime time.Time
	if o.End != "" {
		var err error
		endTime, err = time.Parse(time.RFC3339, o.End)
		if err != nil {
			return endTime, errors.Wrap(err, "Failed to parse End")
		}
	}
	return endTime, nil
}

func (o TemporaryThresholdOverride) IsActive(now time.Time) (bool, error) {
	beginTime, err := o.BeginTime()
	if err != nil {
		return false, err
	}
	endTime, err := o.EndTime()
	if err != nil {
		return false, err
	}

	begin := (beginTime.Before(now) || beginTime.Equal(now))
	end := (endTime.IsZero() || (now.Before(endTime) || now.Equal(endTime)))
	return begin && end, nil
}
