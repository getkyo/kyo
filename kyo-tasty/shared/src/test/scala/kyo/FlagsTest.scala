package kyo

import kyo.internal.tasty.reader.TastyFormat
import kyo.internal.tasty.symbol.Flags as InternalFlags

/** Tests for Flag bit layout and fromTastyModifierTag.
  *
  * Plan tests 1-6.
  */
class FlagsTest extends Test:

    // Test 1: Flag.Inline.bit is a power-of-two Long.
    "Flag.Inline.bit is a power-of-two Long (non-zero, bit-count 1)" in run {
        val b = Tasty.Flag.Inline.bit
        assert(b != 0L)
        assert((b & (b - 1)) == 0L) // power-of-two check
        succeed
    }

    // Test 2: Flags.empty does not contain Flag.Inline.
    "Flags.empty.contains(Flag.Inline) is false" in run {
        assert(!Tasty.Flags.empty.contains(Tasty.Flag.Inline))
        succeed
    }

    // Test 3: Flags constructed from multiple bits contains those flags.
    "Flags from Inline|Private bits contains Flag.Private" in run {
        val flags = new Tasty.Flags(Tasty.Flag.Inline.bit | Tasty.Flag.Private.bit)
        assert(flags.contains(Tasty.Flag.Private))
        assert(flags.contains(Tasty.Flag.Inline))
        succeed
    }

    // Test 4: fromTastyModifierTag maps PRIVATE (tag 6) to a Flags containing Flag.Private.
    "fromTastyModifierTag(PRIVATE=6) maps to Flags containing Flag.Private" in run {
        val flags = InternalFlags.fromTastyModifierTag(TastyFormat.PRIVATE)
        assert(flags.contains(Tasty.Flag.Private))
        succeed
    }

    // Test 5: fromTastyModifierTag maps INLINE (tag 17) to a Flags containing Flag.Inline.
    "fromTastyModifierTag(INLINE=17) maps to Flags containing Flag.Inline" in run {
        val flags = InternalFlags.fromTastyModifierTag(TastyFormat.INLINE)
        assert(flags.contains(Tasty.Flag.Inline))
        succeed
    }

    // Test 6: all ~42 Flag bit fields are distinct (no duplicates).
    "all Flag.bit values are distinct (no two flags share a bit)" in run {
        val allFlags = List(
            Tasty.Flag.Inline,
            Tasty.Flag.Private,
            Tasty.Flag.Protected,
            Tasty.Flag.Public,
            Tasty.Flag.Final,
            Tasty.Flag.Sealed,
            Tasty.Flag.Abstract,
            Tasty.Flag.Given,
            Tasty.Flag.Implicit,
            Tasty.Flag.Opaque,
            Tasty.Flag.Case,
            Tasty.Flag.Module,
            Tasty.Flag.Synthetic,
            Tasty.Flag.JavaDefined,
            Tasty.Flag.Enum,
            Tasty.Flag.JavaRecord,
            Tasty.Flag.Open,
            Tasty.Flag.ParamAccessor,
            Tasty.Flag.Lazy,
            Tasty.Flag.Override,
            Tasty.Flag.Mutable,
            Tasty.Flag.Erased,
            Tasty.Flag.Tracked,
            Tasty.Flag.Tailrec,
            Tasty.Flag.Infix,
            Tasty.Flag.Transparent,
            Tasty.Flag.Trait,
            Tasty.Flag.CaseAccessor,
            Tasty.Flag.FieldAccessor,
            Tasty.Flag.Macro,
            Tasty.Flag.InlineProxy,
            Tasty.Flag.Extension,
            Tasty.Flag.Exported,
            Tasty.Flag.CoVariant,
            Tasty.Flag.ContraVariant,
            Tasty.Flag.HasDefault,
            Tasty.Flag.Stable,
            Tasty.Flag.Local,
            Tasty.Flag.Artifact,
            Tasty.Flag.Invisible,
            Tasty.Flag.Into,
            Tasty.Flag.PARAMsetter,
            Tasty.Flag.PARAMalias,
            Tasty.Flag.Static,
            Tasty.Flag.Scala2
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
        assert(flags.contains(Tasty.Flag.Abstract), "Expected Flag.Abstract for ACC_ABSTRACT class")
        assert(!flags.contains(Tasty.Flag.Trait), "Expected Flag.Trait NOT set for non-interface abstract class")
        succeed
    }

    // Test 8: ACC_PUBLIC|ACC_INTERFACE|ACC_ABSTRACT (0x0601) sets both Flag.Trait and Flag.Abstract.
    "fromJvmAccessFlags: ACC_PUBLIC|ACC_INTERFACE|ACC_ABSTRACT (0x0601) sets both Flag.Trait and Flag.Abstract" in run {
        // Typical interface access_flags per JVMS: ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT
        // 0x0001 = ACC_PUBLIC, 0x0200 = ACC_INTERFACE, 0x0400 = ACC_ABSTRACT
        val flags = InternalFlags.fromJvmAccessFlags(0x0001 | 0x0200 | 0x0400)
        assert(flags.contains(Tasty.Flag.Trait), "Expected Flag.Trait for ACC_INTERFACE")
        assert(flags.contains(Tasty.Flag.Abstract), "Expected Flag.Abstract for ACC_ABSTRACT")
        succeed
    }

    // Test 9: ACC_STATIC (0x0008) sets Flag.Static, not Flag.JavaDefined.
    "fromJvmAccessFlags: ACC_PUBLIC|ACC_STATIC (0x0009) sets Flag.Static but not Flag.JavaDefined" in run {
        // Flag.JavaDefined is set unconditionally by ClassfileUnpickler, not here.
        // ACC_STATIC (0x0008) should map only to Flag.Static.
        val flags = InternalFlags.fromJvmAccessFlags(0x0001 | 0x0008)
        assert(flags.contains(Tasty.Flag.Static), "Expected Flag.Static for ACC_STATIC")
        assert(
            !flags.contains(Tasty.Flag.JavaDefined),
            "Flag.JavaDefined must NOT be set by fromJvmAccessFlags (set by ClassfileUnpickler)"
        )
        succeed
    }

end FlagsTest
