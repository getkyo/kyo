// Scripted test: a .kyo source outside the language is rejected by the compiler plugin the sbt plugin loads.

ThisBuild / scalaVersion := sys.props("kyo.scalaVersion")

lazy val root = (project in file("."))
    .enablePlugins(KyoLangPlugin)
    .settings(
        name           := "rejection-test",
        kyoLangVersion := sys.props("plugin.version")
    )
