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
import cats.arrow.{Arrow, Compose}
import cats.syntax.compose._

import scala.language.higherKinds

object Injection {
  trait Injection[+Arr[_, _], A, B] { self =>
    def to: Arr[A, B]
  }

  type InjectionSet[A, B] = Injection[Function1, A, B]
  type ==>[A, B]          = InjectionSet[A, B]
  object ==> {
    def apply[A, B](a2b: A => B): A ==> B = new (A ==> B) {
      override def to: A => B = a2b
    }
  }

  implicit def ArrowInjectionCanBeCompose[Arr[_, _]: Arrow] =
    new Compose[({ type λ[A, B] = Injection[Arr, A, B] })#λ] {
      override def compose[A, B, C](
          f: Injection[Arr, B, C],
          g: Injection[Arr, A, B]
        ): Injection[Arr, A, C] = new Injection[Arr, A, C] {
        override def to: Arr[A, C] = g.to >>> f.to
      }
    }

  implicit def transitiveInjection[Arr[_, _], A, B, C](
      implicit
      arrow: Arrow[Arr],
      ab: Injection[Arr, A, B],
      bc: Injection[Arr, B, C]
    ): Injection[Arr, A, C] = ab >>> bc

  implicit def functorInjection[F[_], A, B](
      implicit
      f: Functor[F],
      inj: A ==> B
    ): InjectionSet[F[A], F[B]] = ==>(f.map(_)(inj.to))

  implicit class injectSyntax[A](a: A) {
    def ==>[B](implicit a2b: A ==> B): B = a2b.to(a)
  }

}
