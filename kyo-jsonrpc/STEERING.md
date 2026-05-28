# kyo-jsonrpc — Steering directives for impl agents

After every compile or test run, re-read this file. Follow any new directives immediately. Ignoring steering will result in termination.

## Scope integrity (read every cycle)

- Every line item in IMPLEMENTATION.md's `### Files to produce / modify / delete` and `### Tests` sections is **mandatory**.
- You may NOT silently drop, weaken, or substitute. If you cannot implement an item, mark its subtask `pending` with a reason and continue. The supervisor resolves it.
- You do NOT commit. Leave the tree dirty; the supervisor reads `git diff` and commits.
- You do NOT modify DESIGN.md, IMPLEMENTATION.md, the research/* files, or the audit/* files. Those are immutable for this run.
- "Simpler" is not a justification. "Redundant with X" is not a justification. "Probably not needed" is not a justification.

## Code rules (Kyo conventions — non-negotiable)

This is functional Kyo code. Every violation will be reverted.

1. **No `AllowUnsafe` in public API.** `Sync.Unsafe.defer` is permitted ONLY for state-init mirroring `Exchange` / kyo-sql precedent: `ConcurrentHashMap`, `Promise.Unsafe.init`, `Channel.Unsafe.init`, `AtomicXxx.Unsafe.init`. Every site MUST carry a `// Unsafe:` comment with the justification.
2. **Side effects are deferred.** Use `Sync.defer { ... }` for any IO; never eagerly evaluate.
3. **Kyo types only**: `Maybe` (not `Option`), `Chunk` (not `List`/`Vector`), `Span` (not `Array`), `Result` (not `Either`/`Try`), `Fiber.Promise` (not Java promises). `Seq` is permitted only as public API param types per `feedback_seq_vs_chunk`.
4. **Mutable state primitives**: `AtomicRef`, `AtomicLong`, `AtomicInt`, `AtomicBoolean` from `kyo.Atomic` — NOT `java.util.concurrent.atomic.*` directly. Exception: `ConcurrentHashMap` is fine when Exchange itself uses it (the precedent is there).
5. **`Frame` propagation**: every public method takes `(using Frame)`. Never use `Frame.internal` or `Frame.unknown` in user-callable code.
6. **Wire types `derives Schema, CanEqual`.**
7. **No type aliases for effect rows** (`type Eff = Sync` is banned). Spell out the effect row at each use site.
8. **No em-dashes (`—`) in ANY output**: code, comments, docs, commits. Use commas, parentheses, colons, sentence breaks.
9. **Public API in package `kyo`. Implementation in `kyo.internal`.**
10. **Lowercase namespace objects** for nested internals (`JsonRpcEndpoint.internal`, not `Internal`).
11. **No semicolons** (`;`) chaining statements.
12. **No default params on `private[kyo]` methods**: explicit at every call site.
13. **No `asInstanceOf`**. Fix the types instead.
14. **No `var` for shared state**. Use `AtomicRef` / Atomic primitives.
15. **`Maybe[T] = Absent`** for optional fields, NOT `Option[T]` or `null`.
16. **No comments unless the *why* is non-obvious.** Don't narrate what the code does.
17. **Doctests / scaladoc**: minimal. The design doc is the spec.

## Test rules

1. Tests extend `kyo.Test` from `kyo-prelude` (`abstract class Test extends AsyncFreeSpec ...`).
2. Tests live in `kyo-jsonrpc/shared/src/test/scala/kyo/`. All tests are cross-platform; NEVER move tests to a platform-specific folder.
3. Failing tests are the deliverable when they expose bugs. NEVER weaken a test to pass; fix the impl.
4. Tests use the PUBLIC API on the left-hand side; internals only on the RHS for verification.
5. Test names are sentences in backticks: `"sends a request and awaits the response" in { ... }`.

## Verification cadence (per agent)

- After each file written: `sbt 'kyo-jsonrpc/Test/compile'` (JVM). Tail to last 10 lines.
- After all phase files written: `sbt 'kyo-jsonrpcJVM/testOnly *<TestClass>' 2>&1 | tail -20`.
- At phase boundary the supervisor runs cross-platform compile (JVM + JS + Native).

## Forbidden actions

- Do NOT run `git add` or `git commit`. The supervisor commits.
- Do NOT modify the build.sbt for other modules.
- Do NOT touch CLAUDE.md or any file outside `kyo-jsonrpc/` plus the single `build.sbt` line for adding the module.
- Do NOT introduce dependencies beyond `kyo-prelude`, `kyo-core`, `kyo-schema`.
- Do NOT add `// TODO Phase N` markers and claim the work done. If something is incomplete, mark the subtask `pending` with reason.
- Do NOT report "phase complete" without actually running the targeted test command and quoting verbatim output in your summary.
- Do NOT use `pkill`/`kill` against any process you didn't start.

## When stuck

- If you cannot get a type to compile after 3 attempts, mark the subtask `pending`, write the failing snippet + compiler error to your summary, and stop. Don't invent a workaround.
- If a Kyo API doesn't behave as DESIGN.md expects, write a one-line note to this STEERING.md under "Findings from impl" so the supervisor sees it.

## Supervisor discipline (binding on every phase)

These are non-negotiable rules the supervisor follows. Agents inherit them transitively.

1. **No issue left unfixed before launching the next phase.** Every verification finding (compile error, test failure, design mismatch, scope drift, naming violation, em-dash, audit-flagged item — BLOCKER, WARN, AND NOTE) is resolved BEFORE Phase N+1 launches. Steer or re-launch the same agent. Do NOT accumulate findings. Do NOT defer to "future cleanup" or "later phase".

2. **Commit after verification, not before.** Verification order each phase: a) `git diff --stat` matches plan, b) targeted tests pass (with verbatim output quoted), c) cross-platform compile clean, d) design-doc compliance check, e) CONTRIBUTING.md sweep (no AllowUnsafe in public, no em-dashes, kyo types only, etc.), f) audit findings all triaged. Only when all six are green does the supervisor commit.

3. **The commit message is the phase's accountability trail.** Use `[jsonrpc] phase N: <one-line summary>` + body listing files produced and test count.

4. **No work in flight at commit time.** All subtasks for the phase are `completed` (not `pending`). If a subtask had to be marked `pending` mid-flight, it is resolved (impl finished and tested) before the phase commits.

## Findings from impl

### sbt project names (from Phase 0)

The crossProject uses `.withoutSuffixFor(JVMPlatform)`, so the sbt project key for JVM is unsuffixed:

- JVM:    `sbt 'kyo-jsonrpc/Test/compile'` (NOT `kyo-jsonrpcJVM/...`)
- JS:     `sbt 'kyo-jsonrpcJS/Test/compile'`
- Native: `sbt 'kyo-jsonrpcNative/Test/compile'`

IMPLEMENTATION.md and earlier prompts use `kyo-jsonrpcJVM` in verification commands; substitute the unsuffixed name for JVM. JS / Native suffixes work as written.

### Phase 2 variance-driven adjustment to JsonRpcMethod (informational)

The trait `JsonRpcMethod[+S]` cannot declare `handle(...): Structure.Value < S` because `< [+A, -S]` makes `S` contravariant in `<`'s effect-row slot; combined with `[+S]` on the trait this produces a variance error. The kyo-ai-plugin source resolves this by having `handle` return a FIXED effect row `< (Async & Abort[JsonRpcError])`; the impl-classes accept handlers of `Out < S` and use `ev.liftContra` to bridge.

Phase 2 follows that exact pattern. DESIGN.md §5 prose still says `< S`; treat the Phase-2 implementation as the canonical resolution. If future phases need the broader effect row from the trait API, the impl row stays fixed.

### Phase 1 post-commit fixes (apply BEFORE Phase 2 launches)

Two issues found by supervisor verification of Phase 1:

1. **`JsonRpcResponse.success` signature is too loose.** Currently `def success(id: JsonRpcId, result: Maybe[Structure.Value])` allows `success(id, Absent)` which produces `JsonRpcResponse(Present(id), Absent, Absent)`: invalid per JSON-RPC 2.0 (success requires `result`). Tighten to `def success(id: JsonRpcId, result: Structure.Value)(using Frame): JsonRpcResponse = JsonRpcResponse(Present(id), Present(result), Absent)`. For success-with-null-result, callers pass `Structure.Value.Null`. Update Test 16 call site accordingly.

2. **Test 9 assertion is too weak.** Accepts BOTH `Notification` and `Malformed`. Per DESIGN §3 the `Response` case takes `id: JsonRpcId` (not `Maybe`), so null-id Responses cannot be represented as Response; they go to Malformed. Test 9 must assert exactly `JsonRpcEnvelope.Malformed`. Remove the Notification branch from the pattern match.

### Phase 1 prep concern (C1 from PHASE-1-PREP.md)

The kyo-ai-plugin branch's `Schema[JsonRpcId]` uses `reader.peekType()` to dispatch by token type; **this method does NOT exist** on the `crispy-swinging-lemur` worktree. The PHASE-1-PREP.md suggests a workaround using `Result.catching { reader.long() }` to attempt parse, then `reader.string()` on failure. The impl agent for Phase 1 must use that workaround pattern; copying the kyo-ai-plugin source verbatim will fail to compile.
