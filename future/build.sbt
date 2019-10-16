organization := "com.thoughtworks.future"

import meta._
examplePackageRef := q"com.thoughtworks"

libraryDependencies += "org.scalaz" %%% "scalaz-core" % "7.2.14"

libraryDependencies += "org.scalatest" %%% "scalatest" % "3.0.8" % Test

libraryDependencies += "com.thoughtworks.tryt" %%% "covariant" % "2.0.3"

addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.3")

enablePlugins(Example)

import meta._
exampleSuperTypes := exampleSuperTypes.value.map {
  case ctor"_root_.org.scalatest.FreeSpec" =>
    ctor"_root_.org.scalatest.AsyncFreeSpec"
  case otherTrait =>
    otherTrait
}

exampleSuperTypes += ctor"_root_.org.scalatest.Inside"
