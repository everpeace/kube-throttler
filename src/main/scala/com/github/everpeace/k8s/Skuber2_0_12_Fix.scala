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
import skuber.Pod
import skuber.Pod.Affinity.MatchExpression
import com.typesafe.scalalogging.StrictLogging

// FIXME
object Skuber2_0_12_Fix extends StrictLogging {
  import play.api.libs.functional.syntax._
  import play.api.libs.json._
  import skuber.Pod.Affinity.NodeSelectorTerm

  implicit val fixedNodeRequiredDuringSchedulingIgnoredDuringExecutionFormat
    : Format[Pod.Affinity.NodeAffinity.RequiredDuringSchedulingIgnoredDuringExecution] = {

    // THIS IS THE ROOT CAUSE
    // ref: https://github.com/doriordan/skuber/pull/246
    implicit val fixedNodeSelectorTermFormat: Format[NodeSelectorTerm] = {
      import skuber.json.format.{maybeEmptyFormatMethods, nodeMatchExpressionFormat}
      ((JsPath \ "matchExpressions")
        .formatMaybeEmptyList[MatchExpression] and (JsPath \ "matchFields")
        .formatNullable[List[MatchExpression]])(
        (matchExpressions, matchFieldsOpt) => {
          matchFieldsOpt foreach { matchFields =>
            logger.error(
              s"'matchFields' field detected!! it will be ignored for unmarshaling due to the issue (skuber#246). content = ${Json
                .stringify(Json.toJson(matchFields))}")
          }
          Pod.Affinity.NodeSelectorTerm(matchExpressions)
        },
        nst => (nst.matchExpressions, None)
      )
    }

    (JsPath \ "nodeSelectorTerms")
      .format[Pod.Affinity.NodeSelectorTerms]
      .inmap(
        nodeSelectorTerms =>
          Pod.Affinity.NodeAffinity
            .RequiredDuringSchedulingIgnoredDuringExecution(nodeSelectorTerms),
        (rdside: Pod.Affinity.NodeAffinity.RequiredDuringSchedulingIgnoredDuringExecution) =>
          rdside.nodeSelectorTerms
      )
  }

  implicit val fixedNodeAffinityFormat: Format[Pod.Affinity.NodeAffinity] = {
    import skuber._
    import skuber.json.format.{maybeEmptyFormatMethods, nodePreferredSchedulingTermFormat}

    ((JsPath \ "requiredDuringSchedulingIgnoredDuringExecution")
      .formatNullable[Pod.Affinity.NodeAffinity.RequiredDuringSchedulingIgnoredDuringExecution](
        fixedNodeRequiredDuringSchedulingIgnoredDuringExecutionFormat) and
      (JsPath \ "preferredDuringSchedulingIgnoredDuringExecution")
        .formatMaybeEmptyList[Pod.Affinity.NodeAffinity.PreferredSchedulingTerm])(
      Pod.Affinity.NodeAffinity.apply _,
      unlift(Pod.Affinity.NodeAffinity.unapply))
  }

  implicit val fixedPodAffinityFormat: Format[Pod.Affinity] = {
    import skuber.json.format.{podAffinityFormat, podAntiAffinityFormat}

    Json.format[Pod.Affinity]
  }

  implicit val fixedPodSpecFormat: Format[Pod.Spec] = {
    import skuber._
    import skuber.json.format.{affinityFormat => _, _}

    val podSpecPartOneFormat: OFormat[
      (List[Container],
       List[Container],
       List[Volume],
       skuber.RestartPolicy.Value,
       Option[Int],
       Option[Int],
       skuber.DNSPolicy.Value,
       Map[String, String],
       String,
       String,
       Boolean,
       List[LocalObjectReference],
       Option[Pod.Affinity],
       List[Pod.Toleration],
       Option[Security.Context])] = (
      (JsPath \ "containers").format[List[Container]] and
        (JsPath \ "initContainers").formatMaybeEmptyList[Container] and
        (JsPath \ "volumes").formatMaybeEmptyList[Volume] and
        (JsPath \ "restartPolicy").formatEnum(RestartPolicy, Some(RestartPolicy.Always)) and
        (JsPath \ "terminationGracePeriodSeconds").formatNullable[Int] and
        (JsPath \ "activeDeadlineSeconds").formatNullable[Int] and
        (JsPath \ "dnsPolicy").formatEnum(DNSPolicy, Some(DNSPolicy.ClusterFirst)) and
        (JsPath \ "nodeSelector").formatMaybeEmptyMap[String] and
        (JsPath \ "serviceAccountName").formatMaybeEmptyString() and
        (JsPath \ "nodeName").formatMaybeEmptyString() and
        (JsPath \ "hostNetwork").formatMaybeEmptyBoolean() and
        (JsPath \ "imagePullSecrets").formatMaybeEmptyList[LocalObjectReference] and
        (JsPath \ "affinity").formatNullable[Pod.Affinity](fixedPodAffinityFormat) and
        (JsPath \ "tolerations").formatMaybeEmptyList[Pod.Toleration] and
        (JsPath \ "securityContext").formatNullable[Security.Context]
    ).tupled

    (podSpecPartOneFormat and podSpecPartTwoFormat).apply(
      {
        case ((conts,
               initConts,
               vols,
               rpol,
               tgps,
               adls,
               dnspol,
               nodesel,
               svcac,
               node,
               hnet,
               ips,
               aff,
               tol,
               sc),
              (host, aliases, pid, ipc, asat, prio, prioc, sched, subd, dnsc)) =>
          Pod.Spec(conts,
                   initConts,
                   vols,
                   rpol,
                   tgps,
                   adls,
                   dnspol,
                   nodesel,
                   svcac,
                   node,
                   hnet,
                   ips,
                   aff,
                   tol,
                   sc,
                   host,
                   aliases,
                   pid,
                   ipc,
                   asat,
                   prio,
                   prioc,
                   sched,
                   subd,
                   dnsc)
      },
      s =>
        ((s.containers,
          s.initContainers,
          s.volumes,
          s.restartPolicy,
          s.terminationGracePeriodSeconds,
          s.activeDeadlineSeconds,
          s.dnsPolicy,
          s.nodeSelector,
          s.serviceAccountName,
          s.nodeName,
          s.hostNetwork,
          s.imagePullSecrets,
          s.affinity,
          s.tolerations,
          s.securityContext),
         (s.hostname,
          s.hostAliases,
          s.hostPID,
          s.hostIPC,
          s.automountServiceAccountToken,
          s.priority,
          s.priorityClassName,
          s.schedulerName,
          s.subdomain,
          s.dnsConfig))
    )
  }

  implicit val fixedPodFormat: Format[Pod] = {
    import skuber._
    import skuber.json.format.{objFormat, podStatusFormat}
    (objFormat and
      (JsPath \ "spec").formatNullable[Pod.Spec](fixedPodSpecFormat) and
      (JsPath \ "status").formatNullable[Pod.Status])(Pod.apply _, unlift(Pod.unapply))
  }
}
