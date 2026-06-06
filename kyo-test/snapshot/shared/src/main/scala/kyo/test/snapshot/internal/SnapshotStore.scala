package kyo.test.snapshot.internal

import kyo.Maybe

/** Platform-specific file I/O for snapshot files.
  *
  * The shared object forwards to the per-platform `SnapshotStorePlatform` implementation. JVM and Native share a common NIO-backed
  * implementation via the `jvm-native/` source set; JS uses a Node.js `fs` facade.
  */
object SnapshotStore:

    /** Returns the snapshot content, or Absent if the file does not exist. */
    def read(path: String): Maybe[String] =
        SnapshotStorePlatform.read(path)

    /** Writes content to path, creating parent directories as needed. Overwrites if already present.
      *
      * A trailing newline is appended if the content does not already end with one.
      */
    def write(path: String, content: String): Unit =
        val normalized = if content.endsWith("\n") then content else content + "\n"
        SnapshotStorePlatform.write(path, normalized)

end SnapshotStore
