# A4. Naming and Nesting Alignment: kyo-jsonrpc vs kyo-http

Purpose: enumerate structural and naming deltas between `kyo-jsonrpc/shared` and `kyo-http/shared` so Agent C can produce a per-phase cleanup plan. Sources inspected: kyo-jsonrpc shared/jvm public + internal, `kyo-jsonrpc-http`, and the kyo-http counterparts. The A1/A2 surveys referenced in the brief are not present in `kyo-jsonrpc/.flow/api-cleanup/`; inventory derived directly from sources.

## 1. Naming-Prefix Table

### kyo-http (prefix discipline)

| Prefixed (`Http*`) | File:line |
|---|---|
| `HttpAddress` | `kyo-http/shared/src/main/scala/kyo/HttpAddress.scala:18` |
| `HttpClient` | `kyo-http/shared/src/main/scala/kyo/HttpClient.scala:41` |
| `HttpClientConfig` | `kyo-http/shared/src/main/scala/kyo/HttpClientConfig.scala:54` |
| `HttpCodec` | `kyo-http/shared/src/main/scala/kyo/HttpCodec.scala:22` |
| `HttpCookie` | `kyo-http/shared/src/main/scala/kyo/HttpCookie.scala:21` |
| `HttpException` and ~25 concrete subclasses | `kyo-http/shared/src/main/scala/kyo/HttpException.scala:28-347` |
| `HttpFilter`, `HttpFormCodec`, `HttpHandler` | `kyo-http/shared/src/main/scala/kyo/HttpFilter.scala:43`, `HttpFormCodec.scala:25`, `HttpHandler.scala:34` |
| `HttpHeaders`, `HttpMethod`, `HttpOpenApi` | `HttpHeaders.scala:30`, `HttpMethod.scala:8`, `HttpOpenApi.scala:25` |
| `HttpPath`, `HttpQueryParams`, `HttpRawConnection`, `HttpRequest`, `HttpResponse`, `HttpRoute` | `HttpPath.scala:25`, `HttpQueryParams.scala:13`, `HttpRawConnection.scala:11`, `HttpRequest.scala:24`, `HttpResponse.scala:23`, `HttpRoute.scala:40` |
| `HttpServer`, `HttpServerConfig`, `HttpSseEvent` | `HttpServer.scala:37`, `HttpServerConfig.scala:52`, `HttpSseEvent.scala:10` |
| `HttpStatus`, `HttpTlsConfig`, `HttpTransportConfig`, `HttpUrl`, `HttpWebSocket` | `HttpStatus.scala:25`, `HttpTlsConfig.scala:21`, `HttpTransportConfig.scala:24`, `HttpUrl.scala:126`, `HttpWebSocket.scala:30` |

| Not prefixed (kyo-http) | File:line | Note |
|---|---|---|
| `FlagAdmin`, `FlagSync` | `kyo-http/shared/src/main/scala/kyo/FlagAdmin.scala:*`, `FlagSync.scala:*` | sibling `Flag`/`DynamicFlag`/`StaticFlag` types live in kyo-core; the kyo-http additions use the same `Flag*` family, not the `Http*` family |

Discipline: every HTTP-specific type gets `Http`. The two unprefixed top-levels (`FlagAdmin`, `FlagSync`) inherit a different family prefix (`Flag*`) and are off-domain extensions.

### kyo-jsonrpc (current state)

| Prefixed (`JsonRpc*`) | File:line |
|---|---|
| `JsonRpcCodec` | `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcCodec.scala:9` |
| `JsonRpcEndpoint` (+ nested `Pending`, `Config`) | `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEndpoint.scala:7,82,89` |
| `JsonRpcEnvelope` (+ cases `Request`, `Notification`, `Response`, `Malformed`) | `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEnvelope.scala:7-25` |
| `JsonRpcError` | `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcError.scala:11` |
| `JsonRpcId` (+ `Num`, `Str`) | `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcId.scala:9-11` |
| `JsonRpcMethod` (+ `Kind`, `RequestMethod`, `NotificationMethod`) | `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcMethod.scala:14,26,81,113` |
| `JsonRpcResponse` | `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcResponse.scala:12` |
| `JsonRpcTransport` | `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcTransport.scala:6` |
| `JsonRpcHttpTransport` (jsonrpc-http module) | `kyo-jsonrpc-http/src/main/scala/kyo/JsonRpcHttpTransport.scala:4` |
| `JsonRpcTransportJvm` | `kyo-jsonrpc/jvm/src/main/scala/kyo/JsonRpcTransportJvm.scala:10` |

| Not prefixed (kyo-jsonrpc) | File:line |
|---|---|
| `CancellationPolicy` | `kyo-jsonrpc/shared/src/main/scala/kyo/CancellationPolicy.scala:10` |
| `ExtrasEncoder` | `kyo-jsonrpc/shared/src/main/scala/kyo/ExtrasEncoder.scala:4` |
| `Framer` | `kyo-jsonrpc/shared/src/main/scala/kyo/Framer.scala:7` |
| `HandlerCtx` | `kyo-jsonrpc/shared/src/main/scala/kyo/HandlerCtx.scala:14` |
| `IdStrategy` | `kyo-jsonrpc/shared/src/main/scala/kyo/IdStrategy.scala:4` |
| `MessageGate` | `kyo-jsonrpc/shared/src/main/scala/kyo/MessageGate.scala:4` |
| `ProgressPolicy` | `kyo-jsonrpc/shared/src/main/scala/kyo/ProgressPolicy.scala:10` |
| `UnknownMethodPolicy` | `kyo-jsonrpc/shared/src/main/scala/kyo/UnknownMethodPolicy.scala:5` |
| `WireTransport` | `kyo-jsonrpc/shared/src/main/scala/kyo/WireTransport.scala:6` |

