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

- Do NOT run `git add` or `git commit`. The supervisor commits. **Violation precedent: Phase 5 agent committed against rule; do not repeat. Leave the tree dirty and report.**
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

### Phase 6 IMMEDIATE STEER (in-flight pulse 3 — 11 test failures)

Three distinct bugs identified. Fix all three:

**Bug A — `pendingInbound` registration race (7 tests)**

In `internal/JsonRpcEndpointImpl.scala` `decodeCallback` Request branch, the current sequence is:
1. `Fiber.initUnscoped(handlerEffect)` ← creates handler fiber.
2. (Inside `.map { fiber => Sync.Unsafe.defer { ... } }`): `pendingInbound.put(id, Running(...))`.

The scheduler may run the handler fiber BEFORE the defer block fires; when the handler calls `ctx.progress`, the guard `pendingInbound.get(id) == Running` finds `null` and the notification drops.

**Fix**: move `pendingInbound.put(id, Running(method, fiber, cancelledPromise))` to BEFORE `Fiber.initUnscoped`. Create the cancelledPromise + register a dummy fiber-promise first, then start the handler fiber, then update the entry's fiber slot if needed. Simpler: use `Fiber.initUnscoped` with a small wrapper that awaits on a `started: Promise[Unit]` before running the handler body; outer code completes `started` after the put. OR: put the entry FIRST with a `Fiber.unit` placeholder, then init the fiber and immediately atomically replace the entry to include the real fiber handle.

The simplest fix: do `pendingInbound.put` INSIDE the `Sync.defer` block that ALSO contains `Fiber.initUnscoped`, BEFORE the fiber init line. The `Sync.defer { ... }` is sequential; the put runs before the fiber starts.

**Bug B — wrong value offered to progress channel (2 tests: `callPartialResults`)**

In `decodeCallback` step 1b (progress intercept), the code currently offers the full progress notification params (`{token: ..., value: ...}` or `{progressToken: ..., progress: ..., total: ..., message: ...}`) to `progressStreams[token]`. For `callPartialResults[T]`, callers expect the channel to carry just the `value` payload (for LSP) or the merged-without-progressToken payload (for MCP).

**Fix**: add a new field to `ProgressPolicy`:
```scala
extractProgressValue: Structure.Value => (Structure.Value < Sync)
```
- LSP: `(p) => Sync.defer(field(p, "value").getOrElse(p))`
- MCP: `(p) => Sync.defer(merge(p, ...) without progressToken)` — actually for MCP, the value IS the whole params minus progressToken. Use a helper that strips `progressToken` from a Record.

Then in step 1b, after `extractInboundToken(params)` succeeds, call `policy.extractProgressValue(params)` and offer THAT to the channel.

This is a 7th field on ProgressPolicy. Per IMPLEMENTATION.md line 370 the policy has 6 fields. Add the 7th and document the deviation in the commit message; or reuse `extractInboundToken` to also return the value (returning Maybe[(token, value)] pair). Pick the cleaner: add the 7th field `extractProgressValue` and document as a Phase 6 deviation; the alternative `(token, value)` pair return type would force changing both call sites.

**Bug C — `subscribeProgress` lazy channel registration (2 tests: timeout)**

In `ProgressEngine.subscribeProgress`, the `Channel.initUnscoped` happens inside the returned Stream's body. The channel is only created when `stream.run` is invoked, but progress notifications may arrive BEFORE the caller invokes `stream.run`.

**Fix**: do channel creation EAGERLY in a `Sync.defer` outside the Stream, register in `progressStreams[token]`, then return the stream as `Stream` (not `Stream < Sync`). The return type signature is `Stream[Structure.Value, Async & Abort[Closed]]`. If you need `Sync` for the init, change the public signature to `Stream[Structure.Value, Async & Abort[Closed]] < Sync` OR (cleaner) construct the channel inside a `for-yield` block inside the method that returns `Stream[...] < Sync`. Spec the return:
```scala
def subscribeProgress(token: Structure.Value)(using Frame):
    Stream[Structure.Value, Async & Abort[Closed]] < Sync = ...
```
Update `JsonRpcEndpoint.subscribeProgress` signature in JsonRpcEndpoint.scala to match.

