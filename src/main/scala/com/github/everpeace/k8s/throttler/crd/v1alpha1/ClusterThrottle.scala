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
import skuber.{
  CustomResource,
  HasStatusSubresource,
  LabelSelector,
  ResourceDefinition,
  ResourceSpecification
}
import skuber.Resource.{Quantity, ResourceList}
import skuber.ResourceSpecification.Subresources
import skuber.apiextensions.CustomResourceDefinition

object ClusterThrottle {

  case class Spec(throttlerName: String, selector: LabelSelector, threshold: ResourceList)

  case class Status(throttled: Map[String, Boolean], used: ResourceList)

  val crd: CustomResourceDefinition = CustomResourceDefinition[v1alpha1.ClusterThrottle]

  def apply(name: String, spec: Spec) = CustomResource[Spec, Status](spec).withName(name)

  trait JsonFormats {
    import play.api.libs.functional.syntax._
    import play.api.libs.json._
    import skuber.json.format._

    implicit val clusterThrottleSpecFmt: Format[v1alpha1.ClusterThrottle.Spec] = (
      (JsPath \ "throttlerName").formatMaybeEmptyString(true) and
        (JsPath \ "selector").formatLabelSelector and
        (JsPath \ "threshold").formatMaybeEmptyMap[Quantity]
    )(v1alpha1.ClusterThrottle.Spec.apply, unlift(v1alpha1.ClusterThrottle.Spec.unapply))

    implicit val clusterThrottleStatusFmt: Format[v1alpha1.ClusterThrottle.Status] =
      Json.format[v1alpha1.ClusterThrottle.Status]
  }

  trait Implicits extends JsonFormats {
    implicit val clusterThrottleResourceDefinition: ResourceDefinition[ClusterThrottle] =
      ResourceDefinition[ClusterThrottle](
        group = throttler.crd.Group,
        version = "v1alpha1",
        kind = throttler.crd.ClusterThrottle.Kind,
        scope = ResourceSpecification.Scope.Cluster,
        singular = Option(throttler.crd.ClusterThrottle.SingularName),
        plural = Option(throttler.crd.ClusterThrottle.PluralName),
        shortNames = throttler.crd.ClusterThrottle.ShortNames,
        subresources = Some(Subresources().withStatusSubresource)
      )

    implicit val clusterThrottleStatusSubEnabled: HasStatusSubresource[ClusterThrottle] =
      CustomResource.statusMethodsEnabler[ClusterThrottle]
  }
}
