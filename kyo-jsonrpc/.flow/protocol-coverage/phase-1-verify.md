# Phase 1 Verify

Gate report for Phase 01 of the kyo-jsonrpc protocol-coverage campaign. Read-only on the dirty tree. No commit performed.

## 0. Dirty-tree scope

`git status --porcelain` shows exactly the 10 files in the impl report under `kyo-jsonrpc/shared/src/`:

Source (6):
- kyo-jsonrpc/shared/src/main/scala/kyo/CancellationPolicy.scala
- kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEndpoint.scala
- kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEnvelope.scala
- kyo-jsonrpc/shared/src/main/scala/kyo/internal/CancellationEngine.scala
- kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcCodecImpl.scala
- kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcEndpointImpl.scala

Tests (4):
- kyo-jsonrpc/shared/src/test/scala/kyo/CancellationPolicyTest.scala
- kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcCodecTest.scala
- kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcEndpointTest.scala
- kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcEnvelopeTest.scala

Untracked dirs (`.flow/protocol-coverage/`, `research/*.md`) are flow artifacts, not Phase 01 deliverables; they predate Phase 01 work or are flow-doc state.

## 1. Class-A catalog gates

| Catalog | fail_count | override_count | Source-code hits | Verdict |
| --- | --- | --- | --- | --- |
| reward-hacking | 1 | 0 | 0 | PASS (the single hit is a `decisions.md` paragraph; no source-code reward-hack) |
| fp-discipline | 0 | 173 | 0 | PASS |
| llm-tells | 87 | 0 | 0 | PASS (all 87 hits are inside `.flow/protocol-coverage/` and `research/*.md` files; zero in `shared/src`) |
| dev-tag | 0 | 0 | 0 | PASS |
| open-question | 9 | 0 | 0 | PASS (all hits in exploration/research docs, none in design/plan or source) |

Notes:
- The reward-hacking `dismissed-as-flake` hit fires on `phase-1-decisions.md:29` where the decision log explains the `a.close.andThen` -> `a.closeNow.andThen` rewrite triggered by the new `close(Duration)` overload's parse ambiguity. This is a legitimate API-change side effect, not a flake dismissal.
- All 173 fp-discipline OVERRIDE entries are pre-existing rationale-tagged annotations in `JsonRpcEndpointImpl` / `JsonRpcCodecImpl` / `CancellationEngine` / `CancellationPolicy` (Phase 01 does not introduce new unsafe paths).
- llm-tells em-dashes appear only in `.flow/protocol-coverage/steering.md` and `research/*.md`; the source files are clean.

## 2. Organization gate

```
flow-verify-organization.sh --check all --root kyo-jsonrpc
-> organization: violations=0
```

PASS. Phase 01 modifies existing files only and adds no new public files; Rule 8 satisfaction is inherited from the prior cleanup commit (HEAD=251602de6).

## 3. Plan-diff bucket counts

```
flow-verify-plan-diff.sh --plan 05-plan.yaml --phase 1 --baseline phase-1-baseline.txt
-> MISMATCH (missing=75 drift-from-impl=20 pre-existing=0)
```

