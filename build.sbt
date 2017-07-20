import scala.util.matching.Regex.{Groups, Match}

crossScalaVersions := Seq("2.10.6", "2.11.11", "2.12.2")

lazy val continuation = project

lazy val future = project.dependsOn(continuation)

lazy val unidoc = project
  .enablePlugins(StandaloneUnidoc, TravisUnidocTitle)
  .settings(
    UnidocKeys.unidocProjectFilter in ScalaUnidoc in UnidocKeys.unidoc := {
      inDependencies(future)
    },
    addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.3"),
    scalacOptions += "-Xexperimental"
  )

organization in ThisBuild := "com.thoughtworks.future"

publishArtifact := false
