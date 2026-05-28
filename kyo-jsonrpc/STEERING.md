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

### Phase 4 IMMEDIATE STEER (in-flight pulse 3 follow-up)

Pulse 3 found four remaining items. ALL must be resolved before completion.

1. **Test 44 (late reply for cancelled outbound call dropped) is missing.** Per IMPLEMENTATION.md line 282: "Late reply for an already-cancelled (interrupted) outbound call is silently dropped by Exchange; pendingInbound is not consulted for outbound drops." Write this test: cancel an in-flight call (via abortSignal.completeDiscard or endpoint.cancel), then have the peer eventually send a Response for that id, assert the receiver does NOT throw and the cancelled caller is unaffected.

2. **Tests 38 and 39: tighten assertions.** Currently `Result.Failure(_)` accepts ANY error. The spec requires:
   - Test 38 (Exchange pending map drained): `Result.Failure(c: Closed)` (note: Exchange's pending map fails with Closed, not JsonRpcError, on close).
   - Test 39 (callerRegistry drain via abortSignal): `Result.Failure(JsonRpcError)` where the error code is some "transport closed" / internalError code per DESIGN §6.4 step 6.

3. **Test 49 (I9 exit-after-shutdown drain) must use a counting transport.** Per IMPLEMENTATION.md line 287: "verify with a counting test transport that the write count does not increase after close." The test as currently written doesn't have a counting transport. Add one (a private[kyo] InMemoryTransport variant or a wrapper around it that counts `send` invocations).

4. **`// Unsafe:` comment missing at line 342** (`writerChannel.unsafe.offer` inside `onComplete` lambda). Add `// Unsafe: writer-channel offer from Sync-only onComplete callback` immediately before that line.

5. **Sanity-check the writer fiber loop is complete.** Pulse 3 noted the impl file is 459 LOC, below the 600-1000 expected. Read JsonRpcEndpointImpl.scala's writer-fiber section. Verify all of:
   - It consumes from `outbound: Channel[WriterMsg]`.
   - For `SendEnvelope(env)`: codec-encode + transport.send.
   - For `SuppressIfCancelled(id, env)`: snapshot suppress flag (via `r.suppress.get.map`), if true AND policy says no-reply, drop; else send. THEN remove `pendingInbound` entry via `Sync.ensure`.
   - On Closed during transport.send, propagate to endpoint.close path (don't crash the writer fiber).

If the writer fiber is incomplete, complete it before claiming the phase done.

### Phase 4 IMMEDIATE STEER (in-flight pulse 2 follow-up)

Pulse 2 confirmed steer 1 (asInstanceOf), steer 2 (suppress.get typing), and steer 4 (nextId wiring) are GREEN. Steer 3 and 5 still need work:

**STILL OPEN — STEER 3: add `// Unsafe:` comments at these 7 lines in `JsonRpcEndpointImpl.scala`:**
- line 172 (callerRegistry finalizer loop)
- line 222 (initPromise.completeUnitDiscard)
- line 236 (idSignal.completeDiscard)
- line 342 (writerChannel.unsafe.offer inside onComplete)
- line 346 (onComplete closing brace)
- line 359 (writerChannel.unsafe.offer MethodNotFound path)
- line 386 (abortSignal.unsafe.completeDiscard error path)

The existing comments at lines 220 and 234 are explanatory prose, NOT `// Unsafe:` tags. Each `AllowUnsafe`-using line needs its OWN `// Unsafe: <justification>` comment on the immediately preceding line.

**STILL OPEN — STEER 5: write `JsonRpcEndpointTest.scala` with all 18 test leaves (Tests 33-50 per IMPLEMENTATION.md lines 270-289).** No completion claim until 18/18 pass on JVM. This is the LAST critical item; the rest of Phase 4 looks solid.

### Phase 4 IMMEDIATE STEER (in-flight pulse 1 findings)

The Phase 4 impl agent MUST fix the following before claiming the phase done. Each violates a STEERING rule:

1. **Remove `asInstanceOf` at impl:316.** STEERING rule 13: no `asInstanceOf` anywhere. The fallback for `Sync.Unsafe.run(Fiber.initUnscoped(...))` failing must use proper typing. If the type signature is wrong, fix the call chain so the result already has the right type.

2. **Fix `r.suppress.get` typing at impl:424.** `AtomicBoolean.get` returns `Boolean < Sync`, NOT raw `Boolean`. Using it as a `Boolean` in a match arm will fail to compile (or misbehave if forced). Use `.unsafe.get()` inside the `Sync.Unsafe.defer` block (which gives a raw `Boolean` because we're already inside an unsafe context with `AllowUnsafe.embrace.danger` in scope).

3. **Add `// Unsafe:` comment on every `AllowUnsafe` site.** Currently 7 sites at lines 225, 231, 244, 302, 318, 362, 383 lack the mandatory comment. STEERING rule 1: each AllowUnsafe-using line must have `// Unsafe: <justification>` immediately above or on the same line.

4. **Verify `nextId = nextIdFn()` in Exchange.initUnscoped at impl:405.** Exchange's `nextId` is a BY-NAME parameter (`nextId: => Id < Sync`), so passing `nextIdFn()` works only if `nextIdFn` is a `() => JsonRpcId < Sync` thunk that Exchange will re-evaluate. If `nextIdFn` is a `JsonRpcId < Sync` value, the same id will be reused for every request. Confirm the type by looking at `IdStrategy.mkNextId`'s return type. If it returns `() => JsonRpcId < Sync` (a thunk), pass it as `nextId = nextIdFn()`. If it returns `JsonRpcId < Sync` directly (already a kyo effect), pass it as `nextId = nextIdFn` (no parens; Exchange will re-evaluate by-name).

5. **All 18 test leaves MUST land before completion.** `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcEndpointTest.scala` is missing. Tests 33-50 per IMPLEMENTATION.md lines 270-289 are mandatory. Do not claim phase complete without 18/18 passing on JVM.

Re-verify the convention sweep AFTER fixing items 1-3:
```
grep -rn 'asInstanceOf' kyo-jsonrpc/shared/src        # MUST be 0
grep -rn 'AllowUnsafe' kyo-jsonrpc/shared/src         # each line MUST be within a `// Unsafe:` block
```

### Phase 2 variance-driven adjustment to JsonRpcMethod (informational)

The trait `JsonRpcMethod[+S]` cannot declare `handle(...): Structure.Value < S` because `< [+A, -S]` makes `S` contravariant in `<`'s effect-row slot; combined with `[+S]` on the trait this produces a variance error. The kyo-ai-plugin source resolves this by having `handle` return a FIXED effect row `< (Async & Abort[JsonRpcError])`; the impl-classes accept handlers of `Out < S` and use `ev.liftContra` to bridge.

Phase 2 follows that exact pattern. DESIGN.md §5 prose still says `< S`; treat the Phase-2 implementation as the canonical resolution. If future phases need the broader effect row from the trait API, the impl row stays fixed.

### Phase 1 post-commit fixes (apply BEFORE Phase 2 launches)

Two issues found by supervisor verification of Phase 1:

1. **`JsonRpcResponse.success` signature is too loose.** Currently `def success(id: JsonRpcId, result: Maybe[Structure.Value])` allows `success(id, Absent)` which produces `JsonRpcResponse(Present(id), Absent, Absent)`: invalid per JSON-RPC 2.0 (success requires `result`). Tighten to `def success(id: JsonRpcId, result: Structure.Value)(using Frame): JsonRpcResponse = JsonRpcResponse(Present(id), Present(result), Absent)`. For success-with-null-result, callers pass `Structure.Value.Null`. Update Test 16 call site accordingly.

2. **Test 9 assertion is too weak.** Accepts BOTH `Notification` and `Malformed`. Per DESIGN §3 the `Response` case takes `id: JsonRpcId` (not `Maybe`), so null-id Responses cannot be represented as Response; they go to Malformed. Test 9 must assert exactly `JsonRpcEnvelope.Malformed`. Remove the Notification branch from the pattern match.

### Phase 1 prep concern (C1 from PHASE-1-PREP.md)

The kyo-ai-plugin branch's `Schema[JsonRpcId]` uses `reader.peekType()` to dispatch by token type; **this method does NOT exist** on the `crispy-swinging-lemur` worktree. The PHASE-1-PREP.md suggests a workaround using `Result.catching { reader.long() }` to attempt parse, then `reader.string()` on failure. The impl agent for Phase 1 must use that workaround pattern; copying the kyo-ai-plugin source verbatim will fail to compile.
