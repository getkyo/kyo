// Scripted test: compile formats the .kyo sources with the pinned profile before compiling them.

ThisBuild / scalaVersion := sys.props("kyo.scalaVersion")

lazy val root = (project in file("."))
    .enablePlugins(KyoLangPlugin)
    .settings(
        name           := "format-test",
        kyoLangVersion := sys.props("plugin.version")
    )
