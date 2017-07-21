package com.thoughtworks.future.scalatest

import com.thoughtworks.future.continuation.Continuation

import scala.language.implicitConversions
import scala.concurrent.Promise
import scalaz.Trampoline

/**
  * @author 杨博 (Yang Bo)
  */
trait ContinuationToScalaFuture {

  implicit def continuationToScalaFuture[A](task: Continuation[A]): scala.concurrent.Future[A] = {
    val promise = Promise[A]
    Continuation.listen(task) { a =>
      Trampoline.delay {
        val _ = promise.success(a)
      }
    }
    promise.future
  }
}
