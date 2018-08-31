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

import com.github.everpeace.k8s.throttler
import skuber.Resource.Quantity
import skuber.ResourceSpecification.Subresources
import skuber.{CustomResource, HasStatusSubresource, ListResource, ResourceDefinition}

package object v1alpha1 {
  type Throttle     = CustomResource[v1alpha1.Throttle.Spec, v1alpha1.Throttle.Status]
  type ThrottleList = ListResource[Throttle]

  object Implicits {
    import play.api.libs.functional.syntax._
    import play.api.libs.json._
    import skuber.json.format._

    implicit val specFmt: Format[v1alpha1.Throttle.Spec] = (
      (JsPath \ "selector").formatLabelSelector and
        (JsPath \ "threshold").formatMaybeEmptyMap[Quantity]
    )(v1alpha1.Throttle.Spec.apply, unlift(v1alpha1.Throttle.Spec.unapply))

    implicit val statusFmt: Format[v1alpha1.Throttle.Status] = Json.format[v1alpha1.Throttle.Status]
    implicit val throttleResourceDefinition: ResourceDefinition[Throttle] =
      ResourceDefinition[Throttle](
        group = throttler.crd.Group,
        version = "v1alpha1",
        kind = throttler.crd.Kind,
        singular = Option(throttler.crd.SingularName),
        plural = Option(throttler.crd.PluralName),
        shortNames = throttler.crd.ShortNames,
        subresources = Some(Subresources().withStatusSubresource)
      )
    implicit val statusSubEnabled: HasStatusSubresource[Throttle] =
      CustomResource.statusMethodsEnabler[Throttle]
  }

}
