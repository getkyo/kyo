package kyo

import scala.concurrent.Future

/** Tests for Classpath find methods. */
class ClasspathFindTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    private def emptyClasspath(using Frame): Tasty.Classpath < Sync =
        import kyo.internal.tasty.type_.TypeArena
        Sync.defer {
            Tasty.Classpath.make(
                symbols = Chunk.empty,
                rootSymbolId = kyo.Tasty.SymbolId(-1),
                topLevelClassIds = Chunk.empty,
                packageIds = Chunk.empty,
                fullNameIndex = Dict.empty,
                packageIndex = Dict.empty,
                subclassIndex = Dict.empty,
                companionIndex = Dict.empty,
                moduleIndex = Dict.empty,
                errors = Chunk.empty
            )
        }
    end emptyClasspath

    "findClass returns Maybe.Absent for unknown fully-qualified name" in {
        emptyClasspath.map { classpath =>
            assert(classpath.findClass("p.Bar") == Maybe.Absent)
            succeed
        }
    }

    "findPackage returns Maybe.Absent for unknown package" in {
        emptyClasspath.map { classpath =>
            assert(classpath.findPackage("zzz") == Maybe.Absent)
            succeed
        }
    }

    "findModule returns Maybe.Absent for unknown module" in {
        emptyClasspath.map { classpath =>
            assert(classpath.findModule("foo") == Maybe.Absent)
            succeed
        }
    }

    "findClassByBinary translates slash and dollar to dot" in {
        import kyo.Tasty.SymbolId
        import kyo.internal.tasty.type_.TypeArena
        Sync.defer {
            val symWithId = Tasty.Symbol.Class(
                SymbolId(0),
                Tasty.Name("Bar"),
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
                Chunk.empty
            )
            Tasty.Classpath.make(
                symbols = Chunk(symWithId),
                rootSymbolId = SymbolId(-1),
                topLevelClassIds = Chunk.empty,
                packageIds = Chunk.empty,
                fullNameIndex = Dict("p.Foo.Bar" -> SymbolId(0)),
                packageIndex = Dict.empty,
                subclassIndex = Dict.empty,
                companionIndex = Dict.empty,
                moduleIndex = Dict.empty,
                errors = Chunk.empty
            )
        }.map { classpath =>
            val result = classpath.findClassByBinary("p/Foo$Bar")
            result match
                case Maybe.Present(s) =>
                    assert(s.name == Tasty.Name("Bar"), s"Expected symbol name Bar, got ${s.name}")
                    succeed
                case Maybe.Absent =>
                    fail("Expected Present(symbol) for p/Foo$Bar but got Absent")
            end match
        }
    }

end ClasspathFindTest
