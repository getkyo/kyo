// Scripted test: basic passing fence.
//
// Validates that a project with one passing scala fence in README.md succeeds.

ThisBuild / scalaVersion := "3.8.3"

lazy val root = (project in file("."))
    .enablePlugins(KyoDoctestPlugin)
    .settings(
        name := "basic-test",
        doctestSources := Seq(baseDirectory.value / "README.md"),
        doctestScalacOptions := Seq("-release", "17")
    )
