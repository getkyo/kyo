# Phase 4 In-Flight Review (pulse 1)

Pulse 1: 2026-05-28T00:00Z
Files reviewed:
- `kyo/ExtrasEncoder.scala` (15 lines)
- `kyo/IdStrategy.scala` (21 lines)
- `kyo/UnknownMethodPolicy.scala` (21 lines)
- `kyo/JsonRpcEndpoint.scala` (77 lines)
- `kyo/internal/JsonRpcEndpointImpl.scala` (456 lines)

Extra skeleton files also in dirty tree (correct phase-forward stubs, not violations):
- `kyo/CancellationPolicy.scala` (4 lines, sealed trait stub)
- `kyo/MessageGate.scala` (4 lines, sealed trait stub)
- `kyo/ProgressPolicy.scala` (4 lines, sealed trait stub)

## Plan anchor

- **Files to produce**: expected 6 (ExtrasEncoder, IdStrategy, UnknownMethodPolicy, JsonRpcEndpoint, JsonRpcEndpointImpl, JsonRpcEndpointTest). Present: **5 of 6** - `JsonRpcEndpointTest.scala` is ABSENT.
- **Tests**: expected 18 leaves (Tests 33-50). Present: **0 of 18** - test file does not exist yet.
- **Public API additions**: ExtrasEncoder (type alias + companion) PRESENT; IdStrategy (enum) PRESENT; JsonRpcEndpoint (class + companion) PRESENT; JsonRpcEndpoint.Config PRESENT; JsonRpcEndpoint.Pending PRESENT; JsonRpcEndpoint.init PRESENT; UnknownMethodPolicy (skeleton) PRESENT. All 7 expected public types are present. Also added: `CancellationPolicy`, `MessageGate`, `ProgressPolicy` sealed-trait stubs (forward stubs, harmless but out of scope for Phase 4 declaration).

## Reward-hacking checks

| Pattern | Verdict | Citation |
|---|---|---|
| Verification commands run? | UNKNOWN | Agent has written source but no test file exists; cannot confirm sbt runs happened. The STEERING.md rule requires compile after each file. Cannot verify from tree state alone. |
| Compile-only "success" with no tests | LIKELY (benign at this stage) | Tests 33-50 absent. Source may compile but phase is incomplete. |
| Selective verification / pending without reason | CLEAN | No `pending` subtask markers visible in source. |
| Priority inference comment ("edge-case / not prioritary") | CLEAN | No such comment found. |
| Scope substitution (simpler variant of spec) | FLAG | `readerFiber` stored as `Fiber.unit` (impl line 441, 465). The agent does not fork a dedicated reader fiber; Exchange's `initUnscoped` forks its own reader internally. This is likely correct (Exchange owns the reader loop), but `Fiber.unit` stored and then interrupted in finalizer step 2 is a no-op interrupt, not a real reader cancel. The supervision plan says to verify reader fiber handling explicitly. |

## Drifting checks

