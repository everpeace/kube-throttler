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
import skuber.Resource.{Quantity, ResourceList}
import skuber.{Container, LabelSelector, ObjectMeta, Pod, Resource}

class ThrottleControllerLogicSpec extends FreeSpec with Matchers with ThrottleControllerLogic {
  def mkPod(resourceRequirements: List[Option[Resource.Requirements]]): Pod = {
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

  def phase(s: Pod.Phase.Phase) = Option(Pod.Status(Option(s)))

  def resourceRequirements(requests: ResourceList, limits: ResourceList = Map()) =
    Some(Resource.Requirements(limits = limits, requests = requests))

  val commonMeta = ObjectMeta(
    namespace = "default",
    name = "dummy",
    labels = Map("key" -> "value")
  )

  "ThrottleControllerLogic" - {
    "isActiveFor" - {
      "should evaluate active when the throttle's status for all the 'requests' resource of pod are active" - {
        val throttle = v1alpha1
          .Throttle(
            "t1",
            v1alpha1.Throttle.Spec(
              throttlerName = "kube-throttler",
              selector = LabelSelector(IsEqualRequirement("key", "value")),
              // throttle is defined on "r" and "s"
              threshold = Map("r" -> Quantity("2"), "s" -> Quantity("3"))
            )
          )
          .withStatus(
            v1alpha1.Throttle.Status(
              // only "r" is throttled
              throttled = Map("r" -> true, "s" -> false),
              used = Map("r"      -> Quantity("3"))
            ))
          .withNamespace("default")
          .withName("t1")

        val podRequestsR = mkPod(List(resourceRequirements(Map("r" -> Quantity("1"))))).copy(
          metadata = commonMeta,
        )
        isActiveFor(podRequestsR, throttle) shouldBe true

        val podRequestsS = mkPod(List(resourceRequirements(Map("s" -> Quantity("3"))))).copy(
          metadata = commonMeta,
        )
        isActiveFor(podRequestsS, throttle) shouldBe false

        val podRequestsT = mkPod(List(resourceRequirements(Map("t" -> Quantity("3"))))).copy(
          metadata = commonMeta,
        )
        isActiveFor(podRequestsT, throttle) shouldBe false

        val podNoLabel = mkPod(List(resourceRequirements(Map("s" -> Quantity("3"))))).copy(
          metadata = commonMeta.copy(labels = Map.empty),
        )
        isActiveFor(podNoLabel, throttle) shouldBe false
      }
    }
    "calcNextThrottleStatuses" - {
      "should not throttle when total `requests` of running pods can't compare threshold" in {
        val pod = mkPod(List(resourceRequirements(Map("r" -> Quantity("2"))))).copy(
          metadata = commonMeta,
          status = phase(Pod.Phase.Running)
        )
        val throttle = v1alpha1
          .Throttle(
            "t1",
            v1alpha1.Throttle.Spec(
              throttlerName = "kube-throttler",
              selector = LabelSelector(IsEqualRequirement("key", "value")),
              threshold = Map("s" -> Quantity("3"))
            )
          )
          .withNamespace("default")
          .withName("t1")

        val podsInNs      = Set(pod)
        val throttlesInNs = Set(throttle)
        val expectedStatus = v1alpha1.Throttle.Status(
          throttled = Map("s" -> false),
          used = Map("r"      -> Quantity("2"))
        )

        val actual = calcNextThrottleStatuses(throttlesInNs, podsInNs)
        actual.size shouldBe 1
        actual.head shouldBe throttle.key -> expectedStatus
      }

      "should throttle when total `requests` of running pods equal to its threshold" in {
        val pod1 = mkPod(List(resourceRequirements(Map("r" -> Quantity("1"))))).copy(
          metadata = commonMeta,
          status = phase(Pod.Phase.Running)
        )
        val pod2 = mkPod(List(resourceRequirements(Map("r" -> Quantity("1"))))).copy(
          metadata = commonMeta.copy(name = "dummy2"),
          status = phase(Pod.Phase.Running)
        )

        val throttle = v1alpha1
          .Throttle(
            "t1",
            v1alpha1.Throttle.Spec(
              throttlerName = "kube-throttler",
              selector = LabelSelector(IsEqualRequirement("key", "value")),
              threshold = Map("r" -> Quantity("2"), "s" -> Quantity("3"))
            )
          )
          .withNamespace("default")
          .withName("t1")

        val podsInNs      = Set(pod1, pod2)
        val throttlesInNs = Set(throttle)
        val expectedStatus = v1alpha1.Throttle.Status(
          throttled = Map("r" -> true, "s" -> false),
          used = Map("r"      -> Quantity("2"))
        )

        val actual = calcNextThrottleStatuses(throttlesInNs, podsInNs)
        actual.size shouldBe 1
        actual.head shouldBe throttle.key -> expectedStatus
      }

      "should throttle when total 'requests' of running pods exceeds threshold" in {
        val pod = mkPod(
          List(
            resourceRequirements(Map("r" -> Quantity("2")))
          )).copy(
          metadata = commonMeta,
          status = phase(Pod.Phase.Running)
        )
        val throttle = v1alpha1
          .Throttle(
            "t1",
            v1alpha1.Throttle.Spec(
              throttlerName = "kube-throttler",
              selector = LabelSelector(IsEqualRequirement("key", "value")),
              threshold = Map("r" -> Quantity("1"))
            )
          )
          .withNamespace("default")
          .withName("t1")

        val podsInNs      = Set(pod)
        val throttlesInNs = Set(throttle)
        val expectedStatus = v1alpha1.Throttle.Status(
          throttled = Map("r" -> true),
          used = Map("r"      -> Quantity("2"))
        )

        val actual = calcNextThrottleStatuses(throttlesInNs, podsInNs)
        actual.size shouldBe 1
        actual.head shouldBe throttle.key -> expectedStatus
      }

      "should not throttled when total 'requests' of running pods fall below threshold" in {
        val pod = mkPod(
          List(
            resourceRequirements(Map("r" -> Quantity("5")))
          )).copy(
          metadata = commonMeta,
          status = phase(Pod.Phase.Succeeded)
        )
        val throttle = v1alpha1
          .Throttle(
            "t1",
            v1alpha1.Throttle.Spec(
              throttlerName = "kube-throttler",
              selector = LabelSelector(IsEqualRequirement("key", "value")),
              threshold = Map("r" -> Quantity("3"))
            )
          )
          .withNamespace("default")
          .withName("t1")

        val podsInNs      = Set(pod)
        val throttlesInNs = Set(throttle)
        val expectedStatus = v1alpha1.Throttle.Status(
          throttled = Map("r" -> false),
          used = Map()
        )

        val actual = calcNextThrottleStatuses(throttlesInNs, podsInNs)
        actual.size shouldBe 1
        actual.head shouldBe throttle.key -> expectedStatus
      }
    }
  }
}
