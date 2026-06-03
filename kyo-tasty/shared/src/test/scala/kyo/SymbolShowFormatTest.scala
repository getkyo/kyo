package kyo

import kyo.internal.tasty.symbol.SymbolId
import kyo.internal.tasty.type_.TypeArena

/** Plan-mandated tests for Phase 07 (leaves 151-154): Symbol.show(format: ShowFormat).
  *
  * Covers: ShowFormat.FullyQualified, ShowFormat.Simple, ShowFormat.Code for class and method.
  *
  * Pins: INV-009.
  */
class SymbolShowFormatTest extends Test:

    import AllowUnsafe.embrace.danger

    private def makePackage(id: Int, name: String): Tasty.Symbol.Package =
        Tasty.Symbol.Package(SymbolId(id), Tasty.Name.Unsafe.init(name), Tasty.Flags.empty, SymbolId(-1), Chunk.empty)

    private def makeClass(
        id: Int,
        name: String,
        ownerId: Int,
        parentTypes: Chunk[Tasty.Type] = Chunk.empty,
        tpIds: Chunk[SymbolId] = Chunk.empty
    ): Tasty.Symbol.Class =
        Tasty.Symbol.Class(
            SymbolId(id),
            Tasty.Name.Unsafe.init(name),
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
            Chunk.empty,
            Maybe.Absent
        )

    private def makeTypeParam(id: Int, name: String, ownerId: Int): Tasty.Symbol.TypeParam =
        Tasty.Symbol.TypeParam(
            SymbolId(id),
            Tasty.Name.Unsafe.init(name),
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
            Tasty.Name.Unsafe.init(name),
            Tasty.Flags.empty,
            SymbolId(ownerId),
            Maybe.Absent,
            Maybe.Absent,
            declaredType,
            paramListIds,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent,
            Maybe.Absent
        )

    private def makeParameter(id: Int, name: String, ownerId: Int, tpe: Tasty.Type): Tasty.Symbol.Parameter =
        Tasty.Symbol.Parameter(
            SymbolId(id),
            Tasty.Name.Unsafe.init(name),
            Tasty.Flags.empty,
            SymbolId(ownerId),
            Maybe.Absent,
            tpe,
            Maybe.Absent,
            Chunk.empty
        )

    /** Fixture: Package "scala.collection" -> Class "List"[A] extends D Method "foo"(x: List): String
      *
      * Symbol ids: 0 = Package "scala.collection" 1 = Class "List" (typeParam id=2) 2 = TypeParam "A" owned by 1 3 = Class "D" (parent of
      * List) -- in same pkg 4 = Method "foo" with 1 param: x: List, return type: List 5 = Parameter "x" with type Named(1)
      */
    private def buildFixture(using Frame): Tasty.Classpath < Sync =
        Sync.defer:
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
                fqnIndex = Map(
                    "scala.collection.List" -> SymbolId(1),
                    "scala.collection.D"    -> SymbolId(3)
                ),
                packageIndex = Map("scala.collection" -> SymbolId(0)),
                subclassIndex = Map.empty,
                companionIndex = Map.empty,
                moduleIndex = Map.empty,
                errors = Chunk.empty,
                canonical = TypeArena.canonical()
            )

    // ── Leaf 151: show-FullyQualified ─────────────────────────────────────────
    // Given: Symbol.Class List in scala.collection.
    // When: c.show(ShowFormat.FullyQualified).
    // Then: returns "scala.collection.List".
    // Pins: INV-009
    "Leaf 151: show(FullyQualified) returns dotted FQN" in run {
        buildFixture.map: cp =>
            val c   = cp.findClass("scala.collection.List").get
            val out = c.show(Tasty.ShowFormat.FullyQualified)(using cp)
            assert(out == "scala.collection.List", s"Unexpected: $out")
            succeed
    }

    // ── Leaf 152: show-Simple ─────────────────────────────────────────────────
    // Given: same symbol.
    // When: c.show(ShowFormat.Simple).
    // Then: returns "List".
    // Pins: INV-009
    "Leaf 152: show(Simple) returns simple name" in run {
        buildFixture.map: cp =>
            val c   = cp.findClass("scala.collection.List").get
            val out = c.show(Tasty.ShowFormat.Simple)(using cp)
            assert(out == "List", s"Unexpected: $out")
            succeed
    }

    // ── Leaf 153: show-Code-method ────────────────────────────────────────────
    // Given: Symbol.Method "foo"(x: ...) from def foo(x: List): String.
    // When: m.show(ShowFormat.Code).
    // Then: returns a string starting with "def foo" and containing "(x: ".
    // Pins: INV-009
    "Leaf 153: show(Code) for method starts with def name and has params" in run {
        buildFixture.map: cp =>
            val m   = cp.symbol(SymbolId(4)).asInstanceOf[Tasty.Symbol.Method]
            val out = m.show(Tasty.ShowFormat.Code)(using cp)
            assert(out.startsWith("def foo"), s"Expected 'def foo...' but got: $out")
            assert(out.contains("(x: "), s"Expected '(x: ' in: $out")
            succeed
    }

    // ── Leaf 154: show-Code-classlike ─────────────────────────────────────────
    // Given: Symbol.Class "List"[A] extends D.
    // When: c.show(ShowFormat.Code).
    // Then: returns string containing "class List", "[A]", and "extends D".
    // Pins: INV-009
    "Leaf 154: show(Code) for class contains kind, name, type params, and extends clause" in run {
        buildFixture.map: cp =>
            val c   = cp.findClass("scala.collection.List").get
            val out = c.show(Tasty.ShowFormat.Code)(using cp)
            assert(out.contains("class List"), s"Expected 'class List' in: $out")
            assert(out.contains("[A]"), s"Expected '[A]' in: $out")
            assert(out.contains("extends"), s"Expected 'extends' in: $out")
            assert(out.contains("D"), s"Expected 'D' in: $out")
            succeed
    }

end SymbolShowFormatTest
