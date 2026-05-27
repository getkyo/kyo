ThisBuild / scalaVersion := "3.8.3"

lazy val root = (project in file("."))
    .enablePlugins(KyoTastyPlugin)
    .settings(
        name := "missing-runner-scripted-test",
        // Override to a non-existent path so the plugin fails with a useful message.
        tastyRunnerClasspath := Seq(file("/nonexistent/kyo-tasty-sbt-runner-assembly.jar"))
    )
