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
import skuber.{Container, LabelSelector, Namespace, ObjectMeta, Pod, Resource}

class ClusterThrottleControllerLogicSpec
    extends FreeSpec
    with Matchers
    with ClusterThrottleControllerLogic {

  def mkNs(labels: Map[String, String] = Map.empty) =
    Namespace.from(
      ObjectMeta(
        name = commonMeta.namespace,
        labels = labels
      ))

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

  "ClusterThrottleControllerLogic" - {
    "isClusterThrottleAlreadyActiveFor" - {
      "should evaluate active when the clusterthrottle's status for some of the 'requests' resource of pod are active" in {
        val clthrottle = v1alpha1
          .ClusterThrottle(
            "ct1",
            v1alpha1.ClusterThrottle.Spec(
              throttlerName = "kube-throttler",
              selector = v1alpha1.ClusterThrottle.Selector(
                List(
                  v1alpha1.ClusterThrottle.SelectorItem(
                    LabelSelector(IsEqualRequirement("key", "value")))
                )),
              // throttle is defined on "r" and "s"
              threshold = ResourceAmount(
                resourceRequests = Map("r" -> Quantity("2"), "s" -> Quantity("3"))
              )
            )
          )
          .withStatus(v1alpha1.ClusterThrottle.Status(
            // only "r" is throttled
            throttled = IsResourceAmountThrottled(
              resourceRequests = Map("r" -> true, "s" -> false)
            ),
            used = ResourceAmount(
              resourceRequests = Map("r" -> Quantity("3"))
            )
          ))
        val ns = mkNs()
        val podRequestsR = mkPod(List(resourceRequirements(Map("r" -> Quantity("1"))))).copy(
          metadata = commonMeta,
        )
        isClusterThrottleAlreadyActiveFor(podRequestsR, ns, clthrottle) shouldBe true

        val podRequestsS = mkPod(List(resourceRequirements(Map("s" -> Quantity("3"))))).copy(
          metadata = commonMeta,
        )
        isClusterThrottleAlreadyActiveFor(podRequestsS, ns, clthrottle) shouldBe false

        val podRequestsT = mkPod(List(resourceRequirements(Map("t" -> Quantity("3"))))).copy(
          metadata = commonMeta,
        )
        isClusterThrottleAlreadyActiveFor(podRequestsT, ns, clthrottle) shouldBe false

        val podNoLabel = mkPod(List(resourceRequirements(Map("s" -> Quantity("3"))))).copy(
          metadata = commonMeta.copy(labels = Map.empty),
        )
        isClusterThrottleAlreadyActiveFor(podNoLabel, ns, clthrottle) shouldBe false
      }

      "should evaluate active when the clusterthrottle's status.podCounts is active" in {
        val clusterthrottle = v1alpha1
          .ClusterThrottle(
            "clt1",
            v1alpha1.ClusterThrottle.Spec(
              throttlerName = "kube-throttler",
              selector = v1alpha1.ClusterThrottle.Selector(
                List(
                  v1alpha1.ClusterThrottle.SelectorItem(
                    LabelSelector(IsEqualRequirement("key", "value")))
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
            v1alpha1.ClusterThrottle.Status(
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
          .withName("clt1")

        val ns = mkNs()

        val podRequestsR = mkPod(List(resourceRequirements(Map("r" -> Quantity("1"))))).copy(
          metadata = commonMeta,
        )
        isClusterThrottleAlreadyActiveFor(podRequestsR, ns, clusterthrottle) shouldBe true

        val podNoLabel = mkPod(List(resourceRequirements(Map("s" -> Quantity("3"))))).copy(
          metadata = commonMeta.copy(labels = Map.empty),
        )
        isClusterThrottleAlreadyActiveFor(podNoLabel, ns, clusterthrottle) shouldBe false
      }
    }
    "isClusterThrottleInsufficientFor" - {
      "should evaluated 'insufficient' when there are no space for some of the 'requests' resource of pods" in {
        val clthrottle = v1alpha1
          .ClusterThrottle(
            "ct1",
            v1alpha1.ClusterThrottle.Spec(
              throttlerName = "kube-throttler",
              selector = v1alpha1.ClusterThrottle.Selector(
                List(
                  v1alpha1.ClusterThrottle.SelectorItem(
                    LabelSelector(IsEqualRequirement("key", "value")))
                )),
              // throttle is defined on "r" and "s"
              threshold = ResourceAmount(
                resourceRequests = Map("r" -> Quantity("3"), "s" -> Quantity("2"))
              )
            )
          )
          .withStatus(v1alpha1.ClusterThrottle.Status(
            // only "r" is throttled
            throttled = IsResourceAmountThrottled(
              resourceRequests = Map("r" -> false, "s" -> false)
            ),
            used = ResourceAmount(
              resourceRequests = Map("r" -> Quantity("1"))
            )
          ))
        val clthrottleWithCalculated = v1alpha1
          .ClusterThrottle(
            "ct1",
            v1alpha1.ClusterThrottle.Spec(
              throttlerName = "kube-throttler",
              selector = v1alpha1.ClusterThrottle.Selector(
                List(
                  v1alpha1.ClusterThrottle.SelectorItem(
                    LabelSelector(IsEqualRequirement("key", "value")))
                )),
              // throttle is defined on "r" and "s" with high values
              threshold = ResourceAmount(
                resourceRequests = Map("r" -> Quantity("300"), "s" -> Quantity("200"))
              )
            )
          )
          .withStatus(v1alpha1.ClusterThrottle.Status(
            // only "r" is throttled
            throttled = IsResourceAmountThrottled(
              resourceRequests = Map("r" -> false, "s" -> false)
            ),
            used = ResourceAmount(
              resourceRequests = Map("r" -> Quantity("1"))
            ),
            calculatedThreshold = Option(
              CalculatedThreshold(
                ResourceAmount(
                  // throttle is overridden on "r" and "s" with high values
                  resourceRequests = Map("r" -> Quantity("3"), "s" -> Quantity("2"))
                ),
                at
              ))
          ))
        val ns = mkNs()

        val podRequestsR1 = mkPod(List(resourceRequirements(Map("r" -> Quantity("1"))))).copy(
          metadata = commonMeta,
        )
        isClusterThrottleInsufficientFor(podRequestsR1, ns, clthrottle) shouldBe false
        isClusterThrottleInsufficientFor(podRequestsR1, ns, clthrottleWithCalculated) shouldBe false

        val podRequestsR2 = mkPod(List(resourceRequirements(Map("r" -> Quantity("2"))))).copy(
          metadata = commonMeta,
        )
        isClusterThrottleInsufficientFor(podRequestsR2, ns, clthrottle) shouldBe false
        isClusterThrottleInsufficientFor(podRequestsR2, ns, clthrottleWithCalculated) shouldBe false

        val podRequestsR3 = mkPod(List(resourceRequirements(Map("r" -> Quantity("3"))))).copy(
          metadata = commonMeta,
        )
        isClusterThrottleInsufficientFor(podRequestsR3, ns, clthrottle) shouldBe true
        isClusterThrottleInsufficientFor(podRequestsR3, ns, clthrottleWithCalculated) shouldBe true

        val podRequestsS1 = mkPod(List(resourceRequirements(Map("s" -> Quantity("1"))))).copy(
          metadata = commonMeta,
        )
        isClusterThrottleInsufficientFor(podRequestsS1, ns, clthrottle) shouldBe false
        isClusterThrottleInsufficientFor(podRequestsS1, ns, clthrottleWithCalculated) shouldBe false

        val podRequestsS2 = mkPod(List(resourceRequirements(Map("s" -> Quantity("2"))))).copy(
          metadata = commonMeta,
        )
        isClusterThrottleInsufficientFor(podRequestsS2, ns, clthrottle) shouldBe false
        isClusterThrottleInsufficientFor(podRequestsS2, ns, clthrottleWithCalculated) shouldBe false

        val podRequestsS3 = mkPod(List(resourceRequirements(Map("s" -> Quantity("3"))))).copy(
          metadata = commonMeta,
        )
        isClusterThrottleInsufficientFor(podRequestsS3, ns, clthrottle) shouldBe true
        isClusterThrottleInsufficientFor(podRequestsS3, ns, clthrottleWithCalculated) shouldBe true

        val podRequestR1S1 =
          mkPod(List(resourceRequirements(Map("r" -> Quantity("1"), "s" -> Quantity("1"))))).copy(
            metadata = commonMeta,
          )
        isClusterThrottleInsufficientFor(podRequestR1S1, ns, clthrottle) shouldBe false
        isClusterThrottleInsufficientFor(podRequestR1S1, ns, clthrottleWithCalculated) shouldBe false

        val podRequestR1S3 =
          mkPod(List(resourceRequirements(Map("r" -> Quantity("1"), "s" -> Quantity("3"))))).copy(
            metadata = commonMeta,
          )
        isClusterThrottleInsufficientFor(podRequestR1S3, ns, clthrottle) shouldBe true
        isClusterThrottleInsufficientFor(podRequestR1S3, ns, clthrottleWithCalculated) shouldBe true

        val podRequestR3S1 =
          mkPod(List(resourceRequirements(Map("r" -> Quantity("3"), "s" -> Quantity("1"))))).copy(
            metadata = commonMeta,
          )
        isClusterThrottleInsufficientFor(podRequestR3S1, ns, clthrottle) shouldBe true
        isClusterThrottleInsufficientFor(podRequestR3S1, ns, clthrottleWithCalculated) shouldBe true

        val podRequestR3S3 =
          mkPod(List(resourceRequirements(Map("r" -> Quantity("3"), "s" -> Quantity("3"))))).copy(
            metadata = commonMeta,
          )
        isClusterThrottleInsufficientFor(podRequestR3S3, ns, clthrottle) shouldBe true
        isClusterThrottleInsufficientFor(podRequestR3S3, ns, clthrottleWithCalculated) shouldBe true

        val podNoRequests = mkPod(List.empty).copy(
          metadata = commonMeta,
        )
        isClusterThrottleInsufficientFor(podNoRequests, ns, clthrottle) shouldBe false
        isClusterThrottleInsufficientFor(podNoRequests, ns, clthrottleWithCalculated) shouldBe false

        val podNoLabel = mkPod(List(resourceRequirements(Map("s" -> Quantity("3"))))).copy(
          metadata = commonMeta.copy(labels = Map.empty),
        )
        isClusterThrottleInsufficientFor(podNoLabel, ns, clthrottle) shouldBe false
        isClusterThrottleInsufficientFor(podNoLabel, ns, clthrottleWithCalculated) shouldBe false
      }

      "should evaluate 'insufficient' when there are no space for podCount" in {
        val clusterthrottleNoSpace = v1alpha1
          .ClusterThrottle(
            "clt1",
            v1alpha1.ClusterThrottle.Spec(
              throttlerName = "kube-throttler",
              selector = v1alpha1.ClusterThrottle.Selector(
                List(
                  v1alpha1.ClusterThrottle.SelectorItem(
                    LabelSelector(IsEqualRequirement("key", "value")))
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
            v1alpha1.ClusterThrottle.Status(
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
          .withName("clt1")
        val clusterthrottleOvrriddenNoSpace = v1alpha1
          .ClusterThrottle(
            "clt1",
            v1alpha1.ClusterThrottle.Spec(
              throttlerName = "kube-throttler",
              selector = v1alpha1.ClusterThrottle.Selector(
                List(
                  v1alpha1.ClusterThrottle.SelectorItem(
                    LabelSelector(IsEqualRequirement("key", "value")))
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
            v1alpha1.ClusterThrottle.Status(
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
          .withName("clt1")

        val ns = mkNs()
        val podRequestsR = mkPod(List(resourceRequirements(Map("r" -> Quantity("1"))))).copy(
          metadata = commonMeta,
        )
        isClusterThrottleInsufficientFor(podRequestsR, ns, clusterthrottleNoSpace) shouldBe true
        isClusterThrottleInsufficientFor(podRequestsR, ns, clusterthrottleOvrriddenNoSpace) shouldBe true

        val podNoLabel = mkPod(List(resourceRequirements(Map("s" -> Quantity("3"))))).copy(
          metadata = commonMeta.copy(labels = Map.empty),
        )
        isClusterThrottleInsufficientFor(podNoLabel, ns, clusterthrottleNoSpace) shouldBe false
        isClusterThrottleInsufficientFor(podNoLabel, ns, clusterthrottleOvrriddenNoSpace) shouldBe false

        val clusterthrottleWithSpace = v1alpha1
          .ClusterThrottle(
            "clt1",
            v1alpha1.ClusterThrottle.Spec(
              throttlerName = "kube-throttler",
              selector = v1alpha1.ClusterThrottle.Selector(
                List(
                  v1alpha1.ClusterThrottle.SelectorItem(
                    LabelSelector(IsEqualRequirement("key", "value")))
                )),
              threshold = ResourceAmount(
                resourceCounts = Option(
                  ResourceCount(
                    pod = Option(2)
                  ))
              )
            )
          )
          .withStatus(
            v1alpha1.ClusterThrottle.Status(
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
          .withName("clt1")
        val clusterthrottleOverriddenWithSpace = v1alpha1
          .ClusterThrottle(
            "clt1",
            v1alpha1.ClusterThrottle.Spec(
              throttlerName = "kube-throttler",
              selector = v1alpha1.ClusterThrottle.Selector(
                List(
                  v1alpha1.ClusterThrottle.SelectorItem(
                    LabelSelector(IsEqualRequirement("key", "value")))
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
            v1alpha1.ClusterThrottle.Status(
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
          .withName("clt1")
        isClusterThrottleInsufficientFor(podRequestsR, ns, clusterthrottleWithSpace) shouldBe false
        isClusterThrottleInsufficientFor(podRequestsR, ns, clusterthrottleOverriddenWithSpace) shouldBe false
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
              selector = v1alpha1.ClusterThrottle.Selector(
                List(
                  v1alpha1.ClusterThrottle.SelectorItem(
                    LabelSelector(IsEqualRequirement("key", "value")))
                )),
              threshold = ResourceAmount(
                resourceRequests = Map("s" -> Quantity("3"))
              )
            )
          )
        val clthrottleWithActiveOverrides = v1alpha1
          .ClusterThrottle(
            "ct1",
            v1alpha1.ClusterThrottle.Spec(
              throttlerName = "kube-throttler",
              selector = v1alpha1.ClusterThrottle.Selector(
                List(
                  v1alpha1.ClusterThrottle.SelectorItem(
                    LabelSelector(IsEqualRequirement("key", "value")))
                )),
              threshold = ResourceAmount(
                resourceRequests = Map("s" -> Quantity("30"))
              ),
              temporaryThresholdOverrides = List(
                TemporaryThresholdOverride(
                  begin = at.minusDays(1),
                  end = at.plusDays(1),
                  ResourceAmount(
                    resourceRequests = Map("s" -> Quantity("3"))
                  )
                ))
            )
          )
        val clthrottleWithoutActiveOverrides = v1alpha1
          .ClusterThrottle(
            "ct1",
            v1alpha1.ClusterThrottle.Spec(
              throttlerName = "kube-throttler",
              selector = v1alpha1.ClusterThrottle.Selector(
                List(
                  v1alpha1.ClusterThrottle.SelectorItem(
                    LabelSelector(IsEqualRequirement("key", "value")))
                )),
              threshold = ResourceAmount(
                resourceRequests = Map("s" -> Quantity("3"))
              ),
              temporaryThresholdOverrides = List(
                TemporaryThresholdOverride(
                  begin = at.plusDays(1),
                  end = at.plusDays(2),
                  ResourceAmount(
                    resourceRequests = Map("s" -> Quantity("30"))
                  )
                ))
            )
          )
        val nss  = Map("" -> mkNs())
        val pods = Set(pod)
        val expectedStatus = v1alpha1.ClusterThrottle.Status(
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

        val actual = calcNextClusterThrottleStatuses(Set(clthrottle), pods, nss, at)
        actual.size shouldBe 1
        actual.head shouldBe clthrottle.key -> expectedStatus

        val actualWithActiveOverrides =
          calcNextClusterThrottleStatuses(Set(clthrottleWithActiveOverrides), pods, nss, at)
        actualWithActiveOverrides.size shouldBe 1
        actualWithActiveOverrides.head shouldBe clthrottleWithActiveOverrides.key -> expectedStatus

        val actualWithoutActiveOverrides =
          calcNextClusterThrottleStatuses(Set(clthrottleWithoutActiveOverrides), pods, nss, at)
        actualWithoutActiveOverrides.size shouldBe 1
        actualWithoutActiveOverrides.head shouldBe clthrottleWithoutActiveOverrides.key -> expectedStatus
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
              selector = v1alpha1.ClusterThrottle.Selector(
                List(
                  v1alpha1.ClusterThrottle.SelectorItem(
                    LabelSelector(IsEqualRequirement("key", "value")))
                )),
              threshold = ResourceAmount(
                resourceRequests = Map("r" -> Quantity("2"), "s" -> Quantity("3"))
              )
            )
          )
        val clthrottleWithActiveOverrides = v1alpha1
          .ClusterThrottle(
            "ct1",
            v1alpha1.ClusterThrottle.Spec(
              throttlerName = "kube-throttler",
              selector = v1alpha1.ClusterThrottle.Selector(
                List(
                  v1alpha1.ClusterThrottle.SelectorItem(
                    LabelSelector(IsEqualRequirement("key", "value")))
                )),
              threshold = ResourceAmount(
                resourceRequests = Map("r" -> Quantity("20"), "s" -> Quantity("30"))
              ),
              temporaryThresholdOverrides = List(
                TemporaryThresholdOverride(
                  begin = at.minusDays(1),
                  end = at.plusDays(1),
                  ResourceAmount(
                    resourceRequests = Map("r" -> Quantity("2"), "s" -> Quantity("3"))
                  )
                ))
            )
          )
        val clthrottleWithoutActiveOverrides = v1alpha1
          .ClusterThrottle(
            "ct1",
            v1alpha1.ClusterThrottle.Spec(
              throttlerName = "kube-throttler",
              selector = v1alpha1.ClusterThrottle.Selector(
                List(
                  v1alpha1.ClusterThrottle.SelectorItem(
                    LabelSelector(IsEqualRequirement("key", "value")))
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

        val ns   = mkNs()
        val nss  = Map(ns.name -> ns)
        val pods = Set(pod1, pod2)
        val expectedStatus = v1alpha1.ClusterThrottle.Status(
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

        val actual = calcNextClusterThrottleStatuses(Set(clthrottle), pods, nss, at)
        actual.size shouldBe 1
        actual.head shouldBe clthrottle.key -> expectedStatus

        val actualWithActiveOverrides =
          calcNextClusterThrottleStatuses(Set(clthrottleWithActiveOverrides), pods, nss, at)
        actualWithActiveOverrides.size shouldBe 1
        actualWithActiveOverrides.head shouldBe clthrottleWithActiveOverrides.key -> expectedStatus

        val actualWithoutActiveOverrides =
          calcNextClusterThrottleStatuses(Set(clthrottleWithoutActiveOverrides), pods, nss, at)
        actualWithoutActiveOverrides.size shouldBe 1
        actualWithoutActiveOverrides.head shouldBe clthrottleWithoutActiveOverrides.key -> expectedStatus
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
              selector = v1alpha1.ClusterThrottle.Selector(
                List(
                  v1alpha1.ClusterThrottle.SelectorItem(
                    LabelSelector(IsEqualRequirement("key", "value")))
                )),
              threshold = ResourceAmount(
                resourceRequests = Map("r" -> Quantity("1"))
              )
            )
          )
        val clthrottleWithActiveOverrides = v1alpha1
          .ClusterThrottle(
            "ct1",
            v1alpha1.ClusterThrottle.Spec(
              throttlerName = "kube-throttler",
              selector = v1alpha1.ClusterThrottle.Selector(
                List(
                  v1alpha1.ClusterThrottle.SelectorItem(
                    LabelSelector(IsEqualRequirement("key", "value")))
                )),
              threshold = ResourceAmount(
                resourceRequests = Map("r" -> Quantity("10"))
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
        val clthrottleWithoutActiveOverrides = v1alpha1
          .ClusterThrottle(
            "ct1",
            v1alpha1.ClusterThrottle.Spec(
              throttlerName = "kube-throttler",
              selector = v1alpha1.ClusterThrottle.Selector(
                List(
                  v1alpha1.ClusterThrottle.SelectorItem(
                    LabelSelector(IsEqualRequirement("key", "value")))
                )),
              threshold = ResourceAmount(
                resourceRequests = Map("r" -> Quantity("1"))
              ),
              temporaryThresholdOverrides = List(
                TemporaryThresholdOverride(
                  begin = at.plusDays(1),
                  end = at.plusDays(2),
                  ResourceAmount(
                    resourceRequests = Map("r" -> Quantity("10"))
                  )
                ))
            )
          )

        val ns   = mkNs()
        val nss  = Map(ns.name -> ns)
        val pods = Set(pod)
        val expectedStatus = v1alpha1.ClusterThrottle.Status(
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

        val actual = calcNextClusterThrottleStatuses(Set(clthrottle), pods, nss, at)
        actual.size shouldBe 1
        actual.head shouldBe clthrottle.key -> expectedStatus

        val actualWithActiveOverrides =
          calcNextClusterThrottleStatuses(Set(clthrottleWithActiveOverrides), pods, nss, at)
        actualWithActiveOverrides.size shouldBe 1
        actualWithActiveOverrides.head shouldBe clthrottleWithActiveOverrides.key -> expectedStatus

        val actualWithoutActiveOverrides =
          calcNextClusterThrottleStatuses(Set(clthrottleWithoutActiveOverrides), pods, nss, at)
        actualWithoutActiveOverrides.size shouldBe 1
        actualWithoutActiveOverrides.head shouldBe clthrottleWithoutActiveOverrides.key -> expectedStatus
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
              selector = v1alpha1.ClusterThrottle.Selector(
                List(
                  v1alpha1.ClusterThrottle.SelectorItem(
                    LabelSelector(IsEqualRequirement("key", "value")))
                )),
              threshold = ResourceAmount(
                resourceRequests = Map("r" -> Quantity("3"))
              )
            )
          )
        val clthrottleWithActiveOverrides = v1alpha1
          .ClusterThrottle(
            "ct1",
            v1alpha1.ClusterThrottle.Spec(
              throttlerName = "kube-throttler",
              selector = v1alpha1.ClusterThrottle.Selector(
                List(
                  v1alpha1.ClusterThrottle.SelectorItem(
                    LabelSelector(IsEqualRequirement("key", "value")))
                )),
              threshold = ResourceAmount(
                resourceRequests = Map("r" -> Quantity("30"))
              ),
              temporaryThresholdOverrides = List(
                TemporaryThresholdOverride(
                  begin = at.minusDays(1),
                  end = at.plusDays(1),
                  ResourceAmount(
                    resourceRequests = Map("r" -> Quantity("3"))
                  )
                ))
            )
          )
        val clthrottleWithoutActiveOverrides = v1alpha1
          .ClusterThrottle(
            "ct1",
            v1alpha1.ClusterThrottle.Spec(
              throttlerName = "kube-throttler",
              selector = v1alpha1.ClusterThrottle.Selector(
                List(
                  v1alpha1.ClusterThrottle.SelectorItem(
                    LabelSelector(IsEqualRequirement("key", "value")))
                )),
              threshold = ResourceAmount(
                resourceRequests = Map("r" -> Quantity("3"))
              ),
              temporaryThresholdOverrides = List(
                TemporaryThresholdOverride(
                  begin = at.plusDays(1),
                  end = at.plusDays(2),
                  ResourceAmount(
                    resourceRequests = Map("r" -> Quantity("30"))
                  )
                ))
            )
          )

        val nss  = Map("" -> mkNs())
        val pods = Set(pod)
        val expectedStatus = v1alpha1.ClusterThrottle.Status(
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

        val actual = calcNextClusterThrottleStatuses(Set(clthrottle), pods, nss, at)
        actual.size shouldBe 1
        actual.head shouldBe clthrottle.key -> expectedStatus

        val actualWithActiveOverrides =
          calcNextClusterThrottleStatuses(Set(clthrottleWithActiveOverrides), pods, nss, at)
        actualWithActiveOverrides.size shouldBe 1
        actualWithActiveOverrides.head shouldBe clthrottleWithActiveOverrides.key -> expectedStatus

        val actualWithoutActiveOverrides =
          calcNextClusterThrottleStatuses(Set(clthrottleWithoutActiveOverrides), pods, nss, at)
        actualWithoutActiveOverrides.size shouldBe 1
        actualWithoutActiveOverrides.head shouldBe clthrottleWithoutActiveOverrides.key -> expectedStatus
      }

      "should throttle when total number of running pods exceeds threshold" in {
        val pod = mkPod(
          List(
            resourceRequirements(Map("r" -> Quantity("2")))
          )).copy(
          metadata = commonMeta,
          status = phase(Pod.Phase.Running)
        )
        val clthrottle = v1alpha1
          .ClusterThrottle(
            "clt1",
            v1alpha1.ClusterThrottle.Spec(
              throttlerName = "kube-throttler",
              selector = v1alpha1.ClusterThrottle.Selector(
                List(
                  v1alpha1.ClusterThrottle.SelectorItem(
                    LabelSelector(IsEqualRequirement("key", "value")))
                )),
              threshold = ResourceAmount(
                resourceCounts = Option(
                  ResourceCount(
                    pod = Option(1)
                  )),
              )
            )
          )
          .withName("clt1")
        val clthrottleWithActiveOverrides = v1alpha1
          .ClusterThrottle(
            "clt1",
            v1alpha1.ClusterThrottle.Spec(
              throttlerName = "kube-throttler",
              selector = v1alpha1.ClusterThrottle.Selector(
                List(
                  v1alpha1.ClusterThrottle.SelectorItem(
                    LabelSelector(IsEqualRequirement("key", "value")))
                )),
              threshold = ResourceAmount(
                resourceCounts = Option(
                  ResourceCount(
                    pod = Option(10)
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
          .withName("clt1")
        val clthrottleWithoutActiveOverrides = v1alpha1
          .ClusterThrottle(
            "clt1",
            v1alpha1.ClusterThrottle.Spec(
              throttlerName = "kube-throttler",
              selector = v1alpha1.ClusterThrottle.Selector(
                List(
                  v1alpha1.ClusterThrottle.SelectorItem(
                    LabelSelector(IsEqualRequirement("key", "value")))
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
                      pod = Option(10)
                    ))
                  )
                ))
            )
          )
          .withName("clt1")

        val ns   = mkNs()
        val nss  = Map(ns.name -> ns)
        val pods = Set(pod)
        val expectedStatus = v1alpha1.ClusterThrottle.Status(
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

        val actual = calcNextClusterThrottleStatuses(Set(clthrottle), pods, nss, at)
        actual.size shouldBe 1
        actual.head shouldBe clthrottle.key -> expectedStatus

        val actualWithActiveOverrides =
          calcNextClusterThrottleStatuses(Set(clthrottleWithActiveOverrides), pods, nss, at)
        actualWithActiveOverrides.size shouldBe 1
        actualWithActiveOverrides.head shouldBe clthrottleWithActiveOverrides.key -> expectedStatus

        val actualWithoutActiveOverrides =
          calcNextClusterThrottleStatuses(Set(clthrottleWithoutActiveOverrides), pods, nss, at)
        actualWithoutActiveOverrides.size shouldBe 1
        actualWithoutActiveOverrides.head shouldBe clthrottleWithoutActiveOverrides.key -> expectedStatus
      }

      "should not throttle when total number of running pods fall below threshold" in {
        val pod = mkPod(
          List(
            resourceRequirements(Map("r" -> Quantity("2")))
          )).copy(
          metadata = commonMeta,
          status = phase(Pod.Phase.Running)
        )
        val clthrottle = v1alpha1
          .ClusterThrottle(
            "clt1",
            v1alpha1.ClusterThrottle.Spec(
              throttlerName = "kube-throttler",
              selector = v1alpha1.ClusterThrottle.Selector(
                List(
                  v1alpha1.ClusterThrottle.SelectorItem(
                    LabelSelector(IsEqualRequirement("key", "value")))
                )),
              threshold = ResourceAmount(
                resourceCounts = Option(
                  ResourceCount(
                    pod = Option(2)
                  )),
              )
            )
          )
          .withName("clt1")
        val clthrottleWithActiveOverrides = v1alpha1
          .ClusterThrottle(
            "clt1",
            v1alpha1.ClusterThrottle.Spec(
              throttlerName = "kube-throttler",
              selector = v1alpha1.ClusterThrottle.Selector(
                List(
                  v1alpha1.ClusterThrottle.SelectorItem(
                    LabelSelector(IsEqualRequirement("key", "value")))
                )),
              threshold = ResourceAmount(
                resourceCounts = Option(
                  ResourceCount(
                    pod = Option(20)
                  )),
              ),
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
          .withName("clt1")
        val clthrottleWithoutActiveOverrides = v1alpha1
          .ClusterThrottle(
            "clt1",
            v1alpha1.ClusterThrottle.Spec(
              throttlerName = "kube-throttler",
              selector = v1alpha1.ClusterThrottle.Selector(
                List(
                  v1alpha1.ClusterThrottle.SelectorItem(
                    LabelSelector(IsEqualRequirement("key", "value")))
                )),
              threshold = ResourceAmount(
                resourceCounts = Option(
                  ResourceCount(
                    pod = Option(2)
                  ))
              ),
              temporaryThresholdOverrides = List(
                TemporaryThresholdOverride(
                  begin = at.plusDays(1),
                  end = at.plusDays(2),
                  ResourceAmount(
                    resourceCounts = Option(ResourceCount(
                      pod = Option(20)
                    ))
                  )
                ))
            )
          )
          .withName("clt1")

        val ns   = mkNs()
        val nss  = Map(ns.name -> ns)
        val pods = Set(pod)
        val expectedStatus = v1alpha1.ClusterThrottle.Status(
          throttled = IsResourceAmountThrottled(
            resourceCounts = Option(
              IsResourceCountThrottled(
                pod = Option(false)
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
                                      pod = Option(2)
                                    )),
                                ),
                                at))
        )

        val actual = calcNextClusterThrottleStatuses(Set(clthrottle), pods, nss, at)
        actual.size shouldBe 1
        actual.head shouldBe clthrottle.key -> expectedStatus

        val actualWithActiveOverrides =
          calcNextClusterThrottleStatuses(Set(clthrottleWithActiveOverrides), pods, nss, at)
        actualWithActiveOverrides.size shouldBe 1
        actualWithActiveOverrides.head shouldBe clthrottleWithActiveOverrides.key -> expectedStatus

        val actualWithoutActiveOverrides =
          calcNextClusterThrottleStatuses(Set(clthrottleWithoutActiveOverrides), pods, nss, at)
        actualWithoutActiveOverrides.size shouldBe 1
        actualWithoutActiveOverrides.head shouldBe clthrottleWithoutActiveOverrides.key -> expectedStatus
      }

      "should generate calculatedThreshold with messages when parse failure exists" in {
        val pod = mkPod(
          List(
            resourceRequirements(Map("r" -> Quantity("2")))
          )).copy(
          metadata = commonMeta,
          status = phase(Pod.Phase.Running)
        )
        val clthrottle = v1alpha1
          .ClusterThrottle(
            "clt1",
            v1alpha1.ClusterThrottle.Spec(
              throttlerName = "kube-throttler",
              selector = v1alpha1.ClusterThrottle.Selector(
                List(
                  v1alpha1.ClusterThrottle.SelectorItem(
                    LabelSelector(IsEqualRequirement("key", "value")))
                )),
              threshold = ResourceAmount(
                resourceCounts = Option(
                  ResourceCount(
                    pod = Option(1)
                  )),
              ),
              temporaryThresholdOverrides = List(
                TemporaryThresholdOverride(
                  "malformat",
                  "malformat",
                  ResourceAmount(
                    resourceCounts = Option(ResourceCount(
                      pod = Option(2)
                    ))
                  )
                ))
            )
          )
          .withName("clt1")

        val ns   = mkNs()
        val nss  = Map(ns.name -> ns)
        val pods = Set(pod)
        val expectedStatus = v1alpha1.ClusterThrottle.Status(
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
            CalculatedThreshold(
              ResourceAmount(
                resourceCounts = Option(
                  ResourceCount(
                    pod = Option(1)
                  )),
              ),
              at,
              List("[0]: begin: Text 'malformat' could not be parsed at index 0, end: Text 'malformat' could not be parsed at index 0")
            ))
        )
        val actual =
          calcNextClusterThrottleStatuses(Set(clthrottle), pods, nss, at)
        actual.size shouldBe 1
        actual.head shouldBe clthrottle.key -> expectedStatus
      }

      "should return empty when next status is the same except for calculatedAt" in {
        val pod = mkPod(
          List(
            resourceRequirements(Map("r" -> Quantity("2")))
          )).copy(
          metadata = commonMeta,
          status = phase(Pod.Phase.Running)
        )
        val clthrottle = v1alpha1
          .ClusterThrottle(
            "t1",
            v1alpha1.ClusterThrottle.Spec(
              throttlerName = "kube-throttler",
              selector = v1alpha1.ClusterThrottle.Selector(
                List(
                  v1alpha1.ClusterThrottle.SelectorItem(
                    LabelSelector(IsEqualRequirement("key", "value")))
                )),
              threshold = ResourceAmount(
                resourceRequests = Map("r" -> Quantity("1"))
              )
            )
          )
          .withName("t1")

        val ns   = mkNs()
        val nss  = Map(ns.name -> ns)
        val pods = Set(pod)

        val status = v1alpha1.ClusterThrottle.Status(
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

        val actual = calcNextClusterThrottleStatuses(Set(clthrottle.withStatus(status)),
                                                     pods,
                                                     nss,
                                                     at.plusMinutes(1))
        actual shouldBe empty
      }
    }
  }
}
