# Phase 03 decisions — Error hierarchy

## Files created

### Base + 4 operation traits + 11 leaves + 1 abstract open class (one file)
All types consolidated into one file due to Scala 3 sealed-trait same-compilation-unit constraint.
`JsonRpcError` is `sealed abstract class`; every case class leaf extends it directly (in the same file).

- `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcError.scala` — complete hierarchy (1 file, ~410 LoC)

Leaves in the file (11 case classes + 1 abstract class):
1. `JsonRpcParseError` (parse failure, -32700, with `Reason` aux enum)
2. `JsonRpcInvalidRequestError` (parse failure, -32600)
3. `JsonRpcMethodNotFoundError` (dispatch failure, -32601)
4. `JsonRpcInvalidParamsError` (dispatch failure, -32602, with `ParamError` + `Problem` aux types)
5. `JsonRpcConfigurationError` (execution failure, -32603)
6. `JsonRpcLifecycleError` (execution failure, -32603, with `Stage` aux enum)
7. `JsonRpcTransportError` (execution failure, -32603)
8. `JsonRpcHandlerPanicError` (execution failure, -32603)
9. `JsonRpcInternalError` (execution failure, -32603, with `Operation` aux enum)
10. `JsonRpcImplementationError` (execution failure, -32099..-32000)
11. `JsonRpcApplicationError` (abstract open, application failure)
12. `JsonRpcCustomError` (application failure, any code)

## Files modified (caller updates)

### kyo-jsonrpc main sources
- `JsonRpcRoute.scala` — 6 sites: `internalError` → `JsonRpcHandlerPanicError`, `invalidParams` → `JsonRpcInvalidParamsError`
- `JsonRpcHandler.scala` — 1 site: `JsonRpcError.RequestCancelled` → `JsonRpcCustomError(-32800, ...)(using Frame.internal)` in `CancellationPolicy.lsp`
- `internal/engine/JsonRpcEndpointImpl.scala` — 18 sites migrated
- `internal/engine/CancellationEngine.scala` — 2 sites: `cancelled` → `JsonRpcCustomError(-32800, ...)`
- `internal/engine/ProgressEngine.scala` — 1 site: `internalError` → `JsonRpcInternalError`
- `internal/codec/JsonRpcCodecImpl.scala` — 5 sites migrated

### kyo-jsonrpc test sources
- `JsonRpcErrorTest.scala` — full rewrite (tests the new hierarchy)
- `JsonRpcEnvelopeTest.scala` — 2 sites
- `JsonRpcRouteTest.scala` — 3 sites
- `JsonRpcHandlerTest.scala` — 1 site
- `JsonRpcHandlerMessageGateTest.scala` — 3 sites
- `JsonRpcHandlerCancellationPolicyTest.scala` — 1 site
- `JsonRpcCodecTest.scala` — 1 site
- `JsonRpcHandlerUnknownMethodPolicyTest.scala` — 3 sites
- `scenario/MaxInFlightTest.scala` — 1 site
- `scenario/HttpStyleTest.scala` — 1 site

### kyo-browser main sources
- `internal/CdpBackend.scala` — string-prefix-match replaced with typed pattern match (lines 60-71)

### kyo-browser test sources
- `internal/CdpBackendTest.scala` — 5 sites: `JsonRpcError.methodNotFound(...)` → `JsonRpcMethodNotFoundError(...)`
- `internal/JsonRpcPortInvariantsSpec.scala` — 1 site
- `internal/CdpClientDecoderTest.scala` — 2 sites

## The 5 `internalError` reclassifications

