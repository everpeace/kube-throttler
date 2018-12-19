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
import akka.actor.{Actor, Props}
import akka.http.scaladsl.model._
import akka.http.scaladsl.testkit.{RouteTestTimeout, ScalatestRouteTest}
import akka.testkit._
import akka.util.{ByteString, Timeout}
import com.github.everpeace.k8s.throttler.controller.ThrottleController
import com.github.everpeace.k8s.throttler.crd.v1alpha1
import com.github.everpeace.k8s.throttler.crd.v1alpha1.{IsResourceThrottled, ResourceAmount}
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport
import io.k8s.pkg.scheduler.api.v1.ExtenderFilterResult
import io.k8s.pkg.scheduler.api.v1.Implicits._
import org.scalatest.{FreeSpec, Matchers}
import skuber.Resource.Quantity
import skuber._

import scala.concurrent.duration._

class RoutesSpec extends FreeSpec with Matchers with ScalatestRouteTest with PlayJsonSupport {

  def dummyActiveThrottleFor(p: Pod) = {
    v1alpha1
      .Throttle(
        "throttle",
        v1alpha1.Throttle.Spec(
          throttlerName = "kube-throttler",
          selector = LabelSelector(),
          threshold = ResourceAmount(
            resourceRequests = Map("cpu" -> Quantity("1"))
          )
        )
      )
      .withNamespace(p.namespace)
      .withStatus(
        v1alpha1.Throttle.Status(
          throttled = IsResourceThrottled(
            resourceRequests = Map("cpu" -> true)
          ),
          used = ResourceAmount(
            resourceRequests = Map("cpu" -> Quantity("2"))
          )
        ))
  }

  def dummyInsufficientThrottleFor(p: Pod) = {
    v1alpha1
      .Throttle(
        "nospacethrottle",
        v1alpha1.Throttle.Spec(
          throttlerName = "kube-throttler",
          selector = LabelSelector(),
          threshold = ResourceAmount(
            resourceRequests = Map("cpu" -> Quantity("1"))
          )
        )
      )
      .withNamespace(p.namespace)
  }

  def dummyActiveClusterThrottleFor(p: Pod) = {
    v1alpha1
      .ClusterThrottle("clusterthrottle",
                       v1alpha1.ClusterThrottle.Spec(
                         throttlerName = "kube-throttler",
                         selector = LabelSelector(),
                         threshold = Map("cpu" -> Quantity("1"))
                       ))
      .withStatus(
        v1alpha1.ClusterThrottle.Status(
          throttled = Map("cpu" -> true),
          used = Map("cpu"      -> Quantity("2"))
        ))
  }

  def dummyInsufficientClusterThrottleFor(p: Pod) = {
    v1alpha1
      .ClusterThrottle("nospacethrottle",
                       v1alpha1.ClusterThrottle.Spec(
                         throttlerName = "kube-throttler",
                         selector = LabelSelector(),
                         threshold = Map("cpu" -> Quantity("1"))
                       ))
  }

  val dummyThrottleController = system.actorOf(
    Props(new Actor {
      override def receive: Receive = {
        case ThrottleController.CheckThrottleRequest(p) if p.name == "throttled" =>
          sender() ! ThrottleController.Throttled(
            p,
            Set(dummyActiveThrottleFor(p)),
            Set(dummyActiveClusterThrottleFor(p)),
            Set(dummyInsufficientThrottleFor(p)),
            Set(dummyInsufficientClusterThrottleFor(p))
          )
        case ThrottleController.CheckThrottleRequest(p) if p.name == "not-throttled" =>
          sender() ! ThrottleController.NotThrottled(p)
        case ThrottleController.CheckThrottleRequest(p) if p.name == "timeout" =>
        // timeout
      }
    }),
    "dummyThrottleController"
  )

  val checkThrottleRoute = new Routes(dummyThrottleController, Timeout(1 second)).checkThrottle

