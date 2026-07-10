package kyo.doctest

import kyo.*

/** Integration tests for Doctest.check (the public entry point).
  *
  * All tests drive the real Doctest.check public API. Internal types appear only on the RHS for verification. Fixture markdown is written
  * to temp directories to keep tests self-contained.
  */
class OrchestratorTest extends kyo.test.Test[Any]:

    // Real JVM classpath so the compiler can resolve kyo types in blocks.
    private def testClasspath(using Frame): Chunk[kyo.Path] < Sync =
        for
            cp  <- System.property[String]("java.class.path", "")
            sep <- System.property[String]("path.separator", ":")
        yield Chunk.from(cp.split(sep).filter(_.nonEmpty).map(kyo.Path(_)))

    // Helper: create a temp dir and write a named file into it; return the file path.
    private def withTempFile[A, S](
        name: String,
        content: String
    )(f: kyo.Path => A < (Sync & Async & Scope & S))(using Frame): A < (Sync & Async & Scope & S) =
        for
            id <- Random.uuid
            dir = Path.basePaths.tmp / s"kyo-doctest-orch-test-$id"
            _ <- Abort.run[FileException](Path.run(dir.mkDir)).unit
            res <- Scope.acquireRelease(Sync.defer(dir))(_ => Abort.run[FileException](Path.run(dir.removeAll)).unit).flatMap { dir =>
                val file = dir / name
                Abort.run[FileException](Path.run(file.write(content))).flatMap { _ => f(file) }
            }
        yield res

    private def withTempCacheDir[A, S](
        f: kyo.Path => A < (Sync & Async & Scope & S)
    )(using Frame): A < (Sync & Async & Scope & S) =
        for
            id <- Random.uuid
            dir = Path.basePaths.tmp / s"doctest-cache-test-$id"
            _   <- Abort.run[FileException](Path.run(dir.mkDir)).unit
            res <- Scope.acquireRelease(Sync.defer(dir))(_ => Abort.run[FileException](Path.run(dir.removeAll)).unit).flatMap(f)
        yield res

    "empty sources raises NoSourcesConfigured" in {
        withTempCacheDir { cacheDir =>
            for
                cp    <- testClasspath
                nCpus <- System.availableProcessors
                config = Doctest.Config(
                    sources = Chunk.empty,
                    classpath = cp,
                    scalaOpts = Chunk.empty,
                    cache = cacheDir,
                    parallel = nCpus
                )
                result <- Abort.run(Scope.run(Doctest.check(config)))
            yield result match
                case Result.Failure(Doctest.Error.NoSourcesConfigured) =>
                    succeed("correct error raised for empty sources")
                case Result.Success(report) =>
                    fail(s"expected NoSourcesConfigured but got success: $report")
                case Result.Failure(other) =>
                    fail(s"expected NoSourcesConfigured, got: $other")
                case Result.Panic(t) =>
                    fail(s"unexpected panic: ${t.getMessage}")
        }
    }

    "single passing block returns report with 1 compiled, 0 failures" in {
        withTempCacheDir { cacheDir =>
            val md = """|# Test
                        |
                        |```scala
                        |val x = 42
                        |```
                        |""".stripMargin
            withTempFile("README.md", md) { kyoFile =>
                for
                    cp    <- testClasspath
                    nCpus <- System.availableProcessors
                    config = Doctest.Config(
                        sources = Chunk(kyoFile),
                        classpath = cp,
                        scalaOpts = Chunk.empty,
                        cache = cacheDir,
                        parallel = nCpus
                    )
                    result <- Abort.run(Scope.run(Doctest.check(config)))
                yield result match
                    case Result.Success(report) =>
                        assert(report.totalBlocks == 1, s"expected 1 total block, got ${report.totalBlocks}")
                        assert(report.cacheHits == 0, s"expected 0 cache hits, got ${report.cacheHits}")
                        assert(report.compiled == 1, s"expected 1 compiled, got ${report.compiled}")
                        assert(report.failures.isEmpty, s"expected no failures, got ${report.failures}")
                    case Result.Failure(e) =>
                        fail(s"unexpected failure: $e")
                    case Result.Panic(t) =>
                        fail(s"unexpected panic: ${t.getMessage}")
            }
        }
    }

    // The block starts at line 3 of the markdown (the ``` opening is line 3, body on line 4).
    "single failing block returns report with 1 failure and mapped position" in {
        withTempCacheDir { cacheDir =>
            val md = """|# Test
                        |
                        |```scala
                        |val x: Int = "not an int"
                        |```
                        |""".stripMargin
            withTempFile("README.md", md) { kyoFile =>
                for
                    cp    <- testClasspath
                    nCpus <- System.availableProcessors
                    config = Doctest.Config(
                        sources = Chunk(kyoFile),
                        classpath = cp,
                        scalaOpts = Chunk.empty,
                        cache = cacheDir,
                        parallel = nCpus
                    )
                    result <- Abort.run(Scope.run(Doctest.check(config)))
                yield result match
                    case Result.Success(report) =>
                        assert(report.totalBlocks == 1, s"expected 1 total block, got ${report.totalBlocks}")
                        assert(report.compiled == 1, s"expected 1 compiled, got ${report.compiled}")
                        assert(report.failures.size == 1, s"expected 1 failure, got ${report.failures.size}")
                        val failure = report.failures(0)
                        // The block opens at line 3 (the ``` line); line must map back to that README line.
                        assert(failure.line == 3, s"expected failure line == 3, got ${failure.line}")
                        assert(failure.message.nonEmpty, "failure message should be non-empty")
                        // The failure message must reference a type-mismatch error.
                        assert(
                            failure.message.contains("Int") || failure.message.contains("String") ||
                                failure.message.contains("type mismatch") || failure.message.contains("found") ||
                                failure.message.contains("error"),
                            s"expected type-error content in message, got: ${failure.message}"
                        )
                    case Result.Failure(e) =>
                        fail(s"unexpected failure: $e")
                    case Result.Panic(t) =>
                        fail(s"unexpected panic: ${t.getMessage}")
            }
        }
    }

    "mixed pass/fail blocks reported correctly" in {
        withTempCacheDir { cacheDir =>
            val md = """|# Test
                        |
                        |```scala
                        |val good = 1
                        |```
                        |
                        |```scala
                        |val bad: Int = "wrong"
                        |```
                        |""".stripMargin
            withTempFile("README.md", md) { kyoFile =>
                for
                    cp    <- testClasspath
                    nCpus <- System.availableProcessors
                    config = Doctest.Config(
                        sources = Chunk(kyoFile),
                        classpath = cp,
                        scalaOpts = Chunk.empty,
                        cache = cacheDir,
                        parallel = nCpus
                    )
                    result <- Abort.run(Scope.run(Doctest.check(config)))
                yield result match
                    case Result.Success(report) =>
                        assert(report.totalBlocks == 2, s"expected 2 total blocks, got ${report.totalBlocks}")
                        assert(report.compiled == 2, s"expected 2 compiled, got ${report.compiled}")
                        assert(report.failures.size == 1, s"expected 1 failure, got ${report.failures.size}")
                    case Result.Failure(e) =>
                        fail(s"unexpected failure: $e")
                    case Result.Panic(t) =>
                        fail(s"unexpected panic: ${t.getMessage}")
            }
        }
    }

    "warm run has cacheHits == totalBlocks and compiled == 0" in {
        withTempCacheDir { cacheDir =>
            val md = """|# Test
                        |
                        |```scala
                        |val cached = 99
                        |```
                        |""".stripMargin
            withTempFile("README.md", md) { kyoFile =>
                for
                    cp    <- testClasspath
                    nCpus <- System.availableProcessors
                    config = Doctest.Config(
                        sources = Chunk(kyoFile),
                        classpath = cp,
                        scalaOpts = Chunk.empty,
                        cache = cacheDir,
                        parallel = nCpus
                    )
                    // First run: cold
                    r1Result <- Abort.run(Scope.run(Doctest.check(config)))
                    result <- r1Result match
                        case Result.Success(r1) =>
                            assert(r1.compiled == 1, s"first run: expected 1 compiled, got ${r1.compiled}")
                            // Second run: warm
                            Abort.run(Scope.run(Doctest.check(config))).map {
                                case Result.Success(r2) =>
                                    assert(r2.totalBlocks == 1, s"second run: expected 1 total, got ${r2.totalBlocks}")
                                    assert(r2.cacheHits == 1, s"second run: expected 1 cache hit, got ${r2.cacheHits}")
                                    assert(r2.compiled == 0, s"second run: expected 0 compiled, got ${r2.compiled}")
                                case Result.Failure(e) =>
                                    fail(s"second run unexpected failure: $e")
                                case Result.Panic(t) =>
                                    fail(s"second run unexpected panic: ${t.getMessage}")
                            }
                        case Result.Failure(e) =>
                            fail(s"first run unexpected failure: $e")
                        case Result.Panic(t) =>
                            fail(s"first run unexpected panic: ${t.getMessage}")
                yield result
            }
        }
    }

    "editing one block causes only that block to recompile" in {
        withTempCacheDir { cacheDir =>
            for
                id <- Random.uuid
                editDir = Path.basePaths.tmp / s"kyo-doctest-edit-test-$id"
                _ <- Abort.run[FileException](Path.run(editDir.mkDir)).unit
                res <-
                    Scope.acquireRelease(Sync.defer(editDir))(_ => Abort.run[FileException](Path.run(editDir.removeAll)).unit).flatMap {
                        dir =>
                            val file    = dir / "README.md"
                            val kyoFile = file
                            val md1 = """|# Test
                                 |
                                 |```scala
                                 |val a = 1
                                 |```
                                 |
                                 |```scala
                                 |val b = 2
                                 |```
                                 |""".stripMargin
                            val md2 = """|# Test
                                 |
                                 |```scala
                                 |val a = 1
                                 |```
                                 |
                                 |```scala
                                 |val b = 999
                                 |```
                                 |""".stripMargin
                            for
                                cp    <- testClasspath
                                nCpus <- System.availableProcessors
                                config = Doctest.Config(
                                    sources = Chunk(kyoFile),
                                    classpath = cp,
                                    scalaOpts = Chunk.empty,
                                    cache = cacheDir,
                                    parallel = nCpus
                                )
                                _        <- Abort.run[FileException](Path.run(file.write(md1)))
                                r1Result <- Abort.run(Scope.run(Doctest.check(config)))
                                result <- r1Result match
                                    case Result.Success(r1) =>
                                        assert(r1.compiled == 2, s"first run: expected 2 compiled, got ${r1.compiled}")
                                        // Edit one block
                                        Abort.run[FileException](Path.run(file.write(md2))).flatMap { _ =>
                                            Abort.run(Scope.run(Doctest.check(config))).map {
                                                case Result.Success(r2) =>
                                                    assert(r2.totalBlocks == 2, s"second run: expected 2 total, got ${r2.totalBlocks}")
                                                    // One cache hit, one recompile
                                                    assert(r2.cacheHits == 1, s"second run: expected 1 cache hit, got ${r2.cacheHits}")
                                                    assert(r2.compiled == 1, s"second run: expected 1 compiled, got ${r2.compiled}")
                                                case Result.Failure(e) =>
                                                    fail(s"second run unexpected failure: $e")
                                                case Result.Panic(t) =>
                                                    fail(s"second run unexpected panic: ${t.getMessage}")
                                            }
                                        }
                                    case Result.Failure(e) =>
                                        fail(s"first run unexpected failure: $e")
                                    case Result.Panic(t) =>
                                        fail(s"first run unexpected panic: ${t.getMessage}")
                            yield result
                            end for
                    }
            yield res
        }
    }

    "editing a non-first env-grouped block invalidates the unit cache" in {
        withTempCacheDir { cacheDir =>
            for
                id <- Random.uuid
                editDir = Path.basePaths.tmp / s"kyo-doctest-env-cache-test-$id"
                _ <- Abort.run[FileException](Path.run(editDir.mkDir)).unit
                res <-
                    Scope.acquireRelease(Sync.defer(editDir))(_ => Abort.run[FileException](Path.run(editDir.removeAll)).unit).flatMap {
                        dir =>
                            val file = dir / "README.md"
                            val md1 = """|# Test
                                 |
                                 |```scala doctest:scope=env:demo
                                 |val a = 1
                                 |```
                                 |
                                 |```scala doctest:scope=env:demo
                                 |val b = 2
                                 |```
                                 |""".stripMargin
                            // Only the SECOND env block changes. Before the fix the env unit's cache key was
                            // derived from the first block alone, so this edit was silently ignored (stale hit).
                            val md2 = """|# Test
                                 |
                                 |```scala doctest:scope=env:demo
                                 |val a = 1
                                 |```
                                 |
                                 |```scala doctest:scope=env:demo
                                 |val b = 999
                                 |```
                                 |""".stripMargin
                            for
                                cp    <- testClasspath
                                nCpus <- System.availableProcessors
                                config = Doctest.Config(
                                    sources = Chunk(file),
                                    classpath = cp,
                                    scalaOpts = Chunk.empty,
                                    cache = cacheDir,
                                    parallel = nCpus
                                )
                                _        <- Abort.run[FileException](Path.run(file.write(md1)))
                                r1Result <- Abort.run(Scope.run(Doctest.check(config)))
                                result <- r1Result match
                                    case Result.Success(r1) =>
                                        assert(r1.compiled == 2, s"first run: expected 2 compiled, got ${r1.compiled}")
                                        assert(r1.cacheHits == 0, s"first run: expected 0 cache hits, got ${r1.cacheHits}")
                                        Abort.run[FileException](Path.run(file.write(md2))).flatMap { _ =>
                                            Abort.run(Scope.run(Doctest.check(config))).map {
                                                case Result.Success(r2) =>
                                                    assert(r2.totalBlocks == 2, s"second run: expected 2 total, got ${r2.totalBlocks}")
                                                    // Editing the second env block must invalidate the whole unit:
                                                    // both blocks recompile, nothing is served stale from cache.
                                                    assert(r2.cacheHits == 0, s"second run: expected 0 cache hits, got ${r2.cacheHits}")
                                                    assert(r2.compiled == 2, s"second run: expected 2 compiled, got ${r2.compiled}")
                                                case Result.Failure(e) =>
                                                    fail(s"second run unexpected failure: $e")
                                                case Result.Panic(t) =>
                                                    fail(s"second run unexpected panic: ${t.getMessage}")
                                            }
                                        }
                                    case Result.Failure(e) =>
                                        fail(s"first run unexpected failure: $e")
                                    case Result.Panic(t) =>
                                        fail(s"first run unexpected panic: ${t.getMessage}")
                            yield result
                            end for
                    }
            yield res
        }
    }

    "expect=fails-compile behaviour" in {
        withTempCacheDir { cacheDir =>
            val md = """|# Test
                        |
                        |```scala doctest:expect=fails-compile
                        |val broken: Int = "wrong"
                        |```
                        |
                        |```scala doctest:expect=fails-compile
                        |val clean = 42
                        |```
                        |""".stripMargin
            withTempFile("README.md", md) { kyoFile =>
                for
                    cp    <- testClasspath
                    nCpus <- System.availableProcessors
                    config = Doctest.Config(
                        sources = Chunk(kyoFile),
                        classpath = cp,
                        scalaOpts = Chunk.empty,
                        cache = cacheDir,
                        parallel = nCpus
                    )
                    result <- Abort.run(Scope.run(Doctest.check(config)))
                yield result match
                    case Result.Success(report) =>
                        assert(report.totalBlocks == 2, s"expected 2 blocks, got ${report.totalBlocks}")
                        // First block (actually broken) should succeed (expected compile failure)
                        // Second block (actually clean) should fail (expected compile failure but compiled clean)
                        assert(report.failures.size == 1, s"expected 1 failure, got ${report.failures.size}")
                        val failure = report.failures(0)
                        assert(
                            failure.message.contains("expected compile failure but compiled clean"),
                            s"expected 'expected compile failure' in message, got: ${failure.message}"
                        )
                    case Result.Failure(e) =>
                        fail(s"unexpected failure: $e")
                    case Result.Panic(t) =>
                        fail(s"unexpected panic: ${t.getMessage}")
            }
        }
    }

    "expect=warns behaviour" in {
        withTempCacheDir { cacheDir =>
            // This test needs a block that actually emits a warning.
            // Using -deprecation with a deprecated method call.
            val md = """|# Test
                        |
                        |```scala doctest:expect=warns
                        |object DeprecatedTest {
                        |  @deprecated("old", "1.0") def oldMethod(): Int = 42
                        |  val r = oldMethod()
                        |}
                        |```
                        |
                        |```scala doctest:expect=warns
                        |val clean = 42
                        |```
                        |""".stripMargin
            withTempFile("README.md", md) { kyoFile =>
                for
                    cp    <- testClasspath
                    nCpus <- System.availableProcessors
                    config = Doctest.Config(
                        sources = Chunk(kyoFile),
                        classpath = cp,
                        scalaOpts = Chunk("-deprecation"),
                        cache = cacheDir,
                        parallel = nCpus
                    )
                    result <- Abort.run(Scope.run(Doctest.check(config)))
                yield result match
                    case Result.Success(report) =>
                        assert(report.totalBlocks == 2, s"expected 2 blocks, got ${report.totalBlocks}")
                        // Only the second (clean) block should fail: it was tagged expect=warns but emitted no warning.
                        // The first (deprecated-method) block must have succeeded: it emitted a deprecation warning as expected.
                        assert(
                            report.failures.size == 1,
                            s"expected exactly 1 failure (clean block), got ${report.failures.size}: ${report.failures}"
                        )
                        val failure = report.failures(0)
                        // The failure must be for the clean block (second block, opens at line 9 in the fixture).
                        assert(
                            failure.line > 7,
                            s"expected failure for second block (line > 7), got line=${failure.line}"
                        )
                        assert(
                            failure.message.contains("expected at least one compiler warning"),
                            s"expected warning-related failure message, got: ${failure.message}"
                        )
                    case Result.Failure(e) =>
                        fail(s"unexpected failure: $e")
                    case Result.Panic(t) =>
                        fail(s"unexpected panic: ${t.getMessage}")
            }
        }
    }

    "expect=skipped block is not compiled or cached" in {
        withTempCacheDir { cacheDir =>
            val md = """|# Test
                        |
                        |```scala doctest:expect=skipped
                        |this is not valid scala at all !!!! @@@
                        |```
                        |""".stripMargin
            withTempFile("README.md", md) { kyoFile =>
                for
                    cp    <- testClasspath
                    nCpus <- System.availableProcessors
                    config = Doctest.Config(
                        sources = Chunk(kyoFile),
                        classpath = cp,
                        scalaOpts = Chunk.empty,
                        cache = cacheDir,
                        parallel = nCpus
                    )
                    result <- Abort.run(Scope.run(Doctest.check(config)))
                yield result match
                    case Result.Success(report) =>
                        assert(report.totalBlocks == 1, s"expected 1 block, got ${report.totalBlocks}")
                        assert(report.compiled == 0, s"skipped block should not be compiled, got compiled=${report.compiled}")
                        assert(report.cacheHits == 0, s"skipped block should not use cache, got cacheHits=${report.cacheHits}")
                        assert(report.failures.isEmpty, s"skipped block should not produce failures, got ${report.failures}")
                    case Result.Failure(e) =>
                        fail(s"unexpected failure: $e")
                    case Result.Panic(t) =>
                        fail(s"unexpected panic: ${t.getMessage}")
            }
        }
    }

    "invalid classpath surfaces Abort DriverInitFailed" in {
        withTempCacheDir { cacheDir =>
            val md = """|# Test
                        |
                        |```scala
                        |val x = 42
                        |```
                        |""".stripMargin
            withTempFile("README.md", md) { kyoFile =>
                for
                    nCpus <- System.availableProcessors
                    config = Doctest.Config(
                        sources = Chunk(kyoFile),
                        classpath = Chunk(kyo.Path("/nonexistent/completely/bogus.jar")),
                        scalaOpts = Chunk.empty,
                        cache = cacheDir,
                        parallel = nCpus
                    )
                    result <- Abort.run(Scope.run(Doctest.check(config)))
                yield result match
                    case Result.Failure(_: Doctest.Error.DriverInitFailed) =>
                        succeed("DriverInitFailed wraps the classpath error")
                    case Result.Success(report) =>
                        // Some dotty versions accept a bad classpath at init time and fail at compile time.
                        // In that case the block must have failed (kyo types are unavailable on the bogus path).
                        assert(
                            report.failures.nonEmpty,
                            s"dotty accepted bad classpath at init but the block compiled clean, which is impossible: $report"
                        )
                    case Result.Panic(t) =>
                        // Dotty may panic on a missing stdlib. Only accept if the message references the bogus path
                        // or a classpath/IO-related failure, not arbitrary panics.
                        val msg   = Option(t.getMessage).getOrElse("")
                        val cause = Option(t.getCause)
                        val isClasspathRelated =
                            msg.contains("nonexistent") ||
                                msg.contains("bogus") ||
                                msg.contains("classpath") ||
                                msg.contains("class path") ||
                                msg.toLowerCase.contains("no such file") ||
                                cause.exists(_.isInstanceOf[java.io.IOException])
                        assert(
                            isClasspathRelated,
                            s"unexpected panic unrelated to bad classpath: ${t.getClass.getName}: $msg"
                        )
                    case Result.Failure(other) =>
                        fail(s"expected DriverInitFailed, got unexpected failure: $other")
            }
        }
    }

    "non-existent source file surfaces Abort SourceNotFound" in {
        withTempCacheDir { cacheDir =>
            for
                cp    <- testClasspath
                nCpus <- System.availableProcessors
                config = Doctest.Config(
                    sources = Chunk(kyo.Path("/this/does/not/exist/README.md")),
                    classpath = cp,
                    scalaOpts = Chunk.empty,
                    cache = cacheDir,
                    parallel = nCpus
                )
                result <- Abort.run(Scope.run(Doctest.check(config)))
            yield result match
                case Result.Failure(_: Doctest.Error.SourceNotFound) =>
                    succeed("correct error raised for non-existent source file")
                case Result.Success(report) =>
                    fail(s"expected SourceNotFound but got success: $report")
                case Result.Failure(other) =>
                    fail(s"expected SourceNotFound, got: $other")
                case Result.Panic(t) =>
                    fail(s"unexpected panic: ${t.getMessage}")
        }
    }

    "parallel=1 produces the same report as parallel=N" in {
        withTempCacheDir { cacheDir1 =>
            withTempCacheDir { cacheDir2 =>
                val md = """|# Test
                            |
                            |```scala
                            |val a = 1
                            |```
                            |
                            |```scala
                            |val b: Int = "wrong"
                            |```
                            |
                            |```scala
                            |val c = 3
                            |```
                            |""".stripMargin
                withTempFile("README.md", md) { kyoFile =>
                    for
                        cp    <- testClasspath
                        nCpus <- System.availableProcessors
                        configSeq = Doctest.Config(
                            sources = Chunk(kyoFile),
                            classpath = cp,
                            scalaOpts = Chunk.empty,
                            cache = cacheDir1,
                            parallel = 1
                        )
                        configPar = Doctest.Config(
                            sources = Chunk(kyoFile),
                            classpath = cp,
                            scalaOpts = Chunk.empty,
                            cache = cacheDir2,
                            parallel = nCpus.max(2)
                        )
                        seqResult <- Abort.run(Scope.run(Doctest.check(configSeq)))
                        result <- seqResult match
                            case Result.Success(rSeq) =>
                                Abort.run(Scope.run(Doctest.check(configPar))).map {
                                    case Result.Success(rPar) =>
                                        assert(rSeq.totalBlocks == rPar.totalBlocks, s"totalBlocks differ: $rSeq vs $rPar")
                                        assert(
                                            rSeq.failures.size == rPar.failures.size,
                                            s"failure counts differ: seq=${rSeq.failures.size} par=${rPar.failures.size}"
                                        )
                                        // Sort failures by (file, line) for deterministic multi-source comparison.
                                        val seqSorted = rSeq.failures.toSeq.sortBy(f => (f.file.toString, f.line))
                                        val parSorted = rPar.failures.toSeq.sortBy(f => (f.file.toString, f.line))
                                        seqSorted.zip(parSorted).foreach { case (sf, pf) =>
                                            assert(
                                                sf.file == pf.file,
                                                s"failure file mismatch: ${sf.file} vs ${pf.file}"
                                            )
                                            assert(
                                                sf.line == pf.line,
                                                s"failure line mismatch: ${sf.line} vs ${pf.line}"
                                            )
                                        }
                                        ()
                                    case Result.Failure(e) =>
                                        fail(s"par run unexpected failure: $e")
                                    case Result.Panic(t) =>
                                        fail(s"par run unexpected panic: ${t.getMessage}")
                                }
                            case Result.Failure(e) =>
                                fail(s"seq run unexpected failure: $e")
                            case Result.Panic(t) =>
                                fail(s"seq run unexpected panic: ${t.getMessage}")
                    yield result
                }
            }
        }
    }

    "predef: import visible to plain Isolated block" in {
        withTempCacheDir { cacheDir =>
            val md = """|# Predef Test
                        |
                        |```scala
                        |val r = Try("ok")
                        |```
                        |""".stripMargin
            withTempFile("README.md", md) { kyoFile =>
                for
                    cp    <- testClasspath
                    nCpus <- System.availableProcessors
                    config = Doctest.Config(
                        sources = Chunk(kyoFile),
                        classpath = cp,
                        scalaOpts = Chunk.empty,
                        cache = cacheDir,
                        parallel = nCpus,
                        predef = Chunk("import scala.util.Try")
                    )
                    result <- Abort.run(Scope.run(Doctest.check(config)))
                yield result match
                    case Result.Success(report) =>
                        assert(report.totalBlocks == 1, s"expected 1 block, got ${report.totalBlocks}")
                        assert(
                            report.failures.isEmpty,
                            s"expected predef import to make Try visible, but got failures: ${report.failures.map(_.message)}"
                        )
                    case Result.Failure(e) =>
                        fail(s"unexpected failure: $e")
                    case Result.Panic(t) =>
                        fail(s"unexpected panic: ${t.getMessage}")
            }
        }
    }

    "predef: import visible inside scope=env:NAME group" in {
        withTempCacheDir { cacheDir =>
            val md = """|# Predef Env Test
                        |
                        |```scala doctest:scope=env:tutorial
                        |val r = Try("hello")
                        |```
                        |""".stripMargin
            withTempFile("README.md", md) { kyoFile =>
                for
                    cp    <- testClasspath
                    nCpus <- System.availableProcessors
                    config = Doctest.Config(
                        sources = Chunk(kyoFile),
                        classpath = cp,
                        scalaOpts = Chunk.empty,
                        cache = cacheDir,
                        parallel = nCpus,
                        predef = Chunk("import scala.util.Try")
                    )
                    result <- Abort.run(Scope.run(Doctest.check(config)))
                yield result match
                    case Result.Success(report) =>
                        assert(report.totalBlocks == 1, s"expected 1 block, got ${report.totalBlocks}")
                        assert(
                            report.failures.isEmpty,
                            s"expected predef visible to env:tutorial group, got failures: ${report.failures.map(_.message)}"
                        )
                    case Result.Failure(e) =>
                        fail(s"unexpected failure: $e")
                    case Result.Panic(t) =>
                        fail(s"unexpected panic: ${t.getMessage}")
            }
        }
    }

    "predef: empty Chunk preserves existing behavior" in {
        withTempCacheDir { cacheDir =>
            // Block uses Try without an import; with no predef this must fail to compile.
            val md = """|# Empty Predef Test
                        |
                        |```scala
                        |val r = Try("ok")
                        |```
                        |""".stripMargin
            withTempFile("README.md", md) { kyoFile =>
                for
                    cp    <- testClasspath
                    nCpus <- System.availableProcessors
                    config = Doctest.Config(
                        sources = Chunk(kyoFile),
                        classpath = cp,
                        scalaOpts = Chunk.empty,
                        cache = cacheDir,
                        parallel = nCpus,
                        predef = Chunk.empty
                    )
                    result <- Abort.run(Scope.run(Doctest.check(config)))
                yield result match
                    case Result.Success(report) =>
                        assert(report.totalBlocks == 1, s"expected 1 block, got ${report.totalBlocks}")
                        assert(
                            report.failures.nonEmpty,
                            "expected block using Try without import to fail when predef is empty"
                        )
                    case Result.Failure(e) =>
                        fail(s"unexpected failure: $e")
                    case Result.Panic(t) =>
                        fail(s"unexpected panic: ${t.getMessage}")
            }
        }
    }

end OrchestratorTest
