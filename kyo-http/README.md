<!-- doctest:setup
```scala
import kyo.*
import kyo.HttpPath.*

case class User(id: Int, name: String) derives Schema
case class CreateUser(name: String, email: String) derives Schema
case class Post(id: Int, title: String) derives Schema
case class StockPrice(symbol: String, price: Double) derives Schema
case class Tick(count: Int, timestamp: Long) derives Schema
case class NotFound(message: String) derives Schema
case class Forbidden(reason: String) derives Schema
case class Resource(id: Int) derives Schema
case class Data(value: String) derives Schema
case class Credentials(username: String, password: String) derives Schema
case class ApiError(message: String) derives Schema
case class ChatMessage(user: String, text: String) derives Schema

def lookupUser(id: Int): Maybe[User] < Sync              = Present(User(id, "Alice"))
def checkPermission(req: HttpRequest[?]): Boolean < Sync = true
def findResource(id: Int): Maybe[Resource] < Sync        = Present(Resource(id))
def authenticate(creds: Credentials): User < Sync        = User(1, creds.username)
def isAuthorized(req: HttpRequest[?]): Boolean           = true
def getData(): String                                    = "data"
def validateToken(token: String): Boolean < Sync         = true

val handler1 = HttpHandler.getText("health1") { _ => "ok" }
val handler2 = HttpHandler.getText("health2") { _ => "ok" }
```
-->

# kyo-http

Kyo's HTTP/1.1 client and server module. Both client and server share a single API that compiles across JVM, JavaScript, and Scala Native, with platform-specific backends handling the actual I/O:

