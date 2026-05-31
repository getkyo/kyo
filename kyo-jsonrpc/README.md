<!-- doctest:setup
```scala
import kyo.*
import kyo.Maybe.Absent
import kyo.Maybe.Present

case class AddReq(a: Int, b: Int) derives Schema, CanEqual
case class AddResp(sum: Int) derives Schema, CanEqual
case class GreetReq(name: String) derives Schema, CanEqual
case class GreetResp(greeting: String) derives Schema, CanEqual
case class LogMsg(text: String) derives Schema, CanEqual
case class ProgressReq(target: Int) derives Schema, CanEqual
case class ProgressResp(items: Chunk[String]) derives Schema, CanEqual
case class InitReq(clientName: String) derives Schema, CanEqual
case class InitResp(serverName: String) derives Schema, CanEqual
case class WorkReq(id: String) derives Schema, CanEqual
case class WorkResp(done: Boolean) derives Schema, CanEqual
case class Negative(a: Int, b: Int) derives Schema, CanEqual
```
-->

# kyo-jsonrpc

Bidirectional JSON-RPC 2.0 for building peer-to-peer clients and servers. A peer is a `JsonRpcHandler` attached to a `JsonRpcTransport`: register typed routes for the methods this peer answers, and use the handler's `call` / `notify` / `cancel` methods to talk to the other side. Both sides of a connection use the same API; the same handler value can simultaneously serve inbound requests and originate outbound ones, which matches how interactive RPC protocols actually work on the wire.

Transports cover the deployment shapes a JSON-RPC peer typically needs: line-delimited stdio for CLI-style servers, Unix domain sockets for local-machine IPC on JVM, paired in-memory channels for tests, and WebSocket via the sibling `kyo-jsonrpc-http` subproject. The shared API compiles across JVM, JavaScript, and Scala Native; the Unix-domain backend is JVM-only and aborts on the other platforms.

```scala
val greet = JsonRpcRoute.request[GreetReq, GreetResp]("greet") {
    (req, _) => GreetResp(s"Hello, ${req.name}!")
}
```

The single line above is a server. The next sections walk through pairing it with a client, registering more routes, picking a transport, and tuning the handler's behaviour.

## Building a peer

`JsonRpcHandler.init` takes a transport and a varargs list of routes. The result is a `JsonRpcHandler < (Async & Scope)`: `Async` because a background dispatch fiber starts immediately, `Scope` because that fiber, the inbound stream, and the outbound sender must all be released when the enclosing scope exits.

The same constructor is used on both sides. A peer that only initiates requests passes no routes; a peer that only answers requests simply does not call `.call` on the handler. The two snippets below are the same code; the difference is which side calls which extension method.

```scala
val program: Async & Scope ?=> AddResp < (Async & Scope & Abort[JsonRpcError | Closed]) =
    val add = JsonRpcRoute.request[AddReq, AddResp]("add") { (req, _) =>
        AddResp(req.a + req.b)
    }
    JsonRpcTransport.inMemory.map { (ta, tb) =>
        JsonRpcHandler.init(ta).map { client =>
            JsonRpcHandler.init(tb, add).map { _ =>
                client.call[AddReq, AddResp]("add", AddReq(2, 3))
            }
        }
    }
```

The handler closes automatically when the scope exits. The dispatch fiber is interrupted, in-flight responses fail with `Closed`, and the transport's `close` runs.

### Scoped vs unscoped lifecycle

There are two families of constructors:

- `JsonRpcHandler.init(...)`: returns `JsonRpcHandler < (Async & Scope)`. The handler closes when the enclosing `Scope` exits. This is the default and what almost all callers want.
- `JsonRpcHandler.initUnscoped(...)`: returns `JsonRpcHandler < Async`. The caller is responsible for calling `close` or `closeNow`. Useful for handlers whose lifetime exceeds any single scope, such as a server that lives for the entire process.

> **Caution:** an unscoped handler that never has `close` called leaks the inbound dispatch fiber and never releases the transport. Use scoped `init` unless you have a concrete reason to manage the lifetime by hand.

