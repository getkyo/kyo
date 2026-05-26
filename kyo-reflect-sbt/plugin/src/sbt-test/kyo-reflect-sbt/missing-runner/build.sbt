ThisBuild / scalaVersion := "3.8.3"

lazy val root = (project in file("."))
    .enablePlugins(KyoReflectPlugin)
    .settings(
        name := "missing-runner-scripted-test",
        // Override to a non-existent path so the plugin fails with a useful message.
        reflectRunnerClasspath := Seq(file("/nonexistent/kyo-reflect-sbt-runner-assembly.jar"))
    )