### Inconsistencies vs the kyo-http pattern

Every unprefixed kyo-jsonrpc type is JSON-RPC-specific (none is reused outside the module) and therefore breaks the kyo-http rule "module-specific public types carry the module prefix". kyo-http has 27 prefixed and 2 unprefixed (both off-domain); kyo-jsonrpc has 9 unprefixed out of 17 shared-package top-levels.

Two patterns kyo-jsonrpc breaks:

1. Suffix-on-noun for config values: `HttpClientConfig`, `HttpServerConfig`, `HttpTlsConfig`, `HttpTransportConfig` keep the prefix even with the generic "Config" suffix (HttpServerConfig.scala:52). kyo-jsonrpc has unprefixed `CancellationPolicy`, `ProgressPolicy`, `UnknownMethodPolicy`, `IdStrategy` (CancellationPolicy.scala:10, ProgressPolicy.scala:10, UnknownMethodPolicy.scala:5, IdStrategy.scala:4).
2. Codec/Transport siblings keep the prefix: `HttpCodec`, `HttpFormCodec`, `HttpTransportConfig` (HttpCodec.scala:22, HttpFormCodec.scala:25, HttpTransportConfig.scala:24). kyo-jsonrpc has unprefixed `Framer`, `WireTransport`, `ExtrasEncoder` (Framer.scala:7, WireTransport.scala:6, ExtrasEncoder.scala:4).

## 2. Per-Type Rename Candidates

The decision rule is: if kyo-http would prefix it, kyo-jsonrpc should too unless the type can plausibly be reused by a non-JSON-RPC module. The only kyo-jsonrpc types with that property are `Framer` and `WireTransport`, both of which are generic byte-stream concepts (line-delimited framing, byte send/incoming/close); we keep both options on the table below.

| Current name | Proposed name | Rationale (kyo-http precedent) |
|---|---|---|
| `Framer` (Framer.scala:7) | `JsonRpcFramer` if kept in kyo-jsonrpc, else move unprefixed to a future shared module | The two presets (`lineDelimited`, `contentLength` at Framer.scala:17,28) are LSP/CDP-specific framings; no non-JSON-RPC consumer exists today. Same prefix rule as `HttpFormCodec` (HttpFormCodec.scala:25). |
| `WireTransport` (WireTransport.scala:6) | `JsonRpcWireTransport` (if kept) or relocate to a shared `kyo-net` module | Mirrors `HttpTransportConfig`/`HttpRawConnection` naming (HttpTransportConfig.scala:24, HttpRawConnection.scala:11). Until extraction, keep the prefix. |
| `HandlerCtx` (HandlerCtx.scala:14) | nest as `JsonRpcMethod.Context` or `JsonRpcMethod.HandlerCtx` | This type only appears in `JsonRpcMethod.apply`'s handler signature (JsonRpcMethod.scala:29) and `JsonRpcMethod.handle` (JsonRpcMethod.scala:22,92). kyo-http's analogue `HttpRoute.RequestDef`/`ResponseDef` lives nested inside `HttpRoute` (HttpRoute.scala:237,392). Same pattern. |
| `MessageGate` (MessageGate.scala:4) | `JsonRpcEndpoint.Gate` (nested) or `JsonRpcGate` | Used only as the `gate: Maybe[MessageGate]` field on `JsonRpcEndpoint.Config` (JsonRpcEndpoint.scala:94). kyo-http nests its config-policy types: `HttpServerConfig.Cors` (HttpServerConfig.scala:117), `HttpServerConfig.OpenApiEndpoint` (HttpServerConfig.scala:110). Same shape. |
| `ExtrasEncoder` (ExtrasEncoder.scala:4) | `JsonRpcEndpoint.Extras` or `JsonRpcExtras` | Function-typed opaque consumed only by `JsonRpcEndpoint.call`/`notify`/`sendUnmatched` (JsonRpcEndpoint.scala:12,19,27). Comparable to nesting `HttpFilter.Factory` inside `HttpFilter` (HttpFilter.scala:74). |
| `IdStrategy` (IdStrategy.scala:4) | `JsonRpcEndpoint.IdStrategy` (nested) | Used only as the `idStrategy: IdStrategy` field on `JsonRpcEndpoint.Config` (JsonRpcEndpoint.scala:97). Same nesting case as kyo-http's `HttpServerConfig.OpenApiEndpoint`. |
| `UnknownMethodPolicy` (UnknownMethodPolicy.scala:5) | `JsonRpcEndpoint.UnknownMethodPolicy` (nested) | Used only as `unknownMethod: UnknownMethodPolicy` on `JsonRpcEndpoint.Config` (JsonRpcEndpoint.scala:93). Same case. |
| `CancellationPolicy` (CancellationPolicy.scala:10) | `JsonRpcEndpoint.CancellationPolicy` (nested) or `JsonRpcCancellationPolicy` | Used only as `cancellation: Maybe[CancellationPolicy]` on Config (JsonRpcEndpoint.scala:91). Hosts `lsp`/`mcp` presets (CancellationPolicy.scala:58,67) and helper type aliases `ParamsEncoder`/`ParamsDecoder` (CancellationPolicy.scala:20,21). |
| `ProgressPolicy` (ProgressPolicy.scala:10) | `JsonRpcEndpoint.ProgressPolicy` (nested) or `JsonRpcProgressPolicy` | Same shape as `CancellationPolicy`; `progress: Maybe[ProgressPolicy]` (JsonRpcEndpoint.scala:92), `lsp`/`mcp` presets (ProgressPolicy.scala:38,48). |
| `JsonRpcCodec` (JsonRpcCodec.scala:9) | keep | Already prefixed. Mirrors `HttpCodec` (HttpCodec.scala:22). |
| `JsonRpcEndpoint` (JsonRpcEndpoint.scala:7) | keep | Mirrors `HttpServer`/`HttpClient` discipline. |
| `JsonRpcEnvelope` (JsonRpcEnvelope.scala:7) | keep, but absorb `JsonRpcResponse` (see section 3) | Mirrors `HttpRequest`/`HttpResponse`. |
| `JsonRpcError` (JsonRpcError.scala:11) | keep | Mirrors `HttpException` hierarchy (HttpException.scala:28+). |
| `JsonRpcId` (JsonRpcId.scala:9) | keep | No analogue in kyo-http but follows the prefix rule. |
| `JsonRpcMethod` (JsonRpcMethod.scala:14) | keep | Mirrors `HttpRoute`/`HttpHandler` pair: `HttpRoute` is the contract (HttpRoute.scala:40), `HttpHandler` pairs it with a function (HttpHandler.scala:34). `JsonRpcMethod` currently does both jobs; this is a split candidate (see section 4). |
| `JsonRpcRequest` (internal/JsonRpcRequest.scala:8) | keep `private[kyo]`, optionally rename to `EnvelopeWireDecode` if scope is wider | Internal codec-only ADT; already package-private. |
| `JsonRpcResponse` (JsonRpcResponse.scala:12) | merge into `JsonRpcEnvelope.Response` | The existing `JsonRpcEnvelope.Response` (JsonRpcEnvelope.scala:19) and `JsonRpcResponse` (JsonRpcResponse.scala:12) both model the same wire shape with overlapping fields; the duplication is the strongest single delta vs kyo-http. |
| `JsonRpcTransport` (JsonRpcTransport.scala:6) | keep | Mirrors `HttpTransportConfig`/the internal `Transport` (HttpTransportConfig.scala:24, internal/transport/Transport.scala). |

