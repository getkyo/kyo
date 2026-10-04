// Scripted test: a failing fence's compiler error reaches the doctest task's own log, which an sbt client displays.

ThisBuild / scalaVersion := sys.props("kyo.scalaVersion")

lazy val checkDoctestLog = taskKey[Unit]("Asserts the doctest task's log holds the failing fence's compiler error")

lazy val root = (project in file("."))
    .enablePlugins(KyoDoctestPlugin)
    .settings(
        name := "failure-output-test",
        doctestSources := Seq(baseDirectory.value / "README.md"),
        doctestScalacOptions := Seq("-release", "17"),
        checkDoctestLog := {
            val out = target.value / "streams" / "_global" / "doctest" / "_global" / "streams" / "out"
            val log = if (out.exists) IO.read(out) else ""
            if (!log.contains("Required: String"))
                sys.error(s"the doctest task's log at $out does not hold the compiler error; it holds:\n$log")
            // Each relayed line names the Markdown it came from, so a concurrent project's lines cannot be mistaken for it.
            if (!log.linesIterator.exists(l => l.contains("Required: String") && l.contains("doctest: README.md: ")))
                sys.error(s"the compiler error line in $out does not name README.md; the log holds:\n$log")
        }
    )

// Runner classpath injected by the plugin's scriptedDependencies (no ivy resolution).
root / doctestExtraClasspath := IO.readLines(file(sys.props("kyo.doctest.runnerCpFile"))).map(file)
