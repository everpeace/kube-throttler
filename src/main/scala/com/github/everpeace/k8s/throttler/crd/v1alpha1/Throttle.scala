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

import com.github.everpeace.k8s.throttler
import com.github.everpeace.k8s.throttler.crd.v1alpha1
import com.github.everpeace.k8s.throttler.crd.v1alpha1.Implicits._
import skuber.Resource.{Quantity, ResourceList}
import skuber.ResourceSpecification.Subresources
import skuber.apiextensions.CustomResourceDefinition
import skuber.{
  CustomResource,
  HasStatusSubresource,
  LabelSelector,
  ResourceDefinition,
  ResourceSpecification
}

object Throttle {

  case class Spec(throttlerName: String, selector: LabelSelector, threshold: ResourceList)

  case class Status(throttled: Map[String, Boolean], used: ResourceList)

  val crd: CustomResourceDefinition = CustomResourceDefinition[v1alpha1.Throttle]

  def apply(name: String, spec: Spec) = CustomResource[Spec, Status](spec).withName(name)

  trait JsonFormat {
    import play.api.libs.functional.syntax._
    import play.api.libs.json._
    import skuber.json.format._

    implicit val throttleSpecFmt: Format[v1alpha1.Throttle.Spec] = (
      (JsPath \ "throttlerName").formatMaybeEmptyString(true) and
        (JsPath \ "selector").formatLabelSelector and
        (JsPath \ "threshold").formatMaybeEmptyMap[Quantity]
    )(v1alpha1.Throttle.Spec.apply, unlift(v1alpha1.Throttle.Spec.unapply))

    implicit val throttleStatusFmt: Format[v1alpha1.Throttle.Status] =
      Json.format[v1alpha1.Throttle.Status]
  }

  trait Implicits extends JsonFormat {
    implicit val throttleResourceDefinition: ResourceDefinition[Throttle] =
      ResourceDefinition[Throttle](
        group = throttler.crd.Group,
        version = "v1alpha1",
        kind = throttler.crd.Throttle.Kind,
        scope = ResourceSpecification.Scope.Namespaced,
        singular = Option(throttler.crd.Throttle.SingularName),
        plural = Option(throttler.crd.Throttle.PluralName),
        shortNames = throttler.crd.Throttle.ShortNames,
        subresources = Some(Subresources().withStatusSubresource)
      )

    implicit val throttleStatusSubEnabled: HasStatusSubresource[Throttle] =
      CustomResource.statusMethodsEnabler[Throttle]
  }
}
