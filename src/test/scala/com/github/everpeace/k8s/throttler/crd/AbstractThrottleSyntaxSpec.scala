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

import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

import com.github.everpeace.k8s.throttler.crd.v1alpha1.ResourceAmount
import org.scalatest.{Matchers, WordSpec}

class AbstractThrottleSyntaxSpec extends WordSpec with Matchers {
  import v1alpha1.Implicits._

  def st(calculatedAt: Option[skuber.Timestamp]) =
    Option(
      v1alpha1.Throttle.Status(
        throttled = v1alpha1.IsResourceAmountThrottled(),
        used = v1alpha1.ResourceAmount(),
        calculatedThreshold = calculatedAt.map(
          at =>
            v1alpha1.CalculatedThreshold(
              calculatedAt = at,
              threshold = ResourceAmount(),
              messages = List()
          ))
      ))

  def ovr(end: skuber.Timestamp): List[v1alpha1.TemporaryThresholdOverride] = List(
    v1alpha1.TemporaryThresholdOverride(
      begin = end.minus(1, ChronoUnit.DAYS),
      end = end,
      threshold = ResourceAmount()
    )
  )

  def thr(
      overrides: List[v1alpha1.TemporaryThresholdOverride] = List.empty,
      status: Option[v1alpha1.Throttle.Status] = None
    ): v1alpha1.Throttle = {
    val t = v1alpha1
      .Throttle(
        "t1",
        v1alpha1.Throttle.Spec(
          throttlerName = "kube-throttler",
          selector = v1alpha1.Throttle.Selector(List()),
          threshold = ResourceAmount(resourceRequests = Map()),
          temporaryThresholdOverrides = overrides
        )
      )
      .withNamespace("default")
      .withName("t1")

    status.map(s => t.withStatus(s)).getOrElse(t)
  }

  "hasTemporaryOverridesToReconcile" should {
    "return false if no temporaryOverrides and no status" in {
      val t = thr()
      t.hasTemporaryOverridesToReconcile shouldBe false
    }

    "return false if no temporaryOverrides and some status without calculatedAt" in {
      val t = thr(status = st(None))
      t.hasTemporaryOverridesToReconcile shouldBe false
    }

    "return false if no temporaryOverrides and some status and some calculatedAt " in {
      val t = thr(status = st(Option(ZonedDateTime.now())))
      t.hasTemporaryOverridesToReconcile shouldBe false
    }

    "return true if some temporaryOverrides and no status" in {
      val t = thr(overrides = ovr(ZonedDateTime.now()))
      t.hasTemporaryOverridesToReconcile shouldBe true
    }

    "return true if some temporaryOverrides and some status without calculatedAt" in {
      val t = thr(overrides = ovr(ZonedDateTime.now()), status = st(None))
      t.hasTemporaryOverridesToReconcile shouldBe true
    }

    "return false if all temporaryOverrides' end is before calculatedAt" in {
      val end = ZonedDateTime.now()
      val t = thr(
        overrides = ovr(end),
        status = st(Option(end.plus(1, ChronoUnit.MINUTES)))
      )
      t.hasTemporaryOverridesToReconcile shouldBe false
    }

    "return true if all temporaryOverrides' end is equal calculatedAt" in {
      val end = ZonedDateTime.now()
      val t = thr(
        overrides = ovr(end),
        status = st(Option(end))
      )
      t.hasTemporaryOverridesToReconcile shouldBe true
    }

    "return false if some temporaryOverrides' end is after calculatedAt" in {
      val end = ZonedDateTime.now()
      val t = thr(
        overrides = ovr(end),
        status = st(Option(end.minus(1, ChronoUnit.MINUTES)))
      )
      t.hasTemporaryOverridesToReconcile shouldBe true
    }
  }
}
