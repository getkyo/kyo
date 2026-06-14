package kyo

import kyo.internal.tasty.symbol.SymbolKind

/** verify that classpath.symbols still returns all symbols as Chunk[Symbol].
  */
class ClasspathFlatListPreservationTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    private val plainClassPickle =
        Tasty.Pickle("plain-class", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.plainClassTasty))

    // symbols-size-equals-sum-of-per-kind
    // Given: fixture loaded classpath; When: classpath.symbols.size and sum over 14 kinds; Then: equal
    "symbols-size-equals-sum-of-per-kind: flat list size equals sum of per-kind counts" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(plainClassPickle)) {
                Tasty.classpath.map { classpath =>
                    val all    = classpath.symbols
                    val byKind = SymbolKind.values.foldLeft(0)((acc, k) => acc + all.count(_.kind == k))
                    (all.length, byKind)
                }
            }
        ).map {
            case Result.Success((total, byKind)) =>
                assert(total == byKind, s"classpath.symbols.size ($total) != sum of per-kind counts ($byKind)")
                succeed
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    // symbols-still-returns-Chunk-Symbol
    // Given: fixture loaded classpath; When: val xs: Chunk[Tasty.Symbol] = classpath.symbols; Then: compiles; size matches
    "symbols-still-returns-Chunk-Symbol: classpath.symbols has type Chunk[Tasty.Symbol]" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(plainClassPickle)) {
                Tasty.classpath.map { classpath =>
                    // Static type check: the following assignment must compile
                    val xs: Chunk[Tasty.Symbol] = classpath.symbols
                    xs.length
                }
            }
        ).map {
            case Result.Success(len) =>
                assert(len >= 0, s"Expected non-negative length but got $len")
                succeed
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

end ClasspathFlatListPreservationTest
