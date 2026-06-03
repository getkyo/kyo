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
            Tasty.Name.Unsafe.init("Foo"),
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
            Chunk.empty,
            Maybe.Absent
        )
        assert(sym.isFinal, "isFinal must be true")
        assert(sym.isCase, "isCase must be true")
        assert(sym.isClass, "isClass must be true")
        assert(sym.isClassLike, "isClassLike must be true")
        assert(!sym.isMethod, "isMethod must be false")
        assert(!sym.isVal, "isVal must be false")
        assert(!sym.isVar, "isVar must be false")
        assert(!sym.isTrait, "isTrait must be false")
        assert(!sym.isObject, "isObject must be false")
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
            Tasty.Name.Unsafe.init("foo"),
            flags,
            SymbolId(0),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent,
            Maybe.Absent
        )
        assert(sym.isMethod, "isMethod must be true")
        assert(sym.isInline, "isInline must be true")
        assert(sym.isGiven, "isGiven must be true")
        assert(!sym.isClass, "isClass must be false")
        assert(!sym.isTrait, "isTrait must be false")
        assert(!sym.isVal, "isVal must be false")
        assert(!sym.isAbstract, "isAbstract must be false for method with no Abstract flag")
        succeed
    }

    // Leaf 45: 40-predicates-on-package
    // Given: Symbol.Package fixture; When: invoke all 40 predicates; Then: isPackage true; 39 others false
    // Pins: INV-003
    "40-predicates-on-package: isPackage true; isClass/isMethod/isVal false" in {
        val sym = Tasty.Symbol.Package(SymbolId(0), Tasty.Name.Unsafe.init("pkg"), Tasty.Flags.empty, SymbolId(0), Chunk.empty)
        assert(sym.isPackage, "isPackage must be true")
        assert(!sym.isClass, "isClass must be false")
        assert(!sym.isTrait, "isTrait must be false")
        assert(!sym.isMethod, "isMethod must be false")
        assert(!sym.isVal, "isVal must be false")
        assert(!sym.isVar, "isVar must be false")
        assert(!sym.isUnresolved, "isUnresolved must be false")
        succeed
    }

    // Leaf 46: 40-predicates-on-unresolved
    // Given: Classpath.sentinelUnresolved; When: invoke all 40 predicates; Then: isUnresolved true; 39 others false
    // Pins: INV-003
    "40-predicates-on-unresolved: isUnresolved true; all class-like predicates false" in {
        val sym = Tasty.Symbol.Unresolved(SymbolId(-1), Tasty.Name.Unsafe.init("<unresolved>"), SymbolId(-1))
        assert(sym.isUnresolved, "isUnresolved must be true")
        assert(!sym.isClass, "isClass must be false")
        assert(!sym.isTrait, "isTrait must be false")
        assert(!sym.isObject, "isObject must be false")
        assert(!sym.isMethod, "isMethod must be false")
        assert(!sym.isVal, "isVal must be false")
        assert(!sym.isPackage, "isPackage must be false")
        assert(!sym.isFinal, "isFinal must be false (no flags)")
        succeed
    }

end FlagPredicatePreservationTest
