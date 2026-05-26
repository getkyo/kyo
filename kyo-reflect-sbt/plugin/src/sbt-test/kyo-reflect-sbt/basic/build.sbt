ThisBuild / scalaVersion := "3.8.3"

val checkSnapshot = taskKey[Unit]("assert snapshot file is non-empty")

lazy val root = (project in file("."))
    .enablePlugins(KyoReflectPlugin)
    .settings(
        organization := "com.example",
        version      := "0.1.0-TEST",
        name         := "basic-scripted-test",
        checkSnapshot := {
            val snapshotDir = (Compile / reflectSnapshotDir).value
            val files = Option(snapshotDir.listFiles()).getOrElse(Array.empty[java.io.File])
                .filter(_.getName.endsWith(".krfl"))
            if (files.isEmpty)
                sys.error(s"No .krfl snapshot file found in $snapshotDir")
            val f = files.head
            if (f.length() == 0L)
                sys.error(s"Snapshot file ${f.getName} has size 0")
            println(s"checkSnapshot OK: ${f.getName} (${f.length()} bytes)")
        }
    )
