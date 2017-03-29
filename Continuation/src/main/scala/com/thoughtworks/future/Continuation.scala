package com.thoughtworks.future

import com.thoughtworks.Extractor._

import scala.util.control.Exception.Catcher
import scala.util.control.NonFatal
import scala.util.control.TailCalls.{TailRec, _}
import scala.util.{Failure, Success, Try}

/**
  * Something that will be completed in the future.
  *
  * @tparam AwaitResult   The type that awaits.
  * @tparam TailRecResult The response type, should be `Unit` in most cases.
  */
trait Continuation[+AwaitResult, TailRecResult] extends Any {

  def onComplete(handler: Try[AwaitResult] => TailRec[TailRecResult]): TailRec[TailRecResult]

}

object Continuation {

  /**
    * An stateless [[Continuation]] that represents an asynchronous operation.
    *
    * The asynchronous operation may either have been started or will lazily start whenever [[onComplete]] being called.
    *
    * @template
    * @note The result value of the operation will never store in this [[Task]].
    */
  type Task[+AwaitResult] = Continuation[AwaitResult, Unit]

  object Task {
    def apply[AwaitResult](a: => AwaitResult): Task[AwaitResult] = new Return(Try(a))
  }

  implicit final class FunctionContinuation[+AwaitResult, TailRecResult](
      val underlying: (Try[AwaitResult] => TailRec[TailRecResult]) => TailRec[TailRecResult])
      extends AnyVal
      with Continuation[AwaitResult, TailRecResult] {
    override def onComplete(handler: (Try[AwaitResult]) => TailRec[TailRecResult]): TailRec[TailRecResult] =
      underlying(handler)
  }

  private[future] implicit class Scala210TailRec[A](underlying: TailRec[A]) {
    final def flatMap[B](f: A => TailRec[B]): TailRec[B] = {
      tailcall(f(underlying.result))
    }
  }

  class Return[+AwaitResult, TailRecResult](a: => Try[AwaitResult]) extends Continuation[AwaitResult, TailRecResult] {
    override def onComplete(handler: Try[AwaitResult] => TailRec[TailRecResult]): TailRec[TailRecResult] = {
      handler(a)
    }
  }

  def apply[AwaitResult, TailRecResult](a: => AwaitResult) = {
    new Return[AwaitResult, TailRecResult](Try(a))
  }

  /**
    * A [[Continuation]] composed of `upstream` and the `converter`.
    *
    * This [[Continuation]] will pass the original result to `converter` when the original asynchronous operation being completed.
    */
  class Map[A, TailRecResult, B](upstream: Continuation[A, TailRecResult], converter: A => B)
      extends Continuation[B, TailRecResult] {
    override final def onComplete(handler: (Try[B]) => TailRec[TailRecResult]): TailRec[TailRecResult] = {
      upstream.onComplete { tryA =>
        val tryB = tryA.map(converter)
        tailcall {
          // Note that the tail call is not executed in `try` block!
          handler(tryB)
        }
      }
    }
  }

  /**
    * A [[Continuation]] composed of this `upstream` and the `converter`.
    * This [[Continuation]] will pass the original result to `converter` when the original asynchronous operation being completed.
    */
  class FlatMap[A, TailRecResult, B](upstream: Continuation[A, TailRecResult],
                                     converter: A => Continuation[B, TailRecResult])
      extends Continuation[B, TailRecResult] {

    override final def onComplete(handler: Try[B] => TailRec[TailRecResult]): TailRec[TailRecResult] = {
      upstream.onComplete {
        case Success(a) =>
          try {
            val futureB = converter(a)
            tailcall {
              // Note that the tail call is not executed in `try` block!
              futureB.onComplete { b =>
                tailcall(handler(b))
              }
            }
          } catch {
            case NonFatal(e) =>
              tailcall(handler(Failure(e)))
          }
        case failure: Failure[A] =>
          tailcall(handler(failure.asInstanceOf[Failure[B]]))
      }

    }
  }

  class WithFilter[+AwaitResult, TailRecResult](upstream: Continuation[AwaitResult, TailRecResult],
                                                condition: AwaitResult => Boolean)
      extends Continuation[AwaitResult, TailRecResult] {

    override final def onComplete(handler: Try[AwaitResult] => TailRec[TailRecResult]): TailRec[TailRecResult] = {
      upstream.onComplete { tryA =>
        val filteredTryA = tryA.filter(condition)
        tailcall(handler(filteredTryA))
      }
    }
  }

  class HandleError[+AwaitResult, TailRecResult](tryBlock: Continuation[AwaitResult, TailRecResult],
                                                 catchBlock: Throwable => Continuation[AwaitResult, TailRecResult])
      extends Continuation[AwaitResult, TailRecResult] {
    override def onComplete(handler: (Try[AwaitResult]) => TailRec[TailRecResult]): TailRec[TailRecResult] = {
      tailcall {
        tryBlock.onComplete {
          case success: Success[AwaitResult] =>
            tailcall(handler(success))
          case Failure(e) =>
            try {
              val recovered = catchBlock(e)
              tailcall(recovered.onComplete(handler))
            } catch {
              case NonFatal(e) =>
                tailcall(handler(Failure(e)))
            }
        }
      }
    }
  }

  implicit final class ContinuationOps[AwaitResult, TailRecResult](
      val underlying: Continuation[AwaitResult, TailRecResult])
      extends AnyVal {

    def handleError[A](f: Throwable => Continuation[A, TailRecResult]) = new HandleError(underlying, f)

    /**
      * Returns a [[Continuation]] composed of [[underlying]] and the `converter`.
      *
      * The newly created [[Continuation]] will pass the original result to `converter` when the original asynchronous operation being completed.
      */
    def map[ConvertedAwaitResult](converter: AwaitResult => ConvertedAwaitResult) =
      new Map(underlying, converter)

    /**
      * Returns a [[Continuation]] composed of [[underlying]] and the `converter`.
      * The newly created [[Continuation]] will pass the original result to `converter` when the original asynchronous operation being completed.
      */
    def flatMap[ConvertedAwaitResult](converter: AwaitResult => Continuation[ConvertedAwaitResult, TailRecResult]) =
      new FlatMap(underlying, converter)

    /**
      * Asks [[underlying]] to pass result to `handler` when the asynchronous operation being completed,
      * or to pass the exception to `catcher` when the asynchronous operation being failed,
      * and starts the asynchronous operation if [[underlying]] is an [[Task]].
      */
    def foreach(handler: AwaitResult => TailRecResult)(implicit catcher: Catcher[TailRecResult]): TailRecResult = {
      underlying.onComplete {
        case Success(a) =>
          done(handler(a))
        case Failure(catcher.extract(recovered)) =>
          done(recovered)
        case Failure(e) =>
          throw e
      }.result
    }

    /**
      * Returns a [[Continuation]] composed of [[underlying]] and the `condition`.
      * The new created [[Continuation]] will pass the original result to `condition` when the original asynchronous operation being completed.
      */
    def withFilter(condition: AwaitResult => Boolean) = new WithFilter(underlying, condition)

  }
}
