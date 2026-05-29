# 05 Plan: kyo-jsonrpc engine-layer coverage (v2, 10 items)

Task type: refactor (with new-feature seams)
Cites design: ./02-design.md (v2)
Cites invariants: ./04-invariants.md (v2; INV-001 through INV-007)
Cites steering: ./steering.md (Design v1 -> v2 directive)

Module: kyo-jsonrpc (plus new submodule kyo-jsonrpc-http in Phase 05)
crossPlatforms: [jvm, js, native] (Phase 04 is jvm-only; all others share)

This plan supersedes the v1 17-item plan. Seven protocol-named items
(1 Config.cdp preset, 2 partialResultToken stamping, 11 per-sessionId
dispatch, 14 null-id Maybe[Id], 15 MetaPolicy, 16 JsonSchema2020_12,
17 emitProgress) drop to future consumer modules (`kyo-mcp`, `kyo-lsp`,
`kyo-cdp`). The remaining 10 items are genuinely engine-level and ship
across 5 phases. One additional engine fix accompanies Phase 01: the
`Config()` no-arg constructor's `cancellation` default flips from
`Present(CancellationPolicy.lsp)` to `Absent` so a consumer module opts
in to its protocol's cancellation semantics rather than inheriting LSP
silently.

## Phase 01: Engine refactor and Config neutrality (Items 8, 9, 10 plus Config flip)

Depends on: none. First phase. Touches `JsonRpcEnvelope.Malformed`
(tolerant fallback id extraction, Item 8), `JsonRpcEndpoint.close(gracePeriod)`
(two-phase teardown, Item 9), `CancellationPolicy.decodeParams` plus
`CancellationEngine` delegation (per-policy decoder, Item 10), and the
`Config().cancellation = Absent` default flip.

### Files to produce

(none ; this phase modifies existing files only)

### Files to modify

- `/Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEnvelope.scala`: extend `Malformed` with `id: Maybe[JsonRpcId]`.

```scala
// /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEnvelope.scala ; BEFORE
    case Malformed(reason: String, raw: Structure.Value)
end JsonRpcEnvelope
// /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEnvelope.scala ; AFTER
    case Malformed(id: Maybe[JsonRpcId], reason: String, raw: Structure.Value)
end JsonRpcEnvelope
```

- `/Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcCodecImpl.scala`: have Strict2_0 and Cdp decoders attempt `decodeId` on the raw `id` field before constructing `Malformed`.

```scala
// /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcCodecImpl.scala ; BEFORE
                        if hasMethod && hasId && idMaybe.isDefined then
                            val params = getValue("params")
                            JsonRpcEnvelope.Request(idMaybe.get, methodOpt.get, params, Absent)
                        else if hasMethod && (!hasId || idMaybe.isEmpty) then
                            val params = getValue("params")
                            JsonRpcEnvelope.Notification(methodOpt.get, params, Absent)
                        else if !hasMethod && hasId && idMaybe.isDefined && (hasResult || hasError) then
                            if hasResult && hasError then
                                JsonRpcEnvelope.Malformed("response has both result and error", raw)
                            else
                                val decodedError = errorOpt.map: ev =>
                                    Structure.decode[JsonRpcError](ev).getOrElse(JsonRpcError.InvalidRequest)
                                JsonRpcEnvelope.Response(idMaybe.get, resultOpt, decodedError, Absent)
                        else
                            JsonRpcEnvelope.Malformed("unclassifiable envelope", raw)
                        end if

                    case other =>
                        JsonRpcEnvelope.Malformed("expected a Record", other)
// /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcCodecImpl.scala ; AFTER
                        if hasMethod && hasId && idMaybe.isDefined then
                            val params = getValue("params")
                            JsonRpcEnvelope.Request(idMaybe.get, methodOpt.get, params, Absent)
                        else if hasMethod && (!hasId || idMaybe.isEmpty) then
                            val params = getValue("params")
                            JsonRpcEnvelope.Notification(methodOpt.get, params, Absent)
                        else if !hasMethod && hasId && idMaybe.isDefined && (hasResult || hasError) then
                            if hasResult && hasError then
                                JsonRpcEnvelope.Malformed(idMaybe, "response has both result and error", raw)
                            else
                                val decodedError = errorOpt.map: ev =>
                                    Structure.decode[JsonRpcError](ev).getOrElse(JsonRpcError.InvalidRequest)
                                JsonRpcEnvelope.Response(idMaybe.get, resultOpt, decodedError, Absent)
                        else
                            JsonRpcEnvelope.Malformed(idMaybe, "unclassifiable envelope", raw)
                        end if

                    case other =>
                        JsonRpcEnvelope.Malformed(Absent, "expected a Record", other)
```

(The Cdp decoder block at lines 208-220 receives the identical edit: every `JsonRpcEnvelope.Malformed(reason, raw)` site gains the `idMaybe` prefix arg; the `Malformed(...)` site for non-Record receives `Absent`.)

- `/Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcEndpointImpl.scala`: route `Malformed(Present(id), reason, _)` to fail the pending caller promise; keep `Malformed(Absent, _, _)` as Skip.

```scala
// /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcEndpointImpl.scala ; BEFORE
                // ~ line 1181 decodeCallback Malformed branch
                case JsonRpcEnvelope.Malformed(reason, _) =>
                    Exchange.Message.Skip
// /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcEndpointImpl.scala ; AFTER
                case JsonRpcEnvelope.Malformed(Present(id), reason, _) =>
                    Maybe(callerRegistry.get(id)) match
                        case Present(info) =>
                            // flow-allow: CAS-won path completes pending caller promise from outside originating fiber
                            info.responsePromise.unsafe.completeDiscard(
                                Result.fail(JsonRpcError.invalidRequest(s"malformed response: $reason"))
                            )(using AllowUnsafe.embrace.danger)
                            Exchange.Message.Skip
                        case Absent =>
                            Exchange.Message.Skip
                case JsonRpcEnvelope.Malformed(Absent, _, _) =>
                    Exchange.Message.Skip
```

- `/Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEndpoint.scala`: flip the `Config()` no-arg `cancellation` default to `Absent`, and add `close(gracePeriod)` plus `closeNow` (no-arg `close` becomes `close(Duration.Zero)`).

```scala
// /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEndpoint.scala ; BEFORE
    final case class Config(
        codec: JsonRpcCodec = JsonRpcCodec.Strict2_0,
        cancellation: Maybe[CancellationPolicy] = Present(CancellationPolicy.lsp),
        progress: Maybe[ProgressPolicy] = Absent,
        unknownMethod: UnknownMethodPolicy = UnknownMethodPolicy.minimal,
        gate: Maybe[MessageGate] = Absent,
        maxInFlight: Maybe[Int] = Absent,
        requestTimeout: Duration = Duration.Infinity,
        idStrategy: IdStrategy = IdStrategy.SequentialLong,
        progressResetsTimeout: Boolean = false
    )
// /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEndpoint.scala ; AFTER
    final case class Config(
        codec: JsonRpcCodec = JsonRpcCodec.Strict2_0,
        cancellation: Maybe[CancellationPolicy] = Absent,
        progress: Maybe[ProgressPolicy] = Absent,
        unknownMethod: UnknownMethodPolicy = UnknownMethodPolicy.minimal,
        gate: Maybe[MessageGate] = Absent,
        maxInFlight: Maybe[Int] = Absent,
        requestTimeout: Duration = Duration.Infinity,
        idStrategy: IdStrategy = IdStrategy.SequentialLong,
        progressResetsTimeout: Boolean = false
    )
```

```scala
// /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEndpoint.scala ; BEFORE
    def awaitDrain(using Frame): Unit < Async = impl.awaitDrain

    def close(using Frame): Unit < Async = impl.close

end JsonRpcEndpoint
// /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEndpoint.scala ; AFTER
    def awaitDrain(using Frame): Unit < Async = impl.awaitDrain

    def close(using Frame): Unit < Async = impl.close(Duration.Zero)

    def close(gracePeriod: Duration)(using Frame): Unit < Async = impl.close(gracePeriod)

    def closeNow(using Frame): Unit < Async = impl.close(Duration.Zero)

end JsonRpcEndpoint
```

- `/Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcEndpointImpl.scala`: replace the no-arg `close` body with a one-arg form that runs `awaitDrain` under `Async.timeout(gracePeriod)` then runs the existing `finalizer`.

```scala
// /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcEndpointImpl.scala ; BEFORE
    // ~ line 638 close
    def close(using Frame): Unit < Async =
        finalizer
// /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcEndpointImpl.scala ; AFTER
    def close(gracePeriod: Duration)(using Frame): Unit < Async =
        if gracePeriod == Duration.Zero then finalizer
        else
            Abort.run[Timeout](Async.timeout(gracePeriod)(awaitDrain)).map { _ =>
                finalizer
            }
```

- `/Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/CancellationPolicy.scala`: add `decodeParams` field plus matching presets.

