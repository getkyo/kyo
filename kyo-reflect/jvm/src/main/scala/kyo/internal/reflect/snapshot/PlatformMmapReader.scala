package kyo.internal.reflect.snapshot

import kyo.*
import kyo.internal.reflect.binary.ByteView
import kyo.internal.reflect.query.FileSource

/** JVM platform: uses JvmMmapReader to open the snapshot as a memory-mapped ByteView. */
object PlatformMmapReader:

    def readMapped(
        path: String,
        source: FileSource,
        cp: kyo.internal.reflect.query.Classpath
    )(using Frame): Unit < (Sync & Abort[ReflectError] & Scope) =
        JvmMmapReader.open(path).flatMap: mappedView =>
            Sync.defer:
                try SnapshotReader.readMappedView(path, mappedView, cp)
                catch
                    case ex: SnapshotReader.VersionMismatchException =>
                        Abort.fail(ReflectError.SnapshotVersionMismatch(ex.found, ex.supported))
                    case ex: java.io.IOException =>
                        Abort.fail(ReflectError.SnapshotFormatError(path, ex.getMessage))

end PlatformMmapReader
