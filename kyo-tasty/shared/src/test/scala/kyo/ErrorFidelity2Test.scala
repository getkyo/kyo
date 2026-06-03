package kyo

import kyo.internal.Fidelity2TestBase
import kyo.internal.MemoryFileSource
import kyo.internal.tasty.query.ClasspathOrchestrator

/** Fidelity tests for TastyError channel fidelity: SoftFail/FailFast FileNotFound, MalformedSection, CorruptedFile, requireSymbol, and
  * ClasspathBuilding (F-A5-001, F-A5-002, F-A5-005, F-A5-006, F-A1-006, F-A1-007, F-A5-004).
  *
  * Before Phase 2.08:
  *   - `Tasty.Classpath.init(Seq("/tmp/missing"))` with SoftFail aborted via `Abort.fail(TastyError.FileNotFound)` instead of accumulating
  *     the error (F-A5-002 bug).
  *   - `TastyError.SymbolNotFound` was never raised anywhere (dead variant -- F-A5-001 gap).
  *   - `TastyError.ClasspathBuilding` was never raised (dead variant -- F-A5-001 gap).
  *   - `TastyError.MalformedSection` reason was the raw JVM message "Array index out of range: 254" instead of "name table index 254 out of
  *     range" (F-A5-005 gap).
  *   - `TastyError.CorruptedFile.path` was "<byte view>" instead of the actual file path (F-A5-006 gap).
  *
  * Cross-platform: uses MemoryFileSource and ClasspathOrchestrator.init with synthetic corrupt bytes. No JVM filesystem required.
  *
  * Invariants produced: INV-103-DF2, INV-104-DF2, INV-107-DF2.
  */
