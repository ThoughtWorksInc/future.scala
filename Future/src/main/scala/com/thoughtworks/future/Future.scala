package com.thoughtworks.future

import java.util.concurrent.atomic.AtomicReference

import scala.collection.immutable.Queue
import scala.util.{Failure, Success, Try}
import scala.util.control.Exception.Catcher
import scala.util.control.TailCalls.{TailRec, done, tailcall}
import Continuation._

/**
  * An stateful [[Continuation]] that represents an asynchronous operation already started.
  */
trait Future[+AwaitResult] extends Any with Continuation[AwaitResult, Unit] {

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
    * A [[Future]] that will be completed when another [[Future]] or [[Task]] being completed.
    * @param state The internal state that should never be accessed by other modules.
    */
  trait Promise[AwaitResult] extends Any with Future[AwaitResult] {

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
      * @throws java.lang.IllegalStateException Passed to `catcher` when this [[Promise]] being completed more once.
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

    final class AnyValPromise[AwaitResult] private[Future] (
        val state: AtomicReference[Either[Queue[Try[AwaitResult] => TailRec[Unit]], Try[AwaitResult]]])
        extends AnyVal
        with Promise[AwaitResult]

    def apply[AwaitResult] = new AnyValPromise[AwaitResult](new AtomicReference(Left(Queue.empty)))
  }

  def apply[AwaitResult](task: Continuation[AwaitResult, Unit]): Future[AwaitResult] = {
    val promise = Promise[AwaitResult]
    promise.completeWith(task)
    promise
  }
}
