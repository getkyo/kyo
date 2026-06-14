package kyo.ffi

/** Legacy alias for [[FfiLoadError.Unsupported]]. Kept as a deprecated subtype so existing `catch FfiUnsupported` blocks continue to match
  * , new code should `catch FfiLoadError` or `catch FfiLoadError.Unsupported`.
  *
  * Non-final so JS-only refinements (e.g. [[FfiKoffiVersionMismatch]]) can inherit.
  */
@deprecated("Use FfiLoadError.Unsupported", "0.2.0")
class FfiUnsupported(msg: String) extends FfiLoadError.Unsupported(msg)
