package kyo.test.snapshot.internal

import kyo.Maybe
import kyo.Span

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

    /** Returns the raw snapshot bytes, or Absent if the file does not exist.
      *
      * The bytes-path sibling of [[read]] for binary codecs. Unlike [[read]] and [[write]], no trailing-newline handling is
      * applied: the bytes are returned exactly as stored.
      */
    def readBytes(path: String): Maybe[Span[Byte]] =
        SnapshotStorePlatform.readBytes(path)

    /** Writes raw bytes to path verbatim, creating parent directories as needed. Overwrites if already present.
      *
      * The bytes-path sibling of [[write]] for binary codecs: content is written exactly as given, with no base64 encoding and
      * no trailing-newline append, so a stored binary snapshot is the real codec wire artifact.
      */
    def writeBytes(path: String, content: Span[Byte]): Unit =
        SnapshotStorePlatform.writeBytes(path, content)

end SnapshotStore
