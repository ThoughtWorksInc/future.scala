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
  private def suspend[A](a: => Trampoline[A]) = Trampoline.suspend(a)

  private[continuation] trait OpacityTypes {
    type Continuation[+A]
    type ParallelContinuation[A] = Continuation[A] @@ Parallel

    def toContT[A](continuation: Continuation[A]): ContT[Trampoline, Unit, _ <: A]
    def fromContT[A](contT: ContT[Trampoline, Unit, _ <: A]): Continuation[A]
  }

  private[continuation] sealed trait ParallelZipState[A, B]

  private[continuation] object ParallelZipState {

    private[continuation] final case class GotNeither[A, B]() extends ParallelZipState[A, B]

    private[continuation] final case class GotA[A, B](a: A) extends ParallelZipState[A, B]

    private[continuation] final case class GotB[A, B](b: B) extends ParallelZipState[A, B]

  }

  private[continuation] val opacityTypes: OpacityTypes = new OpacityTypes {
    type Continuation[+A] = ContT[Trampoline, Unit, _ <: A]

    def toContT[A](continuation: Continuation[A]): ContT[Trampoline, Unit, _ <: A] = continuation
    def fromContT[A](contT: ContT[Trampoline, Unit, _ <: A]): Continuation[A] = contT
  }

  /** An asynchronous task like `com.thoughtworks.future.continuation.Continuation` except this [[Continuation]] are stack-safe and covariant
    *
    * @template
    */
  type Continuation[+A] = opacityTypes.Continuation[A]

  /** @example Given two [[ParallelContinuation]]s that contain immediate values,
    *
    *          {{{
    *          import com.thoughtworks.future.continuation._
    *          import scalaz.Tags.Parallel
    *          import scalaz.syntax.all._
    *          import com.thoughtworks.future.continuation.Continuation._
    *
    *          val pc0: ParallelContinuation[Int] = Parallel(Continuation.now[Int](40))
    *          val pc1: ParallelContinuation[Int] = Parallel(Continuation.now[Int](2))
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
    *          val pc0: ParallelContinuation[Unit] = Parallel(Continuation.delay[Unit]{
    *            count0 += 1
    *          })
    *          val pc1: ParallelContinuation[Unit] = Parallel(Continuation.delay[Unit]{
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
  type ParallelContinuation[A] = Continuation[A] @@ Parallel

  object Continuation {

    def now[A](a: A): Continuation[A] = Continuation.shift(_(a))

    @inline
    def delay[A](a: => A): Continuation[A] = Continuation.shift { continue =>
      suspend(continue(a))
    }

    @inline
    def run[A](continuation: Continuation[A])(handler: A => Trampoline[Unit]): Trampoline[Unit] = {
      suspend {
        opacityTypes.toContT(continuation).run(handler)
      }
    }

    @inline
    def listen[A](continuation: Continuation[A])(handler: A => Trampoline[Unit]): Unit = {
      run(continuation)(handler).run
    }

    def shift[A](run: (A => Trampoline[Unit]) => Trampoline[Unit]): Continuation[A] = {
      opacityTypes.fromContT[A](ContT(run))
    }

    def apply[A](contT: ContT[Trampoline, Unit, _ <: A]): Continuation[A] = {
      opacityTypes.fromContT(contT)
    }

    def unapply[A](future: Continuation[A]) = {
      Some(opacityTypes.toContT(future))
    }

    implicit object continuationMonad extends Monad[Continuation] with BindRec[Continuation] with Zip[Continuation] {

      @inline
      override def zip[A, B](a: => Continuation[A], b: => Continuation[B]): Continuation[(A, B)] = {
        tuple2(a, b)
      }

      override def bind[A, B](fa: Continuation[A])(f: (A) => Continuation[B]): Continuation[B] = {
        Continuation.shift { (continue: B => Trampoline[Unit]) =>
          Continuation.run[A](fa) { a =>
            Continuation.run[B](f(a))(continue)
          }
        }
      }

      @inline
      override def point[A](a: => A): Continuation[A] = {
        val contT: ContT[Trampoline, Unit, A] = ContT.point(a)
        opacityTypes.fromContT(contT)
      }

      override def tailrecM[A, B](f: (A) => Continuation[A \/ B])(a: A): Continuation[B] = {
        Continuation.shift { (continue: B => Trampoline[Unit]) =>
          def loop(a: A): Trampoline[Unit] = {
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

      override def map[A, B](fa: Continuation[A])(f: (A) => B): Continuation[B] = {
        Continuation.shift { (continue: B => Trampoline[Unit]) =>
          Continuation.run(fa) { a: A =>
            suspend(continue(f(a)))
          }
        }
      }

      override def join[A](ffa: Continuation[Continuation[A]]): Continuation[A] = {
        bind[Continuation[A], A](ffa)(identity)
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

      override def point[A](a: => A): ParallelContinuation[A] = Parallel(continuationMonad.point[A](a))

      override def tuple2[A, B](fa: => ParallelContinuation[A],
                                fb: => ParallelContinuation[B]): ParallelContinuation[(A, B)] = {
        import ParallelZipState._

        val continuation: Continuation[(A, B)] = Continuation.shift { (continue: ((A, B)) => Trampoline[Unit]) =>
          def listenA(state: AtomicReference[ParallelZipState[A, B]]): Trampoline[Unit] = {
            @tailrec
            def handlerA(state: AtomicReference[ParallelZipState[A, B]], a: A): Trampoline[Unit] = {
              state.get() match {
                case oldState @ GotNeither() =>
                  if (state.compareAndSet(oldState, GotA(a))) {
                    Trampoline.done(())
                  } else {
                    handlerA(state, a)
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
            Continuation.run(Parallel.unwrap(fa))(handlerA(state, _))
          }
          def listenB(state: AtomicReference[ParallelZipState[A, B]]): Trampoline[Unit] = {
            @tailrec
            def handlerB(state: AtomicReference[ParallelZipState[A, B]], b: B): Trampoline[Unit] = {
              state.get() match {
                case oldState @ GotNeither() =>
                  if (state.compareAndSet(oldState, GotB(b))) {
                    Trampoline.done(())
                  } else {
                    handlerB(state, b)
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
            Continuation.run(Parallel.unwrap(fb))(handlerB(state, _))
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
        Parallel(continuationMonad.map[(A, A => B), B](Parallel.unwrap[Continuation[(A, A => B)]](tuple2(fa, f))) {
          pair: (A, A => B) =>
            pair._2(pair._1)
        })
      }

    }

  }
}
