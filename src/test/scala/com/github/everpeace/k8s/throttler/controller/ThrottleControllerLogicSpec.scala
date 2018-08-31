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

package com.github.everpeace.k8s.throttler.controller

import com.github.everpeace.k8s.throttler.crd.v1alpha1
import org.scalatest.{FreeSpec, Matchers}
import skuber.LabelSelector.IsEqualRequirement
import skuber.Resource.Quantity
import skuber.{Container, LabelSelector, ObjectMeta, Pod, Resource}

class ThrottleControllerLogicSpec extends FreeSpec with Matchers with ThrottleControllerLogic {
  def simplePod(resourceRequirements: List[Option[Resource.Requirements]]): Pod = {
    val containers = resourceRequirements.zipWithIndex.map {
      case (rr, index) =>
        Container(
          name = s"ctr$index",
          image = "image",
          resources = rr
        )
    }
    val spec = Pod.Spec(
      containers = containers
    )
    Pod("dummy", spec)
  }

  "ThrottleController" - {
    "activate Throttle when total 'requests' of running pods exceeds threshold" in {
      val pod = simplePod(
        List(
          Some(Resource.Requirements(limits = Map(), requests = Map("r" -> Quantity("2"))))
        )).copy(
        metadata = ObjectMeta(
          namespace = "default",
          name = "dummy",
          labels = Map("key" -> "value")
        ),
        status = Option(Pod.Status(Option(Pod.Phase.Running)))
      )
      val throttle = v1alpha1
        .Throttle(
          "t1",
          v1alpha1.Throttle.Spec(
            selector = LabelSelector(IsEqualRequirement("key", "value")),
            threshold = Map("r" -> Quantity("1"))
          )
        )
        .withNamespace("default")
        .withName("t1")

      val podsInNs      = Set(pod)
      val throttlesInNs = Set(throttle)
      val expectedStatus = v1alpha1.Throttle.Status(
        throttled = true,
        used = Map("r" -> Quantity("2"))
      )

      val actual = calcNextThrottleStatuses(throttlesInNs, podsInNs)
      actual.size shouldBe 1
      actual.head shouldBe throttle.key -> expectedStatus
    }

    "deactivate Throttle when total 'requests' of running pods fall below threshold" in {
      val pod = simplePod(
        List(
          Some(Resource.Requirements(limits = Map(), requests = Map("r" -> Quantity("5"))))
        )).copy(
        metadata = ObjectMeta(
          namespace = "default",
          name = "dummy",
          labels = Map("key" -> "value")
        ),
        status = Option(Pod.Status(Option(Pod.Phase.Succeeded)))
      )
      val throttle = v1alpha1
        .Throttle(
          "t1",
          v1alpha1.Throttle.Spec(
            selector = LabelSelector(IsEqualRequirement("key", "value")),
            threshold = Map("r" -> Quantity("3"))
          )
        )
        .withNamespace("default")
        .withName("t1")

      val podsInNs      = Set(pod)
      val throttlesInNs = Set(throttle)
      val expectedStatus = v1alpha1.Throttle.Status(
        throttled = false,
        used = Map()
      )

      val actual = calcNextThrottleStatuses(throttlesInNs, podsInNs)
      actual.size shouldBe 1
      actual.head shouldBe throttle.key -> expectedStatus
    }
  }
}
