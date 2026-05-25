package kyo

import kyo.internal.reflect.symbol.Flags as InternalFlags
import kyo.internal.reflect.tasty.TastyFormat

/** Tests for Flag bit layout and fromTastyModifierTag.
  *
  * Plan tests 1-6.
  */
class FlagsTest extends Test:

    // Test 1: Flag.Inline.bit is a power-of-two Long.
    "Flag.Inline.bit is a power-of-two Long (non-zero, bit-count 1)" in run {
        val b = Reflect.Flag.Inline.bit
        assert(b != 0L)
        assert((b & (b - 1)) == 0L) // power-of-two check
        succeed
    }

    // Test 2: Flags.empty does not contain Flag.Inline.
    "Flags.empty.contains(Flag.Inline) is false" in run {
        assert(!Reflect.Flags.empty.contains(Reflect.Flag.Inline))
        succeed
    }

    // Test 3: Flags constructed from multiple bits contains those flags.
    "Flags from Inline|Private bits contains Flag.Private" in run {
        val flags = new Reflect.Flags(Reflect.Flag.Inline.bit | Reflect.Flag.Private.bit)
        assert(flags.contains(Reflect.Flag.Private))
        assert(flags.contains(Reflect.Flag.Inline))
        succeed
    }

    // Test 4: fromTastyModifierTag maps PRIVATE (tag 6) to a Flags containing Flag.Private.
    "fromTastyModifierTag(PRIVATE=6) maps to Flags containing Flag.Private" in run {
        val flags = InternalFlags.fromTastyModifierTag(TastyFormat.PRIVATE)
        assert(flags.contains(Reflect.Flag.Private))
        succeed
    }

    // Test 5: fromTastyModifierTag maps INLINE (tag 17) to a Flags containing Flag.Inline.
    "fromTastyModifierTag(INLINE=17) maps to Flags containing Flag.Inline" in run {
        val flags = InternalFlags.fromTastyModifierTag(TastyFormat.INLINE)
        assert(flags.contains(Reflect.Flag.Inline))
        succeed
    }

    // Test 6: all ~42 Flag bit fields are distinct (no duplicates).
    "all Flag.bit values are distinct (no two flags share a bit)" in run {
        val allFlags = List(
            Reflect.Flag.Inline,
            Reflect.Flag.Private,
            Reflect.Flag.Protected,
            Reflect.Flag.Public,
            Reflect.Flag.Final,
            Reflect.Flag.Sealed,
            Reflect.Flag.Abstract,
            Reflect.Flag.Given,
            Reflect.Flag.Implicit,
            Reflect.Flag.Opaque,
            Reflect.Flag.Case,
            Reflect.Flag.Module,
            Reflect.Flag.Synthetic,
            Reflect.Flag.JavaDefined,
            Reflect.Flag.Enum,
            Reflect.Flag.JavaRecord,
            Reflect.Flag.Open,
            Reflect.Flag.ParamAccessor,
            Reflect.Flag.Lazy,
            Reflect.Flag.Override,
            Reflect.Flag.Mutable,
            Reflect.Flag.Erased,
            Reflect.Flag.Tracked,
            Reflect.Flag.Tailrec,
            Reflect.Flag.Infix,
            Reflect.Flag.Transparent,
            Reflect.Flag.Trait,
            Reflect.Flag.CaseAccessor,
            Reflect.Flag.FieldAccessor,
            Reflect.Flag.Macro,
            Reflect.Flag.InlineProxy,
            Reflect.Flag.Extension,
            Reflect.Flag.Exported,
            Reflect.Flag.CoVariant,
            Reflect.Flag.ContraVariant,
            Reflect.Flag.HasDefault,
            Reflect.Flag.Stable,
            Reflect.Flag.Local,
            Reflect.Flag.Artifact,
            Reflect.Flag.Invisible,
            Reflect.Flag.Into,
            Reflect.Flag.PARAMsetter,
            Reflect.Flag.PARAMalias,
            Reflect.Flag.Static,
            Reflect.Flag.Scala2
        )
        val bits = allFlags.map(_.bit)
        assert(bits.distinct.size == bits.size, s"Duplicate bit found in flags: ${allFlags.map(f => s"${f.name}=${f.bit}").mkString(", ")}")
        // Verify each bit is a power-of-two
        bits.foreach { b =>
            assert(b != 0L && (b & (b - 1)) == 0L, s"Bit $b is not a power of two")
        }
        succeed
    }

    // Test 7: ACC_ABSTRACT (0x0400) sets Flag.Abstract; not set by ACC_INTERFACE alone.
    "fromJvmAccessFlags: ACC_PUBLIC|ACC_ABSTRACT (0x0401) sets Flag.Abstract, not Flag.Trait" in run {
        // 0x0001 = ACC_PUBLIC, 0x0400 = ACC_ABSTRACT
        val flags = InternalFlags.fromJvmAccessFlags(0x0001 | 0x0400)
        assert(flags.contains(Reflect.Flag.Abstract), "Expected Flag.Abstract for ACC_ABSTRACT class")
        assert(!flags.contains(Reflect.Flag.Trait), "Expected Flag.Trait NOT set for non-interface abstract class")
        succeed
    }

    // Test 8: ACC_PUBLIC|ACC_INTERFACE|ACC_ABSTRACT (0x0601) sets both Flag.Trait and Flag.Abstract.
    "fromJvmAccessFlags: ACC_PUBLIC|ACC_INTERFACE|ACC_ABSTRACT (0x0601) sets both Flag.Trait and Flag.Abstract" in run {
        // Typical interface access_flags per JVMS: ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT
        // 0x0001 = ACC_PUBLIC, 0x0200 = ACC_INTERFACE, 0x0400 = ACC_ABSTRACT
        val flags = InternalFlags.fromJvmAccessFlags(0x0001 | 0x0200 | 0x0400)
        assert(flags.contains(Reflect.Flag.Trait), "Expected Flag.Trait for ACC_INTERFACE")
        assert(flags.contains(Reflect.Flag.Abstract), "Expected Flag.Abstract for ACC_ABSTRACT")
        succeed
    }

    // Test 9: ACC_STATIC (0x0008) sets Flag.Static, not Flag.JavaDefined.
    "fromJvmAccessFlags: ACC_PUBLIC|ACC_STATIC (0x0009) sets Flag.Static but not Flag.JavaDefined" in run {
        // Flag.JavaDefined is set unconditionally by ClassfileUnpickler, not here.
        // ACC_STATIC (0x0008) should map only to Flag.Static.
        val flags = InternalFlags.fromJvmAccessFlags(0x0001 | 0x0008)
        assert(flags.contains(Reflect.Flag.Static), "Expected Flag.Static for ACC_STATIC")
        assert(
            !flags.contains(Reflect.Flag.JavaDefined),
            "Flag.JavaDefined must NOT be set by fromJvmAccessFlags (set by ClassfileUnpickler)"
        )
        succeed
    }

end FlagsTest
