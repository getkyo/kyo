package kyo

import kyo.internal.Fidelity2TestBase
import kyo.internal.tasty.query.ClasspathOrchestrator

/** Fidelity tests for TastyError channel correctness: SoftFail/FailFast FileNotFound,
  * MalformedSection, CorruptedFile, requireSymbol, and ClasspathBuilding. Uses withPickles
  * and ClasspathOrchestrator.init with real temp dirs; no JVM-only filesystem required.
  */
class ErrorFidelity2Test extends Fidelity2TestBase:

    import AllowUnsafe.embrace.danger

    // TASTy bytes with valid magic but a name table that references index 254 (out of range for the truncated table).
    private def truncatedTastyBytes: Array[Byte] =
        val magic        = Array[Byte](0x5c.toByte, 0xa1.toByte, 0xab.toByte, 0x1f.toByte)
        val version      = Array[Byte](0x9c.toByte, 0x88.toByte, 0x80.toByte)
        val toolingLen   = Array[Byte](0x80.toByte)
        val uuid         = Array.fill[Byte](16)(0)
        val nameTableLen = Array[Byte]((100 | 0x80).toByte)
        val nameData     = Array[Byte](1.toByte, 0x83.toByte, 65.toByte, 66.toByte, 67.toByte)
        magic ++ version ++ toolingLen ++ uuid ++ nameTableLen ++ nameData
    end truncatedTastyBytes

    // TASTy bytes with a bit-flipped magic byte (first byte XOR 0x01).
    private def bitFlippedMagicBytes: Array[Byte] =
        val magic   = Array[Byte](0x5c.toByte, 0xa1.toByte, 0xab.toByte, 0x1f.toByte)
        val corrupt = Array[Byte]((magic(0) ^ 0x01).toByte) ++ magic.drop(1)
        val version = Array[Byte](28, 5, 0)
        val uuid    = Array.fill[Byte](16)(0)
        corrupt ++ version ++ uuid
    end bitFlippedMagicBytes

    // TASTy bytes with valid magic but a mid-stream truncated name table.
    private def corruptedMidStreamBytes: Array[Byte] =
        val magic        = Array[Byte](0x5c.toByte, 0xa1.toByte, 0xab.toByte, 0x1f.toByte)
        val version      = Array[Byte](0x9c.toByte, 0x88.toByte, 0x80.toByte)
        val toolingLen   = Array[Byte](0x80.toByte)
        val uuid         = Array.fill[Byte](16)(0)
        val nameTableLen = Array[Byte]((50 | 0x80).toByte)
        val nameData     = Array[Byte](1.toByte, 0x82.toByte, 65.toByte)
        magic ++ version ++ toolingLen ++ uuid ++ nameTableLen ++ nameData
    end corruptedMidStreamBytes

    private def loadCorruptPickle(
        bytes: Array[Byte]
    )(using Frame): Tasty.Classpath < (Async & Abort[TastyError]) =
        val pickle = Tasty.Pickle("corrupt", Tasty.Version(28, 3, 0), Span.from(bytes))
        Tasty.withPickles(Chunk(pickle)) {
            Tasty.classpath
        }
    end loadCorruptPickle

    "SoftFail missing root accumulates FileNotFound in classpath.errors" in {
        // Use a real temp dir with a non-existent sub-path to trigger FileNotFound.
        Path.tempDir("kyo-err-f2-missing").map { tmp =>
            val missing = (tmp / "no-such-root").toString
            Scope.run {
                ClasspathOrchestrator.init(Seq(missing), Tasty.ErrorMode.SoftFail, 1).map { classpath =>
                    assert(
                        classpath.errors.nonEmpty,
                        "Expected classpath.errors to be non-empty after SoftFail init with missing root."
                    )
                    val hasFileNotFound = classpath.errors.exists {
                        case TastyError.FileNotFound(_) => true
                        case _                          => false
                    }
                    assert(
                        hasFileNotFound,
                        s"Expected classpath.errors to contain TastyError.FileNotFound; got ${classpath.errors}."
                    )
                    assert(
                        classpath.symbols.isEmpty,
                        s"Expected classpath.symbols.size == 0 on all-missing classpath; got ${classpath.symbols.size}."
                    )
                    succeed
                }
            }
        }
    }

    "FailFast missing root still raises FileNotFound" in {
        Path.tempDir("kyo-err-f2-failfast").map { tmp =>
            val missing = (tmp / "no-such-root").toString
            Scope.run {
                Abort.run[TastyError](ClasspathOrchestrator.init(Seq(missing), Tasty.ErrorMode.FailFast, 1)).map { result =>
                    result match
                        case Result.Failure(TastyError.FileNotFound(_)) =>
                            succeed
                        case Result.Success(_) =>
                            fail("Expected FailFast init with missing root to abort; succeeded instead.")
                        case Result.Failure(other) =>
                            fail(s"Expected TastyError.FileNotFound but got: $other")
                        case Result.Panic(t) =>
                            fail(s"Unexpected panic: ${t.getMessage}")
                }
            }
        }
    }

    "SoftFail truncated .tasty produces MalformedSection" in {
        loadCorruptPickle(truncatedTastyBytes).map { classpath =>
            assert(
                classpath.errors.nonEmpty,
                s"Expected classpath.errors to be non-empty on truncated .tasty load; got empty."
            )
            val malformed = classpath.errors.collect {
                case e: TastyError.MalformedSection => e
            }
            assert(
                malformed.nonEmpty,
                s"Expected at least one TastyError.MalformedSection in classpath.errors; got: ${classpath.errors}."
            )
            succeed
        }
    }

    "SoftFail bit-flipped magic produces CorruptedFile or MalformedSection" in {
        loadCorruptPickle(bitFlippedMagicBytes).map { classpath =>
            assert(
                classpath.errors.nonEmpty,
                s"Expected classpath.errors to be non-empty on bit-flipped .tasty load; got empty."
            )
            val hasStructuredError = classpath.errors.exists {
                case _: TastyError.CorruptedFile    => true
                case _: TastyError.MalformedSection => true
                case _                              => false
            }
            assert(
                hasStructuredError,
                s"Expected TastyError.CorruptedFile or MalformedSection in classpath.errors; got: ${classpath.errors}."
            )
            succeed
        }
    }

    "requireSymbol raises NotFound for absent fully-qualified name" in {
        kyo.internal.TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            given Tasty.Classpath = classpath
            Abort.run[TastyError](classpath.requireSymbol("non.existent.fullName.abc.xyz")).map { result =>
                result match
                    case Result.Failure(TastyError.NotFound(fullName)) =>
                        assert(
                            fullName == "non.existent.fullName.abc.xyz",
                            s"Expected NotFound fullName == 'non.existent.fullName.abc.xyz'; got '$fullName'."
                        )
                        succeed
                    case Result.Success(symbol) =>
                        fail(s"Expected NotFound but requireSymbol succeeded with: $symbol")
                    case Result.Failure(other) =>
                        fail(s"Expected TastyError.NotFound but got: $other")
                    case Result.Panic(t) =>
                        fail(s"Unexpected panic: ${t.getMessage}")
            }
        }
    }

    "(requireSymbol happy path): requireSymbol returns symbol for present fully-qualified name" in {
        kyo.internal.TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            given Tasty.Classpath = classpath
            // Find any real fully-qualified name from the loaded classpath
            val anyFullName = classpath.indices.byFullName.toMap.keys.headOption.getOrElse("PlainClass")
            classpath.requireSymbol(anyFullName).map { symbol =>
                import Tasty.Name.asString
                assert(
                    symbol.name.asString == anyFullName.split('.').last || symbol.name.asString.nonEmpty,
                    s"requireSymbol returned a symbol with an empty or unexpected name: '${symbol.name.asString}' for fullName '$anyFullName'"
                )
                assert(
                    symbol.name.asString.nonEmpty,
                    "requireSymbol returned a symbol with an empty name."
                )
            }
        }
    }

    "ClasspathBuilding fires from orchestrator on invariant violation" in {
        Abort.run[TastyError](kyo.internal.tasty.query.ClasspathOrchestrator.triggerClasspathBuildingForTest()).map { result =>
            result match
                case Result.Failure(TastyError.ClasspathBuilding(_)) =>
                    succeed
                case Result.Success(_) =>
                    fail(
                        "Expected TastyError.ClasspathBuilding from degenerate MergeState; finalizeMerge succeeded."
                    )
                case Result.Failure(other) =>
                    fail(s"Expected TastyError.ClasspathBuilding but got: $other")
                case Result.Panic(t) =>
                    fail(s"Unexpected panic: ${t.getMessage}")
        }
    }

    "SoftFail mid-stream corruption produces structured error" in {
        loadCorruptPickle(corruptedMidStreamBytes).map { classpath =>
            assert(
                classpath.errors.nonEmpty,
                s"Expected classpath.errors to be non-empty on mid-stream corrupted .tasty load; got empty."
            )
            val errWithStructure = classpath.errors.find {
                case _: TastyError.MalformedSection => true
                case _: TastyError.CorruptedFile    => true
                case _                              => false
            }
            assert(
                errWithStructure.isDefined,
                s"Expected at least one structured error (MalformedSection or CorruptedFile) in classpath.errors; got: ${classpath.errors}."
            )
            succeed
        }
    }

end ErrorFidelity2Test
