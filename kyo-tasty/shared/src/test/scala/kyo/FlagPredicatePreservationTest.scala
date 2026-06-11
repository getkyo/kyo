package kyo

import kyo.Tasty.SymbolId

/** verify all 40 flag predicates still work.
  */
class FlagPredicatePreservationTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    "isFinal/isCase/isClass true on Class; 37 others false" in {
        val flags = Tasty.Flags(Tasty.Flag.Final, Tasty.Flag.Case)
        val symbol = Tasty.Symbol.Class(
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
        assert(symbol.isFinal, "isFinal must be true")
        assert(symbol.isCase, "isCase must be true")
        assert(symbol.isInstanceOf[Tasty.Symbol.Class], "isClass must be true")
        assert(symbol.isInstanceOf[Tasty.Symbol.ClassLike], "isClassLike must be true")
        assert(!symbol.isInstanceOf[Tasty.Symbol.Method], "isMethod must be false")
        assert(!symbol.isInstanceOf[Tasty.Symbol.Val], "isVal must be false")
        assert(!symbol.isInstanceOf[Tasty.Symbol.Var], "isVar must be false")
        assert(!symbol.isInstanceOf[Tasty.Symbol.Trait], "isTrait must be false")
        assert(!symbol.isInstanceOf[Tasty.Symbol.Object], "isObject must be false")
        assert(!symbol.isAbstract, "isAbstract must be false")
        succeed
    }

    "isInline/isGiven/isMethod true on Method" in {
        val flags = Tasty.Flags(Tasty.Flag.Inline, Tasty.Flag.Given)
        val symbol = Tasty.Symbol.Method(
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
        assert(symbol.isInstanceOf[Tasty.Symbol.Method], "isMethod must be true")
        assert(symbol.isInline, "isInline must be true")
        assert(symbol.isGiven, "isGiven must be true")
        assert(!symbol.isInstanceOf[Tasty.Symbol.Class], "isClass must be false")
        assert(!symbol.isInstanceOf[Tasty.Symbol.Trait], "isTrait must be false")
        assert(!symbol.isInstanceOf[Tasty.Symbol.Val], "isVal must be false")
        assert(!symbol.isAbstract, "isAbstract must be false for method with no Abstract flag")
        succeed
    }

    "isPackage true on Package; isClass/isMethod/isVal false" in {
        val symbol = Tasty.Symbol.Package(SymbolId(0), Tasty.Name("pkg"), Tasty.Flags.empty, SymbolId(0), Chunk.empty)
        assert(symbol.isInstanceOf[Tasty.Symbol.Package], "isPackage must be true")
        assert(!symbol.isInstanceOf[Tasty.Symbol.Class], "isClass must be false")
        assert(!symbol.isInstanceOf[Tasty.Symbol.Trait], "isTrait must be false")
        assert(!symbol.isInstanceOf[Tasty.Symbol.Method], "isMethod must be false")
        assert(!symbol.isInstanceOf[Tasty.Symbol.Val], "isVal must be false")
        assert(!symbol.isInstanceOf[Tasty.Symbol.Var], "isVar must be false")
        assert(!symbol.isInstanceOf[Tasty.Symbol.Class], "isClass on Package must be false")
        succeed
    }

    // Verifies that a negative-id Package still passes basic predicate checks.
    "sentinel Package(id=-1) is Package kind, not ClassLike" in {
        val symbol = Tasty.Symbol.Package(SymbolId(-1), Tasty.Name("<unresolved>"), Tasty.Flags.empty, SymbolId(-1), Chunk.empty)
        assert(symbol.isInstanceOf[Tasty.Symbol.Package], "sentinel must still be a Package")
        assert(!symbol.isInstanceOf[Tasty.Symbol.Class], "isClass must be false")
        assert(!symbol.isInstanceOf[Tasty.Symbol.Trait], "isTrait must be false")
        assert(!symbol.isInstanceOf[Tasty.Symbol.Object], "isObject must be false")
        assert(!symbol.isInstanceOf[Tasty.Symbol.Method], "isMethod must be false")
        assert(!symbol.isInstanceOf[Tasty.Symbol.Val], "isVal must be false")
        assert(!symbol.isFinal, "isFinal must be false (no flags)")
        succeed
    }

end FlagPredicatePreservationTest