```scala
// /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/CancellationPolicy.scala ; BEFORE
final case class CancellationPolicy(
    cancelMethod: String,
    encodeParams: CancellationPolicy.ParamsEncoder,
    expectReplyForCancelledRequest: Boolean,
    cancelledError: Maybe[JsonRpcError],
    protectedMethods: Set[String]
) derives CanEqual
// /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/CancellationPolicy.scala ; AFTER
final case class CancellationPolicy(
    cancelMethod: String,
    encodeParams: CancellationPolicy.ParamsEncoder,
    decodeParams: Structure.Value => Maybe[JsonRpcId] < Sync,
    expectReplyForCancelledRequest: Boolean,
    cancelledError: Maybe[JsonRpcError],
    protectedMethods: Set[String]
) derives CanEqual
```

```scala
// /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/CancellationPolicy.scala ; BEFORE
    val lsp: CancellationPolicy = CancellationPolicy(
        cancelMethod = "$/cancelRequest",
        encodeParams = lspEncoder,
        expectReplyForCancelledRequest = true,
        cancelledError = Present(JsonRpcError.RequestCancelled),
        protectedMethods = Set.empty
    )

    val mcp: CancellationPolicy = CancellationPolicy(
        cancelMethod = "notifications/cancelled",
        encodeParams = mcpEncoder,
        expectReplyForCancelledRequest = false,
        cancelledError = Absent,
        protectedMethods = Set("initialize")
    )
// /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/CancellationPolicy.scala ; AFTER
    private val lspDecoder: Structure.Value => Maybe[JsonRpcId] < Sync =
        sv => Sync.defer {
            Structure.decode[LspCancelParams](sv) match
                case Result.Success(p) => Present(p.id)
                case _                 => Absent
        }

    private val mcpDecoder: Structure.Value => Maybe[JsonRpcId] < Sync =
        sv => Sync.defer {
            Structure.decode[McpCancelParams](sv) match
                case Result.Success(p) => Present(p.requestId)
                case _                 => Absent
        }

    val lsp: CancellationPolicy = CancellationPolicy(
        cancelMethod = "$/cancelRequest",
        encodeParams = lspEncoder,
        decodeParams = lspDecoder,
        expectReplyForCancelledRequest = true,
        cancelledError = Present(JsonRpcError.RequestCancelled),
        protectedMethods = Set.empty
    )

    val mcp: CancellationPolicy = CancellationPolicy(
        cancelMethod = "notifications/cancelled",
        encodeParams = mcpEncoder,
        decodeParams = mcpDecoder,
        expectReplyForCancelledRequest = false,
        cancelledError = Absent,
        protectedMethods = Set("initialize")
    )
```

- `/Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/internal/CancellationEngine.scala`: drop the method-name branch; delegate to `policy.decodeParams`; remove private `LspCancelParams` / `McpCancelParams` (the case classes move under `CancellationPolicy` companion alongside the new private `lspDecoder` / `mcpDecoder`).

```scala
// /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/internal/CancellationEngine.scala ; BEFORE
private[kyo] object CancellationEngine:

    private case class LspCancelParams(id: JsonRpcId) derives Schema, CanEqual
    private case class McpCancelParams(requestId: JsonRpcId, reason: Maybe[String]) derives Schema, CanEqual

    private def extractCancelId(
        policy: CancellationPolicy,
        params: Maybe[Structure.Value]
    )(using Frame): Maybe[JsonRpcId] =
        params.flatMap { sv =>
            if policy.cancelMethod == "$/cancelRequest" then
                Structure.decode[LspCancelParams](sv) match
                    case Result.Success(p) => Present(p.id)
                    case _                 => Absent
            else
                Structure.decode[McpCancelParams](sv) match
                    case Result.Success(p) => Present(p.requestId)
                    case _                 => Absent
        }
// /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/internal/CancellationEngine.scala ; AFTER
private[kyo] object CancellationEngine:

    private def extractCancelId(
        policy: CancellationPolicy,
        params: Maybe[Structure.Value]
    )(using Frame): Maybe[JsonRpcId] < Sync =
        params match
            case Absent      => Absent
            case Present(sv) => policy.decodeParams(sv)
```

(Callers of `extractCancelId` switch from `Maybe[JsonRpcId]` to `Maybe[JsonRpcId] < Sync`; the existing call site at `handleInboundCancel` lines 38-39 gains a `.map { ... }` continuation.)

### Files to delete

(none)

### Public API additions

- `def close(gracePeriod: Duration)(using Frame): Unit < Async` in `kyo/JsonRpcEndpoint.scala`.
- `def closeNow(using Frame): Unit < Async` in `kyo/JsonRpcEndpoint.scala`.
- `CancellationPolicy.decodeParams: Structure.Value => Maybe[JsonRpcId] < Sync` (case-class field).

### Public API modifications

- `JsonRpcEnvelope.Malformed(reason, raw)` becomes `JsonRpcEnvelope.Malformed(id, reason, raw)`.
- `JsonRpcEndpoint.Config.cancellation` default flips from `Present(CancellationPolicy.lsp)` to `Absent`.
- `JsonRpcEndpoint.close` body changes from `impl.close` to `impl.close(Duration.Zero)`; behavior identical for callers passing no arg until they observe drain semantics on the new overload.
- `CancellationPolicy.apply` arity: was 5 fields, now 6 fields.

### Tests

Total: 10.

1. `JsonRpcEndpointTest.scala`: default Config() has cancellation = Absent
   - Given: no setup; `JsonRpcEndpoint.Config()` evaluated.
   - When: read the `cancellation` field.
   - Then: equals `Absent`; `codec` equals `JsonRpcCodec.Strict2_0`; `progress` equals `Absent`; `unknownMethod` equals `UnknownMethodPolicy.minimal`.
   - Pins: INV-002 (Config() no-arg default is protocol-neutral).

2. `JsonRpcEndpointTest.scala`: Config() default + LSP-shaped timeout does not emit $/cancelRequest
   - Given: server endpoint with `JsonRpcEndpoint.Config()` (no cancellation policy configured), client endpoint with the same default, paired via `JsonRpcTransport.inMemory`; server-side `JsonRpcMethod` named `"slow"` whose handler sleeps 2 seconds.
   - When: client invokes `call[Unit, Unit]("slow", ())` under `Async.timeout(100.millis)`; the client cancels the call.
   - Then: server-side `incoming` records the original `Request` envelope and does NOT record a `Notification("$/cancelRequest", ...)` within a 500ms observation window after cancel.
   - Pins: INV-002 (no-policy-configured timeout suppresses cancel emission).

3. `JsonRpcEnvelopeTest.scala`: Malformed carries Maybe id slot
   - Given: construct `JsonRpcEnvelope.Malformed(Present(JsonRpcId.Num(7)), "stringy error", Structure.Value.Str("raw"))`.
   - When: pattern-match the case.
   - Then: extracted `id` equals `Present(JsonRpcId.Num(7))`.
   - Pins: INV-001 (Malformed carries id).

4. `JsonRpcCodecTest.scala`: Strict2_0 decoder recovers id from malformed response
   - Given: raw record `{"jsonrpc":"2.0","id":42,"error":"stringy"}` (error field is a string, not an object).
   - When: `JsonRpcCodec.Strict2_0.decode(raw)`.
   - Then: result is `JsonRpcEnvelope.Malformed(Present(JsonRpcId.Num(42)), _, _)`; the reason string is non-empty.
   - Pins: INV-001 producer.

5. `JsonRpcCodecTest.scala`: Cdp decoder recovers id from malformed response
   - Given: raw record `{"id": 99, "result": {"x":1}, "error": "boom"}`.
   - When: `JsonRpcCodec.Cdp.decode(raw)`.
   - Then: result is `JsonRpcEnvelope.Malformed(Present(JsonRpcId.Num(99)), _, _)`.
   - Pins: INV-001 producer.

6. `JsonRpcCodecTest.scala`: Malformed for non-Record carries Absent id (INV-001 smoke)
   - Given: raw `Structure.Value.Str("not a record")`.
   - When: `JsonRpcCodec.Strict2_0.decode(raw)`.
   - Then: `JsonRpcEnvelope.Malformed(Absent, "expected a Record", _)`.
   - Pins: INV-001 (Absent path).

7. `JsonRpcEndpointTest.scala`: malformed response with id fails caller fast (INV-001 consumer-side smoke)
   - Given: two endpoints over `inMemory`; server returns a hand-crafted `Response` whose decoded `error` payload forces the codec into the Malformed-with-id branch.
   - When: client calls `endpoint.call[Req, Resp]("m", Req())` with a 10-second timeout.
   - Then: the call fails within 1 second with `JsonRpcError.invalidRequest` containing `"malformed response"`; does NOT block to the timeout.
   - Pins: INV-001 consumer (also pinned by Phase 02 sendUnmatched test 16).

8. `JsonRpcEndpointTest.scala`: close(0) is equivalent to closeNow
   - Given: endpoint with one in-flight slow handler (sleeps 5 seconds).
   - When: `endpoint.close(Duration.Zero)`; immediately after, attempt `endpoint.call` again.
   - Then: in-flight handler fiber is interrupted; subsequent `call` aborts with `Closed`.
   - Pins: INV-005 (no-grace path).

