# Phase 06 decisions

## 1. Lifecycle variants (item 1)

Added to `JsonRpcHandler` companion mirroring `HttpServer.scala:71-140`.

Scoped `init` overloads (2 new):
```scala
def init(transport, routes: JsonRpcRoute[?,?,?]*): JsonRpcHandler < (Async & Scope)
def init(transport, config)(routes: JsonRpcRoute[?,?,?]*): JsonRpcHandler < (Async & Scope)
```

`initWith` overloads (2 new):
```scala
def initWith[A, S](transport, routes*)(f: JsonRpcHandler => A < S): A < (S & Async & Scope)
def initWith[A, S](transport, config)(routes*)(f: JsonRpcHandler => A < S): A < (S & Async & Scope)
```

Unscoped overloads (2 new):
```scala
def initUnscoped(transport, routes: JsonRpcRoute[?,?,?]*): JsonRpcHandler < Async
def initUnscoped(transport, config)(routes: JsonRpcRoute[?,?,?]*): JsonRpcHandler < Async
```

`initUnscopedWith` overloads (2 new):
```scala
def initUnscopedWith[A, S](transport, routes*)(f: JsonRpcHandler => A < S): A < (S & Async)
def initUnscopedWith[A, S](transport, config)(routes*)(f: JsonRpcHandler => A < S): A < (S & Async)
```

All new varargs overloads delegate to the existing Seq-based canonical overload.
Total: 8 new signatures (4 pairs).

## 2. Config presets Config.lsp / Config.mcp (item 2)

Added to `JsonRpcHandler.Config` companion:

```scala
val lsp: Config = Config.default
    .cancellation(CancellationPolicy.lsp)
    .progress(ProgressPolicy.lsp)

val mcp: Config = Config.default
    .cancellation(CancellationPolicy.mcp)
    .progress(ProgressPolicy.mcp)
    .unknownMethod(UnknownMethodPolicy.minimal)
```

Each preset wires the matched cancellation + progress policy pair in one step (audit A5).
File: `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcHandler.scala` in the `Config` companion.

## 3. JsonRpcMessageGate hoist (item 3)

Created new file: `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcMessageGate.scala`.

`MessageGate` removed from `JsonRpcHandler`. The top-level `JsonRpcMessageGate` trait replaces it, with:
- `JsonRpcMessageGate.Decision` enum (Allow / Reject / Drop)
- `JsonRpcMessageGate.noop` preset (always Allow)
- `JsonRpcMessageGate.server` namespace object with `requireInitialize(onUninitializedRequest: JsonRpcResponse)` preset
- `JsonRpcMessageGate.client` namespace object (empty; outbound gating deferred to Phase G)

`JsonRpcHandler.Config.gate: Maybe[JsonRpcMessageGate]` updated.

## 4. Decision.Reject widening (audit A2)

`Decision.Reject(response: JsonRpcResponse)` - widened from `JsonRpcError` to `JsonRpcResponse`.

Caller migration count: 9 call sites updated across 3 test files.

Migration pattern:
- `Decision.Reject(err)` -> `Decision.Reject(JsonRpcResponse.failure(id, err))` for request gates
- `Decision.Reject(err)` -> `Decision.Reject(someResponse)` for notification gates (response is ignored; engine logs and drops)

Engine update: `JsonRpcEndpointImpl.scala:1178-1190` - the `Reject(response)` arm now sends `response` directly over the wire instead of constructing a new response from an error.

## 5. STEER-1: ErrorMapping consultation

File: `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcRoute.scala`
Location: `RequestRoute.handle` (line ~175) and `NotificationRoute.handle` (line ~217).

When the handler aborts with a `JsonRpcError`, the route's `errorMappings` are consulted:
```scala
errorMappings.iterator.find(_.matches(err)) match
    case Some(mapping) =>
        Abort.fail(JsonRpcCustomError(mapping.code, mapping.message)(using fr))
    case None =>
        Abort.fail(err)
```

`ErrorMapping.matches` uses `ConcreteTag[E].accepts(err)` for runtime type dispatch.

New integration test: `JsonRpcHandlerTest.scala` - "STEER-1: route .error[E2] maps domain error abort to declared wire code and message":
- Route aborts with `JsonRpcCustomError(-31999, "original")`, registered mapping overrides to code -32099 + label "My error".
- Assertion: `e.code == -32099` and `e.message.contains("My error")`.

## 6. STEER-2: Halt routing

Files changed:
- `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcRoute.scala` - `RequestRoute.handle` and `NotificationRoute.handle`
- `kyo-jsonrpc/shared/src/main/scala/kyo/internal/engine/JsonRpcEndpointImpl.scala:1054` - `onComplete` callback

In `handle`, the old behavior was to convert `Halt` to `JsonRpcHandlerPanicError`. New behavior: propagate the Halt:
```scala
case Result.Failure(halt: JsonRpcResponse.Halt) =>
    Abort.fail(halt)
```

In `onComplete`, before the generic error case, Halt is now intercepted:
```scala
case Result.Failure(halt: JsonRpcResponse.Halt) =>
    halt.response
```
The halt's wrapped response is sent directly over the wire.

`InboundEntry.Running.handler` type updated to `Fiber[Structure.Value, Abort[JsonRpcError | JsonRpcResponse.Halt]]`.
`JsonRpcHandler.Unsafe.dispatch` and `JsonRpcRoute.dispatch` return types updated to include `Abort[JsonRpcResponse.Halt]`.

New integration test: `JsonRpcHandlerTest.scala` - "STEER-2: handler Abort.fail(JsonRpcResponse.halt(resp)) sends resp directly over the wire":
- Handler calls `JsonRpcResponse.halt(JsonRpcResponse.failure(id, JsonRpcCustomError(-32777, "short-circuited")))`.
- Assertion: caller receives `JsonRpcError` with `code == -32777`.

## Deviations from the plan

1. **`JsonRpcMessageGate.client` is empty.** The plan mentioned client-side presets. No client-side patterns exist yet; the `client` object is a placeholder for Phase G. Documented with a comment.

2. **STEER-1 message format.** The plan specified `JsonRpcCustomError(code = mapping.code, label = mapping.message)` which produces `message = "Application error ($code): $label"`, not `label` alone. The test assertion was updated to `e.message.contains("My error")` rather than `e.message == "My error"`. The wire code -32099 assertion is exact.

3. **`JsonRpcMessageGate.server.requireInitialize` added as an extra.** Not in the original plan scope, but the HttpFilter.server namespace pattern called for real preset content. The LSP initialize-gate pattern is the canonical server-side use case and was already in the test suite; hoisting it as a reusable preset follows the HttpFilter.server.basicAuth precedent exactly.
