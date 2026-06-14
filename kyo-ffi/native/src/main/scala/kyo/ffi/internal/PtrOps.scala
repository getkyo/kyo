package kyo.ffi.internal

import kyo.ffi.Ffi
import scala.scalanative.unsafe.*

/** Scala Native implementation of [[Ffi.Handle]] operations. The concrete carrier is a boxed `NativePtr` (wrapping `Ptr[Byte]`). */
object PtrOps:

    /** Receives the unwrapped AnyRef carrier (Handle is opaque to AnyRef). */
    def isNull(raw: AnyRef): Boolean =
        raw == null || raw.asInstanceOf[NativePtr].ptr == null
end PtrOps