9. `JsonRpcEndpointTest.scala`: close(gracePeriod) drains before forcing
   - Given: endpoint with one in-flight handler that completes after 200ms.
   - When: invoke `endpoint.close(1.second)`.
   - Then: handler completes successfully (its result delivered); `close` returns after the handler's completion, well before 1 second of wall-clock elapses.
   - Pins: INV-005 producer.

10. `CancellationPolicyTest.scala`: custom policy decoder routes through decodeParams
    - Given: a custom `CancellationPolicy` whose `cancelMethod = "x.cancel"` and `decodeParams` reads `params.target` (a `JsonRpcId.Str`).
    - When: feed a notification `{"method":"x.cancel","params":{"target":"abc"}}` through `CancellationEngine.extractCancelIdForTest(policy, env.params)`.
    - Then: result equals `Present(JsonRpcId.Str("abc"))`; the engine never consults `cancelMethod` for branching.
    - Pins: INV-003 producer (no method-name fork in `CancellationEngine`).

### Consumed invariants

(none ; Phase 01 is the entry phase)

### Produced invariants

- INV-001: `JsonRpcEnvelope.Malformed` carries `id: Maybe[JsonRpcId]` and codec attempts id re-extraction.
- INV-002: `Config()` no-arg default is protocol-neutral (cancellation Absent, codec Strict2_0, progress Absent, unknownMethod minimal).
- INV-003: `CancellationPolicy.decodeParams` is the single source of cancel-id decoding; no method-name fork in `CancellationEngine`.
- INV-005: `close(d)` is the only user-facing teardown; Scope finalizer force-closes.

### Convention sweep (per Decision #25)