| Platform | I/O backend |
|----------|-------------|
| JVM | Java NIO selectors |
| JS | Node.js [`net`](https://nodejs.org/api/net.html) and [`tls`](https://nodejs.org/api/tls.html) |
| Native | Direct [`epoll`](https://man7.org/linux/man-pages/man7/epoll.7.html) (Linux) and [`kqueue`](https://www.freebsd.org/cgi/man.cgi?kqueue) (macOS) |

The library handles JSON, text, and binary content types with automatic serialization, supports streaming via SSE and NDJSON, includes composable middleware (filters), and supports OpenAPI in both directions: generating specs from routes and generating typed routes from specs at compile time. On the client side, it manages connection pooling, retries, and redirect following.

## Getting Started

Add the dependency to your `build.sbt`:

```scala doctest:expect=skipped
libraryDependencies += "io.getkyo" %% "kyo-http" % "<latest version>"
```

## Making Requests (the client)

### Making Requests

The most common starting point is `HttpClient`. For example, fetching a page as text:

```scala
import kyo.*

val html = HttpClient.getText("https://example.com")
```

Text and binary methods (`getText`, `postText`, `putText`, `patchText`, `getBinary`, `postBinary`, `putBinary`, `patchBinary`, etc.) work with `String` and `Span[Byte]` directly, no setup needed.

For JSON, the library needs to know how to serialize and deserialize your types. This is provided by a `Schema` instance, which can be derived automatically for case classes and sealed types:

```scala
val user = HttpClient.getJson[User]("https://api.example.com/users/1")
```

For requests with a body, you specify the response type and the body type is inferred:

```scala
// User is the response type, CreateUser is inferred from the body argument
val created =
    HttpClient.postJson[User](
        "https://api.example.com/users",
        CreateUser("Alice", "alice@example.com")
    )
```

The same pattern applies to `putJson`, `patchJson`, and `deleteJson`.

### Side-Effect Operations

For requests where the response body is irrelevant (container start/stop, resource deletion), use the unit methods:

```scala
HttpClient.postUnit("https://api.example.com/containers/abc/start")
HttpClient.deleteUnit("https://api.example.com/containers/abc")
```

`postUnit`, `putUnit`, `patchUnit`, and `deleteUnit` discard the response body and return `Unit`. They fail with `HttpStatusException` on non-2xx like all body-only methods.

### Error Handling

Body-only methods (`getText`, `getJson`, `getBinary`, etc.) fail with `HttpStatusException` when the server returns a non-2xx status code:

```scala
// Throws HttpStatusException(404). The error body is not returned.
val user = HttpClient.getJson[User]("https://api.example.com/users/999")
```

To inspect non-2xx responses (status, headers, body), use `*Response` methods with `failOnError = false`:

```scala
val response = HttpClient.getTextResponse(
    "https://api.example.com/users/999",
    failOnError = false
)
// response.status.code == 404
// response.fields.body contains the error text
```

By default, `*Response` methods also fail on non-2xx. Pass `failOnError = false` to opt out and handle status codes manually.

### Streaming

SSE and NDJSON responses are returned as a `Stream` that emits values as they arrive from the server:

```scala
// Consumes an SSE endpoint, parsing each event's data as StockPrice
val events = HttpClient.getSseJson[StockPrice]("https://api.example.com/prices")

// Consumes an NDJSON endpoint, parsing each line as StockPrice
val items = HttpClient.getNdJson[StockPrice]("https://api.example.com/stream")
```

`getSseText` is also available for SSE streams with plain text data.

Both return a `Stream` directly. The connection is established and data is parsed when the stream is consumed:

```scala
// Emits HttpSseEvent[StockPrice] values, performs Async IO and can fail with HttpException
val events: Stream[HttpSseEvent[StockPrice], Async & Abort[HttpException]] =
    HttpClient.getSseJson[StockPrice]("https://api.example.com/prices")

// Emits StockPrice values, performs Async IO and can fail with HttpException
val items: Stream[StockPrice, Async & Abort[HttpException]] =
    HttpClient.getNdJson[StockPrice]("https://api.example.com/stream")
```

#### Byte Streams

For raw byte streaming (chunked downloads, log tailing, etc.), `getStreamBytes` and `postStreamBytes` stream the response body without any framing:

```scala
val chunks: Stream[Span[Byte], Async & Abort[HttpException]] =
    HttpClient.getStreamBytes("https://example.com/large-file.bin")
```

### Configuration

Configuration applies to all client calls within a block through `HttpClient.withConfig`. This is useful for setting a common base URL, timeouts, or retry behavior across multiple requests:

```scala
HttpClient.withConfig(
    _.baseUrl("https://api.example.com")
        .timeout(10.seconds)
        .connectTimeout(3.seconds)
) {
    for
        // Requests within this block use the base URL and timeouts above
        users <- HttpClient.getJson[List[User]]("/users")
        posts <- HttpClient.getJson[List[Post]]("/posts")
    yield (users, posts)
}
```

With a base URL set, requests can use relative paths instead of repeating the full URL.

`withConfig(f)` (function form) stacks onto the current config; `withConfig(config)` (value form) replaces it entirely. Same name, opposite behavior.

Redirect following is enabled by default (up to 10 hops) and can be disabled with `.followRedirects(false)`.

### Retries

Retries are configured with a `Schedule` that controls the delay between attempts and a predicate that decides which responses to retry:

```scala
HttpClient.withConfig(
    _.retry(Schedule.exponentialBackoff(500.millis, 2.0, 30.seconds).take(3))
        .retryOn(status => status.isServerError || status.code == 429)
) {
    HttpClient.getJson[User]("/users/1")
}
```

By default, `retryOn` matches server errors (5xx). Retries are only active when a schedule is provided.

### Custom Instances

The `HttpClient` methods use a default client backed by a thread-safe connection pool shared across all fibers. The pool limits concurrent connections per host (100 by default) to prevent overwhelming any single server. When you need to tune that limit or want an isolated pool, create a client instance with `HttpClient.init` and scope it with `HttpClient.let`:

```scala
for
    client <- HttpClient.init(
        maxConnectionsPerHost = 50,
        idleConnectionTimeout = 30.seconds
    )

    result <- HttpClient.let(client) {
        // All computations in this block use the custom client
        HttpClient.getJson[User]("/users/1")
    }
yield result
end for
```

`HttpClient.init` returns a scoped client that closes when the enclosing `Scope` ends. `HttpClient.initUnscoped` is also available for manual lifecycle management.

The default client is process-global and created lazily on first use (100 idle connections per host, 60s idle timeout). Calls that need an isolated pool must use `HttpClient.init`; there is no implicit per-service client.

## Serving Requests (the server)

### Handling Requests

The simplest way to define a server endpoint is with `HttpHandler`'s convenience methods. These combine route definition and request handling in a single call, taking care of content types and status codes automatically:

```scala
// Handles GET /users, returns a User as JSON
val getUsers =
    HttpHandler.getJson("users") { req =>
        User(1, "Alice")
    }

// Handles POST /users, parses a CreateUser body and returns a User as JSON
val createUser =
    HttpHandler.postJson[CreateUser]("users") { (req, body) =>
        User(2, body.name)
    }
```

For GET/DELETE, both the path and response type are inferred from the handler function. For POST/PUT/PATCH, you specify the body type and the response type is inferred. The same pattern applies to text (`getText`, `postText`, `putText`, `patchText`) and binary (`getBinary`, `postBinary`, `putBinary`, `patchBinary`) content types.

`HttpHandler.health()` adds a `GET /health` endpoint that returns `"healthy"`.

`HttpServer.init` binds one or more handlers to a port:

```scala doctest:scope=inherited
val getUsers =
    HttpHandler.getJson("users") { req =>
        User(1, "Alice")
    }

val createUser =
    HttpHandler.postJson[CreateUser]("users") { (req, body) =>
        User(2, body.name)
    }

// By default, the server binds to 127.0.0.1 (localhost only) on port 0.
// Port 0 tells the OS to assign a random available port, which is useful for
// tests and ephemeral services. The actual port is available via server.port.
// To expose the server on all interfaces, set .host("0.0.0.0") explicitly.
val server = HttpServer.init(getUsers, createUser, HttpHandler.health())
```

To bind to a specific host and port, pass an `HttpServerConfig`:

```scala doctest:scope=inherited
val server2 = HttpServer.init(
    HttpServerConfig.default
        .port(8080)      // fixed port instead of OS-assigned
        .host("0.0.0.0") // bind to all interfaces; the default is 127.0.0.1 (localhost only)
)(getUsers, createUser, HttpHandler.health())
```

Additional config options include `backlog`, `tcpFastOpen`, `flushConsolidationLimit`, and `strictCookieParsing`.

`init` returns an `HttpServer < (Async & Scope)`. The `Scope` effect means the server shuts down automatically when the enclosing scope closes.

### Streaming

Streaming handlers return a `Stream` that the server writes to the response as values are produced. The connection stays open until the stream completes.

#### Server-Sent Events

```scala
val handler =
    HttpHandler.getSseJson[Tick]("ticks") { req =>
        Stream.init(Seq(1, 2, 3)).map { n =>
            Clock.now.map(now => HttpSseEvent(data = Tick(n, now.toDuration.toMillis)))
        }
    }
```

`getSseText` works the same way with `HttpSseEvent[String]` instead of a typed value. `HttpSseEvent` also supports optional fields for the SSE protocol: `event` (event type name), `id` (event ID for reconnection), and `retry` (reconnection delay).

#### NDJSON

```scala
val handler =
    HttpHandler.getNdJson[Tick]("stream") { req =>
        Stream.init(Seq(Tick(1, 0L), Tick(2, 1L), Tick(3, 2L)))
    }
```

The convenience handlers above cover the most common case of response-only streaming. For full control, routes support streaming in both directions and in combination. Request bodies can be streamed with `.bodyStream` (raw bytes), `.bodyNdjson[V]` (newline-delimited JSON), and `.bodyMultipartStream` (multipart parts). Response bodies support `.bodyStream`, `.bodyNdjson[V]`, `.bodySseJson[V]`, and `.bodySseText`. A single route can combine both, for example accepting an NDJSON request body and responding with an SSE stream. See [Describing Endpoints with Routes](#describing-endpoints-with-routes) for details.

### Configuration

`HttpServerConfig` controls binding options like port, host, and content limits:

```scala
val handler =
    HttpHandler.getJson("users") { req =>
        User(1, "Alice")
    }

val server = HttpServer.init(
    HttpServerConfig.default
        .port(8080)
        .host("0.0.0.0")
        .maxContentLength(1024 * 1024) // 1MB
        .keepAlive(true)
)(handler)
```

When port is 0 (the default), the OS assigns an available port. For manual lifecycle control, `HttpServer.initUnscoped` returns a server that must be closed explicitly.

### TLS

Both `HttpServerConfig` and `HttpClientConfig` accept an `HttpTlsConfig` via `.tls(...)`. On the server, point `certChainPath` and `privateKeyPath` at PEM files for TLS termination, set `clientAuth` to require or accept client certificates (mutual TLS), and constrain the negotiated version with `minVersion` / `maxVersion`:

```scala
val server = HttpServer.init(
    HttpServerConfig.default
        .port(8443)
        .tls(
            HttpTlsConfig(
                certChainPath = Present("server-cert.pem"),
                privateKeyPath = Present("server-key.pem"),
                clientAuth = HttpTlsConfig.ClientAuth.Required,
                minVersion = HttpTlsConfig.Version.TLS13
            )
        )
)(handler1, handler2)
```

On the client, `trustAll` skips certificate validation:

```scala
HttpClient.withConfig(_.tls(HttpTlsConfig(trustAll = true))) {
    HttpClient.getText("https://localhost:8443")
}
```

Caution: `trustAll = true` disables all certificate verification and exposes the connection to MITM attacks. Use it only in development or integration tests where you control both endpoints.

### Transport Tuning

For low-level tuning of the NIO pump-and-parser pipeline, both configs accept an `HttpTransportConfig` via `.transportConfig(...)`. The defaults are production-ready; override only when profiling reveals a bottleneck. The main knobs are `channelCapacity` (in-flight chunks buffered before backpressure), `readChunkSize` (per-connection read buffer in bytes), and `ioPoolSize` (OS threads for I/O event loops, defaulting to `max(1, cores / 2)`):

```scala
val server = HttpServer.init(
    HttpServerConfig.default
        .port(8080)
        .transportConfig(
            HttpTransportConfig.default
                .channelCapacity(8)
                .readChunkSize(16384)
                .ioPoolSize(4)
        )
)(handler1, handler2)
```

## Describing Endpoints with Routes

The convenience APIs shown so far handle routing and serialization behind the scenes. When you need more control, such as extracting query parameters, headers, cookies, or mapping domain errors to specific status codes, you define routes explicitly with `HttpRoute`.

### Paths

Paths define the URL structure of a route. They are built by composing literal segments and captures with `/`:

```scala
import kyo.HttpPath.*

// Fixed path: /users
val p1 = "users"

// Captures a value from the URL: /users/:id
val p2 = "users" / Capture[Int]("id")

// Multiple captures: /users/:userId/posts/:postId
val p3 = "users" / Capture[Int]("userId") / "posts" / Capture[Int]("postId")
```

String literals become fixed segments. `Capture[A]("name")` extracts a value from the URL and parses it into type `A` using an `HttpCodec[A]`. Built-in codecs exist for `Int`, `Long`, `String`, `Boolean`, `Double`, `Float`, and `UUID`.

`Rest` captures the remaining path as a single string, useful for file-serving or catch-all routes. It must be the last segment in the path. Placing it elsewhere throws an `IllegalArgumentException` at server startup:

```scala
// Matches /files/any/remaining/segments
val p4 = "files" / Capture.Rest("path")
// req.fields.path contains the remainder as a String
```

### Typed Fields

Each capture, query parameter, header, or body declaration adds a named field to the route. These fields are tracked at compile time using Kyo's `Record`, a typed record that maps string literal names to values.

The `~` operator pairs a name with a value, and `&` composes multiple pairs into a record:

```scala
import kyo.*

val record = "id" ~ 42 & "name" ~ "Alice"

record.id   // 42
record.name // "Alice"
// record.missing  // compilation error
```

Field access is checked at compile time, so accessing a field that doesn't exist is a compilation error. The full type of the record reflects its structure:

```scala
val record: Record["id" ~ Int & "name" ~ String] = "id" ~ 42 & "name" ~ "Alice"
val id: Int                                      = record.id
val name: String                                 = record.name
```

These types are built automatically by the API. You don't need to write them out. Here's what the compiler infers for each path:

```scala
// No captures, so the type parameter is Any
val p1: HttpPath[Any] = "users"
// One capture: "id" field of type Int
val p2: HttpPath["id" ~ Int] = "users" / Capture[Int]("id")
// Two captures combined with &
val p3: HttpPath["userId" ~ Int & "postId" ~ Int] =
    "users" / Capture[Int]("userId") / "posts" / Capture[Int]("postId")
```

A fixed path carries `Any` since it produces no fields and imposes no constraints. A single capture gives `HttpPath["id" ~ Int]`, and multiple captures combine with `&`. These fields flow through to the request, so `Capture[Int]("id")` produces an `"id" ~ Int` field accessible through `req.fields.id`.

`HttpRequest` and `HttpResponse` carry a `Record` in their `fields` member, parameterized by the fields declared in the route:

```scala
def handle(req: HttpRequest["id" ~ Int & "name" ~ String]) =
    val id: Int      = req.fields.id
    val name: String = req.fields.name
    // ...
end handle
```

### Request and Response Fields

An `HttpRoute` defines the full contract of an endpoint: the HTTP method, path, which fields to extract from the request, what the response looks like, and how errors map to status codes. You build a route by chaining `.request(...)` and `.response(...)` calls that each add typed fields:

```scala doctest:scope=inherited
val route = HttpRoute
    .getRaw("users" / Capture[Int]("id"))
    .request(
        _.query[Int]("page", default = Present(1))
            .headerOpt[String]("authorization")
    )
    .response(
        _.bodyJson[User]
            .error[NotFound](HttpStatus.NotFound)
    )
```

This route extracts an `id` from the path, a `page` query parameter with a default, and an optional `authorization` header. The response is JSON, and `NotFound` errors map to HTTP 404.

Each call in the chain adds fields to the route's type parameters, which track request fields, response fields, and error types separately:

```scala doctest:expect=skipped
val route: HttpRoute[
    "id" ~ Int & "page" ~ Int & "authorization" ~ Maybe[String], // request fields
    "body" ~ User,                                               // response fields
    NotFound                                                     // error types
]
```

The path capture contributed `"id" ~ Int`, `.query[Int]("page")` added `"page" ~ Int`, `.headerOpt[String]("authorization")` added `"authorization" ~ Maybe[String]`, and `.bodyJson[User]` set the response body. The compiler tracks all of this, so any mismatch between the route definition and the handler that uses it is caught at compile time.

Beyond query parameters, headers, and JSON bodies, routes support several other field types.

**Cookies** are extracted and set just like other fields. Request cookies are read with `cookie` / `cookieOpt`, and response cookies are set with `cookie`, which accepts an `HttpCookie` value with attributes like `maxAge`, `httpOnly`, `secure`, `sameSite`, `domain`, and `path`:

```scala
val route = HttpRoute
    .postRaw("login")
    .request(_.bodyJson[Credentials])
    .response(
        _.bodyJson[User]
            .cookie[String]("session")
    )

val handler =
    route.handler { req =>
        authenticate(req.fields.body).map { user =>
            HttpResponse.ok(user)
                .addField(
                    "session",
                    HttpCookie("token-value")
                        .maxAge(7.days)
                        .httpOnly(true)
                        .sameSite(HttpCookie.SameSite.Lax)
                )
        }
    }
```

**Forms** use `bodyForm[A]` for URL-encoded form data. The type is serialized with an automatically derived `HttpFormCodec`:

```scala
case class LoginForm(username: String, password: String) derives HttpFormCodec

val route = HttpRoute
    .postRaw("login")
    .request(_.bodyForm[LoginForm])
    .response(_.bodyJson[User])
```

**File uploads** use `bodyMultipart` to receive uploaded files as `Seq[HttpPart]`, where each part has `name`, `filename`, `contentType`, and `data` fields. `bodyMultipartStream` provides a streaming variant for large uploads:

```scala
val route = HttpRoute
    .postRaw("upload")
    .request(_.bodyMultipart)
    .response(_.status(HttpStatus.Created))
```

Response definitions also support `header` / `headerOpt`, `status(HttpStatus.Created)` to override the default 200, and streaming body variants (`bodySseJson[V]`, `bodySseText`, `bodyNdjson[V]`, `bodyStream`). All optional variants (`cookieOpt`, `headerOpt`, `queryOpt`) return `Maybe[A]` instead of failing when the value is absent.

### Route Handlers

Converting a route to a handler with `.handler` gives you a request where all declared fields are available:

```scala doctest:scope=inherited
val handler =
    route.handler { req =>
        // req.fields.id is type-safe: Int, from the path capture
        lookupUser(req.fields.id).map {
            case Present(user) => HttpResponse.ok(user)
            // Aborts with NotFound, which the route maps to HTTP 404
            case Absent => Abort.fail(NotFound(s"User ${req.fields.id} not found"))
        }
    }
```

When the handler aborts with `NotFound`, the framework serializes it as JSON and responds with the 404 status declared in the route.

The `req` parameter carries the fields declared in the route's request type. Since the route above defined `"id" ~ Int & "page" ~ Int & "authorization" ~ Maybe[String]`, those fields are available with their expected types:

```scala doctest:scope=nested
val handler2 =
    route.handler { req =>
        val id: Int             = req.fields.id
        val page: Int           = req.fields.page
        val auth: Maybe[String] = req.fields.authorization
        HttpResponse.ok(User(id, "found"))
    }
```

Accessing a field not declared in the route is a compilation error.

### Domain Errors

Routes can map custom error types to HTTP status codes. When a handler aborts with one of these types, the framework serializes it and uses the declared status:

```scala
val route = HttpRoute
    .getRaw("resources" / Capture[Int]("id"))
    .response(
        _.bodyJson[Resource]
            .error[NotFound](HttpStatus.NotFound)
            .error[Forbidden](HttpStatus.Forbidden)
    )

val handler =
    route.handler { req =>
        checkPermission(req).map {
            case false => Abort.fail(Forbidden("insufficient permissions"))
            case true =>
                findResource(req.fields.id).map {
                    case Absent       => Abort.fail(NotFound("not found"))
                    case Present(res) => HttpResponse.ok(res)
                }
        }
    }
```

Only error types declared with `.error[E](status)` map to that status. Any unmapped `Abort` failure propagates as `HttpHandlerException` and becomes a 500, so declaring the error type on the route is what makes `Abort.fail(e)` produce the intended status.

### Filters

Filters are composable middleware that intercept requests and responses. They can transform data flowing through, add fields to the request for downstream handlers, or short-circuit processing entirely (for example, returning 401 before the handler runs).

Filters are split into two namespaces: `HttpFilter.server` for server-side middleware (authentication, logging, rate limiting) and `HttpFilter.client` for client-side middleware (attaching auth headers, logging outgoing requests). Both share the same composition model but operate at different points in the request lifecycle.

A server filter is applied to a route with `.filter(...)`:

```scala
val route = HttpRoute
    .getRaw("admin" / "users")
    .request(_.headerOpt[String]("authorization"))
    .filter(HttpFilter.server.bearerAuth(token => token == "secret"))
    .response(_.bodyJson[List[User]])
```

Multiple filters compose with `andThen`, and they execute in order:

```scala
val pipeline = HttpFilter.server.logging
    .andThen(HttpFilter.server.requestId)
    .andThen(HttpFilter.server.bearerAuth(validateToken))

val route = HttpRoute
    .getRaw("protected")
    .request(_.headerOpt[String]("authorization"))
    .filter(pipeline)
    .response(_.bodyJson[Data])
```

Filters participate in field tracking. For example, `basicAuth` requires an `"authorization" ~ Maybe[String]` field on the request and adds a `"user" ~ String` field for downstream handlers:

```scala
val route = HttpRoute
    .getRaw("admin")
    .request(_.headerOpt[String]("authorization"))
    .filter(HttpFilter.server.basicAuth((user, pass) => user == "admin"))
    .response(_.bodyJson[Data])

val handler =
    route.handler { req =>
        // "authorization" from .request, "user" added by basicAuth
        val auth: Maybe[String] = req.fields.authorization
        val user: String        = req.fields.user
        HttpResponse.ok(Data(s"Hello, $user"))
    }
```

#### Built-in Server Filters

| Filter | Description | Fields |
|--------|-------------|--------|
| `bearerAuth(validate)` | Validates Bearer tokens. Returns 401 on failure. | Takes `"authorization" ~ Maybe[String]` |
| `basicAuth(validate)` | Validates Basic auth. Returns 401 on failure. | Takes `"authorization" ~ Maybe[String]`, outputs `"user" ~ String` |
| `rateLimit(meter, retryAfter)` | Returns 429 when the `Meter` limit is exceeded. | |
| `cors` / `cors(...)` | Handles CORS preflight requests and adds response headers. | |
| `securityHeaders` | Adds `X-Content-Type-Options`, `X-Frame-Options`, and `Referrer-Policy`. Optionally HSTS and CSP. | |
| `logging` | Logs each request as `METHOD /path -> STATUS (Xms)`. | |
| `requestId` / `requestId(headerName)` | Generates or propagates a request ID header. | |

For server-wide CORS that applies to all routes without individual filter setup, configure it on the server directly:

```scala
HttpServer.init(
    HttpServerConfig.default
        .port(8080)
        .cors(HttpServerConfig.Cors(
            allowOrigin = "https://myapp.com",
            allowHeaders = Seq("Content-Type", "Authorization"),
            allowCredentials = true
        ))
)(handler1, handler2)
```

#### Client Filters

Filters also work on the client side, adding authentication, request IDs, logging, tracing, metrics, or custom headers to outgoing requests.
They can be attached at several levels:

```scala
// Reusable policy carried by HttpClientConfig
HttpClient.withConfig(
    HttpClientConfig()
        .baseUrl("https://api.example.com")
        .filter(HttpFilter.client.bearerAuth("secret-token"))
) {
    HttpClient.getText("/users")
}
```

```scala
// Temporary policy scoped to one computation, composed into the active HttpClientConfig
HttpClient.withFilter(HttpFilter.client.addHeader("X-Request-Id", "request-123")) {
    HttpClient.postJson[User]("/users", CreateUser("Alice", "alice@example.com"))
}
```

```scala
// Endpoint-specific policy attached to a typed route
val route = HttpRoute
    .getText("/users")
    .filter(HttpFilter.client.bearerAuth("secret-token"))
```

Scoped filters are stored in the active `HttpClientConfig`, so they can be inspected with `HttpClient.useConfig` or
`HttpClient.useFilter`. Use `HttpClient.withoutFilters { ... }` to clear config and scoped client filters in a nested computation.

The client-side composition order is ServiceLoader filters, config filters, then route filters. Built-in client filters:
`bearerAuth(token)`, `basicAuth(username, password)`, `addHeader(name, value)`, and `logging`.

Client filters also apply to WebSocket HTTP upgrade handshakes, so auth and tracing headers can be configured in the same place for HTTP
requests and WebSocket connections. They do not intercept WebSocket messages after the connection has upgraded.

#### Global Filters via `HttpFilter.Factory`

For cross-cutting concerns that should apply to every request without touching each route (distributed tracing, metrics, structured logging), implement the `HttpFilter.Factory` ServiceLoader SPI. Register your implementation in `META-INF/services/kyo.HttpFilter$Factory`, then override `serverFilter` to install a filter on every incoming request and `clientFilter` to install one on every outgoing request. Either method may return `Absent` to skip installation, which lets a factory be enabled or disabled at runtime via system properties or environment variables. All discovered factories are composed in discovery order.

Caution: Factory instances load eagerly at first server or client use, so any side effect in the Factory constructor runs at an unexpected time. Put initialization logic inside the `serverFilter` / `clientFilter` body, not the constructor.

### Custom Codecs

Path captures, query parameters, headers, and cookies all parse values using `HttpCodec[A]`. Built-in codecs cover `Int`, `Long`, `String`, `Boolean`, `Double`, `Float`, and `UUID`. To use a custom type, define an `HttpCodec` with `encode` and `decode` functions:

```scala
case class Slug(value: String)

given HttpCodec[Slug] = HttpCodec(_.value, Slug(_))

// Now usable in captures, query params, headers, etc.
val route = "articles" / Capture[Slug]("slug")
```

### Primitive Types

A handful of standalone public types model the building blocks of a request. They are exposed directly so you can read and construct them outside the typed-field machinery:

| Type | Description | Key accessors |
|------|-------------|---------------|
| `HttpHeaders` | Immutable, case-insensitive header collection (RFC 9110). | `get`, `getAll`, `contains`, `add`, `set`, `remove`, `cookie`, `cookies`, `responseCookie`, `addCookie` |
| `HttpQueryParams` | Ordered, multi-valued query parameters that allow duplicate keys. | `get`, `getAll`, `add`, `toSeq`, `toQueryString` |
| `HttpUrl` | A parsed URL with structured access to its parts. Build with `HttpUrl.parse` (full URLs, fails on malformed input) or `HttpUrl.fromUri` (path-only request URIs, never fails). | `scheme`, `host`, `port`, `path`, `query`, `queryParams`, `baseUrl`, `ssl`, `address` |
| `HttpAddress` | A connection target, either `Tcp(host, port)` or `Unix(path)`. Returned by `server.address`. | `Tcp`, `Unix` |

`HttpUrl.baseUrl` strips the query string, so it is the safe form for logging since query parameters may carry tokens or API keys. Header name lookups are always case-insensitive while preserving the original case on the wire.

## Error Handling

### Short-Circuit Responses

`HttpResponse.halt` can be called from any handler or filter to abort processing and send a response immediately. This is useful for authorization checks or other early exits:

```scala
val haltRoute = HttpRoute.getRaw("protected").response(_.bodyJson[Data])

val handler =
    haltRoute.handler { req =>
        if !isAuthorized(req) then
            HttpResponse.halt(HttpResponse.forbidden)
        else
            HttpResponse.ok(Data(getData()))
    }
```

### Client Errors

Client operations use `Abort[HttpException]`. The error type has subtypes for different failure modes:

- `HttpConnectionException` for transport-level failures (connect errors, pool exhaustion)
- `HttpRequestException` for request-level failures (timeouts, redirect loops, non-success status codes)
- `HttpDecodeException` for parsing and deserialization failures (URL parsing, JSON/form decoding, missing fields)
- `HttpServerException` for server-side operational failures (bind errors, unhandled handler errors)

Body-only convenience methods (`getText`, `getJson`, `getBinary`, etc.) automatically fail with `HttpStatusException` when the server returns a non-2xx status code. Use the `*Response` variants with `failOnError = false` to receive and inspect error responses.

### Response Helpers

`HttpResponse` provides factory methods for common status codes, both with and without bodies:

```scala
HttpResponse.ok           // 200
HttpResponse.created      // 201
HttpResponse.noContent    // 204
HttpResponse.badRequest   // 400
HttpResponse.unauthorized // 401
HttpResponse.forbidden    // 403
HttpResponse.notFound     // 404
HttpResponse.serverError  // 500

// With bodies
HttpResponse.ok(User(1, "Alice"))
HttpResponse.badRequest("invalid input")
HttpResponse.notFound("not found")
```

Additional variants are available for other status codes (`accepted`, `conflict`, `tooManyRequests`, `serviceUnavailable`, etc.). Each accepts a `String`, `Span[Byte]`, or JSON-serializable body.

## WebSockets and Raw Connections

Some protocols outlive a single request/response: WebSockets carry a long-lived stream of messages, and protocols like Docker exec/attach or CONNECT proxies hijack the connection for raw bytes. kyo-http exposes both through the same module.

### WebSockets

A `HttpWebSocket` is a bidirectional message handle that follows Kyo's `Channel` vocabulary: `put` sends a frame, `take` receives one, and `stream` yields all inbound frames until the connection closes. Messages are `HttpWebSocket.Payload` values, either `Payload.Text(String)` or `Payload.Binary(Span[Byte])`. Backpressure is built in: when the outbound buffer is full, `put` suspends; when the inbound buffer is full, the backend pauses network reads.

Open a client connection with `HttpClient.webSocket`. The connection closes when the supplied function returns:

```scala
val chat =
    HttpClient.webSocket("wss://chat.example.com/room") { ws =>
        for
            _    <- ws.put(HttpWebSocket.Payload.Text(Json.encode(ChatMessage("alice", "hello"))))
            next <- ws.take()
        yield next
    }
```

Serve one with `HttpHandler.webSocket`. The handler receives the upgrade request (useful for auth, cookies, or subprotocol negotiation) and the connection handle, and runs for the lifetime of the connection:

```scala
val echo =
    HttpHandler.webSocket("chat") { (req, ws) =>
        ws.stream.foreach { frame =>
            frame match
                case HttpWebSocket.Payload.Text(data)   => ws.put(HttpWebSocket.Payload.Text(data))
                case HttpWebSocket.Payload.Binary(data) => ws.put(HttpWebSocket.Payload.Binary(data))
        }
    }
```

Call `ws.close(code, reason)` to initiate a close handshake (defaults to code `1000`). After the connection closes, `put` and `take` fail with `Abort[Closed]`, and `ws.closeReason` returns the code and reason sent by the peer. `HttpWebSocket.Config` tunes the connection: `bufferSize` (channel capacity, default 32), `maxFrameSize` (default 16 MiB), `autoPingInterval` for keep-alive pings, `closeTimeout`, and `subprotocols`. Pass it to either `HttpClient.webSocket(url, headers, config)` or `HttpHandler.webSocket(path, config)`.

Caution: the backend does not close the outbound channel when the peer goes away. If you compose separate sender and receiver fibers, include `ws.onPeerClose` in the race or those fibers hang when the peer closes the connection:

```scala
val session =
    HttpClient.webSocket("wss://chat.example.com/room") { ws =>
        val sender   = ws.put(HttpWebSocket.Payload.Text("ping"))
        val receiver = ws.stream.foreach(_ => ())
        // onPeerClose must be in the race, or sender/receiver hang on peer close
        Async.race(sender, receiver, ws.onPeerClose).unit
    }
```

For local testing without a network roundtrip, `HttpWebSocket.connect(p1, p2)` cross-wires two participants directly: what one side puts, the other takes.

### Raw Connections

`HttpClient.connectRaw` upgrades an HTTP request to a raw bidirectional byte stream, detaching the connection from the pool. It returns an `HttpRawConnection` with a `read` stream of `Span[Byte]` and a `write` function, closed automatically when the enclosing `Scope` exits:

```scala
val attach =
    HttpClient.connectRaw("http://localhost/containers/abc/attach", method = HttpMethod.POST).map { conn =>
        conn.write(Span.from("stdin data".getBytes("UTF-8"))).andThen(
            conn.read.foreach(bytes => Console.printLine(s"received ${bytes.size} bytes"))
        )
    }
```

It fails with `HttpStatusException` if the server returns a status that is neither 2xx nor 101 Switching Protocols.

## OpenAPI in Both Directions

kyo-http supports OpenAPI in both directions: generating a spec from your routes, and generating routes from an existing spec.

### Routes to Spec

The server can generate an OpenAPI 3.x specification from the routes of all registered handlers. Adding a single config option enables a `GET` endpoint that serves the spec:

```scala
HttpServer.init(
    HttpServerConfig.default
        .port(8080)
        .openApi("/openapi.json", "My API", "1.0.0", Some("API description"))
)(handler1, handler2)
```

Route metadata enriches the generated spec with descriptions, tags, and other OpenAPI fields:

```scala
val route = HttpRoute
    .getRaw("users" / Capture[Int]("id"))
    .response(_.bodyJson[User])
    .metadata(
        _.summary("Get a user by ID")
            .description("Returns a single user")
            .tag("users")
            .operationId("getUserById")
    )
```

Additional metadata options: `.markDeprecated`, `.externalDocs(url)`, `.security(schemeName)`, `.tags("a", "b")`.

### Spec to Routes

`HttpOpenApi.fromJson` and `HttpOpenApi.fromFile` are compile-time macros that read an OpenAPI spec and produce typed `HttpRoute` values. Path parameters, query parameters, headers, and response bodies are all reflected in the route types.

```scala doctest:expect=skipped
val api = HttpOpenApi.fromFile("api.json")

// Each operation becomes a typed route, accessed by operationId
val route = api.getPet
```

The macro parses the spec at compile time, mapping OpenAPI types to Scala types (`integer` to `Int`, `integer`/`int64` to `Long`, `string`/`uuid` to `UUID`, `boolean` to `Boolean`, `number` to `Double`). Optional parameters become `Maybe[A]` fields. The resulting route carries the full type derived from the spec:

```scala doctest:expect=skipped
val route: HttpRoute["petId" ~ Int, "body" ~ Pet, Nothing] = api.getPet
```

Inline JSON works too:

```scala
val api = HttpOpenApi.fromJson("""{
  "openapi": "3.0.0",
  "info": {"title": "Pets API", "version": "1.0"},
  "paths": {
    "/pets/{petId}": {
      "get": {
        "operationId": "getPet",
        "parameters": [{
          "name": "petId",
          "in": "path",
          "required": true,
          "json": {"type": "integer"}
        }],
        "responses": {
          "200": {
            "description": "ok",
            "content": {
              "application/json": {
                "json": {"type": "string"}
              }
            }
          }
        }
      }
    }
  }
}""")

val route: HttpRoute["petId" ~ Int, "body" ~ String, Nothing] = api.getPet
```

The generated routes can be used with `.handler` on the server or with typed client requests, giving both sides a shared, type-safe contract derived from the spec.

**NOTE:** Spec-to-routes is an initial implementation that covers path, query, and header parameters with primitive types, and JSON response bodies. More advanced OpenAPI features like request bodies, `$ref` resolution, complex schema composition, and security schemes will be added in future releases.

## Feature Flags over HTTP

kyo-http builds on kyo-config's `DynamicFlag` to expose runtime feature flags over HTTP. (kyo-config's README cross-references these as living here, since they depend on the server.)

`FlagAdmin.routes(prefix, readOnly)` returns a `Seq[HttpHandler[?, ?, ?]]` you mount on any server, optionally behind auth middleware or under a prefix. The endpoints inspect and mutate the global flag registry:

- `GET /{prefix}` lists all flags (optional `?filter=glob`)
- `GET /{prefix}/:name` returns a single flag's detail as JSON
- `PUT /{prefix}/:name` updates a `DynamicFlag` expression (plain-text body, not JSON)
- `POST /{prefix}/:name/reload` reloads a `DynamicFlag` from its config source

Passing `readOnly = true` makes the mutating `PUT` and `POST` endpoints return 403. The `GET` endpoints are always open. When the system property `kyo.flag.admin.token` is set, `PUT` and `POST` additionally require `Authorization: Bearer <token>`.

Update a flag by PUTting the rollout expression as a plain-text body (not JSON):

```bash
curl -X PUT -d 'true@premium/50%' http://localhost:8080/flags/myapp.features.newCheckout
```

```scala doctest:expect=skipped
val server = HttpServer.init(
    FlagAdmin.routes(prefix = "flags", readOnly = false)*
)
```

`FlagSync` keeps `DynamicFlag` instances in sync from a background fiber. `FlagSync.startReloader(interval)` periodically re-reads each dynamic flag from its original config source (system properties / env vars), while `FlagSync.startSync(interval, source)` fetches expressions from a caller-supplied function (Consul, etcd, a database). Both apply per-flag error backoff so a persistently broken source does not spam the logs (the first 5 consecutive failures log at WARN, the 6th escalates to one ERROR, and further failures are suppressed until a success resets the counter):

```scala doctest:expect=skipped
// Reload every 30 seconds from system properties / env vars
val reloader = FlagSync.startReloader(30.seconds)

// Or pull expressions from a custom source per flag name
val sync = FlagSync.startSync(30.seconds, name => fetchExpression(name))
```

## Cross-Platform

All APIs are shared across platforms. The same code compiles for JVM, JavaScript, and Scala Native without changes. The backend is selected automatically based on the target platform (see the table in the introduction).

- **JVM**: No additional setup required.
- **JavaScript**: The server backend requires a Node.js runtime.
- **Native**: Requires OpenSSL on the system when TLS is used. Plain HTTP needs no additional setup.

Backends are expected to behave uniformly across platforms. If you encounter a behavioral difference between backends, please [report it](https://github.com/getkyo/kyo/issues).

## Migrating from kyo-sttp and kyo-tapir

`kyo-sttp` (sttp client wrapper) and `kyo-tapir` (tapir + Netty server) have been replaced by `kyo-http`, which provides a unified client/server API across JVM, JavaScript, and Scala Native. The sections below show the most common kyo-sttp / kyo-tapir patterns and their kyo-http equivalents.

### Client: kyo-sttp → HttpClient

`kyo-sttp` exposed sttp's request DSL through `Requests`, where the request was built with a function over `BasicRequest`. In kyo-http, requests are issued with method-specific helpers on `HttpClient`, and JSON/text/binary codecs are inferred from the type.

```scala doctest:expect=skipped
// kyo-sttp
import sttp.client3.*
val resp: String < (Async & Abort[FailedRequest]) =
    Requests(_.get(uri"https://example.com"))

// kyo-http
import kyo.*
val resp: String < (Async & Abort[HttpException]) =
    HttpClient.getText("https://example.com")
```

For typed JSON requests, derive `Schema` instead of a zio-json codec:

```scala
val user = HttpClient.getJson[User]("https://api.example.com/users/1")
```

See the [Client](#making-requests-the-client) section for the full set of helpers (`getText`, `postJson`, `*Unit`, `*Response`, etc.).

### Server: tapir endpoint → HttpRoute

Tapir endpoints described inputs/outputs/errors with a fluent builder, then bound an implementation through `Routes.add`. In kyo-http, the same description lives on `HttpRoute`, and the implementation is attached with `.handler`. Path captures use `HttpPath.Capture` instead of `path[A](...)`, and `jsonBody` is replaced by `request(_.bodyJson[A])` / `response(_.bodyJson[A])`.

```scala doctest:expect=skipped
// kyo-tapir
import sttp.tapir.*
import sttp.tapir.json.zio.*

case class CreateUser(name: String) derives JsonCodec
case class User(id: Int, name: String) derives JsonCodec

val createUser =
    endpoint
        .post
        .in("users")
        .in(jsonBody[CreateUser])
        .out(jsonBody[User])

val routes =
    Routes.add(createUser) { req =>
        direct {
            User(1, req.name)
        }
    }

// kyo-http
import kyo.*
import kyo.HttpPath.*

case class CreateUser(name: String) derives Schema
case class User(id: Int, name: String) derives Schema

val createUser =
    HttpRoute.postRaw("users")
        .request(_.bodyJson[CreateUser])
        .response(_.bodyJson[User])

val handler = createUser.handler { req =>
    HttpResponse.ok(User(1, req.fields.body.name))
}
```

`HttpRoute` exposes higher-level shortcuts when you don't need path captures, e.g. `HttpRoute.postJson[User, CreateUser]("users")` produces the route above directly. Path captures are written `"users" / Capture[Int]("id") / "posts"` and are accessible on the typed request as `req.fields.id`.

### Server: NettyKyoServer → HttpServer

`NettyKyoServer` and `Routes.run` are replaced by a single `HttpServer.init` that accepts handlers as varargs and returns a `Scope`-managed server.

```scala doctest:expect=skipped
// kyo-tapir
import sttp.tapir.server.netty.*

val server =
    NettyKyoServer(NettyKyoServerOptions.default(), NettyConfig.default)
        .host("0.0.0.0")
        .port(9999)

val app = Routes.run(server)(endpoints)

// kyo-http
val app =
    HttpServer.initWith(9999, "0.0.0.0")(handlers*) { server =>
        Console.printLine(s"Server started on port ${server.port}").andThen(Async.never)
    }
```

`HttpServer.init` returns a server managed by `Scope`, so the listener and connections are cleaned up automatically when the enclosing scope exits. Use `HttpServerConfig` for TLS, CORS, idle timeouts, OpenAPI exposure, etc.

### Errors: StatusCode → HttpResponse.halt / .error

`kyo-tapir` mapped errors with `errorOut(statusCode)` and handlers aborted with `Abort.fail[StatusCode](StatusCode.NotFound)`. In `kyo-http`, there are two idiomatic options:

- **Status-only short-circuit**: abort with `HttpResponse.halt` to bypass route serialization and return any response directly:

  ```scala
val notFound: Nothing < Abort[HttpResponse.Halt] =
    HttpResponse.halt(HttpResponse(HttpStatus.NotFound))
  ```

- **Typed domain errors**: declare error types on the route with `.error[E](status)`. The framework serializes the error as JSON and responds with the declared status:

  ```scala
val route =
    HttpRoute.getRaw("users" / Capture[Int]("id"))
        .response(_.bodyJson[User].error[ApiError](HttpStatus.NotFound))

val handler = route.handler { req =>
    Abort.fail(ApiError(s"User ${req.fields.id} not found"))
}
  ```

### JSON: zio-json → Schema

kyo-http uses `Schema` from `kyo-schema` for all JSON, form, and protobuf serialization. Replace zio-json's `derives JsonCodec` with `derives Schema`. Both `Option[A]` and `Maybe[A]` are supported for optional fields; `Maybe[A] = Absent` is the idiomatic choice in Kyo code.

```scala doctest:expect=skipped
// before
case class Transaction(amount: Int, description: Option[String]) derives JsonCodec

// after
import kyo.*
case class Transaction(amount: Int, description: Maybe[String] = Absent) derives Schema
```

### Cheat sheet

| kyo-sttp / kyo-tapir | kyo-http |
| -------------------- | -------- |
| `Requests(_.get(uri"..."))` | `HttpClient.getText` / `getJson` / `postJson` / etc. |
| `endpoint.in(...).out(...)` | `HttpRoute.<method>Raw(...).request(...).response(...)` |
| `path[A]("id")` | `Capture[A]("id")` |
| `jsonBody[A]` | `_.bodyJson[A]` |
| `errorOut(jsonBody[E])` + status | `.error[E](HttpStatus.X)` + `Abort.fail(e)` |
| `errorOut(statusCode)` | `HttpResponse.halt(HttpResponse(HttpStatus.X))` |
| `Routes.add(endpoint)(impl)` | `route.handler(req => impl)` |
| `NettyKyoServer(...).host(h).port(p)` + `Routes.run(server)(...)` | `HttpServer.init(p, h)(handlers*)` |
| `derives JsonCodec` (zio-json) | `derives Schema` |
| `Option[A]` field | `Maybe[A] = Absent` |

For a complete working example, see [kyo-examples ledger](../kyo-examples/jvm/src/main/scala/examples/ledger), which has been migrated from `kyo-tapir` to `kyo-http`.

## Demos

Runnable end-to-end demos live in [`shared/src/test/scala/demo`](shared/src/test/scala/demo). Run any with `sbt 'kyo-httpJVM/Test/runMain demo.<Name>'`.

- [**ChatRoom**](shared/src/test/scala/demo/ChatRoom.scala): text messages plus a live SSE activity feed, with server-level CORS and OpenAPI security metadata.
- [**ApiGateway**](shared/src/test/scala/demo/ApiGateway.scala): aggregates weather and currency APIs through typed routes, parallel client calls, and OpenAPI generation.
- [**WebhookRelay**](shared/src/test/scala/demo/WebhookRelay.scala): receives webhooks via POST and replays them to SSE subscribers, with custom security headers and CORS.
- [**McpServer**](shared/src/test/scala/demo/McpServer.scala): Model Context Protocol server over Streamable HTTP (JSON-RPC POST plus server-initiated SSE).
- [**UrlShortener**](shared/src/test/scala/demo/UrlShortener.scala): 301 redirects, rate limiting, and visit tracking via request and response cookies.
- [**UptimeMonitor**](shared/src/test/scala/demo/UptimeMonitor.scala): pings sites concurrently with `Async.parallel` and streams health results as SSE.
- [**PasteBin**](shared/src/test/scala/demo/PasteBin.scala): content-addressed storage with etag, cache-control, 304 handling, and basic-auth delete.
- [**ImageProxy**](shared/src/test/scala/demo/ImageProxy.scala): binary upload/download with a custom timing filter and OpenAPI deprecation metadata.
- [**BookmarkStore**](shared/src/test/scala/demo/BookmarkStore.scala): in-memory CRUD with bearer auth, rate limiting, typed response headers, and response cookies.
- [**TaskBoard**](shared/src/test/scala/demo/TaskBoard.scala): Kanban board with multiple typed error channels (400/404/409) on a single route.
- [**NotePad**](shared/src/test/scala/demo/NotePad.scala): collaborative notes with PATCH, cookie sessions, and an SSE change feed.
- [**CryptoTicker**](shared/src/test/scala/demo/CryptoTicker.scala): polls a price API and serves it as an NDJSON stream consumed by `getNdJson`.
- [**GithubFeed**](shared/src/test/scala/demo/GithubFeed.scala): polls GitHub's public events API and re-streams them as typed SSE events.
- [**EventBus**](shared/src/test/scala/demo/EventBus.scala): posts events via form and JSON bodies and streams them back as NDJSON.
- [**FileLocker**](shared/src/test/scala/demo/FileLocker.scala): multipart upload and binary download with content-disposition and cache-control.
- [**StaticSite**](shared/src/test/scala/demo/StaticSite.scala): static file server with catch-all paths, caching, HEAD, and path-traversal protection.
- [**HackerNews**](shared/src/test/scala/demo/HackerNews.scala): proxy over the HN and Algolia APIs with `baseUrl` config and parallel story fetches.
- [**LinkChecker**](shared/src/test/scala/demo/LinkChecker.scala): client-only demo that extracts page links and checks them concurrently with `Async.parallel`.
- [**WikiSearch**](shared/src/test/scala/demo/WikiSearch.scala): Wikipedia search proxy showing query-param forwarding and response transformation.
