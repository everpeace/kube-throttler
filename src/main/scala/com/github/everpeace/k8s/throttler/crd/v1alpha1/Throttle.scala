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

package com.github.everpeace.k8s.throttler.crd.v1alpha1

import com.github.everpeace.k8s.throttler.crd.v1alpha1
import com.github.everpeace.k8s.throttler.crd.v1alpha1.Implicits._
import skuber.Resource.ResourceList
import skuber.apiextensions.CustomResourceDefinition
import skuber.{CustomResource, LabelSelector}

object Throttle {

  case class Spec(selector: LabelSelector, threshold: ResourceList)

  case class Status(throttled: Map[String, Boolean], used: ResourceList)

  val crd: CustomResourceDefinition = CustomResourceDefinition[v1alpha1.Throttle]

  def apply(name: String, spec: Spec) = CustomResource[Spec, Status](spec).withName(name)

}
