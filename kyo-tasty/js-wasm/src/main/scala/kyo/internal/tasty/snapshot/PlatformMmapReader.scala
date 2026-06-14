package kyo.internal.tasty.snapshot

import kyo.*

/** JS platform: no mmap support; falls back to the heap-based SnapshotReader.read. */
object PlatformMmapReader:

    def readMapped(
        path: String
    )(using Frame): Tasty.Classpath < (Sync & Abort[TastyError] & Scope) =
        SnapshotReader.read(path)

end PlatformMmapReader
