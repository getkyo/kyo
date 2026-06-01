package kyo.internal.tasty.snapshot

import kyo.*
import kyo.internal.tasty.query.FileSource

/** Native platform: uses NativeMmapReader to init the snapshot as a memory-mapped ByteView. */
object PlatformMmapReader:

    def readMapped(
        path: String,
        source: FileSource
    )(using Frame): Tasty.Classpath < (Sync & Abort[TastyError] & Scope) =
        NativeMmapReader.init(path).flatMap: mappedView =>
            Sync.defer:
                try SnapshotReader.readMappedView(path, mappedView)
                catch
                    case ex: SnapshotReader.VersionMismatchException =>
                        Abort.fail(TastyError.SnapshotVersionMismatch(ex.found, ex.supported))
                    case ex: java.io.IOException =>
                        // no cursor: IOException does not carry a byte offset
                        Abort.fail(TastyError.SnapshotFormatError(path, ex.getMessage, 0L))

end PlatformMmapReader
