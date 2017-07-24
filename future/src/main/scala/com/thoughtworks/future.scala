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

import scalaz.syntax.all._
import com.thoughtworks.continuation._
import com.thoughtworks.tryt.covariant.TryT

import scala.concurrent.ExecutionContext
import scalaz.{@@, Applicative, BindRec, MonadError, Semigroup}
import scala.language.higherKinds
import scala.util.{Success, Try}
import scalaz.Free.Trampoline
import scalaz.Tags.Parallel

/** The name space that contains [[Future]] and utilities for `Future`.
  *
  * @author 杨博 (Yang Bo)
  */
object future {

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

    @inline
    override def fromTryT[A](tryT: TryT[UnitContinuation, A]): Future[A] = tryT

    @inline
    override def toTryT[A](future: Future[A]): TryT[UnitContinuation, A] = future

    def futureMonadError: MonadError[Future, Throwable] with BindRec[Future] = {
      TryT.tryTBindRec[UnitContinuation](continuationMonad, continuationMonad)
    }

    def futureParallelApplicative(implicit throwableSemigroup: Semigroup[Throwable]): Applicative[ParallelFuture] = {
      TryT.tryTParallelApplicative[UnitContinuation](continuationParallelApplicative, throwableSemigroup)
    }
  }

  /**
    * @group Type class instances
    */
  @inline
  implicit def futureMonadError: MonadError[Future, Throwable] with BindRec[Future] = {
    opacityTypes.futureMonadError
  }

  /**
    * @group Type class instances
    */
  @inline
  implicit def futureParallelApplicative(
      implicit throwableSemigroup: Semigroup[Throwable]): Applicative[ParallelFuture] = {
    opacityTypes.futureParallelApplicative
  }

  /**
    *
    * @group Implicit Views
    */
  implicit final class ScalaFutureToThoughtworksFutureOps[A](scalaFuture: scala.concurrent.Future[A]) {
    def toThoughtworksFuture(implicit executionContext: ExecutionContext): Future[A] = {
      Future.async { continue =>
        scalaFuture.onComplete(continue)
      }
    }
  }

  /**
    *
    * @group Implicit Views
    */
  implicit final class UnitContinuationToThoughtworksFutureOps[A](continuation: UnitContinuation[A]) {
    def toThoughtworksFuture: Future[A] = {
      Future(TryT(continuation.map(Try(_))))
    }
  }

  /**
    *
    * @group Implicit Views
    */
  implicit final class ThoughtworksFutureOps[A](future: Future[A]) {
    @inline
    def toScalaFuture: scala.concurrent.Future[A] = {
      val promise = scala.concurrent.Promise[A]
      onComplete(promise.complete)
      promise.future
    }

    @inline
    def onComplete(continue: Try[A] => Unit): Unit = {
      val Future(TryT(continuation)) = future
      continuation.onComplete(continue)
    }

    @inline
    def safeOnComplete(continue: Try[A] => Trampoline[Unit]): Trampoline[Unit] = {
      val Future(TryT(continuation)) = future
      continuation.safeOnComplete(continue)
    }

    @inline
    def blockingAwait: A = {
      val Future(TryT(continuation)) = future
      continuation.blockingAwait.get
    }

  }

  object Future {

    def safeAsync[A](run: (Try[A] => Trampoline[Unit]) => Trampoline[Unit]): Future[A] = {
      fromContinuation(UnitContinuation.safeAsync(run))
    }

    def async[A](run: (Try[A] => Unit) => Unit): Future[A] = {
      fromContinuation(UnitContinuation.async(run))
    }

    def execute[A](a: => A)(implicit executionContext: ExecutionContext): Future[A] = {
      fromContinuation(UnitContinuation.execute(Try(a)))
    }

    @inline
    def now[A](a: A): Future[A] = {
      fromContinuation(UnitContinuation.now(Success(a)))
    }

    def delay[A](a: => A): Future[A] = {
      fromContinuation(UnitContinuation.delay(Try(a)))
    }

    @inline
    def apply[A](tryT: TryT[UnitContinuation, A]): Future[A] = {
      opacityTypes.fromTryT(tryT)
    }

    @inline
    def unapply[A](future: Future[A]): Some[TryT[UnitContinuation, A]] = {
      Some(opacityTypes.toTryT(future))
    }

    @inline
    private def fromContinuation[A](continuation: UnitContinuation[Try[A]]): Future[A] = {
      apply(TryT[UnitContinuation, A](continuation))
    }

  }

  /** @template */
  type ParallelFuture[A] = Future[A] @@ Parallel

  /** @template */
  type Future[+A] = opacityTypes.Future[A]

}
