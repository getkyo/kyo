package kyo.ffi.internal

import java.lang.foreign.MemoryLayout

/** JVM-specific layout helpers for codegen.
  *
  * Primitive [[kyo.internal.UnsafeLayout]] instances are now provided by kyo-data. This object retains only the union layout helper used by
  * the FFI code generator.
  */
private[ffi] object Layouts:

    /** Construct a Panama `MemoryLayout.unionLayout(elements)` and apply the supplied name. Helper for `@Ffi.Union` case-class layout
      * emission. The returned layout has `byteSize = max(elementByteSizes)` and `byteAlignment = max(elementByteAlignments)` -- matching
      * the C union ABI exactly.
      */
    def unionLayout(name: String, fields: MemoryLayout*): MemoryLayout =
        MemoryLayout.unionLayout(fields*).withName(name)
end Layouts
