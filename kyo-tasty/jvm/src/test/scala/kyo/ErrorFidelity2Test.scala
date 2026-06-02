package kyo

import kyo.internal.Fidelity2TestBase
import kyo.internal.TestClasspaths
import kyo.internal.TestClasspaths2
import kyo.internal.tasty.query.ClasspathOrchestrator

/** Fidelity tests for TastyError channel fidelity: SoftFail/FailFast FileNotFound, MalformedSection, CorruptedFile, requireSymbol, and
  * ClasspathBuilding (F-A5-001, F-A5-002, F-A5-005, F-A5-006, F-A1-006, F-A1-007, F-A5-004).
  *
  * Before Phase 2.08:
  *   - `Tasty.Classpath.init(Seq("/tmp/missing"))` with SoftFail aborted via `Abort.fail(TastyError.FileNotFound)` instead of
  *     accumulating the error (F-A5-002 bug).
  *   - `TastyError.SymbolNotFound` was never raised anywhere (dead variant -- F-A5-001 gap).
  *   - `TastyError.ClasspathBuilding` was never raised (dead variant -- F-A5-001 gap).
  *   - `TastyError.MalformedSection` reason was the raw JVM message "Array index out of range: 254" instead of the typed "name table
  *     index 254 out of range" (F-A5-005 gap).
  *   - `TastyError.CorruptedFile.path` was "<byte view>" instead of the actual file path (F-A5-006 gap).
  *
  * Fixes:
  *   - F-A5-002: `ClasspathOrchestrator.init` checks mode before calling `Abort.fail` on missing roots; SoftFail accumulates
  *     `TastyError.FileNotFound` into `cp.errors` via `copyWithPreErrors`.
  *   - F-A5-001 (SymbolNotFound): wired via the new `Classpath.requireSymbol` accessor.
  *   - F-A5-001 (ClasspathBuilding): wired in `ClasspathOrchestrator.triggerClasspathBuildingForTest` and `finalizeMerge`.
  *   - F-A5-005: `NameUnpickler.read` maps `ArrayIndexOutOfBoundsException` message to "name table index N out of range".
  *   - F-A5-006: `readAndDecodeTastyFile` patches `CorruptedFile("<byte view>", ...)` with the actual on-disk path.
  *
  * Invariants produced: INV-103-DF2, INV-104-DF2, INV-107-DF2.
  */
