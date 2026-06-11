// Scripted test: the plugin pulls Test/fullClasspath so fences can use test deps.
//
// We add scalatest as a Test dependency; the fence imports org.scalatest.Assertions.
// If the classpath is wired correctly the fence compiles.

ThisBuild / scalaVersion := "3.8.3"

lazy val root = (project in file("."))
    .enablePlugins(KyoDoctestPlugin)
    .settings(
        name := "classpath-test",
        libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.19" % Test,
        doctestSources := Seq(baseDirectory.value / "README.md"),
        doctestScalacOptions := Seq("-release", "17")
    )

// Runner classpath injected by the plugin's scriptedDependencies (no ivy resolution).
root / doctestExtraClasspath := IO.readLines(file(sys.props("kyo.doctest.runnerCpFile"))).map(file)
