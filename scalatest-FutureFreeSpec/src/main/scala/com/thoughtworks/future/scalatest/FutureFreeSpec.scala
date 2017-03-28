package com.thoughtworks.future.scalatest

import com.thoughtworks.future.Future.Promise
import com.thoughtworks.future.{Continuation, Future}
import org.scalatest._
import org.scalactic._
import com.thoughtworks.future.concurrent.Converters._

/**
  * @author 杨博 (Yang Bo) &lt;pop.atry@gmail.com&gt;
  */
abstract class FutureFreeSpec extends AsyncFreeSpec {

  protected implicit final class FutureFreeSpecStringWrapper(message: String)(implicit pos: source.Position) {

    def in(future: Future[compatible.Assertion]): Unit = {
      convertToFreeSpecStringWrapper(message).in(future: scala.concurrent.Future[compatible.Assertion])

    }

    def in(continuation: Continuation[compatible.Assertion, Unit]): Unit = {
      convertToFreeSpecStringWrapper(message).in(Future(continuation): scala.concurrent.Future[compatible.Assertion])
    }

  }
}