Both families have an `initWith` variant that takes a block which receives the handler. `initWith` exists for the common case where the handler is consumed within a single expression:

```scala
val withCallback: AddResp < (Async & Scope & Abort[JsonRpcError | Closed]) =
    val add = JsonRpcRoute.request[AddReq, AddResp]("add") { (req, _) => AddResp(req.a + req.b) }
    JsonRpcTransport.inMemory.map { (ta, tb) =>
        JsonRpcHandler.init(tb, add).map { _ =>
            JsonRpcHandler.initWith(ta) { client =>
                client.call[AddReq, AddResp]("add", AddReq(2, 3))
            }
        }
    }
```

Each variant has overloads that accept a `Config` before the routes (`init(transport, config)(routes*)`), or a `Seq[JsonRpcRoute]` plus default-argument `Config` (`init(transport, routes, config)`).

### Calling, notifying, cancelling

`JsonRpcHandler` is an opaque type; all surface methods are extension methods on the companion. Five lifecycle methods cover the peer-to-peer interaction surface:

- `call[In, Out](method, params)`: send a request and await the typed response.
- `notify[In](method, params)`: send a fire-and-forget notification.
- `callWithProgress[In, Out](method, params)`: returns a `Pending[Out]` that bundles the final result, a stream of progress notifications, the assigned id, and a cancel action.
- `cancel(id, reason)`: send a cancellation notification for an in-flight request that was started earlier.
- `close` / `close(gracePeriod)` / `closeNow`: tear down the handler.

```scala
val talk: Unit < (Async & Scope & Abort[JsonRpcError | Closed]) =
    val log   = JsonRpcRoute.notification[LogMsg]("log") { (msg, _) => Kyo.unit }
    val greet = JsonRpcRoute.request[GreetReq, GreetResp]("greet") {
        (req, _) => GreetResp(s"Hi, ${req.name}!")
    }
    JsonRpcTransport.inMemory.map { (ta, tb) =>
        JsonRpcHandler.init(tb, greet, log).map { server =>
            JsonRpcHandler.init(ta).map { client =>
                for
                    _    <- client.notify[LogMsg]("log", LogMsg("starting"))
                    resp <- client.call[GreetReq, GreetResp]("greet", GreetReq("World"))
                yield ()
            }
        }
    }
```

`call` adds `Abort[JsonRpcError | Closed]` to its effect row. `JsonRpcError` covers protocol-level failures (the peer rejected the call, decoded the wrong type, etc.); `Closed` covers the case where the transport went away before the response arrived.

> **Note:** `notify` does not add `Abort[JsonRpcError]`. JSON-RPC notifications have no response by definition; the framework cannot surface a remote failure for them. Use `call` if you need to know whether the peer acted on the message.

### Closing with a grace period

`close` is overloaded:

```scala
val tearDown: Unit < (Async & Scope) =
    val noRoutes: Seq[JsonRpcRoute[?, ?, ?]] = Seq.empty
    JsonRpcTransport.inMemory.map { (ta, _) =>
        JsonRpcHandler.init(ta, noRoutes).map { handler =>
            // immediate; identical to closeNow:
            handler.close.andThen(handler.closeNow)
        }
    }

val drainSlowly: Unit < (Async & Scope) =
    val noRoutes: Seq[JsonRpcRoute[?, ?, ?]] = Seq.empty
    JsonRpcTransport.inMemory.map { (ta, _) =>
        JsonRpcHandler.init(ta, noRoutes).map { handler =>
            handler.close(5.seconds)
        }
    }
```

The no-arg `close` is identical to `closeNow`: zero grace period, in-flight requests fail with `Closed`. The `close(gracePeriod)` variant waits up to `gracePeriod` for in-flight requests to drain before forcing.

### Progress-bearing requests

When you only need the final result, use `call`; when you also need the intermediate progress stream, the request id, and a cancel handle, use `callWithProgress`. The latter returns a `JsonRpcHandler.Pending[Out]` that bundles four things:

- `id`: the assigned outbound request id, usable with `handler.cancel`.
- `result`: the final typed response, available as `Out < (Async & Abort[JsonRpcError | Closed])`.
- `progress`: a `Stream[Structure.Value, Async]` of intermediate progress notifications.
- `cancel`: an action that sends a cancellation notification for this request.

