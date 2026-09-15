package kyo.ffi.internal

import kyo.ffi.Ffi
import scala.scalajs.js

/** Scala.js implementation of [[Ffi.Handle]] operations. The concrete carrier is a koffi opaque pointer handle (`js.Any`). */
object PtrOps:

    /** Receives the unwrapped AnyRef carrier (Handle is opaque to AnyRef). */
    def isNull(raw: AnyRef): Boolean =
        raw == null || js.isUndefined(raw) || raw.asInstanceOf[js.Any] == null
end PtrOps
