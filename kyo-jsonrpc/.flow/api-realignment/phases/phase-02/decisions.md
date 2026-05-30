# Phase 02 Decisions — Wire-Message Hoist

## Files Created

One consolidation file containing all 4 case classes plus the sealed trait:
- `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEnvelope.scala` — restructured from `enum` to `sealed trait` + 4 inline case classes
- `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcId.scala` — new opaque type `JsonRpcId = String | Long` with hand-rolled Schema, Num/Str extractors for pattern matching

**Scala 3 sealed-trait constraint**: Scala 3 requires all subtypes of a sealed trait to reside in the same compilation unit. The prompt's "5 new top-level files" shape was adjusted: `JsonRpcRequest`, `JsonRpcResponse`, `JsonRpcNotification`, `JsonRpcMalformedMessage` are declared as top-level case classes in the same `JsonRpcEnvelope.scala` file as the sealed trait. Each has full scaladoc (14-27 lines). `JsonRpcId` is a separate file as planned.

## Files Modified

Internal source (kyo-jsonrpc):
- `JsonRpcHandler.scala` — `Pending.id`, `IdStrategy.Custom`, `ExtrasEncoder` opaque type, `CancellationPolicy` internal types, `cancel` signature: all `JsonRpcEnvelope.Id` → `JsonRpcId`
- `JsonRpcRoute.scala` — `Context.requestId`, `Context.forTest`: `JsonRpcEnvelope.Id` → `JsonRpcId`
- `internal/codec/JsonRpcCodecImpl.scala` — all `JsonRpcEnvelope.X(...)` constructors + `decodeId` functions: enum cases → top-level classes + `JsonRpcId`
- `internal/codec/JsonRpcRequest.scala` (internal) — `id` field type updated to `JsonRpcId`
- `internal/engine/JsonRpcEndpointImpl.scala` — all `ConcurrentHashMap` key types, `OutboundReq.idSignal`, `WriterMsg.SuppressIfCancelled.id`, `encodeCallback` type, `decodeCallback` type, all pattern matches on `JsonRpcEnvelope.X(...)`, all response constructions
- `internal/engine/CancellationEngine.scala` — `handleInboundCancel` param type, `buildAndEnqueueOutboundCancel` id type, `handleTimeout` id/callerRegistry types, outbound cancel construction: `JsonRpcEnvelope.Notification` → `JsonRpcNotification`
- `internal/engine/IdStrategyEngine.scala` — return type + all `JsonRpcEnvelope.Id.Num(...)` → `JsonRpcId(...)`
- `internal/engine/ProgressEngine.scala` — `buildProgressSink` signature + progress notification construction: `JsonRpcEnvelope.Notification` → `JsonRpcNotification`
- `internal/transport/WireTransportAdapter.scala` — 2 `JsonRpcEnvelope.Malformed(...)` → `JsonRpcMalformedMessage(...)`

Test files (kyo-jsonrpc) — 17 files updated by bulk sed:
JsonRpcEnvelopeTest, JsonRpcEnvelopeIdTest, JsonRpcCodecTest, JsonRpcHandlerTest, JsonRpcHandlerIdStrategyTest, JsonRpcHandlerCancellationPolicyTest, JsonRpcHandlerMessageGateTest, JsonRpcHandlerExtrasEncoderTest, JsonRpcHandlerProgressPolicyTest, JsonRpcHandlerUnknownMethodPolicyTest, JsonRpcRouteTest, JsonRpcRouteContextTest, JsonRpcTransportTest, JsonRpcTransportWireTransportTest, scenario/BidiTest, scenario/HttpStyleTest, scenario/MaxInFlightTest, jvm/JsonRpcTransportUnixTest.

kyo-jsonrpc-http (1 main + 1 test file):
- `JsonRpcHttpTransport.scala` — 2 `JsonRpcEnvelope.Malformed(...)` → `JsonRpcMalformedMessage(...)`
- `JsonRpcHttpTransportTest.scala` — bulk sed (no enum references in this file after sweep confirms 0)

kyo-browser (1 main + 3 test files):
- `internal/CdpBackend.scala` — `JsonRpcEnvelope.Id.Num(id.toLong)` → `JsonRpcId(id.toLong)`
- `internal/CdpClientDecoderTest.scala` — 4 `JsonRpcEnvelope.Malformed(...)` → `JsonRpcMalformedMessage(...)`
- `internal/CdpBackendSmokeTest.scala` — bulk sed (no enum references)
- `internal/JsonRpcPortInvariantsSpec.scala` — bulk sed (no enum references)

## Files Deleted

None. `JsonRpcEnvelope.scala` was restructured in-place (enum → sealed trait + 4 inline case classes). The 4 temporary separate files created during the session were removed before the first successful compile.

## Schema Preservation

The `JsonRpcId` opaque type Schema (in `JsonRpcId.scala`) is byte-for-byte identical to the old `JsonRpcEnvelope.Id` Schema (from the deleted `object Id` in `JsonRpcEnvelope.scala`).

Old (lines 67-79 of `JsonRpcEnvelope.scala` before restructure):
```scala
given schema: Schema[Id] = Schema.init[Id](
    writeFn = (id, writer) =>
        id match
            case Id.Num(n) => writer.long(n)
            case Id.Str(s) => writer.string(s),
    readFn = reader =>
        if reader.isNil() then
            throw TypeMismatchException(Seq.empty, "number or string", "null")(using reader.frame)
        else
            try Id.Num(reader.long())
            catch case _: TypeMismatchException => Id.Str(reader.string())
)
```

New (lines 46-58 of `JsonRpcId.scala`):
```scala
given schema: Schema[JsonRpcId] = Schema.init[JsonRpcId](
    writeFn = (id, writer) =>
        id match
            case n: Long   => writer.long(n)
            case s: String => writer.string(s),
    readFn = reader =>
        if reader.isNil() then
            throw TypeMismatchException(Seq.empty, "number or string", "null")(using reader.frame)
        else
            try JsonRpcId(reader.long())
            catch case _: TypeMismatchException => JsonRpcId(reader.string())
)
```

Wire encoding is identical: a `Long` id writes as a JSON integer, a `String` id writes as a JSON string. The `readFn` tries `long()` first (integer on wire) and falls back to `string()` — identical semantics. The `TypeMismatchException` for null is preserved verbatim.

## Response Factories

`JsonRpcResponse.success` and `JsonRpcResponse.failure` were restored on the `JsonRpcResponse` companion object (they were previously on `JsonRpcEnvelope.Response` companion). The signatures are:
```scala
def success(id: JsonRpcId, result: Structure.Value)(using Frame): JsonRpcResponse
def failure(id: JsonRpcId, error: JsonRpcError)(using Frame): JsonRpcResponse
```

## Convention Sweep — 9 Checks, All 0 Hits

1. `JsonRpcEnvelope.Request` — 0
2. `JsonRpcEnvelope.Response` — 0
3. `JsonRpcEnvelope.Notification` — 0
4. `JsonRpcEnvelope.Malformed` — 0
5. `JsonRpcEnvelope.Id` — 0
6. `enum JsonRpcEnvelope` — 0
7. `JsonRpcEnvelope.Id.Num` — 0
8. `JsonRpcEnvelope.Id.Str` — 0
9. `object Id` nested in `JsonRpcEnvelope` — 0

## Test Results

```
kyo-jsonrpc/Test/compile  [success]
kyo-jsonrpc-http/Test/compile  [success]
kyo-browser/Test/compile  [success]
kyo-jsonrpc/test: 179 tests, 0 failed, 0 canceled
```
