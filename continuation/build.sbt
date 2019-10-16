organization := "com.thoughtworks.continuation"

examplePackageRef := q"com.thoughtworks"

libraryDependencies += "org.scalaz" %%% "scalaz-core" % "7.2.14"

addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.3")

enablePlugins(Example)

libraryDependencies += "org.scalatest" %%% "scalatest" % "3.0.8" % Test

import meta._
exampleSuperTypes := exampleSuperTypes.value.map {
  case ctor"_root_.org.scalatest.FreeSpec" =>
    ctor"_root_.org.scalatest.AsyncFreeSpec"
  case otherTrait =>
    otherTrait
}

exampleSuperTypes += ctor"_root_.org.scalatest.Inside"

exampleSuperTypes += ctor"_root_.com.thoughtworks.continuation_test.ContinuationToScalaFuture"

scalacOptions += "-Ypartial-unification"
