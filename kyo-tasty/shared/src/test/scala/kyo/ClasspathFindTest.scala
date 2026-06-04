package kyo

import scala.concurrent.Future

/** Tests for Classpath find methods (Phase 10 Item 3).
  *
  * Leaf ids: 3, 4, 5, 6. Pins: INV-007.
  */
class ClasspathFindTest extends Test:

    import AllowUnsafe.embrace.danger

    private def emptyClasspath(using Frame): Tasty.Classpath < Sync =
        import kyo.internal.tasty.type_.TypeArena
        Sync.defer:
            Tasty.Classpath.make(
                symbols = Chunk.empty,
                rootSymbolId = kyo.Tasty.SymbolId(-1),
                topLevelClassIds = Chunk.empty,
                packageIds = Chunk.empty,
                fqnIndex = Map.empty,
                packageIndex = Map.empty,
                subclassIndex = Map.empty,
                companionIndex = Map.empty,
                moduleIndex = Map.empty,
                errors = Chunk.empty,
                canonical = TypeArena.canonical()
            )
    end emptyClasspath

    // Leaf id:3 -- findClass returns Maybe.Absent when missing
    "findClass returns Maybe.Absent for unknown FQN" in run {
        emptyClasspath.map: cp =>
            assert(cp.findClass("p.Bar") == Maybe.Absent)
            succeed
    }

    // Leaf id:4 -- findPackage returns Maybe
    "findPackage returns Maybe.Absent for unknown package" in run {
        emptyClasspath.map: cp =>
            assert(cp.findPackage("zzz") == Maybe.Absent)
            succeed
    }

    // Leaf id:5 -- findModule returns Maybe
    "findModule returns Maybe.Absent for unknown module" in run {
        emptyClasspath.map: cp =>
            assert(cp.findModule("foo") == Maybe.Absent)
            succeed
    }

    // Leaf id:6 -- findClassByBinary handles dollar-encoded names
    "findClassByBinary translates slash and dollar to dot" in run {
        import kyo.Tasty.SymbolId
        import kyo.internal.tasty.type_.TypeArena
        Sync.defer {
            val symWithId = Tasty.Symbol.Class(
                SymbolId(0),
                Tasty.Name.fromString("Bar"),
                Tasty.Flags.empty,
                SymbolId(-1),
                kyo.Maybe.Absent,
                kyo.Maybe.Absent,
                kyo.Maybe.Absent,
                Chunk.empty,
                Chunk.empty,
                Chunk.empty,
                kyo.Maybe.Absent,
                Chunk.empty,
                Chunk.empty,
                kyo.Maybe.Absent
            )
            Tasty.Classpath.make(
                symbols = Chunk(symWithId),
                rootSymbolId = SymbolId(-1),
                topLevelClassIds = Chunk.empty,
                packageIds = Chunk.empty,
                fqnIndex = Map("p.Foo.Bar" -> SymbolId(0)),
                packageIndex = Map.empty,
                subclassIndex = Map.empty,
                companionIndex = Map.empty,
                moduleIndex = Map.empty,
                errors = Chunk.empty,
                canonical = TypeArena.canonical()
            )
        }.map: cp =>
            val result = cp.findClassByBinary("p/Foo$Bar")
            result match
                case Maybe.Present(s) =>
                    assert(s.name == Tasty.Name.fromString("Bar"), s"Expected symbol name Bar, got ${s.name}")
                    succeed
                case Maybe.Absent =>
                    fail("Expected Present(sym) for p/Foo$Bar but got Absent")
            end match
    }

end ClasspathFindTest
