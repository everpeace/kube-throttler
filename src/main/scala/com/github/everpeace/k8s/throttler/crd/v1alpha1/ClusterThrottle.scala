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
import skuber.Resource.Quantity
import skuber.ResourceSpecification.Subresources
import skuber.apiextensions.CustomResourceDefinition
import skuber.{
  CustomResource,
  HasStatusSubresource,
  LabelSelector,
  Pod,
  ResourceDefinition,
  ResourceSpecification
}

object ClusterThrottle {

  case class Spec(throttlerName: String, selector: LabelSelector, threshold: ResourceAmount)

  case class Status(throttled: IsResourceThrottled, used: ResourceAmount)

  val crd: CustomResourceDefinition = CustomResourceDefinition[v1alpha1.ClusterThrottle]

  def apply(name: String, spec: Spec) = CustomResource[Spec, Status](spec).withName(name)

  trait JsonFormats extends CommonJsonFormat {
    import play.api.libs.functional.syntax._
    import play.api.libs.json._
    import skuber.json.format._

    implicit val clusterThrottleSpecFmt: Format[v1alpha1.ClusterThrottle.Spec] = (
      (JsPath \ "throttlerName").formatMaybeEmptyString(true) and
        (JsPath \ "selector").formatLabelSelector and
        (JsPath \ "threshold").format[ResourceAmount]
    )(v1alpha1.ClusterThrottle.Spec.apply, unlift(v1alpha1.ClusterThrottle.Spec.unapply))

    implicit val clusterThrottleStatusFmt: Format[v1alpha1.ClusterThrottle.Status] =
      Json.format[v1alpha1.ClusterThrottle.Status]
  }

  trait Syntax {
    import cats.implicits._
    import com.github.everpeace.k8s._

    implicit class ClusterThrottleSpecSyntax(spec: Spec) {
      def statusFor(used: ResourceAmount): Status = {
        val throttled = v1alpha1.IsResourceThrottled(
          podsCount = for {
            th <- spec.threshold.podsCount
            c  <- used.podsCount.orElse(Option(0))
          } yield th <= c,
          resourceRequests = spec.threshold.resourceRequests.keys.map { resource =>
            if (used.resourceRequests.contains(resource)) {
              if (used.resourceRequests(resource) < spec.threshold.resourceRequests(resource)) {
                resource -> false
              } else {
                resource -> true
              }
            } else {
              resource -> false
            }
          }.toMap
        )

        Status(
          throttled = throttled,
          used = used
        )
      }
    }

    implicit class ClusterThrottleSyntax(throttle: ClusterThrottle) {
      def isActiveFor(pod: Pod): Boolean = {
        lazy val isTarget = throttle.spec.selector.matches(pod.metadata.labels)
        lazy val isActive = throttle.status.exists { st =>
          lazy val isPodCountActive = st.throttled.podsCount.exists(identity)
          lazy val isResourceActive = pod.totalRequests.keys
            .map(rs => st.throttled.resourceRequests.getOrElse(rs, false))
            .exists(identity)
          isPodCountActive || isResourceActive
        }
        isTarget && isActive
      }

      def isInsufficientFor(pod: Pod): Boolean = {
        throttle.spec.selector.matches(pod.metadata.labels) && {
          val podTotalRequests = pod.totalRequests
          val threshold        = throttle.spec.threshold
          val used =
            throttle.status.map(_.used).getOrElse(v1alpha1.ResourceAmount(None, Map.empty))

          lazy val podsCountInsufficient = (for {
            th <- threshold.podsCount
            u  <- used.podsCount.orElse(Option(0))
          } yield th < (u + 1)).getOrElse(false)

          lazy val someResourceInsufficient = for {
            (r, q) <- podTotalRequests.toList
          } yield {
            if (threshold.resourceRequests.contains(r)) {
              val uq = used.resourceRequests.getOrElse(r, Quantity("0"))
              (threshold.resourceRequests(r) compare (uq add q)) < 0
            } else {
              false
            }
          }

          podsCountInsufficient || someResourceInsufficient.exists(identity)
        }
      }
    }
  }

  trait Implicits extends JsonFormats with Syntax {
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
