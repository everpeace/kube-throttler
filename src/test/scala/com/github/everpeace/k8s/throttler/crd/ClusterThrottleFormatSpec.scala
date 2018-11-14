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
import org.scalatest.{FreeSpec, Matchers}
import play.api.libs.json._
import skuber.LabelSelector
import skuber.LabelSelector.IsEqualRequirement
import skuber.Resource.Quantity

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
           |      "matchLabels": {
           |        "key": "value"
           |      }
           |    },
           |    "threshold": {
           |      "cpu": "10",
           |      "memory": "15Gi",
           |      "nvidia.com/gpu": "10"
           |    }
           |  },
           |  "status": {
           |    "throttled": {
           |      "cpu": true,
           |      "memory": false,
           |      "nvidia.com/gpu": true
           |    },
           |    "used": {
           |      "cpu": "12",
           |      "memory": "12Gi",
           |      "nvidia.com/gpu": "12"
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
            selector = LabelSelector(IsEqualRequirement("key", "value")),
            threshold = Map(
              "cpu"            -> Quantity("10"),
              "memory"         -> Quantity("15Gi"),
              "nvidia.com/gpu" -> Quantity("10")
            )
          )
        )
        .withStatus(v1alpha1.ClusterThrottle.Status(
          throttled = Map(
            "cpu"            -> true,
            "memory"         -> false,
            "nvidia.com/gpu" -> true
          ),
          used = Map(
            "cpu"            -> Quantity("12"),
            "memory"         -> Quantity("12Gi"),
            "nvidia.com/gpu" -> Quantity("12")
          )
        ))

      json.validate[v1alpha1.ClusterThrottle].get shouldBe obj
      Json.toJson(obj) shouldBe json
    }
  }
}
