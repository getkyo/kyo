# kyo-http — Canonical Public-API Template

Public-surface conventions of `kyo-http`, the alignment template for sibling modules (`kyo-jsonrpc`). Every claim cites file:line under `kyo-http/shared/src/main/scala/kyo/...` (and parallel `jvm/`, `native/`, `js/`).

---

## 1. Public-surface census

All top-level declarations in `package kyo`. "Visible" = a typical caller touches it via construction, matching, or signature.

| file:line | name | kind | role | visible |
|---|---|---|---|---|
| `HttpAddress.scala:18` | `HttpAddress` | enum (sealed, `derives CanEqual`) | model (network address) | yes |
| `HttpClient.scala:41` | `HttpClient` | opaque type (over internal backend) | transport entry-point | yes |
| `HttpClientConfig.scala:54` | `HttpClientConfig` | case class (fluent + `copy`) | config | yes |
| `HttpCodec.scala:22` | `HttpCodec[A]` | abstract class + companion | codec (path/query/header/cookie) | yes |
| `HttpCookie.scala:21` | `HttpCookie[A]` | case class + fluent setters | model (typed cookie) | yes |
| `HttpCookie.scala:46` | `HttpCookie.SameSite` | nested enum | model (cookie attribute) | yes |
| `HttpException.scala:28` | `HttpException` | sealed abstract class | error root | match |
| `HttpException.scala:49, :90, :146, :191, :318` | `Http{Connection,Request,Server,Decode,WebSocket}Exception` | sealed abstract class | error category | match |
| `HttpException.scala:53, :62, :68` | `Http{Connect,ConnectTimeout,PoolExhausted}Exception` | case class | error leaf | match |
| `HttpException.scala:94, :104, :125` | `Http{Timeout,RedirectLoop,Status}Exception` | case class, smart ctor | error leaf | match |
| `HttpException.scala:150, :159` | `Http{Bind,Handler}Exception` | case class | error leaf | match |
| `HttpException.scala:195, :210, :230, :244, :255, :270, :285, :298` | `Http{UrlParse,FieldDecode,MissingField,JsonDecode,FormDecode,UnsupportedMediaType,StreamingDecode,MissingBoundary}Exception` | case class, smart ctor | error leaf | match |
| `HttpException.scala:322` | `HttpWebSocketHandshakeException` | case class | error leaf | match |
| `HttpException.scala:335, :341, :347` | `Http{Protocol,PayloadTooLarge,ConnectionClosed}Exception` | case class, `private[kyo]` ctor | error leaf | match only |
| `HttpFilter.scala:43` | `HttpFilter[ReqUse,ReqAdd,ResUse,ResAdd,+E]` | sealed abstract class | middleware | yes |
| `HttpFilter.scala:76` | `HttpFilter.Request` | abstract class | middleware (req only) | yes |
| `HttpFilter.scala:79` | `HttpFilter.Response` | abstract class | middleware (resp only) | yes |
| `HttpFilter.scala:82` | `HttpFilter.Passthrough` | abstract class | middleware (both) | yes |
| `HttpFilter.scala:108` | `HttpFilter.server` | object (lowercase namespace) | middleware factory (server) | yes |
| `HttpFilter.scala:324` | `HttpFilter.client` | object (lowercase namespace) | middleware factory (client) | yes |
| `HttpFilter.scala:409` | `HttpFilter.Factory` | trait + ServiceLoader SPI | extension point | extension authors |
| `HttpFormCodec.scala:21` | `HttpFormCodec[A]` | abstract class + companion (`derived`) | codec (form bodies) | yes |
| `HttpHandler.scala:34` | `HttpHandler[In,Out,+E]` | sealed abstract class | server endpoint | yes |
| `HttpHeaders.scala:30` | `HttpHeaders` | opaque type | model (header collection) | yes |
| `HttpMethod.scala:8` | `HttpMethod` | opaque type (over `String`) | model (method) | yes |
| `HttpOpenApi.scala:25` | `HttpOpenApi` | case class (`derives Schema, CanEqual`) | model (OpenAPI spec root) | yes |
| `HttpOpenApi.scala:47, :59, :65, :69, :77, :83, :88, :100, :121, :126` | `HttpOpenApi.{SchemaObject,Info,MediaType,Parameter,RequestBody,Response,Operation,PathItem,Components,SecurityScheme}` | nested case classes | model | yes |
| `HttpPath.scala:25` | `HttpPath[+A]` | enum (sealed, `derives CanEqual`) | path DSL | yes |
| `HttpPath.scala:54` | `HttpPath.Capture` | nested apply factories | path DSL | yes |
| `HttpQueryParams.scala:13` | `HttpQueryParams` | opaque type | model (query params) | yes |
| `HttpRawConnection.scala:11` | `HttpRawConnection` | final class, `private[kyo]` ctor | model (upgraded conn) | yes (use, not construct) |
| `HttpRequest.scala:24` | `HttpRequest[Fields]` | case class | model (request) | yes |
| `HttpRequest.scala:79` | `HttpRequest.Part` | nested case class (`derives CanEqual`) | model (multipart part) | yes |
| `HttpResponse.scala:23` | `HttpResponse[Fields]` | case class + factories | model (response) | yes |
| `HttpResponse.scala:169` | `HttpResponse.Halt` | nested case class | short-circuit signal | yes |
| `HttpRoute.scala:40` | `HttpRoute[In,Out,+E]` | case class + fluent builder | endpoint contract | yes |
| `HttpRoute.scala:176, :196, :200, :211, :218` | `HttpRoute.{ContentType,Field,Field.Param,Field.Param.Location,Field.Body}` | nested ADT (enum / sealed / case class) | model | RHS match |
| `HttpRoute.scala:237` | `HttpRoute.RequestDef[-In]` | nested case class + fluent builder | builder | yes |
| `HttpRoute.scala:392` | `HttpRoute.ResponseDef[-Out]` | nested case class + fluent builder | builder | yes |
| `HttpRoute.scala:526` | `HttpRoute.ErrorMapping` | nested case class | model | RHS match |
| `HttpRoute.scala:533` | `HttpRoute.Metadata` | nested case class + fluent builder | OpenAPI metadata | yes |
| `HttpServer.scala:37` | `HttpServer` | opaque type | transport entry-point | yes |
| `HttpServer.scala:145` | `HttpServer.Unsafe` | nested abstract class | unsafe-API tier | extension authors |
| `HttpServerConfig.scala:52` | `HttpServerConfig` | case class (fluent + `copy`) | config | yes |
| `HttpServerConfig.scala:110` | `HttpServerConfig.OpenApiEndpoint` | nested case class | config sub-record | yes |
| `HttpServerConfig.scala:117` | `HttpServerConfig.Cors` | nested case class | config sub-record | yes |
| `HttpSseEvent.scala:10` | `HttpSseEvent[+A]` | case class (`derives CanEqual`) | model (SSE event) | yes |
| `HttpStatus.scala:25` | `HttpStatus` | sealed abstract class (`derives CanEqual`) | model (status code) | yes |
| `HttpStatus.scala:71` | `HttpStatus.Custom` | nested final case class | model (escape hatch) | yes |
| `HttpStatus.scala:79` | `HttpStatus.Informational` / `.Success` / `.Redirect` / `.ClientError` / `.ServerError` | nested enums | model (status categories) | yes (exported) |
| `HttpTlsConfig.scala:21` | `HttpTlsConfig` | case class | config | yes |
| `HttpTlsConfig.scala:41` | `HttpTlsConfig.ClientAuth` | nested enum | config sub-enum | yes |
| `HttpTlsConfig.scala:44` | `HttpTlsConfig.Version` | nested enum | config sub-enum | yes |
| `HttpTransportConfig.scala:24` | `HttpTransportConfig` | case class (fluent + `copy`) | config | yes |
| `HttpUrl.scala:32` | `HttpUrl` | final case class (`derives CanEqual`) | model (parsed URL) | yes |
| `HttpWebSocket.scala:30` | `HttpWebSocket` | final class, `private[kyo]` ctor | transport handle | yes (use, not construct) |
| `HttpWebSocket.scala:90` | `HttpWebSocket.Payload` | nested enum | model (frame) | yes |
| `HttpWebSocket.scala:110` | `HttpWebSocket.Config` | nested case class | config | yes |
| `FlagAdmin.scala:31` | `FlagAdmin` | object | adapter (Flag → HttpHandler) | yes |
| `FlagAdmin.scala:148, :160, :167, :172` | `FlagAdmin.{FlagInfo,HistoryInfo,ErrorResponse,ReloadResponse}` | nested case class | model (admin response) | yes |
| `FlagSync.scala:29` | `FlagSync` | object | side-effecting startup helpers | yes |