| Pattern | Verdict | Citation |
|---|---|---|
| Architecture: Exchange + writer fiber + callerRegistry + pendingInbound state machine | CLEAN | Exchange.initUnscoped used; writerChannel(Channel[WriterMsg]) + writer Fiber.initUnscoped; callerRegistry(ConcurrentHashMap); pendingInbound(ConcurrentHashMap) all present. |
| Architecture: sendCallback sends outbound via writerChannel | WARN | sendCallback (impl lines 255-272) decodes the JSON wire string back to JsonRpcEnvelope and re-puts it on writerChannel as SendEnvelope. This is a JSON encode-decode round-trip for every outbound request. PHASE-4-PREP §3 expected `writerChannel.put(wire)` (string directly), but writerChannel is `Channel[WriterMsg]` not `Channel[String]`, so the round-trip is the agent's reconciliation. Functionally correct if codec is lossless, but wasteful and architecturally unclear. |
| Public API expansion beyond spec | WARN | `UnknownMethodPolicy` exposes `UnknownAction` enum and `dollarPrefixOverride: Boolean` field. IMPLEMENTATION.md line 263 says Phase 4 ships `UnknownMethodPolicy.minimal` only with "no dollar-prefix override". The exposed `dollarPrefixOverride` field and `UnknownAction` enum add public surface not in the Phase 4 contract. Phase 7 is supposed to add `.lsp` and `.strict`; these new public names may constrain Phase 7's design. |
| Public API restriction (method removed from §6) | CLEAN | All methods present: call, notify, callWithProgress, callPartialResults, subscribeProgress, unsubscribeProgress, cancel, awaitDrain, close. |
| Cross-cutting refactor (files outside Phase 4 list modified) | CLEAN | Only untracked new files in dirty tree. No modifications to previously committed files detected. |
| Type re-engineering: OutboundReq shape | WARN | `idSignal` typed as `Promise.Unsafe[JsonRpcId, Any]` (impl line 10). PHASE-4-PREP §4 specifies `Fiber.Promise.Unsafe[JsonRpcId, Any]`. These may be the same type (`Fiber.Promise` is `Promise`; check if `Promise.Unsafe` == `Fiber.Promise.Unsafe`). If the type alias differs, this is a naming drift. Supervisor should verify. |
| Type re-engineering: CallerInfo shape | CLEAN | Matches PHASE-4-PREP §4 exactly: method, extras, abortSignal. |
| Type re-engineering: InboundEntry shape | WARN | `InboundEntry.Running.cancelled` is `Fiber.Promise[Unit, Sync]` (impl line 26). PHASE-4-PREP §4 specifies `Fiber.Promise.Unsafe[Unit, Any]` (the unsafe promise to complete when peer requests cancel). A safe `Fiber.Promise` here means Phase 5's cancel-intercept code would need to call `.unsafe.completeDiscard` on it -- this works but is different from the spec shape. |
| Type re-engineering: WriterMsg shape | WARN | `WriterMsg` has only `SendEnvelope` and `SuppressIfCancelled`. PHASE-4-PREP §4 specifies a third `case object Poison` for teardown. Finalizer uses `writerChannel.close()` instead (impl line 158), which is acceptable but differs from the spec. Writer loop uses `Abort.run[Closed]` around `writerChannel.take` to detect close -- this works. Not a blocking issue but note the deviation. |
| Naming drift: file at correct path | CLEAN | File is at `kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcEndpointImpl.scala` as required. |
| Test-infra drift: new test base class or utility files | CLEAN | No new test utility files added. Existing `kyo/Test.scala` (base class) unchanged. |
| Internal helper proliferation | CLEAN | No undeclared private[kyo] helpers beyond the spec-listed case classes. |
| nextId called once (closure) vs per-request | CRITICAL | impl line 405: `nextId = nextIdFn()`. Exchange.initUnscoped's `nextId` parameter is `=> Id < Sync` (call-by-name). The engine calls `nextIdFn()` ONCE at init time, producing a single `JsonRpcId < Sync` computation. If `nextIdFn()` returns a counter-advancing computation, the counter advances only once. This is wrong: `nextId` must be `nextIdFn` (the thunk itself), not `nextIdFn()` (one evaluation of it). The Exchange will call the `nextId` by-name param for every request. Passing `nextIdFn()` makes all requests use the same id. |

## Scope-cutting checks (per plan-mandated test leaf 33-50)

| Leaf | Status | Notes |
|---|---|---|
| Test 33 (basic call round-trip) | ABSENT | JsonRpcEndpointTest.scala does not exist |
| Test 34 (notify, no reply) | ABSENT | Same |
| Test 35 (bidirectional simultaneous calls) | ABSENT | Same |
| Test 36 (multiple concurrent calls) | ABSENT | Same |
| Test 37 (unknown method) | ABSENT | Same |
| Test 38 (Scope cleanup) | ABSENT | Same |
| Test 39 (callerRegistry drain, pending Closed) | ABSENT | Same |
| Test 40 (callerRegistry empty after normal) | ABSENT | Same |
| Test 41 (callerRegistry empty after interrupt) | ABSENT | Same |
| Test 42 (transport closed mid-call) | ABSENT | Same |
| Test 43 (awaitDrain) | ABSENT | Same |
| Test 44 (late reply silently dropped) | ABSENT | Same |
| Test 45 (cancel, no CancellationPolicy) | ABSENT | Same |
| Test 46 (ExtrasEncoder.const propagates) | ABSENT | Same |
| Test 47 (IdStrategy.SequentialLong ids) | ABSENT | Same |
| Test 48 (IdStrategy.SequentialInt ids) | ABSENT | Same |
| Test 49 (no writes after close) | ABSENT | Same |
| Test 50 (Custom.next concurrency, no collisions) | ABSENT | Same |

All 18 test leaves are absent. The agent has produced source files only. This is consistent with being mid-phase (source-first approach), not a scope cut, PROVIDED tests are written before the agent reports completion.

## Convention sweep

| Check | Result |
|---|---|
| Em-dashes | CLEAN (0 matches) |
| AllowUnsafe without `// Unsafe:` comment | FLAG: 7 sites lack a preceding `// Unsafe:` comment: lines 225 (drainSignalRef init), 231 (extrasVal evalOrThrow), 244 (sv evalOrThrow inner), 302 (codec.decode evalOrThrow), 318 (fiber evalOrThrow), 362 (onComplete using clause), 383 (fiber initUnscoped using clause). STEERING.md rule 1: "Every site MUST carry a `// Unsafe:` comment." |
| `: Option[` | CLEAN (0 matches) |
| Semicolon line endings | CLEAN (0 matches) |
| `asInstanceOf` | FLAG: impl line 316: `.getOrElse(Fiber.unit.asInstanceOf[Fiber[Structure.Value, Any]])`. STEERING.md rule 13: "No `asInstanceOf`. Fix the types instead." |

