package com.thoughtworks.future

import java.lang
import java.util.concurrent.atomic.AtomicReference

import com.thoughtworks.future.Continuation.Task
import com.thoughtworks.future.Future.Zip.{GotA, GotBoth, GotNeither}
import com.thoughtworks.future.Future.{Promise, Zip}
import org.scalatest._
import com.thoughtworks.future.concurrent.Converters._
import org.scalactic.ErrorMessage

import scala.collection.immutable.Queue
import scala.util.control.TailCalls
import scala.util.control.TailCalls.TailRec
import scala.util.{Failure, Success, Try}
import org.scalatest.Inside

/**
  * Created by 张志豪 on 2017/3/28.
  */
class FutureSpec extends AsyncFreeSpec with Matchers {

  "zip" in Future.completeWith {

    val future = Zip(Continuation(1), Continuation("String"))

    for (value <- future) yield {
      value should be((1, "String"))
    }

  }

  "zip 2" in Future.completeWith {

    val p1 = Promise[Int]
    val p2 = Promise[String]

    val future = Zip(p1, p2)

    val assertions = for (value <- future) yield {
      value should be((1, "String"))
    }

    p1.complete(Try(1)).result
    p2.complete(Try("String")).result

    assertions
  }

  "zip 3" in Future.completeWith {

    val p1 = Promise[Int]
    val p2 = Promise[String]
    val p3 = Promise[Double]

    val future = Zip(Zip(p1, p2), p3)

    val assertions = for (value <- future) yield {
      value should be(((1, "String"), 2.3))
    }

    p1.complete(Try(1)).result
    p2.complete(Try("String")).result
    p3.complete(Try(2.3)).result

    assertions
  }

  "zip 4" in Future.completeWith {

    val p1 = Promise[Int]
    val p2 = Promise[String]
    val p3 = Promise[Double]
    val p4 = Promise[(Int, String)]

    val future = Zip(Zip(p1, p2), Zip(p3, p4))

    val assertions = for (value <- future) yield {
      value should be(((1, "String"), (2.3, (2, "aString"))))
    }

    p1.complete(Try(1)).result
    p2.complete(Try("String")).result
    p3.complete(Try(2.3)).result
    p4.complete(Try((2, "aString"))).result

    assertions
  }

  "zip 5" in Future.completeWith {

    val p1 = Promise[Int]
    val p2 = Promise[String]
    val p3 = Promise[Double]
    val p4 = Promise[(Int, String)]

    val future = Zip(Zip(Zip(p1, p2), p3), p4)

    val assertions = for (value <- future) yield {
      value should be((((1, "String"), 2.3), (2, "aString")))
    }

    p1.complete(Try(1)).result
    p2.complete(Try("String")).result
    p3.complete(Try(2.3)).result
    p4.complete(Try((2, "aString"))).result

    assertions
  }

  "zip -- with a exception" in Future.completeWith {
    import scala.concurrent.ExecutionContext
    implicit val executionContext = ExecutionContext.Implicits.global

    case class Boom(errorMessage: ErrorMessage) extends RuntimeException

    val p1 = Promise[Int]
    val p2 = Promise[String]

    val future = Zip(p1, p2)

    val assertions = recoverToSucceededIf[Boom] {
      future
    }

    val boom = Boom("boom!")

    p1.complete(Failure(boom)).result
    p2.complete(Try("String")).result

    assertions
  }

  "zip -- with another exception" in Future.completeWith {
    import scala.concurrent.ExecutionContext
    implicit val executionContext = ExecutionContext.Implicits.global

    case class Boom(errorMessage: ErrorMessage) extends RuntimeException

    val p1 = Promise[Int]
    val p2 = Promise[String]

    val future = Zip(p1, p2)

    val assertions = recoverToSucceededIf[Boom] {
      future
    }

    val boom = Boom("boom!")

    p1.complete(Try(1)).result
    p2.complete(Failure(boom)).result

    assertions
  }

  "zip -- with two exceptions" in Future.completeWith {
    import scala.concurrent.ExecutionContext
    implicit val executionContext = ExecutionContext.Implicits.global

    case class Boom(errorMessage: ErrorMessage) extends RuntimeException
    case class Boom2(errorMessage: ErrorMessage) extends RuntimeException

    val p1 = Promise[Int]
    val p2 = Promise[String]

    val future = Zip(p1, p2)

    val assertions = recoverToSucceededIf[Boom2] {
      future
    }

    val boom = Boom("boom!")
    val boom2 = Boom2("boom!")

    p1.complete(Failure(boom2)).result
    p2.complete(Failure(boom)).result

    assertions
  }

  "zip in order" in Future.completeWith {

    val p1 = Promise[Int]
    val p2 = Promise[String]

    val future = Zip(p1, p2)

    val assertions = for (value <- future) yield {
      value should be((1, "String"))
    }

    future.state.get match {
      case GotNeither(_) =>
    }

    p1.complete(Try(1)).result

    future.state.get match {
      case GotA(Success(a), _) => a should be(1)
    }

    p2.complete(Try("String")).result

    future.state.get match {
      case GotBoth(Success((a, b))) => {
        a should be(1)
        b should be("String")
      }
    }
    assertions
  }

  "zip in complex order" in Future.completeWith {

    val p1 = Promise[Int]
    val p2 = Promise[String]
    val p3 = Promise[Double]
    val p4 = Promise[(Int, String)]

    val future = Zip(Zip(p1, p2), Zip(p3, p4))

    val assertions = for (value <- future) yield {
      value should be(((1, "String"), (2.3, (2, "aString"))))
    }

    future.state.get match {
      case GotNeither(_) =>
    }

    p1.complete(Try(1)).result

    future.state.get match {
      case GotNeither(_) =>
    }

    p2.complete(Try("String")).result

    future.state.get match {
      case GotA(Success(a), _) =>
        a match {
          case (p1Result, p2Result) => {
            p1Result should be(1)
            p2Result should be("String")
          }
        }
    }

    p3.complete(Try(2.3)).result

    future.state.get match {
      case GotA(Success(a), _) =>
        a match {
          case (p1Result, p2Result) => {
            p1Result should be(1)
            p2Result should be("String")
          }
        }
    }

    p4.complete(Try((2, "aString"))).result

    future.state.get match {
      case GotBoth(Success((a, b))) =>
        a match {
          case (p1Result, p2Result) => {
            p1Result should be(1)
            p2Result should be("String")
          }
        }
        b match {
          case (p3Result, p4Result) => {
            p3Result should be(2.3)
            p4Result should be((2, "aString"))
          }
        }
    }

    assertions
  }

}
