package com.thoughtworks.future

import java.util.concurrent.atomic.AtomicReference

import scala.collection.immutable.Queue
import scala.util.{Failure, Success, Try}
import scala.util.control.Exception.Catcher
import scala.util.control.TailCalls.{TailRec, done, tailcall}
import Continuation._
import com.thoughtworks.future.Future.Promise.State

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

  /**
    * A [[Future]] that will be completed when another [[Future]] or [[Continuation.Task]] being completed.
    */
  trait Promise[AwaitResult] extends Any with Future[AwaitResult] {

    /**
      * Returns the internal state that should never be accessed by other modules.
      */
    protected def state: AtomicReference[Either[Queue[Try[AwaitResult] => TailRec[Unit]], Try[AwaitResult]]]

    private def dispatch(handlers: Queue[Try[AwaitResult] => TailRec[Unit]], value: Try[AwaitResult]): TailRec[Unit] = {
      if (handlers.isEmpty) {
        done(())
      } else {
        val (handler, tail) = handlers.dequeue
        handler(value).flatMap { _ =>
          dispatch(tail, value)
        }
      }
    }

    override final def value = state.get.right.toOption

    // @tailrec // Comment this because of https://issues.scala-lang.org/browse/SI-6574
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
      * @usecase def completeWith(other: Future[AwaitResult]): Unit = ???
      */
    final def completeWith[OriginalAwaitResult](other: Continuation[OriginalAwaitResult, Unit])(
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

    // @tailrec // Comment this annotation because of https://issues.scala-lang.org/browse/SI-6574
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
      * @usecase def tryCompleteWith(other: Future[AwaitResult]): Unit = ???
      */
    final def tryCompleteWith[OriginalAwaitResult](other: Continuation[OriginalAwaitResult, Unit])(
        implicit view: Try[OriginalAwaitResult] <:< Try[AwaitResult]): Unit = {
      implicit def catcher: Catcher[TailRec[Unit]] = {
        case e: Throwable => {
          val value = Failure(e)
          tailcall(tryComplete(value))
        }
      }
      (other.onComplete { b =>
        tailcall(tryComplete(b))
      }).result
    }

    // @tailrec // Comment this annotation because of https://issues.scala-lang.org/browse/SI-6574
    override final def onComplete(handler: Try[AwaitResult] => TailRec[Unit]): TailRec[Unit] = {
      state.get match {
        case Right(value) => {
          handler(value)
        }
        case oldState @ Left(tail) => {
          if (state.compareAndSet(oldState, Left(tail.enqueue(handler)))) {
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
        override protected final def state: this.type = this
      }
    }

  }

  def apply[AwaitResult](task: Continuation[AwaitResult, Unit]): Future[AwaitResult] = {
    val promise = Promise[AwaitResult]
    promise.completeWith(task)
    promise
  }

  // TODO: state
  trait Zip[A, B] extends Future[(A, B)] {
    protected def state: AtomicReference[Zip.State[A, B]]

    override final def value: Option[Try[(A, B)]] = ???

    override final def onComplete(handler: (Try[(A, B)]) => TailRec[Unit]): TailRec[Unit] = ???
  }

  object Zip {
    private[Zip] sealed trait State[A, B]
    private final case class GotNeither[A, B](handlers: Queue[Try[(A, B)] => TailRec[Unit]]) extends State[A, B]
    private final case class GotA[A, B](a: A, handlers: Queue[Try[(A, B)] => TailRec[Unit]]) extends State[A, B]
    private final case class GotF[A, B](f: B, handlers: Queue[Try[(A, B)] => TailRec[Unit]]) extends State[A, B]
    private final case class GotBoth[A, B](a: A, b: B) extends State[A, B]

    def apply[A, B](continuationA: Task[A], continuationB: Task[B]): Zip[A, B] = {
      val zip = new AtomicReference[State[A, B]](GotNeither[A, B](Queue.empty)) with Zip[A, B] {
        override protected final def state: this.type = this
      }

      ???

      zip
    }

  }

}
