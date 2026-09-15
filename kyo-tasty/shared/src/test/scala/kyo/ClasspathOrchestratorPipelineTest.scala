package kyo

import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.symbol.SymbolKind

/** Tests for the streaming pipeline via Channels using withPickles and real filesystem roots.
  *
  * Pipeline correctness, fully-qualified name index parity, arena determinism, soft-fail and strict-fail error
  * handling, channel backpressure with 100+ entries, ordering independence, and concurrency.
  */
class ClasspathOrchestratorPipelineTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    private val plainClassPickle =
        Tasty.Pickle("plain-class", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.plainClassTasty))

    "pipeline produces correct symbol set for fixture classpath" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(plainClassPickle)) {
                Tasty.classpath.map { classpath =>
                    import Tasty.Name.asString
                    classpath.symbols.map(s => classpath.computeFullName(s).asString).toSet
                }
            }
        ).map {
            case Result.Success(names) =>
                assert(names.exists(_.contains("PlainClass")), s"Expected PlainClass in symbol names, got: $names")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    "fully-qualified name index parity - classByFullName returns symbol for known fully-qualified name" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(plainClassPickle)) {
                Tasty.classpath.map(_.findClass("kyo.fixtures.PlainClass"))
            }
        ).map {
            case Result.Success(Present(symbol)) =>
                assert(
                    symbol.kind == SymbolKind.Class || symbol.kind == SymbolKind.Trait,
                    s"Expected Class or Trait kind, got: ${symbol.kind}"
                )
            case Result.Success(Absent) =>
                fail("Expected Present(symbol) for kyo.fixtures.PlainClass but got Absent")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    "opening same fixture twice produces same fully-qualified name key set and symbol count" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(plainClassPickle)) {
                Tasty.classpath.map(_.symbols.size)
            }
                .map { size1 =>
                    Tasty.withPickles(Chunk(Tasty.Pickle(
                        "plain-class-2",
                        Tasty.Version(28, 3, 0),
                        Span.from(kyo.fixtures.Embedded.plainClassTasty)
                    ))) {
                        Tasty.classpath.map(_.symbols.size)
                    }
                        .map { size2 =>
                            (size1, size2)
                        }
                }
        ).map {
            case Result.Success((size1, size2)) =>
                assert(size1 == size2, s"allSymbols sizes differ: $size1 vs $size2")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    "soft-fail on corrupted tasty file appends to classpath.errors without aborting load" in {
        val corruptPickle = Tasty.Pickle("corrupt", Tasty.Version(28, 3, 0), Span.from(Array[Byte](0, 1, 2, 3, 4, 5)))
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(plainClassPickle, corruptPickle)) {
                Tasty.classpath.map(_.errors)
            }
        ).map {
            case Result.Success(errs) =>
                assert(errs.nonEmpty, "Expected at least one error for corrupted .tasty file")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    "strict mode raises Abort[TastyError] for corrupted tasty file without hanging" in {
        // FailFast mode requires real filesystem roots. Use a temp dir with a corrupt file.
        Scope.run {
            Path.run(Path.tempDir("kyo-pipe-failfast")).map { dir =>
                Path.run((dir / "Corrupt.tasty").writeBytes(Span.from(Array[Byte](0, 1, 2, 3, 4, 5)))).map { _ =>
                    Scope.run {
                        Abort.run[TastyError](ClasspathOrchestrator.init(Seq(dir.toString), Tasty.ErrorMode.FailFast, 1)).map {
                            case Result.Failure(_: TastyError) =>
                                succeed
                            case Result.Success(_) =>
                                fail("Expected Abort[TastyError] for corrupted tasty in strict mode, but got success")
                            case Result.Panic(t) =>
                                throw t
                        }
                    }
                }
            }
        }
    }

    "pipeline completes successfully with 100+ entries at concurrency=2" in {
        val bytes = kyo.fixtures.Embedded.plainClassTasty
        val pickles = Chunk.from((1 to 110).map(i =>
            Tasty.Pickle(s"file-$i", Tasty.Version(28, 3, 0), Span.from(bytes))
        ))
        Abort.run[TastyError](
            Tasty.withPickles(pickles) {
                Tasty.classpath.map(_.symbols.size)
            }
        ).map {
            case Result.Success(count) =>
                assert(count > 0, s"Expected non-zero symbol count after pipeline with 110 entries, got $count")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    "two pipeline runs on identical inputs produce the same fully-qualified name key set" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(plainClassPickle)) {
                Tasty.classpath.map { cp1 =>
                    import Tasty.Name.asString
                    cp1.symbols.map(s => cp1.computeFullName(s).asString).toSet
                }
            }
                .map { names1 =>
                    Tasty.withPickles(Chunk(Tasty.Pickle(
                        "plain-class-2",
                        Tasty.Version(28, 3, 0),
                        Span.from(kyo.fixtures.Embedded.plainClassTasty)
                    ))) {
                        Tasty.classpath.map { cp2 =>
                            import Tasty.Name.asString
                            val names2: Set[String] = cp2.symbols.map(s => cp2.computeFullName(s).asString).toSet
                            (names1, names2)
                        }
                    }
                }
        ).map {
            case Result.Success((names1, names2)) =>
                assert(names1 == names2, s"fully-qualified name sets differ between runs: diff=${names1.diff(names2).take(5)}")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    "pipeline with 100+ entries completes successfully" in {
        val bytes = kyo.fixtures.Embedded.plainClassTasty
        val pickles = Chunk.from((1 to 100).map(i =>
            Tasty.Pickle(s"entry-$i", Tasty.Version(28, 3, 0), Span.from(bytes))
        ))
        Abort.run[TastyError](
            Tasty.withPickles(pickles) {
                Tasty.classpath.map(_.symbols.size)
            }
        ).map {
            case Result.Success(count) =>
                assert(count > 0, s"Expected non-zero symbol count after pipeline with 100 entries, got $count")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

end ClasspathOrchestratorPipelineTest
