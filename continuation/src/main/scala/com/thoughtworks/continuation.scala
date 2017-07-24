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

import java.util.concurrent.atomic.AtomicReference

import com.thoughtworks.continuation._

import scala.annotation.tailrec
import scala.collection.immutable.Queue
import scala.concurrent.{ExecutionContext, Future, Promise}
import scalaz.{-\/, @@, Applicative, BindRec, ContT, Monad, Tags, Trampoline, Zip, \/, \/-}
import scalaz.Free.Trampoline
import scalaz.Tags.Parallel
import scala.language.higherKinds
import scala.language.existentials
import scala.util.Try

/**
  * @author 杨博 (Yang Bo)
  */
object continuation {

  @inline
  private def suspend[R](a: => Trampoline[R]) = Trampoline.suspend(a)

  private[continuation] trait OpacityTypes {
    type Continuation[R, +A]
    type ParallelContinuation[R, A] = Continuation[R, A] @@ Parallel

    def toContT[R, A](continuation: Continuation[R, A]): ContT[Trampoline, R, _ <: A]
    def fromContT[R, A](contT: ContT[Trampoline, R, _ <: A]): Continuation[R, A]
  }

  private[continuation] sealed trait ParallelZipState[A, B]

  private[continuation] object ParallelZipState {

    private[continuation] final case class GotNeither[A, B]() extends ParallelZipState[A, B]

    private[continuation] final case class GotA[A, B](a: A) extends ParallelZipState[A, B]

    private[continuation] final case class GotB[A, B](b: B) extends ParallelZipState[A, B]

  }

  @inline
  private[continuation] val opacityTypes: OpacityTypes = new OpacityTypes {
    type Continuation[R, +A] = ContT[Trampoline, R, _ <: A]

    def toContT[R, A](continuation: Continuation[R, A]): ContT[Trampoline, R, _ <: A] = continuation
    def fromContT[R, A](contT: ContT[Trampoline, R, _ <: A]): Continuation[R, A] = contT
  }

  /** The stack-safe and covariant version of [[scalaz.Cont]].
    * @note The underlying type of this `Continuation` is `ContT[Trampoline, R, _ <: A]`.
    * @see [[ContinuationOps]] for extension methods for this `Continuation`.
    * @see [[UnitContinuation]] if you want to use this `Continuation` as an asynchronous task.
    * @template
    */
  type Continuation[R, +A] = opacityTypes.Continuation[R, A]

  /** A [[Continuation]] whose response type is [[scala.Unit]].
    *
    * This `UnitContinuation` type can be used as an asynchronous task.
    *
    * @see [[UnitContinuationOps]] for extension methods for this `UnitContinuationOps`.
    * @see [[ParallelContinuation]] for parallel version of this `UnitContinuation`.
    * @note This `UnitContinuation` type does not support exception handling.
    * @see [[com.thoughtworks.future.Future Future]] for asynchronous task that supports exception handling.
    * @template
    */
  type UnitContinuation[+A] = Continuation[Unit, A]

  /**
    * @group Implicit Views
    */
  implicit final class ContinuationOps[R, A](val underlying: Continuation[R, A]) extends AnyVal {

    /** Runs the [[underlying]] continuation.
      *
      * @param continue the callback function that will be called once the [[underlying]] continuation complete.
      * @note The JVM call stack will grow if there are recursive calls to [[onComplete]] in `continue`.
      *       A `StackOverflowError` may occurs if the recursive calls are very deep.
      * @see [[safeOnComplete]] in case of `StackOverflowError`.
      *
      */
    @inline
    def onComplete(continue: A => R): R = {
      opacityTypes
        .toContT(underlying)
        .run { a =>
          Trampoline.delay(continue(a))
        }
        .run
    }

    /** Runs the [[underlying]] continuation like [[onComplete]], except this `safeOnComplete` is stack-safe. */
    @inline
    def safeOnComplete(continue: A => Trampoline[R]): Trampoline[R] = {
      Continuation.safeOnComplete(underlying)(continue)
    }

    @inline
    def reset(implicit aAsR: A <:< R): R = {
      onComplete(aAsR)
    }

  }

  private final class BlockingState[A] {
    @volatile var result: Option[A] = None
  }

