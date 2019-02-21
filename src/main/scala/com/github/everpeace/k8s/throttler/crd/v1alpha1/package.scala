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

package com.github.everpeace.k8s.throttler.crd

import java.time.{Instant, ZoneOffset, ZonedDateTime}
import skuber.{CustomResource, ListResource, Pod}
import com.github.everpeace.util.Injection._

package object v1alpha1 {
  type Throttle     = CustomResource[v1alpha1.Throttle.Spec, v1alpha1.Throttle.Status]
  type ThrottleList = ListResource[Throttle]

  type ClusterThrottle =
    CustomResource[v1alpha1.ClusterThrottle.Spec, v1alpha1.ClusterThrottle.Status]
  type ClusterThrottleList = ListResource[ClusterThrottle]

  val epoch = ZonedDateTime.ofInstant(Instant.ofEpochSecond(0), ZoneOffset.UTC)

  trait Spec[Selector] {
    def throttlerName: String
    def selector: Selector
    def threshold: ResourceAmount
    def temporaryThresholdOverrides: List[TemporaryThresholdOverride]
  }

  trait Status {
    def throttled: IsResourceAmountThrottled
    def used: ResourceAmount
    def calculatedThreshold: Option[CalculatedThreshold]
  }

  trait JsonFormat
      extends v1alpha1.Throttle.JsonFormat
      with v1alpha1.ClusterThrottle.JsonFormats
      with CalculatedThreshold.JsonFormat
      with TemporaryThresholdOverride.JsonFormat
      with ResourceAmount.JsonFormat

  trait Syntax
      extends v1alpha1.Throttle.Syntax
      with v1alpha1.ClusterThrottle.Syntax
      with CalculatedThreshold.Syntax
      with TemporaryThresholdOverride.Syntax
      with ResourceAmount.Syntax

  trait ResourceDefinitions
      extends v1alpha1.Throttle.ResourceDefinitions
      with v1alpha1.ClusterThrottle.ResourceDefinitions

  object Implicits extends JsonFormat with Syntax with ResourceDefinitions {

    implicit class AbstractThrottleSyntax(abstThr: CustomResource[_ <: Spec[_], _ <: Status]) {
      def isAlreadyActiveFor(pod: Pod, isTarget: => Boolean): Boolean = isTarget && {
        (for {
          st        <- abstThr.status
          throttled = st.throttled
        } yield throttled.isAlreadyThrottled(pod)).getOrElse(false)
      }

      def isInsufficientFor(pod: Pod, isTarget: => Boolean): Boolean = isTarget && {
        val podResourceAmount = pod.==>[ResourceAmount]
        val used              = abstThr.status.map(_.used).getOrElse(zeroResourceAmount)
        val threshold = (for {
          st         <- abstThr.status
          calculated <- st.calculatedThreshold
        } yield calculated.threshold).getOrElse(abstThr.spec.threshold)
        val isThrottled =
          (podResourceAmount add used).isThrottledFor(threshold, isThrottledOnEqual = false)
        isThrottled.resourceCounts.flatMap(_.pod).getOrElse(false) || isThrottled.resourceRequests
          .exists(_._2)
      }
    }

    implicit class SpecSyntax[S](spec: Spec[S]) {
      def statusFor[ST <: Status](
          used: ResourceAmount,
          at: skuber.Timestamp
        )(apply: (IsResourceAmountThrottled, ResourceAmount, Option[CalculatedThreshold]) => ST
        ): ST = {
        val calculated = spec.thresholdAt(at)
        apply(
          used.isThrottledFor(calculated, isThrottledOnEqual = true),
          used.filterEffectiveOn(spec.threshold),
          Option(
            CalculatedThreshold(calculated,
                                at,
                                spec.temporaryThresholdOverrides.collectParseError())
          )
        )
      }

      def thresholdAt(at: skuber.Timestamp): ResourceAmount =
        (spec.threshold, spec.temporaryThresholdOverrides).thresholdAt(at)
    }

    implicit class StatusSyntax(observedOpt: Option[Status]) {
      def needToUpdateWith(desired: Status): Boolean =
        if (observedOpt.isEmpty) {
          true
        } else {
          val observedStatus = observedOpt.get
          lazy val used      = observedStatus.used != desired.used
          lazy val throttled = observedStatus.throttled != desired.throttled
          lazy val threshold =
            (observedStatus.calculatedThreshold, desired.calculatedThreshold) match {
              case (None, None)    => false
              case (None, Some(_)) => true
              case (Some(_), None) => true
              case (Some(o), Some(d)) =>
                o.copy(calculatedAt = epoch) != d.copy(calculatedAt = epoch)
            }
          used || throttled || threshold
        }
    }
  }
}
