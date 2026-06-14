package kyo.ffi.internal

import scala.scalanative.unsafe.*

/** Opaque AnyRef wrapper around a Scala Native `Ptr[Byte]` so it can be stored in [[kyo.ffi.Buffer.Raw]], which is an opaque `AnyRef` in
  * the shared API. `Ptr[Byte]` is a value type on Native and not itself an `AnyRef` -- wrapping it in a class gives us a boxed reference
  * that generated FFI code can unwrap.
  */
final class NativePtr(val ptr: Ptr[Byte])