- [em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set

- platforms: [jvm, js, native]

### Verification command

- `sbt 'kyo-jsonrpcJVM/testOnly *JsonRpcEnvelopeTest *JsonRpcCodecTest *JsonRpcEndpointTest *CancellationPolicyTest' 'kyo-jsonrpcJS/testOnly *JsonRpcEnvelopeTest *JsonRpcCodecTest *JsonRpcEndpointTest *CancellationPolicyTest' 'kyo-jsonrpcNative/testOnly *JsonRpcEnvelopeTest *JsonRpcCodecTest *JsonRpcEndpointTest *CancellationPolicyTest'`

## Phase 02: API additions (Items 3, 12, 13)

Depends on: Phase 01. Items 12 and 13 add user-facing reach-ins to
`JsonRpcMethod` and `JsonRpcEndpoint`; Item 3 extracts the collision-safe
`allocateProgressToken` helper on `internal.ProgressEngine`. The
`sendUnmatched` writer-channel write rides through Phase 01's
Malformed-with-id branch (INV-001 consumer).

### Files to produce

(none)

### Files to modify

- `/Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/internal/ProgressEngine.scala`: add `allocateProgressToken` helper used by callWithProgress and callPartialResults call sites.

```scala
// /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/internal/ProgressEngine.scala ; BEFORE
private[kyo] object ProgressEngine:

    /** Builds a per-invocation progress sink closure for a handler.
// /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/internal/ProgressEngine.scala ; AFTER
private[kyo] object ProgressEngine:

    /** Allocates a unique progress token by generating random alphanumeric strings
      * and registering them with `progressStreams.putIfAbsent` until success.
      * Fails fast with `JsonRpcError.internalError("progress token exhaustion")`
      * after `maxAttempts` collisions (default 32).
      */
    private[kyo] def allocateProgressToken(
        progressStreams: ConcurrentHashMap[Structure.Value, Channel[Structure.Value]],
        channel: Channel[Structure.Value],
        maxAttempts: Int
    )(using Frame): Structure.Value < (Sync & Abort[JsonRpcError]) =
        def loop(attemptsLeft: Int): Structure.Value < (Sync & Abort[JsonRpcError]) =
            if attemptsLeft <= 0 then
                Abort.fail(JsonRpcError.internalError("progress token exhaustion", Absent))
            else
                Random.live.nextStringAlphanumeric(32).map { raw =>
                    val token: Structure.Value = Structure.Value.Str(raw)
                    Sync.defer {
                        // flow-allow: putIfAbsent returns Java reference; null arm is interop with ConcurrentHashMap contract
                        val prior = progressStreams.putIfAbsent(token, channel)
                        if prior == null then token
                        else loop(attemptsLeft - 1)
                    }.flatten
                }
        loop(maxAttempts)
    end allocateProgressToken

    /** Builds a per-invocation progress sink closure for a handler.
```

- `/Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcEndpointImpl.scala`: route progress-token allocation through `ProgressEngine.allocateProgressToken` in both `callWithProgress` (~line 368) and `callPartialResults` (~line 451).

```scala
// /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcEndpointImpl.scala ; BEFORE
        // ~ line 368 callWithProgress token alloc
        Random.live.unsafe.nextStringAlphanumeric(32).map { raw =>
            val token: Structure.Value = Structure.Value.Str(raw)
            // ~ line 391 registration
            Sync.defer(discard(progressStreams.put(token, progChan))).andThen {
                // ... existing flow continues
// /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcEndpointImpl.scala ; AFTER
        ProgressEngine.allocateProgressToken(progressStreams, progChan, 32).map { token =>
            // Registration is now atomic-inside-helper; no separate put call here.
            // ... existing flow continues with `token` bound
```

```scala
// /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcEndpointImpl.scala ; BEFORE
        // ~ line 451 callPartialResults token alloc
        Random.live.unsafe.nextStringAlphanumeric(32).map { raw =>
            val token: Structure.Value = Structure.Value.Str(raw)
            policy.stampOutboundToken(paramsVal, token).map { stamped =>
                Sync.defer(discard(progressStreams.put(token, progChan))).andThen {
// /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcEndpointImpl.scala ; AFTER
        ProgressEngine.allocateProgressToken(progressStreams, progChan, 32).map { token =>
            policy.stampOutboundToken(paramsVal, token).map { stamped =>
                // Registration is atomic-inside-helper; the prior put call is gone.
```

- `/Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcMethod.scala`: add public `dispatch` object method.

```scala
// /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcMethod.scala ; BEFORE
    def notification[In: Schema, S](name: String)(handler: (In, HandlerCtx) => Unit < S)(
        using
        Frame,
        (Async & Abort[JsonRpcError]) <:< S
    ): JsonRpcMethod[S] =
        val capturedName                 = name
        val capturedSchemaIn: Schema[In] = summon[Schema[In]]
        val ev                           = summon[(Async & Abort[JsonRpcError]) <:< S]
        new NotificationMethod(capturedName, capturedSchemaIn, handler, ev)
    end notification

    final private class RequestMethod[In, Out, S](
// /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcMethod.scala ; AFTER
    def notification[In: Schema, S](name: String)(handler: (In, HandlerCtx) => Unit < S)(
        using
        Frame,
        (Async & Abort[JsonRpcError]) <:< S
    ): JsonRpcMethod[S] =
        val capturedName                 = name
        val capturedSchemaIn: Schema[In] = summon[Schema[In]]
        val ev                           = summon[(Async & Abort[JsonRpcError]) <:< S]
        new NotificationMethod(capturedName, capturedSchemaIn, handler, ev)
    end notification

    /** Dispatches `params` to the named method in `methods`. Returns Absent for unknown method.
      * Public reach-in for non-engine consumers (one-shot stdio loop, HTTP POST endpoints,
      * custom routers); keeps `JsonRpcMethod.handle` private[kyo]. For Notification kind the
      * inner result is `Structure.Value.Null` after the handler completes.
      */
    def dispatch[S](
        name: String,
        methods: Seq[JsonRpcMethod[S]],
        params: Structure.Value,
        ctx: HandlerCtx
    )(using Frame, (Async & Abort[JsonRpcError]) <:< S): Maybe[Structure.Value < (S & Abort[JsonRpcError])] =
        val methodMap: Map[String, JsonRpcMethod[S]] =
            methods.iterator.map(m => (m.name, m)).toMap
        val ev = summon[(Async & Abort[JsonRpcError]) <:< S]
        // flow-allow: Map.get returns scala.Option; match arms are interop, not kyo code
        methodMap.get(name) match
            // flow-allow: scala.Option arm; interop with Map.get
            case Some(method) =>
                val handled: Structure.Value < (Async & Abort[JsonRpcError]) = method.handle(params, ctx)
                Present(ev.liftContra[[X] =>> Structure.Value < (X & Abort[JsonRpcError])].apply(handled))
            // flow-allow: scala.Option arm; interop with Map.get
            case None => Absent
    end dispatch

    final private class RequestMethod[In, Out, S](
```

- `/Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEndpoint.scala`: add `sendUnmatched`.

```scala
// /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEndpoint.scala ; BEFORE
    def notify[In: Schema](
        method: String,
        params: In,
        extras: ExtrasEncoder = ExtrasEncoder.empty
    )(using Frame): Unit < (Async & Abort[Closed]) =
        impl.notify[In](method, params, extras)
// /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEndpoint.scala ; AFTER
    def notify[In: Schema](
        method: String,
        params: In,
        extras: ExtrasEncoder = ExtrasEncoder.empty
    )(using Frame): Unit < (Async & Abort[Closed]) =
        impl.notify[In](method, params, extras)

    def sendUnmatched[In: Schema](
        method: String,
        params: In,
        id: JsonRpcId,
        extras: ExtrasEncoder = ExtrasEncoder.empty
    )(using Frame): Unit < (Async & Abort[Closed]) =
        impl.sendUnmatched[In](method, params, id, extras)
```

- `/Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcEndpointImpl.scala`: implement `sendUnmatched` (encode as Request envelope, push to writerChannel; no callerRegistry / inFlight registration).

```scala
// /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcEndpointImpl.scala ; BEFORE
    def notify[In: Schema](method: String, params: In, extras: ExtrasEncoder)(using Frame): Unit < (Async & Abort[Closed]) =
        // ... existing body
// /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcEndpointImpl.scala ; AFTER
    def notify[In: Schema](method: String, params: In, extras: ExtrasEncoder)(using Frame): Unit < (Async & Abort[Closed]) =
        // ... existing body

    def sendUnmatched[In: Schema](
        method: String,
        params: In,
        id: JsonRpcId,
        extras: ExtrasEncoder
    )(using Frame): Unit < (Async & Abort[Closed]) =
        Sync.defer(Structure.encode[In](params)).map { encodedParams =>
            extras(id).map { extrasVal =>
                val env = JsonRpcEnvelope.Request(id, method, Present(encodedParams), extrasVal)
                writerChannel.put(WriterMsg.SendEnvelope(env))
            }
        }
    end sendUnmatched
```

### Files to delete

(none)

### Public API additions

- `def JsonRpcMethod.dispatch[S](name, methods, params, ctx): Maybe[Structure.Value < (S & Abort[JsonRpcError])]` in `kyo/JsonRpcMethod.scala`.
- `def JsonRpcEndpoint.sendUnmatched[In: Schema](method, params, id, extras): Unit < (Async & Abort[Closed])` in `kyo/JsonRpcEndpoint.scala`.
- `private[kyo] def ProgressEngine.allocateProgressToken(progressStreams, channel, maxAttempts): Structure.Value < (Sync & Abort[JsonRpcError])` (engine-internal, exposed at `private[kyo]` boundary for the call sites in `JsonRpcEndpointImpl`).

### Public API modifications

(none)

### Tests

Total: 8.

11. `JsonRpcMethodTest.scala`: dispatch known request returns Present
    - Given: a `JsonRpcMethod[Async & Abort[JsonRpcError]]("add")` summing two ints, a fresh `HandlerCtx.forTest(...)`, and `params = Structure.encode(AddReq(2, 3))`.
    - When: `JsonRpcMethod.dispatch("add", Seq(method), params, ctx)`.
    - Then: returns `Present(comp)` where `comp` evaluates to a `Structure.Value` decoding to `AddResp(5)`.
    - Pins: design Item 12.

12. `JsonRpcMethodTest.scala`: dispatch unknown name returns Absent
    - Given: `methods = Seq(addMethod)`.
    - When: `JsonRpcMethod.dispatch("missing", methods, paramsVal, ctx)`.
    - Then: returns `Absent`.
    - Pins: design Item 12 unknown branch.

13. `JsonRpcMethodTest.scala`: dispatch known notification returns Present(Null)
    - Given: `notification("log")` whose handler increments an `AtomicInt`.
    - When: dispatch invoked; outer computation evaluated.
    - Then: returns `Present(comp)`; evaluating `comp` yields `Structure.Value.Null` and counter increments to 1.
    - Pins: design Item 12 notification arm.

14. `JsonRpcMethodTest.scala`: dispatch propagates handler Abort
    - Given: a request method whose handler invokes `Abort.fail(JsonRpcError.invalidParams("bad"))`.
    - When: dispatch invoked and the inner `comp` evaluated under `Abort.run[JsonRpcError]`.
    - Then: result is `Result.Failure(JsonRpcError.invalidParams("bad"))`.
    - Pins: design Item 12 abort propagation.

15. `JsonRpcEndpointTest.scala`: sendUnmatched emits request envelope with id
    - Given: two endpoints over `inMemory`; server side records every inbound envelope into an `AtomicRef[Chunk[JsonRpcEnvelope]]`.
    - When: client invokes `endpoint.sendUnmatched("Page.handleJavaScriptDialog", DialogParams(true), JsonRpcId.Num(-1))`.
    - Then: server receives one `JsonRpcEnvelope.Request(JsonRpcId.Num(-1), "Page.handleJavaScriptDialog", Present(_), _)`.
    - Pins: design Item 13.

16. `JsonRpcEndpointTest.scala`: sendUnmatched registers no pending caller (INV-001 consumer)
    - Given: client endpoint with a server that never replies; the server is just a sink.
    - When: invoke `sendUnmatched` 5 times in sequence then call `awaitDrain` under `Async.timeout(200.millis)`.
    - Then: `awaitDrain` completes within 200ms (no pending caller registered, so drain is immediate). Any peer reply for these ids would route through INV-001's Malformed-with-id Skip branch instead of resolving a non-existent caller promise.
    - Pins: design Item 13; INV-001 consumer.

17. `JsonRpcEndpointTest.scala`: sendUnmatched does not block waiting for response
    - Given: server endpoint with no method named "noop"; client uses `sendUnmatched`.
    - When: invoke `sendUnmatched("noop", Unit, JsonRpcId.Num(1))` then immediately `awaitDrain`.
    - Then: `awaitDrain` returns within 100ms wall-clock.
    - Pins: design Item 13 fire-and-forget contract.

18. `ProgressPolicyTest.scala`: progress-token allocator regenerates on collision
    - Given: a `ConcurrentHashMap[Structure.Value, Channel[Structure.Value]]` pre-seeded with one entry under a fixed-string token, plus two fresh Channels.
    - When: call `ProgressEngine.allocateProgressToken(map, ch1, 32)` then `ProgressEngine.allocateProgressToken(map, ch2, 32)`.
    - Then: both returned tokens are non-equal to each other and non-equal to the pre-seeded token; both are registered in the map.
    - Pins: design Item 3 (MCP unique-tokens MUST).

### Consumed invariants

- INV-001: sendUnmatched relies on Phase 01's Malformed-with-id Skip branch when a peer reply arrives for an id that has no registered caller.

### Produced invariants

(none new for the cross-phase ledger ; Items 3 / 12 / 13 contracts are testable inline.)

### Convention sweep (per Decision #25)

- [em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set

- platforms: [jvm, js, native]

### Verification command

- `sbt 'kyo-jsonrpcJVM/testOnly *JsonRpcMethodTest *JsonRpcEndpointTest *ProgressPolicyTest' 'kyo-jsonrpcJS/testOnly *JsonRpcMethodTest *JsonRpcEndpointTest *ProgressPolicyTest' 'kyo-jsonrpcNative/testOnly *JsonRpcMethodTest *JsonRpcEndpointTest *ProgressPolicyTest'`

## Phase 03: Wire transport seam plus stdio (Items 7, 5)

Depends on: Phase 01 because the `WireTransportAdapter` calls
`codec.decode` whose `Malformed(Absent, ...)` route is the Phase 01
contract for invalid JSON; and Phase 02 because the codec / encoder
paths share the writer pipeline that Phase 02 wired for `sendUnmatched`.
Adds `WireTransport`, `Framer` (with `lineDelimited` and `contentLength`
presets), `JsonRpcTransport.fromWire`, and `JsonRpcTransport.stdio`.

### Files to produce

- `/Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/WireTransport.scala`: PUBLIC byte-stream transport seam under the envelope codec.
  Matching test: `/Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/test/scala/kyo/WireTransportTest.scala`

```scala
// /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/WireTransport.scala
// flow-allow: PUBLIC byte-level user-facing transport seam consumed by JsonRpcTransport.fromWire and the byte-stream adapter set
package kyo

import kyo.Stream

trait WireTransport:
    def send(bytes: Chunk[Byte])(using Frame): Unit < (Async & Abort[Closed])
    def incoming(using Frame): Stream[Chunk[Byte], Async & Abort[Closed]]
    def close(using Frame): Unit < Async
end WireTransport

object WireTransport:
    /** No-op wire transport for tests: send drops bytes, incoming is empty, close is no-op. */
    val empty: WireTransport = new WireTransport:
        def send(bytes: Chunk[Byte])(using Frame): Unit < (Async & Abort[Closed]) = Kyo.unit
        def incoming(using Frame): Stream[Chunk[Byte], Async & Abort[Closed]]    = Stream.empty
        def close(using Frame): Unit < Async                                      = Kyo.unit
end WireTransport
```

- `/Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/Framer.scala`: PUBLIC framer preset library (lineDelimited, contentLength).
  Matching test: `/Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/test/scala/kyo/FramerTest.scala`

```scala
// /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/Framer.scala
// flow-allow: PUBLIC framer preset library for byte-stream transports (line-delimited stdio, Content-Length envelopes)
package kyo

import kyo.Stream
import kyo.Sync

trait Framer:
    def frame(bytes: Chunk[Byte])(using Frame): Chunk[Byte] < Sync
    def parse(stream: Stream[Chunk[Byte], Async & Abort[Closed]])(using Frame): Stream[Chunk[Byte], Async & Abort[Closed]]
end Framer

object Framer:

    /** One frame per LF-terminated segment. Trailing CR before LF is stripped.
      * Empty lines are skipped. EOF closes the stream without flushing a partial line.
      */
    val lineDelimited: Framer = new Framer:
        def frame(bytes: Chunk[Byte])(using Frame): Chunk[Byte] < Sync =
            Sync.defer(bytes :+ '\n'.toByte)

        def parse(stream: Stream[Chunk[Byte], Async & Abort[Closed]])(using Frame): Stream[Chunk[Byte], Async & Abort[Closed]] =
            internal.FramerImpl.parseLineDelimited(stream)

    /** Content-Length envelope framing: `Content-Length: N\r\n\r\n<N bytes>`. Tolerant
      * of `\n\n` on parse, strict `\r\n\r\n` on emit. Header errors raise
      * `Abort.fail(JsonRpcError.parseError(reason))`.
      */
    val contentLength: Framer = new Framer:
        def frame(bytes: Chunk[Byte])(using Frame): Chunk[Byte] < Sync =
            Sync.defer {
                val header = s"Content-Length: ${bytes.length}\r\n\r\n".getBytes("UTF-8")
                Chunk.from(header) ++ bytes
            }

        def parse(stream: Stream[Chunk[Byte], Async & Abort[Closed]])(using Frame): Stream[Chunk[Byte], Async & Abort[Closed]] =
            internal.FramerImpl.parseContentLength(stream)
end Framer
```

- `/Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/internal/FramerImpl.scala`: INTERNAL parser helpers consumed by `Framer.lineDelimited.parse` and `Framer.contentLength.parse`.
  Matching test: `/Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/test/scala/kyo/FramerTest.scala` (covers internal parser through `Framer` public surface).

```scala
// /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/internal/FramerImpl.scala
package kyo.internal

import kyo.*

private[kyo] object FramerImpl:

    def parseLineDelimited(
        stream: Stream[Chunk[Byte], Async & Abort[Closed]]
    )(using Frame): Stream[Chunk[Byte], Async & Abort[Closed]] =
        Stream.statefulChunk[Chunk[Byte], Async & Abort[Closed], Chunk[Byte]](Chunk.empty)(stream) { (buffer, chunk) =>
            val combined           = buffer ++ chunk
            val (frames, leftover) = splitOnLf(combined)
            (leftover, frames)
        }

    def parseContentLength(
        stream: Stream[Chunk[Byte], Async & Abort[Closed]]
    )(using Frame): Stream[Chunk[Byte], Async & Abort[Closed]] =
        Stream.statefulChunk[Chunk[Byte], Async & Abort[Closed], Chunk[Byte]](Chunk.empty)(stream) { (buffer, chunk) =>
            val combined           = buffer ++ chunk
            val (frames, leftover) = splitContentLength(combined)
            (leftover, frames)
        }

    private def splitOnLf(buf: Chunk[Byte]): (Chunk[Chunk[Byte]], Chunk[Byte]) =
        val builder = scala.collection.mutable.ArrayBuffer.empty[Chunk[Byte]]
        var start   = 0
        var i       = 0
        val arr     = buf.toArray
        while i < arr.length do
            if arr(i) == '\n'.toByte then
                val end   = if i > 0 && arr(i - 1) == '\r'.toByte then i - 1 else i
                val frame = Chunk.from(arr.slice(start, end))
                if frame.nonEmpty then builder += frame
                start = i + 1
            i += 1
        val leftover = Chunk.from(arr.slice(start, arr.length))
        (Chunk.from(builder.toSeq), leftover)

    private def splitContentLength(buf: Chunk[Byte]): (Chunk[Chunk[Byte]], Chunk[Byte]) =
        val builder = scala.collection.mutable.ArrayBuffer.empty[Chunk[Byte]]
        var rest    = buf
        var done    = false
        while !done do
            parseOneContentLengthFrame(rest) match
                case Maybe.Absent              => done = true
                case Maybe.Present((frame, r)) =>
                    builder += frame
                    rest = r
        (Chunk.from(builder.toSeq), rest)
    end splitContentLength

    private def parseOneContentLengthFrame(buf: Chunk[Byte]): Maybe[(Chunk[Byte], Chunk[Byte])] =
        val arr    = buf.toArray
        val sep1   = indexOf(arr, "\r\n\r\n".getBytes("UTF-8"))
        val sep2   = indexOf(arr, "\n\n".getBytes("UTF-8"))
        val sepIdx = (sep1, sep2) match
            case (-1, -1) => -1
            case (a, -1)  => a
            case (-1, b)  => b
            case (a, b)   => Math.min(a, b)
        if sepIdx == -1 then Maybe.Absent
        else
            val sepLen  = if sepIdx == sep1 then 4 else 2
            val headers = new String(arr.slice(0, sepIdx), "UTF-8")
            val len     = parseContentLengthHeader(headers).getOrElse(-1)
            if len < 0 then Maybe.Absent
            else
                val bodyStart = sepIdx + sepLen
                val bodyEnd   = bodyStart + len
                if arr.length < bodyEnd then Maybe.Absent
                else
                    val frame = Chunk.from(arr.slice(bodyStart, bodyEnd))
                    val rest  = Chunk.from(arr.slice(bodyEnd, arr.length))
                    Maybe.Present((frame, rest))
    end parseOneContentLengthFrame

    private def parseContentLengthHeader(headers: String): Maybe[Int] =
        val lines = headers.split("\r?\n").iterator
        var found: Maybe[Int] = Maybe.Absent
        while lines.hasNext && found.isEmpty do
            val line  = lines.next()
            val colon = line.indexOf(':')
            if colon > 0 then
                val key   = line.substring(0, colon).trim
                val value = line.substring(colon + 1).trim
                if key.equalsIgnoreCase("Content-Length") then
                    scala.util.Try(value.toInt).toOption match
                        // flow-allow: scala.Option arm; interop with stdlib Try.toOption
                        case Some(n) if n >= 0 => found = Maybe.Present(n)
                        // flow-allow: scala.Option arm; interop with stdlib Try.toOption
                        case _                 => ()
        found
    end parseContentLengthHeader

    private def indexOf(haystack: Array[Byte], needle: Array[Byte]): Int =
        var i     = 0
        val limit = haystack.length - needle.length
        while i <= limit do
            var j  = 0
            var ok = true
            while j < needle.length && ok do
                if haystack(i + j) != needle(j) then ok = false
                j += 1
            if ok then return i
            i += 1
        -1
    end indexOf

end FramerImpl
```

- `/Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/internal/WireTransportAdapter.scala`: INTERNAL adapter lifting `WireTransport` + `Framer` + `JsonRpcCodec` to `JsonRpcTransport`.
  Matching test: `/Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcTransportTest.scala`

```scala
// /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/internal/WireTransportAdapter.scala
package kyo.internal

import kyo.*

private[kyo] final class WireTransportAdapter(
    wire: WireTransport,
    framer: Framer,
    codec: JsonRpcCodec
) extends JsonRpcTransport:

    def send(env: JsonRpcEnvelope)(using Frame): Unit < (Async & Abort[Closed]) =
        Abort.run[JsonRpcError](codec.encode(env)).map {
            case Result.Success(structure) =>
                Json.encode(structure).map { jsonStr =>
                    val bytes = Chunk.from(jsonStr.getBytes("UTF-8"))
                    framer.frame(bytes).map(framed => wire.send(framed))
                }
            case Result.Failure(err) =>
                Log.warn(s"kyo-jsonrpc: wire-transport encode failed: ${err.message}")
            case Result.Panic(t) =>
                Log.warn(s"kyo-jsonrpc: wire-transport encode panic: ${t.getMessage}")
        }

    def incoming(using Frame): Stream[JsonRpcEnvelope, Async & Abort[Closed]] =
        framer.parse(wire.incoming).mapEffectful { bytes =>
            val jsonStr = new String(bytes.toArray, "UTF-8")
            Json.decode[Structure.Value](jsonStr) match
                case Result.Success(structureValue) => codec.decode(structureValue)
                case Result.Failure(_)              =>
                    Sync.defer(JsonRpcEnvelope.Malformed(Absent, "json parse failed", Structure.Value.Str(jsonStr)))
                case Result.Panic(t)                =>
                    Sync.defer(JsonRpcEnvelope.Malformed(Absent, s"json parse panic: ${t.getMessage}", Structure.Value.Null))
        }

    def close(using Frame): Unit < Async = wire.close
end WireTransportAdapter
```

- `/Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/internal/StdioWireTransport.scala`: INTERNAL stdio byte-stream transport reading `Console.readLine` and writing `Console.printLine`.
  Matching test: `/Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcTransportTest.scala`

```scala
// /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/internal/StdioWireTransport.scala
package kyo.internal

import kyo.*

private[kyo] final class StdioWireTransport extends WireTransport:

    def send(bytes: Chunk[Byte])(using Frame): Unit < (Async & Abort[Closed]) =
        val line    = new String(bytes.toArray, "UTF-8")
        val trimmed = if line.endsWith("\n") then line.dropRight(1) else line
        Console.printLine(trimmed)

    def incoming(using Frame): Stream[Chunk[Byte], Async & Abort[Closed]] =
        Stream.unfold[Chunk[Byte], Async & Abort[Closed], Unit](()) { _ =>
            Console.readLine.map {
                case Maybe.Absent => Maybe.Absent
                case Maybe.Present(line) =>
                    val bytes = Chunk.from((line + "\n").getBytes("UTF-8"))
                    Maybe.Present((bytes, ()))
            }
        }

    def close(using Frame): Unit < Async = Kyo.unit
end StdioWireTransport
```

### Files to modify

- `/Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcTransport.scala`: add `stdio` and `fromWire` to the companion.

```scala
// /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcTransport.scala ; BEFORE
    def inMemory(using Frame): (JsonRpcTransport, JsonRpcTransport) < Sync = inMemory(64)
end JsonRpcTransport
// /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcTransport.scala ; AFTER
    def inMemory(using Frame): (JsonRpcTransport, JsonRpcTransport) < Sync = inMemory(64)

    /** Lifts a byte-stream transport plus framer plus envelope codec into the envelope-level
      * `JsonRpcTransport` seam. Inbound bytes pass through `framer.parse` and `codec.decode`;
      * outbound envelopes pass through `codec.encode` and `framer.frame`.
      */
    def fromWire(
        wire: WireTransport,
        framer: Framer,
        codec: JsonRpcCodec = JsonRpcCodec.Strict2_0
    )(using Frame): JsonRpcTransport < (Async & Scope) =
        Sync.defer(new internal.WireTransportAdapter(wire, framer, codec))

    /** Line-delimited stdio transport for CLI-style RPC servers. Reads `Console.readLine`
      * and writes `Console.printLine`. EOF on stdin closes `incoming`. One envelope per line.
      */
    def stdio(
        codec: JsonRpcCodec = JsonRpcCodec.Strict2_0,
        framer: Framer = Framer.lineDelimited
    )(using Frame): JsonRpcTransport < (Async & Scope) =
        Sync.defer(new internal.StdioWireTransport).map { wire =>
            fromWire(wire, framer, codec)
        }
end JsonRpcTransport
```

### Files to delete

(none)

### Public API additions

- `trait WireTransport` plus `WireTransport.empty` in `kyo/WireTransport.scala`.
- `trait Framer` plus `Framer.lineDelimited`, `Framer.contentLength` in `kyo/Framer.scala`.
- `def JsonRpcTransport.fromWire(wire, framer, codec): JsonRpcTransport < (Async & Scope)` on `JsonRpcTransport` companion.
- `def JsonRpcTransport.stdio(codec, framer): JsonRpcTransport < (Async & Scope)` on companion.

### Public API modifications

(none)

### Tests

Total: 12.

19. `WireTransportTest.scala`: empty wire transport produces no bytes
    - Given: `WireTransport.empty`.
    - When: consume `incoming` with `.run`.
    - Then: result is an empty Chunk.
    - Pins: design Item 7 empty preset.

20. `WireTransportTest.scala`: fromWire round-trips one envelope (INV-004 producer)
    - Given: a paired in-memory `WireTransport` over `Channel[Chunk[Byte]]`; `fromWire(wireA, Framer.lineDelimited, JsonRpcCodec.Strict2_0)` and the symmetric on the other side.
    - When: send a `JsonRpcEnvelope.Request(JsonRpcId.Num(1), "ping", Absent, Absent)` from side A.
    - Then: side B's `incoming` yields a matching `Request(JsonRpcId.Num(1), "ping", Absent, _)`.
    - Pins: INV-004 (byte-stream-through-fromWire path).

21. `FramerTest.scala`: lineDelimited.frame appends LF
    - Given: `Framer.lineDelimited.frame(Chunk.from("abc".getBytes("UTF-8")))`.
    - When: evaluated.
    - Then: result is `Chunk.from("abc\n".getBytes("UTF-8"))`.
    - Pins: design Item 7 frame contract.

22. `FramerTest.scala`: lineDelimited.parse splits multi-line buffer
    - Given: a single chunk `"a\nb\nc\n"` pushed to a `Stream.emit`.
    - When: pipe through `Framer.lineDelimited.parse` and `.run`.
    - Then: result equals `Chunk(Chunk("a"), Chunk("b"), Chunk("c"))` (as byte chunks).
    - Pins: design Item 7 line splitter.

23. `FramerTest.scala`: lineDelimited.parse strips CR before LF
    - Given: chunk `"a\r\nb\r\n"`.
    - When: parse and run.
    - Then: yields `Chunk("a"), Chunk("b")`; no `\r` bytes remain in any output frame.
    - Pins: design Item 7 CR-stripping rule.

24. `FramerTest.scala`: lineDelimited.parse skips empty lines
    - Given: chunk `"a\n\n\nb\n"`.
    - When: parse and run.
    - Then: yields exactly `Chunk("a"), Chunk("b")`.
    - Pins: design Item 7 empty-line skip rule.

25. `FramerTest.scala`: contentLength.frame prepends header (strict emit)
    - Given: payload `"{}"` as bytes.
    - When: `Framer.contentLength.frame(payload)`.
    - Then: equals `Chunk.from("Content-Length: 2\r\n\r\n{}".getBytes("UTF-8"))`.
    - Pins: design Item 7 contentLength emit (strict CRLF).

26. `FramerTest.scala`: contentLength.parse extracts one frame
    - Given: chunk `"Content-Length: 5\r\n\r\nhello"`.
    - When: parse and run.
    - Then: yields `Chunk(Chunk("hello"))`.
    - Pins: design Item 7 contentLength parse.

27. `FramerTest.scala`: contentLength.parse tolerates double-LF header terminator
    - Given: chunk `"Content-Length: 2\n\n{}"` (no CR).
    - When: parse and run.
    - Then: yields `Chunk(Chunk("{}"))`.
    - Pins: design Item 7 tolerant-parse contract.

28. `JsonRpcTransportTest.scala`: stdio transport sends one line per envelope
    - Given: under `Console.Unsafe` test seam capturing stdout, build `JsonRpcTransport.stdio()`; from-scope endpoint constructed with `JsonRpcEndpoint.Config()`.
    - When: send `JsonRpcEnvelope.Notification("log", Present(Structure.Value.Record(Chunk("text" -> Str("hi")))), Absent)`.
    - Then: captured stdout receives exactly one line whose JSON parses as `{"jsonrpc":"2.0","method":"log","params":{"text":"hi"}}`.
    - Pins: design Item 5 stdio contract.

29. `JsonRpcTransportTest.scala`: stdio transport reads one envelope per stdin line
    - Given: under `Console.Unsafe` test seam pre-loaded with one line `{"jsonrpc":"2.0","method":"ping"}\n`, build `stdio()` and consume `.incoming.run`.
    - When: stream evaluated until EOF.
    - Then: result is `Chunk(JsonRpcEnvelope.Notification("ping", _, _))`.
    - Pins: design Item 5 line-per-envelope rule.

30. `JsonRpcTransportTest.scala`: stdio transport EOF closes incoming
    - Given: empty stdin under `Console.Unsafe`.
    - When: consume stdio incoming.
    - Then: stream yields empty Chunk without hanging (bounded by `Async.timeout(2.seconds)`).
    - Pins: design Item 5 EOF behavior.

### Consumed invariants

- INV-001: `WireTransportAdapter.incoming` maps JSON parse failures to `JsonRpcEnvelope.Malformed(Absent, _, _)` whose Skip route was produced by Phase 01.

### Produced invariants

- INV-004: Byte-stream transports route through `JsonRpcTransport.fromWire(wire, framer, codec)`; envelope-stream transports construct `JsonRpcTransport` directly.
- INV-006: New PUBLIC files (`WireTransport.scala`, `Framer.scala`) ship with `// flow-allow: PUBLIC ...` markers.
- INV-007: Each new source file ships in the same commit as its paired test file (`WireTransport.scala` with `WireTransportTest.scala`; `Framer.scala` and `internal/FramerImpl.scala` with `FramerTest.scala`; `internal/WireTransportAdapter.scala` and `internal/StdioWireTransport.scala` with `JsonRpcTransportTest.scala`).

### Convention sweep (per Decision #25)

- [em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set

- platforms: [jvm, js, native]

### Verification command

- `sbt 'kyo-jsonrpcJVM/testOnly *WireTransportTest *FramerTest *JsonRpcTransportTest' 'kyo-jsonrpcJS/testOnly *WireTransportTest *FramerTest *JsonRpcTransportTest' 'kyo-jsonrpcNative/testOnly *WireTransportTest *FramerTest *JsonRpcTransportTest'`

## Phase 04: JVM unixDomain transport (Item 6)

Depends on: Phase 03. The `unixDomain` adapter builds atop
`JsonRpcTransport.fromWire` (INV-004 consumer) using a UDS-backed
`WireTransport`. JVM-only; the file lives under
`kyo-jsonrpc/jvm/src/main/scala/kyo/`.

### Files to produce

- `/Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/jvm/src/main/scala/kyo/JsonRpcTransportJvm.scala`: PUBLIC JVM-only file holding `JsonRpcTransportJvm.unixDomain` plus an `extension`-on-companion that exposes the method as `JsonRpcTransport.unixDomain`.
  Matching test: `/Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/jvm/src/test/scala/kyo/JsonRpcTransportJvmTest.scala`

```scala
// /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/jvm/src/main/scala/kyo/JsonRpcTransportJvm.scala
// flow-allow: PUBLIC JVM-only UDS transport extension on the shared JsonRpcTransport companion
package kyo

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path

object JsonRpcTransportJvm:

    /** JVM-only UDS server transport. Binds a `ServerSocketChannel` on `sockPath`,
      * registers Scope.ensure cleanup that closes the channel and deletes the socket
      * file, and exposes the resulting bytes through `Framer.lineDelimited` by default.
      */
    def unixDomain(
        sockPath: Path,
        codec: JsonRpcCodec = JsonRpcCodec.Strict2_0,
        framer: Framer = Framer.lineDelimited
    )(using Frame): JsonRpcTransport < (Async & Scope) =
        Sync.defer {
            val addr    = UnixDomainSocketAddress.of(sockPath)
            val channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            channel.bind(addr)
            Scope.ensure(Sync.defer {
                channel.close()
                Files.deleteIfExists(sockPath)
                ()
            }).andThen {
                Sync.defer(new internal.UdsWireTransport(channel)).map { wire =>
                    JsonRpcTransport.fromWire(wire, framer, codec)
                }
            }
        }

    extension (self: JsonRpcTransport.type)
        def unixDomain(
            sockPath: Path,
            codec: JsonRpcCodec = JsonRpcCodec.Strict2_0,
            framer: Framer = Framer.lineDelimited
        )(using Frame): JsonRpcTransport < (Async & Scope) =
            JsonRpcTransportJvm.unixDomain(sockPath, codec, framer)

end JsonRpcTransportJvm
```

- `/Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/jvm/src/main/scala/kyo/internal/UdsWireTransport.scala`: INTERNAL UDS byte-stream transport (accept-loop, per-connection read/write).
  Matching test: `/Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/jvm/src/test/scala/kyo/JsonRpcTransportJvmTest.scala` (covers internal accept-loop through public `unixDomain`).

```scala
// /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/jvm/src/main/scala/kyo/internal/UdsWireTransport.scala
package kyo.internal

import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import kyo.*

private[kyo] final class UdsWireTransport(server: ServerSocketChannel) extends WireTransport:

    // Single client-at-a-time MVP: the first accepted connection wires send/incoming;
    // subsequent accepts are dropped. Multi-client requires a per-conn map, deferred to
    // the consumer-module roadmap.
    private val activeChannelRef: AtomicRef.Unsafe[Maybe[SocketChannel]] =
        AtomicRef.Unsafe.init[Maybe[SocketChannel]](Absent)(using AllowUnsafe.embrace.danger)

    def send(bytes: Chunk[Byte])(using Frame): Unit < (Async & Abort[Closed]) =
        Sync.defer {
            // flow-allow: AtomicRef.Unsafe.get inside Sync.defer for SocketChannel handoff
            activeChannelRef.get()(using AllowUnsafe.embrace.danger) match
                case Present(socket) =>
                    val buffer = ByteBuffer.wrap(bytes.toArray)
                    while buffer.hasRemaining do discard(socket.write(buffer))
                case Absent =>
                    ()
        }

    def incoming(using Frame): Stream[Chunk[Byte], Async & Abort[Closed]] =
        Stream.unfold[Chunk[Byte], Async & Abort[Closed], Unit](()) { _ =>
            Sync.defer {
                // flow-allow: AtomicRef.Unsafe access for accept-then-read MVP
                val socket = activeChannelRef.get()(using AllowUnsafe.embrace.danger) match
                    case Present(s) => s
                    case Absent     =>
                        val s = server.accept()
                        discard(activeChannelRef.compareAndSet(Absent, Present(s))(using AllowUnsafe.embrace.danger))
                        s
                val buffer = ByteBuffer.allocate(4096)
                val n      = socket.read(buffer)
                if n < 0 then Maybe.Absent
                else
                    buffer.flip()
                    val arr = new Array[Byte](n)
                    buffer.get(arr)
                    Maybe.Present((Chunk.from(arr), ()))
            }
        }

    def close(using Frame): Unit < Async =
        Sync.defer {
            // flow-allow: AtomicRef.Unsafe.get inside Sync.defer for SocketChannel handoff
            activeChannelRef.get()(using AllowUnsafe.embrace.danger).foreach(_.close())
            server.close()
        }
end UdsWireTransport
```

### Files to modify

(none)

### Files to delete

(none)

### Public API additions

- `def JsonRpcTransport.unixDomain(sockPath, codec, framer): JsonRpcTransport < (Async & Scope)` (JVM-only, via `extension` on `JsonRpcTransport.type` in `kyo/JsonRpcTransportJvm.scala`).

### Public API modifications

(none)

### Tests

Total: 4.

31. `JsonRpcTransportJvmTest.scala`: unixDomain binds and accepts a connection
    - Given: temp directory under `Files.createTempDirectory("kyo-jsonrpc-uds-")`; sock path `tempDir.resolve("test.sock")`.
    - When: `JsonRpcTransport.unixDomain(sockPath)` evaluated under Scope; client `SocketChannel.open(UnixDomainSocketAddress.of(sockPath))` invoked.
    - Then: `Files.exists(sockPath) == true` AND the client's `isConnected` returns `true`.
    - Pins: design Item 6 bind contract.

32. `JsonRpcTransportJvmTest.scala`: unixDomain round-trips one envelope (line-delimited)
    - Given: bound UDS transport on tempDir/test.sock; client writes one line `{"jsonrpc":"2.0","method":"ping"}\n` and then half-closes.
    - When: consume server `incoming.run`.
    - Then: yields `Chunk(JsonRpcEnvelope.Notification("ping", _, _))`.
    - Pins: INV-004 consumer (UDS goes through fromWire).

33. `JsonRpcTransportJvmTest.scala`: unixDomain Scope cleanup deletes socket file
    - Given: bound UDS transport.
    - When: scope completes (`Scope.run` returns).
    - Then: `Files.exists(sockPath) == false`.
    - Pins: design Item 6 scope cleanup.

34. `JsonRpcTransportJvmTest.scala`: unixDomain framer override changes wire shape
    - Given: bound UDS transport with `Framer.contentLength` override; client sends `Content-Length: 27\r\n\r\n{"jsonrpc":"2.0","method":"p"}`.
    - When: consume server `incoming.run`.
    - Then: yields a `Notification` whose `method == "p"`.
    - Pins: design Item 6 framer parameter.

### Consumed invariants

- INV-004: UDS routes through `fromWire(wire, framer, codec)`.
- INV-006: the new public file carries a `// flow-allow: PUBLIC ...` marker.
- INV-007: source plus test ship in the same commit.

### Produced invariants

(none new)

### Convention sweep (per Decision #25)

- [em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set

- platforms: [jvm]

### Verification command

- `sbt 'kyo-jsonrpcJVM/testOnly *JsonRpcTransportJvmTest'`

## Phase 05: kyo-jsonrpc-http subproject plus webSocket transport (Item 4)

Depends on: Phase 03. The WebSocket relay produces envelopes through the
same codec contract used by `fromWire`-built transports (envelope-stream
branch of INV-004; webSocket constructs `JsonRpcTransport` directly
rather than routing through `fromWire`). The subproject lives at
`kyo-jsonrpc-http/` and dependsOn `kyo-jsonrpc` plus `kyo-http`.

### Files to produce

- `/Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/build.sbt`: add the `kyo-jsonrpc-http` cross-platform subproject (jvm/js/native) with `dependsOn(kyo-jsonrpc, kyo-http)`.

(`build.sbt` is an sbt file, not Scala source. The block below is sbt-Scala
syntax; the flow-impl phase verifies sbt structure at commit time.)

```scala
// /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/build.sbt ; BEFORE
lazy val `kyo-jsonrpc` =
    crossProject(JVMPlatform, JSPlatform, NativePlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-jsonrpc"))
        .dependsOn(`kyo-schema`)
        .settings(/* ... existing settings ... */)
// /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/build.sbt ; AFTER
lazy val `kyo-jsonrpc` =
    crossProject(JVMPlatform, JSPlatform, NativePlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-jsonrpc"))
        .dependsOn(`kyo-schema`)
        .settings(/* ... existing settings ... */)

lazy val `kyo-jsonrpc-http` =
    crossProject(JVMPlatform, JSPlatform, NativePlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-jsonrpc-http"))
        .dependsOn(`kyo-jsonrpc`, `kyo-http`)
        .settings(/* inherit common test + scalac settings */)
```

- `/Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc-http/shared/src/main/scala/kyo/JsonRpcHttpTransport.scala`: PUBLIC `webSocket` transport bridging `HttpWebSocket` text frames through `JsonRpcCodec`.
  Matching test: `/Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc-http/shared/src/test/scala/kyo/JsonRpcHttpTransportTest.scala`

```scala
// /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc-http/shared/src/main/scala/kyo/JsonRpcHttpTransport.scala
// flow-allow: PUBLIC kyo-http-backed WebSocket transport adapter lifting HttpWebSocket text frames to JsonRpcTransport
package kyo

object JsonRpcHttpTransport:

    /** Lifts an `HttpClient.webSocket(url, headers, config)` session into a `JsonRpcTransport`.
      * Text frames are decoded through `codec`; Binary frames are dropped with a warn log.
      * `Scope.ensure` closes the WS on scope exit. A bridge fiber consumes `ws.stream` and
      * pushes decoded envelopes onto an inbound `Channel[JsonRpcEnvelope]`.
      */
    def webSocket(
        url: HttpUrl,
        headers: HttpHeaders = HttpHeaders.empty,
        codec: JsonRpcCodec = JsonRpcCodec.Strict2_0
    )(using Frame): JsonRpcTransport < (Async & Scope & Abort[HttpException]) =
        HttpClient.webSocket(url, headers, HttpWebSocket.Config()) { ws =>
            for
                // Unsafe: inbound channel must outlive the WS bridge fiber's local scope
                inbound <- Channel.initUnscoped[JsonRpcEnvelope](64)
                _       <- Fiber.initUnscoped {
                    ws.stream.foreach {
                        case HttpWebSocket.Frame.Text(text) =>
                            Json.decode[Structure.Value](text) match
                                case Result.Success(structureValue) =>
                                    Abort.run[JsonRpcError](codec.decode(structureValue)).map {
                                        case Result.Success(env) =>
                                            Abort.run[Closed](inbound.put(env)).unit
                                        case Result.Failure(err) =>
                                            Abort.run[Closed](inbound.put(JsonRpcEnvelope.Malformed(Absent, err.message, structureValue))).unit
                                        case Result.Panic(t) =>
                                            Log.warn(s"kyo-jsonrpc-http: codec panic ${t.getMessage}")
                                    }
                                case Result.Failure(e) =>
                                    Abort.run[Closed](inbound.put(JsonRpcEnvelope.Malformed(Absent, s"json parse: ${e.getMessage}", Structure.Value.Str(text)))).unit
                                case Result.Panic(t) =>
                                    Log.warn(s"kyo-jsonrpc-http: json parse panic ${t.getMessage}")
                        case HttpWebSocket.Frame.Binary(_) =>
                            Log.warn("kyo-jsonrpc-http: dropping binary frame (text-only contract)")
                        case _ => Kyo.unit
                    }
                }
                _       <- Scope.ensure(Abort.run[Closed](inbound.close).unit.andThen(ws.close))
            yield new JsonRpcTransport:
                def send(env: JsonRpcEnvelope)(using Frame): Unit < (Async & Abort[Closed]) =
                    Abort.run[JsonRpcError](codec.encode(env)).map {
                        case Result.Success(structure) =>
                            Json.encode(structure).map { jsonStr =>
                                ws.put(HttpWebSocket.Frame.Text(jsonStr))
                            }
                        case Result.Failure(err) =>
                            Log.warn(s"kyo-jsonrpc-http: encode failed ${err.message}")
                        case Result.Panic(t) =>
                            Log.warn(s"kyo-jsonrpc-http: encode panic ${t.getMessage}")
                    }

                def incoming(using Frame): Stream[JsonRpcEnvelope, Async & Abort[Closed]] =
                    Stream.fromChannel(inbound)

                def close(using Frame): Unit < Async =
                    Abort.run[Closed](inbound.close).unit.andThen(ws.close)
            end yield
        }
end JsonRpcHttpTransport
```

### Files to modify

(see build.sbt above ; that file is the only modification)

### Files to delete

(none)

### Public API additions

- `def JsonRpcHttpTransport.webSocket(url, headers, codec): JsonRpcTransport < (Async & Scope & Abort[HttpException])` in `kyo-jsonrpc-http/.../kyo/JsonRpcHttpTransport.scala`.

### Public API modifications

(none)

### Tests

Total: 4.

35. `JsonRpcHttpTransportTest.scala`: webSocket connects to a local kyo-http server
    - Given: an `HttpServer` started on `127.0.0.1:0` that accepts a single WebSocket upgrade and echoes received text frames; client invokes `JsonRpcHttpTransport.webSocket(serverUrl)` under Scope.
    - When: send `JsonRpcEnvelope.Request(JsonRpcId.Num(1), "ping", Absent, Absent)`.
    - Then: client's `incoming` observes a matching `Request` from the echo (decoded through the codec).
    - Pins: design Item 4 connect contract.

36. `JsonRpcHttpTransportTest.scala`: webSocket drops binary frames with warn
    - Given: same local server but it sends a binary frame after the upgrade.
    - When: consume client's `incoming` for 200ms.
    - Then: no envelope observed (binary frame dropped); a warn log line containing `"dropping binary frame"` is present in the Log fixture.
    - Pins: design Item 4 binary-drop contract.

37. `JsonRpcHttpTransportTest.scala`: Scope.ensure closes the WS on scope exit
    - Given: local server tracking close events.
    - When: client builds the transport under `Scope.run { ... }` and the scope completes.
    - Then: server observes a WS close handshake within 1 second.
    - Pins: design Item 4 Scope.ensure contract.

38. `JsonRpcHttpTransportTest.scala`: codec failure on malformed text frame surfaces as Malformed envelope (INV-001 consumer)
    - Given: server sends `"not json"` as a text frame.
    - When: consume `incoming` for one envelope.
    - Then: yields `JsonRpcEnvelope.Malformed(Absent, _, _)` whose reason contains `"json parse"`.
    - Pins: design Item 4 malformed-frame contract; INV-001 consumer.

### Consumed invariants

- INV-001: Malformed-with-Absent-id envelope shape produced by Phase 01 is the surface routed by test 38.
- INV-004: webSocket is the envelope-stream branch (not byte-stream-via-fromWire) of INV-004.
- INV-006: new PUBLIC file carries its marker.
- INV-007: source plus test paired in the same commit.

### Produced invariants

(none new)

### Convention sweep (per Decision #25)

- [em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set

- platforms: [jvm, js, native]

### Verification command

- `sbt 'kyo-jsonrpc-httpJVM/testOnly *JsonRpcHttpTransportTest' 'kyo-jsonrpc-httpJS/testOnly *JsonRpcHttpTransportTest' 'kyo-jsonrpc-httpNative/testOnly *JsonRpcHttpTransportTest'`

## Test totals

- Phase 01: 10 tests.
- Phase 02: 8 tests.
- Phase 03: 12 tests.
- Phase 04: 4 tests.
- Phase 05: 4 tests.
- Total: 38 tests.

## Invariant ledger mapping

- INV-001: produced Phase 01 (tests 3, 4, 5, 6, 7); consumed Phase 02 (test 16), Phase 03 (decoder branch in WireTransportAdapter), Phase 05 (test 38).
- INV-002: produced Phase 01 (tests 1, 2); consumed by plan-as-contract validation that every CDP / LSP / MCP test setup explicitly opts in via `Config().copy(...)`.
- INV-003: produced Phase 01 (test 10); consumed by `scripts/flow-verify-grep.sh::no-cancelMethod-fork` at commit time.
- INV-004: produced Phase 03 (tests 20, 22, 26); consumed Phase 04 (tests 32, 34), Phase 05 (envelope-stream branch).
- INV-005: produced Phase 01 (tests 8, 9); consumed by `scripts/flow-verify-grep.sh::single-teardown` at commit time.
- INV-006: produced Phase 03 (every new PUBLIC file carries marker); consumed Phase 04, Phase 05.
- INV-007: produced Phase 03 (source + test pairs); consumed Phase 04, Phase 05.

## Plan-level notes

- Design v2 dropped the v1 plan's seven protocol-named items. The
  v1 phases that wrapped those items (Phase 04 MCP edge-cases entirely;
  parts of v1 Phase 05 covering items 11 and 17) are absent here.
- The `Config()` no-arg default flip lives in Phase 01 alongside the
  other engine refactor work because the Config record edit and the
  test for it sit on the same file pair.
- Q-008 (Framer.contentLength strictness on trailing CRLF) was resolved
  in Design v2: tolerant on parse, strict on emit. Test 27 pins the
  tolerant-parse contract; test 25 pins strict emit. No further user
  input is required.
- The `CancellationEngine.extractCancelIdForTest` accessor referenced by
  test 10 is the existing `private[kyo]` shim; if missing, Phase 01's
  impl adds it as a one-line `private[kyo] def extractCancelIdForTest`
  forwarder over `extractCancelId`.
