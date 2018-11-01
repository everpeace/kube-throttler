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

import akka.Done
import akka.actor.{Actor, ActorLogging, Props}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Merge, Sink, Source}
import cats.implicits._
import com.github.everpeace.healthchecks
import com.github.everpeace.k8s._
import com.github.everpeace.k8s.throttler.KubeThrottleConfig
import com.github.everpeace.k8s.throttler.controller.ThrottleController._
import com.github.everpeace.k8s.throttler.crd.v1alpha1
import com.github.everpeace.k8s.throttler.crd.v1alpha1.Implicits._
import kamon.Kamon
import play.api.libs.json.Format
import skuber.Resource.ResourceList
import skuber.ResourceSpecification.Subresources
import skuber.{ResourceSpecification, _}
import skuber.api.client.{EventType, WatchEvent}
import skuber.json.format._

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class ThrottleController(implicit val k8s: K8SRequestContext, config: KubeThrottleConfig)
    extends Actor
    with ActorLogging
    with ThrottleControllerLogic
    with ThrottleControllerMetrics {

  implicit private val system = context.system
  implicit private val mat    = ActorMaterializer()
  implicit private val ec     = context.dispatcher

  private val k8sMap: mutable.Map[String, K8SRequestContext] = mutable.Map(k8s.namespaceName -> k8s)

  private def schedulerName(pod: Pod): Option[String] = pod.spec.flatMap(_.schedulerName)
  private val isPodResponsible = (pod: Pod) =>
    schedulerName(pod).exists(n => config.targetSchedulerNames.contains(n))
  private val isThrottleResponsible = (thr: v1alpha1.Throttle) =>
    config.throttlerName == thr.spec.throttlerName

  private val cache = new {
    val pods      = new ObjectResourceCache[Pod](isPodResponsible)
    val throttles = new ObjectResourceCache[v1alpha1.Throttle](isThrottleResponsible)
  }

  override def preStart(): Unit = {
    super.preStart()
    log.info("starting ThrottleController actor (path = {})", self.path)
    val watch = for {
      throttleVersion <- cache.throttles.init
      podVersion      <- cache.pods.init
      _               <- reconcileAllThrottles()
      // watch throttle in all namespaces
      throttleWatch = k8s.watchAllContinuously[v1alpha1.Throttle](
        sinceResourceVersion = throttleVersion)(
        implicitly[Format[v1alpha1.Throttle]],
        clusterScopedResourceDefinition[v1alpha1.Throttle]
      )
      // watch pods in all namespaces
      podWatch = k8s.watchAllContinuously[Pod](sinceResourceVersion = podVersion)(
        implicitly[Format[Pod]],
        clusterScopedResourceDefinition[Pod]
      )
      // todo: use RestartSource to make the actor more stable.
      done <- {
        val fut = Source
          .combine(throttleWatch, podWatch)(Merge(_, eagerComplete = true))
          .runWith(
            Sink.foreach {
              case event @ WatchEvent(_, _: Pod) =>
                self ! PodWatchEvent(event.asInstanceOf[K8SWatchEvent[Pod]])
              case event @ WatchEvent(_, CustomResource(_, _, _, _, _)) =>
                self ! ThrottleWatchEvent(event.asInstanceOf[K8SWatchEvent[v1alpha1.Throttle]])
              case _ =>
              // drop
            }
          )
        fut.onComplete { done =>
          self ! ResourceWatchDone(done)
        }
        fut
      }
    } yield done
  }

  private def _calcNextThrottleStatuses(
      targetThrottles: Set[v1alpha1.Throttle],
      podsInNs: Set[Pod]
    ): List[(ObjectKey, v1alpha1.Throttle.Status)] = {
    val nextStatuses = calcNextThrottleStatuses(targetThrottles, podsInNs)
    if (nextStatuses.isEmpty) {
      log.info("All throttle statuses are up-to-date. No updates.")
    }
    nextStatuses
  }

  private def syncThrottle(
      key: ObjectKey,
      st: v1alpha1.Throttle.Status
    ): Future[v1alpha1.Throttle] = {
    val (ns, n) = key

    val k8sNs = k8sMap.getOrElseUpdate(
      ns,
      skuber.k8sInit(skuber.api.client.defaultK8sConfig.setCurrentNamespace(ns)))
    val fut = for {
      latest    <- k8sNs.get[v1alpha1.Throttle](n)
      nextState = latest.copy(status = Option(st))
      _         <- Future { log.info("updating throttle {} with status ({})", key, st) }
      result    <- k8sNs.updateStatus(nextState)
    } yield result

    fut.onComplete {
      case Success(thr) =>
        log.info("successfully updated throttle {} with status {}", thr.key, thr.status)
        recordStatusMetric(thr)
      case Failure(ex) =>
        log.error("failed updating throttle {} with status {} by {}", key, Option(st), ex)
    }

    fut
  }

  def reconcileAllThrottles(): Future[List[v1alpha1.Throttle]] = {
    log.info("start reconciling throttle statuses.")

    val throttleStatusesToReconcile = for {
      (namespace, throttles) <- cache.throttles.toImmutable.toList
      pods                   = cache.pods.toImmutable.getOrElse(namespace, Set.empty)
      thr                    <- _calcNextThrottleStatuses(throttles, pods)
    } yield thr

    val fut = Future.sequence(throttleStatusesToReconcile.map {
      case (key, st) =>
        syncThrottle(key, st)
    })

    fut.onComplete {
      case Success(_) =>
        // update all metrics when reconciling
        cache.throttles.toImmutable.values.foreach(_.foreach { thr =>
          recordSpecMetric(thr)
          recordStatusMetric(thr)
        })
        log.info("finished reconciling throttle statuses.")
      case Failure(ex) =>
        log.error("failed reconciling throttle statues by: {}", ex)
    }

    fut
  }

  // on detecting some pod change.
  def updateThrottleBecauseOf(pod: Pod): Unit = {
    val ns               = pod.namespace
    val podsInNs         = cache.pods.toImmutable.getOrElse(ns, Set.empty[Pod])
    val allThrottlesInNs = cache.throttles.toImmutable.getOrElse(ns, Set.empty[v1alpha1.Throttle])

    for {
      (key, st) <- _calcNextThrottleStatuses(allThrottlesInNs, podsInNs)
    } yield {
      syncThrottle(key, st)
    }
  }

  // on detecting some throttle change.
  def updateThrottleBecauseOf(throttle: v1alpha1.Throttle): Unit = {
    val ns       = throttle.namespace
    val podsInNs = cache.pods.toImmutable.getOrElse(ns, Set.empty[Pod])

    for {
      (key, st) <- _calcNextThrottleStatuses(Set(throttle), podsInNs)
    } yield {
      syncThrottle(key, st)
    }
  }

  override def receive: Receive =
    eventHandler orElse resourceWatchHandler orElse requestHandler orElse healthCheckHandler

  def eventHandler: Receive = {
    case PodWatchEvent(e) if isPodResponsible(e._object) =>
      log.info("detected pod {} was {}", e._object.key, e._type)
      val updateOrRemoveThen = e._type match {
        case EventType.DELETED =>
          cache.pods.removeThen(e._object)(_)
        case _ =>
          cache.pods.updateThen(e._object)(_)
      }
      updateOrRemoveThen { pod =>
        updateThrottleBecauseOf(pod)
      }
    case PodWatchEvent(e) if !isPodResponsible(e._object) =>
      log.info(
        "detected pod {} was {}.  but it will be ignored because it is not responsible for {}",
        e._object.key,
        e._type,
        config.targetSchedulerNames)

    case ThrottleWatchEvent(e) if isThrottleResponsible(e._object) =>
      log.info("detected throttle {} was {}", e._object.key, e._type)
      val updateOrRemoveCacheThen = e._type match {
        case EventType.DELETED =>
          cache.throttles.removeThen(e._object)(_)
        case _ =>
          cache.throttles.updateThen(e._object)(_)
      }
      updateOrRemoveCacheThen { throttle =>
        updateThrottleBecauseOf(throttle)
        e._type match {
          case EventType.DELETED =>
            log.info(
              s"resetting all metrics for ${throttle.key} because detecting ${throttle.key} was DELETED.")
            resetMetric(e._object)
          case _ =>
            recordSpecMetric(e._object)
        }
      }
    case ThrottleWatchEvent(e) if !isThrottleResponsible(e._object) =>
      log.info(
        "detected throttle {} was {}.  but it will be ignored because it is not responsible for {}",
        e._object.key,
        e._type,
        config.throttlerName)
  }

  def resourceWatchHandler: Receive = {
    case ResourceWatchDone(done) =>
      done match {
        case scala.util.Success(_) =>
          log.error("watch api connection is closed.  restarting ThrottleController actor.")
          throw new RuntimeException("watch api connection was closed.")
        case scala.util.Failure(ex) =>
          log.error(
            "watch api connection was closed by an exceptions.  " +
              "restarting ThrottleController actor. (cause = {})",
            ex
          )
          throw new RuntimeException(s"watch api failed by $ex.")
      }
  }

  def requestHandler: Receive = {
    case CheckThrottleRequest(pod) =>
      val throttlesInNs   = cache.throttles.toImmutable.getOrElse(pod.namespace, Set.empty)
      val activeThrottles = throttlesInNs.filter(isActiveFor(pod, _))
      if (activeThrottles.nonEmpty) {
        sender ! Throttled(pod, activeThrottles)
      } else {
        sender ! NotThrottled(pod)
      }
  }

  def healthCheckHandler: Receive = {
    case HealthCheckRequest => sender ! healthchecks.healthy
  }

  private[controller] class ObjectResourceCache[R <: ObjectResource](
      val isResponsible: R => Boolean = (_: R) => true,
      val map: mutable.Map[String, mutable.Map[ObjectKey, R]] =
        mutable.Map.empty[String, mutable.Map[ObjectKey, R]]) {

    def init(
        implicit
        k8s: K8SRequestContext,
        ec: ExecutionContext,
        fmt: Format[R],
        rd: ResourceDefinition[R],
        listfmt: Format[ListResource[R]],
        listrd: ResourceDefinition[ListResource[R]]
      ): Future[Option[String]] = {

      for {
        _ <- Future { log.info("syncing {} status", rd.spec.names.singular) }
        // extract resource list in all namespaces
        resourceList <- k8s.list[ListResource[R]]()(
                         implicitly[Format[ListResource[R]]],
                         clusterScopedResourceDefinition[ListResource[R]]
                       )
        latestResourceVersion = resourceList.metadata map {
          _.resourceVersion
        }
        _ = resourceList.items.filter(isResponsible).foreach(update)
        _ <- Future { log.info("finished syncing {} status", rd.spec.names.singular) }
      } yield latestResourceVersion
    }

    def update(r: R): Unit = updateThen(r)(_ => ())
    def remove(r: R): Unit = removeThen(r)(_ => ())

    def updateThen(r: R)(f: R => Unit): Unit = if (isResponsible(r)) {
      val key @ (ns, _) = r.key

      if (!map.contains(ns)) {
        map += ns -> mutable.Map(key -> r)
      } else {
        map(ns)(key) = r
      }

      f(r)
    }
    def removeThen(r: R)(f: R => Unit): Unit = if (isResponsible(r)) {
      val key @ (ns, _) = r.key

      if (map.contains(ns)) {
        map(ns).remove(key)
      }

      f(r)
    }

    def toImmutable: Map[String, Set[R]] = map.toMap.mapValues(m => m.values.toSet)
  }
}

