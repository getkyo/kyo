<!-- doctest:setup
```scala
import kyo.*
import kyo.Maybe.Absent
import kyo.Maybe.Present

case class AddReq(a: Int, b: Int) derives Schema, CanEqual
case class AddResp(sum: Int) derives Schema, CanEqual
case class LogMsg(text: String) derives Schema, CanEqual
case class Job(id: String, name: String) derives Schema, CanEqual
```
-->

# kyo-jsonrpc

`kyo-jsonrpc` carries JSON-RPC 2.0 messages bidirectionally between two peers over a pluggable transport, with a shared API that compiles on JVM, JavaScript, and Scala Native. The same handler runs the server side (registering routes that answer incoming requests and notifications) and the client side (calling out, notifying, cancelling, receiving progress). A peer is a handler, not a server or a client; whether it serves work, requests work, or does both is a function of which routes you register and which methods you call.

The architecture is three layers stacked under one entry point. At the top, `JsonRpcHandler` owns the dispatch loop and exposes typed `call`, `notify`, `callWithProgress`, and `cancel` operations. Below it, `JsonRpcTransport` ferries one `JsonRpcEnvelope` at a time, in either direction. Below that, `JsonRpcWireTransport` + `JsonRpcFramer` + `JsonRpcCodec` together turn raw byte streams into envelopes, so that a custom transport (TCP socket, UDS, content-length stdio) only has to plug in at the byte level. Handlers fail with a typed `JsonRpcError` hierarchy organized by pipeline stage (parse, dispatch, execution, application), and user-domain errors register through `.error[E2](code, message)` on the route to flow back to peers as wire error responses.

```scala
val add = JsonRpcRoute.request[AddReq, AddResp]("add") {
    (req, _) => AddResp(req.a + req.b)
}
```

That single declaration is the answering side of a peer. The next sections pair it with a caller, layer in notifications and progress, pick a transport, and tune behaviour through `Config`.

## Building a peer

Every JSON-RPC peer is the same construct; a handler attached to a transport; whether it serves work, requests it, or both. `JsonRpcHandler.init` takes the transport and a varargs list of routes, and returns a `JsonRpcHandler < (Async & Scope)`: `Async` because a background dispatch fiber starts immediately, `Scope` because that fiber, the inbound stream, and the outbound sender must all be released when the enclosing scope exits.

The same constructor is used on both sides. A peer that only initiates requests passes no routes; a peer that only answers requests simply does not call `.call` on the handler. The snippet below builds both sides over a paired in-memory transport, then issues one round-trip:

```scala
val program: AddResp < (Async & Scope & Abort[JsonRpcError | Closed]) =
    val add = JsonRpcRoute.request[AddReq, AddResp]("add") { (req, _) =>
        AddResp(req.a + req.b)
    }
    JsonRpcTransport.inMemory.map { (ta, tb) =>
        JsonRpcHandler.init(tb, add).map { _ =>
            JsonRpcHandler.init(ta).map { client =>
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

> **Note:** `JsonRpcHandler.initUnscopedWith(transport, routes*)(f)` is the closure-style unscoped variant ; same lifecycle obligation as `initUnscoped` (you must close the handler explicitly), with `initWith`'s convenience of receiving the live `JsonRpcHandler` inside `f`.

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

## Defining routes

A route is the unit of "what this peer answers." A `JsonRpcRoute[In, Out, +E]` binds a method name to a typed handler: `In` is the decoded request parameter type (`Schema` required), `Out` is the encoded response result type (`Schema` required), and `+E` is the union of user-domain error types the handler may abort with, accumulated by chained `.error[E2]` calls (`Nothing` by default).

The handler's effect row is fixed to `Async & Abort[E | JsonRpcResponse.Halt]`. Framework wire errors (`JsonRpcError`) are produced by the engine, not by user closures; a closure that aborts with `JsonRpcInternalError(...)` is wire-encoded as such but bypasses the typed `.error` mapping path. Fixing the row at this shape keeps user closures focused on their own domain errors while the framework owns the protocol-level error surface. The `Halt` channel is the one escape hatch for closures that need to emit a pre-built wire response without going through the schema and mapping pipeline.

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
val cancellable = JsonRpcRoute.request[Job, AddResp]("doJob") { (job, ctx) =>
    val work: AddResp < Async = Async.sleep(1.second).andThen(AddResp(0))
    Async.race(work, ctx.cancelled.get.andThen(Abort.fail(JsonRpcCustomError(409, "cancelled"))))
}
```

