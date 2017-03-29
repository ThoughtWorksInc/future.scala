package com.thoughtworks.future.scalaz

import java.time.{Instant, LocalTime}

import com.thoughtworks.future.Continuation.Task
import com.thoughtworks.future.Future
import com.thoughtworks.future.Future.Promise
import com.thoughtworks.future.concurrent.Converters._
import org.scalatest._

import com.thoughtworks.future.scalaz.ContinuationInstance.scalazContinuationInstance

import scala.concurrent.duration._
import scala.util.Try
import scala.util.control.TailCalls
import scala.util.control.TailCalls.tailcall
import scalaz.syntax.traverse.ToTraverseOps
import scalaz.std.list.listInstance
import scalaz.std.option.optionInstance

/**
  * Created by 张志豪 on 2017/3/29.
  */
class ContinuationInstanceSpec extends AsyncFreeSpec with Matchers {
  "order --in sequence" in Future.completeWith {
    var orderSeq: Seq[Int] = Nil
    val p1 = Promise[Int]
    val p2 = new Task[String] {
      override def onComplete(handler: (Try[String]) => TailCalls.TailRec[Unit]): TailCalls.TailRec[Unit] = {
        orderSeq = orderSeq :+ 2
        handler(Try("String"))
      }
    }
    val p3 = new Task[Double] {
      override def onComplete(handler: (Try[Double]) => TailCalls.TailRec[Unit]): TailCalls.TailRec[Unit] = {
        orderSeq = orderSeq :+ 3
        handler(Try(2.3))
      }
    }
    val p4 = new Task[Float] {
      override def onComplete(handler: (Try[Float]) => TailCalls.TailRec[Unit]): TailCalls.TailRec[Unit] = {
        orderSeq = orderSeq :+ 4
        handler(Try(4.5f))
      }
    }
    val p5 = new Task[(Int, String)] {
      override def onComplete(handler: (Try[(Int, String)]) => TailCalls.TailRec[Unit]): TailCalls.TailRec[Unit] = {
        orderSeq = orderSeq :+ 5
        handler(Try((6, "aString")))
      }
    }

    val promiseList: List[Task[Any]] = List(p1, p2, p3, p4, p5)

    val futureList: Task[List[Any]] = promiseList.sequence[Task, Any]

    val assertions: Task[Assertion] = for (value <- futureList) yield {

      orderSeq should be(1 to 5)
      value should be(List(1, "String", 2.3, 4.5, (6, "aString")))
    }

    p1.complete(
        Try(1)
      )
      .result

    p1.onComplete { _ =>
      orderSeq = orderSeq :+ 1
      tailcall[Unit](
        TailCalls.done(())
      )
    }

    assertions
  }
}
