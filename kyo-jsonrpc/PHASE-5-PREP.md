# kyo-jsonrpc Phase 5 prep: CancellationPolicy

Target: `IMPLEMENTATION.md` lines 304-356. 14 tests. Three new files, one modified.

---

## 1. CancellationPolicy verbatim definition

The Phase-4 placeholder in `CancellationPolicy.scala` is a one-line `sealed trait CancellationPolicy private[kyo]`. Phase 5 **replaces the entire file** with the full `final case class` + companion. There is no "extend the trait" path; the sealed trait is the placeholder and is gone.

```scala
package kyo

import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.Structure
import kyo.Sync

final case class CancellationPolicy(
    cancelMethod:                   String,
    encodeParams:                   CancellationPolicy.ParamsEncoder,
    expectReplyForCancelledRequest: Boolean,
    cancelledError:                 Maybe[JsonRpcError],
    protectedMethods:               Set[String]
)

object CancellationPolicy:
    type ParamsEncoder = (JsonRpcId, Maybe[String]) => Structure.Value < Sync

    private case class LspCancelParams(id: JsonRpcId) derives Schema, CanEqual
    private case class McpCancelParams(requestId: JsonRpcId, reason: Maybe[String]) derives Schema, CanEqual

    private val lspEncoder: ParamsEncoder = (id, _) =>
        Sync.defer(Structure.encode(LspCancelParams(id)))

    private val mcpEncoder: ParamsEncoder = (id, reason) =>
        Sync.defer(Structure.encode(McpCancelParams(id, reason)))

    val lsp: CancellationPolicy = CancellationPolicy(
        cancelMethod                   = "$/cancelRequest",
        encodeParams                   = lspEncoder,
        expectReplyForCancelledRequest = true,
        cancelledError                 = Present(JsonRpcError.RequestCancelled),
        protectedMethods               = Set.empty
    )

    val mcp: CancellationPolicy = CancellationPolicy(
        cancelMethod                   = "notifications/cancelled",
        encodeParams                   = mcpEncoder,
        expectReplyForCancelledRequest = false,
        cancelledError                 = Absent,
        protectedMethods               = Set("initialize")
    )
end CancellationPolicy
```

Notes:
- `LspCancelParams` and `McpCancelParams` are `private` to the companion. They never appear on the public API surface.
- Both private case classes carry `derives Schema, CanEqual` per the STEERING wire-type rule. Even though they are private, `Structure.encode` requires a `Schema` instance.
- `lspEncoder` ignores `reason` (`_`); `mcpEncoder` passes it through as `reason: Maybe[String]` which maps to an optional field via kyo-schema's `Maybe` support.
- `ParamsEncoder` is a public type alias living in the companion. It is part of the public API surface listed in `IMPLEMENTATION.md` §"Public API additions".

---

## 2. ConcurrentHashMap.replace CAS pattern

Java's `ConcurrentHashMap` provides two overloads of `replace`. The one used here is the three-argument atomic compare-and-swap:

```java
// Java signature (java.util.concurrent.ConcurrentHashMap):
V replace(K key, V oldValue, V newValue)  // wrong overload, returns V

// Correct CAS overload:
boolean replace(K key, V oldValue, V newValue)
```

The boolean overload returns `true` only if the map currently holds `(key -> oldValue)` and atomically replaces it with `newValue`. Concurrent callers racing on the same key: exactly one wins, the rest see `false`.

Kyo idiom for invoking it inside a `Sync`-only context (no `AllowUnsafe` needed; this is a plain Java call):

