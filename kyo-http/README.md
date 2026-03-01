# kyo-http

An HTTP/1.1 client and server library for [Kyo](https://github.com/getkyo/kyo). Both client and server share a single API that compiles across JVM, JavaScript, and Scala Native, with platform-specific backends handling the actual I/O:

| Platform | Client | Server |
|----------|--------|--------|
| JVM | [Netty](https://netty.io/) | [Netty](https://netty.io/) |
| JS | [Fetch API](https://developer.mozilla.org/en-US/docs/Web/API/Fetch_API) | [Node.js HTTP](https://nodejs.org/api/http.html) |
| Native | [libcurl](https://curl.se/libcurl/) | [H2O](https://h2o.examp1e.net/) |

The library handles JSON, text, and binary content types with automatic serialization, supports streaming via SSE and NDJSON, includes composable middleware (filters), and supports OpenAPI in both directions: generating specs from routes and generating typed routes from specs at compile time. On the client side, it manages connection pooling, retries, and redirect following.

## Getting Started

Add the dependency to your `build.sbt`:

```scala
libraryDependencies += "io.getkyo" %% "kyo-http" % "<version>"
```

## Client

### Making Requests

The most common starting point is `HttpClient`. For example, fetching a page as text:

```scala
import kyo.*

val html = HttpClient.getText("https://example.com")
```

Text and binary methods (`getText`, `postText`, `getBinary`, `postBinary`, etc.) work with `String` and `Span[Byte]` directly, no setup needed.

For JSON, the library needs to know how to serialize and deserialize your types. This is provided by a `Schema` instance, which can be derived automatically for case classes and sealed types:

```scala
case class User(id: Int, name: String) derives Schema

val user = HttpClient.getJson[User]("https://api.example.com/users/1")
```

For requests with a body, you specify the response type and the body type is inferred:

```scala
case class CreateUser(name: String, email: String) derives Schema

// User is the response type, CreateUser is inferred from the body argument
val created =
  HttpClient.postJson[User](
    "https://api.example.com/users",
    CreateUser("Alice", "alice@example.com")
  )
```

The same pattern applies to `putJson`, `patchJson`, and `deleteJson`.

### Streaming

SSE and NDJSON responses are returned as a `Stream` that emits values as they arrive from the server:

```scala
case class StockPrice(symbol: String, price: Double) derives Schema

// Consumes an SSE endpoint, parsing each event's data as StockPrice
val events = HttpClient.getSseJson[StockPrice]("https://api.example.com/prices")

// Consumes an NDJSON endpoint, parsing each line as StockPrice
val items = HttpClient.getNdJson[StockPrice]("https://api.example.com/stream")
```

`getSseText` is also available for SSE streams with plain text data.

Both return a `Stream` directly. The connection is established and data is parsed when the stream is consumed:

```scala
// Emits HttpEvent[StockPrice] values, performs Async IO and can fail with HttpError
val events: Stream[HttpEvent[StockPrice], Async & Abort[HttpError]] =
  HttpClient.getSseJson[StockPrice]("https://api.example.com/prices")

// Emits StockPrice values, performs Async IO and can fail with HttpError
val items: Stream[StockPrice, Async & Abort[HttpError]] =
  HttpClient.getNdJson[StockPrice]("https://api.example.com/stream")
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
    HttpBackend.Client.default,
    maxConnectionsPerHost = 50,
    idleConnectionTimeout = 30.seconds
  )

  result <- HttpClient.let(client) {
    // All computations in this block use the custom client
    HttpClient.getJson[User]("/users/1")
  }
yield result
```

`HttpClient.init` returns a scoped client that closes when the enclosing `Scope` ends. `HttpClient.initUnscoped` is also available for manual lifecycle management.

## Server

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

For GET/DELETE, both the path and response type are inferred from the handler function. For POST/PUT/PATCH, you specify the body type and the response type is inferred. The same pattern applies to text (`getText`, `postText`) and binary (`getBinary`, `postBinary`) content types.

`HttpHandler.health()` adds a `GET /health` endpoint that returns `"healthy"`.

`HttpServer.init` binds one or more handlers to a port:

```scala
import kyo.*

case class User(id: Int, name: String) derives Schema
case class CreateUser(name: String) derives Schema

val getUsers =
  HttpHandler.getJson("users") { req =>
    User(1, "Alice")
  }

val createUser =
  HttpHandler.postJson[CreateUser]("users") { (req, body) =>
    User(2, body.name)
  }

val server = HttpServer.init(getUsers, createUser, HttpHandler.health())
```

`init` returns an `HttpServer < (Async & Scope)`. The `Scope` effect means the server shuts down automatically when the enclosing scope closes.

### Streaming

Streaming handlers return a `Stream` that the server writes to the response as values are produced. The connection stays open until the stream completes.

#### Server-Sent Events

```scala
case class Tick(count: Int, timestamp: Long) derives Schema

val handler =
  HttpHandler.getSseJson[Tick]("ticks") { req =>
    Stream.init(1, 2, 3).map { n =>
      Clock.now.map(now => HttpEvent(Tick(n, now.toEpochMilli)))
    }
  }
```

`getSseText` works the same way with `HttpEvent[String]` instead of a typed value. `HttpEvent` also supports optional fields for the SSE protocol: `event` (event type name), `id` (event ID for reconnection), and `retry` (reconnection delay).

#### NDJSON

```scala
val handler =
  HttpHandler.getNdJson[Tick]("stream") { req =>
    Stream.init(Tick(1, 0L), Tick(2, 1L), Tick(3, 2L))
  }
```

The convenience handlers above cover the most common case of response-only streaming. For full control, routes support streaming in both directions and in combination. Request bodies can be streamed with `.bodyStream` (raw bytes), `.bodyNdjson[V]` (newline-delimited JSON), and `.bodyMultipartStream` (multipart parts). Response bodies support `.bodyStream`, `.bodyNdjson[V]`, `.bodySseJson[V]`, and `.bodySseText`. A single route can combine both, for example accepting an NDJSON request body and responding with an SSE stream. See [Routes](#routes) for details.

### Configuration

`HttpServer.Config` controls binding options like port, host, and content limits:

```scala
val handler =
  HttpHandler.getJson("users") { req =>
    User(1, "Alice")
  }

val server = HttpServer.init(
    HttpServer.Config()
      .port(8080)
      .host("0.0.0.0")
      .maxContentLength(1024 * 1024) // 1MB
      .keepAlive(true)
  )(handler)
```

When port is 0 (the default), the OS assigns an available port. For manual lifecycle control, `HttpServer.initUnscoped` returns a server that must be closed explicitly.

## Routes

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
val p4 = "files" / Rest("path")
// req.fields.path contains the remainder as a String
```

### Typed Fields

Each capture, query parameter, header, or body declaration adds a named field to the route. These fields are tracked at compile time using Kyo's `Record2`, a typed record that maps string literal names to values.

The `~` operator pairs a name with a value, and `&` composes multiple pairs into a record:

```scala
import kyo.Record2.~

val record = "id" ~ 42 & "name" ~ "Alice"

record.id   // 42
record.name // "Alice"
// record.missing  // compilation error
```

Field access is checked at compile time, so accessing a field that doesn't exist is a compilation error. The full type of the record reflects its structure:

```scala
val record: Record2["id" ~ Int & "name" ~ String] = "id" ~ 42 & "name" ~ "Alice"
val id: Int = record.id
val name: String = record.name
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

`HttpRequest` and `HttpResponse` carry a `Record2` in their `fields` member, parameterized by the fields declared in the route:

```scala
def handle(req: HttpRequest["id" ~ Int & "name" ~ String]) =
  val id: Int = req.fields.id
  val name: String = req.fields.name
  // ...
```

### Request and Response Fields

An `HttpRoute` defines the full contract of an endpoint: the HTTP method, path, which fields to extract from the request, what the response looks like, and how errors map to status codes. You build a route by chaining `.request(...)` and `.response(...)` calls that each add typed fields:

```scala
case class User(id: Int, name: String, email: String) derives Schema
case class NotFound(message: String) derives Schema

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

```scala
val route: HttpRoute[
  "id" ~ Int & "page" ~ Int & "authorization" ~ Maybe[String], // request fields
  "body" ~ User, // response fields
  NotFound // error types
]
```

The path capture contributed `"id" ~ Int`, `.query[Int]("page")` added `"page" ~ Int`, `.headerOpt[String]("authorization")` added `"authorization" ~ Maybe[String]`, and `.bodyJson[User]` set the response body. The compiler tracks all of this, so any mismatch between the route definition and the handler that uses it is caught at compile time.

Beyond query parameters, headers, and JSON bodies, routes support several other field types.

**Cookies** are extracted and set just like other fields. Request cookies are read with `cookie` / `cookieOpt`, and response cookies are set with `cookie`, which accepts an `HttpCookie` value with attributes like `maxAge`, `httpOnly`, `secure`, `sameSite`, and `path`:

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
      HttpResponse.okJson(user)
        .addField("session",
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

```scala
val handler =
  route.handler { req =>
    // req.fields.id is type-safe: Int, from the path capture
    lookupUser(req.fields.id).map {
      case Present(user) => HttpResponse.okJson(user)
      // Aborts with NotFound, which the route maps to HTTP 404
      case Absent => Abort.fail(NotFound(s"User ${req.fields.id} not found"))
    }
  }
```

When the handler aborts with `NotFound`, the framework serializes it as JSON and responds with the 404 status declared in the route.

The `req` parameter carries the fields declared in the route's request type. Since the route above defined `"id" ~ Int & "page" ~ Int & "authorization" ~ Maybe[String]`, those fields are available with their expected types:

```scala
val handler =
  route.handler { req =>
    val id: Int = req.fields.id
    val page: Int = req.fields.page
    val auth: Maybe[String] = req.fields.authorization
    // ...
  }
```

Accessing a field not declared in the route is a compilation error.

### Domain Errors

Routes can map custom error types to HTTP status codes. When a handler aborts with one of these types, the framework serializes it and uses the declared status:

```scala
case class NotFound(message: String) derives Schema
case class Forbidden(reason: String) derives Schema

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
      case true  =>
        findResource(req.fields.id).map {
          case Absent        => Abort.fail(NotFound("not found"))
          case Present(res)  => HttpResponse.okJson(res)
        }
    }
  }
```

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
    val user: String = req.fields.user
    // ...
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
  HttpServer.Config()
    .port(8080)
    .cors(HttpCors(
      allowOrigin = "https://myapp.com",
      allowHeaders = Seq("Content-Type", "Authorization"),
      allowCredentials = true
    ))
)(handler1, handler2)
```

#### Client Filters

Filters also work on the client side, attaching to typed routes to add authentication or custom headers to outgoing requests.

Built-in client filters: `bearerAuth(token)`, `basicAuth(username, password)`, `addHeader(name, value)`, and `logging`.

### Custom Codecs

Path captures, query parameters, headers, and cookies all parse values using `HttpCodec[A]`. Built-in codecs cover `Int`, `Long`, `String`, `Boolean`, `Double`, `Float`, and `UUID`. To use a custom type, define an `HttpCodec` with `encode` and `decode` functions:

```scala
case class Slug(value: String)

given HttpCodec[Slug] = HttpCodec(_.value, Slug(_))

// Now usable in captures, query params, headers, etc.
val route = "articles" / Capture[Slug]("slug")
```

## Error Handling

### Short-Circuit Responses

`HttpResponse.halt` can be called from any handler or filter to abort processing and send a response immediately. This is useful for authorization checks or other early exits:

```scala
val handler =
  route.handler { req =>
    if !isAuthorized(req) then
      HttpResponse.halt(HttpResponse.forbidden)
    else
      HttpResponse.okJson(getData())
  }
```

### Client Errors

Client operations use `Abort[HttpError]`. The error type has subtypes for different failure modes:

- `TimeoutError` when a request exceeds the configured timeout
- `ConnectionError` for transport-level failures
- `ConnectionPoolExhausted` when all connections to a host are in use
- `TooManyRedirects` when redirect following exceeds `maxRedirects`
- `ParseError` for URL or response parsing failures
- `StatusError` for non-success HTTP status codes

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
HttpResponse.okJson(user)
HttpResponse.badRequestText("invalid input")
HttpResponse.notFoundJson(ErrorMessage("not found"))
```

## OpenAPI

kyo-http supports OpenAPI in both directions: generating a spec from your routes, and generating routes from an existing spec.

### Routes to Spec

The server can generate an OpenAPI 3.x specification from the routes of all registered handlers. Adding a single config option enables a `GET` endpoint that serves the spec:

```scala
HttpServer.init(
  HttpServer.Config()
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

```scala
val api = HttpOpenApi.fromFile("api.json")

// Each operation becomes a typed route, accessed by operationId
val route = api.getPet
```

The macro parses the spec at compile time, mapping OpenAPI types to Scala types (`integer` to `Int`, `integer`/`int64` to `Long`, `string`/`uuid` to `UUID`, `boolean` to `Boolean`, `number` to `Double`). Optional parameters become `Maybe[A]` fields. The resulting route carries the full type derived from the spec:

```scala
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
          "schema": {"type": "integer"}
        }],
        "responses": {
          "200": {
            "description": "ok",
            "content": {
              "application/json": {
                "schema": {"type": "string"}
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

## Cross-Platform

All APIs are shared across platforms. The same code compiles for JVM, JavaScript, and Scala Native without changes. The backend is selected automatically based on the target platform (see the table in the introduction).

- **JVM**: No additional setup required.
- **JavaScript**: The server backend requires a Node.js runtime.
- **Native**: Requires libcurl and H2O to be available on the system.

Backends are expected to behave uniformly across platforms. If you encounter a behavioral difference between backends, please [report it](https://github.com/getkyo/kyo/issues).
