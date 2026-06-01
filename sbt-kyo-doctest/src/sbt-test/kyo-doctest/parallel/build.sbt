// Scripted test: doctestParallel := 1 produces the same exit code as the default.
//
// Two passing fences run serially (parallel=1). Both pass, doctest exits 0.

ThisBuild / scalaVersion := "3.8.3"

lazy val root = (project in file("."))
    .enablePlugins(KyoDoctestPlugin)
    .settings(
        name := "parallel-test",
        doctestSources := Seq(baseDirectory.value / "README.md"),
        doctestScalacOptions := Seq("-release", "17"),
        doctestParallel := 1
    )
