# Validation report

## Verdict: FAIL

## Per-rule results

1. Every open audit item appears in the plan — **FAIL**. C1 (engine-emitted notif extras) has no concrete phase resolution or test leaf; I3 (timeout reset on progress) and I9 (exit-after-shutdown drain) are not mapped to any phase; I14 has supervision text but no test for monotonicity CAS atomicity. Several Round-2 D-findings (D1 Structure.Value 10 cases, D6 Sync row, H4 CAS-loop monotonicity, H5 partial-result final-non-empty, H6 duplicate-inbound-id, H7 finalizer order vs Scope) are not mentioned.
2. No priority/preference language — **PASS**. No banned phrasings found ("priority", "tier", "TBD", "polish", "out of scope" in plan body, "investigate further", etc).
3. Concrete content per phase — **PARTIAL FAIL**. Most phases have the eight required sections, but Phase 4's `JsonRpcEndpoint.call/notify/callWithProgress/...` signatures are not given at signature level (only names listed); Phase 4's Exchange type pinning is named only by reference to DESIGN §6.1, never inlined.
4. No vague phrasings — **FAIL**. Line 18 "parallel with 5 and 6 conceptually, but executed after 6 to reuse the endpoint engine" mixes conceptual+actual ordering; line 249 "stubs" undefined; line 260 "`UnknownMethodPolicy.lsp` stub" undefined.
5. Deviations are explicit — **PARTIAL**. The plan defers to DESIGN.md for deviations but never re-states them. I7 (21-vs-7 public types) is not flagged in the plan.
6. Test scenarios are listed, not summarized — **PASS**. All 95 leaves are single-sentence specific.
7. Phase ordering is dependency-justified — **FAIL**. Phase 7 row says "Phase 4 (parallel with 5 and 6 conceptually, but executed after 6...)" without specific API/type justification for why "after 6" (vs after 5). Phase 4 forward-references `UnknownMethodPolicy.lsp` (Phase 7 type) in Config defaults: compile-isolation violation.
8. Zero open items — **FAIL**. Line 260 "`unknownMethod` defaults to `UnknownMethodPolicy.lsp` stub" leaves "stub" undefined. Line 249's "those are stubs" leaves Phase-4 stub shape undefined. Phase 6 modifies `JsonRpcEndpointImpl.scala` to wire ProgressPolicy but never says how the Phase-4 stub for `callWithProgress`/`callPartialResults`/`subscribeProgress`/`unsubscribeProgress` is shaped.
9. Implicit-open-items hunt — **FAIL**. See hunt section below; multiple deferred decisions.
10. All prompt-required test categories appear — **PASS** (modulo missing scenario assertions; see table).
11. All round-2 critical findings have concrete tests — **FAIL**. C1 has no test leaf; C3 covered by Test 50; C4 covered by Tests 38/39/42 + supervision; C6 covered by supervision (Phase 1, line 129); D2 covered (codec signature in line 83 uses `Abort[JsonRpcError]`); D5 covered structurally (uses `abortSignal: Promise` per line 249, no `Fiber.current` reference) but no explicit test that captures absence of `Fiber.current` use.
12. Cross-platform commitment honored — **PASS**. All tests in `shared/`. Phase 10 enforces. Phase 6 monotonicity, Phase 8 timing tests may be JS-flaky; not platform-blocking but flake risk noted in Phase 10's risk section.
13. Exchange type-parameter pinning consistent — **FAIL**. The plan never inlines `Exchange[JsonRpcId, OutboundReq, Structure.Value, String, Nothing, JsonRpcError]` (DESIGN §6.1 line 370). Phase 4 line 235 says "see DESIGN §6.1" but doesn't pin in the plan itself; later phases never contradict because they never restate.
14. Stub-vs-final API resolution — **FAIL**. Phase 4 lists `callWithProgress`, `callPartialResults`, `subscribeProgress`, `unsubscribeProgress` as public additions (line 263) but doesn't say they are typed stubs that throw / return `Sync.defer(Abort.fail)`; Phase 6 "Files to modify" (line 365) wires `ProgressPolicy` but never says the stub bodies are replaced. Signatures are never stated, so "do not change signature mid-plan" cannot be verified.
15. STEERING.md kyo-convention sweeps in every phase — **FAIL**. ZERO phases include a sweep for em-dashes, AllowUnsafe-in-public, Option-vs-Maybe, semicolons, asInstanceOf, default-params-on-private[kyo] in their Supervision plan. Phase 4 line 290 mentions `// Unsafe:` comments only; Phase 10 line 577 mentions only `scalafmtCheckAll` + TODO markers.
16. Total file/test counts add up — **FAIL**. Per-phase sum is 95 (1+16+8+6+15+12+12+9+6+10). Master table §3 says "Total test count: 95". Footer line 812 says "Total tests: 95". **But Phase 10 supervision (line 577) reads "master table sum (94 test leaves)"** — off-by-one bug.

