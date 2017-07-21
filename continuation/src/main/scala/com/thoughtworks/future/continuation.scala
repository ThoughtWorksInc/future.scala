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

package com.thoughtworks.future

import java.util.concurrent.atomic.AtomicReference

import scala.annotation.tailrec
import scala.collection.immutable.Queue
import scala.concurrent.ExecutionContext
import scalaz.{-\/, @@, Applicative, BindRec, ContT, Monad, Tags, Trampoline, Zip, \/, \/-}
import scalaz.Free.Trampoline
import scalaz.Tags.Parallel
import scala.language.higherKinds
import scala.language.existentials

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

  private[continuation] val opacityTypes: OpacityTypes = new OpacityTypes {
    type Continuation[R, +A] = ContT[Trampoline, R, _ <: A]

    def toContT[R, A](continuation: Continuation[R, A]): ContT[Trampoline, R, _ <: A] = continuation
    def fromContT[R, A](contT: ContT[Trampoline, R, _ <: A]): Continuation[R, A] = contT
  }

  /** An asynchronous task like `com.thoughtworks.future.continuation.Continuation` with additional benifits:
    *
    *  - Stack safe
    *  - Support covariant
    *  - Support both JVM and Scala.js
    *
    * @template
    */
  type Continuation[R, +A] = opacityTypes.Continuation[R, A]

  /**
    * @template
    */
  type UnitContinuation[+A] = Continuation[Unit, A]

  /** @example Given two [[ParallelContinuation]]s that contain immediate values,
    *
    *          {{{
    *          import com.thoughtworks.future.continuation._
    *          import scalaz.Tags.Parallel
    *          import scalaz.syntax.all._
    *          import com.thoughtworks.future.continuation.Continuation._
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
    *          import com.thoughtworks.future.continuation._
    *          import scalaz.Tags.Parallel
    *          import scalaz.syntax.all._
    *          import com.thoughtworks.future.continuation.Continuation._
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

  object Continuation {

    def async[R, A](run: (A => R) => R): Continuation[R, A] = {
      Continuation { continue =>
        Trampoline.delay {
          run { a =>
            continue(a).run
          }
        }
      }
    }

    def execute[A](a: => A)(implicit executionContext: ExecutionContext): Continuation[Unit, A] = {
      async { continue: (A => Unit) =>
        executionContext.execute(new Runnable {
          override def run(): Unit = continue(a)
        })
      }
    }

    @inline
    def now[R, A](a: A): Continuation[R, A] = Continuation.apply(_(a))

    @inline
    def delay[R, A](a: => A): Continuation[R, A] = Continuation.apply { continue =>
      suspend(continue(a))
    }

    @inline
    def run[R, A](continuation: Continuation[R, A])(continue: A => Trampoline[R]): Trampoline[R] = {
      suspend {
        opacityTypes.toContT(continuation).run(continue)
      }
    }

    @inline
    def onComplete[R, A](continuation: Continuation[R, A])(continue: A => R): R = {
      opacityTypes
        .toContT(continuation)
        .run { a =>
          Trampoline.delay(continue(a))
        }
        .run
    }

    def apply[R, A](run: (A => Trampoline[R]) => Trampoline[R]): Continuation[R, A] = {
      opacityTypes.fromContT[R, A](ContT(run))
    }

    @inline
    def fromContT[R, A](contT: ContT[Trampoline, R, _ <: A]): Continuation[R, A] = {
      opacityTypes.fromContT(contT)
    }

    @inline
    def toContT[R, A](continuation: Continuation[R, A]): ContT[Trampoline, R, _ <: A] = {
      opacityTypes.toContT[R, A](continuation)
    }

    @inline
    implicit def continuationMonad[R]
      : Monad[Continuation[R, ?]] with BindRec[Continuation[R, ?]] with Zip[Continuation[R, ?]] =
      new Monad[Continuation[R, ?]] with BindRec[Continuation[R, ?]] with Zip[Continuation[R, ?]] {

        @inline
        override def zip[A, B](a: => Continuation[R, A], b: => Continuation[R, B]): Continuation[R, (A, B)] = {
          tuple2(a, b)
        }

        override def bind[A, B](fa: Continuation[R, A])(f: (A) => Continuation[R, B]): Continuation[R, B] = {
          Continuation.apply { (continue: B => Trampoline[R]) =>
            Continuation.run[R, A](fa) { a =>
              Continuation.run[R, B](f(a))(continue)
            }
          }
        }

        @inline
        override def point[A](a: => A): Continuation[R, A] = {
          val contT: ContT[Trampoline, R, A] = ContT.point(a)
          opacityTypes.fromContT(contT)
        }

        override def tailrecM[A, B](f: (A) => Continuation[R, A \/ B])(a: A): Continuation[R, B] = {
          Continuation.apply { (continue: B => Trampoline[R]) =>
            def loop(a: A): Trampoline[R] = {
              Continuation.run(f(a)) {
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
          Continuation.apply { (continue: B => Trampoline[R]) =>
            Continuation.run(fa) { a: A =>
              suspend(continue(f(a)))
            }
          }
        }

        override def join[A](ffa: Continuation[R, Continuation[R, A]]): Continuation[R, A] = {
          bind[Continuation[R, A], A](ffa)(identity)
        }
      }

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

        val continuation: Continuation[Unit, (A, B)] = Continuation.apply { (continue: ((A, B)) => Trampoline[Unit]) =>
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
            Continuation.run(Parallel.unwrap(fa))(continueA(state, _))
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
            Continuation.run(Parallel.unwrap(fb))(continueB(state, _))
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
        Parallel(
          continuationMonad.map[(A, A => B), B](Parallel.unwrap[Continuation[Unit, (A, A => B)]](tuple2(fa, f))) {
            pair: (A, A => B) =>
              pair._2(pair._1)
          })
      }

    }

  }
}
