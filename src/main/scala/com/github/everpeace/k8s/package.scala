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

package com.github.everpeace

import com.github.everpeace.util.Injection.==>
import skuber.LabelSelector._
import skuber.Resource.{Quantity, ResourceList}
import skuber.{LabelSelector, ObjectResource, Pod}

package object k8s {

  type ObjectKey = (String, String)

  def isScheduledAndNotFinished(pod: Pod): Boolean = {
    pod.status.nonEmpty && pod.spec.nonEmpty &&
    pod.status.get.phase.nonEmpty &&
    pod.status.get.phase.get != Pod.Phase.Succeeded &&
    pod.status.get.phase.get != Pod.Phase.Failed &&
    pod.spec.get.nodeName.nonEmpty
  }

  // TODO: change explicit convertion(__.key) to context aware conversion(__.==>)
  implicit val or2key: ObjectResource ==> ObjectKey = new ==>[ObjectResource, ObjectKey] {
    override def to: ObjectResource => ObjectKey = o => o.namespace -> o.name
  }

  implicit class ObjectKeyExtractor(o: ObjectResource) {
    def key: ObjectKey = o.namespace -> o.name
  }

  implicit class LabelSelectorMatcher(selector: LabelSelector) {
    def matches(labels: Map[String, String]): Boolean =
      selector.requirements.forall(_.matches(labels))
  }

  implicit class LabelSelectorRequirementMatcher(requirement: LabelSelector.Requirement) {
    def matches(labels: Map[String, String]): Boolean = requirement match {
      case ExistsRequirement(key) =>
        labels.keys.exists(_ == key)
      case NotExistsRequirement(key) =>
        !labels.keys.exists(_ == key)
      case IsEqualRequirement(key, value) =>
        labels.get(key).contains(value)
      case IsNotEqualRequirement(key, value) =>
        !labels.get(key).contains(value)
      case InRequirement(key, values) =>
        labels.get(key).exists(values.contains(_))
      case NotInRequirement(key, values) =>
        !labels.get(key).exists(values.contains(_))
    }
  }

  implicit class QuantityAddition(qa: Quantity) {
    def add(qb: Quantity) = {
      Quantity((qa.amount + qb.amount).toString())
    }
  }

  val zeroResourceList = Map.empty[String, Quantity]
  implicit class ResourceListAddtion(ra: ResourceList) {
    def add(rb: ResourceList): ResourceList = (ra.toList ++ rb.toList).groupBy(_._1).map {
      case (k, vs) =>
        k -> vs.map(_._2).foldLeft(Quantity("0"))(_ add _)
    }
  }

  implicit class PodTotalRequests(pod: Pod) {
    def totalRequests: ResourceList = {
      pod.spec
        .map { status =>
          (for {
            c   <- status.containers
            res <- c.resources
          } yield res.requests).foldLeft(Map.empty: ResourceList)(_ add _)
        }
        .getOrElse(Map.empty: ResourceList)
    }
  }

  implicit val quantityOrdering: cats.Order[Quantity] = new cats.Order[Quantity] {
    override def compare(x: Quantity, y: Quantity): Int = x.amount.compare(y.amount)
  }

}
