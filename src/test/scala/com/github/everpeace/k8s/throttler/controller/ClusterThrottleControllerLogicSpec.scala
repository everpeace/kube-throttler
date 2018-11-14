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

class ClusterThrottleControllerLogicSpec
    extends FreeSpec
    with Matchers
    with ClusterThrottleControllerLogic {
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

  "ClusterThrottleControllerLogic" - {
    "isClusterThrottleActiveFor" - {
      "should evaluate active when the clusterthrottle's status for some of the 'requests' resource of pod are active" in {
        val clthrottle = v1alpha1
          .ClusterThrottle(
            "ct1",
            v1alpha1.ClusterThrottle.Spec(
              throttlerName = "kube-throttler",
              selector = LabelSelector(IsEqualRequirement("key", "value")),
              // throttle is defined on "r" and "s"
              threshold = Map("r" -> Quantity("2"), "s" -> Quantity("3"))
            )
          )
          .withStatus(
            v1alpha1.ClusterThrottle.Status(
              // only "r" is throttled
              throttled = Map("r" -> true, "s" -> false),
              used = Map("r"      -> Quantity("3"))
            ))

        val podRequestsR = mkPod(List(resourceRequirements(Map("r" -> Quantity("1"))))).copy(
          metadata = commonMeta,
        )
        isClusterThrottleActiveFor(podRequestsR, clthrottle) shouldBe true

        val podRequestsS = mkPod(List(resourceRequirements(Map("s" -> Quantity("3"))))).copy(
          metadata = commonMeta,
        )
        isClusterThrottleActiveFor(podRequestsS, clthrottle) shouldBe false

        val podRequestsT = mkPod(List(resourceRequirements(Map("t" -> Quantity("3"))))).copy(
          metadata = commonMeta,
        )
        isClusterThrottleActiveFor(podRequestsT, clthrottle) shouldBe false

        val podNoLabel = mkPod(List(resourceRequirements(Map("s" -> Quantity("3"))))).copy(
          metadata = commonMeta.copy(labels = Map.empty),
        )
        isClusterThrottleActiveFor(podNoLabel, clthrottle) shouldBe false
      }
    }
    "isClusterThrottleInsufficientFor" - {
      "should evaluated 'insufficient' when there are no space for some of the 'requests' resource of pods" in {
        val clthrottle = v1alpha1
          .ClusterThrottle(
            "ct1",
            v1alpha1.ClusterThrottle.Spec(
              throttlerName = "kube-throttler",
              selector = LabelSelector(IsEqualRequirement("key", "value")),
              // throttle is defined on "r" and "s"
              threshold = Map("r" -> Quantity("3"), "s" -> Quantity("2"))
            )
          )
          .withStatus(
            v1alpha1.ClusterThrottle.Status(
              // only "r" is throttled
              throttled = Map("r" -> false, "s" -> false),
              used = Map("r"      -> Quantity("1"))
            ))

        val podRequestsR1 = mkPod(List(resourceRequirements(Map("r" -> Quantity("1"))))).copy(
          metadata = commonMeta,
        )
        isClusterThrottleInsufficientFor(podRequestsR1, clthrottle) shouldBe false

        val podRequestsR2 = mkPod(List(resourceRequirements(Map("r" -> Quantity("2"))))).copy(
          metadata = commonMeta,
        )
        isClusterThrottleInsufficientFor(podRequestsR2, clthrottle) shouldBe false

        val podRequestsR3 = mkPod(List(resourceRequirements(Map("r" -> Quantity("3"))))).copy(
          metadata = commonMeta,
        )
        isClusterThrottleInsufficientFor(podRequestsR3, clthrottle) shouldBe true

        val podRequestsS1 = mkPod(List(resourceRequirements(Map("s" -> Quantity("1"))))).copy(
          metadata = commonMeta,
        )
        isClusterThrottleInsufficientFor(podRequestsS1, clthrottle) shouldBe false

        val podRequestsS2 = mkPod(List(resourceRequirements(Map("s" -> Quantity("2"))))).copy(
          metadata = commonMeta,
        )
        isClusterThrottleInsufficientFor(podRequestsS2, clthrottle) shouldBe false

        val podRequestsS3 = mkPod(List(resourceRequirements(Map("s" -> Quantity("3"))))).copy(
          metadata = commonMeta,
        )
        isClusterThrottleInsufficientFor(podRequestsS3, clthrottle) shouldBe true

        val podRequestR1S1 =
          mkPod(List(resourceRequirements(Map("r" -> Quantity("1"), "s" -> Quantity("1"))))).copy(
            metadata = commonMeta,
          )
        isClusterThrottleInsufficientFor(podRequestR1S1, clthrottle) shouldBe false

        val podRequestR1S3 =
          mkPod(List(resourceRequirements(Map("r" -> Quantity("1"), "s" -> Quantity("3"))))).copy(
            metadata = commonMeta,
          )
        isClusterThrottleInsufficientFor(podRequestR1S3, clthrottle) shouldBe true

        val podRequestR3S1 =
          mkPod(List(resourceRequirements(Map("r" -> Quantity("3"), "s" -> Quantity("1"))))).copy(
            metadata = commonMeta,
          )
        isClusterThrottleInsufficientFor(podRequestR3S1, clthrottle) shouldBe true

        val podRequestR3S3 =
          mkPod(List(resourceRequirements(Map("r" -> Quantity("3"), "s" -> Quantity("3"))))).copy(
            metadata = commonMeta,
          )
        isClusterThrottleInsufficientFor(podRequestR3S3, clthrottle) shouldBe true

        val podNoRequests = mkPod(List.empty).copy(
          metadata = commonMeta,
        )
        isClusterThrottleInsufficientFor(podNoRequests, clthrottle) shouldBe false

        val podNoLabel = mkPod(List(resourceRequirements(Map("s" -> Quantity("3"))))).copy(
          metadata = commonMeta.copy(labels = Map.empty),
        )
        isClusterThrottleInsufficientFor(podNoLabel, clthrottle) shouldBe false
      }
    }
    "calcNextClusterThrottleStatuses" - {
      "should not clusterthrottle when total `requests` of running pods can't compare threshold" in {
        val pod = mkPod(List(resourceRequirements(Map("r" -> Quantity("2"))))).copy(
          metadata = commonMeta,
          status = phase(Pod.Phase.Running)
        )
        val clthrottle = v1alpha1
          .ClusterThrottle(
            "ct1",
            v1alpha1.ClusterThrottle.Spec(
              throttlerName = "kube-throttler",
              selector = LabelSelector(IsEqualRequirement("key", "value")),
              threshold = Map("s" -> Quantity("3"))
            )
          )

        val pods        = Set(pod)
        val clthrottles = Set(clthrottle)
        val expectedStatus = v1alpha1.ClusterThrottle.Status(
          throttled = Map("s" -> false),
          used = Map("r"      -> Quantity("2"))
        )

        val actual = calcNextClusterThrottleStatuses(clthrottles, pods)
        actual.size shouldBe 1
        actual.head shouldBe clthrottle.key -> expectedStatus
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

        val clthrottle = v1alpha1
          .ClusterThrottle(
            "ct1",
            v1alpha1.ClusterThrottle.Spec(
              throttlerName = "kube-throttler",
              selector = LabelSelector(IsEqualRequirement("key", "value")),
              threshold = Map("r" -> Quantity("2"), "s" -> Quantity("3"))
            )
          )

        val pods        = Set(pod1, pod2)
        val clthrottles = Set(clthrottle)
        val expectedStatus = v1alpha1.ClusterThrottle.Status(
          throttled = Map("r" -> true, "s" -> false),
          used = Map("r"      -> Quantity("2"))
        )

        val actual = calcNextClusterThrottleStatuses(clthrottles, pods)
        actual.size shouldBe 1
        actual.head shouldBe clthrottle.key -> expectedStatus
      }

      "should throttle when total 'requests' of running pods exceeds threshold" in {
        val pod = mkPod(
          List(
            resourceRequirements(Map("r" -> Quantity("2")))
          )).copy(
          metadata = commonMeta,
          status = phase(Pod.Phase.Running)
        )
        val clthrottle = v1alpha1
          .ClusterThrottle(
            "ct1",
            v1alpha1.ClusterThrottle.Spec(
              throttlerName = "kube-throttler",
              selector = LabelSelector(IsEqualRequirement("key", "value")),
              threshold = Map("r" -> Quantity("1"))
            )
          )

        val pods        = Set(pod)
        val clthrottles = Set(clthrottle)
        val expectedStatus = v1alpha1.ClusterThrottle.Status(
          throttled = Map("r" -> true),
          used = Map("r"      -> Quantity("2"))
        )

        val actual = calcNextClusterThrottleStatuses(clthrottles, pods)
        actual.size shouldBe 1
        actual.head shouldBe clthrottle.key -> expectedStatus
      }

      "should not throttled when total 'requests' of running pods fall below threshold" in {
        val pod = mkPod(
          List(
            resourceRequirements(Map("r" -> Quantity("5")))
          )).copy(
          metadata = commonMeta,
          status = phase(Pod.Phase.Succeeded)
        )
        val clthrottle = v1alpha1
          .ClusterThrottle(
            "ct1",
            v1alpha1.ClusterThrottle.Spec(
              throttlerName = "kube-throttler",
              selector = LabelSelector(IsEqualRequirement("key", "value")),
              threshold = Map("r" -> Quantity("3"))
            )
          )

        val pods        = Set(pod)
        val clthrottles = Set(clthrottle)
        val expectedStatus = v1alpha1.ClusterThrottle.Status(
          throttled = Map("r" -> false),
          used = Map()
        )

        val actual = calcNextClusterThrottleStatuses(clthrottles, pods)
        actual.size shouldBe 1
        actual.head shouldBe clthrottle.key -> expectedStatus
      }
    }
  }
}
