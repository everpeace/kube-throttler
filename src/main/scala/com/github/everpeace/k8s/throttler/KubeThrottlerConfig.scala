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

import akka.util.Timeout
import com.typesafe.config.Config

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.collection.JavaConverters._

case class KubeThrottleConfig(config: Config) {
  private val basePath        = "kube-throttler"
  private val throttlerConfig = config.getConfig(basePath)

  def throttlerName: String = throttlerConfig.getString("throttler-name")

  def serverDispatcherName: Option[String] =
    if (throttlerConfig.hasPath("server-dispatcher-name")) {
      Some(throttlerConfig.getString("server-dispatcher-name"))
    } else {
      None
    }
  def watchBufferSize: Int = throttlerConfig.getInt("watch-buffer-size")

  def targetSchedulerNames: List[String] =
    throttlerConfig.getStringList("target-scheduler-names").asScala.toList

  def gracefulShutdownDuration: FiniteDuration = Duration.fromNanos(
    throttlerConfig.getDuration(s"graceful-shutdown-duration").toNanos
  )
  def throttlerAskTimeout: Timeout =
    Timeout(
      Duration.fromNanos(
        throttlerConfig.getDuration("ask-timeout").toNanos
      ))

  def reconcileTemporaryThresholdInterval: FiniteDuration = Duration.fromNanos(
    throttlerConfig.getDuration("reconcile-temporary-threshold-overrides-interval").toNanos
  )

  def statusForceUpdateInterval: FiniteDuration = Duration.fromNanos(
    throttlerConfig.getDuration("status-force-update-interval").toNanos
  )

  def host: String = throttlerConfig.getString("host")
  def port: Int    = throttlerConfig.getInt("port")
}
