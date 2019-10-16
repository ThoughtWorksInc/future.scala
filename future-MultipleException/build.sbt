import meta._
examplePackageRef := q"com.thoughtworks"

name := "MultipleException"

libraryDependencies += "org.scalaz" %%% "scalaz-core" % "7.2.14"

enablePlugins(Example)

libraryDependencies += "org.scalatest" %%% "scalatest" % "3.0.8" % Test