Counts: 38 instances of `derives Schema` / `derives CanEqual` / `derives Schema, CanEqual` across the 28 top-level files in `shared/src/main/scala/kyo/`.

---

## 2. Naming conventions

### `Http*` prefix discipline

Uniformly prefixed. Every type whose meaning is HTTP-specific carries `Http`:

| pattern | examples | rationale |
|---|---|---|
| transport entry-points | `HttpServer` (`HttpServer.scala:37`), `HttpClient` (`HttpClient.scala:41`) | namespaced collision-proof |
| message types | `HttpRequest` (`HttpRequest.scala:24`), `HttpResponse` (`HttpResponse.scala:23`) | namespaced |
| model primitives | `HttpUrl` (`HttpUrl.scala:32`), `HttpMethod` (`HttpMethod.scala:8`), `HttpStatus` (`HttpStatus.scala:25`), `HttpHeaders` (`HttpHeaders.scala:30`), `HttpPath` (`HttpPath.scala:25`), `HttpQueryParams` (`HttpQueryParams.scala:13`), `HttpAddress` (`HttpAddress.scala:18`), `HttpCookie` (`HttpCookie.scala:21`), `HttpSseEvent` (`HttpSseEvent.scala:10`) | prefix even on common nouns |
| codecs | `HttpCodec` (`HttpCodec.scala:22`), `HttpFormCodec` (`HttpFormCodec.scala:21`) | `*Codec` suffix |
| configs | `HttpClientConfig`, `HttpServerConfig`, `HttpTlsConfig`, `HttpTransportConfig` (one file each) | `*Config` suffix |
| middleware | `HttpFilter` (`HttpFilter.scala:43`) | unified `Filter` term, not `Middleware` |
| endpoint | `HttpRoute` (`HttpRoute.scala:40`), `HttpHandler` (`HttpHandler.scala:34`) | `*Route`/`*Handler` |
| errors | `HttpException`, `HttpXyzException` (all in `HttpException.scala`) | `*Exception` |
| raw upgrade | `HttpRawConnection` (`HttpRawConnection.scala:11`) | prefixed |
| WebSocket | `HttpWebSocket` (`HttpWebSocket.scala:30`) | not bare `WebSocket`; the WS is a sub-protocol of HTTP |
| spec | `HttpOpenApi` (`HttpOpenApi.scala:25`) | prefix even for derived specs |

