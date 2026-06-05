package io.getkyo.tasty.sbt

import sbt._
import sbt.Keys._

/** sbt plugin that generates a kyo-tasty snapshot at compile time by forking a JVM.
  *
  * The forked JVM runs [[io.getkyo.tasty.sbt.runner.SnapshotRunner]] with the project's compile classpath. The
  * snapshot is written to `META-INF/kyo-tasty/snapshot.krfl` inside the managed resource directory, so `sbt package`
  * automatically includes it in the published JAR.
  *
  * Enable on a project with:
  * {{{
  * lazy val myProject = (project in file("..."))
  *     .enablePlugins(KyoTastyPlugin)
  * }}}
  *
  * The runner JAR is bundled inside the plugin; no separate dependency is needed. To opt out of the auto-hook into
  * `Compile / resourceGenerators` (and therefore keep the snapshot out of the published JAR), set:
  * {{{
  *     tastySnapshotEnabled := false
  * }}}
  */
object KyoTastyPlugin extends AutoPlugin {

    override def trigger  = noTrigger
    override def requires = sbt.plugins.JvmPlugin

    object autoImport {

        /** Directory where the `.krfl` snapshot file was previously written.
          * Retained for backward compatibility. After Phase 14 the snapshot is written to
          * `(Compile / resourceManaged).value / META-INF / kyo-tasty / snapshot.krfl`.
          */
        val tastySnapshotDir: SettingKey[File] = settingKey[File](
            "Snapshot staging directory (retained for backward compat; snapshot now goes to resourceManaged)."
        )

        /** Produces the snapshot file by forking a JVM that runs SnapshotRunner.
          * Returns the snapshot file path.
          */
        val tastySnapshot: TaskKey[File] = taskKey[File](
            "Generate a kyo-tasty snapshot of the project's compile classpath."
        )

        /** Classpath entries (JARs and directories) for the forked SnapshotRunner JVM.
          * Defaults to the bundled runner JAR extracted from the plugin classpath
          * (item 23 / Q-026 / BIND-017). Override with `Seq.empty` to disable; the task
          * then fails with a message directing users to either remove the override or supply
          * a runner JAR.
          */
        val tastyRunnerClasspath: SettingKey[Seq[File]] = settingKey[Seq[File]](
            "Runner classpath; defaults to bundled runner JAR (item 23 / Q-026 BIND-017)."
        )

        /** Toggle the auto-hook into `Compile / resourceGenerators` (item 24 / Q-011 RI-002).
          * When true (default), `sbt package` automatically includes `META-INF/kyo-tasty/snapshot.krfl`
          * in the output JAR. Set to false to opt out.
          */
        val tastySnapshotEnabled: SettingKey[Boolean] = settingKey[Boolean](
            "Toggle the auto-hook into Compile / resourceGenerators (item 24 / Q-011 RI-002). Defaults to true."
        )
    }

    import autoImport._

    private val runnerMainClass = "io.getkyo.tasty.sbt.runner.SnapshotRunner"

    override lazy val projectSettings: Seq[Setting[_]] = Seq(
        tastySnapshotDir     := (Compile / target).value / "kyo-tasty-snapshots",
        tastySnapshotEnabled := true,
        tastyRunnerClasspath := {
            val extractDir: File       = (LocalRootProject / target).value / "kyo-tasty-runner-extract"
            IO.createDirectory(extractDir)
            val extracted: File        = extractDir / "runner.jar"
            val resource: java.net.URL = getClass.getResource("/kyo-tasty/runner.jar")
            if (resource == null)
                sys.error(
                    "kyo-tasty-sbt: bundled runner.jar resource not found on plugin classpath; " +
                        "this is a plugin packaging bug, not a user error."
                )
            else {
                IO.transfer(resource.openStream(), extracted)
                Seq(extracted)
            }
        },
        tastySnapshot := {
            val log         = streams.value.log
            val rc          = tastyRunnerClasspath.value
            // Use dependencyClasspath (not fullClasspath) to avoid a circular dependency:
            // fullClasspath depends on resourceGenerators which may include tastySnapshot
            // itself when tastySnapshotEnabled := true. dependencyClasspath excludes
            // resourceGenerators output and breaks the cycle.
            val roots       = (Compile / dependencyClasspath).value.map(_.data.getAbsolutePath)
            val out         = (Compile / resourceManaged).value / "META-INF" / "kyo-tasty" / "snapshot.krfl"
            val cacheDir    = streams.value.cacheDirectory / "tasty-snapshot"
            val javaHomeVal = javaHome.value
            val baseDirVal  = baseDirectory.value
            if (rc.isEmpty)
                throw new sbt.internal.util.MessageOnlyException(
                    "kyo-tasty-sbt: tastyRunnerClasspath is empty. Either remove the explicit " +
                        "`:= Seq.empty` override (to use the bundled runner) or supply a runner JAR."
                )
            else {
                val cpSet = roots.map(file(_)).toSet
                FileFunction.cached(cacheDir) { _ =>
                    log.info(
                        s"kyo-tasty-sbt: generating snapshot for ${roots.size} classpath entries into ${out.getAbsolutePath}"
                    )
                    forkRunnerAndWrite(rc, roots, out, javaHomeVal, baseDirVal)
                    Set(out)
                }(cpSet)
                out
            }
        },
        Compile / resourceGenerators ++= {
            if (tastySnapshotEnabled.value) Seq(tastySnapshot.taskValue.map(Seq(_)))
            else Nil
        }
    )

    /** Fork the SnapshotRunner JVM. Passes the compile-classpath roots and a staging
      * directory. After the fork completes, copies the produced `.krfl` file to the
      * fixed `out` path (item 23 staging-then-rename approach, Decision 1).
      */
    private def forkRunnerAndWrite(
        rc: Seq[File],
        roots: Seq[String],
        out: File,
        javaHomeOpt: Option[File],
        workDir: File
    ): Unit = {
        val stagingDir = out.getParentFile / "staging"
        IO.createDirectory(out.getParentFile)
        IO.createDirectory(stagingDir)

        val exitCode = Fork.java(
            ForkOptions()
                .withJavaHome(javaHomeOpt)
                .withOutputStrategy(Some(StdoutOutput))
                .withWorkingDirectory(Some(workDir)),
            Seq(
                "-classpath", rc.map(_.getAbsolutePath).mkString(java.io.File.pathSeparator),
                runnerMainClass,
                roots.mkString(java.io.File.pathSeparator),
                stagingDir.getAbsolutePath
            )
        )

        if (exitCode != 0)
            throw new sbt.internal.util.MessageOnlyException(
                s"kyo-tasty-sbt: SnapshotRunner exited with code $exitCode. Check output above for details."
            )

        val krflFiles = Option(stagingDir.listFiles()).getOrElse(Array.empty[File])
            .filter(_.getName.endsWith(".krfl"))
        if (krflFiles.isEmpty)
            throw new sbt.internal.util.MessageOnlyException(
                s"kyo-tasty-sbt: SnapshotRunner produced no .krfl file in $stagingDir"
            )
        IO.copyFile(krflFiles.head, out)
    }
}
