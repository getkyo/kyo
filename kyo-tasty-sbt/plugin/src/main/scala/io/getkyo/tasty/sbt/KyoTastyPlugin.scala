package io.getkyo.tasty.sbt

import sbt._
import sbt.Keys._
import java.nio.file.{ Files, StandardCopyOption }
import java.security.MessageDigest

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

        /** Directory where the `.krfl` snapshot file was previously written. Retained for backward compatibility; the snapshot is now
          * written to `(Compile / resourceManaged).value / META-INF / kyo-tasty / snapshot.krfl`.
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

        /** Classpath entries (JARs and directories) for the forked SnapshotRunner JVM. Defaults to the bundled runner JAR extracted from
          * the plugin classpath. Override with `Seq.empty` to disable; the task then fails with a message directing users to either remove
          * the override or supply a runner JAR.
          */
        val tastyRunnerClasspath: SettingKey[Seq[File]] = settingKey[Seq[File]](
            "Runner classpath; defaults to bundled runner JAR."
        )

        /** Toggle the auto-hook into `Compile / resourceGenerators`. When true (default), `sbt package` automatically includes
          * `META-INF/kyo-tasty/snapshot.krfl` in the output JAR. Set to false to opt out.
          */
        val tastySnapshotEnabled: SettingKey[Boolean] = settingKey[Boolean](
            "Toggle the auto-hook into Compile / resourceGenerators. Defaults to true."
        )
    }

    import autoImport._

    private val runnerMainClass = "io.getkyo.tasty.sbt.runner.SnapshotRunner"

    /** SHA-256 hex digest of a byte array. */
    private def sha256Hex(bytes: Array[Byte]): String = {
        val md = MessageDigest.getInstance("SHA-256")
        md.digest(bytes).map("%02x".format(_)).mkString
    }

    /** SHA-256 hex digest of a file on disk, or empty string if the file does not exist. */
    private def sha256HexFile(f: File): String =
        if (f.exists()) sha256Hex(Files.readAllBytes(f.toPath)) else ""

    /** Extract the bundled runner.jar to `extractDir/runner.jar` using a content-hash guard and atomic rename.
      *
      * If the target already exists and its SHA-256 matches the bundled resource, the write is skipped entirely
      * to avoid racing concurrent sbt processes that call this setting in parallel. Otherwise the bytes are written
      * to a sibling `.tmp` file in the SAME directory (same filesystem) and then renamed with ATOMIC_MOVE so no
      * reader ever observes a partially-written JAR.
      */
    private def extractRunnerJar(extractDir: File): File = {
        IO.createDirectory(extractDir)
        val target   = extractDir / "runner.jar"
        val resource = getClass.getResource("/kyo-tasty/runner.jar")
        if (resource == null)
            sys.error(
                "kyo-tasty-sbt: bundled runner.jar resource not found on plugin classpath; " +
                    "this is a plugin packaging bug, not a user error."
            )
        val in = resource.openStream()
        val bytes =
            try in.readAllBytes()
            finally in.close()
        val bundledHash = sha256Hex(bytes)
        if (sha256HexFile(target) != bundledHash) {
            val tmp = extractDir / "runner.jar.tmp"
            Files.write(tmp.toPath, bytes)
            Files.move(
                tmp.toPath,
                target.toPath,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        }
        target
    }

    override lazy val projectSettings: Seq[Setting[_]] = Seq(
        tastySnapshotDir     := (Compile / target).value / "kyo-tasty-snapshots",
        tastySnapshotEnabled := true,
        tastyRunnerClasspath := {
            val extractDir: File = (LocalRootProject / target).value / "kyo-tasty-runner-extract"
            Seq(extractRunnerJar(extractDir))
        },
        tastySnapshot := {
            val log = streams.value.log
            val rc  = tastyRunnerClasspath.value
            // Use dependencyClasspath (not fullClasspath) to avoid a circular dependency:
            // fullClasspath depends on resourceGenerators which may include tastySnapshot
            // itself when tastySnapshotEnabled := true. dependencyClasspath excludes
            // resourceGenerators output and breaks the cycle.
            // Append classDirectory to restore the project's own compiled classes which
            // dependencyClasspath omits. classDirectory does NOT depend on resourceGenerators,
            // so it does not re-introduce the cycle.
            val depRoots  = (Compile / dependencyClasspath).value.map(_.data.getAbsolutePath)
            val classDir  = (Compile / classDirectory).value.getAbsolutePath
            val roots     = depRoots :+ classDir
            val out       = (Compile / resourceManaged).value / "META-INF" / "kyo-tasty" / "snapshot.krfl"
            val cacheDir  = streams.value.cacheDirectory / "tasty-snapshot"
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

    /** Fork the SnapshotRunner JVM. Passes the compile-classpath roots and a staging directory. After the fork completes, copies the
      * produced `.krfl` file to the fixed `out` path (staging-then-rename to give the consumer an atomic single-file view).
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