**No exceptions:** bare `Url`, `Method`, `Status`, `Header`, `Cookie`, `Route`, or `WebSocket` do not appear. The only un-prefixed top-level types are `FlagAdmin` (`FlagAdmin.scala:31`) and `FlagSync` (`FlagSync.scala:29`); their `Flag*` prefix is the namespacing.

### Suffix patterns

- `*Config` for config records (`HttpClientConfig`, `HttpServerConfig`, `HttpTlsConfig`, `HttpTransportConfig`).
- `*Codec` for codecs (`HttpCodec`, `HttpFormCodec`).
- `*Exception` for errors; root is `HttpException` not `HttpError`.
- `*Handler` for endpoint impls. No `*Service`, `*Manager`, `*Helper`, `*Util` on the public surface.
- No `*Builder`: case classes carry fluent methods directly (§5).
- No `*Factory` other than the SPI trait `HttpFilter.Factory` (`HttpFilter.scala:409`), named for its `ServiceLoader` role.
- `*Middleware` does not appear; the term is `Filter`.

### Casing for namespace objects

Per `feedback_lowercase_namespace_objects`, nested namespace-objects use **lowercase**: `HttpFilter.server` (`HttpFilter.scala:108`), `HttpFilter.client` (`HttpFilter.scala:324`). Nested types stay capitalized (`HttpRoute.RequestDef`, `HttpStatus.Success`, `HttpWebSocket.Payload`). Lowercase = collection of related defs without value-type meaning.

### `HttpWebSocket` vs `WebSocket`

Always `HttpWebSocket` (`HttpWebSocket.scala:30`). Nested types follow: `HttpWebSocketException` (`HttpException.scala:318`), `HttpWebSocketHandshakeException` (`HttpException.scala:322`).

### `Http` entry-point shape

No single top-level `Http` effect. Two siblings:

- `HttpClient` (`HttpClient.scala:41`) — outbound, fiber-local `Local` (`HttpClient.scala:52`).
- `HttpServer` (`HttpServer.scala:37`) — inbound, lifecycle-managed via `Scope`.

Both are opaque-typed wrappers; public API exposed via `extension (self: HttpServer)` (`HttpServer.scala:41`) and `extension (self: HttpClient)` (`HttpClient.scala:71`). Companion objects host factories.

---

## 3. Type-organization patterns

Mix of (a) one type per file, (b) related types nested in a companion, (c) flat siblings in one file when they're variants of one ADT. Five concrete examples:

1. **Sealed exception hierarchy in one file.** `HttpException.scala` has root (`:28`), four category supertypes (`:49`, `:90`, `:146`, `:191`, plus `:318` for WebSocket) and ~17 leaf case classes. One file because callers `match` over the whole tree.
2. **Sealed abstract + nested category enums.** `HttpStatus.scala:25` is `sealed abstract`; five enums (`Informational :79`, `Success :86`, `Redirect :96`, `ClientError :107`, `ServerError :139`) all extend it and re-export to the companion (`:73-77`).
3. **Builder + DSL types in route companion.** `HttpRoute.scala` nests `RequestDef` (`:237`), `ResponseDef` (`:392`), `Field` (`:196`), `ContentType` (`:176`), `ErrorMapping` (`:526`), `Metadata` (`:533`). Useless outside `HttpRoute`, so nested to avoid `kyo.*` pollution.
4. **Config + sub-records nested.** `HttpServerConfig.scala` nests `OpenApiEndpoint` (`:110`) and `Cors` (`:117`); `Cors` has its own sub-companion with `allowAll` (`:125`).
5. **Path DSL as flat enum + companion factory.** `HttpPath.scala:25` is a four-case enum; companion (`:51`) supplies `Capture[A](...)` factories. Top-of-file `export`s (`:48-49`) lift `/` and `Capture` into `package kyo`.

### Rules

- ADT with leaves matched as a tree → one file, sealed root + leaves.
- Type only meaningful inside another → nested in the companion.
- Config sub-record referenced from one config only → nested.
- Standalone primitive with factories → own file (`HttpUrl`, `HttpMethod`, `HttpCookie`).
- `opaque type` over a carrier → own file; companion extension API.
- Static factories → always in the companion.

### `opaque type` placement

Five public opaque types:

- `HttpMethod = String` (`HttpMethod.scala:8`) — finite constants + `unsafe(name)` escape hatch.
- `HttpHeaders = Chunk[String] | Array[Byte]` (`HttpHeaders.scala:30`) — dual representation behind extension API.
- `HttpQueryParams = Seq[(String, String)]` (`HttpQueryParams.scala:13`).
- `HttpServer = HttpServer.Unsafe` (`HttpServer.scala:37`).
- `HttpClient = HttpClientBackend[?]` (`HttpClient.scala:41`).

