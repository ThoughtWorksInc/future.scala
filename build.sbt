lazy val continuation = crossProject.crossType(CrossType.Pure)

lazy val continuationJVM = continuation.jvm.addSbtFiles(file("../build.sbt.shared"))

lazy val continuationJS = continuation.js.addSbtFiles(file("../build.sbt.shared"))

lazy val future = crossProject.crossType(CrossType.Pure).dependsOn(continuation, `future-MultipleException`)

lazy val futureJVM = future.jvm.addSbtFiles(file("../build.sbt.shared"))

lazy val futureJS = future.js.addSbtFiles(file("../build.sbt.shared"))

lazy val `future-MultipleException` = crossProject.crossType(CrossType.Pure)

lazy val `future-MultipleExceptionJVM` = `future-MultipleException`.jvm.addSbtFiles(file("../build.sbt.shared"))

lazy val `future-MultipleExceptionJS` = `future-MultipleException`.js.addSbtFiles(file("../build.sbt.shared"))

enablePlugins(ScalaUnidocPlugin)

ScalaUnidoc / unidoc / unidocProjectFilter := {
  inDependencies(futureJVM)
}

addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.9")

scalacOptions += "-Xexperimental"

publish / skip := true