  /**
    * @group Implicit Views
    */
  implicit final class UnitContinuationOps[A](val underlying: UnitContinuation[A]) extends AnyVal {

    /** Returns a memorized [[scala.concurrent.Future]] for the [[underlying]] [[UnitContinuation]].*/
    def toScalaFuture: Future[A] = {
      val promise = Promise[A]
      ContinuationOps[Unit, A](underlying).onComplete { a =>
        val _ = promise.success(a)
      }
      promise.future
    }

    /** Blocking waits and returns the result value of the [[underlying]] [[UnitContinuation]].*/
    def blockingAwait(): A = {
      val state = new BlockingState[A]
      state.synchronized {
        underlying.onComplete { a =>
          state.synchronized {
            state.result = Some(a)
            state.notify()
          }
        }
        while (state.result.isEmpty) {
          state.wait()
        }
      }
      val Some(a) = state.result
      a
    }
  }

  /** [[scalaz.Tags.Parallel Parallel]]-tagged type of [[UnitContinuation]] that needs to be executed in parallel when using an Applicative instance
    *
    * @example Given two [[ParallelContinuation]]s that contain immediate values,
    *
    *          {{{
    *          import com.thoughtworks.continuation._
    *          import scalaz.Tags.Parallel
    *          import scalaz.syntax.all._
    *
    *          val pc0: ParallelContinuation[Int] = Parallel(Continuation.now[Unit, Int](40))
    *          val pc1: ParallelContinuation[Int] = Parallel(Continuation.now[Unit, Int](2))
    *          }}}
    *
    *          when map them together,
    *
    *          {{{
    *          val result: ParallelContinuation[Int] = (pc0 |@| pc1)(_ + _)
    *          }}}
    *
    *          then the result should be a `ParallelContinuation` as well,
    *          and it is able to convert to a normal [[Continuation]]
    *
    *          {{{
    *          Parallel.unwrap(result).map {
    *            _ should be(42)
    *          }
    *          }}}
    *
    * @example Given two [[ParallelContinuation]]s,
    *          each of them modifies a `var`,
    *
    *          {{{
    *          import com.thoughtworks.continuation._
    *          import scalaz.Tags.Parallel
    *          import scalaz.syntax.all._
    *
    *          var count0 = 0
    *          var count1 = 0
    *
    *          val pc0: ParallelContinuation[Unit] = Parallel(Continuation.delay {
    *            count0 += 1
    *          })
    *          val pc1: ParallelContinuation[Unit] = Parallel(Continuation.delay {
    *            count1 += 1
    *          })
    *          }}}
    *
    *          when map them together,
    *
    *          {{{
    *          val result: ParallelContinuation[Unit] = (pc0 |@| pc1) { (u0: Unit, u1: Unit) => }
    *          }}}
    *
    *          then the two vars have not been modified right now,
    *
    *          {{{
    *          count0 should be(0)
    *          count1 should be(0)
    *          }}}
    *
    *          when the result `ParallelContinuation` get done,
    *          then two vars should be modified only once for each.
    *
    *          {{{
    *          Parallel.unwrap(result).map { _: Unit =>
    *            count0 should be(1)
    *            count1 should be(1)
    *          }
    *          }}}
    *
    * @template
    */
  type ParallelContinuation[A] = UnitContinuation[A] @@ Parallel

