// The bench harness: an isolated sbt project on published kyo artifacts. It never depends on the
// kyo build it measures, so it compiles and runs while that tree is red or mid-edit, and its sbt
// server is its own (keyed on this directory), never the one the kernel is built through.
val kyoVersion = "1.0.0-RC6"

lazy val root = (project in file("."))
    .settings(
        name         := "bench-harness",
        scalaVersion := "3.8.4",
        libraryDependencies ++= Seq(
            "io.getkyo" %% "kyo-core"        % kyoVersion,
            "io.getkyo" %% "kyo-schema-json" % kyoVersion,
            "io.getkyo" %% "kyo-case-app"    % kyoVersion,
            "io.getkyo" %% "kyo-test-api"    % kyoVersion % Test,
            "io.getkyo" %% "kyo-test-runner" % kyoVersion % Test
        ),
        testFrameworks += new TestFramework("kyo.test.runner.SbtFramework"),
        // the tests read the qa-artifacts captures relative to this directory, as the mains did
        Test / fork := false,
        // one sbt server here shares nothing with the kernel build's, so no run overlaps a measurement
        Compile / run / fork := true,
        Compile / run / connectInput := true
    )
