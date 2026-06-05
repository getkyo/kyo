ThisBuild / scalaVersion := "3.8.3"

val checkSnapshot = taskKey[Unit]("assert snapshot file is non-empty at the managed resource path")

lazy val root = (project in file("."))
    .enablePlugins(KyoTastyPlugin)
    .settings(
        organization := "com.example",
        version      := "0.1.0-TEST",
        name         := "basic-scripted-test",
        checkSnapshot := {
            val snapshotFile = (Compile / resourceManaged).value / "META-INF" / "kyo-tasty" / "snapshot.krfl"
            if (!snapshotFile.exists())
                sys.error(s"No snapshot file found at ${snapshotFile.getAbsolutePath}")
            if (snapshotFile.length() == 0L)
                sys.error(s"Snapshot file has size 0 at ${snapshotFile.getAbsolutePath}")
            println(s"checkSnapshot OK: ${snapshotFile.getName} (${snapshotFile.length()} bytes)")
        }
    )