```scala
// Transition Running -> Replying after handler completes:
val suppress = AtomicBoolean.Unsafe.init(false)  // Unsafe: state-init only, see Exchange precedent
val newEntry = InboundEntry.Replying(runningEntry.method, suppress)
val ok: Boolean = pendingInbound.replace(id, runningEntry, newEntry)
if ok then
    writerChannel.put(WriterMsg.SuppressIfCancelled(id, responseEnvelope))
// else: Cancelled won the race; drop the response, do not enqueue

// Transition Running -> Cancelled when inbound cancel arrives:
val cancelledEntry = InboundEntry.Cancelled(runningEntry.method)
val won: Boolean = pendingInbound.replace(id, runningEntry, cancelledEntry)
if won then
    runningEntry.cancelled.safe.completeDiscard(Result.unit)
// else: handler already transitioned to Replying; fall through to Replying case
```

The `AtomicBoolean.Unsafe.init` call is the only `Unsafe` call here. Every `ConcurrentHashMap` method itself is thread-safe without Unsafe. Each `Unsafe` site must carry `// Unsafe:` with justification.

---

## 3. The §6.5 CAS race-close sequence

The three-state machine: `Running | Replying | Cancelled`.

### Scenario A: handler completes BEFORE cancel arrives

1. Handler fiber finishes producing its response value.
2. Engine builds the encoded `JsonRpcEnvelope.Response`.
3. Engine attempts CAS: `pendingInbound.replace(id, runningEntry, Replying(method, AtomicBoolean(false)))`.
4. CAS succeeds (cancel has not arrived yet).
5. Engine calls `writerChannel.put(WriterMsg.SuppressIfCancelled(id, env))`.
6. Cancel notification arrives (notification from remote). Reader sees entry is `Replying(_, suppress)`.
7. Cancel sets `suppress.set(true)`.
8. Writer dequeues `SuppressIfCancelled(id, env)`.
9. Writer snapshots `suppress.get()` from the `Replying` entry. If `true` AND `policy.expectReplyForCancelledRequest = false` (MCP): drop `env`. If LSP: always send.
10. Either way: `Sync.ensure { send(env) }(_ => pendingInbound.remove(id))` removes the entry.

### Scenario B: cancel arrives BEFORE handler completes

1. Cancel notification arrives. Reader looks up `pendingInbound[id]`: entry is `Running(method, handlerFiber, cancelledPromise)`.
2. Reader attempts CAS: `pendingInbound.replace(id, runningEntry, Cancelled(method))`.
3. CAS succeeds.
4. Reader calls `cancelledPromise.safe.completeDiscard(Result.unit)`. The handler fiber observes `ctx.cancelled.get` resolving (if it is checking).
5. For MCP (`expectReplyForCancelledRequest = false`): reader also interrupts the handler fiber.
6. Handler eventually finishes. Engine attempts CAS: `pendingInbound.replace(id, runningEntry, Replying(...))`.
7. CAS fails; the entry is now `Cancelled`, not `Running`. The CAS expected `runningEntry` and found `Cancelled`.
8. Engine discards the response. No `writerChannel.put`. Done.

### Scenario C: concurrent tie

1. Both threads race to CAS from `Running` simultaneously.
2. `ConcurrentHashMap.replace(key, oldValue, newValue)` is atomic: exactly one thread wins, the other gets `false`.
3. If handler-completion thread wins: scenario A plays out. Cancel sees `Replying`, sets `suppress`.
4. If cancel thread wins: scenario B plays out. Handler-completion sees CAS fail, discards response.

There is no state where both win. The three-state machine closes every ordering.

---

## 4. Engine wiring touchpoints in JsonRpcEndpointImpl.scala

Phase 4 did not produce `JsonRpcEndpointImpl.scala` (the file does not exist yet in the worktree; Phase 4 is not yet committed). Phase 5 must produce it together with `CancellationEngine.scala`. The wiring points are:

### Reader fiber step 1 intercept

Inside the reader loop, before the MessageGate check and before method dispatch, add:

```scala
// Step 1a: cancellation policy intercept
config.cancellation match
    case Present(policy) if env.isNotification && env.method == policy.cancelMethod =>
        CancellationEngine.handleInboundCancel(env, policy, pendingInbound)
        // STOP: return Exchange.Message.Skip
    case _ =>
        // continue to step 2
```

