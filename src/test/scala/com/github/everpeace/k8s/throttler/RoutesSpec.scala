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
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.util.{ByteString, Timeout}
import com.github.everpeace.k8s.throttler.controller.ThrottleController
import com.github.everpeace.k8s.throttler.crd.v1alpha1
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
      .Throttle("throttle",
                v1alpha1.Throttle.Spec(
                  selector = LabelSelector(),
                  threshold = Map("cpu" -> Quantity("1"))
                ))
      .withNamespace(p.namespace)
      .withStatus(
        v1alpha1.Throttle.Status(
          throttled = true,
          used = Map("cpu" -> Quantity("2"))
        ))
  }

  val dummyThrottleController = system.actorOf(Props(new Actor {
    override def receive: Receive = {
      case ThrottleController.CheckThrottleRequest(p) if p.name == "throttled" =>
        sender() ! ThrottleController.Throttled(p, Set(dummyActiveThrottleFor(p)))
      case ThrottleController.CheckThrottleRequest(p) if p.name == "not-throttled" =>
        sender() ! ThrottleController.NotThrottled(p)
    }
  }))

  val checkThrottleRoute = new Routes(dummyThrottleController, Timeout(1 second)).checkThrottle

  "check_throttle" - {
    "should return ExtenderFilterResult without Errors" in {

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

    "should return ExtenderFilterResult with Errors" in {
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
          failedNodes = Map("minikube" -> "pod is unschedulable due to throttles=throttle"),
          error = Some("pod is unschedulable due to throttles=throttle")
        )
      }
    }
  }
}
