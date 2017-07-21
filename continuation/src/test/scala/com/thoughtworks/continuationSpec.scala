package com.thoughtworks

import com.thoughtworks.continuation_test.ContinuationToScalaFuture
import org.scalatest.{AsyncFreeSpec, Inside, Matchers}

/**
  * @author 杨博 (Yang Bo)
  */
class continuationSpec extends AsyncFreeSpec with Matchers with Inside with ContinuationToScalaFuture {
  "test in paralle" in {

    import com.thoughtworks.continuation._
    import scalaz.Tags.Parallel
    import scalaz.syntax.all._
    import scalaz.Trampoline
    import scalaz.Free.Trampoline

    var resultCount = 0
    var handler0: Option[Unit => Trampoline[Unit]] = None
    var handler1: Option[Unit => Trampoline[Unit]] = None

    val pc0: ParallelContinuation[Unit] = Parallel(Continuation.fromFunction { (handler: Unit => Trampoline[Unit]) =>
      inside(handler0) {
        case None =>
          Trampoline.delay {
            handler0 = Some(handler)
          }
      }
    })
    val pc1: ParallelContinuation[Unit] = Parallel(Continuation.fromFunction { (handler: Unit => Trampoline[Unit]) =>
      inside(handler1) {
        case None =>
          Trampoline.delay {
            handler1 = Some(handler)
          }
      }
    })
    val result: ParallelContinuation[Unit] = (pc0 |@| pc1) { (u0: Unit, u1: Unit) =>
      resultCount should be(0)
      resultCount += 1
    }
    resultCount should be(0);
    {
      Continuation.run(Parallel.unwrap(result)) { _: Unit =>
        Trampoline.delay {
          val _ = resultCount should be(1)
        }
      } >> Trampoline.suspend {
        inside(handler0) {
          case Some(handler) => handler(())
        }
      } >> Trampoline.suspend {
        inside(handler1) {
          case Some(handler) => handler(())
        }
      }
    }.run

    resultCount should be(1)

  }

}