`ctx.progress(value)` is a no-op when no progress policy is set on the handler's `Config`; the handler does not error and the caller sees nothing. Set a policy on `Config` before wiring routes that emit progress.

### Domain errors and `.error[E2]`

A route accumulates typed user-domain error types via `.error[E2](code, message)`. Each call:

1. Adds `E2` to the route's `+E` union (so `JsonRpcRoute[In, Out, E | E2]`).
2. Registers a mapping: when the handler aborts with a value matching `E2`, the engine emits a `JsonRpcCustomError(code, mapping.message, data = encode(e))` on the wire, where `encode` uses the registered `Schema[E2]`.

```scala
case class Negative()(using Frame) extends JsonRpcApplicationError(-32010, "Negative input")

val checkedAdd =
    JsonRpcRoute.request[AddReq, AddResp]("checkedAdd") { (req, _) =>
        if req.a < 0 || req.b < 0 then Abort.fail(Negative())
        else AddResp(req.a + req.b)
    }
```

Registration is by type at `.error[E2]` call time rather than by reading runtime fields off the error value. The `Schema[E2]` and a `ConcreteTag[E2]` are captured then; the engine matches abort values by tag, encodes them by schema, and stamps the registered `code`/`message` on the wire. A runtime-field approach would force every error class to carry the same `code` / `message` accessors and would push wire concerns into the domain types; the mapping table keeps domain types as plain Scala values.

> **Caution:** the abort value's own `toString` is **not** propagated. Only the registered mapping's `code`, the mapping's `message`, and the `Schema[E2]`-encoded `data` payload reach the peer. If you want diagnostic context to flow as the wire `message`, abort with a `JsonRpcApplicationError` subclass directly instead of using a `.error[E2]` mapping.

### Short-circuiting with `Halt`

A route or gate that wants to bypass remaining processing and emit a pre-built response can abort with `JsonRpcResponse.Halt(response)`:

```scala
val gated = JsonRpcRoute.request[AddReq, AddResp]("addOrHalt") { (req, ctx) =>
    if req.a == 0 && req.b == 0 then
        val zeroId    = ctx.requestId.getOrElse(JsonRpcId(0L))
        val rejection = JsonRpcResponse.failure(zeroId, JsonRpcImplementationError(-32001, "Both zero"))
        JsonRpcResponse.halt(rejection)
    else AddResp(req.a + req.b)
}
```

`JsonRpcResponse.halt(response)` is the convenience that builds the `Halt` and aborts with it. The engine emits the wrapped response directly, skipping the normal encoding path.

> **Unlike** ordinary `JsonRpcError` aborts, which the framework encodes through the route's schema and error mappings, a `Halt` carries a full response envelope. Use `Halt` when you have already chosen the wire response (including id, extras, error code); use `Abort.fail(JsonRpcError)` when you want the framework to encode the error normally.

## Calling the peer

A handler value is both ends. The factory you used in the previous section produced the same shape whether the peer is intended to mostly answer or mostly call; this section is about the calling side.

### Calling, notifying, cancelling

`JsonRpcHandler` is an opaque type; all surface methods are extension methods on the companion. The peer-to-peer interaction surface is:

- `call[In, Out](method, params)`: send a request and await the typed response.
- `notify[In](method, params)`: send a fire-and-forget notification.
- `sendUnmatched[In](method, params, id)`: send a request with a caller-chosen id without registering a pending caller.
- `cancel(id, reason)`: send a cancellation notification for an in-flight request that was started earlier.
- `callWithProgress[In, Out](method, params)` / `callPartialResults[In, T](method, params)`: progress-bearing variants, covered below.
- `close` / `close(gracePeriod)` / `closeNow`: tear down the handler.

