package kyo.ffi.it

import kyo.ffi.Ffi

/** Cross-platform union-feature spec.
  *
  * Verifies that Scala 3 union types (`Int | Float`) marshal correctly across all three platforms by writing an int value into the union on
  * the Scala side, then reading both variants back from the C side. The key assertion:
  *
  *   - Int written = `0x42280000`
  *   - Float read via bit reinterpretation = `42.0f` (since `0x42280000` is the IEEE-754 encoding of 42.0f)
  *
  * If the reinterpretation works on every platform, the union's shared-bytes semantics are confirmed: the same 4 bytes can be seen as
  * either an int or a float and the bit pattern is preserved across the FFI boundary.
  */
class ItUnionsTest extends ItTestBase:

    // IEEE-754 single-precision encoding of 42.0f is 0x42280000. Written as Int, read as Float should yield 42.0f.
    private val IntBitsOf42f: Int = 0x42280000

    "ItUnionsTest" - {
        "reads back the int variant after writing an Int" in {
            val b = Ffi.load[ItUnionsBindings]
            assert(b.kyoItUnionReadInt(IntBitsOf42f) == IntBitsOf42f)
        }

        "reads the float variant as a bit reinterpretation of the same 4 bytes" in {
            val b     = Ffi.load[ItUnionsBindings]
            val asFlt = b.kyoItUnionReadFloat(IntBitsOf42f)
            assert(asFlt == 42.0f)
        }

        "write-as-int/read-as-float roundtrip preserves bit pattern for 0 and 1.0f encoding" in {
            val b = Ffi.load[ItUnionsBindings]

            // 0 bits → 0.0f
            assert(b.kyoItUnionReadInt(0) == 0)
            assert(b.kyoItUnionReadFloat(0) == 0.0f)

            // 0x3F800000 → 1.0f (IEEE-754)
            val oneF = 0x3f800000
            assert(b.kyoItUnionReadInt(oneF) == oneF)
            assert(b.kyoItUnionReadFloat(oneF) == 1.0f)
        }

        // #248: a struct (case class) variant in a union parameter. JVM and Native write the struct's
        // fields into the union scratch; before the fix JS threw FfiUnsupported at runtime. All three
        // backends must now write the struct variant's fields into the union storage identically.
        "writes a struct variant's fields into a union parameter (Int | UnionPoint)" in {
            val b = Ffi.load[ItUnionsBindings]
            assert(b.kyoItUnionStructX(UnionPoint(10, 20)) == 10)
            assert(b.kyoItUnionStructY(UnionPoint(10, 20)) == 20)
        }

        "still marshals the int variant of a union that also has a struct variant" in {
            val b = Ffi.load[ItUnionsBindings]
            assert(b.kyoItUnionStructI(7) == 7)
        }

        // #248: a struct variant with a POINTER field (Ffi.Handle) inside a union parameter. JVM and Native write the
        // handle's address into the union storage; the same must hold on JS (which delegates struct serialization to
        // koffi's encoder). The handle points at a kyo_it_create_handle allocation, so the C side dereferences it to
        // recover the stored value. This is the full-consistency proof: pointer-bearing struct variants work on every
        // backend, with no platform-specific rejection.
        "writes a pointer-bearing struct variant (handle field) into a union parameter on every backend" in {
            val ops = Ffi.load[ItOpaqueBindings]
            val b   = Ffi.load[ItUnionsBindings]
            val h   = ops.kyo_it_create_handle(99)
            try
                assert(b.kyoItUnionHolderTag(HandleHolder(7, h)) == 7)
                assert(b.kyoItUnionHolderValue(HandleHolder(7, h)) == 99)
            finally ops.kyo_it_destroy_handle(h)
            end try
        }

        // #251: a Long variant in a union parameter must write its 8 raw bytes, so the C long view reads back the
        // exact value. On JS the Long variant previously went through setFloat64(longValue.toDouble), writing the
        // double bit-pattern instead of the integer bytes, so even small values came back as garbage and values
        // above 2^53 also lost precision. JVM and Native always wrote the raw long. All three must now agree.
        "writes a Long variant's exact 8 bytes into a union parameter (Long | Double)" in {
            val b = Ffi.load[ItUnionsBindings]
            assert(b.kyoItUnionLongView(42L) == 42L)
            assert(b.kyoItUnionLongView(0L) == 0L)
            assert(b.kyoItUnionLongView(-1L) == -1L)
            assert(b.kyoItUnionLongView((1L << 53) + 1L) == ((1L << 53) + 1L))
            assert(b.kyoItUnionLongView(Long.MaxValue) == Long.MaxValue)
            assert(b.kyoItUnionLongView(Long.MinValue) == Long.MinValue)
        }

        "still marshals the Double variant of the Long | Double union" in {
            val b = Ffi.load[ItUnionsBindings]
            assert(b.kyoItUnionDoubleView(3.5d) == 3.5d)
            assert(b.kyoItUnionDoubleView(0.0d) == 0.0d)
        }

        // #253: a struct PARAMETER with a union FIELD (the documented Event(tag, data: Int | Float) shape). JVM and
        // Native marshal the union field into the struct; on JS this previously failed at codegen because koffiType
        // had no UnionT case (struct registration threw). All three backends must now marshal the union field, with
        // the active variant written into the struct's union storage.
        "marshals a struct parameter that has a union field (Tagged) on every backend" in {
            val b = Ffi.load[ItUnionsBindings]
            assert(b.kyoItTaggedTag(Tagged(7, 42)) == 7)
            assert(b.kyoItTaggedInt(Tagged(7, 42)) == 42)
            assert(b.kyoItTaggedFloat(Tagged(3, 1.5f)) == 1.5f)
        }

        // #253: a struct whose union field has a STRUCT variant (Int | UnionPoint). The registered koffi union
        // references another registered struct type, so this verifies that combination resolves and marshals on
        // every backend, for both the int variant and the struct (point) variant.
        "marshals a struct param whose union field has a struct variant (Int | UnionPoint) on every backend" in {
            val b = Ffi.load[ItUnionsBindings]
            assert(b.kyoItBoxedLabel(Boxed(5, 42)) == 5)
            assert(b.kyoItBoxedInt(Boxed(5, 42)) == 42)
            assert(b.kyoItBoxedX(Boxed(5, UnionPoint(10, 20))) == 10)
            assert(b.kyoItBoxedY(Boxed(5, UnionPoint(10, 20))) == 20)
        }
    }
end ItUnionsTest