object ThrottleController {

  def clusterScopedResourceDefinition[O <: TypeMeta](implicit rd: ResourceDefinition[O]) =
    new ResourceDefinition[O] {
      def spec = new ResourceSpecification {
        override def apiPathPrefix: String                    = rd.spec.apiPathPrefix
        override def group: Option[String]                    = rd.spec.group
        override def defaultVersion: String                   = rd.spec.defaultVersion
        override def prioritisedVersions: List[String]        = rd.spec.prioritisedVersions
        override def scope: ResourceSpecification.Scope.Value = ResourceSpecification.Scope.Cluster
        override def names: ResourceSpecification.Names       = rd.spec.names
        override def subresources: Option[Subresources]       = rd.spec.subresources
      }
    }

  // messages
  case class CheckThrottleRequest(pod: Pod)

  case class Throttled(pod: Pod, activeThrottles: Set[v1alpha1.Throttle])

  case class NotThrottled(pod: Pod)

  object HealthCheckRequest

  // messages for internal controls
  private case class ResourceWatchDone(done: Try[Done])

  private case class PodWatchEvent(e: K8SWatchEvent[Pod])

  private case class ThrottleWatchEvent(e: K8SWatchEvent[v1alpha1.Throttle])

  def props(k8s: K8SRequestContext, config: KubeThrottleConfig) = {
    implicit val _k8s    = k8s
    implicit val _config = config
    Props(new ThrottleController())
  }
}

