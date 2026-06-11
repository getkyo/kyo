package kyo

import kyo.Tasty.ShowFormat
import kyo.Tasty.SymbolId

/** Verifies that removing Sync.defer from pure expressions in Tasty.scala does not affect
  * observable behavior: effect rows are unchanged, show and bodyTree still work correctly.
  */
class TastySyncDeferRemovalTest extends kyo.test.Test[Any]:

    private val cls = Tasty.Symbol.Class(
        SymbolId(0),
        Tasty.Name("Foo"),
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

    "showRendersSimpleNameWithoutSyncDefer" in {
        val classpath = Tasty.Classpath.fromPicklesWithSymbols(Chunk(cls))
        classpath.map { classpath =>
            val result = classpath.show(cls, ShowFormat.Simple)
            assert(result == "Foo", s"Expected 'Foo' but got '$result'")
        }
    }

    "typeShowReturnsAbsentWithoutSyncDefer" in {
        val classpath = Tasty.Classpath.fromPicklesWithSymbols(Chunk(cls))
        classpath.map { classpath =>
            Tasty.withClasspath(classpath) {
                Abort.run[TastyError](Tasty.bodyTree(cls)).map {
                    case Result.Success(Maybe.Absent) => succeed
                    case Result.Success(Maybe.Present(_)) =>
                        fail("Expected Maybe.Absent but got Maybe.Present")
                    case Result.Failure(e) =>
                        fail(s"Unexpected TastyError: $e")
                    case Result.Panic(t) =>
                        throw t
                }
            }
        }
    }

end TastySyncDeferRemovalTest
