package kyo

import kyo.Tasty.SymbolId
import kyo.internal.tasty.type_.TypeArena

/** Plan-mandated tests for Phase 07 (leaves 145-150): declared vs inherited member split.
  *
  * Covers: declaredMembers, allMembers, findDeclaredMember, findInheritedMember, findAnyMember, collectMembers (plural on ClassLike).
  *
  * Pins: INV-005.
  */
class SymbolMemberSearchTest extends Test:

    import AllowUnsafe.embrace.danger

    private def makeClass(
        id: Int,
        name: String,
        ownerId: Int,
        parentTypes: Chunk[Tasty.Type] = Chunk.empty,
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
            parentTypes,
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

    private def makeVal(id: Int, name: String, ownerId: Int): Tasty.Symbol.Val =
        Tasty.Symbol.Val(
            SymbolId(id),
            Tasty.Name.fromString(name),
            Tasty.Flags.empty,
            SymbolId(ownerId),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Maybe.Absent
        )

    /** Fixture: class A { def foo; val x } Symbol ids: 0=A, 1=foo, 2=x
      */
    private def buildClassA(using Frame): Tasty.Classpath < Sync =
        Sync.defer:
            val foo  = makeMethod(1, "foo", ownerId = 0)
            val x    = makeVal(2, "x", ownerId = 0)
            val clsA = makeClass(0, "A", ownerId = -1, decls = Chunk(SymbolId(1), SymbolId(2)))
            Tasty.Classpath.make(
                symbols = Chunk(clsA, foo, x),
                rootSymbolId = SymbolId(-1),
                topLevelClassIds = Chunk(SymbolId(0)),
                packageIds = Chunk.empty,
                fqnIndex = Map("A" -> SymbolId(0)),
                packageIndex = Map.empty,
                subclassIndex = Map.empty,
                companionIndex = Map.empty,
                moduleIndex = Map.empty,
                errors = Chunk.empty,
                canonical = TypeArena.canonical()
            )

    /** Fixture: class A { def foo }; class B extends A { def bar } Symbol ids: 0=A, 1=foo, 2=B, 3=bar B.parentTypes =
      * Chunk(Type.Named(SymbolId(0)))
      */
    private def buildInheritanceFixture(using Frame): Tasty.Classpath < Sync =
        Sync.defer:
            val foo  = makeMethod(1, "foo", ownerId = 0)
            val bar  = makeMethod(3, "bar", ownerId = 2)
            val clsA = makeClass(0, "A", ownerId = -1, decls = Chunk(SymbolId(1)))
            val clsB = makeClass(
                2,
                "B",
                ownerId = -1,
                parentTypes = Chunk(Tasty.Type.Named(SymbolId(0))),
                decls = Chunk(SymbolId(3))
            )
            Tasty.Classpath.make(
                symbols = Chunk(clsA, foo, clsB, bar),
                rootSymbolId = SymbolId(-1),
                topLevelClassIds = Chunk(SymbolId(0), SymbolId(2)),
                packageIds = Chunk.empty,
                fqnIndex = Map("A" -> SymbolId(0), "B" -> SymbolId(2)),
                packageIndex = Map.empty,
                subclassIndex = Map.empty,
                companionIndex = Map.empty,
                moduleIndex = Map.empty,
                errors = Chunk.empty,
                canonical = TypeArena.canonical()
            )

    /** Fixture: class A { def foo(x: Int); def foo(s: String) } (overloaded) Symbol ids: 0=A, 1=foo(Int), 2=foo(String)
      */
    private def buildOverloadedFixture(using Frame): Tasty.Classpath < Sync =
        Sync.defer:
            val foo1 = makeMethod(1, "foo", ownerId = 0)
            val foo2 = makeMethod(2, "foo", ownerId = 0)
            val clsA = makeClass(0, "A", ownerId = -1, decls = Chunk(SymbolId(1), SymbolId(2)))
            Tasty.Classpath.make(
                symbols = Chunk(clsA, foo1, foo2),
                rootSymbolId = SymbolId(-1),
                topLevelClassIds = Chunk(SymbolId(0)),
                packageIds = Chunk.empty,
                fqnIndex = Map("A" -> SymbolId(0)),
                packageIndex = Map.empty,
                subclassIndex = Map.empty,
                companionIndex = Map.empty,
                moduleIndex = Map.empty,
                errors = Chunk.empty,
                canonical = TypeArena.canonical()
            )

    // ── Leaf 145: declaredMembers-on-classlike ────────────────────────────────
    // Given: class A { def foo; val x }
    // When: a.declaredMembers.map(_.simpleName)
    // Then: returns Chunk("foo", "x")
    // Pins: INV-005
    "Leaf 145: declaredMembers returns direct declarations of ClassLike" in run {
        buildClassA.map: cp =>
            val a     = cp.findClass("A").get
            val names = a.declaredMembers(using cp).map(_.simpleName)
            assert(names == Chunk("foo", "x"), s"Unexpected names: $names")
            succeed
    }

    // ── Leaf 146: declaredMembers-on-non-classlike-empty ──────────────────────
    // Given: a Symbol.Method m.
    // When: m.declaredMembers.
    // Then: returns Chunk.empty.
    // Pins: INV-005
    "Leaf 146: declaredMembers returns empty for non-ClassLike symbol" in run {
        buildClassA.map: cp =>
            val m = cp.symbol(SymbolId(1)).asInstanceOf[Tasty.Symbol.Method]
            assert(m.declaredMembers(using cp) == Chunk.empty)
            succeed
    }

    // ── Leaf 147: allMembers-includes-inherited ───────────────────────────────
    // Given: class A { def foo }; class B extends A { def bar }
    // When: b.allMembers.map(_.simpleName).toSet
    // Then: returns Set("bar", "foo")
    // Pins: INV-005
    "Leaf 147: allMembers includes inherited members from parent ClassLike" in run {
        buildInheritanceFixture.map: cp =>
            val b     = cp.findClass("B").get
            val names = b.allMembers(using cp).map(_.simpleName).toIndexedSeq.toSet
            assert(names == Set("bar", "foo"), s"Unexpected names: $names")
            succeed
    }

    // ── Leaf 148: findDeclaredMember-vs-findInheritedMember ───────────────────
    // Given: same fixture as leaf 147.
    // When: b.findDeclaredMember("foo") and b.findInheritedMember("foo").
    // Then: declared returns Absent; inherited returns Present.
    // Pins: INV-005
    "Leaf 148: findDeclaredMember vs findInheritedMember for inherited method" in run {
        buildInheritanceFixture.map: cp =>
            val b         = cp.findClass("B").get
            val declared  = b.findDeclaredMember("foo")(using cp)
            val inherited = b.findInheritedMember("foo")(using cp)
            assert(declared == Maybe.Absent, s"Expected Absent for declared but got $declared")
            assert(inherited.isDefined, s"Expected Present for inherited but got $inherited")
            succeed
    }

    // ── Leaf 149: findAnyMember ───────────────────────────────────────────────
    // Given: same fixture as leaf 147.
    // When: b.findAnyMember("foo").
    // Then: returns Maybe.Present(_).
    // Pins: INV-005
    "Leaf 149: findAnyMember finds inherited member" in run {
        buildInheritanceFixture.map: cp =>
            val b      = cp.findClass("B").get
            val result = b.findAnyMember("foo")(using cp)
            assert(result.isDefined, s"Expected Present but got $result")
            succeed
    }

    // ── Leaf 150: collectMembers-plural ──────────────────────────────────────────
    // Given: class A { def foo(x: Int); def foo(s: String) } (overloaded).
    // When: a.collectMembers("foo").
    // Then: returns Chunk[Symbol] of size 2.
    // Pins: INV-005
    "Leaf 150: collectMembers returns all overloaded declarations" in run {
        buildOverloadedFixture.map: cp =>
            val a       = cp.findClass("A").get
            val members = a.collectMembers("foo")(using cp)
            assert(members.size == 2, s"Expected 2 but got ${members.size}")
            succeed
    }

end SymbolMemberSearchTest
