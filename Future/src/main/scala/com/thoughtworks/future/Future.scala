package com.thoughtworks.future

import java.util.concurrent.atomic.AtomicReference

import scala.collection.immutable.Queue
import scala.util.{Failure, Success, Try}
import scala.util.control.Exception.Catcher
import scala.util.control.TailCalls.{TailRec, done, tailcall}
import Continuation._
import com.thoughtworks.future.Future.Promise.State
import com.thoughtworks.future.Future.Zip.{apply => _, _}

import scala.Option
import scala.annotation.tailrec

/**
  * An stateful [[Continuation]] that represents an asynchronous operation already started.
  */
trait Future[+AwaitResult] extends Any with Task[AwaitResult] {

  /**
    * Tests if this [[Future]] is completed.
    */
  def isCompleted: Boolean = value.isDefined

  /**
    * The result of the asynchronous operation.
    */
  def value: Option[Try[AwaitResult]]

}

object Future {

  private def dispatch[AwaitResult](handlers: Queue[Try[AwaitResult] => TailRec[Unit]],
                                    value: Try[AwaitResult]): TailRec[Unit] = {
    if (handlers.isEmpty) {
      done(())
    } else {
      val (head, tail) = handlers.dequeue
      head(value).flatMap { _ =>
        dispatch(tail, value)
      }
    }
  }

  /**
    * A [[Future]] that will be completed when another [[Future]] or [[Continuation.Task]] being completed.
    */
  trait Promise[AwaitResult] extends Any with Future[AwaitResult] {

    /**
      * Returns the internal state that should never be accessed by other modules.
      */
    protected[future] def state: AtomicReference[Either[Queue[Try[AwaitResult] => TailRec[Unit]], Try[AwaitResult]]]

    override final def value = state.get.right.toOption

    @tailrec
    final def complete(value: Try[AwaitResult]): TailRec[Unit] = {
      state.get match {
        case oldState @ Left(handlers) => {
          if (state.compareAndSet(oldState, Right(value))) {
            tailcall(dispatch(handlers, value))
          } else {
            complete(value)
          }
        }
        case Right(origin) => {
          throw new IllegalStateException("Cannot complete a Promise twice!")
        }
      }
    }

    /**
      * Starts a waiting operation that will be completed when `other` being completed.
      *
      * @usecase def completeWith(other: Future[AwaitResult]): Unit = ???
      */
    final def completeWith[OriginalAwaitResult](other: Task[OriginalAwaitResult])(
        implicit view: Try[OriginalAwaitResult] <:< Try[AwaitResult]): Unit = {
      implicit def catcher: Catcher[TailRec[Unit]] = {
        case e: Throwable => {
          val value = Failure(e)
          tailcall(complete(value))
        }
      }

      (other onComplete { b =>
        tailcall(complete(b))
      }).result
    }

    @tailrec
    final def tryComplete(value: Try[AwaitResult]): TailRec[Unit] = {
      state.get match {
        case oldState @ Left(handlers) => {
          if (state.compareAndSet(oldState, Right(value))) {
            tailcall(dispatch(handlers, value))
          } else {
            tryComplete(value)
          }
        }
        case Right(origin) => {
          done(())
        }
      }
    }

    /**
      * Starts a waiting operation that will be completed when `other` being completed.
      * Unlike [[completeWith]], no exception will be created when this [[Promise]] being completed more once.
      *
      * @usecase def tryCompleteWith(other: Future[AwaitResult]): Unit = ???
      */
    final def tryCompleteWith[OriginalAwaitResult](other: Continuation[OriginalAwaitResult, Unit])(
        implicit view: Try[OriginalAwaitResult] <:< Try[AwaitResult]): Unit = {
      other.onComplete { b =>
        tailcall(tryComplete(b))
      }.result
    }

    @tailrec
    override final def onComplete(handler: Try[AwaitResult] => TailRec[Unit]): TailRec[Unit] = {
      state.get match {
        case Right(value) => {
          handler(value)
        }
        case oldState @ Left(handlers) => {
          if (state.compareAndSet(oldState, Left(handlers.enqueue(handler)))) {
            done(())
          } else {
            onComplete(handler)
          }
        }
      }
    }

  }

  object Promise {

    type State[AwaitResult] = Either[Queue[Try[AwaitResult] => TailRec[Unit]], Try[AwaitResult]]

    def apply[AwaitResult]: Promise[AwaitResult] = {
      new AtomicReference[State[AwaitResult]](Left(Queue.empty)) with Promise[AwaitResult] {
        override protected[future] final def state: this.type = this
      }
    }

  }