The handler only forwards progress to the caller when a `JsonRpcProgressPolicy` is set on `Config`; without a policy, the stream is empty and the server-side `ctx.progress(value)` is a silent no-op.

For protocols that stream typed partial results (rather than opaque progress values), `callPartialResults[In, T]` is the streaming variant:

```scala
import scala.compiletime.uninitialized
val handler: JsonRpcHandler = uninitialized
val partials: Stream[ProgressResp, Async & Abort[JsonRpcError | Closed]] =
    handler.callPartialResults[ProgressReq, ProgressResp]("longJob", ProgressReq(100))
```

> **Note:** `callPartialResults` requires a `Tag[Emit[Chunk[T]]]` in addition to the usual `Schema` and `Tag` for `T`. Missing this implicit is the most common compile-error reason; the inferred-type display is the give-away.

## Defining routes

A `JsonRpcRoute[In, Out, +E]` binds a method name to a typed handler. Three things are typed:

- `In`: the decoded request parameter type. Must have a `Schema`.
- `Out`: the encoded response result type. Must have a `Schema`.
- `+E`: the union of user-domain error types the handler may abort with, accumulated by chained `.error[E2]` calls. `Nothing` by default.

The handler's effect row is fixed to `Async & Abort[JsonRpcError | JsonRpcResponse.Halt]`. Domain errors `E` are tracked through the `+E` parameter and the registered `.error` mappings; they are not free type parameters on the handler.

### Request and notification factories

`JsonRpcRoute.request[In, Out](name)(handler)` is the canonical factory for request/response routes:

```scala
val add = JsonRpcRoute.request[AddReq, AddResp]("add") { (req, ctx) =>
    AddResp(req.a + req.b)
}
```

`JsonRpcRoute.notification[In](name)(handler)` is the fire-and-forget mirror; the handler returns `Unit` and the framework sends no reply:

```scala
val logRoute = JsonRpcRoute.notification[LogMsg]("log") { (msg, _) =>
    Kyo.unit
}
```

> **Note:** there is no bare `JsonRpcRoute.apply` form. Always use `request` or `notification`; the kind discriminator (`Kind.Request` vs `Kind.Notification`) is implicit in the factory you choose.

### The per-request context

Each route handler receives the decoded request value and a `JsonRpcRoute.Context`. The context exposes four fields:

- `cancelled`: a `Fiber.Promise[Unit, Sync]` that completes when the peer cancels this request. Race it against your work to abort cleanly.
- `requestId`: the JSON-RPC id of the inbound request, `Absent` for notifications.
- `extras`: protocol-specific extra fields from the inbound envelope, if any.
- `progress(value)`: reports a progress notification back to the caller, using whichever method name the active `JsonRpcProgressPolicy` specifies.

```scala
val cancellable = JsonRpcRoute.request[WorkReq, WorkResp]("work") { (req, ctx) =>
    val work: WorkResp < Async = Async.sleep(1.second).andThen(WorkResp(true))
    Async.race(work, ctx.cancelled.get.andThen(Abort.fail(JsonRpcCustomError(409, "cancelled"))))
}
```

`ctx.progress(value)` is a no-op when no progress policy is set on the handler's `Config`; the handler does not error and the caller sees nothing. Set a policy on `Config` before wiring routes that emit progress.

### Domain errors and `.error[E2]`

A route accumulates typed user-domain error types via `.error[E2](code, message)`. Each call:

1. Adds `E2` to the route's `+E` union (so `JsonRpcRoute[In, Out, E | E2]`).
2. Registers a mapping: when the handler aborts with a value matching `E2`, the engine emits a `JsonRpcCustomError(code, message)` on the wire.

```scala
val negCheckedAdd: JsonRpcRoute[AddReq, AddResp, Negative] =
    JsonRpcRoute.request[AddReq, AddResp]("add") { (req, _) =>
        if req.a < 0 || req.b < 0 then Abort.fail(Negative(req.a, req.b))
        else AddResp(req.a + req.b)
    }.error[Negative](-32010, "Negative input")
```