Companion exposes API via `extension (self: T)` or companion methods; carrier is never referenced by users.

---

## 4. Effect-row idioms

### The canonical row

I/O methods use `Async` base, `Abort[HttpException]` for client errors, `Scope` when the result holds a managed resource.

Verbatim signatures:

1. `HttpServer.init(handlers: HttpHandler[?, ?, ?]*)(using Frame): HttpServer < (Async & Scope)` — `HttpServer.scala:71`.
2. `HttpClient.init(...): HttpClient < (Async & Scope)` — `HttpClient.scala:130`.
3. `HttpClient.getJson[A: Schema](url, headers, query)(using Frame): A < (Async & Abort[HttpException])` — `HttpClient.scala:157-161`.
4. `HttpClient.connectRaw(...): HttpRawConnection < (Async & Abort[HttpException] & Scope)` — `HttpClient.scala:774-779`.
5. `HttpClient.getSseJson[V: Schema: Tag](url, ...): Stream[HttpSseEvent[V], Async & Abort[HttpException]]` — `HttpClient.scala:622-626`.
6. `HttpServer.close(using Frame): Unit < Async` — `HttpServer.scala:56`.
7. `HttpHandler.apply(request: HttpRequest[In])(using Frame): HttpResponse[Out] < (Async & Abort[E | HttpResponse.Halt])` — `HttpHandler.scala:36`.
8. `HttpWebSocket.put(frame)(using Frame): Unit < (Async & Abort[Closed])` — `HttpWebSocket.scala:39`.

### Error-channel discipline

- **Client convenience methods**: `Abort[HttpException]`, sealed root at `HttpException.scala:28`. Match on subtypes to differentiate.
- **Server handlers**: `Abort[E | HttpResponse.Halt]` (`HttpHandler.scala:36`). `E` is user's mapped type via `route.error[E](status)` (`HttpRoute.scala:82`); `Halt` is short-circuit (`HttpResponse.scala:169`).
- **Synchronous parsing**: `Result[HttpException, A]` — e.g., `HttpUrl.parse` (`HttpUrl.scala:132`), `HttpCodec[A].decode` returns `Result[Throwable, A]` (`HttpCodec.scala:24`). Callers lift via `Abort.get`.

### `Maybe` / `Result` discipline

`Option` / `Either` / `Try` are **absent** from kyo-shaped public types. `Maybe[X]` is universal:

- `HttpCookie.maxAge: Maybe[Duration] = Absent` (`HttpCookie.scala:24`).
- `HttpUrl.query(name): Maybe[String]` (`HttpUrl.scala:80`).
- `HttpServerConfig.openApi: Maybe[OpenApiEndpoint]` (`HttpServerConfig.scala:61`).
- `HttpClientConfig.baseUrl: Maybe[HttpUrl]` (`HttpClientConfig.scala:55`).

The only `Option` usage is in Schema-derived wire records: `HttpOpenApi.Info.description: Option[String]` (`HttpOpenApi.scala:62`), `FlagAdmin.FlagInfo.value: Option[String]` (`FlagAdmin.scala:151`). Per `feedback_json_use_option`, new code uses `Maybe`.

---

## 5. Builder / config patterns

### Canonical config shape: case-class + fluent + `default`

`HttpServerConfig` (`HttpServerConfig.scala:52-88`) is the exemplar:

```
case class HttpServerConfig(
  port: Int,
  host: String,
  ...
) derives CanEqual:
  def port(p: Int): HttpServerConfig = copy(port = p)
  def host(h: String): HttpServerConfig = copy(host = h)
  ...

object HttpServerConfig:
  val default: HttpServerConfig = HttpServerConfig(port = 0, host = "127.0.0.1", ...)
```

Rules:

1. Case class carries the field set.
2. Per-field fluent setter named for the field: `def port(p: Int): HttpServerConfig = copy(port = p)`. No `with*` prefix. Examples: `HttpServerConfig.scala:68-87`, `HttpClientConfig.scala:72-81`, `HttpTransportConfig.scala:31-35`, `HttpRoute.Metadata` (`HttpRoute.scala:543-551`).
3. `val default` in companion (`HttpServerConfig.scala:92`, `HttpTransportConfig.scala:39`, `HttpTlsConfig.scala:47`). `HttpClientConfig` instead uses constructor defaults (`HttpClientConfig.scala:54-64`) so `HttpClientConfig()` works.
4. `copy` is public (case class) but fluent setters are documented path.
5. Validation via `require` at construction (`HttpClientConfig.scala:65-70`).
6. Nested sub-records via `Maybe[Sub]` field + setter that wraps in `Present`: `def cors(c: Cors): HttpServerConfig = copy(cors = Present(c))` (`HttpServerConfig.scala:76`).

### Sub-record placement

