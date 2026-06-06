package kyo

import scala.concurrent.Future

/** Tests for subclassesOf, implementationsOf, directSubclassesOf.
  *
  * Leaf ids: 9, 10, 16. Pins: INV-008.
  */
class SubclassesOfTest extends Test:

    import AllowUnsafe.embrace.danger

    private def flagsOf(flags: Tasty.Flag*): Tasty.Flags =
        flags.foldLeft(Tasty.Flags.empty)((acc, f) => acc.union(Tasty.Flags(f)))

    /** Build a classpath with an inheritance ladder A <: B <: C <: D.
      *
      * Symbol layout: 0: A (abstract class) 1: B (abstract class, subclass of A) 2: C (abstract class, subclass of B) 3: D (concrete class,
      * subclass of C)
      */
    private def ladderClasspath(using Frame): Tasty.Classpath < Sync =
        import kyo.Tasty.SymbolId
        import kyo.internal.tasty.type_.TypeArena
        Sync.defer {
            val absFlags  = flagsOf(Tasty.Flag.Abstract)
            val concFlags = Tasty.Flags.empty
            val a = Tasty.Symbol.Class(
                SymbolId(0),
                Tasty.Name("A"),
                absFlags,
                SymbolId(-1),
                kyo.Maybe.Absent,
                kyo.Maybe.Absent,
                kyo.Maybe.Absent,
                Chunk.empty,
                Chunk.empty,
                Chunk.empty,
                kyo.Maybe.Absent,
                Chunk.empty,
                Chunk.empty
            )
            val b = Tasty.Symbol.Class(
                SymbolId(1),
                Tasty.Name("B"),
                absFlags,
                SymbolId(-1),
                kyo.Maybe.Absent,
                kyo.Maybe.Absent,
                kyo.Maybe.Absent,
                Chunk.empty,
                Chunk.empty,
                Chunk.empty,
                kyo.Maybe.Absent,
                Chunk.empty,
                Chunk.empty
            )
            val c = Tasty.Symbol.Class(
                SymbolId(2),
                Tasty.Name("C"),
                absFlags,
                SymbolId(-1),
                kyo.Maybe.Absent,
                kyo.Maybe.Absent,
                kyo.Maybe.Absent,
                Chunk.empty,
                Chunk.empty,
                Chunk.empty,
                kyo.Maybe.Absent,
                Chunk.empty,
                Chunk.empty
            )
            val d = Tasty.Symbol.Class(
                SymbolId(3),
                Tasty.Name("D"),
                concFlags,
                SymbolId(-1),
                kyo.Maybe.Absent,
                kyo.Maybe.Absent,
                kyo.Maybe.Absent,
                Chunk.empty,
                Chunk.empty,
                Chunk.empty,
                kyo.Maybe.Absent,
                Chunk.empty,
                Chunk.empty
            )
            Tasty.Classpath.make(
                symbols = Chunk(a, b, c, d),
                rootSymbolId = SymbolId(-1),
                topLevelClassIds = Chunk(SymbolId(0), SymbolId(1), SymbolId(2), SymbolId(3)),
                packageIds = Chunk.empty,
                fqnIndex = Dict("A" -> SymbolId(0), "B" -> SymbolId(1), "C" -> SymbolId(2), "D" -> SymbolId(3)),
                packageIndex = Dict.empty,
                subclassIndex = Dict(
                    SymbolId(0) -> Chunk(SymbolId(1)),
                    SymbolId(1) -> Chunk(SymbolId(2)),
                    SymbolId(2) -> Chunk(SymbolId(3))
                ),
                companionIndex = Dict.empty,
                moduleIndex = Dict.empty,
                errors = Chunk.empty
            )
        }
    end ladderClasspath

    /** Build a classpath with trait T having concrete and abstract subclasses. */
    private def traitClasspath(using Frame): Tasty.Classpath < Sync =
        import kyo.Tasty.SymbolId
        import kyo.internal.tasty.type_.TypeArena
        Sync.defer {
            val absFlags  = flagsOf(Tasty.Flag.Abstract)
            val concFlags = Tasty.Flags.empty
            val t = Tasty.Symbol.Trait(
                SymbolId(0),
                Tasty.Name("T"),
                absFlags,
                SymbolId(-1),
                kyo.Maybe.Absent,
                kyo.Maybe.Absent,
                kyo.Maybe.Absent,
                Chunk.empty,
                Chunk.empty,
                Chunk.empty,
                kyo.Maybe.Absent,
                Chunk.empty,
                Chunk.empty
            )
            val impl1 = Tasty.Symbol.Class(
                SymbolId(1),
                Tasty.Name("Impl1"),
                concFlags,
                SymbolId(-1),
                kyo.Maybe.Absent,
                kyo.Maybe.Absent,
                kyo.Maybe.Absent,
                Chunk.empty,
                Chunk.empty,
                Chunk.empty,
                kyo.Maybe.Absent,
                Chunk.empty,
                Chunk.empty
            )
            val impl2 = Tasty.Symbol.Class(
                SymbolId(2),
                Tasty.Name("Impl2"),
                concFlags,
                SymbolId(-1),
                kyo.Maybe.Absent,
                kyo.Maybe.Absent,
                kyo.Maybe.Absent,
                Chunk.empty,
                Chunk.empty,
                Chunk.empty,
                kyo.Maybe.Absent,
                Chunk.empty,
                Chunk.empty
            )
            val abs = Tasty.Symbol.Class(
                SymbolId(3),
                Tasty.Name("AbsImpl"),
                absFlags,
                SymbolId(-1),
                kyo.Maybe.Absent,
                kyo.Maybe.Absent,
                kyo.Maybe.Absent,
                Chunk.empty,
                Chunk.empty,
                Chunk.empty,
                kyo.Maybe.Absent,
                Chunk.empty,
                Chunk.empty
            )
            Tasty.Classpath.make(
                symbols = Chunk(t, impl1, impl2, abs),
                rootSymbolId = SymbolId(-1),
                topLevelClassIds = Chunk(SymbolId(0), SymbolId(1), SymbolId(2), SymbolId(3)),
                packageIds = Chunk.empty,
                fqnIndex = Dict("T" -> SymbolId(0), "Impl1" -> SymbolId(1), "Impl2" -> SymbolId(2), "AbsImpl" -> SymbolId(3)),
                packageIndex = Dict.empty,
                subclassIndex = Dict(SymbolId(0) -> Chunk(SymbolId(1), SymbolId(2), SymbolId(3))),
                companionIndex = Dict.empty,
                moduleIndex = Dict.empty,
                errors = Chunk.empty
            )
        }
    end traitClasspath

    // Leaf id:9 -- subclassesOf is transitive
    "subclassesOf returns transitive closure in BFS order" in run {
        ladderClasspath.map: cp =>
            import Tasty.Name.asString
            val a          = cp.findClass("A").get
            val subclasses = cp.subclassesOf(a)
            val names      = subclasses.map(_.name.asString).toSet
            assert(names.contains("B"), s"Missing B in subclasses: $names")
            assert(names.contains("C"), s"Missing C in subclasses: $names")
            assert(names.contains("D"), s"Missing D in subclasses: $names")
            assert(subclasses.size == 3, s"Expected 3 subclasses, got ${subclasses.size}")
            succeed
    }

    // Leaf id:10 -- implementationsOf excludes abstract classes and traits
    "implementationsOf excludes abstract subclasses" in run {
        traitClasspath.map: cp =>
            import Tasty.Name.asString
            val t     = cp.findClassLike("T").get
            val impls = cp.implementationsOf(t)
            val names = impls.map(_.name.asString).toSet
            assert(names.contains("Impl1"), s"Missing Impl1: $names")
            assert(names.contains("Impl2"), s"Missing Impl2: $names")
            assert(!names.contains("AbsImpl"), s"AbsImpl should be excluded: $names")
            assert(impls.size == 2, s"Expected 2 implementations, got ${impls.size}")
            succeed
    }

    // Leaf id:16 partial -- directSubclassesOf returns one-level subclasses
    "directSubclassesOf returns exactly one hop" in run {
        ladderClasspath.map: cp =>
            import Tasty.Name.asString
            val a      = cp.findClass("A").get
            val direct = cp.directSubclassesOf(a)
            val names  = direct.map(_.name.asString).toSet
            assert(names == Set("B"), s"Expected only B, got $names")
            succeed
    }

    "subclassesOf returns empty chunk for leaf class" in run {
        ladderClasspath.map: cp =>
            val d   = cp.findClass("D").get
            val sub = cp.subclassesOf(d)
            assert(sub.isEmpty, s"Expected empty chunk for D, got $sub")
            succeed
    }

end SubclassesOfTest
