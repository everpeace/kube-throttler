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
import akka.actor.{Actor, ActorLogging, ActorRef, Cancellable, PoisonPill, Props}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Merge, Sink, Source}
import com.github.everpeace.k8s._
import com.github.everpeace.k8s.throttler.KubeThrottleConfig
import com.github.everpeace.k8s.throttler.controller.ThrottleController._
import com.github.everpeace.k8s.throttler.controller.ThrottleRequestHandler.Initialize
import com.github.everpeace.k8s.throttler.crd.v1alpha1
import com.github.everpeace.k8s.throttler.crd.v1alpha1.Implicits._
import com.github.everpeace.k8s.throttler.metrics.{
  ClusterThrottleControllerMetrics,
  ThrottleControllerMetrics
}
import play.api.libs.json._
import skuber._
import skuber.ResourceSpecification.Subresources
import skuber.api.client.EventType
import skuber.api.client.LoggingContext._
import skuber.json.format.{namespaceFormat, namespaceListFmt, podFormat, podListFmt}

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class ThrottleController(
    requestHandlerActor: ActorRef
  )(implicit
    val k8s: K8SRequestContext,
    config: KubeThrottleConfig)
    extends Actor
    with ActorLogging
    with ThrottleControllerLogic
    with ThrottleControllerMetrics
    with ClusterThrottleControllerLogic
    with ClusterThrottleControllerMetrics {

  implicit private val system = context.system
  implicit private val mat    = ActorMaterializer()
  implicit private val ec     = context.dispatcher

  private var cancelWhenRestart: mutable.ListBuffer[Cancellable] = mutable.ListBuffer.empty

  private val k8sMap: mutable.Map[String, K8SRequestContext] = mutable.Map(k8s.namespaceName -> k8s)

  private def schedulerName(pod: Pod): Option[String] = pod.spec.flatMap(_.schedulerName)

  private val isPodResponsible = (pod: Pod) =>
    schedulerName(pod).exists(n => config.targetSchedulerNames.contains(n))
  private val isPodCompleted = (pod: Pod) =>
    pod.status.exists(st =>
      st.phase.contains(Pod.Phase.Succeeded) || st.phase.contains(Pod.Phase.Failed))
  private val isThrottleResponsible = (thr: v1alpha1.Throttle) =>
    config.throttlerName == thr.spec.throttlerName
  private val isClusterThrottleResponsible = (clthr: v1alpha1.ClusterThrottle) =>
    config.throttlerName == clthr.spec.throttlerName

  private val cache = new {
    val namespaces = new ObjectResourceCache[Namespace]()
    val pods       = new ObjectResourceCache[Pod](isPodResponsible)
    val throttles  = new ObjectResourceCache[v1alpha1.Throttle](isThrottleResponsible)
    val clusterThrottles =
      new ObjectResourceCache[v1alpha1.ClusterThrottle](isClusterThrottleResponsible)
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    cancelWhenRestart.foreach(cancellable =>
      Try { if (!cancellable.isCancelled) cancellable.cancel() })
    super.preRestart(reason, message)
  }

  override def preStart(): Unit = {
    super.preStart()
    log.info("starting ThrottleController actor (path = {})", self.path)
    val now = java.time.ZonedDateTime.now()
    val syncAll = for {
      // init cache
      _ <- cache.namespaces.init()
      _ <- cache.clusterThrottles.init()
      _ <- cache.throttles.init()
      _ <- cache.pods.init(p => !isPodCompleted(p)) // completed pods need not to cache
      // reconcile all throttles/clthrottles
      _ <- reconcileAllClusterThrottles(at = now)
      _ <- reconcileAllThrottles(at = now)
      // get the latest resource versions again to watch
      nsVersion <- latestResourceList[Namespace].map(_.metadata.map(_.resourceVersion))
      clthrottleVersion <- latestResourceList[v1alpha1.ClusterThrottle]
                            .map(_.metadata.map(_.resourceVersion))
      throttleVersion <- latestResourceList[v1alpha1.Throttle]
                          .map(_.metadata.map(_.resourceVersion))
      podVersion <- latestResourceList[Pod].map(_.metadata.map(_.resourceVersion))
    } yield (nsVersion, clthrottleVersion, throttleVersion, podVersion)

    syncAll.onComplete {
      case scala.util.Success(v) =>
        val (namespaceVersion, clthrottleVersion, throttleVersion, podVersion) = v
        log.info(
          s"latest throttle list resource version = $throttleVersion, latest clusterthrottle list resource version = $clthrottleVersion, latest pod list resource version = $podVersion, namespace list resource version = $namespaceVersion")

        log.info(
          "starting periodic syncs for throttles/clusterthrottles with temporaryThresholdOverrides")

        requestHandlerActor ! Initialize(cache.throttles.toImmutableMap,
                                         cache.clusterThrottles.toImmutableMap,
                                         cache.namespaces.toImmutableMap)

        cancelWhenRestart += system.scheduler.schedule(config.reconcileTemporaryThresholdInterval,
                                                       config.reconcileTemporaryThresholdInterval) {

          self ! ReconcileClusterThrottlesEvent(_.spec.temporaryThresholdOverrides.nonEmpty,
                                                "temporaryThresholdOverridden")
        }
        cancelWhenRestart += system.scheduler.schedule(config.reconcileTemporaryThresholdInterval,
                                                       config.reconcileTemporaryThresholdInterval) {
          self ! ReconcileThrottlesEvent(_.spec.temporaryThresholdOverrides.nonEmpty,
                                         "temporaryThresholdOverridden")
        }

      case scala.util.Failure(th) =>
        log.error("error in syncing throttles and pods: {}, {}",
                  th,
                  th.getStackTrace.mkString("\n"))
        log.error("{} will commit suicide", self.path)
        self ! PoisonPill
    }

    for {
      (namespaceVersion, clthrottleVersion, throttleVersion, podVersion) <- syncAll
      // watch namespace
      nsWatch = k8s
        .watchAllContinuously[Namespace](
          sinceResourceVersion = namespaceVersion,
          bufSize = config.watchBufferSize
        )(
          implicitly[Format[Namespace]],
          clusterScopedResourceDefinition[Namespace],
          lc
        )
        .map(NamespaceWatchEvent(_))
      // watch clusterthrottle
      clthrottleWatch = k8s
        .watchAllContinuously[v1alpha1.ClusterThrottle](
          sinceResourceVersion = clthrottleVersion,
          bufSize = config.watchBufferSize
        )(
          implicitly[Format[v1alpha1.ClusterThrottle]],
          clusterScopedResourceDefinition[v1alpha1.ClusterThrottle],
          lc
        )
        .map(ClusterThrottleWatchEvent(_))
      // watch throttle in all namespaces
      throttleWatch = k8s
        .watchAllContinuously[v1alpha1.Throttle](
          sinceResourceVersion = throttleVersion,
          bufSize = config.watchBufferSize
        )(
          implicitly[Format[v1alpha1.Throttle]],
          clusterScopedResourceDefinition[v1alpha1.Throttle],
          lc
        )
        .map(ThrottleWatchEvent(_))
      // watch pods in all namespaces
      podWatch = k8s
        .watchAllContinuously[Pod](
          sinceResourceVersion = podVersion,
          bufSize = config.watchBufferSize
        )(
          implicitly[Format[Pod]],
          clusterScopedResourceDefinition[Pod],
          lc
        )
        .map(PodWatchEvent(_))
      // todo: use RestartSource to make the actor more stable.
      done <- {
        val fut = Source
          .combine(nsWatch, clthrottleWatch, throttleWatch, podWatch)(
            Merge(_, eagerComplete = true))
          .runWith(
            Sink.foreach {
              case event: PodWatchEvent             => self ! event
              case event: ThrottleWatchEvent        => self ! event
              case event: ClusterThrottleWatchEvent => self ! event
              case event: NamespaceWatchEvent       => self ! event
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
      podsInNs: => Set[Pod],
      at: skuber.Timestamp
    ): List[(ObjectKey, v1alpha1.Throttle.Status)] = {
    val nextStatuses = calcNextThrottleStatuses(targetThrottles, podsInNs, at)
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

  def reconcileAllThrottles(at: skuber.Timestamp) =
    reconcileThrottlesWithFilterSync(_ => true, "all", at)
  def reconcileThrottlesWithFilterSync(
      filter: v1alpha1.Throttle => Boolean,
      filterName: String,
      at: skuber.Timestamp
    ): Future[List[v1alpha1.Throttle]] = {
    log.info(s"reconciling statuses of $filterName throttles")

    val throttleStatusesToReconcile = for {
      (namespace, throttles) <- cache.throttles.toImmutable.toList
      targetThrottles        = throttles.filter(filter)
      thr <- _calcNextThrottleStatuses(targetThrottles,
                                       cache.pods.toImmutable.getOrElse(namespace, Set.empty),
                                       at)
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
        log.info(s"finished reconciling statuses of $filterName throttle.")
      case Failure(ex) =>
        log.error(s"failed reconciling statuses of $filterName throttle by: {}", ex)
    }

    fut
  }

  def reconcileThrottlesWithFilterAsync(
      filter: v1alpha1.Throttle => Boolean,
      filterName: String,
      at: skuber.Timestamp
    ) = {
    log.info(s"sending reconcile requests for $filterName throttles")
    for {
      (_, throttles)  <- cache.throttles.toImmutable.toList
      targetThrottles = throttles.filter(filter)
      thr             <- targetThrottles
    } yield {
      self ! ReconcileOneThrottleEvent(thr.key, at)
    }
  }

  def reconcileOneThrottle(key: ObjectKey, at: skuber.Timestamp) = {
    log.info(s"start reconciling statuses of throttle=$key.")

    val (namespace, _) = key
    val throttleStatusToReconcile = for {
      thr <- cache.throttles.get(key).map(List(_)).getOrElse(List.empty)
      result <- calcNextThrottleStatuses(Set(thr),
                                         cache.pods.toImmutable.getOrElse(namespace, Set.empty),
                                         at)
    } yield result

    if (throttleStatusToReconcile.nonEmpty) {
      val fut = Future.sequence(throttleStatusToReconcile.map {
        case (key, st) =>
          syncThrottle(key, st)
      })
      fut.onComplete {
        case Success(_) =>
          log.info(s"finished reconciling status of throttle=$key.")
        case Failure(ex) =>
          log.error(s"failed reconciling status of throttle=$key by: {}", ex)
      }
    } else {
      log.info(s"throttle=$key status are up-to-date. No updates.")
    }
  }

  // on detecting some pod change.
  def updateThrottleBecauseOf(pod: Pod, at: skuber.Timestamp): Unit = {
    val ns                    = pod.namespace
    val allThrottlesInNs      = cache.throttles.toImmutable.getOrElse(ns, Set.empty[v1alpha1.Throttle])
    val affectedThrottlesInNs = allThrottlesInNs.filter(_.spec.selector.matches(pod))

    if (affectedThrottlesInNs.nonEmpty) {
      for {
        (key, st) <- _calcNextThrottleStatuses(affectedThrottlesInNs,
                                               cache.pods.toImmutable.getOrElse(ns, Set.empty[Pod]),
                                               at)
      } yield {
        syncThrottle(key, st)
      }
    }
  }

  // on detecting some throttle change.
  def updateThrottleBecauseOf(throttle: v1alpha1.Throttle, at: skuber.Timestamp): Unit = {
    val ns = throttle.namespace
    for {
      (key, st) <- _calcNextThrottleStatuses(Set(throttle),
                                             cache.pods.toImmutable.getOrElse(ns, Set.empty[Pod]),
                                             at)
    } yield {
      syncThrottle(key, st)
    }
  }

  private def _calcNextClusterThrottleStatuses(
      targetClusterThrottles: Set[v1alpha1.ClusterThrottle],
      podsInAllNamespaces: => Set[Pod],
      namespaces: Map[String, Namespace],
      at: skuber.Timestamp
    ): List[(ObjectKey, v1alpha1.ClusterThrottle.Status)] = {
    val nextStatuses =
      calcNextClusterThrottleStatuses(targetClusterThrottles, podsInAllNamespaces, namespaces, at)
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

  def reconcileAllClusterThrottles(at: skuber.Timestamp) =
    reconcileClusterThrottlesWithFilterSync(_ => true, "all", at)

  def reconcileClusterThrottlesWithFilterSync(
      filter: v1alpha1.ClusterThrottle => Boolean,
      filterName: String,
      at: skuber.Timestamp
    ): Future[List[v1alpha1.ClusterThrottle]] = {
    log.info(s"start reconciling statuses of $filterName clusterthrottle.")

    val clusterThrottleStatusesToReconcile = for {
      (_, throttles) <- cache.clusterThrottles.toImmutable.toList
      filtered       = throttles.filter(filter)
      namespaces     = cache.namespaces.toImmutable.flatMap(kv => kv._2.map(ns => ns.name -> ns).toMap)
      clthr <- _calcNextClusterThrottleStatuses(
                filtered,
                cache.pods.toImmutable.values.fold(Set.empty)(_ ++ _),
                namespaces,
                at)
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
        log.info(s"finished reconciling statuses of $filterName clusterthrottle.")
      case Failure(ex) =>
        log.error(s"failed reconciling statuses of $filterName clusterthrottle by: {}", ex)
    }

    fut
  }

  def reconcileClusterThrottlesWithFilterAsync(
      filter: v1alpha1.ClusterThrottle => Boolean,
      filterName: String,
      at: skuber.Timestamp
    ): Unit = {
    log.info(s"sending reconcile request for $filterName clusterthrottle.")
    for {
      (_, clthrottles) <- cache.clusterThrottles.toImmutable.toList
      filtered         = clthrottles.filter(filter)
      clthr            <- filtered
    } yield {
      self ! ReconcileOneClusterThrottleEvent(clthr.key, at)
    }
  }

  def reconcileOneClusterThrottle(key: ObjectKey, at: skuber.Timestamp): Unit = {
    log.info(s"start reconciling statuses of clusterthrottle=$key.")

    val clusterThrottleStatusToReconcile = for {
      clthr      <- cache.clusterThrottles.get(key).map(List(_)).getOrElse(List.empty)
      namespaces = cache.namespaces.toImmutable.flatMap(kv => kv._2.map(ns => ns.name -> ns).toMap)
      clthr <- calcNextClusterThrottleStatuses(
                Set(clthr),
                cache.pods.toImmutable.values.fold(Set.empty)(_ ++ _),
                namespaces,
                at)
    } yield clthr

    if (clusterThrottleStatusToReconcile.nonEmpty) {
      val fut = Future.sequence(clusterThrottleStatusToReconcile.map {
        case (key, st) =>
          syncClusterThrottle(key, st)
      })

      fut.onComplete {
        case Success(_) =>
          log.info(s"finished reconciling statuses of clusterthrottle=$key.")
        case Failure(ex) =>
          log.error(s"failed reconciling statuses of clusterthrottle=$key by: {}", ex)
      }
    } else {
      log.info(s"clusterthrottle=$key statuses are up-to-date. No updates.")
    }
  }

  // on detecting some pod change.
  def updateClusterThrottleBecauseOf(pod: Pod, at: skuber.Timestamp): Unit = {
    val namespaces =
      cache.namespaces.toImmutable.flatMap(kv => kv._2.map(ns => ns.name -> ns).toMap)
    if (namespaces.contains(pod.namespace)) {
      val allClusterThrottles = cache.clusterThrottles.toImmutable.values.fold(Set.empty)(_ ++ _)
      val affectedClusterThrottles =
        allClusterThrottles.filter(_.spec.selector.matches(pod, namespaces(pod.namespace)))
      if (affectedClusterThrottles.nonEmpty) {
        for {
          (key, st) <- _calcNextClusterThrottleStatuses(
                        affectedClusterThrottles,
                        cache.pods.toImmutable.values.fold(Set.empty)(_ ++ _),
                        namespaces,
                        at)
        } yield {
          syncClusterThrottle(key, st)
        }
      }
    }
  }

  // on detecting some namespace change.
  def updateClusterThrottleBecauseOf(namespace: Namespace, at: skuber.Timestamp): Unit = {
    val podsInAllNamespaces = cache.pods.toImmutable.values.fold(Set.empty)(_ ++ _)
    val allClusterThrottles = cache.clusterThrottles.toImmutable.values.fold(Set.empty)(_ ++ _)
    val namespaces =
      cache.namespaces.toImmutable.flatMap(kv => kv._2.map(ns => ns.name -> ns).toMap)

    for {
      (key, st) <- _calcNextClusterThrottleStatuses(allClusterThrottles,
                                                    podsInAllNamespaces,
                                                    namespaces,
                                                    at)
    } yield {
      syncClusterThrottle(key, st)
    }
  }

  // on detecting some throttle change.
  def updateClusterThrottleBecauseOf(
      clthrottle: v1alpha1.ClusterThrottle,
      at: skuber.Timestamp
    ): Unit = {
    val podsInAllNamespaces = cache.pods.toImmutable.values.fold(Set.empty)(_ ++ _)
    val namespaces =
      cache.namespaces.toImmutable.flatMap(kv => kv._2.map(ns => ns.name -> ns).toMap)

    for {
      (key, st) <- _calcNextClusterThrottleStatuses(Set(clthrottle),
                                                    podsInAllNamespaces,
                                                    namespaces,
                                                    at)
    } yield {
      syncClusterThrottle(key, st)
    }
  }

  override def receive: Receive =
    eventHandler orElse resourceWatchHandler orElse reconcileHandler

  def eventHandler: Receive = {
    case PodWatchEvent(e, at) if isPodResponsible(e._object) =>
      log.info("detected pod {} was {}", e._object.key, e._type)
      val updateOrRemoveThen = e._type match {
        case EventType.DELETED =>
          cache.pods.removeThen(e._object)(_)
        case _ =>
          // Completed pods is not needed anymore
          if (isPodCompleted(e._object)) {
            cache.pods.removeThen(e._object)(_)
          } else {
            cache.pods.addOrUpdateThen(e._object)(_)
          }
      }
      updateOrRemoveThen { pod =>
        updateThrottleBecauseOf(pod, at)
        updateClusterThrottleBecauseOf(pod, at)
      }

    case PodWatchEvent(e, at) if !isPodResponsible(e._object) =>
      log.info(
        "detected pod {} was {}.  but it will be ignored because it is not responsible for {}",
        e._object.key,
        e._type,
        config.targetSchedulerNames)

    case evt @ ClusterThrottleWatchEvent(e, at) if isClusterThrottleResponsible(e._object) =>
      log.info("detected clusterthrottle {} was {}", e._object.key, e._type)
      val prev = cache.clusterThrottles.get(e._object.key)
      val updateOrRemoveCacheThen = e._type match {
        case EventType.DELETED =>
          cache.clusterThrottles.removeThen(e._object)(_)
        case _ =>
          cache.clusterThrottles.addOrUpdateThen(e._object)(_)
      }
      updateOrRemoveCacheThen { clusterThrottle =>
        if (prev.isEmpty || prev.get.spec != clusterThrottle.spec) {
          updateClusterThrottleBecauseOf(clusterThrottle, at)
        }
        e._type match {
          case EventType.DELETED =>
            log.info(
              s"resetting all metrics for ${clusterThrottle.key} because detecting ${clusterThrottle.key} was DELETED.")
            resetClusterThrottleMetric(e._object)
          case _ =>
            recordClusterThrottleSpecMetric(e._object)
        }
      }
      requestHandlerActor forward evt

    case ClusterThrottleWatchEvent(e, at) if !isClusterThrottleResponsible(e._object) =>
      log.info(
        "detected clusterthrottle {} was {}.  but it will be ignored because it is not responsible for {}",
        e._object.key,
        e._type,
        config.throttlerName)

    case evt @ ThrottleWatchEvent(e, at) if isThrottleResponsible(e._object) =>
      log.info("detected throttle {} was {}", e._object.key, e._type)
      val prev = cache.throttles.get(e._object.key)
      val updateOrRemoveCacheThen = e._type match {
        case EventType.DELETED =>
          cache.throttles.removeThen(e._object)(_)
        case _ =>
          cache.throttles.addOrUpdateThen(e._object)(_)
      }
      updateOrRemoveCacheThen { throttle =>
        if (prev.isEmpty || prev.get.spec != throttle.spec) {
          updateThrottleBecauseOf(throttle, at)
        }
        e._type match {
          case EventType.DELETED =>
            log.info(
              s"resetting all metrics for ${throttle.key} because detecting ${throttle.key} was DELETED.")
            resetThrottleMetric(e._object)
          case _ =>
            recordThrottleSpecMetric(e._object)
        }
      }
      requestHandlerActor forward evt

    case ThrottleWatchEvent(e, at) if !isThrottleResponsible(e._object) =>
      log.info(
        "detected throttle {} was {}.  but it will be ignored because it is not responsible for {}",
        e._object.key,
        e._type,
        config.throttlerName)

    case evt @ NamespaceWatchEvent(e, at) =>
      log.info("detected namespace {} was {}", e._object.key, e._type)
      val updateOrRemoveThen = e._type match {
        case EventType.DELETED =>
          cache.namespaces.removeThen(e._object)(_)
        case _ =>
          cache.namespaces.addOrUpdateThen(e._object)(_)
      }
      updateOrRemoveThen { namespace =>
        updateClusterThrottleBecauseOf(namespace, at)
      }
      requestHandlerActor forward evt
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

  def reconcileHandler: Receive = {
    case ReconcileThrottlesEvent(f, fName, at) => reconcileThrottlesWithFilterAsync(f, fName, at)
    case ReconcileClusterThrottlesEvent(f, fName, at) =>
      reconcileClusterThrottlesWithFilterAsync(f, fName, at)
    case ReconcileOneThrottleEvent(key, at)        => reconcileOneThrottle(key, at)
    case ReconcileOneClusterThrottleEvent(key, at) => reconcileOneClusterThrottle(key, at)
  }

  private def latestResourceList[R <: ObjectResource](
      implicit
      k8s: K8SRequestContext,
      ec: ExecutionContext,
      fmt: Format[R],
      rd: ResourceDefinition[R],
      listfmt: Format[ListResource[R]],
      listrd: ResourceDefinition[ListResource[R]]
    ): Future[skuber.ListResource[R]] =
    k8s.list[ListResource[R]]()(implicitly[Format[ListResource[R]]],
                                clusterScopedResourceDefinition[ListResource[R]],
                                lc)

  private[controller] class ObjectResourceCache[R <: ObjectResource](
      val isResponsible: R => Boolean = (_: R) => true,
      val map: mutable.Map[String, mutable.Map[ObjectKey, R]] =
        mutable.Map.empty[String, mutable.Map[ObjectKey, R]]) {

    def init(
        initFilter: R => Boolean = _ => true
      )(implicit
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
        resourceList <- latestResourceList[R]
        latestResourceVersion = resourceList.metadata map {
          _.resourceVersion
        }
        _ = resourceList.items.filter(r => isResponsible(r) && initFilter(r)).foreach(addOrUpdate)
        _ <- Future { log.info("finished syncing {} status", rd.spec.names.singular) }
      } yield latestResourceVersion
    }

    def addOrUpdate(r: R): Unit = addOrUpdateThen(r)(_ => ())
    def remove(r: R): Unit      = removeThen(r)(_ => ())

    def addOrUpdateThen(r: R)(f: R => Unit): Unit = if (isResponsible(r)) {
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

    def toImmutableMap: Map[String, Map[ObjectKey, R]] = map.toMap.mapValues(_.toMap)

    def get(key: ObjectKey): Option[R] = {
      map.get(key._1).flatMap(_.get(key))
    }
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
  case class ReconcileThrottlesEvent(
      filter: v1alpha1.Throttle => Boolean,
      filterName: String,
      at: skuber.Timestamp = java.time.ZonedDateTime.now())
  case class ReconcileOneThrottleEvent(
      key: ObjectKey,
      at: skuber.Timestamp = java.time.ZonedDateTime.now())

  case class ReconcileClusterThrottlesEvent(
      filter: v1alpha1.ClusterThrottle => Boolean,
      filterName: String,
      at: skuber.Timestamp = java.time.ZonedDateTime.now())
  case class ReconcileOneClusterThrottleEvent(
      key: ObjectKey,
      at: skuber.Timestamp = java.time.ZonedDateTime.now())

  // messages for controls
  private case class ResourceWatchDone(done: Try[Done])

  case class PodWatchEvent(
      e: K8SWatchEvent[Pod],
      at: skuber.Timestamp = java.time.ZonedDateTime.now())

  case class ThrottleWatchEvent(
      e: K8SWatchEvent[v1alpha1.Throttle],
      at: skuber.Timestamp = java.time.ZonedDateTime.now())

  case class ClusterThrottleWatchEvent(
      e: K8SWatchEvent[v1alpha1.ClusterThrottle],
      at: skuber.Timestamp = java.time.ZonedDateTime.now())

  case class NamespaceWatchEvent(
      e: K8SWatchEvent[Namespace],
      at: skuber.Timestamp = java.time.ZonedDateTime.now())

  def props(requestHandlerActor: ActorRef, k8s: K8SRequestContext, config: KubeThrottleConfig) = {
    implicit val _k8s    = k8s
    implicit val _config = config
    Props(new ThrottleController(requestHandlerActor))
  }
}
