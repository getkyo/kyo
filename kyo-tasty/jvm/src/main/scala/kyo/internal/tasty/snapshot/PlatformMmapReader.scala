package kyo.internal.tasty.snapshot

import kyo.*
import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.query.FileSource

/** JVM platform: uses JvmMmapReader to init the snapshot as a memory-mapped ByteView. */
object PlatformMmapReader:

    def readMapped(
        path: String,
        source: FileSource
    )(using Frame): Tasty.Classpath < (Sync & Abort[TastyError] & Scope) =
        JvmMmapReader.init(path).flatMap: mappedView =>
            Sync.defer:
                try SnapshotReader.readMappedView(path, mappedView)
                catch
                    case ex: SnapshotReader.VersionMismatchException =>
                        Abort.fail(TastyError.SnapshotVersionMismatch(ex.found, ex.supported))
                    case ex: SectionValidator.SectionValidationException =>
                        Abort.fail(ex.error)
                    case ex: java.io.IOException =>
                        // no cursor: IOException does not carry a byte offset
                        Abort.fail(TastyError.SnapshotFormatError(path, ex.getMessage, 0L))

end PlatformMmapReader
