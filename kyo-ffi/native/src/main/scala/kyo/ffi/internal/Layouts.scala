package kyo.ffi.internal

/** Native-specific layout helpers for codegen.
  *
  * Primitive [[kyo.internal.UnsafeLayout]] instances are now provided by kyo-data. This object retains only the union layout descriptor and
  * helper used by the FFI code generator.
  */
private[ffi] object Layouts:

    /** Describes a union layout -- a name, byte size (max of field sizes), and byte alignment (max of field alignments). Scala Native has
      * no built-in union layout; generated impl code emits the union as a single-field carrier struct (`CStruct1[<widest primitive>]`)
      * whose sizeof matches [[byteSize]] and alignof matches [[byteAlignment]].
      */
    final case class UnionLayoutDesc(name: String, byteSize: Long, byteAlignment: Long) derives CanEqual

    /** Construct a [[UnionLayoutDesc]] for an `@Ffi.Union` case class from its field sizes / alignments. Helper used by the struct-ABI
      * self-check to surface a human-readable union descriptor in error paths; the generated extern-struct type carrying the union bytes is
      * independent of this descriptor.
      */
    def unionLayout(name: String, fieldSizes: Seq[Long], fieldAlignments: Seq[Long]): UnionLayoutDesc =
        val maxSize  = if fieldSizes.isEmpty then 0L else fieldSizes.max
        val maxAlign = if fieldAlignments.isEmpty then 1L else fieldAlignments.max
        val total    = ((maxSize + maxAlign - 1L) / maxAlign) * maxAlign
        UnionLayoutDesc(name, total, maxAlign)
    end unionLayout
end Layouts