Interpretation:
- **missing=75**: The script counts YAML-quoted snippet lines from `before:` / `after:` blocks (`path:`, `before: |`, `after: |`, `final case class Config(`, etc.) that are not literal-matched inside the dirty git diff. These are plan-document scaffolding tokens, not real "missing" deliverables. Every concrete API change called out in Phase 01 IS present in the diff (verified by source inspection below).
- **drift-from-impl=20**: All 20 entries are files inside `kyo-jsonrpc/.flow/rule8-cleanup/` (the PRIOR campaign's artifacts) and `kyo-jsonrpc/audit/flow-allow-verdicts.md`. These are untracked carryover from the Rule 8 campaign already merged at HEAD=251602de6, not Phase 01 drift.
- **pre-existing=0**: PASS. The baseline was CLEAN, so no test ran against it.

Verified by source inspection that the YAML "after" snippets correspond to actual code:
- `Malformed(id: Maybe[JsonRpcId], reason: String, raw: Structure.Value)` -> JsonRpcEnvelope.scala
- `cancellation: Maybe[CancellationPolicy] = Absent` -> JsonRpcEndpoint.scala
- `close(gracePeriod: Duration)` / `closeNow` -> JsonRpcEndpoint.scala + JsonRpcEndpointImpl.scala
- `decodeParams: CancellationPolicy.ParamsDecoder` -> CancellationPolicy.scala
- `Malformed(Present(id), reason, _)` routing to `info.abortSignal.unsafe.completeDiscard` -> JsonRpcEndpointImpl.scala (per Decision 2 substitution of `abortSignal` for the non-existent `responsePromise` field)
- `extractCancelId` returning `Maybe[JsonRpcId] < Sync` via `policy.decodeParams(sv)` -> CancellationEngine.scala

Effective plan-diff verdict: PASS for Phase 01 scope.

## 4. Compile + test

```
sbt "project kyo-jsonrpc" "Test/compile" "test"
```

Result:
- Test/compile: success (no warnings)
- test: **148 tests, succeeded 148, failed 0, canceled 0, ignored 0**
- Suites completed: 19, aborted: 0
- Total time: 6 s

All 10 new Phase 01 leaves visible in the report:

| Leaf | Test name | Observed |
| --- | --- | --- |
| 1 | "default Config() has cancellation Absent" | PASS |
| 2 | "Config() default plus LSP-shaped timeout emits no cancel" | PASS (610ms) |
| 3 | "Malformed carries Maybe id slot" | PASS |
| 4 | "Strict2_0 decoder recovers id from malformed response" | PASS |
| 5 | "Cdp decoder recovers id from malformed response" | PASS |
| 6 | "Malformed for non-Record carries Absent id" | PASS |
| 7 | "malformed response with id fails caller fast" | PASS |
| 8 | "close(0) is equivalent to closeNow" | PASS |
| 9 | "close(gracePeriod) drains before forcing" | PASS (204ms) |
| 10 | "custom policy decoder routes through decodeParams" | PASS |

Test compile + run: PASS.

## 5. INV-001 smoke

INV-001 (engine-default-neutrality + Malformed-with-id routing + CancellationPolicy.decodeParams refactor) is verified by:
- Test 1 (`cancellation == Absent` in default Config) -> Config-neutrality producer
- Test 2 (default-Config timeout emits no cancel) -> Config-neutrality consumer
- Test 3 (Malformed id slot) -> ADT producer
- Test 4 (Strict2_0 id recovery) + Test 5 (Cdp id recovery) -> codec producers
- Test 6 (non-Record -> Absent id) -> codec Absent path
- Test 7 (malformed-response-with-id fails caller fast) -> engine consumer of Present-id Malformed
- Test 10 (custom decoder routes through `policy.decodeParams`) -> engine consumer of `decodeParams` refactor
- Tests 9 / 10 also pin INV-005 (grace-period close) and INV-003 (decoder routing) producers

All 8 invariant-relevant tests PASS. INV-001 smoke: PASS.

## 6. Class-B catch-list (architecture + doc drift)

Reviewed the 6 plan adaptations in `phase-1-decisions.md`:

| # | Adaptation | Legitimacy |
| --- | --- | --- |
| 1 | `decodeParams` uses `CancellationPolicy.ParamsDecoder` context-function alias instead of bare `Structure.Value => Maybe[JsonRpcId] < Sync` | LEGITIMATE. `package kyo` can't auto-derive `Frame` at `val` sites; the alias matches the existing `ParamsEncoder` design idiom. Public-API behavior identical; the type is `Frame ?=> Maybe[JsonRpcId] < Sync` at use sites. |
| 2 | Malformed handler uses `info.abortSignal.unsafe.completeDiscard` instead of plan's `info.responsePromise.unsafe.completeDiscard` | LEGITIMATE. `CallerInfo` has no `responsePromise` field; `abortSignal` is the canonical pending-call termination channel (same pattern at line 1146 for normal Response-errors). The Malformed routing semantics match the plan. |
| 3 | Strict2_0 emits `Malformed` for non-Record `error` field instead of falling back to InvalidRequest Response | LEGITIMATE. Test 4 explicitly requires Malformed-with-id for stringy error; the original fallback would have produced a Response, breaking the test. The decoder is now strictly shape-aware. |
| 4 | Pre-existing `a.close.andThen { ... }` rewritten to `a.closeNow.andThen { ... }` in two test files | LEGITIMATE. The new `close(gracePeriod: Duration)` overload triggers Scala 3 application-style parse ambiguity for `close.andThen { block }`. Semantics of `closeNow` are documented identical to `close(Duration.Zero)`. Caught by reward-hacking scanner but justified. |
| 5 | Test 2 wraps in `Abort.run[Timeout](Async.timeout(...)(Abort.run[JsonRpcError | Closed](...)))` | LEGITIMATE. `Async.timeout` introduces `Abort[Timeout]` which must be discharged before andThen-chaining; the plan's snippet was effect-incomplete. |
| 6 | Test 7 wraps `tb.send` in `Abort.run[Closed]` inside `Fiber.initUnscoped` | LEGITIMATE. `tb.send` carries `Abort[Closed]`; `Fiber.initUnscoped` accepts via Reducible but the explicit wrap is the idiomatic safer form. |

None of the 6 adaptations hide larger architectural reshaping. All are localized type-system or API-shape constraints that the plan's pseudocode did not surface; the underlying design (Config neutrality, Malformed-with-id, decodeParams refactor, graceful close) is implemented as designed.

Decisions log entries match implementation: confirmed by inspection of `decisions.md` against the 6 listed source files.

Doc drift: none. No `02-design.md` or `04-invariants.md` claim is contradicted by the implementation.

## 7. Verdict

**PASS.**

All gates green:
- Class-A catalogs: 0 source-code hits (all surface findings are plan/research docs or rationale-tagged OVERRIDE annotations)
- Organization: violations=0
- Plan-diff: every Phase 01 deliverable present; "missing/drift" entries are scanner artifacts of YAML snippet matching and prior-campaign untracked dirs
- Compile: success
- Tests: 148/148 PASS including all 10 new leaves
- INV-001 smoke: PASS
- Class-B: 6 plan adaptations all legitimate, no doc drift, no architecture substitution

Phase 01 is ready for commit.
