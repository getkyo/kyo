# Phase 02: API Additions Prep

## Scope

Item 3: ProgressEngine.allocateProgressToken (generic putIfAbsent retry loop, max 32 attempts)
Item 12: Public JsonRpcMethod.dispatch(name, methods, params, ctx) (currently `handle` is private[kyo])
Item 13: endpoint.sendUnmatched(method, params, id, extras) (fire-and-forget with explicit id)

## Current State Snapshots

### 1. ProgressEngine Token Issuance

**File**: `/Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/internal/ProgressEngine.scala`

Current lines 1-84 show the ProgressEngine object. It contains `buildProgressSink` (lines 16-82) which assembles a progress sink closure for handlers. The token generation currently happens inline at the call site (not extracted into allocateProgressToken yet).

In `JsonRpcEndpointImpl.scala` at line 368 (inside `callWithProgress`):
```scala
val tokenStr = Random.live.unsafe.nextStringAlphanumeric(32)(using AllowUnsafe.embrace.danger)
val tokenVal = Structure.Value.Str(tokenStr)
...
discard(progressStreams.put(tokenVal, progChan))
```

This pattern will be extracted into a reusable `allocateProgressToken(progressStreams, channel, maxAttempts)` helper that retries on collision (putIfAbsent returns non-null).

Precedent for putIfAbsent pattern exists in kyo-http's NioIoDriver (multiple worktrees):
```scala
Maybe(pendingConnects.putIfAbsent(handle.channel, promise)) match
    // handle collision or success
```

### 2. JsonRpcMethod Dispatch (public API)

**File**: `/Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcMethod.scala`

Current private[kyo] `handle` method (line 22):
```scala
private[kyo] def handle(params: Structure.Value, ctx: HandlerCtx)(using Frame): Structure.Value < (Async & Abort[JsonRpcError])
```

Item 12 adds a public companion object method:
```scala
def dispatch[S](
    name: String,
    methods: Seq[JsonRpcMethod[S]],
    params: Structure.Value,
    ctx: HandlerCtx
)(using Frame, (Async & Abort[JsonRpcError]) <:< S): Maybe[Structure.Value < (S & Abort[JsonRpcError])]
```

This will be placed at lines 25-40 (inside the companion object, before RequestMethod class definition at line 59). It routes lookups through a Map[String, JsonRpcMethod[S]] and invokes the private `handle` method on the matched method, returning Present(comp) on hit and Absent on miss.

### 3. JsonRpcEndpoint sendUnmatched (public fire-and-forget)

**File**: `/Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEndpoint.scala`

Current public surface (lines 1-54) includes `call`, `notify`, `callWithProgress`, etc. No `sendUnmatched` yet.

Item 13 adds:
```scala
def sendUnmatched[In: Schema](
    method: String,
    params: In,
    id: JsonRpcId,
    extras: ExtrasEncoder = ExtrasEncoder.empty
)(using Frame): Unit < (Async & Abort[Closed])
```

Implementation delegates to `impl.sendUnmatched` in JsonRpcEndpointImpl.

**File**: `/Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcEndpointImpl.scala`

Current `notify` (lines 339-350):
```scala
def notify[In: Schema](
    method: String,
    params: In,
    extras: ExtrasEncoder
)(using Frame): Unit < (Async & Abort[Closed]) =
    val sentinelId    = JsonRpcId.Num(-1L)
    val encodedParams = Present(Structure.encode[In](params))
    extras.resolve(sentinelId).map { extrasVal =>
        val env = JsonRpcEnvelope.Notification(method, encodedParams, extrasVal)
        writerChannel.put(WriterMsg.SendEnvelope(env))
    }
```

`sendUnmatched` will follow the same pattern but emit a Request envelope with the provided id instead of a Notification:
```scala
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
```

It does NOT register in callerRegistry (fire-and-forget), so no response expectation.

## Tests Touching Phase 02 Items

**File**: `/Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/test/scala/kyo/ProgressPolicyTest.scala`

- Lines 66-90: "callWithProgress with LSP" test that relies on token issuance (future allocateProgressToken call)
- Lines 92-113: "stampOutboundToken" test verifying token arrives in handler params
- Lines 160-197: "subscribeProgress" test using explicit out-of-band token (not allocated, pre-seeded)
- Lines 243-276: "MCP monotonicity" test touching progress token collection behavior

**File**: `/Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcMethodTest.scala`

Expected to contain tests for JsonRpcMethod.dispatch (Item 12) once this phase lands.

**File**: `/Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcEndpointTest.scala`

Expected to contain tests for sendUnmatched (Item 13) once this phase lands.

## Conventions to Verify

- No em-dashes, en-dashes, or LLM-tells (feedback_no_em_dashes.md)
- AllowUnsafe only where justified (feedback_no_unsafe.md); Random token generation in Sync.Unsafe.defer is justified
- No Option vs Maybe inconsistency
- No semicolons separating statements (feedback_no_semicolons.md)
- No asInstanceOf (feedback_no_casts.md)
- No default params in internal helpers (allocateProgressToken is private[kyo], no defaults)
- No manual JSON string building (use Structure.encode, Json.encode)
- Log all panics/failures (feedback_log_unexpected_failures.md)

## Ready for Implementation

All three public APIs are scoped and grounded in existing tests. allocateProgressToken collision handling via putIfAbsent follows kyo-http precedent. No blocking dependencies or design conflicts identified.
