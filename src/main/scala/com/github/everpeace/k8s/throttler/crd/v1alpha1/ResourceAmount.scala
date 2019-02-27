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

package com.github.everpeace.k8s.throttler.crd.v1alpha1

import skuber.Resource.ResourceList
import com.github.everpeace.k8s._
import com.github.everpeace.util.Injection._
import skuber.Pod
import cats.syntax.order._

case class ResourceAmount(
    resourceCounts: Option[ResourceCount] = None,
    resourceRequests: ResourceList = Map.empty)

case class ResourceCount(pod: Option[Int] = None)

case class IsResourceCountThrottled(pod: Option[Boolean] = None)

case class IsResourceAmountThrottled(
    resourceCounts: Option[IsResourceCountThrottled] = None,
    resourceRequests: Map[String, Boolean] = Map.empty)

object ResourceAmount {
  trait JsonFormat {
    import play.api.libs.json._
    import play.api.libs.functional.syntax._
    import skuber.json.format.{quantityFormat, maybeEmptyFormatMethods}

    implicit val resourceCountsFmt: Format[ResourceCount] =
      Json.format[ResourceCount]

    implicit val resourceAmountFmt: Format[ResourceAmount] = (
      (JsPath \ "resourceCounts").formatNullable[ResourceCount] and
        (JsPath \ "resourceRequests").formatMaybeEmptyMap[skuber.Resource.Quantity]
    )(ResourceAmount.apply, unlift(ResourceAmount.unapply))

    implicit val isResourceCountThrottledFmt: Format[IsResourceCountThrottled] =
      Json.format[IsResourceCountThrottled]

    implicit val isResourceThrottledFmt: Format[IsResourceAmountThrottled] =
      Json.format[IsResourceAmountThrottled]
  }

  trait Syntax {
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
        IsResourceAmountThrottled(
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

      def merge(rb: ResourceAmount): ResourceAmount = {
        val mergedResourceCounts = rb.resourceCounts.orElse(ra.resourceCounts)
        val mergedResourceRequests = rb.resourceRequests.foldLeft(ra.resourceRequests) {
          (merged, req) =>
            merged + req
        }
        ResourceAmount(
          resourceCounts = mergedResourceCounts,
          resourceRequests = mergedResourceRequests
        )
      }

      def filterEffectiveOn(threshold: ResourceAmount): ResourceAmount = {
        val used = ra
        ResourceAmount(
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
}
