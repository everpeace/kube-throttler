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
import akka.actor.{Actor, ActorLogging, PoisonPill, Props}
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
import skuber.Resource.{Quantity, ResourceList}
import skuber.ResourceSpecification.Subresources
import skuber.{ResourceSpecification, _}
import skuber.api.client.EventType
import skuber.json.format._

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class ThrottleController(implicit val k8s: K8SRequestContext, config: KubeThrottleConfig)
    extends Actor
    with ActorLogging
    with ThrottleControllerLogic
    with ThrottleControllerMetrics
    with ClusterThrottleControllerLogic
    with ClusterThrottleControllerMetrics {

  implicit private val system = context.system
  implicit private val mat    = ActorMaterializer()
  implicit private val ec     = context.dispatcher

  private val k8sMap: mutable.Map[String, K8SRequestContext] = mutable.Map(k8s.namespaceName -> k8s)

  private def schedulerName(pod: Pod): Option[String] = pod.spec.flatMap(_.schedulerName)

  private val isPodResponsible = (pod: Pod) =>
    schedulerName(pod).exists(n => config.targetSchedulerNames.contains(n))
  private val isThrottleResponsible = (thr: v1alpha1.Throttle) =>
    config.throttlerName == thr.spec.throttlerName
  private val isClusterThrottleResponsible = (clthr: v1alpha1.ClusterThrottle) =>
    config.throttlerName == clthr.spec.throttlerName

  private val cache = new {
    val pods      = new ObjectResourceCache[Pod](isPodResponsible)
    val throttles = new ObjectResourceCache[v1alpha1.Throttle](isThrottleResponsible)
    val clusterThrottles =
      new ObjectResourceCache[v1alpha1.ClusterThrottle](isClusterThrottleResponsible)
  }

  override def preStart(): Unit = {
    super.preStart()
    log.info("starting ThrottleController actor (path = {})", self.path)

    val syncThrottleAndPods = for {
      clthrottleVersion <- cache.clusterThrottles.init
      throttleVersion   <- cache.throttles.init
      podVersion        <- cache.pods.init
      _                 <- reconcileAllClusterThrottles()
      _                 <- reconcileAllThrottles()
    } yield (clthrottleVersion, throttleVersion, podVersion)

    syncThrottleAndPods.onComplete {
      case scala.util.Success(v) =>
        val (clthrottleVersion, throttleVersion, podVersion) = v
        log.info(
          s"latest throttle list resource version = $throttleVersion, latest clusterthrottle list resource version = $clthrottleVersion, latest pod list resource version = $podVersion")
      case scala.util.Failure(th) =>
        log.error("error in syncing throttles and pods: {}, {}",
                  th,
                  th.getStackTrace.mkString("\n"))
        log.error("{} will commit suicide", self.path)
        self ! PoisonPill
    }

    for {
      (clthrottleVersion, throttleVersion, podVersion) <- syncThrottleAndPods
      // watch clusterthrottle
      clthrottleWatch = k8s
        .watchAllContinuously[v1alpha1.ClusterThrottle](
          sinceResourceVersion = clthrottleVersion,
          bufSize = config.watchBufferSize
        )(
          implicitly[Format[v1alpha1.ClusterThrottle]],
          clusterScopedResourceDefinition[v1alpha1.ClusterThrottle]
        )
        .map(ClusterThrottleWatchEvent)
      // watch throttle in all namespaces
      throttleWatch = k8s
        .watchAllContinuously[v1alpha1.Throttle](
          sinceResourceVersion = throttleVersion,
          bufSize = config.watchBufferSize
        )(
          implicitly[Format[v1alpha1.Throttle]],
          clusterScopedResourceDefinition[v1alpha1.Throttle]
        )
        .map(ThrottleWatchEvent)
      // watch pods in all namespaces
      podWatch = k8s
        .watchAllContinuously[Pod](
          sinceResourceVersion = podVersion,
          bufSize = config.watchBufferSize
        )(
          implicitly[Format[Pod]],
          clusterScopedResourceDefinition[Pod]
        )
        .map(PodWatchEvent)
      // todo: use RestartSource to make the actor more stable.
      done <- {
        val fut = Source
          .combine(clthrottleWatch, throttleWatch, podWatch)(Merge(_, eagerComplete = true))
          .runWith(
            Sink.foreach {
              case event: PodWatchEvent             => self ! event
              case event: ThrottleWatchEvent        => self ! event
              case event: ClusterThrottleWatchEvent => self ! event
              case _                                =>
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
        recordThrottleStatusMetric(thr)
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
          recordThrottleSpecMetric(thr)
          recordThrottleStatusMetric(thr)
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

  private def _calcNextClusterThrottleStatuses(
      targetClusterThrottles: Set[v1alpha1.ClusterThrottle],
      podsInAllNamespaces: Set[Pod]
    ): List[(ObjectKey, v1alpha1.ClusterThrottle.Status)] = {
    val nextStatuses = calcNextClusterThrottleStatuses(targetClusterThrottles, podsInAllNamespaces)
    if (nextStatuses.isEmpty) {
      log.info("All clusterthrottle statuses are up-to-date. No updates.")
    }
    nextStatuses
  }

  private def syncClusterThrottle(
      key: ObjectKey,
      st: v1alpha1.ClusterThrottle.Status
    ): Future[v1alpha1.ClusterThrottle] = {
    val (_, n) = key

    val k8s = k8sMap.getOrElseUpdate(
      skuber.api.client.defaultK8sConfig.currentContext.namespace.name,
      skuber.k8sInit(skuber.api.client.defaultK8sConfig))
    val fut = for {
      latest    <- k8s.get[v1alpha1.ClusterThrottle](n)
      nextState = latest.copy(status = Option(st))
      _         <- Future { log.info("updating clusterthrottle {} with status ({})", key, st) }
      result    <- k8s.updateStatus(nextState)
    } yield result

    fut.onComplete {
      case Success(clthr) =>
        log.info("successfully updated clusterthrottle {} with status {}", clthr.key, clthr.status)
        recordClusterThrottleStatusMetric(clthr)
      case Failure(ex) =>
        log.error("failed updating clusterthrottle {} with status {} by {}", key, Option(st), ex)
    }

    fut
  }

  def reconcileAllClusterThrottles(): Future[List[v1alpha1.ClusterThrottle]] = {
    log.info("start reconciling clusterthrottle statuses.")

    val clusterThrottleStatusesToReconcile = for {
      (_, throttles) <- cache.clusterThrottles.toImmutable.toList
      pods           = cache.pods.toImmutable.values.fold(Set.empty)(_ ++ _)
      clthr          <- _calcNextClusterThrottleStatuses(throttles, pods)
    } yield clthr

    val fut = Future.sequence(clusterThrottleStatusesToReconcile.map {
      case (key, st) =>
        syncClusterThrottle(key, st)
    })

    fut.onComplete {
      case Success(_) =>
        // update all metrics when reconciling
        cache.clusterThrottles.toImmutable.values.foreach(_.foreach { clthr =>
          recordClusterThrottleSpecMetric(clthr)
          recordClusterThrottleStatusMetric(clthr)
        })
        log.info("finished reconciling clusterthrottle statuses.")
      case Failure(ex) =>
        log.error("failed reconciling clusterthrottle statues by: {}", ex)
    }

    fut
  }

  // on detecting some pod change.
  def updateClusterThrottleBecauseOf(pod: Pod): Unit = {
    val podsInAllNamespaces = cache.pods.toImmutable.values.fold(Set.empty)(_ ++ _)
    val allClusterThrottles = cache.clusterThrottles.toImmutable.values.fold(Set.empty)(_ ++ _)

    for {
      (key, st) <- _calcNextClusterThrottleStatuses(allClusterThrottles, podsInAllNamespaces)
    } yield {
      syncClusterThrottle(key, st)
    }
  }

  // on detecting some throttle change.
  def updateClusterThrottleBecauseOf(clthrottle: v1alpha1.ClusterThrottle): Unit = {
    val podsInAllNamespaces = cache.pods.toImmutable.values.fold(Set.empty)(_ ++ _)

    for {
      (key, st) <- _calcNextClusterThrottleStatuses(Set(clthrottle), podsInAllNamespaces)
    } yield {
      syncClusterThrottle(key, st)
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
        updateClusterThrottleBecauseOf(pod)
      }
    case PodWatchEvent(e) if !isPodResponsible(e._object) =>
      log.info(
        "detected pod {} was {}.  but it will be ignored because it is not responsible for {}",
        e._object.key,
        e._type,
        config.targetSchedulerNames)

    case ClusterThrottleWatchEvent(e) if isClusterThrottleResponsible(e._object) =>
      log.info("detected clusterthrottle {} was {}", e._object.key, e._type)
      val updateOrRemoveCacheThen = e._type match {
        case EventType.DELETED =>
          cache.clusterThrottles.removeThen(e._object)(_)
        case _ =>
          cache.clusterThrottles.updateThen(e._object)(_)
      }
      updateOrRemoveCacheThen { clusterThrottle =>
        updateClusterThrottleBecauseOf(clusterThrottle)
        e._type match {
          case EventType.DELETED =>
            log.info(
              s"resetting all metrics for ${clusterThrottle.key} because detecting ${clusterThrottle.key} was DELETED.")
            resetClusterThrottleMetric(e._object)
          case _ =>
            recordClusterThrottleSpecMetric(e._object)
        }
      }

    case ClusterThrottleWatchEvent(e) if !isClusterThrottleResponsible(e._object) =>
      log.info(
        "detected clusterthrottle {} was {}.  but it will be ignored because it is not responsible for {}",
        e._object.key,
        e._type,
        config.throttlerName)

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
            resetThrottleMetric(e._object)
          case _ =>
            recordThrottleSpecMetric(e._object)
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
      val activeThrottles = throttlesInNs.filter(isThrottleActiveFor(pod, _))
      val insufficientThrottles =
        throttlesInNs.filter(t => !isThrottleActiveFor(pod, t) && isThrottleInsufficientFor(pod, t))

      val clusterThrottles       = cache.clusterThrottles.toImmutable.values.fold(Set.empty)(_ ++ _)
      val activeClusterThrottles = clusterThrottles.filter(isClusterThrottleActiveFor(pod, _))
      val insufficientClusterThrottles = clusterThrottles.filter(t =>
        !isClusterThrottleActiveFor(pod, t) && isClusterThrottleInsufficientFor(pod, t))

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
    rd.spec.scope match {
      case ResourceSpecification.Scope.Cluster =>
        rd
      case ResourceSpecification.Scope.Namespaced =>
        new ResourceDefinition[O] {
          def spec = new ResourceSpecification {
            override def apiPathPrefix: String             = rd.spec.apiPathPrefix
            override def group: Option[String]             = rd.spec.group
            override def defaultVersion: String            = rd.spec.defaultVersion
            override def prioritisedVersions: List[String] = rd.spec.prioritisedVersions
            override def scope: ResourceSpecification.Scope.Value =
              ResourceSpecification.Scope.Cluster
            override def names: ResourceSpecification.Names = rd.spec.names
            override def subresources: Option[Subresources] = rd.spec.subresources
          }
        }
    }

  // messages
  case class CheckThrottleRequest(pod: Pod)

  case class Throttled(
      pod: Pod,
      activeThrottles: Set[v1alpha1.Throttle],
      activeClusterThrottles: Set[v1alpha1.ClusterThrottle],
      insufficientThrottles: Set[v1alpha1.Throttle],
      insufficientClusterThrottles: Set[v1alpha1.ClusterThrottle])

  case class NotThrottled(pod: Pod)

  object HealthCheckRequest

  // messages for internal controls
  private case class ResourceWatchDone(done: Try[Done])

  private case class PodWatchEvent(e: K8SWatchEvent[Pod])

  private case class ThrottleWatchEvent(e: K8SWatchEvent[v1alpha1.Throttle])

  private case class ClusterThrottleWatchEvent(e: K8SWatchEvent[v1alpha1.ClusterThrottle])

  def props(k8s: K8SRequestContext, config: KubeThrottleConfig) = {
    implicit val _k8s    = k8s
    implicit val _config = config
    Props(new ThrottleController())
  }
}

trait MetricsBase {
  def resourceQuantityToLong(q: (String, Resource.Quantity)): Long = q._1 match {
    case Resource.cpu =>
      (q._2.amount * 1000).toLong // convert to milli
    case _ => q._2.amount.toLong
  }

  def resourceQuantityToTag(q: (String, _)): kamon.Tags = Map("resource" -> q._1)
}

trait ClusterThrottleControllerMetrics extends MetricsBase {
  self: {
    def log: {
      def info(s: String): Unit
      def debug(s: String): Unit
    }
  } =>

  def resetClusterThrottleMetric(clthrottle: v1alpha1.ClusterThrottle): Unit = {
    val zeroSpec = clthrottle.spec.copy(
      threshold = clthrottle.spec.threshold.mapValues(_ => Resource.Quantity("0")),
      selector = clthrottle.spec.selector
    )

    val zeroFalseStatus = clthrottle.status.map(
      st =>
        st.copy(
          throttled = st.throttled.mapValues(_ => false),
          used = st.used.mapValues(_ => Resource.Quantity("0"))
      ))
    val zero = clthrottle.copy(
      spec = zeroSpec,
      status = zeroFalseStatus
    )

    recordClusterThrottleSpecMetric(zero)
    recordClusterThrottleStatusMetric(zero)
  }

  def recordClusterThrottleSpecMetric(clthrottle: v1alpha1.ClusterThrottle): Unit = {
    val metadataTags   = metadataTagsForClusterThrottle(clthrottle)
    val thresholdGauge = Kamon.gauge("clusterthrottle.spec.threshold")

    clthrottle.spec.threshold.foreach { rq =>
      val tags  = metadataTags ++ resourceQuantityToTag(rq)
      val value = resourceQuantityToLong(rq)
      log.info(
        s"setting gauge '${thresholdGauge.name}{${tags.values.mkString(",")}}' value with ${value}")
      thresholdGauge.refine(tags).set(value)
    }
  }

  def recordClusterThrottleStatusMetric(clthrottle: v1alpha1.ClusterThrottle): Unit = {
    val metadataTags = metadataTagsForClusterThrottle(clthrottle)
    val statusGauge  = Kamon.gauge("clusterthrottle.status.throttled")
    val usedGauge    = Kamon.gauge("clusterthrottle.status.used")
    val b2i          = (b: Boolean) => b compare false

    clthrottle.status.foreach { status =>
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

  def metadataTagsForClusterThrottle(throttle: v1alpha1.ClusterThrottle): kamon.Tags = {
    val metadata = List(
      "name" -> throttle.metadata.name,
      "uuid" -> throttle.metadata.uid
    ).toMap ++ throttle.metadata.clusterName.map(cn => Map("cluster" -> cn)).getOrElse(Map.empty)

    metadata ++ throttle.metadata.labels ++ throttle.metadata.annotations
  }

}

trait ThrottleControllerMetrics extends MetricsBase {
  self: {
    def log: {
      def info(s: String): Unit
      def debug(s: String): Unit
    }
  } =>

  def resetThrottleMetric(throttle: v1alpha1.Throttle): Unit = {
    val zeroSpec = throttle.spec.copy(
      threshold = throttle.spec.threshold.copy(
        podsCount = Option(0),
        resourceRequests =
          throttle.spec.threshold.resourceRequests.mapValues(_ => Resource.Quantity("0"))
      ),
      selector = throttle.spec.selector
    )

    val zeroFalseStatus = throttle.status.map(
      st =>
        st.copy(
          throttled = st.throttled.copy(
            podsCount = st.throttled.podsCount.map(_ => false),
            resourceRequests = st.throttled.resourceRequests.mapValues(_ => false)
          ),
          used = st.used.copy(
            podsCount = st.used.podsCount.map(_ => 0),
            resourceRequests = st.used.resourceRequests.mapValues(_ => Resource.Quantity("0"))
          )
      ))
    val zero = throttle.copy(
      spec = zeroSpec,
      status = zeroFalseStatus
    )

    recordThrottleSpecMetric(zero)
    recordThrottleStatusMetric(zero)
  }

  def recordThrottleSpecMetric(throttle: v1alpha1.Throttle): Unit = {
    val metadataTags = metadataTagsForThrottle(throttle)

    val resourceRequestsThresholdGauge = Kamon.gauge("throttle.spec.threshold.resourceRequests")
    val podsCountsThresholdGauge       = Kamon.gauge("throttle.spec.threshold.podsCount")

    throttle.spec.threshold.resourceRequests.foreach { rq =>
      val tags  = metadataTags ++ resourceQuantityToTag(rq)
      val value = resourceQuantityToLong(rq)
      log.info(s"setting gauge '${resourceRequestsThresholdGauge.name}{${tags.values
        .mkString(",")}}' value with ${value}")
      resourceRequestsThresholdGauge.refine(tags).set(value)
    }

    throttle.spec.threshold.podsCount.foreach { value =>
      val tags = metadataTags
      log.info(
        s"setting gauge '${podsCountsThresholdGauge.name}{${tags.values.mkString(",")}}' value with ${value}")
      podsCountsThresholdGauge.refine(tags).set(value)
    }
  }

  def recordThrottleStatusMetric(throttle: v1alpha1.Throttle): Unit = {
    val metadataTags  = metadataTagsForThrottle(throttle)
    val statusRRGauge = Kamon.gauge("throttle.status.throttled.resourceRequests")
    val statusPCGauge = Kamon.gauge("throttle.status.throttled.podsCount")
    val usedRRGauge   = Kamon.gauge("throttle.status.used.resourceRequests")
    val usedPCGauge   = Kamon.gauge("throttle.status.used.podsCount")

    val b2i = (b: Boolean) => b compare false
    throttle.status.foreach { status =>
      status.throttled.resourceRequests foreach { rq =>
        val tags = metadataTags ++ resourceQuantityToTag(rq)
        log.info(
          s"setting gauge '${statusRRGauge.name}{${tags.values.mkString(",")}}' value with ${b2i(rq._2)}")
        statusRRGauge.refine(tags).set(b2i(rq._2))
      }
      status.throttled.podsCount foreach { value =>
        val tags = metadataTags
        log.info(
          s"setting gauge '${statusPCGauge.name}{${tags.values.mkString(",")}}' value with ${b2i(value)}")
        statusPCGauge.refine(tags).set(b2i(value))

      }

      status.used.resourceRequests foreach { rq =>
        val tags  = metadataTags ++ resourceQuantityToTag(rq)
        val value = resourceQuantityToLong(rq)
        log.debug(
          s"setting gauge '${usedRRGauge.name}{${tags.values.mkString(",")}}' value with ${value}")
        usedRRGauge.refine(tags).set(value)
      }
      status.used.podsCount foreach { value =>
        val tags = metadataTags
        log.debug(
          s"setting gauge '${usedPCGauge.name}{${tags.values.mkString(",")}}' value with ${value}")
        usedPCGauge.refine(tags).set(value)
      }
    }
  }

  def metadataTagsForThrottle(throttle: v1alpha1.Throttle): kamon.Tags = {
    val metadata = List(
      "name"      -> throttle.metadata.name,
      "namespace" -> throttle.metadata.namespace,
      "uuid"      -> throttle.metadata.uid
    ).toMap ++ throttle.metadata.clusterName.map(cn => Map("cluster" -> cn)).getOrElse(Map.empty)

    metadata ++ throttle.metadata.labels ++ throttle.metadata.annotations
  }

}

trait ClusterThrottleControllerLogic {

  def isClusterThrottleActiveFor(pod: Pod, clthrottle: v1alpha1.ClusterThrottle): Boolean = {
    clthrottle.spec.selector.matches(pod.metadata.labels) && clthrottle.status.exists { st =>
      pod.totalRequests.keys.map(rs => st.throttled.getOrElse(rs, false)).exists(_ == true)
    }
  }

  def isClusterThrottleInsufficientFor(pod: Pod, clthrottle: v1alpha1.ClusterThrottle): Boolean = {
    clthrottle.spec.selector.matches(pod.metadata.labels) && {
      val podTotalRequests = pod.totalRequests
      val threshold        = clthrottle.spec.threshold
      val used             = clthrottle.status.map(_.used).getOrElse(Map.empty)

      val enoughSpaces = for {
        (r, q) <- podTotalRequests.toList
      } yield {
        if (threshold.contains(r)) {
          val uq = used.getOrElse(r, Quantity("0"))
          ((uq add q) compare threshold(r)) <= 0
        } else {
          true
        }
      }

      enoughSpaces.exists(!_)
    }
  }

  def calcNextClusterThrottleStatuses(
      targetClusterThrottles: Set[v1alpha1.ClusterThrottle],
      podsInAllNamespaces: Set[Pod]
    ): List[(ObjectKey, v1alpha1.ClusterThrottle.Status)] = {

    for {
      clthrottle <- targetClusterThrottles.toList

      matchedPods = podsInAllNamespaces.filter(p =>
        clthrottle.spec.selector.matches(p.metadata.labels))
      running      = matchedPods.filter(p => p.status.exists(_.phase.exists(_ == Pod.Phase.Running)))
      usedResource = running.toList.map(_.totalRequests).foldLeft(Map.empty: ResourceList)(_ add _)

      throttled = clthrottle.spec.threshold.keys.map { resource =>
        if (usedResource.contains(resource)) {
          if (usedResource(resource) < clthrottle.spec.threshold(resource)) {
            resource -> false
          } else {
            resource -> true
          }
        } else {
          resource -> false
        }
      }.toMap
      nextStatus = v1alpha1.ClusterThrottle.Status(
        throttled = throttled,
        used = usedResource
      )

      toUpdate <- if (clthrottle.status != Option(nextStatus)) {
                   List(clthrottle.key -> nextStatus)
                 } else {
                   List.empty
                 }
    } yield toUpdate
  }
}

trait ThrottleControllerLogic {
  def isThrottleActiveFor(pod: Pod, throttle: v1alpha1.Throttle): Boolean =
    throttle.isThrottleActiveFor(pod)

  def isThrottleInsufficientFor(pod: Pod, throttle: v1alpha1.Throttle): Boolean =
    throttle.isThrottleInsufficientFor(pod)

  def calcNextThrottleStatuses(
      targetThrottles: Set[v1alpha1.Throttle],
      podsInNs: Set[Pod]
    ): List[(ObjectKey, v1alpha1.Throttle.Status)] = {

    for {
      throttle <- targetThrottles.toList

      matchedPods = podsInNs.filter(p => throttle.spec.selector.matches(p.metadata.labels))
      running     = matchedPods.filter(p => p.status.exists(_.phase.exists(_ == Pod.Phase.Running)))
      usedResource = v1alpha1.ResourceAmount(
        podsCount = throttle.spec.threshold.podsCount.map(_ => running.size),
        resourceRequests = {
          val actual =
            running.toList.map(_.totalRequests).foldLeft(Map.empty: ResourceList)(_ add _)
          val ret = for {
            (r, _) <- throttle.spec.threshold.resourceRequests if actual.contains(r)
          } yield r -> actual(r)
          ret
        }
      )
      nextStatus = throttle.spec.statusFor(usedResource)

      toUpdate <- if (throttle.status != Option(nextStatus)) {
                   List(throttle.key -> nextStatus)
                 } else {
                   List.empty
                 }
    } yield toUpdate
  }
}
