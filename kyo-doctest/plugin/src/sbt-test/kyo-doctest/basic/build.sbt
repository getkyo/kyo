// Scripted test: basic passing fence.
//
// Validates that a project with one passing scala fence in README.md succeeds, and that the run's summary names that README.

ThisBuild / scalaVersion := sys.props("kyo.scalaVersion")

lazy val checkSummaryNamesSource = taskKey[Unit]("Asserts the doctest summary line names the Markdown it validated")

lazy val root = (project in file("."))
    .enablePlugins(KyoDoctestPlugin)
    .settings(
        name := "basic-test",
        doctestSources := Seq(baseDirectory.value / "README.md"),
        doctestScalacOptions := Seq("-release", "17"),
        // Runner classpath injected by the plugin's scriptedDependencies (no ivy resolution).
        doctestExtraClasspath := IO.readLines(file(sys.props("kyo.doctest.runnerCpFile"))).map(file),
        checkSummaryNamesSource := {
            val out   = target.value / "streams" / "_global" / "doctest" / "_global" / "streams" / "out"
            val lines = if (out.exists) IO.readLines(out) else Nil
            if (!lines.exists(_.contains("doctest: README.md: total=1 ")))
                sys.error(s"no summary line names README.md in $out:\n${lines.mkString("\n")}")
            if (lines.exists(_.contains("doctest: total=")))
                sys.error(s"a summary line names no source in $out:\n${lines.mkString("\n")}")
        }
    )
