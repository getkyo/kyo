package kyo

import kyo.Tasty.SymbolId

/** Tests for typed Classpath all* aggregation accessors.
  *
  * Fixture has: 3 Class symbols (A, B, C), 2 Traits (T1, T2), 1 Object (O),
  * 5 Methods (m1-m5), 2 Vals (v1, v2), 1 Var (w1), 1 TypeAlias (ta1),
  * 1 OpaqueType (ot1), 1 AbstractType (at1), 1 TypeParam (tp1),
  * 1 Parameter (p1), 2 Packages (pkg, pkg.sub).
  */
class ClasspathTypedAllAggregationsTest extends kyo.test.Test[Any]:

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
        Sync.defer {
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
                fullNameIndex = Dict.empty,
                packageIndex = Dict.empty,
                subclassIndex = Dict.empty,
                companionIndex = Dict.empty,
                moduleIndex = Dict.empty,
                errors = Chunk.empty
            )
        }

    "allClassLike returns Chunk[ClassLike] of size 6 (3 Class + 2 Trait + 1 Object)" in {
        buildFixture.map { classpath =>
            val cs: Chunk[Tasty.Symbol.ClassLike] = classpath.allClassLike
            assert(cs.size == 6, s"Expected 6 classlike symbols (3 class + 2 trait + 1 object) but got ${cs.size}: $cs")
            succeed
        }
    }

    "allTraits returns Chunk[Trait] of size 2" in {
        buildFixture.map { classpath =>
            val ts: Chunk[Tasty.Symbol.Trait] = classpath.allTraits
            assert(ts.size == 2, s"Expected 2 traits but got ${ts.size}: $ts")
            succeed
        }
    }

    "allObjects returns Chunk[Object] of size 1" in {
        buildFixture.map { classpath =>
            val os: Chunk[Tasty.Symbol.Object] = classpath.allObjects
            assert(os.size == 1, s"Expected 1 object but got ${os.size}: $os")
            succeed
        }
    }

    "allClassLike size equals sum of classes + traits + objects" in {
        buildFixture.map { classpath =>
            val cl: Chunk[Tasty.Symbol.ClassLike] = classpath.allClassLike
            assert(cl.size == 6, s"Expected 6 ClassLike symbols but got ${cl.size}: $cl")
            succeed
        }
    }

    "allMethods returns Chunk[Method] of size 5" in {
        buildFixture.map { classpath =>
            val ms: Chunk[Tasty.Symbol.Method] = classpath.allMethods
            assert(ms.size == 5, s"Expected 5 methods but got ${ms.size}: $ms")
            succeed
        }
    }

    "allVals=2, allVars=1, allFields=0" in {
        buildFixture.map { classpath =>
            val vs: Chunk[Tasty.Symbol.Val]   = classpath.allVals
            val ws: Chunk[Tasty.Symbol.Var]   = classpath.allVars
            val fs: Chunk[Tasty.Symbol.Field] = classpath.allFields
            assert(vs.size == 2, s"Expected 2 vals but got ${vs.size}")
            assert(ws.size == 1, s"Expected 1 var but got ${ws.size}")
            assert(fs.size == 0, s"Expected 0 fields but got ${fs.size}")
            succeed
        }
    }

    "allTypeAliases=1, allOpaqueTypes=1, allAbstractTypes=1" in {
        buildFixture.map { classpath =>
            val tas: Chunk[Tasty.Symbol.TypeAlias]    = classpath.allTypeAliases
            val ots: Chunk[Tasty.Symbol.OpaqueType]   = classpath.allOpaqueTypes
            val ats: Chunk[Tasty.Symbol.AbstractType] = classpath.allAbstractTypes
            assert(tas.size == 1, s"Expected 1 type alias but got ${tas.size}")
            assert(ots.size == 1, s"Expected 1 opaque type but got ${ots.size}")
            assert(ats.size == 1, s"Expected 1 abstract type but got ${ats.size}")
            succeed
        }
    }

    "allTypeParams=1, allParameters=1" in {
        buildFixture.map { classpath =>
            val tps: Chunk[Tasty.Symbol.TypeParam] = classpath.allTypeParams
            val ps: Chunk[Tasty.Symbol.Parameter]  = classpath.allParameters
            assert(tps.size == 1, s"Expected 1 type param but got ${tps.size}")
            assert(ps.size == 1, s"Expected 1 parameter but got ${ps.size}")
            succeed
        }
    }

    "allPackages returns Chunk[Package] of size 2" in {
        buildFixture.map { classpath =>
            val ps: Chunk[Tasty.Symbol.Package] = classpath.allPackages
            assert(ps.size == 2, s"Expected 2 packages but got ${ps.size}: $ps")
            succeed
        }
    }

    // Symbol.Unresolved does not exist; compile-time check confirms it.
    "allUnresolved and Symbol.Unresolved are gone" in {
        val __tcErrors1 = compiletime.testing.typeCheckErrors("val _: kyo.Chunk[kyo.Tasty.Symbol.Unresolved] = ???").length

        assert(__tcErrors1 > 0, "Symbol.Unresolved should not exist")
        succeed
    }

end ClasspathTypedAllAggregationsTest
