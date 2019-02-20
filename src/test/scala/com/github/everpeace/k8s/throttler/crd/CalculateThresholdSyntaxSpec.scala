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
import com.github.everpeace.k8s.throttler.crd.v1alpha1.{
  ResourceAmount,
  ResourceCount,
  TemporaryThresholdOverride
}
import org.scalatest.{FreeSpec, Matchers}
import skuber.Resource.Quantity

class CalculateThresholdSyntaxSpec extends FreeSpec with Matchers {
  import v1alpha1.Implicits._

  val baseAmount = ResourceAmount(
    resourceCounts = Option(ResourceCount(pod = Option(1))),
    resourceRequests = Map(
      "r1" -> Quantity("1"),
      "r2" -> Quantity("2"),
      "r3" -> Quantity("3")
    )
  )

  val at = java.time.ZonedDateTime.parse("2019-03-01T00:00:00+09:00")

  def activeOverride(ra: ResourceAmount) = TemporaryThresholdOverride(
    begin = at.minusDays(1),
    end = at.plusDays(1),
    ra
  )
  def inactiveOverride(ra: ResourceAmount) = TemporaryThresholdOverride(
    begin = at.plusDays(1),
    end = at.plusDays(2),
    ra
  )

  "CalculateThresholdSyntax" - {

    "should pass through threshold when empty overrides" in {
      (baseAmount, List.empty).thresholdAt(at) shouldBe baseAmount
    }
    "should ignore inactive overrides" in {
      (baseAmount,
       List(
         inactiveOverride(
           ResourceAmount(
             resourceRequests = Map("r1" -> Quantity("10"))
           )))).thresholdAt(at) shouldBe baseAmount
    }

    "should merge active overrides per each resource types" in {
      val overrides = List(
        activeOverride(
          ResourceAmount(
            resourceRequests = Map("r1" -> Quantity("10"))
          )),
        activeOverride(
          ResourceAmount(
            resourceRequests = Map("r2" -> Quantity("20"))
          )),
        activeOverride(
          ResourceAmount(
            resourceRequests = Map("r3" -> Quantity("30"))
          )),
        activeOverride(
          ResourceAmount(
            resourceCounts = Option(ResourceCount(Option(2)))
          )),
        inactiveOverride(
          ResourceAmount(
            resourceRequests = Map("r1" -> Quantity("1000"))
          ))
      )

      (baseAmount, overrides).thresholdAt(at) shouldBe ResourceAmount(
        resourceCounts = Option(ResourceCount(pod = Option(2))),
        resourceRequests = Map(
          "r1" -> Quantity("10"),
          "r2" -> Quantity("20"),
          "r3" -> Quantity("30")
        )
      )
    }

    "should choose first active overrides when multiple are active" in {
      val overrides = List(
        activeOverride(
          ResourceAmount(
            resourceRequests = Map("r1" -> Quantity("10"))
          )),
        activeOverride(
          ResourceAmount(
            resourceRequests = Map("r1" -> Quantity("100"), "r2" -> Quantity("20"))
          ))
      )

      (baseAmount, overrides).thresholdAt(at) shouldBe ResourceAmount(
        resourceCounts = Option(ResourceCount(pod = Option(1))),
        resourceRequests = Map(
          "r1" -> Quantity("10"),
          "r2" -> Quantity("20"),
          "r3" -> Quantity("3")
        )
      )
    }
  }
}
