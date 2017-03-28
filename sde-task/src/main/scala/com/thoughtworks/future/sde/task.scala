package com.thoughtworks.future.sde

import com.thoughtworks.future.Continuation
import com.thoughtworks.future.Continuation.Task
import com.thoughtworks.future.scalaz.TaskInstance
import com.thoughtworks.sde.core.{MonadicFactory, Preprocessor}
import macrocompat.bundle

import scala.annotation.{StaticAnnotation, compileTimeOnly}
import scala.reflect.macros.whitebox
import scalaz.MonadError
import scala.language.experimental.macros
import scala.language.higherKinds

/**
  * @author 杨博 (Yang Bo) &lt;pop.atry@gmail.com&gt;
  */
@compileTimeOnly("enable macro paradise to expand macro annotations")
final class task extends StaticAnnotation {
  def macroTransform(annottees: Any*): Any = macro task.AnnotationBundle.macroTransform
}

object task extends MonadicFactory.WithTypeClass[MonadError[?[_], Throwable], Task] {

  override val typeClass = TaskInstance.scalazTaskInstance

  type MonadThrowable[F[_]] = MonadError[F, Throwable]

  @bundle
  private[future] final class AnnotationBundle(context: whitebox.Context) extends Preprocessor(context) {

    import c.universe._

    def macroTransform(annottees: Tree*): Tree = {
      replaceDefBody(
        annottees, { body =>
          q"""
          {
             import com.thoughtworks.future.sde.task.AwaitOps
              _root_.com.thoughtworks.future.sde.task(${(new ComprehensionTransformer).transform(body)})
          }
          """
        }
      )
    }

  }

  @bundle
  private[future] class AwaitBundle(val c: whitebox.Context) {
    import c.universe._

    def prefixAwait(future: Tree): Tree = {
      val q"$methodName[$a]($future)" = c.macroApplication
      q"""
        _root_.com.thoughtworks.sde.core.MonadicFactory.Instructions.each[
          _root_.com.thoughtworks.future.Continuation.Task,
          $a
        ]($future)
      """
    }

    def postfixAwait: Tree = {
      val q"$ops.$methodName" = c.macroApplication
      val opsName = TermName(c.freshName("ops"))
      q"""
        val $opsName = $ops
        _root_.com.thoughtworks.sde.core.MonadicFactory.Instructions.each[
          _root_.com.thoughtworks.future.Continuation.Task,
          $opsName.A
        ]($opsName.underlying)
      """
    }

  }

  implicit final class AwaitOps[A0](val underlying: Task[A0]) extends AnyVal {
    type A = A0
    def ! : A = macro AwaitBundle.postfixAwait
  }

}
