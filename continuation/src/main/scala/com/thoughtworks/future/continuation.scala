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
import scalaz.{-\/, @@, Applicative, BindRec, ContT, Monad, Trampoline, Zip, \/, \/-}
import scalaz.Free.Trampoline
import scalaz.Tags.Parallel
import scala.language.higherKinds

/**
  * @author 杨博 (Yang Bo)
  */
object continuation {
  private[continuation] trait OpacityTypes {
    type Continuation[+A]
    type ParallelContinuation[A] = Continuation[A] @@ Parallel

    def continuationMonad: Monad[Continuation] with BindRec[Continuation] with Zip[Continuation]
    def continuationParallelApplicative: Applicative[ParallelContinuation] with Zip[ParallelContinuation]

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
    override object continuationMonad extends Monad[Continuation] with BindRec[Continuation] with Zip[Continuation] {

      @inline
      override def zip[A, B](a: => Continuation[A], b: => Continuation[B]): Continuation[(A, B)] = {
        tuple2(a, b)
      }

      override def bind[A, B](fa: Continuation[A])(f: (A) => Continuation[B]): Continuation[B] = {
        ContT { (continue: B => Trampoline[Unit]) =>
          Trampoline.suspend {
            fa.run { a =>
              Trampoline.suspend {
                f(a).run(continue)
              }
            }
          }
        }
      }

      @inline
      override def point[A](a: => A): ContT[Trampoline, Unit, A] = {
        ContT.point(a)
      }

      override def tailrecM[A, B](f: (A) => Continuation[A \/ B])(a: A): Continuation[B] = {
        ContT { (continue: B => Trampoline[Unit]) =>
          def loop(a: A): Trampoline[Unit] = {
            f(a).run {
              case -\/(a) =>
                Trampoline.suspend(loop(a))
              case \/-(b) =>
                Trampoline.suspend(continue(b))
            }
          }
          Trampoline.suspend(loop(a))
        }
      }

      override def map[A, B](fa: Continuation[A])(f: (A) => B): Continuation[B] = {
        ContT { (continue: B => Trampoline[Unit]) =>
          Trampoline.suspend {
            fa.run { a: A =>
              continue(f(a))
            }
          }

        }
      }

      override def join[A](ffa: Continuation[Continuation[A]]): Continuation[A] = {
        bind[Continuation[A], A](ffa)(identity)
      }
    }

    override object continuationParallelApplicative
        extends Applicative[ParallelContinuation]
        with Zip[ParallelContinuation] {

      override def point[A](a: => A): ParallelContinuation[A] = Parallel(continuationMonad.point[A](a))

      override def tuple2[A, B](fa: => ParallelContinuation[A],
                                fb: => ParallelContinuation[B]): ParallelContinuation[(A, B)] = {
        import ParallelZipState._

        val continuation: Continuation[(A, B)] = ContT { (continue: ((A, B)) => Trampoline[Unit]) =>
          def runA(state: AtomicReference[ParallelZipState[A, B]]): Trampoline[Unit] = {
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
                  Trampoline.suspend {
                    runA(forkState)
                  }
                case GotB(b) =>
                  Trampoline.suspend {
                    continue((a, b))
                  }
              }
            }
            Parallel.unwrap(fa).run(handlerA(state, _))
          }
          def runB(state: AtomicReference[ParallelZipState[A, B]]): Trampoline[Unit] = {
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
                  Trampoline.suspend {
                    runB(forkState)
                  }
                case GotA(a) =>
                  Trampoline.suspend {
                    continue((a, b))
                  }
              }
            }
            Parallel.unwrap(fb).run(handlerB(state, _))
          }
          val state = new AtomicReference[ParallelZipState[A, B]](GotNeither())
          import scalaz.syntax.bind._
          runA(state) >> runB(state)
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

    type Continuation[+A] = ContT[Trampoline, Unit, _ <: A]

    def toContT[A](continuation: Continuation[A]): ContT[Trampoline, Unit, _ <: A] = continuation
    def fromContT[A](contT: ContT[Trampoline, Unit, _ <: A]): Continuation[A] = contT
  }

  /** An asynchronous task like `scalaz.concurrent.Future` except this [[Continuation]] are stack-safe and covariant
    *
    * @template
    */
  type Continuation[+A] = opacityTypes.Continuation[A]

  type ParallelContinuation[A] = Continuation[A] @@ Parallel

  object Continuation {
    def now[A](a: A): Continuation[A] = Continuation.async(_(a))
    def delay[A](a: => A): Continuation[A] = Continuation.async(_(a))

    def run[A](continuation: Continuation[A])(handler: A => Trampoline[Unit]): Trampoline[Unit] = {
      opacityTypes.toContT(continuation).run(handler)
    }

    def async[A](run: (A => Trampoline[Unit]) => Trampoline[Unit]): Continuation[A] = {
      opacityTypes.fromContT[A](ContT(run))
    }

    def apply[A](contT: ContT[Trampoline, Unit, _ <: A]): Continuation[A] = {
      opacityTypes.fromContT(contT)
    }

    def unapply[A](future: Continuation[A]) = {
      Some(opacityTypes.toContT(future))
    }

    implicit def continuationMonad: Monad[Continuation] with BindRec[Continuation] with Zip[Continuation] = {
      opacityTypes.continuationMonad
    }

    implicit def continuationParallelApplicative: Applicative[ParallelContinuation] with Zip[ParallelContinuation] = {
      opacityTypes.continuationParallelApplicative
    }

  }
}
