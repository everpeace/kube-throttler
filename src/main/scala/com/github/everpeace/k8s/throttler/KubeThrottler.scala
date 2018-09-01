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

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import com.github.everpeace.k8s.throttler.controller.ThrottleController

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
import scala.util.{Failure, Success}

object KubeThrottler extends App {
  private def gracefulShutdown(
      system: ActorSystem,
      gracefulShutdownDuration: FiniteDuration
    ): Unit = {
    system.terminate()
    Await.result(system.whenTerminated, gracefulShutdownDuration)
  }

  implicit val system: ActorSystem    = ActorSystem("kube-throttler")
  implicit val mat: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContext   = system.dispatcher
  val logger                          = system.log
  logger.info("starting kube-throttler")

  val k8s    = skuber.k8sInit
  val config = KubeThrottleConfig(system.settings.config)

  scala.sys.addShutdownHook {
    logger.info(
      "detected a signal. shutting down kube-throttler (graceful period = {}).",
      config.gracefulShutdownDuration
    )
    gracefulShutdown(system, config.gracefulShutdownDuration)
  }

  val throttler = system.actorOf(ThrottleController.props(k8s), "throttle-controller")
  val routes    = new Routes(throttler, config.throttlerAskTimeout).all

  Http().bindAndHandle(routes, config.host, config.port).onComplete {
    case Success(binding) =>
      logger.info("successfully started kube-throttler on {}", binding.localAddress)
    case Failure(_) =>
      logger.error(
        "failed creating http server.  shutting down kube-throttler. (graceful period = {})",
        config.gracefulShutdownDuration
      )
      gracefulShutdown(system, config.gracefulShutdownDuration)
      sys.exit(1)
  }
}
