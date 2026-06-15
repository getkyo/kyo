package kyo

import kyo.Tasty.SymbolId

/** Symbol.show(format: ShowFormat).
  *
  * Covers: ShowFormat.FullyQualified, ShowFormat.Simple, ShowFormat.Code for class and method.
  */
class SymbolShowFormatTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    private def makePackage(id: Int, name: String): Tasty.Symbol.Package =
        Tasty.Symbol.Package(SymbolId(id), Tasty.Name(name), Tasty.Flags.empty, SymbolId(-1), Chunk.empty)

    private def makeClass(
        id: Int,
        name: String,
        ownerId: Int,
        parentTypes: Chunk[Tasty.Type] = Chunk.empty,
        tpIds: Chunk[SymbolId] = Chunk.empty
    ): Tasty.Symbol.Class =
        Tasty.Symbol.Class(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(ownerId),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            parentTypes,
            tpIds,
            Chunk.empty,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty
        )

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

    private def makeMethod(
        id: Int,
        name: String,
        ownerId: Int,
        paramListIds: Chunk[Chunk[SymbolId]] = Chunk.empty,
        declaredType: Maybe[Tasty.Type] = Maybe.Absent
    ): Tasty.Symbol.Method =
        Tasty.Symbol.Method(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(ownerId),
            Maybe.Absent,
            Maybe.Absent,
            declaredType,
            paramListIds,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent
        )

    private def makeParameter(id: Int, name: String, ownerId: Int, tpe: Tasty.Type): Tasty.Symbol.Parameter =
        Tasty.Symbol.Parameter(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(ownerId),
            Maybe.Absent,
            Maybe.Present(tpe),
            Maybe.Absent,
            Chunk.empty
        )

    /** Fixture: Package "scala.collection" -> Class "List"[A] extends D Method "foo"(x: List): String
      *
      * Symbol ids: 0 = Package "scala.collection" 1 = Class "List" (typeParam id=2) 2 = TypeParam "A" owned by 1 3 = Class "D" (parent of
      * List) -- in same pkg 4 = Method "foo" with 1 param: x: List, return type: List 5 = Parameter "x" with type Named(1)
      */
    private def buildFixture(using Frame): Tasty.Classpath < Sync =
        Sync.defer {
            val pkg  = makePackage(0, "scala.collection")
            val tpA  = makeTypeParam(2, "A", ownerId = 1)
            val clsD = makeClass(3, "D", ownerId = 0)
            val clsList = makeClass(
                1,
                "List",
                ownerId = 0,
                parentTypes = Chunk(Tasty.Type.Named(SymbolId(3))),
                tpIds = Chunk(SymbolId(2))
            )
            val param = makeParameter(5, "x", ownerId = 4, tpe = Tasty.Type.Named(SymbolId(1)))
            val methodFoo = makeMethod(
                4,
                "foo",
                ownerId = 0,
                paramListIds = Chunk(Chunk(SymbolId(5))),
                declaredType = Maybe(Tasty.Type.Named(SymbolId(1)))
            )
            Tasty.Classpath.make(
                symbols = Chunk(pkg, clsList, tpA, clsD, methodFoo, param),
                rootSymbolId = SymbolId(-1),
                topLevelClassIds = Chunk(SymbolId(1), SymbolId(3)),
                packageIds = Chunk(SymbolId(0)),
                fullNameIndex = Dict(
                    "scala.collection.List" -> SymbolId(1),
                    "scala.collection.D"    -> SymbolId(3)
                ),
                packageIndex = Dict("scala.collection" -> SymbolId(0)),
                subclassIndex = Dict.empty,
                companionIndex = Dict.empty,
                moduleIndex = Dict.empty,
                errors = Chunk.empty
            )
        }

    "show(FullyQualified) returns dotted fully-qualified name" in {
        buildFixture.map { classpath =>
            val c   = classpath.findClass("scala.collection.List").get
            val out = classpath.show(c, Tasty.ShowFormat.FullyQualified)
            assert(out == "scala.collection.List", s"Unexpected: $out")
        }
    }

    "show(Simple) returns simple name" in {
        buildFixture.map { classpath =>
            val c   = classpath.findClass("scala.collection.List").get
            val out = classpath.show(c, Tasty.ShowFormat.Simple)
            assert(out == "List", s"Unexpected: $out")
        }
    }

    "show(Code) for method starts with def name and has params" in {
        buildFixture.map { classpath =>
            val m = classpath.symbol(SymbolId(4)) match
                case Maybe.Present(m: Tasty.Symbol.Method) => m
                case other                                 => fail(s"expected Symbol.Method at id 4, got $other")
            val out = classpath.show(m, Tasty.ShowFormat.Code)
            assert(out.startsWith("def foo"), s"Expected 'def foo...' but got: $out")
            assert(out.contains("(x: "), s"Expected '(x: ' in: $out")
        }
    }

    "show(Code) for class contains kind, name, type params, and extends clause" in {
        buildFixture.map { classpath =>
            val c   = classpath.findClass("scala.collection.List").get
            val out = classpath.show(c, Tasty.ShowFormat.Code)
            assert(out.contains("class List"), s"Expected 'class List' in: $out")
            assert(out.contains("[A]"), s"Expected '[A]' in: $out")
            assert(out.contains("extends"), s"Expected 'extends' in: $out")
            assert(out.contains("D"), s"Expected 'D' in: $out")
        }
    }

end SymbolShowFormatTest
