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

import com.github.everpeace.k8s.throttler.crd.v1alpha1.Implicits._
import com.github.everpeace.k8s.throttler.crd.v1alpha1.{
  IsResourceAmountThrottled,
  IsResourceCountThrottled,
  ResourceAmount,
  ResourceCount
}
import org.scalatest.{FreeSpec, Matchers}
import play.api.libs.json._
import skuber.LabelSelector
import skuber.LabelSelector.IsEqualRequirement
import skuber.Resource.Quantity

class ThrottleFormatSpec extends FreeSpec with Matchers {

  "v1alpha1.Throttle" - {
    "yaml can be parsed to case class" in {
      val json = Json.parse(
        """|{
           |  "apiVersion": "schedule.k8s.everpeace.github.com/v1alpha1",
           |  "kind": "Throttle",
           |  "metadata": {
           |    "namespace": "default",
           |    "name": "app-throttle"
           |  },
           |  "spec": {
           |    "throttlerName": "kube-throttler",
           |    "selector": {
           |      "matchLabels": {
           |        "key": "value"
           |      }
           |    },
           |    "threshold": {
           |      "resourceCounts": {
           |        "pod": 10
           |      },
           |      "resourceRequests": {
           |        "cpu": "10",
           |        "memory": "15Gi",
           |        "nvidia.com/gpu": "10"
           |      }
           |    }
           |  },
           |  "status": {
           |    "throttled": {
           |      "resourceCounts": {
           |        "pod": true
           |      },
           |      "resourceRequests": {
           |        "cpu": true,
           |        "memory": false,
           |        "nvidia.com/gpu": true
           |      }
           |    },
           |    "used": {
           |      "resourceCounts": {
           |        "pod": 12
           |      },
           |      "resourceRequests": {
           |        "cpu": "12",
           |        "memory": "12Gi",
           |        "nvidia.com/gpu": "12"
           |      }
           |    }
           |  }
           |}
           |""".stripMargin
      )
      val obj = v1alpha1
        .Throttle(
          name = "app-throttle",
          spec = v1alpha1.Throttle.Spec(
            throttlerName = "kube-throttler",
            selector = LabelSelector(IsEqualRequirement("key", "value")),
            threshold = ResourceAmount(
              resourceCounts = Option(
                ResourceCount(
                  pod = Option(10)
                )),
              resourceRequests = Map(
                "cpu"            -> Quantity("10"),
                "memory"         -> Quantity("15Gi"),
                "nvidia.com/gpu" -> Quantity("10")
              )
            )
          )
        )
        .withNamespace("default")
        .withStatus(v1alpha1.Throttle.Status(
          throttled = IsResourceAmountThrottled(
            resourceCounts = Option(IsResourceCountThrottled(
              pod = Option(true)
            )),
            resourceRequests = Map(
              "cpu"            -> true,
              "memory"         -> false,
              "nvidia.com/gpu" -> true
            )
          ),
          used = ResourceAmount(
            resourceCounts = Option(ResourceCount(
              pod = Option(12)
            )),
            resourceRequests = Map(
              "cpu"            -> Quantity("12"),
              "memory"         -> Quantity("12Gi"),
              "nvidia.com/gpu" -> Quantity("12")
            )
          )
        ))

      json.validate[v1alpha1.Throttle].get shouldBe obj
      Json.toJson(obj) shouldBe json
    }
  }
}
