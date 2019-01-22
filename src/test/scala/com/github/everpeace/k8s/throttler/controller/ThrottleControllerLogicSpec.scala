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
import com.github.everpeace.k8s.throttler.crd.v1alpha1.{
  IsResourceAmountThrottled,
  IsResourceCountThrottled,
  ResourceAmount,
  ResourceCount
}
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
    "isThrottleAlreadyActiveFor" - {
      "should evaluate active when the throttle's status for all the 'requests' resource of pod are active" in {
        val throttle = v1alpha1
          .Throttle(
            "t1",
            v1alpha1.Throttle.Spec(
              throttlerName = "kube-throttler",
              selector = v1alpha1.Throttle.Selector(List(
                v1alpha1.Throttle.SelectorItem(LabelSelector(IsEqualRequirement("key", "value")))
              )),
              // throttle is defined on "r" and "s"
              threshold = ResourceAmount(
                resourceRequests = Map("r" -> Quantity("2"), "s" -> Quantity("3"))
              )
            )
          )
          .withStatus(
            v1alpha1.Throttle.Status(
              // only "r" is throttled
              throttled = IsResourceAmountThrottled(
                resourceRequests = Map("r" -> true, "s" -> false)
              ),
              used = ResourceAmount(
                resourceRequests = Map("r" -> Quantity("3"))
              )
            )
          )
          .withNamespace("default")
          .withName("t1")

        val podRequestsR = mkPod(List(resourceRequirements(Map("r" -> Quantity("1"))))).copy(
          metadata = commonMeta,
        )
        isThrottleAlreadyActiveFor(podRequestsR, throttle) shouldBe true

        val podRequestsS = mkPod(List(resourceRequirements(Map("s" -> Quantity("3"))))).copy(
          metadata = commonMeta,
        )
        isThrottleAlreadyActiveFor(podRequestsS, throttle) shouldBe false

        val podRequestsT = mkPod(List(resourceRequirements(Map("t" -> Quantity("3"))))).copy(
          metadata = commonMeta,
        )
        isThrottleAlreadyActiveFor(podRequestsT, throttle) shouldBe false

        val podNoLabel = mkPod(List(resourceRequirements(Map("s" -> Quantity("3"))))).copy(
          metadata = commonMeta.copy(labels = Map.empty),
        )
        isThrottleAlreadyActiveFor(podNoLabel, throttle) shouldBe false
      }

      "should evaluate active when the throttle's status.podCounts is active" in {
        val throttle = v1alpha1
          .Throttle(
            "t1",
            v1alpha1.Throttle.Spec(
              throttlerName = "kube-throttler",
              selector = v1alpha1.Throttle.Selector(List(
                v1alpha1.Throttle.SelectorItem(LabelSelector(IsEqualRequirement("key", "value")))
              )),
              threshold = ResourceAmount(
                resourceCounts = Option(
                  ResourceCount(
                    pod = Option(1)
                  )),
              )
            )
          )
          .withStatus(
            v1alpha1.Throttle.Status(
              throttled = IsResourceAmountThrottled(
                resourceCounts = Option(
                  IsResourceCountThrottled(
                    pod = Option(true)
                  )),
              ),
              used = ResourceAmount(
                resourceCounts = Option(
                  ResourceCount(
                    pod = Option(1)
                  )),
                resourceRequests = Map("r" -> Quantity("3"))
              )
            )
          )
          .withNamespace("default")
          .withName("t1")

        val podRequestsR = mkPod(List(resourceRequirements(Map("r" -> Quantity("1"))))).copy(
          metadata = commonMeta,
        )
        isThrottleAlreadyActiveFor(podRequestsR, throttle) shouldBe true

        val podNoLabel = mkPod(List(resourceRequirements(Map("s" -> Quantity("3"))))).copy(
          metadata = commonMeta.copy(labels = Map.empty),
        )
        isThrottleAlreadyActiveFor(podNoLabel, throttle) shouldBe false
      }
    }
    "isThrottleInsufficientFor" - {
      "should evaluate 'insufficient' when there are no space for some of the 'requests' resource of pods" in {
        val throttle = v1alpha1
          .Throttle(
            "t1",
            v1alpha1.Throttle.Spec(
              throttlerName = "kube-throttler",
              selector = v1alpha1.Throttle.Selector(
                List(
                  v1alpha1.Throttle.SelectorItem(LabelSelector(IsEqualRequirement("key", "value")))
                )),
              // throttle is defined on "r" and "s"
              threshold = ResourceAmount(
                resourceRequests = Map("r" -> Quantity("3"), "s" -> Quantity("2"))
              )
            )
          )
          .withStatus(v1alpha1.Throttle.Status(
            // only "r" is throttled
            throttled = IsResourceAmountThrottled(
              resourceRequests = Map("r" -> false, "s" -> false)
            ),
            used = ResourceAmount(
              resourceRequests = Map("r" -> Quantity("1"))
            )
          ))
          .withNamespace("default")

        val podRequestsR1 = mkPod(List(resourceRequirements(Map("r" -> Quantity("1"))))).copy(
          metadata = commonMeta,
        )
        isThrottleInsufficientFor(podRequestsR1, throttle) shouldBe false

        val podRequestsR2 = mkPod(List(resourceRequirements(Map("r" -> Quantity("2"))))).copy(
          metadata = commonMeta,
        )
        isThrottleInsufficientFor(podRequestsR2, throttle) shouldBe false

        val podRequestsR3 = mkPod(List(resourceRequirements(Map("r" -> Quantity("3"))))).copy(
          metadata = commonMeta,
        )
        isThrottleInsufficientFor(podRequestsR3, throttle) shouldBe true

        val podRequestsS1 = mkPod(List(resourceRequirements(Map("s" -> Quantity("1"))))).copy(
          metadata = commonMeta,
        )
        isThrottleInsufficientFor(podRequestsS1, throttle) shouldBe false

        val podRequestsS2 = mkPod(List(resourceRequirements(Map("s" -> Quantity("2"))))).copy(
          metadata = commonMeta,
        )
        isThrottleInsufficientFor(podRequestsS2, throttle) shouldBe false

        val podRequestsS3 = mkPod(List(resourceRequirements(Map("s" -> Quantity("3"))))).copy(
          metadata = commonMeta,
        )
        isThrottleInsufficientFor(podRequestsS3, throttle) shouldBe true

        val podRequestR1S1 =
          mkPod(List(resourceRequirements(Map("r" -> Quantity("1"), "s" -> Quantity("1"))))).copy(
            metadata = commonMeta,
          )
        isThrottleInsufficientFor(podRequestR1S1, throttle) shouldBe false

        val podRequestR1S3 =
          mkPod(List(resourceRequirements(Map("r" -> Quantity("1"), "s" -> Quantity("3"))))).copy(
            metadata = commonMeta,
          )
        isThrottleInsufficientFor(podRequestR1S3, throttle) shouldBe true

        val podRequestR3S1 =
          mkPod(List(resourceRequirements(Map("r" -> Quantity("3"), "s" -> Quantity("1"))))).copy(
            metadata = commonMeta,
          )
        isThrottleInsufficientFor(podRequestR3S1, throttle) shouldBe true

        val podRequestR3S3 =
          mkPod(List(resourceRequirements(Map("r" -> Quantity("3"), "s" -> Quantity("3"))))).copy(
            metadata = commonMeta,
          )
        isThrottleInsufficientFor(podRequestR3S3, throttle) shouldBe true

        val podNoRequests = mkPod(List.empty).copy(
          metadata = commonMeta,
        )
        isThrottleInsufficientFor(podNoRequests, throttle) shouldBe false

        val podNoLabel = mkPod(List(resourceRequirements(Map("s" -> Quantity("3"))))).copy(
          metadata = commonMeta.copy(labels = Map.empty),
        )
        isThrottleInsufficientFor(podNoLabel, throttle) shouldBe false
      }

      "should evaluate 'insufficient' when there are no space for podCount" in {
        val throttleNoSpace = v1alpha1
          .Throttle(
            "t1",
            v1alpha1.Throttle.Spec(
              throttlerName = "kube-throttler",
              selector = v1alpha1.Throttle.Selector(List(
                v1alpha1.Throttle.SelectorItem(LabelSelector(IsEqualRequirement("key", "value")))
              )),
              threshold = ResourceAmount(
                resourceCounts = Option(
                  ResourceCount(
                    pod = Option(1)
                  ))
              )
            )
          )
          .withStatus(
            v1alpha1.Throttle.Status(
              throttled = IsResourceAmountThrottled(
                resourceCounts = Option(
                  IsResourceCountThrottled(
                    pod = Option(true)
                  ))
              ),
              used = ResourceAmount(
                resourceCounts = Option(
                  ResourceCount(
                    pod = Option(1)
                  ))
              )
            )
          )
          .withNamespace("default")
          .withName("t1")

        val podRequestsR = mkPod(List(resourceRequirements(Map("r" -> Quantity("1"))))).copy(
          metadata = commonMeta,
        )
        isThrottleInsufficientFor(podRequestsR, throttleNoSpace) shouldBe true

        val podNoLabel = mkPod(List(resourceRequirements(Map("s" -> Quantity("3"))))).copy(
          metadata = commonMeta.copy(labels = Map.empty),
        )
        isThrottleInsufficientFor(podNoLabel, throttleNoSpace) shouldBe false

        val throttleWithSpace = v1alpha1
          .Throttle(
            "t1",
            v1alpha1.Throttle.Spec(
              throttlerName = "kube-throttler",
              selector = v1alpha1.Throttle.Selector(List(
                v1alpha1.Throttle.SelectorItem(LabelSelector(IsEqualRequirement("key", "value")))
              )),
              threshold = ResourceAmount(
                resourceCounts = Option(ResourceCount(
                  pod = Option(2)
                )))
            )
          )
          .withStatus(
            v1alpha1.Throttle.Status(
              // only "r" is throttled
              throttled = IsResourceAmountThrottled(
                resourceCounts = Option(
                  IsResourceCountThrottled(
                    pod = Option(false)
                  ))
              ),
              used = ResourceAmount(
                resourceCounts = Option(
                  ResourceCount(
                    pod = Option(1)
                  ))
              )
            )
          )
          .withNamespace("default")
          .withName("t1")
        isThrottleInsufficientFor(podRequestsR, throttleWithSpace) shouldBe false
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
              selector = v1alpha1.Throttle.Selector(
                List(
                  v1alpha1.Throttle.SelectorItem(LabelSelector(IsEqualRequirement("key", "value")))
                )),
              threshold = ResourceAmount(
                resourceRequests = Map("s" -> Quantity("3"))
              )
            )
          )
          .withNamespace("default")
          .withName("t1")

        val podsInNs      = Set(pod)
        val throttlesInNs = Set(throttle)
        val expectedStatus = v1alpha1.Throttle.Status(
          throttled = IsResourceAmountThrottled(
            resourceRequests = Map("s" -> false)
          ),
          used = ResourceAmount()
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
              selector = v1alpha1.Throttle.Selector(
                List(
                  v1alpha1.Throttle.SelectorItem(LabelSelector(IsEqualRequirement("key", "value")))
                )),
              threshold = ResourceAmount(
                resourceRequests = Map("r" -> Quantity("2"), "s" -> Quantity("3"))
              )
            )
          )
          .withNamespace("default")
          .withName("t1")

        val podsInNs      = Set(pod1, pod2)
        val throttlesInNs = Set(throttle)
        val expectedStatus = v1alpha1.Throttle.Status(
          throttled = IsResourceAmountThrottled(
            resourceRequests = Map("r" -> true, "s" -> false)
          ),
          used = ResourceAmount(
            resourceRequests = Map("r" -> Quantity("2"))
          )
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
              selector = v1alpha1.Throttle.Selector(
                List(
                  v1alpha1.Throttle.SelectorItem(LabelSelector(IsEqualRequirement("key", "value")))
                )),
              threshold = ResourceAmount(
                resourceRequests = Map("r" -> Quantity("1"))
              )
            )
          )
          .withNamespace("default")
          .withName("t1")

        val podsInNs      = Set(pod)
        val throttlesInNs = Set(throttle)
        val expectedStatus = v1alpha1.Throttle.Status(
          throttled = IsResourceAmountThrottled(
            resourceRequests = Map("r" -> true)
          ),
          used = ResourceAmount(
            resourceRequests = Map("r" -> Quantity("2"))
          )
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
              selector = v1alpha1.Throttle.Selector(
                List(
                  v1alpha1.Throttle.SelectorItem(LabelSelector(IsEqualRequirement("key", "value")))
                )),
              threshold = ResourceAmount(
                resourceRequests = Map("r" -> Quantity("3"))
              )
            )
          )
          .withNamespace("default")
          .withName("t1")

        val podsInNs      = Set(pod)
        val throttlesInNs = Set(throttle)
        val expectedStatus = v1alpha1.Throttle.Status(
          throttled = IsResourceAmountThrottled(
            resourceRequests = Map("r" -> false)
          ),
          used = ResourceAmount(
            resourceRequests = Map()
          )
        )

        val actual = calcNextThrottleStatuses(throttlesInNs, podsInNs)
        actual.size shouldBe 1
        actual.head shouldBe throttle.key -> expectedStatus
      }

      "should throttle when total number of running pods exceeds threshold" in {
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
              selector = v1alpha1.Throttle.Selector(
                List(
                  v1alpha1.Throttle.SelectorItem(LabelSelector(IsEqualRequirement("key", "value")))
                )),
              threshold = ResourceAmount(
                resourceCounts = Option(
                  ResourceCount(
                    pod = Option(1)
                  )),
              )
            )
          )
          .withNamespace("default")
          .withName("t1")

        val podsInNs      = Set(pod)
        val throttlesInNs = Set(throttle)
        val expectedStatus = v1alpha1.Throttle.Status(
          throttled = IsResourceAmountThrottled(
            resourceCounts = Option(
              IsResourceCountThrottled(
                pod = Option(true)
              )),
          ),
          used = ResourceAmount(
            resourceCounts = Option(
              ResourceCount(
                pod = Option(1)
              )),
          )
        )

        val actual = calcNextThrottleStatuses(throttlesInNs, podsInNs)
        actual.size shouldBe 1
        actual.head shouldBe throttle.key -> expectedStatus
      }

      "should not throttle when total number of running pods fall below threshold" in {
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
              selector = v1alpha1.Throttle.Selector(
                List(
                  v1alpha1.Throttle.SelectorItem(LabelSelector(IsEqualRequirement("key", "value")))
                )),
              threshold = ResourceAmount(
                resourceCounts = Option(ResourceCount(
                  pod = Option(2)
                )))
            )
          )
          .withNamespace("default")
          .withName("t1")

        val podsInNs      = Set(pod)
        val throttlesInNs = Set(throttle)
        val expectedStatus = v1alpha1.Throttle.Status(
          throttled = IsResourceAmountThrottled(
            resourceCounts = Option(
              IsResourceCountThrottled(
                pod = Option(false)
              ))
          ),
          used = ResourceAmount(
            resourceCounts = Option(
              ResourceCount(
                pod = Option(1)
              ))
          )
        )

        val actual = calcNextThrottleStatuses(throttlesInNs, podsInNs)
        actual.size shouldBe 1
        actual.head shouldBe throttle.key -> expectedStatus
      }
    }
  }
}