```scala
val talk: Unit < (Async & Scope & Abort[JsonRpcError | Closed]) =
    val log = JsonRpcRoute.notification[LogMsg]("log") { (msg, _) => Kyo.unit }
    val add = JsonRpcRoute.request[AddReq, AddResp]("add") {
        (req, _) => AddResp(req.a + req.b)
    }
    JsonRpcTransport.inMemory.map { (ta, tb) =>
        JsonRpcHandler.init(tb, add, log).map { server =>
            JsonRpcHandler.init(ta).map { client =>
                for
                    _    <- client.notify[LogMsg]("log", LogMsg("starting"))
                    resp <- client.call[AddReq, AddResp]("add", AddReq(2, 3))
                yield ()
            }
        }
    }
```

`call` adds `Abort[JsonRpcError | Closed]` to its effect row. `JsonRpcError` covers protocol-level failures (the peer rejected the call, decoded the wrong type, etc.); `Closed` covers the case where the transport went away before the response arrived.

> **Note:** `notify` does not add `Abort[JsonRpcError]`. JSON-RPC notifications have no response by definition; the framework cannot surface a remote failure for them. Use `call` if you need to know whether the peer acted on the message.

`sendUnmatched` is for the cases where the framework's id allocator must not own the outbound id. Two patterns reach for it. The first is protocol passthrough, where one peer is forwarding a request whose id it already received from a third party; the framework should not allocate a fresh id, and it should not register the local handler as a pending caller waiting for the response (the response will be routed back to the original third party). The second is foreign-id replies, where the peer needs to send a "request" frame whose id is dictated by the protocol rather than by the local id strategy. `sendUnmatched` writes the envelope and returns; no `Pending` entry is created.

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

`callWithProgress` and `callPartialResults` cover two distinct protocol shapes. Pick by what the peer actually sends. `callWithProgress` is for protocols where the peer streams opaque progress notifications (often a counter or a percentage) alongside a single typed final result; the result and the progress stream are separate channels:

```scala
val withProgress: JsonRpcHandler.Pending[AddResp] < (Async & Scope & Abort[JsonRpcError | Closed]) =
    JsonRpcTransport.inMemory.map { (ta, _) =>
        JsonRpcHandler.init(ta).map { handler =>
            handler.callWithProgress[Job, AddResp]("longJob", Job("j1", "build"))
        }
    }
```

The returned `JsonRpcHandler.Pending[Out]` bundles four things:

- `id`: the assigned outbound request id, usable with `handler.cancel`.
- `result`: the final typed response, available as `Out < (Async & Abort[JsonRpcError | Closed])`.
- `progress`: a `Stream[Structure.Value, Async]` of opaque intermediate progress values.
- `cancel`: an action that sends a cancellation notification for this request.

`callPartialResults[In, T]` is for protocols where the peer streams *typed* partial results of the same shape; there is no separate "final" value, just a `Stream[T, ...]` that completes when the peer sends the closing frame:

```scala
val partials: JsonRpcTransport => Stream[AddResp, Async & Abort[JsonRpcError | Closed]] < (Async & Scope) =
    transport =>
        JsonRpcHandler.init(transport).map(_.callPartialResults[Job, AddResp]("stream", Job("j1", "build")))
```

The handler only forwards progress to the caller when a `JsonRpcProgressPolicy` is set on `Config`; without a policy, `callWithProgress` returns a `Pending` whose `progress` stream is empty, and `callPartialResults` only sees what the engine receives on the configured progress method (so it likewise emits nothing without a policy).

> **Note:** `callPartialResults` requires a `Tag[Emit[Chunk[T]]]` in addition to the usual `Schema` and `Tag` for `T`. Missing this implicit is the most common compile-error reason; the inferred-type display is the give-away.

## Transports

`JsonRpcTransport` is the envelope-level seam between the handler and the underlying I/O. The trait has three methods (`send`, `incoming`, `close`); the companion ships factories for the four shapes a JSON-RPC peer typically needs: paired in-memory channels for tests, line-delimited stdio for CLI-style servers, Unix domain sockets for local-machine IPC on JVM, and WebSocket via the sibling `kyo-jsonrpc-http` subproject.

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
    val add = JsonRpcRoute.request[AddReq, AddResp]("add") {
        (req, _) => AddResp(req.a + req.b)
    }
    JsonRpcTransport.stdio().map { transport =>
        JsonRpcHandler.init(transport, add).map(_ => ())
    }
