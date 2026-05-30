# D5. FINAL Consolidated Cleanup Plan: kyo-jsonrpc + kyo-jsonrpc-http

Source-grounded final plan. Supersedes `C-cleanup-plan.md` where any of the four fork verdicts (`D1-fork-policies.md`, `D2-fork-framer-wiretransport.md`, `D3-fork-jsonrpc-error.md`, `D4-fork-transport-jvm.md`) contradicts it. Every claim cites file:line or D-doc:section.

---

## 1. Executive summary

**Final shape (post-cleanup):** 15 top-level public types in `kyo-jsonrpc/shared/src/main/scala/kyo/` (up from 17 by the merge-and-prefix mechanics: -1 from `JsonRpcResponse` merging into `JsonRpcEnvelope.Response`, -1 from `JsonRpcTransportJvm` folding into multi-platform `JsonRpcTransport.unixDomain`, +0 net from renames, all 9 unprefixed types becoming prefixed `JsonRpc*`), plus 1 sibling-module public type in `kyo-jsonrpc-http/src/main/scala/kyo/JsonRpcHttpTransport.scala`. **9 renames** with prefix (`Framer`, `WireTransport`, `HandlerCtx`, `MessageGate`, `CancellationPolicy`, `ProgressPolicy`, `UnknownMethodPolicy`, `IdStrategy`, `ExtrasEncoder` → `JsonRpc{Framer, WireTransport, HandlerCtx, MessageGate, CancellationPolicy, ProgressPolicy, UnknownMethodPolicy, IdStrategy, ExtrasEncoder}`). **1 merge/delete** (`JsonRpcResponse` → `JsonRpcEnvelope.Response`). **5 config-record additions** to `JsonRpcEndpoint.Config` (9 fluent setters, `default` constant, 2 `require` guards, `derives CanEqual`, drop primary-ctor defaults). **1 platform refactor** (`JsonRpcTransportJvm` folds into multi-platform `JsonRpcTransport.unixDomain` with JVM real impl + Native/JS abort stubs). **Internal reorg** flattens 12 internal files into 4 subpackages (`codec`, `transport`, `framing`, `engine`). **Net LoC delta:** approximately +210 lines (rename mechanical 0, prefix-imports +80 across consumers, Config alignment +60, UDS fold +90 with 3 stubs, JsonRpcError polish +10, merge -24, banner sweep -17). **New file count:** +5 new files (`UdsBackend.scala` JVM/JS/Native, scaladoc-only growth on three existing files), 6 file renames, 13 internal file moves, 2 file deletions (`JsonRpcResponse.scala`, `JsonRpcTransportJvm.scala`).

---

## 2. Per-type FINAL decision table

One row per kyo-jsonrpc public type. "C-plan §3" column marks where the row supersedes the C-plan recommendation; "D-resolver" cites which D-doc decided it.

| Type (current name & file:line) | C-plan §3 verdict | FINAL verdict | Target name + path | D-resolver | Supersedes C-plan? |
|---|---|---|---|---|---|
| `JsonRpcEndpoint` (`JsonRpcEndpoint.scala:7`) | KEEP-PUBLIC-AS-IS | KEEP-PUBLIC-AS-IS | `kyo.JsonRpcEndpoint` (same path) | n/a | no |
| `JsonRpcEnvelope` (`JsonRpcEnvelope.scala:7`) | KEEP + MERGE-IN `JsonRpcResponse` | KEEP + MERGE-IN `JsonRpcResponse` | `kyo.JsonRpcEnvelope` (same path) | n/a | no |
| `JsonRpcResponse` (`JsonRpcResponse.scala:12`) | DELETE (merge) | DELETE (merge) | gone; `JsonRpcEnvelope.success` / `JsonRpcEnvelope.failure` factories | n/a | no |
| `JsonRpcError` (`JsonRpcError.scala:11`) | KEEP-PUBLIC-AS-IS | KEEP-FLAT + POLISH (+10 LoC: `serverError`, `applicationError` range-guarded helpers + scaladoc) | `kyo.JsonRpcError` (same path) | D3 §6, §8 | confirms; adds the polish detail |
| `JsonRpcId` (`JsonRpcId.scala:9`) | KEEP-PUBLIC-AS-IS | KEEP-PUBLIC-AS-IS | `kyo.JsonRpcId` (same path) | n/a | no |
| `JsonRpcMethod` (`JsonRpcMethod.scala:14`) | KEEP-PUBLIC-AS-IS | KEEP-PUBLIC-AS-IS | `kyo.JsonRpcMethod` (same path) | n/a | no |
| `JsonRpcCodec` (`JsonRpcCodec.scala:9`) | KEEP-PUBLIC-AS-IS | KEEP-PUBLIC-AS-IS | `kyo.JsonRpcCodec` (same path) | n/a | no |
| `JsonRpcTransport` (`JsonRpcTransport.scala:6`) | KEEP-PUBLIC-AS-IS | KEEP-PUBLIC-AS-IS + GAIN `unixDomain` factory (multi-platform fold from `JsonRpcTransportJvm`) | `kyo.JsonRpcTransport` (same path) | D4 §5 | YES (was "leave as-is"; now gains `unixDomain`) |
| `JsonRpcTransportJvm` (`jvm/.../JsonRpcTransportJvm.scala:10`) | KEEP-PUBLIC-AS-IS | DELETE (fold into `JsonRpcTransport.unixDomain` with platform stubs) | gone | D4 §5 | **YES** (C-plan said leave as-is) |
| `JsonRpcHttpTransport` (`kyo-jsonrpc-http/.../JsonRpcHttpTransport.scala:4`) | KEEP-PUBLIC-AS-IS | KEEP-PUBLIC-AS-IS | `kyo.JsonRpcHttpTransport` (same path) | n/a | no |
| `HandlerCtx` (`HandlerCtx.scala:14`) | NEST-IN-COMPANION (`JsonRpcMethod.Context`) | RENAME-WITH-PREFIX-KEEP-PUBLIC | `kyo.JsonRpcHandlerCtx` (`shared/.../JsonRpcHandlerCtx.scala`) | D1 §1 reasoning extended; brief tiebreaker (PREFIX default + constructed externally via `HandlerCtx.forTest` at `HandlerCtxTest.scala:21, 31, 40, 50` and `JsonRpcMethodTest.scala:29, 131, 146, 158, 174`; appears as parameter type in all handler signatures `JsonRpcMethod.scala:29, 48, 68, 85, 92, 116, 123`; doc-mentioned in `kyo-browser/.../CdpBackend.scala:607`) | **YES** (C-plan said nest) |
| `MessageGate` (`MessageGate.scala:4`) | MOVE-TO-INTERNAL | RENAME-WITH-PREFIX-KEEP-PUBLIC | `kyo.JsonRpcMessageGate` (`shared/.../JsonRpcMessageGate.scala`) | D1 §1 (8 in-repo subclasses at `MessageGateTest.scala:27, 36`, `UnknownMethodPolicyTest.scala:95, 112, 133, 163, 184`, `HttpStyleTest.scala:89`) | **YES** (C-plan said internal) |
| `ExtrasEncoder` (`ExtrasEncoder.scala:4`) | NEST-IN-COMPANION (`JsonRpcEndpoint.Extras`) | RENAME-WITH-PREFIX-KEEP-PUBLIC | `kyo.JsonRpcExtrasEncoder` (`shared/.../JsonRpcExtrasEncoder.scala`) | D1 §6 (10+ external call sites; 1-char nesting cost; `Encoder` suffix communicates role) | **YES** (C-plan said nest) |
| `IdStrategy` (`IdStrategy.scala:4`) | NEST-IN-COMPANION (`JsonRpcEndpoint.IdStrategy`) | RENAME-WITH-PREFIX-KEEP-PUBLIC | `kyo.JsonRpcIdStrategy` (`shared/.../JsonRpcIdStrategy.scala`) | D1 §5 (6 kyo-browser sites + `JsonRpcEndpointTest.scala:438` exercises `IdStrategy.Custom`; matches `HttpMethod`/`HttpStatus` "small enum + Custom hatch" precedent) | **YES** (C-plan said nest) |
| `UnknownMethodPolicy` (`UnknownMethodPolicy.scala:5`) | NEST-IN-COMPANION (`JsonRpcEndpoint.UnknownMethodPolicy`) | RENAME-WITH-PREFIX-KEEP-PUBLIC | `kyo.JsonRpcUnknownMethodPolicy` (`shared/.../JsonRpcUnknownMethodPolicy.scala`) | D1 §4 (kyo-browser `JsonRpcPortInvariantsSpec.scala:304-306` constructs custom policy; 3-segment access cleaner than 4-segment nested) | **YES** (C-plan said nest) |
| `CancellationPolicy` (`CancellationPolicy.scala:10`) | MOVE-TO-INTERNAL | RENAME-WITH-PREFIX-KEEP-PUBLIC | `kyo.JsonRpcCancellationPolicy` (`shared/.../JsonRpcCancellationPolicy.scala`) | D1 §2 (76-line record with `ParamsEncoder`/`ParamsDecoder` aliases + `.lsp`/`.mcp` presets + custom-policy construction at `CancellationPolicyTest.scala:646`; matches `HttpTlsConfig` shape) | **YES** (C-plan said internal) |
| `ProgressPolicy` (`ProgressPolicy.scala:10`) | MOVE-TO-INTERNAL | RENAME-WITH-PREFIX-KEEP-PUBLIC | `kyo.JsonRpcProgressPolicy` (`shared/.../JsonRpcProgressPolicy.scala`) | D1 §3 (structural twin of `CancellationPolicy`; engine error message at `JsonRpcEndpointImpl.scala:374, 454` advertises the type; tests at `ProgressPolicyTest.scala:29, 31`, `BidiTest.scala:191`, `MaxInFlightTest.scala:319`) | **YES** (C-plan said internal) |
| `Framer` (`Framer.scala:7`) | RENAME-WITH-PREFIX | RENAME-WITH-PREFIX-KEEP-PUBLIC | `kyo.JsonRpcFramer` (`shared/.../JsonRpcFramer.scala`) | D2 §6 | confirms |
| `WireTransport` (`WireTransport.scala:6`) | RENAME-WITH-PREFIX | RENAME-WITH-PREFIX-KEEP-PUBLIC | `kyo.JsonRpcWireTransport` (`shared/.../JsonRpcWireTransport.scala`) | D2 §6 | confirms |

