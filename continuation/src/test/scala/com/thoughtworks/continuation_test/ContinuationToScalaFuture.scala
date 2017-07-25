package com.thoughtworks.continuation_test

import scala.language.implicitConversions
import com.thoughtworks.continuation._

/**
  * @author 杨博 (Yang Bo)
  */
trait ContinuationToScalaFuture {

  implicit def continuationToScalaFuture[A](continuation: UnitContinuation[A]): scala.concurrent.Future[A] = {
    continuation.toScalaFuture
  }
}
