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

package io.k8s.pkg.scheduler.api.v1
import org.scalatest.{FreeSpec, Matchers}
import play.api.libs.json._
import ExtenderFormatSpec._
import Implicits._

class ExtenderFormatSpec extends FreeSpec with Matchers {

  "Format[ExtenderArgs]" - {
    "can unmarshal jsons without 'apiVersion' and 'kind'" in {
      argTestJson.validate[ExtenderArgs].isSuccess shouldBe true
    }
    "can marshal ExtenderArgs to json without 'apiVersion' and 'kind'" in {
      Json.toJson(argTestJson.validate[ExtenderArgs].get) shouldBe argTestJson
    }
  }

  "Format[ExtenderFilterResult]" - {
    "can unmarshal jsons without 'apiVersion' and 'kind'" in {
      filterResultJson.validate[ExtenderFilterResult].isSuccess shouldBe true
    }
    "can marshal ExtenderFilterResult to json without 'apiVersion' and 'kind" in {
      Json.toJson(filterResultJson.validate[ExtenderFilterResult].get) shouldBe filterResultJson
    }
  }

  "Format[ExtenderPreemptionArgs]" - {
    "can unmarshal jsons without 'apiVersion' and 'kind'" in {
      preemptArgJson.validate[ExtenderFilterResult].isSuccess shouldBe true
    }
    "can marshal ExtenderPreemptionArgs to json without 'apiVersion' and 'kind" in {
      val v = preemptArgJson.validate[ExtenderPreemptionArgs]
      Json.toJson(v.get) shouldBe preemptArgJson
    }
  }

  "Format[ExtenderPreemptionResult]" - {
    "can unmarshal jsons without 'apiVersion' and 'kind'" in {
      preemptResultJson.validate[ExtenderFilterResult].isSuccess shouldBe true
    }
    "can marshal ExtenderPreemptionResult to json without 'apiVersion' and 'kind" in {
      Json.toJson(preemptResultJson.validate[ExtenderPreemptionResult].get) shouldBe preemptResultJson
    }
  }
}

object ExtenderFormatSpec {
  val argTestJson = Json.parse("""|{
       |  "Pod": {
       |    "metadata": {
       |      "name": "pod-rzgq6",
       |      "labels": {
       |        "throttle": "t1"
       |      }
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
       |""".stripMargin)

  val filterResultJson = Json.parse("""|{
       |  "Nodes": {
       |    "metadata": {},
       |    "items": [
       |      {
       |        "metadata": {
       |          "name": "minikube"
       |        }
       |      }
       |    ]
       |  },
       |  "FailedNodes": {
       |    "minikube": "error"
       |  },
       |  "Error": "error"
       |}
       |""".stripMargin)

  val preemptArgJson = Json.parse("""|{
      |  "Pod": {
      |    "metadata": {
      |      "name": "pod-rzgq6",
      |      "labels": {
      |        "throttle": "t1"
      |      }
      |    }
      |  },
      |  "NodeNameToVictims": {
      |    "node1": {
      |      "Pods": [{
      |        "metadata": {
      |          "name": "pod-rzgq6",
      |          "labels": {
      |            "throttle": "t1"
      |          }
      |        }
      |      }],
      |      "NumPDBViolations": 0
      |    }
      |  },
      |  "NodeNameToMetaVictims": {
      |    "node1": {
      |      "Pods": [{ "UID": "xxx" }],
      |      "NumPDBViolations": 0
      |    }
      |  }
      |}
    """.stripMargin)

  val preemptResultJson = Json.parse("""|{
      |  "NodeNameToMetaVictims":{
      |    "node1": {
      |      "Pods": [{ "UID": "xxx" }],
      |      "NumPDBViolations": 0
      |    }
      |  }
      |}
    """.stripMargin)
}
