package kyo.ffi.internal

import java.lang.foreign.MemorySegment
import kyo.ffi.Ffi

/** JVM implementation of [[Ffi.Handle]] operations. The concrete carrier is a `MemorySegment`. */
object PtrOps:

    /** Receives the unwrapped AnyRef carrier (Handle is opaque to AnyRef). */
    def isNull(raw: AnyRef): Boolean =
        raw == null || raw.asInstanceOf[MemorySegment].address() == 0L
end PtrOps
