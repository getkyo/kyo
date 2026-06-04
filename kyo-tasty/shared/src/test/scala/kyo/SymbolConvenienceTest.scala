package kyo

import kyo.Tasty.SymbolId
import kyo.internal.tasty.type_.TypeArena

/** Plan-mandated tests for Phase 07 (leaves 131-136): Symbol convenience accessors.
  *
  * Covers: fullNameString, simpleName, ownersChain, owner.
  *
  * Pins: INV-002.
  */
class SymbolConvenienceTest extends Test:

    import AllowUnsafe.embrace.danger

    private def makeClass(
        id: Int,
        name: String,
        ownerId: Int,
        decls: Chunk[SymbolId] = Chunk.empty
    ): Tasty.Symbol.Class =
        Tasty.Symbol.Class(
            SymbolId(id),
            Tasty.Name.fromString(name),
            Tasty.Flags.empty,
            SymbolId(ownerId),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            decls,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent
        )

    private def makeMethod(id: Int, name: String, ownerId: Int): Tasty.Symbol.Method =
        Tasty.Symbol.Method(
            SymbolId(id),
            Tasty.Name.fromString(name),
            Tasty.Flags.empty,
            SymbolId(ownerId),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent,
            Maybe.Absent
        )

    private def makePackage(id: Int, name: String, ownerId: Int, members: Chunk[SymbolId]): Tasty.Symbol.Package =
        Tasty.Symbol.Package(SymbolId(id), Tasty.Name.fromString(name), Tasty.Flags.empty, SymbolId(ownerId), members)

    // ── Leaf 131: fullNameString-dotted ──────────────────────────────────────
    // Given: Symbol.Class named "List" owned by package "scala.collection".
    // When: c.fullNameString.
    // Then: returns "scala.collection.List".
    // Pins: INV-002
    "Leaf 131: fullNameString returns dotted FQN" in run {
        Sync.defer {
            val pkg = makePackage(0, "scala.collection", ownerId = -1, Chunk(SymbolId(1)))
            val cls = makeClass(1, "List", ownerId = 0)
            val cp = Tasty.Classpath.make(
                symbols = Chunk(pkg, cls),
                rootSymbolId = SymbolId(-1),
                topLevelClassIds = Chunk(SymbolId(1)),
                packageIds = Chunk(SymbolId(0)),
                fqnIndex = Map("scala.collection.List" -> SymbolId(1)),
                packageIndex = Map("scala.collection" -> SymbolId(0)),
                subclassIndex = Map.empty,
                companionIndex = Map.empty,
                moduleIndex = Map.empty,
                errors = Chunk.empty,
                canonical = TypeArena.canonical()
            )
            cls.fullNameString(using summon[Frame], cp)
        }.map: fqn =>
            assert(fqn == "scala.collection.List", s"Expected scala.collection.List but got $fqn")
            succeed
    }

    // ── Leaf 132: simpleName-asString ─────────────────────────────────────────
    // Given: any Symbol whose name.asString == "Foo".
    // When: s.simpleName.
    // Then: returns "Foo".
    // Pins: INV-002
    "Leaf 132: simpleName returns the symbol's simple name" in {
        val sym = makeClass(0, "Foo", ownerId = -1)
        assert(sym.simpleName == "Foo")
        succeed
    }

    // ── Leaf 133: ownersChain-depth-bounded ───────────────────────────────────
    // Given: a 5-deep nested fixture pkg.A.B.C.D.
    // When: d.ownersChain.map(_.simpleName).
    // Then: returns Chunk("D", "C", "B", "A", "pkg").
    // Pins: INV-002
    "Leaf 133: ownersChain returns self-first chain" in run {
        Sync.defer {
            val pkg  = makePackage(0, "pkg", ownerId = -1, Chunk(SymbolId(1)))
            val clsA = makeClass(1, "A", ownerId = 0, Chunk(SymbolId(2)))
            val clsB = makeClass(2, "B", ownerId = 1, Chunk(SymbolId(3)))
            val clsC = makeClass(3, "C", ownerId = 2, Chunk(SymbolId(4)))
            val clsD = makeClass(4, "D", ownerId = 3)
            val cp = Tasty.Classpath.make(
                symbols = Chunk(pkg, clsA, clsB, clsC, clsD),
                rootSymbolId = SymbolId(-1),
                topLevelClassIds = Chunk(SymbolId(1)),
                packageIds = Chunk(SymbolId(0)),
                fqnIndex = Map.empty,
                packageIndex = Map("pkg" -> SymbolId(0)),
                subclassIndex = Map.empty,
                companionIndex = Map.empty,
                moduleIndex = Map.empty,
                errors = Chunk.empty,
                canonical = TypeArena.canonical()
            )
            val chain = clsD.ownersChain(using cp).map(_.simpleName)
            assert(chain == Chunk("D", "C", "B", "A", "pkg"), s"Unexpected chain: $chain")
            succeed
        }
    }

    // ── Leaf 134: ownersChain-self-cycle-stops ────────────────────────────────
    // Given: a synthetic fixture where ownerId == id (self-loop).
    // When: s.ownersChain.size.
    // Then: returns 1 (the cycle guard stops the walk).
    // Pins: INV-002
    "Leaf 134: ownersChain stops on self-loop" in run {
        Sync.defer {
            val pkg = makePackage(5, "root", ownerId = 5, Chunk.empty)
            val cp = Tasty.Classpath.make(
                symbols = Chunk(pkg),
                rootSymbolId = SymbolId(5),
                topLevelClassIds = Chunk.empty,
                packageIds = Chunk(SymbolId(5)),
                fqnIndex = Map.empty,
                packageIndex = Map("root" -> SymbolId(5)),
                subclassIndex = Map.empty,
                companionIndex = Map.empty,
                moduleIndex = Map.empty,
                errors = Chunk.empty,
                canonical = TypeArena.canonical()
            )
            val size = pkg.ownersChain(using cp).size
            assert(size == 1, s"Expected size 1 for self-loop but got $size")
            succeed
        }
    }

    // ── Leaf 135: owner-present ───────────────────────────────────────────────
    // Given: Symbol.Method declared in Symbol.Class A.
    // When: m.owner.
    // Then: returns Maybe.Present(a) where a.simpleName == "A".
    // Pins: INV-002
    "Leaf 135: owner returns Present for method with owner" in run {
        Sync.defer {
            val cls = makeClass(0, "A", ownerId = -1)
            val m   = makeMethod(1, "foo", ownerId = 0)
            val cp = Tasty.Classpath.make(
                symbols = Chunk(cls, m),
                rootSymbolId = SymbolId(-1),
                topLevelClassIds = Chunk(SymbolId(0)),
                packageIds = Chunk.empty,
                fqnIndex = Map.empty,
                packageIndex = Map.empty,
                subclassIndex = Map.empty,
                companionIndex = Map.empty,
                moduleIndex = Map.empty,
                errors = Chunk.empty,
                canonical = TypeArena.canonical()
            )
            m.owner(using cp) match
                case Maybe.Present(a) =>
                    assert(a.simpleName == "A", s"Expected A but got ${a.simpleName}")
                    succeed
                case Maybe.Absent =>
                    fail("Expected Present but got Absent")
            end match
        }
    }

    // ── Leaf 136: owner-root-is-absent ────────────────────────────────────────
    // Given: a symbol with ownerId == SymbolId(-1).
    // When: s.owner.
    // Then: returns Maybe.Absent.
    // Pins: INV-002
    "Leaf 136: owner returns Absent for root symbol" in {
        val sym = makeClass(0, "Root", ownerId = -1)
        import AllowUnsafe.embrace.danger
        val cp = Tasty.Classpath.make(
            symbols = Chunk(sym),
            rootSymbolId = SymbolId(-1),
            topLevelClassIds = Chunk(SymbolId(0)),
            packageIds = Chunk.empty,
            fqnIndex = Map.empty,
            packageIndex = Map.empty,
            subclassIndex = Map.empty,
            companionIndex = Map.empty,
            moduleIndex = Map.empty,
            errors = Chunk.empty,
            canonical = TypeArena.canonical()
        )
        assert(sym.owner(using cp) == Maybe.Absent)
        succeed
    }

end SymbolConvenienceTest
