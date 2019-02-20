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
import com.github.everpeace.k8s.throttler.crd.v1alpha1._
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

  val at = java.time.ZonedDateTime.parse("2019-03-01T00:00:00+09:00")

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
              ),
              calculatedThreshold = Option(
                CalculatedThreshold(ResourceAmount(
                                      resourceRequests =
                                        Map("r" -> Quantity("2"), "s" -> Quantity("3"))
                                    ),
                                    at))
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
                  )),
              ),
              used = ResourceAmount(
                resourceCounts = Option(
                  ResourceCount(
                    pod = Option(1)
                  )),
                resourceRequests = Map("r" -> Quantity("3"))
              ),
              calculatedThreshold = Option(
                CalculatedThreshold(ResourceAmount(
                                      resourceCounts = Option(ResourceCount(
                                        pod = Option(1)
                                      ))
                                    ),
                                    at))
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
            ),
            calculatedThreshold = None
          ))
          .withNamespace("default")

        val throttleWithCalculatedThreshold = v1alpha1
          .Throttle(
            "t1",
            v1alpha1.Throttle.Spec(
              throttlerName = "kube-throttler",
              selector = v1alpha1.Throttle.Selector(
                List(
                  v1alpha1.Throttle.SelectorItem(LabelSelector(IsEqualRequirement("key", "value")))
                )),
              // throttle is defined on "r" and "s" with very high value
              threshold = ResourceAmount(
                resourceRequests = Map("r" -> Quantity("300"), "s" -> Quantity("200"))
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
            ),
            calculatedThreshold = Option(
              v1alpha1.CalculatedThreshold(
                ResourceAmount(
                  // throttle is overridden on "r" and "s" with normal value
                  resourceRequests = Map("r" -> Quantity("3"), "s" -> Quantity("2"))
                ),
                at
              ))
          ))
          .withNamespace("default")

        val podRequestsR1 = mkPod(List(resourceRequirements(Map("r" -> Quantity("1"))))).copy(
          metadata = commonMeta,
        )
        isThrottleInsufficientFor(podRequestsR1, throttle) shouldBe false
        isThrottleInsufficientFor(podRequestsR1, throttleWithCalculatedThreshold) shouldBe false

        val podRequestsR2 = mkPod(List(resourceRequirements(Map("r" -> Quantity("2"))))).copy(
          metadata = commonMeta,
        )
        isThrottleInsufficientFor(podRequestsR2, throttle) shouldBe false
        isThrottleInsufficientFor(podRequestsR2, throttleWithCalculatedThreshold) shouldBe false

        val podRequestsR3 = mkPod(List(resourceRequirements(Map("r" -> Quantity("3"))))).copy(
          metadata = commonMeta,
        )
        isThrottleInsufficientFor(podRequestsR3, throttle) shouldBe true
        isThrottleInsufficientFor(podRequestsR3, throttleWithCalculatedThreshold) shouldBe true

        val podRequestsS1 = mkPod(List(resourceRequirements(Map("s" -> Quantity("1"))))).copy(
          metadata = commonMeta,
        )
        isThrottleInsufficientFor(podRequestsS1, throttle) shouldBe false
        isThrottleInsufficientFor(podRequestsS1, throttleWithCalculatedThreshold) shouldBe false

        val podRequestsS2 = mkPod(List(resourceRequirements(Map("s" -> Quantity("2"))))).copy(
          metadata = commonMeta,
        )
        isThrottleInsufficientFor(podRequestsS2, throttle) shouldBe false
        isThrottleInsufficientFor(podRequestsS2, throttleWithCalculatedThreshold) shouldBe false

        val podRequestsS3 = mkPod(List(resourceRequirements(Map("s" -> Quantity("3"))))).copy(
          metadata = commonMeta,
        )
        isThrottleInsufficientFor(podRequestsS3, throttle) shouldBe true
        isThrottleInsufficientFor(podRequestsS3, throttleWithCalculatedThreshold) shouldBe true

        val podRequestR1S1 =
          mkPod(List(resourceRequirements(Map("r" -> Quantity("1"), "s" -> Quantity("1"))))).copy(
            metadata = commonMeta,
          )
        isThrottleInsufficientFor(podRequestR1S1, throttle) shouldBe false
        isThrottleInsufficientFor(podRequestR1S1, throttleWithCalculatedThreshold) shouldBe false

        val podRequestR1S3 =
          mkPod(List(resourceRequirements(Map("r" -> Quantity("1"), "s" -> Quantity("3"))))).copy(
            metadata = commonMeta,
          )
        isThrottleInsufficientFor(podRequestR1S3, throttle) shouldBe true
        isThrottleInsufficientFor(podRequestR1S3, throttleWithCalculatedThreshold) shouldBe true

        val podRequestR3S1 =
          mkPod(List(resourceRequirements(Map("r" -> Quantity("3"), "s" -> Quantity("1"))))).copy(
            metadata = commonMeta,
          )
        isThrottleInsufficientFor(podRequestR3S1, throttle) shouldBe true
        isThrottleInsufficientFor(podRequestR3S1, throttleWithCalculatedThreshold) shouldBe true

        val podRequestR3S3 =
          mkPod(List(resourceRequirements(Map("r" -> Quantity("3"), "s" -> Quantity("3"))))).copy(
            metadata = commonMeta,
          )
        isThrottleInsufficientFor(podRequestR3S3, throttle) shouldBe true
        isThrottleInsufficientFor(podRequestR3S3, throttleWithCalculatedThreshold) shouldBe true

        val podNoRequests = mkPod(List.empty).copy(
          metadata = commonMeta,
        )
        isThrottleInsufficientFor(podNoRequests, throttle) shouldBe false
        isThrottleInsufficientFor(podNoRequests, throttleWithCalculatedThreshold) shouldBe false

        val podNoLabel = mkPod(List(resourceRequirements(Map("s" -> Quantity("3"))))).copy(
          metadata = commonMeta.copy(labels = Map.empty),
        )
        isThrottleInsufficientFor(podNoLabel, throttle) shouldBe false
        isThrottleInsufficientFor(podNoLabel, throttleWithCalculatedThreshold) shouldBe false
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
              ),
              calculatedThreshold = None
            )
          )
          .withNamespace("default")
          .withName("t1")

        val throttleNoSpaceOverridden = v1alpha1
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
                    pod = Option(100)
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
              ),
              calculatedThreshold = Option(
                CalculatedThreshold(
                  ResourceAmount(
                    resourceCounts = Option(ResourceCount(
                      pod = Option(1)
                    ))
                  ),
                  at
                ))
            )
          )
          .withNamespace("default")
          .withName("t1")
        val podRequestsR = mkPod(List(resourceRequirements(Map("r" -> Quantity("1"))))).copy(
          metadata = commonMeta,
        )
        isThrottleInsufficientFor(podRequestsR, throttleNoSpace) shouldBe true
        isThrottleInsufficientFor(podRequestsR, throttleNoSpaceOverridden) shouldBe true

        val podNoLabel = mkPod(List(resourceRequirements(Map("s" -> Quantity("3"))))).copy(
          metadata = commonMeta.copy(labels = Map.empty),
        )
        isThrottleInsufficientFor(podNoLabel, throttleNoSpace) shouldBe false
        isThrottleInsufficientFor(podNoLabel, throttleNoSpaceOverridden) shouldBe false

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
                  )),
              ),
              calculatedThreshold = None
            )
          )
          .withNamespace("default")
          .withName("t1")

        val throttleWithSpaceOverridden = v1alpha1
          .Throttle(
            "t1",
            v1alpha1.Throttle.Spec(
              throttlerName = "kube-throttler",
              selector = v1alpha1.Throttle.Selector(List(
                v1alpha1.Throttle.SelectorItem(LabelSelector(IsEqualRequirement("key", "value")))
              )),
              threshold = ResourceAmount(
                resourceCounts = Option(ResourceCount(
                  pod = Option(1)
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
                  )),
              ),
              calculatedThreshold = Option(
                CalculatedThreshold(
                  ResourceAmount(
                    resourceCounts = Option(ResourceCount(
                      pod = Option(2)
                    ))
                  ),
                  at
                ))
            )
          )
          .withNamespace("default")
          .withName("t1")
        isThrottleInsufficientFor(podRequestsR, throttleWithSpace) shouldBe false
        isThrottleInsufficientFor(podRequestsR, throttleWithSpaceOverridden) shouldBe false
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

        val throttleWithActiveOverrides = v1alpha1
          .Throttle(
            "t1",
            v1alpha1.Throttle.Spec(
              throttlerName = "kube-throttler",
              selector = v1alpha1.Throttle.Selector(
                List(
                  v1alpha1.Throttle.SelectorItem(LabelSelector(IsEqualRequirement("key", "value")))
                )),
              threshold = ResourceAmount(
                resourceRequests = Map("s" -> Quantity("1"))
              ),
              temporaryThresholdOverrides = List(
                TemporaryThresholdOverride(
                  begin = at.minusDays(1),
                  end = at.plusDays(1),
                  threshold = ResourceAmount(
                    resourceRequests = Map("s" -> Quantity("3"))
                  )
                ))
            )
          )
          .withNamespace("default")
          .withName("t1")

        val throttleWithoutActiveOverrides = v1alpha1
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
              ),
              temporaryThresholdOverrides = List(
                TemporaryThresholdOverride(
                  begin = at.plusDays(1),
                  end = at.plusDays(2),
                  threshold = ResourceAmount(
                    resourceRequests = Map("s" -> Quantity("1"))
                  )
                ))
            )
          )
          .withNamespace("default")
          .withName("t1")

        val podsInNs = Set(pod)
        val expectedStatus = v1alpha1.Throttle.Status(
          throttled = IsResourceAmountThrottled(
            resourceRequests = Map("s" -> false)
          ),
          used = ResourceAmount(),
          calculatedThreshold = Option(
            CalculatedThreshold(ResourceAmount(
                                  resourceRequests = Map("s" -> Quantity("3"))
                                ),
                                at))
        )

        val actual = calcNextThrottleStatuses(Set(throttle), podsInNs, at)
        actual.size shouldBe 1
        actual.head shouldBe throttle.key -> expectedStatus

        val actualWithActiveOverride =
          calcNextThrottleStatuses(Set(throttleWithActiveOverrides), podsInNs, at)
        actualWithActiveOverride.size shouldBe 1
        actualWithActiveOverride.head shouldBe throttleWithActiveOverrides.key -> expectedStatus

        val actualWithoutActiveOverride =
          calcNextThrottleStatuses(Set(throttleWithoutActiveOverrides), podsInNs, at)
        actualWithoutActiveOverride.size shouldBe 1
        actualWithoutActiveOverride.head shouldBe throttleWithoutActiveOverrides.key -> expectedStatus

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

        val throttleWithActiveOverrides = v1alpha1
          .Throttle(
            "t1",
            v1alpha1.Throttle.Spec(
              throttlerName = "kube-throttler",
              selector = v1alpha1.Throttle.Selector(
                List(
                  v1alpha1.Throttle.SelectorItem(LabelSelector(IsEqualRequirement("key", "value")))
                )),
              threshold = ResourceAmount(
                resourceRequests = Map("r" -> Quantity("200"), "s" -> Quantity("300"))
              ),
              temporaryThresholdOverrides = List(
                TemporaryThresholdOverride(
                  begin = at.minusDays(2),
                  end = at.plusDays(1),
                  ResourceAmount(
                    resourceRequests = Map("r" -> Quantity("2"), "s" -> Quantity("3"))
                  )
                ),
                TemporaryThresholdOverride(
                  begin = at.minusDays(1),
                  end = at.plusDays(2),
                  ResourceAmount(
                    resourceRequests = Map("r" -> Quantity("20"), "s" -> Quantity("30"))
                  )
                )
              )
            )
          )
          .withNamespace("default")
          .withName("t1")

        val throttleWithoutActiveOverrides = v1alpha1
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
              ),
              temporaryThresholdOverrides = List(
                TemporaryThresholdOverride(
                  begin = at.plusDays(1),
                  end = at.plusDays(2),
                  ResourceAmount(
                    resourceRequests = Map("r" -> Quantity("20"), "s" -> Quantity("30"))
                  )
                ))
            )
          )
          .withNamespace("default")
          .withName("t1")

        val podsInNs = Set(pod1, pod2)
        val expectedStatus = v1alpha1.Throttle.Status(
          throttled = IsResourceAmountThrottled(
            resourceRequests = Map("r" -> true, "s" -> false)
          ),
          used = ResourceAmount(
            resourceRequests = Map("r" -> Quantity("2"))
          ),
          calculatedThreshold = Option(
            CalculatedThreshold(ResourceAmount(
                                  resourceRequests = Map("r" -> Quantity("2"), "s" -> Quantity("3"))
                                ),
                                at))
        )

        val actual = calcNextThrottleStatuses(Set(throttle), podsInNs, at)
        actual.size shouldBe 1
        actual.head shouldBe throttle.key -> expectedStatus

        val actualWithActiveOverrides =
          calcNextThrottleStatuses(Set(throttleWithActiveOverrides), podsInNs, at)
        actualWithActiveOverrides.size shouldBe 1
        actualWithActiveOverrides.head shouldBe throttleWithActiveOverrides.key -> expectedStatus

        val actualWithoutActiveOverrides =
          calcNextThrottleStatuses(Set(throttleWithoutActiveOverrides), podsInNs, at)
        actualWithoutActiveOverrides.size shouldBe 1
        actualWithoutActiveOverrides.head shouldBe throttleWithoutActiveOverrides.key -> expectedStatus
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
        val throttleWithActiveOverrides = v1alpha1
          .Throttle(
            "t1",
            v1alpha1.Throttle.Spec(
              throttlerName = "kube-throttler",
              selector = v1alpha1.Throttle.Selector(
                List(
                  v1alpha1.Throttle.SelectorItem(LabelSelector(IsEqualRequirement("key", "value")))
                )),
              threshold = ResourceAmount(
                resourceRequests = Map("r" -> Quantity("100"))
              ),
              temporaryThresholdOverrides = List(
                TemporaryThresholdOverride(
                  begin = at.minusDays(1),
                  end = at.plusDays(1),
                  ResourceAmount(
                    resourceRequests = Map("r" -> Quantity("1"))
                  )
                ))
            )
          )
          .withNamespace("default")
          .withName("t1")
        val throttleWithoutActiveOverrides = v1alpha1
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
              ),
              temporaryThresholdOverrides = List(
                TemporaryThresholdOverride(
                  begin = at.plusDays(1),
                  end = at.plusDays(2),
                  ResourceAmount(
                    resourceRequests = Map("r" -> Quantity("1"))
                  )
                ))
            )
          )
          .withNamespace("default")
          .withName("t1")

        val podsInNs = Set(pod)
        val expectedStatus = v1alpha1.Throttle.Status(
          throttled = IsResourceAmountThrottled(
            resourceRequests = Map("r" -> true)
          ),
          used = ResourceAmount(
            resourceRequests = Map("r" -> Quantity("2"))
          ),
          calculatedThreshold = Option(
            CalculatedThreshold(ResourceAmount(
                                  resourceRequests = Map("r" -> Quantity("1"))
                                ),
                                at))
        )

        val actual = calcNextThrottleStatuses(Set(throttle), podsInNs, at)
        actual.size shouldBe 1
        actual.head shouldBe throttle.key -> expectedStatus

        val actualWithActiveOverrides =
          calcNextThrottleStatuses(Set(throttleWithActiveOverrides), podsInNs, at)
        actualWithActiveOverrides.size shouldBe 1
        actualWithActiveOverrides.head shouldBe throttleWithActiveOverrides.key -> expectedStatus

        val actualWithougActiveOverrides =
          calcNextThrottleStatuses(Set(throttleWithoutActiveOverrides), podsInNs, at)
        actualWithougActiveOverrides.size shouldBe 1
        actualWithougActiveOverrides.head shouldBe throttleWithoutActiveOverrides.key -> expectedStatus
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
        val throttleWithActiveOverrides = v1alpha1
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
        val throttleWithoutActiveOverrides = v1alpha1
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

        val podsInNs = Set(pod)
        val expectedStatus = v1alpha1.Throttle.Status(
          throttled = IsResourceAmountThrottled(
            resourceRequests = Map("r" -> false)
          ),
          used = ResourceAmount(
            resourceRequests = Map()
          ),
          calculatedThreshold = Option(
            CalculatedThreshold(ResourceAmount(
                                  resourceRequests = Map("r" -> Quantity("3"))
                                ),
                                at))
        )

        val actual = calcNextThrottleStatuses(Set(throttle), podsInNs, at)
        actual.size shouldBe 1
        actual.head shouldBe throttle.key -> expectedStatus

        val actualWithActiveOverrides =
          calcNextThrottleStatuses(Set(throttleWithActiveOverrides), podsInNs, at)
        actualWithActiveOverrides.size shouldBe 1
        actualWithActiveOverrides.head shouldBe throttleWithActiveOverrides.key -> expectedStatus

        val actualWithougActiveOverrides =
          calcNextThrottleStatuses(Set(throttleWithoutActiveOverrides), podsInNs, at)
        actualWithougActiveOverrides.size shouldBe 1
        actualWithougActiveOverrides.head shouldBe throttleWithoutActiveOverrides.key -> expectedStatus
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
        val throttleWithActiveOverrides = v1alpha1
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
                    pod = Option(100)
                  )),
              ),
              temporaryThresholdOverrides = List(
                TemporaryThresholdOverride(
                  begin = at.minusDays(1),
                  end = at.plusDays(1),
                  ResourceAmount(
                    resourceCounts = Option(ResourceCount(
                      pod = Option(1)
                    ))
                  )
                ))
            )
          )
          .withNamespace("default")
          .withName("t1")
        val throttleWithoutActiveOverrides = v1alpha1
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
              ),
              temporaryThresholdOverrides = List(
                TemporaryThresholdOverride(
                  begin = at.plusDays(1),
                  end = at.plusDays(2),
                  ResourceAmount(
                    resourceCounts = Option(ResourceCount(
                      pod = Option(100)
                    ))
                  )
                ))
            )
          )
          .withNamespace("default")
          .withName("t1")

        val podsInNs = Set(pod)
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
          ),
          calculatedThreshold = Option(
            CalculatedThreshold(ResourceAmount(
                                  resourceCounts = Option(
                                    ResourceCount(
                                      pod = Option(1)
                                    )),
                                ),
                                at))
        )

        val actual = calcNextThrottleStatuses(Set(throttle), podsInNs, at)
        actual.size shouldBe 1
        actual.head shouldBe throttle.key -> expectedStatus

        val actualWithActiveOverrides =
          calcNextThrottleStatuses(Set(throttleWithActiveOverrides), podsInNs, at)
        actualWithActiveOverrides.size shouldBe 1
        actualWithActiveOverrides.head shouldBe throttleWithActiveOverrides.key -> expectedStatus

        val actualWithoutActiveOverrides =
          calcNextThrottleStatuses(Set(throttleWithoutActiveOverrides), podsInNs, at)
        actualWithoutActiveOverrides.size shouldBe 1
        actualWithoutActiveOverrides.head shouldBe throttleWithoutActiveOverrides.key -> expectedStatus
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
        val throttleWithActiveOverrides = v1alpha1
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
                  pod = Option(200)
                ))),
              temporaryThresholdOverrides = List(
                TemporaryThresholdOverride(
                  begin = at.minusDays(1),
                  end = at.plusDays(1),
                  ResourceAmount(
                    resourceCounts = Option(ResourceCount(
                      pod = Option(2)
                    ))
                  )
                ))
            )
          )
          .withNamespace("default")
          .withName("t1")
        val throttleWithoutActiveOverrides = v1alpha1
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
                ))),
              temporaryThresholdOverrides = List(
                TemporaryThresholdOverride(begin = at.plusDays(1),
                                           end = at.plusDays(2),
                                           ResourceAmount(
                                             resourceCounts = Option(ResourceCount(
                                               pod = Option(200)
                                             ))
                                           )))
            )
          )
          .withNamespace("default")
          .withName("t1")

        val podsInNs = Set(pod)
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
          ),
          calculatedThreshold = Option(
            CalculatedThreshold(ResourceAmount(
                                  resourceCounts = Option(
                                    ResourceCount(
                                      pod = Option(2)
                                    ))
                                ),
                                at))
        )

        val actual = calcNextThrottleStatuses(Set(throttle), podsInNs, at)
        actual.size shouldBe 1
        actual.head shouldBe throttle.key -> expectedStatus

        val actualWithActiveOverrides =
          calcNextThrottleStatuses(Set(throttleWithActiveOverrides), podsInNs, at)
        actualWithActiveOverrides.size shouldBe 1
        actualWithActiveOverrides.head shouldBe throttleWithActiveOverrides.key -> expectedStatus

        val actualWithoutActiveOverrides =
          calcNextThrottleStatuses(Set(throttleWithoutActiveOverrides), podsInNs, at)
        actualWithoutActiveOverrides.size shouldBe 1
        actualWithoutActiveOverrides.head shouldBe throttleWithoutActiveOverrides.key -> expectedStatus
      }

      "should generate calculatedThreshold with messages when parse failure exists" in {
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
                ))),
              temporaryThresholdOverrides = List(
                TemporaryThresholdOverride(
                  "malformat",
                  "malformat",
                  ResourceAmount(
                    resourceCounts = Option(ResourceCount(
                      pod = Option(3)
                    ))
                  )
                )
              )
            )
          )
          .withNamespace("default")
          .withName("t1")

        val podsInNs = Set(pod)
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
          ),
          calculatedThreshold = Option(
            CalculatedThreshold(
              ResourceAmount(
                resourceCounts = Option(
                  ResourceCount(
                    pod = Option(2)
                  ))
              ),
              at,
              List("[0]: begin: Text 'malformat' could not be parsed at index 0, end: Text 'malformat' could not be parsed at index 0")
            ))
        )

        val actual = calcNextThrottleStatuses(Set(throttle), podsInNs, at)
        actual.size shouldBe 1
        actual.head shouldBe throttle.key -> expectedStatus
      }
    }
  }
}
