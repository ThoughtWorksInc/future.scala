package com.thoughtworks.future.scalatest

import com.thoughtworks.future.continuation.Continuation

import scala.language.implicitConversions
import scala.concurrent.Promise
import scalaz.Trampoline

/**
  * @author 杨博 (Yang Bo)
  */
trait ContinuationToScalaFuture {

  implicit def continuationToScalaFuture[A](task: Continuation[Unit, A]): scala.concurrent.Future[A] = {
    val promise = Promise[A]
    Continuation.onComplete(task) { a =>
      val _ = promise.success(a)
    }
    promise.future
  }
}
