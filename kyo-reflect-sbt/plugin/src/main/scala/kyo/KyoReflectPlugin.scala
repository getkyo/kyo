package kyo

import sbt._
import sbt.Keys._

/** sbt plugin that generates a kyo-reflect snapshot at compile time by forking a JVM.
  *
  * The forked JVM runs [[io.getkyo.reflect.sbt.runner.SnapshotRunner]] with the project's compile classpath and writes a
  * `.krfl` snapshot to [[reflectSnapshotDir]]. Subsequent calls to `Reflect.Classpath.openCached` on the same classpath
  * find the snapshot on the first stat check instead of paying the full cold-load cost.
  *
  * Enable on a project with:
  * {{{
  * lazy val myProject = (project in file("..."))
  *     .enablePlugins(KyoReflectPlugin)
  *     .settings(
  *         reflectRunnerClasspath := Seq(file("/path/to/kyo-reflect-sbt-runner-assembly.jar"))
  *     )
  * }}}
  */
object KyoReflectPlugin extends AutoPlugin {

    override def trigger  = noTrigger
    override def requires = sbt.plugins.JvmPlugin

    object autoImport {

        /** Directory where the `.krfl` snapshot file is written.
          * Defaults to `target/kyo-reflect-snapshots/` inside the project's compile target.
          */
        val reflectSnapshotDir: SettingKey[File] = settingKey[File](
            "Directory where kyo-reflect snapshot files are written (default: target/kyo-reflect-snapshots/)."
        )

        /** Produces the snapshot file by forking a JVM that runs SnapshotRunner.
          * Returns the snapshot directory (the runner writes the actual .krfl file inside it).
          */
        val reflectSnapshot: TaskKey[File] = taskKey[File](
            "Generate a kyo-reflect snapshot of the project's compile classpath."
        )

        /** Classpath entries (JARs and directories) for the forked SnapshotRunner JVM.
          * Set this to the runner assembly JAR plus any additional dependencies.
          * Defaults to empty; the task fails with a helpful message if left empty.
          */
        val reflectRunnerClasspath: SettingKey[Seq[File]] = settingKey[Seq[File]](
            "Classpath entries for the forked SnapshotRunner JVM. " +
                "Set to the kyo-reflect-sbt-runner assembly JAR path(s). Defaults to empty."
        )
    }

    import autoImport._

    private val runnerMainClass = "io.getkyo.reflect.sbt.runner.SnapshotRunner"

    override lazy val projectSettings: Seq[Setting[_]] = Seq(
        reflectSnapshotDir := (Compile / target).value / "kyo-reflect-snapshots",
        reflectRunnerClasspath := {
            // Option B: read runner JAR from the runner.jar system property if set,
            // otherwise fall back to the reflectRunnerClasspath setting value (empty by default).
            sys.props.get("runner.jar") match {
                case Some(path) => Seq(file(path))
                case None       => Seq.empty
            }
        },
        reflectSnapshot := {
            val log          = streams.value.log
            val snapshotDir  = reflectSnapshotDir.value
            val runnerCp     = reflectRunnerClasspath.value
            val compileRoots = (Compile / fullClasspath).value.map(_.data.getAbsolutePath)
            val javaHomeOpt  = javaHome.value
            val baseDir      = baseDirectory.value

            // Validate runner classpath before forking.
            if (runnerCp.isEmpty) {
                throw new sbt.internal.util.MessageOnlyException(
                    "kyo-reflect-sbt: reflectRunnerClasspath is empty. " +
                        "Set reflectRunnerClasspath to the kyo-reflect-sbt-runner assembly JAR, " +
                        "or pass -Drunner.jar=<path> to sbt."
                )
            }

            val missingJars = runnerCp.filterNot(_.exists())
            if (missingJars.nonEmpty) {
                throw new sbt.internal.util.MessageOnlyException(
                    "kyo-reflect-sbt: runner JAR(s) not found: " +
                        missingJars.map(_.getAbsolutePath).mkString(", ") + ". " +
                        "Run `kyo-reflect-sbt-runner/assembly` first."
                )
            }

            // Ensure snapshot directory exists.
            IO.createDirectory(snapshotDir)

            val runnerClasspathStr = runnerCp.map(_.getAbsolutePath).mkString(java.io.File.pathSeparator)
            val rootsArg           = compileRoots.mkString(java.io.File.pathSeparator)
            val snapshotDirArg     = snapshotDir.getAbsolutePath

            log.info(
                s"kyo-reflect-sbt: generating snapshot for ${compileRoots.size} classpath entries " +
                    s"into $snapshotDirArg"
            )

            val forkOpts = ForkOptions()
                .withJavaHome(javaHomeOpt)
                .withOutputStrategy(Some(StdoutOutput))
                .withWorkingDirectory(Some(baseDir))

            val exitCode = Fork.java(
                forkOpts,
                Seq(
                    "-classpath", runnerClasspathStr,
                    runnerMainClass,
                    rootsArg,
                    snapshotDirArg
                )
            )

            if (exitCode != 0) {
                throw new sbt.internal.util.MessageOnlyException(
                    s"kyo-reflect-sbt: SnapshotRunner exited with code $exitCode. " +
                        s"Check output above for details."
                )
            }

            snapshotDir
        }
    )
}
