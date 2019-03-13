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
import akka.actor.{Actor, Props}
import com.github.everpeace.k8s._
import com.github.everpeace.k8s.throttler.controller.ThrottleController.{
  ClusterThrottleWatchEvent,
  NamespaceWatchEvent,
  ThrottleWatchEvent
}
import com.github.everpeace.k8s.throttler.controller.ThrottleRequestHandler._
import com.github.everpeace.k8s.throttler.crd.v1alpha1
import skuber.{K8SWatchEvent, ObjectResource, Pod}
import skuber.api.client.EventType

class ThrottleRequestHandler
    extends Actor
    with ThrottleControllerLogic
    with ClusterThrottleControllerLogic {

  var initialized                                                                = false
  var throttlesMap: Map[String, Map[ObjectKey, v1alpha1.Throttle]]               = Map.empty
  var clusterThrottlesMap: Map[String, Map[ObjectKey, v1alpha1.ClusterThrottle]] = Map.empty
  var namespacesMap: Map[String, Map[ObjectKey, skuber.Namespace]]               = Map.empty
  var throttles: Map[String, Set[v1alpha1.Throttle]]                             = Map.empty
  var clusterThrottles: Set[v1alpha1.ClusterThrottle]                            = Set.empty
  var namespaces: Map[String, skuber.Namespace]                                  = Map.empty

  def updateThrottleMap(map: Map[String, Map[ObjectKey, v1alpha1.Throttle]]): Unit = {
    throttlesMap = map
    throttles = map.mapValues(m => m.values.toSet)
  }
  def updateClusterThrottleMap(map: Map[String, Map[ObjectKey, v1alpha1.ClusterThrottle]]): Unit = {
    clusterThrottlesMap = map
    clusterThrottles = map.mapValues(m => m.values.toSet).values.fold(Set.empty)(_ ++ _)
  }
  def updateNamespaceMap(map: Map[String, Map[ObjectKey, skuber.Namespace]]): Unit = {
    namespacesMap = map
    namespaces = map.mapValues(m => m.values.toSet).flatMap { kv =>
      kv._2.map(ns => ns.name -> ns).toMap
    }
  }

  def receive: Receive = requestHandler orElse updateMessageHandler orElse readinessHandler

  def readinessHandler: Receive = {
    case IsReady => sender ! initialized
  }

  def updateMessageHandler: Receive = {
    case ThrottleWatchEvent(e, at) =>
      updateThrottleMap(upsertOrDelete(throttlesMap, e))
    case ClusterThrottleWatchEvent(e, at) =>
      updateClusterThrottleMap(upsertOrDelete(clusterThrottlesMap, e))
    case NamespaceWatchEvent(e, at) =>
      updateNamespaceMap(upsertOrDelete(namespacesMap, e))
  }

  def requestHandler: Receive = {
    case Initialize(thrmap, clthrmap, nsmap) =>
      updateThrottleMap(thrmap)
      updateClusterThrottleMap(clthrmap)
      updateNamespaceMap(nsmap)
      initialized = true

    case CheckThrottleRequest(_) if !initialized =>
      sender ! NotReady

    case CheckThrottleRequest(pod) if initialized =>
      val throttlesInNs   = throttles.getOrElse(pod.namespace, Set.empty)
      val activeThrottles = throttlesInNs.filter(isThrottleAlreadyActiveFor(pod, _))
      val insufficientThrottles =
        throttlesInNs.filter(t =>
          !isThrottleAlreadyActiveFor(pod, t) && isThrottleInsufficientFor(pod, t))

      val activeClusterThrottles = clusterThrottles.filter { clthr =>
        if (namespaces.contains(pod.namespace)) {
          isClusterThrottleAlreadyActiveFor(pod, namespaces(pod.namespace), clthr)
        } else {
          false
        }
      }
      val insufficientClusterThrottles = clusterThrottles.filter { clthr =>
        if (namespaces.contains(pod.namespace)) {
          val podsNs = namespaces(pod.namespace)
          !isClusterThrottleAlreadyActiveFor(pod, podsNs, clthr) && isClusterThrottleInsufficientFor(
            pod,
            podsNs,
            clthr)
        } else {
          false
        }
      }

      val isThrottled = activeThrottles.nonEmpty || activeClusterThrottles.nonEmpty || insufficientThrottles.nonEmpty || insufficientClusterThrottles.nonEmpty
      if (isThrottled) {
        sender ! Throttled(pod,
                           activeThrottles,
                           activeClusterThrottles,
                           insufficientThrottles,
                           insufficientClusterThrottles)
      } else {
        sender ! NotThrottled(pod)
      }
  }

  private def delete[R <: ObjectResource](
      map: Map[String, Map[ObjectKey, R]],
      r: R
    ): Map[String, Map[ObjectKey, R]] = {
    val key @ (ns, _) = r.key
    if (map.contains(ns)) {
      val inNs = map(ns)
      map.updated(ns, inNs - key)
    } else {
      map
    }
  }

  private def upsert[R <: ObjectResource](
      map: Map[String, Map[ObjectKey, R]],
      r: R
    ): Map[String, Map[ObjectKey, R]] = {
    val key @ (ns, _) = r.key
    val inNs          = map.getOrElse(ns, Map.empty)
    map.updated(ns, inNs.updated(key, r))
  }

  private def upsertOrDelete[R <: ObjectResource](
      map: Map[String, Map[ObjectKey, R]],
      e: K8SWatchEvent[R]
    ): Map[String, Map[ObjectKey, R]] = {
    e._type match {
      case EventType.DELETED =>
        delete(map, e._object)
      case _ =>
        upsert(map, e._object)
    }
  }
}

object ThrottleRequestHandler {
  case class Initialize(
      initialThrottlesMap: Map[String, Map[ObjectKey, v1alpha1.Throttle]],
      initialclusterThrottlesMap: Map[String, Map[ObjectKey, v1alpha1.ClusterThrottle]],
      initialNamespacesMap: Map[String, Map[ObjectKey, skuber.Namespace]])
  case object IsReady

  case class CheckThrottleRequest(pod: Pod)
  sealed trait CheckThrottleResponse
  case object NotReady extends CheckThrottleResponse
  case class Throttled(
      pod: Pod,
      activeThrottles: Set[v1alpha1.Throttle],
      activeClusterThrottles: Set[v1alpha1.ClusterThrottle],
      insufficientThrottles: Set[v1alpha1.Throttle],
      insufficientClusterThrottles: Set[v1alpha1.ClusterThrottle])
      extends CheckThrottleResponse

  case class NotThrottled(pod: Pod) extends CheckThrottleResponse

  def props() = Props(new ThrottleRequestHandler())
}
