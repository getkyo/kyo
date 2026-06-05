package kyo

import kyo.Tasty.SymbolId

/** Plan-mandated tests for Phase 06 (leaves 118-127): typed Classpath all* aggregation accessors.
  *
  * Fixture layout (all as a flat Chunk[Symbol], index == id.value): 0-2 -> 3 Class symbols (A, B, C) 3-4 -> 2 Trait symbols (T1, T2) 5 -> 1
  * Object symbol (O) 6-10 -> 5 Method symbols (m1..m5) 11-12 -> 2 Val symbols (v1, v2) 13 -> 1 Var symbol (w1) 14 -> 1 TypeAlias symbol
  * (ta1) 15 -> 1 OpaqueType symbol (ot1) 16 -> 1 AbstractType symbol (at1) 17 -> 1 TypeParam symbol (tp1) -- part of class A 18 -> 1
  * Parameter symbol (p1) -- part of m1 19-20 -> 2 Package symbols (pkg, pkg.sub)
  *
  * Pins: INV-005.
  */
class ClasspathTypedAllAggregationsTest extends Test:

    import AllowUnsafe.embrace.danger

    private def makeClass(id: Int, name: String): Tasty.Symbol.Class =
        Tasty.Symbol.Class(
            SymbolId(id),
            Tasty.Name(name),
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

    private def makeTrait(id: Int, name: String): Tasty.Symbol.Trait =
        Tasty.Symbol.Trait(
            SymbolId(id),
            Tasty.Name(name),
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

    private def makeObject(id: Int, name: String): Tasty.Symbol.Object =
        Tasty.Symbol.Object(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(-1),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty
        )

    private def makeMethod(id: Int, name: String): Tasty.Symbol.Method =
        Tasty.Symbol.Method(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(-1),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent
        )

    private def makeVal(id: Int, name: String): Tasty.Symbol.Val =
        Tasty.Symbol.Val(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(-1),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty
        )

    private def makeVar(id: Int, name: String): Tasty.Symbol.Var =
        Tasty.Symbol.Var(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(-1),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty
        )

    private def makeTypeAlias(id: Int, name: String): Tasty.Symbol.TypeAlias =
        Tasty.Symbol.TypeAlias(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(-1),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Present(Tasty.Type.Nothing),
            Chunk.empty,
            Chunk.empty
        )

    private def makeOpaqueType(id: Int, name: String): Tasty.Symbol.OpaqueType =
        Tasty.Symbol.OpaqueType(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(-1),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Present(Tasty.Type.Nothing),
            Tasty.TypeBounds(Tasty.Type.Nothing, Tasty.Type.Any),
            Chunk.empty,
            Chunk.empty
        )

    private def makeAbstractType(id: Int, name: String): Tasty.Symbol.AbstractType =
        Tasty.Symbol.AbstractType(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(-1),
            Maybe.Absent,
            Maybe.Absent,
            Tasty.TypeBounds(Tasty.Type.Nothing, Tasty.Type.Any),
            Chunk.empty
        )

    private def makeTypeParam(id: Int, name: String): Tasty.Symbol.TypeParam =
        Tasty.Symbol.TypeParam(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(0),
            Maybe.Absent,
            Tasty.TypeBounds(Tasty.Type.Nothing, Tasty.Type.Any),
            Tasty.Variance.Invariant
        )

    private def makeParameter(id: Int, name: String): Tasty.Symbol.Parameter =
        Tasty.Symbol.Parameter(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(6),
            Maybe.Absent,
            Maybe.Present(Tasty.Type.Nothing),
            Maybe.Absent,
            Chunk.empty
        )

    private def makePackage(id: Int, name: String, ownerId: Int): Tasty.Symbol.Package =
        Tasty.Symbol.Package(SymbolId(id), Tasty.Name(name), Tasty.Flags.empty, SymbolId(ownerId), Chunk.empty)

    private def buildFixture(using Frame): Tasty.Classpath < Sync =
        Sync.defer:
            val syms: Chunk[Tasty.Symbol] = Chunk(
                makeClass(0, "A"),                    // 0
                makeClass(1, "B"),                    // 1
                makeClass(2, "C"),                    // 2
                makeTrait(3, "T1"),                   // 3
                makeTrait(4, "T2"),                   // 4
                makeObject(5, "O"),                   // 5
                makeMethod(6, "m1"),                  // 6
                makeMethod(7, "m2"),                  // 7
                makeMethod(8, "m3"),                  // 8
                makeMethod(9, "m4"),                  // 9
                makeMethod(10, "m5"),                 // 10
                makeVal(11, "v1"),                    // 11
                makeVal(12, "v2"),                    // 12
                makeVar(13, "w1"),                    // 13
                makeTypeAlias(14, "ta1"),             // 14
                makeOpaqueType(15, "ot1"),            // 15
                makeAbstractType(16, "at1"),          // 16
                makeTypeParam(17, "A"),               // 17
                makeParameter(18, "x"),               // 18
                makePackage(19, "pkg", ownerId = -1), // 19
                makePackage(20, "sub", ownerId = 19)  // 20
            )
            Tasty.Classpath.make(
                symbols = syms,
                rootSymbolId = SymbolId(-1),
                topLevelClassIds = Chunk(SymbolId(0), SymbolId(1), SymbolId(2), SymbolId(3), SymbolId(4), SymbolId(5)),
                packageIds = Chunk(SymbolId(19), SymbolId(20)),
                fqnIndex = Dict.empty,
                packageIndex = Dict.empty,
                subclassIndex = Dict.empty,
                companionIndex = Dict.empty,
                moduleIndex = Dict.empty,
                errors = Chunk.empty
            )

    // ── Leaf 118: allClassLike-typed ──────────────────────────────────────────
    // Given: fixture with 3 classes, 2 traits, 1 object.
    // When: cp.allClassLike
    // Then: Chunk[Symbol.ClassLike] including Class, Trait, Object -- size 6 (3+2+1)
    // F-G-006 fix: allClassLike returns Chunk[Symbol.ClassLike] covering Class, Trait, Object, EnumCase.
    // Pins: INV-005
    "Leaf 118: allClassLike returns Chunk[ClassLike] of size 6 (3 Class + 2 Trait + 1 Object)" in run {
        buildFixture.map: cp =>
            val cs: Chunk[Tasty.Symbol.ClassLike] = cp.allClassLike
            assert(cs.size == 6, s"Expected 6 classlike symbols (3 class + 2 trait + 1 object) but got ${cs.size}: $cs")
            succeed
    }

    // ── Leaf 119: allTraits-typed ─────────────────────────────────────────────
    // Given: fixture with 2 traits.
    // When: cp.allTraits
    // Then: Chunk[Symbol.Trait] size 2
    // Pins: INV-005
    "Leaf 119: allTraits returns Chunk[Trait] of size 2" in run {
        buildFixture.map: cp =>
            val ts: Chunk[Tasty.Symbol.Trait] = cp.allTraits
            assert(ts.size == 2, s"Expected 2 traits but got ${ts.size}: $ts")
            succeed
    }

    // ── Leaf 120: allObjects-typed ────────────────────────────────────────────
    // Given: fixture with 1 object.
    // When: cp.allObjects
    // Then: Chunk[Symbol.Object] size 1
    // Pins: INV-005
    "Leaf 120: allObjects returns Chunk[Object] of size 1" in run {
        buildFixture.map: cp =>
            val os: Chunk[Tasty.Symbol.Object] = cp.allObjects
            assert(os.size == 1, s"Expected 1 object but got ${os.size}: $os")
            succeed
    }

    // ── Leaf 121: allClassLike-equals-sum ─────────────────────────────────────
    // Given: fixture with 3 classes + 2 traits + 1 object.
    // When: cp.allClassLike.size
    // Then: 6
    // Pins: INV-005
    "Leaf 121: allClassLike size equals sum of classes + traits + objects" in run {
        buildFixture.map: cp =>
            val cl: Chunk[Tasty.Symbol.ClassLike] = cp.allClassLike
            assert(cl.size == 6, s"Expected 6 ClassLike symbols but got ${cl.size}: $cl")
            succeed
    }

    // ── Leaf 122: allMethods-typed ────────────────────────────────────────────
    // Given: fixture with 5 methods.
    // When: cp.allMethods
    // Then: Chunk[Method] size 5
    // Pins: INV-005
    "Leaf 122: allMethods returns Chunk[Method] of size 5" in run {
        buildFixture.map: cp =>
            val ms: Chunk[Tasty.Symbol.Method] = cp.allMethods
            assert(ms.size == 5, s"Expected 5 methods but got ${ms.size}: $ms")
            succeed
    }

    // ── Leaf 123: allVals-allVars-allFields ───────────────────────────────────
    // Given: fixture with 2 vals, 1 var, 0 fields.
    // When: invoke 3 accessors
    // Then: sizes 2, 1, 0; typed returns
    // Pins: INV-005
    "Leaf 123: allVals=2, allVars=1, allFields=0" in run {
        buildFixture.map: cp =>
            val vs: Chunk[Tasty.Symbol.Val]   = cp.allVals
            val ws: Chunk[Tasty.Symbol.Var]   = cp.allVars
            val fs: Chunk[Tasty.Symbol.Field] = cp.allFields
            assert(vs.size == 2, s"Expected 2 vals but got ${vs.size}")
            assert(ws.size == 1, s"Expected 1 var but got ${ws.size}")
            assert(fs.size == 0, s"Expected 0 fields but got ${fs.size}")
            succeed
    }

    // ── Leaf 124: allTypeAliases-allOpaqueTypes-allAbstractTypes ─────────────
    // Given: fixture with 1 each.
    // When: invoke 3 accessors
    // Then: sizes 1, 1, 1; typed returns
    // Pins: INV-005
    "Leaf 124: allTypeAliases=1, allOpaqueTypes=1, allAbstractTypes=1" in run {
        buildFixture.map: cp =>
            val tas: Chunk[Tasty.Symbol.TypeAlias]    = cp.allTypeAliases
            val ots: Chunk[Tasty.Symbol.OpaqueType]   = cp.allOpaqueTypes
            val ats: Chunk[Tasty.Symbol.AbstractType] = cp.allAbstractTypes
            assert(tas.size == 1, s"Expected 1 type alias but got ${tas.size}")
            assert(ots.size == 1, s"Expected 1 opaque type but got ${ots.size}")
            assert(ats.size == 1, s"Expected 1 abstract type but got ${ats.size}")
            succeed
    }

    // ── Leaf 125: allTypeParams-allParameters ─────────────────────────────────
    // Given: fixture with 1 TypeParam and 1 Parameter.
    // When: invoke 2 accessors
    // Then: sizes 1, 1; typed returns
    // Pins: INV-005
    "Leaf 125: allTypeParams=1, allParameters=1" in run {
        buildFixture.map: cp =>
            val tps: Chunk[Tasty.Symbol.TypeParam] = cp.allTypeParams
            val ps: Chunk[Tasty.Symbol.Parameter]  = cp.allParameters
            assert(tps.size == 1, s"Expected 1 type param but got ${tps.size}")
            assert(ps.size == 1, s"Expected 1 parameter but got ${ps.size}")
            succeed
    }

    // ── Leaf 126: allPackages-typed ───────────────────────────────────────────
    // Given: fixture with 2 packages (pkg and pkg.sub).
    // When: cp.allPackages.size
    // Then: 2; typed return
    // Pins: INV-005
    "Leaf 126: allPackages returns Chunk[Package] of size 2" in run {
        buildFixture.map: cp =>
            val ps: Chunk[Tasty.Symbol.Package] = cp.allPackages
            assert(ps.size == 2, s"Expected 2 packages but got ${ps.size}: $ps")
            succeed
    }

    // ── Leaf 127: allUnresolved removed (Phase 08) ────────────────────────────
    // allUnresolved was deleted in Phase 08 (Cat 19). Symbol.Unresolved no longer exists.
    // The test is replaced with a compile-time check that allUnresolved does not exist.
    // Pins: Cat 19
    "Leaf 127: allUnresolved and Symbol.Unresolved are gone (Phase 08 Cat 19)" in {
        assert(
            compiletime.testing.typeCheckErrors("val _: kyo.Chunk[kyo.Tasty.Symbol.Unresolved] = ???").nonEmpty,
            "Symbol.Unresolved should not exist"
        )
        succeed
    }

end ClasspathTypedAllAggregationsTest
