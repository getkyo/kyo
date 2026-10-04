// Scripted test: formatting.
//
// Validates that doctest rewrites a fence into the style of the build's .scalafmt.conf before validating it.

ThisBuild / scalaVersion := sys.props("kyo.scalaVersion")

lazy val root = (project in file("."))
    .enablePlugins(KyoDoctestPlugin)
    .settings(
        name := "format-test",
        doctestSources := Seq(baseDirectory.value / "README.md"),
        doctestScalacOptions := Seq("-release", "17"),
        // Runner classpath injected by the plugin's scriptedDependencies (no ivy resolution).
        doctestExtraClasspath := IO.readLines(file(sys.props("kyo.doctest.runnerCpFile"))).map(file)
    )
