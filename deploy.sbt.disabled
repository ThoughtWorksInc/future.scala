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

lazy val secret = project.settings(publishArtifact := false).in {
  val secretDirectory = file(sourcecode.File()).getParentFile / "secret"
  org.apache.commons.io.FileUtils.deleteDirectory(secretDirectory)
  org.eclipse.jgit.api.Git
    .cloneRepository()
    .setURI("https://github.com/ThoughtWorksInc/tw-data-china-continuous-delivery-password.git")
    .setDirectory(secretDirectory)
    .setCredentialsProvider(
      new org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider(sys.env("GITHUB_PERSONAL_ACCESS_TOKEN"), "")
    )
    .call()
    .close()
  secretDirectory
}

scalacOptions in ThisBuild ++= {
  import scala.math.Ordering.Implicits._
  if (VersionNumber(scalaVersion.value).numbers < Seq(2L, 12L)) {
    Seq("-Ybackend:GenBCode")
  } else {
    Nil
  }
}
