# Phase 01 Prep: Engine refactor + Config neutrality

## Scope summary
- Item 8: JsonRpcEnvelope.Malformed gains `id: Maybe[JsonRpcId]` field (3rd position).
- Item 9: JsonRpcEndpoint gains `close(gracePeriod: Duration)` and `closeNow` overloads; existing `close()` becomes `close(Duration.Zero)`.
- Item 10: CancellationPolicy gains `decodeParams` field; CancellationEngine delegates to it instead of hard-coding method-name fork.
- Config flip: `cancellation` default flips from `Present(CancellationPolicy.lsp)` to `Absent`.

## Current source captures

### 1. JsonRpcEnvelope.scala (lines 1-27)

```scala
enum JsonRpcEnvelope derives CanEqual:
    case Request(
        id: JsonRpcId,
        method: String,
        params: Maybe[Structure.Value],
        extras: Maybe[Structure.Value]
    )
    case Notification(
        method: String,
        params: Maybe[Structure.Value],
        extras: Maybe[Structure.Value]
    )
    case Response(
        id: JsonRpcId,
        result: Maybe[Structure.Value],
        error: Maybe[JsonRpcError],
        extras: Maybe[Structure.Value]
    )
    case Malformed(reason: String, raw: Structure.Value)
end JsonRpcEnvelope
```

### 2. JsonRpcEndpoint.scala (lines 62-72)

Config default currently reads:
```scala
final case class Config(
    codec: JsonRpcCodec = JsonRpcCodec.Strict2_0,
    cancellation: Maybe[CancellationPolicy] = Present(CancellationPolicy.lsp),  // <- FLIP TARGET
    progress: Maybe[ProgressPolicy] = Absent,
    unknownMethod: UnknownMethodPolicy = UnknownMethodPolicy.minimal,
    gate: Maybe[MessageGate] = Absent,
    maxInFlight: Maybe[Int] = Absent,
    requestTimeout: Duration = Duration.Infinity,
    idStrategy: IdStrategy = IdStrategy.SequentialLong,
    progressResetsTimeout: Boolean = false
)
```

Close method currently reads (lines 48-49):
```scala
def awaitDrain(using Frame): Unit < Async = impl.awaitDrain

def close(using Frame): Unit < Async = impl.close
```

### 3. CancellationPolicy.scala (lines 10-51)

Current case class signature (lines 10-16):
```scala
final case class CancellationPolicy(
    cancelMethod: String,
    encodeParams: CancellationPolicy.ParamsEncoder,
    expectReplyForCancelledRequest: Boolean,
    cancelledError: Maybe[JsonRpcError],
    protectedMethods: Set[String]
) derives CanEqual
```

Current presets (lines 36-50):
```scala
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
```

### 4. JsonRpcCodecImpl.scala (lines 96-108 Strict2_0 decoder)

Current Malformed sites in Strict2_0:
```scala
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
```

Current Malformed sites in Cdp decoder (lines 218-223):
```scala
else
    JsonRpcEnvelope.Malformed("unclassifiable envelope", raw)
end if

case other =>
    JsonRpcEnvelope.Malformed("expected a Record", other)
```

### 5. JsonRpcEndpointImpl.scala close method (lines 638-639)

Current close implementation:
```scala
def close(using Frame): Unit < Async =
    finalizer
```

Finalizer exists at lines 641+; writerFiber, writerChannel, transport are all defined in the impl class.

### 6. JsonRpcEndpointImpl.scala decodeCallback Malformed branch (line 1181)

Current Malformed handling in decodeCallback (used by Exchange.initUnscoped):
```scala
case JsonRpcEnvelope.Malformed(_, _) =>
    Exchange.Message.Skip
```

### 7. CancellationEngine.scala (lines 9-27)

Current structure contains hard-coded method-name fork:
```scala
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
```

extractCancelId is called at lines 38-40 inside handleInboundCancel:
```scala
extractCancelId(policy, env.params) match
    case Absent =>
        Log.warn(s"kyo-jsonrpc: inbound cancel notification missing id, dropping")
    case Present(id) =>
        // ... rest of handler
```

## Test suite Config() callsites

Two files contain `Config()` no-arg calls:
1. `/Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/test/scala/kyo/ProgressPolicyTest.scala` (1 site)
2. `/Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/test/scala/kyo/UnknownMethodPolicyTest.scala` (2 sites)

Total: 3 Config() callsites in test suite. These inherit the flipped `cancellation = Absent` default and will not emit cancel notifications unless the test explicitly passes `cancellation = Present(CancellationPolicy.lsp)` or `.mcp`.

## Existing test coverage for Items 8 and 9

Tests touching Malformed (no tests for `.close` overloads yet):
- `JsonRpcCodecTest.scala`: "Strict2_0 decodes response with both result and error as Malformed" (pattern-matches current 2-arg Malformed)
- `JsonRpcCodecTest.scala`: "Strict2_0 decodes unclassifiable envelope as Malformed" (pattern-matches current 2-arg Malformed)
- `JsonRpcEnvelopeTest.scala`: "Request, Notification, Response, Malformed are CanEqual-distinguishable" (constructs 2-arg Malformed)
- `JsonRpcEnvelopeTest.scala`: "Malformed retains both reason and raw payload" (constructs 2-arg Malformed)

These 4 existing tests will fail pattern-match until they are updated to account for the new `id` field.

## Cross-platform notes for closeWithGracePeriod

- `Async.timeout` is available cross-platform (JVM/JS/Native).
- `awaitDrain` is already async-capable (does not depend on platform-specific transport mechanics for drain semantics).
- Duration.Zero comparison works identically across all platforms.
- No surprises expected; graceful shutdown logic is portable.

## Files to modify (count: 7)

1. `JsonRpcEnvelope.scala` - add `id: Maybe[JsonRpcId]` to Malformed enum case
2. `JsonRpcCodecImpl.scala` - update all Malformed constructor calls in Strict2_0 and Cdp decoders to pass `idMaybe`
3. `JsonRpcEndpointImpl.scala` - update decodeCallback Malformed branch to inspect id and fail pending caller if Present
4. `JsonRpcEndpoint.scala` - flip Config.cancellation default to Absent; add close(gracePeriod) and closeNow overloads
5. `JsonRpcEndpointImpl.scala` - replace close(no-arg) body with close(gracePeriod) that runs awaitDrain under timeout then finalizer
6. `CancellationPolicy.scala` - add decodeParams field; add lspDecoder and mcpDecoder private vals; update lsp and mcp presets
7. `CancellationEngine.scala` - remove LspCancelParams/McpCancelParams; change extractCancelId return type to `Maybe[JsonRpcId] < Sync`; delegate to policy.decodeParams

## Invariants to pin

INV-001: Malformed envelope carries extractable id (Present when the raw record has a valid id field, Absent otherwise).
INV-002: Config() no-arg constructor has protocol-neutral defaults (cancellation = Absent, not Present(CancellationPolicy.lsp)).
