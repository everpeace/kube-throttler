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

package com.github.everpeace.k8s.throttler

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.ContentTypes._
import akka.http.scaladsl.model.headers.`Content-Type`
import akka.http.scaladsl.server.Directives._
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.util.Timeout
import com.github.everpeace.healthchecks._
import com.github.everpeace.healthchecks.k8s._
import com.github.everpeace.k8s._
import com.github.everpeace.k8s.throttler.controller.ThrottleRequestHandler._
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport
import io.k8s.pkg.scheduler.api.v1
import io.k8s.pkg.scheduler.api.v1.ExtenderArgs
import com.github.everpeace.healthchecks

import scala.util.{Failure, Success}
import cats.implicits._
import com.github.everpeace.util.ActorWatcher.IsTargetAlive

class Routes(
    requestHandleActor: ActorRef,
    controllerWatcher: ActorRef,
    askTimeout: Timeout,
    serverDispatcherName: Option[String] = None,
)(implicit
    system: ActorSystem,
    materializer: ActorMaterializer)
    extends PlayJsonSupport {

  import v1.Implicits._

  implicit private val _askTimeout = askTimeout
  implicit private val ec =
    serverDispatcherName.map(system.dispatchers.lookup(_)).getOrElse(system.dispatcher)

  private val controllerAlive = asyncHealthCheck("isThrottleControllerLive") {
    (controllerWatcher ? IsTargetAlive).mapTo[Boolean].map { isAlive =>
      if (isAlive) {
        healthchecks.healthy
      } else {
        healthchecks.unhealthy("throttle-controller is not alive.")
      }
    }
  }
  private val requestHandlerReady = asyncHealthCheck("isThrottleControllerReady") {
    (requestHandleActor ? IsReady).mapTo[Boolean].map { isReady =>
      if (isReady) {
        healthchecks.healthy
      } else {
        healthchecks.unhealthy("throttle-request-handler is not ready.")
      }
    }
  }

  def all =
    readinessProbe(requestHandlerReady).toRoute ~ livenessProbe(controllerAlive).toRoute ~ checkThrottle ~ preemptIfNotThrottled

  def errorResult(arg: ExtenderArgs, message: String): v1.ExtenderFilterResult = {
    val nodeNames = if (arg.nodes.nonEmpty) {
      arg.nodes.get.items.map(_.name)
    } else {
      arg.nodenames
    }

    v1.ExtenderFilterResult(
      nodes = arg.nodes.map(
        _.copy(
          items = List.empty
        )),
      nodenames = List.empty,
      failedNodes = nodeNames.map(nodeName => nodeName -> message).toMap,
      error = Option(message)
    )
  }

  def unSchedulableResult(arg: ExtenderArgs, message: String): v1.ExtenderFilterResult = {
    val nodeNames = if (arg.nodes.nonEmpty) {
      arg.nodes.get.items.map(_.name)
    } else {
      arg.nodenames
    }

    v1.ExtenderFilterResult(
      nodes = arg.nodes.map(
        _.copy(
          items = List.empty
        )),
      nodenames = List.empty,
      failedNodes = nodeNames.map(nodeName => nodeName -> message).toMap
    )
  }

  def schedulableResult(arg: ExtenderArgs): v1.ExtenderFilterResult = {
    v1.ExtenderFilterResult(
      nodes = arg.nodes,
      nodenames = arg.nodenames,
      failedNodes = Map.empty
    )
  }

  def checkThrottle = path("check_throttle") {
    post {
      logRequestResult("preempt_if_not_throttled") {
        entity(as[v1.ExtenderArgs]) { extenderArgs =>
          val pod = extenderArgs.pod
          system.log.info("checking throttle status for pod {}", pod.key)
          onComplete((requestHandleActor ? CheckThrottleRequest(pod)).mapTo[CheckThrottleResponse]) {
            // some throttles are active!!  no nodes are schedulable
            case Success(
                Throttled(p,
                          activeThrottles,
                          activeClusterThrottles,
                          noSpaceThrottles,
                          noSpaceClusterThrottles)) if p == pod =>
              val activeThrottleMessage = activeThrottles.toList.toNel
                .map { thrs =>
                  val names = thrs.map(thr => thr.namespace -> thr.name).toList
                  s"throttles[active]=${names.mkString(",")}"
                }

              val activeClusterThrottleMessage = activeClusterThrottles.toList.toNel
                .map { thrs =>
                  val names = thrs.map(_.name).toList
                  s"clusterthrottles[active]=${names.mkString(",")}"
                }

              val noSpaceThrottleMessage = noSpaceThrottles.toList.toNel
                .map { thrs =>
                  val names = thrs.map(thr => thr.namespace -> thr.name).toList
                  s"throttles[insufficient]=${names.mkString(",")}"
                }

              val noSpaceClusterThrottleMessage = noSpaceClusterThrottles.toList.toNel
                .map { thrs =>
                  val names = thrs.map(_.name).toList
                  s"clusterthrottles[insufficient]=${names.mkString(",")}"
                }

              val aggregatedMessage =
                List(activeThrottleMessage,
                     activeClusterThrottleMessage,
                     noSpaceThrottleMessage,
                     noSpaceClusterThrottleMessage).filter(_.nonEmpty).map(_.get).mkString(", ")

              val message = s"pod ${pod.key} is unschedulable due to $aggregatedMessage"
              system.log.info(message)
              complete(unSchedulableResult(extenderArgs, message))

            // no throttles are active!!  all nodes are schedulable.
            case Success(NotThrottled(p)) if p == pod =>
              system.log.info(
                "pod {} is schedulable because no 'throttled' throttles/clusterthrottles for the pod.",
                pod.key)
              complete(schedulableResult(extenderArgs))

            case Success(NotReady) =>
              val message = s"throttler is not ready in checking throttle status of pod ${pod.key}"
              system.log.error(message)
              complete(errorResult(extenderArgs, message))

            // failure.  no nodes are schedulable.
            case Failure(exp) =>
              val message =
                s"exception occurred in checking throttles for pod ${pod.key}: ${exp.getMessage}"
              system.log.error(message)
              complete(errorResult(extenderArgs, message))
          }
        }
      }
    }
  }

  val noVictims = v1.ExtenderPreemptionResult(Map.empty)
  def echoedResult(args: v1.ExtenderPreemptionArgs): v1.ExtenderPreemptionResult =
    v1.ExtenderPreemptionResult(
      args.nodeNameToVictims.mapValues(
        victims =>
          v1.MetaVictims(
            pods = victims.pods.map(p => v1.MetaPod(p.uid)),
            numPDBViolations = victims.numPDBViolations
        )))

  def preemptIfNotThrottled = path("preempt_if_not_throttled") {
    post {
      logRequestResult("preempt_if_not_throttled") {
        entity(as[v1.ExtenderPreemptionArgs]) { extenderArgs =>
          val pod = extenderArgs.pod
          system.log.info("checking throttle status of pod {} for preemption", pod.key)
          onComplete((requestHandleActor ? CheckThrottleRequest(pod)).mapTo[CheckThrottleResponse]) {
            case Success(Throttled(_, _, _, _, _)) =>
              val message = s"pod ${pod.key} is throttled.  no victims should be selected."
              system.log.info(message)
              complete(noVictims)
            case Success(NotThrottled(_)) =>
              val result     = echoedResult(extenderArgs)
              val numVictims = result.nodeNameToMetaVictims.mapValues(_.pods.length)
              val message    = s"pod ${pod.key} is throttled.  echo victims: $numVictims"
              system.log.info(message)
              complete(result)
            case Success(NotReady) =>
              val message =
                s"throttler is not ready in checking throttle status of pod ${pod.key} for preemption"
              system.log.error(message)
              complete(StatusCodes.InternalServerError,
                       List(`Content-Type`(`application/json`)),
                       "{}")
            case Failure(exp) =>
              val message =
                s"exception occurred in checking throttles of pod ${pod.key} for preemption: ${exp.getMessage}"
              system.log.error(message)
              complete(StatusCodes.InternalServerError,
                       List(`Content-Type`(`application/json`)),
                       "{}")
          }
        }
      }
    }
  }
}
