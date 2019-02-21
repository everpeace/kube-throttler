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

package com.github.everpeace.k8s.throttler.controller
import com.github.everpeace.k8s._
import com.github.everpeace.k8s.throttler.crd.v1alpha1
import com.github.everpeace.k8s.throttler.crd.v1alpha1.Implicits._
import com.github.everpeace.k8s.throttler.crd.v1alpha1.ResourceAmount
import com.github.everpeace.util.Injection._
import skuber._
import cats.instances.list._

trait ClusterThrottleControllerLogic {

  def isClusterThrottleAlreadyActiveFor(
      pod: Pod,
      ns: Namespace,
      clthrottle: v1alpha1.ClusterThrottle
    ): Boolean =
    clthrottle.isAlreadyActiveFor(pod, clthrottle.isTarget(pod, ns))

  def isClusterThrottleInsufficientFor(
      pod: Pod,
      ns: Namespace,
      clthrottle: v1alpha1.ClusterThrottle
    ): Boolean =
    clthrottle.isInsufficientFor(pod, clthrottle.isTarget(pod, ns))

  def calcNextClusterThrottleStatuses(
      targetClusterThrottles: Set[v1alpha1.ClusterThrottle],
      podsInAllNamespaces: Set[Pod],
      namespaces: Map[String, Namespace],
      at: skuber.Timestamp
    ): List[(ObjectKey, v1alpha1.ClusterThrottle.Status)] = {

    for {
      clthrottle <- targetClusterThrottles.toList

      matchedPods = podsInAllNamespaces.filter { p =>
        if (namespaces.contains(p.namespace)) {
          clthrottle.spec.selector.matches(p, namespaces(p.namespace))
        } else {
          false
        }
      }
      runningPods = matchedPods
        .filter(p => p.status.exists(_.phase.exists(_ == Pod.Phase.Running)))
        .toList
      runningTotal = runningPods.==>[List[ResourceAmount]].foldLeft(zeroResourceAmount)(_ add _)
      nextStatus = clthrottle.spec.statusFor(runningTotal, at)(
        v1alpha1.ClusterThrottle.Status.apply)

      toUpdate <- if (clthrottle.status.needToUpdateWith(nextStatus)) {
                   List(clthrottle.key -> nextStatus)
                 } else {
                   List.empty
                 }
    } yield toUpdate
  }
}