`CancellationEngine.handleInboundCancel` is `Sync`-only (Exchange reader discipline). It:
1. Decodes params to extract the `JsonRpcId` of the request being cancelled.
2. Looks up `pendingInbound.get(id)`.
3. If absent: log warning (audit C5 principle applied to inbound: log + drop, do not error). Return.
4. If `Running(method, handlerFiber, cancelledPromise)`: attempt CAS to `Cancelled`. If won: complete `cancelledPromise`. If MCP (no-reply): interrupt `handlerFiber` via `Fiber.initUnscoped`-compatible interrupt. If lost: fall through to Replying case.
5. If `Replying(_, suppress)`: call `suppress.set(true)`.

### Reader fiber unknown-method drop for cancel notifications

If `config.cancellation = Absent`, cancel notifications are treated as ordinary notifications. No registered handler exists for `"$/cancelRequest"` or `"notifications/cancelled"` at the engine level. They fall through to step 3 (method dispatch), where the `UnknownMethodPolicy` governs (drop for unknown notifications). No special handling needed. The intercept only fires when policy is `Present`.

### Writer fiber: SuppressIfCancelled

The writer fiber loop matches on `WriterMsg`:

```scala
case WriterMsg.SuppressIfCancelled(id, env) =>
    val shouldDrop = pendingInbound.get(id) match
        case r: InboundEntry.Replying => r.suppress.get()
        case _                        => false
    Sync.ensure {
        if shouldDrop then Sync.defer(())
        else transport.send(env)
    }(_ => Sync.defer(pendingInbound.remove(id)).unit)
```

The `shouldDrop` check happens at dequeue time. By then the entry is either still `Replying` (suppress may or may not be true) or `Cancelled` (suppress check returns false, but `Cancelled` means the CAS race went to scenario B and the response was never enqueued; this path is unreachable in correct execution). The `Sync.ensure` guarantees removal regardless of whether send succeeds or fails.

### Post-handler completion: CAS Running -> Replying

Inside the handler fiber's `onComplete` callback (forked off the reader, running on its own fiber):

```scala
val suppress = AtomicBoolean.Unsafe.init(false)  // Unsafe: state-init
val ok = pendingInbound.replace(id, runningEntry, InboundEntry.Replying(method, suppress))
if ok then
    writerChannel.put(WriterMsg.SuppressIfCancelled(id, responseEnvelope))
// else: Cancelled won the CAS; response is discarded silently
```

For LSP (`expectReplyForCancelledRequest = true`): writer never actually suppresses (the LSP `handleInboundCancel` does NOT interrupt the handler fiber, only completes `cancelledPromise`). The `suppress.get()` check in the writer will be `false` because cancel, on seeing `Replying`, sets `suppress.set(true)`, but LSP's writer ignores `suppress` entirely (it sends regardless).

To keep the writer simple, the writer's suppress check is gated on policy:

```scala
val shouldDrop = config.cancellation match
    case Present(p) if !p.expectReplyForCancelledRequest =>
        pendingInbound.get(id) match
            case r: InboundEntry.Replying => r.suppress.get()
            case _                        => false
    case _ => false
```

### endpoint.cancel: steps 1-5

Per DESIGN §7 outbound flow:

1. Look up `callerRegistry.get(id)`. If absent: log warning ("cancel for unknown/already-completed id; no-op") and return `()`. Do not throw.
2. Check `policy.protectedMethods.contains(callerInfo.method)`. If true: log warning ("cancel refused for protected method <method>") and return `()`. No wire notification.
3. If `config.cancellation = Present(policy)`: encode the cancel notification params via `policy.encodeParams(id, reason)`, build `JsonRpcEnvelope.Notification(policy.cancelMethod, Present(params), callerInfo.extras)`, put on writer channel.
4. Complete `callerInfo.abortSignal` with `Result.fail(policy.cancelledError.getOrElse(JsonRpcError.cancelled(reason)))`.
5. The caller's `Async.race(abortSignal.safe.get, ...)` observes the failure; its `Sync.ensure` removes the `callerRegistry` entry.

