package kyo.ffi

import kyo.AllowUnsafe
import kyo.internal.UnsafeBuffer
import kyo.internal.UnsafeLayout
import scala.compiletime.*
import scala.deriving.Mirror

/** Derives an [[kyo.internal.UnsafeLayout]] for an FFI struct case class so it can back a `Buffer[A]` of structs.
  *
  * A binding may take a `Buffer[StructType]` (for example `epoll_wait` filling an array of `struct epoll_event`, or `kevent` reading a
  * changelist). The codegen marshals such a buffer as a raw pointer, but user code still needs to allocate the buffer and read or write its
  * elements field by field. The primitive `UnsafeLayout` givens cannot do that for a case class, so this object derives one.
  *
  * The derived layout mirrors the same size and alignment model the codegen uses for struct parameters and the ABI check (natural alignment:
  * declaration order, each field aligned to its own size, total size rounded up to the max field alignment). A packed variant collapses every
  * alignment to 1, matching `Ffi.Config.packedStructs`. Use [[derived]] for a naturally-aligned struct and [[derivedPacked]] for a packed one;
  * the choice must match the struct's `packedStructs` membership so the in-buffer layout agrees with the C ABI.
  *
  * Supported field types are the primitives `Byte`, `Short`, `Int`, `Long`, `Float`, `Double`, and `Boolean` (the same set the codegen treats
  * as flat scalar struct fields). Nested struct, `String`, `Buffer`, and `Handle` fields are rejected at compile time: no FFI binding in this
  * codebase needs a `Buffer` of a non-flat struct, and supporting them would duplicate the codegen's recursive layout. Add it here if a
  * binding ever requires it.
  */
