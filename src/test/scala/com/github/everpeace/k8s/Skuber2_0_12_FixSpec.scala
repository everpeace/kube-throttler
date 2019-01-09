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

package com.github.everpeace.k8s

import org.scalatest.{FreeSpec, Matchers}
import play.api.libs.json._
import skuber._
import Pod.Affinity._
import NodeAffinity.{RequiredDuringSchedulingIgnoredDuringExecution => Required}

class Skuber2_0_12_FixSpec extends FreeSpec with Matchers {

  "FixedNodeSelectorTermFormat" - {
    import Skuber2_0_12_Fix.fixedNodeRequiredDuringSchedulingIgnoredDuringExecutionFormat

    "can parse it only with matchFields but it is skipped" in {
      val termJson = Json.parse(
        """
          |{
          |  "nodeSelectorTerms": [{
          |    "matchFields": [{
          |       "key": "some-key",
          |       "operator": "In",
          |       "values": ["some-value"]
          |    }]
          |  }]
          |}
        """.stripMargin
      )
      val myTerm = Json.fromJson[Required](termJson).get
      val term   = Required(nodeSelectorTerms = List(NodeSelectorTerm(List.empty)))
      myTerm shouldBe term
    }

    "can parse it only with matchExpressions" in {
      val termJson = Json.parse(
        """
          |{
          |  "nodeSelectorTerms": [{
          |    "matchExpressions": [{
          |       "key": "some-key",
          |       "operator": "In",
          |      "values": ["some-value"]
          |    }]
          |  }]
          |}
        """.stripMargin
      )
      val myTerm = Json.fromJson[Required](termJson).get
      val term = Required(
        nodeSelectorTerms = List(
          NodeSelectorTerm(
            MatchExpressions(
              MatchExpression("some-key", NodeSelectorOperator.In, List("some-value"))
            )))
      )
      myTerm shouldBe term
    }

    "can parse it with both matchExpressions and matchFields, but matchFields are ignored" in {
      val termJson = Json.parse(
        """
          |{
          |  "nodeSelectorTerms": [{
          |    "matchExpressions": [{
          |       "key": "some-key",
          |       "operator": "In",
          |       "values": ["some-value"]
          |    }],
          |    "matchFields": [{
          |       "key": "some-key",
          |       "operator": "In",
          |       "values": ["some-value"]
          |    }]
          |  }]
          |}
        """.stripMargin
      )
      val myTerm = Json.fromJson[Required](termJson).get
      val term = Required(
        nodeSelectorTerms = List(
          NodeSelectorTerm(
            MatchExpressions(
              MatchExpression("some-key", NodeSelectorOperator.In, List("some-value"))
            )))
      )
      myTerm shouldBe term
    }
  }
}
