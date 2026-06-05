package kyo

import kyo.Tasty.SymbolId

/** Plan-mandated tests for Phase 06 (leaves 101-109): typed Classpath find* lookups.
  *
  * Fixture layout (index == id.value): 0 -> Class "A" in pkg "pkg.A" 1 -> Trait "T" in pkg "pkg.T" 2 -> Object "O" in pkg "pkg.O" 3 ->
  * Package "sub" in pkg "pkg.sub" 4 -> Package "pkg" 5 -> Class "A" in pkg "pkg.sub.A" (same simple name, different package)
  *
  * fqnIndex: "pkg.A" -> 0, "pkg.T" -> 1, "pkg.O" -> 2 packageIndex: "pkg" -> 4, "pkg.sub" -> 3 subclassIndex: empty (these tests do not
  * exercise subclass queries)
  *
  * Pins: INV-005.
  */
class ClasspathTypedFindTest extends Test:

    import AllowUnsafe.embrace.danger

    private def makeClass(id: Int, name: String, ownerId: Int): Tasty.Symbol.Class =
        Tasty.Symbol.Class(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(ownerId),
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

    private def makeTrait(id: Int, name: String, ownerId: Int): Tasty.Symbol.Trait =
        Tasty.Symbol.Trait(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(ownerId),
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

    private def makeObject(id: Int, name: String, ownerId: Int): Tasty.Symbol.Object =
        Tasty.Symbol.Object(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(ownerId),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty
        )

    private def makePackage(id: Int, name: String, ownerId: Int, members: Chunk[SymbolId]): Tasty.Symbol.Package =
        Tasty.Symbol.Package(SymbolId(id), Tasty.Name(name), Tasty.Flags.empty, SymbolId(ownerId), members)

    private def buildFixture(using Frame): Tasty.Classpath < Sync =
        Sync.defer:
            val clsA   = makeClass(0, "A", ownerId = 4)
            val trtT   = makeTrait(1, "T", ownerId = 4)
            val objO   = makeObject(2, "O", ownerId = 4)
            val subPkg = makePackage(3, "sub", ownerId = 4, Chunk(SymbolId(5)))
            val pkg    = makePackage(4, "pkg", ownerId = -1, Chunk(SymbolId(0), SymbolId(1), SymbolId(2), SymbolId(3)))
            val clsA2  = makeClass(5, "A", ownerId = 3)
            Tasty.Classpath.make(
                symbols = Chunk(clsA, trtT, objO, subPkg, pkg, clsA2),
                rootSymbolId = SymbolId(-1),
                topLevelClassIds = Chunk(SymbolId(0), SymbolId(1), SymbolId(2)),
                packageIds = Chunk(SymbolId(3), SymbolId(4)),
                fqnIndex = Dict(
                    "pkg.A"     -> SymbolId(0),
                    "pkg.T"     -> SymbolId(1),
                    "pkg.O"     -> SymbolId(2),
                    "pkg.sub.A" -> SymbolId(5)
                ),
                packageIndex = Dict("pkg" -> SymbolId(4), "pkg.sub" -> SymbolId(3)),
                subclassIndex = Dict.empty,
                companionIndex = Dict.empty,
                moduleIndex = Dict.empty,
                errors = Chunk.empty
            )

    // ── Leaf 101: findClass-class-fqn ─────────────────────────────────────────
    // Given: fixture cp with "pkg.A" (a Class).
    // When: cp.findClass("pkg.A")
    // Then: Maybe.Present(c) where c.isInstanceOf[Symbol.Class]
    // Pins: INV-005
    "Leaf 101: findClass returns Present[Class] for a class FQN" in run {
        buildFixture.map: cp =>
            cp.findClass("pkg.A") match
                case Maybe.Present(c) =>
                    assert(c.name.asString == "A", s"Expected Class name 'A' but got '${c.name.asString}'")
                case Maybe.Absent =>
                    fail("Expected Present for pkg.A but got Absent")
    }

    // ── Leaf 102: findClass-trait-returns-absent ──────────────────────────────
    // Given: fixture with "pkg.T" (a Trait).
    // When: cp.findClass("pkg.T")
    // Then: Maybe.Absent (trait does not satisfy the Class filter)
    // Pins: INV-005
    "Leaf 102: findClass returns Absent for a trait FQN" in run {
        buildFixture.map: cp =>
            val result = cp.findClass("pkg.T")
            assert(result == Maybe.Absent, s"Expected Absent for trait pkg.T but got $result")
    }

    // ── Leaf 103: findTrait-trait ─────────────────────────────────────────────
    // Given: fixture with "pkg.T" (a Trait).
    // When: cp.findTrait("pkg.T")
    // Then: Present[Trait]
    // Pins: INV-005
    "Leaf 103: findTrait returns Present[Trait] for a trait FQN" in run {
        buildFixture.map: cp =>
            cp.findTrait("pkg.T") match
                case Maybe.Present(t) =>
                    assert(t.name.asString == "T", s"Expected Trait name 'T' but got '${t.name.asString}'")
                case Maybe.Absent =>
                    fail("Expected Present for pkg.T but got Absent")
    }

    // ── Leaf 104: findObject-object ───────────────────────────────────────────
    // Given: fixture with "pkg.O" (an Object).
    // When: cp.findObject("pkg.O")
    // Then: Present[Object]
    // Pins: INV-005
    "Leaf 104: findObject returns Present[Object] for an object FQN" in run {
        buildFixture.map: cp =>
            cp.findObject("pkg.O") match
                case Maybe.Present(o) =>
                    assert(o.name.asString == "O", s"Expected Object name 'O' but got '${o.name.asString}'")
                case Maybe.Absent =>
                    fail("Expected Present for pkg.O but got Absent")
    }

    // ── Leaf 105: findClassLike-class-trait-object ────────────────────────────
    // Given: fixture pkg.A (Class), pkg.T (Trait), pkg.O (Object).
    // When: findClassLike on each FQN
    // Then: each returns Present with matching subtype
    // Pins: INV-005
    "Leaf 105: findClassLike returns Present[ClassLike] for class, trait, and object FQNs" in run {
        buildFixture.map: cp =>
            cp.findClassLike("pkg.A") match
                case Maybe.Present(c: Tasty.Symbol.Class) =>
                    assert(c.name.asString == "A", s"Expected Class name 'A' but got '${c.name.asString}'")
                case Maybe.Present(other) => fail(s"Expected Symbol.Class but got $other")
                case Maybe.Absent         => fail("Expected Present for pkg.A")
            end match
            cp.findClassLike("pkg.T") match
                case Maybe.Present(t: Tasty.Symbol.Trait) =>
                    assert(t.name.asString == "T", s"Expected Trait name 'T' but got '${t.name.asString}'")
                case Maybe.Present(other) => fail(s"Expected Symbol.Trait but got $other")
                case Maybe.Absent         => fail("Expected Present for pkg.T")
            end match
            cp.findClassLike("pkg.O") match
                case Maybe.Present(o: Tasty.Symbol.Object) =>
                    assert(o.name.asString == "O", s"Expected Object name 'O' but got '${o.name.asString}'")
                case Maybe.Present(other) => fail(s"Expected Symbol.Object but got $other")
                case Maybe.Absent         => fail("Expected Present for pkg.O")
            end match
    }

    // ── Leaf 106: findPackage-typed ───────────────────────────────────────────
    // Given: fixture with "pkg" package.
    // When: cp.findPackage("pkg")
    // Then: Present[Package]
    // Pins: INV-005
    "Leaf 106: findPackage returns Present[Package] for a package FQN" in run {
        buildFixture.map: cp =>
            cp.findPackage("pkg") match
                case Maybe.Present(p) =>
                    assert(p.name.asString == "pkg", s"Expected Package name 'pkg' but got '${p.name.asString}'")
                case Maybe.Absent =>
                    fail("Expected Present for pkg but got Absent")
    }

    // ── Leaf 107: findClassesByName-typed ──────────────────────────────────────
    // Given: fixture with "pkg.A" (id 0) and "pkg.sub.A" (id 5), both named "A".
    // When: cp.findClassesByName("A")
    // Then: Chunk[Symbol.Class] of size 2
    // Pins: INV-005
    "Leaf 107: findClassesByName returns all Class instances with the given simple name" in run {
        buildFixture.map: cp =>
            val result = cp.findClassesByName("A")
            assert(result.size == 2, s"Expected 2 classes named A but got ${result.size}: $result")
            result.foreach:
                case _: Tasty.Symbol.Class => ()
                case null                  => fail("Expected Symbol.Class but got null")
            assert(result.forall(_.name.asString == "A"), s"All results must be named 'A' but got: ${result.map(_.name.asString)}")
    }

    // ── Leaf 108: findClassByBinary-typed ────────────────────────────────────
    // Given: fixture with "pkg.A" in fqnIndex.
    // When: cp.findClassByBinary("pkg/A")
    // Then: Present[Class]
    // Pins: INV-005
    "Leaf 108: findClassByBinary returns Present[Class] for a binary name" in run {
        buildFixture.map: cp =>
            cp.findClassByBinary("pkg/A") match
                case Maybe.Present(c) =>
                    assert(c.name.asString == "A", s"Expected Class name 'A' but got '${c.name.asString}'")
                case Maybe.Absent =>
                    fail("Expected Present for pkg/A but got Absent")
    }

    // ── Leaf 109: findClass-missing-fqn-absent ────────────────────────────────
    // Given: fixture with no "does.not.exist" symbol.
    // When: cp.findClass("does.not.exist")
    // Then: Maybe.Absent
    // Pins: INV-004
    "Leaf 109: findClass returns Absent for a missing FQN" in run {
        buildFixture.map: cp =>
            val result = cp.findClass("does.not.exist")
            assert(result == Maybe.Absent, s"Expected Absent for missing FQN but got $result")
            succeed
    }

end ClasspathTypedFindTest