## 3. Nesting Candidates

kyo-http reserves nesting for two roles: (a) types that exist only as fields/parameters of an outer type, and (b) co-modeled variants of an outer ADT (`HttpStatus.Success.OK`, HttpStatus.scala:50; `HttpPath` constructors, HttpPath.scala:25). The applicable kyo-jsonrpc candidates:

| Current top-level | Proposed location | kyo-http precedent | File:line |
|---|---|---|---|
| `MessageGate` | `JsonRpcEndpoint.Gate` | `HttpServerConfig.Cors` (HttpServerConfig.scala:117) | MessageGate.scala:4 referenced at JsonRpcEndpoint.scala:94 |
| `IdStrategy` | `JsonRpcEndpoint.IdStrategy` | `HttpServerConfig.OpenApiEndpoint` (HttpServerConfig.scala:110) | IdStrategy.scala:4 referenced at JsonRpcEndpoint.scala:97 |
| `UnknownMethodPolicy` | `JsonRpcEndpoint.UnknownMethodPolicy` | Same precedent | UnknownMethodPolicy.scala:5 referenced at JsonRpcEndpoint.scala:93 |
| `CancellationPolicy` | `JsonRpcEndpoint.CancellationPolicy` or top-level `JsonRpcCancellationPolicy` | `HttpServerConfig.Cors` (HttpServerConfig.scala:117); or `HttpTlsConfig.Version` (HttpTlsConfig.scala:44) | CancellationPolicy.scala:10 referenced at JsonRpcEndpoint.scala:91 |
| `ProgressPolicy` | `JsonRpcEndpoint.ProgressPolicy` or top-level `JsonRpcProgressPolicy` | Same precedent | ProgressPolicy.scala:10 referenced at JsonRpcEndpoint.scala:92 |
| `ExtrasEncoder` | `JsonRpcEndpoint.Extras` | `HttpFilter.Factory` (HttpFilter.scala:74 internal pattern) | ExtrasEncoder.scala:4 referenced at JsonRpcEndpoint.scala:12,19,27 |
| `HandlerCtx` | `JsonRpcMethod.Context` | `HttpRoute.RequestDef`/`ResponseDef` (HttpRoute.scala:237,392) | HandlerCtx.scala:14 referenced at JsonRpcMethod.scala:22,29,48,69 |
| `JsonRpcResponse` | `JsonRpcEnvelope.ResponseWire` (or absorb into `JsonRpcEnvelope.Response`) | `HttpRequest.Part` nested inside `HttpRequest` (HttpRequest.scala:79) | JsonRpcResponse.scala:12 |
| `Framer`, `WireTransport` | stay top-level; consider relocation to a `kyo-net`-style shared module later | `HttpHeaders` and `HttpMethod` stay top-level because they are end-user vocabulary, not config-fields | Framer.scala:7, WireTransport.scala:6 |

