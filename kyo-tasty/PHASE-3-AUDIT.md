# Phase 3 Audit — streaming pipeline via Channels

Commit audited: `4811fba87 kyo-reflect Phase 3: streaming pipeline via Channels`
Verified via `git log --oneline -5` (top commit matches).
Files in commit: 2 (matches plan).
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/ClasspathOrchestrator.scala` (M)
- `kyo-reflect/shared/src/test/scala/kyo/ClasspathOrchestratorPipelineTest.scala` (A)

All citations refer to committed HEAD blobs at `4811fba87` (extracted to `/tmp/cp_orch.scala` and `/tmp/cp_test.scala`; line numbers are within the committed file content).

---

## Test count (T1-T8)

| Leaf | Status | File:line | Notes |
|------|--------|-----------|-------|
| T1 symbol set equivalence | PRESENT_WEAKENED | ClasspathOrchestratorPipelineTest.scala:80-91 | Asserts only `names.exists(_.contains("PlainClass"))` (line 86). Plan called for "same symbol set as the pre-pipeline implementation"; no pre-pipeline reference run, single substring check on one fixture. |
| T2 FQN index parity | PRESENT_WEAKENED | ClasspathOrchestratorPipelineTest.scala:94-109 | `findClass("kyo.fixtures.PlainClass")` returns `Present(sym)` (lines 97-101). Plan called for key-for-key index comparison; only one key probed. |
| T3 arena determinism | PRESENT_WEAKENED | ClasspathOrchestratorPipelineTest.scala:112-130 | Compares only `allSymbols.size` across two runs (line 125). Plan called for "structurally equal Classpath values"; size equality cannot detect content drift. T7 partially compensates for FQN content (sets only, not arena structure). |
| T4 soft-fail file error | PRESENT_STRICT | ClasspathOrchestratorPipelineTest.scala:134-147 | Corrupted `.tasty` mixed with valid; asserts `cp.errors.nonEmpty` (line 142). Matches plan. |
| T5 strict-fail NO HANG | PRESENT_STRICT | ClasspathOrchestratorPipelineTest.scala:151-162 | Corrupted `.tasty`, `strict=true`, asserts `Result.Failure(_: ReflectError)` (line 156). Test inherits the `BaseKyoCoreTest` `run` wall-clock budget; if the pipeline hung the test would time out and fail. Matches plan intent. |
| T6 backpressure | PRESENT_WEAKENED | ClasspathOrchestratorPipelineTest.scala:166-182 | 110 entries at `concurrency=2`. Asserts only `count > 0` (line 177). Plan required asserting `peak entryCh queue depth <= (decodeConcurrency * 4) + rootCount`. Inline comment at line 165 explicitly defers queue-depth observation to Phase 8. |
| T7 ordering independence | PRESENT_STRICT | ClasspathOrchestratorPipelineTest.scala:186-204 | Two runs, FQN name sets compared for equality (line 199). Matches plan. |
| T8 decoder concurrency respected | PRESENT_WEAKENED | ClasspathOrchestratorPipelineTest.scala:209-223 | 100 entries at `concurrency=2`; asserts `count > 0` (line 218). Plan required asserting "exactly 2 decoder fibers are spawned." No fiber-count assertion; inline comment at line 207 defers to Phase 8. |

8 tests planned, 8 present. 3 STRICT, 5 WEAKENED. None MISSING.

---

## Pipeline design verification

| Aspect | Status | Citation |
|--------|--------|----------|
| Producer: `Async.foreach(Chunk.from(roots), rootCount)` per root | PRESENT | ClasspathOrchestrator.scala:151-152 |
| Producer puts to `entryCh` (cap `decodeConcurrency * 4`) | PRESENT | line 137 (`entryCap = decodeConcurrency * 4`), line 143 (`Channel.initUnscoped[(String, String)](entryCap, ...)`) |
| Decoder: `Async.foreach(Chunk.fill(decodeConcurrency)(()), decodeConcurrency)` | PRESENT | line 154 |
| Decoder drains `entryCh.streamUntilClosed()` | PRESENT | line 155 |
| Decoder puts to `resultCh` (cap `decodeConcurrency * 2`) | PRESENT | line 138 (`resultCap = decodeConcurrency * 2`), line 144 |
| Merger: single fiber draining `resultCh.streamUntilClosed()` | PRESENT | lines 160-162 (single `mergerStage` value, one consumer fiber inside `Async.foreach(stages, 3)`) |
| Stages combined via fail-fast `Async.foreach(Chunk(producer, decoder, merger), 3)` | PRESENT | lines 174-177; NOT `Async.gather`, NOT `Async.zip` |
| `Scope.ensure(entryCh.close.unit)` registered | PRESENT | line 149 |
| `Scope.ensure(resultCh.close.unit)` registered | PRESENT | line 150 |
| Happy-path `closeAwaitEmpty` on producer-done | PRESENT | line 166 (`producerStage.andThen(entryCh.closeAwaitEmpty.unit)`) |
| Happy-path `closeAwaitEmpty` on decoders-done | PRESENT | line 169 (`decoderStage.andThen(resultCh.closeAwaitEmpty.unit)`) |

The committed implementation matches the STEERING.md prescribed pattern, with one substitution: it uses `Async.foreach(stages, 3)` rather than the steering doc's `Async.collectAllDiscard(Seq(...), concurrency = 3)`. Both have the same fail-fast Abort-propagation semantics; the substitution is benign and the commit message explains the choice ("Unlike Async.gather (best-effort), Async.foreach propagates the first Abort and interrupts the other fibers via IOPromise.interrupts").

Implementation note: ensures use `close` rather than `closeAwaitEmpty` (lines 149-150). The author's rationale in the commit message and inline comment (lines 146-148) is sound: on abort paths the consumers have been interrupted and are no longer draining, so `closeAwaitEmpty` would block forever waiting for them. `streamUntilClosed` handles the `Closed` signal correctly. This deviates from the STEERING.md sample code (which used `closeAwaitEmpty` in `Scope.ensure`) but the deviation is correct and prevents the very deadlock the steering doc warned about.

---

## Anti-pattern checks

| Pattern | Verdict | Evidence |
|---------|---------|----------|
| `AtomicRef[Maybe[ReflectError]]` sidechannel | ABSENT | `grep AtomicRef /tmp/cp_orch.scala` returns no matches. The wrong-hypothesis path called out in STEERING.md was not taken. |
| `Fiber.initUnscoped(mergerWork)` + `mergerHandle.get` dance | ABSENT | `grep Fiber.initUnscoped /tmp/cp_orch.scala` returns no matches. Merger is now an inline stage in `Async.foreach(stages, 3)`. |
| `Async.gather` (best-effort semantics) | NOT USED IN LIVE CODE | Only references at line 120 (scaladoc) and line 172 (inline comment "Unlike Async.gather..."). Live combinator is `Async.foreach`. The scaladoc at line 120 ("Three concurrent stages run inside `Async.gather`") IS the stale doc-reference the agent flagged; see WARN-1. |
| `Async.zip` for Unit fibers | ABSENT | `grep Async.zip /tmp/cp_orch.scala` returns no matches. |
| Stale `Async.gather` comment at ~line 120 | PRESENT (stale) | Lines 120-123 scaladoc body of `runPhaseAB` still says "Three concurrent stages run inside `Async.gather`". The implementation at line 176 uses `Async.foreach`. The line-172 inline comment correctly references `Async.foreach`; only the scaladoc lags. See WARN-1. |

---

## Unsafe markers (new vs pre-existing)

`grep` over `/tmp/cp_orch.scala`:

- `asInstanceOf`: 0 matches in committed orchestrator.
- `Frame.internal`: 0 matches.
- `AllowUnsafe`: 2 matches (lines 300, 302). `// Unsafe: replaceSlot.set uses AllowUnsafe (covered by the import below)` and `import AllowUnsafe.embrace.danger`. Both are inside `finalizeMerge`, the post-merger Phase C placeholder/symbol assignment block. This block existed pre-Phase 3 (it is the relocated body of the old `mergeResults`); the import is pre-existing per the audit brief.
- `Sync.Unsafe.defer`: 0 matches.
- `null`: 1 match at line 495 (inside `makeUnresolvedSym`, passed as `outer: Reflect.Symbol` argument). This is pre-existing code lifted from the old `mergeResults` body (synthetic unresolved symbol construction); the scaladoc at lines 487-489 says "Mirrors TypeUnpickler.makeUnresolvedSym; duplicated here to avoid promoting a private method across package boundaries." Not introduced by Phase 3 logic; a pre-existing tech debt site relocated to this file. Flagged as NOTE-2.