object StructLayout:

    /** Derive a naturally-aligned [[kyo.internal.UnsafeLayout]] for the struct case class `A`. */
    inline def derived[A](using m: Mirror.ProductOf[A]): UnsafeLayout[A] =
        make[A](packed = false)

    /** Derive a packed (alignment-1) [[kyo.internal.UnsafeLayout]] for the struct case class `A`. Use when `A` is listed in the binding's
      * `Ffi.Config.packedStructs`.
      */
    inline def derivedPacked[A](using m: Mirror.ProductOf[A]): UnsafeLayout[A] =
        make[A](packed = true)

    private[kyo] inline def make[A](inline packed: Boolean)(using m: Mirror.ProductOf[A]): UnsafeLayout[A] =
        val accessors = fieldAccessors[m.MirroredElemTypes]
        val offsets   = computeOffsets(accessors.map(_.size), packed)
        val totalSize = roundedSize(accessors.map(_.size), accessors.map(_.align), offsets, packed)
        val maxAlign  = if packed then 1 else accessors.map(_.align).foldLeft(1)(math.max)
        new StructUnsafeLayout[A](m, accessors, offsets, totalSize, maxAlign)
    end make

    /** Per-field primitive marshalling: its byte size, natural alignment, and reader/writer against an [[UnsafeBuffer]] at an offset. */
    final private[kyo] case class FieldAccessor(
        size: Int,
        align: Int,
        read: (UnsafeBuffer, Long) => Any,
        write: (UnsafeBuffer, Long, Any) => Unit
    )

    private[kyo] inline def fieldAccessors[Elems <: Tuple]: List[FieldAccessor] =
        inline erasedValue[Elems] match
            case _: EmptyTuple => Nil
            case _: (head *: tail) =>
                fieldAccessor[head] :: fieldAccessors[tail]

    private[kyo] inline def fieldAccessor[T]: FieldAccessor =
        inline erasedValue[T] match
            case _: Byte =>
                FieldAccessor(
                    1,
                    1,
                    (b, o) =>
                        // Unsafe: raw struct-field read at the computed offset; offset is layout-derived and in-bounds by construction.
                        import AllowUnsafe.embrace.danger;
                        b.getByte(o)
                    ,
                    (b, o, v) =>
                        // Unsafe: raw struct-field write at the computed offset; offset is layout-derived and in-bounds by construction.
                        import AllowUnsafe.embrace.danger; b.setByte(o, v.asInstanceOf[Byte])
                )
            case _: Short =>
                FieldAccessor(
                    2,
                    2,
                    (b, o) =>
                        // Unsafe: raw struct-field read at the computed offset; offset is layout-derived and in-bounds by construction.
                        import AllowUnsafe.embrace.danger;
                        b.getShort(o)
                    ,
                    (b, o, v) =>
                        // Unsafe: raw struct-field write at the computed offset; offset is layout-derived and in-bounds by construction.
                        import AllowUnsafe.embrace.danger; b.setShort(o, v.asInstanceOf[Short])
                )
            case _: Int =>
                FieldAccessor(
                    4,
                    4,
                    (b, o) =>
                        // Unsafe: raw struct-field read at the computed offset; offset is layout-derived and in-bounds by construction.
                        import AllowUnsafe.embrace.danger;
                        b.getInt(o)
                    ,
                    (b, o, v) =>
                        // Unsafe: raw struct-field write at the computed offset; offset is layout-derived and in-bounds by construction.
                        import AllowUnsafe.embrace.danger; b.setInt(o, v.asInstanceOf[Int])
                )
            case _: Long =>
                FieldAccessor(
                    8,
                    8,
                    (b, o) =>
                        // Unsafe: raw struct-field read at the computed offset; offset is layout-derived and in-bounds by construction.
                        import AllowUnsafe.embrace.danger;
                        b.getLong(o)
                    ,
                    (b, o, v) =>
                        // Unsafe: raw struct-field write at the computed offset; offset is layout-derived and in-bounds by construction.
                        import AllowUnsafe.embrace.danger; b.setLong(o, v.asInstanceOf[Long])
                )
            case _: Float =>
                FieldAccessor(
                    4,
                    4,
                    (b, o) =>
                        // Unsafe: raw struct-field read at the computed offset; offset is layout-derived and in-bounds by construction.
                        import AllowUnsafe.embrace.danger;
                        b.getFloat(o)
                    ,
                    (b, o, v) =>
                        // Unsafe: raw struct-field write at the computed offset; offset is layout-derived and in-bounds by construction.
                        import AllowUnsafe.embrace.danger; b.setFloat(o, v.asInstanceOf[Float])
                )
            case _: Double =>
                FieldAccessor(
                    8,
                    8,
                    (b, o) =>
                        // Unsafe: raw struct-field read at the computed offset; offset is layout-derived and in-bounds by construction.
                        import AllowUnsafe.embrace.danger;
                        b.getDouble(o)
                    ,
                    (b, o, v) =>
                        // Unsafe: raw struct-field write at the computed offset; offset is layout-derived and in-bounds by construction.
                        import AllowUnsafe.embrace.danger; b.setDouble(o, v.asInstanceOf[Double])
                )
            case _: Boolean =>
                // Booleans marshal as a 4-byte int (0/1), matching the codegen's BooleanT size model.
                FieldAccessor(
                    4,
                    4,
                    (b, o) =>
                        // Unsafe: raw struct-field read at the computed offset; booleans marshal as a 4-byte int (0/1).
                        import AllowUnsafe.embrace.danger;
                        b.getInt(o) != 0
                    ,
                    (b, o, v) =>
                        // Unsafe: raw struct-field write at the computed offset; booleans marshal as a 4-byte int (0/1).
                        import AllowUnsafe.embrace.danger; b.setInt(o, if v.asInstanceOf[Boolean] then 1 else 0)
                )
            case _ =>
                error(
                    "StructLayout supports only flat structs of Byte/Short/Int/Long/Float/Double/Boolean fields. " +
                        "Nested struct, String, Buffer, and Handle fields are not supported for Buffer[Struct] element layout."
                )

    private[kyo] def computeOffsets(sizes: List[Int], packed: Boolean): List[Long] =
        val builder = List.newBuilder[Long]
        var off     = 0L
        sizes.foreach { s =>
            val a       = if packed then 1 else s
            val aligned = (off + a - 1L) & -a.toLong
            builder += aligned
            off = aligned + s
        }
        builder.result()
    end computeOffsets

    private[kyo] def roundedSize(sizes: List[Int], aligns: List[Int], offsets: List[Long], packed: Boolean): Int =
        if sizes.isEmpty then 0
        else
            val lastEnd  = offsets.last + sizes.last
            val maxAlign = if packed then 1L else aligns.foldLeft(1)(math.max).toLong
            (((lastEnd + maxAlign - 1L) & -maxAlign)).toInt
    end roundedSize

    final private[kyo] class StructUnsafeLayout[A](
        m: Mirror.ProductOf[A],
        accessors: List[FieldAccessor],
        offsets: List[Long],
        totalSize: Int,
        maxAlign: Int
    ) extends UnsafeLayout[A]:
        private val accessorArr = accessors.toArray
        private val offsetArr   = offsets.toArray

        def size: Int      = totalSize
        def alignment: Int = maxAlign

        def read(buf: UnsafeBuffer, offset: Long)(using AllowUnsafe): A =
            val values = new Array[Any](accessorArr.length)
            var i      = 0
            while i < accessorArr.length do
                values(i) = accessorArr(i).read(buf, offset + offsetArr(i))
                i += 1
            m.fromProduct(Tuple.fromArray(values))
        end read

        def write(buf: UnsafeBuffer, offset: Long, value: A)(using AllowUnsafe): Unit =
            val product = value.asInstanceOf[Product]
            var i       = 0
            while i < accessorArr.length do
                accessorArr(i).write(buf, offset + offsetArr(i), product.productElement(i))
                i += 1
        end write
    end StructUnsafeLayout

end StructLayout
