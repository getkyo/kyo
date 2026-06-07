package kyo

import kyo.internal.Fidelity2TestBase
import kyo.internal.MemoryFileSource
import kyo.internal.tasty.query.ClasspathOrchestrator

/** Fidelity tests for TastyError channel correctness: SoftFail/FailFast FileNotFound,
  * MalformedSection, CorruptedFile, requireSymbol, and ClasspathBuilding. Uses MemoryFileSource
  * and ClasspathOrchestrator.init with synthetic corrupt bytes; no JVM filesystem required.
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

    private def loadFromMemory(
        fileName: String,
        bytes: Array[Byte],
        mode: Tasty.ErrorMode
    )(using Frame): Tasty.Classpath < (Sync & Async & Scope & Abort[TastyError]) =
        val src = MemoryFileSource()
        src.add(s"root/$fileName", bytes)
        ClasspathOrchestrator.init(Seq("root"), mode, src, 1)
    end loadFromMemory

    "SoftFail missing root accumulates FileNotFound in cp.errors" in {
        val src = MemoryFileSource()
        ClasspathOrchestrator.init(Seq("missing"), Tasty.ErrorMode.SoftFail, src, 1).map: cp =>
            assert(
                cp.errors.nonEmpty,
                "Expected cp.errors to be non-empty after SoftFail init with missing root."
            )
            val hasFileNotFound = cp.errors.exists:
                case TastyError.FileNotFound(_) => true
                case _                          => false
            assert(
                hasFileNotFound,
                s"Expected cp.errors to contain TastyError.FileNotFound; got ${cp.errors}. " +
                    "missing root must accumulate FileNotFound under SoftFail."
            )
            assert(
                cp.symbols.isEmpty,
                s"Expected cp.symbols.size == 0 on all-missing classpath; got ${cp.symbols.size}."
            )
            succeed
    }

    "FailFast missing root still raises FileNotFound" in {
        val src = MemoryFileSource()
        Abort.run[TastyError](ClasspathOrchestrator.init(Seq("missing"), Tasty.ErrorMode.FailFast, src, 1)).map: result =>
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

    "SoftFail truncated .tasty produces MalformedSection" in {
        loadFromMemory("Truncated.tasty", truncatedTastyBytes, Tasty.ErrorMode.SoftFail).map: cp =>
            assert(
                cp.errors.nonEmpty,
                s"Expected cp.errors to be non-empty on truncated .tasty load; got empty."
            )
            val malformed = cp.errors.collect:
                case e: TastyError.MalformedSection => e
            assert(
                malformed.nonEmpty,
                s"Expected at least one TastyError.MalformedSection in cp.errors; got: ${cp.errors}."
            )
            succeed
    }

    "SoftFail bit-flipped magic produces CorruptedFile or MalformedSection" in {
        loadFromMemory("BitFlipped.tasty", bitFlippedMagicBytes, Tasty.ErrorMode.SoftFail).map: cp =>
            assert(
                cp.errors.nonEmpty,
                s"Expected cp.errors to be non-empty on bit-flipped .tasty load; got empty."
            )
            val hasStructuredError = cp.errors.exists:
                case _: TastyError.CorruptedFile    => true
                case _: TastyError.MalformedSection => true
                case _                              => false
            assert(
                hasStructuredError,
                s"Expected TastyError.CorruptedFile or MalformedSection in cp.errors; got: ${cp.errors}."
            )
            succeed
    }

    "requireSymbol raises NotFound for absent FQN" in {
        kyo.internal.TestClasspaths.withClasspath()(Tasty.classpath).flatMap: cp =>
            given Tasty.Classpath = cp
            Abort.run[TastyError](cp.requireSymbol("non.existent.fqn.abc.xyz")).map: result =>
                result match
                    case Result.Failure(TastyError.NotFound(fqn)) =>
                        assert(
                            fqn == "non.existent.fqn.abc.xyz",
                            s"Expected NotFound fqn == 'non.existent.fqn.abc.xyz'; got '$fqn'."
                        )
                        succeed
                    case Result.Success(sym) =>
                        fail(s"Expected NotFound but requireSymbol succeeded with: $sym")
                    case Result.Failure(other) =>
                        fail(s"Expected TastyError.NotFound but got: $other")
                    case Result.Panic(t) =>
                        fail(s"Unexpected panic: ${t.getMessage}")
    }

    "(requireSymbol happy path): requireSymbol returns symbol for present FQN" in {
        kyo.internal.TestClasspaths.withClasspath()(Tasty.classpath).flatMap: cp =>
            given Tasty.Classpath = cp
            // Find any real FQN from the loaded classpath
            val anyFqn = cp.indices.byFqn.toMap.keys.headOption.getOrElse("PlainClass")
            cp.requireSymbol(anyFqn).map: sym =>
                import Tasty.Name.asString
                assert(
                    sym.name.asString == anyFqn.split('.').last || sym.name.asString.nonEmpty,
                    s"requireSymbol returned a symbol with an empty or unexpected name: '${sym.name.asString}' for fqn '$anyFqn'"
                )
                assert(
                    sym.name.asString.nonEmpty,
                    "requireSymbol returned a symbol with an empty name."
                )
    }

    "ClasspathBuilding fires from orchestrator on invariant violation" in {
        Abort.run[TastyError](kyo.internal.tasty.query.ClasspathOrchestrator.triggerClasspathBuildingForTest()).map: result =>
            result match
                case Result.Failure(TastyError.ClasspathBuilding(_)) =>
                    succeed
                case Result.Success(_) =>
                    fail(
                        "Expected TastyError.ClasspathBuilding from degenerate MergeState; finalizeMerge succeeded. " +
                            "Check that ClasspathBuilding check fires when fqnIndex has unresolvable partial symbols under FailFast."
                    )
                case Result.Failure(other) =>
                    fail(s"Expected TastyError.ClasspathBuilding but got: $other")
                case Result.Panic(t) =>
                    fail(s"Unexpected panic: ${t.getMessage}")
    }

    "SoftFail mid-stream corruption produces structured error" in {
        loadFromMemory("MidStream.tasty", corruptedMidStreamBytes, Tasty.ErrorMode.SoftFail).map: cp =>
            assert(
                cp.errors.nonEmpty,
                s"Expected cp.errors to be non-empty on mid-stream corrupted .tasty load; got empty."
            )
            val errWithStructure = cp.errors.find:
                case _: TastyError.MalformedSection => true
                case _: TastyError.CorruptedFile    => true
                case _                              => false
            assert(
                errWithStructure.isDefined,
                s"Expected at least one structured error (MalformedSection or CorruptedFile) in cp.errors; got: ${cp.errors}. " +
                    "partial-file corruption must produce a structured TastyError, not a raw string."
            )
            succeed
    }

end ErrorFidelity2Test