  /**
    * @group Type class instances
    */
  implicit object continuationParallelApplicative
      extends Applicative[ParallelContinuation]
      with Zip[ParallelContinuation] {

    override def apply2[A, B, C](fa: => ParallelContinuation[A], fb: => ParallelContinuation[B])(
        f: (A, B) => C): ParallelContinuation[C] = {
      val Parallel(continuation) = tuple2(fa, fb)
      Parallel(continuationMonad.map(continuation) { case (a, b) => f(a, b) })
    }

    override def map[A, B](fa: ParallelContinuation[A])(f: (A) => B): ParallelContinuation[B] = {
      val Parallel(continuation) = fa
      Parallel(continuationMonad.map(continuation)(f))
    }

    override def point[A](a: => A): ParallelContinuation[A] = Parallel(continuationMonad[Unit].point[A](a))

    override def tuple2[A, B](fa: => ParallelContinuation[A],
                              fb: => ParallelContinuation[B]): ParallelContinuation[(A, B)] = {
      import ParallelZipState._

      val continuation
        : Continuation[Unit, (A, B)] = Continuation.safeAsync { (continue: ((A, B)) => Trampoline[Unit]) =>
        def listenA(state: AtomicReference[ParallelZipState[A, B]]): Trampoline[Unit] = {
          @tailrec
          def continueA(state: AtomicReference[ParallelZipState[A, B]], a: A): Trampoline[Unit] = {
            state.get() match {
              case oldState @ GotNeither() =>
                if (state.compareAndSet(oldState, GotA(a))) {
                  Trampoline.done(())
                } else {
                  continueA(state, a)
                }
              case GotA(_) =>
                val forkState = new AtomicReference[ParallelZipState[A, B]](GotA(a))
                listenB(forkState)
              case GotB(b) =>
                suspend {
                  continue((a, b))
                }
            }
          }
          Continuation.safeOnComplete(Parallel.unwrap(fa))(continueA(state, _))
        }
        def listenB(state: AtomicReference[ParallelZipState[A, B]]): Trampoline[Unit] = {
          @tailrec
          def continueB(state: AtomicReference[ParallelZipState[A, B]], b: B): Trampoline[Unit] = {
            state.get() match {
              case oldState @ GotNeither() =>
                if (state.compareAndSet(oldState, GotB(b))) {
                  Trampoline.done(())
                } else {
                  continueB(state, b)
                }
              case GotB(_) =>
                val forkState = new AtomicReference[ParallelZipState[A, B]](GotB(b))
                listenA(forkState)
              case GotA(a) =>
                suspend {
                  continue((a, b))
                }
            }
          }
          Continuation.safeOnComplete(Parallel.unwrap(fb))(continueB(state, _))
        }
        val state = new AtomicReference[ParallelZipState[A, B]](GotNeither())
        import scalaz.syntax.bind._
        listenA(state) >> listenB(state)
      }
      Parallel(continuation)
    }

    override def zip[A, B](fa: => ParallelContinuation[A],
                           fb: => ParallelContinuation[B]): ParallelContinuation[(A, B)] = {
      tuple2(fa, fb)
    }

    override def ap[A, B](fa: => ParallelContinuation[A])(
        f: => ParallelContinuation[(A) => B]): ParallelContinuation[B] = {
      Parallel(continuationMonad.map[(A, A => B), B](Parallel.unwrap[UnitContinuation[(A, A => B)]](tuple2(fa, f))) {
        pair: (A, A => B) =>
          pair._2(pair._1)
      })
    }

  }

  object UnitContinuation {

    /** Returns a [[UnitContinuation]] of a blocking operation that will run on `executionContext`. */
    def execute[A](a: => A)(implicit executionContext: ExecutionContext): UnitContinuation[A] = {
      Continuation.async { continue: (A => Unit) =>
        executionContext.execute(new Runnable {
          override def run(): Unit = continue(a)
        })
      }
    }

    /** A synonym of [[Continuation.async]] */
    def async[A](start: (A => Unit) => Unit): UnitContinuation[A] = {
      Continuation.async(start)
    }

    /** A synonym of [[Continuation.now]] */
    @inline
    def now[A](a: A): UnitContinuation[A] = {
      Continuation.now(a)
    }

    /** A synonym of [[Continuation.delay]] */
    def delay[A](a: => A): UnitContinuation[A] = {
      Continuation.delay(a)
    }

    /** A synonym of [[Continuation.safeAsync]] */
    def safeAsync[A](start: (A => Trampoline[Unit]) => Trampoline[Unit]): UnitContinuation[A] = {
      Continuation.safeAsync(start)
    }

    /** A synonym of [[Continuation.apply]] */
    @inline
    def apply[A](contT: ContT[Trampoline, Unit, _ <: A]): UnitContinuation[A] = {
      Continuation.apply(contT)
    }

    /** A synonym of [[Continuation.unapply]] */
    @inline
    def unapply[A](continuation: UnitContinuation[A]): Some[ContT[Trampoline, Unit, _ <: A]] = {
      Continuation.unapply[Unit, A](continuation)
    }
  }

  /** The companion object for [[Continuation]].
    *
    */
  object Continuation {