If `config.cancellation = Absent` (step 3 skipped): only the local abort fires. No wire traffic.

---

## 5. Timeout auto-fire wiring

`Config.requestTimeout: Duration` wraps each outbound `call`. Implementation:

```scala
// Inside endpoint.call, around the Async.race:
val callResult =
    if config.requestTimeout == Duration.Infinity then
        Async.race(abortSignal.safe.get, exchange(req).map(decode[Out]))
    else
        Async.timeout(config.requestTimeout)(
            Async.race(abortSignal.safe.get, exchange(req).map(decode[Out]))
        ).flatMap {
            case Result.Fail(_: Timeout) =>
                // Timeout fired. Trigger cancellation flow.
                handleTimeout(id, reason = Absent, config, callerRegistry, writerChannel)
                    .flatMap(_ => abortSignal.safe.get)
            case other => other
        }
```

`handleTimeout` is in `CancellationEngine.scala`:

```
handleTimeout(id, reason, config, callerRegistry, writerChannel):
    config.cancellation match
        case Present(policy):
            // Same as endpoint.cancel(id, reason) except we already hold id
            // and skip the callerRegistry absent-check (we just allocated it)
            1. Encode cancel notification: policy.encodeParams(id, reason)
            2. Build Notification envelope with callerInfo.extras
            3. writerChannel.put(notificationEnvelope)
            4. Complete callerInfo.abortSignal with
               Result.fail(policy.cancelledError.getOrElse(JsonRpcError.cancelled(reason)))
        case Absent:
            // CDP shape: no wire notification
            Complete callerInfo.abortSignal with Result.fail(JsonRpcError.cancelled(reason))
```

Key difference: when `cancellation = Absent`, no cancel notification is sent. The caller still gets `Abort[JsonRpcError]` with a cancelled error; only the wire stays silent.

---

## 6. Test data and anti-flakiness

Tests live in `kyo-jsonrpc/shared/src/test/scala/kyo/CancellationPolicyTest.scala`. All extend `kyo.Test`. All are cross-platform.

### Tests 51-56: basic flows

Use `InMemoryTransport` with two endpoints sharing a channel pair. Use `Fiber.Promise` handoffs (never `Thread.sleep`) for deterministic timing.

Pattern for "handler awaits cancellation":

```scala
val gate = Promise.init[Unit, Sync]
val handler: String => String < (Async & Abort[JsonRpcError]) = _ =>
    gate.safe.get.map(_ => "done")
// Register handler on endpoint B
// Issue call from A; save the returned Fiber
// Issue cancel from A
// Verify behavior
```

**Test 51** (LSP inbound cancel): handler blocks on `gate`; A calls B; A sends `$/cancelRequest` notification directly via transport; handler observes `ctx.cancelled.get` resolving; A's `call` eventually gets `Abort[JsonRpcError]` with code `-32800`. Use `gate.completeDiscard(Result.unit)` to let the handler finish after observing cancelled. Verify error code is `JsonRpcError.RequestCancelled.code`.

**Test 52** (LSP reply still sent): same setup; count frames at `InMemoryTransport`; verify that a response frame for the cancelled request id IS present (LSP requires a reply).

**Test 53** (MCP no-reply): use `CancellationPolicy.mcp` config; handler blocks on gate; A calls B; A sends `notifications/cancelled`; release gate; count frames on B's outbound transport; verify zero response frames for that request id.

**Test 54** (CAS race: cancel arrives while reply is queued):

The goal is to test the `Running -> Replying` CAS path where cancel arrives after the handler finishes but before the writer sends. This requires careful sequencing. Use a `InMemoryTransport` wrapper that pauses before actually sending the first response frame:

