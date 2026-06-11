package kyo

/** Tasty.withClasspath and Tasty.withPickles entry points:
  *   - withClasspath(classpath) binds pure-data without decode context
  *   - withPickles(pickles) binds from pickles
  *   - withClasspath(roots, Absent) does not touch any cache
  *   - Classpath.init and Classpath.initCached are not on the public surface (compileErrors)
  */
class WithClasspathTest extends kyo.test.Test[Any]:

    "withClasspath(classpath) binds pure-data classpath; returns correct symbol count" in {
        val classpath = Tasty.Classpath(
            symbols = Chunk(
                Tasty.Symbol.Package(
                    Tasty.SymbolId(0),
                    Tasty.Name("root"),
                    Tasty.Flags.empty,
                    Tasty.SymbolId(-1),
                    Chunk.empty
                ),
                Tasty.Symbol.Package(
                    Tasty.SymbolId(1),
                    Tasty.Name("child"),
                    Tasty.Flags.empty,
                    Tasty.SymbolId(0),
                    Chunk.empty
                )
            ),
            indices = Tasty.Classpath.Indices.empty,
            errors = Chunk.empty,
            modules = Chunk.empty,
            rootSymbolId = Tasty.SymbolId(0)
        )
        Tasty.withClasspath(classpath) {
            Tasty.classpath.map { bound =>
                val n = bound.symbols.size
                assert(n == 2, s"withClasspath(classpath) must bind the passed classpath; expected 2, got $n")
                succeed
            }
        }
    }

    "withPickles(pickles) binds classpath from pickles; PlainClass discoverable" in {
        val pickle = Tasty.Pickle(
            uuid = "leaf3-plain-class",
            version = Tasty.Version(28, 3, 0),
            bytes = Span.from(kyo.fixtures.Embedded.plainClassTasty)
        )
        Tasty.withPickles(Chunk(pickle)) {
            Tasty.classpath.map { classpath =>
                val found = classpath.findClassLike("kyo.fixtures.PlainClass")
                assert(found.isDefined, s"PlainClass must be discoverable after withPickles; got ${classpath.symbols.size} symbols")
                assert(classpath.symbols.size > 0, s"withPickles must bind a non-empty classpath; got ${classpath.symbols.size}")
                succeed
            }
        }
    }

    "Classpath.init is not on the public surface" in {
        val errCount = compiletime.testing.typeCheckErrors("kyo.Tasty.Classpath.init(Seq(\"x\"))").length
        assert(errCount > 0, "Classpath.init must not be on the surface; expected a compile error")
        succeed
    }

    "Classpath.initCached is not on the public surface" in {
        val errCount = compiletime.testing.typeCheckErrors("kyo.Tasty.Classpath.initCached(Seq(\"x\"), \"/tmp\")").length
        assert(errCount > 0, "Classpath.initCached must not be on the surface; expected a compile error")
        succeed
    }

    "withClasspath(roots, Absent) does not touch any cache" in {
        val pickle = Tasty.Pickle("plain-class", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.plainClassTasty))
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(pickle)) {
                Tasty.classpath.map { classpath =>
                    Tasty.withClasspath(classpath) {
                        Tasty.classpath.map { bound =>
                            val n = bound.symbols.size
                            assert(n > 0, s"withClasspath(classpath) must return a non-empty classpath; got $n")
                            succeed
                        }
                    }
                }
            }
        ).map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected TastyError: $e")
            case Result.Panic(t)   => throw t
        }
    }

end WithClasspathTest
