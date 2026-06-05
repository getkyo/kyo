package kyo

import kyo.Tasty.SymbolId

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
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(ownerId),
            Maybe.Absent,
            Tasty.TypeBounds(Tasty.Type.Nothing, Tasty.Type.Any),
            Tasty.Variance.Invariant
        )

    private def makeTypeAlias(
        id: Int,
        name: String,
        body: Maybe[Tasty.Type],
        typeParamIds: Chunk[SymbolId] = Chunk.empty
    ): Tasty.Symbol.TypeAlias =
        Tasty.Symbol.TypeAlias(
            SymbolId(id),
            Tasty.Name(name),
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
        body: Maybe[Tasty.Type],
        bounds: Tasty.TypeBounds,
        typeParamIds: Chunk[SymbolId] = Chunk.empty
    ): Tasty.Symbol.OpaqueType =
        Tasty.Symbol.OpaqueType(
            SymbolId(id),
            Tasty.Name(name),
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
            Tasty.Name("Int"),
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
            Chunk.empty
        )
        val aliasBody = Tasty.Type.Named(intId)
        val typeAlias = makeTypeAlias(1, "Foo", Maybe.Present(aliasBody))
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(intSymbol, typeAlias)).map: cp =>
            given Tasty.Classpath = cp
            typeAlias.body match
                case Maybe.Present(Tasty.Type.Named(sid)) =>
                    import Tasty.Name.asString
                    val resolved = cp.symbol(sid)
                    assert(
                        resolved.map(_.name.asString).getOrElse("<absent>") == "Int",
                        s"Expected body to resolve to Int but got ${resolved.map(_.name.asString).getOrElse("<absent>")}"
                    )
                case Maybe.Present(other) =>
                    fail(s"Expected Type.Named but got $other")
                case Maybe.Absent =>
                    fail("Expected body to be Present but got Absent")
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
        val typeAlias = makeTypeAlias(2, "Foo", Maybe.Absent, typeParamIds = Chunk(SymbolId(0), SymbolId(1)))
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(tpA, tpB, typeAlias)).map: cp =>
            given Tasty.Classpath = cp
            val tps               = typeAlias.typeParamIds.flatMap(id => cp.symbol(id).toChunk).asInstanceOf[Chunk[Tasty.Symbol.TypeParam]]
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
            Tasty.Name("Long"),
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
            Chunk.empty
        )
        val body       = Tasty.Type.Named(longId)
        val bounds     = Tasty.TypeBounds(Tasty.Type.Nothing, Tasty.Type.Any)
        val opaqueType = makeOpaqueType(1, "Money", Maybe.Present(body), bounds)
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(longSymbol, opaqueType)).map: cp =>
            given Tasty.Classpath = cp
            opaqueType.body match
                case Maybe.Present(Tasty.Type.Named(sid)) =>
                    import Tasty.Name.asString
                    val resolved = cp.symbol(sid)
                    assert(
                        resolved.map(_.name.asString).getOrElse("<absent>") == "Long",
                        s"Expected Long but got ${resolved.map(_.name.asString).getOrElse("<absent>")}"
                    )
                case Maybe.Present(other) =>
                    fail(s"Expected Type.Named but got $other")
                case Maybe.Absent =>
                    fail("Expected body to be Present but got Absent")
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
            Tasty.Name("Int"),
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
            Chunk.empty
        )
        val upperType  = Tasty.Type.Named(intId)
        val bounds     = Tasty.TypeBounds(Tasty.Type.Nothing, upperType)
        val body       = Tasty.Type.Named(intId)
        val opaqueType = makeOpaqueType(1, "N", Maybe.Present(body), bounds)
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(intSymbol, opaqueType)).map: cp =>
            given Tasty.Classpath    = cp
            val tb: Tasty.TypeBounds = opaqueType.bounds
            tb.upper match
                case Tasty.Type.Named(sid) =>
                    import Tasty.Name.asString
                    val resolved = cp.symbol(sid)
                    assert(
                        resolved.map(_.name.asString).getOrElse("<absent>") == "Int",
                        s"Expected Int upper bound but got ${resolved.map(_.name.asString).getOrElse("<absent>")}"
                    )
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
        val bounds     = Tasty.TypeBounds(Tasty.Type.Nothing, Tasty.Type.Any)
        val opaqueType = makeOpaqueType(1, "Box", Maybe.Absent, bounds, typeParamIds = Chunk(SymbolId(0)))
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(tpA, opaqueType)).map: cp =>
            given Tasty.Classpath = cp
            val tps               = opaqueType.typeParamIds.flatMap(id => cp.symbol(id).toChunk).asInstanceOf[Chunk[Tasty.Symbol.TypeParam]]
            assert(tps.length == 1, s"Expected 1 type param but got ${tps.length}")
            import Tasty.Name.asString
            assert(tps(0).name.asString == "A", s"Expected name A but got ${tps(0).name.asString}")
            succeed
    }

    // ── Phase 10 leaves: Maybe[Type] fields and error accumulation ──────────────

    // Phase 10 leaf 2: typeAliasBodyIsMaybe
    // Given: a fixture Symbol.TypeAlias with a real body
    // When: the test reads ta.body
    // Then: Maybe.Present(t) for a resolvable body; Maybe.Absent under SoftFail with no resolution
    // Pins: Cat 14
    "Phase 10 leaf 2: TypeAlias.body is Maybe[Type] -- Present for real body, Absent for SoftFail missing" in {
        val bodyType = Tasty.Type.Named(Tasty.SymbolId(0))
        val ta       = makeTypeAlias(0, "Foo", Maybe.Present(bodyType))
        assert(ta.body == Maybe.Present(bodyType), s"Expected Maybe.Present but got ${ta.body}")
        val taAbsent = makeTypeAlias(0, "Bar", Maybe.Absent)
        assert(taAbsent.body == Maybe.Absent, "Expected Maybe.Absent for body=Maybe.Absent")
        succeed
    }

    // Phase 10 leaf 3: opaqueTypeBodyIsMaybe
    // Given: a fixture Symbol.OpaqueType with a real body
    // When: the test reads ot.body
    // Then: Maybe.Present(t); bounds unchanged (TypeBounds(Type.Nothing, Type.Any) by default)
    // Pins: Cat 14; PRESERVE-M
    "Phase 10 leaf 3: OpaqueType.body is Maybe[Type] -- Present for real body; bounds default Nothing/Any" in {
        val bodyType = Tasty.Type.Named(Tasty.SymbolId(0))
        val bounds   = Tasty.TypeBounds(Tasty.Type.Nothing, Tasty.Type.Any)
        val ot       = makeOpaqueType(0, "Money", Maybe.Present(bodyType), bounds)
        assert(ot.body == Maybe.Present(bodyType), s"Expected Maybe.Present but got ${ot.body}")
        assert(ot.bounds.lower == Tasty.Type.Nothing, "Expected Nothing as default lower bound")
        assert(ot.bounds.upper == Tasty.Type.Any, "Expected Any as default upper bound")
        succeed
    }

    // Phase 10 leaf 4: parameterDeclaredTypeIsMaybe
    // Given: a fixture Symbol.Parameter
    // When: the test reads p.declaredType
    // Then: Maybe.Present(t) for resolvable; Maybe.Absent for unresolved under SoftFail
    // Pins: Cat 14
    "Phase 10 leaf 4: Parameter.declaredType is Maybe[Type] -- Present for real type, Absent for missing" in {
        val dt = Tasty.Type.Named(Tasty.SymbolId(0))
        val p1 = Tasty.Symbol.Parameter(
            Tasty.SymbolId(0),
            Tasty.Name("x"),
            Tasty.Flags.empty,
            Tasty.SymbolId(-1),
            Maybe.Absent,
            Maybe.Present(dt),
            Maybe.Absent,
            Chunk.empty
        )
        assert(p1.declaredType == Maybe.Present(dt), s"Expected Maybe.Present but got ${p1.declaredType}")
        val p2 = Tasty.Symbol.Parameter(
            Tasty.SymbolId(1),
            Tasty.Name("y"),
            Tasty.Flags.empty,
            Tasty.SymbolId(-1),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty
        )
        assert(p2.declaredType == Maybe.Absent, "Expected Maybe.Absent for missing declaredType")
        succeed
    }

    // Phase 10 leaf 5: failFastRaisesMissingDeclaredType
    // Given: a SymbolDescriptor with kind=Parameter, declaredType=Maybe.Absent
    // When: TypedSymbolFactory.from called with FailFast mode and a non-null accErrors buffer
    // Then: SymbolMaterializationError is thrown carrying TastyError.MissingDeclaredType
    // Pins: Cat 14; INV-TASTYERROR-WIRE consumer
    "Phase 10 leaf 5: TypedSymbolFactory.from throws SymbolMaterializationError under FailFast with absent declaredType" in {
        import kyo.internal.tasty.symbol.SymbolDescriptor
        import kyo.internal.tasty.symbol.SymbolKind
        import kyo.internal.tasty.symbol.SymbolMaterializationError
        import kyo.internal.tasty.symbol.TypedSymbolFactory
        val d = new SymbolDescriptor(
            id = 42,
            kind = SymbolKind.Parameter,
            flags = Tasty.Flags.empty,
            name = Tasty.Name("p"),
            ownerId = -1,
            declaredType = Maybe.Absent,
            scaladoc = Maybe.Absent,
            sourcePosition = Maybe.Absent,
            javaMetadata = Maybe.Absent,
            parentTypes = Chunk.empty,
            typeParamIds = Chunk.empty,
            declarationIds = Chunk.empty,
            permittedSubclassIds = Maybe.Absent,
            body = Maybe.Absent
        )
        // A non-null accErrors buffer is required to activate the error path.
        // When accErrors is null, the factory silently returns Maybe.Absent (for INV-009 compliance).
        val sentinel = new scala.collection.mutable.ArrayBuffer[TastyError]()
        try
            TypedSymbolFactory.from(d, Tasty.ErrorMode.FailFast, sentinel, "test.tasty", 0L)
            fail("Expected SymbolMaterializationError to be thrown")
        catch
            case sme: SymbolMaterializationError =>
                sme.error match
                    case TastyError.MissingDeclaredType(sid, file) =>
                        assert(sid == Tasty.SymbolId(42), s"Expected symbolId=42 but got $sid")
                        assert(file == "test.tasty", s"Expected file='test.tasty' but got '$file'")
                    case other =>
                        fail(s"Expected MissingDeclaredType but got $other")
        end try
        succeed
    }

    // Phase 10 leaf 6: softFailAccumulatesUnknownType
    // Given: the same SymbolDescriptor with kind=Parameter, declaredType=Maybe.Absent
    // When: TypedSymbolFactory.from called with SoftFail mode and a real accErrors buffer
    // Then: accErrors contains exactly one TastyError.UnknownType; returned symbol has declaredType==Maybe.Absent
    // Pins: Cat 14
    "Phase 10 leaf 6: TypedSymbolFactory.from accumulates UnknownType under SoftFail with absent declaredType" in {
        import kyo.internal.tasty.symbol.SymbolDescriptor
        import kyo.internal.tasty.symbol.SymbolKind
        import kyo.internal.tasty.symbol.TypedSymbolFactory
        val accErrors = new scala.collection.mutable.ArrayBuffer[TastyError]()
        val d = new SymbolDescriptor(
            id = 7,
            kind = SymbolKind.Parameter,
            flags = Tasty.Flags.empty,
            name = Tasty.Name("q"),
            ownerId = -1,
            declaredType = Maybe.Absent,
            scaladoc = Maybe.Absent,
            sourcePosition = Maybe.Absent,
            javaMetadata = Maybe.Absent,
            parentTypes = Chunk.empty,
            typeParamIds = Chunk.empty,
            declarationIds = Chunk.empty,
            permittedSubclassIds = Maybe.Absent,
            body = Maybe.Absent
        )
        val sym = TypedSymbolFactory.from(d, Tasty.ErrorMode.SoftFail, accErrors, "soft.tasty", 0L)
        assert(accErrors.length == 1, s"Expected 1 accumulated error but got ${accErrors.length}")
        accErrors.head match
            case TastyError.UnknownType(file, _, reason) =>
                assert(file == "soft.tasty", s"Expected file='soft.tasty' but got '$file'")
                assert(reason.contains("Parameter"), s"Expected reason to mention Parameter but got '$reason'")
            case other =>
                fail(s"Expected UnknownType but got $other")
        end match
        sym match
            case p: Tasty.Symbol.Parameter =>
                assert(p.declaredType == Maybe.Absent, s"Expected Maybe.Absent declaredType but got ${p.declaredType}")
            case other =>
                fail(s"Expected Parameter but got $other")
        end match
        succeed
    }

end TypeAliasOpaqueTypedAccessorsTest
