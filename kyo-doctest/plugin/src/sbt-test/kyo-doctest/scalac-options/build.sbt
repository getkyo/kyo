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
        doctestScalacOptions := Seq("-release", "25", "-Werror", "-Wunused:imports")
    )

// Runner classpath injected by the plugin's scriptedDependencies (no ivy resolution).
root / doctestExtraClasspath := IO.readLines(file(sys.props("kyo.doctest.runnerCpFile"))).map(file)
