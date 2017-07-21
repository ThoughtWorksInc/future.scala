package com.thoughtworks.continuation_test

import scala.language.implicitConversions
import scala.concurrent.Promise
import com.thoughtworks.continuation._
import org.scalactic.source
import org.scalatest.{Assertion, AsyncFreeSpec}

/**
  * @author 杨博 (Yang Bo)
  */
trait ContinuationToScalaFuture extends AsyncFreeSpec {

  implicit def continuationToScalaFuture[A](continuation: Continuation[Unit, A]): scala.concurrent.Future[A] = {
    // Avoid recursive call to continuationToScalaFuture
    val continuationToScalaFuture = DummyImplicit.dummyImplicit

    import com.thoughtworks.continuation._

    val promise = Promise[A]
    continuation.onComplete(promise.success(_))
    promise.future
  }
}
