package com.thoughtworks.future.scalaz

import com.thoughtworks.future.Continuation.{FlatMap, HandleError, Map, Return}
import com.thoughtworks.future.Future.Zip
import com.thoughtworks.future.{Continuation, Task}

import scala.util.{Failure, Success}
import scalaz.MonadError

/**
  * @author 杨博 (Yang Bo) &lt;pop.atry@gmail.com&gt;
  */
class TaskInstance extends MonadError[Task, Throwable] {
  override def raiseError[A](e: Throwable) = {
    new Continuation.Return[A, Unit](Failure(e)) with Task[A]
  }

  override def handleError[A](fa: Task[A])(f: (Throwable) => Task[A]) = {
    new Continuation.HandleError(fa, f) with Task[A]
  }

  final def map[A, B](fa: Continuation[A, Unit])(f: A => B) = {
    new Continuation.Map(fa, f) with Task[B]
  }
  override def map[A, B](fa: Task[A])(f: A => B) = {
    this.map(fa: Continuation[A, Unit])(f)
  }

  override def ap[A, B](fa: => Task[A])(f: => Task[A => B]): Task[B] = {
    map(Zip(fa, f)) { pair: (A, A => B) =>
      pair._2(pair._1)
    }
  }

  override def bind[A, B](fa: Task[A])(f: (A) => Task[B]) = {
    new Continuation.FlatMap(fa, f) with Task[B]
  }

  override def point[A](a: => A) = {
    new Continuation.Return[A, Unit](Success(a)) with Task[A]
  }

  override def apply2[A, B, C](fa: => Task[A], fb: => Task[B])(f: (A, B) => C): Task[C] = {
    map(Zip(fa, fb)) { pair =>
      f(pair._1, pair._2)
    }
  }
}

// val f: () => Future[Int] = ???
// val task: Task[Int]
