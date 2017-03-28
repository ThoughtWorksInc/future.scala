//lazy val `stateless-future` = project
//
//lazy val `stateless-future-scalaz` = project.dependsOn(`stateless-future`, `stateless-future-scalatest` % Test)
//
//lazy val `stateless-future-sde` = project.dependsOn(`stateless-future-scalaz`, `stateless-future-scalatest` % Test)
//
//lazy val `stateless-future-scalatest` = project.dependsOn(`stateless-future-util`)
//
//lazy val `stateless-future-util` = project.dependsOn(`stateless-future`)

lazy val Continuation = project

lazy val Future = project.dependsOn(Continuation)

lazy val Task = project.dependsOn(Continuation)

lazy val `scalaz-ContinuationInstance` = project.dependsOn(Continuation)

lazy val `scalaz-TaskInstance` = project.dependsOn(Task, Future)

lazy val `scalatest-FutureFreeSpec` = project.dependsOn(Future, `concurrent-Converters`)

lazy val `sde-task` = project.dependsOn(`scalaz-TaskInstance`)

lazy val `concurrent-Execution` = project.dependsOn(Task)

lazy val `concurrent-Converters` = project.dependsOn(Future, Continuation)

lazy val unidoc = project
  .enablePlugins(TravisUnidocTitle)
  .settings(addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.3"),
            addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full))
