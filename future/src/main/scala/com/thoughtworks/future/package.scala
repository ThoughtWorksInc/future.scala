/*
 * Copyright 2017 ThoughtWorks, Inc.
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

package com.thoughtworks

import com.thoughtworks.future.continuation.Continuation
import com.thoughtworks.tryt.covariant.TryT

import scalaz.{@@, Applicative, BindRec, ContT, MonadError, Semigroup}
import scalaz.Free.Trampoline
import scala.language.higherKinds
import scala.util.{Success, Try}
import scalaz.Tags.Parallel

/**
  * @author 杨博 (Yang Bo)
  */
package object future {

  private[future] trait OpacityTypes {
    type Future[+A]
    type ParallelFuture[A] = Future[A] @@ Parallel
    def fromTryT[A](tryT: TryT[Continuation, A]): Future[A]
    def toTryT[A](future: Future[A]): TryT[Continuation, A]
    def futureMonadError: MonadError[Future, Throwable] with BindRec[Future]
    def futureParallelApplicative(implicit throwableSemigroup: Semigroup[Throwable]): Applicative[ParallelFuture]
  }

  private[future] val opacityTypes: OpacityTypes = new OpacityTypes {
    type Future[+A] = TryT[Continuation, A]

    override def fromTryT[A](tryT: TryT[Continuation, A]): Future[A] = tryT

    override def toTryT[A](future: Future[A]): TryT[Continuation, A] = future

    def futureMonadError: MonadError[Future, Throwable] with BindRec[Future] = {
      TryT.tryTBindRec(Continuation.continuationMonad, Continuation.continuationMonad)
    }

    def futureParallelApplicative(implicit throwableSemigroup: Semigroup[Throwable]): Applicative[ParallelFuture] = {
      TryT.tryTParallelApplicative[Continuation](Continuation.continuationParallelApplicative, throwableSemigroup)
    }
  }

  object Future {
    def now[A](a: A): Future[A] = {
      Future(TryT(Continuation.delay(Success(a))))
    }

    def delay[A](a: => A): Future[A] = {
      Future(TryT(Continuation.delay(Try(a))))
    }

    implicit def futureMonadError: MonadError[Future, Throwable] with BindRec[Future] = {
      opacityTypes.futureMonadError
    }

    implicit def futureParallelApplicative(
        implicit throwableSemigroup: Semigroup[Throwable]): Applicative[ParallelFuture] = {
      opacityTypes.futureParallelApplicative
    }

    def apply[A](tryT: TryT[Continuation, A]): Future[A] = {
      opacityTypes.fromTryT(tryT)
    }

    def unapply[A](future: Future[A]): Some[TryT[Continuation, A]] = {
      Some(opacityTypes.toTryT(future))
    }

    def async[A](run: (Try[A] => Trampoline[Unit]) => Trampoline[Unit]): Future[A] = {
      opacityTypes.fromTryT(TryT(Continuation.async(run)))
    }

    def run[A](future: Future[A])(handler: Try[A] => Trampoline[Unit]): Trampoline[Unit] = {
      val TryT(continuation) = opacityTypes.toTryT(future)
      Continuation.run(continuation)(handler)
    }
  }

  /** @template */
  type ParallelFuture[A] = Future[A] @@ Parallel

  /** @template */
  type Future[+A] = opacityTypes.Future[A]

}