No NEW unsafe markers introduced by Phase 3.

---

## CONTRIBUTING.md conformance

- "Most-used first" / file template ordering: the public `open` and `openInto` factories come before the private `runPhaseAB` pipeline, which comes before per-stage helpers (`walkRoot`, `decodeOneEntry`, `mergeOneInto`, `finalizeMerge`), then bottom-of-file utilities (`decodeTastyBytes`, `nameToString`, `makeUnresolvedSym`). Consistent with the file template.
- Public API in `kyo`, implementation in `kyo.internal`: `ClasspathOrchestrator` lives in `kyo.internal.reflect.query`. Compliant.
- Maybe / Chunk / Result / Span usage: uses `Maybe.Absent` (line 4 import), `Chunk` for fanout collections, `Result.{Success, Failure, Panic}` for `Abort.run` destructuring. Compliant.
- No `Fiber.block`: confirmed (no matches).
- No semicolons to chain statements: confirmed (no matches).
- No manual JSON: N/A (no serialization in this file).
- No `var` for shared mutable state: `MergeState` (lines 75-84) uses `mutable.HashMap` / `mutable.ArrayBuffer` as fields, but these are owned by a single merger fiber (sole writer; the pipeline design enforces single-threaded mutation). Scaladoc at lines 70-74 makes this ownership explicit. Acceptable per the CONTRIBUTING note that "Mutable state is acceptable only in performance-critical internals where it's encapsulated behind a pure interface." Phase 4 will extend this pattern further.
- No default params on internal APIs: `runPhaseAB`, `walkRoot`, `decodeOneEntry`, `mergeOneInto`, `finalizeMerge`, `readAndDecodeTastyFile`, `emptyFileResultWithError`, `decodeTastyBytes`, `nameToString`, `makeUnresolvedSym` all take explicit parameters with no defaults. `open` and `openInto` already had explicit `concurrency: Int` from Phase 1. Compliant.
- "Never block a thread": pipeline uses `Channel.put` / `streamUntilClosed`, not blocking primitives. Compliant.

