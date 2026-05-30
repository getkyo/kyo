# kyo-jsonrpc + kyo-jsonrpc-http: Current-state inventory

Inventory of `kyo-jsonrpc` and `kyo-jsonrpc-http` as SUBJECT of an upcoming API cleanup.
No proposals. The prior `kyo-browser/.flow/api-cleanup/A-kyo-jsonrpc-api-survey.md` was
written framing kyo-jsonrpc as a template; this replaces that framing with a flat census.

Source roots: `kyo-jsonrpc/shared/src/main/scala/kyo/*.scala` (17 files),
`kyo-jsonrpc/shared/src/main/scala/kyo/internal/*.scala` (12 files),
`kyo-jsonrpc/jvm/src/main/scala/kyo/*.scala` (1 file),
`kyo-jsonrpc/jvm/src/main/scala/kyo/internal/*.scala` (1 file),
`kyo-jsonrpc-http/src/main/scala/kyo/*.scala` (1 file). Total 32 main-source files, 3273
source lines. No JS-only or Native-only sources.

## 1. Public-surface census

Every top-level declaration at `package kyo` across `kyo-jsonrpc` (shared + jvm) and
`kyo-jsonrpc-http`. "External-visible" means a user importing from `kyo.*` can reference
the symbol; types whose primary constructor is `private[kyo]` are still externally visible
because the type itself is referenced in user signatures, but cannot be instantiated directly.

| Module          | file:line                                                      | Name                              | Kind               | Purpose                                                     | Visibility (primary ctor) | External-visible |
| --------------- | -------------------------------------------------------------- | --------------------------------- | ------------------ | ----------------------------------------------------------- | ------------------------- | ---------------- |
| kyo-jsonrpc     | `shared/.../kyo/CancellationPolicy.scala:10`                   | `CancellationPolicy`              | final case class   | declarative cancel-method wire shape, .lsp / .mcp presets   | public                    | yes              |
| kyo-jsonrpc     | `shared/.../kyo/CancellationPolicy.scala:19`                   | `CancellationPolicy` (companion)  | object             | presets + nested type aliases `ParamsEncoder` / `Decoder`   | public                    | yes              |
| kyo-jsonrpc     | `shared/.../kyo/ExtrasEncoder.scala:4`                         | `ExtrasEncoder`                   | opaque type        | `JsonRpcId => Maybe[Structure.Value] < Sync` factory        | public                    | yes              |
| kyo-jsonrpc     | `shared/.../kyo/ExtrasEncoder.scala:6`                         | `ExtrasEncoder` (companion)       | object             | `apply` / `empty` / `const` + `.resolve` extension          | public                    | yes              |
| kyo-jsonrpc     | `shared/.../kyo/Framer.scala:7`                                | `Framer`                          | trait              | byte-stream framer (`frame` + `parse`)                      | public                    | yes              |
| kyo-jsonrpc     | `shared/.../kyo/Framer.scala:12`                               | `Framer` (companion)              | object             | `lineDelimited` + `contentLength` presets                   | public                    | yes              |
| kyo-jsonrpc     | `shared/.../kyo/HandlerCtx.scala:14`                           | `HandlerCtx`                      | final class        | handler-side cancelled/requestId/extras/progressSink bundle | `private[kyo]`            | yes (refs only)  |
| kyo-jsonrpc     | `shared/.../kyo/HandlerCtx.scala:26`                           | `HandlerCtx` (companion)          | object             | `forTest` factory (test-internal only)                      | public                    | yes              |
| kyo-jsonrpc     | `shared/.../kyo/IdStrategy.scala:4`                            | `IdStrategy`                      | enum               | `SequentialLong` / `SequentialInt` / `Custom`               | public                    | yes              |
| kyo-jsonrpc     | `shared/.../kyo/JsonRpcCodec.scala:9`                          | `JsonRpcCodec`                    | trait              | envelope <-> `Structure.Value` bidirectional codec          | public                    | yes              |
| kyo-jsonrpc     | `shared/.../kyo/JsonRpcCodec.scala:14`                         | `JsonRpcCodec` (companion)        | object             | `Strict2_0` + `Cdp` preset wires-to-internal                | public                    | yes              |
| kyo-jsonrpc     | `shared/.../kyo/JsonRpcEndpoint.scala:7`                       | `JsonRpcEndpoint`                 | final class        | primary user handle: call/notify/cancel/close               | `private[kyo]`            | yes (refs only)  |
| kyo-jsonrpc     | `shared/.../kyo/JsonRpcEndpoint.scala:79`                      | `JsonRpcEndpoint` (companion)     | object             | `init` factory plus nested `Pending` and `Config`           | public                    | yes              |
| kyo-jsonrpc     | `shared/.../kyo/JsonRpcEnvelope.scala:7`                       | `JsonRpcEnvelope`                 | enum               | wire-shape sum (Request/Notification/Response/Malformed)    | public                    | yes              |
| kyo-jsonrpc     | `shared/.../kyo/JsonRpcError.scala:11`                         | `JsonRpcError`                    | case class         | error-channel payload (code/message/data)                   | public                    | yes              |
| kyo-jsonrpc     | `shared/.../kyo/JsonRpcError.scala:13`                         | `JsonRpcError` (companion)        | object             | named code constants + parametric helpers                   | public                    | yes              |
| kyo-jsonrpc     | `shared/.../kyo/JsonRpcId.scala:9`                             | `JsonRpcId`                       | enum               | request id sum (Num/Str)                                    | public                    | yes              |
| kyo-jsonrpc     | `shared/.../kyo/JsonRpcId.scala:14`                            | `JsonRpcId` (companion)           | object             | hand-rolled `given schema: Schema[JsonRpcId]`               | public                    | yes              |
| kyo-jsonrpc     | `shared/.../kyo/JsonRpcMethod.scala:14`                        | `JsonRpcMethod[+S]`               | sealed trait       | method binding (built only through companion factories)     | public (sealed)           | yes              |
| kyo-jsonrpc     | `shared/.../kyo/JsonRpcMethod.scala:25`                        | `JsonRpcMethod` (companion)       | object             | `apply` x2 / `notification` / `dispatch` + `Kind` enum      | public                    | yes              |
| kyo-jsonrpc     | `shared/.../kyo/JsonRpcResponse.scala:12`                      | `JsonRpcResponse`                 | case class         | user-buildable response wire shape (Schema-derived)         | `private[kyo]`            | yes (refs only)  |
| kyo-jsonrpc     | `shared/.../kyo/JsonRpcResponse.scala:18`                      | `JsonRpcResponse` (companion)     | object             | `success(id, result)` / `failure(id, error)`                | public                    | yes              |
| kyo-jsonrpc     | `shared/.../kyo/JsonRpcTransport.scala:6`                      | `JsonRpcTransport`                | trait              | envelope-level transport (`send` / `incoming` / `close`)    | public                    | yes              |
| kyo-jsonrpc     | `shared/.../kyo/JsonRpcTransport.scala:12`                     | `JsonRpcTransport` (companion)    | object             | `inMemory` x2 / `fromWire` / `stdio` factories              | public                    | yes              |
| kyo-jsonrpc     | `shared/.../kyo/MessageGate.scala:4`                           | `MessageGate`                     | trait              | inbound dispatch veto seam                                  | public                    | yes              |
| kyo-jsonrpc     | `shared/.../kyo/MessageGate.scala:7`                           | `MessageGate` (companion)         | object             | holds nested `Decision` enum only                           | public                    | yes              |
| kyo-jsonrpc     | `shared/.../kyo/ProgressPolicy.scala:10`                       | `ProgressPolicy`                  | final case class   | progress-method wire-shape preset (.lsp / .mcp)             | public                    | yes              |
| kyo-jsonrpc     | `shared/.../kyo/ProgressPolicy.scala:20`                       | `ProgressPolicy` (companion)      | object             | `lsp` + `mcp` named values                                  | public                    | yes              |
| kyo-jsonrpc     | `shared/.../kyo/UnknownMethodPolicy.scala:5`                   | `UnknownMethodPolicy`             | final case class   | unknown-request / unknown-notification dispatch policy      | `private[kyo]`            | yes (refs only)  |
| kyo-jsonrpc     | `shared/.../kyo/UnknownMethodPolicy.scala:11`                  | `UnknownMethodPolicy` (companion) | object             | `minimal` / `lsp` / `strict` presets + `UnknownAction` enum | public                    | yes              |
| kyo-jsonrpc     | `shared/.../kyo/WireTransport.scala:6`                         | `WireTransport`                   | trait              | byte-level transport (`send` / `incoming` / `close`)        | public                    | yes              |
| kyo-jsonrpc     | `shared/.../kyo/WireTransport.scala:12`                        | `WireTransport` (companion)       | object             | `empty` no-op preset                                        | public                    | yes              |
| kyo-jsonrpc     | `jvm/.../kyo/JsonRpcTransportJvm.scala:10`                     | `JsonRpcTransportJvm`             | object             | `unixDomain` factory + extension on `JsonRpcTransport.type` | public                    | yes              |
| kyo-jsonrpc-http| `src/main/scala/kyo/JsonRpcHttpTransport.scala:4`              | `JsonRpcHttpTransport`            | object             | `webSocket` factory + extension on `JsonRpcTransport.type`  | public                    | yes              |

