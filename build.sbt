crossScalaVersions := Seq("2.10.6", "2.11.11", "2.12.2")

lazy val continuation = crossProject.crossType(CrossType.Pure)

lazy val continuationJVM = continuation.jvm.addSbtFiles(file("../build.sbt.shared"))

lazy val continuationJS = continuation.js.addSbtFiles(file("../build.sbt.shared"))

lazy val future = crossProject.crossType(CrossType.Pure).dependsOn(continuation)

lazy val futureJVM = future.jvm.addSbtFiles(file("../build.sbt.shared"))

lazy val futureJS = future.js.addSbtFiles(file("../build.sbt.shared"))

lazy val unidoc = project
  .enablePlugins(StandaloneUnidoc, TravisUnidocTitle)
  .settings(
    UnidocKeys.unidocProjectFilter in ScalaUnidoc in UnidocKeys.unidoc := {
      inDependencies(futureJVM)
    },
    addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.3"),
    scalacOptions += "-Xexperimental"
  )

organization in ThisBuild := "com.thoughtworks.future"

publishArtifact := false