```

The framer and codec default to `JsonRpcFramer.lineDelimited` and `JsonRpcCodec.Strict2_0`; override either argument for protocols that frame differently.

> **Caution:** `lineDelimited.parse` skips empty lines and does not flush a partial line at EOF. Bytes sent without a trailing newline are silently dropped when the peer closes its stdout. Always terminate frames with `\n` and flush before closing.

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

### Content-length stdio (JVM-only)

For LSP/DAP/BSP-style stdio that frames each message with `Content-Length: N\r\n\r\n<N bytes>`, the JVM provides an extension factory:

```scala doctest:expect=skipped
import kyo.JsonRpcTransport

val lspServer: Unit < (Async & Scope) =
    JsonRpcTransport.contentLengthStdio().map { transport =>
        JsonRpcHandler.init(transport).map(_ => ())
    }
```

> **Caution:** `contentLengthStdio` is a JVM-only extension method (lives in `kyo-jsonrpc/jvm/src/main/scala`). Code that targets JS or Native must not import it; calling it under a cross-platform source root will fail to compile on those platforms.

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
- `JsonRpcCodec.Lenient`: omits the `"jsonrpc"` version field on encode; accepts messages without it on decode.

`JsonRpcCodec.default` returns `Strict2_0`. Set the active codec via `JsonRpcHandler.Config.codec` or pass it directly to `JsonRpcTransport.fromWire` and `JsonRpcTransport.stdio`.

> **Note:** `JsonRpcCodec.Lenient` is asymmetric. Decoding tolerates a missing `jsonrpc` version field, but encoding still validates that user-supplied `extras` do not collide with reserved keys (`jsonrpc`, `id`, `method`, `params`, `result`, `error`); collisions abort with `JsonRpcInvalidRequestError`.

## Configuration

`JsonRpcHandler.Config` collects the per-handler behaviour knobs. The defaults match the common case: a JSON-RPC 2.0 peer that does not need cancellation, progress, message gating, or in-flight caps.

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

Optional policies are typed as `Maybe[...]` rather than carrying a no-op default. `Absent` means the engine skips the entire policy code path: with `cancellation = Absent`, the outbound `cancel` extension writes no frame and inbound cancellation notifications hit `unknownMethod`; with `progress = Absent`, `ctx.progress(value)` returns without allocating and `Pending.progress` is an empty stream. A no-op default would force every handler to evaluate "is this the no-op?" on every message; the `Maybe` makes "feature not configured" a static, zero-cost branch.

Start from `JsonRpcHandler.Config.default` and use the fluent builder methods:

```scala
val tuned: JsonRpcHandler.Config =
    JsonRpcHandler.Config.default
        .codec(JsonRpcCodec.Lenient)
        .maxInFlight(64)
        .requestTimeout(30.seconds)
        .unknownMethod(JsonRpcUnknownMethodPolicy.strict)
