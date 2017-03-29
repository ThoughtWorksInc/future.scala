package com.thoughtworks.future.scalaz

import java.time.{Instant, LocalTime}

import com.thoughtworks.future.Continuation.Task
import com.thoughtworks.future.Future
import com.thoughtworks.future.Future.Promise
import com.thoughtworks.future.concurrent.Converters._
import com.thoughtworks.future.scalaz.TaskInstance.scalazTaskInstance
import org.scalatest._

import scala.collection.mutable
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
class TaskInstanceSpec extends AsyncFreeSpec with Matchers {
  "result" in Future.completeWith {
    val p1 = Promise[Int]
    val p2 = Promise[String]
    val p3 = Promise[Double]
    val p4 = Promise[Float]
    val p5 = Promise[(Int, String)]

    val promiseList: List[Task[Any]] = List(p1, p2, p3, p4, p5)

    val futureList = promiseList.sequence[Task, Any]

    val assertions = for (value <- futureList) yield {
      value should be(List(1, "String", 2.3, 4.5, (6, "aString")))
    }

    p1.complete(Try(1)).result
    p2.complete(Try("String")).result
    p3.complete(Try(2.3)).result
    p4.complete(Try(4.5f)).result
    p5.complete(Try((6, "aString"))).result

    assertions
  }

  "order --in parallel" in Future.completeWith {
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

    val assertions = for (value <- futureList) yield {
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

    orderSeq.reverse.head should be(1)

    assertions
  }

  "order --in parallel 2" in Future.completeWith {
    val handlerCount: mutable.Seq[Int] = mutable.Seq(0, 0, 0, 0, 0)
    val handlerMap: mutable.Map[Int, (Try[Int]) => TailCalls.TailRec[Unit]] = mutable.Map()
    val tailUnit = tailcall[Unit](
      TailCalls.done(())
    )

    def invokeHandler() = {
      if (handlerMap.size == 5) {
        for ((key, handler) <- handlerMap) {
          handler(Try(key)).result
          handlerCount(key - 1) += 1
        }
      }
    }

    val p1 = new Task[Int] {
      override def onComplete(handler: (Try[Int]) => TailCalls.TailRec[Unit]): TailCalls.TailRec[Unit] = {
        handlerMap(1) = handler
        invokeHandler()
        tailUnit
      }
    }
    val p2 = new Task[Int] {
      override def onComplete(handler: (Try[Int]) => TailCalls.TailRec[Unit]): TailCalls.TailRec[Unit] = {
        handlerMap(2) = handler
        invokeHandler()
        tailUnit
      }
    }
    val p3 = new Task[Int] {
      override def onComplete(handler: (Try[Int]) => TailCalls.TailRec[Unit]): TailCalls.TailRec[Unit] = {
        handlerMap(3) = handler
        invokeHandler()
        tailUnit
      }
    }
    val p4 = new Task[Int] {
      override def onComplete(handler: (Try[Int]) => TailCalls.TailRec[Unit]): TailCalls.TailRec[Unit] = {
        handlerMap(4) = handler
        invokeHandler()
        tailUnit
      }
    }
    val p5 = new Task[Int] {
      override def onComplete(handler: (Try[Int]) => TailCalls.TailRec[Unit]): TailCalls.TailRec[Unit] = {
        handlerMap(5) = handler
        invokeHandler()
        tailUnit
      }
    }

    val promiseList: List[Task[Any]] = List(p1, p2, p3, p4, p5)

    val futureList: Task[List[Any]] = promiseList.sequence[Task, Any]

    val assertions = for (value <- futureList) yield {
      value should be(1 to 5)
    }

    handlerCount.product should be(1)

    assertions
  }
}