```scala
// Wrap transport to expose a "hold first response" gate
val sendGate = Promise.init[Unit, Sync]
// Intercept the first transport.send call; do not proceed until sendGate resolves
```

Sequence:
1. A calls B with MCP policy; handler returns immediately.
2. Transport holds the response in the send gate.
3. Test fires cancel. At this point `pendingInbound[id]` is `Replying`.
4. Cancel sets `suppress = true`.
5. Release `sendGate`. Writer checks `suppress`; it is `true`; drops the frame.
6. Verify no response frame appears. A's call fails with cancelled error.

This is the most complex test. If the transport wrapper approach is too intrusive, an alternative: inject a paused `Channel` acting as the writer channel and observe state directly after the handler fiber finishes but before draining. Either approach is acceptable; the paused-transport approach is more black-box.

**Test 55** (LSP outbound cancel): A issues `endpoint.cancel(id, Absent)` after calling B. Verify: (a) `$/cancelRequest` notification appears on transport; (b) A's `call` fiber fails with code `-32800`.

**Test 56** (MCP outbound cancel): same but with MCP policy; verify `notifications/cancelled` with `requestId` and `reason` fields; A's `call` fails.

### Test 57: protectedMethods

Use MCP config. Register a `"initialize"` handler on B that hangs. A calls `initialize`. A calls `endpoint.cancel(id)`. Verify: (a) no `notifications/cancelled` frame appears; (b) A's call is still pending (not failed); (c) a warning is logged (if the test has a log capture hook) or simply verified by frame count staying at 1 (only the initial request).

### Tests 58-59: absent-id cancels

**Test 58** (outbound cancel for already-completed call): let a call complete normally; record the id; then call `endpoint.cancel(id, Absent)`. Verify: returns `()` without throwing; no additional frame on transport.

**Test 59** (inbound cancel for absent handler id): manually craft and inject a `notifications/cancelled` notification for a non-existent request id into the transport. Verify: no error thrown, no response sent, endpoint continues functioning.

### Tests 60-61: timeout auto-fire

**Test 60** (timeout with LSP policy): `Config.requestTimeout = 100.millis`. Handler blocks on a promise that never resolves. After timeout fires: verify `$/cancelRequest` appears on transport AND A's call fails with `-32800`.

**Test 61** (timeout with `cancellation = Absent`): same setup but `cancellation = Absent`. After timeout: verify zero cancel notification frames; A's call fails with `Abort[JsonRpcError]` (cancelled error).

Use a `Promise` that the test never completes, ensuring the only path to call failure is the timeout. `100.millis` is enough for deterministic test execution; the handler will not complete naturally.

### Test 62: handler aborts with specific error on cancel

LSP policy. Handler blocks; cancel arrives; handler observes cancelled and does `Abort.fail(JsonRpcError.ContentModified)`. Verify wire response carries code `-32801` (ContentModified), NOT `-32800` (RequestCancelled). Engine must never substitute its own code when the handler chose its own error.

### Test 63: extras propagation for cancel notification

A calls B with `ExtrasEncoder.const(Structure.encode(Record(Map("session" -> Str("s1")))))`. Record the assigned id via `idSignal`. Issue `endpoint.cancel(id, Absent)`. Capture the cancel notification envelope from the transport. Assert `env.extras == Present(...)` matching the original call's extras.

### Test 64: cancel-during-encode race

Use a custom `JsonRpcCodec` wrapper that pauses mid-encode (specifically: override the request-encoding step so it calls a `Latch.await()` before completing). Sequence:
1. A issues `endpoint.call(...)`. Encoding pauses at the latch.
2. At this point the `idSignal` has fired (id is known), `callerRegistry[id]` is populated (inserted inside encode callback before the latch).
3. A calls `endpoint.cancel(id, Absent)`. This completes `abortSignal` immediately.
4. Release the latch. Encoding completes; the encoded envelope is enqueued on the writer.
5. Verify: the encoded envelope IS still sent (cancel does not suppress outbound requests, only inbound responses). A's `call` has already failed via the `Async.race` abort path; any late reply will be dropped by Exchange.

