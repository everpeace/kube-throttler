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
import com.github.everpeace.k8s.throttler.crd.v1alpha1.CalculatedThreshold

trait ClusterThrottleControllerMetrics extends MetricsBase {
  self: {
    def log: {
      def info(s: String): Unit
      def debug(s: String): Unit
    }
  } =>

  def resetClusterThrottleMetric(clthrottle: v1alpha1.ClusterThrottle): Unit = {
    import com.github.everpeace.k8s.throttler.crd.v1alpha1.Implicits.ResourceAmountSyntax

    val zeroSpec = clthrottle.spec.copy(
      threshold = zeroResourceAmount(clthrottle.spec.threshold)
    )
    val zeroFalseStatus = clthrottle.status.map(
      st =>
        st.copy(
          throttled = falseIsResourceAmountThrottled(st.throttled),
          used = zeroResourceAmount(st.used),
          calculatedThreshold = Option(CalculatedThreshold(
            zeroResourceAmount(
              clthrottle.spec.temporaryThresholdOverrides.foldRight(clthrottle.spec.threshold) {
                (o, acc) =>
                  acc.merge(o.threshold)
              }
            ),
            st.calculatedThreshold.map(_.calculatedAt).getOrElse(java.time.ZonedDateTime.now())
          ))
      ))
    val zero = clthrottle.copy(
      spec = zeroSpec,
      status = zeroFalseStatus
    )
    recordClusterThrottleSpecMetric(zero)
    recordClusterThrottleStatusMetric(zero)
  }

  def recordClusterThrottleSpecMetric(clthrottle: v1alpha1.ClusterThrottle): Unit = {
    val metadataTags = metadataTagsForClusterThrottle(clthrottle)
    recordResourceAmountMetric("clusterthrottle.spec.threshold",
                               metadataTags,
                               clthrottle.spec.threshold)
  }

  def recordClusterThrottleStatusMetric(clthrottle: v1alpha1.ClusterThrottle): Unit = {
    val metadataTags = metadataTagsForClusterThrottle(clthrottle)
    clthrottle.status.foreach { status =>
      recordIsThrottledMetric("clusterthrottle.status.throttled", metadataTags, status.throttled)
      recordResourceAmountMetric("clusterthrottle.status.used", metadataTags, status.used)
      status.calculatedThreshold.foreach { calculated =>
        recordResourceAmountMetric("clusterthrottle.status.calculated.threshold",
                                   metadataTags,
                                   calculated.threshold)
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
