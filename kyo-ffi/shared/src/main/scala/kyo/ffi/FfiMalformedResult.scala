package kyo.ffi

/** Thrown by generated returned-struct readers when a `char*` field is not NUL-terminated within the bounded
  * `-Dkyo.ffi.stringFieldMaxBytes=` window (default 64 KiB). Indicates the C library either returned a pointer that is not NUL-terminated
  * within the cap or returned a pointer to freed/uninitialized memory. The diagnostic names the binding, method, and field.
  */
final class FfiMalformedResult(msg: String) extends RuntimeException(msg)
