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

package io.k8s.pkg.scheduler.api

import play.api.libs.functional.syntax._
import play.api.libs.json._
import skuber.json.format.{podSpecFmt => _, _}
import skuber.{ListMeta, ListResource, Node, NodeList, ObjectMeta, Pod}

// ref: https://github.com/kubernetes/kubernetes/blob/master/pkg/scheduler/api/v1/types.go
package object v1 {

  case class ExtenderArgs(pod: Pod, nodes: Option[NodeList], nodenames: List[String])

  case class ExtenderFilterResult(
      nodes: Option[NodeList] = None,
      nodenames: List[String] = List.empty,
      failedNodes: Map[String, String] = Map.empty,
      error: Option[String] = None)

  case class MetaPod(uid: String)
  case class Victims(pods: List[Pod], numPDBViolations: Int)
  case class MetaVictims(pods: List[MetaPod], numPDBViolations: Int)
  case class ExtenderPreemptionArgs(
      pod: Pod,
      nodeNameToVictims: Map[String, Victims],
      nodeNameToMetaVictims: Map[String, MetaVictims])
  case class ExtenderPreemptionResult(nodeNameToMetaVictims: Map[String, MetaVictims])

  object Implicits {
    implicit val nodeFormat: Format[Node] = (
      (JsPath \ "metadata").lazyFormat[ObjectMeta](objectMetaFormat) and
        (JsPath \ "spec").formatNullable[Node.Spec] and
        (JsPath \ "status").formatNullable[Node.Status]
    )(
      Node("Node", "v1", _, _, _),
      unlift((n: Node) => Option(n.metadata, n.spec, n.status))
    )
    implicit val nodeListFormat: Format[NodeList] = (
      (JsPath \ "metadata").formatNullable[ListMeta] and
        (JsPath \ "items").formatMaybeEmptyList[Node](nodeFormat.reads(_), nodeFormat.writes(_))
    )(
      ListResource[Node]("v1", "NodeList", _, _),
      unlift((nl: NodeList) => Option(nl.metadata, nl.items))
    )
    implicit val extenderArgsFmt: Format[ExtenderArgs] = (
      (JsPath \ "Pod").format[Pod] and
        (JsPath \ "Nodes").formatNullable[NodeList](nodeListFormat) and
        (JsPath \ "NodeNames").formatMaybeEmptyList[String]
    )(ExtenderArgs.apply, unlift(ExtenderArgs.unapply))

    implicit val extenderFilterResult: Format[ExtenderFilterResult] = (
      (JsPath \ "Nodes").formatNullable[NodeList](nodeListFormat) and
        (JsPath \ "NodeNames").formatMaybeEmptyList[String] and
        (JsPath \ "FailedNodes").formatMaybeEmptyMap[String] and
        (JsPath \ "Error").formatNullable[String]
    )(ExtenderFilterResult.apply, unlift(ExtenderFilterResult.unapply))

    implicit val metaPodFmt: Format[MetaPod] =
      (JsPath \ "UID").format[String].inmap(MetaPod.apply, unlift(MetaPod.unapply))
    implicit val victimsFmt: Format[Victims] = (
      (JsPath \ "Pods").formatMaybeEmptyList[Pod] and
        (JsPath \ "NumPDBViolations").format[Int]
    )(Victims.apply, unlift(Victims.unapply))
    implicit val metaVictimsFmt: Format[MetaVictims] = (
      (JsPath \ "Pods").formatMaybeEmptyList[MetaPod] and
        (JsPath \ "NumPDBViolations").format[Int]
    )(MetaVictims.apply, unlift(MetaVictims.unapply))
    implicit val extenderPreemptionArgsFmt: Format[ExtenderPreemptionArgs] = (
      (JsPath \ "Pod").format[Pod] and
        (JsPath \ "NodeNameToVictims").formatMaybeEmptyMap[Victims] and
        (JsPath \ "NodeNameToMetaVictims").formatMaybeEmptyMap[MetaVictims]
    )(ExtenderPreemptionArgs.apply, unlift(ExtenderPreemptionArgs.unapply))
    implicit val extenderPreemptionResultFmt: Format[ExtenderPreemptionResult] =
      (JsPath \ "NodeNameToMetaVictims")
        .formatMaybeEmptyMap[MetaVictims]
        .inmap(ExtenderPreemptionResult.apply, unlift(ExtenderPreemptionResult.unapply))
  }

}
