organization := "com.qifun"

name := "stateless-future-util"

version := "0.6.0-SNAPSHOT"

libraryDependencies += "com.qifun" %% "stateless-future" % "0.3.2"

libraryDependencies += "com.dongxiguo" %% "fastring" % "0.3.1"

libraryDependencies += "com.dongxiguo" %% "zero-log" % "0.4.1"

libraryDependencies += "com.novocode" % "junit-interface" % "0.10" % "test"

// Disable optimizer due to https://issues.scala-lang.org/browse/SI-8906
// scalacOptions += "-optimize"

scalacOptions += "-unchecked"

scalacOptions += "-Xlint"

scalacOptions += "-feature"

scalacOptions += "-Ywarn-value-discard"

scalacOptions <++= (scalaVersion) map { sv =>
  if (sv.startsWith("2.10.")) {
    Seq("-deprecation") // Fully compatible with 2.10.x 
  } else {
    Seq() // May use deprecated API in 2.11.x
  }
}

crossScalaVersions := Seq("2.10.4", "2.11.2")

description := "Utilities for working with Stateless Future."

startYear := Some(2014)

