name := "absmartly-sdk"

organization := "com.absmartly"

version := "0.1.0"

// Cross-build for Scala 2.13 and Scala 3
scalaVersion := "2.13.12"
crossScalaVersions := Seq("2.13.12", "3.3.1")

// Compiler options
scalacOptions ++= Seq(
  "-encoding", "utf8",
  "-deprecation",
  "-feature",
  "-unchecked"
) ++ (CrossVersion.partialVersion(scalaVersion.value) match {
  case Some((2, 13)) => Seq(
    "-Xlint",
    "-Ywarn-dead-code",
    "-Ywarn-numeric-widen",
    "-Ywarn-value-discard"
  )
  case Some((3, _)) => Seq(
    "-Xfatal-warnings"
  )
  case _ => Seq.empty
})

// Dependencies
libraryDependencies ++= Seq(
  // HTTP client
  "com.softwaremill.sttp.client3" %% "core" % "3.9.2",
  "com.softwaremill.sttp.client3" %% "circe" % "3.9.2",

  // JSON
  "io.circe" %% "circe-core" % "0.14.6",
  "io.circe" %% "circe-generic" % "0.14.6",
  "io.circe" %% "circe-parser" % "0.14.6",

  // Hashing - use battle-tested libraries
  "commons-codec" % "commons-codec" % "1.16.0", // MD5
  "com.google.guava" % "guava" % "33.0.0-jre", // Murmur3_32

  // Testing
  "org.scalatest" %% "scalatest" % "3.2.17" % Test
)

// Resolve version conflicts
dependencyOverrides ++= Seq(
  "org.scala-lang.modules" %% "scala-parser-combinators" % "2.3.0"
)

// Publishing settings
licenses := Seq("MIT" -> url("https://opensource.org/licenses/MIT"))
homepage := Some(url("https://github.com/absmartly/scala-sdk"))
developers := List(
  Developer(
    "absmartly",
    "ABsmartly",
    "sdk@absmartly.com",
    url("https://www.absmartly.com")
  )
)
scmInfo := Some(
  ScmInfo(
    url("https://github.com/absmartly/scala-sdk"),
    "scm:git@github.com:absmartly/scala-sdk.git"
  )
)
