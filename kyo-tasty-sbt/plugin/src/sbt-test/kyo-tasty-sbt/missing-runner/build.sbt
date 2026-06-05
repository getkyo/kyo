ThisBuild / scalaVersion := "3.8.3"

lazy val root = (project in file("."))
    .enablePlugins(KyoTastyPlugin)
    .settings(
        name := "missing-runner-scripted-test",
        // Explicit empty override: exercises the "tastyRunnerClasspath is empty" error path.
        // The default (bundled runner) is bypassed; the task must fail with a helpful message.
        tastyRunnerClasspath := Seq.empty
    )
