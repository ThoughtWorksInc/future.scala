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

import com.thoughtworks.future.continuation.{Continuation, UnitContinuation}
import com.thoughtworks.tryt.covariant.TryT

import scala.concurrent.ExecutionContext
import scalaz.{@@, Applicative, BindRec, ContT, MonadError, Semigroup, Trampoline}
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
    def fromTryT[A](tryT: TryT[UnitContinuation, A]): Future[A]
    def toTryT[A](future: Future[A]): TryT[UnitContinuation, A]
    def futureMonadError: MonadError[Future, Throwable] with BindRec[Future]
    def futureParallelApplicative(implicit throwableSemigroup: Semigroup[Throwable]): Applicative[ParallelFuture]
  }

  private[future] val opacityTypes: OpacityTypes = new OpacityTypes {
    type Future[+A] = TryT[UnitContinuation, A]

    override def fromTryT[A](tryT: TryT[UnitContinuation, A]): Future[A] = tryT

    override def toTryT[A](future: Future[A]): TryT[UnitContinuation, A] = future

    def futureMonadError: MonadError[Future, Throwable] with BindRec[Future] = {
      TryT.tryTBindRec[UnitContinuation](Continuation.continuationMonad, Continuation.continuationMonad)
    }

    def futureParallelApplicative(implicit throwableSemigroup: Semigroup[Throwable]): Applicative[ParallelFuture] = {
      TryT.tryTParallelApplicative[UnitContinuation](Continuation.continuationParallelApplicative, throwableSemigroup)
    }
  }

  object Future {

    def async[A](run: (Try[A] => Unit) => Unit): Future[A] = {
      fromContinuation(Continuation.async(run))
    }

    def execute[A](a: => A)(implicit executionContext: ExecutionContext): Future[A] = {
      fromContinuation(Continuation.execute(Try(a)))
    }

    def now[A](a: A): Future[A] = {
      fromContinuation(Continuation.now(Success(a)))
    }

    def delay[A](a: => A): Future[A] = {
      fromContinuation(Continuation.delay(Try(a)))
    }

    implicit def futureMonadError: MonadError[Future, Throwable] with BindRec[Future] = {
      opacityTypes.futureMonadError
    }

    implicit def futureParallelApplicative(
        implicit throwableSemigroup: Semigroup[Throwable]): Applicative[ParallelFuture] = {
      opacityTypes.futureParallelApplicative
    }

    def fromTryT[A](tryT: TryT[UnitContinuation, A]): Future[A] = {
      opacityTypes.fromTryT(tryT)
    }

    @inline
    def toTryT[A](future: Future[A]): TryT[UnitContinuation, A] = {
      opacityTypes.toTryT(future)
    }

    @inline
    def toContinuation[A](future: Future[A]): Continuation[Unit, Try[A]] = {
      TryT.unapply[UnitContinuation, A](toTryT(future)).get
    }

    @inline
    def fromContinuation[A](continuation: Continuation[Unit, Try[A]]): Future[A] = {
      fromTryT(TryT[UnitContinuation, A](continuation))
    }

    @inline
    def apply[A](run: (Try[A] => Trampoline[Unit]) => Trampoline[Unit]): Future[A] = {
      fromContinuation(Continuation.apply(run))
    }

    @inline
    def run[A](future: Future[A])(handler: Try[A] => Trampoline[Unit]): Trampoline[Unit] = {
      Continuation.run(toContinuation(future))(handler)
    }

    @inline
    def onComplete[A](future: Future[A])(handler: Try[A] => Unit): Unit = {
      Continuation.onComplete(toContinuation(future))(handler)
    }
  }

  /** @template */
  type ParallelFuture[A] = Future[A] @@ Parallel

  /** @template */
  type Future[+A] = opacityTypes.Future[A]

}