## CRITICAL (steer immediately)

1. **impl:405 `nextId = nextIdFn()` passes a single evaluated computation, not the thunk.** Exchange's `nextId: => Id < Sync` parameter is called once per outbound request; passing `nextIdFn()` (which evaluates the counter closure once) means every request gets the same id. Must be `nextId = nextIdFn()` written as `nextId = nextIdFn()` where `nextIdFn` is `() => JsonRpcId < Sync` -- but the call-by-name `=>` boundary in Exchange.initUnscoped means the expression is re-evaluated each call. If `nextIdFn` is `() => JsonRpcId < Sync`, then `nextIdFn()` IS a `JsonRpcId < Sync` (not a function), so Exchange will use the same computation value for every id allocation. Fix: store `nextIdFn` as `IdStrategy.mkNextId(config.idStrategy)` returning `() => JsonRpcId < Sync`, and pass `nextId = nextIdFn()` -- this only works if Exchange's by-name param re-evaluates the expression each call. Because `nextIdFn()` is a function call expression, re-evaluation DOES call the counter each time. This is likely correct IF `nextIdFn` is a stable val. Supervisor must verify by reading Exchange.initUnscoped's internal usage of `nextId` to confirm it is called once per request.

2. **impl:316 `asInstanceOf[Fiber[Structure.Value, Any]]`** on the fallback when `Sync.Unsafe.run(Fiber.initUnscoped(...))` returns a failure. If the fiber init fails, the cast is unsound. Fix the types: `Fiber.initUnscoped` inside `Sync.Unsafe.run` should not fail; handle the error case explicitly instead of silently falling back to a unit fiber via cast.

3. **impl:424 `r.suppress.get` inside `Sync.Unsafe.defer` block.** `AtomicBoolean.get` returns `Boolean < Sync`, a Kyo computation. Inside `Sync.Unsafe.defer`, it must be called as `Sync.Unsafe.evalOrThrow(r.suppress.get)(using AllowUnsafe.embrace.danger)` or `r.suppress.unsafe.get`. As written, the compiler accepts it only if `suppress.get` returns a raw `Boolean` (i.e., if `suppress` is the unsafe variant). Verify: `InboundEntry.Replying.suppress` is typed `AtomicBoolean` (safe), so `.get` returns `Boolean < Sync`, not `Boolean`. This will either fail to compile or silently do nothing (treating the `< Sync` wrapper as a truthy value).

4. **`JsonRpcEndpointTest.scala` entirely absent.** The agent must produce all 18 test leaves before claiming phase completion. If the agent reports completion without this file, do not commit.

## MINOR (queue for post-commit audit)

- `UnknownMethodPolicy` exposes `UnknownAction` enum and `dollarPrefixOverride` field beyond the Phase 4 spec. These are not harmful stubs, but they add public surface IMPLEMENTATION.md did not authorize for Phase 4. Phase 7 should absorb them rather than add new fields.
- `sendCallback` does a JSON encode-decode round-trip for every outbound request (enc -> String -> dec -> Envelope -> writerChannel). Functionally correct but inefficient. Consider a `WriterMsg.SendString(wire: String)` variant to pass the already-encoded string directly to the writer fiber which calls `transport.send(env)` only for inbound replies.
- `readerFiber = Fiber.unit` stored then "interrupted" in finalizer step 2 is a no-op interrupt. If Exchange.initUnscoped forks its own reader, step 2 of the §6.4 finalizer does nothing. This may be intentional (let Exchange's own close handle reader teardown in step 5), but it means the documented 8-step order is not faithfully implemented for steps 2 and 3.
- 7 `AllowUnsafe` call sites lack the mandatory `// Unsafe:` comment (convention sweep result above). Must be added before commit.
- `PHASE-5-PREP.md` (399 lines) is already in the dirty tree as an untracked file. This is acceptable (supervisor wrote it ahead of time), not an agent scope jump.

## Recommendation

STEER: Block commit until (1) `nextIdFn()` vs `nextId` thunk wiring is verified correct against Exchange internals, (2) `asInstanceOf` on line 316 is fixed, (3) `suppress.get` in Sync.Unsafe.defer on line 424 is fixed with `evalOrThrow` or `.unsafe.get`, (4) all 7 bare `AllowUnsafe` sites gain `// Unsafe:` comments, and (5) all 18 test leaves in `JsonRpcEndpointTest.scala` are written and passing.