**Counts:**
- KEEP-PUBLIC-AS-IS: 7 (`JsonRpcEndpoint`, `JsonRpcEnvelope`, `JsonRpcError`, `JsonRpcId`, `JsonRpcMethod`, `JsonRpcCodec`, `JsonRpcTransport`, `JsonRpcHttpTransport`).
- RENAME-WITH-PREFIX-KEEP-PUBLIC: 9 (`Framer`, `WireTransport`, `HandlerCtx`, `MessageGate`, `ExtrasEncoder`, `IdStrategy`, `UnknownMethodPolicy`, `CancellationPolicy`, `ProgressPolicy`).
- DELETE (merge or fold): 2 (`JsonRpcResponse`, `JsonRpcTransportJvm`).
- MOVE-TO-INTERNAL: 0 (every previously-internal-bound type is now prefix-kept-public).
- NEST-IN-COMPANION: 0 (D1's verdict eliminates nesting; D4 also rejects platform-suffix nesting).

Final public top-level surface in `kyo-jsonrpc/shared/src/main/scala/kyo/`: **15 types** (all prefixed `JsonRpc*`), plus 1 in `kyo-jsonrpc-http/...` (`JsonRpcHttpTransport`).

---

## 3. Per-file FINAL reorganization table

### Shared public files (`kyo-jsonrpc/shared/src/main/scala/kyo/`)

| Source file (current) | Target file | Action | LoC delta |
|---|---|---|---|
| `JsonRpcEndpoint.scala` | `JsonRpcEndpoint.scala` (same path) | KEEP, grows by ~50 lines (fluent setters + `Config.default` + `require` + scaladoc + banner-drop). No nested type additions. | +50 |
| `JsonRpcEnvelope.scala` | `JsonRpcEnvelope.scala` (same path) | KEEP, absorbs `JsonRpcResponse.success`/`failure` factories on the `JsonRpcEnvelope` companion (or on `JsonRpcEnvelope.Response`'s nested companion). | +20 |
| `JsonRpcResponse.scala` | DELETE | DELETE | -24 |
| `JsonRpcError.scala` | `JsonRpcError.scala` (same path) | KEEP. Add `serverError(code, message, data)` + `applicationError(code, message, data)` with `require` range guards; add scaladoc block citing JSON-RPC 2.0 §5.1 and LSP §3.16. | +10 |
| `JsonRpcId.scala` | `JsonRpcId.scala` (same path) | KEEP (banner drop only). | -1 |
| `JsonRpcMethod.scala` | `JsonRpcMethod.scala` (same path) | KEEP. Update `HandlerCtx` references to `JsonRpcHandlerCtx` in 7 sites (`:22, :29, :48, :68, :85, :92, :116, :123`). | 0 (mechanical rename) |
| `JsonRpcCodec.scala` | `JsonRpcCodec.scala` (same path) | KEEP (banner drop only). | -1 |
| `JsonRpcTransport.scala` | `JsonRpcTransport.scala` (same path) | KEEP. Add `def unixDomain(sockPath: Path, framer: JsonRpcFramer = ..., codec: JsonRpcCodec = ...)` factory delegating to `internal.transport.UdsBackend.open`. Update parameter types: `framer: Framer` → `framer: JsonRpcFramer`; `wire: WireTransport` → `wire: JsonRpcWireTransport`. | +12 |
| `HandlerCtx.scala` | `JsonRpcHandlerCtx.scala` | RENAME (file + symbol; type body unchanged) | 0 |
| `MessageGate.scala` | `JsonRpcMessageGate.scala` | RENAME | 0 |
| `ExtrasEncoder.scala` | `JsonRpcExtrasEncoder.scala` | RENAME | 0 |
| `IdStrategy.scala` | `JsonRpcIdStrategy.scala` | RENAME | 0 |
| `UnknownMethodPolicy.scala` | `JsonRpcUnknownMethodPolicy.scala` | RENAME | 0 |
| `CancellationPolicy.scala` | `JsonRpcCancellationPolicy.scala` | RENAME | 0 |
| `ProgressPolicy.scala` | `JsonRpcProgressPolicy.scala` | RENAME | 0 |
| `Framer.scala` | `JsonRpcFramer.scala` | RENAME | 0 |
| `WireTransport.scala` | `JsonRpcWireTransport.scala` | RENAME | 0 |

Net shared public file count: 17 → 15 (deletes `JsonRpcResponse.scala`; no other deletions in shared/; everything else renames in place).

### Shared internal files (`kyo-jsonrpc/shared/src/main/scala/kyo/internal/`)

| Source file | Target file | Action | LoC delta |
|---|---|---|---|
| `CancellationEngine.scala` | `internal/engine/CancellationEngine.scala` | MOVE + package change | 0 |
| `FramerImpl.scala` | `internal/framing/FramerImpl.scala` | MOVE + package change; `Framer` ref → `JsonRpcFramer` | 0 |
| `IdStrategyEngine.scala` | `internal/engine/IdStrategyEngine.scala` | MOVE; `IdStrategy` ref → `JsonRpcIdStrategy` | 0 |
| `InMemoryTransport.scala` | `internal/transport/InMemoryTransport.scala` | MOVE | 0 |
| `JsonRpcCodecImpl.scala` | `internal/codec/JsonRpcCodecImpl.scala` | MOVE; update `JsonRpcResponse` references to `JsonRpcEnvelope.Response` / `JsonRpcEnvelope.success`/`failure` | ~3 |
| `JsonRpcEndpointImpl.scala` | `internal/engine/JsonRpcEndpointImpl.scala` | MOVE; rename refs: `HandlerCtx` → `JsonRpcHandlerCtx`, `MessageGate.Decision` → `JsonRpcMessageGate.Decision`, `ExtrasEncoder` → `JsonRpcExtrasEncoder`, `UnknownMethodPolicy` → `JsonRpcUnknownMethodPolicy`, `IdStrategy` → `JsonRpcIdStrategy`, `CancellationPolicy` → `JsonRpcCancellationPolicy`, `ProgressPolicy` → `JsonRpcProgressPolicy`; update `JsonRpcResponse.success`/`failure` to envelope equivalents | ~20 |
| `JsonRpcRequest.scala` | `internal/codec/JsonRpcRequest.scala` | MOVE | 0 |
| `ProgressEngine.scala` | `internal/engine/ProgressEngine.scala` | MOVE; rename `ProgressPolicy` → `JsonRpcProgressPolicy` | 0 |
| `RateLimitEngine.scala` | `internal/engine/RateLimitEngine.scala` | MOVE | 0 |
| `RawJsonParser.scala` | `internal/codec/RawJsonParser.scala` | MOVE | 0 |
| `StdioWireTransport.scala` | `internal/transport/StdioWireTransport.scala` | MOVE; `WireTransport` ref → `JsonRpcWireTransport`, `Framer` → `JsonRpcFramer` | 0 |
| `WireTransportAdapter.scala` | `internal/transport/WireTransportAdapter.scala` | MOVE; rename refs | 0 |

### JVM-only files (`kyo-jsonrpc/jvm/src/main/scala/`)

| Source file | Target file | Action | LoC delta |
|---|---|---|---|
| `kyo/JsonRpcTransportJvm.scala` | DELETE | DELETE (body relocated; see below) | -47 |
| `kyo/internal/UdsWireTransport.scala` | `kyo/internal/transport/UdsWireTransport.scala` | MOVE | 0 |
| (new) `kyo/internal/transport/UdsBackend.scala` | NEW FILE; body lifted from `JsonRpcTransportJvm.scala:21-33` | NEW | +20 |

### JS-only files (`kyo-jsonrpc/js/src/main/scala/`)

| Target file | Action | LoC delta |
|---|---|---|
| `kyo/internal/transport/UdsBackend.scala` (new) | NEW abort stub | +12 |

### Native-only files (`kyo-jsonrpc/native/src/main/scala/`)

| Target file | Action | LoC delta |
|---|---|---|
| `kyo/internal/transport/UdsBackend.scala` (new) | NEW abort stub | +12 |

### Sibling module (`kyo-jsonrpc-http/src/main/scala/kyo/`)

| Source file | Target file | Action | LoC delta |
|---|---|---|---|
| `JsonRpcHttpTransport.scala` | same | KEEP (banner drop only) | -1 |

**Total file count delta:** shared public 17 → 15 (-2 deletes via merge + zero via rename); shared internal 12 files → 12 files but spread across 4 subdirectories; jvm 2 files → 2 files (`JsonRpcTransportJvm.scala` deleted, `UdsBackend.scala` added, `UdsWireTransport.scala` moved into subpackage); js +1 new file; native +1 new file. Net: +1 file (the abort-stub work outpaces the merge-deletes).

---

## 4. Subpackage structure for `kyo.internal.*` (REVISED post-D1)

D1's verdict (every policy stays public, prefixed) does **not** invalidate the C-plan's internal subpackage scheme: the engine still has its helper objects (`CancellationEngine`, `ProgressEngine`, `RateLimitEngine`, `IdStrategyEngine`) regardless of where the policies live publicly. The four-subpackage layout from C-plan §5 is retained as-is:

| Subpackage | Contents | Notes |
|---|---|---|
| `kyo.internal.codec` | `JsonRpcCodecImpl.scala`, `RawJsonParser.scala`, `JsonRpcRequest.scala` | Wire-encoding implementations |
| `kyo.internal.transport` | `InMemoryTransport.scala`, `StdioWireTransport.scala`, `WireTransportAdapter.scala`, JVM `UdsBackend.scala`, JVM `UdsWireTransport.scala`, JS `UdsBackend.scala`, Native `UdsBackend.scala` | Transport seams + concrete impls |
| `kyo.internal.framing` | `FramerImpl.scala` | Byte-stream framers (line-delimited / content-length) |
| `kyo.internal.engine` | `JsonRpcEndpointImpl.scala`, `CancellationEngine.scala`, `IdStrategyEngine.scala`, `ProgressEngine.scala`, `RateLimitEngine.scala` | Endpoint engine + per-feature helpers. Policy types do NOT live here (D1 §1-§6 verdict). |

Standardise every internal file's package declaration to `package kyo.internal.<sub>` (single line, not `package kyo\npackage internal`), per `A4 §11`.

Compared to C-plan §5: this layout is now **simpler** at the engine level because the three policies (`MessageGate`, `CancellationPolicy`, `ProgressPolicy`) that C-plan §5 placed in `kyo.internal.engine` are not present (D1 keeps them public-prefixed). The engine subpackage shrinks from 8 files to 5.

---

## 5. Config alignment

Confirmed unchanged from `C-cleanup-plan.md §6`, with the type references updated to reflect D1's verdict (every formerly-unprefixed policy becomes `JsonRpc<Name>` rather than `internal.engine.<Name>` or `JsonRpcEndpoint.<Name>`):

```scala
final case class Config(
    codec: JsonRpcCodec,
    cancellation: Maybe[JsonRpcCancellationPolicy],
    progress: Maybe[JsonRpcProgressPolicy],
    unknownMethod: JsonRpcUnknownMethodPolicy,
    gate: Maybe[JsonRpcMessageGate],
    maxInFlight: Maybe[Int],
    requestTimeout: Duration,
    idStrategy: JsonRpcIdStrategy,
    progressResetsTimeout: Boolean
) derives CanEqual:
    require(maxInFlight.forall(_ > 0), s"maxInFlight must be > 0, got $maxInFlight")
    require(
        requestTimeout > Duration.Zero || requestTimeout == Duration.Infinity,
        s"requestTimeout must be positive or Duration.Infinity, got $requestTimeout"
    )

    def codec(c: JsonRpcCodec): Config                     = copy(codec = c)
    def cancellation(p: JsonRpcCancellationPolicy): Config = copy(cancellation = Present(p))
    def progress(p: JsonRpcProgressPolicy): Config         = copy(progress = Present(p))
    def unknownMethod(p: JsonRpcUnknownMethodPolicy): Config = copy(unknownMethod = p)
    def gate(g: JsonRpcMessageGate): Config                = copy(gate = Present(g))
    def maxInFlight(n: Int): Config                        = copy(maxInFlight = Present(n))
    def requestTimeout(d: Duration): Config                = copy(requestTimeout = d)
    def idStrategy(s: JsonRpcIdStrategy): Config           = copy(idStrategy = s)
    def progressResetsTimeout(b: Boolean): Config          = copy(progressResetsTimeout = b)
end Config

object Config:
    val default: Config = Config(
        codec = JsonRpcCodec.Strict2_0,
        cancellation = Absent,
        progress = Absent,
        unknownMethod = JsonRpcUnknownMethodPolicy.minimal,
        gate = Absent,
        maxInFlight = Absent,
        requestTimeout = Duration.Infinity,
        idStrategy = JsonRpcIdStrategy.SequentialLong,
        progressResetsTimeout = false
    )
end Config
```

Five deltas confirmed (per `HttpServerConfig.scala:52-92` precedent):

1. Drop primary-ctor defaults; defaults centralise on `Config.default`.
2. Add 9 per-field fluent setters; `Maybe`-typed fields take bare values and wrap in `Present`.
3. Add `derives CanEqual`.
4. Add 2 `require(...)` guards on `maxInFlight` and `requestTimeout`.
5. Update `JsonRpcEndpoint.init(transport, methods, config: Config = Config.default)(using Frame)`.

---

## 6. Effect-row alignment

Confirmed unchanged from `C-cleanup-plan.md §7`. Single change: drop `Sync` from `JsonRpcEndpoint.init`'s effect row at `JsonRpcEndpoint.scala:105`.

Current: `JsonRpcEndpoint < (Sync & Async & Scope)`.
Target: `JsonRpcEndpoint < (Async & Scope)`.

`Sync` is subsumed by `Async`. `JsonRpcTransport.fromWire` (`JsonRpcTransport.scala:41`), `JsonRpcTransport.stdio` (`:50`), and (post-D4) the new shared `JsonRpcTransport.unixDomain` are already `< (Async & Scope)`. Single-line change in `JsonRpcEndpoint.scala`.

---

## 7. Error-tree alignment

Confirmed unchanged from `C-cleanup-plan.md §8` (FLAT case class survives), with the +10 LoC polish from D3 §6.

Two additions to `JsonRpcError.scala`:

```scala
/** Constructs a server-defined error with code in the JSON-RPC 2.0 server-reserved
  * range (-32099 to -32000). Use this for implementation-defined error codes that
  * are not part of the JSON-RPC standard set. See JSON-RPC 2.0 §5.1.
  */
def serverError(code: Int, message: String, data: Maybe[Structure.Value] = Absent)(using Frame): JsonRpcError =
    require(
        code >= -32099 && code <= -32000,
        s"serverError code must be in [-32099, -32000], got $code; use applicationError for application-defined codes"
    )
    JsonRpcError(code, message, data)

/** Constructs an application-defined error with a code outside the JSON-RPC 2.0
  * reserved range (-32768 to -32000). Use this for custom protocol error codes
  * that have no JSON-RPC standard meaning. See JSON-RPC 2.0 §5.1.
  */
def applicationError(code: Int, message: String, data: Maybe[Structure.Value] = Absent)(using Frame): JsonRpcError =
    require(
        code < -32768 || code > -32000,
        s"applicationError code must be outside [-32768, -32000] (reserved range), got $code; use a standard constant or serverError"
    )
    JsonRpcError(code, message, data)
```

Plus a scaladoc block on the `case class JsonRpcError(...)` declaration citing the spec, the open-extension rationale, and pointing to `ParseError`/`InvalidRequest`/etc. constants for the standard codes (D3 §6 spec).

No structural change; the case class itself is untouched. Total: +10 lines.

---

## 8. Naming-prefix coverage

Every type that gets a new name (final list):

| Current name | Renamed to | Reason | D-resolver |
|---|---|---|---|
| `Framer` | `JsonRpcFramer` | Prefix discipline; protocol-agnostic byte-chunking trait | D2 §6 |
| `WireTransport` | `JsonRpcWireTransport` | Prefix discipline; byte send/incoming/close seam | D2 §6 |
| `HandlerCtx` | `JsonRpcHandlerCtx` | Prefix discipline; appears in every handler signature, constructed externally via `forTest` | brief tiebreaker + D1 §1 reasoning |
| `MessageGate` | `JsonRpcMessageGate` | Prefix discipline; open extension-point trait with 8 in-repo subclasses | D1 §1 |
| `ExtrasEncoder` | `JsonRpcExtrasEncoder` | Prefix discipline; 10+ external call sites use companion factories | D1 §6 |
| `IdStrategy` | `JsonRpcIdStrategy` | Prefix discipline; 6 kyo-browser sites + `Custom` extension hatch exercised | D1 §5 |
| `UnknownMethodPolicy` | `JsonRpcUnknownMethodPolicy` | Prefix discipline; kyo-browser invariants spec constructs custom policy | D1 §4 |
| `CancellationPolicy` | `JsonRpcCancellationPolicy` | Prefix discipline; 76-line dialect-customization record with `.lsp`/`.mcp` presets | D1 §2 |
| `ProgressPolicy` | `JsonRpcProgressPolicy` | Prefix discipline; structural twin of `CancellationPolicy`, advertised in engine error messages | D1 §3 |

Total: **9 renames**. Post-cleanup every kyo-jsonrpc public top-level carries the `JsonRpc*` prefix; no exceptions in the shared package. The kyo-jsonrpc-http sibling type `JsonRpcHttpTransport` already carries the prefix.

---

## 9. Cross-platform alignment

D4 §5 supersedes C-plan §10's "leave as-is". `JsonRpcTransportJvm.scala` is deleted; `unixDomain` is folded into the multi-platform `JsonRpcTransport` companion with platform-specific internal backends.

### Multi-platform structure (post-fold)

| Platform | File | Role |
|---|---|---|
| Shared | `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcTransport.scala` | Adds `def unixDomain(sockPath: Path, framer: JsonRpcFramer = JsonRpcFramer.lineDelimited, codec: JsonRpcCodec = JsonRpcCodec.Strict2_0)(using Frame): JsonRpcTransport < (Async & Scope)` delegating to `internal.transport.UdsBackend.open(sockPath)` |
| JVM | `kyo-jsonrpc/jvm/src/main/scala/kyo/internal/transport/UdsBackend.scala` (new) | Real impl using `java.net.UnixDomainSocketAddress` + `ServerSocketChannel`, body lifted verbatim from current `JsonRpcTransportJvm.scala:21-33` |
| JVM | `kyo-jsonrpc/jvm/src/main/scala/kyo/internal/transport/UdsWireTransport.scala` | Moved from `kyo/internal/UdsWireTransport.scala` to the transport subpackage; body unchanged |
| Native | `kyo-jsonrpc/native/src/main/scala/kyo/internal/transport/UdsBackend.scala` (new) | Abort stub returning `Abort.fail(new UnsupportedOperationException("UDS not yet implemented on Scala Native"))` |
| JS | `kyo-jsonrpc/js/src/main/scala/kyo/internal/transport/UdsBackend.scala` (new) | Abort stub returning `Abort.fail(new UnsupportedOperationException("UDS not yet implemented on Scala.js"))` |

### Test relocation

`kyo-jsonrpc/jvm/src/test/scala/kyo/JsonRpcTransportJvmTest.scala` relocates to `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcTransportUnixTest.scala`. On Native and JS the test catches the `UnsupportedOperationException` and `cancel`s (matching `kyo-http/shared/src/test/scala/kyo/HttpServerUnixTest.scala:17` style). On JVM the test runs the real UDS flow as today.

### Future work (explicitly out of this campaign)

Real Native and JS UDS implementations are deferred to a follow-up task that can either (a) lift from kyo-http's `kyo_tcp.c` AF_UNIX bindings (`kyo-http/native/src/main/resources/scala-native/kyo_tcp.c:179, 226`) and `JsTransport.connectUnix` (`kyo-http/js/src/main/scala/kyo/internal/JsTransport.scala:165`), or (b) consume a future kyo-net extraction. This is documented in §13.

### JS / Native source trees

Per D4 §2 and confirmed by C-plan §10(a): the previously-empty JS / Native source trees now each carry one internal file (`UdsBackend.scala`). The build remains `CrossType.Full` for `kyo-jsonrpc`. No JS / Native public types are added.

---

## 10. REVISED migration phases

Each phase is one atomic commit, ending green on `kyo-jsonrpc/Test/compile` + `kyo-jsonrpc-http/Test/compile` (+ targeted scenario tests for affected types). Per `feedback_targeted_tests_only`, broad cross-platform / regression runs are reserved for phase-group boundaries; per `feedback_sequential_test_runs`, JVM → JS → Native runs sequentially. Per `feedback_commit_between_phases`, every phase ends with a commit (no phase wraps with a "next is X" tease).

### Phase 1 — Internal subpackage reorg + banner sweep + scaladoc adds

**Scope:**
- Create `kyo-jsonrpc/shared/src/main/scala/kyo/internal/{codec,transport,framing,engine}` directories.
- Move all 12 shared-internal files per §3 internal table.
- Move JVM `kyo/internal/UdsWireTransport.scala` into `kyo/internal/transport/` (still under the JVM source root).
- Standardise every internal file's package declaration to `package kyo.internal.<sub>` (single line; rewrites the mixed `package kyo\npackage internal` form in `CancellationEngine.scala`, `JsonRpcEndpointImpl.scala`, `ProgressEngine.scala`, `RateLimitEngine.scala`).
- Strip every `// PUBLIC ...` banner line (line 1 of all 17 shared public files + 1 jvm public file + 1 jsonrpc-http public file). Where a banner described the type's role, fold into the type's scaladoc; otherwise drop.
- Add or expand scaladoc on every public type that lacks one (per `A1 §13`).

**Diff size:** ~13 internal files move + ~19 banner lines removed + ~150 lines of scaladoc additions. Mechanical.

**Affected test classes:** none (no public-surface change).

**Blocked-by:** none. First commit.

### Phase 2 — **REMOVED**

The previous "Phase 2 — Move A3-UNUSED types to internal" is **deleted**. D1 supersedes C-plan §3 on this question: every policy type stays public with a prefix rename. No move-to-internal happens. Phase 2 collapses entirely; subsequent phase numbers shift.

### Phase 3 — **REMOVED** (was: nest `HandlerCtx`)

D1's reasoning extended to `HandlerCtx` (brief tiebreaker, confirmed by external construction at `HandlerCtxTest.scala:21, 31, 40, 50` and parameter type in 7 `JsonRpcMethod.scala` sites) gives PREFIX, not NEST. No nesting phase exists. `HandlerCtx` rename happens in the consolidated rename phase below.

### Phase 4 (renumbered to Phase 2) — Merge `JsonRpcResponse` into `JsonRpcEnvelope.Response`

**Scope:**
- Lift `success(id: JsonRpcId, result: Structure.Value): JsonRpcEnvelope` and `failure(id: JsonRpcId, error: JsonRpcError): JsonRpcEnvelope` factories onto the `JsonRpcEnvelope` companion (`JsonRpcEnvelope.scala:7`), taking the bodies from `JsonRpcResponse.success`/`failure` (`JsonRpcResponse.scala:19-22`).
- The `id` field shape reconciles cleanly: `JsonRpcEnvelope.Response.id: JsonRpcId` is the canonical type; the parse-error-id-unknown branch already routes through `JsonRpcEnvelope.Malformed`. No schema change.
- Delete `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcResponse.scala` and `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcResponseTest.scala`.
- Update `internal/engine/JsonRpcEndpointImpl.scala` references from `JsonRpcResponse.success`/`failure` to `JsonRpcEnvelope.success`/`failure`.
- Update `internal/codec/JsonRpcCodecImpl.scala` if it references `JsonRpcResponse` (verify).
- Migrate any test cases from `JsonRpcResponseTest.scala` into `JsonRpcEnvelopeTest.scala` (per `feedback_test_placement`, test placement follows the runtime type).

**Diff size:** -24 / +30, ~10 reference updates.

**Affected test classes:** `JsonRpcResponseTest` (deleted, ~6 cases migrated), `JsonRpcEnvelopeTest` (gains success/failure cases).

**Blocked-by:** Phase 1.

### Phase 5 (renumbered to Phase 3) — PREFIX-rename all 9 unprefixed types

**Scope:** rename 9 source files + symbols; update all in-module references; update kyo-browser consumer imports.

File renames in `kyo-jsonrpc/shared/src/main/scala/kyo/`:
- `Framer.scala` → `JsonRpcFramer.scala`; type `Framer` → `JsonRpcFramer`.
- `WireTransport.scala` → `JsonRpcWireTransport.scala`; type `WireTransport` → `JsonRpcWireTransport`.
- `HandlerCtx.scala` → `JsonRpcHandlerCtx.scala`; type `HandlerCtx` → `JsonRpcHandlerCtx`.
- `MessageGate.scala` → `JsonRpcMessageGate.scala`; type `MessageGate` → `JsonRpcMessageGate`.
- `ExtrasEncoder.scala` → `JsonRpcExtrasEncoder.scala`; type `ExtrasEncoder` → `JsonRpcExtrasEncoder`.
- `IdStrategy.scala` → `JsonRpcIdStrategy.scala`; type `IdStrategy` → `JsonRpcIdStrategy`.
- `UnknownMethodPolicy.scala` → `JsonRpcUnknownMethodPolicy.scala`; type `UnknownMethodPolicy` → `JsonRpcUnknownMethodPolicy`.
- `CancellationPolicy.scala` → `JsonRpcCancellationPolicy.scala`; type `CancellationPolicy` → `JsonRpcCancellationPolicy`.
- `ProgressPolicy.scala` → `JsonRpcProgressPolicy.scala`; type `ProgressPolicy` → `JsonRpcProgressPolicy`.

Test file renames (mirroring source, 1:1 per Rule 8c):
- `FramerTest.scala` → `JsonRpcFramerTest.scala`.
- `WireTransportTest.scala` → `JsonRpcWireTransportTest.scala`.
- `HandlerCtxTest.scala` → `JsonRpcHandlerCtxTest.scala`.
- `MessageGateTest.scala` → `JsonRpcMessageGateTest.scala`.
- `ExtrasEncoderTest.scala` → `JsonRpcExtrasEncoderTest.scala`.
- `IdStrategyTest.scala` → `JsonRpcIdStrategyTest.scala`.
- `UnknownMethodPolicyTest.scala` → `JsonRpcUnknownMethodPolicyTest.scala`.
- `CancellationPolicyTest.scala` → `JsonRpcCancellationPolicyTest.scala`.
- `ProgressPolicyTest.scala` → `JsonRpcProgressPolicyTest.scala`.

In-module reference updates (sed sweep across all of `kyo-jsonrpc/`): every occurrence of the bare type names is now prefixed. Particular sites:
- `JsonRpcEndpoint.scala`: `Config` field types (`cancellation`, `progress`, `unknownMethod`, `gate`, `idStrategy`), `call`/`notify`/`sendUnmatched` extras parameter (`ExtrasEncoder` → `JsonRpcExtrasEncoder`).
- `JsonRpcMethod.scala`: handler signature `(In, HandlerCtx) => ...` → `(In, JsonRpcHandlerCtx) => ...` at lines 29, 48, 68, 85, 92, 116, 123.
- `JsonRpcTransport.scala`: `fromWire(wire: WireTransport, framer: Framer, ...)` → `fromWire(wire: JsonRpcWireTransport, framer: JsonRpcFramer, ...)`.
- `JsonRpcTransportJvm.scala` (still extant pre-Phase 4): `framer: Framer` → `framer: JsonRpcFramer`.
- `internal/framing/FramerImpl.scala`: `Framer` references.
- `internal/transport/{InMemoryTransport,StdioWireTransport,WireTransportAdapter,UdsWireTransport}.scala`: `WireTransport`, `Framer` references.
- `internal/engine/JsonRpcEndpointImpl.scala`: `HandlerCtx`, `MessageGate.Decision`, `ExtrasEncoder`, `UnknownMethodPolicy`, `IdStrategy`, `CancellationPolicy`, `ProgressPolicy` references (~20 sites).
- `internal/engine/{CancellationEngine,IdStrategyEngine,ProgressEngine}.scala`: policy references.

Consumer (kyo-browser) updates: ~25 lines across 6 files (see §11 for the exhaustive table).

**Diff size:** 9 source-file renames + 9 test-file renames + ~50 in-module reference updates + ~25 kyo-browser updates. Approximately +80 added-line touches (most are pure renames).

**Affected test classes:** all 9 renamed test files (no body change beyond the symbol rename), plus `JsonRpcEndpointTest.scala` (config field type refs), `JsonRpcMethodTest.scala` (`HandlerCtx` → `JsonRpcHandlerCtx`), `JsonRpcTransportTest.scala` (parameter type refs), `JsonRpcTransportJvmTest.scala` (parameter type refs), plus the 4 scenario tests (`BidiTest`, `HttpStyleTest`, `MaxInFlightTest`, `WsStyleTest`).

**Blocked-by:** Phase 2 (the merge must complete before the rename touches the same envelope codepaths).

### Phase 6 (renumbered to Phase 4) — Config alignment (fluent setters + default + require + CanEqual)

**Scope:** implement §5's target Config shape in `JsonRpcEndpoint.scala`.

- Drop primary-ctor defaults; defaults live exclusively on `Config.default`.
- Add 9 fluent setters per §5 sketch.
- Add `Config.default` constant.
- Add 2 `require(...)` guards.
- Add `derives CanEqual`.
- Update `JsonRpcEndpoint.init(...)` default to `config: Config = Config.default`.
- Verify caller sites: `kyo-browser/.../CdpBackend.scala:195, 456` use named-arg `Config(codec = ..., idStrategy = ..., unknownMethod = ..., gate = Absent)` form, which still works with the new shape (all fields are explicit).
- Add tests to `JsonRpcEndpointTest.scala` covering: fluent-setter round-trip, `Config.default` equality, `require` thrown on `maxInFlight = Present(0)` and `requestTimeout = Duration.Zero`, `derives CanEqual` for two equal configs.

**Diff size:** +60 lines in `JsonRpcEndpoint.scala`, ~10 lines of new tests.

**Affected test classes:** `JsonRpcEndpointTest.scala`, plus any test that constructs `Config(...)` positionally (the named-arg sites in kyo-browser still work).

**Blocked-by:** Phase 3.

### Phase 7 (renumbered to Phase 5) — Effect-row drop

**Scope:** drop `Sync` from `JsonRpcEndpoint.init`'s effect row at `JsonRpcEndpoint.scala:105`. Verify no other public method carries a redundant `Sync`. Single-line change.

**Diff size:** 1 line.

**Affected test classes:** none (no test currently asserts the exact row; verify on commit).

**Blocked-by:** Phase 4.

### Phase 8 (NEW, replacing C-plan §10's "leave as-is") — Cross-platform UDS fold-in

**Scope** (per D4 §5):

1. **`kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcTransport.scala`**: add `def unixDomain(sockPath: java.nio.file.Path, framer: JsonRpcFramer = JsonRpcFramer.lineDelimited, codec: JsonRpcCodec = JsonRpcCodec.Strict2_0)(using Frame): JsonRpcTransport < (Async & Scope)` delegating to `internal.transport.UdsBackend.open(sockPath)`.
2. **`kyo-jsonrpc/jvm/src/main/scala/kyo/internal/transport/UdsBackend.scala`** (new file): JVM real implementation, body lifted verbatim from `JsonRpcTransportJvm.scala:21-33`. Returns `JsonRpcWireTransport < (Async & Scope)` opening a `ServerSocketChannel` and registering the `Files.deleteIfExists` finalizer via `Scope.ensure`.
3. **`kyo-jsonrpc/native/src/main/scala/kyo/internal/transport/UdsBackend.scala`** (new file): Abort stub:
   ```scala
   package kyo.internal.transport
   import kyo.*
   import java.nio.file.Path
   private[kyo] object UdsBackend:
       def open(sockPath: Path)(using Frame): JsonRpcWireTransport < (Async & Scope) =
           Abort.fail(new UnsupportedOperationException(
               s"JsonRpcTransport.unixDomain: not yet implemented on Scala Native (sockPath=$sockPath)"
           ))
   end UdsBackend
   ```
4. **`kyo-jsonrpc/js/src/main/scala/kyo/internal/transport/UdsBackend.scala`** (new file): same stub shape, JS message.
5. **`kyo-jsonrpc/jvm/src/main/scala/kyo/JsonRpcTransportJvm.scala`**: DELETE.
6. **`kyo-jsonrpc/jvm/src/test/scala/kyo/JsonRpcTransportJvmTest.scala`**: relocate to `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcTransportUnixTest.scala`. On Native/JS the test catches `UnsupportedOperationException` and `cancel`s (matching `kyo-http/shared/src/test/scala/kyo/HttpServerUnixTest.scala:17` style). On JVM the test runs the real UDS flow.

**Diff size:** -47 (delete `JsonRpcTransportJvm.scala`), +20 (JVM `UdsBackend.scala`), +12 (JS `UdsBackend.scala`), +12 (Native `UdsBackend.scala`), +12 (`JsonRpcTransport.scala` `unixDomain` factory), test relocation neutral. Net ~+10 lines.

**Affected test classes:** `JsonRpcTransportJvmTest` deleted; `JsonRpcTransportUnixTest` (new shared location) on JVM runs real flow, on Native/JS catches and cancels.

**Blocked-by:** Phase 5 (must complete `Sync` drop on `init`; new `unixDomain` factory matches the cleaned row).

### Phase 9 (renumbered to Phase 6) — Final cross-platform green run

**Scope:**

- Run JVM tests: `kyo-jsonrpc/Test/compile`, `kyo-jsonrpc/test`, `kyo-jsonrpc-http/Test/compile`, `kyo-jsonrpc-http/test`, `kyo-browser/Test/compile`, `kyo-browser/test`.
- Run JS tests: `kyo-jsonrpcJS/test`, `kyo-jsonrpc-httpJS/test`, `kyo-browserJS/test`. Per `feedback_sequential_test_runs`, run JVM → JS → Native sequentially, not in parallel.
- Run Native tests: `kyo-jsonrpcNative/test`, `kyo-jsonrpc-httpNative/test`, `kyo-browserNative/test`.
- If any test fails, fix in place; do not weaken tests (`feedback_test_rigor`).
- Per `feedback_own_all_failures`, any red signal during the green run is owned by this campaign, including kyo-browser failures induced by the renames.

**Diff size:** 0 to small fix-ups.

**Affected test classes:** all.

**Blocked-by:** Phase 5 (Phase 8 UDS fold landing in the prior commit).

### Phase summary table

| # | Phase | LoC delta | Files touched | Blocked by |
|---|---|---|---|---|
| 1 | Internal subpackage reorg + banner sweep + scaladoc adds | ~+130 (scaladoc) -19 (banners) +0 (moves) | ~33 files | none |
| 2 | Merge `JsonRpcResponse` into `JsonRpcEnvelope.Response` | -24 / +30 / ~10 ref updates | 2 deletes, 1 grow, 2 ref-site files | Phase 1 |
| 3 | PREFIX-rename 9 types (incl. kyo-browser consumer updates) | ~+80 (mostly imports) | 18 renames + ~30 in-module ref-site files + ~6 kyo-browser files | Phase 2 |
| 4 | Config alignment | +60 | 1 grow + 1 test grow | Phase 3 |
| 5 | Drop `Sync` from `JsonRpcEndpoint.init` | 1 line | 1 file | Phase 4 |
| 6 | UDS fold (delete `JsonRpcTransportJvm`, add multi-platform stubs) | ~+10 net (-47 + 20 + 12 + 12 + 12) | 1 delete + 4 new + 1 grow + 1 test relocate | Phase 5 |
| 7 | Final cross-platform green run | 0 or fix-up | all | Phase 6 |

(Note: phase numbering 1-7 reflects the post-collapse layout; the brief's "Phase 2/3/4/5/6/7/8/9" enumeration maps as `1=Phase 1, removed=Phase 2, removed=Phase 3, Phase 4→2, Phase 5→3, Phase 6→4, Phase 7→5, Phase 8(new)→6, Phase 9→7`.)

---

## 11. kyo-browser consumer-update plan

All kyo-browser file:line sites that need an import or symbol update for the renames. Compiled from `A3 §5` plus a fresh grep against `kyo-browser/shared/src/`.

| File | Line(s) | Symbol affected | Update |
|---|---|---|---|
| `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala` | 41 | `ExtrasEncoder.const` | → `JsonRpcExtrasEncoder.const` |
| `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala` | 44 | `ExtrasEncoder.empty` | → `JsonRpcExtrasEncoder.empty` |
| `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala` | 199 | `UnknownMethodPolicy.minimal` | → `JsonRpcUnknownMethodPolicy.minimal` |
| `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala` | 203 | `IdStrategy.SequentialInt` | → `JsonRpcIdStrategy.SequentialInt` |
| `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala` | 460 | `UnknownMethodPolicy.minimal` | → `JsonRpcUnknownMethodPolicy.minimal` |
| `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala` | 464 | `IdStrategy.SequentialInt` | → `JsonRpcIdStrategy.SequentialInt` |
| `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala` | 576 | `IdStrategy.SequentialInt` | → `JsonRpcIdStrategy.SequentialInt` |
| `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala` | 590, 593 | `ExtrasEncoder.const`, `.empty` | → `JsonRpcExtrasEncoder.const`, `.empty` |
| `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala` | 607-608 | `HandlerCtx` (doc comment) | reword: "Reads sessionId from JsonRpcHandlerCtx.extras..." |
| `kyo-browser/shared/src/test/scala/kyo/internal/CdpBackendTest.scala` | 52 | `IdStrategy.SequentialInt` | → `JsonRpcIdStrategy.SequentialInt` |
| `kyo-browser/shared/src/test/scala/kyo/internal/CdpBackendSmokeTest.scala` | 43 | `IdStrategy.SequentialInt` | → `JsonRpcIdStrategy.SequentialInt` |
| `kyo-browser/shared/src/test/scala/kyo/internal/CdpBackendSmokeTest.scala` | 152, 186, 211, 251 | `ExtrasEncoder.const` | → `JsonRpcExtrasEncoder.const` |
| `kyo-browser/shared/src/test/scala/kyo/internal/CdpBackendLifecycleTest.scala` | 1178 | `IdStrategy.SequentialInt` | → `JsonRpcIdStrategy.SequentialInt` |
| `kyo-browser/shared/src/test/scala/kyo/internal/CdpClientDecoderTest.scala` | 45 | `IdStrategy.SequentialInt` | → `JsonRpcIdStrategy.SequentialInt` |
| `kyo-browser/shared/src/test/scala/kyo/internal/CdpClientDecoderTest.scala` | 267 | `UnknownMethodPolicy.minimal` (doc) | reword |
| `kyo-browser/shared/src/test/scala/kyo/internal/JsonRpcPortInvariantsSpec.scala` | 56, 321 | `IdStrategy.SequentialInt` | → `JsonRpcIdStrategy.SequentialInt` |
| `kyo-browser/shared/src/test/scala/kyo/internal/JsonRpcPortInvariantsSpec.scala` | 212 | `ExtrasEncoder.const` | → `JsonRpcExtrasEncoder.const` |
| `kyo-browser/shared/src/test/scala/kyo/internal/JsonRpcPortInvariantsSpec.scala` | 304-306 | `UnknownMethodPolicy(...)`, `UnknownMethodPolicy.UnknownAction.Drop` | → `JsonRpcUnknownMethodPolicy(...)`, `JsonRpcUnknownMethodPolicy.UnknownAction.Drop` |

**Totals:**
- 9 files touched (1 main + 5 tests + 3 other test files).
- ~25 line changes (most are pure symbol rename via sed).
- Zero references to `MessageGate`, `CancellationPolicy`, `ProgressPolicy`, `Framer`, `WireTransport`, `HandlerCtx` (other than the doc comment), `JsonRpcResponse` — none of these types is consumed by kyo-browser today (`A3 §2`), so the rename is invisible to kyo-browser for those symbols.
- The kyo-browser changes land in Phase 3 (PREFIX-rename) as part of the same atomic commit as the kyo-jsonrpc renames, per the C-plan §15 risk-1 atomic-commit rule.

---

## 12. Risks and verification

### Risks

1. **Atomic-commit cross-module discipline.** Phase 3 renames 9 kyo-jsonrpc public symbols. Every kyo-browser site referencing those symbols MUST update in the same commit, or kyo-browser breaks. Mitigation: Phase 3 commit includes both kyo-jsonrpc and kyo-browser changes. Per `feedback_commit_between_phases` and the atomic-green discipline.
2. **Subpackage rename triggers a one-off cross-platform recompile of `kyo.internal.*`.** Mitigation: run JVM → JS → Native sequentially per `feedback_sequential_test_runs`; confirm green per platform before advancing.
3. **`Config.require` may trip pre-existing tests with edge values.** Sites to verify: `JsonRpcEndpointTest.scala` configurations with `maxInFlight = Present(0)` or `requestTimeout = Duration.Zero`. Mitigation: grep all `Config(` constructions; either correct the test (real bug surfaced) or relax the `require` guard if the test documents an intentional edge case.
4. **`JsonRpcResponse` deletion (Phase 2) loses a Schema-derived type.** No external user imports `kyo.JsonRpcResponse` per `A3 §2.4`. Mitigation: final grep before deletion across the full repo.
5. **`HandlerCtx` rename does not collide with `kyo.Context`** (verified: kyo-core has no top-level `Context` type). Risk is hypothetical; no mitigation needed unless a future kyo-core type takes the name.
6. **UDS fold introduces Native/JS abort stubs.** Any caller invoking `JsonRpcTransport.unixDomain(...)` on Native or JS will fail at runtime with `UnsupportedOperationException`. Mitigation: the relocated `JsonRpcTransportUnixTest` catches and cancels on Native/JS, matching `HttpServerUnixTest`'s pattern. No existing caller in the repo uses UDS on Native/JS (kyo-browser uses HTTP+WS, not UDS).
7. **`feedback_no_type_aliases` constraint on `JsonRpcCancellationPolicy`.** The companion exposes `type ParamsEncoder` and `type ParamsDecoder` (`CancellationPolicy.scala:20-21`). Per the rule "never `type X = ...` at user-facing scope", these aliases are problematic. Mitigation: inline the function types at each use site within the companion. The aliases are internal-to-the-companion already; the rename to `JsonRpcCancellationPolicy` is independent of removing the type aliases. This is a small follow-up that should land in Phase 3.
8. **`JsonRpcMessageGate`'s test-extension surface.** D1 §1 cites 8 in-repo `MessageGate:` anonymous-subclass sites (`MessageGateTest.scala:27, 36`; `UnknownMethodPolicyTest.scala:95, 112, 133, 163, 184`; `HttpStyleTest.scala:89`). Every one becomes `new JsonRpcMessageGate:` in Phase 3. Mitigation: sed sweep covers anonymous-class sites identically to type references.

### Verification gates

Per `feedback_targeted_tests_only`:

- After Phase 1: `kyo-jsonrpc/Test/compile` (verifies the internal reorg compiles).
- After Phase 2: `kyo-jsonrpc/test` filtered to `JsonRpcEnvelopeTest` and `JsonRpcResponseTest`-derived cases; `kyo-jsonrpc/Test/compile` for the engine reference updates.
- After Phase 3: `kyo-jsonrpc/Test/compile` + `kyo-jsonrpc-http/Test/compile` + `kyo-browser/Test/compile` (the cross-module canary; all three modules must compile in the same commit). Run renamed tests `JsonRpcMessageGateTest`, `JsonRpcExtrasEncoderTest`, `JsonRpcIdStrategyTest`, `JsonRpcUnknownMethodPolicyTest`, `JsonRpcCancellationPolicyTest`, `JsonRpcProgressPolicyTest`, `JsonRpcHandlerCtxTest`, `JsonRpcFramerTest`, `JsonRpcWireTransportTest` plus `JsonRpcEndpointTest`, `JsonRpcMethodTest`, `JsonRpcTransportTest`.
- After Phase 4: `JsonRpcEndpointTest` (fluent setter + default + require).
- After Phase 5: `JsonRpcEndpointTest.init` row check.
- After Phase 6: `JsonRpcTransportUnixTest` (new location), `JsonRpcTransportTest` (the new `unixDomain` factory).
- After Phase 7 (final cross-platform green): full suite, all platforms, sequential.

Atomic-green confirmation: every phase commit ends green per the gates above; no phase commit ships red.

---

## 13. Non-goals (explicit)

1. **No kyo-browser behaviour changes.** Only mechanical import / symbol updates required to keep kyo-browser compiling; no kyo-browser API, CDP method, or behaviour change.
2. **No kyo-core changes.** This cleanup touches `kyo-jsonrpc/`, `kyo-jsonrpc-http/`, and (mechanically) `kyo-browser/` only.
3. **No kyo-net extraction.** D2 §2 and D4 §4 confirm kyo-net is hypothetical for these primitives. `JsonRpcFramer` and `JsonRpcWireTransport` stay in kyo-jsonrpc until a separate kyo-net extraction campaign happens; the prefix-rename does not foreclose that future move.
4. **No real Native or JS UDS implementation.** Phase 6 ships abort stubs; real implementations are a separate follow-up (D4 §5 final paragraph) that may lift from kyo-http's UDS code or consume the future kyo-net extraction.
5. **No `JsonRpcMethod` / `JsonRpcHandler` split.** `A4 §12` nice-to-have 13 is deferred. `JsonRpcMethod.apply` doing both contract + handler-pairing jobs is acceptable.
6. **No `JsonRpcEndpoint.Unsafe` low-level API.** `A4 §12` nice-to-have 14 is deferred; no low-level construction need today.
7. **No sealed `JsonRpcError` hierarchy.** D3 §6 rejects this outright; the flat case class with polished helpers wins.
8. **No `JsonRpcEnvelope` `Schema` derivation.** `A4 §10` flagged this as conditional on `Structure.Value` derives stability; not required for the cleanup, and the hand-written codec at `JsonRpcCodecImpl.scala:108, 233` already handles encode/decode.
9. **No JS / Native source population beyond the UDS stub.** `CrossType.Full` builds JS/Native from `shared/`; the only platform-specific source files are the three `UdsBackend.scala`s added in Phase 6.
10. **No nesting of any public type.** D1 supersedes C-plan's nesting recommendations for `HandlerCtx`, `ExtrasEncoder`, `IdStrategy`, `UnknownMethodPolicy`; D4 supersedes for `JsonRpcTransportJvm` (folded, not nested). No type ends up nested under any other public type in this campaign.
11. **No test file consolidation.** Per `feedback_test_placement`, every renamed test file maps 1:1 to its renamed source file. No absorbing of `JsonRpcHandlerCtxTest` into `JsonRpcMethodTest`, no absorbing `JsonRpcExtrasEncoderTest` into `JsonRpcEndpointTest`.

---

## 14. Approval checkpoints

Single-line yes/no questions for sign-off before `/flow` execution. Three blocking items:

1. **Rename 9 types with `JsonRpc*` prefix and keep all public** (`Framer`, `WireTransport`, `HandlerCtx`, `MessageGate`, `ExtrasEncoder`, `IdStrategy`, `UnknownMethodPolicy`, `CancellationPolicy`, `ProgressPolicy` → `JsonRpc<Name>`)? **Recommend YES** (per D1 §1-§6, D2 §6, brief tiebreaker for `HandlerCtx`).

2. **Delete `JsonRpcTransportJvm.scala` and fold `unixDomain` into `JsonRpcTransport` companion** with JVM real backend + JS/Native abort stubs (`UdsBackend.scala` files added; `JsonRpcTransportUnixTest` relocated to shared)? **Recommend YES** (per D4 §5).

3. **Merge `JsonRpcResponse.scala` into `JsonRpcEnvelope`** (delete the standalone file; `success`/`failure` factories move to `JsonRpcEnvelope` companion)? **Recommend YES** (per C-plan §3 + D3-consistent, no D-doc overrides).

If all three are YES, proceed to `/flow` execution starting at Phase 1.