A subordinate decision: when a policy's preset surface is large (lsp/mcp encoders/decoders, 50+ lines), inlining inside `JsonRpcEndpoint` would balloon the file. The kyo-http parallel `HttpRoute.scala` is 554 lines and already inlines `RequestDef`, `ResponseDef`, `ContentType`, `Field`, `Metadata`, `ErrorMapping`; the same density is acceptable for `JsonRpcEndpoint.scala`. Alternatively, keep the policy types top-level and prefix them. Section 12 marks the fork.

## 4. File-Split Candidates (Rule 8b)

Survey of kyo-jsonrpc shared files with more than one public top-level type:

| File | Top-level types | Recommendation |
|---|---|---|
| `JsonRpcMethod.scala` | `JsonRpcMethod` (sealed trait, line 14), `JsonRpcMethod.Kind` enum (line 26), `RequestMethod`/`NotificationMethod` private (lines 81, 113) | Acceptable as-is. Private nested classes follow kyo-http's `WebSocketHttpHandler` pattern (HttpHandler.scala:94). Optionally split into `JsonRpcMethod` (trait + companion) and `JsonRpcHandler` (request handler counterpart) to mirror the kyo-http `HttpRoute`/`HttpHandler` split (HttpRoute.scala:40, HttpHandler.scala:34). |
| `CancellationPolicy.scala` | `CancellationPolicy` + private `LspCancelParams` and `McpCancelParams` (lines 23, 24) | Keep; private helpers belong with the consumer. Matches `HttpRoute.ErrorMapping` (HttpRoute.scala:526). |
| `JsonRpcEndpoint.scala` | `JsonRpcEndpoint`, `JsonRpcEndpoint.Pending`, `JsonRpcEndpoint.Config` | Acceptable; matches `HttpClient.Config`-style nesting in `HttpServerConfig`. Could be expanded if section 3 nestings adopted (adds Gate, IdStrategy, UnknownMethodPolicy, etc.). |

No current file violates Rule 8b: every file has a single primary top-level public type plus optionally nested or private auxiliaries. Internal files are equally well-partitioned.

Where the cleanup _adds_ types (e.g. by splitting `JsonRpcMethod` into a `JsonRpcMethod`/`JsonRpcHandler` pair, or by splitting policy presets per below), each new public type goes in its own file.

## 5. File-Merge Candidates (anti-split)

These are kyo-jsonrpc files whose contents are so small that a separate file feels like a single-symbol penalty without payoff:

