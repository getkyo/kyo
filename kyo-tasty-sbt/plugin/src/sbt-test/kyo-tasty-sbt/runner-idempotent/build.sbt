ThisBuild / scalaVersion := "3.8.3"

/** Writes the current runner.jar last-modified time (milliseconds) to runner-mtime.txt
  * in the project's target directory. Called before and after reload to compare.
  */
val recordRunnerMtime = taskKey[Unit]("record runner.jar mtime to target/runner-mtime.txt")

/** Reads runner-mtime.txt and asserts the recorded mtime matches the current runner.jar mtime.
  * If they differ the file was overwritten on the second extraction, which is the bug being guarded.
  */
val assertRunnerMtimeUnchanged = taskKey[Unit]("assert runner.jar mtime is identical to the recorded value")

lazy val root = (project in file("."))
    .enablePlugins(KyoTastyPlugin)
    .settings(
        organization := "com.example",
        version      := "0.1.0-TEST",
        name         := "runner-idempotent-scripted-test",
        recordRunnerMtime := {
            val _ = tastyRunnerClasspath.value // ensure extraction has run
            val runnerJar =
                (LocalRootProject / target).value / "kyo-tasty-runner-extract" / "runner.jar"
            if (!runnerJar.exists())
                sys.error(s"runner.jar not found at ${runnerJar.getAbsolutePath}")
            val mtime    = runnerJar.lastModified()
            val mtimeOut = target.value / "runner-mtime.txt"
            IO.write(mtimeOut, mtime.toString)
            println(s"recordRunnerMtime: mtime=$mtime written to ${mtimeOut.getName}")
        },
        assertRunnerMtimeUnchanged := {
            val _ = tastyRunnerClasspath.value // re-evaluate setting (triggers extractRunnerJar)
            val runnerJar =
                (LocalRootProject / target).value / "kyo-tasty-runner-extract" / "runner.jar"
            val mtimeOut = target.value / "runner-mtime.txt"
            if (!mtimeOut.exists())
                sys.error(s"runner-mtime.txt not found; run recordRunnerMtime first")
            val recorded = IO.read(mtimeOut).trim.toLong
            val current  = runnerJar.lastModified()
            if (current != recorded)
                sys.error(
                    s"runner.jar mtime changed: recorded=$recorded current=$current " +
                        s"(file was overwritten on second extraction - BLOCKER-1 not fixed)"
                )
            println(s"assertRunnerMtimeUnchanged OK: mtime=$current (unchanged after reload)")
        }
    )
