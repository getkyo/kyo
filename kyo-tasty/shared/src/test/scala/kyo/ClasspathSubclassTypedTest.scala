package kyo

import kyo.Tasty.SymbolId

/** Tests for typed Classpath subclass queries: directSubclassesOf and implementationsOf
  * with typed arguments and typed returns.
  *
  * directSubclassesOf fixture: class A (abstract) with direct subclass B.
  * implementationsOf fixture: sealed trait T with concrete B, C, abstract AbsA (excluded),
  * and transitive concrete CFromA (via AbsA).
  */
class ClasspathSubclassTypedTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger
    import Tasty.Name.asString

    private val absFlags  = Tasty.Flags(Tasty.Flag.Abstract)
    private val concFlags = Tasty.Flags.empty

    private def makeClass(id: Int, name: String, flags: Tasty.Flags): Tasty.Symbol.Class =
        Tasty.Symbol.Class(
            SymbolId(id),
            Tasty.Name(name),
            flags,
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

    private def directSubclassFixture(using Frame): Tasty.Classpath < Sync =
        Sync.defer:
            val a = makeClass(0, "A", absFlags)
            val b = makeClass(1, "B", concFlags)
            Tasty.Classpath.make(
                symbols = Chunk(a, b),
                rootSymbolId = SymbolId(-1),
                topLevelClassIds = Chunk(SymbolId(0), SymbolId(1)),
                packageIds = Chunk.empty,
                fqnIndex = Dict("A" -> SymbolId(0), "B" -> SymbolId(1)),
                packageIndex = Dict.empty,
                subclassIndex = Dict(SymbolId(0) -> Chunk(SymbolId(1))),
                companionIndex = Dict.empty,
                moduleIndex = Dict.empty,
                errors = Chunk.empty
            )

    private def implementationsFixture(using Frame): Tasty.Classpath < Sync =
        Sync.defer:
            val t      = makeTrait(0, "T")
            val b      = makeClass(1, "B", concFlags)
            val c      = makeClass(2, "C", concFlags)
            val absA   = makeClass(3, "AbsA", absFlags)
            val cFromA = makeClass(4, "CFromA", concFlags)
            Tasty.Classpath.make(
                symbols = Chunk(t, b, c, absA, cFromA),
                rootSymbolId = SymbolId(-1),
                topLevelClassIds = Chunk(SymbolId(0), SymbolId(1), SymbolId(2), SymbolId(3), SymbolId(4)),
                packageIds = Chunk.empty,
                fqnIndex = Dict("T" -> SymbolId(0), "B" -> SymbolId(1), "C" -> SymbolId(2), "AbsA" -> SymbolId(3), "CFromA" -> SymbolId(4)),
                packageIndex = Dict.empty,
                subclassIndex = Dict(
                    SymbolId(0) -> Chunk(SymbolId(1), SymbolId(2), SymbolId(3)),
                    SymbolId(3) -> Chunk(SymbolId(4))
                ),
                companionIndex = Dict.empty,
                moduleIndex = Dict.empty,
                errors = Chunk.empty
            )

    "directSubclassesOf returns Chunk[ClassLike] with direct subclasses" in {
        directSubclassFixture.map: cp =>
            cp.findClass("A") match
                case Maybe.Present(a) =>
                    val direct: Chunk[Tasty.Symbol.ClassLike] = cp.directSubclassesOf(a)
                    assert(direct.size == 1, s"Expected 1 direct subclass but got ${direct.size}")
                    val names = direct.map(_.name.asString).toSeq.toSet
                    assert(names.contains("B"), s"Expected B in direct subclasses: $names")
                    direct.foreach:
                        case cl: Tasty.Symbol.ClassLike =>
                            ()
                        case null =>
                            fail("Expected Symbol.ClassLike but got null")
                    assert(
                        direct.map(_.name.asString).toSet == Set("B"),
                        s"Expected exactly Set(B) but got ${direct.map(_.name.asString).toSet}"
                    )
                case Maybe.Absent =>
                    fail("Expected to find class A in fixture")
    }

    "implementationsOf returns only concrete Class instances transitively" in {
        implementationsFixture.map: cp =>
            cp.findClassLike("T") match
                case Maybe.Present(t) =>
                    val impls: Chunk[Tasty.Symbol.Class] = cp.implementationsOf(t)
                    val names                            = impls.map(_.name.asString).toSeq.toSet
                    assert(names.contains("B"), s"Expected B in implementations: $names")
                    assert(names.contains("C"), s"Expected C in implementations: $names")
                    assert(names.contains("CFromA"), s"Expected CFromA (transitive) in implementations: $names")
                    assert(!names.contains("AbsA"), s"AbsA (abstract) must be excluded: $names")
                    assert(impls.size == 3, s"Expected 3 implementations but got ${impls.size}: $names")
                    impls.foreach:
                        case _: Tasty.Symbol.Class => ()
                        case null                  => fail("Expected Symbol.Class but got null")
                    assert(
                        impls.map(_.name.asString).toSet == Set("B", "C", "CFromA"),
                        s"Expected exactly Set(B, C, CFromA) but got ${impls.map(_.name.asString).toSet}"
                    )
                case Maybe.Absent =>
                    fail("Expected to find trait T in fixture")
    }

end ClasspathSubclassTypedTest
