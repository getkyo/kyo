// Scripted test: doctest formats README blocks with the build's .scalafmt.conf before validating.

ThisBuild / scalaVersion := sys.props("kyo.scalaVersion")

lazy val root = (project in file("."))
    .enablePlugins(KyoDoctestPlugin)
    .settings(
        name := "format-test",
        doctestSources := Seq(baseDirectory.value / "README.md"),
        doctestScalacOptions := Seq("-release", "17")
    )

// Runner classpath injected by the plugin's scriptedDependencies (no ivy resolution).
root / doctestExtraClasspath := IO.readLines(file(sys.props("kyo.doctest.runnerCpFile"))).map(file)