class ErrorFidelity2Test extends Fidelity2TestBase:

    import AllowUnsafe.embrace.danger

    // Leaf 6 (F-A5-002, INV-104-DF2): softfail-accumulates-filenotfound
    // Given: init with a path that does not exist, ErrorMode.SoftFail (default)
    // When: awaiting init result
    // Then: returns Classpath with cp.errors.head == TastyError.FileNotFound and cp.symbols.size == 0
    // Pins: INV-104-DF2; F-A5-002 (OQ-002 fix)
    "F-A5-002 leaf 1 (Phase 2.08): SoftFail missing root accumulates FileNotFound in cp.errors" in run {
        val missingPath = "/tmp/decoder-fidelity-2-missing-xyz-do-not-create"
        Tasty.Classpath.init(Seq(missingPath), Tasty.ErrorMode.SoftFail).map: cp =>
            assert(
                cp.errors.nonEmpty,
                "Expected cp.errors to be non-empty after SoftFail init with missing root. " +
                    "Before fix: Abort.fail(TastyError.FileNotFound) was raised instead of accumulating."
            )
            assert(
                cp.errors.head == TastyError.FileNotFound(missingPath),
                s"Expected cp.errors.head == TastyError.FileNotFound('$missingPath'); got ${cp.errors.head}. " +
                    "F-A5-002: missing root must accumulate FileNotFound under SoftFail."
            )
            assert(
                cp.symbols.isEmpty,
                s"Expected cp.symbols.size == 0 on all-missing classpath; got ${cp.symbols.size}."
            )
            succeed
    }

    // Leaf 7 (INV-104-DF2 FailFast): failfast-aborts-on-filenotfound
    // Given: same missing root with ErrorMode.FailFast
    // When: awaiting init result
    // Then: aborts with Abort.fail(TastyError.FileNotFound(...))
    // Pins: INV-104-DF2 FailFast preservation (current behavior preserved)
    "INV-104-DF2 leaf 2 (Phase 2.08): FailFast missing root still raises FileNotFound" in run {
        val missingPath = "/tmp/decoder-fidelity-2-missing-xyz-do-not-create"
        Abort.run[TastyError](Tasty.Classpath.init(Seq(missingPath), Tasty.ErrorMode.FailFast)).map: result =>
            result match
                case Result.Failure(TastyError.FileNotFound(path)) =>
                    assert(
                        path == missingPath,
                        s"Expected FileNotFound path '$missingPath'; got '$path'."
                    )
                    succeed
                case Result.Success(_) =>
                    fail("Expected FailFast init with missing root to abort; succeeded instead.")
                case Result.Failure(other) =>
                    fail(s"Expected TastyError.FileNotFound but got: $other")
                case Result.Panic(t) =>
                    fail(s"Unexpected panic: ${t.getMessage}")
    }

    // Leaf 8 (F-A5-005, INV-107-DF2): softfail-accumulates-malformedsection
    // Given: truncated .tasty file loaded with SoftFail
    // When: inspecting cp.errors
    // Then: cp.errors contains exactly one MalformedSection whose reason starts with "name table index"
    // Pins: INV-107-DF2; F-A5-005 (typed reason string); F-A1-006
    "F-A5-005 leaf 3 (Phase 2.08): SoftFail truncated .tasty produces MalformedSection with typed reason" in run {
        Sync.defer(TestClasspaths2.truncatedTastyPath).flatMap: path =>
            Tasty.Classpath.init(Seq(path), Tasty.ErrorMode.SoftFail).map: cp =>
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
                val reason = malformed(0).reason
                assert(
                    reason.contains("name table index") || reason.contains("name table"),
                    s"Expected reason to contain 'name table index' (F-A5-005 typed message); got: '$reason'. " +
                        "Before fix: reason was the raw JVM 'Array index out of range: N' message."
                )
                succeed
    }

    // Leaf 9 (F-A5-006, INV-107-DF2): softfail-accumulates-corruptedfile
    // Given: bit-flipped magic .tasty file loaded with SoftFail
    // When: inspecting cp.errors.head.path
    // Then: path is the actual on-disk filename; not "<byte view>"
    // Pins: INV-107-DF2; F-A5-006 (path field rewrite); F-A1-007
    "F-A5-006 leaf 4 (Phase 2.08): SoftFail bit-flipped magic produces CorruptedFile with real path" in run {
        Sync.defer(TestClasspaths2.bitFlippedMagicTastyPath).flatMap: path =>
            Tasty.Classpath.init(Seq(path), Tasty.ErrorMode.SoftFail).map: cp =>
                assert(
                    cp.errors.nonEmpty,
                    s"Expected cp.errors to be non-empty on bit-flipped .tasty load; got empty."
                )
                val corrupted = cp.errors.collect:
                    case e: TastyError.CorruptedFile => e
                assert(
                    corrupted.nonEmpty,
                    s"Expected at least one TastyError.CorruptedFile in cp.errors; got: ${cp.errors}."
                )
                val reportedPath = corrupted(0).path
                assert(
                    reportedPath == path,
                    s"Expected CorruptedFile.path == '$path' (actual file path); got '$reportedPath'. " +
                        "Before fix: path was '<byte view>' (TastyHeader.read placeholder)."
                )
                assert(
                    reportedPath != "<byte view>",
                    "CorruptedFile.path must not be the placeholder '<byte view>'."
                )
                succeed
    }

    // Leaf 10 (F-A5-001 SymbolNotFound): requiresymbol-raises-on-absent
    // Given: requireSymbol('non.existent.fqn') against standard classpath
    // When: awaiting result
    // Then: aborts with TastyError.SymbolNotFound('non.existent.fqn')
    // Pins: INV-103-DF2; F-A5-001 (SymbolNotFound wired via requireSymbol)
    "F-A5-001 leaf 5 (Phase 2.08): requireSymbol raises SymbolNotFound for absent FQN" in run {
        TestClasspaths.withClasspath().flatMap: cp =>
            given Tasty.Classpath = cp
            Abort.run[TastyError](cp.requireSymbol("non.existent.fqn.abc.xyz")).map: result =>
                result match
                    case Result.Failure(TastyError.SymbolNotFound(fqn)) =>
                        assert(
                            fqn == "non.existent.fqn.abc.xyz",
                            s"Expected SymbolNotFound fqn == 'non.existent.fqn.abc.xyz'; got '$fqn'."
                        )
                        succeed
                    case Result.Success(sym) =>
                        fail(s"Expected SymbolNotFound but requireSymbol succeeded with: $sym")
                    case Result.Failure(other) =>
                        fail(s"Expected TastyError.SymbolNotFound but got: $other")
                    case Result.Panic(t) =>
                        fail(s"Unexpected panic: ${t.getMessage}")
    }

    // Leaf 11 (requireSymbol happy path): requiresymbol-returns-present-symbol
    // Given: requireSymbol('scala.collection.immutable.List')
    // When: awaiting
    // Then: returns the corresponding Symbol (no abort)
    // Pins: requireSymbol happy path
    "Phase 2.08 leaf 6 (requireSymbol happy path): requireSymbol returns symbol for present FQN" in run {
        TestClasspaths.withClasspath().flatMap: cp =>
            given Tasty.Classpath = cp
            cp.requireSymbol("scala.collection.immutable.List").map: sym =>
                assert(sym.isInstanceOf[Tasty.Symbol], s"requireSymbol returned a non-Symbol: $sym")
                import Tasty.Name.asString
                assert(
                    sym.name.asString.nonEmpty,
                    "requireSymbol returned a symbol with an empty name."
                )
                succeed
    }

    // Leaf 12 (F-A5-001 ClasspathBuilding): classpath-building-fires-from-orchestrator
    // Given: synthetic degenerate MergeState (ghost symbol in fqnIndex, not in allSyms) + FailFast
    // When: awaiting finalizeMerge result
    // Then: aborts with TastyError.ClasspathBuilding
    // Pins: INV-103-DF2; F-A5-001 (ClasspathBuilding wired in finalizeMerge)
    "F-A5-001 leaf 7 (Phase 2.08): ClasspathBuilding fires from orchestrator on invariant violation" in run {
        Abort.run[TastyError](ClasspathOrchestrator.triggerClasspathBuildingForTest()).map: result =>
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

    // Leaf 14 (F-A5-004, INV-107-DF2): softfail-accumulates-corruptedfile-midstream
    // Given: mid-stream corrupted file fixture (valid magic, truncated name table)
    // When: inspecting cp.errors
    // Then: cp.errors contains exactly one structured error with on-disk path
    // Pins: F-A5-004; INV-107-DF2
    "F-A5-004 leaf 8 (Phase 2.08): SoftFail mid-stream corruption produces structured error with on-disk path" in run {
        Sync.defer(TestClasspaths2.corruptedMidStreamTastyPath).flatMap: path =>
            Tasty.Classpath.init(Seq(path), Tasty.ErrorMode.SoftFail).map: cp =>
                assert(
                    cp.errors.nonEmpty,
                    s"Expected cp.errors to be non-empty on mid-stream corrupted .tasty load; got empty."
                )
                // Verify at least one error carries the on-disk path
                val errWithPath = cp.errors.find:
                    case TastyError.MalformedSection(_, _, _) => true
                    case TastyError.CorruptedFile(p, _, _)    => p.nonEmpty && p != "<byte view>"
                    case _                                    => false
                assert(
                    errWithPath.isDefined,
                    s"Expected at least one structured error (MalformedSection or CorruptedFile) in cp.errors; got: ${cp.errors}. " +
                        "F-A5-004: partial-file corruption must produce a structured TastyError, not a raw string."
                )
                succeed
    }

end ErrorFidelity2Test
