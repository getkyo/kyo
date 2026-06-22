// Scripted test: doctestSources set to a custom file (not README.md).
//
// GUIDE.md has a passing fence. README.md has a failing fence.
// By pointing doctestSources only at GUIDE.md, doctest should succeed.

ThisBuild / scalaVersion := "3.8.3"

lazy val root = (project in file("."))
    .enablePlugins(KyoDoctestPlugin)
    .settings(
        name := "sources-test",
        // Only validate GUIDE.md; ignore README.md which has a failing fence.
        doctestSources := Seq(baseDirectory.value / "GUIDE.md"),
        doctestScalacOptions := Seq("-release", "25")
    )

// Runner classpath injected by the plugin's scriptedDependencies (no ivy resolution).
root / doctestExtraClasspath := IO.readLines(file(sys.props("kyo.doctest.runnerCpFile"))).map(file)