This verifies the `idSignal` pattern: the cancel can find `callerRegistry[id]` even while the encode callback is still running, because insertion happens at the start of the callback.

---

## 7. Concerns

### 7.1 CancellationPolicy.scala: full replacement, not extension

The Phase-4 placeholder is `sealed trait CancellationPolicy private[kyo]`. Phase 5 must **replace the entire file** with the `final case class` definition. The impl agent must not add the case class as a companion to the trait or extend the trait from the case class. The sealed trait is discarded.

`Config.cancellation` in Phase 4 was typed as `Maybe[Any] = Absent` (placeholder for the not-yet-typed field). Phase 5 changes it to `Maybe[CancellationPolicy] = Present(CancellationPolicy.lsp)`. This is a source-breaking change to `JsonRpcEndpoint.Config`, but since Phase 4 has not shipped to users (it is not yet committed), there are no callers to update beyond `JsonRpcEndpointTest.scala` (the Phase-4 test file). The Phase-5 impl agent must update all test call sites that construct `Config` to use the typed field.

### 7.2 Config.cancellation default changes

DESIGN §6 shows the intended default as `Present(CancellationPolicy.lsp)`. Phase-4's IMPLEMENTATION.md says "cancellation defaults to Absent in Phase 4". Phase 5's contract (IMPLEMENTATION.md lines 321-322) says "update `Config` defaults to have `cancellation = Present(CancellationPolicy.lsp)` as the default". The Phase-5 impl agent must update the default in `JsonRpcEndpoint.Config`. This means Phase-4 tests that construct `Config()` and assume `cancellation = Absent` (e.g. Test 45) will need their `Config` construction adjusted to pass `cancellation = Absent` explicitly.

### 7.3 CancellationEngine.scala as a new internal file

IMPLEMENTATION.md specifies `kyo-jsonrpc/shared/src/main/scala/kyo/internal/CancellationEngine.scala`. This is a genuinely new file (not a modification of an existing file). It holds three top-level functions: `handleInboundCancel`, `handleOutboundCancel`, `handleTimeout`. All are `private[kyo]` and `Sync`-only (the reader's discipline; see §6.6).

### 7.4 Binary compatibility

Not a concern. All phases of kyo-jsonrpc are pre-release. No published artifact exists to be binary-compatible with. The field-type change from `Maybe[Any]` to `Maybe[CancellationPolicy]` is a source-level fix, not a regression.

### 7.5 Audit C5 (log-don't-silently-succeed)

The audit's C5 finding is about `endpoint.cancel(id)` for a never-issued or already-completed id. IMPLEMENTATION.md Test 58 explicitly tests the log+return-unit behavior. The impl agent must use a log call (not a no-op) and must NOT throw. The warning message should include the id value.

### 7.6 Audit C1 (extras propagation for engine-emitted cancel)

Test 63 covers this. The cancel notification built by `endpoint.cancel` (and by `handleTimeout`) must take `extras` from `callerRegistry[id].extras`, not from a default `Absent`. This is load-bearing for MCP-over-Streamable-HTTP routing; the transport uses `extras` to identify the SSE channel.

### 7.7 Convention sweep commands (copy to supervision checklist)

After each file written:

```
grep -nP '\xe2\x80\x94' <file>          # must be 0 (no em-dashes)
grep -n 'AllowUnsafe' <file>             # each occurrence must have // Unsafe: comment
grep -n ': Option\[' <file>             # must be 0; use Maybe
grep -nE ';$' <file>                    # must be 0; no semicolons
grep -n 'asInstanceOf' <file>           # must be 0
grep -n 'private\[kyo\].*=.*=' <file>  # no default params on private[kyo] methods
grep -n 'var ' <file>                   # must be 0 in shared state; AtomicRef instead
```
