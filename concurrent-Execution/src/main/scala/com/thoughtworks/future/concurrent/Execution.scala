package com.thoughtworks.future.concurrent
import com.thoughtworks.future.Task

import scala.util.{Success, Try}

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

}
