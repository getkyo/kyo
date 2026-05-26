# STEERING.md — kyo-reflect performance plan

Read this on every compile/test cycle. Follow any directives immediately.

## Active plan

`kyo-reflect/execution-plan-perf.md` — 8-phase cold-load optimization plan. Baseline 55 ms cold-load / 57 ms snapshot; targets 25 ms / 5 ms.

## Scope integrity (read every cycle)

- Every line item in execution-plan-perf.md's Files to produce/modify/delete and Tests sections is mandatory.
- You may not silently drop, weaken, or substitute. If you cannot implement an item, mark its subtask `pending` with a reason and continue. The supervisor will resolve it.
- You do NOT commit. Leave the working tree dirty; the supervisor reads `git diff` and commits.
- You do NOT modify the plan, analysis docs, PERF-VERIFICATION.md, COLD-LOAD-PROFILE-FULL.md, or PLAN-VALIDATION.md.
- "Simpler" is not a justification. "Redundant with X" is not a justification. "Edge case" / "out of scope" / "probably not needed" are not justifications. Implement exactly as specified or escalate.
- Refactor phases preserve existing behavior byte-for-byte unless the plan explicitly says otherwise.

## Project-specific guardrails

- **No `Co-Authored-By` lines in commits.** Supervisor handles all commits.
- **No em-dashes** (`—` or `–`) in any output (code, prose, commits, comments).
- **No `Frame.internal`** anywhere in production code.
- **No `AllowUnsafe`** outside of justified bridging sites; if added, include a `// Unsafe:` comment with the reason.
- **No `asInstanceOf`** outside macro source (emitted in `'{...}` quotes is allowed).
- **No `null`** in new code; use sentinel objects or Maybe.
- **No `var` for shared mutable state.** Use AtomicRef/AtomicInt/AtomicLong/AtomicBoolean.
- **No default params on internal/private APIs.** Every caller passes explicitly.
- **No explicit `[E]` on `Abort.fail`** that inference could resolve; keep only when removing it breaks compilation.
- **Public API in `kyo` package; implementation in `kyo.internal`.**
- **Tests live cross-platform in `kyo-reflect/shared/src/test/scala/kyo/`** unless the feature is platform-specific (JVM-only for jar handling: tests go in `kyo-reflect/jvm/src/test/`).
- **Use Kyo types:** Maybe not Option, Chunk not Seq (for internal storage), Span where mutability not needed, Result not Either.
- **No `Fiber.block`.** Use `fiber.safe.get` or `onComplete`.
- **No semicolons** to chain statements.
- **No manual JSON.** Use kyo-http's Json with derives Json.

## NEVER STOP (supervisor's hard rule)

The supervisor drives every phase through commit and immediately launches the next. Valid stopping points: plan exhausted (Phase 8 green), 3-retry blocked task with documented repro, explicit user "stop". Anything else is a stall.

## Test-run cadence (impl agent rule)

Inside a sub-phase agent: targeted `testOnly` for files touched in this phase + cross-platform `Test/compile`. Never run the full suite from inside an agent. Phase-group full suites are supervisor-driven.

## Verification before commit

Supervisor runs verification before committing each phase. Agents leave dirty trees; falsely claiming "committed" is a hard violation.

## Sequential cross-platform test runs

Never run JVM/JS/Native suites in parallel. One platform at a time (resource contention, Chrome instances, ports). For kyo-reflect: JVM first, then Native, then JS.

## Steering log (mid-flight corrections)

### 2026-05-26: Phase 3 hard reset — wrong-hypothesis course-correct

