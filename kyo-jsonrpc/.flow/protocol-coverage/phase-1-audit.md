# Phase 01 Audit (post-commit deep audit)

Scope: HEAD commit `b07967942` ([jsonrpc] Phase 01: engine refactor + Config neutrality) against `02-design.md` v2, `05-plan.md` Phase 01, `phase-1-verify.md`, `phase-1-decisions.md`.

Mode: read-only. No commits. No nested subagents.

## Summary verdict

HALT. **BLOCKER count: 2.** Two cross-platform test failures shipped into HEAD because `phase-1-verify.md` ran only the JVM suite even though the plan's `crossPlatforms: [jvm, js, native]` (05-plan.yaml:4, :371) and the explicit Phase 01 verification command at 05-plan.md:392 mandate all three. The phase did not actually satisfy the plan-as-contract.

WARN: 3. NOTE: 4. Cross-platform: JVM PASS (148/148), JS 147/148 FAIL, Native 147/148 FAIL.

---

## BLOCKER (2)

### BLOCKER-1: JS test "malformed response with id fails caller fast" times out

- File:line: `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcEndpointTest.scala:495-512`
- Symptom: `unexpected Failure(kyo.Timeout: Computation has timed out after 1.seconds) (JsonRpcEndpointTest.scala:507)`. Observed by re-running `sbt 'project kyo-jsonrpcJS' 'testOnly kyo.JsonRpcEndpointTest'` against a clean checkout of HEAD (stash-isolated; no Phase 02 dirty work). The JVM suite passes because of timing slack.
- Root cause: the test as written does NOT actually drive the Malformed-with-id branch. It sends a well-formed `JsonRpcEnvelope.Response(JsonRpcId.Num(1), Absent, Present(JsonRpcError.invalidRequest("x")), Absent)`. The decoder routes this through the normal `Response(id, _, Present(error), _)` branch at `kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcEndpointImpl.scala:1140-1154`, completing `info.abortSignal`. The id sent is `Num(1)`; the call is issued with `IdStrategy.SequentialLong` which the default Config also issues as `Num(1)`. On JVM the race resolves; on JS the single-threaded scheduler order leaves the caller fiber waiting before `callerRegistry` has been populated, then the abortSignal completion is a no-op.
- Why this is a BLOCKER not a flake: test 7 is plan-mandated to pin INV-001 consumer (05-plan.md:347-351) and asserts the consumer side of Item 8 Malformed-with-id routing. It does neither. A test that times out depending on platform scheduler luck is reward-hacking-shaped (it passes on the platform the dev ran, fails on the cross-platform set the plan committed to).
- Required remedy: rewrite the test to (a) actually construct a Malformed-shaped raw record (e.g. `{"jsonrpc":"2.0","id":1,"error":"stringy"}`) and inject through a custom transport whose `incoming` emits the malformed envelope, OR (b) send a Response payload that the codec re-classifies as Malformed (both result+error present, satisfies line 102-103). The Malformed-with-id branch at `JsonRpcEndpointImpl.scala:1184-1200` is the engine path under test; the current test never enters it.

### BLOCKER-2: Native test "close(gracePeriod) drains before forcing" hangs to 1-minute Async cap

