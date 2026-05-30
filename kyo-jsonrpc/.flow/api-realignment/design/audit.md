# kyo-http vs kyo-jsonrpc — Comprehensive API Divergence Audit

Source-grounded comparison of every API-shape dimension where kyo-http has a convention. Every claim cites file:line. All `kyo-http/...` paths are `kyo-http/shared/src/main/scala/kyo/...` unless suffixed with a platform; same for `kyo-jsonrpc/...`.

---

## Axis 1: Type naming + prefix discipline

**kyo-http convention.** Every public top-level type in `package kyo` starts with the `Http` prefix. Census (28 files in `kyo-http/shared/src/main/scala/kyo/`): `HttpAddress` (`HttpAddress.scala:18`), `HttpClient` (`HttpClient.scala:41`), `HttpClientConfig` (`HttpClientConfig.scala:54`), `HttpCodec` (`HttpCodec.scala:22`), `HttpCookie` (`HttpCookie.scala:21`), `HttpException` + ~20 leaf subclasses (`HttpException.scala:28..347`), `HttpFilter` (`HttpFilter.scala:43`), `HttpFormCodec` (`HttpFormCodec.scala:21`), `HttpHandler` (`HttpHandler.scala:34`), `HttpHeaders` (`HttpHeaders.scala:30`), `HttpMethod` (`HttpMethod.scala:8`), `HttpOpenApi` (`HttpOpenApi.scala:25`), `HttpPath` (`HttpPath.scala:25`), `HttpQueryParams` (`HttpQueryParams.scala:13`), `HttpRawConnection` (`HttpRawConnection.scala:11`), `HttpRequest` (`HttpRequest.scala:24`), `HttpResponse` (`HttpResponse.scala:23`), `HttpRoute` (`HttpRoute.scala:40`), `HttpServer` (`HttpServer.scala:37`), `HttpServerConfig` (`HttpServerConfig.scala:52`), `HttpSseEvent` (`HttpSseEvent.scala:10`), `HttpStatus` (`HttpStatus.scala:25`), `HttpTlsConfig` (`HttpTlsConfig.scala:21`), `HttpTransportConfig` (`HttpTransportConfig.scala:24`), `HttpUrl` (`HttpUrl.scala`), `HttpWebSocket` (`HttpWebSocket.scala:84`). All 26+ top-level public types start with `Http`. Zero exceptions.

**kyo-jsonrpc current.** Six files in `kyo-jsonrpc/shared/src/main/scala/kyo/`. Every public top-level type is correctly prefixed with `JsonRpc`: `JsonRpcCodec` (`JsonRpcCodec.scala:22`), `JsonRpcEndpoint` (`JsonRpcEndpoint.scala:28`), `JsonRpcEnvelope` (`JsonRpcEnvelope.scala:26`), `JsonRpcError` (`JsonRpcError.scala:24`), `JsonRpcMethod` (`JsonRpcMethod.scala:27`), `JsonRpcTransport` (`JsonRpcTransport.scala:20`). The user-stated final-plan target was 15 top-level types; the actual is 6 because the campaign **nested** many former top-level types (`MessageGate`, `IdStrategy`, `UnknownMethodPolicy`, `CancellationPolicy`, `ProgressPolicy`, `ExtrasEncoder`) into `JsonRpcEndpoint`'s companion (`JsonRpcEndpoint.scala:130, 149, 194, 220, 303, 371`), nested `Framer`/`WireTransport` into `JsonRpcTransport`'s companion (`JsonRpcTransport.scala:40, 71`), and dropped `JsonRpcId` (it is now `JsonRpcEnvelope.Id` at `JsonRpcEnvelope.scala:60`). So the prefix-discipline axis is technically satisfied for the remaining top-level types, but the strategy chosen (aggressive nesting) is the **opposite** of what `D5-final-plan.md` resolved (which was `RENAME-WITH-PREFIX-KEEP-PUBLIC` for 9 of those).

| kyo-http top-level | kyo-jsonrpc top-level | Note |
|---|---|---|
| `HttpServer` | `JsonRpcEndpoint` | server analogue, but unified with client (see Axis 8) |
| `HttpClient` | (none — folded into `JsonRpcEndpoint`) | |
| `HttpRoute` | `JsonRpcMethod` | route analogue, but only single covariant `+S` (see Axis 3) |
| `HttpHandler` | (none — handler is a field on `JsonRpcMethod`) | |
| `HttpFilter` | `JsonRpcEndpoint.MessageGate` (nested) | Should be top-level `JsonRpcFilter`/`JsonRpcMessageGate` per D1 |
| `HttpException` (+ ~20 leaves) | `JsonRpcError` (flat case class) | Not a hierarchy (see Axis 10) |
| `HttpRequest` / `HttpResponse` | `JsonRpcEnvelope.{Request,Response,Notification,Malformed}` (nested) | Per the open Phase 09 proposal, these should be hoisted to top-level. |
| `HttpServerConfig` | `JsonRpcEndpoint.Config` (nested) | http nests nothing here; jsonrpc does. |
| `HttpClientConfig` | (none) | |
| `HttpTlsConfig` | (none) | |
| `HttpTransportConfig` | (none — UDS is just a factory on `JsonRpcTransport`) | |
| `HttpCodec` | `JsonRpcCodec` | |
| `HttpStatus` | (no analogue; codes are bare `Int` in `JsonRpcError`) | |
| `HttpMethod` | (no analogue; method names are bare `String`) | |
| `HttpAddress` | (no analogue) | |

**Status:** PARTIALLY-ALIGNED.

**Fix.** Adopt the `D5-final-plan.md §2` decision: hoist `MessageGate`, `IdStrategy`, `UnknownMethodPolicy`, `CancellationPolicy`, `ProgressPolicy`, `ExtrasEncoder`, `Framer`, `WireTransport`, and `HandlerCtx` to top-level files prefixed `JsonRpc*`. Hoist `JsonRpcEnvelope.{Request,Response,Notification}` to top-level `JsonRpcRequest`/`JsonRpcResponse`/`JsonRpcNotification` (Phase 09 proposal). Approximate ~500 LoC of file moves + import-fixup; mechanical.

---

## Axis 2: Opaque-type-vs-class for the entry-point type

**kyo-http convention.** `HttpServer` is an opaque type over `HttpServer.Unsafe` (`HttpServer.scala:37`) — `opaque type HttpServer = HttpServer.Unsafe`. There is **no public class**. All user-facing methods are extension methods on the opaque alias (`HttpServer.scala:41-67`: `address`, `port`, `host`, `close`, `closeNow`, `await`, `unsafe`). The `Unsafe` abstract class is the actual runtime base. `HttpClient` follows the identical pattern (`HttpClient.scala:41`: `opaque type HttpClient = HttpClientBackend[?]`), with all user methods in an `extension (self: HttpClient)` block at `HttpClient.scala:71-97`. Even `HttpHeaders` (`HttpHeaders.scala:30`), `HttpMethod` (`HttpMethod.scala:8`), and `HttpQueryParams` (`HttpQueryParams.scala:13`) follow the opaque-type idiom.

**kyo-jsonrpc current.** `JsonRpcEndpoint` is a `final class JsonRpcEndpoint private[kyo] (private[kyo] val impl: internal.engine.JsonRpcEndpointImpl)` at `JsonRpcEndpoint.scala:28`. Methods are instance methods on the class (`call`, `notify`, `sendUnmatched`, `callWithProgress`, etc. at `JsonRpcEndpoint.scala:30-96`). There is no opaque-type/Unsafe split. The constructor is `private[kyo]` so users still go through `JsonRpcEndpoint.init`, but the runtime surface is a heap class, not an opaque alias. The internal impl `JsonRpcEndpointImpl` (`internal/engine/JsonRpcEndpointImpl.scala`) has no `Unsafe` companion exposing a low-level API tier.