    /** Returns a [[Continuation]] of an asynchronous operation.
      *
      * @see [[safeAsync]] in case of `StackOverflowError`.
      */
    def async[R, A](start: (A => R) => R): Continuation[R, A] = {
      safeAsync { continue =>
        Trampoline.delay {
          start { a =>
            continue(a).run
          }
        }
      }
    }

    /** Returns a [[Continuation]] whose value is always `a`. */
    @inline
    def now[R, A](a: A): Continuation[R, A] = Continuation.safeAsync(_(a))

    /** Returns a [[Continuation]] of a blocking operation */
    @inline
    def delay[R, A](block: => A): Continuation[R, A] = Continuation.safeAsync { continue =>
      suspend(continue(block))
    }

    @inline
    private[thoughtworks] def safeOnComplete[R, A](continuation: Continuation[R, A])(
        continue: A => Trampoline[R]): Trampoline[R] = {
      suspend {
        opacityTypes.toContT(continuation).run(continue)
      }
    }

    /** Returns a [[Continuation]] of an asynchronous operation like [[async]] except this method is stack-safe. */
    def safeAsync[R, A](start: (A => Trampoline[R]) => Trampoline[R]): Continuation[R, A] = {
      opacityTypes.fromContT[R, A](ContT(start))
    }

    /** Creates a [[Continuation]] from the raw [[scalaz.ContT]] */
    @inline
    def apply[R, A](contT: ContT[Trampoline, R, _ <: A]): Continuation[R, A] = {
      opacityTypes.fromContT(contT)
    }

    /** Extracts the underlying [[scalaz.ContT]] of `continuation`
      *
      * @example This `unapply` can be used in pattern matching expression.
      *          {{{
      *          import com.thoughtworks.continuation.Continuation
      *          val Continuation(contT) = Continuation.now[Unit, Int](42)
      *          contT should be(a[scalaz.ContT[_, _, _]])
      *          }}}
      *
      */
    @inline
    def unapply[R, A](continuation: Continuation[R, A]): Some[ContT[Trampoline, R, _ <: A]] = {
      Some(opacityTypes.toContT[R, A](continuation))
    }
  }

  /**
    * @group Type class instances
    */
  @inline
  implicit def continuationMonad[R]
    : Monad[Continuation[R, `+?`]] with BindRec[Continuation[R, `+?`]] with Zip[Continuation[R, `+?`]] =
    new Monad[Continuation[R, `+?`]] with BindRec[Continuation[R, `+?`]] with Zip[Continuation[R, `+?`]] {

      @inline
      override def zip[A, B](a: => Continuation[R, A], b: => Continuation[R, B]): Continuation[R, (A, B)] = {
        tuple2(a, b)
      }

      override def bind[A, B](fa: Continuation[R, A])(f: (A) => Continuation[R, B]): Continuation[R, B] = {
        Continuation.safeAsync { (continue: B => Trampoline[R]) =>
          Continuation.safeOnComplete[R, A](fa) { a =>
            Continuation.safeOnComplete[R, B](f(a))(continue)
          }
        }
      }

      @inline
      override def point[A](a: => A): Continuation[R, A] = {
        val contT: ContT[Trampoline, R, A] = ContT.point(a)
        opacityTypes.fromContT(contT)
      }

      override def tailrecM[A, B](f: (A) => Continuation[R, A \/ B])(a: A): Continuation[R, B] = {
        Continuation.safeAsync { (continue: B => Trampoline[R]) =>
          def loop(a: A): Trampoline[R] = {
            Continuation.safeOnComplete(f(a)) {
              case -\/(a) =>
                loop(a)
              case \/-(b) =>
                suspend(continue(b))
            }
          }
          loop(a)
        }
      }

      override def map[A, B](fa: Continuation[R, A])(f: (A) => B): Continuation[R, B] = {
        Continuation.safeAsync { (continue: B => Trampoline[R]) =>
          Continuation.safeOnComplete(fa) { a: A =>
            suspend(continue(f(a)))
          }
        }
      }

      override def join[A](ffa: Continuation[R, Continuation[R, A]]): Continuation[R, A] = {
        bind[Continuation[R, A], A](ffa)(identity)
      }
    }
}
