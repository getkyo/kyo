package kyo

import kyo.internal.tasty.symbol.SymbolId
import kyo.internal.tasty.type_.TypeArena

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
            Tasty.Name.Unsafe.init(name),
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
            Chunk.empty,
            Maybe.Absent
        )

    private def makeTrait(id: Int, name: String, ownerId: Int): Tasty.Symbol.Trait =
        Tasty.Symbol.Trait(
            SymbolId(id),
            Tasty.Name.Unsafe.init(name),
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
            Chunk.empty,
            Maybe.Absent
        )

    private def makeObject(id: Int, name: String, ownerId: Int): Tasty.Symbol.Object =
        Tasty.Symbol.Object(
            SymbolId(id),
            Tasty.Name.Unsafe.init(name),
            Tasty.Flags.empty,
            SymbolId(ownerId),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent
        )

    private def makePackage(id: Int, name: String, ownerId: Int, members: Chunk[SymbolId]): Tasty.Symbol.Package =
        Tasty.Symbol.Package(SymbolId(id), Tasty.Name.Unsafe.init(name), Tasty.Flags.empty, SymbolId(ownerId), members)

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
                fqnIndex = Map(
                    "pkg.A"     -> SymbolId(0),
                    "pkg.T"     -> SymbolId(1),
                    "pkg.O"     -> SymbolId(2),
                    "pkg.sub.A" -> SymbolId(5)
                ),
                packageIndex = Map("pkg" -> SymbolId(4), "pkg.sub" -> SymbolId(3)),
                subclassIndex = Map.empty,
                companionIndex = Map.empty,
                moduleIndex = Map.empty,
                errors = Chunk.empty,
                canonical = TypeArena.canonical()
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
                    assert(c.isInstanceOf[Tasty.Symbol.Class], s"Expected Symbol.Class but got $c")
                    succeed
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
            succeed
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
                    assert(t.isInstanceOf[Tasty.Symbol.Trait], s"Expected Symbol.Trait but got $t")
                    succeed
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
                    assert(o.isInstanceOf[Tasty.Symbol.Object], s"Expected Symbol.Object but got $o")
                    succeed
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
                case Maybe.Present(c) => assert(c.isInstanceOf[Tasty.Symbol.Class])
                case Maybe.Absent     => fail("Expected Present for pkg.A")
            cp.findClassLike("pkg.T") match
                case Maybe.Present(t) => assert(t.isInstanceOf[Tasty.Symbol.Trait])
                case Maybe.Absent     => fail("Expected Present for pkg.T")
            cp.findClassLike("pkg.O") match
                case Maybe.Present(o) => assert(o.isInstanceOf[Tasty.Symbol.Object])
                case Maybe.Absent     => fail("Expected Present for pkg.O")
            succeed
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
                    assert(p.isInstanceOf[Tasty.Symbol.Package], s"Expected Symbol.Package but got $p")
                    succeed
                case Maybe.Absent =>
                    fail("Expected Present for pkg but got Absent")
    }

    // ── Leaf 107: findClassByName-typed ──────────────────────────────────────
    // Given: fixture with "pkg.A" (id 0) and "pkg.sub.A" (id 5), both named "A".
    // When: cp.findClassByName("A")
    // Then: Chunk[Symbol.Class] of size 2
    // Pins: INV-005
    "Leaf 107: findClassByName returns all Class instances with the given simple name" in run {
        buildFixture.map: cp =>
            val result = cp.findClassByName("A")
            assert(result.size == 2, s"Expected 2 classes named A but got ${result.size}: $result")
            assert(result.forall(_.isInstanceOf[Tasty.Symbol.Class]), "All results must be Symbol.Class")
            succeed
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
                    assert(c.isInstanceOf[Tasty.Symbol.Class], s"Expected Symbol.Class but got $c")
                    succeed
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
