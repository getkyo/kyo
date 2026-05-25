package kyo.internal.reflect.snapshot

import kyo.*
import kyo.internal.reflect.query.FileSource

/** Native platform: uses NativeMmapReader to open the snapshot as a memory-mapped ByteView. */
object PlatformMmapReader:

    def readMapped(
        path: String,
        source: FileSource,
        cp: kyo.internal.reflect.query.Classpath
    )(using Frame): Unit < (Sync & Abort[ReflectError] & Scope) =
        NativeMmapReader.open(path).flatMap: mappedView =>
            Sync.defer:
                try SnapshotReader.readMappedView(path, mappedView, cp)
                catch
                    case ex: SnapshotReader.VersionMismatchException =>
                        Abort.fail(ReflectError.SnapshotVersionMismatch(ex.found, ex.supported))
                    case ex: java.io.IOException =>
                        Abort.fail(ReflectError.SnapshotFormatError(path, ex.getMessage))

end PlatformMmapReader
