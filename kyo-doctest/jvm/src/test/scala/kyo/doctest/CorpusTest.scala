package kyo.doctest

import kyo.*
import kyo.doctest.internal.Block
import kyo.doctest.internal.MarkdownParser

/** Validates the corpus fixture files and the kyo-doctest README itself.
  *
  * Each test loads one fixture from the resources/corpus/ directory, runs Doctest.check against it, and asserts the expected Report shape.
  * The final test self-validates README.md.
  */
class CorpusTest extends Test:

    // JVM classpath so the compiler can resolve types.
    private def testClasspath(using Frame): Chunk[kyo.Path] < Sync =
        for
            cp  <- System.property[String]("java.class.path", "")
            sep <- System.property[String]("path.separator", ":")
        yield Chunk.from(cp.split(sep).filter(_.nonEmpty).map(kyo.Path(_)))

    // Locate a fixture file from the corpus resources directory.
    // getClassLoader.getResource returns a java.net.URL; we convert via URI.getPath to get a filesystem path.
    private def fixtureFile(name: String): kyo.Path =
        val url = getClass.getClassLoader.getResource(s"corpus/$name")
        if url == null then throw new IllegalArgumentException(s"corpus fixture not found: corpus/$name")
        // url.toURI.getPath converts a file:// URL to an absolute filesystem path string.
        kyo.Path(url.toURI.getPath)
    end fixtureFile

    // Helper: run Doctest.check on a file path with a fresh temp cache.
    private def runCheck[S](
        filePath: kyo.Path,
        scalaOpts: Chunk[String] = Chunk.empty
    )(using Frame): Doctest.Report < (Sync & Async & Scope & Abort[Doctest.Error]) =
        for
            id    <- Random.uuid
            cp    <- testClasspath
            nCpus <- System.availableProcessors
            cacheDir = Path.basePaths.tmp / s"kyo-doctest-corpus-cache-$id"
            _ <- Abort.recover[FileFsException](e => Abort.fail(Doctest.Error.IoError(cacheDir, "mkDir", e)))(
                cacheDir.mkDir
            )
            _ <- Scope.acquireRelease(cacheDir)(dir =>
                // Release must not fail; swallow removeAll errors.
                Abort.run[FileFsException](dir.removeAll).unit
            )
            report <- Doctest.check(Doctest.Config(
                sources = Chunk(filePath),
                classpath = cp,
                scalaOpts = scalaOpts,
                cache = cacheDir,
                parallel = nCpus
            ))
        yield report

    // Helper: run Doctest.check on a named corpus fixture; invoke assertReport with the resulting Report.
    private def runAndCheck(name: String, scalaOpts: Chunk[String] = Chunk.empty)(
        assertReport: Doctest.Report => Assertion
    )(using Frame): Assertion < (Sync & Async & Scope) =
        val path = fixtureFile(name)
        Abort.run(Scope.run(runCheck(path, scalaOpts))).map {
            case Result.Success(report) =>
                assertReport(report)
            case Result.Failure(e) =>
                fail(s"unexpected failure: $e")
            case Result.Panic(t) =>
                fail(s"unexpected panic: ${t.getMessage}")
        }
    end runAndCheck

    // Helper: parse the corpus fixture at the given name and return its Blocks.
    // On any parse error the test is failed immediately and Chunk.empty is returned to allow
    // the for-comprehension that follows to complete without crashing.
    private def parseFixtureBlocks(name: String)(using Frame): Chunk[Block] < (Sync & Async & Scope) =
        val path = fixtureFile(name)
        Abort.run(MarkdownParser.parse(path)).map {
            case Result.Success(blocks) => blocks
            case Result.Failure(e)      => fail(s"MarkdownParser failed: $e"); Chunk.empty
            case Result.Panic(t)        => fail(s"MarkdownParser panicked: ${t.getMessage}"); Chunk.empty
        }
    end parseFixtureBlocks

    "visible-bare.md has 3 blocks, all compile cleanly" in run {
        runAndCheck("visible-bare.md") { report =>
            assert(report.totalBlocks == 3, s"expected 3 blocks, got ${report.totalBlocks}")
            assert(report.failures.isEmpty, s"expected no failures, got: ${report.failures.map(_.message)}")
        }
    }

    "visible-with-modifiers.md modifier combinations validated" in run {
        runAndCheck("visible-with-modifiers.md") { report =>
            // The expect=fails-compile block succeeds (compile failure was expected).
            // The expect=skipped block is counted but not compiled.
            // All other blocks compile cleanly.
            assert(
                report.failures.isEmpty,
                s"expected no failures, got: ${report.failures.map(_.message)}"
            )
        }
    }

    "html-wrapper-around-block.md all blocks parse as Carrier.Visible regardless of HTML wrapper" in run {
        for
            parsedBlocks <- parseFixtureBlocks("html-wrapper-around-block.md")
            _ =
                // Both blocks parse as Carrier.Visible; HTML wrapper tags are transparent to the parser.
                val allVisible = parsedBlocks.forall(_.carrier == Block.Carrier.Visible)
                assert(
                    allVisible,
                    s"expected all blocks to be Block.Carrier.Visible, got carriers: ${parsedBlocks.map(_.carrier)}"
                )
                assert(parsedBlocks.nonEmpty, "expected at least one block in html-wrapper-around-block.md")
            result <- runAndCheck("html-wrapper-around-block.md") { report =>
                assert(report.failures.isEmpty, s"expected no failures, got: ${report.failures.map(_.message)}")
                // Setup blocks are injected as preludes into consumers, not emitted as standalone compile units.
                // The one consumer block is compiled; setup block does not appear in outcomes.
                assert(report.totalBlocks == 1, s"expected 1 block in outcomes (consumer only), got ${report.totalBlocks}")
                assert(report.compiled == 1, s"expected 1 compiled block, got compiled=${report.compiled}")
            }
        yield result
    }

    "html-comment-hard-hide.md hard-hidden blocks validated" in run {
        for
            parsedBlocks <- parseFixtureBlocks("html-comment-hard-hide.md")
            _ =
                // The setup block is inside an HTML comment: Carrier.Hidden
                val hiddenBlocks = parsedBlocks.filter(_.carrier == Block.Carrier.Hidden)
                assert(
                    hiddenBlocks.nonEmpty,
                    s"expected at least one Block.Carrier.Hidden block, got carriers: ${parsedBlocks.map(_.carrier)}"
                )
                // The consumer block is bare: Block.Carrier.Visible
                val visibleBlocks = parsedBlocks.filter(_.carrier == Block.Carrier.Visible)
                assert(
                    visibleBlocks.nonEmpty,
                    s"expected at least one Block.Carrier.Visible block, got carriers: ${parsedBlocks.map(_.carrier)}"
                )
            result <- runAndCheck("html-comment-hard-hide.md") { report =>
                assert(report.failures.isEmpty, s"expected no failures, got: ${report.failures.map(_.message)}")
                // Setup block is injected as prelude; only the consumer block appears in outcomes.
                assert(report.totalBlocks == 1, s"expected 1 block in outcomes (consumer only), got ${report.totalBlocks}")
                assert(report.compiled == 1, s"expected 1 compiled block, got compiled=${report.compiled}")
            }
        yield result
    }

    "scope-isolated.md three isolated blocks all compile cleanly" in run {
        runAndCheck("scope-isolated.md") { report =>
            assert(report.totalBlocks == 3, s"expected 3 blocks, got ${report.totalBlocks}")
            assert(report.failures.isEmpty, s"expected no failures, got: ${report.failures.map(_.message)}")
        }
    }

    "scope-inherited.md inherited blocks see prior bindings" in run {
        runAndCheck("scope-inherited.md") { report =>
            assert(report.totalBlocks == 3, s"expected 3 blocks, got ${report.totalBlocks}")
            assert(report.failures.isEmpty, s"expected no failures, got: ${report.failures.map(_.message)}")
        }
    }

    "scope-env-named.md named env scopes are isolated from each other" in run {
        for
            parsedBlocks <- parseFixtureBlocks("scope-env-named.md")
            _ =
                // Verify the two distinct env names are present
                val envNames = parsedBlocks.toSeq.collect { case Block(_, _, _, _, Block.Visibility.Env(name), _, _, _) => name }.distinct
                assert(
                    envNames.contains("tutorial"),
                    s"expected env:tutorial scope among parsed blocks, got env names: $envNames"
                )
                assert(
                    envNames.contains("other"),
                    s"expected env:other scope among parsed blocks, got env names: $envNames"
                )
                assert(
                    envNames.size == 2,
                    s"expected exactly 2 distinct env names (tutorial, other), got: $envNames"
                )
            result <- runAndCheck("scope-env-named.md") { report =>
                assert(report.failures.isEmpty, s"expected no failures, got: ${report.failures.map(_.message)}")
                assert(report.totalBlocks == 3, s"expected 3 blocks (2 tutorial + 1 other), got ${report.totalBlocks}")
            }
        yield result
    }

    "scope-nested.md nested block sees outer but does not leak" in run {
        for
            parsedBlocks <- parseFixtureBlocks("scope-nested.md")
            _ =
                // Verify at least one nested-scope block was parsed
                val nestedBlocks = parsedBlocks.filter(_.visibility == Block.Visibility.Nested)
                assert(
                    nestedBlocks.nonEmpty,
                    s"expected at least one Block.Visibility.Nested block in scope-nested.md, got visibilities: ${parsedBlocks.map(_.visibility)}"
                )
                // Verify also an inherited-scope block exists
                val inheritedBlocks = parsedBlocks.filter(_.visibility == Block.Visibility.Inherited)
                assert(inheritedBlocks.nonEmpty, s"expected at least one Block.Visibility.Inherited block in scope-nested.md")
                assert(parsedBlocks.size == 3, s"expected 3 parsed blocks (inherited + nested + isolated), got ${parsedBlocks.size}")
            result <- runAndCheck("scope-nested.md") { report =>
                assert(report.failures.isEmpty, s"expected no failures, got: ${report.failures.map(_.message)}")
                assert(report.totalBlocks == 3, s"expected 3 blocks (inherited + nested + isolated), got ${report.totalBlocks}")
                assert(report.compiled == 3, s"expected 3 compiled blocks, got compiled=${report.compiled}")
            }
        yield result
    }

    "expect-fails-compile.md compile failure reported as success" in run {
        runAndCheck("expect-fails-compile.md") { report =>
            assert(report.totalBlocks == 1, s"expected 1 block, got ${report.totalBlocks}")
            // The block is marked expect=fails-compile; it should compile with type error,
            // and the validator should flip that into a success (no failures).
            assert(
                report.failures.isEmpty,
                s"expected no failures (compile failure was expected), got: ${report.failures.map(_.message)}"
            )
        }
    }

    "expect-warns.md warning block reports success" in run {
        runAndCheck("expect-warns.md", scalaOpts = Chunk("-deprecation")) { report =>
            assert(report.totalBlocks == 1, s"expected 1 block, got ${report.totalBlocks}")
            assert(
                report.failures.isEmpty,
                s"expected no failures (warning was expected and emitted), got: ${report.failures.map(_.message)}"
            )
        }
    }

    "expect-skipped.md skipped block not compiled, valid block compiled" in run {
        runAndCheck("expect-skipped.md") { report =>
            assert(report.totalBlocks == 2, s"expected 2 blocks, got ${report.totalBlocks}")
            // Only 1 block is compiled (the valid one); the skipped block is not.
            assert(
                report.compiled == 1,
                s"expected 1 compiled block (skipped block not compiled), got compiled=${report.compiled}"
            )
            assert(report.failures.isEmpty, s"expected no failures, got: ${report.failures.map(_.message)}")
        }
    }

    "setup-composite.md setup bindings visible to subsequent blocks" in run {
        for
            parsedBlocks <- parseFixtureBlocks("setup-composite.md")
            _ =
                // MarkdownParser sees all 3 blocks (1 setup + 2 consumers)
                assert(parsedBlocks.size == 3, s"expected 3 parsed blocks (1 setup + 2 consumers), got ${parsedBlocks.size}")
                val setupBlocks = parsedBlocks.filter(_.visibility == Block.Visibility.Env("__doc__"))
                assert(
                    setupBlocks.nonEmpty,
                    s"expected at least one setup block (Env(__doc__)), got visibilities: ${parsedBlocks.map(_.visibility)}"
                )
            result <- runAndCheck("setup-composite.md") { report =>
                assert(
                    report.failures.isEmpty,
                    s"expected no failures (setup bindings visible), got: ${report.failures.map(_.message)}"
                )
                // Only the 2 consumer blocks appear in outcomes; setup is injected as prelude only.
                assert(report.totalBlocks == 2, s"expected 2 blocks in outcomes (consumers only), got ${report.totalBlocks}")
                assert(report.compiled == 2, s"expected 2 compiled blocks, got compiled=${report.compiled}")
            }
        yield result
    }

    "per-readme-defaults.md default scope=inherited applies globally, per-block override works" in run {
        for
            parsedBlocks <- parseFixtureBlocks("per-readme-defaults.md")
            _ =
                assert(parsedBlocks.size == 3, s"expected 3 parsed blocks, got ${parsedBlocks.size}")
                // First two blocks get scope=inherited from the file-level default
                val inheritedBlocks = parsedBlocks.filter(_.visibility == Block.Visibility.Inherited)
                assert(
                    inheritedBlocks.size == 2,
                    s"expected 2 blocks with Block.Visibility.Inherited (file default), got: ${parsedBlocks.map(_.visibility)}"
                )
                // Third block has explicit scope=isolated override
                val isolatedBlocks = parsedBlocks.filter(_.visibility == Block.Visibility.Isolated)
                assert(
                    isolatedBlocks.size == 1,
                    s"expected 1 block with Block.Visibility.Isolated (explicit override), got: ${parsedBlocks.map(_.visibility)}"
                )
            result <- runAndCheck("per-readme-defaults.md") { report =>
                assert(
                    report.failures.isEmpty,
                    s"expected no failures (inherited default + isolated override), got: ${report.failures.map(_.message)}"
                )
                assert(report.totalBlocks == 3, s"expected 3 blocks, got ${report.totalBlocks}")
                assert(report.compiled == 3, s"expected 3 compiled blocks, got compiled=${report.compiled}")
            }
        yield result
    }

    "macro-block.md Schema derivation macro, Tag, ConcreteTag, and direct all compile cleanly" in run {
        runAndCheck("macro-block.md") { report =>
            assert(
                report.failures.isEmpty,
                s"expected no failures (macros resolve on JVM classpath), got: ${report.failures.map(_.message)}"
            )
            // 4 blocks: Schema derivation, Tag[Int], ConcreteTag[String | Int], direct defer
            assert(report.totalBlocks == 4, s"expected 4 blocks in macro-fence.md, got ${report.totalBlocks}")
            assert(report.compiled == 4, s"expected 4 compiled blocks, got compiled=${report.compiled}")
        }
    }

    "kyo-doctest README.md self-validates with zero failures" in run {
        // Locate the README.md at the project root relative to the module.
        // In sbt, baseDirectory for kyo-doctest is kyo-doctest/. The README lives there.
        findReadme.flatMap { readmePath =>
            Abort.run(Scope.run(runCheck(readmePath))).map {
                case Result.Success(report) =>
                    assert(
                        report.failures.isEmpty,
                        s"README.md self-validation failed with ${report.failures.size} failure(s):\n" +
                            report.failures.toSeq.map(f => s"  ${f.line}: ${f.message}").mkString("\n")
                    )
                case Result.Failure(e) =>
                    fail(s"unexpected failure during README self-validation: $e")
                case Result.Panic(t) =>
                    fail(s"unexpected panic during README self-validation: ${t.getMessage}")
            }
        }
    }

    // Locate the kyo-doctest/README.md by searching upward from user.dir.
    // sbt sets user.dir to the sub-project base (e.g. kyo-doctest/jvm/).
    // We walk parent directories looking for a directory named "kyo-doctest" that contains README.md.
    // Failing that, fall back to user.dir itself (in case the test runs from the kyo-doctest dir directly).
    // NOTE: override via the kyo.doctest.readme system property for non-standard build layouts.
    private def findReadme(using Frame): kyo.Path < Sync =
        System.property[String]("kyo.doctest.readme").flatMap { overrideProp =>
            overrideProp match
                case Present(p) => kyo.Path(p)
                case Absent =>
                    kyo.System.property[String]("user.dir").flatMap { maybeCwd =>
                        val cwdStr = maybeCwd.getOrElse(".")
                        val cwd    = kyo.Path(cwdStr)
                        def loop(dir: kyo.Path): kyo.Path < Sync =
                            // Check if this dir contains kyo-doctest/README.md as a sibling subdir.
                            val sibling = dir / "kyo-doctest" / "README.md"
                            // Check if this dir IS named kyo-doctest and directly contains README.md.
                            val direct  = dir / "README.md"
                            val dirName = dir.name.getOrElse("")
                            sibling.exists.flatMap { siblingExists =>
                                if siblingExists then sibling
                                else
                                    direct.exists.flatMap { directExists =>
                                        if dirName == "kyo-doctest" && directExists then direct
                                        else
                                            dir.parent match
                                                case Present(parent) => loop(parent)
                                                case Absent          => cwd / "README.md"
                                            end match
                                    }
                            }
                        end loop
                        loop(cwd)
                    }
            end match
        }
    end findReadme

end CorpusTest
