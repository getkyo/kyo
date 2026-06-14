package kyo.ffi.internal

import kyo.ffi.FfiUnsupported
import scala.scalanative.unsafe.*

/** Stub NativeLoader for Scala Native, libraries are linked at build time, not loaded at runtime. Exists for API surface compatibility. */
private[ffi] object NativeLoader:

    /** Always throws. Performs the 64-bit host check first; the 32-bit rejection wins over the not-applicable message. */
    def load(libraryId: String): Nothing =
        ensurePlatformChecked()
        throw new UnsupportedOperationException(FfiPlatformErrors.nativeLoaderNotApplicable(libraryId))
    end load

    // --- 32-bit host rejection ---

    @volatile private var platformChecked: Boolean = false

    /** Run the 64-bit host check exactly once per process. */
    private def ensurePlatformChecked(): Unit =
        if !platformChecked then
            // Carrier-thread substrate: the 64-bit host check via `sizeof[Ptr]` runs once per process; OS-thread
            // lock, not fiber coordination; off the scheduler-managed fiber path. See kyo-ffi/CONTRIBUTING.md.
            this.synchronized {
                if !platformChecked then
                    checkPlatform(detectIs64Bit())
                    platformChecked = true
                end if
            }
        end if
    end ensurePlatformChecked

    /** Detect whether the binary is 64-bit via `sizeof[Ptr[Byte]]`. */
    def detectIs64Bit(): Boolean =
        sizeof[Ptr[Byte]].toLong == 8L

    /** Throw [[kyo.ffi.FfiUnsupported]] if not 64-bit. Exposed for unit tests. */
    def checkPlatform(isBit64: Boolean): Unit =
        if !isBit64 then
            val ptrBytes = sizeof[Ptr[Byte]].toLong
            val msg      = FfiPlatformErrors.unsupported32BitHost(s"sizeof(Ptr[Byte]) = $ptrBytes")
            throw new FfiUnsupported(msg)
        end if
    end checkPlatform
end NativeLoader
