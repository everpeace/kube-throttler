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

case class CalculatedThreshold(
    threshold: ResourceAmount,
    calculatedAt: skuber.Timestamp,
    messages: List[String] = List.empty)

object CalculatedThreshold {
  trait JsonFormat extends ResourceAmount.JsonFormat {
    import play.api.libs.json._
    import play.api.libs.functional.syntax._
    import skuber.json.format.{timeReads, timewWrites, maybeEmptyFormatMethods}

    implicit val calculatedThresholdFmt: Format[CalculatedThreshold] = (
      (JsPath \ "threshold").format[ResourceAmount] and
        (JsPath \ "calculatedAt").format[skuber.Timestamp] and
        (JsPath \ "messages").formatMaybeEmptyList[String]
    )(CalculatedThreshold.apply, unlift(CalculatedThreshold.unapply))
  }

  trait Syntax extends TemporaryThresholdOverride.Syntax with ResourceAmount.Syntax {
    implicit class CalculatedThresholdSyntax(
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
  }
}
