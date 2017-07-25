package com.thoughtworks.future_test

import scala.language.implicitConversions
import com.thoughtworks.future._

import scala.concurrent.Promise

/**
  * @author 杨博 (Yang Bo)
  */
trait ThoughtworksFutureToScalaFuture {

  implicit def continuationToScalaFuture[A](continuation: Future[A]): scala.concurrent.Future[A] = {
    continuation.toScalaFuture
  }
}
