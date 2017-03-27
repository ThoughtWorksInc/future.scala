package com.thoughtworks.future.concurrent

import com.thoughtworks.future.{Continuation, Future}

import scala.concurrent.Promise
import scala.util.control.Exception.Catcher
import scala.util.control.TailCalls.{TailRec, done}

/**
  * @author 杨博 (Yang Bo) &lt;pop.atry@gmail.com&gt;
  */
object Converters {

  /**
    * Forwards all [[com.thoughtworks.future.Future]] API to the underlying [[scala.concurrent.Future]].
    */
  implicit final class FromConcurrentFuture[AwaitResult](underlying: scala.concurrent.Future[AwaitResult])(
      implicit executor: scala.concurrent.ExecutionContext)
      extends Future[AwaitResult] {
    import scala.util._

    override def value = underlying.value

    override def onComplete(handler: (Try[AwaitResult]) => TailRec[Unit]): TailRec[Unit] = {
      underlying.onComplete { tryA =>
        executor.execute(new Runnable {
          override def run(): Unit = {
            handler(tryA).result
          }
        })
      }
      done(())

    }
  }

  /**
    * Forwards all [[scala.concurrent.Future]] API to the underlying [[com.thoughtworks.future.Future]].
    */
  implicit final class ToConcurrentFuture[AwaitResult](underlying: Future[AwaitResult])
      extends scala.concurrent.Future[AwaitResult] {
    def transform[S](f: scala.util.Try[AwaitResult] => scala.util.Try[S])(
        implicit executor: scala.concurrent.ExecutionContext): scala.concurrent.Future[S] = {
      val p = Promise[S]
      underlying.onComplete { tryA =>
        p.completeWith(scala.concurrent.Future {
          f(tryA).get
        })
        done(())
      }.result
      p.future
    }

    def transformWith[S](f: scala.util.Try[AwaitResult] => scala.concurrent.Future[S])(
        implicit executor: scala.concurrent.ExecutionContext): scala.concurrent.Future[S] = {
      val p = Promise[S]
      underlying.onComplete { tryA =>
        new Runnable {
          override def run(): Unit = {
            p.completeWith(
              f(tryA)
            )
          }
        }
        done(())
      }.result
      p.future
    }

    import scala.concurrent._
    import scala.concurrent.duration.Duration
    import scala.util._

    override def value: Option[Try[AwaitResult]] = underlying.value

    override def isCompleted: Boolean = underlying.isCompleted

    override def onComplete[U](func: (Try[AwaitResult]) => U)(implicit executor: ExecutionContext) {
      underlying.onComplete { tryA =>
        executor.execute(new Runnable {
          override def run(): Unit = {
            func(tryA)
          }
        })
        done(())
      }.result

    }

    override def result(atMost: Duration)(implicit permit: CanAwait): AwaitResult = {
      ready(atMost)
      value.get match {
        case Success(successValue) => successValue
        case Failure(throwable) => throw throwable
      }
    }

    override def ready(atMost: Duration)(implicit permit: CanAwait): this.type = {
      if (atMost eq Duration.Undefined) {
        throw new IllegalArgumentException
      }
      val lock = new AnyRef

      lock.synchronized {
        {
          implicit def catcher: Catcher[Unit] = {
            case throwable: Throwable => {
              lock.synchronized {
                notifyAll()
              }
            }
          }

          for (successValue <- new Continuation.ContinuationOps(underlying)) {
            lock.synchronized {
              notifyAll()
            }
          }
        }

        if (atMost.isFinite) {
          val timeoutAt = atMost.toNanos + System.nanoTime
          while (!isCompleted) {
            val restDuration = timeoutAt - System.nanoTime
            if (restDuration < 0) {
              throw new TimeoutException
            }
            wait(restDuration / 1000000, (restDuration % 1000000).toInt)
          }
        } else {
          while (!isCompleted) {
            wait()
          }
        }
        this
      }
    }

  }
}