trait ThrottleControllerMetrics {
  self: {
    def log: {
      def info(s: String): Unit
      def debug(s: String): Unit
    }
  } =>

  def resetMetric(throttle: v1alpha1.Throttle): Unit = {
    val zeroSpec = throttle.spec.copy(
      threshold = throttle.spec.threshold.mapValues(_ => Resource.Quantity("0")),
      selector = throttle.spec.selector
    )

    val zeroFalseStatus = throttle.status.map(
      st =>
        st.copy(
          throttled = st.throttled.mapValues(_ => false),
          used = st.used.mapValues(_ => Resource.Quantity("0"))
      ))
    val zero = throttle.copy(
      spec = zeroSpec,
      status = zeroFalseStatus
    )

    recordSpecMetric(zero)
    recordStatusMetric(zero)
  }

  def recordSpecMetric(throttle: v1alpha1.Throttle): Unit = {
    val metadataTags   = metadataTagsFor(throttle)
    val thresholdGauge = Kamon.gauge("throttle.spec.threshold")

    throttle.spec.threshold.foreach { rq =>
      val tags  = metadataTags ++ resourceQuantityToTag(rq)
      val value = resourceQuantityToLong(rq)
      log.info(
        s"setting gauge '${thresholdGauge.name}{${tags.values.mkString(",")}}' value with ${value}")
      thresholdGauge.refine(tags).set(value)
    }
  }

