ThisBuild / organization := "com.thoughtworks.future"

lazy val future = crossProject.crossType(CrossType.Pure).dependsOn(`future-MultipleException`)

lazy val futureJVM = future.jvm

lazy val futureJS = future.js

lazy val `future-MultipleException` = crossProject.crossType(CrossType.Pure)

lazy val `future-MultipleExceptionJVM` = `future-MultipleException`.jvm

lazy val `future-MultipleExceptionJS` = `future-MultipleException`.js

enablePlugins(ScalaUnidocPlugin)

ScalaUnidoc / unidoc / unidocProjectFilter := {
  inDependencies(futureJVM)
}

addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.9")

scalacOptions += "-Xexperimental"

publish / skip := true

parallelExecution in Global := false
