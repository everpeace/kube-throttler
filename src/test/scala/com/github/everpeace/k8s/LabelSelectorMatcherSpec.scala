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

package com.github.everpeace.k8s

import org.scalatest.{FreeSpec, Matchers}
import skuber.LabelSelector
import skuber.LabelSelector.IsEqualRequirement

class LabelSelectorMatcherSpec extends FreeSpec with Matchers {

  "LabelSelectorRequirementMatcher" - {
    "works correctly for ExistsRequirement" in {
      LabelSelector.ExistsRequirement("key").matches(Map("key"  -> "value")) shouldBe true
      LabelSelector.ExistsRequirement("key").matches(Map("key2" -> "value")) shouldBe false
      LabelSelector.ExistsRequirement("key").matches(Map.empty) shouldBe false
    }
    "works correctly for NotExistsRequirement" in {
      LabelSelector.NotExistsRequirement("key").matches(Map("key"  -> "value")) shouldBe false
      LabelSelector.NotExistsRequirement("key").matches(Map("key2" -> "value")) shouldBe true
      LabelSelector.NotExistsRequirement("key").matches(Map.empty) shouldBe true
    }
    "works correctly for IsEqualRequirement" in {
      LabelSelector.IsEqualRequirement("key", "value").matches(Map("key" -> "value")) should be(
        true)
      LabelSelector.IsEqualRequirement("key", "value").matches(Map("key" -> "value2")) should be(
        false)
      LabelSelector.IsEqualRequirement("key", "value").matches(Map.empty) shouldBe false
    }
    "works correctly for IsNotEqualRequirement" in {
      LabelSelector.IsNotEqualRequirement("key", "value").matches(Map("key" -> "value")) should be(
        false)
      LabelSelector.IsNotEqualRequirement("key", "value").matches(Map("key" -> "value2")) should be(
        true)
      LabelSelector.IsNotEqualRequirement("key", "value").matches(Map.empty) shouldBe true
    }
    "works correctly for InRequirement" in {
      LabelSelector.InRequirement("key", List("value")).matches(Map("key" -> "value")) should be(
        true)
      LabelSelector.InRequirement("key", List("value")).matches(Map("key" -> "value2")) should be(
        false)
      LabelSelector.InRequirement("key", List("value")).matches(Map.empty) shouldBe false
    }
    "works correctly for NotInRequirement" in {
      LabelSelector.NotInRequirement("key", List("value")).matches(Map("key" -> "value")) should be(
        false)
      LabelSelector
        .NotInRequirement("key", List("value"))
        .matches(Map("key" -> "value2")) shouldBe true
      LabelSelector.NotInRequirement("key", List("value")).matches(Map.empty) shouldBe true
    }
  }

  "LabelSelectorMatcher" - {
    "works correctly" in {
      LabelSelector(IsEqualRequirement("key", "value"), IsEqualRequirement("key2", "value2"))
        .matches(Map("key" -> "value", "key2" -> "value2")) shouldBe true
      LabelSelector(IsEqualRequirement("key", "value"), IsEqualRequirement("key2", "value2"))
        .matches(Map("key" -> "value")) shouldBe false
      LabelSelector(IsEqualRequirement("key", "value"), IsEqualRequirement("key2", "value2"))
        .matches(Map.empty) shouldBe false
      LabelSelector().matches(Map("key" -> "value")) shouldBe true
    }
  }
}
