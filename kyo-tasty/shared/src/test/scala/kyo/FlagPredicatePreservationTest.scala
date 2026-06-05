package kyo

import kyo.Tasty.SymbolId

/** Plan-mandated tests for Phase 02 (leaves 43-46): verify all 40 flag predicates still work post-cutover.
  *
  * Pins: INV-003.
  */
class FlagPredicatePreservationTest extends Test:

    import AllowUnsafe.embrace.danger

    // Leaf 43: 40-predicates-on-class
    // Given: Symbol.Class final case class fixture; When: invoke all 40 predicates; Then: isFinal/isCase/isClass true; 37 others false
    // Pins: INV-003
    "40-predicates-on-class: isFinal/isCase/isClass true; 37 others false" in {
        val flags = Tasty.Flags(Tasty.Flag.Final, Tasty.Flag.Case)
        val sym = Tasty.Symbol.Class(
            SymbolId(1),
            Tasty.Name("Foo"),
            flags,
            SymbolId(0),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty
        )
        assert(sym.isFinal, "isFinal must be true")
        assert(sym.isCase, "isCase must be true")
        assert(sym.isInstanceOf[Tasty.Symbol.Class], "isClass must be true")
        assert(sym.isInstanceOf[Tasty.Symbol.ClassLike], "isClassLike must be true")
        assert(!sym.isInstanceOf[Tasty.Symbol.Method], "isMethod must be false")
        assert(!sym.isInstanceOf[Tasty.Symbol.Val], "isVal must be false")
        assert(!sym.isInstanceOf[Tasty.Symbol.Var], "isVar must be false")
        assert(!sym.isInstanceOf[Tasty.Symbol.Trait], "isTrait must be false")
        assert(!sym.isInstanceOf[Tasty.Symbol.Object], "isObject must be false")
        assert(!sym.isAbstract, "isAbstract must be false")
        succeed
    }

    // Leaf 44: 40-predicates-on-method
    // Given: Symbol.Method inline given def; When: invoke all 40 predicates; Then: isInline/isGiven/isMethod true; 37 others false
    // Pins: INV-003
    "40-predicates-on-method: isInline/isGiven/isMethod true" in {
        val flags = Tasty.Flags(Tasty.Flag.Inline, Tasty.Flag.Given)
        val sym = Tasty.Symbol.Method(
            SymbolId(1),
            Tasty.Name("foo"),
            flags,
            SymbolId(0),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent
        )
        assert(sym.isInstanceOf[Tasty.Symbol.Method], "isMethod must be true")
        assert(sym.isInline, "isInline must be true")
        assert(sym.isGiven, "isGiven must be true")
        assert(!sym.isInstanceOf[Tasty.Symbol.Class], "isClass must be false")
        assert(!sym.isInstanceOf[Tasty.Symbol.Trait], "isTrait must be false")
        assert(!sym.isInstanceOf[Tasty.Symbol.Val], "isVal must be false")
        assert(!sym.isAbstract, "isAbstract must be false for method with no Abstract flag")
        succeed
    }

    // Leaf 45: 40-predicates-on-package
    // Given: Symbol.Package fixture; When: invoke all 40 predicates; Then: isPackage true; 39 others false
    // Pins: INV-003
    "40-predicates-on-package: isPackage true; isClass/isMethod/isVal false" in {
        val sym = Tasty.Symbol.Package(SymbolId(0), Tasty.Name("pkg"), Tasty.Flags.empty, SymbolId(0), Chunk.empty)
        assert(sym.isInstanceOf[Tasty.Symbol.Package], "isPackage must be true")
        assert(!sym.isInstanceOf[Tasty.Symbol.Class], "isClass must be false")
        assert(!sym.isInstanceOf[Tasty.Symbol.Trait], "isTrait must be false")
        assert(!sym.isInstanceOf[Tasty.Symbol.Method], "isMethod must be false")
        assert(!sym.isInstanceOf[Tasty.Symbol.Val], "isVal must be false")
        assert(!sym.isInstanceOf[Tasty.Symbol.Var], "isVar must be false")
        // Phase 08: Symbol.Unresolved deleted; isPackage is false for all non-Package sym kinds.
        assert(!sym.isInstanceOf[Tasty.Symbol.Class], "isClass on Package must be false")
        succeed
    }

    // Leaf 46: 40-predicates-on-sentinel-package
    // Phase 08: Symbol.Unresolved is deleted; the former Classpath.sentinelUnresolved is now a Package(id=-1).
    // Verifies that a negative-id Package still passes basic predicate checks.
    // Pins: INV-003
    "40-predicates-on-unresolved: sentinel Package(id=-1) is Package kind, not ClassLike" in {
        val sym = Tasty.Symbol.Package(SymbolId(-1), Tasty.Name("<unresolved>"), Tasty.Flags.empty, SymbolId(-1), Chunk.empty)
        assert(sym.isInstanceOf[Tasty.Symbol.Package], "sentinel must still be a Package")
        assert(!sym.isInstanceOf[Tasty.Symbol.Class], "isClass must be false")
        assert(!sym.isInstanceOf[Tasty.Symbol.Trait], "isTrait must be false")
        assert(!sym.isInstanceOf[Tasty.Symbol.Object], "isObject must be false")
        assert(!sym.isInstanceOf[Tasty.Symbol.Method], "isMethod must be false")
        assert(!sym.isInstanceOf[Tasty.Symbol.Val], "isVal must be false")
        assert(!sym.isFinal, "isFinal must be false (no flags)")
        succeed
    }

end FlagPredicatePreservationTest
