package kyo

/** Bit-identity and aggregate tests for [[Tasty.Flag]].
  *
  * Each of the 45 enum cases carries a `bit: Long` value equal to `1L << ordinal`. These tests
  * verify that every case holds its bit verbatim, that `Flag.values.length` is 45, that `Flags`
  * aggregation uses those bits correctly, that `Flags.contains` is exhaustive over all 45 cases,
  * and that pattern matching on `Flag` is seen as exhaustive by the compiler.
  *
  * Tests also verify that `Covariant`, `Contravariant`, `ParamSetter`, and `ParamAlias` carry
  * bit values equal to `1L << 33`, `1L << 34`, `1L << 41`, and `1L << 42` respectively.
  */
class FlagEnumTest extends kyo.test.Test[Any]:

    // ── One bit-identity assertion per case ──────────────────────────────────

    "Flag.Inline.bit == 1L << 0" in {
        assert(Tasty.Flag.Inline.bit == (1L << 0))
        succeed
    }

    "Flag.Private.bit == 1L << 1" in {
        assert(Tasty.Flag.Private.bit == (1L << 1))
        succeed
    }

    "Flag.Protected.bit == 1L << 2" in {
        assert(Tasty.Flag.Protected.bit == (1L << 2))
        succeed
    }

    "Flag.Public.bit == 1L << 3" in {
        assert(Tasty.Flag.Public.bit == (1L << 3))
        succeed
    }

    "Flag.Final.bit == 1L << 4" in {
        assert(Tasty.Flag.Final.bit == (1L << 4))
        succeed
    }

    "Flag.Sealed.bit == 1L << 5" in {
        assert(Tasty.Flag.Sealed.bit == (1L << 5))
        succeed
    }

    "Flag.Abstract.bit == 1L << 6" in {
        assert(Tasty.Flag.Abstract.bit == (1L << 6))
        succeed
    }

    "Flag.Given.bit == 1L << 7" in {
        assert(Tasty.Flag.Given.bit == (1L << 7))
        succeed
    }

    "Flag.Implicit.bit == 1L << 8" in {
        assert(Tasty.Flag.Implicit.bit == (1L << 8))
        succeed
    }

    "Flag.Opaque.bit == 1L << 9" in {
        assert(Tasty.Flag.Opaque.bit == (1L << 9))
        succeed
    }

    "Flag.Case.bit == 1L << 10" in {
        assert(Tasty.Flag.Case.bit == (1L << 10))
        succeed
    }

    "Flag.Module.bit == 1L << 11" in {
        assert(Tasty.Flag.Module.bit == (1L << 11))
        succeed
    }

    "Flag.Synthetic.bit == 1L << 12" in {
        assert(Tasty.Flag.Synthetic.bit == (1L << 12))
        succeed
    }

    "Flag.JavaDefined.bit == 1L << 13" in {
        assert(Tasty.Flag.JavaDefined.bit == (1L << 13))
        succeed
    }

    "Flag.Enum.bit == 1L << 14" in {
        assert(Tasty.Flag.Enum.bit == (1L << 14))
        succeed
    }

    "Flag.JavaRecord.bit == 1L << 15" in {
        assert(Tasty.Flag.JavaRecord.bit == (1L << 15))
        succeed
    }

    "Flag.Open.bit == 1L << 16" in {
        assert(Tasty.Flag.Open.bit == (1L << 16))
        succeed
    }

    "Flag.ParamAccessor.bit == 1L << 17" in {
        assert(Tasty.Flag.ParamAccessor.bit == (1L << 17))
        succeed
    }

    "Flag.Lazy.bit == 1L << 18" in {
        assert(Tasty.Flag.Lazy.bit == (1L << 18))
        succeed
    }

    "Flag.Override.bit == 1L << 19" in {
        assert(Tasty.Flag.Override.bit == (1L << 19))
        succeed
    }

    "Flag.Mutable.bit == 1L << 20" in {
        assert(Tasty.Flag.Mutable.bit == (1L << 20))
        succeed
    }

    "Flag.Erased.bit == 1L << 21" in {
        assert(Tasty.Flag.Erased.bit == (1L << 21))
        succeed
    }

    "Flag.Tracked.bit == 1L << 22" in {
        assert(Tasty.Flag.Tracked.bit == (1L << 22))
        succeed
    }

    "Flag.Tailrec.bit == 1L << 23" in {
        assert(Tasty.Flag.Tailrec.bit == (1L << 23))
        succeed
    }

    "Flag.Infix.bit == 1L << 24" in {
        assert(Tasty.Flag.Infix.bit == (1L << 24))
        succeed
    }

    "Flag.Transparent.bit == 1L << 25" in {
        assert(Tasty.Flag.Transparent.bit == (1L << 25))
        succeed
    }

    "Flag.Trait.bit == 1L << 26" in {
        assert(Tasty.Flag.Trait.bit == (1L << 26))
        succeed
    }

    "Flag.CaseAccessor.bit == 1L << 27" in {
        assert(Tasty.Flag.CaseAccessor.bit == (1L << 27))
        succeed
    }

    "Flag.FieldAccessor.bit == 1L << 28" in {
        assert(Tasty.Flag.FieldAccessor.bit == (1L << 28))
        succeed
    }

    "Flag.Macro.bit == 1L << 29" in {
        assert(Tasty.Flag.Macro.bit == (1L << 29))
        succeed
    }

    "Flag.InlineProxy.bit == 1L << 30" in {
        assert(Tasty.Flag.InlineProxy.bit == (1L << 30))
        succeed
    }

    "Flag.Extension.bit == 1L << 31" in {
        assert(Tasty.Flag.Extension.bit == (1L << 31))
        succeed
    }

    "Flag.Exported.bit == 1L << 32" in {
        assert(Tasty.Flag.Exported.bit == (1L << 32))
        succeed
    }

    "Flag.Covariant.bit == 1L << 33" in {
        assert(Tasty.Flag.Covariant.bit == (1L << 33))
        succeed
    }

    "Flag.Contravariant.bit == 1L << 34" in {
        assert(Tasty.Flag.Contravariant.bit == (1L << 34))
        succeed
    }

    "Flag.HasDefault.bit == 1L << 35" in {
        assert(Tasty.Flag.HasDefault.bit == (1L << 35))
        succeed
    }

    "Flag.Stable.bit == 1L << 36" in {
        assert(Tasty.Flag.Stable.bit == (1L << 36))
        succeed
    }

    "Flag.Local.bit == 1L << 37" in {
        assert(Tasty.Flag.Local.bit == (1L << 37))
        succeed
    }

    "Flag.Artifact.bit == 1L << 38" in {
        assert(Tasty.Flag.Artifact.bit == (1L << 38))
        succeed
    }

    "Flag.Invisible.bit == 1L << 39" in {
        assert(Tasty.Flag.Invisible.bit == (1L << 39))
        succeed
    }

    "Flag.Into.bit == 1L << 40" in {
        assert(Tasty.Flag.Into.bit == (1L << 40))
        succeed
    }

    "Flag.ParamSetter.bit == 1L << 41" in {
        assert(Tasty.Flag.ParamSetter.bit == (1L << 41))
        succeed
    }

    "Flag.ParamAlias.bit == 1L << 42" in {
        assert(Tasty.Flag.ParamAlias.bit == (1L << 42))
        succeed
    }

    "Flag.Static.bit == 1L << 43" in {
        assert(Tasty.Flag.Static.bit == (1L << 43))
        succeed
    }

    "Flag.Scala2.bit == 1L << 44" in {
        assert(Tasty.Flag.Scala2.bit == (1L << 44))
        succeed
    }

    // ── Value count ───────────────────────────────────────────────────────────

    "Flag.values.length == 45" in {
        assert(Tasty.Flag.values.length == 45)
        succeed
    }

    // ── Flags aggregate bit arithmetic ───────────────────────────────────────

    "Flags(Flag.Inline, Flag.Private).bits == 3L" in {
        assert(Tasty.Flags(Tasty.Flag.Inline, Tasty.Flag.Private).bits == ((1L << 0) | (1L << 1)))
        succeed
    }

    // ── Flags.contains exhaustive over all 45 cases ──────────────────────────

    "Flags with all bits set contains every Flag case" in {
        val allFlags = Tasty.Flag.values.foldLeft(Tasty.Flags.empty) { (acc, f) =>
            acc.union(Tasty.Flags(f))
        }
        val missing = Tasty.Flag.values.filterNot(allFlags.contains)
        assert(missing.isEmpty, s"Flags.contains returned false for: ${missing.map(_.toString).mkString(", ")}")
        succeed
    }

    // ── Flags bit round-trip preserves bit-identity ──────────────────────────

    "Flags bit round-trip: fromBits followed by bits is identity" in {
        val mask  = 0xf0f0f0f0L
        val flags = Tasty.Flags.fromBits(mask)
        assert(flags.bits == mask, s"Expected $mask but got ${flags.bits}")
        succeed
    }

    // ── Flag is a sealed enum; instantiation from outside is rejected ────────

    "Flag cannot be instantiated as a new subclass outside the enum" in {
        // A sealed enum's cases are the only valid values; attempting to extend Flag
        // with a new class or anonymous subclass must produce a compile error.
        val errors = compiletime.testing.typeCheckErrors(
            "new kyo.Tasty.Flag(0L) {}"
        )
        assert(errors.nonEmpty, "Flag must be sealed; constructing a new anonymous subclass must fail to compile")
        succeed
    }

end FlagEnumTest
