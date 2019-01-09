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

package com.github.everpeace.util

import cats.Functor
import cats.arrow.Arrow
import cats.syntax.compose._

import scala.language.higherKinds

object Isomorphism {

  trait Isomorphism[+Arr[_, _], A, B] { self =>
    def to: Arr[A, B]
    def from: Arr[B, A]
  }

  type IsoSet[A, B] = Isomorphism[Function1, A, B]
  type <=>[A, B]    = IsoSet[A, B]

  implicit class ArrowIsomorphismSyntax[Arr[_, _]: Arrow, A, B](iso1: Isomorphism[Arr, A, B]) {
    def composeIsomorphism[C](iso2: Isomorphism[Arr, B, C]): Isomorphism[Arr, A, C] =
      new Isomorphism[Arr, A, C] {
        def to   = iso1.to >>> iso2.to
        def from = iso1.from <<< iso2.from
      }
  }

  implicit def functorIso[F[_]: Functor, A, B](implicit iso: A <=> B) = new <=>[F[A], F[B]] {
    override def to: F[A] => F[B] = fa => implicitly[Functor[F]].map(fa)(iso.to)

    override def from: F[B] => F[A] = fb => implicitly[Functor[F]].map(fb)(iso.from)
  }

  implicit class isoSyntax[A](a: A) {
    def <=>[B](implicit ab: A <=> B): B = ab.to(a)
  }
}
