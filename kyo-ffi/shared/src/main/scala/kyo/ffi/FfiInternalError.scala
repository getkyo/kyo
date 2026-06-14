package kyo.ffi

/** Thrown by the kyo-ffi runtime when an internal invariant is violated, most prominently by `kyo.ffi.internal.FfiUnsafe.expect` when a
  * runtime cast site receives a value whose class does not match the kyo-ffi-internal shape the generated code expects. The diagnostic
  * includes binding and method context. The generated code uses `asInstanceOf` for type-shape casts that the codegen validates statically
  * (e.g. unwrapping `Ffi.Guard` to `JvmGuard`); the checked `FfiUnsafe.expect` helper guards the sites whose input provenance comes from
  * user / host code.
  */
final class FfiInternalError(msg: String) extends RuntimeException(msg)
