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

trait ThrottleControllerMetrics extends MetricsBase {
  self: {
    def log: {
      def info(s: String): Unit
      def debug(s: String): Unit
    }
  } =>

  def resetThrottleMetric(throttle: v1alpha1.Throttle): Unit = {
    import com.github.everpeace.k8s.throttler.crd.v1alpha1.Implicits.ResourceAmountSyntax

    val zeroSpec = throttle.spec.copy(
      threshold = zeroResourceAmount(throttle.spec.threshold)
    )
    val zeroFalseStatus = throttle.status.map(
      st =>
        st.copy(
          throttled = falseIsResourceAmountThrottled(st.throttled),
          used = zeroResourceAmount(st.used),
          calculatedThreshold = Option(CalculatedThreshold(
            zeroResourceAmount(
              throttle.spec.temporaryThresholdOverrides.foldRight(throttle.spec.threshold) {
                (o, acc) =>
                  acc.merge(o.threshold)
              }
            ),
            st.calculatedThreshold.map(_.calculatedAt).getOrElse(java.time.ZonedDateTime.now())
          ))
      ))
    val zero = throttle.copy(
      spec = zeroSpec,
      status = zeroFalseStatus
    )
    recordThrottleSpecMetric(zero)
    recordThrottleStatusMetric(zero)
  }

  def recordThrottleSpecMetric(throttle: v1alpha1.Throttle): Unit = {
    val metadataTags = metadataTagsForThrottle(throttle)
    recordResourceAmountMetric("throttle.spec.threshold", metadataTags, throttle.spec.threshold)
  }

  def recordThrottleStatusMetric(throttle: v1alpha1.Throttle): Unit = {
    val metadataTags = metadataTagsForThrottle(throttle)
    throttle.status.foreach { status =>
      recordIsThrottledMetric("throttle.status.throttled", metadataTags, status.throttled)
      recordResourceAmountMetric("throttle.status.used", metadataTags, status.used)
      status.calculatedThreshold.foreach { calculated =>
        recordResourceAmountMetric("throttle.status.calculated.threshold",
                                   metadataTags,
                                   calculated.threshold)
      }
    }
  }

  def metadataTagsForThrottle(throttle: v1alpha1.Throttle): kamon.Tags = {
    val metadata = List(
      "name"      -> throttle.metadata.name,
      "namespace" -> throttle.metadata.namespace,
      "uuid"      -> throttle.metadata.uid
    ).toMap ++ throttle.metadata.clusterName.map(cn => Map("cluster" -> cn)).getOrElse(Map.empty)

    sanitizeTagKeys(metadata ++ throttle.metadata.labels)
  }

}
