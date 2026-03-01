# kyo-http Commit Plan

Each commit compiles and passes tests incrementally (except demos, which may reference types from later commits). Tests accompany their respective production files in every commit.


## Commit 1: README and build configuration

Read the README before looking at any code. It walks through the library from the user's perspective — client usage, server, then the route DSL that powers both. The code commits follow dependency order (bottom-up), which is the reverse of the README. As you review each commit, you can look back at the README to see how that piece surfaces in the user-facing API.

Files: `build.sbt` changes, `kyo-http/README.md`, `Test.scala` (shared test base class)


## Commit 2: HTTP vocabulary

Leaf types with no internal dependencies — the vocabulary that everything else is built on.

`HttpMethod` and `HttpStatus` are standard HTTP concepts as Scala types. `HttpException` defines the failure modes that appear in `Abort[HttpException]` signatures throughout — scan the subtypes to get a feel for what can go wrong (timeout, parse, connection exhaustion, etc.).

Two types to pay attention to: `HttpCodec[A]` and `Json[A]`. They're small but they're the serialization backbone for the route system in commit 6. `HttpCodec` handles string conversions for path captures, query params, headers, and cookies — it defines `encode: A => String` and `decode: String => A` with built-in instances for `Int`, `Long`, `String`, `Boolean`, `UUID`, etc. `Json[A]` wraps zio-schema and is what `derives Json` provides on case classes.

`HttpCookie` is the typed cookie value with attributes (maxAge, httpOnly, sameSite, etc.). It uses `HttpCodec` for value serialization.

Files: `HttpMethod`, `HttpStatus`, `HttpException`, `HttpCodec`, `Json`, `HttpCookie`


## Commit 3: URLs, headers, and messages

The wire-level data types.

`HttpUrl` provides parsed URL access — scheme, host, port, path, and lazy query parameters.

`HttpHeaders` uses a flat interleaved `Chunk[String]` (`[name, value, name, value, ...]`) rather than a `Dict`. `Dict` would need `Dict[String, Chunk[String]]` to support multi-value headers (e.g. multiple `Set-Cookie`), which allocates a `Chunk` per header. The flat layout avoids per-header allocation, supports zero-copy conversion from Netty's internal format (see `FlatNettyHttpHeaders` in the JVM backend), and handles case-insensitive lookup without key normalization. The tradeoff is O(n) lookup, but HTTP requests rarely have more than ~20 headers.

`HttpRequest` and `HttpResponse` are data carriers: method + URL + headers + a `Record[Fields]` holding the fields a route declared. The `HttpResponse` companion provides factory methods for every common status code (`okJson`, `badRequest`, etc.) and `Halt` — the short-circuit mechanism that lets filters and handlers abort request processing.

Files: `HttpUrl`, `HttpHeaders`, `HttpRequest`, `HttpResponse`


## Commit 4: Route building blocks

Four standalone types that `HttpRoute` depends on. Introduced here so they have context when routes arrive in commit 6.

`HttpPath` is the path pattern DSL: literal segments and typed captures like `"users" / Capture[Int]("id")` producing `HttpPath["id" ~ Int]`. It depends only on `HttpCodec`.

`HttpFormCodec` is a derived codec for URL-encoded form bodies. `HttpSseEvent` is the Server-Sent Event wrapper with typed data payload, optional event name, id, and retry interval.

`HttpServerConfig` controls server binding: port, host, content limits, CORS, and OpenAPI.

Files: `HttpPath`, `HttpFormCodec`, `HttpSseEvent`, `HttpServerConfig`


## Commit 5: Filters

`HttpFilter` has five type parameters — `HttpFilter[ReqUse, ReqAdd, ResUse, ResAdd, E]` — but the idea is straightforward: a filter can *require* fields on the request (`ReqUse`), *add* fields for downstream (`ReqAdd`), and transform the response (`ResUse → ResAdd`). Look at `basicAuth`'s signature to see how this works: it requires `"authorization" ~ Maybe[String]` on the request and adds `"user" ~ String` for downstream handlers. The compiler enforces that you can't use `basicAuth` without declaring the authorization header on the route.

`andThen` composes filters by intersecting their type parameters. Built-in server filters cover auth (basic, bearer), CORS, rate limiting, logging, security headers, and request IDs. Client filters attach auth headers to outgoing requests.

Files: `HttpFilter`


## Commit 6: Routes and handlers