  "check_throttle" - {
    "should return ExtenderFilterResult without FailedNodes and Errors" in {

      val requestBody = """|{
                         |  "Pod": {
                         |    "metadata": {
                         |      "name": "not-throttled",
                         |      "namespace": "default"
                         |    }
                         |  },
                         |  "NodeNames": [ "minikube" ],
                         |  "Nodes": {
                         |    "metadata": {},
                         |    "items": [
                         |      {
                         |        "metadata": {
                         |          "name": "minikube"
                         |        }
                         |      }
                         |    ]
                         |  }
                         |}
                         |""".stripMargin

      val request = HttpRequest(HttpMethods.POST,
                                "/check_throttle",
                                entity = HttpEntity(
                                  MediaTypes.`application/json`,
                                  ByteString(requestBody)
                                ))

      request ~> checkThrottleRoute ~> check {
        status shouldBe StatusCodes.OK
        responseAs[ExtenderFilterResult] shouldBe ExtenderFilterResult(
          nodenames = List("minikube"),
          nodes = Option(
            ListResource[Node](
              apiVersion = "v1",
              kind = "NodeList",
              metadata = Option(ListMeta()),
              items = List(Node(metadata = ObjectMeta(name = "minikube", namespace = "")))
            ))
        )
      }
    }

    "should return ExtenderFilterResult with FailedNodes and without Errors" in {
      val requestBody = """|{
                           |  "Pod": {
                           |    "metadata": {
                           |      "name": "throttled",
                           |      "namespace": "default"
                           |    }
                           |  },
                           |  "NodeNames": [ "minikube" ],
                           |  "Nodes": {
                           |    "metadata": {},
                           |    "items": [
                           |      {
                           |        "metadata": {
                           |          "name": "minikube"
                           |        }
                           |      }
                           |    ]
                           |  }
                           |}
                           |""".stripMargin

      val request = HttpRequest(HttpMethods.POST,
                                "/check_throttle",
                                entity = HttpEntity(
                                  MediaTypes.`application/json`,
                                  ByteString(requestBody)
                                ))

      request ~> checkThrottleRoute ~> check {
        status shouldBe StatusCodes.OK
        responseAs[ExtenderFilterResult] shouldBe ExtenderFilterResult(
          nodenames = List.empty,
          nodes = Option(
            ListResource[Node](
              apiVersion = "v1",
              kind = "NodeList",
              metadata = Option(ListMeta()),
              items = List.empty
            )
          ),
          failedNodes = Map(
            "minikube" -> "pod (default,throttled) is unschedulable due to throttles[active]=(default,throttle), clusterthrottles[active]=clusterthrottle, throttles[insufficient]=(default,nospacethrottle), clusterthrottles[insufficient]=nospacethrottle"),
          error = None
        )
      }
    }

    "should return ExtenderFilterResult with FailedNodes and Errors" in {
      val requestBody = """|{
                           |  "Pod": {
                           |    "metadata": {
                           |      "name": "timeout",
                           |      "namespace": "default"
                           |    }
                           |  },
                           |  "NodeNames": [ "minikube" ],
                           |  "Nodes": {
                           |    "metadata": {},
                           |    "items": [
                           |      {
                           |        "metadata": {
                           |          "name": "minikube"
                           |        }
                           |      }
                           |    ]
                           |  }
                           |}
                           |""".stripMargin

      val request = HttpRequest(HttpMethods.POST,
                                "/check_throttle",
                                entity = HttpEntity(
                                  MediaTypes.`application/json`,
                                  ByteString(requestBody)
                                ))

      implicit val timeout = RouteTestTimeout(3.seconds.dilated)
      val messageHead =
        """exception occurred in checking throttles for pod (default,timeout): Ask timed out on"""

      request ~> checkThrottleRoute ~> check {
        status shouldBe StatusCodes.OK
        val result = responseAs[ExtenderFilterResult]
        result.nodenames shouldBe List.empty
        result.nodes shouldBe Option(
          ListResource[Node](
            apiVersion = "v1",
            kind = "NodeList",
            metadata = Option(ListMeta()),
            items = List.empty
          )
        )
        result.failedNodes.size shouldBe 1
        result.failedNodes("minikube") should startWith(messageHead)
        result.error.get should startWith(messageHead)
      }
    }
  }
}