Counts (top-level types only, excluding companions): 2 final classes (`JsonRpcEndpoint`,
`HandlerCtx`); 5 traits (`Framer`, `JsonRpcCodec`, `JsonRpcTransport`, `WireTransport`,
`MessageGate`) plus 1 sealed (`JsonRpcMethod`); 5 case classes (`CancellationPolicy`,
`ProgressPolicy`, `UnknownMethodPolicy`, `JsonRpcError`, `JsonRpcResponse`); 3 enums
(`IdStrategy`, `JsonRpcEnvelope`, `JsonRpcId`); 1 opaque type (`ExtrasEncoder`); 2 standalone
objects (`JsonRpcTransportJvm`, `JsonRpcHttpTransport`). 13 companion objects (every type
except `IdStrategy` and `JsonRpcEnvelope`; the two platform objects are themselves
companion-substitutes).

Five public types have `private[kyo]` primary constructors: `JsonRpcEndpoint`,
`HandlerCtx`, `JsonRpcResponse`, `UnknownMethodPolicy`, and nested
`JsonRpcEndpoint.Pending[Out]`. Construction goes through companion factories.

## 2. Internal-surface census

Everything under `kyo.internal` (shared + jvm). Each declaration is `private[kyo]` so the
symbol is invisible outside the `kyo` package.

