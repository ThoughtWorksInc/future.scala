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

  private trait OpacityTypes {
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

  /** Extension methods for [[scala.concurrent.Future]]
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

  /** Extension methods for [[com.thoughtworks.continuation.UnitContinuation UnitContinuation]]
    *
    * @group Implicit Views
    */
  implicit final class UnitContinuationToThoughtworksFutureOps[A](continuation: UnitContinuation[A]) {
    def toThoughtworksFuture: Future[A] = {
      Future(TryT(continuation.map(Try(_))))
    }
  }

  /** Extension methods for [[Future]]
    *
    * @group Implicit Views
    */
  implicit final class ThoughtworksFutureOps[A](val underlying: Future[A]) extends AnyVal {
    @inline
    def toScalaFuture: scala.concurrent.Future[A] = {
      val promise = scala.concurrent.Promise[A]
      onComplete(promise.complete)
      promise.future
    }

    /** Runs the [[underlying]] [[Future]].
      *
      * @param continue the callback function that will be called once the [[underlying]] continuation complete.
      * @note The JVM call stack will grow if there are recursive calls to [[onComplete]] in `continue`.
      *       A `StackOverflowError` may occurs if the recursive calls are very deep.
      * @see [[safeOnComplete]] in case of `StackOverflowError`.
      */
    @inline
    def onComplete(continue: Try[A] => Unit): Unit = {
      val Future(TryT(continuation)) = underlying
      continuation.onComplete(continue)
    }

    /** Runs the [[underlying]] continuation like [[onComplete]], except this `safeOnComplete` is stack-safe. */
    @inline
    def safeOnComplete(continue: Try[A] => Trampoline[Unit]): Trampoline[Unit] = {
      val Future(TryT(continuation)) = underlying
      continuation.safeOnComplete(continue)
    }

    /** Blocking waits and returns the result value of the [[underlying]] [[Future]].*/
    @inline
    def blockingAwait: A = {
      val Future(TryT(continuation)) = underlying
      continuation.blockingAwait.get
    }

  }

  object Future {

    /** Returns a [[Future]] of an asynchronous operation like [[async]] except this method is stack-safe. */
    def safeAsync[A](run: (Try[A] => Trampoline[Unit]) => Trampoline[Unit]): Future[A] = {
      fromContinuation(UnitContinuation.safeAsync(run))
    }

    /** Returns a [[Future]] of an asynchronous operation.
      *
      * @see [[safeAsync]] in case of `StackOverflowError`.
      */
    def async[A](run: (Try[A] => Unit) => Unit): Future[A] = {
      fromContinuation(UnitContinuation.async(run))
    }

    /** Returns a [[Future]] of a blocking operation that will run on `executionContext`. */
    def execute[A](a: => A)(implicit executionContext: ExecutionContext): Future[A] = {
      fromContinuation(UnitContinuation.execute(Try(a)))
    }

    /** Returns a [[Future]] whose value is always `a`. */
    @inline
    def now[A](a: A): Future[A] = {
      fromContinuation(UnitContinuation.now(Success(a)))
    }

    /** Returns a [[Future]] of a blocking operation */
    def delay[A](a: => A): Future[A] = {
      fromContinuation(UnitContinuation.delay(Try(a)))
    }

    /** Creates a [[Future]] from the raw [[com.thoughtworks.tryt.covariant.TryT]] */
    @inline
    def apply[A](tryT: TryT[UnitContinuation, A]): Future[A] = {
      opacityTypes.fromTryT(tryT)
    }

    /** Extracts the underlying [[com.thoughtworks.tryt.covariant.TryT]] of `future`
      *
      * @example This `unapply` can be used in pattern matching expression.
      *          {{{
      *          import com.thoughtworks.future.Future
      *          import com.thoughtworks.continuation.UnitContinuation
      *          val Future(tryT) = Future.now[Int](42)
      *          tryT should be(a[com.thoughtworks.tryt.covariant.TryT[UnitContinuation, _]])
      *          }}}
      *
      */
    @inline
    def unapply[A](future: Future[A]): Some[TryT[UnitContinuation, A]] = {
      Some(opacityTypes.toTryT(future))
    }

    @inline
    private def fromContinuation[A](continuation: UnitContinuation[Try[A]]): Future[A] = {
      apply(TryT[UnitContinuation, A](continuation))
    }

  }

  /** [[scalaz.Tags.Parallel Parallel]]-tagged type of [[Future]] that needs to be executed in parallel when using an [[scalaz.Applicative]] instance
    * @template
    */
  type ParallelFuture[A] = Future[A] @@ Parallel

  /** @template */
  type Future[+A] = opacityTypes.Future[A]

}
