// BSP fixture for scala-analyzer: two modules, a module dependency and per-module compiler options.
ThisBuild / scalaVersion := "2.13.18"

lazy val core = project

lazy val app = project
  .dependsOn(core)
  .settings(scalacOptions += "-Xsource:3")

lazy val core3 = project
  .settings(scalaVersion := "3.3.4")