## Failures

| Rule | IMPL.md line | Issue | Fix |
|---|---|---|---|
| 1, 11 | n/a | Audit C1 (engine-emitted notif extras) has no test leaf | Add a Phase 5 test: outbound `endpoint.cancel(id)` propagates `callerRegistry[id].extras` into the cancel-notification envelope; add a Phase 6 test: handler `ctx.progress(v)` propagates inbound `HandlerCtx.extras` into the `$/progress` notification envelope. |
| 1 | n/a | I3 (progress resets timeout) and I9 (exit-after-shutdown drain) unmapped | Phase 8 add Test 86 (or note "consumer concern" with explicit § citation); Phase 4 §6.4 finalizer step 1.5 needs an audit-named test. |
| 4, 7, 8, 14 | 260 | "`unknownMethod` defaults to `UnknownMethodPolicy.lsp` stub" — `UnknownMethodPolicy` does not exist until Phase 7 | Change Phase 4 to make `unknownMethod` field of type `Maybe[UnknownMethodPolicy] = Absent`, with the Phase-7 default flip explicitly documented in Phase 7's "Files to modify" block; OR introduce `UnknownMethodPolicy` skeleton in Phase 4 with comment "filled in Phase 7". |
| 7 | 18 | Phase 7 dependency "Phase 4 (parallel with 5 and 6 conceptually, but executed after 6...)" — no API-level justification | Rewrite as "Phase 7 depends on Phase 4's reader-fiber step-2/step-3 routing hooks; placed after Phase 6 to share the engine-modification edit cycle." |
| 13 | 235, 247 | Exchange type-parameter pinning not inlined in Phase 4 | Phase 4 "Files to produce" `JsonRpcEndpointImpl.scala` description must state: built on `Exchange[JsonRpcId, OutboundReq, Structure.Value, String, Nothing, JsonRpcError]` (six parameters, per DESIGN §6.1 line 370). |
| 14 | 263, 247 | `callWithProgress`/`callPartialResults`/`subscribeProgress`/`unsubscribeProgress` Phase-4 stub vs Phase-6 fill — undefined | Phase 4 "Files to produce" must show stub signatures: e.g. `def callWithProgress[...](...): Pending[Out] < (Async & Abort[...]) = Sync.defer(throw NotImplementedError("Phase 6"))` or document as `private[kyo]` deferred; Phase 6 "Files to modify" must say "replaces stub body in `JsonRpcEndpoint.scala`". |
| 15 | every Supervision plan | No STEERING.md kyo-convention sweep | Each Supervision plan adds a checklist line: "grep added files for `—`, `AllowUnsafe` (outside `// Unsafe:`-commented sites), `: Option[`, `;` line-end, `asInstanceOf`, default params on `private[kyo]` methods". |
| 16 | 577 | Phase 10 reads "94 test leaves" but plan totals 95 | Change to 95. |
| 9 | 463 | `RateLimitEngine.timeoutGuard` "fires cancel notification, then fails with `Abort[JsonRpcError]`" — code (LSP -32800 vs absent-policy "fails") unspecified for the absent-policy case | State explicitly the error code under `cancellation = Absent`: e.g. `JsonRpcError.cancelled(Absent)` per CancellationPolicy.cancelledError fallback chain. |
| 4 | 249 | "WITHOUT policy intercepts for Phase 4, those are stubs" — `those` antecedent ambiguous | Specify: "step 1 (cancellation policy intercept) and step 1b (progress policy intercept) are no-ops in Phase 4; step 2 (gate) is no-op; step 3 (unknown method) uses `MethodNotFound` directly." |

