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
import com.github.everpeace.k8s.throttler.crd.v1alpha1.{IsResourceAmountThrottled, ResourceAmount}
import kamon.Kamon
import skuber.Resource

trait MetricsBase {
  self: {
    def log: {
      def info(s: String): Unit
      def debug(s: String): Unit
    }
  } =>

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

  def b2i(b: Boolean): Int = b compare false

  def recordIsThrottledMetric(
      metricsPrefix: String,
      tags: kamon.Tags,
      throttled: IsResourceAmountThrottled
    ): Unit = {
    val statusRRGauge = Kamon.gauge(s"$metricsPrefix.resourceRequests")
    val statusRCGauge = Kamon.gauge(s"$metricsPrefix.resourceCounts")

    throttled.resourceRequests foreach { rq =>
      val _tags = sanitizeTagKeys(tags ++ resourceQuantityToTag(rq))
      log.debug(
        s"setting gauge '${statusRRGauge.name}{${_tags.values.mkString(",")}}' value with ${b2i(rq._2)}")
      statusRRGauge.refine(_tags).set(b2i(rq._2))
    }

    for {
      rc    <- throttled.resourceCounts
      value <- rc.pod
    } yield {
      val _tags = sanitizeTagKeys(tags ++ resourceCountsTag("pod"))
      log.debug(
        s"setting gauge '${statusRCGauge.name}{${tags.values.mkString(",")}}' value with ${b2i(value)}")
      statusRCGauge.refine(_tags).set(b2i(value))
    }
  }

  def recordResourceAmountMetric(
      metricsPrefix: String,
      tags: kamon.Tags,
      ra: ResourceAmount
    ): Unit = {
    val rrGauge = Kamon.gauge(s"$metricsPrefix.resourceRequests")
    val rcGauge = Kamon.gauge(s"$metricsPrefix.resourceCounts")

    ra.resourceRequests foreach { rq =>
      val _tags = sanitizeTagKeys(tags ++ resourceQuantityToTag(rq))
      val value = resourceQuantityToLong(rq)
      log.debug(
        s"setting gauge '${rrGauge.name}{${_tags.values.mkString(",")}}' value with ${value}")
      rrGauge.refine(_tags).set(value)
    }

    for {
      rc    <- ra.resourceCounts
      value <- rc.pod
    } yield {
      val _tags = sanitizeTagKeys(tags ++ resourceCountsTag("pod"))
      log.debug(
        s"setting gauge '${rcGauge.name}{${_tags.values.mkString(",")}}' value with ${value}")
      rcGauge.refine(_tags).set(value)
    }
  }

  def zeroResourceAmount(ra: ResourceAmount): ResourceAmount = ra.copy(
    resourceCounts = ra.resourceCounts.map(
      rc =>
        rc.copy(
          pod = rc.pod.map(_ => 0)
      )),
    resourceRequests = ra.resourceRequests.mapValues(_ => Resource.Quantity("0"))
  )

  def falseIsResourceAmountThrottled(
      throttled: IsResourceAmountThrottled
    ): IsResourceAmountThrottled = throttled.copy(
    resourceCounts = throttled.resourceCounts.map { rc =>
      rc.copy(
        pod = rc.pod.map(_ => false)
      )
    },
    resourceRequests = throttled.resourceRequests.mapValues(_ => false)
  )
}