> **Caution:** the original `Abort.fail(e: E2)` value is **not** propagated to the wire. Only the registered mapping's `code` and `message` are. If you want the diagnostic context to reach the peer, encode it into a custom error subclass and use `.error` mappings whose messages cover the case, or abort with `JsonRpcApplicationError` directly.

### Short-circuiting with `Halt`

A route or gate that wants to bypass remaining processing and emit a pre-built response can abort with `JsonRpcResponse.Halt(response)`:

```scala
val gated = JsonRpcRoute.request[InitReq, InitResp]("initialize") { (req, ctx) =>
    if req.clientName.isEmpty then
        val rejection = JsonRpcResponse.failure(
            JsonRpcId(0L),
            JsonRpcImplementationError(-32001, "Empty clientName")
        )
        JsonRpcResponse.halt(rejection)
    else InitResp("server")
}
```

`JsonRpcResponse.halt(response)` is the convenience that builds the `Halt` and aborts with it. The engine emits the wrapped response directly, skipping the normal encoding path.

> **Unlike** ordinary `JsonRpcError` aborts, which the framework encodes through the route's schema and error mappings, a `Halt` carries a full response envelope. Use `Halt` when you have already chosen the wire response (including id, extras, error code); use `Abort.fail(JsonRpcError)` when you want the framework to encode the error normally.

## Transports

Pick a transport based on how the peer reaches its counterpart: tests use paired in-memory channels, CLI servers use stdio, local IPC uses Unix domain sockets, networked peers use WebSocket (see `kyo-jsonrpc-http`). The factories below show each pattern.

`JsonRpcTransport` is the envelope-level seam between the handler and the underlying I/O. The trait has three methods (`send`, `incoming`, `close`); the companion ships factories for the four shapes above.

### In-memory pairs (testing)

`JsonRpcTransport.inMemory` returns a pair of cross-wired transports. `a.send` arrives on `b.incoming` and vice versa. `close` on either end terminates both streams.

```scala
val paired: Unit < (Async & Scope) =
    JsonRpcTransport.inMemory.map { (a, b) =>
        JsonRpcHandler.init(a).map { _ =>
            JsonRpcHandler.init(b).map(_ => ())
        }
    }
```

A capacity override is available: `JsonRpcTransport.inMemory(1024)`. The default is 64.

### Line-delimited stdio (CLI servers)

`JsonRpcTransport.stdio` reads `Console.readLine` and writes `Console.printLine`, one envelope per line. EOF on stdin closes `incoming`. This is the deployment shape for CLI-style RPC servers spawned by another process.

```scala
val cliServer: Unit < (Async & Scope) =
    val ping = JsonRpcRoute.request[GreetReq, GreetResp]("ping") {
        (req, _) => GreetResp(s"pong ${req.name}")
    }
    JsonRpcTransport.stdio().map { transport =>
        JsonRpcHandler.init(transport, ping).map(_ => ())
    }
```

The framer and codec default to `JsonRpcFramer.lineDelimited` and `JsonRpcCodec.Strict2_0`; override either argument for protocols that frame differently.

### Unix domain sockets (JVM-only)

`JsonRpcTransport.unixDomain(path)` binds a `ServerSocketChannel` using `StandardProtocolFamily.UNIX`, registers a `Scope` cleanup that closes the channel and deletes the socket file, and exposes the connection as a `JsonRpcTransport`.

```scala
import java.nio.file.Paths
val uds: Unit < (Async & Scope & Abort[Throwable]) =
    JsonRpcTransport.unixDomain(Paths.get("/tmp/myrpc.sock")).map { transport =>
        JsonRpcHandler.init(transport).map(_ => ())
    }
```

> **Caution:** on Scala.js and Scala Native, `unixDomain` immediately aborts with an `UnsupportedOperationException` because the NIO UDS APIs are not available on those platforms. Cross-platform code that may run outside JVM must guard with `Abort.run` or pick a different transport per platform.

### WebSocket (kyo-jsonrpc-http)