## Implicit-open-items hunt

- **Engine-emitted notif extras for ProgressEngine** (audit C1 not closed): Phase 6's `ProgressEngine.ctx.progress` sink-builder description (line 361) never says where the sink reads the inbound envelope's `extras` from. Resolution: HandlerCtx already carries `extras` (line 146); `progressSink` closure must capture `ctx.extras` and stamp it onto the emitted notification envelope.
- **Phase 4 stub body shapes** for the four progress methods (see rule 14 above).
- **Phase 6 `progressLastEmitted` CAS-loop** (audit H4) — Phase 6 supervision (line 395) says monotonicity `AtomicRef[Maybe[BigDecimal]]` lives in the sink closure but doesn't specify CAS-loop semantics under concurrent `ctx.progress` from the same handler. Resolution: add an explicit test where two concurrent `ctx.progress` from the same handler with values 10 and 5 both observe monotonicity.
- **Phase 6 `partialResults` non-empty-final result** (audit H5): Test 70 exists; good. But the engine routing logic that distinguishes `partialResults` vs `progressStreams` (DESIGN §6.2 line 424 was flagged ambiguous) is not pinned in Phase 6. Resolution: add an `id→token` side-table description to Phase 4's `JsonRpcEndpointImpl.scala` block.
- **Phase 1 Schema-derived case classes with `derives Schema` on JsonRpcRequest/JsonRpcResponse** (line 81): these still `derive Schema` even though they contain `Maybe[JsonRpcError]`. The audit C6 fix excluded `JsonRpcEnvelope` only; whether the Request/Response derivation works at compile-time is untested. Resolution: a Phase 1 compile-time test that simply summons `Schema[JsonRpcResponse]`.
- **Phase 4 callerRegistry drain at scope close** (DESIGN §6.4 step 6) — Phase 4 supervision (line 290) lists §6.4 finalizer order but no test verifies the drain. Resolution: Test 37 currently asserts `Abort[Closed]` after scope exit, but does not verify a pending call (with abortSignal armed) is completed via `callerRegistry` drain (vs Exchange close). Split into two tests.
- **Phase 7 `Reject` for Notification semantics** — Test 77 asserts "silently dropped" but the engine has to consume the gate's error somewhere; "discard" vs "log" is unspecified. Resolution: tie to Phase 5 C5-style "log warning" pattern.
- **Phase 9 Test 90 wallclock-flakiness on JS** — "9th call parks until one resolves" relies on timing. Resolution: use a `Latch`/`Channel`-based explicit handoff in the handler bodies of the first 8 to deterministically gate the 9th, not sleep.
- **Phase 8 Test 82 100ms vs handler-500ms-sleep** — JS scheduler latency is observable; 100ms is fragile. Resolution: use a manually-blocked handler (Promise.get on never-completed) rather than a sleep so the timeout is the only path that fires.
- **Phase 5 cancel-of-mid-allocation race** (audit H2): the registry-insert-inside-encode-callback design closes the window. But no test exercises a cancel arriving while the encode callback is mid-run. Resolution: add a deterministic test using a `Latch` to pause inside the encode callback.

## Test category cross-reference table

