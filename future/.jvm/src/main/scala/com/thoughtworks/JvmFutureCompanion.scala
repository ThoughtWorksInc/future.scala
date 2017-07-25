package com.thoughtworks

import java.nio.channels.CompletionHandler

import com.thoughtworks.future.Future

import scala.util.{Failure, Success, Try}
import scalaz.Free.Trampoline
import scalaz.Trampoline
private[thoughtworks] object JvmFutureCompanion {

  private final class CompletionHandlerProxy[A] extends CompletionHandler[A, Try[A] => Trampoline[Unit]] {
    override def failed(exc: Throwable, attachment: (Try[A]) => Trampoline[Unit]): Unit = {
      attachment(Failure(exc)).run
    }

    override def completed(result: A, attachment: (Try[A]) => Trampoline[Unit]): Unit = {
      attachment(Success(result)).run
    }
  }

  // Cache `CompletionHandlerProxy[A]` to avoid frequent allocation
  @inline
  private val completionHandlerProxyCache = new CompletionHandlerProxy[Any]

  @inline
  private def CompletionHandlerProxy[A]: CompletionHandlerProxy[A] = {
    completionHandlerProxyCache.asInstanceOf[CompletionHandlerProxy[A]]
  }

}

/**
  * @author 杨博 (Yang Bo)
  */
private[thoughtworks] trait JvmFutureCompanion { this: Future.type =>
  import JvmFutureCompanion._
  private[JvmFutureCompanion] type NioAttachment[A] = Try[A] => Trampoline[Unit]

  /** Returns a [[Future]] from a NIO asynchronous operation.
    *
    * @example Given an NIO asynchronous channel,
    *
    *          {{{
    *          import com.thoughtworks.future._
    *          import scalaz.syntax.all._
    *          import java.nio._, file._, channels._
    *          val channel = AsynchronousFileChannel.open(Files.createTempFile(null, null), StandardOpenOption.WRITE)
    *          }}}
    *
    *          when writing bytes into the channel,
    *
    *          {{{
    *          val bytes = ByteBuffer.wrap("Hello, World!".getBytes(scala.io.Codec.UTF8.charSet))
    *          val position = 0L
    *          val writeFuture: Future[java.lang.Integer] = Future.nio[java.lang.Integer](channel.write(bytes, position, _, _))
    *          }}}
    *
    *          then the file size should be the number of bytes written.
    *
    *          {{{
    *          writeFuture.map { numberOfBytesWritten =>
    *            try {
    *              numberOfBytesWritten.toInt should be > 0
    *              channel.size should be(numberOfBytesWritten.toLong)
    *            } finally {
    *              channel.close()
    *            }
    *          }
    *          }}}
    */
  def nio[A](start: (NioAttachment[A], CompletionHandler[A, NioAttachment[A]]) => Unit): Future[A] = {
    safeAsync { continue =>
      Trampoline.delay {
        start(continue, CompletionHandlerProxy[A])
      }
    }
  }
}
