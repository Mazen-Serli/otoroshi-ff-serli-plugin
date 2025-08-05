import Dependencies._

ThisBuild / scalaVersion     := "2.12.13"
ThisBuild / version          := "1.0.0-dev"
ThisBuild / organization     := "com.serli"
ThisBuild / organizationName := "Serli SAS"

lazy val root = (project in file("."))
  .settings(
    name := "otoroshi-ff-serli-plugin",
    resolvers += "jitpack" at "https://jitpack.io",
    libraryDependencies ++= Seq(
      "fr.maif" %% "otoroshi" % "17.3.0" % "provided",
      munit % Test
    )
  )
