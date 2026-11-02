// Scripted test: a .kyo source under src/main/scala compiles through the plugin and ordinary Scala calls it.

ThisBuild / scalaVersion := sys.props("kyo.scalaVersion")

lazy val root = (project in file("."))
    .enablePlugins(KyoLangPlugin)
    .settings(
        name           := "compile-test",
        kyoLangVersion := sys.props("plugin.version")
    )
