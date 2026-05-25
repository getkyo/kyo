package kyo.internal.reflect.snapshot

import kyo.*
import kyo.internal.reflect.query.FileSource

/** JS platform: no mmap support; falls back to the heap-based SnapshotReader.read. */
object PlatformMmapReader:

    def readMapped(
        path: String,
        source: FileSource,
        cp: kyo.internal.reflect.query.Classpath
    )(using Frame): Unit < (Sync & Abort[ReflectError] & Scope) =
        SnapshotReader.read(path, source, cp)

end PlatformMmapReader
