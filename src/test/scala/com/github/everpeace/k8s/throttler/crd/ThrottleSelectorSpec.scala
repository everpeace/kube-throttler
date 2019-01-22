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

class ThrottleSelectorSpec extends FreeSpec with Matchers with v1alpha1.Throttle.Implicits {
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

  "Selector" - {
    "with empty selecterTerms doesn't match anything" in {
      v1alpha1.Throttle.Selector(List.empty).matches(mkPod()) shouldBe false
    }

    "with non empty selecterTerms are evaluated in OR-ed" in {
      val selector = v1alpha1.Throttle.Selector(
        List(
          v1alpha1.Throttle.SelectorItem(
            LabelSelector(LabelSelector.InRequirement("key1", List("value1")))),
          v1alpha1.Throttle.SelectorItem(
            LabelSelector(LabelSelector.InRequirement("key2", List("value2")))),
        ))

      val p1 = mkPod("", "", Map("key1" -> "value1"))
      val p2 = mkPod("", "", Map("key2" -> "value2"))
      val p3 = mkPod("", "", Map("key3" -> "value3"))

      selector.matches(p1) shouldBe true
      selector.matches(p2) shouldBe true
      selector.matches(p3) shouldBe false
    }
  }

}
