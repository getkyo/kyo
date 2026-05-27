# Phase 3 In-Flight Review (pulse 1)

Pulse 1: 2026-05-26T00:00:00Z
Files reviewed:
- `kyo-reflect/execution-plan-perf.md` (Phase 3 section, lines 134-169)
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/ClasspathOrchestrator.scala` (git diff, ~300 lines)
- `kyo-reflect/shared/src/test/scala/kyo/ClasspathOrchestratorPipelineTest.scala` (full, 226 lines)
- `kyo-core/shared/src/main/scala/kyo/Channel.scala` (lines 200-320, close/streamUntilClosed API)

## Plan anchor

- ### Files to produce: 1 expected (ClasspathOrchestratorPipelineTest.scala) | 1 present (untracked `??`)
- ### Files to modify: 1 expected (ClasspathOrchestrator.scala) | 1 present (`M`); git status shows no unexpected source file modifications
- ### Tests: 8 expected | 8 present (T1-T8 at lines 80-223)
- ### Public API: none changed | VERIFIED — `openInto` signature stable; only `runPhaseAB` (private) gained `roots: Seq[String]` parameter

---

## Three-stage check

- **Producer:** `Async.foreach(Chunk.from(roots), rootCount)` per root, uses `source.list(root, Chunk(".tasty", "module-info.class"))`, puts `(entryPath, kind)` pairs to `entryCh`. After `Async.foreach` completes, `entryCh.close()` called via `.andThen(entryCh.close.unit)`. | **PRESENT**

  Note: producer enumerates both `.tasty` and `module-info.class` entries (not just `.tasty` as the plan's Stage 1 description states). Module-info entries are sent through the decoder pipeline as `ModuleInfoCase`. This is a coherent design deviation via a sealed sum type `DecodeResult` — see module-info double-decode issue in CRITICAL.

- **Decoders:** `Async.foreach(Chunk.fill(decodeConcurrency)(()), decodeConcurrency)` — `decodeConcurrency` fibers each call `entryCh.streamUntilClosed()` (correct Channel.scala line 298 API), decode each entry, put `FileResultCase` or `ModuleInfoCase` to `resultCh`. After all decoder fibers complete via `decoderFibers.andThen(resultCh.close.unit)`, result channel is closed. | **PRESENT**

  Note: `Async.foreach` is used instead of the plan's suggested `Async.fill` — both are equivalent for bounded parallelism over a fixed-size collection.

- **Merger:** Single fiber launched via `Fiber.initUnscoped(mergerWork)` before producer/decoders. Drains `resultCh.streamUntilClosed()`, accumulates into `MergeState` via `mergeOneInto` / inline `moduleIndex` update. `MergeState` fields are all `mutable.*` and exclusively owned by this fiber. | **PRESENT**

- **Phase C placeholder resolution:** Runs once in `finalizeMerge(...)`, called only after `mergerHandle.get` returns (`.flatMap: _ => finalizeMerge(...)`). Not inside the per-result loop. | **PRESENT**

---

## Channel capacity check

- **entryCh capacity:** `decodeConcurrency * 4` — literal `Channel.initUnscoped[(String, String)](decodeConcurrency * 4, Access.MultiProducerMultiConsumer)` | matches plan
- **resultCh capacity:** `decodeConcurrency * 2` — literal `Channel.initUnscoped[DecodeResult](decodeConcurrency * 2, Access.MultiProducerMultiConsumer)` | matches plan
- **Access mode:** Both channels use `Access.MultiProducerMultiConsumer` — correct; multiple producer fibers on each channel, and MPMC is a safe superset for the single-consumer merger

---

## Reward-hacking checks

| Pattern | Verdict | Citation |
|---|---|---|
| Scope substitution (plan requirement replaced with weaker equivalent) | MINOR — sum-type result channel is a valid equivalent design; T6/T8 weakened (see Scope-cutting) | - |
| Compile-only "success" (stub that compiles but does not run pipeline) | CLEAN — `openInto` live path calls `runPhaseAB` with `roots` arg; pipeline is wired | diff lines ~114-133 |
| Using `close()` then `stream()` instead of `streamUntilClosed` | CLEAN — both consumers call `.streamUntilClosed()` | diff decoder and merger blocks |
| Missing `entryCh.close()` after producer (deadlock decoders) | CLEAN — `producerWithClose = producerStage.andThen(entryCh.close.unit)` | diff |
| Missing `resultCh.close()` after decoders (deadlock merger) | CLEAN — `decodersWithClose = decoderFibers.andThen(resultCh.close.unit)` | diff |

---

## Drifting checks

| Pattern | Verdict | Citation |
|---|---|---|
| Files outside plan's modify list touched | CLEAN — only `ClasspathOrchestrator.scala` (M) and `ClasspathOrchestratorPipelineTest.scala` (??) in source; two dirty .md files are planning artifacts | git status |
| Phase 3 encroaching on Phase 4 territory | MINOR — `MergeState` uses `mutable.HashMap` / `mutable.ArrayBuffer` which is Phase 4's incremental-merge design intent; no `.toMap` removed from `AstUnpickler` (Phase 4's job), so boundary is intact | diff |
| Module-info double-decode (design drift from plan) | CRITICAL — see below | diff |
| `mergeResults` refactored to per-result as plan requires | PRESENT — `mergeOneInto(state, fr)` + inline `moduleIndex` update replaces the bulk `mergeResults(fileResults, moduleIndex, cp)` | diff |

---

## Scope-cutting checks (per plan-mandated test leaf T1-T8)

| Leaf | Status | Notes |
|---|---|---|
| T1: symbol set equivalence | PRESENT_WEAKENED | Asserts `PlainClass` appears in the symbol name set (line 86). Plan requires equivalence against the pre-pipeline implementation. No pre-pipeline comparison path is run. |
| T2: FQN index parity | PRESENT_WEAKENED | Asserts `findClass("kyo.fixtures.PlainClass")` returns `Present(sym)` (line 97-101). Plan requires key-for-key index comparison. Only one known key is checked. |
| T3: arena determinism | PRESENT_WEAKENED | Compares `allSymbols.size` across two runs (lines 120-125). Plan requires "structurally equal Classpath values." Size equality does not exclude content differences. T7 partially compensates for FQN content. |
| T4: soft-fail file error | PRESENT_STRICT | Corrupted `.tasty` added; `cp.errors` asserted non-empty (lines 136-146). Matches plan. |
| T5: strict-fail file error | PRESENT_STRICT | Corrupted `.tasty`, `strict=true`, asserts `Abort[ReflectError]` without hanging (lines 151-162). Matches plan. |
| T6: channel backpressure | PRESENT_WEAKENED | 110 entries with `concurrency=2`, asserts `count > 0` only (line 177). Plan requires asserting `peak entryCh queue depth <= (decodeConcurrency * 4) + rootCount`. Queue-depth invariant deferred to Phase 8 per comment at line 165. |
| T7: ordering independence | PRESENT_STRICT | Two runs on identical inputs, FQN name sets compared for equality (lines 194-199). Matches plan. |
| T8: decoder concurrency respected | PRESENT_WEAKENED | Asserts `count > 0` after 100 entries at `concurrency=2` (line 218). Plan requires "exactly 2 decoder fibers are spawned." No fiber-count assertion; only successful completion. Deferred to Phase 8 per comment at line 207. |

---

## CRITICAL (steer immediately)

1. **Module-info double-decode.** The producer enumerates `module-info.class` entries and sends them through the decoder pipeline as `ModuleInfoCase`. The merger accumulates them into `state.moduleIndex`. However, `runPhaseAB` also receives `moduleFiles: Chunk[String]` (from the outer `collectAllEntries` call at the `openInto` level), and `finalizeMerge` receives this same chunk. If `finalizeMerge` calls `readModuleInfoFiles(moduleFiles, source, strict)`, every `module-info.class` is decoded twice and the second decode overwrites `state.moduleIndex`. The tail of the diff was cut before line 479 of the original file — the body of `finalizeMerge` past the `state.fileResults` loops was not visible. **Before committing, verify `finalizeMerge` does NOT call `readModuleInfoFiles` and that `moduleFiles` is either passed as empty or not used.** If it is still called, remove it (module-info is fully handled via the streaming pipeline's `ModuleInfoCase` path now).

2. **Merger fiber is not cancelled on Abort paths.** The merger is launched as an unscoped background fiber (`Fiber.initUnscoped`). The producer and decoders are gathered via `Async.gather(producerWithClose, decodersWithClose)`. If a decoder raises `Abort[ReflectError]` (strict mode), `Async.gather` propagates the abort immediately. At that point `mergerHandle.get` is never reached, so `resultCh` is never closed, and the merger fiber blocks forever on `resultCh.streamUntilClosed()`. The fiber leaks and holds a thread. Fix: wrap the `Async.gather(...).flatMap(mergerHandle.get...)` block in a `Abort.run` or use `Scope.ensure` to call `mergerHandle.interrupt` (or `mergerFiber.cancel`) on any exit path, including abort paths.

---

## MINOR (queue for post-commit audit)

1. **T1/T2/T3 weak assertions.** None of the three correctness tests compare against the pre-pipeline output path. They only check known postconditions on the new implementation. A bug that silently drops all-but-one symbol would pass T1, T2, T3, and T7. Adding one comparison test against the legacy `mergeResults` path (even on a 1-file fixture) would give a regression anchor.

2. **T6/T8 queue-depth and fiber-count invariants deferred.** These are behavioral guarantees of the pipeline design, not only performance observables. If they are permanently deferred to Phase 8 re-profiling (which may not revisit the test file), the plan's correctness contract for the channel backpressure design is permanently underspecified.

3. **`Interner` still uses `new Interner(128)` hardcoded** inside `runPhaseAB` (diff line ~136). Phase 6 changes this callsite. The Phase 6 implementer must update this callsite inside the refactored `runPhaseAB` body, not the old location.

4. **`Async.gather` used with two arguments instead of all three stages.** Plan says "all three stages are gathered via `Async.gather`." The merger is excluded from the gather (launched separately via `Fiber.initUnscoped`). This is architecturally sound provided the CRITICAL item 2 (merger leak) is fixed, but deviates from the plan's wording.

5. **`Result.Panic(_) => Kyo.unit` in the module-info decode branch (decoder fiber).** Panics are silently swallowed. Per `feedback_log_unexpected_failures.md`, unexpected errors should be logged. At minimum, rethrow as `Abort.panic(t)` to surface the panic upward rather than silently continuing.

---

## Recommendation: STEER — fix merger fiber leak on Abort paths (CRITICAL 2) and verify/fix module-info double-decode (CRITICAL 1) before committing
