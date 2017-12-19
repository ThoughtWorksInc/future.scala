crossScalaVersions := Seq("2.11.11", "2.12.2")

lazy val continuation = crossProject.crossType(CrossType.Pure)

lazy val continuationJVM = continuation.jvm.addSbtFiles(file("../build.sbt.shared"))

lazy val continuationJS = continuation.js.addSbtFiles(file("../build.sbt.shared"))

lazy val future = crossProject.crossType(CrossType.Pure).dependsOn(continuation)

lazy val futureJVM =
  future.jvm.dependsOn(ProjectRef(file("tryt.scala"), "covariantJVM")).addSbtFiles(file("../build.sbt.shared"))

lazy val futureJS =
  future.js
    .dependsOn(ProjectRef(file("tryt.scala"), "covariantJS"))
    .addSbtFiles(file("../build.sbt.shared"))

lazy val unidoc = project
  .enablePlugins(StandaloneUnidoc, TravisUnidocTitle)
  .settings(
    UnidocKeys.unidocProjectFilter in ScalaUnidoc in UnidocKeys.unidoc := {
      inDependencies(futureJVM)
    },
    addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.3"),
    scalacOptions += "-Xexperimental"
  )

publishArtifact := false
