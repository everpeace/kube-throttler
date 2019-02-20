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

import com.github.everpeace.k8s.throttler.crd.v1alpha1.ClusterThrottle.{Selector, SelectorItem}
import com.github.everpeace.k8s.throttler.crd.v1alpha1.Implicits._
import com.github.everpeace.k8s.throttler.crd.v1alpha1._
import org.scalatest.{FreeSpec, Matchers}
import play.api.libs.json._
import skuber.LabelSelector
import skuber.LabelSelector.IsEqualRequirement
import skuber.Resource.Quantity
import java.time.ZonedDateTime

class ClusterThrottleFormatSpec extends FreeSpec with Matchers {

  "v1alpha1.ClusterThrottle" - {
    "yaml can be parsed to case class" in {
      val json = Json.parse(
        """|{
           |  "apiVersion": "schedule.k8s.everpeace.github.com/v1alpha1",
           |  "kind": "ClusterThrottle",
           |  "metadata": {
           |    "name": "app-throttle"
           |  },
           |  "spec": {
           |    "throttlerName": "kube-throttler",
           |    "selector": {
           |      "selectorTerms": [{
           |        "podSelector": {
           |          "matchLabels": {
           |            "key": "value"
           |          }
           |        }
           |      }]
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
           |    },
           |    "temporaryThresholdOverrides": [{
           |      "begin": "2019-02-01T00:00:00+09:00",
           |      "end": "2019-03-01T00:00:00+09:00",
           |      "threshold": {
           |        "resourceRequests": {
           |          "cpu": "20"
           |        }
           |      }
           |    }]
           |  },
           |  "status": {
           |    "throttled": {
           |      "resourceCounts": {
           |        "pod": false
           |      },
           |      "resourceRequests": {
           |        "cpu": false,
           |        "memory": false,
           |        "nvidia.com/gpu": true
           |      }
           |    },
           |    "used": {
           |      "resourceCounts": {
           |        "pod": 5
           |      },
           |      "resourceRequests": {
           |        "cpu": "12",
           |        "memory": "12Gi",
           |        "nvidia.com/gpu": "12"
           |      }
           |    },
           |    "calculatedThreshold":{
           |      "threshold": {
           |        "resourceCounts": {
           |          "pod": 10
           |        },
           |        "resourceRequests": {
           |          "cpu": "20",
           |          "memory": "15Gi",
           |          "nvidia.com/gpu": "10"
           |        }
           |      },
           |      "calculatedAt": "2019-02-01T00:00:00+09:00"
           |    }
           |  }
           |}
           |""".stripMargin
      )
      val obj = v1alpha1
        .ClusterThrottle(
          name = "app-throttle",
          spec = v1alpha1.ClusterThrottle.Spec(
            throttlerName = "kube-throttler",
            selector = Selector(
              selectorTerms = List(
                SelectorItem(
                  podSelector = LabelSelector(IsEqualRequirement("key", "value")),
                  namespaceSelector = None
                )
              )),
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
            ),
            temporaryThresholdOverrides = List(v1alpha1.TemporaryThresholdOverride(
              begin = ZonedDateTime.parse("2019-02-01T00:00:00+09:00"),
              end = ZonedDateTime.parse("2019-03-01T00:00:00+09:00"),
              threshold = ResourceAmount(
                resourceRequests = Map(
                  "cpu" -> Quantity("20")
                )
              )
            ))
          )
        )
        .withStatus(v1alpha1.ClusterThrottle.Status(
          throttled = IsResourceAmountThrottled(
            resourceCounts = Option(IsResourceCountThrottled(
              pod = Option(false)
            )),
            resourceRequests = Map(
              "cpu"            -> false,
              "memory"         -> false,
              "nvidia.com/gpu" -> true
            )
          ),
          used = ResourceAmount(
            resourceCounts = Option(ResourceCount(
              pod = Option(5)
            )),
            resourceRequests = Map(
              "cpu"            -> Quantity("12"),
              "memory"         -> Quantity("12Gi"),
              "nvidia.com/gpu" -> Quantity("12")
            )
          ),
          calculatedThreshold = Option(CalculatedThreshold(
            ResourceAmount(
              resourceCounts = Option(ResourceCount(
                pod = Option(10)
              )),
              resourceRequests = Map(
                "cpu"            -> Quantity("20"),
                "memory"         -> Quantity("15Gi"),
                "nvidia.com/gpu" -> Quantity("10")
              )
            ),
            java.time.ZonedDateTime.parse("2019-02-01T00:00:00+09:00")
          ))
        ))

      json.validate[v1alpha1.ClusterThrottle].get shouldBe obj
      Json.toJson(obj) shouldBe json
    }
  }
}
