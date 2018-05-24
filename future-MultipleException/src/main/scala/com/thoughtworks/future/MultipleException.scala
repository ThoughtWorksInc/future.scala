package com.thoughtworks.future

import java.io.{PrintStream, PrintWriter}

import scalaz.Semigroup

object MultipleException {

  implicit object multipleExceptionThrowableSemigroup extends Semigroup[Throwable] {
    override def append(f1: Throwable, f2: => Throwable): Throwable =
      f1 match {
        case MultipleException(exceptionSet1) =>
          f2 match {
            case MultipleException(exceptionSet2) => MultipleException(exceptionSet1 ++ exceptionSet2)
            case e: Throwable                     => MultipleException(exceptionSet1 + e)
          }
        case _: Throwable =>
          f2 match {
            case MultipleException(exceptionSet2) => MultipleException(exceptionSet2 + f1)
            case `f1`                             => f1
            case e: Throwable                     => MultipleException(Set(f1, e))
          }
      }
  }
}

final case class MultipleException(throwableSet: Set[Throwable]) extends RuntimeException("Multiple exceptions found") {
  override def toString: String = throwableSet.mkString("\n")

  override def printStackTrace(): Unit = {
    for (throwable <- throwableSet) {
      throwable.printStackTrace()
    }
  }

  override def printStackTrace(s: PrintStream): Unit = {
    for (throwable <- throwableSet) {
      throwable.printStackTrace(s)
    }
  }

  override def printStackTrace(s: PrintWriter): Unit = {
    for (throwable <- throwableSet) {
      throwable.printStackTrace(s)
    }
  }

  override def getStackTrace: Array[StackTraceElement] = synchronized {
    super.getStackTrace match {
      case null =>
        setStackTrace(throwableSet.flatMap(_.getStackTrace)(collection.breakOut))
        super.getStackTrace
      case stackTrace =>
        stackTrace
    }
  }

  override def fillInStackTrace(): this.type = {
    this
  }

}
