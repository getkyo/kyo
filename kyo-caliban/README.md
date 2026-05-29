# kyo-caliban

kyo-caliban serves a [Caliban](https://ghostdogpr.github.io/caliban/) GraphQL API over an `HttpServer` from kyo-http. You write Caliban schemas the normal way (case classes with `derives caliban.schema.Schema.SemiAuto`), but resolver field types can be Kyo computations: a `Query` field typed `Int < Async`, `String < Abort[Throwable]`, `Int < (Abort[Throwable] & Async)`, and so on. Two `given` instances in scope bridge those Kyo effects into Caliban's resolver machinery, so derivation works without any extra wiring for the common case.

For resolvers that require effects beyond `Abort[Throwable] & Async` (`Var`, `Env`, custom effects), you provide a `CalibanRunner[S]` that discharges those effects down to `Abort[Throwable] & Async` once per request. The server itself is a single call: `Resolvers.run(interpreter)` returns an `HttpServer` that handles POST/GET queries, SSE subscriptions, `@defer` multipart streaming, multipart uploads, GraphiQL, and a WebSocket endpoint speaking both `graphql-transport-ws` and the legacy `graphql-ws` subprotocols.

kyo-caliban is JVM-only because `caliban-core` is JVM-only.

```scala
import kyo.zioSchema
import kyo.runnerSchema
import caliban.ws.WebSocketHooks

case class Query(hello: String, delayed: Int < Async) derives caliban.schema.Schema.SemiAuto

val api = caliban.graphQL(caliban.RootResolver(Query(
    hello   = "world",
    delayed = Async.sleep(50.millis).andThen(42)
)), Nil, Nil, None)

val server: HttpServer < (Async & Scope & Abort[caliban.CalibanError]) =
    for
        interpreter <- Resolvers.get(api)
        s           <- Resolvers.run(interpreter, Resolvers.Config.default, WebSocketHooks.empty[Any, caliban.CalibanError])
    yield s
```

`delayed` is an `Int < Async` and `authed` is a `User < (Abort[AuthError] & Async)`; both derive a `Schema` without any extra imports. That bridge, from Kyo effect rows into Caliban resolvers, is what kyo-caliban adds on top of Caliban.

## Quick start

The minimal end-to-end is three things: a Caliban schema where resolver fields may be Kyo computations, an interpreter built from that schema, and a server that serves it.

### Define the schema

Argument types derive `ArgBuilder`; record types derive `caliban.schema.Schema.SemiAuto`. Resolver fields can be plain values, plain functions, or Kyo computations whose effect row fits the default schema bridge (see the next section).

```scala
import kyo.zioSchema
import kyo.runnerSchema
import caliban.schema.ArgBuilder

case class AddArgs(a: Int, b: Int) derives caliban.schema.Schema.SemiAuto, ArgBuilder
case class User(id: Int, name: String) derives caliban.schema.Schema.SemiAuto
case class AuthError(reason: String) extends Throwable(reason)

case class Query(
    hello: String,
    add: AddArgs => Int,
    delayed: Int < Async,
    authed: User < (Abort[AuthError] & Async)
) derives caliban.schema.Schema.SemiAuto
```

Mutations and subscriptions are ordinary Caliban shapes; only the resolver field types change.

```scala
import kyo.zioSchema
import kyo.runnerSchema
import caliban.schema.ArgBuilder

case class User(id: Int, name: String) derives caliban.schema.Schema.SemiAuto
case class NewUser(name: String) derives caliban.schema.Schema.SemiAuto, ArgBuilder
case class Mutation(createUser: NewUser => User) derives caliban.schema.Schema.SemiAuto
case class Subscriptions(
    ticks: zio.stream.ZStream[Any, Nothing, Int]
) derives caliban.schema.Schema.SemiAuto
```

### Build an interpreter

When the schema is ready, the next step is turning it into a `GraphQLInterpreter`. `Resolvers.get` lifts Caliban's `api.interpreter` (a `zio.IO`) into a Kyo effect.

```scala
import kyo.zioSchema
import kyo.runnerSchema
import caliban.schema.ArgBuilder

case class AddArgs(a: Int, b: Int) derives caliban.schema.Schema.SemiAuto, ArgBuilder
case class User(id: Int, name: String) derives caliban.schema.Schema.SemiAuto
case class AuthError(reason: String) extends Throwable(reason)
case class Query(
    hello: String,
    add: AddArgs => Int,
    delayed: Int < Async,
    authed: User < (Abort[AuthError] & Async)
) derives caliban.schema.Schema.SemiAuto

val api = caliban.graphQL(caliban.RootResolver(Query(
    hello   = "world",
    add     = args => args.a + args.b,
    delayed = Async.sleep(50.millis).andThen(42),
    authed  = User(1, "alice")
)), Nil, Nil, None)

val interpreter: caliban.GraphQLInterpreter[Any, caliban.CalibanError] < (Abort[caliban.CalibanError] & Async) =
    Resolvers.get(api)
```

The returned effect row is the `Resolvers` effect tag, an opaque alias for `Abort[caliban.CalibanError] & Async`.

### Serve it

With an interpreter in hand, a single call serves it as an HTTP endpoint. `Resolvers.run(interpreter)` returns an `HttpServer` whose lifecycle is tied to the surrounding `Scope`.

```scala
import kyo.zioSchema
import kyo.runnerSchema
import caliban.schema.ArgBuilder
import caliban.ws.WebSocketHooks

case class AddArgs(a: Int, b: Int) derives caliban.schema.Schema.SemiAuto, ArgBuilder
case class User(id: Int, name: String) derives caliban.schema.Schema.SemiAuto
case class AuthError(reason: String) extends Throwable(reason)
case class Query(
    hello: String,
    add: AddArgs => Int,
    delayed: Int < Async,
    authed: User < (Abort[AuthError] & Async)
) derives caliban.schema.Schema.SemiAuto

val api = caliban.graphQL(caliban.RootResolver(Query(
    hello   = "world",
    add     = args => args.a + args.b,
    delayed = Async.sleep(50.millis).andThen(42),
    authed  = User(1, "alice")
)), Nil, Nil, None)

val program: HttpServer < (Async & Scope & Abort[caliban.CalibanError]) =
    for
        interpreter <- Resolvers.get(api)
        server      <- Resolvers.run(interpreter, Resolvers.Config.default, WebSocketHooks.empty[Any, caliban.CalibanError])
    yield server
```

> **Note:** `Resolvers.run` returns `HttpServer < (Async & Scope)`. The server is bound to the surrounding `Scope` and shuts down when it exits. Wrap the whole program in `Scope.run { ... }` (or use the kyo-http server-runner pattern) so the scope outlives every request you intend to handle.

That is the complete server. The default `Config` serves on `/api/graphql`, enables GraphiQL at `/graphiql`, enables introspection, and rejects mutations over GET. Customize via `Resolvers.Config.default.<setter>(...)` (see "Server configuration" below).

## Kyo effects in resolvers

The central pitch is that resolver field types can be Kyo computations. Which effect rows are supported, and what you have to do to support richer ones, is the most important thing to understand about kyo-caliban.

### The default bridge: `Abort[Throwable] & Async`

When a resolver field's effect row is a subtype of `Abort[Throwable] & Async`, the default bridge handles derivation automatically. A `given zioSchema` in scope derives `Schema[R, A < S]` whenever `S <:< Abort[Throwable] & Async`. That covers the common cases: `Async`, `Abort[Throwable]`, `Abort[Throwable] & Async`, and `Sync` (which is a subtype of `Async`).

Looking back at the running `Query`:

```scala
import kyo.zioSchema
import kyo.runnerSchema
import caliban.schema.ArgBuilder

case class AddArgs(a: Int, b: Int) derives caliban.schema.Schema.SemiAuto, ArgBuilder
case class User(id: Int, name: String) derives caliban.schema.Schema.SemiAuto
case class AuthError(reason: String) extends Throwable(reason)

case class Query(
    hello: String,
    add: AddArgs => Int,
    delayed: Int < Async,                         // matches: Async <:< Abort[Throwable] & Async
    authed: User < (Abort[AuthError] & Async)     // matches: AuthError <: Throwable
) derives caliban.schema.Schema.SemiAuto
```

All four fields derive without any further wiring. The bridge runs each Kyo computation via `ZIOs.run`, which discharges `Async` to a `zio.IO` and surfaces any failure as a Caliban resolver error.

> **Caution:** The constraint is `<:<`, not "any effect row." A field typed `Int < (Async & Var[Int])` does **not** match `zioSchema` and will fail derivation with a missing-`Schema` error unless a `CalibanRunner[S]` is in scope (see below). This is the dominant kyo-caliban footgun.

### When you need more: `CalibanRunner[S]`

When a resolver needs effects the default bridge cannot discharge (`Var`, `Env`, custom user effects), the bridge cannot guess how to peel them off. You provide a `CalibanRunner[S]` that discharges the extra effects per resolver invocation.

```scala
import kyo.zioSchema
import kyo.runnerSchema
trait CalibanRunner[S]:
    def apply[A](v: A < S): A < (Abort[Throwable] & Async)
```

A `CalibanRunner` is the contract "given any computation in `S`, run it down to `Abort[Throwable] & Async`." Implement it for whichever effect row your resolvers carry.

Suppose `authed` needs more than `Abort[AuthError] & Async`: it needs a database handle from `Env` and a per-request id from `Var`. Extend the running `Query` with a new field whose effect row exceeds the default bridge:

```scala
import kyo.zioSchema
import kyo.runnerSchema
import caliban.schema.{ArgBuilder, SchemaDerivation}

case class AddArgs(a: Int, b: Int) derives caliban.schema.Schema.SemiAuto, ArgBuilder
case class User(id: Int, name: String) derives caliban.schema.Schema.SemiAuto
case class AuthError(reason: String) extends Throwable(reason)
case class Database(currentUser: User)
case class RequestId(value: Long)
object RequestId:
    def fresh: RequestId = RequestId(0L)

object schema extends SchemaDerivation[CalibanRunner[Env[Database] & Var[RequestId] & Abort[AuthError] & Async]]

type AppEnv = Env[Database] & Var[RequestId] & Abort[AuthError] & Async

case class Query(
    hello: String,
    add: AddArgs => Int,
    delayed: Int < Async,
    authed: User < (Abort[AuthError] & Async),
    currentUser: User < AppEnv                    // new: needs a CalibanRunner
) derives schema.SemiAuto

val database = Database(User(1, "alice"))
val runner: CalibanRunner[AppEnv] = new CalibanRunner[AppEnv]:
    def apply[A](v: A < AppEnv): A < (Abort[Throwable] & Async) =
        Var.run(RequestId.fresh)(Env.run(database)(v))
```

The schema side also needs to know about the `CalibanRunner`. A second `given runnerSchema` derives `Schema[R & CalibanRunner[S], A < S]` for arbitrary `S` by deferring to a `CalibanRunner[S]` provided as a ZIO service. The usual recipe is to ground your schema derivation on `SchemaDerivation[CalibanRunner[AppEnv]]`:

```scala
import kyo.zioSchema
import kyo.runnerSchema
import caliban.schema.SchemaDerivation

type AppEnv = Env[Any] & Async
object schema extends SchemaDerivation[CalibanRunner[AppEnv]]
```

Note `derives schema.SemiAuto` on the extended `Query`, not `derives caliban.schema.Schema.SemiAuto`: the resolver row needs the `CalibanRunner[AppEnv]` requirement, which only the `schema` object's derivation carries. The earlier fields (`hello`, `add`, `delayed`, `authed`) keep deriving the same way; the `CalibanRunner`-based derivation is a strict superset of `zioSchema`.

### Serving a `CalibanRunner`-parameterized schema

Once the schema needs a `CalibanRunner`, the no-`CalibanRunner` overload of `Resolvers.run` no longer fits. `Resolvers.run` has an overload that accepts a `CalibanRunner[R]` and injects it as a ZIO service for `runnerSchema` to find at execution time.

```scala
import kyo.zioSchema
import kyo.runnerSchema
import caliban.schema.{ArgBuilder, SchemaDerivation}

case class AddArgs(a: Int, b: Int) derives caliban.schema.Schema.SemiAuto, ArgBuilder
case class User(id: Int, name: String) derives caliban.schema.Schema.SemiAuto
case class AuthError(reason: String) extends Throwable(reason)
case class Database(currentUser: User)
case class RequestId(value: Long)
object RequestId:
    def fresh: RequestId = RequestId(0L)

type AppEnv = Env[Database] & Var[RequestId] & Abort[AuthError] & Async
object schema extends SchemaDerivation[CalibanRunner[AppEnv]]

case class Query(
    hello: String,
    add: AddArgs => Int,
    delayed: Int < Async,
    authed: User < (Abort[AuthError] & Async),
    currentUser: User < AppEnv
) derives schema.SemiAuto

val database = Database(User(1, "alice"))
val runner: CalibanRunner[AppEnv] = new CalibanRunner[AppEnv]:
    def apply[A](v: A < AppEnv): A < (Abort[Throwable] & Async) =
        Var.run(RequestId.fresh)(Env.run(database)(v))

val api = caliban.graphQL(caliban.RootResolver(Query(
    hello       = "world",
    add         = args => args.a + args.b,
    delayed     = 42,
    authed      = User(1, "alice"),
    currentUser = Env.use[Database](_.currentUser)
)), Nil, Nil, None)

val program: HttpServer < (Async & Scope & Abort[caliban.CalibanError]) =
    for
        interpreter <- Resolvers.get(api)
        server      <- Resolvers.run(
            interpreter,
            runner,
            Resolvers.Config.default.path("graphql")
        )
    yield server
```

When you call `Resolvers.run(interpreter)` (no-`CalibanRunner` overload) on a schema whose resolvers require `CalibanRunner[S]`, you will get a compile error. When you call it on a schema whose resolvers exceed `Abort[Throwable] & Async` but don't use `CalibanRunner` derivation, you will get a missing-`Schema` derivation error. Both diagnostics point at the same fix: switch to the `CalibanRunner` overload AND base derivation on `SchemaDerivation[CalibanRunner[S]]`.

> **Unlike** the no-`CalibanRunner` overload, which assumes resolver effects fit the default bridge, the `CalibanRunner` overload threads a single `CalibanRunner[R]` instance through every resolver invocation. The `CalibanRunner` is constructed once and runs per-resolver; if you need per-request state (request IDs, auth context), build it inside the `CalibanRunner.apply` body.

## Server configuration

Once Quick start works, the HTTP-layer behavior is controlled by `Resolvers.Config`. Build one by chaining setters off `Config.default`; the constructor is private.

```scala
import kyo.zioSchema
import kyo.runnerSchema
val config = Resolvers.Config.default
    .path("graphql")
    .graphiql(false)
    .enableIntrospection(false)
    .skipValidation(false)
    .allowMutationsOverGetRequests(false)
    .webSocketKeepAlive(30.seconds)
```

The defaults are:

| Setting | Default | Notes |
| --- | --- | --- |
| `path` | `"api/graphql"` | The POST/GET endpoint. SSE/defer/upload/ws derive from this. |
| `filter` | `HttpFilter.noop` | A passthrough `HttpFilter` applied to every kyo-caliban route. |
| `graphiql` | `true` | Serves GraphiQL HTML at `/graphiql`. |
| `enableIntrospection` | `true` | Allows `__schema` / `__type` introspection queries. |
| `skipValidation` | `false` | Skips Caliban's query validation pass. |
| `queryExecution` | `QueryExecution.Parallel` | Caliban execution strategy. |
| `allowMutationsOverGetRequests` | `false` | Reject mutations on the GET endpoint. |
| `webSocketKeepAlive` | `Absent` | Optional server-side ping interval for WS subscriptions. |

> **Caution:** Production deployments usually want `graphiql(false)` and `enableIntrospection(false)`. The defaults favor development.

### Mounting under a custom path

To serve queries under a route other than the default `/api/graphql`, set `path`. It is a prefix: with `config.path("graphql")`, queries go to `POST /graphql`, SSE to `POST /graphql/sse`, `@defer` to `POST /graphql/defer`, uploads to `POST /graphql/upload`, and the WebSocket to `/graphql/ws`. GraphiQL stays at the literal `/graphiql`, not under the configured path.

```scala
import kyo.zioSchema
import kyo.runnerSchema
val config = Resolvers.Config.default.path("graphql")
```

### Wrapping every route with a filter

For cross-cutting concerns (CORS, logging, response headers, auth gates that apply to every kyo-caliban request), use `filter`. It is a kyo-http `HttpFilter.Passthrough[Nothing]` applied to every kyo-caliban route.

```scala
import kyo.zioSchema
import kyo.runnerSchema
import caliban.schema.ArgBuilder
import caliban.ws.WebSocketHooks

case class AddArgs(a: Int, b: Int) derives caliban.schema.Schema.SemiAuto, ArgBuilder
case class User(id: Int, name: String) derives caliban.schema.Schema.SemiAuto
case class AuthError(reason: String) extends Throwable(reason)
case class Query(
    hello: String,
    add: AddArgs => Int,
    delayed: Int < Async,
    authed: User < (Abort[AuthError] & Async)
) derives caliban.schema.Schema.SemiAuto

val filter = new HttpFilter.Passthrough[Nothing]:
    def apply[In, Out, E](
        request: HttpRequest[In],
        next: HttpRequest[In] => HttpResponse[Out] < (Async & Abort[E | HttpResponse.Halt])
    )(using Frame): HttpResponse[Out] < (Async & Abort[E | HttpResponse.Halt]) =
        next(request).map(_.setHeader("X-Custom", "test-value"))

val server: HttpServer < (Async & Scope & Abort[caliban.CalibanError]) =
    for
        interpreter <- Resolvers.get(caliban.graphQL(caliban.RootResolver(Query(
            hello   = "world",
            add     = args => args.a + args.b,
            delayed = 42,
            authed  = User(1, "alice")
        )), Nil, Nil, None))
        s <- Resolvers.run(interpreter, Resolvers.Config.default.filter(filter), WebSocketHooks.empty[Any, caliban.CalibanError])
    yield s
```

### Turning off introspection in production

To prevent clients from discovering the schema shape (a common production hardening step), set `enableIntrospection(false)`. The `__schema` and `__type` queries are rejected with `Introspection is disabled`.

```scala
import kyo.zioSchema
import kyo.runnerSchema
val config = Resolvers.Config.default.enableIntrospection(false)
```

Related execution knobs: `skipValidation(true)` bypasses Caliban's query validation pass, `queryExecution(QueryExecution.Sequential)` resolves fields in declaration order rather than in parallel, and `allowMutationsOverGetRequests(true)` accepts `mutation { ... }` on the GET endpoint. All four translate to a Caliban `ExecutionConfiguration` that wraps every interpreter call.

### Disabling GraphiQL

For deployments that should not expose the GraphiQL playground, set `graphiql(false)`. The `/graphiql` route is omitted entirely; requests against it return 404.

```scala
import kyo.zioSchema
import kyo.runnerSchema
val config = Resolvers.Config.default.graphiql(false)
```

### Keeping WebSocket subscriptions alive

When idle WebSocket subscriptions risk being dropped by intermediaries (load balancers, proxies), use `webSocketKeepAlive(d)` to set a server-side ping interval. With `Absent` (the default), the server does not send unsolicited pings; clients can still send `ping` and receive `pong`.

```scala
import kyo.zioSchema
import kyo.runnerSchema
val config = Resolvers.Config.default.webSocketKeepAlive(30.seconds)
```

## Wire-protocol surface

Once `Resolvers.run` is called, the server speaks a fixed set of endpoints. You rarely interact with them directly (Caliban clients, GraphiQL, and `graphql-ws` libraries do), but knowing what is there helps when debugging.

### POST and GET queries

The primary endpoint at `${config.path}` accepts:

- `POST` with `Content-Type: application/json` and body `{"query":"...","variables":{...},"operationName":"...","extensions":{...}}` for queries and mutations.
- `POST` with `Content-Type: application/graphql` and the raw query as the body.
- `GET` with `?query=...&variables=...&operationName=...&extensions=...` (URL-encoded) for queries (and mutations if `allowMutationsOverGetRequests(true)`).

The response content type and status depend on the request's `Accept` header. `Accept: application/json` (the default) returns `200` with errors in the body. `Accept: application/graphql-response+json` returns `400` on `ParsingError` / `ValidationError` and on a mutation-over-GET rejection.

> **Note:** Malformed JSON bodies, empty bodies, and missing `operations` parts on uploads do **not** crash the connection. They are decoded into a structured GraphQL error envelope and returned with HTTP status 200 (or 400 if `Accept: application/graphql-response+json`). The TCP connection stays open.

### SSE subscriptions

For clients that consume subscriptions over plain HTTP (no WebSocket), the server speaks server-sent events at `POST ${config.path}/sse`. Each subscription emission is a `next` event with the JSON payload; completion is a final `event: complete` with an empty data line.

```
event: next
data: {"data":{"ticks":1}}

event: next
data: {"data":{"ticks":2}}

event: complete
data:
```

One-shot queries (non-subscription operations) over SSE produce a single `next` followed by `complete`.

### `@defer` multipart streaming

To stream deferred fragments as they resolve (rather than waiting for the full response), the server returns a `multipart/mixed` response at `POST ${config.path}/defer`: the first part carries the initial query data; subsequent parts carry deferred fragments as they resolve.

```scala
import kyo.zioSchema
import kyo.runnerSchema
import caliban.schema.ArgBuilder

case class AddArgs(a: Int, b: Int) derives caliban.schema.Schema.SemiAuto, ArgBuilder
case class User(id: Int, name: String) derives caliban.schema.Schema.SemiAuto
case class AuthError(reason: String) extends Throwable(reason)
case class Query(
    hello: String,
    add: AddArgs => Int,
    delayed: Int < Async,
    authed: User < (Abort[AuthError] & Async)
) derives caliban.schema.Schema.SemiAuto

val api =
    caliban.graphQL(caliban.RootResolver(Query(
        hello   = "world",
        add     = args => args.a + args.b,
        delayed = 42,
        authed  = User(1, "alice")
    )), Nil, Nil, None) @@ caliban.wrappers.IncrementalDelivery.defer
```

> **Caution:** `@defer` requires the API to be wrapped with `caliban.wrappers.IncrementalDelivery.defer`. Without that wrapper, `... @defer { ... }` fragments execute eagerly and the multipart response contains a single part.

### Multipart uploads

For clients that need to send files alongside a GraphQL query, the server accepts the [GraphQL multipart request](https://github.com/jaydenseric/graphql-multipart-request-spec) format at `POST ${config.path}/upload`: a multipart body with `operations` (the GraphQL request JSON), `map` (a path map for file inputs), and one part per file.

The handler installs Caliban's `Uploads` ZLayer so resolvers calling `Upload.allBytes` / `Upload.meta` see the actual file map.

> **Caution:** kyo-caliban's schema derivation does not currently support resolvers whose `R` includes `caliban.uploads.Uploads`. The multipart parser and handler dispatch work (the test suite exercises them with a non-`Upload`-aware schema), but a `Upload`-typed argument is not yet end-to-end expressible from Kyo resolver code. Use a Caliban-side resolver if you need this today.

### WebSocket subscriptions

For long-lived bidirectional subscriptions (the most common production setup), the server speaks `graphql-transport-ws` and the legacy `graphql-ws` subprotocols at `${config.path}/ws`. The handshake picks the first subprotocol the client offers in `Sec-WebSocket-Protocol` that the server supports; if the client offers none, the server defaults to legacy `graphql-ws`.

Both subprotocols support the full subscription lifecycle: `connection_init` / `connection_ack`, `subscribe` (or legacy `start`) carrying a query/mutation/subscription, streaming `next` events, `complete`, ping/pong keep-alive, and graceful close.

### GraphiQL

When `config.graphiql == true` (the default), `GET /graphiql` returns the GraphiQL HTML, pre-pointed at `${config.path}`. When `graphiql == false`, the route is omitted and `/graphiql` returns 404.

## WebSocket lifecycle

For finer control over the subscription handshake (auth, custom ack payloads, ping interception, outbound message transformation), pass a `caliban.ws.WebSocketHooks` as the trailing argument to `Resolvers.run`.

```scala
import kyo.zioSchema
import kyo.runnerSchema
import caliban.ws.WebSocketHooks
```

### Gating a connection on an auth token: `beforeInit`

To gate a connection on an auth token before any subscription can start, intercept `connection_init`. The same `authed` field from the running `Query` only makes sense for an authenticated client; `beforeInit` is where you reject anonymous connections so `authed` never runs for them.

`beforeInit` runs when the server receives `connection_init`. It can inspect the payload (typically an auth token) and either succeed (allowing the connection) or fail (closing with code 4403).

```scala
import kyo.zioSchema
import kyo.runnerSchema
import caliban.schema.ArgBuilder
import caliban.ws.WebSocketHooks

case class AddArgs(a: Int, b: Int) derives caliban.schema.Schema.SemiAuto, ArgBuilder
case class User(id: Int, name: String) derives caliban.schema.Schema.SemiAuto
case class AuthError(reason: String) extends Throwable(reason)
case class Query(
    hello: String,
    add: AddArgs => Int,
    delayed: Int < Async,
    authed: User < (Abort[AuthError] & Async)
) derives caliban.schema.Schema.SemiAuto

val hooks = WebSocketHooks.init[Any, caliban.CalibanError] { payload =>
    payload match
        case caliban.InputValue.ObjectValue(fields)
            if fields.get("token").contains(caliban.Value.StringValue("ok")) =>
            zio.ZIO.unit
        case _ =>
            zio.ZIO.fail(caliban.CalibanError.ExecutionError("invalid token"))
}

val server: HttpServer < (Async & Scope & Abort[caliban.CalibanError]) =
    for
        interpreter <- Resolvers.get(caliban.graphQL(caliban.RootResolver(Query(
            hello   = "world",
            add     = args => args.a + args.b,
            delayed = 42,
            authed  = User(1, "alice")
        )), Nil, Nil, None))
        s <- Resolvers.run(interpreter, Resolvers.Config.default, hooks)
    yield s
```

Failed `beforeInit` closes the WebSocket with code `4403` (`graphql-transport-ws`).

### Returning a session payload to the client: `onAck`

To return a session payload to the client after a successful connection (server version, feature flags, granted scopes), provide an `onAck` payload. It is attached to `connection_ack` so the client receives server metadata at connection time.

```scala
import kyo.zioSchema
import kyo.runnerSchema
import caliban.ws.WebSocketHooks

val hooks = WebSocketHooks.ack[Any, caliban.CalibanError](
    zio.ZIO.succeed(caliban.ResponseValue.ObjectValue(
        List("serverVersion" -> caliban.Value.StringValue("1.0.0"))
    ))
)
```

If `onAck` fails, caliban falls back to acking with no payload (the connection still succeeds).

### Re-checking the auth context after init: `afterInit`

To enforce that an authenticated context built by `beforeInit` is still valid (token not expired, scope still granted), run a check immediately after `connection_ack`. A failure here closes the connection with code `4401`.

```scala
import kyo.zioSchema
import kyo.runnerSchema
import caliban.ws.WebSocketHooks

val hooks = WebSocketHooks.afterInit[Any, caliban.CalibanError](
    zio.ZIO.fail(caliban.CalibanError.ExecutionError("auth expired"))
)
```

### Customizing pong and outbound frames: `onPing` / `onMessage`

To attach server timing data to pong frames or transform every outbound subscription message (tracing IDs, redactions, envelopes), use `onPing` and `onMessage`. `onPing` lets you customize the pong response; `onMessage` runs the outbound subscription stream through a `ZPipeline`.

```scala
import kyo.zioSchema
import kyo.runnerSchema
import caliban.ws.WebSocketHooks
import caliban.GraphQLWSOutput

val hooks = new WebSocketHooks[Any, caliban.CalibanError]:
    override def onMessage: Option[zio.stream.ZPipeline[Any, caliban.CalibanError, GraphQLWSOutput, GraphQLWSOutput]] =
        Some(zio.stream.ZPipeline.map(out => out.copy(payload = out.payload)))
```

`onMessage` applies to every output frame on both subprotocols (transport-ws and legacy).

## Putting it together

The snippet below brings together the full schema from the rest of this README: all four resolver fields (plain value, function argument, async computation, and an effect-raising computation), a `caliban.RootResolver`, and both the default program and a custom-config variant.

```scala
import kyo.zioSchema
import kyo.runnerSchema
import caliban.schema.ArgBuilder
import caliban.ws.WebSocketHooks

case class AddArgs(a: Int, b: Int) derives caliban.schema.Schema.SemiAuto, ArgBuilder
case class User(id: Int, name: String) derives caliban.schema.Schema.SemiAuto
case class AuthError(reason: String) extends Throwable(reason)

case class Query(
    hello: String,
    add: AddArgs => Int,
    delayed: Int < Async,
    authed: User < (Abort[AuthError] & Async)
) derives caliban.schema.Schema.SemiAuto

val root = caliban.RootResolver(Query(
    hello   = "world",
    add     = args => args.a + args.b,
    delayed = Async.sleep(50.millis).andThen(42),
    authed  = User(1, "alice")
))

// Default config: serves on /api/graphql with GraphiQL enabled.
val program: HttpServer < (Async & Scope & Abort[caliban.CalibanError]) =
    for
        interpreter <- Resolvers.get(caliban.graphQL(root, Nil, Nil, None))
        server      <- Resolvers.run(interpreter, Resolvers.Config.default, WebSocketHooks.empty[Any, caliban.CalibanError])
    yield server

// Custom config: different path, GraphiQL off, introspection off.
val custom: HttpServer < (Async & Scope & Abort[caliban.CalibanError]) =
    for
        interpreter <- Resolvers.get(caliban.graphQL(root, Nil, Nil, None))
        server      <- Resolvers.run(
            interpreter,
            Resolvers.Config.default
                .path("graphql")
                .graphiql(false)
                .enableIntrospection(false),
            WebSocketHooks.empty[Any, caliban.CalibanError]
        )
    yield server
```

## Effect interop

`Resolvers` is an opaque effect tag (an alias for `Abort[caliban.CalibanError] & Async`), so it composes with other Kyo effects wherever `Abort` and `Async` do. Parallel zips, scoped runs, and custom effect handlers all work over the `Resolvers` row without extra ceremony.
