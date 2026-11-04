// BSP fixture for scala-analyzer: two modules, a module dependency and per-module compiler options.
ThisBuild / scalaVersion := "2.13.18"

lazy val core = project
  .settings(libraryDependencies ++= Seq(
    "org.scalatest" %% "scalatest" % "3.2.19" % Test,
    "org.scalameta" %% "munit" % "1.3.4" % Test,
    "com.lihaoyi" %% "utest" % "0.8.5" % Test,
    "org.specs2" %% "specs2-core" % "4.20.9" % Test,
  ))

lazy val app = project
  .dependsOn(core)
  .settings(scalacOptions += "-Xsource:3")

lazy val core3 = project
  .settings(scalaVersion := "3.3.4")
