// Scripted test: doctestScalacOptions += "-Werror" turns a warning into a failure.
//
// The README fence has an unused import. Without -Werror it compiles clean.
// With -Werror the unused import becomes an error and doctest fails.

ThisBuild / scalaVersion := "3.8.3"

lazy val root = (project in file("."))
    .enablePlugins(KyoDoctestPlugin)
    .settings(
        name := "scalac-options-test",
        doctestSources := Seq(baseDirectory.value / "README.md"),
        // -Werror promotes warnings to errors; -Wunused:imports flags the unused import.
        doctestScalacOptions := Seq("-release", "17", "-Werror", "-Wunused:imports")
    )
