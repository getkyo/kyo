package kyo.doctest

import kyo.*

/** Integration tests for Doctest.check covering Driver lifecycle and setup block visibility. */
class DoctestCheckTest extends kyo.test.Test[Any]:

    // The "Driver lifecycle" leaf diffs the `doctest-out*` dirs in the shared tmp dir around a run; the suite's
    // leaves run one at a time so a sibling leaf's Doctest.check does not land inside that window.
    override def config = super.config.sequential

    private def testClasspath(using Frame): Chunk[kyo.Path] < Sync =
        for
            cp  <- System.property[String]("java.class.path", "")
            sep <- System.property[String]("path.separator", ":")
        yield Chunk.from(cp.split(sep).filter(_.nonEmpty).map(kyo.Path(_)))

    private def withTempFile[A, S](
        name: String,
        content: String
    )(f: kyo.Path => A < (Sync & Async & Scope & S))(using Frame): A < (Sync & Async & Scope & S) =
        for
            id <- Random.uuid
            dir = Path.basePaths.tmp / s"doctest-check-test-$id"
            _   <- Abort.run[FileSystemException](Path.run(dir.mkDir)).unit
            res <- Scope.acquireRelease(Sync.defer(dir))(_ => Abort.run[FileSystemException](Path.run(dir.removeAll)).unit).flatMap { dir =>
                val file = dir / name
                Abort.run[FileSystemException](Path.run(file.write(content))).flatMap { _ => f(file) }
            }
        yield res

    private def withTempCacheDir[A, S](
        f: kyo.Path => A < (Sync & Async & Scope & S)
    )(using Frame): A < (Sync & Async & Scope & S) =
        for
            id <- Random.uuid
            dir = Path.basePaths.tmp / s"doctest-cache-check-$id"
            _   <- Abort.run[FileSystemException](Path.run(dir.mkDir)).unit
            res <- Scope.acquireRelease(Sync.defer(dir))(_ => Abort.run[FileSystemException](Path.run(dir.removeAll)).unit).flatMap(f)
        yield res

    // Doctest.check opens and closes the Driver via Scope.acquireRelease; the Driver's output dir is what its
    // finalizer removes, so a doctest-out dir left behind is a finalizer that did not run.
    "Driver lifecycle: Scope.acquireRelease ensures cleanup" in {
        withTempCacheDir { cacheDir =>
            val md = """|# Test
                        |
                        |```scala
                        |val scoped = true
                        |```
                        |""".stripMargin
            withTempFile("README.md", md) { kyoFile =>
                // The doctest-out* dirs under the shared tmp dir. Sibling suites (OrchestratorTest, CorpusTest, DriverTest)
                // create their own there concurrently, so the check is on the dirs that appeared during this run: each is
                // gone once its run ended, ours the moment Scope.run returned, and a dir ours leaked never goes.
                val tempDirBase                                                               = Path.basePaths.tmp
                def outDirs()(using Frame): Set[String] < (Sync & Abort[FileSystemException]) =
                    Path.runReadOnly(tempDirBase.list).map { entries =>
                        entries.map(_.name.getOrElse("")).filter(_.startsWith("doctest-out")).toSet
                    }
                def outDirsOrNone()(using Frame): Set[String] < Sync =
                    Abort.run[FileSystemException](outDirs()).map(_.getOrElse(Set.empty))

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
                    before <- outDirsOrNone()
                    // Run with Scope.run, which triggers all finalizers.
                    result <- Abort.run(Scope.run(Doctest.check(config)))
                    after  <- outDirsOrNone()
                    appeared = after -- before
                    _ <- assertEventually(outDirsOrNone().map(now => (now & appeared).isEmpty))
                yield result match
                    case Result.Success(_) => succeed
                    case Result.Failure(e) => fail(s"unexpected failure: $e")
                    case Result.Panic(t)   => fail(s"unexpected panic: ${t.getMessage}")
                end for
            }
        }
    }

    // A file WITH a setup block: subsequent Isolated blocks see the setup bindings.
    // A file WITHOUT a setup block: Isolated blocks do NOT see setup-only types (compile error if they reference them).
    "setup-having file: isolated block sees setup bindings" in {
        withTempCacheDir { cacheDir =>
            // The setup block defines `case class SetupType(n: Int)`.
            // The subsequent isolated block uses `SetupType` without redefining it.
            // Because the file HAS a setup block, the isolated block should inherit setup bindings
            // and compile without error.
            val md = """|# Setup Test
                        |
                        |<!-- doctest:setup
                        |```scala
                        |case class SetupTypeA(n: Int)
                        |```
                        |-->
                        |
                        |```scala
                        |val inst = SetupTypeA(42)
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
                        assert(
                            report.failures.isEmpty,
                            s"expected setup bindings visible to isolated block, but got failures: ${report.failures.map(_.message)}"
                        )
                    case Result.Failure(e) =>
                        fail(s"unexpected failure: $e")
                    case Result.Panic(t) =>
                        fail(s"unexpected panic: ${t.getMessage}")
            }
        }
    }

    "setup-free file: isolated block does not see undeclared bindings" in {
        withTempCacheDir { cacheDir =>
            // No setup block in this file. The isolated block tries to use `SetupTypeB` which was
            // never defined. This should produce a compile failure (not a success).
            val md = """|# No Setup Test
                        |
                        |```scala
                        |val inst = SetupTypeB(42)
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
                        // The isolated block has no access to SetupTypeB; the compile MUST fail.
                        assert(
                            report.failures.nonEmpty,
                            "expected compile failure when isolated block references undefined type in setup-free file"
                        )
                    case Result.Failure(e) =>
                        fail(s"unexpected failure: $e")
                    case Result.Panic(t) =>
                        fail(s"unexpected panic: ${t.getMessage}")
            }
        }
    }

end DoctestCheckTest
