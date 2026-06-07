ThisBuild / scalaVersion := "3.8.3"

val snapshotEntryName = "META-INF/kyo-tasty/snapshot.krfl"

val checkJarContainsSnapshot = taskKey[Unit]("assert produced jar contains META-INF/kyo-tasty/snapshot.krfl")

def jarEntryExists(jarFile: java.io.File, entryName: String): Boolean = {
    val zf = new java.util.zip.ZipFile(jarFile)
    try Option(zf.getEntry(entryName)).isDefined
    finally zf.close()
}

// tastySnapshotEnabled := true is the default; no explicit setting needed.
// This fixture verifies that `sbt package` emits META-INF/kyo-tasty/snapshot.krfl when
// enabled, and that a second package run short-circuits via FileFunction.cached.
lazy val root = (project in file("."))
    .enablePlugins(KyoTastyPlugin)
    .settings(
        organization := "com.example",
        version      := "0.1.0-TEST",
        name         := "resource-generators-hook-scripted-test",
        checkJarContainsSnapshot := {
            val jarFile = (Compile / packageBin).value
            if (!jarEntryExists(jarFile, snapshotEntryName))
                sys.error(s"Expected entry $snapshotEntryName in ${jarFile.getAbsolutePath} but not found")
            println(s"checkJarContainsSnapshot OK: $snapshotEntryName present in ${jarFile.getName}")
        }
    )
