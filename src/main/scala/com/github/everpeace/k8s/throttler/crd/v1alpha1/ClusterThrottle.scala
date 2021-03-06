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
import skuber.ResourceSpecification.Subresources
import skuber.apiextensions.CustomResourceDefinition
import skuber.{
  CustomResource,
  HasStatusSubresource,
  LabelSelector,
  Namespace,
  Pod,
  ResourceDefinition,
  ResourceSpecification
}

object ClusterThrottle {

  case class Selector(selectorTerms: List[SelectorItem])
  case class SelectorItem(
      podSelector: LabelSelector,
      namespaceSelector: Option[LabelSelector] = None)

  case class Spec(
      throttlerName: String,
      selector: Selector,
      threshold: ResourceAmount,
      temporaryThresholdOverrides: List[TemporaryThresholdOverride] = List.empty)
      extends v1alpha1.Spec[Selector]

  case class Status(
      throttled: IsResourceAmountThrottled,
      used: ResourceAmount,
      calculatedThreshold: Option[CalculatedThreshold] = None)
      extends v1alpha1.Status

  val crd: CustomResourceDefinition = CustomResourceDefinition[v1alpha1.ClusterThrottle]

  def apply(name: String, spec: Spec) = CustomResource[Spec, Status](spec).withName(name)

  trait JsonFormats
      extends CalculatedThreshold.JsonFormat
      with TemporaryThresholdOverride.JsonFormat
      with ResourceAmount.JsonFormat {
    import play.api.libs.functional.syntax._
    import play.api.libs.json._
    import skuber.json.format.{maybeEmptyFormatMethods, jsPath2LabelSelFormat}

    implicit val clusterThrottleSelectorItemFmt: Format[v1alpha1.ClusterThrottle.SelectorItem] = (
      (JsPath \ "podSelector").formatLabelSelector and
        (JsPath \ "namespaceSelector").formatNullableLabelSelector
    )(v1alpha1.ClusterThrottle.SelectorItem.apply,
      unlift(v1alpha1.ClusterThrottle.SelectorItem.unapply))

    implicit val clusterThrottleSelectorFmt: Format[v1alpha1.ClusterThrottle.Selector] =
      (JsPath \ "selectorTerms")
        .formatMaybeEmptyList[v1alpha1.ClusterThrottle.SelectorItem]
        .inmap(v1alpha1.ClusterThrottle.Selector.apply,
               unlift(v1alpha1.ClusterThrottle.Selector.unapply))

    implicit val clusterThrottleSpecFmt: Format[v1alpha1.ClusterThrottle.Spec] = (
      (JsPath \ "throttlerName").formatMaybeEmptyString(true) and
        (JsPath \ "selector").format[v1alpha1.ClusterThrottle.Selector] and
        (JsPath \ "threshold").format[ResourceAmount] and
        (JsPath \ "temporaryThresholdOverrides")
          .formatMaybeEmptyList[v1alpha1.TemporaryThresholdOverride]
    )(v1alpha1.ClusterThrottle.Spec.apply, unlift(v1alpha1.ClusterThrottle.Spec.unapply))

    implicit val clusterThrottleStatusFmt: Format[v1alpha1.ClusterThrottle.Status] =
      Json.format[v1alpha1.ClusterThrottle.Status]
  }

  trait Syntax {
    import com.github.everpeace.k8s._

    implicit class ThrottleSelectorItemSyntax(selectorItem: SelectorItem) {
      def matches(pod: skuber.Pod, namespace: skuber.Namespace): Boolean = {
        (namespace.name == pod.namespace) && {
          val namespaceSelectorEmpty = selectorItem.namespaceSelector.isEmpty
          val namespaceMatched =
            selectorItem.namespaceSelector.exists(_.matches(namespace.metadata.labels))
          val podMatched = selectorItem.podSelector.matches(pod.metadata.labels)
          (namespaceSelectorEmpty || namespaceMatched) && podMatched
        }
      }
    }

    implicit class ThrottleSelectorSyntax(selector: Selector) {
      def matches(pod: skuber.Pod, namespace: skuber.Namespace): Boolean = {
        (namespace.name == pod.namespace) && selector.selectorTerms.exists(
          _.matches(pod, namespace))
      }
    }

    implicit class ClusterThrottleSyntax(clthrottle: ClusterThrottle) {
      def isTarget(pod: Pod, ns: Namespace): Boolean = clthrottle.spec.selector.matches(pod, ns)
    }
  }

  trait ResourceDefinitions {
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