| Module      | file:line                                                          | Name                          | Kind               | Purpose                                                         |
| ----------- | ------------------------------------------------------------------ | ----------------------------- | ------------------ | --------------------------------------------------------------- |
| kyo-jsonrpc | `shared/.../kyo/internal/CancellationEngine.scala:9`               | `CancellationEngine`          | object             | inbound/outbound cancel routing + timeout auto-fire             |
| kyo-jsonrpc | `shared/.../kyo/internal/FramerImpl.scala:5`                       | `FramerImpl`                  | object             | `parseLineDelimited` / `parseContentLength` parser impl         |
| kyo-jsonrpc | `shared/.../kyo/internal/IdStrategyEngine.scala:6`                 | `IdStrategyEngine`            | object             | `mkNextId(strategy)` allocator factory                          |
| kyo-jsonrpc | `shared/.../kyo/internal/InMemoryTransport.scala:5`                | `InMemoryTransport`           | final class        | `JsonRpcTransport` backed by paired channels                    |
| kyo-jsonrpc | `shared/.../kyo/internal/JsonRpcCodecImpl.scala:5`                 | `JsonRpcCodecImpl`            | object             | `Strict2_0` + `Cdp` codec implementations                       |
| kyo-jsonrpc | `shared/.../kyo/internal/JsonRpcEndpointImpl.scala:7`              | `OutboundReq`                 | case class         | exchange request (method, params, idSignal, abortSignal, extras)|
| kyo-jsonrpc | `shared/.../kyo/internal/JsonRpcEndpointImpl.scala:16`             | `CallerInfo`                  | case class         | per-id outbound bookkeeping struct                              |
| kyo-jsonrpc | `shared/.../kyo/internal/JsonRpcEndpointImpl.scala:24`             | `InboundEntry`                | sealed trait       | Running / Replying / Cancelled state ADT                        |
| kyo-jsonrpc | `shared/.../kyo/internal/JsonRpcEndpointImpl.scala:25-38`          | `InboundEntry` (companion)    | object             | 3 cases (Running, Replying, Cancelled)                          |
| kyo-jsonrpc | `shared/.../kyo/internal/JsonRpcEndpointImpl.scala:40`             | `WriterMsg`                   | sealed trait       | writer-fiber message ADT                                        |
| kyo-jsonrpc | `shared/.../kyo/internal/JsonRpcEndpointImpl.scala:41-44`          | `WriterMsg` (companion)       | object             | 2 cases (SendEnvelope, SuppressIfCancelled)                     |
| kyo-jsonrpc | `shared/.../kyo/internal/JsonRpcEndpointImpl.scala:46`             | `JsonRpcEndpointImpl`         | final class        | engine: holds maps, channels, exchange, all impl methods        |
| kyo-jsonrpc | `shared/.../kyo/internal/JsonRpcEndpointImpl.scala:725`            | `JsonRpcEndpointImpl` (comp.) | object             | `init` + `initEngine` factories                                 |
| kyo-jsonrpc | `shared/.../kyo/internal/JsonRpcRequest.scala:8`                   | `JsonRpcRequest`              | case class         | internal Schema-derived encode struct                           |
| kyo-jsonrpc | `shared/.../kyo/internal/ProgressEngine.scala:9`                   | `ProgressEngine`              | object             | token allocator + per-invocation progress sink builder          |
| kyo-jsonrpc | `shared/.../kyo/internal/RateLimitEngine.scala:4`                  | `RateLimitEngine`             | object             | `maxInFlightGuard` semaphore wrapper                            |
| kyo-jsonrpc | `shared/.../kyo/internal/RawJsonParser.scala:23`                   | `RawJsonParser`               | object             | text JSON <-> `Structure.Value` parser (not kyo-schema format)  |
| kyo-jsonrpc | `shared/.../kyo/internal/StdioWireTransport.scala:6`               | `StdioWireTransport`          | final class        | `WireTransport` over `Console.readLine` / `printLine`           |
| kyo-jsonrpc | `shared/.../kyo/internal/WireTransportAdapter.scala:5`             | `WireTransportAdapter`        | final class        | lifts `(WireTransport, Framer, JsonRpcCodec)` to transport      |
| kyo-jsonrpc | `jvm/.../kyo/internal/UdsWireTransport.scala:8`                    | `UdsWireTransport`            | final class        | JVM-only UDS server-side wire transport (MVP single-client)     |

Counts: 18 internal types (16 shared, 1 jvm, plus 2 sealed-sum companion holders for the
nested types inside `JsonRpcEndpointImpl.scala`). Two of those are sub-ADT companions
(`InboundEntry`, `WriterMsg`) that hold three and two enum-case-like cases respectively. No
internal types live at JS or Native source roots (the `js/` and `native/` directories under
`kyo-jsonrpc/` contain only generated `target/`; everything is in `shared/`).

### Leakage check: internal types referenced from public signatures?

Grep of `internal.*` in public files:

- `JsonRpcEndpoint.scala:7`: `internal.JsonRpcEndpointImpl` appears in a `private[kyo] val impl: ...` field. The field is itself `private[kyo]`, so the symbol stays inside the `kyo` package.
- `JsonRpcCodec.scala:15-16`, `JsonRpcTransport.scala:26,28,42,51`, `Framer.scala:22,36`, `JsonRpcEndpoint.scala:106`, `JsonRpcTransportJvm.scala:30`, `JsonRpcHttpTransport.scala:27,60`: all references inside method bodies, not signatures. No leak.

No public method or field exposes a `kyo.internal.*` type in its declared signature.

## 3. Nested-type catalog

Top-level types that have nested types in their companion:

- `CancellationPolicy.scala:20-21`: nested type aliases `ParamsEncoder`, `ParamsDecoder`. Two private case classes (`LspCancelParams:23`, `McpCancelParams:24`) also live in the companion as `private case class` (visible only inside `CancellationPolicy`).
- `JsonRpcEndpoint.scala:82`: nested `final class Pending[Out] private[kyo]`. Built by `JsonRpcEndpointImpl.callWithProgress`. Holds id, result, progress stream, cancel handle.
- `JsonRpcEndpoint.scala:89`: nested `final case class Config(codec, cancellation, progress, unknownMethod, gate, maxInFlight, requestTimeout, idStrategy, progressResetsTimeout)`.
- `JsonRpcEnvelope.scala:8-25`: four enum cases at `JsonRpcEnvelope.Request`, `.Notification`, `.Response`, `.Malformed`. These are NOT in a companion; they are inside the enum block itself, accessible as `JsonRpcEnvelope.Request` from outside.
- `JsonRpcId.scala:10-11`: enum cases `JsonRpcId.Num(value: Long)` and `JsonRpcId.Str(value: String)`. Companion at `JsonRpcId.scala:14` adds `given schema: Schema[JsonRpcId]`.
- `JsonRpcMethod.scala:26`: nested `enum Kind derives CanEqual` with cases `Request` and `Notification`. Two `final private class`es `RequestMethod` (`JsonRpcMethod.scala:81`) and `NotificationMethod` (`JsonRpcMethod.scala:113`) inside the companion, fully private (not even `private[kyo]`).
- `MessageGate.scala:8`: nested `enum Decision derives CanEqual` with cases `Allow`, `Reject(error: JsonRpcError)`, `Drop`.
- `UnknownMethodPolicy.scala:12`: nested `enum UnknownAction derives CanEqual` with cases `ReplyMethodNotFound`, `Drop`, `Reject`.
- `JsonRpcCodec.scala:15-16`: not a nested type; nested values `Strict2_0` and `Cdp` of type `JsonRpcCodec` (preset references).
- `Framer.scala:17,28`: nested vals `lineDelimited` and `contentLength` (preset references, not nested types).
- `IdStrategy.scala:7`: enum case `IdStrategy.Custom(next: () => JsonRpcId < Sync)` (alongside the two parameterless cases at lines 5-6).
- `JsonRpcError.scala:14-24`: 11 named `val`s for standard error codes; helper `def`s at 26-40. No nested types.
- Internal nested types: `InboundEntry` (`JsonRpcEndpointImpl.scala:24`) is a sealed trait with three companion-nested case classes (`Running`, `Replying`, `Cancelled`). `WriterMsg` (`JsonRpcEndpointImpl.scala:40`) is a sealed trait with two companion-nested case classes (`SendEnvelope`, `SuppressIfCancelled`).

