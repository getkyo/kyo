// Scripted test: second doctest run reports cache hits.

ThisBuild / scalaVersion := "3.8.3"

lazy val root = (project in file("."))
    .enablePlugins(KyoDoctestPlugin)
    .settings(
        name := "cache-test",
        doctestSources := Seq(baseDirectory.value / "README.md"),
        doctestScalacOptions := Seq("-release", "17")
    )

// Runner classpath injected by the plugin's scriptedDependencies (no ivy resolution).
root / doctestExtraClasspath := IO.readLines(file(sys.props("kyo.doctest.runnerCpFile"))).map(file)