| File | Lines | Top-level type | Merge target |
|---|---|---|---|
| `IdStrategy.scala` (kyo-jsonrpc/shared/src/main/scala/kyo/IdStrategy.scala) | 8 | `IdStrategy` enum | `JsonRpcEndpoint.scala` (nest under `JsonRpcEndpoint.IdStrategy`) |
| `MessageGate.scala` (MessageGate.scala) | 13 | `MessageGate` trait | `JsonRpcEndpoint.scala` (nest) |
| `ExtrasEncoder.scala` (ExtrasEncoder.scala) | 17 | `ExtrasEncoder` opaque | `JsonRpcEndpoint.scala` (nest) |
| `JsonRpcCodec.scala` (JsonRpcCodec.scala) | 17 | `JsonRpcCodec` trait | keep separate; mirrors `HttpCodec.scala` (40 lines) and is a public extension point. |
| `WireTransport.scala` (WireTransport.scala) | 18 | `WireTransport` trait | keep; user implementation surface. |
| `JsonRpcResponse.scala` (JsonRpcResponse.scala) | 24 | `JsonRpcResponse` case class | merge into `JsonRpcEnvelope.scala` (see section 3) |
| `JsonRpcEnvelope.scala` (JsonRpcEnvelope.scala) | 26 | `JsonRpcEnvelope` enum | absorb `JsonRpcResponse` |
| `UnknownMethodPolicy.scala` (UnknownMethodPolicy.scala) | 35 | `UnknownMethodPolicy` case class | `JsonRpcEndpoint.scala` (nest) |
| `JsonRpcId.scala` (JsonRpcId.scala) | 29 | `JsonRpcId` enum + Schema | keep; widely referenced ADT |
| `Framer.scala` (Framer.scala) | 37 | `Framer` trait + presets | keep separate (37 lines is consistent with kyo-http's smallest standalone files like `HttpSseEvent.scala`) |

The kyo-http baseline of "no public file under 30 lines" is approximate (HttpSseEvent.scala is ~25 lines), but the discipline holds where the type is a co-modeled fragment of an outer type. `IdStrategy`, `MessageGate`, `ExtrasEncoder` are all "field-type-only" auxiliaries and are exactly what kyo-http nests.

## 6. Companion-Content Alignment

kyo-http companions consistently hold the following content:

| Content kind | Example | Kyo-jsonrpc gap |
|---|---|---|
| `default` value for config | `HttpServerConfig.default` (HttpServerConfig.scala:92), `HttpClientConfig` defaults inline (HttpClientConfig.scala:54-64) | `JsonRpcEndpoint.Config()` uses Scala default-args (JsonRpcEndpoint.scala:89-99). No `.default` constant. Add `JsonRpcEndpoint.Config.default` for parity. |
| `init`/`initUnscoped` factory | `HttpServer.init` (HttpServer.scala:71-91), `HttpClient.init`/`initUnscoped` (HttpClient.scala:130-150) | `JsonRpcEndpoint.init` exists (JsonRpcEndpoint.scala:101) but lacks `initWith` / `initUnscoped` variants. The kyo-http precedent ships scoped and unscoped pairs. |
| Smart-constructor `private[kyo]` plus public factories | `HttpResponse.ok`, `HttpResponse.created`, etc. (HttpResponse.scala:64-100) | `JsonRpcResponse.success`/`failure` (JsonRpcResponse.scala:19,22) does this already; mirrors kyo-http. |
| `Unsafe` subobject for low-level construction | `HttpServer.Unsafe`/`HttpServer.Unsafe.init` (HttpServer.scala:145,166) | No `JsonRpcEndpoint.Unsafe`. Probably not needed yet; flag for future. |
| Type aliases co-located with companion | `CancellationPolicy.ParamsEncoder`/`ParamsDecoder` (CancellationPolicy.scala:20,21) | kyo-http does this sparingly (e.g. `HttpHeaders` opaque-type definitions, HttpHeaders.scala:30); current placement is correct. |
| Error-code constants on the error companion | `JsonRpcError.ParseError`, `MethodNotFound`, etc. (JsonRpcError.scala:14-24); plus smart constructors `methodNotFound`/`invalidRequest`/etc. (JsonRpcError.scala:26-40) | Mirrors kyo-http's `HttpStatus.OK`, `HttpStatus.NotFound` etc. (HttpStatus.scala:50+) but the kyo-http style is to use a `sealed abstract class HttpStatus` with case-object subclasses, not flat `val`s. Discrepancy; section 7 covers. |
| Companion extension methods | `extension (self: JsonRpcTransport.type) def webSocket` (kyo-jsonrpc-http/JsonRpcHttpTransport.scala:96-106), `extension (self: JsonRpcTransport.type) def unixDomain` (kyo-jsonrpc/jvm/JsonRpcTransportJvm.scala:36) | Companion-extension is the kyo idiom for cross-module factories; kyo-http does not use it (everything ships in the same JAR). Pattern is correct, retain. |

## 7. Effect-Row and Error-Idiom Alignment

| Dimension | kyo-http | kyo-jsonrpc | Alignment delta |
|---|---|---|---|
| Server-init effect row | `< (Async & Scope)` (HttpServer.scala:71) | `< (Sync & Async & Scope)` (JsonRpcEndpoint.scala:105) | `Sync` is implied by `Async`; remove `Sync` from `JsonRpcEndpoint.init` for parity. |
| Client effect row | `< (Async & Abort[HttpException])` (HttpClient.scala:81) | `< (Async & Abort[JsonRpcError \| Closed])` (JsonRpcEndpoint.scala:13) | Both follow `Async & Abort[E]`; consistent. |
| Transport `send` | `Unit < (Async & Abort[Closed])` style (kyo-http uses lower-level `Transport`; internal) | `Unit < (Async & Abort[Closed])` (JsonRpcTransport.scala:7) | Aligned. |
| Stream return | `Stream[V, Async & Abort[HttpException]]` (HttpClient.scala:626) | `Stream[T, Async & Abort[JsonRpcError \| Closed]]` (JsonRpcEndpoint.scala:42) | Aligned in shape. |
| Error ADT | `sealed abstract class HttpException`, ~20 concrete subclasses (HttpException.scala:28-347), all derive nothing (Throwable already) | `case class JsonRpcError(code, message, data)` with const factory methods (JsonRpcError.scala:11). Not a sealed hierarchy. | Major delta. kyo-http uses an open sealed hierarchy; kyo-jsonrpc uses one flat case class with `Int` discriminator. The JSON-RPC spec defines numeric codes, so the flat form is plausibly correct for the wire; but a kyo-http-style sealed hierarchy with `JsonRpcParseError`, `JsonRpcMethodNotFound`, etc. would let users pattern-match by type. Trade-off: wire-faithful (current) vs idiomatic-kyo (sealed hierarchy). Keep current; flag for design discussion. |
| Frame-aware throwables | `case class HttpTimeoutException ... (using Frame)` (HttpException.scala:94) | `JsonRpcError` is not a `Throwable`, no `Frame` capture | kyo-jsonrpc routes failures through `Abort[JsonRpcError]` only; the kyo-http hybrid (Throwable + Abort) is not adopted. Consistent within itself. |

## 8. Config / Builder Pattern Alignment

kyo-http config style (from `HttpServerConfig`):

- `case class` with all fields and per-field fluent setters (`def port(p: Int) = copy(port = p)`, HttpServerConfig.scala:68-87).
- Companion's `.default` constant (HttpServerConfig.scala:92-108).
- Nested co-modeled types (`Cors`, `OpenApiEndpoint`) live in the companion (HttpServerConfig.scala:110,117).

kyo-jsonrpc current state (from `JsonRpcEndpoint.Config`, JsonRpcEndpoint.scala:89-99):

- `case class` with `= default` Scala defaults inline on the primary constructor.
- No per-field fluent setters. Users must use `.copy(...)`.
- No `.default` constant.
- Co-modeled types are top-level (`IdStrategy`, `MessageGate`, `UnknownMethodPolicy`, ...).

Recommended changes for alignment:

1. Add fluent setters `def codec(c: JsonRpcCodec) = copy(codec = c)` etc.
2. Add `JsonRpcEndpoint.Config.default: JsonRpcEndpoint.Config`.
3. Nest the policy types under `JsonRpcEndpoint` (section 3) so the config object surface is one-stop.

Default-value discipline check: kyo-http requires non-negative `maxRedirects` etc. via `require(...)` in the case-class body (HttpClientConfig.scala:65-69). kyo-jsonrpc's `JsonRpcEndpoint.Config` does no `require` validation (JsonRpcEndpoint.scala:89-99). Add `require(maxInFlight.map(_ > 0).getOrElse(true), ...)`, `require(requestTimeout > Duration.Zero || requestTimeout == Duration.Infinity, ...)` for parity.

## 9. Cross-Platform Pattern Alignment

kyo-http distribution:

- `kyo-http/shared/src/main/scala/kyo/Http*.scala` — public API, 28 files.
- `kyo-http/shared/src/main/scala/kyo/internal/{client,codec,http1,server,transport,util,websocket}/` — internal subpackaged (kyo-http/shared/src/main/scala/kyo/internal/transport/Transport.scala etc.).
- `kyo-http/jvm/src/main/scala/kyo/internal/{HttpPlatformTransport,NioHandle,NioIoDriver,NioTlsState,NioTransport}.scala` — JVM transport.
- `kyo-http/js/src/main/scala/kyo/internal/{HttpPlatformTransport,JsHandle,JsIoDriver,JsTransport}.scala` — JS transport.
- `kyo-http/native/src/main/scala/kyo/internal/{HttpPlatformTransport,...}.scala` — Native transport.

Each platform provides a same-named `HttpPlatformTransport` so the public `HttpClient` / `HttpServer` calls through `kyo.internal.HttpPlatformTransport.transport` (HttpServer.scala:5, HttpClient.scala:4).

kyo-jsonrpc current state:

- `kyo-jsonrpc/shared/src/main/scala/kyo/{17 public files}` and `kyo/internal/{12 internal files}` flat (no subpackages).
- `kyo-jsonrpc/jvm/src/main/scala/kyo/JsonRpcTransportJvm.scala` (public) + `kyo-jsonrpc/jvm/src/main/scala/kyo/internal/UdsWireTransport.scala` (one internal).
- `kyo-jsonrpc/js/` and `kyo-jsonrpc/native/` have only `target/` directories; no source files yet.

Suggested alignment:

1. Group `kyo-jsonrpc/shared/src/main/scala/kyo/internal/` into subpackages mirroring kyo-http: `internal/codec/` for `JsonRpcCodecImpl`, `RawJsonParser`; `internal/transport/` for `InMemoryTransport`, `WireTransportAdapter`, `StdioWireTransport`; `internal/engine/` (new name) for `CancellationEngine`, `IdStrategyEngine`, `ProgressEngine`, `RateLimitEngine`, `JsonRpcEndpointImpl`; `internal/framing/` for `FramerImpl`. (Section 11 elaborates.)
2. The JS and Native directories are present but empty. Per `[feedback_all_platforms_all_tests]` and `[feedback_no_scope_cuts]`, both should host at least a `JsonRpcTransportNative.scala` / `JsonRpcTransportJs.scala` companion-extension file even if it only exposes a `webSocket` shim; the design currently delegates websocket to `kyo-jsonrpc-http` which already ships cross-platform.
3. The `JsonRpcTransportJvm` filename does not follow kyo-http's pattern where JVM-only public extensions are not platform-suffixed (kyo-http does not expose a `HttpServerJvm`; UDS lives in `HttpServerConfig.unixSocket` and is implemented at platform-internal layer). Consider renaming `JsonRpcTransportJvm` to a more action-named object like `JsonRpcUds` or fold the `unixDomain` constructor into a multi-platform `JsonRpcTransport.unixDomain` with platform-specific `private[kyo]` backends (the JS/Native variants would `Abort.fail(UnsupportedOperationException(...))` or use whatever the platform offers). The latter matches kyo-http: one public API, platform-specific implementations behind `internal.HttpPlatformTransport`.

## 10. derives Schema / CanEqual Alignment

kyo-http derives discipline (representative sample):

- Wire-shape value classes: `HttpRequest derives CanEqual` (HttpRequest.scala:24), `HttpResponse` no `derives` (`Record` is not Equal-friendly), `HttpSseEvent derives CanEqual` (HttpSseEvent.scala:15), `HttpOpenApi` and all its nested types `derives Schema, CanEqual` (HttpOpenApi.scala:30,57,63,67,75,81,86,98,108,124,132).
- ADTs: `HttpPath derives CanEqual` (HttpPath.scala:25), `HttpAddress derives CanEqual` (HttpAddress.scala:18), `HttpStatus derives CanEqual` (HttpStatus.scala:25), `HttpTlsConfig.ClientAuth` and `.Version` (HttpTlsConfig.scala:41,44).
- Config: `HttpServerConfig`, `HttpServerConfig.Cors`, `HttpServerConfig.OpenApiEndpoint`, `HttpClientConfig` (implicit-only?), `HttpTlsConfig` all `derives CanEqual` (HttpServerConfig.scala:67,115,123, HttpTlsConfig.scala:38).

kyo-jsonrpc current state:

| Type | `derives` | File:line | Should add/remove |
|---|---|---|---|
| `JsonRpcError` | `Schema, CanEqual` | JsonRpcError.scala:11 | keep |
| `JsonRpcResponse` | `Schema, CanEqual` | JsonRpcResponse.scala:16 | merge into `JsonRpcEnvelope.Response` and inherit its derives |
| `JsonRpcEnvelope` | `CanEqual` | JsonRpcEnvelope.scala:7 | add `Schema` once `Structure.Value` derives are stabilized (currently kyo-jsonrpc has `derives Schema` only on `JsonRpcError` and `JsonRpcResponse`, with `JsonRpcEnvelope` deliberately Schema-less because of the `Maybe[Structure.Value]` fields). Verify before committing. |
| `JsonRpcId` | `CanEqual` (line 9) + explicit `Schema` given (line 16-28) | JsonRpcId.scala:9,16 | keep; the explicit Schema handles the JSON-RPC "num-or-string" union which `derives Schema` cannot infer. |
| `JsonRpcMethod.Kind` | `CanEqual` | JsonRpcMethod.scala:26 | keep |
| `IdStrategy` | `CanEqual` | IdStrategy.scala:4 | keep |
| `MessageGate.Decision` | `CanEqual` | MessageGate.scala:8 | keep |
| `UnknownMethodPolicy` | `CanEqual` | UnknownMethodPolicy.scala:9 | keep |
| `UnknownMethodPolicy.UnknownAction` | `CanEqual` | UnknownMethodPolicy.scala:12 | keep |
| `CancellationPolicy` | `CanEqual` | CancellationPolicy.scala:17 | keep |
| `ProgressPolicy` | `CanEqual` | ProgressPolicy.scala:18 | keep |
| `JsonRpcEndpoint.Config` | none | JsonRpcEndpoint.scala:89 | add `derives CanEqual` to match `HttpServerConfig derives CanEqual` (HttpServerConfig.scala:67). |
| `JsonRpcEndpoint.Pending` | none | JsonRpcEndpoint.scala:82 | acceptable; contains a `Stream` which is not Equal-friendly. Mirrors kyo-http leaving `HttpResponse` undecided. |

## 11. Sub-Package Alignment (`kyo.internal.*`)

kyo-http `kyo.internal.*` subpackages and their roles:

| Subpackage | Files | Role |
|---|---|---|
| `kyo.internal.client` | `ConnectionPool`, `HttpClientBackend`, `HttpConnection` (internal/client/*.scala) | Client-side connection management |
| `kyo.internal.codec` | `OpenApiGenerator`, `OpenApiMacro`, `ParsedRequest`, `ParsedRequestBuilder`, `ParsedResponse` | Wire-encoding/decoding implementations |
| `kyo.internal.http1` | `ChunkedBodyDecoder`, `Http1ClientConnection`, `Http1Parser`, `Http1ResponseParser`, `Http1StreamContext` | Protocol-version-specific implementation |
| `kyo.internal.server` | `HttpRouter`, `ResponseWriter`, `RouteLookup`, `RouteUtil`, `StreamContext`, `UnsafeServerDispatch` | Server-side dispatch |
| `kyo.internal.transport` | `Connection`, `ConnectionBackedStream`, `IoDriver`, `IoDriverPool`, `ReadPump`, `Transport`, `TransportStream`, `WritePump`, `WriteResult` | Cross-cutting transport seam |
| `kyo.internal.util` | `ByteStream`, `ChannelBackedStream`, `GrowableByteBuffer`, `Sha1` | Generic helpers |
| `kyo.internal.websocket` | `WebSocketCodec` | WebSocket-specific |

kyo-jsonrpc has 12 internal files (kyo-jsonrpc/shared/src/main/scala/kyo/internal/*) all in one flat `kyo.internal` package, plus inconsistent package declarations:

- `package kyo\npackage internal` form: `CancellationEngine.scala`, `JsonRpcEndpointImpl.scala`, `ProgressEngine.scala`, `RateLimitEngine.scala` (4 files)
- `package kyo.internal` form: the other 8 files (FramerImpl.scala, IdStrategyEngine.scala, InMemoryTransport.scala, JsonRpcCodecImpl.scala, JsonRpcRequest.scala, RawJsonParser.scala, StdioWireTransport.scala, WireTransportAdapter.scala)

Both forms are semantically equivalent but the mix is a Rule 8a style break.

Proposed subpackage mirror:

| Proposed subpackage | Move targets | Why |
|---|---|---|
| `kyo.internal.codec` | `JsonRpcCodecImpl`, `RawJsonParser`, `JsonRpcRequest` (the internal wire shape) | Mirrors `kyo.internal.codec` in kyo-http |
| `kyo.internal.transport` | `InMemoryTransport`, `WireTransportAdapter`, `StdioWireTransport`, `UdsWireTransport` (currently jvm-internal) | Mirrors `kyo.internal.transport` |
| `kyo.internal.framing` (new) | `FramerImpl` | No kyo-http analogue; framing is JSON-RPC-specific |
| `kyo.internal.engine` (new) | `JsonRpcEndpointImpl`, `CancellationEngine`, `IdStrategyEngine`, `ProgressEngine`, `RateLimitEngine` | Mirrors the kyo-http `server` / `client` partition, "engine" because JSON-RPC is symmetric (no client/server distinction at this layer) |

Also standardize on a single `package kyo.internal.<sub>` form across all moved files.

## 12. Summary of Structural Deltas (Prioritized)

### High-value alignment (do first)

1. **Resolve the `JsonRpcResponse` / `JsonRpcEnvelope.Response` duplication.** Two case classes model the same wire shape (JsonRpcResponse.scala:12, JsonRpcEnvelope.scala:19). Pick one. The kyo-http equivalent has a single `HttpResponse` (HttpResponse.scala:23) and a single `HttpRequest` (HttpRequest.scala:24). Recommendation: keep `JsonRpcEnvelope.Response` and delete `JsonRpcResponse.scala`, lifting `success`/`failure` factories onto `JsonRpcEnvelope.Response`'s companion.
2. **Prefix the policy types.** Choose either nest-under-`JsonRpcEndpoint` (preferred; matches kyo-http `HttpServerConfig.Cors`, HttpServerConfig.scala:117) or prefix-rename to `JsonRpcCancellationPolicy` / `JsonRpcProgressPolicy` / `JsonRpcUnknownMethodPolicy` / `JsonRpcIdStrategy`. Same decision for `MessageGate` and `ExtrasEncoder`.
3. **Subpackage `kyo.internal`.** Move the 12 internal files into `internal/{codec,transport,framing,engine}` mirroring kyo-http (kyo-http/shared/src/main/scala/kyo/internal/{client,codec,http1,server,transport,util,websocket}). Standardize on `package kyo.internal.<sub>` form.
4. **Add fluent setters to `JsonRpcEndpoint.Config`.** Currently users must `.copy(field = ...)` (JsonRpcEndpoint.scala:89). kyo-http exposes `.port(p)`, `.host(h)`, etc. (HttpServerConfig.scala:68-87).
5. **Add `JsonRpcEndpoint.Config.default`.** Single source of truth for defaults, matches `HttpServerConfig.default` (HttpServerConfig.scala:92).
6. **Drop `Sync` from `JsonRpcEndpoint.init`'s effect row.** `Async` subsumes `Sync`; HttpServer is `< (Async & Scope)` (HttpServer.scala:71). JsonRpcEndpoint.init at JsonRpcEndpoint.scala:105 is `< (Sync & Async & Scope)`.
7. **Nest `HandlerCtx` inside `JsonRpcMethod`.** Used only there (JsonRpcMethod.scala:22,29,48,69). Mirrors `HttpRoute.RequestDef`/`ResponseDef` (HttpRoute.scala:237,392).

### Medium-value alignment (do after the high-value batch)

8. **Prefix `Framer` and `WireTransport`.** They are JSON-RPC-specific today (Framer.scala:7, WireTransport.scala:6); future extraction to `kyo-net` is hypothetical. Until then, `JsonRpcFramer` / `JsonRpcWireTransport` aligns with the kyo-http rule.
9. **`derives CanEqual` on `JsonRpcEndpoint.Config`.** Matches `HttpServerConfig derives CanEqual` (HttpServerConfig.scala:67).
10. **`require` validations on `JsonRpcEndpoint.Config`.** Match `HttpClientConfig`'s discipline (HttpClientConfig.scala:65-69).
11. **Reconcile JS / Native source presence.** Currently empty (only `target/`). Provide at least the cross-platform companion-extension shims; the `kyo-jsonrpc-http.webSocket` extension already works cross-platform but should be exposed as a single `JsonRpcTransport.webSocket` rather than scattered across `JsonRpcHttpTransport.webSocket` and `extension (self: JsonRpcTransport.type)` blocks (kyo-jsonrpc-http/JsonRpcHttpTransport.scala:96-106).
12. **Decide `JsonRpcTransportJvm`'s fate.** Either rename for clarity (e.g., `JsonRpcUds`) or fold `unixDomain` into `JsonRpcTransport` with platform-specific internals (kyo-jsonrpc/jvm/JsonRpcTransportJvm.scala:10-40 vs kyo-http's `HttpServerConfig.unixSocket`-driven approach at HttpServerConfig.scala:78).

### Nice-to-have (final pass)

13. Consider splitting `JsonRpcMethod` into `JsonRpcMethod` (contract) + `JsonRpcHandler` (function pairing) mirroring `HttpRoute` + `HttpHandler` (HttpRoute.scala:40, HttpHandler.scala:34). Currently `JsonRpcMethod.apply` does both in one call (JsonRpcMethod.scala:29). Deeper refactor; flag for design conversation.
14. Consider an `Unsafe` subobject on `JsonRpcEndpoint` mirroring `HttpServer.Unsafe` (HttpServer.scala:145) if low-level construction is needed.
15. Revisit `JsonRpcError`'s flat case-class vs kyo-http's sealed-hierarchy (HttpException.scala:28-347). Wire-faithful vs idiomatic. Trade-off, not an unambiguous fix.

### Open forks (Agent C should resolve before locking the plan)

- Nesting vs prefix-only for the policy types: `JsonRpcEndpoint.CancellationPolicy` (nested) vs `JsonRpcCancellationPolicy` (top-level). kyo-http leans nested for config-fields (HttpServerConfig.Cors at HttpServerConfig.scala:117) but top-level for cross-module vocabulary (HttpTlsConfig.scala:21).
- Whether `Framer` and `WireTransport` deserve prefix today or stay unprefixed in anticipation of `kyo-net` extraction (the planned extraction from kyo-http).
- `JsonRpcError` hierarchy: flat-with-int-code (current) vs sealed-with-subtypes (kyo-http-idiomatic). Design question, not a structural cleanup.