- File:line: `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcEndpointTest.scala:532-551`
- Symptom: `kyo.Timeout: Computation has timed out after 1.minutes`. Observed via `sbt 'project kyo-jsonrpcNative' 'test'` on clean HEAD.
- Root cause hypothesis: `close(gracePeriod: Duration)` body at `JsonRpcEndpointImpl.scala:638-643` calls `Async.timeout(gracePeriod)(awaitDrain)` then `finalizer`. The in-flight `q` handler sleeps 200ms then completes a `Fiber.Promise`. On Native's single-threaded scheduler the close fiber and the handler fiber compete; the `Fiber.initUnscoped(a.call[Unit, Unit]("q", ()))` at :540 starts the call but `close(1.second)` is then invoked synchronously, blocking before the call fiber gets a turn. `awaitDrain` is `inFlight.get` then `drainSignal.get` (`JsonRpcEndpointImpl.scala:632-636`); on Native this never observes a drain because the handler hasn't been scheduled yet. The 1.second `Async.timeout` itself should then fire and let `finalizer` run, but the test observes a 1-minute timeout, meaning the outer `kyo.Test.run` 1-minute cap fires before the inner finalizer completes.
- Required remedy: the `close(gracePeriod)` impl should ensure forward progress even when no handler-fiber has been scheduled yet. Candidates: (a) `Async.sleep(Duration.Zero)` yield before the `Async.timeout(gracePeriod)(awaitDrain)` call, (b) replace `Abort.run[Timeout](Async.timeout(...))` with `Async.race(awaitDrain, Async.sleep(gracePeriod))`. Either way, the Native pass is required by 05-plan.yaml:371 `platforms: [jvm, js, native]`.

---

## WARN (3)

### WARN-1: phase-1-verify.md falsely declares cross-platform PASS

- File:line: `kyo-jsonrpc/.flow/protocol-coverage/phase-1-verify.md:73-98`
- Drift: the verify report section 4 ran `sbt "project kyo-jsonrpc" "Test/compile" "test"`, which is the JVM-only project alias. The Phase 01 verification command at `05-plan.md:392` mandates `kyo-jsonrpcJVM/testOnly`, `kyo-jsonrpcJS/testOnly`, and `kyo-jsonrpcNative/testOnly`. The verify gate accepted JVM-only output and declared `## 7. Verdict: PASS`. This is the upstream gate failure that allowed BLOCKER-1 and BLOCKER-2 to ship.
- Decisions-log faithfulness: phase-1-decisions.md does not claim cross-platform validation either, so doc drift is bounded to the verify report.

### WARN-2: Decision 3 (errorIsRecord branch) is an over-reach beyond the plan contract

- File:line: `kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcCodecImpl.scala:90-93, :104-105` and `phase-1-decisions.md:19-25`
- Plan contract (05-plan.md:47-89): the Strict2_0 decoder gains `idMaybe` prefix on existing `Malformed(reason, raw)` sites. The plan does NOT introduce a new `errorIsRecord` shape gate. Decision 3 added it to make test 4 pass for raw `{"jsonrpc":"2.0","id":42,"error":"stringy"}`.
- Why it's a legitimate adaptation: the plan's test 4 expectation requires Malformed-with-id for a stringy error, and the prior decoder behavior (`Structure.decode[JsonRpcError](ev).getOrElse(JsonRpcError.InvalidRequest)`) would have produced a `Response(Num(42), _, Present(InvalidRequest), _)` instead of a Malformed. So the test could not pass without the gate.
- Why it warns: the implementation now silently rejects every Strict2_0 response whose `error` field is not a Record. The audit does not see a `Cdp` decoder mirror of this gate at the corresponding site (`JsonRpcCodecImpl.scala:217-233` region). The Cdp codec still falls back to `InvalidRequest` for a non-Record error, leaving the engine surface asymmetric across codecs. The plan-implied invariant ("Strict2_0 and Cdp decoders both attempt id re-extraction before Malformed") is half-implemented.
- Required remedy: either (a) propagate `errorIsRecord` gate into the Cdp decoder for symmetry, OR (b) document the asymmetry in `02-design.md`'s Item 8 semantics.

### WARN-3: `close.andThen` test rewrites are a Scala 3 overload-parse side effect with no documented mitigation