  def recordStatusMetric(throttle: v1alpha1.Throttle): Unit = {
    val metadataTags = metadataTagsFor(throttle)
    val statusGauge  = Kamon.gauge("throttle.status.throttled")
    val usedGauge    = Kamon.gauge("throttle.status.used")
    val b2i          = (b: Boolean) => b compare false
    throttle.status.foreach { status =>
      status.throttled.foreach { rq =>
        val tags = metadataTags ++ resourceQuantityToTag(rq)
        log.info(
          s"setting gauge '${statusGauge.name}{${tags.values.mkString(",")}}' value with ${b2i(rq._2)}")
        statusGauge.refine(tags).set(b2i(rq._2))
      }

      status.used.foreach { rq =>
        val tags  = metadataTags ++ resourceQuantityToTag(rq)
        val value = resourceQuantityToLong(rq)
        log.debug(
          s"setting gauge '${usedGauge.name}{${tags.values.mkString(",")}}' value with ${value}")
        usedGauge.refine(tags).set(value)
      }
    }
  }

  def resourceQuantityToLong(q: (String, Resource.Quantity)): Long = q._1 match {
    case Resource.cpu =>
      (q._2.amount * 1000).toLong // convert to milli
    case _ => q._2.amount.toLong
  }

  def resourceQuantityToTag(q: (String, _)): kamon.Tags = Map("resource" -> q._1)

  def metadataTagsFor(throttle: v1alpha1.Throttle): kamon.Tags = {
    val metadata = List(
      "name"      -> throttle.metadata.name,
      "namespace" -> throttle.metadata.namespace,
      "uuid"      -> throttle.metadata.uid
    ).toMap ++ throttle.metadata.clusterName.map(cn => Map("cluster" -> cn)).getOrElse(Map.empty)

    metadata ++ throttle.metadata.labels ++ throttle.metadata.annotations
  }
}

trait ThrottleControllerLogic {
  def isActiveFor(pod: Pod, throttle: v1alpha1.Throttle): Boolean = {
    throttle.spec.selector.matches(pod.metadata.labels) && throttle.status.exists { st =>
      pod.totalRequests.keys.map(rs => st.throttled.getOrElse(rs, false)).exists(_ == true)
    }
  }

  def calcNextThrottleStatuses(
      targetThrottles: Set[v1alpha1.Throttle],
      podsInNs: Set[Pod]
    ): List[(ObjectKey, v1alpha1.Throttle.Status)] = {

    for {
      throttle <- targetThrottles.toList

      matchedPods  = podsInNs.filter(p => throttle.spec.selector.matches(p.metadata.labels))
      running      = matchedPods.filter(p => p.status.exists(_.phase.exists(_ == Pod.Phase.Running)))
      usedResource = running.toList.map(_.totalRequests).foldLeft(Map.empty: ResourceList)(_ add _)

      throttled = throttle.spec.threshold.keys.map { resource =>
        if (usedResource.contains(resource)) {
          if (usedResource(resource) < throttle.spec.threshold(resource)) {
            resource -> false
          } else {
            resource -> true
          }
        } else {
          resource -> false
        }
      }.toMap
      nextStatus = v1alpha1.Throttle.Status(
        throttled = throttled,
        used = usedResource
      )

      toUpdate <- if (throttle.status != Option(nextStatus)) {
                   List(throttle.key -> nextStatus)
                 } else {
                   List.empty
                 }
    } yield toUpdate
  }
}
