package com.thoughtworks.future.sde

import com.thoughtworks.future.Continuation.Task
import com.thoughtworks.future.Future
import org.scalatest.{AsyncFreeSpec, Matchers}
import com.thoughtworks.future.sde.task.AwaitOps
import com.thoughtworks.future.concurrent.Converters._

/**
  * @author 杨博 (Yang Bo) &lt;pop.atry@gmail.com&gt;
  */
final class taskSpec extends AsyncFreeSpec with Matchers {
  "try / finally in a sde block should compile" in Future.completeWith {
    val f: Task[Int] = task(42)
    task {
      try {
        f.! should be(42)
      } finally {
        f.!
      }
    }
  }

  "task block should create a Task" in {

    task {
      1
    } should be(a[Task[_]])

  }

  "task annotation should create a Task" in {

    @task
    val t = 1

    t should be(a[Task[_]])

  }

}