This is the commit to spend the most time on. Everything else in the library exists either as a building block for routes or as a convenience on top of them.

`HttpRoute[In, Out, E]` is a complete endpoint contract: method, path, what to extract from the request, what the response looks like, and how domain errors map to status codes. The three type parameters track request fields, response fields, and error types at compile time via kyo's `Record`.

The key mechanism is in `RequestDef`. Each call (`.query`, `.header`, `.cookie`, `.bodyJson`) appends a `Field` and *refines the `In` type* via intersection — so `RequestDef[A].query[Int]("page")` returns `RequestDef[A & "page" ~ Int]`. This accumulates fields at the type level, and the compiler enforces that handlers can only access declared fields.

Suggested reading order within `HttpRoute.scala`: the case class definition → `RequestDef` (how request fields are declared) → `ResponseDef` (output side) → `ContentType` (the full range of body formats: JSON, text, binary, streaming, SSE, NDJSON, form, multipart).

`HttpHandler` pairs a route with its implementation function. `HttpRoute.handler` is the main entry point for creating one. `HttpHandler` also provides convenience methods (`getJson`, `postJson`, etc.) that create a route and handler in one call.

Files: `HttpRoute`, `HttpHandler`


## Commit 7: Backend contract

`HttpBackend` is the platform seam — the traits that JVM/JS/Native must implement. The `Client` trait is connection-oriented: `connectWith` establishes a connection, `sendWith` sends a typed request through it. This design lets `HttpClient` manage connection pooling independently of the backend. The `Server` trait takes handlers and config, returns a `Binding` with port info and lifecycle.

When reviewing the platform backends later, this is the contract they fulfill.

Files: `HttpBackend`


## Commit 8: Client

The consumer side of the design. `HttpClient`'s convenience methods (`.getJson`, `.postJson`, etc.) all create an `HttpRoute` internally and delegate to the typed send path — routes are the underlying abstraction even when users don't interact with them directly.

The request lifecycle is layered as a chain of `*With` methods: `sendWithConfig → retryWith → redirectsWith → timeoutWith → poolWith`. `poolWith` at the bottom is where the connection pool is consulted and the backend is called. Read this chain top-to-bottom to understand how retries, redirects, timeouts, and pooling compose.

`HttpClientConfig` controls timeout, base URL, redirect following, and retry behavior.

`ConnectionPool` is self-contained — a lock-free per-host Vyukov MPMC ring buffer with idle eviction. It can be reviewed independently.

Demo: `LinkChecker` — a client-only app that fetches a page, extracts links, and checks them concurrently. A good sanity check that the client API works before looking at the server side.

Files: `HttpClient`, `HttpClientConfig`, `ConnectionPool`


## Commit 9: OpenAPI

Self-contained feature that works in two directions.

**Routes → spec**: `OpenApiGenerator` walks route definitions — introspecting `RequestDef`/`ResponseDef` fields, content types, error mappings, and metadata — and produces an OpenAPI 3.x JSON spec.

**Spec → routes**: `OpenApiMacro` is a compile-time macro that reads an OpenAPI JSON spec and generates typed `HttpRoute` values. Current limitations: only primitive type mappings (`integer` → `Int`, `string` → `String`, etc.), only `application/json` response bodies, no request body generation, no `$ref` resolution, no `allOf`/`oneOf`/`anyOf` composition, no security scheme generation.

`HttpOpenApi` is the model type for the OpenAPI spec.

Files: `HttpOpenApi`, `OpenApiGenerator`, `OpenApiMacro`


## Commit 10: Server

`HttpServer` binds handlers to a port and manages lifecycle. Look at `initUnscoped` to see the full wiring: if OpenAPI is enabled, it generates a spec endpoint from all handlers via `OpenApiGenerator`, then calls `backend.bind`. The server is a thin orchestrator — the heavy lifting is in `HttpBackend`, `HttpRouter`, and `RouteUtil`.

Demos: Three apps that exercise the full client+server stack. Start with `HackerNews` — the simplest, proxying an external API with typed routes and query params. `BookmarkStore` shows the pattern of defining routes first then implementing handlers separately, with filters (bearer auth, rate limiting, request ID), cookies, and error mappings. `NotePad` is the most complex: CRUD + PATCH + SSE live change feed + cookie sessions, with a client that consumes the stream.

Files: `HttpServer`


## Commit 11: Internal dispatch

