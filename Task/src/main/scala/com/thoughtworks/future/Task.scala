package com.thoughtworks.future

import scala.util.Try
import scala.util.control.TailCalls.TailRec

/**
  * An stateless [[Continuation]] that starts a new asynchronous operation whenever [[onComplete]] being called.
  *
  * @note The result value of the operation will never store in this [[Task]].
  */
trait Task[+AwaitResult] extends Any with Continuation[AwaitResult, Unit]

object Task {

  implicit final class FunctionTask[+AwaitResult](val underlying: (Try[AwaitResult] => TailRec[Unit]) => TailRec[Unit])
      extends AnyVal
      with Task[AwaitResult] {
    override def onComplete(handler: (Try[AwaitResult]) => TailRec[Unit]): TailRec[Unit] = underlying(handler)
  }

}