WebSocket support lives in the sibling `kyo-jsonrpc-http` subproject. Add the dependency and import `kyo.JsonRpcHttpTransport`:

```scala doctest:expect=skipped
libraryDependencies += "io.getkyo" %% "kyo-jsonrpc-http" % "<latest version>"
```

```scala doctest:expect=skipped
import kyo.JsonRpcHttpTransport

val ws: JsonRpcTransport < (Async & Scope & Abort[HttpException]) =
    JsonRpcHttpTransport.webSocket(HttpUrl("wss://example.com/rpc"))
```

The same factory is also reachable through `JsonRpcTransport.webSocket(url)` (via an extension on the `JsonRpcTransport` companion); both forms produce the same value.

### Lifting a custom byte-stream transport

For deployment shapes the built-in factories do not cover (a TCP socket, a named pipe, a test double over `Stream[Chunk[Byte], ...]`), implement `JsonRpcWireTransport` and lift it with `JsonRpcTransport.fromWire`:

```scala
val custom: JsonRpcTransport < (Async & Scope) =
    JsonRpcTransport.fromWire(
        wire   = JsonRpcWireTransport.empty,
        framer = JsonRpcFramer.lineDelimited,
        codec  = JsonRpcCodec.Strict2_0
    )
```

`JsonRpcWireTransport` is the byte-level seam below `JsonRpcTransport`. The trait has `send(bytes)`, `incoming` (a byte stream), and `close`. `JsonRpcWireTransport.empty` is a no-op test double.

### Framing and codec dials

Most readers will not touch these; the `stdio` and `inMemory` factories supply sensible defaults. Override only when your transport demands a different byte-framing pattern (binary-safe, length-prefixed) or your peer expects a non-standard JSON-RPC dialect.

A `JsonRpcFramer` controls how raw bytes are split into discrete message frames on the wire. Two presets ship:

- `JsonRpcFramer.lineDelimited`: one frame per LF-terminated segment. Trailing CR before LF is stripped; empty lines are skipped. Suitable for stdio transports.
- `JsonRpcFramer.contentLength`: `Content-Length: N\r\n\r\n<N bytes>` framing. Tolerant of `\n\n` on parse, strict `\r\n\r\n` on emit. Suitable for binary-safe channels that need to carry messages with embedded newlines.

A `JsonRpcCodec` encodes and decodes `JsonRpcEnvelope` values to and from structural `Structure.Value` trees. Two presets ship:

- `JsonRpcCodec.Strict2_0`: strict JSON-RPC 2.0; the `"jsonrpc":"2.0"` field is required on decode and emitted on encode. The default.
- `JsonRpcCodec.Lenient`: omits the `"jsonrpc"` version field on encode; accepts messages without it on decode. Useful for peers that do not include the version field.

`JsonRpcCodec.default` returns `Strict2_0`. Set the active codec via `JsonRpcHandler.Config.codec` or pass it directly to `JsonRpcTransport.fromWire` and `JsonRpcTransport.stdio`.

## Errors

`JsonRpcError` is the root error type. It is a `sealed abstract class` extending `KyoException` and carries `code`, `message`, `data: Maybe[Structure.Value]`, and an optional `cause`. The handler effect row carries `Abort[JsonRpcError]`; pattern-match on the specific subtype to discriminate.

### Operational stage traits

The hierarchy is organised by the pipeline stage at which an error arises:

- `JsonRpcParseFailure`: surfaces during JSON-text parsing or envelope-shape validation.
- `JsonRpcDispatchFailure`: surfaces during method routing or parameter validation.
- `JsonRpcExecutionFailure`: surfaces during handler-body execution, transport, lifecycle, or configuration.
- `JsonRpcApplicationFailure`: user-domain errors from handler bodies.

These are sealed traits, not effect-row entries. The handler row carries `Abort[JsonRpcError]`; the stage trait is a discriminator for pattern matching:

```scala
def classify(e: JsonRpcError): String = e match
    case _: JsonRpcParseFailure       => "parse"
    case _: JsonRpcDispatchFailure    => "dispatch"
    case _: JsonRpcExecutionFailure   => "execution"
    case _: JsonRpcApplicationFailure => "application"
```