- File:line: `phase-1-decisions.md:27-29`
- Drift: Decision 4 rewrites two pre-existing tests from `a.close.andThen { ... }` to `a.closeNow.andThen { ... }` to dodge a Scala 3 overload resolution ambiguity introduced by the new `close(gracePeriod: Duration)` overload.
- Why it warns: every existing user of `endpoint.close.andThen { block }` now silently breaks at the call site. The decision log notes "Semantics of `closeNow` are documented identical to `close(Duration.Zero)`" but the documentation in question is `JsonRpcEndpoint.scala:48-52` which has zero docstrings explaining the overload ambiguity. The change is source-compatible in name but parse-incompatible in syntax. Per CLAUDE.md "no backwards compatibility, migrations replace APIs outright", this is fine — but it should be called out so consumer modules (`kyo-mcp`, `kyo-lsp`, `kyo-cdp`) know the new shape.
- Required remedy: add a doc comment on `close` and/or `closeNow` warning that `close.andThen { block }` no longer compiles because of the overload, and consumers must use `close(Duration.Zero).andThen { ... }` or `closeNow.andThen { ... }`.

---

## NOTE (4)

### NOTE-1: New test "Config() default plus LSP-shaped timeout emits no cancel" is heavy for its scope

- File:line: `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcEndpointTest.scala:467-493`
- Observation: the test wraps `tbWrap` over `tb` to record every inbound envelope, then sleeps 500ms after the timeout to observe that no `$/cancelRequest` notification was emitted. This is a 607ms test in the suite (per verify report table). The plan test #2 spec (05-plan.md:317-322) explicitly calls for the 500ms observation window. Not a defect; flagging because tests like this dominate suite wallclock; consider tightening the observation to 100ms in a future polish pass.

### NOTE-2: `extractCancelIdForTest` is a test-only escape hatch in `kyo.internal`

- File:line: `kyo-jsonrpc/shared/src/main/scala/kyo/internal/CancellationEngine.scala:19-24`
- Observation: per Decision 1, the engine adds `private[kyo] def extractCancelIdForTest(...)`. The name `*ForTest` is an LLM-tell-shaped naming pattern; the canonical kyo internal pattern is to expose the function under its real name at `private[kyo]` and let the test file in `kyo.*` reach in. Since `extractCancelId` is already `private` (not `private[kyo]`), the wrapper exists only to widen visibility. Consider widening `extractCancelId` to `private[kyo]` directly and dropping the `ForTest` alias.

### NOTE-3: `LspCancelParams` / `McpCancelParams` moved from `CancellationEngine` to `CancellationPolicy`

- File:line: `kyo-jsonrpc/shared/src/main/scala/kyo/CancellationPolicy.scala:23-24`
- Observation: per plan 05-plan.md:253, these case classes move out of `CancellationEngine` and into `CancellationPolicy`'s private companion. They remain `private`. INV-003 (`no method-name fork in CancellationEngine`) holds. No further action; flagging for the audit trail.

### NOTE-4: `Sync.defer(...)(using f)` ascription in lspDecoder/mcpDecoder is verbose

- File:line: `kyo-jsonrpc/shared/src/main/scala/kyo/CancellationPolicy.scala:42, :46, :52, :56`
- Observation: the explicit `(using f)` ascription on `Sync.defer` and `Structure.decode` is required by the `Frame ?=>` context-function shape (Decision 1). The alternative is a `Frame ?=> Sync.defer { ... }` block syntax. Not a defect; flagging for an eventual readability sweep.

---

## Cross-platform results

| Platform | Test exit | Counts |
| --- | --- | --- |
| JVM | PASS | 148 succeeded, 0 failed (per phase-1-verify.md §4) |
| JS | FAIL | 147 succeeded, 1 failed (`JsonRpcEndpointTest: malformed response with id fails caller fast`) |
| Native | FAIL | 147 succeeded, 1 failed (`JsonRpcEndpointTest: close(gracePeriod) drains before forcing`) |

Tests captured by stashing the dirty Phase 02 work and running each platform's full suite against HEAD.

---

## Verdict

HALT. BLOCKER count > 0. Phase 02 must NOT proceed until BLOCKER-1 and BLOCKER-2 are remediated and the Phase 01 verification command is re-run with all three platforms green.

WARN-1 must be addressed by re-running `phase-1-verify.md` section 4 against the plan-specified verification command (kyo-jsonrpcJVM + JS + Native), not the JVM-only `project kyo-jsonrpc` alias.

WARN-2 and WARN-3 flow into the Phase 02 prep input.
