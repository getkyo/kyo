package kyo

import kyo.Tasty.SymbolId

/** Symbol convenience accessors.
  *
  * Covers: fullNameString, simpleName, ownersChain, owner.
  */
class SymbolConvenienceTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    private def makeClass(
        id: Int,
        name: String,
        ownerId: Int,
        decls: Chunk[SymbolId] = Chunk.empty
    ): Tasty.Symbol.Class =
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
            decls,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty
        )

    private def makeMethod(id: Int, name: String, ownerId: Int): Tasty.Symbol.Method =
        Tasty.Symbol.Method(
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
            Maybe.Absent
        )

    private def makePackage(id: Int, name: String, ownerId: Int, members: Chunk[SymbolId]): Tasty.Symbol.Package =
        Tasty.Symbol.Package(SymbolId(id), Tasty.Name(name), Tasty.Flags.empty, SymbolId(ownerId), members)

    "fullNameString returns dotted fully-qualified name" in {
        Sync.defer {
            val pkg = makePackage(0, "scala.collection", ownerId = -1, Chunk(SymbolId(1)))
            val cls = makeClass(1, "List", ownerId = 0)
            val classpath = Tasty.Classpath.make(
                symbols = Chunk(pkg, cls),
                rootSymbolId = SymbolId(-1),
                topLevelClassIds = Chunk(SymbolId(1)),
                packageIds = Chunk(SymbolId(0)),
                fullNameIndex = Dict("scala.collection.List" -> SymbolId(1)),
                packageIndex = Dict("scala.collection" -> SymbolId(0)),
                subclassIndex = Dict.empty,
                companionIndex = Dict.empty,
                moduleIndex = Dict.empty,
                errors = Chunk.empty
            )
            classpath.computeFullName(cls)
        }.map { fullName =>
            assert(fullName.asString == "scala.collection.List", s"Expected scala.collection.List but got ${fullName.asString}")
            succeed
        }
    }

    "simpleName returns the symbol's simple name" in {
        val symbol = makeClass(0, "Foo", ownerId = -1)
        assert(symbol.simpleName == "Foo")
        succeed
    }

    "ownersChain returns self-first chain" in {
        val pkg  = makePackage(0, "pkg", ownerId = -1, Chunk(SymbolId(1)))
        val clsA = makeClass(1, "A", ownerId = 0, Chunk(SymbolId(2)))
        val clsB = makeClass(2, "B", ownerId = 1, Chunk(SymbolId(3)))
        val clsC = makeClass(3, "C", ownerId = 2, Chunk(SymbolId(4)))
        val clsD = makeClass(4, "D", ownerId = 3)
        val classpath = Tasty.Classpath.make(
            symbols = Chunk(pkg, clsA, clsB, clsC, clsD),
            rootSymbolId = SymbolId(-1),
            topLevelClassIds = Chunk(SymbolId(1)),
            packageIds = Chunk(SymbolId(0)),
            fullNameIndex = Dict.empty,
            packageIndex = Dict("pkg" -> SymbolId(0)),
            subclassIndex = Dict.empty,
            companionIndex = Dict.empty,
            moduleIndex = Dict.empty,
            errors = Chunk.empty
        )
        val chain = classpath.ownersChain(clsD)
        assert(chain.map(_.simpleName) == Chunk("D", "C", "B", "A", "pkg"), s"Unexpected chain: ${chain.map(_.simpleName)}")
    }

    "ownersChain stops on self-loop" in {
        val pkg = makePackage(5, "root", ownerId = 5, Chunk.empty)
        val classpath = Tasty.Classpath.make(
            symbols = Chunk(pkg),
            rootSymbolId = SymbolId(5),
            topLevelClassIds = Chunk.empty,
            packageIds = Chunk(SymbolId(5)),
            fullNameIndex = Dict.empty,
            packageIndex = Dict("root" -> SymbolId(5)),
            subclassIndex = Dict.empty,
            companionIndex = Dict.empty,
            moduleIndex = Dict.empty,
            errors = Chunk.empty
        )
        val chain = classpath.ownersChain(pkg)
        assert(chain.size == 1, s"Expected size 1 for self-loop but got ${chain.size}")
    }

    "owner returns Present for method with owner" in {
        Sync.defer {
            val cls = makeClass(0, "A", ownerId = -1)
            val m   = makeMethod(1, "foo", ownerId = 0)
            val classpath = Tasty.Classpath.make(
                symbols = Chunk(cls, m),
                rootSymbolId = SymbolId(-1),
                topLevelClassIds = Chunk(SymbolId(0)),
                packageIds = Chunk.empty,
                fullNameIndex = Dict.empty,
                packageIndex = Dict.empty,
                subclassIndex = Dict.empty,
                companionIndex = Dict.empty,
                moduleIndex = Dict.empty,
                errors = Chunk.empty
            )
            (if m.ownerId.value == -1 then Maybe.Absent else classpath.symbol(m.ownerId)) match
                case Maybe.Present(a) =>
                    assert(a.simpleName == "A", s"Expected A but got ${a.simpleName}")
                    succeed
                case Maybe.Absent =>
                    fail("Expected Present but got Absent")
            end match
        }
    }

    "owner returns Absent for root symbol" in {
        val symbol = makeClass(0, "Root", ownerId = -1)
        import AllowUnsafe.embrace.danger
        val classpath = Tasty.Classpath.make(
            symbols = Chunk(symbol),
            rootSymbolId = SymbolId(-1),
            topLevelClassIds = Chunk(SymbolId(0)),
            packageIds = Chunk.empty,
            fullNameIndex = Dict.empty,
            packageIndex = Dict.empty,
            subclassIndex = Dict.empty,
            companionIndex = Dict.empty,
            moduleIndex = Dict.empty,
            errors = Chunk.empty
        )
        assert((if symbol.ownerId.value == -1 then Maybe.Absent else Maybe(classpath.symbol(symbol.ownerId))) == Maybe.Absent)
        succeed
    }

end SymbolConvenienceTest
