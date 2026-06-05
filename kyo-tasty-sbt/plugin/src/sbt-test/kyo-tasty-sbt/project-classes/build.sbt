ThisBuild / scalaVersion := "3.8.3"

/** Assert the snapshot file contains the FQN of a class defined in the project's own sources.
  *
  * BLOCKER-2 guard: tastySnapshot must include (Compile / classDirectory) so the runner indexes
  * the project's own compiled classes, not only dependency JARs. Without the fix, MyProjectClass
  * is absent from the snapshot even though it is compiled by the same project.
  *
  * Verification strategy: the KRFL NAMES section stores every string (including FQNs) as raw
  * UTF-8 bytes. Scanning the snapshot binary for the literal UTF-8 encoding of "MyProjectClass"
  * confirms the class was indexed.
  */
val checkProjectClassInSnapshot = taskKey[Unit](
    "assert snapshot bytes contain the FQN of a class from this project's own sources"
)

def containsBytes(haystack: Array[Byte], needle: Array[Byte]): Boolean = {
    val limit = haystack.length - needle.length
    var i     = 0
    var found = false
    while (i <= limit && !found) {
        var j    = 0
        var same = true
        while (j < needle.length && same) {
            if (haystack(i + j) != needle(j)) same = false
            j += 1
        }
        if (same) found = true
        i += 1
    }
    found
}

lazy val root = (project in file("."))
    .enablePlugins(KyoTastyPlugin)
    .settings(
        organization := "com.example",
        version      := "0.1.0-TEST",
        name         := "project-classes-scripted-test",
        checkProjectClassInSnapshot := {
            val snapshotFile = tastySnapshot.value
            if (!snapshotFile.exists())
                sys.error(s"No snapshot file found at ${snapshotFile.getAbsolutePath}")
            val bytes   = java.nio.file.Files.readAllBytes(snapshotFile.toPath)
            val fqn     = "MyProjectClass"
            val fqnUtf8 = fqn.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            if (!containsBytes(bytes, fqnUtf8))
                sys.error(
                    s"Snapshot at ${snapshotFile.getAbsolutePath} does not contain '$fqn'. " +
                        "classDirectory was not included in the snapshot scope (BLOCKER-2 not fixed)."
                )
            println(s"checkProjectClassInSnapshot OK: '$fqn' found in ${snapshotFile.getName} (${bytes.length} bytes)")
        }
    )
