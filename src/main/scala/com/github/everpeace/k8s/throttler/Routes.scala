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
import akka.http.scaladsl.server.Directives._
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.util.Timeout
import com.github.everpeace.healthchecks._
import com.github.everpeace.healthchecks.k8s._
import com.github.everpeace.k8s.throttler.controller.ThrottleController.{
  CheckThrottleRequest,
  HealthCheckRequest,
  NotThrottled,
  Throttled
}
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport
import io.k8s.pkg.scheduler.api.v1
import io.k8s.pkg.scheduler.api.v1.ExtenderArgs

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

class Routes(
    throttleController: ActorRef,
    askTimeout: Timeout
  )(implicit
    system: ActorSystem,
    materializer: ActorMaterializer,
    ec: ExecutionContext)
    extends PlayJsonSupport {

  import v1.Implicits._

  implicit private val _askTimeout = askTimeout

  private val checkController = asyncHealthCheck("isThrottleControllerLive") {
    (throttleController ? HealthCheckRequest).mapTo[HealthCheckResult]
  }

  def all =
    readinessProbe(checkController).toRoute ~ livenessProbe(checkController).toRoute ~ checkThrottle

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
      failedNodes = nodeNames.map(nodeName => nodeName -> message).toMap,
      error = Option(message)
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
      entity(as[v1.ExtenderArgs]) { extenderArgs =>
        val pod = extenderArgs.pod

        onComplete(throttleController ? CheckThrottleRequest(pod)) {
          // some throttles are active!!  no nodes are schedulable
          case Success(Throttled(p, activeThrottles)) if p == pod =>
            val throttleNames = activeThrottles.map(_.name).mkString(",")
            val message       = s"pod is unschedulable due to throttles=$throttleNames"
            complete(unSchedulableResult(extenderArgs, message))

          // no throttles are active!!  all nodes are schedulable.
          case Success(NotThrottled(p)) if p == pod =>
            complete(schedulableResult(extenderArgs))

          // failure.  no nodes are schedulable.
          case Failure(exp) =>
            val message = s"exception occurred in checking throttles: ${exp.getMessage}"
            complete(unSchedulableResult(extenderArgs, message))
        }
      }
    }
  }
}
