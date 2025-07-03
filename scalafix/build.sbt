lazy val V = _root_.scalafix.sbt.BuildInfo

lazy val rulesVersion         = V.scala213
lazy val scala3Version        = "3.7.0"
lazy val scalaFixScalaVersion = V.scala213

inThisBuild(
    List(
        semanticdbEnabled := true,
        semanticdbVersion := scalafixSemanticdb.revision
    )
)

lazy val `kyo-rules` = (project in file("."))
    .aggregate(rules, input, output, tests)
    .settings(publish / skip := true)

lazy val rules = (project in file("rules"))
    .settings(
        moduleName                             := "scalafix",
        libraryDependencies += "ch.epfl.scala" %% "scalafix-core" % V.scalafixVersion,
        scalaVersion                           := scalaFixScalaVersion
    )

lazy val input = (project in file("input"))
    .settings(
        publish / skip := true,
        scalaVersion   := scala3Version
    )

lazy val output = (project in file("output"))
    .settings(
        publish / skip := true,
        scalaVersion   := scala3Version
    )

lazy val tests = (project in file("tests"))
    .settings(
        scalaVersion                           := scalaFixScalaVersion,
        publish / skip                         := true,
        scalafixTestkitOutputSourceDirectories := (output / Compile / unmanagedSourceDirectories).value,
        scalafixTestkitInputSourceDirectories  := (input / Compile / unmanagedSourceDirectories).value,
        scalafixTestkitInputClasspath          := (input / Compile / fullClasspath).value,
        scalafixTestkitInputScalacOptions      := (input / Compile / scalacOptions).value,
        scalafixTestkitInputScalaVersion       := (input / Compile / scalaVersion).value
    )
    .dependsOn(rules)
    .enablePlugins(ScalafixTestkitPlugin)
