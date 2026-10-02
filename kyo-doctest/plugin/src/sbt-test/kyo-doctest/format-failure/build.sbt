// Scripted test: a .scalafmt.conf naming a scalafmt that cannot be loaded fails doctest.

ThisBuild / scalaVersion := sys.props("kyo.scalaVersion")

lazy val root = (project in file("."))
    .enablePlugins(KyoDoctestPlugin)
    .settings(
        name := "format-failure-test",
        doctestSources := Seq(baseDirectory.value / "README.md"),
        doctestScalacOptions := Seq("-release", "17")
    )

// Runner classpath injected by the plugin's scriptedDependencies (no ivy resolution).
root / doctestExtraClasspath := IO.readLines(file(sys.props("kyo.doctest.runnerCpFile"))).map(file)
