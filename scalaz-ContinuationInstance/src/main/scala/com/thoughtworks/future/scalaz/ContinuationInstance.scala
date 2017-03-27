package com.thoughtworks.future.scalaz

import com.thoughtworks.future.Continuation
import com.thoughtworks.future.Continuation.{HandleError, Return}

import scala.util.{Failure, Success, Try}
import scala.util.control.TailCalls.TailRec
import scalaz.MonadError

/**
  * @author 杨博 (Yang Bo) &lt;pop.atry@gmail.com&gt;
  */
trait ContinuationInstance[TailRecResult]
    extends MonadError[({ type T[AwaitResult] = Continuation[AwaitResult, TailRecResult] })#T, Throwable] {

  override final def raiseError[A](e: Throwable) = {
    new Return(Failure(e))
  }

  override final def handleError[A](fa: Continuation[A, TailRecResult])(
      f: (Throwable) => Continuation[A, TailRecResult]) = {
    new HandleError(fa, f)
  }

  override final def map[A, B](fa: Continuation[A, TailRecResult])(f: A => B) = {
    import Continuation.{ContinuationOps => _, _}
    fa.map(f)
  }

  override final def bind[A, B](fa: Continuation[A, TailRecResult])(f: (A) => Continuation[B, TailRecResult]) = {
    fa.flatMap(f)
  }

  override final def point[A](a: => A) = {
    new Return(Success(a))
  }
}

object ContinuationInstance {

  implicit def apply[TailRecResult]: ContinuationInstance[TailRecResult] = new ContinuationInstance[TailRecResult] {}

}