  class Constant[+AwaitResult](a: Try[AwaitResult]) extends Future[AwaitResult] {

    override final def onComplete(handler: Try[AwaitResult] => TailRec[Unit]): TailRec[Unit] = {
      handler(a)
    }

    override final def isCompleted: Boolean = true

    override final def value: Option[Try[AwaitResult]] = Some(a)

  }

  def apply[AwaitResult](a: => AwaitResult): Future[AwaitResult] = {
    new Constant(Try(a))
  }

  def completeWith[AwaitResult](task: Continuation[AwaitResult, Unit]): Future[AwaitResult] = {
    val promise = Promise[AwaitResult]
    promise.completeWith(task)
    promise
  }

  trait Zip[A, B] extends Future[(A, B)] {
    protected[future] def state: AtomicReference[Zip.State[A, B]]

    override final def value: Option[Try[(A, B)]] =
      state.get() match {
        case GotBoth(result) => Option(result)
      }

    @tailrec
    override final def onComplete(handler: Try[(A, B)] => TailRec[Unit]): TailRec[Unit] = {
      state.get match {
        case GotBoth(both) => handler(both)
        case oldState @ GotNeither(handlers) => {
          if (state.compareAndSet(oldState, GotNeither(handlers.enqueue(handler)))) {
            done(())
          } else {
            onComplete(handler)
          }
        }
        case oldState @ GotA(a, handlers) => {
          if (state.compareAndSet(oldState, GotA(a, handlers.enqueue(handler)))) {
            done(())
          } else {
            onComplete(handler)
          }
        }
        case oldState @ GotB(b, handlers) => {
          if (state.compareAndSet(oldState, GotB(b, handlers.enqueue(handler)))) {
            done(())
          } else {
            onComplete(handler)
          }
        }
      }
    }

    @tailrec
    private def tryCompleteA(value: Try[A]): TailRec[Unit] = {
      state.get match {
        case oldState @ GotNeither(handlers) => {
          if (state.compareAndSet(oldState, GotA(value, handlers))) {
            done(())
          } else {
            tryCompleteA(value)
          }
        }
        case GotA(_, _) =>
          throw new IllegalStateException("Cannot complete a Future twice!")
        case oldState @ GotB(b, handlers) => {
          if (state.compareAndSet(oldState, GotBoth(Try(value.get, b.get)))) {
            tailcall(dispatch(handlers, Try(value.get, b.get)))
          } else {
            tryCompleteA(value)
          }
        }
        case GotBoth(_) =>
          throw new IllegalStateException("Cannot complete a Future twice!")
      }
    }

    @tailrec
    private def tryCompleteB(value: Try[B]): TailRec[Unit] = {
      state.get match {
        case oldState @ GotNeither(handlers) => {
          if (state.compareAndSet(oldState, GotB(value, handlers))) {
            done(())
          } else {
            tryCompleteB(value)
          }
        }
        case GotB(_, _) =>
          throw new IllegalStateException("Cannot complete a Future twice!")
        case oldState @ GotA(a, handlers) => {
          if (state.compareAndSet(oldState, GotBoth(Try(a.get, value.get)))) {
            tailcall(dispatch(handlers, Try(a.get, value.get)))
          } else {
            tryCompleteB(value)
          }
        }
        case GotBoth(_) =>
          throw new IllegalStateException("Cannot complete a Future twice!")
      }
    }

  }

  object Zip {

    private[future] sealed trait State[A, B]

    private[future] final case class GotNeither[A, B](handlers: Queue[Try[(A, B)] => TailRec[Unit]])
        extends State[A, B]

    private[future] final case class GotA[A, B](a: Try[A], handlers: Queue[Try[(A, B)] => TailRec[Unit]])
        extends State[A, B]

    private[future] final case class GotB[A, B](b: Try[B], handlers: Queue[Try[(A, B)] => TailRec[Unit]])
        extends State[A, B]

    private[future] final case class GotBoth[A, B](pair: Try[(A, B)]) extends State[A, B]

    def apply[A, B](taskA: Task[A], taskB: Task[B]): Zip[A, B] = {
      val zip = new AtomicReference[State[A, B]](GotNeither[A, B](Queue.empty)) with Zip[A, B] {
        override protected[future] final def state: this.type = this
      }

      taskA.onComplete { a =>
        tailcall(
          zip.tryCompleteA(a)
        )
      }.result

      taskB.onComplete { b =>
        tailcall(
          zip.tryCompleteB(b)
        )
      }.result

      zip
    }

  }

}
