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

import scala.util.Try

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

  case class TemporaryThresholdOverride(
      beginString: String,
      begin: skuber.Timestamp,
      endString: String,
      end: skuber.Timestamp,
      threshold: ResourceAmount,
      parseError: Option[String])

  object TemporaryThresholdOverride {
    import java.time.{ZonedDateTime, Instant, ZoneOffset}
    import java.time.format.DateTimeFormatter.{ISO_OFFSET_DATE_TIME, ISO_DATE_TIME}

    private val epoch = ZonedDateTime.ofInstant(Instant.ofEpochSecond(0), ZoneOffset.UTC)
    private def parse(str: String): Try[ZonedDateTime] =
      Try(ZonedDateTime.parse(str, ISO_DATE_TIME))
    private def format(zt: ZonedDateTime): String = zt.format(ISO_OFFSET_DATE_TIME)
    private def errorMessage(t: Try[ZonedDateTime]): Option[String] =
      t.failed.toOption.map(th => s"${th.getMessage()}")

    def apply(
        beginString: String,
        endString: String,
        threshold: ResourceAmount
      ): v1alpha1.TemporaryThresholdOverride = {
      val parsedBegin = parse(beginString)
      val parsedEnd   = parse(endString)
      val message = {
        val beginMesssage = errorMessage(parsedBegin).map(msg => s"begin: $msg")
        val endMessage    = errorMessage(parsedEnd).map(msg => s"end: $msg")
        val combined      = List(beginMesssage, endMessage).filter(_.nonEmpty).map(_.get).mkString(", ")
        Option(combined).filter(_.trim.nonEmpty)
      }

      new TemporaryThresholdOverride(
        beginString,
        parsedBegin.getOrElse(epoch),
        endString,
        parsedEnd.getOrElse(epoch),
        threshold,
        message
      )
    }

    def apply(
        begin: skuber.Timestamp,
        end: skuber.Timestamp,
        threshold: ResourceAmount
      ): v1alpha1.TemporaryThresholdOverride = {
      new TemporaryThresholdOverride(
        format(begin),
        begin,
        format(end),
        end,
        threshold,
        None
      )
    }

    def unapply(arg: TemporaryThresholdOverride): Option[(String, String, ResourceAmount)] =
      Option(arg.beginString, arg.endString, arg.threshold)
  }

  case class CalculatedThreshold(
      threshold: ResourceAmount,
      calculatedAt: skuber.Timestamp,
      messages: List[String] = List.empty)

  case class IsResourceCountThrottled(pod: Option[Boolean] = None)

  case class IsResourceAmountThrottled(
      resourceCounts: Option[IsResourceCountThrottled] = None,
      resourceRequests: Map[String, Boolean] = Map.empty)

  trait CommonJsonFormat {
    import play.api.libs.json._
    import play.api.libs.functional.syntax._
    import skuber.json.format.{quantityFormat, timeReads, timewWrites, maybeEmptyFormatMethods}

    implicit val resourceCountsFmt: Format[v1alpha1.ResourceCount] =
      Json.format[v1alpha1.ResourceCount]

    implicit val resourceAmountFmt: Format[v1alpha1.ResourceAmount] = (
      (JsPath \ "resourceCounts").formatNullable[ResourceCount] and
        (JsPath \ "resourceRequests").formatMaybeEmptyMap[skuber.Resource.Quantity]
    )(ResourceAmount.apply, unlift(ResourceAmount.unapply))

    implicit val temporaryThrottleOverrideFmt: Format[v1alpha1.TemporaryThresholdOverride] = (
      (JsPath \ "begin").format[String] and
        (JsPath \ "end").format[String] and
        (JsPath \ "threshold").format[ResourceAmount]
    )(TemporaryThresholdOverride.apply, unlift(TemporaryThresholdOverride.unapply))

    implicit val calculatedThresholdFmt: Format[v1alpha1.CalculatedThreshold] = (
      (JsPath \ "threshold").format[ResourceAmount] and
        (JsPath \ "calculatedAt").format[skuber.Timestamp] and
        (JsPath \ "messages").formatMaybeEmptyList[String]
    )(v1alpha1.CalculatedThreshold.apply, unlift(v1alpha1.CalculatedThreshold.unapply))

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

    implicit class CalculateThresholdSyntax(
        tup: (ResourceAmount, List[TemporaryThresholdOverride])) {
      def thresholdAt(at: skuber.Timestamp): ResourceAmount = {
        val threshold = tup._1
        val overrides = tup._2
        overrides.foldRight(threshold) { (thresholdOverride, calculated) =>
          if (thresholdOverride.isActiveAt(at)) {
            calculated.merge(thresholdOverride.threshold)
          } else {
            calculated
          }
        }
      }
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

      def merge(rb: ResourceAmount): ResourceAmount = {
        val mergedResoureCounts = rb.resourceCounts.orElse(ra.resourceCounts)
        val mergedResourceRequests = rb.resourceRequests.foldLeft(ra.resourceRequests) {
          (merged, req) =>
            merged + req
        }
        ResourceAmount(
          resourceCounts = mergedResoureCounts,
          resourceRequests = mergedResourceRequests
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

    implicit class TemporaryThresholdOverridesSyntax(ovrds: List[TemporaryThresholdOverride]) {
      def collectParseError(): List[String] = {
        ovrds.zipWithIndex.foldRight(List.empty[String]) { (ovrd, msgs) =>
          if (ovrd._1.parseError.nonEmpty) {
            s"[${ovrd._2}]: ${ovrd._1.parseError.get}" :: msgs
          } else {
            msgs
          }
        }
      }
    }

    implicit class TemporaryThresholdOverrideSyntax(ovrd: TemporaryThresholdOverride) {
      def isActiveAt(at: skuber.Timestamp): Boolean =
        ovrd.parseError.isEmpty &&
          (ovrd.begin.isEqual(at) || ovrd.begin.isBefore(at)) &&
          (at.isEqual(ovrd.end) || at.isBefore(ovrd.end))
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