```

`Config.require(c)` validates the config (currently: `maxInFlight > 0`); it is called from every `init` variant. `maxInFlight <= 0` throws `IllegalArgumentException` synchronously at init time, not via `Abort`.

> **Caution:** the validation throws a JVM exception (`IllegalArgumentException`) rather than aborting through an effect, because it runs inside `init` before any effect-row context exists. Wrap the `init` call in `Abort.catching[IllegalArgumentException]` if you need a typed handle for the failure.

### Request timeouts and the silent no-op

`requestTimeout` defaults to `Duration.Infinity`. With the default, an in-flight `call` never times out locally. Combine `requestTimeout = Duration.Infinity` with `cancellation = Absent` and the caller can hang indefinitely waiting for a peer reply that never arrives; nothing in the framework will surface a timeout, and there is no protocol-level cancellation to send. Outbound `call`s that may need to survive an unresponsive peer should either set a finite `requestTimeout` or attach a `cancellation` policy and race the call against an explicit deadline.

> **Caution:** `Config.progressResetsTimeout = true` is a silent no-op when `requestTimeout = Duration.Infinity`. The flag tells the engine to push the deadline forward each time a progress notification arrives, but there is no deadline to push when the timeout is infinite. The combination compiles, runs, and does nothing.

### Unknown methods

When a peer calls a method you have not registered, this policy decides what happens: silently ignore the notification, return a `JsonRpcMethodNotFoundError`, or apply a per-name predicate. Set it via `Config.unknownMethod(JsonRpcUnknownMethodPolicy.strict)` or one of the other presets. Two presets ship:

- `JsonRpcUnknownMethodPolicy.minimal` (default): reply `MethodNotFound` on requests, silently drop notifications.
- `JsonRpcUnknownMethodPolicy.strict`: reply `MethodNotFound` on requests, reject unknown notifications.

For protocols where a class of notifications should always be ignored (e.g. those whose method name starts with `$/`), use the predicate field:

```scala
val pol = JsonRpcUnknownMethodPolicy.minimal.copy(
    ignoreUnknownNotification = _.startsWith("$/")
)
```

> **Caution:** `strict` does not turn an unknown notification into a recoverable typed error. It closes the inbound stream as a lifecycle event. Pick `strict` only when an unknown notification is genuinely a protocol violation that should tear the peer down; otherwise stay on `minimal` or use the predicate field.

### Cancellation

When your protocol supports cancellation, set a policy describing the wire shape so the handler's `cancel` extension actually emits something. `JsonRpcCancellationPolicy` covers the notification method name, the `(id, reason)` params encoder and decoder, whether the cancelled request still expects a reply, the error surfaced to the caller, and a set of protected method names that cannot be cancelled. Wire it via `Config.cancellation`. Without a policy, the handler's `cancel` extension sends nothing.

The `expectReplyForCancelledRequest` flag selects between two opposite hang risks. With `true`, the cancelled handler is still allowed to run to completion and emit its reply; the calling side gets a normal response (possibly carrying the cancelled error). With `false`, the handler is interrupted as soon as the cancel arrives and no reply frame is sent; observers that were waiting for a transport-level response will hang until the transport closes. Pick `true` when the peer protocol guarantees a reply on cancellation; pick `false` only when callers are coded to expect silent cancellation.

> **Caution:** `expectReplyForCancelledRequest = false` causes observers (telemetry sinks, audit loggers, anything subscribed to outbound responses) to never see a frame for a cancelled request. The handler is gone; the wire is silent. Surface this in your protocol contract.

> **Note:** Library authors who layer protocol-specific cancel-params shapes on top (kyo-mcp, kyo-lsp) wire through the two type aliases `JsonRpcCancellationPolicy.ParamsEncoder` and `ParamsDecoder`. Both alias plain `Codec[A]`-shaped functions; you do not subclass the policy.

### Progress

When your protocol streams intermediate progress notifications, set a policy so the handler knows the method name to listen for and emit on. `JsonRpcProgressPolicy` covers the progress-notification method name, the token-extraction and stamping functions, the value-extraction function, and an `enforceMonotonic` flag. Wire it via `Config.progress`. Without a policy, server-side `ctx.progress(value)` is a no-op and client-side `Pending.progress` is empty.

> **Caution:** `enforceMonotonic = true` silently drops progress values whose `progress` field is not strictly non-decreasing. The caller never sees the dropped values. Default is `false`.

> **Note:** Library authors who layer protocol-specific progress-notification shapes wire through `JsonRpcProgressPolicy.field(v, name)` (project one field out of the policy's progress record) and `JsonRpcProgressPolicy.merge(left, right)` (combine two policies into one). Both are pure helpers for building custom policy values; you do not subclass `JsonRpcProgressPolicy`.

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

Two short-circuit paths emit a wire response without going through normal handler encoding: `JsonRpcMessageGate.Decision.Reject(response)` and `JsonRpcResponse.halt(response)` (from inside a route, via `Abort.fail(Halt(...))`). Both bypass the schema/mapping pipeline; the difference is where in the pipeline they fire. A gate `Reject` fires before any handler runs, which means the gate is the right place for cross-cutting policy (auth, rate limiting, handshake gating) where running the handler at all is wrong. A route `Halt` fires after the handler is selected and entered, which means the route is the right place for per-request decisions that need the decoded `In` value to choose the response.

### Per-call extras

When your protocol expects out-of-band fields on every outbound envelope (a session id, a trace token, an auth header field), supply an encoder so each `call` / `notify` stamps them automatically. `JsonRpcExtrasEncoder` is an opaque function type that receives the assigned request id and returns an optional `Structure.Value` map merged into the envelope's `extras` field.

```scala
val tagged: JsonRpcExtrasEncoder = JsonRpcExtrasEncoder.const(
    Structure.Value.Record(Chunk("sessionId" -> Structure.Value.Str("abc123")))
)
```

Factories: `JsonRpcExtrasEncoder.empty`, `JsonRpcExtrasEncoder.const(value)`, and `JsonRpcExtrasEncoder.apply(fn)`. Pass the result as the third argument to `call`, `notify`, or `sendUnmatched`.

## Errors

JSON-RPC errors fall into four pipeline stages; parse, dispatch, execution, application. Each stage marks where in the request lifecycle the error arose, and the framework's error hierarchy mirrors that shape so a `case _: JsonRpcParseFailure` arm catches everything that failed before routing, a `case _: JsonRpcDispatchFailure` arm catches everything that failed after routing but before the handler ran, and so on. `JsonRpcError` is the sealed root; it extends `KyoException` and carries `code`, `message`, `data: Maybe[Structure.Value]`, and an optional `cause`.

The shape is `sealed abstract class + 4 sealed-trait markers + 10 concrete leaves` rather than a flat `enum`. The marker traits give callers a stage-level discriminator that pattern matching can use as a wildcard arm without enumerating every leaf; the sealed class gives the engine a single `Schema[JsonRpcError]` that projects to the `(code, message, data)` wire triple no matter which leaf is raised; and the four marker layout matches the JSON-RPC 2.0 error-code partitioning, so adding a new leaf is a typed change that the compiler tracks through every matcher. A flat enum would force every matcher to list every leaf and would lose the stage-level abstraction.

### Operational stage traits

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

> **Caution:** the smart constructor throws `IllegalArgumentException` synchronously when `code` is outside `[-32099, -32000]`. The check runs at the call site, not via `Abort`. Wrap the construction in `Abort.catching[IllegalArgumentException]` if `code` is computed from untrusted input.

> **Note:** four execution leaves (`JsonRpcConfigurationError`, `JsonRpcLifecycleError`, `JsonRpcTransportError`, `JsonRpcHandlerPanicError`) share code `-32603`. Information about which subtype was raised does not survive a wire round-trip: `JsonRpcError.fromWire(-32603, ...)` always reconstructs a `JsonRpcInternalError(Operation.Other)`. If the peer needs to know the specific cause, encode it into the `data` field at the source side.

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

The relationship between `.error[E2]` route mappings and the error hierarchy: an `Abort.fail(e: E2)` matched by a registered mapping becomes a `JsonRpcCustomError(mapping.code, mapping.message, data = encode(e))` on the wire. If you want richer wire data, abort with a `JsonRpcApplicationError` subclass directly; the framework will encode its `code`, `message`, and `data` fields as-is.

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
- `JsonRpcResponse(id, result, error, extras)`: at most one of `result` and `error` is `Present` on a well-formed response.
- `JsonRpcNotification(method, params, extras)`: no id by definition.
- `JsonRpcMalformedMessage(id, reason, raw)`: a message that failed shape validation.

`JsonRpcResponse.success(id, result)` and `JsonRpcResponse.failure(id, error)` are the builders for the canonical response shapes.

A message with both `result` and `error` set is surfaced as `JsonRpcMalformedMessage`, not as a `JsonRpcResponse` whose `result` and `error` are both `Present`. The choice is deliberate. A `JsonRpcResponse` carrying both fields would be a wire-shape liar: every consumer would have to remember to check the "both set" case, and pattern matches that ignore the case would compile cleanly and quietly mishandle malformed traffic. Making the "both set" case a sibling envelope kind forces the four-arm match above, and routes all shape-violation handling through one codepath. The same logic covers non-object top-level values, non-object `error` fields, and method-less and id-less responses.

> **Note:** when a malformed message has `id: Present` matching a pending caller, that caller is failed with `InvalidRequest`. Otherwise the message is silently dropped at the framing layer with no surface to handler code; only id-bearing malformed messages reach the typed error path.

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

## Low-level API

For library authors building higher-level protocols on top of JSON-RPC (kyo-mcp, kyo-lsp, custom dialect adapters), three surfaces sit under the safe API.

### `JsonRpcHandler.Unsafe`

`JsonRpcHandler` is an opaque alias for `JsonRpcHandler.Unsafe`. Every safe extension method on the handler wraps a parallel `Unsafe` method that returns a `Fiber.Unsafe[...]` directly; the safe tier composes them through `Sync.Unsafe.defer(... .safe.get)`. Access the unsafe view via `handler.unsafe`:

```scala
val rawCall: Fiber.Unsafe[AddResp, Abort[JsonRpcError | Closed]] < (Async & Scope) =
    JsonRpcTransport.inMemory.map { (ta, _) =>
        JsonRpcHandler.init(ta).map { handler =>
            given AllowUnsafe = AllowUnsafe.embrace.danger
            handler.unsafe.call[AddReq, AddResp]("add", AddReq(1, 2), JsonRpcExtrasEncoder.empty)
        }
    }
