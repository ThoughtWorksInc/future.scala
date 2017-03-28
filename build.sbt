lazy val Continuation = project

lazy val Future = project.dependsOn(Continuation)

lazy val `scalaz-ContinuationInstance` = project.dependsOn(Continuation)

lazy val `scalaz-TaskInstance` = project.dependsOn(Future, `scalaz-ContinuationInstance`)

lazy val `sde-task` = project.dependsOn(`scalaz-TaskInstance`, `concurrent-Converters` % Test)

lazy val `concurrent-Execution` = project.dependsOn(Continuation)

lazy val `concurrent-Converters` = project.dependsOn(Future, Continuation)

lazy val unidoc = project
  .enablePlugins(TravisUnidocTitle)
  .settings(addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.3"),
            addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full))
