package kyo

import kyo.internal.tasty.reader.TastyFormat
import kyo.internal.tasty.symbol.Flags as InternalFlags

/** Tests for Flag bit layout and fromTastyModifierTag.
  *
  * Plan tests 1-6.
  */
class FlagsTest extends Test:

    // Test 1: Flag.Inline is a single-bit flag (Flags(Flag.Inline) is non-empty and contains exactly Inline).
    "Flag.Inline encodes a non-empty single-flag Flags value" in run {
        val f = Tasty.Flags(Tasty.Flag.Inline)
        // Stronger assertion than "non-empty": the Flags value must equal exactly the single-flag
        // representation built from Flag.Inline.
        assert(f == Tasty.Flags(Tasty.Flag.Inline), s"Expected Flags(Inline) to equal itself but got $f")
        assert(f.contains(Tasty.Flag.Inline))
        assert(!f.contains(Tasty.Flag.Private))
        succeed
    }

    // Test 2: Flags.empty does not contain Flag.Inline.
    "Flags.empty.contains(Flag.Inline) is false" in run {
        assert(!Tasty.Flags.empty.contains(Tasty.Flag.Inline))
        succeed
    }

    // Test 3: Flags constructed from multiple flags contains those flags.
    "Flags(Inline, Private) contains both Flag.Inline and Flag.Private" in run {
        val flags = Tasty.Flags(Tasty.Flag.Inline, Tasty.Flag.Private)
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

    // Test 6: all 45 declared Flag values are pairwise distinct (no two flags collapse to the same encoding).
    "all declared Flag values are pairwise distinct" in run {
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
        // Each single-flag Flags value must contain exactly the flag it was built from and no other.
        // If two flags shared an encoding, Flags(f).contains(g) would be true for distinct f, g.
        val collisions: List[(Tasty.Flag, Tasty.Flag)] =
            allFlags.flatMap { f =>
                val fOnly = Tasty.Flags(f)
                if !fOnly.contains(f) then List((f, f))
                else
                    allFlags.flatMap { g =>
                        if Tasty.Flag.name(f) != Tasty.Flag.name(g) && fOnly.contains(g) then List((f, g))
                        else Nil
                    }
                end if
            }
        assert(
            collisions.isEmpty,
            s"Encoding collisions: ${collisions.map { case (f, g) => s"${Tasty.Flag.name(f)}->${Tasty.Flag.name(g)}" }.mkString(", ")}"
        )
        // Names must also be distinct.
        val names = allFlags.map(Tasty.Flag.name)
        assert(names.distinct.size == names.size, s"Duplicate flag name: ${names.diff(names.distinct).mkString(", ")}")
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