### Wire-defined leaves

Ten leaves correspond directly to the JSON-RPC 2.0 reserved error codes plus the residual `-32603` execution range:

| Leaf | Code | Category | Notes |
|------|------|----------|-------|
| `JsonRpcParseError` | -32700 | parse | typed `Reason` enum |
| `JsonRpcInvalidRequestError` | -32600 | parse | carries `missingFields` |
| `JsonRpcMethodNotFoundError` | -32601 | dispatch | carries `available` methods |
| `JsonRpcInvalidParamsError` | -32602 | dispatch | carries `Chunk[ParamError]` |
| `JsonRpcConfigurationError` | -32603 | execution | misconfiguration at runtime |
| `JsonRpcLifecycleError` | -32603 | execution | typed `Stage` enum |
| `JsonRpcTransportError` | -32603 | execution | wire transport failed |
| `JsonRpcHandlerPanicError` | -32603 | execution | handler body threw |
| `JsonRpcInternalError` | -32603 | execution | typed `Operation` enum |
| `JsonRpcImplementationError` | -32099..-32000 | execution | reserved server-error range |

`JsonRpcImplementationError`'s constructor is private; use the smart constructor which enforces the reserved range:

```scala
val rangeOk = JsonRpcImplementationError(-32050, "Rate limited")
// JsonRpcImplementationError(0, "bad") would throw IllegalArgumentException
```

> **Note:** codes inside `[-32099, -32000]` are JSON-RPC 2.0 reserved server-error space; use `JsonRpcImplementationError`. Codes outside that range are user-defined; use a `JsonRpcApplicationError` subclass for typed cases, or `JsonRpcCustomError(code, message)` for the untyped catchall.

### Application errors

When you want a typed domain-error class, like `Unauthorized` or `NotFound`, extend `JsonRpcApplicationError`. The base is non-sealed precisely to allow this. Any subclass you define gets its `code`, `message`, and `data` fields encoded as-is on the wire.

```scala
case class Unauthorized()(using Frame) extends JsonRpcApplicationError(401, "Unauthorized")
case class NotFound(resource: String)(using Frame)
    extends JsonRpcApplicationError(404, s"Not found: $resource")
```

For callers who do not want a typed subclass, `JsonRpcCustomError(code, label, data)` is the catchall. The same type is used by `JsonRpcError.fromWire` for unknown wire codes.

### Wire interop

`JsonRpcError.fromWire(code, message, data)` is the wire decoder. Known codes map to specific leaves; codes in `[-32099, -32000]` become `JsonRpcImplementationError`; all other unknown codes become `JsonRpcCustomError`. The companion also provides a `given Schema[JsonRpcError]` that projects any leaf to the `(code, message, data)` wire triple.

### `.error[E2]` revisited

The relationship between `.error[E2]` route mappings and the error hierarchy: an `Abort.fail(e: E2)` matched by a registered mapping becomes a `JsonRpcCustomError(mapping.code, mapping.message)` on the wire. If you want richer wire data, abort with a `JsonRpcApplicationError` subclass directly; the framework will encode its `code`, `message`, and `data` fields as-is.

## Configuration and behavior policies

The defaults match the common case: a JSON-RPC 2.0 peer that does not need cancellation, progress, message gating, or in-flight caps. Override a field only when your protocol or deployment actually requires that behaviour; leave it `Absent` otherwise. Each policy below is described in WHEN-to-reach-for-it terms.

`JsonRpcHandler.Config` collects the per-handler behaviour knobs:

```scala
final case class Config(
    codec:                 JsonRpcCodec,
    cancellation:          Maybe[JsonRpcCancellationPolicy],
    progress:              Maybe[JsonRpcProgressPolicy],
    unknownMethod:         JsonRpcUnknownMethodPolicy,
    gate:                  Maybe[JsonRpcMessageGate],
    maxInFlight:           Maybe[Int],
    requestTimeout:        Duration,
    idStrategy:            JsonRpcIdStrategy,
    progressResetsTimeout: Boolean
)
```

