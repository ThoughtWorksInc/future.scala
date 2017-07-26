# future.scala <a href="http://thoughtworks.com/"><img align="right" src="https://www.thoughtworks.com/imgs/tw-logo.png" alt="ThoughtWorks" height="15"/></a>

[![Build Status](https://travis-ci.org/ThoughtWorksInc/future.scala.svg?branch=1.0.x)](https://travis-ci.org/ThoughtWorksInc/future.scala)
[![Latest version](https://index.scala-lang.org/thoughtworksinc/future.scala/future/latest.svg)](https://index.scala-lang.org/thoughtworksinc/future.scala/future)
[![Scaladoc](https://javadoc.io/badge/com.thoughtworks.future/future_2.11.svg?label=scaladoc)](https://javadoc.io/page/com.thoughtworks.future/future_2.11/latest/com/thoughtworks/future$$Future.html)

**future.scala** is the spiritual successor to [Stateless Future](https://github.com/qifun/stateless-future) for stack-safe asynchronous programming in pure functional flavor. We dropped the Stateless Future's built-in `async`/`await` support, in favor of more general `monadic`/`each` syntax provided by [ThoughtWorks Each](https://github.com/ThoughtWorksInc/each).

future.scala provide an API similar to `scala.concurrent.Future` or `scalaz.concurrent.Task`, except future.scala never throws a `StackOverflowError`.

## Design

future.scala inherits many design decision from Stateless Future.

<table>
<thead>
<tr>
<td></td>
<th><code>com.thoughtworks.future.Future[+A]</code></th>
<th><code>scalaz.concurrent.Task[A]</code></th>
<th><code>scala.concurrent.Future[+A]</code></th>
</tr>
<tr>
<th>Stateless</th>
<td>Yes</td>
<td>Yes</td>
<td>No</td>
</tr>
<tr>
<th>Threading-free</th>
<td>Yes</td>
<td>Yes</td>
<td>No</td>
</tr>
<tr>
<th>Exception Handling</th>
<td>Yes</td>
<td>Yes</td>
<td>Yes</td>
</tr>
<tr>
<th>Support covariance</th>
<td>Yes</td>
<td>No</td>
<td>Yes</td>
</tr>
<tr>
<th>Stack-safe</th>
<td>Yes</td>
<td>No</td>
<td>No</td>
</tr>
</thead>
</table>

### Statelessness

future.scala are pure functional, thus they will never store result values or exceptions. Instead, future.scala evaluate lazily, and they do the same job every time you invoke `onComplete`.

Also, there is no `isComplete` method in future.scala. As a result, the users of future.scala are forced not to share futures between threads, not to check the states in futures. They have to care about control flows instead of threads, and build the control flows by defining future.scala.


### Threading-free Model

There are too many threading models and implimentations in the Java/Scala world, `java.util.concurrent.Executor`, `scala.concurrent.ExecutionContext`, `javax.swing.SwingUtilities.invokeLater`, `java.util.Timer`, ... It is very hard to communicate between threading models. When a developer is working with multiple threading models, he must very carefully pass messages between threading models, or he have to maintain bulks of `synchronized` methods to properly deal with the shared variables between threads.

Why does he need multiple threading models? Because the libraries that he uses depend on different threading modes. For example, you must update Swing components in the Swing's UI thread, you must specify `java.util.concurrent.ExecutionService`s for `java.nio.channels.CompletionHandler`, and, you must specify `scala.concurrent.ExecutionContext`s for `scala.concurrent.Future` and `scala.async.Async`. Oops!

Think about somebody who uses Swing to develop a text editor software. He wants to create a state machine to update UI. He have heard the cool `scala.async`, then he uses the cool "A-Normal Form" expression in `async` to build the state machine that updates UI, and he types `import scala.concurrent.ExecutionContext.Implicits._` to suppress the compiler errors. Everything looks pretty, except the software always crashes.

Fortunately, future.scala depends on none of these threading model, and cooperates with all of these threading models. If the poor guy tries Stateless Future, replacing `async { }` to `monadic[Future] { }`, deleting the `import scala.concurrent.ExecutionContext.Implicits._`, he will find that everything looks pretty like before, and does not crash any more. That's why threading-free model is important.

### Exception Handling

There were two `Future` implementations in Scala standard library, `scala.actors.Future` and `scala.concurrent.Future`. `scala.actors.Future`s are not designed to handling exceptions, since exceptions are always handled by actors. There is no way to handle a particular exception in a particular subrange of an actor.

Unlike `scala.actors.Future`s, `scala.concurrent.Future`s are designed to handle exceptions. Unfortunately, `scala.concurrent.Future`s provide too many mechanisms to handle an exception. For example:

    import scala.concurrent.Await
    import scala.concurrent.ExecutionContext
    import scala.concurrent.duration.Duration
    import scala.util.control.Exception.Catcher
    import scala.concurrent.forkjoin.ForkJoinPool
    val threadPool = new ForkJoinPool()
    val catcher1: Catcher[Unit] = { case e: Exception => println("catcher1") }
    val catcher2: Catcher[Unit] = {
      case e: java.io.IOException => println("catcher2")
      case other: Exception => throw new RuntimeException(other)
    }
    val catcher3: Catcher[Unit] = {
      case e: java.io.IOException => println("catcher3")
      case other: Exception => throw new RuntimeException(other)
    }
    val catcher4: Catcher[Unit] = { case e: Exception => println("catcher4") }
    val catcher5: Catcher[Unit] = { case e: Exception => println("catcher5") }
    val catcher6: Catcher[Unit] = { case e: Exception => println("catcher6") }
    val catcher7: Catcher[Unit] = { case e: Exception => println("catcher7") }
    def future1 = scala.concurrent.future { 1 }(ExecutionContext.fromExecutor(threadPool, catcher1))
    def future2 = scala.concurrent.Future.failed(new Exception)
    val composedFuture = future1.flatMap { _ => future2 }(ExecutionContext.fromExecutor(threadPool, catcher2))
    composedFuture.onFailure(catcher3)(ExecutionContext.fromExecutor(threadPool, catcher4))
    composedFuture.onFailure(catcher5)(ExecutionContext.fromExecutor(threadPool, catcher6))
    try { Await.result(composedFuture, Duration.Inf) } catch { case e if catcher7.isDefinedAt(e) => catcher7(e) }

Is any sane developer able to tell which catchers will receive the exceptions?

There are too many concepts about exceptions when you work with `scala.concurrent.Future`. You have to remember the different exception handling strategies between `flatMap`, `recover`, `recoverWith` and `onFailure`, and the difference between `scala.concurrent.Future.failed(new Exception)` and `scala.concurrent.future { throw new Exception }`.

`scala.async` does not make things better, because `scala.async` will [produce a compiler error](https://github.com/scala/async/blob/master/src/test/scala/scala/async/neg/NakedAwait.scala#L104) for every `await` in a `try` statement.

Fortunately, you can get rid of all those concepts if you switch to future.scala. There is neither `executor` implicit parameter in `flatMap` or `map` in future.scala, nor `onFailure` nor `recover` method at all. You just simply `try`, and things get done. See [the examples](https://github.com/ThoughtWorksInc/each/blob/3.3.x/each/src/test/scala/com/thoughtworks/each/MonadicErrorTest.scala) to learn that.

### Tail Call Optimization

Tail call optimization is an important feature for pure functional programming. Without tail call optimization, many recursive algorithm will fail at run-time, and you will get the well-known `StackOverflowError`.


future.scala project is internally based on `scalaz.Trampoline`, and automatically performs tail call optimization in the magic `Future` blocks, without any additional special syntax.

See [this example](https://github.com/ThoughtWorksInc/RAII.scala/blob/d6390ba439356d3f50891f4b501547bb2748cb6a/asynchronous/src/test/scala/com/thoughtworks/raii/asynchronousSpec.scala#L59). The example creates 30000 stack levels recursively. And it just works, without any `StackOverflowError` or `OutOfMemoryError`. Note that if you port this example for `scala.async` it will throw an `OutOfMemoryError` or a `TimeoutException`.

## Related projects

* [Scalaz](http://scalaz.org/) provides type classes and underlying data structures for this project.
* [ThoughtWorks Each](https://github.com/ThoughtWorksInc/each) provides `async`/`await`-like syntax for this project.
* [tryt.scala](https://github.com/ThoughtWorksInc/TryT.scala) provides exception handling monad transformers for this project.
* [RAII.scala](https://github.com/ThoughtWorksInc/RAII.scala) uses this project for asynchronous automatic resource management。
* [DeepLearning.scala](http://deeplearning.thoughtworks.school/) uses this project for aysnchronous executed neural networks.

## Links

* [API Documentation](https://javadoc.io/page/com.thoughtworks.future/future_2.11/latest/com/thoughtworks/future$$Future.html)