The previous SLOT-A spent 30+ minutes chasing a wrong hypothesis (Async.foreach "type erasure to Any" via Fiber.internal.foreachIndexed's `IOPromise[Any, ...]`). That diagnosis is INCORRECT. The `IOPromise[Any, ...]` is only static widening; the runtime error value is preserved. `Fiber.getResult` reads the error back as ReflectError per the Fiber's declared type. Do NOT pursue the type-erasure / AtomicRef workaround pattern.

The REAL bugs in the current ClasspathOrchestrator.scala dirty tree:

1. **T1 data loss — producer uses `entryCh.close` instead of `closeAwaitEmpty`.** Per Channel.scala lines 217 vs 228: `close` returns buffered items in the `Maybe[Seq[A]]` result rather than draining them to consumers, and fails pending takes with `Closed`. If the producer puts items and finishes before all decoders have drained them, those items are LOST (returned via the close result, not delivered). Fix: producer must call `entryCh.closeAwaitEmpty.unit` (not `entryCh.close.unit`) after `Async.foreach` returns, so the channel waits for decoders to drain before transitioning to fully-closed.

2. **T5 hang — decoders raising Abort skip resultCh close.** Current `.andThen(resultCh.closeAwaitEmpty.unit)` runs only on the success path of `decoderFibers`. In strict mode, when a decoder raises `Abort[ReflectError]`, the andThen never executes, resultCh never closes, and the merger fiber blocks forever on `streamUntilClosed`. Fix: register `Scope.ensure` for `resultCh.closeAwaitEmpty` (or use `Abort.run` + explicit close on both paths) so resultCh always closes on any exit (success/abort/interrupt). Same fix applies to `entryCh.close` on the producer side (use closeAwaitEmpty in a `Scope.ensure`).

3. **Dead code to remove:** `moduleFiles: Chunk[String]` parameter in `runPhaseAB` and `finalizeMerge` (unused since module-info now flows through the pipeline). `readModuleInfoFiles` method (dead). `collectAllEntries` may not need to partition by suffix any more if both kinds flow through the same channel; simplify to a single Chunk return if so.

Concrete pattern (use this verbatim, or close to it):

```scala
Channel.initUnscoped[(String, String)](entryCap, Access.MultiProducerMultiConsumer).flatMap: entryCh =>
  Channel.initUnscoped[DecodeResult](resultCap, Access.MultiProducerMultiConsumer).flatMap: resultCh =>
    val producerStage = Async.foreach(Chunk.from(roots), rootCount): root =>
        walkRoot(root, entryCh, source)

    val decoderStage = Async.foreach(Chunk.fill(decodeConcurrency)(()), decodeConcurrency): _ =>
        entryCh.streamUntilClosed().foreach: (entryPath, kind) =>
            decodeOneEntry(entryPath, kind, ...).flatMap(resultCh.put)

    val mergerStage = resultCh.streamUntilClosed().foreach: result =>
        Sync.defer(mergeOneInto(mergeState, result))

    // Ensure channels close on any exit (success, abort, interrupt)
    Scope.ensure(entryCh.closeAwaitEmpty.unit).andThen:
        Scope.ensure(resultCh.closeAwaitEmpty.unit).andThen:
            // Producer closes entryCh when done (closeAwaitEmpty so decoders drain buffer)
            val producerWithClose = producerStage.andThen(entryCh.closeAwaitEmpty.unit)
            // Decoders close resultCh when done
            val decodersWithClose = decoderStage.andThen(resultCh.closeAwaitEmpty.unit)

            Async.collectAllDiscard(Seq(producerWithClose, decoderWithClose, mergerStage), concurrency = 3).flatMap: _ =>
                finalizeMerge(mergeState, source, strict, cp)
```

CORRECTION: use `Async.collectAllDiscard(Seq(producer, decoder, merger), concurrency = 3)`. Not `Async.gather` (best-effort, no fail-fast). Not `Async.zip` (tupling combinator, semantically misaligned even though it does fail-fast). `Async.collectAllDiscard` (Async.scala line 510) runs all three computations in parallel via `foreachDiscard`, discards their Unit results, fails fast on any Abort (cancelling siblings), and returns `Unit < (Abort[E] & Async & S)` directly. That is exactly the semantics we want: three Unit-returning concurrent stages, strict-mode decoder failure unwinds via Abort propagation, Scope.ensure cleans up channels.

Notes:
- Producer puts to entryCh; decoders drain entryCh and put to resultCh; merger drains resultCh.
- When producer finishes, it calls entryCh.closeAwaitEmpty so decoders drain the buffer. When decoders finish, they call resultCh.closeAwaitEmpty so the merger drains. All stages exit cleanly.
- On any Abort (strict-mode decoder error), `collectAllDiscard` interrupts the sibling stages and propagates the Abort. The Scope.ensure registrations close both channels on the unwind, releasing any blocked fibers.
- `Scope.ensure` registrations guarantee channels close on ANY exit (success, abort, interrupt). This eliminates the merger-leak issue.
- No need for `Fiber.initUnscoped` + `mergerHandle.get` dance. Async.gather handles concurrency and termination cleanly.
- No AtomicRef sidechannel. Strict-mode Abort propagates naturally through Async.gather, which interrupts the other fibers (Scope.ensure closes channels), unwinding cleanly.

After applying the fix, run `sbt 'kyo-reflect/testOnly *ClasspathOrchestratorPipelineTest *QueryApiTest' 2>&1 | tail -20`. T1 should populate the symbol set (no data loss). T5 should fail-fast with Abort, no hang.
