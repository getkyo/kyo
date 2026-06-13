package kyo.ffi

/** Legacy subtype of [[FfiUnsupported]] (which is itself a subtype of [[FfiLoadError.Unsupported]]). Raised by the Scala.js
  * [[kyo.ffi.internal.KoffiAbiProbe]] at first [[kyo.ffi.internal.KoffiFacade.load]] call when the installed koffi npm package is either
  * missing a required ABI method or reports a `koffi.version` outside kyo-ffi's supported range.
  *
  * The message always carries both the detected koffi version (or `unknown`) and the supported range, so operators can resolve the mismatch
  * by pinning `"koffi": "^2.7"` in their app's `package.json`. Retained as a deprecated alias so existing `catch FfiKoffiVersionMismatch`
  * blocks continue to match, new code should catch [[FfiLoadError]].
  */
@deprecated("Use FfiLoadError.Unsupported (or FfiLoadError.AbiMismatch for structured expected/actual)", "0.2.0")
class FfiKoffiVersionMismatch(msg: String) extends FfiUnsupported(msg)
