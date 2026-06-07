ThisBuild / scalaVersion := "3.8.3"

val snapshotEntryName = "META-INF/kyo-tasty/snapshot.krfl"

val checkJarMissingSnapshot = taskKey[Unit]("assert produced jar does NOT contain META-INF/kyo-tasty/snapshot.krfl")

def jarEntryExists(jarFile: java.io.File, entryName: String): Boolean = {
    val zf = new java.util.zip.ZipFile(jarFile)
    try Option(zf.getEntry(entryName)).isDefined
    finally zf.close()
}

// tastySnapshotEnabled := false opts out of the auto-hook. resourceGenerators is populated
// at load time; false here means the snapshot task is NOT appended.
lazy val root = (project in file("."))
    .enablePlugins(KyoTastyPlugin)
    .settings(
        organization         := "com.example",
        version              := "0.1.0-TEST",
        name                 := "resource-generators-hook-disabled-scripted-test",
        tastySnapshotEnabled := false,
        checkJarMissingSnapshot := {
            val jarFile = (Compile / packageBin).value
            if (jarEntryExists(jarFile, snapshotEntryName))
                sys.error(s"Expected NO entry $snapshotEntryName in ${jarFile.getAbsolutePath} but found one")
            println(s"checkJarMissingSnapshot OK: $snapshotEntryName absent from ${jarFile.getName}")
        }
    )