Start from `JsonRpcHandler.Config.default` and use the fluent builder methods (`codec`, `cancellation`, `progress`, `unknownMethod`, `gate`, `maxInFlight`, `requestTimeout`, `idStrategy`, `progressResetsTimeout`):

```scala
val tuned: JsonRpcHandler.Config =
    JsonRpcHandler.Config.default
        .codec(JsonRpcCodec.Lenient)
        .maxInFlight(64)
        .requestTimeout(30.seconds)
        .unknownMethod(JsonRpcUnknownMethodPolicy.strict)
```

`Config.require(c)` validates the config (currently: `maxInFlight > 0`); it is called from every `init` variant. `maxInFlight <= 0` throws `IllegalArgumentException` at init time, not lazily.

### Unknown methods

When a peer calls a method you have not registered, this policy decides what happens: silently ignore the notification, return a `JsonRpcMethodNotFoundError`, or apply a per-name predicate. Set it via `Config.unknownMethod(JsonRpcUnknownMethodPolicy.strict)` or one of the other presets. `JsonRpcUnknownMethodPolicy` controls how the handler reacts to inbound requests and notifications for methods that have no registered route. Two presets:

- `JsonRpcUnknownMethodPolicy.minimal` (default): reply `MethodNotFound` on requests, silently drop notifications.
- `JsonRpcUnknownMethodPolicy.strict`: reply `MethodNotFound` on requests, reject unknown notifications.

For protocols where a class of notifications should always be ignored (e.g. those whose method name starts with `$/`), use the predicate field:

```scala
val pol = JsonRpcUnknownMethodPolicy.minimal.copy(
    ignoreUnknownNotification = _.startsWith("$/")
)
```

### Cancellation

When your protocol supports cancellation, set a policy describing the wire shape so the handler's `cancel` extension actually emits something. `JsonRpcCancellationPolicy` covers the notification method name, the `(id, reason)` params encoder and decoder, whether the cancelled request still expects a reply, the error surfaced to the caller, and a set of protected method names that cannot be cancelled. Wire it via `Config.cancellation`. Without a policy, the handler's `cancel` extension sends nothing.

### Progress

When your protocol streams intermediate progress notifications, set a policy so the handler knows the method name to listen for and emit on. `JsonRpcProgressPolicy` covers the progress-notification method name, the token-extraction and stamping functions, the value-extraction function, and an `enforceMonotonic` flag. Wire it via `Config.progress`. Without a policy, server-side `ctx.progress(value)` is a no-op and client-side `Pending.progress` is empty.

> **Caution:** `enforceMonotonic = true` silently drops progress values whose `progress` field is not strictly non-decreasing. The caller never sees the dropped values. Default is `false`.

### Outbound id allocation

When your peer requires a specific id shape (string ids, ints, or a custom format), override the default with `Config.idStrategy(...)`. `JsonRpcIdStrategy` selects how outbound request ids are allocated:

- `SequentialLong` (default): monotonically increasing `Long` ids starting at 1.
- `SequentialInt`: monotonically increasing `Int` ids; wraps at `Int.MaxValue`.
- `Custom(next)`: caller-supplied generator. Use when the peer requires string ids or a specific id format.

### Message gates

When you need to filter inbound envelopes before routing (authentication, rate limiting, protocol handshake), install a gate via `Config.gate(...)`. `JsonRpcMessageGate` is a pre-dispatch hook returning `Decision.Allow`, `Decision.Reject(response)`, or `Decision.Drop`.

```scala
val rejection = JsonRpcResponse.failure(
    JsonRpcId(0L),
    JsonRpcImplementationError(-32002, "Not ready")
)

val handshakeGate: JsonRpcMessageGate =
    JsonRpcMessageGate.server.requireHandshake("initialize", rejection)
```

`requireHandshake(method, rejectionResponse)` is the built-in preset: any request before the named method completes once is rejected with the supplied response. `JsonRpcMessageGate.noop` admits every envelope. The `client` namespace is reserved for future outbound gates.

> **Note:** `Decision.Reject` carries a full `JsonRpcResponse`, not a bare `JsonRpcError`. Gates that want to fail a request construct the response themselves via `JsonRpcResponse.failure(id, err)`; the engine does not synthesise one.