This is where the type-safe route world meets raw HTTP bytes, and the most likely place for subtle bugs. Every platform backend delegates to these two files.

`RouteUtil` is the codec bridge: it reads path captures, query params, headers, and cookies from a raw request and assembles the typed `Record` that handlers receive. It also serializes response fields (including cookies, SSE events, NDJSON lines) back to wire format. A mistake here silently affects all platforms — prioritize this for review.

`HttpRouter` compiles handlers into a trie for O(path-segments) dispatch, with support for literal segments, typed captures, and catch-all (Rest) routes. It also handles CORS preflight when configured at the server level.

Files: `RouteUtil`, `HttpRouter`


## Commit 12: RFC validation tests

Protocol compliance tests — no production code. Structured by RFC: URI syntax (3986), cookies (6265), additional status codes (6585), bearer auth (6750), multipart (7578), basic auth (7617), and HTTP semantics (9110). Most of these spin up a server and make real HTTP calls, exercising the full stack end-to-end.


## Commit 13: JVM backend — Netty

The largest and most complex backend. The core challenge is bridging Netty's channel pipeline (async, callback-driven) with kyo's effect system (fiber-based).

Two files carry most of the complexity: `NettyServerHandler` handles inbound requests — parses the Netty message, builds an `HttpRequest`, routes through `HttpRouter`, writes the response. `NettyConnection` handles the client side — request/response lifecycle including streaming bodies, backpressure, and connection reuse. Both delegate to `RouteUtil` for field extraction and serialization.

`FlatNettyHttpHeaders` converts between Netty's header format and the flat `Chunk[String]` representation from commit 3 — this is where that representation choice pays off with zero-copy conversion. `NettyTransport` manages event loop groups with platform detection (epoll on Linux, kqueue on macOS, NIO fallback). `NettyUtil` provides shared error mapping and async bridging helpers.

Files: `NettyTransport`, `NettyUtil`, `FlatNettyHttpHeaders`, `NettyConnection`, `NettyServerHandler`, `NettyClientBackend`, `NettyServerBackend`, JVM `HttpPlatformBackend`


## Commit 14: JS backend — Fetch/Node

Smaller than JVM. `FetchClientBackend` maps routes to Fetch API calls. `NodeServerBackend` wraps Node's `http.createServer`. `NodeHttp` is the JS facade for Node's HTTP module.

The key review question: do these backends fulfill the same behavioral contract as Netty? The shared tests and RFC tests are supposed to guarantee equivalence.

Files: `FetchClientBackend`, `NodeServerBackend`, `NodeHttp`, JS `HttpPlatformBackend`, JS `SecureRandom`


## Commit 15: Native backend — libcurl/H2O

C interop via Scala Native bindings. The bindings files (`CurlBindings`, `H2oBindings`) are FFI declarations — skim these. The C wrapper files (`curl_wrappers.c`, `h2o_wrappers.c`) provide thin C helpers where Scala Native's interop needs assistance.

`CurlEventLoop` is the most complex piece: it drives libcurl's multi interface for non-blocking concurrent requests, polling file descriptors and dispatching completions to kyo fibers. `CurlTransferState` tracks per-request state (headers, body chunks, completion promise). `H2oServerBackend` wraps the H2O HTTP server library, mapping H2O's request/response model to `HttpRouter` dispatch.

Files: `CurlBindings`, `CurlTransferState`, `CurlEventLoop`, `CurlClientBackend`, `H2oBindings`, `H2oServerBackend`, Native `HttpPlatformBackend`, Native `SecureRandom`, `curl_wrappers.c`, `h2o_wrappers.c`, `CurlClientBackendTest`, `H2oServerBackendTest`


## Commit 16: Remaining demos

More complete applications covering the full feature set. Each file's header comment lists which features it demonstrates — browse based on interest.

- Streaming: `CryptoTicker` + client (NDJSON), `GithubFeed` (SSE re-streaming from external API)
- Binary/multipart: `ImageProxy` (binary round-trip, custom filter), `FileLocker` (multipart upload), `StaticSite` (file serving with ETag caching)
- Advanced patterns: `PasteBin` (Rest paths, ETag/If-None-Match), `UrlShortener` (rate limiting, redirects, cookies), `ChatRoom`, `EventBus` (form input + NDJSON), `TaskBoard`, `UptimeMonitor`, `ApiGateway`, `WebhookRelay`, `McpServer`, `WikiSearch`