## 4. Companion-object inventory

What each public companion holds (file:line of the companion's `object Foo:` declaration).

| Type                  | Companion file:line                          | Companion contents                                                                              |
| --------------------- | -------------------------------------------- | ----------------------------------------------------------------------------------------------- |
| `CancellationPolicy`  | `CancellationPolicy.scala:19`                | type aliases `ParamsEncoder` / `ParamsDecoder`, two private case classes (LSP/MCP), `lsp` / `mcp` presets, four private encoder/decoder lambdas |
| `ExtrasEncoder`       | `ExtrasEncoder.scala:6`                      | `apply(f)`, `val empty`, `const(extras)`, `extension (self) def resolve(id)`                    |
| `Framer`              | `Framer.scala:12`                            | `val lineDelimited`, `val contentLength` (two anonymous-class presets)                          |
| `HandlerCtx`          | `HandlerCtx.scala:26`                        | `private[kyo] def forTest(...)` (test-only escape hatch)                                        |
| `JsonRpcCodec`        | `JsonRpcCodec.scala:14`                      | `val Strict2_0`, `val Cdp` (wired to `internal.JsonRpcCodecImpl.*`)                             |
| `JsonRpcEndpoint`     | `JsonRpcEndpoint.scala:79`                   | nested `class Pending[Out]`, nested `case class Config`, `def init(transport, methods, config)` |
| `JsonRpcError`        | `JsonRpcError.scala:13`                      | 11 named `val`s (ParseError, InvalidRequest, MethodNotFound, InvalidParams, InternalError, ServerNotInitialized, UnknownErrorCode, RequestCancelled, ContentModified, ServerCancelled, RequestFailed), 5 parametric `def`s (methodNotFound, invalidRequest, invalidParams, internalError, cancelled) |
| `JsonRpcId`           | `JsonRpcId.scala:14`                         | hand-rolled `given schema: Schema[JsonRpcId]` (custom write/read)                               |
| `JsonRpcMethod`       | `JsonRpcMethod.scala:25`                     | nested `enum Kind`, `def apply` (with HandlerCtx), `def apply` (without HandlerCtx overload sugar), `def notification`, `def dispatch`, two `final private class` impls (`RequestMethod`, `NotificationMethod`) |
| `JsonRpcResponse`     | `JsonRpcResponse.scala:18`                   | `success(id, result)` factory, `failure(id, error)` factory                                     |
| `JsonRpcTransport`    | `JsonRpcTransport.scala:12`                  | `def inMemory(capacity)`, `def inMemory` (default 64), `def fromWire(wire, framer, codec)`, `def stdio(framer, codec)` |
| `MessageGate`         | `MessageGate.scala:7`                        | nested `enum Decision`                                                                          |
| `ProgressPolicy`      | `ProgressPolicy.scala:20`                    | two private inline helpers (`field`, `merge`), `val lsp`, `val mcp` presets                     |
| `UnknownMethodPolicy` | `UnknownMethodPolicy.scala:11`               | nested `enum UnknownAction`, `val minimal`, `val lsp`, `val strict` presets                     |
| `WireTransport`       | `WireTransport.scala:12`                     | `val empty` (no-op WireTransport)                                                               |
| `IdStrategy`          | (none in source)                             | no companion; the enum block holds everything                                                   |
| `JsonRpcEnvelope`     | (none in source)                             | no companion; enum cases live in the enum block                                                 |

Pattern: companions are doing three jobs - holding factory `def`s (`init`, `apply`,
`notification`, `dispatch`, `inMemory`, `fromWire`, `stdio`, `success`, `failure`), holding
preset `val`s (`Strict2_0`, `Cdp`, `lineDelimited`, `contentLength`, `lsp`, `mcp`, `minimal`,
`strict`, `SequentialLong`, `SequentialInt`, `empty`), or holding nested sub-types
(`Config`, `Pending`, `Kind`, `Decision`, `UnknownAction`, `ParamsEncoder`, `ParamsDecoder`).
Only one companion (`HandlerCtx`) has nothing user-facing - it exposes a `forTest` factory
that is `private[kyo]`.

## 5. Rule 8 status per file

Rule 8 reference (per audit trail in `kyo-jsonrpc/.flow/rule8-cleanup/`):
- 8a: `private[kyo]` types belong in `package kyo.internal`, not `package kyo`.
- 8b: one top-level type per file.
- 8c: every `Xxx.scala` in main has a matching `XxxTest.scala`.

### 8a (no `private[kyo]` types in `package kyo`)

Grep for `private[kyo]` declarations under non-internal paths found six hits, all on
`private[kyo]` *constructor/member* annotations of otherwise-public types - which is the
sanctioned smart-constructor pattern, not Rule 8a violations:

- `JsonRpcEndpoint.scala:7`: `final class JsonRpcEndpoint private[kyo] (...)` (smart-ctor, allowed)
- `JsonRpcEndpoint.scala:82`: `final class Pending[Out] private[kyo] (...)` (smart-ctor, allowed)
- `JsonRpcResponse.scala:12`: `case class JsonRpcResponse private[kyo] (...)` (smart-ctor, allowed)
- `JsonRpcMethod.scala:18,20,22`: `private[kyo] def schemaIn / schemaOut / handle` (sealed-trait framework-only members, allowed)
- `JsonRpcMethod.scala:89-92, 120-123`: `private[kyo]` overrides of those members in the two `final private class` impls
- `HandlerCtx.scala:14`: `final class HandlerCtx private[kyo] (...)` (smart-ctor, allowed)
- `HandlerCtx.scala:18`: `private[kyo] val progressSink` (field, allowed)
- `HandlerCtx.scala:28`: `private[kyo] def forTest` (test-only escape hatch, allowed)
- `UnknownMethodPolicy.scala:5`: `final case class UnknownMethodPolicy private[kyo] (...)` (smart-ctor, allowed)

There are no fully-`private[kyo]` *top-level types* in `package kyo` (non-internal). Rule
8a is satisfied across all 18 non-internal files.

### 8b (one top-level type per file)

Each public-surface file's top-level-decl count, based on the file scan above. All public
files in `shared/src/main/scala/kyo/*.scala` and the jvm/http public files declare exactly
one top-level type plus its companion. No file declares two unrelated top-level types.

Internal files: `JsonRpcEndpointImpl.scala` declares six top-level entities in the
`kyo.internal` namespace:
- `case class OutboundReq` (line 7)
- `case class CallerInfo` (line 16)
- `sealed trait InboundEntry` (line 24) plus its `object InboundEntry` companion (line 25)
- `sealed trait WriterMsg` (line 40) plus its `object WriterMsg` companion (line 41)
- `final class JsonRpcEndpointImpl` (line 46) plus its `object JsonRpcEndpointImpl` companion (line 725)

That single file at 1288 lines holds the engine, two helper structs (`OutboundReq`,
`CallerInfo`) referenced only by engine code, and two sealed sums (`InboundEntry`,
`WriterMsg`) used only by the engine. From a Rule-8b lens this internal file holds 4
top-level types where it could hold 1, but the audit trail in `rule8-cleanup/` documents
that splitting the engine helpers was deferred because they are not user-facing.

### 8c (matching test file per source file)

| Source file                                      | Test file                                          | Match? |
| ------------------------------------------------ | -------------------------------------------------- | ------ |
| `shared/.../CancellationPolicy.scala`            | `shared/.../CancellationPolicyTest.scala`          | yes    |
| `shared/.../ExtrasEncoder.scala`                 | `shared/.../ExtrasEncoderTest.scala`               | yes    |
| `shared/.../Framer.scala`                        | `shared/.../FramerTest.scala`                      | yes    |
| `shared/.../HandlerCtx.scala`                    | `shared/.../HandlerCtxTest.scala`                  | yes    |
| `shared/.../IdStrategy.scala`                    | `shared/.../IdStrategyTest.scala`                  | yes    |
| `shared/.../JsonRpcCodec.scala`                  | `shared/.../JsonRpcCodecTest.scala`                | yes    |
| `shared/.../JsonRpcEndpoint.scala`               | `shared/.../JsonRpcEndpointTest.scala`             | yes    |
| `shared/.../JsonRpcEnvelope.scala`               | `shared/.../JsonRpcEnvelopeTest.scala`             | yes    |
| `shared/.../JsonRpcError.scala`                  | `shared/.../JsonRpcErrorTest.scala`                | yes    |
| `shared/.../JsonRpcId.scala`                     | `shared/.../JsonRpcIdTest.scala`                   | yes    |
| `shared/.../JsonRpcMethod.scala`                 | `shared/.../JsonRpcMethodTest.scala`               | yes    |
| `shared/.../JsonRpcResponse.scala`               | `shared/.../JsonRpcResponseTest.scala`             | yes    |
| `shared/.../JsonRpcTransport.scala`              | `shared/.../JsonRpcTransportTest.scala`            | yes    |
| `shared/.../MessageGate.scala`                   | `shared/.../MessageGateTest.scala`                 | yes    |
| `shared/.../ProgressPolicy.scala`                | `shared/.../ProgressPolicyTest.scala`              | yes    |
| `shared/.../UnknownMethodPolicy.scala`           | `shared/.../UnknownMethodPolicyTest.scala`         | yes    |
| `shared/.../WireTransport.scala`                 | `shared/.../WireTransportTest.scala`               | yes    |
| `jvm/.../JsonRpcTransportJvm.scala`              | `jvm/.../JsonRpcTransportJvmTest.scala`            | yes    |
| `kyo-jsonrpc-http/.../JsonRpcHttpTransport.scala`| `kyo-jsonrpc-http/.../JsonRpcHttpTransportTest.scala` | yes |

Plus a shared base `JsonRpcTestBase.scala` that the rest extend, and four scenario tests
under `shared/src/test/scala/kyo/scenario/`: `BidiTest`, `HttpStyleTest`, `MaxInFlightTest`,
`WsStyleTest`. These do not have a corresponding `*.scala` in main; they are integration-style
scenarios covering multi-type interactions.

Internal main files do NOT have matching tests, which is the intended Rule 8c carve-out
("test public APIs, not internal helpers"):
- `internal/CancellationEngine.scala`, `internal/FramerImpl.scala`, `internal/IdStrategyEngine.scala`, `internal/InMemoryTransport.scala`, `internal/JsonRpcCodecImpl.scala`, `internal/JsonRpcEndpointImpl.scala`, `internal/JsonRpcRequest.scala`, `internal/ProgressEngine.scala`, `internal/RateLimitEngine.scala`, `internal/RawJsonParser.scala`, `internal/StdioWireTransport.scala`, `internal/WireTransportAdapter.scala`, `jvm/internal/UdsWireTransport.scala`.

All 12+1 internal files are covered indirectly through their public seam tests.

Rule 8 status: 8a clean, 8b clean for public files (one violation file in internal
documented as deferred), 8c clean for all public/main files.

## 6. Effect-row patterns in current public signatures

Top observed patterns by frequency (file:line citations):

1. `A < (Async & Abort[JsonRpcError | Closed])` - call sites that can fault on protocol OR lifecycle: `JsonRpcEndpoint.scala:13` (`call`), `:35` (`callWithProgress`).
2. `A < (Async & Abort[Closed])` - lifecycle-only fault: `JsonRpcEndpoint.scala:20` (`notify`), `:28` (`sendUnmatched`), `:52` (`cancel`), `JsonRpcTransport.scala:7` (`send`), `WireTransport.scala:7` (`send`).
3. `Stream[T, Async & Abort[Closed]]` - inbound or progress stream: `JsonRpcTransport.scala:8` (`incoming`), `WireTransport.scala:8` (`incoming`), `JsonRpcEndpoint.scala:46` (`subscribeProgress` return-element).
4. `Stream[T, Async & Abort[JsonRpcError | Closed]]` - call-returning stream: `JsonRpcEndpoint.scala:42` (`callPartialResults`).
5. `A < Async` (no Abort) - close/drain idempotent: `JsonRpcEndpoint.scala:54` (`awaitDrain`), `:61` (`close()`), `:68` (`close(grace)`), `:75` (`closeNow`), `:49` (`unsubscribeProgress`), `JsonRpcTransport.scala:9` (`close`), `WireTransport.scala:9` (`close`).
6. `A < (Sync & Async & Scope)` - construction-with-finalizer: `JsonRpcEndpoint.scala:105` (`init`), `JsonRpcTransport.scala:41` (`fromWire`), `:50` (`stdio`), `JsonRpcTransportJvm.scala:20` (`unixDomain`).
7. `A < (Sync & Abort[JsonRpcError])` - codec encode side: `JsonRpcCodec.scala:10` (`encode`).
8. `A < Sync` - codec decode side, framer frame, in-memory init: `JsonRpcCodec.scala:11` (`decode`), `Framer.scala:8` (`frame`), `JsonRpcTransport.scala:18,31` (`inMemory`), `ExtrasEncoder.scala:16` (`resolve`).
9. `A < (Async & Abort[JsonRpcError])` - server-side method dispatch: `JsonRpcMethod.scala:22` (`handle` framework-private), `:69` (`dispatch` returns `Maybe[Structure.Value < (Async & Abort[JsonRpcError])]`).
10. `A < (Async & Scope & Abort[HttpException])` - HTTP-WS transport factory: `JsonRpcHttpTransport.scala:10` (`webSocket`).

`Maybe[X]` is used throughout where a value can be absent. No `Option` appears in a public
signature. `Frame` is used implicitly (`(using Frame)`) on every effectful method that can
fault, including factories and constructors. Constructor methods compose `Sync & Async & Scope`
so that `Scope.acquireRelease`-based finalizers are wired at the call site.

## 7. Builder/config patterns currently in use

Only one Config-style pattern: a `final case class Config` nested in the handle's companion
with default values, passed positionally or by name to `.init`.

`JsonRpcEndpoint.Config` (`JsonRpcEndpoint.scala:89`) has 9 fields, all defaulted:

| Field                   | Type                          | Default                          |
| ----------------------- | ----------------------------- | -------------------------------- |
| `codec`                 | `JsonRpcCodec`                | `JsonRpcCodec.Strict2_0`         |
| `cancellation`          | `Maybe[CancellationPolicy]`   | `Absent`                         |
| `progress`              | `Maybe[ProgressPolicy]`       | `Absent`                         |
| `unknownMethod`         | `UnknownMethodPolicy`         | `UnknownMethodPolicy.minimal`    |
| `gate`                  | `Maybe[MessageGate]`          | `Absent`                         |
| `maxInFlight`           | `Maybe[Int]`                  | `Absent`                         |
| `requestTimeout`        | `Duration`                    | `Duration.Infinity`              |
| `idStrategy`            | `IdStrategy`                  | `IdStrategy.SequentialLong`      |
| `progressResetsTimeout` | `Boolean`                     | `false`                          |

There are no fluent `.withFoo(...)` setters or builder types. Mutation happens via the
`.copy(...)` method synthesised on the case class. The `.init` method takes `config: Config
= Config()` (`JsonRpcEndpoint.scala:104`), so the default-config path is `JsonRpcEndpoint.init(transport, methods)`.

Other config-shaped types follow the same shape but at the top level (not nested), because
they are referenced as fields of `JsonRpcEndpoint.Config` and would create a cycle if nested
inside it:

- `CancellationPolicy` (`CancellationPolicy.scala:10`): 6 fields, all required (no defaults). Constructed via `.lsp` or `.mcp` preset, not via direct call.
- `ProgressPolicy` (`ProgressPolicy.scala:10`): 7 fields, all required. Constructed via `.lsp` or `.mcp` preset.
- `UnknownMethodPolicy` (`UnknownMethodPolicy.scala:5`): 3 fields, all required, `private[kyo]` ctor. Constructed via `.minimal` / `.lsp` / `.strict` preset only.

There is no top-level `JsonRpcEndpointConfig` or `JsonRpcEndpointBuilder`. Construction is
one factory (`JsonRpcEndpoint.init`) plus a defaulted `Config` case class.

The transport factories (`JsonRpcTransport.scala:37,47`) use defaulted-argument constructors
rather than a Config case class. `fromWire(wire, framer, codec = JsonRpcCodec.Strict2_0)` and
`stdio(framer = Framer.lineDelimited, codec = JsonRpcCodec.Strict2_0)` are typical.

## 8. Naming patterns in current code

### Types WITH the `JsonRpc*` prefix at `package kyo` (12 total)

`JsonRpcEndpoint` (handle), `JsonRpcTransport` (envelope-level seam), `JsonRpcCodec`
(envelope <-> Structure.Value bidirectional), `JsonRpcEnvelope` (wire-shape sum), `JsonRpcId`
(request id sum), `JsonRpcError` (error-channel ADT), `JsonRpcResponse` (response wire shape),
`JsonRpcMethod` (server-side method binding), `JsonRpcTransportJvm` (JVM-only object holding
extension methods for the shared companion), `JsonRpcHttpTransport` (the kyo-http-backed
WebSocket transport object).

Internal types reaching for the prefix: `JsonRpcEndpointImpl`, `JsonRpcCodecImpl`,
`JsonRpcRequest` (`internal/JsonRpcRequest.scala:8`). The `Impl` suffix is consistent for
"the private engine for the public seam"; the bare `JsonRpcRequest` is an encode-side struct
that mirrors the wire shape for `Schema` derivation, distinct from the public
`JsonRpcEnvelope.Request` variant.

### Types WITHOUT the prefix at `package kyo`

`CancellationPolicy`, `ExtrasEncoder`, `Framer`, `HandlerCtx`, `IdStrategy`, `MessageGate`,
`ProgressPolicy`, `UnknownMethodPolicy`, `WireTransport`. Nine types. Reasoning (from
scaladoc one-liners and the `kyo-browser/.../A-` survey's section 2 reading):

- `Framer` and `WireTransport`: deliberately byte-stream-level seams reusable for any binary
  protocol, not JSON-RPC-specific.
- `MessageGate`, `HandlerCtx`, `ExtrasEncoder`: generic helper seams that happen to be
  consumed only by `JsonRpcEndpoint` today but are not naming-locked to the protocol.
- `CancellationPolicy`, `ProgressPolicy`, `UnknownMethodPolicy`, `IdStrategy`: declarative
  policy types whose semantics aren't tied to JSON-RPC's literal wire grammar (a
  `CancellationPolicy` happens to be wired through a JSON-RPC notification, but the policy
  shape is generic).

No scaladoc explicitly justifies the split. The top-of-file `// PUBLIC X consumed by Y`
single-line comment on each file gives the role but not the prefix rationale. The implicit
decision rule observed: prefix when the type carries the protocol's wire grammar (id, error,
method, envelope, codec) or is the primary user handle (`JsonRpcEndpoint`).

### Suffixes observed

`*Endpoint` (1), `*Transport` (2 traits + 2 objects), `*Codec` (1), `*Envelope` (1), `*Method`
(1), `*Error` (1), `*Response` (1), `*Policy` (3), `*Strategy` (1), `*Encoder` (1), `*Gate`
(1), `*Ctx` (1), `*Id` (1), `*Framer` -> bare `Framer` (1, no suffix).

`*Config` appears once and only nested: `JsonRpcEndpoint.Config`. There is no top-level
`*Config` type.

`*Jvm` / `*Http` are platform/transport suffixes on the two object holders for extension
methods (`JsonRpcTransportJvm`, `JsonRpcHttpTransport`); no other types use these suffixes.

### Lowercase namespace objects

Per `feedback_lowercase_namespace_objects`, names like `isolate`, `internal`, `literal`
should be lowercase when used as namespace objects. kyo-jsonrpc applies this convention only
to the `kyo.internal` package itself (lowercase package name). No companion holds a lowercase
nested namespace object such as `JsonRpcEndpoint.internal`. Type-like nested members are
PascalCase (`Pending`, `Config`, `Kind`, `Decision`, `UnknownAction`); preset values are a
mix of camelCase (`lineDelimited`, `contentLength`, `lsp`, `mcp`, `minimal`, `strict`,
`empty`) and PascalCase when they read like a constant or enum-like name (`Strict2_0`, `Cdp`,
`SequentialLong`, `SequentialInt`, `Custom`).

## 9. Error-type layering

`JsonRpcError` is declared at `JsonRpcError.scala:11` as a `case class`, not a sealed sum:

```scala
case class JsonRpcError(code: Int, message: String, data: Maybe[Structure.Value])
    derives Schema, CanEqual
```

The companion (`JsonRpcError.scala:13`) holds:

- **11 named constants** for standard codes (`JsonRpcError.scala:14-24`): `ParseError`,
  `InvalidRequest`, `MethodNotFound`, `InvalidParams`, `InternalError`,
  `ServerNotInitialized`, `UnknownErrorCode`, `RequestCancelled`, `ContentModified`,
  `ServerCancelled`, `RequestFailed`.
- **5 parametric helpers** (`JsonRpcError.scala:26-40`): `methodNotFound(name)`,
  `invalidRequest(reason)`, `invalidParams(reason)`, `internalError(cause, data)`,
  `cancelled(reason)`. Each takes context and returns a fresh error value.

The choice of `case class` over `enum` is deliberate: JSON-RPC error codes are an open
extension point and every protocol family invents its own. A sealed enum would force a
`Custom(code, message)` case that defeats exhaustiveness anyway.

Both forms surface through `Abort[JsonRpcError | Closed]` on user-facing call paths. The
error union with `Closed` expresses "protocol fault or lifecycle termination" without
introducing a wrapper type.

## 10. `derives Schema` / `derives CanEqual` discipline

Types that derive `Schema, CanEqual` (both, for wire-traveling types):

- `JsonRpcError` (`JsonRpcError.scala:11`)
- `JsonRpcResponse` (`JsonRpcResponse.scala:16`)
- `internal.JsonRpcRequest` (`internal/JsonRpcRequest.scala:12`)
- private case classes `LspCancelParams`, `McpCancelParams` inside `CancellationPolicy.scala:23-24`

Types that derive `CanEqual` only:

- `JsonRpcEnvelope` (`JsonRpcEnvelope.scala:7`)
- `JsonRpcId` (`JsonRpcId.scala:9`) - plus a hand-rolled `given schema: Schema[JsonRpcId]` (`JsonRpcId.scala:16`) for custom number-or-string write/read.
- `JsonRpcMethod.Kind` (`JsonRpcMethod.scala:26`)
- `MessageGate.Decision` (`MessageGate.scala:8`)
- `UnknownMethodPolicy` (`UnknownMethodPolicy.scala:9`) and its nested `.UnknownAction` (`:12`)
- `CancellationPolicy` (`CancellationPolicy.scala:17`)
- `ProgressPolicy` (`ProgressPolicy.scala:18`)
- `IdStrategy` (`IdStrategy.scala:4`)

Rule observed: `CanEqual` is universal on every public ADT (`case class`, `enum`, sealed
sum). `Schema` is added only on types that actually cross the wire as a complete object
graph; types like `JsonRpcEnvelope` skip `Schema` because the codec (`JsonRpcCodec`) is the
hand-written boundary and the envelope is the parsed model not a `Schema`-derived one.

Notably, `JsonRpcMethod`, `Framer`, `JsonRpcCodec`, `WireTransport`, `JsonRpcTransport`,
`MessageGate`, `ExtrasEncoder`, and the platform objects do NOT derive anything because they
are traits, factories, or function-typed values rather than data ADTs.

## 11. Cross-platform code distribution

- `shared/src/main/scala/kyo/*.scala` (17 files): every protocol-portable public type
  (Endpoint, Transport, WireTransport, Framer, Codec, Envelope, Id, Error, Response, Method,
  HandlerCtx, ExtrasEncoder, MessageGate, CancellationPolicy, ProgressPolicy,
  UnknownMethodPolicy, IdStrategy).
- `shared/src/main/scala/kyo/internal/*.scala` (12 files): every protocol-portable engine
  helper (the 1288-line `JsonRpcEndpointImpl.scala` plus 11 smaller helpers).
- `jvm/src/main/scala/kyo/*.scala` (1 file): `JsonRpcTransportJvm.scala` adding the
  Unix-domain-socket capability.
- `jvm/src/main/scala/kyo/internal/*.scala` (1 file): `UdsWireTransport.scala` backing the
  UDS server.
- `js/` and `native/` directories under `kyo-jsonrpc/`: only `target/` artifacts, no source
  files. JS and Native get exactly what `shared/` offers.
- `kyo-jsonrpc-http/src/main/scala/kyo/*.scala` (1 file): `JsonRpcHttpTransport.scala` adding
  the WebSocket transport. The kyo-jsonrpc-http subproject is cross-built (JVM/JS/Native) per
  `build.sbt` but uses `CrossType.Pure` so every platform shares the same source. The HTTP
  transport itself has no JVM/JS/Native source split because kyo-http already abstracts those.
- `kyo-jsonrpc-http/src/main/scala/kyo/internal/*.scala`: empty (no internal files).

No duplicated code observed. Each platform-conditional capability is opt-in via import of
the corresponding object's extension block.

The `build.sbt` declarations:

```
lazy val `kyo-jsonrpc` = crossProject(JSPlatform, JVMPlatform, NativePlatform)
    .withoutSuffixFor(JVMPlatform).crossType(CrossType.Full).in(file("kyo-jsonrpc"))
lazy val `kyo-jsonrpc-http` = crossProject(JSPlatform, JVMPlatform, NativePlatform)
    .withoutSuffixFor(JVMPlatform).crossType(CrossType.Pure).in(file("kyo-jsonrpc-http"))
    .dependsOn(`kyo-jsonrpc`).dependsOn(`kyo-http`)
```

`CrossType.Full` for kyo-jsonrpc enables the jvm-only `JsonRpcTransportJvm`; `CrossType.Pure`
for kyo-jsonrpc-http means no per-platform overrides, the WebSocket transport works
uniformly. kyo-jsonrpc-http depends on both kyo-jsonrpc and kyo-http.

## 12. Test pairing inventory (Rule 8c summary)

19 of 19 public main files have a matching test file (see Section 5.8c table). 13 of 13
internal main files have no direct test, by design (carve-out).

Additional test infrastructure:
- `JsonRpcTestBase.scala` (`shared/src/test/scala/kyo/JsonRpcTestBase.scala:9`): abstract
  `class JsonRpcTestBase extends AsyncFreeSpec with NonImplicitAssertions with BaseKyoCoreTest`. The
  shared base for every individual `*Test.scala`.
- `shared/src/test/scala/kyo/scenario/`: four scenario/integration tests
  (`BidiTest.scala`, `HttpStyleTest.scala`, `MaxInFlightTest.scala`, `WsStyleTest.scala`)
  covering multi-type interactions, not bound to any single source file.

## 13. Sub-package layout

Everything in `kyo.internal` is flat. There are no sub-packages such as
`kyo.internal.codec`, `kyo.internal.transport`, `kyo.internal.engine`. Confirmed by
directory listing of `shared/src/main/scala/kyo/internal/` and
`jvm/src/main/scala/kyo/internal/`.

By topic, the 13 internal files naturally cluster into three groups:

- **Codec / parser group** (3): `JsonRpcCodecImpl.scala`, `RawJsonParser.scala`,
  `JsonRpcRequest.scala`.
- **Transport / framing group** (5): `FramerImpl.scala`, `InMemoryTransport.scala`,
  `StdioWireTransport.scala`, `WireTransportAdapter.scala`, plus the JVM-only
  `UdsWireTransport.scala`.
- **Engine group** (5): `JsonRpcEndpointImpl.scala` (with its 4 nested top-level types),
  `IdStrategyEngine.scala`, `CancellationEngine.scala`, `ProgressEngine.scala`,
  `RateLimitEngine.scala`.

These groupings are not currently reflected in directory structure. If a sub-package
reorganisation were to happen (decision deferred to agent C), the candidate names from the
file naming and roles would be `kyo.internal.codec`, `kyo.internal.transport`, and
`kyo.internal.engine`.

## 14. Migration-size estimate

This section is a rough scale-of-change estimate, NOT a proposal. Numbers are file/type
counts from Sections 1-3.

**Moving public types into `kyo.internal`**: 18 public top-level types (Section 1).
Conceivable internalisation candidates: `MessageGate`, `HandlerCtx`, `JsonRpcResponse`,
`internal.JsonRpcRequest` mirror. Reasonable upper bound: 0-4 types.

**Nesting top-level types into companions**: Section 8 lists 9 unprefixed types. Arguably
nestable into `JsonRpcEndpoint.*` (referenced only via `Config` fields): `CancellationPolicy`,
`ProgressPolicy`, `UnknownMethodPolicy`, `MessageGate`, `IdStrategy`, `ExtrasEncoder`,
`HandlerCtx` (7 types). `Framer` and `WireTransport` are reusable byte-level seams. Per-type
touch surface: 3-6 files (source, tests, engine refs, scaladoc).

**Splitting `JsonRpcEndpointImpl.scala` (1288 lines) per Rule 8b**: 4 currently-bundled
internal types (`OutboundReq`, `CallerInfo`, `InboundEntry`, `WriterMsg`) plus the engine
produce 5 internal files. Touch surface limited to `JsonRpcEndpointImpl.scala` plus minor
import updates in engine helpers.

**Sub-packaging `kyo.internal.*`**: 13 internal files move to subdirs (codec / transport /
engine). Touch surface: 13 internal files plus ~5 public-file references.

**Tests**: 19 main + 4 scenario + 1 base, paired 1:1 with public main files, reference
internals only via public seam; sub-packaging is transparent to them.

**Net scale**: a "modest" migration (split `JsonRpcEndpointImpl.scala`, sub-package
internals, nest 2-3 policy types) touches ~25 files mechanically. A "deep" migration (nest
7 types, internalise 3-4 types, restructure all internals) touches ~50 files across source,
scaladoc, READMEs, and test imports.

End of inventory. Subject is kyo-jsonrpc + kyo-jsonrpc-http as they exist on disk in the
current worktree. No opinion expressed about which changes (if any) should be proposed; that
is for agent A3/A4 to analyse and agent C to propose.
