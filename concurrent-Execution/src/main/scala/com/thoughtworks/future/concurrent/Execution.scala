package com.thoughtworks.future.concurrent
import com.thoughtworks.future.Continuation.Task

import scala.util.{Failure, Success, Try}

/**
  * @author 杨博 (Yang Bo) &lt;pop.atry@gmail.com&gt;
  */
object Execution {

  import scala.util.control.Exception.Catcher
  import scala.util.control.TailCalls._
  import java.util.concurrent.Executor

  import scala.concurrent.ExecutionContext

  type JumpInto = Task[Unit]

  object JumpInto {

    /**
      * Returns a [[JumpInto]] that causes the code after `JumpInto(executionContext).await` run in `executionContext`.
      *
      * @param executionContext Where the code after `JumpInto(executionContext).await` runs.
      */
    def apply(executionContext: ExecutionContext): JumpInto = new JumpInto {
      override def onComplete(handler: (Try[Unit]) => TailRec[Unit]): TailRec[Unit] = {
        executionContext.execute(new Runnable {
          override final def run(): Unit = {
            handler(Success(())).result
          }
        })
        done(())
      }
    }

    /**
      * Returns a [[JumpInto]] that causes the code after `JumpInto(executor).await` run in `executor`.
      *
      * @param executor Where the code after `JumpInto(executor).await` runs.
      */
    def apply(executor: Executor): JumpInto = new JumpInto {
      override def onComplete(handler: (Try[Unit]) => TailRec[Unit]): TailRec[Unit] = {
        executor.execute(new Runnable {
          override final def run(): Unit = {
            handler(Success(())).result
          }
        })
        done(())
      }
    }

  }


  final def blockingAwait[A](future: Task[A]): A = {
    val lock = new AnyRef
    lock.synchronized {
      @volatile var result: Option[Try[A]] = None
      implicit def catcher: Catcher[Unit] = {
        case e: Exception => {
          lock.synchronized {
            result = Some(Failure(e))
            lock.notifyAll()
          }
        }
      }
      future.foreach { u =>
        lock.synchronized {
          result = Some(Success(u))
          lock.notify()
        }
      }
      while (result == None) {
        lock.wait()
      }
      val Some(some) = result
      some match {
        case Success(u) => u
        case Failure(e) => throw e
      }
    }
  }

}
