import meta._
examplePackageRef := q"com.thoughtworks"

name := "MultipleException"

libraryDependencies += "org.scalaz" %%% "scalaz-core" % "7.2.31"

enablePlugins(Example)

libraryDependencies += "org.scalatest" %%% "scalatest" % "3.1.0" % Test