**Status:** DIVERGENT.

**Fix.** Convert `JsonRpcEndpoint` to `opaque type JsonRpcEndpoint = JsonRpcEndpoint.Unsafe`, expose the public methods via an `extension (self: JsonRpcEndpoint)` block, and rename the current `JsonRpcEndpointImpl` to `JsonRpcEndpoint.Unsafe` with `abstract class Unsafe { ... ; final def safe: JsonRpcEndpoint = this }`. Approximate ~150 LoC: ~50 in `JsonRpcEndpoint.scala`, ~100 in renaming/moving the engine impl + adding the `Unsafe` abstract layer. Existing tests must compile unchanged.

---

## Axis 3: Type parameters on the route type

**kyo-http convention.** `HttpRoute[In, Out, +E]` (`HttpRoute.scala:40`). Three type parameters: `In` for the intersection of request fields, `Out` for response fields, `+E` for the union of domain errors registered via `.error[E](status)`. `E` is the *error* row, not an effect-set row — there is no caller-provided effect parameter. The handler's effect row is fixed: `Async & Abort[E2 | HttpResponse.Halt]` (`HttpRoute.scala:95-99`).

**kyo-jsonrpc current.** `JsonRpcMethod[+S]` (`JsonRpcMethod.scala:27`). Single covariant `+S` representing the effect set the handler requires. The factory `apply[In: Schema, Out: Schema, S](name)(handler: (In, Context) => Out < S)` (`JsonRpcMethod.scala:80-90`) lets the **caller** pick the effect row, with a constraint `(Async & Abort[JsonRpcError]) <:< S`. There is no `In`/`Out` in the public surface — those are erased at the factory boundary; the route's data shape is hidden behind the handler closure. There is no `E` for typed domain errors; errors are always `JsonRpcError` (a single concrete case class).

The free `S` is a kyo-jsonrpc invention with no analogue in kyo-http. In kyo-http, a route is *data* — a pure description usable by both client and server — and the effect row only appears at handler-attachment time. In kyo-jsonrpc, a method *is* the handler; the description and the implementation are fused.

**Status:** DIVERGENT.

**Fix.** Restructure `JsonRpcMethod` to `JsonRpcMethod[In, Out, +E]` where `In` is the params type, `Out` is the result type, and `E` is the domain-error union. Factor the handler attachment into a separate `JsonRpcHandler` analogous to `HttpHandler`, so a method definition is data and the handler is a follow-on step. Approximate ~250 LoC including a new `JsonRpcHandler.scala`, plus signature changes in `JsonRpcEndpoint.init` (which currently takes `Seq[JsonRpcMethod[Async & Abort[JsonRpcError]]]` at `JsonRpcEndpoint.scala:435`).

---

## Axis 4: Handler effect-row signature

**kyo-http convention.** Fixed row, typed `E`: `def handler[E2 >: E](f: HttpRequest[In] => HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt]))` (`HttpRoute.scala:95-98`). The framework always grants the handler `Async`, always reserves `Abort[HttpResponse.Halt]` for short-circuits, and adds `Abort[E]` for the domain errors the route declared via `.error[E](status)`.

**kyo-jsonrpc current.** Caller-provided row: `def apply[In: Schema, Out: Schema, S](name: String)(handler: (In, JsonRpcMethod.Context) => Out < S)(using Frame, (Async & Abort[JsonRpcError]) <:< S)` (`JsonRpcMethod.scala:80-84`). The caller chooses `S`; the framework only requires that `S` contain `Async & Abort[JsonRpcError]`. The handler signature is `(In, Context) => Out < S`, *not* a single-arg request-style. The `Context` parameter (containing `cancelled`, `requestId`, `extras`, `progress`) is positional, not derivable from the request type.

**Status:** DIVERGENT.

**Fix.** Switch to a fixed effect row matching kyo-http: `Async & Abort[E | JsonRpcError]` (or `Abort[E | JsonRpcHalt]` if we add a Halt analogue per Axis 5). The handler signature should become `JsonRpcRequest[In] => JsonRpcResponse[Out] < (Async & Abort[E2 | JsonRpcHalt])` once Axis 3 is also done. Approximate ~100 LoC delta beyond Axis 3; cascades to every test in `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcMethodTest.scala` and downstream consumers (kyo-browser, kyo-ai-harness).

---

## Axis 5: Halt / short-circuit pattern

**kyo-http convention.** `case class Halt(response: HttpResponse[Any])` nested under `HttpResponse` (`HttpResponse.scala:169`), with a helper `def halt(response: HttpResponse[Any])(using Frame): Nothing < Abort[Halt]` (`HttpResponse.scala:172-173`). Any filter or handler aborts with `Abort.fail(Halt(...))` to short-circuit; the framework sends the response directly. Server filters use it for `WWW-Authenticate: Basic` 401s, etc. (`HttpFilter.scala:110-118`).

**kyo-jsonrpc current.** No `Halt`-style short-circuit type exists. `grep -rn "Halt\|halt(" kyo-jsonrpc/shared/src/main/scala/kyo/` returns zero results. The closest analogue is `JsonRpcEndpoint.MessageGate.Decision.Reject(error: JsonRpcError)` (`JsonRpcEndpoint.scala:198-200`), which only short-circuits at the *gate* layer before dispatch, not from within a handler. Handlers themselves can only return a value or abort with `JsonRpcError`.

**Status:** DIVERGENT.

**Fix proposal.** Add `JsonRpcResponse.Halt` (after Axis 9 hoists `JsonRpcResponse` to top-level) and a `JsonRpcResponse.halt(response: JsonRpcResponse[Any]): Nothing < Abort[Halt]` helper, wire it through `JsonRpcEndpointImpl.dispatch` to bypass the normal envelope-construction path. Approximate ~80 LoC. Useful for protocol-specific short-circuits like LSP `initialize`-not-yet-called responses or MCP non-`initialize` calls before init.

---

## Axis 6: `.error[E](status)` declarations on the route

**kyo-http convention.** `HttpRoute.error[E](using schema: Schema[E], tag: ConcreteTag[E])(s: HttpStatus): HttpRoute[In, Out, E | E2]` (`HttpRoute.scala:82-84`). Registers a domain error type and its status mapping; the framework auto-serialises the error to JSON with the declared status when handlers `Abort.fail(e: E)`. `ResponseDef` stores them in a `Chunk[ErrorMapping]` (`HttpRoute.scala:395, 509-514, 526`).

**kyo-jsonrpc current.** No `.error[E](code, message)` mechanism on `JsonRpcMethod`. Domain errors must be manually `Abort.fail(JsonRpcError.applicationError(code, message, data))` inside the handler body. There is no schema-driven error mapping; every error is the flat `JsonRpcError(code, message, data: Maybe[Structure.Value])` (`JsonRpcError.scala:24`).

**Status:** DIVERGENT.

**Fix proposal.** Add `JsonRpcMethod.error[E](using Schema[E], ConcreteTag[E])(code: Int, message: String): JsonRpcMethod[In, Out, E | E2]`, store error mappings in the method definition, and have the dispatch layer auto-encode caught `E` values into `JsonRpcError(code, message, Present(Structure.encode(e)))`. Approximate ~150 LoC. Prerequisite: Axis 3 (so `JsonRpcMethod` has an `E` type parameter to thread through).