| Engine site | Old call | New leaf |
|---|---|---|
| `JsonRpcEndpointImpl.scala:373` | `JsonRpcError.internalError("progress not configured: ...")` | `JsonRpcConfigurationError("progressPolicy", "required for callWithProgress; ...")` |
| `JsonRpcEndpointImpl.scala:453` | `JsonRpcError.internalError("progress not configured: ...")` | `JsonRpcConfigurationError("progressPolicy", "required for callPartialResults; ...")` |
| `JsonRpcEndpointImpl.scala:674,680` | `JsonRpcError.internalError("endpoint closed", Absent)` | `JsonRpcLifecycleError(JsonRpcLifecycleError.Stage.Close)` |
| `JsonRpcEndpointImpl.scala:813,836` | `JsonRpcError.internalError(s"transport closed: ...")` | `JsonRpcTransportError(detail, cause)` |
| `JsonRpcEndpointImpl.scala:819` | `JsonRpcError.internalError("wire decode error", Absent)` | `JsonRpcTransportError("wire decode error", new RuntimeException(...))` |
| `JsonRpcEndpointImpl.scala:1041` | `JsonRpcError.internalError("Internal error", Present(...))` | `JsonRpcHandlerPanicError(method, t)` (also recovers method name) |
| `internal/codec/JsonRpcCodecImpl.scala:51,151` | `JsonRpcError.internalError("cannot encode Malformed", Absent)` | `JsonRpcInternalError(Operation.EncodeResponse, ...)` |
| `internal/engine/ProgressEngine.scala:23` | `JsonRpcError.internalError("progress token exhaustion", Absent)` | `JsonRpcInternalError(Operation.Other, ...)` |

## The 4 out-of-spec -32602 reclassifications

| Engine site | Old call | New leaf | Rationale |
|---|---|---|---|
| `JsonRpcEndpointImpl.scala:107` | `JsonRpcError.invalidParams(e.getMessage)` on `Structure.decode[Out]` failure | `JsonRpcInternalError(Operation.DecodeResult, e)` | Response-decode failure is caller-side, not param validation |
| `JsonRpcEndpointImpl.scala:203` | `JsonRpcError.invalidParams(e.getMessage)` on response decode | `JsonRpcInternalError(Operation.DecodeResult, e)` | Same |
| `JsonRpcEndpointImpl.scala:518` | `JsonRpcError.invalidParams(e.getMessage)` on partial-result progress item decode | `JsonRpcInternalError(Operation.DecodeResult, e)` | Decode failure during stream consumption |
| `JsonRpcEndpointImpl.scala:534` | `JsonRpcError.invalidParams(e.getMessage)` on final partial-result decode | `JsonRpcInternalError(Operation.DecodeResult, e)` | Same |

## CdpBackend pattern-match migration

Old (string-prefix matching, `CdpBackend.scala:60-71`):
```scala
if err.code == JsonRpcError.RequestCancelled.code then ...timeout...
else if err.message == "endpoint closed" || err.message.startsWith("transport closed") then ...lost...
else ...protocol error...
```

New (typed pattern match):
```scala
case e: JsonRpcCustomError if e.code == -32800 => ...timeout...
case _: JsonRpcTransportError | _: JsonRpcLifecycleError => ...lost...
case err => ...protocol error...
```

## Structural deviations from the plan

1. **All leaves in one file**: The plan called for 11 separate leaf files. Scala 3's sealed-trait constraint requires all subtypes of `sealed abstract class JsonRpcError` to be in the same compilation unit. All 12 types (11 leaves + 1 abstract open class) live in `JsonRpcError.scala`. The 4 operation traits are sealed and also in the same file per the plan's sealed-trait same-compilation-unit note.

2. **Schema implementation**: The plan called for a hand-rolled `given Schema[JsonRpcError]`. The implementation uses `new Schema[JsonRpcError](Seq.empty)` with a `fromStructureValue` override. `serializeRead` reads `code`/`message` for binary codec paths; `fromStructureValue` reads the `Structure.Value.Record` directly for `Structure.decode` paths (which is the primary codec use case). The data field is captured in `fromStructureValue`; the binary `serializeRead` path drops data (no current binary decode caller needs it).

3. **`JsonRpcTransportError` for wire-decode error**: `Result.Failure(e)` from `Json.decode[Structure.Value]` returns a `DecodeException`. The `JsonRpcTransportError` wraps a plain `new RuntimeException("wire decode error")` since the `DecodeException` is not a `Throwable` in a usable way at that call site.

## Test results

- kyo-jsonrpc: `[success]` — 184/184 tests green (5 new error-hierarchy tests added)
- kyo-jsonrpc-http: `[success]` — compile green
- kyo-browser: `[success]` — compile green
- Convention sweeps: 9/9 = 0 hits in changed files
