import meta._
examplePackageRef := q"com.thoughtworks"

libraryDependencies += "org.scalaz" %%% "scalaz-core" % "7.2.14"

libraryDependencies += "org.scalatest" %%% "scalatest" % "3.1.4" % Test

libraryDependencies += "com.thoughtworks.tryt" %%% "covariant" % "2.1.1"

libraryDependencies += "com.thoughtworks.continuation" %%% "continuation" % "2.0.0"

addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.10.3")

enablePlugins(Example)

import meta._
exampleSuperTypes := exampleSuperTypes.value.map {
  case ctor"_root_.org.scalatest.FreeSpec" =>
    ctor"_root_.org.scalatest.AsyncFreeSpec"
  case otherTrait =>
    otherTrait
}

exampleSuperTypes += ctor"_root_.org.scalatest.Inside"