### Per-call extras

When your protocol expects out-of-band fields on every outbound envelope (a session id, a trace token, an auth header field), supply an encoder so each `call` / `notify` stamps them automatically. `JsonRpcExtrasEncoder` is an opaque function type that receives the assigned request id and returns an optional `Structure.Value` map merged into the envelope's `extras` field.

```scala
val tagged: JsonRpcExtrasEncoder = JsonRpcExtrasEncoder.const(
    Structure.Value.Record(Chunk("sessionId" -> Structure.Value.Str("abc123")))
)
```

Factories: `JsonRpcExtrasEncoder.empty`, `JsonRpcExtrasEncoder.const(value)`, and `JsonRpcExtrasEncoder.apply(fn)`. Pass the result as the third argument to `call`, `notify`, or `sendUnmatched`.

## Wire messages and ids

For codec authors and transport authors who work directly with the wire data model, the envelope type is `JsonRpcEnvelope`: a sealed trait with four concrete cases.

```scala
def describe(env: JsonRpcEnvelope): String = env match
    case _: JsonRpcRequest          => "request"
    case _: JsonRpcResponse         => "response"
    case _: JsonRpcNotification     => "notification"
    case _: JsonRpcMalformedMessage => "malformed"
```

Each case is a top-level case class:

- `JsonRpcRequest(id, method, params, extras)`.
- `JsonRpcResponse(id, result, error, extras)`: at most one of `result` and `error` is `Present` on a well-formed response. The codec surfaces a message with both fields set as `JsonRpcMalformedMessage`.
- `JsonRpcNotification(method, params, extras)`: no id by definition.
- `JsonRpcMalformedMessage(id, reason, raw)`: a message that failed shape validation.

`JsonRpcResponse.success(id, result)` and `JsonRpcResponse.failure(id, error)` are the builders for the canonical response shapes.

> **Note:** when a malformed message has `id: Present` matching a pending caller, that caller is failed with `InvalidRequest`. Otherwise the message is silently dropped. Four shape failures produce a `JsonRpcMalformedMessage`: non-object top level, both `result` and `error` set on a response, non-object `error` field, or no-method-and-no-id-with-result-or-error.

### `JsonRpcId`

`JsonRpcId` is an opaque `String | Long` union. JSON-RPC 2.0 §5 allows ids to be a string, a number without fractional parts, or null; null is represented at the call site by `Maybe.Absent`. Construct via the apply factories and pattern-match via `Num` / `Str`:

```scala
val numId: JsonRpcId = JsonRpcId(42L)
val strId: JsonRpcId = JsonRpcId("req-1")

def label(id: JsonRpcId): String = id match
    case JsonRpcId.Num(n) => s"num=$n"
    case JsonRpcId.Str(s) => s"str=$s"
```

Extensions on `JsonRpcId`: `fold(ifLong, ifString)`, `isLong`, `isString`, `toLongOption`, `toStringOption`. A `Schema[JsonRpcId]` and a `CanEqual[JsonRpcId, JsonRpcId]` are provided.

## Cross-platform behavior

The shared API compiles and runs on JVM, JavaScript, and Scala Native. The cross-platform exceptions are:

| Surface | JVM | JS | Native |
|---------|-----|----|----|
| `JsonRpcHandler` and routes | yes | yes | yes |
| `JsonRpcTransport.inMemory` | yes | yes | yes |
| `JsonRpcTransport.stdio` | yes | yes | yes |
| `JsonRpcTransport.fromWire` + custom `JsonRpcWireTransport` | yes | yes | yes |
| `JsonRpcTransport.unixDomain` | yes | aborts | aborts |
| `JsonRpcHttpTransport.webSocket` (separate subproject) | yes | yes | yes |

On Scala.js and Scala Native, `unixDomain` aborts with an `UnsupportedOperationException`. Code that targets all three platforms must guard the call or pick a different transport per platform.

The WebSocket transport requires the `kyo-jsonrpc-http` subproject, which depends on `kyo-http`. It compiles for all three platforms.