A sub-record only used as a field of one config nests in that config's companion: `HttpServerConfig.OpenApiEndpoint` (`HttpServerConfig.scala:110`), `HttpServerConfig.Cors` (`:117`), `HttpTlsConfig.ClientAuth` (`HttpTlsConfig.scala:41`), `HttpTlsConfig.Version` (`:44`), `HttpWebSocket.Config` (`HttpWebSocket.scala:110`). Standalone configs reused across surfaces get their own file.

---

## 6. Internal / `private[kyo]` discipline

### `package kyo.internal` vs `private[kyo]` at top level

Implementation classes live in `kyo.internal.<sub>` and are declared `final private[kyo] class …` or `private[kyo] object …`. Sub-packages:

- `kyo/internal/client/` — `HttpClientBackend`, `ConnectionPool`, `HttpConnection`
- `kyo/internal/server/` — `HttpRouter` (`HttpRouter.scala:20`), `RouteLookup`, `UnsafeServerDispatch`, `RouteUtil`, `ResponseWriter`, `StreamContext`
- `kyo/internal/transport/` — `Transport` (`Transport.scala:25`), `Connection`, `IoDriver`, `IoDriverPool`, `Listener`, pumps, streams
- `kyo/internal/codec/` — `ParsedRequest`, `OpenApiGenerator`, `OpenApiMacro`
- `kyo/internal/http1/` — wire parser
- `kyo/internal/util/` — `ByteStream`, `GrowableByteBuffer`, `Sha1`
- `kyo/internal/websocket/` — `WebSocketCodec`

`private[kyo]` at `package kyo` is rare and used for:

- Bridge helpers on public types: `HttpException.stripQuery` (`HttpException.scala:33`), `HttpHeaders.fromChunk` (`HttpHeaders.scala:46`), `HttpHeaders.intToString` (`HttpHeaders.scala:501`).
- Private primary ctor with public scrubbing `apply`: `HttpTimeoutException private (...)` (`HttpException.scala:94`) and companion `apply` (`:99`).
- Cached internals: `HttpFilter.Factory.composedServer` (`HttpFilter.scala:417`).
- Server-dispatch hooks on `HttpHandler` (`HttpHandler.scala:44-87`).
- Framework-only constructors: `HttpRawConnection private[kyo] (...)` (`HttpRawConnection.scala:11`), `HttpWebSocket private[kyo] (...)` (`HttpWebSocket.scala:30`).
- "Match-only" leaf errors: `HttpProtocolException private[kyo]` (`HttpException.scala:335`), `HttpPayloadTooLargeException private[kyo]` (`:341`), `HttpConnectionClosedException private[kyo]` (`:347`).

### `*Impl` naming

Not used. Implementation classes carry role names: `HttpClientBackend`, `HttpRouter`, `NioTransport`, `NativeTransport`, `JsTransport`. `ListenerUnsafe` (`HttpServer.scala:206`) is a private inner class.

### Extensions over `kyo.internal` carriers

`extension (self: HttpServer)` (`HttpServer.scala:41`) and `extension (self: HttpClient)` (`HttpClient.scala:71`) declared inside the public companion. Carrier types are package-internal.

---

## 7. Error type layering

### Sealed `HttpException` tree (root → category → leaf)

```
HttpException                              (HttpException.scala:28)
├── HttpConnectionException                (HttpException.scala:49)
│   ├── HttpConnectException               (HttpException.scala:53)
│   ├── HttpConnectTimeoutException        (HttpException.scala:62)
│   └── HttpPoolExhaustedException         (HttpException.scala:68)
├── HttpRequestException                   (HttpException.scala:90)
│   ├── HttpTimeoutException               (HttpException.scala:94)
│   ├── HttpRedirectLoopException          (HttpException.scala:104)
│   └── HttpStatusException                (HttpException.scala:125)
├── HttpServerException                    (HttpException.scala:146)
│   ├── HttpBindException                  (HttpException.scala:150)
│   └── HttpHandlerException               (HttpException.scala:159)
├── HttpDecodeException                    (HttpException.scala:191)
│   ├── HttpUrlParseException              (HttpException.scala:195)
│   ├── HttpFieldDecodeException           (HttpException.scala:210)
│   ├── HttpMissingFieldException          (HttpException.scala:230)
│   ├── HttpJsonDecodeException            (HttpException.scala:244)
│   ├── HttpFormDecodeException            (HttpException.scala:255)
│   ├── HttpUnsupportedMediaTypeException  (HttpException.scala:270)
│   ├── HttpStreamingDecodeException       (HttpException.scala:285)
│   ├── HttpMissingBoundaryException       (HttpException.scala:298)
│   ├── HttpProtocolException              (HttpException.scala:335, private[kyo] ctor)
│   ├── HttpPayloadTooLargeException       (HttpException.scala:341, private[kyo] ctor)
│   └── HttpConnectionClosedException      (HttpException.scala:347, private[kyo] ctor)
└── HttpWebSocketException                 (HttpException.scala:318)
    └── HttpWebSocketHandshakeException    (HttpException.scala:322)
```

Root: `sealed abstract class HttpException(message: Text, cause: Text | Throwable = "")(using Frame) extends KyoException(...)` (`HttpException.scala:28-29`). Cause is `Text | Throwable` so structured text threads without wrapping.

### Smart-constructor shape

Pattern: **private case-class primary ctor**, **public companion `apply` that scrubs sensitive data and rebuilds with `new`**:

