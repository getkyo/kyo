package kyo.internal.tasty.snapshot

import kyo.*
import kyo.internal.tasty.query.FileSource

/** JS platform: no mmap support; falls back to the heap-based SnapshotReader.read. */
object PlatformMmapReader:

    def readMapped(
        path: String,
        source: FileSource
    )(using Frame): Tasty.Classpath < (Sync & Abort[TastyError] & Scope) =
        SnapshotReader.read(path, source)

end PlatformMmapReader
