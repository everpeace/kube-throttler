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
import play.api.libs.json.Json

import java.time.{ZonedDateTime, Instant, ZoneOffset}

class TemporaryThresholdOverrideSpec extends FreeSpec with Matchers with v1alpha1.CommonJsonFormat {
  val amount = ResourceAmount(resourceCounts = Option(ResourceCount(Option(1))))
  val epoch  = ZonedDateTime.ofInstant(Instant.ofEpochSecond(0), ZoneOffset.UTC)

  "TemporaryThresholdOverrideFormat" - {
    "should parse ISO-like (RFC3339-like) timestamp" in {
      val json = Json.parse("""
          |{
          |  "begin": "2019-02-01T00:00:00+09:00",
          |  "end": "2019-02-01T00:00:00+09:00",
          |  "threshold": {
          |    "resourceCounts": {
          |      "pod": 1
          |    }
          |  }
          |}
        """.stripMargin)
      val obj = new TemporaryThresholdOverride(
        "2019-02-01T00:00:00+09:00",
        ZonedDateTime.parse("2019-02-01T00:00:00+09:00"),
        "2019-02-01T00:00:00+09:00",
        ZonedDateTime.parse("2019-02-01T00:00:00+09:00"),
        amount,
        None
      )

      json.validate[TemporaryThresholdOverride].get shouldBe obj
      Json.toJson(obj) shouldBe json
    }

    "should fallback to epoch when parse failed" in {
      val json = Json.parse("""
          |{
          |  "begin": "malformed time",
          |  "end": "malformed time",
          |  "threshold": {
          |    "resourceCounts": {
          |      "pod": 1
          |    }
          |  }
          |}
        """.stripMargin)
      val obj = new TemporaryThresholdOverride(
        "malformed time",
        epoch,
        "malformed time",
        epoch,
        amount,
        Option(
          "begin: Text 'malformed time' could not be parsed at index 0, end: Text 'malformed time' could not be parsed at index 0")
      )

      json.validate[TemporaryThresholdOverride].get shouldBe obj
      Json.toJson(obj) shouldBe json
    }

    "TemporaryThresholdOverrideSyntax" - {
      import v1alpha1.Implicits._

      "isActiveAt should not return active at anytime when parse error" in {
        val ovrd = TemporaryThresholdOverride("malformed", "2019-02-01T00:00:00+09:00", amount)

        ovrd.begin shouldBe epoch
        ovrd.end shouldBe java.time.ZonedDateTime.parse("2019-02-01T00:00:00+09:00")
        ovrd.parseError shouldBe Option("begin: Text 'malformed' could not be parsed at index 0")
        ovrd.isActiveAt(epoch) shouldBe false
      }

      "isActiveAt should return active when at in [begin,end]" in {
        val ovrd =
          TemporaryThresholdOverride("2019-02-01T00:00:00+09:00",
                                     "2019-02-01T00:01:00+09:00",
                                     amount)

        ovrd.isActiveAt(java.time.ZonedDateTime.parse("2019-01-31T23:59:59+09:00")) shouldBe false
        ovrd.isActiveAt(java.time.ZonedDateTime.parse("2019-02-01T00:00:00+09:00")) shouldBe true
        ovrd.isActiveAt(java.time.ZonedDateTime.parse("2019-02-01T00:00:30+09:00")) shouldBe true
        ovrd.isActiveAt(java.time.ZonedDateTime.parse("2019-02-01T00:01:00+09:00")) shouldBe true
        ovrd.isActiveAt(java.time.ZonedDateTime.parse("2019-02-01T00:01:01+09:00")) shouldBe false
      }

      "collectParseError should collect parse error string" in {
        val ovrdsNg = List(
          TemporaryThresholdOverride("malformed", "2019-02-01T00:00:00+09:00", amount),
          TemporaryThresholdOverride("2019-02-01T00:00:00+09:00",
                                     "2019-02-01T00:00:00+09:00",
                                     amount),
          TemporaryThresholdOverride("2019-02-01T00:00:00+09:00", "malformed", amount),
          TemporaryThresholdOverride("malformed", "malformed", amount)
        )
        ovrdsNg.collectParseError() shouldBe List(
          "[0]: begin: Text 'malformed' could not be parsed at index 0",
          "[2]: end: Text 'malformed' could not be parsed at index 0",
          "[3]: begin: Text 'malformed' could not be parsed at index 0, end: Text 'malformed' could not be parsed at index 0",
        )

        val ovrdOk = List(
          TemporaryThresholdOverride("2019-02-01T00:00:00+09:00",
                                     "2019-02-01T00:00:00+09:00",
                                     amount),
          TemporaryThresholdOverride("2019-02-01T00:00:00+09:00",
                                     "2019-02-01T00:00:00+09:00",
                                     amount)
        )
        ovrdOk.collectParseError() shouldBe List.empty
      }
    }
  }
}