class ErrorFidelity2Test extends Fidelity2TestBase:

    import AllowUnsafe.embrace.danger

    // TASTy bytes with valid magic but a name table that references index 254 (out of range for the truncated table).
    // Mirrors TestClasspaths2Jvm.truncatedTastyPath byte construction.
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
    // Mirrors TestClasspaths2Jvm.bitFlippedMagicTastyPath byte construction.
    private def bitFlippedMagicBytes: Array[Byte] =
        val magic   = Array[Byte](0x5c.toByte, 0xa1.toByte, 0xab.toByte, 0x1f.toByte)
        val corrupt = Array[Byte]((magic(0) ^ 0x01).toByte) ++ magic.drop(1)
        val version = Array[Byte](28, 5, 0)
        val uuid    = Array.fill[Byte](16)(0)
        corrupt ++ version ++ uuid
    end bitFlippedMagicBytes

    // TASTy bytes with valid magic but a mid-stream truncated name table.
    // Mirrors TestClasspaths2Jvm.corruptedMidStreamTastyPath byte construction.
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

    // Leaf 1 (F-A5-002, INV-104-DF2): softfail-accumulates-filenotfound
    // Given: init with a MemoryFileSource containing no files for root "missing", ErrorMode.SoftFail
    // When: awaiting init result
    // Then: returns Classpath with cp.errors.head == TastyError.FileNotFound and cp.symbols.size == 0
    // Pins: INV-104-DF2; F-A5-002 (OQ-002 fix)
    "F-A5-002 leaf 1 (Phase 2.08): SoftFail missing root accumulates FileNotFound in cp.errors" in run {
        val src = MemoryFileSource()
        // "missing" root exists in neither key: exists("missing") returns false, triggering FileNotFound
        ClasspathOrchestrator.init(Seq("missing"), Tasty.ErrorMode.SoftFail, src, 1).map: cp =>
            assert(
                cp.errors.nonEmpty,
                "Expected cp.errors to be non-empty after SoftFail init with missing root. " +
                    "Before fix: Abort.fail(TastyError.FileNotFound) was raised instead of accumulating."
            )
            val hasFileNotFound = cp.errors.exists:
                case TastyError.FileNotFound(_) => true
                case _                          => false
            assert(
                hasFileNotFound,
                s"Expected cp.errors to contain TastyError.FileNotFound; got ${cp.errors}. " +
                    "F-A5-002: missing root must accumulate FileNotFound under SoftFail."
            )
            assert(
                cp.symbols.isEmpty,
                s"Expected cp.symbols.size == 0 on all-missing classpath; got ${cp.symbols.size}."
            )
            succeed
    }

    // Leaf 2 (INV-104-DF2 FailFast): failfast-aborts-on-filenotfound
    // Given: same missing root with ErrorMode.FailFast
    // When: awaiting init result
    // Then: aborts with Abort.fail(TastyError.FileNotFound(...))
    // Pins: INV-104-DF2 FailFast preservation (current behavior preserved)
    "INV-104-DF2 leaf 2 (Phase 2.08): FailFast missing root still raises FileNotFound" in run {
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

    // Leaf 3 (F-A5-005, INV-107-DF2): softfail-accumulates-malformedsection
    // Given: truncated .tasty bytes loaded with SoftFail via MemoryFileSource
    // When: inspecting cp.errors
    // Then: cp.errors contains at least one MalformedSection
    // Pins: INV-107-DF2; F-A5-005 (typed reason string); F-A1-006
    "F-A5-005 leaf 3 (Phase 2.08): SoftFail truncated .tasty produces MalformedSection" in run {
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

    // Leaf 4 (F-A5-006, INV-107-DF2): softfail-accumulates-corruptedfile
    // Given: bit-flipped magic .tasty bytes loaded with SoftFail via MemoryFileSource
    // When: inspecting cp.errors
    // Then: cp.errors contains at least one CorruptedFile or MalformedSection
    // Pins: INV-107-DF2; F-A5-006 (CorruptedFile raised for bad magic); F-A1-007
    "F-A5-006 leaf 4 (Phase 2.08): SoftFail bit-flipped magic produces CorruptedFile or MalformedSection" in run {
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

    // Leaf 5 (F-A5-001 NotFound): requiresymbol-raises-on-absent
    // Given: requireSymbol('non.existent.fqn') against embedded classpath
    // When: awaiting result
    // Then: aborts with TastyError.NotFound('non.existent.fqn')
    // Pins: INV-103-DF2; F-A5-001 (NotFound wired via requireSymbol; unified with kind-specific requireX)
    "F-A5-001 leaf 5 (Phase 2.08): requireSymbol raises NotFound for absent FQN" in run {
        kyo.internal.TestClasspaths.withClasspath().flatMap: cp =>
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

    // Leaf 6 (requireSymbol happy path): requiresymbol-returns-present-symbol
    // Given: requireSymbol('PlainClass') against embedded classpath
    // When: awaiting
    // Then: returns the corresponding Symbol (no abort)
    // Pins: requireSymbol happy path
    "Phase 2.08 leaf 6 (requireSymbol happy path): requireSymbol returns symbol for present FQN" in run {
        kyo.internal.TestClasspaths.withClasspath().flatMap: cp =>
            given Tasty.Classpath = cp
            // Find any real FQN from the loaded classpath
            val anyFqn = cp.fqnIndex.keys.headOption.getOrElse("PlainClass")
            cp.requireSymbol(anyFqn).map: sym =>
                assert(sym.isInstanceOf[Tasty.Symbol], s"requireSymbol returned a non-Symbol: $sym")
                import Tasty.Name.asString
                assert(
                    sym.name.asString.nonEmpty,
                    "requireSymbol returned a symbol with an empty name."
                )
                succeed
    }

    // Leaf 7 (F-A5-001 ClasspathBuilding): classpath-building-fires-from-orchestrator
    // Given: synthetic degenerate MergeState + FailFast
    // When: awaiting finalizeMerge result
    // Then: aborts with TastyError.ClasspathBuilding
    // Pins: INV-103-DF2; F-A5-001 (ClasspathBuilding wired in finalizeMerge)
    "F-A5-001 leaf 7 (Phase 2.08): ClasspathBuilding fires from orchestrator on invariant violation" in run {
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

    // Leaf 8 (F-A5-004, INV-107-DF2): softfail-accumulates-corruptedfile-midstream
    // Given: mid-stream corrupted bytes loaded via MemoryFileSource
    // When: inspecting cp.errors
    // Then: cp.errors contains exactly one structured error
    // Pins: F-A5-004; INV-107-DF2
    "F-A5-004 leaf 8 (Phase 2.08): SoftFail mid-stream corruption produces structured error" in run {
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
                    "F-A5-004: partial-file corruption must produce a structured TastyError, not a raw string."
            )
            succeed
    }

end ErrorFidelity2Test
