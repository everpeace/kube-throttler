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
import skuber.Resource.Quantity
import skuber.{Container, Pod, Resource}

class PodTotalRequestsSpec extends FreeSpec with Matchers {

  def pod(resourceRequirements: List[Option[Resource.Requirements]]): Pod = {
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
    Pod(name = "dummy", spec = spec)
  }

  "PodTotalResourceSpec" - {
    "can sum up only 'requests'" in {
      val requirements = List(
        Some(
          Resource.Requirements(limits = Map("rb"   -> Quantity("1")),
                                requests = Map("ra" -> Quantity("1")))),
        None,
        Some(
          Resource.Requirements(limits = Map("rb"   -> Quantity("1")),
                                requests = Map("ra" -> Quantity("1")))),
      )
      pod(requirements).totalRequests shouldBe Map("ra" -> Quantity("2"))
    }
    "can sum up even when no 'requests'" in {
      val requirements = List(None, None)
      pod(requirements).totalRequests shouldBe Map.empty
    }
  }

}
