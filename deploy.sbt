enablePlugins(Travis)

enablePlugins(Optimization)

releaseProcess := {
  import ReleaseTransformations._
  import xerial.sbt.Sonatype.SonatypeCommand.sonatypeReleaseAll
  releaseProcess.value.patch(
    releaseProcess.value.indexOf(pushChanges),
    Seq[ReleaseStep](
      releaseStepCommand(sonatypeReleaseAll, " com.thoughtworks.continuation"),
      releaseStepCommand(sonatypeReleaseAll, " com.thoughtworks.future")
    ),
    0
  )
}

lazy val secret = project settings(publishArtifact := false) configure { secret =>
  sys.env.get("GITHUB_PERSONAL_ACCESS_TOKEN") match {
    case Some(pat) =>
      import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
      secret.addSbtFilesFromGit(
        "https://github.com/ThoughtWorksInc/tw-data-china-continuous-delivery-password.git",
        new UsernamePasswordCredentialsProvider(pat, ""),
        file("secret.sbt"))
    case None =>
      secret
  }
}

scalacOptions in ThisBuild ++= {
  import scala.math.Ordering.Implicits._
  if (VersionNumber(scalaVersion.value).numbers < Seq(2L, 12L)) {
    Seq("-Ybackend:GenBCode")
  } else {
    Nil
  }
}
