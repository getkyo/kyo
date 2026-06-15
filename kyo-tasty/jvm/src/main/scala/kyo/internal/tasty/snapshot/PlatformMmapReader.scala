package kyo.internal.tasty.snapshot

import kyo.*
import kyo.internal.tasty.binary.ByteView

/** JVM platform: uses JvmMmapReader to init the snapshot as a memory-mapped ByteView. */
object PlatformMmapReader:

    def readMapped(
        path: String
    )(using Frame): Tasty.Classpath < (Sync & Abort[TastyError] & Scope) =
        JvmMmapReader.init(path).map { mappedView =>
            // Sync.Unsafe.defer supplies AllowUnsafe for the SnapshotReader.readMappedView boundary;
            // readMappedView reconstructs the symbol graph from a memory-mapped ByteView, which the
            // safe-tier API does not expose without suspension.
            Sync.Unsafe.defer {
                try SnapshotReader.readMappedView(path, mappedView)
                catch
                    case ex: SnapshotReader.VersionMismatchException =>
                        Abort.fail(TastyError.SnapshotVersionMismatch(ex.found, ex.supported))
                    case ex: SectionValidator.SectionValidationException =>
                        Abort.fail(ex.error)
                    case ex: java.io.IOException =>
                        // no cursor: IOException does not carry a byte offset
                        Abort.fail(TastyError.SnapshotFormatError(path, ex.getMessage, 0L))
            }
        }

end PlatformMmapReader
