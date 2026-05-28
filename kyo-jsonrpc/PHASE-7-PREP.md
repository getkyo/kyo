# kyo-jsonrpc Phase 7 prep

Phase 7 implements `UnknownMethodPolicy` (full: `.lsp`, `.strict`, `.minimal`) + `MessageGate` (real trait replacing the Phase 4 sealed-trait placeholder) + engine wiring (step 2 gate intercept + step 3 unknown-method dispatch branches in the reader fiber's `decodeCallback`). 9 tests.

---

## 1. Full UnknownMethodPolicy spec

The Phase 4 skeleton already has the correct case class shape and `UnknownAction` enum in
`kyo-jsonrpc/shared/src/main/scala/kyo/UnknownMethodPolicy.scala`. Only two preset vals are missing:

```scala
package kyo

final case class UnknownMethodPolicy private[kyo] (
    onUnknownRequest:      UnknownMethodPolicy.UnknownAction,
    onUnknownNotification: UnknownMethodPolicy.UnknownAction,
    dollarPrefixOverride:  Boolean
) derives CanEqual

object UnknownMethodPolicy:
    enum UnknownAction derives CanEqual:
        case ReplyMethodNotFound
        case Drop
        case Reject
    end UnknownAction

    val minimal: UnknownMethodPolicy = UnknownMethodPolicy(
        onUnknownRequest      = UnknownAction.ReplyMethodNotFound,
        onUnknownNotification = UnknownAction.Drop,
        dollarPrefixOverride  = false
    )

    val lsp: UnknownMethodPolicy = UnknownMethodPolicy(
        onUnknownRequest      = UnknownAction.ReplyMethodNotFound,
        onUnknownNotification = UnknownAction.Drop,
        dollarPrefixOverride  = true
    )

    val strict: UnknownMethodPolicy = UnknownMethodPolicy(
        onUnknownRequest      = UnknownAction.ReplyMethodNotFound,
        onUnknownNotification = UnknownAction.Reject,
        dollarPrefixOverride  = false
    )
end UnknownMethodPolicy
```

**Design-doc discrepancy (must resolve before coding):**
DESIGN.md §9 line 744 says `.strict` is `notifications=Drop`. IMPLEMENTATION.md line 442 (Test 82) says "strict policy: unknown notification -> Reject action closes the engine". The prompt spec above uses `Reject`. IMPLEMENTATION.md is the binding implementation contract; adopt `Reject` for `.strict`.

---

## 2. MessageGate spec

Replace the Phase 4 placeholder in `kyo-jsonrpc/shared/src/main/scala/kyo/MessageGate.scala`.
Current content is `sealed trait MessageGate private[kyo]` (one line, no body).

Full replacement:

```scala
package kyo

trait MessageGate:
    def beforeDispatch(env: JsonRpcEnvelope)(using Frame): MessageGate.Decision < Sync

object MessageGate:
    enum Decision derives CanEqual:
        case Allow
        case Reject(error: JsonRpcError)
        case Drop
    end Decision
end MessageGate
```

Key constraints:
- `trait MessageGate` is public (no `private[kyo]`, no `sealed`) so kyo-lsp / kyo-mcp can implement it.
- `beforeDispatch` returns `< Sync` only. No `Async` in the return type (Exchange reader-fiber invariant: decode callback must not park).
- `Decision` derives `CanEqual`; `Reject` carries a `JsonRpcError` payload.

---

## 3. Engine wiring touchpoints in JsonRpcEndpointImpl.scala

### 3a. Config.gate and Config.unknownMethod (already wired)

Both fields already exist in `JsonRpcEndpoint.Config` with correct types and defaults:

```
unknownMethod: UnknownMethodPolicy = UnknownMethodPolicy.minimal
gate:          Maybe[MessageGate]  = Absent
```

IMPLEMENTATION.md line 428 says "update `Config` default `unknownMethod = UnknownMethodPolicy.lsp`". The prompt says keep `.minimal`. **Resolution: keep `.minimal`.** Changing the default would break the 9 existing Phase 4 tests (Tests 33-50) that rely on unknown-request -> MethodNotFound behavior without configuring any policy. `.minimal` already does MethodNotFound for requests and Drop for notifications, which matches Phase 4 test 49's frame-count assertion.

`JsonRpcEndpointImpl` already holds `unknownPolicy: UnknownMethodPolicy` from `config.unknownMethod`. No structural change needed to Config or the constructor.

### 3b. Step 2 gate intercept (insert in decodeCallback, after step 1)

Current `decodeCallback` handles Notification + Request + Response + Malformed branches. Step 2 must run after step 1 (cancellation/progress intercepts) and before step 3 (method lookup).

The intercept applies to `Request` and `Notification` envelopes only (Response and Malformed bypass it). Wire it after the existing step 1a/1b checks fall through to the "not a cancel/progress notification" path.

Pseudo-structure for the decodeCallback Notification branch (after cancellation check passes through):

```scala
// After step 1 cancel/progress checks pass:
config.gate match
    case Present(g) =>
        g.beforeDispatch(env)(using frame).map {
            case MessageGate.Decision.Allow =>
                // proceed to step 3 method lookup (existing logic)
            case MessageGate.Decision.Reject(_) =>
                // Notifications have no id: log WARN, drop silently (no wire response)
                // Unsafe: Log.warn inside decode callback
                Sync.Unsafe.defer {
                    // note: kyo.Log.warn returns < Sync; safe to sequence
                }.andThen(Exchange.Message.Skip)
            case MessageGate.Decision.Drop =>
                Exchange.Message.Skip
        }
    case Absent =>
        // proceed to step 3 directly (existing path)
```

For the Request branch (after step 1 pass-through):

```scala
config.gate match
    case Present(g) =>
        g.beforeDispatch(env)(using frame).map {
            case MessageGate.Decision.Allow =>
                // proceed to step 3 method lookup
            case MessageGate.Decision.Reject(err) =>
                // Request has an id: send error response
                val response = JsonRpcEnvelope.Response(id, Absent, Present(err), Absent)
                // Unsafe: offer to writerChannel inside decode callback
                Sync.Unsafe.defer {
                    discard(writerChannel.unsafe.offer(WriterMsg.SendEnvelope(response))(using
                        AllowUnsafe.embrace.danger, frame))
                }.andThen(Exchange.Message.Skip)
            case MessageGate.Decision.Drop =>
                Exchange.Message.Skip
        }
    case Absent =>
        // proceed to step 3 directly
```

`gate.beforeDispatch` returns `< Sync` so it composes cleanly inside the decode callback which is already `< Sync`.

### 3c. Step 3 unknown-method dispatch (replace the current hardcoded MethodNotFound)

**Request branch, method not in methodMap (lines 480-493 of current impl):**

Replace the current unconditional MethodNotFound reply with policy-driven dispatch:

```scala
case None =>
    config.unknownMethod.onUnknownRequest match
        case UnknownMethodPolicy.UnknownAction.ReplyMethodNotFound =>
            val response = JsonRpcEnvelope.Response(
                id, Absent, Present(JsonRpcError.methodNotFound(method)(using frame)), Absent)
            Sync.Unsafe.defer {
                discard(writerChannel.unsafe.offer(WriterMsg.SendEnvelope(response))(using
                    AllowUnsafe.embrace.danger, frame))
            }.andThen(Exchange.Message.Skip)
        case UnknownMethodPolicy.UnknownAction.Drop =>
            Exchange.Message.Skip
        case UnknownMethodPolicy.UnknownAction.Reject =>
            // Reject for a Request: send MethodNotFound (caller must not hang), then close.
            val response = JsonRpcEnvelope.Response(
                id, Absent, Present(JsonRpcError.methodNotFound(method)(using frame)), Absent)
            Sync.Unsafe.defer {
                discard(writerChannel.unsafe.offer(WriterMsg.SendEnvelope(response))(using
                    AllowUnsafe.embrace.danger, frame))
            }.andThen {
                Fiber.initUnscoped(impl.close).andThen(Exchange.Message.Skip)
            }
```

Note: `Reject` for a Request still sends a MethodNotFound reply before closing, so the caller does not hang. This matches Test 82 semantics: "Reject action closes the engine (verify via subsequent `call` returning `Abort[Closed]`)."

**Notification branch, method not in methodMap (currently just `Exchange.Message.Skip`):**

```scala
case None =>
    // dollarPrefixOverride: $/-prefix unknown notifications always drop silently (LSP §3.4).
    // This check applies ONLY when method is not in methodMap.
    val isDollarPrefix = method.startsWith("$/")
    if config.unknownMethod.dollarPrefixOverride && isDollarPrefix then
        Exchange.Message.Skip
    else
        config.unknownMethod.onUnknownNotification match
            case UnknownMethodPolicy.UnknownAction.Drop =>
                Exchange.Message.Skip
            case UnknownMethodPolicy.UnknownAction.ReplyMethodNotFound =>
                // Unusual (no id to reply to) but Drop semantically; log and skip.
                Exchange.Message.Skip
            case UnknownMethodPolicy.UnknownAction.Reject =>
                // Reject: log WARN (no wire response for notifications).
                Log.warn(s"kyo-jsonrpc: unknown notification method '$method' rejected").andThen(
                    Exchange.Message.Skip)
```

The `dollarPrefixOverride` check applies ONLY to the `None` (unknown method) path. Known methods are dispatched normally regardless of the `$/` prefix.

---

## 4. Tests (9 tests, file: UnknownMethodPolicyTest.scala)

File: `kyo-jsonrpc/shared/src/test/scala/kyo/UnknownMethodPolicyTest.scala`

Tests 79-87 per IMPLEMENTATION.md lines 439-447. The numbering below aligns with IMPLEMENTATION.md.

**Test 79 (IMPL line 439): lsp policy, unknown request -> MethodNotFound.**
Setup: two endpoints over InMemoryTransport, B registered with no methods, A configured with `Config(unknownMethod = UnknownMethodPolicy.lsp)`. A calls `"unknown/method"`. Assert `Abort[JsonRpcError]` with code `-32601`.

**Test 80 (IMPL line 440): lsp policy, unknown notification starting with `$/` -> drop.**
Setup: B has no methods, A uses `Config(unknownMethod = UnknownMethodPolicy.lsp)`. A sends `notify("$/setTrace", ...)`. Use a CountingTransport (Phase 4 Test 49 pattern) or verify via a Promise that B's handler is never invoked. Assert no error, no reply frame emitted from B.

**Test 81 (IMPL line 441): lsp policy, unknown notification NOT starting with `$/` -> drop.**
Same as Test 80 but method is `"unknown/event"`. Standard JSON-RPC drop. Assert no handler called.

**Test 82 (IMPL line 442): strict policy, unknown notification -> Reject closes engine.**
Setup: B configured with `Config(unknownMethod = UnknownMethodPolicy.strict)`. A sends a notification to B for an unregistered method. After B processes it, A attempts `A.call(B, "something")` on B's endpoint and asserts `Abort[Closed]`.

Implementation note: `Reject` for a Notification triggers `Log.warn` + engine close. The test verifies the close side-effect, not the log (log capture is not in scope unless a test-log hook exists).

**Test 83 (IMPL line 443): gate Allow -> normal dispatch.**
Setup: B registers a handler for `"ping"`. B is configured with a gate that always returns `Decision.Allow`. A calls `"ping"`. Assert the handler is invoked and the response returns normally.

**Test 84 (IMPL line 444): gate Reject(err) for Request -> A sees error reply.**
Setup: B configured with a gate returning `Decision.Reject(JsonRpcError(-32099, "gate blocked"))`. A calls any method. Assert `Abort[JsonRpcError]` with code `-32099`. The registered handler (if any) must NOT be invoked.

**Test 85 (IMPL line 445): gate Reject(err) for Notification -> notification dropped, no reply.**
Setup: B configured with gate returning `Decision.Reject(...)`. A sends a notification. Verify no wire reply is emitted (Notifications have no reply channel). Verify handler is not invoked. Engine does not close (Reject on notification only logs WARN).

**Test 86 (IMPL line 446): gate Drop for Request -> A's call hangs until timeout.**
Setup: gate returns `Decision.Drop`. A calls with a short `requestTimeout`. Assert `Abort[JsonRpcError]` (timeout error, not MethodNotFound). The request is silently dropped by the gate; no MethodNotFound is sent.

**Test 87 (IMPL line 447): gate Allow + unknown method -> behaves per unknownMethod policy (LSP pattern).**
A `MessageGate` implementation that allows the `"initialize"` method and rejects all others with `JsonRpcError(-32002, "ServerNotInitialized")`. B has only `"initialize"` registered; unknown methods fall through to `unknownMethod` policy. But gate fires first: A calls `"textDocument/hover"` on B and gets `-32002` from the gate, not `-32601` from the policy. A calls `"initialize"` and gets normal dispatch.

---

## 5. Anti-flakiness

All 9 tests are pure in-process dispatch. No real I/O timers except Test 86 which relies on `Config.requestTimeout`. Use a small timeout (e.g. 100.millis) in Test 86 and keep it deterministic.

For "drop" assertions (Tests 80, 81, 85, 86): use an `AtomicInt` or `Latch` inside the registered handler; verify the handler was never invoked. Alternatively wrap `InMemoryTransport` with a frame-counting delegate (the `CountingTransport` pattern from Phase 4 Test 49) and assert the send count does not increase for the expected silent-drop cases.

For Tests 82 and 86 that verify engine close or timeout, ensure the test does not leave a live engine open. The `Scope.run` wrapping each test in `kyo.Test` handles Scope cleanup automatically.

---

## 6. Concerns

**6.1. Config default: `.minimal` vs `.lsp`.**
IMPLEMENTATION.md line 428 says the Phase 7 task should update `Config.unknownMethod` default to `.lsp`. However, the existing `Config` already reads `UnknownMethodPolicy.minimal` (checked in `JsonRpcEndpoint.scala` line 63) and all 50 Phase 0-4 tests were built against that default. Changing it would require re-auditing every test that relies on unknown-method behavior. The safe resolution is to keep `.minimal` as the default. The three existing tests that test unknown-request behavior (within the Phase 4 suite) pass with `.minimal` (which does `ReplyMethodNotFound` for requests, same as `.lsp`). The only behavioral difference is `dollarPrefixOverride`, which no Phase 0-4 test exercises. **Keep the default as `.minimal`.** Phase 7 tests all set the policy explicitly.

**6.2. `strict` policy's `UnknownAction.Reject` semantics for Requests.**
DESIGN.md §9 defines `Reject` as "engine logs + closes; useful for strict servers". Applied to an unknown Request, closing without replying leaves the caller hanging until timeout. The correct behavior is: send MethodNotFound reply first (so the caller is unblocked immediately), then close. This is the semantics described in the prompt and confirmed by IMPLEMENTATION.md Test 82 ("verify via subsequent `call` returning `Abort[Closed]`", implying the first call still got an error back, not a hang). The impl agent must implement Request-Reject as reply-then-close.

**6.3. `dollarPrefixOverride` scope.**
The `$/` check applies only when the method is NOT in `methodMap`. If a consumer registers a handler for `"$/setTrace"`, it is dispatched normally. The override is purely a fallback for the unknown-method path.

**6.4. Gate position relative to progress intercept.**
The decode callback steps (from DESIGN.md §6.2) are: (1) cancel/progress intercept, (2) gate, (3) method dispatch. If a consumer registers `"$/progress"` as a known notification, step 1 routes it before the gate ever sees it. The gate cannot block progress notifications for a consumer that configured a ProgressPolicy. This is by design and does not need a workaround.

**6.5. Gate returning `Decision.Reject` for a Notification.**
DESIGN.md §6.2 step 2 says: "Decision.Reject(err): for Request -> send Response(id, err); for Notification -> drop; STOP." The prompt says log WARN as well. Both are correct: drop the notification (no wire response), log WARN so the consumer has an observable signal. The impl agent must log before returning `Exchange.Message.Skip`.

**6.6. No audit (COMPLETENESS.md) I-findings directly address gate or `$/` prefix.**
Scanning round2/COMPLETENESS.md: I5 ("Event whitelist vs UnknownMethodPolicy semantics") is a minor documentation note only. I16 ("inbound request dispatch has no cap") documents that `MessageGate` is the intended inbound-rate-limit mechanism. No findings block Phase 7 implementation. I16 is informational context: the gate can be used for inbound throttling but Phase 7 tests do not need to cover that scenario.

---

## 7. File summary

**Files to modify:**
- `kyo-jsonrpc/shared/src/main/scala/kyo/UnknownMethodPolicy.scala`: add `.lsp` and `.strict` vals.
- `kyo-jsonrpc/shared/src/main/scala/kyo/MessageGate.scala`: replace sealed-trait placeholder with real trait + Decision enum.
- `kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcEndpointImpl.scala`: wire step 2 (gate) and step 3 (unknown-method policy) in `decodeCallback`.

**Files to produce:**
- `kyo-jsonrpc/shared/src/test/scala/kyo/UnknownMethodPolicyTest.scala`: Tests 79-87.

**Files to delete:** none.

**Verification commands (per STEERING.md sbt naming):**
```
sbt 'kyo-jsonrpc/Test/compile' 2>&1 | tail -10
sbt 'kyo-jsonrpc/testOnly *UnknownMethodPolicyTest' 2>&1 | tail -20
```
