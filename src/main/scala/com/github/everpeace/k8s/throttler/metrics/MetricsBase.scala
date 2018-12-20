/*
 * Copyright 2018 Shingo Omura <https://github.com/everpeace>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.everpeace.k8s.throttler.metrics
import skuber.Resource

trait MetricsBase {
  def resourceQuantityToLong(q: (String, Resource.Quantity)): Long = q._1 match {
    case Resource.cpu =>
      (q._2.amount * 1000).toLong // convert to milli
    case _ => q._2.amount.toLong
  }

  // prometheus restricts label name characters
  // ref: https://prometheus.io/docs/concepts/data_model/#metric-names-and-labels
  def sanitizeTagKeys(tag: kamon.Tags): kamon.Tags = tag.map {
    case (k, v) =>
      k.replaceAll("[^a-zA-z_]", "_").replaceAll("^[^a-zA-Z]", "X") -> v
  }

  def resourceQuantityToTag(q: (String, _)): kamon.Tags   = Map("resource" -> q._1)
  def resourceCountsTag(resourceName: String): kamon.Tags = Map("resource" -> resourceName)
}
