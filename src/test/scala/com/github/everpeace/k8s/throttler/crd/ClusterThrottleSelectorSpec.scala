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
import org.scalatest.{FreeSpec, Matchers}
import skuber.{LabelSelector, Namespace, ObjectMeta, Pod}

class ClusterThrottleSelectorSpec extends FreeSpec with Matchers with v1alpha1.Syntax {
  def mkNs(name: String, labels: Map[String, String] = Map.empty) =
    Namespace.from(
      ObjectMeta(
        name = name,
        labels = labels
      ))
  def mkPod(namespace: String = "", name: String = "", labels: Map[String, String] = Map.empty) =
    Pod
      .named("")
      .copy(
        metadata = ObjectMeta(
          namespace = namespace,
          name = name,
          labels = labels
        )
      )

  "SelecterItem" - {
    "maches(pod, namespace) should not match anything when pod and namespace are different" in {
      val selectorItem = v1alpha1.ClusterThrottle.SelectorItem(
        LabelSelector(LabelSelector.InRequirement("key1", List("value1"))),
        Option(LabelSelector(LabelSelector.InRequirement("nskey1", List("nsvalue1"))))
      )
      val p1  = mkPod("default")
      val ns1 = mkNs("different")
      selectorItem.matches(p1, ns1) shouldBe false
    }

    "with podSelector and namespaceSelector should be evaluated in AND-ed" in {
      val selectorItem = v1alpha1.ClusterThrottle.SelectorItem(
        LabelSelector(LabelSelector.InRequirement("key1", List("value1"))),
        Option(LabelSelector(LabelSelector.InRequirement("nskey1", List("nsvalue1"))))
      )

      val p1  = mkPod("default", "pod", Map("key1" -> "value1"))
      val p2  = mkPod("default", "pod", Map("key2" -> "value2"))
      val ns1 = mkNs(p1.namespace, Map("nskey1"    -> "nsvalue1"))
      val ns2 = mkNs(p2.namespace, Map("nskey2"    -> "nsvalue2"))

      selectorItem.matches(p1, ns1) shouldBe true
      selectorItem.matches(p1, ns2) shouldBe false
      selectorItem.matches(p2, ns1) shouldBe false
      selectorItem.matches(p2, ns2) shouldBe false
    }

    "with empty namespaceSelector should be matched to all namespaces" in {
      val selectorItem = v1alpha1.ClusterThrottle.SelectorItem(
        LabelSelector(LabelSelector.InRequirement("key1", List("value1"))),
        None
      )
      val p1  = mkPod("default", "pod", Map("key1" -> "value1"))
      val p2  = mkPod("default2", "pod", Map("key1" -> "value1"))
      val ns1 = mkNs(p1.namespace)
      val ns2 = mkNs(p2.namespace)
      selectorItem.matches(p1, ns1) shouldBe true
      selectorItem.matches(p2, ns2) shouldBe true
    }
  }

  "Selector" - {
    "matches(pod, namespace) returns false when pod and namespace is different" in {
      val p  = mkPod()
      val ns = mkNs("ns1")
      v1alpha1.ClusterThrottle.Selector(List.empty).matches(p, ns) shouldBe false
    }
    "with empty selecterTerms doesn't match anything" in {
      val p  = mkPod()
      val ns = mkNs(p.namespace)
      v1alpha1.ClusterThrottle.Selector(List.empty).matches(p, ns) shouldBe false
    }
    "nonEmpty selecterTerms are evaluated in OR-ed" in {
      val selector = v1alpha1.ClusterThrottle.Selector(
        List(
          v1alpha1.ClusterThrottle.SelectorItem(
            LabelSelector(LabelSelector.InRequirement("key1", List("value1")))),
          v1alpha1.ClusterThrottle.SelectorItem(
            LabelSelector(LabelSelector.InRequirement("key2", List("value2")))),
        ))

      val p1 = mkPod("default", "pod", Map("key1" -> "value1"))
      val p2 = mkPod("default", "pod", Map("key2" -> "value2"))
      val p3 = mkPod("default", "pod", Map("key3" -> "value3"))
      val ns = mkNs("default")

      selector.matches(p1, ns) shouldBe true
      selector.matches(p2, ns) shouldBe true
      selector.matches(p3, ns) shouldBe false
    }
  }

}
