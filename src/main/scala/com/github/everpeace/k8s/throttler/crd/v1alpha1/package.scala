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

import cats.syntax.order._
import com.github.everpeace.k8s._
import com.github.everpeace.util.Injection.{==>, _}
import skuber.Resource.ResourceList
import skuber.{CustomResource, ListResource, Pod}

package object v1alpha1 {
  type Throttle     = CustomResource[v1alpha1.Throttle.Spec, v1alpha1.Throttle.Status]
  type ThrottleList = ListResource[Throttle]

  type ClusterThrottle =
    CustomResource[v1alpha1.ClusterThrottle.Spec, v1alpha1.ClusterThrottle.Status]
  type ClusterThrottleList = ListResource[ClusterThrottle]

  case class ResourceAmount(
      resourceCounts: Option[ResourceCount] = None,
      resourceRequests: ResourceList = Map.empty)

  case class ResourceCount(pod: Option[Int] = None)

  case class TemporalThresholdOverride(
      begin: skuber.Timestamp,
      end: skuber.Timestamp,
      threshold: ResourceAmount)

  case class IsResourceCountThrottled(pod: Option[Boolean] = None)

  case class IsResourceAmountThrottled(
      resourceCounts: Option[IsResourceCountThrottled] = None,
      resourceRequests: Map[String, Boolean] = Map.empty)

  trait CommonJsonFormat {
    import play.api.libs.functional.syntax._
    import play.api.libs.json._
    import skuber.json.format.{quantityFormat, timeReads, timewWrites}

    implicit val resourceCountsFmt: Format[v1alpha1.ResourceCount] =
      Json.format[v1alpha1.ResourceCount]

    implicit val resourceAmountFmt: Format[v1alpha1.ResourceAmount] =
      Json.format[v1alpha1.ResourceAmount]

    implicit val temporalThrottleOverrideFmt: Format[v1alpha1.TemporalThresholdOverride] = (
      (JsPath \ "begin").format[skuber.Timestamp] and
        (JsPath \ "end").format[skuber.Timestamp] and
        (JsPath \ "threshold").format[v1alpha1.ResourceAmount]
    )(v1alpha1.TemporalThresholdOverride.apply, unlift(v1alpha1.TemporalThresholdOverride.unapply))

    implicit val isResourceCountThrottledFmt: Format[v1alpha1.IsResourceCountThrottled] =
      Json.format[v1alpha1.IsResourceCountThrottled]

    implicit val isResourceThrottledFmt: Format[v1alpha1.IsResourceAmountThrottled] =
      Json.format[v1alpha1.IsResourceAmountThrottled]
  }

  trait CommonSyntax {
    implicit val podCanBeResourceAmount: Pod ==> ResourceAmount = new ==>[Pod, ResourceAmount] {
      override def to: Pod => ResourceAmount =
        pod =>
          ResourceAmount(
            resourceCounts = Option(ResourceCount(pod = Option(1))),
            resourceRequests = pod.totalRequests
        )
    }

    implicit class IsResourceAmountThrottledSyntax(throttled: IsResourceAmountThrottled) {
      def isAlreadyThrottled(pod: Pod): Boolean = {
        val podAmount           = pod.==>[ResourceAmount]
        val isPodCountThrottled = throttled.resourceCounts.flatMap(_.pod).getOrElse(false)
        val isSomePodRequestedResourceThrottled = throttled.resourceRequests
          .filterKeys(key => podAmount.resourceRequests.contains(key))
          .exists(_._2)
        isPodCountThrottled || isSomePodRequestedResourceThrottled
      }
    }

    implicit class ResourceAmountSyntax(ra: ResourceAmount) {
      def isThrottledFor(
          threshold: ResourceAmount,
          isThrottledOnEqual: Boolean = true
        ): IsResourceAmountThrottled = {
        val used = ra
        v1alpha1.IsResourceAmountThrottled(
          resourceCounts = for {
            rc <- threshold.resourceCounts
            th <- rc.pod
            c  <- used.resourceCounts.flatMap(_.pod).orElse(Option(0))
          } yield
            IsResourceCountThrottled(
              pod = Option(
                if (isThrottledOnEqual) th <= c else th < c
              )),
          resourceRequests = threshold.resourceRequests.keys.map { resource =>
            if (used.resourceRequests.contains(resource)) {
              resource -> (if (isThrottledOnEqual) {
                             threshold.resourceRequests(resource) <= used.resourceRequests(resource)
                           } else {
                             threshold.resourceRequests(resource) < used.resourceRequests(resource)
                           })
            } else {
              resource -> false
            }
          }.toMap
        )
      }

      def filterEffectiveOn(threshold: ResourceAmount): ResourceAmount = {
        val used = ra
        v1alpha1.ResourceAmount(
          resourceCounts = for {
            rc  <- threshold.resourceCounts
            urc <- used.resourceCounts
          } yield {
            val pc = for {
              _ <- rc.pod
              p <- urc.pod
            } yield p
            ResourceCount(pod = pc)
          },
          resourceRequests = used.resourceRequests.filterKeys(threshold.resourceRequests.contains)
        )
      }
    }
  }

  object Implicits
      extends v1alpha1.Throttle.Implicits
      with v1alpha1.ClusterThrottle.Implicits
      with CommonJsonFormat
      with CommonSyntax {

    val zeroResourceCount = ResourceCount()
    implicit class ResourceCountAddition(rc: ResourceCount) {
      def add(rc2: ResourceCount) = ResourceCount(
        pod = for {
          i <- rc.pod.orElse(Option(0))
          j <- rc2.pod
        } yield i + j
      )
    }

    val zeroResourceAmount = ResourceAmount()
    implicit class ResourceAmountAddition(ra: ResourceAmount) {
      def add(ra2: ResourceAmount) = ResourceAmount(
        resourceCounts = for {
          a <- ra.resourceCounts.orElse(Option(zeroResourceCount))
          b <- ra2.resourceCounts
        } yield a add b,
        resourceRequests = ra.resourceRequests add ra2.resourceRequests
      )
    }
  }

}
