package com.thoughtworks.future.scalaz

import com.thoughtworks.future.Continuation._
import com.thoughtworks.future.Future.Zip

import scala.util.{Failure, Success}
import scalaz.MonadError

/**
  * @author 杨博 (Yang Bo) &lt;pop.atry@gmail.com&gt;
  */
class TaskInstance extends ContinuationInstance[Unit] {

  override final def ap[A, B](fa: => Task[A])(f: => Task[A => B]): Task[B] = {
    map(Zip(fa, f)) { pair: (A, A => B) =>
      pair._2(pair._1)
    }
  }

  override final def apply2[A, B, C](fa: => Task[A], fb: => Task[B])(f: (A, B) => C): Task[C] = {
    map(Zip(fa, fb)) { pair =>
      f(pair._1, pair._2)
    }
  }
}

object TaskInstance {

  implicit def apply: TaskInstance = new TaskInstance {}

}