After all three fixes:
- `sbt 'kyo-jsonrpc/Test/compile' 2>&1 | tail -5` green.
- `sbt 'kyo-jsonrpc/testOnly *ProgressPolicyTest' 2>&1 | tail -5` 14/14.
- `sbt 'kyo-jsonrpc/test' 2>&1 | tail -5` ALL passing.

### Phase 9 IMMEDIATE STEER (in-flight pulse 1 — flake risk)

10/10 tests pass on first run but 2 use wallclock-based parking witnesses, contradicting the plan's explicit Latch-handoff requirement (IMPLEMENTATION.md line 538). These will flake on slow CI / JS / Native.

**Fix 1 — ScenarioWsStyleTest.scala line 105 (Test 99 CDP maxInFlight)**: replace `Async.sleep(30.millis)` parking-witness with Latch handoff. Pattern: each handler completes a `Promise[Unit]` on entry; the test awaits `Promise.get` for the 8 entry-promises to fire (proves 8 handlers are inside the semaphore); then the 9th call's fiber is `Fiber.initUnscoped`'d; assert via `fiber.done.map(d => !d)` that the 9th is NOT done. NO sleep. Then complete one of the first 8's holding-promises and assert the 9th's done flag flips.

**Fix 2 — ScenarioBidiTest.scala line 130 (Test 103 MCP no-reply)**: replace `Async.sleep(100.millis)` drain-delay with a deterministic signal. Pattern: the handler holds a `Promise[Unit]`; the test completes the holding-promise; after the handler exits, the engine's writer suppresses the queued response (per Phase 5 §6.5 CAS); the test asserts frame count == 1 (the original request only). To detect "handler finished", the test can either (a) use the engine's `pendingInbound` private accessor (if exposed), or (b) wire a side-channel `Channel[Unit]` the handler sends to before returning. Pick (b).

**Fix 3 — ScenarioWsStyleTest.scala lines 110, 117**: add `// Unsafe: <justification>` comments above both `AllowUnsafe` sites. The comment text should explain why unsafe is necessary (e.g., "Promise.Unsafe.init mirrors Phase 4 idiom").

After fixes:
- `sbt 'kyo-jsonrpc/testOnly *Scenario*Test' 2>&1 | tail -5` must show 10/10 still passing.
- `grep -rn 'AllowUnsafe' kyo-jsonrpc/shared/src/test/scala/kyo/Scenario*.scala` — every hit immediately preceded by a `// Unsafe:` line.
- `grep -rnE 'Async\.sleep|Thread\.sleep' kyo-jsonrpc/shared/src/test/scala/kyo/Scenario*.scala` — 0 hits in parking-witness contexts (sleeps inside `Async.race` arms with explicit deadline-like roles are OK, but parking-witness sleeps must be replaced).

### Phase 6 IMMEDIATE STEER (in-flight pulse 2)

The 3 compile errors at lines 294/346/666 are fixed (good). One remaining error at `internal/JsonRpcEndpointImpl.scala:329`:

**Root cause**: `Emit.value(Chunk(v))` at line 329 requires `Tag[T]` directly. The `T: Schema` bound doesn't surface a `Tag[T]` resolvable for `Emit.value`.

**Fix**: change the signature of `callPartialResults[In: Schema, T: Schema]` to `callPartialResults[In: Schema, T: Schema: Tag]` in BOTH:
- `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEndpoint.scala` (the public-method signature, ~line 28)
- `kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcEndpointImpl.scala` (the impl-method signature, ~line 282)

Import `kyo.Tag` at the top of each if missing.

After this fix:
- `sbt 'kyo-jsonrpc/Test/compile' 2>&1 | tail -5` MUST be green.
- Write the 14 tests in `ProgressPolicyTest.scala`.
- Run `sbt 'kyo-jsonrpc/testOnly *ProgressPolicyTest' 2>&1 | tail -5` until 14/14.
- DO NOT commit.

### Phase 6 IMMEDIATE STEER (in-flight pulse 1)

Pulse 1 found Phase 6 in good shape architecturally (ProgressPolicy has 6 fields, ProgressEngine.scala present, 4 stubs replaced, step-1b intercept wired, monotonicity AtomicRef in place, convention sweep clean) BUT:

1. **Build does not compile.** Three type errors in `internal/JsonRpcEndpointImpl.scala`:
   - Line 294 (callPartialResults body): wrapped in `Sync.Unsafe.defer { ... }`, returns `Stream[...] < Sync` instead of `Stream[...]`. Unwrap so the body just returns the Stream directly. The stream's effect row already includes Sync via the channel ops.
   - Line 346 (subscribeProgress body): same issue. Unwrap.
   - Line 666: passing `Structure.Value < Any` where `Structure.Value` is required. Likely missing a `.map { v => ... }` or an erroneous `.andThen(_)` — read the line and fix the effect threading.

2. **ProgressPolicyTest.scala does not exist (0/14 tests).** Write it with all 14 leaves (Tests 65-78) per IMPLEMENTATION.md lines 384-397. Use ProgressEngine + JsonRpcEndpoint via inMemory transport pair.

3. **Config.progress default**: KEEP `Absent`, matching Phase 5's pattern. Do NOT change to `Present(ProgressPolicy.lsp)` (IMPLEMENTATION.md line 375 suggests changing, but Phase 5 deferred similar Config-default tightening for the same reason: stability of prior phases' tests). Pulse-1 reviewer flagged this as a "missing fix" but it's intentional.

After Fix 1 + 2:
- `sbt 'kyo-jsonrpc/Test/compile' 2>&1 | tail -5` green.
- `sbt 'kyo-jsonrpc/testOnly *ProgressPolicyTest' 2>&1 | tail -5` shows 14/14 passing.
- `sbt 'kyo-jsonrpc/test' 2>&1 | tail -5` shows ALL passing (77+).

### Phase 5 IMMEDIATE STEER (in-flight pulse 3 follow-up — 3 test failures)

Pulse 3 diagnosed two real engine bugs.

**Fix A — Tests 60 + 61 (timeout)**:

1. **Add a 2-arg `JsonRpcEndpoint.init` overload** that defaults the Config:
   ```scala
   def init(transport: JsonRpcTransport, methods: Seq[JsonRpcMethod[Async & Abort[JsonRpcError]]])(
       using Frame
   ): JsonRpcEndpoint < (Sync & Async & Scope) =
       init(transport, methods, Config())
   ```
   This makes Test 61's `JsonRpcEndpoint.init(tb, Seq(neverReturns))` compile.

2. **Fix the Timeout branch sequencing** in `JsonRpcEndpointImpl.scala` lines ~99-122. The outer `Sync.Unsafe.defer` wraps the entire `handleTimeout(...).andThen(abortSignal.get)` chain, double-nesting with `handleTimeout`'s inner `Sync.Unsafe.defer`. Remove the outer wrap; let `handleTimeout` return its `< Async` effect and chain `abortSignal.get` directly at the same level. Specifically, the Result.Failure(_: Timeout) branch should look like:
   ```scala
   case Result.Failure(_: Timeout) =>
       handleTimeout(id, config.cancellation, callerRegistry, writerChannel, frame).andThen(
           abortSignal.safe.get.map(err => Abort.fail(err))
       )
   ```
   (without an extra Sync.Unsafe.defer wrapper).

**Fix B — Test 62 (LSP ContentModified verbatim)**:

In `endpoint.cancel(id, reason)`, when `Config.cancellation = Present(policy)`:
- If `policy.expectReplyForCancelledRequest = true` (LSP): DO NOT complete `abortSignal` locally. Only send the cancel notification on the wire. The server MUST reply per LSP §3, so the caller will be unblocked by the wire response coming back through Exchange's pending map. The wire response's error code (e.g. `-32801 ContentModified` from a handler that aborted with that specific error) reaches the caller verbatim.
- If `policy.expectReplyForCancelledRequest = false` (MCP): keep the existing behavior — complete `abortSignal` locally with `policy.cancelledError.getOrElse(JsonRpcError.cancelled(reason))` (no wire reply expected from server).
- If `Config.cancellation = Absent` (CDP): complete `abortSignal` locally with `JsonRpcError.cancelled(reason)`.

After Fix A + Fix B:
- `sbt 'kyo-jsonrpc/test' 2>&1 | tail -5` must show ALL passing (49 from Phase 0-4 plus 14 from Phase 5 = 63 total).
- No regressions in Phase 0-4 tests.

### Phase 5 IMMEDIATE STEER (in-flight pulse 2 follow-up)