---

## Axis 7: Init / lifecycle method variants

**kyo-http convention.** `HttpServer` exposes **6 init overloads** (`HttpServer.scala:71-91`): `init(handlers*)`, `init(port, host)(handlers*)`, `init(config)(handlers*)`, plus three `initWith` continuation variants. Then **6 more** `initUnscoped`/`initUnscopedWith` variants (`HttpServer.scala:95-140`). Twelve total entry points. `HttpClient` has `init` + `initUnscoped` (`HttpClient.scala:130, 140`) — fewer overloads because no `(port, host)` analogue exists, but it still has the scoped/unscoped split.

**kyo-jsonrpc current.** **One** init method: `JsonRpcEndpoint.init(transport, methods, config)(using Frame): JsonRpcEndpoint < (Async & Scope)` (`JsonRpcEndpoint.scala:433-440`). No `initWith` (continuation). No `initUnscoped`. No varargs convenience for `methods*`. No `(transport, methods)` overload that defaults the config. Users that need manual lifecycle must do `Scope.run { JsonRpcEndpoint.init(...) }` and pull the value out themselves.

**Status:** DIVERGENT.

**Fix.** Add `initWith[A, S](transport, methods, config)(f: JsonRpcEndpoint => A < S): A < (S & Async & Scope)`, `initUnscoped(transport, methods, config): JsonRpcEndpoint < Async` (with explicit `close()` lifecycle), `initUnscopedWith[A, S]`, plus convenience overloads accepting `methods: JsonRpcMethod[?, ?, ?]*` varargs and `methods` + `Config.default`. Approximate ~80 LoC in `JsonRpcEndpoint.scala`.

---

## Axis 8: Server / client split vs unified endpoint

**kyo-http convention.** Strict split. `HttpServer` (`HttpServer.scala`) handles incoming connections and bound handlers. `HttpClient` (`HttpClient.scala`) handles outgoing requests. They share `HttpRoute` as the typed contract but are otherwise independent — different config types, different lifecycle, different filter namespaces (`HttpFilter.server` vs `HttpFilter.client` at `HttpFilter.scala:108, 324`).

**kyo-jsonrpc current.** `JsonRpcEndpoint` is bidirectional: a single instance both serves inbound requests via the `methods: Seq[JsonRpcMethod[...]]` registered at init (`JsonRpcEndpoint.scala:433-440`) and originates outbound requests via `call`/`notify`/`callWithProgress`/`callPartialResults` (`JsonRpcEndpoint.scala:30-65`). This reflects JSON-RPC 2.0 §1: "primarily used for two purposes: communicating with a server, communicating with another peer (peer-to-peer)". MCP, LSP, and CDP all use this peer-symmetric model. **A split would be wrong** for the protocol.

**Status:** ALIGNED (justifiably divergent). The unified shape mirrors the protocol's symmetry; splitting would force consumers (kyo-browser CDP, MCP servers, LSP servers) to instantiate two distinct objects bound to the same transport. The kyo-http template doesn't apply to peer-to-peer wire protocols. **However**, the failure to also offer dedicated client-only or server-only convenience facades (think `JsonRpcEndpoint.client(transport, config)` that only exposes `call`/`notify` and rejects all inbound requests with `MethodNotFound`) is a missed ergonomic. Approximate ~50 LoC if added; non-blocking.

---

## Axis 9: Wire-message ADT vs separate types

**kyo-http convention.** `HttpRequest[Fields]` (`HttpRequest.scala:24`) and `HttpResponse[Fields]` (`HttpResponse.scala:23`) are separate top-level case classes. Both carry typed `Record[Fields]` of route-declared values. There is no ADT umbrella — request and response are unrelated types at the type level.

**kyo-jsonrpc current.** `enum JsonRpcEnvelope` (`JsonRpcEnvelope.scala:26-45`) with four nested cases: `Request`, `Notification`, `Response`, `Malformed`. The enum is consumed by `JsonRpcTransport.send/incoming` (`JsonRpcTransport.scala:21-22`) and by `MessageGate.beforeDispatch` (`JsonRpcEndpoint.scala:195`) as a discriminated union. Users that want to construct a `JsonRpcEnvelope.Request` directly must reach in three segments deep: `JsonRpcEnvelope.Request(id, method, params, extras)`.

The user-stated intent (Phase 09 proposal) is to hoist Request/Response/Notification/Malformed to top-level types and keep `JsonRpcEnvelope` only as a sealed union (or delete it entirely if the transport interface can be parameterised on the four cases independently). This phase has not been executed.

**Status:** DIVERGENT.

**Fix.** Hoist `JsonRpcEnvelope.Request`, `.Response`, `.Notification`, `.Malformed` to top-level types `JsonRpcRequest`, `JsonRpcResponse`, `JsonRpcNotification`, `JsonRpcMalformed`. Keep `JsonRpcEnvelope` as a sealed type union (`type JsonRpcEnvelope = JsonRpcRequest | JsonRpcResponse | JsonRpcNotification | JsonRpcMalformed`) so the transport signature is unchanged. Approximate ~200 LoC of file splits + import-fixup. The `JsonRpcEnvelope.Id` opaque-id type would move to a top-level `JsonRpcId` (matching `D5-final-plan.md §2` which already lists `JsonRpcId` as KEEP-PUBLIC-AS-IS — but the current state has it nested at `JsonRpcEnvelope.scala:60`, contrary to the plan).

---

## Axis 10: Error type structure

**kyo-http convention.** Sealed hierarchy with rich contextual fields. `sealed abstract class HttpException(message: Text, cause: Text | Throwable)(using Frame) extends KyoException` (`HttpException.scala:28-29`). Five sealed subcategories: `HttpConnectionException` (`:49`), `HttpRequestException` (`:90`), `HttpServerException` (`:146`), `HttpDecodeException` (`:191`), `HttpWebSocketException` (`:318`). Concrete leaves carry contextual fields: `HttpConnectException(host, port, cause)` (`:53`), `HttpPoolExhaustedException(host, port, maxConnections, clientFrame)` (`:68`), `HttpTimeoutException(duration, method, url)` (`:94`), `HttpRedirectLoopException(count, method, url, chain)` (`:104`), `HttpStatusException(status, method, url, body)` (`:125`), `HttpBindException` (`:150`), `HttpHandlerException` (`:159`), `HttpUrlParseException` (`:195`), `HttpFieldDecodeException(fieldName, fieldType, detail, method, url, cause)` (`:210`), `HttpMissingFieldException` (`:230`), `HttpJsonDecodeException` (`:244`), `HttpFormDecodeException` (`:255`), `HttpUnsupportedMediaTypeException` (`:270`), `HttpStreamingDecodeException` (`:285`), `HttpMissingBoundaryException` (`:298`), `HttpWebSocketHandshakeException` (`:322`), `HttpProtocolException` (`:335`), `HttpPayloadTooLargeException` (`:341`), `HttpConnectionClosedException` (`:347`). ~20 leaf case classes. Every leaf has a multi-line scaladoc with usage hints (e.g., `:69-77` for pool exhaustion gives the fix advice "Increase maxConnectionsPerHost..."). All exception constructors strip query parameters via `HttpException.stripQuery` (`:33`) to prevent secret leakage.

