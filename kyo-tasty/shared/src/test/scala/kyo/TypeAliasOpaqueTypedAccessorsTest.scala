package kyo

import kyo.internal.tasty.symbol.SymbolId

/** Plan-mandated tests for Phase 05 (leaves 91-95): TypeAlias.body/typeParams and OpaqueType.body/bounds/typeParams accessors.
  *
  * Leaf 91 pins INV-008: the body field on TypeAlias is named `body`, not `rhs` or `rhsType`. Leaves 92-95 exercise the typed resolution
  * methods on TypeAlias and OpaqueType.
  *
  * Pins: INV-005, INV-008, INV-009.
  */
class TypeAliasOpaqueTypedAccessorsTest extends Test:

    import AllowUnsafe.embrace.danger

    // ── Synthetic builders ────────────────────────────────────────────────────

    private def makeTypeParam(id: Int, name: String, ownerId: Int): Tasty.Symbol.TypeParam =
        Tasty.Symbol.TypeParam(
            SymbolId(id),
            Tasty.Name.Unsafe.init(name),
            Tasty.Flags.empty,
            SymbolId(ownerId),
            Maybe.Absent,
            Tasty.TypeBounds(Tasty.Type.Unknown, Tasty.Type.Unknown),
            Tasty.Variance.Invariant
        )

    private def makeTypeAlias(
        id: Int,
        name: String,
        body: Tasty.Type,
        typeParamIds: Chunk[SymbolId] = Chunk.empty
    ): Tasty.Symbol.TypeAlias =
        Tasty.Symbol.TypeAlias(
            SymbolId(id),
            Tasty.Name.Unsafe.init(name),
            Tasty.Flags.empty,
            SymbolId(-1),
            Maybe.Absent,
            Maybe.Absent,
            body,
            typeParamIds,
            Chunk.empty
        )

    private def makeOpaqueType(
        id: Int,
        name: String,
        body: Tasty.Type,
        bounds: Tasty.TypeBounds,
        typeParamIds: Chunk[SymbolId] = Chunk.empty
    ): Tasty.Symbol.OpaqueType =
        Tasty.Symbol.OpaqueType(
            SymbolId(id),
            Tasty.Name.Unsafe.init(name),
            Tasty.Flags.empty,
            SymbolId(-1),
            Maybe.Absent,
            Maybe.Absent,
            body,
            bounds,
            typeParamIds,
            Chunk.empty
        )

    // ── Leaf 91: typealias-body-Type ──────────────────────────────────────────
    // Given: type Foo = Int  (TypeAlias body = Type.Named pointing to Int)
    // When: t.body
    // Then: Type whose Named symbolId target resolves to a symbol with name Int
    // Pins: INV-008
    "Leaf 91: typealias-body-Type: TypeAlias.body field holds the alias body as a Type" in run {
        val intId = SymbolId(0)
        val intSymbol = Tasty.Symbol.Class(
            intId,
            Tasty.Name.Unsafe.init("Int"),
            Tasty.Flags.empty,
            SymbolId(-1),
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
        val aliasBody = Tasty.Type.Named(intId)
        val typeAlias = makeTypeAlias(1, "Foo", aliasBody)
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(intSymbol, typeAlias)).map: cp =>
            given Tasty.Classpath = cp
            val b: Tasty.Type     = typeAlias.body
            b match
                case Tasty.Type.Named(sid) =>
                    import Tasty.Name.asString
                    val resolved = cp.symbol(sid)
                    assert(resolved.name.asString == "Int", s"Expected body to resolve to Int but got ${resolved.name.asString}")
                case other =>
                    fail(s"Expected Type.Named but got $other")
            end match
            succeed
    }

    // ── Leaf 92: typealias-typeParams ─────────────────────────────────────────
    // Given: type Foo[A,B] = (A,B)  (TypeAlias with 2 type param ids)
    // When: t.typeParams
    // Then: Chunk[TypeParam] size 2
    // Pins: INV-005, INV-008
    "Leaf 92: typealias-typeParams: TypeAlias.typeParams returns Chunk[TypeParam] size 2" in run {
        // Indices: tpA=0, tpB=1, typeAlias=2
        val tpA       = makeTypeParam(0, "A", ownerId = 2)
        val tpB       = makeTypeParam(1, "B", ownerId = 2)
        val typeAlias = makeTypeAlias(2, "Foo", Tasty.Type.Unknown, typeParamIds = Chunk(SymbolId(0), SymbolId(1)))
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(tpA, tpB, typeAlias)).map: cp =>
            given Tasty.Classpath                  = cp
            val tps: Chunk[Tasty.Symbol.TypeParam] = typeAlias.typeParams
            assert(tps.length == 2, s"Expected 2 type params but got ${tps.length}")
            import Tasty.Name.asString
            val names = tps.map(_.name.asString).toSet
            assert(names == Set("A", "B"), s"Expected A,B but got $names")
            succeed
    }

    // ── Leaf 93: opaque-body-Type ─────────────────────────────────────────────
    // Given: opaque type Money = Long  (OpaqueType body = Type.Named pointing to Long)
    // When: o.body
    // Then: Type.Named whose target resolves to symbol named Long
    // Pins: INV-008
    "Leaf 93: opaque-body-Type: OpaqueType.body holds the underlying Type" in run {
        val longId = SymbolId(0)
        val longSymbol = Tasty.Symbol.Class(
            longId,
            Tasty.Name.Unsafe.init("Long"),
            Tasty.Flags.empty,
            SymbolId(-1),
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
        val body       = Tasty.Type.Named(longId)
        val bounds     = Tasty.TypeBounds(Tasty.Type.Unknown, Tasty.Type.Unknown)
        val opaqueType = makeOpaqueType(1, "Money", body, bounds)
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(longSymbol, opaqueType)).map: cp =>
            given Tasty.Classpath = cp
            val b: Tasty.Type     = opaqueType.body
            b match
                case Tasty.Type.Named(sid) =>
                    import Tasty.Name.asString
                    val resolved = cp.symbol(sid)
                    assert(resolved.name.asString == "Long", s"Expected Long but got ${resolved.name.asString}")
                case other =>
                    fail(s"Expected Type.Named but got $other")
            end match
            succeed
    }

    // ── Leaf 94: opaque-bounds ────────────────────────────────────────────────
    // Given: opaque type N <: Int = Int  (OpaqueType with bounds upper=Int)
    // When: o.bounds
    // Then: TypeBounds whose upper is a Type.Named resolving to Int
    // Pins: INV-009
    "Leaf 94: opaque-bounds: OpaqueType.bounds field exposes TypeBounds with correct upper type" in run {
        val intId = SymbolId(0)
        val intSymbol = Tasty.Symbol.Class(
            intId,
            Tasty.Name.Unsafe.init("Int"),
            Tasty.Flags.empty,
            SymbolId(-1),
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
        val upperType  = Tasty.Type.Named(intId)
        val bounds     = Tasty.TypeBounds(Tasty.Type.Unknown, upperType)
        val body       = Tasty.Type.Named(intId)
        val opaqueType = makeOpaqueType(1, "N", body, bounds)
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(intSymbol, opaqueType)).map: cp =>
            given Tasty.Classpath    = cp
            val tb: Tasty.TypeBounds = opaqueType.bounds
            tb.upper match
                case Tasty.Type.Named(sid) =>
                    import Tasty.Name.asString
                    val resolved = cp.symbol(sid)
                    assert(resolved.name.asString == "Int", s"Expected Int upper bound but got ${resolved.name.asString}")
                case other =>
                    fail(s"Expected Type.Named upper bound but got $other")
            end match
            succeed
    }

    // ── Leaf 95: opaque-typeParams ────────────────────────────────────────────
    // Given: opaque type Box[A] = A  (OpaqueType with 1 type param)
    // When: o.typeParams
    // Then: Chunk[TypeParam] size 1 name A
    // Pins: INV-005
    "Leaf 95: opaque-typeParams: OpaqueType.typeParams returns Chunk[TypeParam] size 1 name A" in run {
        // Indices: tpA=0, opaqueType=1
        val tpA        = makeTypeParam(0, "A", ownerId = 1)
        val bounds     = Tasty.TypeBounds(Tasty.Type.Unknown, Tasty.Type.Unknown)
        val opaqueType = makeOpaqueType(1, "Box", Tasty.Type.Unknown, bounds, typeParamIds = Chunk(SymbolId(0)))
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(tpA, opaqueType)).map: cp =>
            given Tasty.Classpath                  = cp
            val tps: Chunk[Tasty.Symbol.TypeParam] = opaqueType.typeParams
            assert(tps.length == 1, s"Expected 1 type param but got ${tps.length}")
            import Tasty.Name.asString
            assert(tps(0).name.asString == "A", s"Expected name A but got ${tps(0).name.asString}")
            succeed
    }

end TypeAliasOpaqueTypedAccessorsTest