Pulse 2 confirmed:
- Steer 1 (Frame.internal): different now, NEW build break at `JsonRpcEndpointImpl.scala:447` — `pendingInbound.remove(id)` discards a non-Unit return (`InboundEntry`). E175 fires.
- Steer 2 (extractId): NOT REMOVED. Still 6 fields on CancellationPolicy.
- Steer 3 (14 tests): landed.

**Fix 1**: at `JsonRpcEndpointImpl.scala:447`, change:
```scala
pendingInbound.remove(id)
```
to:
```scala
discard(pendingInbound.remove(id))
```
(or use `val _ = pendingInbound.remove(id)` if `discard` is not in scope; check imports). This is a one-line fix.

**Fix 2**: REMOVE the `extractId` field from `CancellationPolicy`. The case class MUST have exactly 5 fields per IMPLEMENTATION.md line 316:
```scala
final case class CancellationPolicy(
    cancelMethod:                   String,
    encodeParams:                   CancellationPolicy.ParamsEncoder,
    expectReplyForCancelledRequest: Boolean,
    cancelledError:                 Maybe[JsonRpcError],
    protectedMethods:               Set[String]
) derives CanEqual
```
Move the id-extraction logic into `internal/CancellationEngine.scala` as a private helper keyed on `policy.cancelMethod`. The helper takes the inbound notification's params and the policy, and returns `Maybe[JsonRpcId]`. It is private to CancellationEngine.

After both fixes:
- `sbt 'kyo-jsonrpc/Test/compile' 2>&1 | tail -10` must be green.
- `sbt 'kyo-jsonrpc/testOnly *CancellationPolicyTest' 2>&1 | tail -10` must show 14/14 passing.
- `sbt 'kyo-jsonrpc/test' 2>&1 | tail -10` must show ALL prior tests still passing (no Phase 0-4 regressions).

### Phase 5 IMMEDIATE STEER (in-flight pulse 1 findings)

1. **BUILD IS BROKEN** at `CancellationPolicy.scala` lines 26 and 29: `Frame.internal` is used inside `package kyo`, which kyo lints as "Frame cannot be derived within the kyo package". The lspEncoder / mcpEncoder lambdas must use a captured `Frame` rather than `Frame.internal`. Two ways:
   - (a) Change `ParamsEncoder` to take an implicit Frame: `type ParamsEncoder = (JsonRpcId, Maybe[String]) => Frame ?=> Structure.Value < Sync`. Then the lspEncoder body becomes `(id, _) => f ?=> Sync.defer(Structure.encode(LspCancelParams(id)))(using f)`.
   - (b) Pass a closure that captures the encode site's Frame: in JsonRpcEndpointImpl where `policy.encodeParams(id, reason)` is called, pass a Frame in via `given` ambient. CancellationPolicy's ParamsEncoder type stays `(JsonRpcId, Maybe[String]) => Structure.Value < Sync` with `using Frame` parameter at the call site.
   
   Pick (a) or (b). Option (a) is cleaner. Update lspEncoder / mcpEncoder accordingly and the call site in JsonRpcEndpointImpl + CancellationEngine.

2. **`extractId: IdExtractor` field is scope expansion.** IMPLEMENTATION.md line 316 specifies 5 fields on `CancellationPolicy`: `cancelMethod`, `encodeParams`, `expectReplyForCancelledRequest`, `cancelledError`, `protectedMethods`. The agent added a 6th: `extractId` (and a new companion type `IdExtractor`). 
   
   Remove this 6th field. The inbound-cancel id extraction belongs in `CancellationEngine.handleInboundCancel` as a private match that uses `Structure.decode[LspCancelParams]` first (try-pattern), falling back to `Structure.decode[McpCancelParams]` if that fails. Or even better: each policy provides a single `extractCancelId: Structure.Value => Maybe[JsonRpcId]` derived from the cancelMethod (LSP looks for "id" field, MCP looks for "requestId" field). Move this entirely into `CancellationEngine` as private helpers keyed on `policy.cancelMethod`.

3. **All 14 tests still missing.** Write `CancellationPolicyTest.scala` with all 14 leaves (Tests 51-64) per IMPLEMENTATION.md lines 331-344. No completion claim without 14/14 passing.

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