```scala
case class HttpTimeoutException private (duration: Duration, method: String, url: String)(using Frame) extends ...
object HttpTimeoutException:
    def apply(duration: Duration, method: String, url: String)(using Frame) =
        new HttpTimeoutException(duration, method, HttpException.stripQuery(url))
```
(`HttpException.scala:94-101`)

Same shape in `HttpRedirectLoopException` (`:104-122`), `HttpStatusException` (`:125-135`, two overloads), `HttpUrlParseException` (`:195-207`), `HttpFieldDecodeException` (`:210-227`).

### "Match-only" leaves

`case class … private[kyo]` (no public ctor): `HttpProtocolException` (`:335`), `HttpPayloadTooLargeException` (`:341`), `HttpConnectionClosedException` (`:347`).

### Named error constants

Not used. Match on case-class types + field values (e.g., `case HttpStatusException(status, _, _, _) if status.code == 404`). Status codes themselves have named cases (`HttpStatus.NotFound` at `HttpStatus.scala:112`).

### Per-call `Result[E, A]`

Used for synchronous parsing: `HttpUrl.parse` (`HttpUrl.scala:132`), `HttpRequest.parse` (`HttpRequest.scala:55`), `HttpRequest.getRaw(rawUrl)` etc. (`HttpRequest.scala:66-72`). Caller lifts via `Abort.get`. Effect-row methods (`HttpClient.getJson`) lift internally.

---

## 8. Wire / message types

| type | shape | derives | file:line |
|---|---|---|---|
| `HttpRequest[Fields]` | case class — `method`, `url`, `headers`, `fields` | — | `HttpRequest.scala:24` |
| `HttpRequest.Part` | nested case class — multipart part | `CanEqual` | `HttpRequest.scala:79` |
| `HttpResponse[Fields]` | case class — `status`, `headers`, `fields`, `rawBody` | — | `HttpResponse.scala:23` |
| `HttpResponse.Halt` | nested case class — short-circuit | — | `HttpResponse.scala:169` |
| `HttpHeaders` | opaque type (`Chunk[String] \| Array[Byte]`) | `CanEqual` (manual given) | `HttpHeaders.scala:30, :34` |
| `HttpUrl` | final case class — parsed URL | `CanEqual` | `HttpUrl.scala:32` |
| `HttpMethod` | opaque type over `String` | `CanEqual` (manual given) | `HttpMethod.scala:8, :11` |
| `HttpStatus` | sealed abstract class with nested enums | `CanEqual` | `HttpStatus.scala:25` |
| `HttpCookie[A]` | case class with fluent setters | `CanEqual` (parameterized given) | `HttpCookie.scala:21, :41` |
| `HttpAddress` | sealed enum (`Tcp` / `Unix`) | `CanEqual` | `HttpAddress.scala:18` |
| `HttpQueryParams` | opaque type over `Seq[(String, String)]` | — | `HttpQueryParams.scala:13` |
| `HttpSseEvent[+A]` | case class — typed SSE event | `CanEqual` | `HttpSseEvent.scala:10` |
| `HttpRawConnection` | final class, `private[kyo]` ctor | — | `HttpRawConnection.scala:11` |
| `HttpWebSocket` | final class, `private[kyo]` ctor | — | `HttpWebSocket.scala:30` |
| `HttpWebSocket.Payload` | sealed enum (`Text` / `Binary`) | `CanEqual` | `HttpWebSocket.scala:90` |

Rules:

- **Case class** — plain data records with public construction (request, response, cookie, URL, SSE event).
- **`opaque type`** — primitive carrier we forbid casual cross-typing on (`HttpMethod`, `HttpHeaders`, `HttpQueryParams`).
- **`enum`** — closed value-shaped alternatives (`HttpAddress`, `HttpWebSocket.Payload`, `HttpCookie.SameSite`, `HttpTlsConfig.*`; `HttpPath` is parametric GADT-style).
- **Sealed abstract + nested enums** — when supertype carries common state (`HttpStatus`) and cases split into themed groups.
- **`final class` + `private[kyo]` ctor** — lifecycle-bound handles the framework hands out (`HttpRawConnection`, `HttpWebSocket`).

`derives CanEqual` is near-universal. Exceptions: parametric `CanEqual` given manually (`HttpCookie`, `HttpHeaders`, `HttpMethod`).

---

## 9. Cross-platform pattern

```
kyo-http/
  shared/src/main/scala/kyo/         ← all public types, all business logic
  shared/src/main/scala/kyo/internal/ ← shared internals (Transport, Connection, ...)
  jvm/src/main/scala/kyo/internal/    ← JVM-only: NioTransport, NioIoDriver, NioHandle, NioTlsState
  native/src/main/scala/kyo/internal/ ← Native-only: NativeTransport, Epoll/Kqueue, TlsBindings, PosixBindings
  js/src/main/scala/kyo/internal/     ← JS-only: JsTransport, JsHandle, JsIoDriver
```

### `HttpPlatformTransport` pattern

Each platform supplies one identically-named file declaring `private[kyo] object HttpPlatformTransport` with `lazy val transport: Transport[?]`:

- `kyo-http/jvm/.../HttpPlatformTransport.scala` — `NioTransport.init(...)`
- `kyo-http/native/.../HttpPlatformTransport.scala` — `NativeTransport.init(...)` with `PollerBackend.default`
- `kyo-http/js/.../HttpPlatformTransport.scala` — `JsTransport.init(...)` with `poolSize = 1`

`HttpServer.initUnscoped` (`HttpServer.scala:116`) and `HttpClient.defaultClient` (`HttpClient.scala:49`) reference `HttpPlatformTransport.transport`; the build's platform supplies it.

### Zero platform-specific public types

Public surface identical across JVM / Native / JS. No `HttpClientJvm` / `HttpServerNative` / per-platform extension methods. Carrier types in `kyo.internal` (`Transport[Handle]`, `Connection[Handle]`) are parameterized over `Handle`, so the platform fills the type parameter without leaking it. No `*Platform.scala` spreading one feature across `Foo.scala` + `FooJvm.scala`. Native and JS each ship a `java/security/SecureRandom.scala` shim (not public).

---

## 10. Top-level effect entry-point pattern

### Two opaque-typed handles

- `HttpServer` (`HttpServer.scala:37`) — opaque alias for `HttpServer.Unsafe`. Companion: `init` / `initWith` / `initUnscoped` / `initUnscopedWith`; `extension (self: HttpServer)` for runtime API (`address`, `port`, `close`, `await`, `unsafe`).
- `HttpClient` (`HttpClient.scala:41`) — opaque alias for `HttpClientBackend[?]`. Companion: `init` / `initUnscoped`, fiber-local API (`let`, `use`, `update`, `withConfig`), typed convenience methods (`getJson`, `postJson`, `getSseJson`, `webSocket`, `connectRaw`).

No top-level `Http` effect. The closest analog is the `Async & Abort[HttpException]` row that surfaces on every client method.

### Lifecycle: `init` vs `initUnscoped`

Both transports follow the same four-method pattern (`HttpServer.scala:71-91`, `HttpClient.scala:130-150`):

- `init(...): T < (Async & Scope)` — Scope-managed.
- `initUnscoped(...): T < Async` — caller closes explicitly.
- `initWith(...)(f: T => A < S): A < (S & Async & Scope)`.
- `initUnscopedWith(...)(f: ...)`.

### Fiber-local config

`HttpClient` carries `private val local: Local[(HttpClient, HttpClientConfig)]` (`HttpClient.scala:52`): `let(client) { v }`, `withConfig(_.timeout(...)) { v }` (compose), `withConfig(config) { v }` (replace).

### Middleware stack

Filters compose via `andThen` on `HttpFilter` (`HttpFilter.scala:54`). `HttpHandler.init` (`HttpHandler.scala:105`) wraps user handler in `HttpFilter.Factory.composedServer.andThen(route.filter)`. Per-route via `route.filter(f)` (`HttpRoute.scala:73`); global via `HttpFilter.Factory` SPI loaded by `ServiceLoader` (`HttpFilter.scala:413-433`).

---

## 11. `derives Schema / CanEqual` discipline

### `derives CanEqual` is the default

All enums and most case classes carry it. Parametric types use manual `given`: `HttpHeaders` (`:34`), `HttpMethod` (`:11`), `HttpCookie` (`:41`), `HttpRoute.ContentType` (`:192`).

### `derives Schema, CanEqual` — wire records

Used on types that travel as JSON:

- `HttpOpenApi` and nested models (`HttpOpenApi.scala:30, :57, :63, :67, :75, :81, :86, :98, :108, :124, :132`).
- `FlagAdmin.FlagInfo` / `.HistoryInfo` / `.ErrorResponse` / `.ReloadResponse` (`FlagAdmin.scala:157, :164, :169, :177`).

Domain models passed to `bodyJson[A]` are user code; kyo-http doesn't derive `Schema` on framework types like `HttpRequest` / `HttpResponse`.

### `derives Json` not used

The module uses `kyo.Json.encode/decode` (`HttpOpenApi.scala:41`, `FlagAdmin.scala:60`), which finds `Schema[A]`. No `derives Json` at the public surface.

---

## 12. File-header convention

No `// PUBLIC <role>` header banner exists. (Grep `^// PUBLIC` over `shared/src/main/scala/kyo/*.scala` returns no hits.) Don't invent one.

Every public file has:

1. `package kyo` on line 1.
2. Optional imports (typically `import kyo.*`).
3. Scaladoc above the primary type with role, lifecycle, `@see` cross-references.

---

## 13. Doc / Scaladoc conventions

### Type-level Scaladoc

Every public type gets a 5-30 line block. Structure:

1. One-sentence summary.
2. Body — model / lifecycle / interaction rules.
3. Optional `WARNING:` / `Note:` / `IMPORTANT:` callouts.
4. `@param` / `@tparam` for non-obvious meaning.
5. `@see [[kyo.XYZ]]` listing 2-4 neighbours.

Examples: `HttpException.scala:5-27`, `HttpClient.scala:7-40`, `HttpRoute.scala:5-39`.

### Member-level Scaladoc

Short docs when behaviour isn't obvious. Field-level `@param` inlined where useful (`HttpClientConfig.scala:19-53`, `HttpTransportConfig.scala:8-17`). Setters get docs only when they encode policy (`HttpResponse.scala:43-54`: `cacheControl`, `etag`, `contentDisposition`).

