ThisBuild / scalaVersion := "3.8.3"

val checkRunnerExtracted = taskKey[Unit]("assert runner.jar was extracted from the bundled plugin resource")
val checkSnapshot        = taskKey[Unit]("assert snapshot file is non-empty at the managed resource path")

lazy val root = (project in file("."))
    .enablePlugins(KyoTastyPlugin)
    .settings(
        organization := "com.example",
        version      := "0.1.0-TEST",
        name         := "bundled-runner-scripted-test",
        // No tastyRunnerClasspath override: exercises the bundled-runner extraction path.
        checkRunnerExtracted := {
            val extractedJar =
                (LocalRootProject / target).value / "kyo-tasty-runner-extract" / "runner.jar"
            if (!extractedJar.exists())
                sys.error(s"Bundled runner.jar not extracted at ${extractedJar.getAbsolutePath}")
            if (extractedJar.length() == 0L)
                sys.error(s"Extracted runner.jar has size 0 at ${extractedJar.getAbsolutePath}")
            println(s"checkRunnerExtracted OK: runner.jar (${extractedJar.length()} bytes)")
        },
        checkSnapshot := {
            val snapshotFile = (Compile / resourceManaged).value / "META-INF" / "kyo-tasty" / "snapshot.krfl"
            if (!snapshotFile.exists())
                sys.error(s"No snapshot file found at ${snapshotFile.getAbsolutePath}")
            if (snapshotFile.length() == 0L)
                sys.error(s"Snapshot file has size 0 at ${snapshotFile.getAbsolutePath}")
            println(s"checkSnapshot OK: ${snapshotFile.getName} (${snapshotFile.length()} bytes)")
        }
    )
