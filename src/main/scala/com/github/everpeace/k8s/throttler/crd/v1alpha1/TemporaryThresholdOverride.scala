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

import scala.util.Try

case class TemporaryThresholdOverride(
    beginString: String,
    begin: skuber.Timestamp,
    endString: String,
    end: skuber.Timestamp,
    threshold: ResourceAmount,
    parseError: Option[String])

object TemporaryThresholdOverride {
  import java.time.ZonedDateTime
  import java.time.format.DateTimeFormatter.{ISO_OFFSET_DATE_TIME, ISO_DATE_TIME}

  private def parse(str: String): Try[ZonedDateTime] =
    Try(ZonedDateTime.parse(str, ISO_DATE_TIME))
  private def format(zt: ZonedDateTime): String = zt.format(ISO_OFFSET_DATE_TIME)
  private def errorMessage(t: Try[ZonedDateTime]): Option[String] =
    t.failed.toOption.map(th => s"${th.getMessage()}")

  def apply(
      beginString: String,
      endString: String,
      threshold: ResourceAmount
    ): TemporaryThresholdOverride = {
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
    ): TemporaryThresholdOverride = {
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

  trait JsonFormat extends ResourceAmount.JsonFormat {
    import play.api.libs.json._
    import play.api.libs.functional.syntax._

    implicit val temporaryThrottleOverrideFmt: Format[TemporaryThresholdOverride] = (
      (JsPath \ "begin").format[String] and
        (JsPath \ "end").format[String] and
        (JsPath \ "threshold").format[ResourceAmount]
    )(TemporaryThresholdOverride.apply, unlift(TemporaryThresholdOverride.unapply))

  }

  trait Syntax {
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
}