---

## Cross-platform consistency

ClasspathOrchestrator.scala lives under `kyo-reflect/shared/src/main/scala`. `Channel` is cross-platform (kyo-core shared, per the plan's PERF-VERIFICATION.md §8). `Async.foreach`, `Scope.ensure`, `Sync.defer`, `Abort.run` are all kyo-prelude/kyo-core shared APIs. No JVM-specific calls in the new pipeline code. The commit message confirms "JVM + Native + JS compile."

The test file `ClasspathOrchestratorPipelineTest.scala` lives in `kyo-reflect/shared/src/test/scala/kyo/` and uses an in-memory `MemFileSource` (lines 19-54) rather than disk I/O, so the same test runs identically on all three platforms. Compliant with the `feedback_all_platforms_all_tests` rule.

---

## Steering deviation check

Plan's `### Files to modify`: `ClasspathOrchestrator.scala`. Plan's `### Files to produce`: `ClasspathOrchestratorPipelineTest.scala`. Commit `--name-only`:
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/ClasspathOrchestrator.scala`
- `kyo-reflect/shared/src/test/scala/kyo/ClasspathOrchestratorPipelineTest.scala`

Exact match. Zero steering deviation. No unrelated source files touched in this commit.

---

## Anti-flakiness measures

- T5 (strict-fail NO HANG): asserts `Result.Failure(_: ReflectError)` within the `BaseKyoCoreTest` `run` wall-clock budget. If the merger fiber were leaked (the original wrong-hypothesis bug), the test would never receive a `Result` and would time out. The test exercises the deadlock-detection path indirectly via the test framework timeout. Adequate.
- T6 (backpressure): asserts successful completion (`count > 0`) within the `run` budget. 110 entries at `concurrency=2`, channel cap = 8, so the producer must back-pressure on `put` at least 102 times. If backpressure were broken (e.g., `entryCh` size growing unbounded), the test would still pass on this small workload. Queue-depth invariant is not measured. WEAKENED but completes-within-budget gives a coarse signal.
- T8 (decoder concurrency): asserts `count > 0` within budget. Decoder-fiber count is not asserted. Same WEAKENED note as T6.

No `Thread.sleep` anywhere in the test file. No untilTrue/polling. Tests are deterministic given the `BaseKyoCoreTest.run` timeout.

---

## Findings — categorized

### BLOCKER

None. The two CRITICAL items flagged in `PHASE-3-INFLIGHT-REVIEW-1.md` (module-info double-decode in `finalizeMerge`; merger fiber leak on Abort paths) are both resolved in the committed code:

1. Module-info double-decode: `finalizeMerge` (lines 277-383) does NOT call `readModuleInfoFiles` (which was deleted, per commit message: "Dead code removed: ... readModuleInfoFiles"). `moduleIndex` is built solely from the streaming pipeline's `ModuleInfoCase` path (`mergeOneInto` lines 269-270) and consumed once at line 292 (`val moduleIndex = state.moduleIndex.toMap`). Single source of truth.
2. Merger fiber leak: merger runs as one of three stages inside `Async.foreach(stages, 3)` (line 176). On any decoder Abort, `Async.foreach` propagates the failure and interrupts the merger fiber via `IOPromise.interrupts`. `Scope.ensure(resultCh.close.unit)` (line 150) additionally guarantees `resultCh` is closed on any exit, so even if the interrupt arrived during a `take`, the merger would receive `Closed` rather than block. No `Fiber.initUnscoped` dance.

Phase 5 SLOT-A may launch.

### WARN

1. **Stale `Async.gather` reference in `runPhaseAB` scaladoc** (lines 120-123). Reads "Three concurrent stages run inside `Async.gather`" but the implementation at line 176 uses `Async.foreach(stages, 3)`. The line-172 inline comment is correct; only the doc lags. Misleading for the next reader and contradicts the commit message which prominently distinguishes `Async.foreach` from `Async.gather`. Fix in the Phase 4 commit or a follow-up doc-only nit.

2. **T1, T2, T3, T6, T8 are PRESENT_WEAKENED** (substring / single-key / size-equality / count-positive assertions, no reference comparison against pre-pipeline output). A bug that silently dropped most symbols would pass T1, T2, T3, and T7. The plan's correctness contract for the channel backpressure and decoder-concurrency invariants (T6, T8) is permanently deferred to Phase 8 re-profiling per inline comments at lines 165 and 207. If Phase 8 does not actually revisit this test file, the design invariants are never asserted. Track for Phase 8.

3. **Unsafe `Abort.run[Closed](resultCh.put(result)).unit` swallows `Closed`** (line 158). The decoder discards `Closed` results when `resultCh` has been closed early by an Abort cascade. This is intentional (comment line 157: "If resultCh closed early (strict-mode abort), silently discard") and correct for the abort path, but is a silent drop. Also at line 202 for `entryCh.put`. Per `feedback_log_unexpected_failures`, unexpected errors should be logged. `Closed` here is expected on the abort-cancellation path, so silent discard is defensible, but a single `Log.trace` would aid future debugging. NOTE-level for some reviewers; raising to WARN because two sites exhibit the same pattern and the silent discard is non-obvious.

### NOTE

1. **`Interner` still constructed as `new Interner(128)`** at line 139 of the new `runPhaseAB`. Phase 6 changes this site to `new Interner(numShards = 128, initialShardCapacity = sizeHint)`. Phase 6 implementer must locate this inside the refactored `runPhaseAB` body, not at any pre-Phase-3 location. No action for Phase 3.

2. **`null` literal in `makeUnresolvedSym` at line 495** (passing `null` as the `outer` arg to `InternalSymbol.makeSymbol`). Pre-existing code relocated from the old `mergeResults` body; not introduced by Phase 3. Scaladoc at lines 486-489 acknowledges duplication from `TypeUnpickler.makeUnresolvedSym`. Track as kyo-reflect tech debt; the STEERING.md "no null in new code" rule does not apply to relocated pre-existing code, but a future cleanup pass should sentinel/Maybe this.

3. **`MergeState.moduleIndex.toMap`** (line 292) and `Classpath.transitionToReady` (lines 373-383) round-trip `Map`/`Chunk` conversions that incur a final allocation pass. Phase 4 changes the per-file maps inside `FileResult` to `mutable.HashMap` and rewires the merger to read them directly; the bulk-final-conversion sites at `finalizeMerge` are unchanged this phase and will remain unchanged after Phase 4 (they are at the Classpath boundary, not the per-file decode boundary). No action for Phase 3.

4. **Phase 4 boundary**: Phase 3 already lifts `mutable.HashMap` / `mutable.ArrayBuffer` into `MergeState` for the merger-owned accumulators. Phase 4's stated job is to change the `FileResult` map field types from `Map` to `mutable.HashMap` and propagate that through `AstUnpickler`. Phase 3 does NOT touch `FileResult` field types (still `Map[...]` at lines 55-59) or `AstUnpickler.scala`. The boundary is intact: per-file maps are still `Map`, merger-accumulator maps are now `mutable.HashMap`. Phase 4 has its full scope ahead of it.

---

## Summary

Phase 3 ships cleanly. The wrong-hypothesis trap documented in STEERING.md was avoided; the merger-leak and module-info double-decode CRITICALs flagged in the in-flight review are both resolved; no unsafe markers or anti-patterns introduced; cross-platform safe; no steering deviation. Five of the eight tests are weakened (assert-on-known-postcondition rather than reference-equivalence or invariant-observation), with two of the five explicitly punting their invariants to Phase 8. One stale scaladoc reference to `Async.gather` remains in `runPhaseAB`. No BLOCKERs. Phase 5 SLOT-A may launch.