| Prompt category | Plan phase | Plan test # |
|---|---|---|
| Wire round-trip every envelope shape | 1 | 2-3, 6, 7, 9, 14 |
| JsonRpcId.Num/Str bare encoding | 1 | 4, 5 |
| Response with both result+error rejected | 1 | 8 |
| Maybe[JsonRpcId] Absent → no id key | 1 | 6 (implicit), 9 |
| Method dispatch valid call | 2, 4 | 18, 32 |
| Method dispatch MethodNotFound | 4 | 36 |
| Method dispatch InvalidParams | 2 | 21 |
| Method dispatch handler Abort(JsonRpcError) | 2 | 19 |
| Method dispatch handler panic → InternalError | 2 | 20 |
| Notification handler no response (frame count) | 4 | 33 |
| Bidirectional A.call(B) | 4 | 32 |
| Bidirectional concurrent | 4 | 34 |
| Bidirectional A.notify(B) | 4 | 33 |
| Cancellation peer cancel by id | 5 | 47, 48 |
| Cancellation no-op for already-completed | 5 | 54 |
| Progress 3 notifications then result | 6 | 59, 95 |
| Lifecycle Scope cleanup | 4 | 37 |
| Lifecycle transport-close mid-call | 4 | 40 |
| Three-consumer HTTP-style | 9 | 86, 87, 88 |
| Three-consumer WS-style | 9 | 89, 90, 91 |
| Three-consumer stdio-bidi | 9 | 92, 93, 94, 95 |

## Audit-finding cross-reference table

| Round-2 finding | Severity | Plan phase | Resolution mechanism |
|---|---|---|---|
| C1 engine-emitted notif extras | Critical | none | **NOT RESOLVED** |
| C2 pendingInbound Replying→done removal | Critical | 4 | line 290 supervision (Sync.ensure) + Test 50 |
| C3 cancel-during-transition CAS | Critical | 4, 5 | InboundEntry.Cancelled variant (line 249) + Test 50 |
| C4 callerRegistry timing | Critical | 4 | line 249 "callerRegistry population inside Exchange encode callback using idSignal pattern" + line 290 supervision + Tests 38/39 |
| C5 cancel for absent id | Critical | 5 | Test 54 + line 313 modify-Config |
| C6 no derives Schema on Envelope | Critical | 1 | line 82 ADT + line 129 supervision |
| D2 codec encode Abort | Disproven | 1 | line 83 signature `Abort[JsonRpcError]` |
| D3 HandlerCtx.cancelled type | Disproven | 2 | line 146 `Fiber.Promise[Unit, Sync]` |
| D5 no Fiber.current | Disproven | 4 | line 249 `CallerInfo(method, extras, abortSignal)` (no callerFiber field) |
| D6 init returns includes Sync | Disproven | 4 | line 262 `< (Sync & Async & Scope)` |
| H1 race in Running→Replying | Hazard | 4, 5 | covered by C3 mechanism |
| H4 monotonicity CAS | Hazard | 6 | line 395 supervision mentions AtomicRef but not CAS-loop semantics |
| H5 partialResults non-empty final | Hazard | 6 | Test 70 |
| I1 awaitDrain Replying semantics | Important | 4 | line 290 supervision + Test 41 |
| I3 timeout reset on progress | Important | none | **NOT RESOLVED** |
| I4 ContentModified emission verbatim | Important | 5 | Test 58 |
| I6 Unsafe in hot path | Important | 4 | line 290 `// Unsafe:` comments |
| I7 21-vs-7 public types | Important | n/a (no plan annotation) | not flagged for user approval |
| I9 exit-after-shutdown drain | Important | none | **NOT RESOLVED** |
| I14 monotonicity state | Important | 6 | line 395 supervision (partial; see H4) |
| I15 Custom.next thread-safety | Important | none | **NOT RESOLVED** |
| I16 inbound rate limit | Important | none | **NOT RESOLVED** |

(Word count: ~1080.)
