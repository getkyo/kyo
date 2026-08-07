package kyo

import kyo.internal.Fidelity2TestBase
import kyo.internal.TestClasspaths
import kyo.internal.TestClasspaths2
import kyo.internal.tasty.query.ClasspathOrchestrator

/** Confirmation tests for decoder fidelity: empty classpath, truncated snapshot, bit-flipped
  * magic, mid-stream truncation, and Java symbols.
  */
class ConfirmationFidelity2Test extends Fidelity2TestBase:

    import AllowUnsafe.embrace.danger

    "empty classpath init returns 0 symbols, 0 errors" in {
        Tasty.withClasspath(Seq.empty)(Tasty.classpath).map { classpath =>
            assert(classpath.symbols.size == 0, s"Expected 0 symbols on empty classpath; got ${classpath.symbols.size}")
            assert(classpath.errors.size == 0, s"Expected 0 errors on empty classpath; got ${classpath.errors.size}")
            succeed
        }
    }

    "truncated .krfl snapshot fails with TastyError via readFromBytes" in {
        import kyo.internal.tasty.snapshot.SnapshotReader
        val truncatedBytes = Array[Byte]('K', 'R', 'F', 'L', 1, 5, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        Abort.run[TastyError](SnapshotReader.readFromBytes(truncatedBytes, "mem/truncated.krfl")).map { result =>
            result match
                case Result.Failure(_) =>
                    succeed
                case Result.Success(_) =>
                    fail("Expected SnapshotReader.readFromBytes on truncated KRFL bytes to fail; it succeeded unexpectedly")
                case Result.Panic(t) =>
                    throw t
        }
    }

    "classpath.errors entries pattern-match as sealed TastyError variants via in-memory source" in {
        val corruptBytes = kyo.fixtures.Embedded.plainClassTasty.clone()
        corruptBytes(0) = (corruptBytes(0) ^ 0xff.toByte).toByte // flip first magic byte
        val pickle = Tasty.Pickle("corrupt-magic", Tasty.Version(28, 3, 0), Span.from(corruptBytes))
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(pickle)) {
                Tasty.classpath.map { classpath =>
                    assert(
                        classpath.errors.nonEmpty,
                        "Expected at least one error for bit-flipped .tasty file; classpath.errors was empty"
                    )
                    val firstError = classpath.errors(0)
                    val matchedVariant = firstError match
                        case _: TastyError.CorruptedFile        => true
                        case _: TastyError.FileNotFound         => true
                        case _: TastyError.MalformedSection     => true
                        case _: TastyError.ClassfileFormatError => true
                        case _: TastyError.SnapshotFormatError  => true
                        case _                                  => false
                    assert(
                        matchedVariant,
                        s"Expected classpath.errors.head to be a sealed TastyError variant; got $firstError"
                    )
                    succeed
                }
            }
        ).map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "SoftFail mid-stream malformed section produces 0 symbols via in-memory source" in {
        // Take a valid TASTy file and truncate it mid-stream (keep first 20 bytes: magic+version, then cut)
        val truncated = kyo.fixtures.Embedded.plainClassTasty.take(20)
        val pickle    = Tasty.Pickle("truncated", Tasty.Version(28, 3, 0), Span.from(truncated))
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(pickle)) {
                Tasty.classpath.map { classpath =>
                    assert(
                        classpath.errors.nonEmpty,
                        "Expected at least one error for mid-stream truncated .tasty file; got 0"
                    )
                    assert(
                        classpath.symbols.size == 0,
                        s"Expected 0 symbols from a truncated .tasty file; got ${classpath.symbols.size}"
                    )
                    succeed
                }
            }
        ).map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "Java-defined symbols present in standard classpath (java interop guard)" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            val javaCount = classpath.symbols.count(_.isJava)
            assert(
                javaCount > 0,
                s"Expected > 0 Java-defined symbols in standard classpath (from JavaSimpleFixture.class embedded in EmbeddedJavaFixtures); found $javaCount"
            )
            succeed
        }
    }

    "findClass(kyo.fixtures.JavaSimpleFixture) returns Present with isJava via temp dir" in {
        // Write the .class file to a temp dir and use ClasspathOrchestrator.init with a real path.
        Scope.run {
            Path.run(Path.tempDir("kyo-cf2-java")).map { tmpDir =>
                val subDir = tmpDir / "kyo" / "fixtures"
                Path.run(subDir.mkDir).map { _ =>
                    Path.run((subDir / "JavaSimpleFixture.class").writeBytes(
                        Span.from(kyo.fixtures.EmbeddedJavaFixtures.javaSimpleFixtureClassfile)
                    )).map { _ =>
                        Scope.run {
                            Abort.run[TastyError](
                                ClasspathOrchestrator.init(
                                    Seq((tmpDir / "kyo" / "fixtures" / "JavaSimpleFixture.class").toString),
                                    Tasty.ErrorMode.SoftFail,
                                    1
                                ).map { classpath =>
                                    Tasty.withClasspath(classpath) {
                                        Tasty.findClass("kyo.fixtures.JavaSimpleFixture")
                                    }
                                }
                            ).map {
                                case Result.Success(Maybe.Present(c)) =>
                                    assert(c.isJava, "JavaSimpleFixture must have isJava (Flag.JavaDefined set by ClassfileUnpickler)")
                                    succeed
                                case Result.Success(Maybe.Absent) =>
                                    fail("kyo.fixtures.JavaSimpleFixture not found; standalone .class root was not discovered")
                                case Result.Failure(e) =>
                                    fail(s"Unexpected failure: $e")
                                case Result.Panic(t) =>
                                    throw t
                            }
                        }
                    }
                }
            }
        }
    }

end ConfirmationFidelity2Test
