lazy val `stateless-future` = project

lazy val `stateless-future-scalaz` = project.dependsOn(`stateless-future`, `stateless-future-scalatest` % Test)

lazy val `stateless-future-sde` = project.dependsOn(`stateless-future-scalaz`, `stateless-future-scalatest` % Test)

lazy val `stateless-future-scalatest` = project.dependsOn(`stateless-future-util`)

lazy val `stateless-future-util` = project.dependsOn(`stateless-future`)
