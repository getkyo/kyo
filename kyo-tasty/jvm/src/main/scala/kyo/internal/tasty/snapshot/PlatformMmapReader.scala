package kyo.internal.tasty.snapshot

import kyo.*
import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.query.FileSource

/** JVM platform: uses JvmMmapReader to open the snapshot as a memory-mapped ByteView. */
object PlatformMmapReader:

    def readMapped(
        path: String,
        source: FileSource,
        cp: kyo.internal.tasty.query.Classpath
    )(using Frame): Unit < (Sync & Abort[TastyError] & Scope) =
        JvmMmapReader.open(path).flatMap: mappedView =>
            Sync.defer:
                try SnapshotReader.readMappedView(path, mappedView, cp)
                catch
                    case ex: SnapshotReader.VersionMismatchException =>
                        Abort.fail(TastyError.SnapshotVersionMismatch(ex.found, ex.supported))
                    case ex: java.io.IOException =>
                        Abort.fail(TastyError.SnapshotFormatError(path, ex.getMessage))

end PlatformMmapReader
