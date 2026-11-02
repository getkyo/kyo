// Scripted test: check mode. Formatting on compile is off, kyoFormatCheck fails on an unformatted source, kyoFormat fixes it.

ThisBuild / scalaVersion := sys.props("kyo.scalaVersion")

lazy val root = (project in file("."))
    .enablePlugins(KyoLangPlugin)
    .settings(
        name               := "check-test",
        kyoLangVersion     := sys.props("plugin.version"),
        kyoFormatOnCompile := false
    )