```

The unsafe tier mirrors `call`, `notify`, `sendUnmatched`, `callWithProgress`, `callPartialResults`, `subscribeProgress`, `unsubscribeProgress`, `cancel`, `awaitDrain`, and `close`. It also exposes `dispatch(name, params, ctx)` for engine-level route invocation against the handler's registered method map.

> **Caution:** using `Unsafe` directly bypasses the `Sync.Unsafe.defer` barrier that the safe tier provides. The `Fiber.Unsafe` returned by `Unsafe.call` is already running; the caller is responsible for embedding it back into the effect graph via `.safe.get` or for handling its result through `Fiber.Unsafe` extensions.

### `applyMappingsAtBoundary`

Protocol-author modules that build *indirection routes* (a single wire-level route like MCP `tools/call` that internally fans out to user-registered tool handlers by name) cannot rely on the per-route `.error[E2]` mapping table, because the wire-level route does not own the user handlers' mappings. Those mappings live on a separate per-tool carrier inside the higher-level module. The framework exposes `JsonRpcRoute.applyMappingsAtBoundary` (`private[kyo]`, but documented for protocol authors) as the seam to bridge the two:

```scala
// Inside a kyo-mcp / kyo-lsp internal:
//   val toolName: String       = ...
//   val toolBody: Out < (Async & Abort[Any]) = invokeUserToolByName(toolName, params)
//   val toolMappings: Chunk[JsonRpcRoute.ErrorMapping[?]] = registry(toolName).mappings
//   JsonRpcRoute.applyMappingsAtBoundary(toolName, toolBody, toolMappings)
```

Without `applyMappingsAtBoundary`, a user `Abort.fail(MyError(...))` raised inside an indirection route falls through the wire-level route's empty mapping list and becomes `JsonRpcInternalError(-32603)` instead of the registered code.

### `JsonRpcRoute.Context.forTest`

`JsonRpcRoute.Context.forTest(cancelled, requestId, extras, progressSink)` is a `private[kyo]` constructor used by route-handler unit tests inside the kyo packages. It exposes the four `Context` fields directly so a test can pass a fresh `Fiber.Promise[Unit, Sync]`, an explicit `requestId`, ad-hoc `extras`, and a captured progress sink without spinning up a transport. External code cannot call it; protocol-author modules that live in the `kyo` package may.

## Cross-platform behavior

The shared API compiles and runs on JVM, JavaScript, and Scala Native. The cross-platform exceptions are:

| Surface | JVM | JS | Native |
|---------|-----|----|----|
| `JsonRpcHandler` and routes | yes | yes | yes |
| `JsonRpcTransport.inMemory` | yes | yes | yes |
| `JsonRpcTransport.stdio` | yes | yes | yes |
| `JsonRpcTransport.fromWire` + custom `JsonRpcWireTransport` | yes | yes | yes |
| `JsonRpcTransport.unixDomain` | yes | aborts | aborts |
| `JsonRpcTransport.contentLengthStdio` | yes | not on classpath | not on classpath |
| `JsonRpcHttpTransport.webSocket` (separate subproject) | yes | yes | yes |

On Scala.js and Scala Native, `unixDomain` aborts with an `UnsupportedOperationException`. Code that targets all three platforms must guard the call or pick a different transport per platform. `contentLengthStdio` is JVM-only at the source level (it lives under `jvm/src/main/scala`); cross-platform code that references it will fail to compile on JS and Native rather than aborting at runtime.

The WebSocket transport requires the `kyo-jsonrpc-http` subproject, which depends on `kyo-http`. It compiles for all three platforms.