---

## 14. Anti-patterns NOT used

From grep-misses and code-quality feedback:

1. **No `Future` / `Promise` / `Either` / `Option` / `Try` at public surface.** Only `Maybe`, `Result`, effect rows. Schema-derived records (`HttpOpenApi`, `FlagAdmin`) use `Option` for upstream-schema reasons.
2. **No `*Impl` / `*Service` / `*Manager` / `*Provider` / `*Helper` / `*Util`.** Impl classes carry role names; all under `kyo.internal`.
3. **No exception-throwing factories.** Even `HttpUrl.parse` returns `Result[HttpException, HttpUrl]` (`HttpUrl.scala:132`); `getOrThrow` only in private helpers (`HttpClientConfig.scala:72`).
4. **No `Map`/`Set` of `String → Any` on public surface.** Use `HttpHeaders` / `HttpQueryParams` opaque types.
5. **No `String` for typed identifiers.** `HttpMethod` is opaque over `String` with named constants + `unsafe(name)`.
6. **No `withFoo` builder-prefix.** Setters are named for the field.
7. **No `type X = ...` aliases.** Per `feedback_no_type_aliases`, no `type` declarations anywhere in `shared/src/main/scala/kyo/`.
8. **No `given Isolate[...]`** at user-visible scope.
9. **No throws as control flow.** Use `Abort.fail(Halt(response))` (`HttpResponse.scala:171`).
10. **No `Kyo.lift`** in public API.
11. **No default params on `private` / `private[kyo]` methods.**
12. **No `AllowUnsafe` / `Frame.internal` in user signatures.** Internal uses (`HttpFilter.scala:419`, `HttpClient.scala:48`) have explanatory comments.
13. **No `asInstanceOf` at public surface** except three justified existential-dispatch casts (`HttpHandler.scala:83, :111`, `HttpClient.scala:795-801`).

---

## 15. Summary — "the template"

An aligning module follows these 15 rules:

1. **Prefix every public type with the module brand** (kyo-http uses `Http*`; kyo-jsonrpc uses `JsonRpc*`). No bare nouns at `package kyo` — `Method`, `Status`, `Url`, `Connection`, `Frame`, `Message` all carry the prefix.
2. **One opaque-typed entry-point per transport role.** `opaque type Foo = Foo.Unsafe` (or `= FooBackend[?]`); companion factories `init` / `initWith` / `initUnscoped` / `initUnscopedWith`; `extension (self: Foo)` for runtime API; nested `Unsafe` for unsafe tier.
3. **Co-locate sealed error root + categories + leaves in one file.** Root: `sealed abstract class XException(message: Text, cause: Text | Throwable = "")(using Frame) extends KyoException(...)`. Leaves: `case class … private (...)` with public companion `apply` that scrubs sensitive data; framework-only leaves use `private[kyo]` ctor.
4. **Use `Maybe` / `Result` / `Chunk` / `Span` — never `Option` / `Either` / `Try` / `List` / `Array`** on public boundaries. Schema-derived records inherit upstream conventions.
5. **Effect rows spelled at every use site.** Standard rows: `Async`, `Async & Abort[XException]`, `Async & Abort[XException] & Scope`. No `type Eff = ...`.
6. **Config = case class + per-field fluent setter + companion `val default`.** Setter name is the field name. Optional clusters via `Maybe[SubRecord]` with a setter wrapping in `Present`.
7. **Sub-records nest in the owning config's companion.** Standalone reused configs get their own file.
8. **Impl classes in `kyo.internal.<sub>` with `final private[kyo] class` / `private[kyo] object`.** Sub-packages by role: `client/`, `server/`, `transport/`, `codec/`, `util/`. No `*Impl`; classes carry role names.
9. **Framework-only ctors use `final class … private[kyo] (...)`** with the companion supplying instances.
10. **`derives CanEqual` on every public value type;** `derives Schema, CanEqual` on wire records. Manual `given CanEqual` only when parametric or opaque.
11. **One file per top-level public type**, with two exceptions: (a) sealed ADTs co-locate root + leaves; (b) types only meaningful inside another type nest in its companion.
12. **Nested namespace objects use lowercase** (`HttpFilter.server`); nested types stay capitalized.
13. **Cross-platform partitions as `shared/` (all public + business logic) + `<platform>/` (one `*PlatformTransport.scala` object).** Public API identical across platforms; platform symbols stay in `kyo.internal`.
14. **Every public type has Scaladoc** with summary, body, `WARNING` / `Note` / `IMPORTANT` callouts, and `@see [[kyo.XYZ]]` to 2-4 neighbours.
15. **No file-header banner comments. No `// PUBLIC` markers.**

---

### Appendix: file-count summary

- 28 public files in `kyo-http/shared/src/main/scala/kyo/`.
- 25 internal files in `kyo-http/shared/src/main/scala/kyo/internal/` (7 sub-packages).
- 5 JVM internal files; 10 Native internal files (+ `SecureRandom` shim); 4 JS internal files (+ shim).
- 0 public files in `jvm/`, `native/`, `js/` — every public type lives in `shared/`.

This is the alignment baseline.
