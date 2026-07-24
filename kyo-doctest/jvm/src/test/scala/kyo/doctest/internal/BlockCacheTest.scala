package kyo.doctest.internal

import kyo.*
import kyo.doctest.*

/** Tests for BlockCache covering directory creation, cache lookup and recording, cache key components, and concurrent safety. */
class BlockCacheTest extends kyo.doctest.DoctestTest:

    private def makeBlock(body: String, lineStart: Int = 1): Block =
        Block(
            file = kyo.Path("README.md"),
            lineStart = lineStart,
            lineEnd = lineStart + 3,
            body = body,
            visibility = Block.Visibility.Isolated,
            expect = Block.Expectation.Compiles,
            platform = Set(Block.Target.JVM, Block.Target.JS, Block.Target.Native),
            carrier = Block.Carrier.Visible
        )

    private def makeDiag(msg: String, severity: Driver.Diagnostic.Severity = Driver.Diagnostic.Severity.Error): Driver.Diagnostic =
        Driver.Diagnostic(severity, kyo.Path("Block.scala"), 1, 1, msg, Chunk.empty)

    // Per-test temp directory, cleaned up via Scope.acquireRelease.
    // S is an additional effect row so callers can return Async or any other effect.
    private def withTempDir[A, S](f: kyo.Path => A < (Async & Sync & Scope & S))(using Frame): A < (Async & Sync & Scope & S) =
        for
            id <- UUID.v4.map(_.show)
            dir = Path.basePaths.tmp / s"doctest-cache-test-$id"
            _   <- Abort.run[FileFsException](dir.mkDir).unit
            res <- Scope.acquireRelease(Sync.defer(dir))(_ => Abort.run[FileFsException](dir.removeAll).unit).flatMap(f)
        yield res

    "BlockCache.init creates the cache directory if missing" in {
        withTempDir { dir =>
            val subDir = dir / "new-subdir"
            subDir.exists.flatMap { existsBefore =>
                assert(!existsBefore, "subdir should not exist before init")
                BlockCache.init(subDir).flatMap { _ =>
                    subDir.exists.flatMap { existsAfter =>
                        assert(existsAfter, "subdir should exist after init")
                        subDir.isDirectory.map { isDir =>
                            assert(isDir, "subdir should be a directory")
                        }
                    }
                }
            }
        }
    }

    "lookup returns Absent for an unknown key" in {
        withTempDir { dir =>
            BlockCache.init(dir).flatMap { cache =>
                val block = makeBlock("val x = 42")
                cache.lookup(block, Chunk.empty, "fp1", "3.8.3", Chunk.empty).map { result =>
                    assert(result.isEmpty, s"expected Absent but got $result")
                }
            }
        }
    }

    "record Ok then lookup returns Present(Ok)" in {
        withTempDir { dir =>
            BlockCache.init(dir).flatMap { cache =>
                val block = makeBlock("val answer = 42")
                val ok    = Driver.Outcome.Ok(Chunk.empty)
                cache.record(block, Chunk.empty, "fp1", "3.8.3", Chunk.empty, ok).flatMap { _ =>
                    cache.lookup(block, Chunk.empty, "fp1", "3.8.3", Chunk.empty).map { result =>
                        result match
                            case Maybe.Present(entry) =>
                                entry.result match
                                    case Driver.Outcome.Ok(warnings) =>
                                        assert(warnings.isEmpty, s"expected no warnings, got $warnings")
                                    case other =>
                                        fail(s"expected Ok, got $other")
                            case Maybe.Absent =>
                                fail("expected Present but got Absent")
                    }
                }
            }
        }
    }

    "record Failed stores diagnostics and lookup replays them" in {
        withTempDir { dir =>
            BlockCache.init(dir).flatMap { cache =>
                val block  = makeBlock("val x: Int = \"oops\"")
                val errMsg = "found: String, required: Int"
                val diag   = makeDiag(errMsg)
                val failed = Driver.Outcome.Failed(Chunk(diag), Chunk.empty)
                cache.record(block, Chunk.empty, "fp1", "3.8.3", Chunk.empty, failed).flatMap { _ =>
                    cache.lookup(block, Chunk.empty, "fp1", "3.8.3", Chunk.empty).map { result =>
                        result match
                            case Maybe.Present(entry) =>
                                entry.result match
                                    case Driver.Outcome.Failed(errors, _) =>
                                        assert(errors.nonEmpty, "expected non-empty errors")
                                        assert(
                                            errors.exists(_.message.contains("required: Int")),
                                            s"expected error message to contain 'required: Int', got: ${errors.map(_.message)}"
                                        )
                                    case other =>
                                        fail(s"expected Failed, got $other")
                            case Maybe.Absent =>
                                fail("expected Present but got Absent")
                    }
                }
            }
        }
    }

    "different block bodies produce different cache entries" in {
        withTempDir { dir =>
            BlockCache.init(dir).flatMap { cache =>
                val block1 = makeBlock("val x = 1")
                val block2 = makeBlock("val x = 2")
                val ok     = Driver.Outcome.Ok(Chunk.empty)
                cache.record(block1, Chunk.empty, "fp1", "3.8.3", Chunk.empty, ok).flatMap { _ =>
                    // block2 has not been recorded yet; lookup must return Absent.
                    cache.lookup(block2, Chunk.empty, "fp1", "3.8.3", Chunk.empty).map { result =>
                        assert(result.isEmpty, s"expected Absent for different block body, got $result")
                    }
                }
            }
        }
    }

    "changing scope-closure bodies invalidates cache entry" in {
        withTempDir { dir =>
            BlockCache.init(dir).flatMap { cache =>
                val block  = makeBlock("val y = x + 1")
                val ok     = Driver.Outcome.Ok(Chunk.empty)
                val scope1 = Chunk("val x = 1")
                val scope2 = Chunk("val x = 99") // changed
                cache.record(block, scope1, "fp1", "3.8.3", Chunk.empty, ok).flatMap { _ =>
                    cache.lookup(block, scope2, "fp1", "3.8.3", Chunk.empty).map { result =>
                        assert(result.isEmpty, s"expected Absent after scope closure change, got $result")
                    }
                }
            }
        }
    }

    "changing classpath fingerprint invalidates cache entry" in {
        withTempDir { dir =>
            BlockCache.init(dir).flatMap { cache =>
                val block = makeBlock("val z = 42")
                val ok    = Driver.Outcome.Ok(Chunk.empty)
                cache.record(block, Chunk.empty, "fingerprint-v1", "3.8.3", Chunk.empty, ok).flatMap { _ =>
                    cache.lookup(block, Chunk.empty, "fingerprint-v2", "3.8.3", Chunk.empty).map { result =>
                        assert(result.isEmpty, s"expected Absent after fingerprint change, got $result")
                    }
                }
            }
        }
    }

    "changing scalaVersion invalidates cache entry" in {
        withTempDir { dir =>
            BlockCache.init(dir).flatMap { cache =>
                val block = makeBlock("val v = true")
                val ok    = Driver.Outcome.Ok(Chunk.empty)
                cache.record(block, Chunk.empty, "fp1", "3.8.3", Chunk.empty, ok).flatMap { _ =>
                    cache.lookup(block, Chunk.empty, "fp1", "3.9.0", Chunk.empty).map { result =>
                        assert(result.isEmpty, s"expected Absent after scalaVersion change, got $result")
                    }
                }
            }
        }
    }

    "changing scalac options invalidates cache entry" in {
        withTempDir { dir =>
            BlockCache.init(dir).flatMap { cache =>
                val block = makeBlock("val w = 0")
                val ok    = Driver.Outcome.Ok(Chunk.empty)
                val opts1 = Chunk("-release", "17")
                val opts2 = Chunk("-release", "21") // changed
                cache.record(block, Chunk.empty, "fp1", "3.8.3", opts1, ok).flatMap { _ =>
                    cache.lookup(block, Chunk.empty, "fp1", "3.8.3", opts2).map { result =>
                        assert(result.isEmpty, s"expected Absent after scalac options change, got $result")
                    }
                }
            }
        }
    }

    "scalac option ordering does not affect cache key" in {
        withTempDir { dir =>
            BlockCache.init(dir).flatMap { cache =>
                val block = makeBlock("val opts = 0")
                val ok    = Driver.Outcome.Ok(Chunk.empty)
                val opts1 = Chunk("-release", "17", "-Werror")
                val opts2 = Chunk("-Werror", "-release", "17") // same opts, different order
                cache.record(block, Chunk.empty, "fp1", "3.8.3", opts1, ok).flatMap { _ =>
                    cache.lookup(block, Chunk.empty, "fp1", "3.8.3", opts2).map { result =>
                        result match
                            case Maybe.Present(_) => succeed("cache hit confirms option order does not affect key")
                            case Maybe.Absent =>
                                fail("expected same cache key regardless of scalac option ordering")
                    }
                }
            }
        }
    }

    "two concurrent lookups for the same key are safe" in {
        withTempDir { dir =>
            BlockCache.init(dir).flatMap { cache =>
                val block = makeBlock("val concurrent1 = 1")
                val ok    = Driver.Outcome.Ok(Chunk.empty)
                // Pre-populate the cache entry.
                cache.record(block, Chunk.empty, "fp1", "3.8.3", Chunk.empty, ok).flatMap { _ =>
                    // Run two lookups concurrently via Async.
                    Async.zip(
                        cache.lookup(block, Chunk.empty, "fp1", "3.8.3", Chunk.empty),
                        cache.lookup(block, Chunk.empty, "fp1", "3.8.3", Chunk.empty)
                    ).map { case (r1, r2) =>
                        assert(r1.isDefined, s"concurrent lookup 1 returned Absent")
                        assert(r2.isDefined, s"concurrent lookup 2 returned Absent")
                    }
                }
            }
        }
    }

    "cache key is preimage-resistant to delimiter ambiguity" in {
        withTempDir { dir =>
            BlockCache.init(dir).flatMap { cache =>
                // Under naive space-concat these two produce identical hash input:
                //   "a b" + " " + "c"      => "a b c <rest>"
                //   "a"   + " " + "b c"    => "a b c <rest>"
                val blockA = makeBlock("a b")
                val blockB = makeBlock("a")
                val scopeA = Chunk("c")
                val scopeB = Chunk("b c")
                val ok     = Driver.Outcome.Ok(Chunk.empty)
                cache.record(blockA, scopeA, "fp", "3.8.3", Chunk.empty, ok).flatMap { _ =>
                    cache.lookup(blockB, scopeB, "fp", "3.8.3", Chunk.empty).map { result =>
                        assert(result.isEmpty, s"distinct inputs must not produce a false cache hit, got $result")
                    }
                }
            }
        }
    }

    "concurrent recordFailure and lookup for the same key are safe" in {
        withTempDir { dir =>
            BlockCache.init(dir).flatMap { cache =>
                val block  = makeBlock("val concurrent2 = 2")
                val failed = Driver.Outcome.Failed(Chunk(makeDiag("concurrent error")), Chunk.empty)
                // Run record and lookup concurrently; the exact result depends on ordering but neither should throw.
                Async.zip(
                    cache.record(block, Chunk.empty, "fp1", "3.8.3", Chunk.empty, failed),
                    cache.lookup(block, Chunk.empty, "fp1", "3.8.3", Chunk.empty)
                ).map { case (_, result) =>
                    // Either Absent (lookup ran before record) or Present(Failed) is valid; no panics.
                    result match
                        case Maybe.Absent => succeed("lookup won the race: valid outcome")
                        case Maybe.Present(e) =>
                            e.result match
                                case _: Driver.Outcome.Failed => succeed("record won the race: Failed entry present")
                                case other                    => fail(s"unexpected result: $other")
                }
            }
        }
    }

end BlockCacheTest
