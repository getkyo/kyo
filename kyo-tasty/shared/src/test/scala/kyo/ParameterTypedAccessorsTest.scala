package kyo

import kyo.internal.tasty.symbol.SymbolId

/** Plan-mandated tests for Phase 05 (leaves 96-100): Parameter typed accessors.
  *
  * Leaf 96: defaultArg resolves to Present when defaultArgId is Present. Leaf 97: defaultArg returns Absent when defaultArgId is Absent.
  * Leaf 98: isImplicit returns true for a Given-flagged Parameter. Leaf 99: isByName returns true for a Parameter with a Type.ByName
  * declared type. Leaf 100: isRepeated returns true for a Parameter with a Type.Repeated declared type.
  *
  * Pins: INV-002, INV-003.
  */
class ParameterTypedAccessorsTest extends Test:

    import AllowUnsafe.embrace.danger

    // ── Synthetic builders ────────────────────────────────────────────────────

    private def makeDefaultArgSymbol(id: Int): Tasty.Symbol.Method =
        Tasty.Symbol.Method(
            SymbolId(id),
            Tasty.Name("f$default$1"),
            Tasty.Flags.empty,
            SymbolId(-1),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent,
            Maybe.Absent
        )

    private def makeParameter(
        id: Int,
        name: String,
        declaredType: Tasty.Type,
        defaultArgId: Maybe[SymbolId] = Maybe.Absent,
        flags: Tasty.Flags = Tasty.Flags.empty
    ): Tasty.Symbol.Parameter =
        Tasty.Symbol.Parameter(
            SymbolId(id),
            Tasty.Name(name),
            flags,
            SymbolId(-1),
            Maybe.Absent,
            declaredType,
            defaultArgId,
            Chunk.empty
        )

    // ── Leaf 96: defaultArg-present ───────────────────────────────────────────
    // Given: def f(x: Int = 0) -- parameter with defaultArgId pointing to a default-arg method
    // When: p.defaultArg
    // Then: Maybe.Present(_)
    // Pins: INV-002
    "Leaf 96: defaultArg-present: Parameter.defaultArg returns Present when defaultArgId is Present" in run {
        // defaultArgMethod at index 0, parameter at index 1
        val defaultArgMethod = makeDefaultArgSymbol(0)
        val param            = makeParameter(1, "x", Tasty.Type.Unknown, defaultArgId = Maybe(SymbolId(0)))
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(defaultArgMethod, param)).map: cp =>
            given Tasty.Classpath       = cp
            val da: Maybe[Tasty.Symbol] = param.defaultArg
            assert(da.isDefined, "defaultArg must be Present when defaultArgId is Present")
            succeed
    }

    // ── Leaf 97: defaultArg-absent ────────────────────────────────────────────
    // Given: def f(x: Int) -- parameter with no default argument
    // When: p.defaultArg
    // Then: Maybe.Absent
    // Pins: INV-002
    "Leaf 97: defaultArg-absent: Parameter.defaultArg returns Absent when defaultArgId is Absent" in run {
        val param = makeParameter(0, "x", Tasty.Type.Unknown, defaultArgId = Maybe.Absent)
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(param)).map: cp =>
            given Tasty.Classpath       = cp
            val da: Maybe[Tasty.Symbol] = param.defaultArg
            assert(!da.isDefined, "defaultArg must be Absent when defaultArgId is Absent")
            succeed
    }

    // ── Leaf 98: isImplicit-given ─────────────────────────────────────────────
    // Given: def f(using x: Int) -- parameter with Flag.Given
    // When: p.isImplicit
    // Then: true
    // Pins: INV-003
    "Leaf 98: isImplicit-given: Parameter.isImplicit returns true for a Given-flagged parameter" in run {
        val givenFlags = Tasty.Flags(Tasty.Flag.Given)
        val param      = makeParameter(0, "x", Tasty.Type.Unknown, flags = givenFlags)
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(param)).map: cp =>
            assert(param.isImplicit, "isImplicit must be true when Flag.Given is set")
            succeed
    }

    // ── Leaf 99: isByName-typed ───────────────────────────────────────────────
    // Given: Parameter declaredType = Type.ByName(underlying)
    // When: p.isByName
    // Then: true
    // Pins: INV-002
    "Leaf 99: isByName-typed: Parameter.isByName returns true when declaredType is Type.ByName" in run {
        val byNameType = Tasty.Type.ByName(Tasty.Type.Unknown)
        val param      = makeParameter(0, "x", byNameType)
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(param)).map: cp =>
            assert(param.isByName, "isByName must be true when declaredType is Type.ByName")
            assert(!param.isRepeated, "isRepeated must be false when declaredType is Type.ByName")
            succeed
    }

    // ── Leaf 100: isRepeated-typed ────────────────────────────────────────────
    // Given: Parameter declaredType = Type.Repeated(elem)
    // When: p.isRepeated
    // Then: true
    // Pins: INV-002
    "Leaf 100: isRepeated-typed: Parameter.isRepeated returns true when declaredType is Type.Repeated" in run {
        val repeatedType = Tasty.Type.Repeated(Tasty.Type.Unknown)
        val param        = makeParameter(0, "xs", repeatedType)
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(param)).map: cp =>
            assert(param.isRepeated, "isRepeated must be true when declaredType is Type.Repeated")
            assert(!param.isByName, "isByName must be false when declaredType is Type.Repeated")
            succeed
    }

end ParameterTypedAccessorsTest