**kyo-jsonrpc current.** Flat case class. `case class JsonRpcError(code: Int, message: String, data: Maybe[Structure.Value]) derives Schema, CanEqual` (`JsonRpcError.scala:24`). It is *not* a `Throwable`, *not* a `KyoException`. It carries no contextual fields beyond an integer code and string message. The companion has 11 named constants (`ParseError`, `InvalidRequest`, `MethodNotFound`, `InvalidParams`, `InternalError`, `ServerNotInitialized`, `UnknownErrorCode`, `RequestCancelled`, `ContentModified`, `ServerCancelled`, `RequestFailed` at `:27-37`) and 5 smart-constructor helpers (`methodNotFound`, `invalidRequest`, `invalidParams`, `internalError`, `cancelled` at `:39-53`). No sealed hierarchy, no per-category sub-class, no contextual fields. The structure is **constrained by the wire format** (JSON-RPC 2.0 §5.1 mandates `{code, message, data}`), but the *Scala* representation does not need to be just a case class.

This is the Phase 08 deferral that `D3-fork-jsonrpc-error.md` confirmed as KEEP-FLAT. But the kyo-http template is a sealed hierarchy of Throwables with rich context, and the user calls out alignment failure here.

**Status:** DIVERGENT.

**Fix proposal.** Convert `JsonRpcError` to a sealed hierarchy:
```
sealed abstract class JsonRpcError(val code: Int, message: String, val data: Maybe[Structure.Value])(using Frame) extends KyoException(message)
object JsonRpcError:
    sealed abstract class Protocol(...) extends JsonRpcError(...)  // -32700..-32603 standard
    sealed abstract class Lsp(...)      extends JsonRpcError(...)  // -32800..-32803
    sealed abstract class Application(code: Int, ...) extends JsonRpcError(code, ...)  // user range
    case class ParseError(detail: String, ...) extends Protocol(-32700, "Parse error", Present(...))
    case class MethodNotFound(name: String, ...) extends Protocol(-32601, ...)
    case class InvalidParams(reason: String, target: Maybe[String], ...) extends Protocol(-32602, ...)
    case class InternalError(cause: String, ...) extends Protocol(-32603, ...)
    // ... etc
    case class RequestCancelled(id: JsonRpcId, reason: Maybe[String], ...) extends Lsp(-32800, ...)
    case class ApplicationError(override val code: Int, override val message: String, ...) extends Application(code, ...)
```
Each leaf gains the contextual fields that the current `data: Maybe[Structure.Value]` blob hides. Wire serialisation flattens to `{code, message, data}` per spec. Approximate ~300 LoC (including stripping the `data` field's irregular `Structure.Value.Str(reason)` ad-hoc encoding done in current helpers `JsonRpcError.scala:42-53`).

---

## Axis 11: Filter / interceptor pattern

**kyo-http convention.** `sealed abstract class HttpFilter[ReqUse, ReqAdd, ResUse, ResAdd, +E]` (`HttpFilter.scala:43`). Five type parameters tracking required/added request fields, required/added response fields, and errors. Three abstract sub-classes (`HttpFilter.Request`, `HttpFilter.Response`, `HttpFilter.Passthrough` at `:76, 79, 82`). Composes with `andThen` (`:54`). A `noop` constant (`:98`). A `Factory` ServiceLoader SPI (`:413`). Server-side filters live in object `HttpFilter.server` (auth, CORS, rate-limit, logging at `:108`). Client-side filters live in `HttpFilter.client` (auth-header attachment, logging at `:324`). Server filters can short-circuit with `HttpResponse.Halt`.

**kyo-jsonrpc current.** `JsonRpcEndpoint.MessageGate` (`JsonRpcEndpoint.scala:194-203`) — a single-method trait with `def beforeDispatch(env): Decision < Sync` where `Decision = Allow | Reject(error) | Drop`. It is *nested* under `JsonRpcEndpoint` (after Phase 03 reorg). No type parameters. No `Allow` ↔ `Reject` ↔ `Drop` semantics for response side. No composition mechanism (`andThen`). No `noop` constant. No `server`/`client` namespaces. No `ServiceLoader` SPI. The filter applies only *before* dispatch, never *after* (no post-response interception).

**Status:** DIVERGENT.

**Fix proposal.** Either (a) keep the current shape and rename/hoist to top-level `JsonRpcMessageGate` (matching `D5-final-plan.md §2`), OR (b) introduce a proper `JsonRpcFilter` analogue with pre-dispatch and post-dispatch hooks and per-method composition. Given JSON-RPC has no `Record[Fields]` shape and no body-typing dimensions, the kyo-http five-parameter form would be over-engineered. The minimal alignment is: hoist `MessageGate` to `JsonRpcMessageGate`, add `andThen` composition, add `noop`, and split into `JsonRpcMessageGate.server`/`JsonRpcMessageGate.client` (or `.inbound`/`.outbound`) namespaces. Approximate ~150 LoC.

---

## Axis 12: Convenience factories on the route type

**kyo-http convention.** `HttpRoute` companion (`HttpRoute.scala:102-167`) exposes **22 named factories** organised by HTTP method × body type:
- Raw (no body type fixed): `getRaw`, `postRaw`, `putRaw`, `patchRaw`, `deleteRaw`, `headRaw`, `optionsRaw` (`:109-115`)
- JSON: `getJson`, `postJson`, `putJson`, `patchJson`, `deleteJson` (`:119-132`)
- Text: `getText`, `postText`, `putText`, `patchText`, `deleteText` (`:136-149`)
- Binary: `getBinary`, `postBinary`, `putBinary`, `patchBinary`, `deleteBinary` (`:153-166`)

**kyo-jsonrpc current.** `JsonRpcMethod` companion (`JsonRpcMethod.scala:40-194`) exposes **3 factories**: two overloads of `apply` (with-context and without-context at `:80, 92`) plus `notification` (`:99`). No JSON-RPC-specific factory shape like `JsonRpcMethod.request[I, O]` or `JsonRpcMethod.notification[I]` that fix the kind dimension at the type level.

**Status:** PARTIALLY-ALIGNED. The factory count is small because JSON-RPC has no HTTP-method × body-type matrix to enumerate. The `apply`/`notification` split *is* the JSON-RPC analogue.

**Fix.** None strictly required, but two improvements: (a) rename `apply` to `request` for symmetry with `notification`, exposing the method kind in the factory name; (b) add a `JsonRpcMethod.error[E](code, message)` factory once Axis 6 lands. Approximate ~20 LoC.

---

## Axis 13: Companion object presets

**kyo-http convention.** Every config-shaped type has a `default` constant: `HttpServerConfig.default` (`HttpServerConfig.scala:92`), `HttpClientConfig` is constructed via `HttpClientConfig()` with all-default-args at `HttpClient.scala:52`, `HttpTlsConfig.default` (`HttpTlsConfig.scala:47`), `HttpTransportConfig.default` (`HttpTransportConfig.scala:38`+), `HttpWebSocket.Config()` (`HttpClient.scala:740, 758`). Plus `HttpFilter.noop` (`HttpFilter.scala:98`), `HttpServerConfig.Cors.allowAll` (`HttpServerConfig.scala:126`).

**kyo-jsonrpc current.** Mixed coverage:
- `JsonRpcEndpoint.Config.default` — present at `JsonRpcEndpoint.scala:422`.
- `JsonRpcCodec.Strict2_0` and `JsonRpcCodec.Cdp` — present at `JsonRpcCodec.scala:28-29`.
- `JsonRpcTransport.WireTransport.empty` — present at `JsonRpcTransport.scala:48`.
- `JsonRpcEndpoint.ExtrasEncoder.empty` — present at `JsonRpcEndpoint.scala:376`.
- `JsonRpcEndpoint.UnknownMethodPolicy.minimal`/`.lsp`/`.strict` — present at `JsonRpcEndpoint.scala:162, 168, 174`.
- `JsonRpcEndpoint.CancellationPolicy.lsp`/`.mcp` — present at `JsonRpcEndpoint.scala:268, 277`.
- `JsonRpcEndpoint.ProgressPolicy.lsp`/`.mcp` — present at `JsonRpcEndpoint.scala:331, 341`.

Missing relative to the kyo-http pattern:
- No `JsonRpcTransport.default` (the kyo-http analogue is `HttpTransportConfig.default`).
- No `JsonRpcCodec.default` (could alias `Strict2_0`).
- No `JsonRpcMessageGate.allowAll` / `.noop` (the kyo-http analogue is `HttpFilter.noop`).
- No `JsonRpcEndpoint.Config.dev` / `.prod` distinction (kyo-http also doesn't have these, so not a divergence — just noting).

**Status:** PARTIALLY-ALIGNED. The presets that exist are good; a few obvious gaps remain.

**Fix.** Add `JsonRpcCodec.default: JsonRpcCodec = Strict2_0`, add `JsonRpcEndpoint.MessageGate.noop` (returns `Decision.Allow` for all). Approximate ~10 LoC.

---

## Axis 14: Internal subpackage layout

**kyo-http convention.** `kyo.internal` is split into **7 subpackages** (`kyo-http/shared/src/main/scala/kyo/internal/`):
- `client` (`HttpClientBackend.scala`, ...) — outbound stack
- `server` (`HttpRouter.scala`, `UnsafeServerDispatch.scala`, ...) — inbound stack
- `codec` (`OpenApiGenerator.scala`, `ParsedResponse.scala`, ...) — wire serialisation
- `transport` (`Connection.scala`, `Transport.scala`, `ReadPump.scala`, `WritePump.scala`, ...) — byte-level I/O
- `http1` (`Http1Parser.scala`, `Http1ClientConnection.scala`, `ChunkedBodyDecoder.scala`, ...) — protocol parser
- `websocket` (`WebSocketCodec.scala`) — protocol parser
- `util` (`Sha1.scala`, `ChannelBackedStream.scala`, `ByteStream.scala`) — generic helpers

**kyo-jsonrpc current.** `kyo.internal` is split into **4 subpackages** (`kyo-jsonrpc/shared/src/main/scala/kyo/internal/`):
- `codec` (`JsonRpcCodecImpl.scala`, `JsonRpcRequest.scala`, `RawJsonParser.scala`) — wire serialisation
- `transport` (`InMemoryTransport.scala`, `StdioWireTransport.scala`, `WireTransportAdapter.scala`) — byte-level
- `framing` (`FramerImpl.scala`) — frame extraction
- `engine` (`JsonRpcEndpointImpl.scala`, `CancellationEngine.scala`, `ProgressEngine.scala`, `IdStrategyEngine.scala`, `RateLimitEngine.scala`) — dispatch/loop

There is no `server` or `client` split inside `engine` (because the protocol is bidirectional — Axis 8). There is no `util` package. There is no `dispatch` package: dispatch logic is fused into `engine`. The `framing` package is parallel to kyo-http's `http1` (protocol-level parser sub-tree), which is fine.

**Status:** PARTIALLY-ALIGNED. The subpackage names *are* a subset of kyo-http's naming vocabulary (`codec`, `transport`, `framing`/`http1`, `engine`/`server`), and the split is reasonable for a peer-to-peer protocol. The Phase 01 reorg landed cleanly. Two minor gaps:

**Fix.** Consider extracting `IdStrategyEngine`, `RateLimitEngine` to a `dispatch` (or `policy`) subpackage to separate per-policy implementation from the core loop. Optional; non-blocking. ~30 LoC moves.

---

## Axis 15: Doctest discipline + scaladoc length

**kyo-http convention.** Every public top-level type has scaladoc of 8-35 lines with multiple `@see` references and design rationale. Sample: `HttpStatus.scala:1-24` is 20 lines covering category split, predicate purpose, and three `@see` cross-refs. `HttpRoute.scala:5-39` is 35 lines describing the type-parameter purpose, the fluent-builder workflow, and 6 `@see` refs. `HttpServer.scala:10-36` is 27 lines covering scoped vs unscoped, port-0 idiom, security warning, and 4 `@see` refs.

**kyo-jsonrpc current.** Most public top-level types now have scaladoc (Phase 01 added these). Sample lengths:
- `JsonRpcCodec.scala:8-21` — 14 lines (good).
- `JsonRpcEndpoint.scala:10-26` — 17 lines (good).
- `JsonRpcEnvelope.scala:12-25` — 14 lines (good).
- `JsonRpcMethod.scala:13-26` — 14 lines (good).
- `JsonRpcTransport.scala:5-19` — 15 lines (good).
- `JsonRpcError.scala:10-23` — 14 lines (good).

Quality: scaladoc is functional but mostly **descriptive** rather than **didactic**. kyo-http scaladocs commonly include code-style examples in prose (e.g., `HttpStatus.scala:14-15` "Use `HttpStatus(code)` to resolve...") and design rationale ("Note: The default shared client is created lazily..." at `HttpClient.scala:29-30`). Jsonrpc scaladocs lean more on bullet lists. None of the kyo-jsonrpc scaladocs have fenced code examples — but neither do kyo-http's main-module scaladocs (the doctest convention applies primarily to README files per `CLAUDE.md`'s `Writing Module READMEs` section). So this is on parity.

**Status:** ALIGNED. Marginal stylistic divergence (bullet-heavy vs prose-heavy); not a substantive gap.

**Fix.** Optional: tighten 5-10 of the longest internal-doc scaladocs (e.g., `JsonRpcEndpoint.scala:386-398` is 13 lines for `Config`; consider whether half could be cut). Non-blocking.

---

## Axis 16: Companion structure (extension methods vs methods)

**kyo-http convention.** Extension methods on opaque types (`HttpServer.scala:41-67`, `HttpClient.scala:71-97`, `HttpHeaders.scala`, `HttpMethod.scala:10+`, `HttpQueryParams.scala`). Methods on case classes for non-opaque types (`HttpRequest.scala:24-47`, `HttpResponse.scala:23-55`, `HttpRoute.scala:40-100`).

**kyo-jsonrpc current.** Instance methods on `JsonRpcEndpoint` (final class) at `JsonRpcEndpoint.scala:30-96`. There is **one** use of extension methods: `extension (self: ExtrasEncoder) def resolve(...)` at `JsonRpcEndpoint.scala:382-383` — the opaque-type companion carve-out. Everywhere else, methods are on instances. This is downstream of Axis 2 (no opaque-type entry-point), so it inherits the same status.

**Status:** DIVERGENT (downstream of Axis 2).

**Fix.** Fold into Axis 2's fix.

---

## Axis 17: `@volatile`, `@nowarn`, `AllowUnsafe` usage

**kyo-http convention.** `AllowUnsafe` references occur in `HttpClient.scala` (2), `HttpFilter.scala` (4), `HttpServer.scala` (6), `HttpCodec.scala` (1). `@nowarn` and `@volatile` are not present at the public-API surface (they live in `internal/` impl files).

**kyo-jsonrpc current.** Single `@nowarn("msg=anonymous")` at `JsonRpcEnvelope.scala:66`, on the `Id` `Schema` derivation. **Zero** `AllowUnsafe` annotations on the public surface — because all unsafe-tier mechanics live behind `internal.engine.JsonRpcEndpointImpl` (which does use `AllowUnsafe.embrace.danger` at `JsonRpcEndpointImpl.scala:171, 249, 267`, etc.). The absence of public `AllowUnsafe` is correlated with the absence of an `Unsafe` tier on `JsonRpcEndpoint` (Axis 2). If Axis 2 is fixed, several methods that today call `Sync.Unsafe.defer` internally would shift to extension methods that explicitly take `using AllowUnsafe`.

**Status:** PARTIALLY-ALIGNED. The hygiene practice (annotations are sparse and intentional) is good. The architectural deficit (no `Unsafe` tier) is the same as Axis 2.

**Fix.** Fold into Axis 2.

---

## Axis 18: Frame / using-clause ordering

**kyo-http convention.** Per `CLAUDE.md`: `Tag` before `Frame` (inline), `Frame` before evidence (non-inline), `AllowUnsafe` last. Sample non-inline at `HttpClient.scala:626`: `(using Frame, Tag[Emit[Chunk[HttpSseEvent[V]]]])` — Frame first, Tag second. Sample inline at `HttpRoute.scala:82`: `inline def error[E2](using schema: Schema[E2], tag: ConcreteTag[E2])(s: HttpStatus)` — uses-block contains evidence-like givens; no `Frame` here. The convention is consistent throughout.

**kyo-jsonrpc current.** Sample at `JsonRpcEndpoint.scala:63`: `def callPartialResults[In: Schema, T: Schema: Tag](method, params, extras)(using Frame, Tag[Emit[Chunk[T]]])` — non-inline; `Frame` before `Tag` ✓. Sample at `JsonRpcEndpoint.scala:437`: `def init(transport, methods, config)(using Frame)` ✓. Sample at `JsonRpcMethod.scala:80-84`: `def apply[In: Schema, Out: Schema, S](name)(handler)(using Frame, (Async & Abort[JsonRpcError]) <:< S)` — non-inline; `Frame` before evidence (`<:<`) ✓.

**Status:** ALIGNED. No violations found.

**Fix.** None.

---

## Axis 19: Test base class

**kyo-http convention.** `abstract class Test extends AsyncFreeSpec with NonImplicitAssertions with BaseKyoCoreTest` at `kyo-http/shared/src/test/scala/kyo/Test.scala:9`. Adds: `timeout = 60.seconds` override, a `run` override that wraps every test in `HttpClient.withConfig(_.timeout(60.seconds))` (`Test.scala:23-24`), and an `initTrustAllClient` helper (`Test.scala:27-31`). The class lives in `package kyo` and is named simply `Test`.

**kyo-jsonrpc current.** `abstract class JsonRpcTestBase extends AsyncFreeSpec with NonImplicitAssertions with BaseKyoCoreTest` at `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcTestBase.scala:9`. No `timeout` override (defaults to whatever `BaseKyoCoreTest` declares). No `run` wrapper. No helpers. The class name is `JsonRpcTestBase` — kyo-http calls it `Test` per the project convention noted in `CLAUDE.md` ("Tests use the module's `Test` base class").

**Status:** DIVERGENT.

**Fix.** Rename `JsonRpcTestBase` → `Test`, add a `timeout` override consistent with the network-bound test cost, drop the redundant `Assertion`/`assertionSuccess`/`assertionFailure` re-declarations (they duplicate what `BaseKyoCoreTest` already gives). Approximate ~20 LoC + ~16 test-file references to update (one per test file). Note: the Phase 8c rename touched on this; verify whether the rename was *to* `JsonRpcTestBase` (current state) or *from* `JsonRpcTestBase`. Recent commit log shows `c332edf4c [jsonrpc] Rule 8c Phase 3: orphan-test moves + Test->JsonRpcTestBase rename` — meaning the project moved **away** from kyo-http convention here, presumably to avoid name collision across modules in a multi-module sbt context. If that constraint holds, the divergence is justified; otherwise rename back.

---

## Axis 20: Cross-platform parity

**kyo-http convention.** JVM, JS, Native all supported with feature-complete WebSocket, TLS, and Unix domain sockets. Platform-specific code lives in `kyo-http/{jvm,js,native}/src/main/scala/kyo/internal/`. Sample: `HttpPlatformTransport.scala` exists for all three platforms; `NativeTransport.scala`/`JsTransport.scala` provide platform-specific I/O; `TlsBindings.scala` is in native.

**kyo-jsonrpc current.** JVM, JS, Native directories exist (`kyo-jsonrpc/{jvm,js,native}/src/main/scala/kyo/internal/transport/`), each with a `UdsBackend.scala`. The JVM has the real implementation (`UdsBackend.scala` + `UdsWireTransport.scala` per Phase 06). JS and Native have stub `UdsBackend.scala` files that fail with `UnsupportedOperationException` — confirmed by `JsonRpcTransport.scala:160-165` scaladoc: "On Scala.js and Scala Native, this method immediately fails with an `UnsupportedOperationException`". 

Comparing capabilities:
- **In-memory transport**: shared (`internal/transport/InMemoryTransport.scala`) — all three platforms ✓.
- **Stdio transport**: shared (`internal/transport/StdioWireTransport.scala`) — uses `Console.readLine`/`Console.printLine` — should work on all platforms but Console availability on Native/JS is restricted. No `*Test.scala` for stdio on JS/Native to confirm.
- **UDS**: JVM only (per Phase 06 scope).
- **Wire-from-bytes**: shared.
- **TLS**: not in scope for kyo-jsonrpc (the protocol is transport-agnostic; TLS happens at the WireTransport layer).
- **WebSocket transport** for JSON-RPC: not implemented; would need to bridge to `kyo-http`'s `HttpWebSocket` via the planned `kyo-jsonrpc-http` sibling module (`D5-final-plan.md §2` calls out `JsonRpcHttpTransport` in `kyo-jsonrpc-http/src/main/scala/kyo/`).

**Status:** PARTIALLY-ALIGNED. The expected platform spread (JVM/JS/Native, shared common) is in place. The major capability missing from JS/Native is UDS — and the scaladoc honestly documents the limitation. The bigger gap is the absence of a `kyo-jsonrpc-http` sibling module (transport over HTTP POST + SSE for MCP; transport over WebSocket for browser clients). `D5-final-plan.md` already lists `JsonRpcHttpTransport` as planned; it does not yet exist in this worktree.

**Fix.** (a) Create the `kyo-jsonrpc-http` sibling module with `JsonRpcHttpTransport` (post-only and SSE variants). (b) Add a `JsonRpcWebSocketTransport` in either kyo-jsonrpc-http or a new `kyo-jsonrpc-websocket` module. Approximate ~400 LoC each. Non-blocking for the current API-shape alignment but blocking for full kyo-http parity. The JS/Native UDS stubs as they stand are acceptable.

---

## Additional findings

These divergences are outside the user's enumerated axes but stood out during the audit.

### A1. `JsonRpcMethod.dispatch` leaks an internal helper to the public surface

`JsonRpcMethod.dispatch[S](name, methods, params, ctx)` at `JsonRpcMethod.scala:115-130` is documented as "Public reach-in for non-engine consumers (one-shot stdio loop, HTTP POST endpoints, custom routers)". kyo-http has no analogue — there is no `HttpRoute.dispatch` or `HttpHandler.dispatch` public function. This either belongs in `internal/engine/` or behind a `JsonRpcEndpoint.Unsafe.dispatch` once the Unsafe tier exists (Axis 2). Approximate ~30 LoC: move to internal + add an `Unsafe.dispatch` wrapper.

### A2. `JsonRpcEndpoint.MessageGate.Decision.Reject` carries a `JsonRpcError` — wire dialect leaks into gate signature

`MessageGate.Decision` at `JsonRpcEndpoint.scala:198-202` includes `case Reject(error: JsonRpcError)`. In kyo-http, the analogue (filter short-circuit) carries an `HttpResponse` (`HttpResponse.Halt` at `HttpResponse.scala:169`), not an `HttpStatus`+message tuple. The jsonrpc gate-rejection is *one rung too low* — it reaches into the wire-error type instead of producing a full envelope. This restricts the gate's expressiveness (it cannot, for instance, reject a request by silently swapping its method to a default; it cannot tag a response with custom extras on rejection). Approximate ~20 LoC if `Decision.Reject` is widened to `Reject(response: JsonRpcResponse)`.

### A3. `JsonRpcEnvelope.Id` `Schema` derivation uses a hand-written `init` instead of `derives`

`JsonRpcEnvelope.scala:66-79` defines `given schema: Schema[Id] = Schema.init[Id](writeFn, readFn)` with a manually-written `readFn` that try/catches on `TypeMismatchException`. The pattern is the project's standard "two-way derivation for sum types where the wire encoding is untagged"; the hand-written form is necessary because `derives Schema` for enums emits a `{type: "Num" | "Str", value: ...}` tagged encoding which is wrong for JSON-RPC (the wire is untagged number-or-string). Confirmed correct but worth a comment justifying the deviation. No fix needed — this is *justifiably* divergent from the `derives Schema` convention seen on `JsonRpcError.scala:24` and most http types.

### A4. Two `close` overloads on `JsonRpcEndpoint` with explicit ambiguity warnings in scaladoc

`JsonRpcEndpoint.scala:78-89` documents Scala 3 overload-resolution ambiguity between `close(using Frame)` (no-arg) and `close(gracePeriod: Duration)` when chaining with `.andThen`. The mitigation is a third method `closeNow` (`:96`). kyo-http does not have this ambiguity (because `HttpServer.close(using Frame)` and `HttpServer.close(gracePeriod: Duration)(using Frame)` are extension methods on an opaque type, which Scala 3 resolves differently). This is *caused by* the class-vs-opaque divergence (Axis 2). Fixing Axis 2 eliminates the workaround. Approximate −10 LoC (remove the workaround) after Axis 2 lands.

### A5. `Maybe[CancellationPolicy]` / `Maybe[ProgressPolicy]` defaults to `Absent` in `Config` — silent feature-off

`JsonRpcEndpoint.Config` defaults `cancellation = Absent`, `progress = Absent` (`JsonRpcEndpoint.scala:401-402`). Users that call `JsonRpcEndpoint.init(transport, methods)` get cancellation/progress *silently disabled*. kyo-http's `HttpServerConfig.default` similarly defaults `cors = Absent`, `tls = Absent`, `openApi = Absent` — but each of these has a clear "off by default for security" rationale documented in scaladoc. In jsonrpc, cancellation/progress are protocol-level features (LSP and MCP both use them); the silent-off default likely surprises new users. Approximate ~20 LoC if a `Config.lsp`/`Config.mcp` preset (analogous to `CancellationPolicy.lsp`/`.mcp`) is added that wires the matched cancellation+progress policies in one step.

### A6. `JsonRpcMethod` second-overload "no Context" form is positionally identical to the with-Context form — Scala 3 overload resolution risk

`JsonRpcMethod.scala:80-90` (`apply` with `(In, Context) => Out < S`) and `:92-97` (`apply` with `In => Out < S`) differ only in the handler arity. Scala 3's eta-expansion can make the resolution surprising when the user passes a method reference. kyo-http avoids this by giving its convenience factories distinct names (`HttpRoute.getJson` vs `HttpRoute.getRaw().request(_.bodyJson)`). Approximate ~10 LoC if the second overload is renamed to `simple` or removed in favor of a callable `_ => ...` idiom.

### A7. `JsonRpcEndpoint.Pending` does not declare `+Out` covariance

`final class Pending[Out] private[kyo]` at `JsonRpcEndpoint.scala:111` declares `Out` invariantly. The kyo-http analogue would be `+Out` for the same reason `HttpRoute[In, Out, +E]` declares `+E`. The current absence of `+Out` blocks pattern matching/widening at use sites. Approximate ~5 LoC: add `+Out`, verify the inner field positions are co-variant.

### A8. `JsonRpcCodec.encode` returns `Sync & Abort[JsonRpcError]` but `decode` returns just `Sync`

`JsonRpcCodec.encode(env): Structure.Value < (Sync & Abort[JsonRpcError])` (`JsonRpcCodec.scala:23`) — fallible.
`JsonRpcCodec.decode(raw): JsonRpcEnvelope < Sync` (`JsonRpcCodec.scala:24`) — infallible.

The asymmetry is because `decode` returns a `JsonRpcEnvelope.Malformed` on decode failure rather than aborting (`JsonRpcEnvelope.scala:44`). This is a reasonable design (malformed messages are *data*, not errors, because the wire transport must not be torn down for a single bad message). However: kyo-http codecs are symmetric — both directions are `Abort[HttpDecodeException]`. The kyo-jsonrpc choice is defensible but worth a scaladoc note explaining the asymmetry. Approximate ~5 LoC of scaladoc on `JsonRpcCodec`.

### A9. `JsonRpcTransport.unixDomain` returns `Async & Scope & Abort[Throwable]` — `Abort[Throwable]` is over-broad

`JsonRpcTransport.scala:160-165`: the return type is `JsonRpcTransport < (Async & Scope & Abort[Throwable])`. kyo-http uses `Abort[HttpException]` (a sealed hierarchy). `Abort[Throwable]` here means callers cannot pattern-match on specific failure modes (path-already-exists vs permission-denied vs unsupported-platform). Approximate ~40 LoC if a dedicated `JsonRpcTransportException` sealed hierarchy is added with `UnsupportedPlatform`, `BindFailed`, `SocketPathConflict` leaves — fits naturally into Axis 10's broader error-hierarchy refactor.

### A10. `JsonRpcError` is not a `Throwable`

`case class JsonRpcError(code, message, data: Maybe[Structure.Value]) derives Schema, CanEqual` (`JsonRpcError.scala:24`). It does **not** extend `Throwable` (or `KyoException`). The kyo-http analogue `HttpException` extends `KyoException(message, cause)` (`HttpException.scala:28-29`). Implications:
- Cannot be wrapped by `Result.Panic`.
- Cannot propagate through `Sync.defer { throw _ }` interop boundaries cleanly.
- Stack-trace context (where the error was raised) is lost.
- The `Frame` provided to `JsonRpcError.methodNotFound(name)(using Frame)` is *ignored* — the case class has no Frame field.

This is the most surprising single divergence relative to kyo-http. Approximate ~50 LoC to convert `JsonRpcError` to extend `KyoException`, capture `Frame`, and update the 30+ call sites that construct it. Strongly recommended; folds into Axis 10's hierarchy refactor.

---

## Summary table

| # | Axis | Status | Blast radius |
|---|------|--------|---|
| 1 | Type naming + prefix discipline | PARTIALLY-ALIGNED | LARGE — 9 nested types to hoist + many file moves + import-fixup; ~500 LoC |
| 2 | Opaque-type-vs-class entry-point | DIVERGENT | LARGE — refactor `JsonRpcEndpoint` to opaque + `Unsafe` tier; ~150 LoC |
| 3 | Type parameters on route type | DIVERGENT | LARGE — `JsonRpcMethod[+S]` → `JsonRpcMethod[In, Out, +E]`; cascade through engine and tests; ~250 LoC |
| 4 | Handler effect-row signature | DIVERGENT | MEDIUM — folds into Axis 3; ~100 LoC additional |
| 5 | Halt / short-circuit pattern | DIVERGENT | SMALL — new `JsonRpcResponse.Halt` + helper; ~80 LoC |
| 6 | `.error[E](code, message)` declarations | DIVERGENT | MEDIUM — depends on Axis 3; ~150 LoC |
| 7 | Init / lifecycle method variants | DIVERGENT | SMALL — add 5 init overloads; ~80 LoC |
| 8 | Server / client split | ALIGNED (justified) | NONE |
| 9 | Wire-message ADT vs separate types | DIVERGENT | MEDIUM — hoist Request/Response/Notification/Malformed; ~200 LoC |
| 10 | Error type structure | DIVERGENT | LARGE — sealed hierarchy with leaves; KyoException base; ~300 LoC |
| 11 | Filter / interceptor pattern | DIVERGENT | MEDIUM — hoist + compose + namespace MessageGate; ~150 LoC |
| 12 | Convenience factories on route type | PARTIALLY-ALIGNED | SMALL — rename `apply`→`request`; add `error`; ~20 LoC |
| 13 | Companion object presets | PARTIALLY-ALIGNED | SMALL — add 2-3 presets; ~10 LoC |
| 14 | Internal subpackage layout | PARTIALLY-ALIGNED | SMALL — optional `dispatch` extract; ~30 LoC |
| 15 | Doctest discipline + scaladoc length | ALIGNED | NONE |
| 16 | Companion structure (extension methods) | DIVERGENT | (folds into Axis 2) |
| 17 | `@volatile`/`@nowarn`/AllowUnsafe usage | PARTIALLY-ALIGNED | (folds into Axis 2) |
| 18 | Frame / using-clause ordering | ALIGNED | NONE |
| 19 | Test base class | DIVERGENT | SMALL — rename + augment; ~20 LoC + 16 file refs |
| 20 | Cross-platform parity | PARTIALLY-ALIGNED | LARGE — new sibling module `kyo-jsonrpc-http`; ~400 LoC, deferrable |
| A1 | `JsonRpcMethod.dispatch` leaks internal | DIVERGENT | SMALL — ~30 LoC |
| A2 | `Decision.Reject(JsonRpcError)` too low-level | DIVERGENT | SMALL — ~20 LoC |
| A3 | `Id` Schema hand-written | ALIGNED (justified) | NONE |
| A4 | `close` ambiguity workaround | DIVERGENT | (folds into Axis 2) |
| A5 | Cancellation/progress silently off | PARTIALLY-ALIGNED | SMALL — ~20 LoC |
| A6 | `JsonRpcMethod.apply` overload risk | DIVERGENT | SMALL — ~10 LoC |
| A7 | `Pending[Out]` invariant | DIVERGENT | SMALL — ~5 LoC |
| A8 | Codec encode/decode asymmetry | ALIGNED (justified) | NONE — scaladoc only |
| A9 | `unixDomain` returns `Abort[Throwable]` | DIVERGENT | SMALL — folds into Axis 10; ~40 LoC |
| A10 | `JsonRpcError` is not a `Throwable` | DIVERGENT | MEDIUM — folds into Axis 10; ~50 LoC |

**Counts:** ALIGNED 4 (axes 8, 15, 18, A3, A8); PARTIALLY-ALIGNED 7 (1, 12, 13, 14, 17, 20, A5); DIVERGENT 19 (the rest).

---

## Recommended fix order

The fix sequence below is ordered to minimise rework — earlier phases produce names and types that later phases consume.

### Phase A: Foundation renames (no behaviour change)
1. **Axis 19 + A6**: Rename `JsonRpcTestBase` → `Test` (if multi-module collision doesn't block); rename `JsonRpcMethod.apply`(2nd overload) → drop or rename. ~30 LoC.
2. **Axis 1 (partial)**: Hoist `JsonRpcEnvelope.Id` → top-level `JsonRpcId` per `D5-final-plan.md §2`. ~50 LoC.

### Phase B: Error hierarchy (Axis 10 + A9 + A10)
3. Convert `JsonRpcError` to sealed hierarchy extending `KyoException`. Add categorised sub-classes with contextual fields. Add `JsonRpcTransportException` for UDS-style transport failures. **Why first among the substantive changes:** the new error type names ripple into every signature touched by Axes 3, 4, 6, 7. Doing this last would force re-touching every signature. ~350 LoC.

### Phase C: Wire-message hoist (Axis 9)
4. Hoist `JsonRpcEnvelope.{Request, Response, Notification, Malformed}` to top-level types. Keep `JsonRpcEnvelope` as a sealed union. **Why now:** the `JsonRpcResponse` top-level type is the home for `Halt` (Axis 5) and the carrier for the new `JsonRpcMethod[In, Out, +E]` `Out` parameter (Axis 3). ~200 LoC.

### Phase D: Route + handler restructuring (Axes 3, 4, 5, 6)
5. Add `+E` to `JsonRpcMethod`, separate `JsonRpcMethod` (definition) from `JsonRpcHandler` (impl attachment). Add `.error[E](code, message)`. Add `JsonRpcResponse.Halt` + `.halt`. Fix the handler signature to the kyo-http shape. ~600 LoC combined.

### Phase E: Entry-point opaque-type refactor (Axes 2, 16, 17, A4)
6. Convert `JsonRpcEndpoint` to `opaque type JsonRpcEndpoint = JsonRpcEndpoint.Unsafe`. Add `extension (self: JsonRpcEndpoint)` block. Drop the `close` ambiguity workaround. Wrap `Unsafe.dispatch` for Axis A1. ~200 LoC.

### Phase F: Lifecycle variants + filter hoist (Axes 7, 11, A1, A2, A5)
7. Add `initWith`, `initUnscoped`, `initUnscopedWith`, plus the `methods*` varargs convenience and the `Config.lsp`/`Config.mcp` presets. Hoist `MessageGate` to top-level `JsonRpcMessageGate` with `andThen`/`noop`/`server`/`client` namespaces. Widen `Decision.Reject` to carry a `JsonRpcResponse`. Move `JsonRpcMethod.dispatch` to internal + add `Unsafe.dispatch`. ~250 LoC combined.

### Phase G: Cleanup (Axes 1 fully, 12, 13, 14, A7)
8. Hoist remaining nested types per `D5-final-plan.md §2` (`IdStrategy`, `UnknownMethodPolicy`, `CancellationPolicy`, `ProgressPolicy`, `ExtrasEncoder`, `Framer`, `WireTransport`). Add `JsonRpcCodec.default`, `JsonRpcMessageGate.noop`. Rename `JsonRpcMethod.apply`→`request`. Add `+Out` covariance on `Pending`. Optional internal subpackage shuffle. ~350 LoC.

### Phase H: Sibling modules (Axis 20)
9. Create `kyo-jsonrpc-http` with `JsonRpcHttpTransport`. Defer to a separate campaign; non-blocking.

### Total estimate
Phases A-G: ~2030 LoC across the public surface + dependent internal moves. Phases A-G can be executed in seven independent commits, each preserving build green and tests green. Phase H is a new module and can lag.

### Cross-cutting blast-radius warning
**Phase B (Error hierarchy) renames `JsonRpcError`'s sub-types.** Consumers (kyo-browser CDP, kyo-ai-harness MCP) currently pattern-match on `JsonRpcError(code, _, _)` with integer codes. They will need to migrate to matching on the new sub-class names (e.g., `case _: JsonRpcError.MethodNotFound`). This is the largest consumer-impact item; pre-coordinate before landing Phase B.
