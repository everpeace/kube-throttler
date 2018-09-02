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

import cats.implicits._
import org.scalatest.{FreeSpec, Matchers}
import skuber.Resource.Quantity

class ResourceListSpec extends FreeSpec with Matchers {
  "Quantity" - {
    "can add" in {
      (Quantity("1") add Quantity("2")) shouldBe Quantity("3")
    }

    "can compare" in {
      (Quantity("1") compare Quantity("2")) shouldBe -1
      (Quantity("1") compare Quantity("1")) shouldBe 0
      (Quantity("2") compare Quantity("1")) shouldBe 1
    }
  }

  "ResourceList" - {
    "can add" in {
      val lhs      = Map("ra" -> Quantity("1"))
      val rhs      = Map("ra" -> Quantity("3"), "rb" -> Quantity("1"))
      val expected = Map("ra" -> Quantity("4"), "rb" -> Quantity("1"))
      (lhs add rhs) shouldBe expected
    }
  }
}
