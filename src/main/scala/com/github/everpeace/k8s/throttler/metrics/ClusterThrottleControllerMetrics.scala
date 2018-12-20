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
import com.github.everpeace.k8s.throttler.crd.v1alpha1
import com.github.everpeace.k8s.throttler.crd.v1alpha1.ResourceCount
import kamon.Kamon
import skuber.Resource

trait ClusterThrottleControllerMetrics extends MetricsBase {
  self: {
    def log: {
      def info(s: String): Unit
      def debug(s: String): Unit
    }
  } =>

  def resetClusterThrottleMetric(clthrottle: v1alpha1.ClusterThrottle): Unit = {
    val zeroSpec = clthrottle.spec.copy(
      threshold = clthrottle.spec.threshold.copy(
        resourceCounts = Option(
          ResourceCount(
            pod = Option(0)
          )),
        resourceRequests =
          clthrottle.spec.threshold.resourceRequests.mapValues(_ => Resource.Quantity("0"))
      ),
      selector = clthrottle.spec.selector
    )

    val zeroFalseStatus = clthrottle.status.map(
      st =>
        st.copy(
          throttled = st.throttled.copy(
            resourceCounts = st.throttled.resourceCounts.map { rc =>
              rc.copy(
                pod = rc.pod.map(_ => false)
              )
            },
            resourceRequests = st.throttled.resourceRequests.mapValues(_ => false)
          ),
          used = st.used.copy(
            resourceCounts = st.used.resourceCounts.map { rc =>
              rc.copy(
                pod = rc.pod.map(_ => 0)
              )
            },
            resourceRequests = st.used.resourceRequests.mapValues(_ => Resource.Quantity("0"))
          )
      )
    )
    val zero = clthrottle.copy(
      spec = zeroSpec,
      status = zeroFalseStatus
    )

    recordClusterThrottleSpecMetric(zero)
    recordClusterThrottleStatusMetric(zero)
  }

  def recordClusterThrottleSpecMetric(clthrottle: v1alpha1.ClusterThrottle): Unit = {
    val metadataTags = metadataTagsForClusterThrottle(clthrottle)

    val resourceRequestsThresholdGauge =
      Kamon.gauge("clusterthrottle.spec.threshold.resourceRequests")
    val resourceCountsThresholdGauge = Kamon.gauge("clusterthrottle.spec.threshold.resourceCounts")

    clthrottle.spec.threshold.resourceRequests.foreach { rq =>
      val tags  = metadataTags ++ resourceQuantityToTag(rq)
      val value = resourceQuantityToLong(rq)
      log.info(s"setting gauge '${resourceRequestsThresholdGauge.name}{${tags.values
        .mkString(",")}}' value with ${value}")
      resourceRequestsThresholdGauge.refine(tags).set(value)
    }

    for {
      rc    <- clthrottle.spec.threshold.resourceCounts
      value <- rc.pod
    } yield {
      val tags = metadataTags ++ resourceCountsTag("pod")
      log.info(
        s"setting gauge '${resourceCountsThresholdGauge.name}{${tags.values.mkString(",")}}' value with ${value}")
      resourceCountsThresholdGauge.refine(tags).set(value)
    }
  }

  def recordClusterThrottleStatusMetric(clthrottle: v1alpha1.ClusterThrottle): Unit = {
    val metadataTags  = metadataTagsForClusterThrottle(clthrottle)
    val statusRRGauge = Kamon.gauge("clusterthrottle.status.throttled.resourceRequests")
    val statusRCGauge = Kamon.gauge("clusterthrottle.status.throttled.resourceCounts")
    val usedRRGauge   = Kamon.gauge("clusterthrottle.status.used.resourceRequests")
    val usedRCGauge   = Kamon.gauge("clusterthrottle.status.used.resourceCounts")

    val b2i = (b: Boolean) => b compare false
    clthrottle.status.foreach { status =>
      status.throttled.resourceRequests foreach { rq =>
        val tags = metadataTags ++ resourceQuantityToTag(rq)
        log.info(
          s"setting gauge '${statusRRGauge.name}{${tags.values.mkString(",")}}' value with ${b2i(rq._2)}")
        statusRRGauge.refine(tags).set(b2i(rq._2))
      }

      for {
        rc    <- status.throttled.resourceCounts
        value <- rc.pod
      } yield {
        val tags = metadataTags ++ resourceCountsTag("pod")
        log.info(
          s"setting gauge '${statusRCGauge.name}{${tags.values.mkString(",")}}' value with ${b2i(value)}")
        statusRCGauge.refine(tags).set(b2i(value))
      }

      status.used.resourceRequests foreach { rq =>
        val tags  = metadataTags ++ resourceQuantityToTag(rq)
        val value = resourceQuantityToLong(rq)
        log.debug(
          s"setting gauge '${usedRRGauge.name}{${tags.values.mkString(",")}}' value with ${value}")
        usedRRGauge.refine(tags).set(value)
      }

      for {
        rc    <- status.used.resourceCounts
        value <- rc.pod
      } yield {
        val tags = metadataTags ++ resourceCountsTag("pod")
        log.debug(
          s"setting gauge '${usedRCGauge.name}{${tags.values.mkString(",")}}' value with ${value}")
        usedRCGauge.refine(tags).set(value)
      }
    }
  }

  def metadataTagsForClusterThrottle(throttle: v1alpha1.ClusterThrottle): kamon.Tags = {
    val metadata = List(
      "name" -> throttle.metadata.name,
      "uuid" -> throttle.metadata.uid
    ).toMap ++ throttle.metadata.clusterName.map(cn => Map("cluster" -> cn)).getOrElse(Map.empty)

    sanitizeTagKeys(metadata ++ throttle.metadata.labels)
  }

}
