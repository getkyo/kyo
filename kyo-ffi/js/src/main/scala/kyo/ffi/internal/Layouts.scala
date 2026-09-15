package kyo.ffi.internal

import scala.scalajs.js.typedarray.DataView
import scala.scalajs.js.typedarray.Uint8Array

/** JS-specific layout helpers for codegen.
  *
  * Primitive [[kyo.internal.UnsafeLayout]] instances are now provided by kyo-data. This object retains only the union layout helper used by
  * the FFI code generator, plus the [[JsRawSegment]] class used by generated code and [[kyo.ffi.Buffer.Raw]].
  */
private[ffi] object Layouts:

    /** Register an `@Ffi.Union` case class as a koffi union type. Delegates to `KoffiFacade.union` -- koffi applies the C ABI union layout
      * internally (`sizeof == max(fieldSizes)`, `alignof == max(fieldAlignments)`).
      */
    def unionLayout(name: String, fields: scala.scalajs.js.Dynamic): scala.scalajs.js.Dynamic =
        KoffiFacade.union(name, fields)
end Layouts

/** Opaque AnyRef wrapper around a `Uint8Array` + a pre-constructed `DataView` covering the same `ArrayBuffer` -- so it can be stored in
  * [[kyo.ffi.Buffer.Raw]], which is an opaque `AnyRef` in the shared API. Holding both avoids reconstructing a `DataView` on every typed
  * access.
  *
  * The [[u8a]] field is what generated FFI code unwraps to pass to koffi; the [[view]] field is used by JS platform code.
  */
final class JsRawSegment(val u8a: Uint8Array, val view: DataView)

object JsRawSegment:
    private[ffi] def fromUint8(u8a: Uint8Array): JsRawSegment =
        new JsRawSegment(u8a, new DataView(u8a.buffer, u8a.byteOffset, u8a.byteLength))
end JsRawSegment
