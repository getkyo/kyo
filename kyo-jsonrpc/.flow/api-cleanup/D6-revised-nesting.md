# D6. REVISED Cleanup Plan: 6 top-level + 11 nested-public shape

Source-grounded revision of `D5-final-plan.md`. Treats D5 as the source of truth for every decision EXCEPT the top-level-vs-nested call. The user challenged D5's "16 top-level public types in `kyo.*`" surface as too many. D6 collapses that to **6 top-level** types under `kyo-jsonrpc/shared` (plus 1 in the `kyo-jsonrpc-http` sibling module) with **11 nested-public** types living under the owning companion. Every nested type stays user-extensible / user-constructible exactly as proven by the test evidence in `D1-fork-policies.md`. The kyo-http precedent `HttpServerConfig.Cors` (`kyo-http/shared/src/main/scala/kyo/HttpServerConfig.scala:117`) and `HttpTlsConfig.{ClientAuth,Version}` (`HttpTlsConfig.scala:41, :44`) document the "nested-public under owning companion" pattern; D6 applies that pattern across the kyo-jsonrpc policy surface.

D6 supersedes D5 §2, §3, §5, §8, §10 (per-type table, per-file table, Config field types, naming-prefix table, migration phases). D6 carries forward D5 §6 (effect-row), §7 (error-tree), §9 (cross-platform UDS), §12-§13 (risks, non-goals) with the type-reference updates required by the nesting.

---

## 1. Executive summary

D6 final shape: **6 top-level public types** in `kyo-jsonrpc/shared/src/main/scala/kyo/` (`JsonRpcEndpoint`, `JsonRpcTransport`, `JsonRpcMethod`, `JsonRpcError`, `JsonRpcEnvelope`, `JsonRpcCodec`), **11 nested-public types** living under their owning companions (7 under `JsonRpcEndpoint`, 2 under `JsonRpcTransport`, 1 under `JsonRpcMethod`, 1 under `JsonRpcEnvelope`), **0 nested-private** policies. Plus **1 sibling-module top-level** type in `kyo-jsonrpc-http/src/main/scala/kyo/JsonRpcHttpTransport.scala`. Total public surface: 6 + 11 + 1 = 18 named types, down from D5's 16 top-level + 4 implicit-nested = 20. Renames: 11 NEST relocations (was 9 PREFIX), 1 MERGE (`JsonRpcResponse` into `JsonRpcEnvelope`), 1 DELETE (`JsonRpcTransportJvm` fold into multi-platform `JsonRpcTransport.unixDomain`). Additions: 5 Config alignment deltas, 10 LoC `JsonRpcError` polish, 4 platform UDS stub files (1 JVM real, 1 Native abort, 1 JS abort, plus the relocated `UdsWireTransport`). Net LoC delta: approximately +230 lines (rename mechanical 0; nesting-import update +50 across consumers including kyo-browser; Config alignment +60; UDS fold +90; `JsonRpcError` polish +10; merge -24; banner sweep -17; +60 scaladoc growth). File count delta: 17 -> 6 in `kyo-jsonrpc/shared/src/main/scala/kyo/` (top-level file count drops from 17 to 6 since 11 files merge into their owning companions); +4 new files in `internal/transport/` subpackage spread across JVM/JS/Native plus the relocated `UdsWireTransport`. Total: deletes 11 top-level files (10 policy/seam files folded into companions, plus `JsonRpcResponse.scala`, plus `JsonRpcTransportJvm.scala`); adds 0 top-level files; adds 4 internal files (UDS backends); moves 13 internal files into subpackages.

---

## 2. Per-type FINAL decision table

One row per current public top-level type. Columns: `Current name | Current placement | New name | New placement | Decision | Tier (A=top-level, B=nested-public) | Rationale (1 line, cite file:line)`.

| Current name | Current placement | New name | New placement | Decision | Tier | Rationale (file:line) |
|---|---|---|---|---|---|---|
| `JsonRpcEndpoint` | `kyo.JsonRpcEndpoint` (`JsonRpcEndpoint.scala:7`) | `JsonRpcEndpoint` | `kyo.JsonRpcEndpoint` (same) | KEEP | A | Primary user handle. Mirrors `HttpServer`/`HttpClient` discipline (`HttpServer.scala:37`). |
| `JsonRpcTransport` | `kyo.JsonRpcTransport` (`JsonRpcTransport.scala:6`) | `JsonRpcTransport` | `kyo.JsonRpcTransport` (same) + GAIN `unixDomain` | KEEP | A | Transport factories root; D4 §5 folds in multi-platform `unixDomain`. |
| `JsonRpcMethod` | `kyo.JsonRpcMethod` (`JsonRpcMethod.scala:14`) | `JsonRpcMethod` | `kyo.JsonRpcMethod` (same) | KEEP | A | Handler registration root; mirrors `HttpRoute`/`HttpHandler`. |
| `JsonRpcError` | `kyo.JsonRpcError` (`JsonRpcError.scala:11`) | `JsonRpcError` | `kyo.JsonRpcError` (same) | KEEP + POLISH | A | Flat case class per D3 §6; +10 LoC `serverError`/`applicationError` helpers. |
| `JsonRpcEnvelope` | `kyo.JsonRpcEnvelope` (`JsonRpcEnvelope.scala:7`) | `JsonRpcEnvelope` | `kyo.JsonRpcEnvelope` (same) + MERGE-IN | KEEP + MERGE | A | Wire-shape ADT root; absorbs `JsonRpcResponse` factories. |
| `JsonRpcCodec` | `kyo.JsonRpcCodec` (`JsonRpcCodec.scala:9`) | `JsonRpcCodec` | `kyo.JsonRpcCodec` (same) | KEEP | A | Codec contract + presets; already prefixed; mirrors `HttpCodec` (`HttpCodec.scala:22`). |
| `JsonRpcHttpTransport` | `kyo.JsonRpcHttpTransport` (kyo-jsonrpc-http) | `JsonRpcHttpTransport` | same | KEEP | A (sibling module) | Sibling subproject; counted separately from kyo-jsonrpc/shared. |
| `JsonRpcResponse` | `kyo.JsonRpcResponse` (`JsonRpcResponse.scala:12`) | gone; factories on `JsonRpcEnvelope` | gone | DELETE (merge) | n/a | D5 §2; duplicates `JsonRpcEnvelope.Response` (`JsonRpcEnvelope.scala:19`). |
| `JsonRpcTransportJvm` | `kyo.JsonRpcTransportJvm` (jvm) | gone; `JsonRpcTransport.unixDomain` | gone | DELETE (fold) | n/a | D4 §5; folds into multi-platform companion with platform backends. |
| `IdStrategy` | `kyo.IdStrategy` (`IdStrategy.scala:4`) | `JsonRpcEndpoint.IdStrategy` | nested in `JsonRpcEndpoint` companion | NEST | B | Used only as `Config.idStrategy` (`JsonRpcEndpoint.scala:97`); kyo-browser `CdpBackend.scala:203, :464, :576` and 6 test sites reference it; `Custom` extension hatch preserved. |
| `UnknownMethodPolicy` | `kyo.UnknownMethodPolicy` (`UnknownMethodPolicy.scala:5`) | `JsonRpcEndpoint.UnknownMethodPolicy` | nested in `JsonRpcEndpoint` companion | NEST | B | Used only as `Config.unknownMethod` (`JsonRpcEndpoint.scala:93`); `private[kyo]` ctor allows external custom construction at `JsonRpcPortInvariantsSpec.scala:304-306`; nesting preserves that visibility. |
| `MessageGate` | `kyo.MessageGate` (`MessageGate.scala:4`) | `JsonRpcEndpoint.MessageGate` | nested in `JsonRpcEndpoint` companion | NEST | B | Open-extension trait; 8 in-repo `new MessageGate:` subclass sites (`MessageGateTest.scala:27, :36`; `UnknownMethodPolicyTest.scala:95, :112, :133, :163, :184`; `HttpStyleTest.scala:89`); nesting under the consumer companion is the kyo-http `HttpServerConfig.Cors` pattern (`HttpServerConfig.scala:117`). |
| `CancellationPolicy` | `kyo.CancellationPolicy` (`CancellationPolicy.scala:10`) | `JsonRpcEndpoint.CancellationPolicy` | nested in `JsonRpcEndpoint` companion | NEST | B | Used only as `Config.cancellation` (`JsonRpcEndpoint.scala:91`); 76-line dialect record; `lsp`/`mcp` presets + custom construction at `CancellationPolicyTest.scala:646`; nesting preserves all surfaces. |
| `ProgressPolicy` | `kyo.ProgressPolicy` (`ProgressPolicy.scala:10`) | `JsonRpcEndpoint.ProgressPolicy` | nested in `JsonRpcEndpoint` companion | NEST | B | Used only as `Config.progress` (`JsonRpcEndpoint.scala:92`); structural twin of `CancellationPolicy`; engine error message at `JsonRpcEndpointImpl.scala:374, :454` advertises the type. |
| `ExtrasEncoder` | `kyo.ExtrasEncoder` (`ExtrasEncoder.scala:4`) | `JsonRpcEndpoint.ExtrasEncoder` | nested in `JsonRpcEndpoint` companion | NEST | B | Parameter type on `JsonRpcEndpoint.call`/`notify`/`sendUnmatched` (`JsonRpcEndpoint.scala:12, :19, :27`); 10+ external call sites; nesting under the consumer companion keeps `Encoder` suffix and matches `HttpFilter.Factory` (`HttpFilter.scala:74`). |
| `HandlerCtx` | `kyo.HandlerCtx` (`HandlerCtx.scala:14`) | `JsonRpcMethod.Context` | nested in `JsonRpcMethod` companion + RENAME | NEST + RENAME | B | Parameter type in handler signatures (`JsonRpcMethod.scala:29, :48, :68, :85, :92, :116, :123`); A4 §3 recommends `JsonRpcMethod.Context`; matches `HttpRoute.RequestDef`/`ResponseDef` (`HttpRoute.scala:237, :392`); `HandlerCtx.forTest` becomes `JsonRpcMethod.Context.forTest`. |
| `Framer` | `kyo.Framer` (`Framer.scala:7`) | `JsonRpcTransport.Framer` | nested in `JsonRpcTransport` companion | NEST | B | Used only as `JsonRpcTransport.fromWire` / `stdio` / `unixDomain` parameter (`JsonRpcTransport.scala:39, :48`; `JsonRpcTransportJvm.scala:18`); preset surface (`lineDelimited`, `contentLength`) stays on the nested companion. |
| `WireTransport` | `kyo.WireTransport` (`WireTransport.scala:6`) | `JsonRpcTransport.WireTransport` | nested in `JsonRpcTransport` companion | NEST | B | Used only as `JsonRpcTransport.fromWire` parameter (`JsonRpcTransport.scala:38`); user-implementable trait; `empty` preset stays on the nested companion. |
| `JsonRpcId` | `kyo.JsonRpcId` (`JsonRpcId.scala:9`) | `JsonRpcEnvelope.Id` | nested in `JsonRpcEnvelope` companion + RENAME | NEST + RENAME | B | Wire-id ADT; sole field type on `JsonRpcEnvelope.Request.id` / `Response.id` / `Notification` (`JsonRpcEnvelope.scala:8-25`); nesting under the wire-message ADT collapses two top-level wire types into one. |

**Counts:**

| Bucket | Count |
|---|---|
| KEEP top-level (Tier A) in kyo-jsonrpc/shared | 6 |
| KEEP top-level (Tier A) in kyo-jsonrpc-http | 1 |
| NEST under owning companion (Tier B) | 11 |
| DELETE (merge or fold) | 2 |
| MOVE-TO-INTERNAL | 0 |

Final shared public surface: 6 top-level + 11 nested = 17 named public types collapsed into 6 top-level files (vs D5's 15 top-level files). The kyo-jsonrpc-http sibling type counts separately.

---

## 3. Per-file FINAL reorganization table

### Shared public files (`kyo-jsonrpc/shared/src/main/scala/kyo/`)

| Source file (current) | Target file | Action | LoC delta |
|---|---|---|---|
| `JsonRpcEndpoint.scala` | `JsonRpcEndpoint.scala` (same) | KEEP; ABSORBS 6 nested types (`IdStrategy`, `UnknownMethodPolicy`, `MessageGate`, `CancellationPolicy`, `ProgressPolicy`, `ExtrasEncoder`) + Config alignment | +290 (absorbed bodies) +60 (Config) +50 (scaladoc) |
| `JsonRpcTransport.scala` | `JsonRpcTransport.scala` (same) | KEEP; ABSORBS 2 nested types (`Framer`, `WireTransport`); ADDS `unixDomain` factory | +50 (absorbed) +12 (`unixDomain`) |
| `JsonRpcMethod.scala` | `JsonRpcMethod.scala` (same) | KEEP; ABSORBS nested `Context` (was `HandlerCtx`); updates handler signature parameter type | +30 (absorbed `HandlerCtx` body) |
| `JsonRpcEnvelope.scala` | `JsonRpcEnvelope.scala` (same) | KEEP; ABSORBS nested `Id` (was `JsonRpcId`) + `success`/`failure` factories (from `JsonRpcResponse`) | +30 (`Id` body) +20 (factories) |
| `JsonRpcError.scala` | `JsonRpcError.scala` (same) | KEEP; +10 LoC polish per D3 §6 | +10 |
| `JsonRpcCodec.scala` | `JsonRpcCodec.scala` (same) | KEEP (banner drop only) | -1 |
| `IdStrategy.scala` | DELETE (body moves to `JsonRpcEndpoint.IdStrategy`) | DELETE | -8 |
| `UnknownMethodPolicy.scala` | DELETE | DELETE | -35 |
| `MessageGate.scala` | DELETE | DELETE | -13 |
| `CancellationPolicy.scala` | DELETE | DELETE | -76 |
| `ProgressPolicy.scala` | DELETE | DELETE | -55 |
| `ExtrasEncoder.scala` | DELETE | DELETE | -17 |
| `Framer.scala` | DELETE | DELETE | -37 |
| `WireTransport.scala` | DELETE | DELETE | -18 |
| `HandlerCtx.scala` | DELETE | DELETE | -32 |
| `JsonRpcId.scala` | DELETE | DELETE | -29 |
| `JsonRpcResponse.scala` | DELETE (merge into `JsonRpcEnvelope`) | DELETE | -24 |

Net shared public file count: 17 -> 6 (eleven files deleted, no new top-level files added; their bodies move into the 4 owning companions plus the `JsonRpcEnvelope` merge target). Top-level surface shrinks 65 percent; nothing user-extensible is lost (every deleted file's named symbols re-appear under `<Companion>.<Name>`).

### Shared internal files (`kyo-jsonrpc/shared/src/main/scala/kyo/internal/`)

| Source file | Target file | Action | LoC delta |
|---|---|---|---|
| `CancellationEngine.scala` | `internal/engine/CancellationEngine.scala` | MOVE + package change; rename refs `CancellationPolicy` -> `JsonRpcEndpoint.CancellationPolicy` | ~5 |
| `FramerImpl.scala` | `internal/framing/FramerImpl.scala` | MOVE + package change; rename refs `Framer` -> `JsonRpcTransport.Framer` | ~3 |
| `IdStrategyEngine.scala` | `internal/engine/IdStrategyEngine.scala` | MOVE; rename refs `IdStrategy` -> `JsonRpcEndpoint.IdStrategy` | ~3 |
| `InMemoryTransport.scala` | `internal/transport/InMemoryTransport.scala` | MOVE | 0 |
| `JsonRpcCodecImpl.scala` | `internal/codec/JsonRpcCodecImpl.scala` | MOVE; update `JsonRpcResponse` refs to `JsonRpcEnvelope.success`/`failure`; update `JsonRpcId` -> `JsonRpcEnvelope.Id` | ~6 |
| `JsonRpcEndpointImpl.scala` | `internal/engine/JsonRpcEndpointImpl.scala` | MOVE; rename refs across ~20 sites: `HandlerCtx` -> `JsonRpcMethod.Context`, `MessageGate.Decision` -> `JsonRpcEndpoint.MessageGate.Decision`, `ExtrasEncoder` -> `JsonRpcEndpoint.ExtrasEncoder`, `UnknownMethodPolicy` -> `JsonRpcEndpoint.UnknownMethodPolicy`, `IdStrategy` -> `JsonRpcEndpoint.IdStrategy`, `CancellationPolicy` -> `JsonRpcEndpoint.CancellationPolicy`, `ProgressPolicy` -> `JsonRpcEndpoint.ProgressPolicy`, `JsonRpcId` -> `JsonRpcEnvelope.Id` | ~25 |
| `JsonRpcRequest.scala` | `internal/codec/JsonRpcRequest.scala` | MOVE; rename `JsonRpcId` -> `JsonRpcEnvelope.Id` | ~2 |
| `ProgressEngine.scala` | `internal/engine/ProgressEngine.scala` | MOVE; rename `ProgressPolicy` -> `JsonRpcEndpoint.ProgressPolicy` | ~3 |
| `RateLimitEngine.scala` | `internal/engine/RateLimitEngine.scala` | MOVE | 0 |
| `RawJsonParser.scala` | `internal/codec/RawJsonParser.scala` | MOVE | 0 |
| `StdioWireTransport.scala` | `internal/transport/StdioWireTransport.scala` | MOVE; rename `WireTransport` -> `JsonRpcTransport.WireTransport`, `Framer` -> `JsonRpcTransport.Framer` | ~4 |
| `WireTransportAdapter.scala` | `internal/transport/WireTransportAdapter.scala` | MOVE; rename refs | ~4 |

### JVM-only files (`kyo-jsonrpc/jvm/src/main/scala/`)

| Source file | Target file | Action | LoC delta |
|---|---|---|---|
| `kyo/JsonRpcTransportJvm.scala` | DELETE | DELETE | -47 |
| `kyo/internal/UdsWireTransport.scala` | `kyo/internal/transport/UdsWireTransport.scala` | MOVE; rename refs `WireTransport` -> `JsonRpcTransport.WireTransport` | ~2 |
| (new) `kyo/internal/transport/UdsBackend.scala` | NEW; body lifted from `JsonRpcTransportJvm.scala:21-33` | NEW | +20 |

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
| `JsonRpcHttpTransport.scala` | same | KEEP (banner drop + update import: `import kyo.JsonRpcId` becomes `import kyo.JsonRpcEnvelope.Id`) | -1 |

Net file count: shared public 17 -> 6 (-11); shared internal 12 -> 12 files spread across 4 subpackages; jvm public 1 -> 0; jvm internal 1 -> 2 (UDS backend + relocated UdsWireTransport); js 0 -> 1; native 0 -> 1; jsonrpc-http 1 -> 1. Net: -8 source files overall.

---

## 4. Subpackage structure for `kyo.internal.*`

D5 §4's four-subpackage layout is unchanged. Verified against `A4 §11`:

| Subpackage | Contents | Notes |
|---|---|---|
| `kyo.internal.codec` | `JsonRpcCodecImpl.scala`, `RawJsonParser.scala`, `JsonRpcRequest.scala` | Wire-encoding implementations |
| `kyo.internal.transport` | `InMemoryTransport.scala`, `StdioWireTransport.scala`, `WireTransportAdapter.scala`, JVM `UdsBackend.scala`, JVM `UdsWireTransport.scala`, JS `UdsBackend.scala`, Native `UdsBackend.scala` | Transport seams + concrete impls |
| `kyo.internal.framing` | `FramerImpl.scala` | Byte-stream framers (line-delimited / content-length) |
| `kyo.internal.engine` | `JsonRpcEndpointImpl.scala`, `CancellationEngine.scala`, `IdStrategyEngine.scala`, `ProgressEngine.scala`, `RateLimitEngine.scala` | Endpoint engine + per-feature helpers |

Standardize every internal file's package declaration to `package kyo.internal.<sub>` (single line). Rewrite the mixed `package kyo\npackage internal` form currently present in `CancellationEngine.scala`, `JsonRpcEndpointImpl.scala`, `ProgressEngine.scala`, `RateLimitEngine.scala` (per `A4 §11`).

Engine subpackage's contents are the same regardless of where the policies live publicly. The 11 nested-public policy/seam types are referenced from the engine via their new dotted names (`JsonRpcEndpoint.CancellationPolicy`, `JsonRpcEndpoint.MessageGate.Decision`, etc.) and remain externally extensible.

---

## 5. Config alignment

Confirmed unchanged from D5 §5 except that every formerly-top-level field type now lives nested under `JsonRpcEndpoint`. Updated field types:

```scala
object JsonRpcEndpoint:
    // ... nested types: IdStrategy, UnknownMethodPolicy, MessageGate,
    //                   CancellationPolicy, ProgressPolicy, ExtrasEncoder

    final case class Config(
        codec: JsonRpcCodec,
        cancellation: Maybe[CancellationPolicy],
        progress: Maybe[ProgressPolicy],
        unknownMethod: UnknownMethodPolicy,
        gate: Maybe[MessageGate],
        maxInFlight: Maybe[Int],
        requestTimeout: Duration,
        idStrategy: IdStrategy,
        progressResetsTimeout: Boolean
    ) derives CanEqual:
        require(maxInFlight.forall(_ > 0), s"maxInFlight must be > 0, got $maxInFlight")
        require(
            requestTimeout > Duration.Zero || requestTimeout == Duration.Infinity,
            s"requestTimeout must be positive or Duration.Infinity, got $requestTimeout"
        )

        def codec(c: JsonRpcCodec): Config                       = copy(codec = c)
        def cancellation(p: CancellationPolicy): Config          = copy(cancellation = Present(p))
        def progress(p: ProgressPolicy): Config                  = copy(progress = Present(p))
        def unknownMethod(p: UnknownMethodPolicy): Config        = copy(unknownMethod = p)
        def gate(g: MessageGate): Config                         = copy(gate = Present(g))
        def maxInFlight(n: Int): Config                          = copy(maxInFlight = Present(n))
        def requestTimeout(d: Duration): Config                  = copy(requestTimeout = d)
        def idStrategy(s: IdStrategy): Config                    = copy(idStrategy = s)
        def progressResetsTimeout(b: Boolean): Config            = copy(progressResetsTimeout = b)
    end Config

    object Config:
        val default: Config = Config(
            codec = JsonRpcCodec.Strict2_0,
            cancellation = Absent,
            progress = Absent,
            unknownMethod = UnknownMethodPolicy.minimal,
            gate = Absent,
            maxInFlight = Absent,
            requestTimeout = Duration.Infinity,
            idStrategy = IdStrategy.SequentialLong,
            progressResetsTimeout = false
        )
    end Config
end JsonRpcEndpoint
```

Inside the `JsonRpcEndpoint` companion the nested types reference each other by short name (`UnknownMethodPolicy.minimal`, `IdStrategy.SequentialLong`); external callers spell them as `JsonRpcEndpoint.UnknownMethodPolicy.minimal` etc. Five deltas confirmed (per `HttpServerConfig.scala:52-92`):

1. Drop primary-ctor defaults; defaults centralize on `Config.default`.
2. Add 9 per-field fluent setters; `Maybe`-typed fields take bare values and wrap in `Present`.
3. Add `derives CanEqual`.
4. Add 2 `require(...)` guards on `maxInFlight` and `requestTimeout`.
5. Update `JsonRpcEndpoint.init(transport, methods, config: Config = Config.default)(using Frame)`.

---

## 6. Effect-row alignment

Unchanged from D5 §6. Single change: drop `Sync` from `JsonRpcEndpoint.init`'s effect row at `JsonRpcEndpoint.scala:105`.

Current: `JsonRpcEndpoint < (Sync & Async & Scope)`. Target: `JsonRpcEndpoint < (Async & Scope)`. `Sync` is subsumed by `Async`. `JsonRpcTransport.fromWire` (`JsonRpcTransport.scala:41`), `JsonRpcTransport.stdio` (`:50`), and the new shared `JsonRpcTransport.unixDomain` are already `< (Async & Scope)`. Single-line change in `JsonRpcEndpoint.scala`.

---

## 7. Error-tree alignment

Unchanged from D5 §7. FLAT `JsonRpcError` case class confirmed per D3 §6. +10 LoC polish:

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

Plus a scaladoc block on the `case class JsonRpcError(...)` declaration citing JSON-RPC 2.0 §5.1 and LSP §3.16. No structural change; the case class itself is untouched. Total: +10 lines.

---

## 8. Naming-prefix coverage

D6 supersedes D5 §8 entirely. The naming move is not a PREFIX rename; it is a NEST + (optional) RENAME. Final list of every rename:

| Current name | New name | Reason | D-resolver |
|---|---|---|---|
| `IdStrategy` | `JsonRpcEndpoint.IdStrategy` | NEST; only used as `Config.idStrategy` field | D1 §5 + D6 user challenge |
| `UnknownMethodPolicy` | `JsonRpcEndpoint.UnknownMethodPolicy` | NEST; only used as `Config.unknownMethod` field | D1 §4 + D6 user challenge |
| `MessageGate` | `JsonRpcEndpoint.MessageGate` | NEST; consumer-companion home for open-extension trait | D1 §1 + D6 user challenge |
| `CancellationPolicy` | `JsonRpcEndpoint.CancellationPolicy` | NEST; only used as `Config.cancellation` field | D1 §2 + D6 user challenge |
| `ProgressPolicy` | `JsonRpcEndpoint.ProgressPolicy` | NEST; only used as `Config.progress` field | D1 §3 + D6 user challenge |
| `ExtrasEncoder` | `JsonRpcEndpoint.ExtrasEncoder` | NEST; parameter type on three `JsonRpcEndpoint` methods | D1 §6 + D6 user challenge |
| `Framer` | `JsonRpcTransport.Framer` | NEST; only used as `JsonRpcTransport.fromWire`/`stdio`/`unixDomain` parameter | D2 §6 + D6 user challenge |
| `WireTransport` | `JsonRpcTransport.WireTransport` | NEST; only used as `JsonRpcTransport.fromWire` parameter | D2 §6 + D6 user challenge |
| `HandlerCtx` | `JsonRpcMethod.Context` | NEST + RENAME; parameter type in handler signatures; `Context` is the consumer-facing name | A4 §3 + D6 user challenge |
| `JsonRpcId` | `JsonRpcEnvelope.Id` | NEST + RENAME; sole wire-id field type on three envelope cases | A4 §3 + D6 user challenge |

Total: 11 NEST moves (9 NEST-only, 2 NEST + RENAME). Every nested name preserves the full surface (companion factories, presets, nested sub-enums like `Decision` / `UnknownAction`) under the new dotted path.

The 6 top-level types (`JsonRpcEndpoint`, `JsonRpcTransport`, `JsonRpcMethod`, `JsonRpcError`, `JsonRpcEnvelope`, `JsonRpcCodec`) already carry the `JsonRpc*` prefix and stay unchanged. The kyo-jsonrpc-http sibling type `JsonRpcHttpTransport` is also already prefixed. Total prefix-coverage across the public surface: 100 percent.

D6 supersedes D5 §8 based on user's 16-vs-7 challenge: D5 prefix-renames 9 types to top-level `JsonRpc*` siblings; D6 nests those 9 plus 2 additional rename-and-nest moves (`HandlerCtx` -> `JsonRpcMethod.Context`, `JsonRpcId` -> `JsonRpcEnvelope.Id`) under their owning companions.

---

## 9. Cross-platform alignment

Unchanged from D5 §9. D4 §5 supersedes C-plan §10's "leave as-is". `JsonRpcTransportJvm.scala` is deleted; `unixDomain` folds into the multi-platform `JsonRpcTransport` companion with platform-specific internal backends.

### Multi-platform structure (post-fold)

| Platform | File | Role |
|---|---|---|
| Shared | `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcTransport.scala` | Adds `def unixDomain(sockPath: Path, framer: JsonRpcTransport.Framer = JsonRpcTransport.Framer.lineDelimited, codec: JsonRpcCodec = JsonRpcCodec.Strict2_0)(using Frame): JsonRpcTransport < (Async & Scope)` delegating to `internal.transport.UdsBackend.open(sockPath)`. Note the `Framer` reference is now nested. |
| JVM | `kyo-jsonrpc/jvm/src/main/scala/kyo/internal/transport/UdsBackend.scala` (new) | Real impl using `java.net.UnixDomainSocketAddress` + `ServerSocketChannel`, body lifted verbatim from current `JsonRpcTransportJvm.scala:21-33` |
| JVM | `kyo-jsonrpc/jvm/src/main/scala/kyo/internal/transport/UdsWireTransport.scala` | Moved from `kyo/internal/UdsWireTransport.scala` to the transport subpackage; body updated to reference `JsonRpcTransport.WireTransport` |
| Native | `kyo-jsonrpc/native/src/main/scala/kyo/internal/transport/UdsBackend.scala` (new) | Abort stub returning `Abort.fail(new UnsupportedOperationException("UDS not yet implemented on Scala Native"))` |
| JS | `kyo-jsonrpc/js/src/main/scala/kyo/internal/transport/UdsBackend.scala` (new) | Abort stub returning `Abort.fail(new UnsupportedOperationException("UDS not yet implemented on Scala.js"))` |

### Test relocation

`kyo-jsonrpc/jvm/src/test/scala/kyo/JsonRpcTransportJvmTest.scala` relocates to `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcTransportUnixTest.scala`. On Native and JS the test catches the `UnsupportedOperationException` and cancels (matching `kyo-http/shared/src/test/scala/kyo/HttpServerUnixTest.scala:17` style). On JVM the test runs the real UDS flow.

### Future work (out of scope)

Real Native and JS UDS implementations defer to a follow-up task that can lift from kyo-http's `kyo_tcp.c` AF_UNIX bindings or consume a future kyo-net extraction.

---

## 10. REVISED migration phases

Each phase is one atomic commit, ending green on `kyo-jsonrpc/Test/compile` + `kyo-jsonrpc-http/Test/compile` + (when reached) `kyo-browser/Test/compile`. Per `feedback_targeted_tests_only`, broad cross-platform / regression runs are reserved for phase-group boundaries. Per `feedback_sequential_test_runs`, JVM -> JS -> Native runs sequentially. Per `feedback_commit_between_phases`, every phase ends with a commit.

### Phase 1: Internal subpackage reorg + banner sweep + scaladoc adds

**Scope:** identical to D5 Phase 1.

- Create `kyo-jsonrpc/shared/src/main/scala/kyo/internal/{codec,transport,framing,engine}` directories.
- Move all 12 shared-internal files per §3 internal table.
- Move JVM `kyo/internal/UdsWireTransport.scala` into `kyo/internal/transport/`.
- Standardize every internal file's package declaration to `package kyo.internal.<sub>` (single line).
- Strip every `// PUBLIC ...` banner line.
- Add or expand scaladoc on every public type that lacks one.

**Diff size:** ~13 internal files move + ~19 banner lines removed + ~150 lines of scaladoc additions.

**Affected test classes:** none.

**Blocked-by:** none. First commit.

### Phase 2: Merge `JsonRpcResponse` into `JsonRpcEnvelope`

**Scope:** identical intent to D5's "Phase 2 (renumbered)".

- Lift `success(id, result)` and `failure(id, error)` factories onto the `JsonRpcEnvelope` companion (`JsonRpcEnvelope.scala:7`), bodies from `JsonRpcResponse.scala:19-22`.
- Delete `JsonRpcResponse.scala` and `JsonRpcResponseTest.scala` (cases migrate into `JsonRpcEnvelopeTest.scala`).
- Update `internal/engine/JsonRpcEndpointImpl.scala` references from `JsonRpcResponse.success`/`failure` to `JsonRpcEnvelope.success`/`failure`.
- Update `internal/codec/JsonRpcCodecImpl.scala` `JsonRpcResponse` references.

**Diff size:** -24 / +30, ~10 reference updates.

**Affected test classes:** `JsonRpcResponseTest` (deleted; ~6 cases migrated), `JsonRpcEnvelopeTest` (gains success/failure cases).

**Blocked-by:** Phase 1.

### Phase 3: NEST 11 types under their owning companions

This phase replaces D5 Phase 3's "PREFIX-rename 9 types". The blast radius and LoC envelope are similar; the mechanic differs (file deletion + body absorption vs file rename).

**Scope:**

For each of the 11 nested-public types (per §2 / §8), perform: delete the standalone file, absorb its body into the owning companion, update all references throughout `kyo-jsonrpc`, `kyo-jsonrpc-http`, and `kyo-browser`.

#### Per-type sub-operations

1. **`IdStrategy` -> `JsonRpcEndpoint.IdStrategy`**: delete `IdStrategy.scala` (8 lines); inline its enum body into the `JsonRpcEndpoint` companion. Update kyo-jsonrpc internal refs (`internal/engine/IdStrategyEngine.scala`, `internal/engine/JsonRpcEndpointImpl.scala`). Update kyo-browser refs at `CdpBackend.scala:203, :464, :576` + 7 test sites. Rename `IdStrategyTest.scala` to keep mapping (the test continues to extend `JsonRpcTestBase`; the body imports `JsonRpcEndpoint.IdStrategy`).

2. **`UnknownMethodPolicy` -> `JsonRpcEndpoint.UnknownMethodPolicy`**: delete `UnknownMethodPolicy.scala` (35 lines); inline case class + `UnknownAction` enum + presets into the `JsonRpcEndpoint` companion. Update kyo-jsonrpc internal refs. Update kyo-browser `CdpBackend.scala:199, :460` and `JsonRpcPortInvariantsSpec.scala:304-306`.

3. **`MessageGate` -> `JsonRpcEndpoint.MessageGate`**: delete `MessageGate.scala` (13 lines); inline trait + `Decision` enum into the `JsonRpcEndpoint` companion. Update kyo-jsonrpc internal refs (`internal/engine/JsonRpcEndpointImpl.scala:967, :969, :976, :1134, :1136, :1148`). Update 8 in-repo anonymous-subclass sites (`MessageGateTest.scala:27, :36`; `UnknownMethodPolicyTest.scala:95, :112, :133, :163, :184`; `HttpStyleTest.scala:89`) from `new MessageGate:` to `new JsonRpcEndpoint.MessageGate:`.

4. **`CancellationPolicy` -> `JsonRpcEndpoint.CancellationPolicy`**: delete `CancellationPolicy.scala` (76 lines); inline case class + `ParamsEncoder`/`ParamsDecoder` aliases + private `LspCancelParams`/`McpCancelParams` + `lsp`/`mcp` presets into the `JsonRpcEndpoint` companion. Note: the two `type X = ...` aliases at `CancellationPolicy.scala:20-21` need to be inlined per `feedback_no_type_aliases` (companion-local but still flagged by D5 §12 risk 7); inline the function types at each use site. Update kyo-jsonrpc internal refs (`internal/engine/CancellationEngine.scala`, `internal/engine/JsonRpcEndpointImpl.scala`).

5. **`ProgressPolicy` -> `JsonRpcEndpoint.ProgressPolicy`**: delete `ProgressPolicy.scala` (55 lines); inline case class + private helpers + `lsp`/`mcp` presets into the `JsonRpcEndpoint` companion. Update `internal/engine/ProgressEngine.scala`, `internal/engine/JsonRpcEndpointImpl.scala`.

6. **`ExtrasEncoder` -> `JsonRpcEndpoint.ExtrasEncoder`**: delete `ExtrasEncoder.scala` (17 lines); inline opaque type + `apply`/`empty`/`const` factories + `resolve` extension into the `JsonRpcEndpoint` companion. Update parameter types in `JsonRpcEndpoint.call`/`notify`/`sendUnmatched`. Update kyo-browser `CdpBackend.scala:41, :44, :590, :593` and 4 test sites.

7. **`Framer` -> `JsonRpcTransport.Framer`**: delete `Framer.scala` (37 lines); inline trait + `lineDelimited`/`contentLength` presets into the `JsonRpcTransport` companion. Update `JsonRpcTransport.fromWire`/`stdio`/`unixDomain` parameter types. Update kyo-jsonrpc internal refs (`internal/framing/FramerImpl.scala`, `internal/transport/StdioWireTransport.scala`, `internal/transport/WireTransportAdapter.scala`).

8. **`WireTransport` -> `JsonRpcTransport.WireTransport`**: delete `WireTransport.scala` (18 lines); inline trait + `empty` preset into the `JsonRpcTransport` companion. Update `JsonRpcTransport.fromWire` parameter type. Update kyo-jsonrpc internal refs and JVM `UdsWireTransport.scala`.

9. **`HandlerCtx` -> `JsonRpcMethod.Context`** (NEST + RENAME): delete `HandlerCtx.scala` (32 lines); inline final class + `forTest` factory into the `JsonRpcMethod` companion, renaming `HandlerCtx` to `Context`. Update handler signature parameter at `JsonRpcMethod.scala:29, :48, :68, :85, :92, :116, :123`. Update kyo-jsonrpc internal refs (`internal/engine/JsonRpcEndpointImpl.scala`). Update kyo-browser doc comment at `CdpBackend.scala:607-608`.

10. **`JsonRpcId` -> `JsonRpcEnvelope.Id`** (NEST + RENAME): delete `JsonRpcId.scala` (29 lines); inline enum + hand-rolled Schema into the `JsonRpcEnvelope` companion, renaming `JsonRpcId` to `Id`. Update field types on `JsonRpcEnvelope.Request.id` / `Response.id` / `Notification.id` (`JsonRpcEnvelope.scala:8-25`). Update kyo-jsonrpc internal refs (`internal/codec/JsonRpcCodecImpl.scala`, `internal/codec/JsonRpcRequest.scala`, `internal/engine/JsonRpcEndpointImpl.scala`, all `IdStrategyEngine.scala` returns).

11. **`JsonRpcMethod` companion's `Context` import**: confirm the renamed nested type is visible at use sites. Handler signature becomes `(In, JsonRpcMethod.Context) => Out < S`.

Test file renames mirror source moves (Rule 8c):

- `IdStrategyTest.scala`, `UnknownMethodPolicyTest.scala`, `MessageGateTest.scala`, `CancellationPolicyTest.scala`, `ProgressPolicyTest.scala`, `ExtrasEncoderTest.scala`, `FramerTest.scala`, `WireTransportTest.scala`, `HandlerCtxTest.scala`, `JsonRpcIdTest.scala`: per Rule 8c, every test file maps 1:1 to a source file. Since the source files no longer exist as standalone units, the test files either (a) consolidate into the owning companion's test file (`JsonRpcEndpointTest`, `JsonRpcTransportTest`, `JsonRpcMethodTest`, `JsonRpcEnvelopeTest`), or (b) stay separate as topic tests of the nested type and rename to reflect the new dotted name (e.g., `JsonRpcEndpointIdStrategyTest.scala`).

D6 chooses (b) for blast-radius minimization: each test file renames to `JsonRpcEndpoint<Type>Test.scala` (or `JsonRpcTransport<Type>Test.scala`, etc.) and updates its imports. The test bodies stay 1:1 with the nested-type semantics they were already exercising. Final test files:

- `IdStrategyTest.scala` -> `JsonRpcEndpointIdStrategyTest.scala`
- `UnknownMethodPolicyTest.scala` -> `JsonRpcEndpointUnknownMethodPolicyTest.scala`
- `MessageGateTest.scala` -> `JsonRpcEndpointMessageGateTest.scala`
- `CancellationPolicyTest.scala` -> `JsonRpcEndpointCancellationPolicyTest.scala`
- `ProgressPolicyTest.scala` -> `JsonRpcEndpointProgressPolicyTest.scala`
- `ExtrasEncoderTest.scala` -> `JsonRpcEndpointExtrasEncoderTest.scala`
- `FramerTest.scala` -> `JsonRpcTransportFramerTest.scala`
- `WireTransportTest.scala` -> `JsonRpcTransportWireTransportTest.scala`
- `HandlerCtxTest.scala` -> `JsonRpcMethodContextTest.scala`
- `JsonRpcIdTest.scala` -> `JsonRpcEnvelopeIdTest.scala`

Plus consumer (kyo-browser) updates: ~25 lines across 9 files (see §11).

**Diff size:** 11 source-file deletes + 4 companion absorptions (`JsonRpcEndpoint.scala` grows by ~290 LoC; `JsonRpcTransport.scala` by ~50; `JsonRpcMethod.scala` by ~30; `JsonRpcEnvelope.scala` by ~30) + 10 test-file renames + ~50 in-module reference updates + ~25 kyo-browser updates. Approximately +400 absorbed-body LoC and +75 import-update LoC. Net source LoC ~ +75 across kyo-jsonrpc + kyo-browser; the absorbed-body LoC is migrated, not added.

**Affected test classes:** all 10 renamed test files (body change limited to import + dotted-name updates), plus `JsonRpcEndpointTest.scala` (config field type refs), `JsonRpcMethodTest.scala` (`HandlerCtx` -> `Context`), `JsonRpcTransportTest.scala` (parameter type refs), `JsonRpcEnvelopeTest.scala` (`Id` refs), plus the 4 scenario tests (`BidiTest`, `HttpStyleTest`, `MaxInFlightTest`, `WsStyleTest`).

**Blocked-by:** Phase 2 (the merge must complete before the rename touches the same envelope codepaths; the `JsonRpcEnvelope` companion is the target of both the merge and the `Id` nesting).

### Phase 4: Config alignment (fluent setters + default + require + CanEqual)

Unchanged from D5 Phase 4 ("renumbered to Phase 4") with the type references updated per §5 above. The 5 deltas land in the `JsonRpcEndpoint` companion alongside the nested types absorbed in Phase 3.

**Scope:**

- Drop primary-ctor defaults; defaults live exclusively on `Config.default`.
- Add 9 fluent setters.
- Add `Config.default` constant.
- Add 2 `require(...)` guards.
- Add `derives CanEqual`.
- Update `JsonRpcEndpoint.init(...)` default to `config: Config = Config.default`.
- Verify caller sites: `kyo-browser/.../CdpBackend.scala:195, :456` use named-arg form, which still works.
- Add tests to `JsonRpcEndpointTest.scala` covering fluent-setter round-trip, `Config.default` equality, `require` thrown on edge values, `derives CanEqual`.

**Diff size:** +60 lines in `JsonRpcEndpoint.scala`, ~10 lines of new tests.

**Affected test classes:** `JsonRpcEndpointTest.scala`.

**Blocked-by:** Phase 3.

### Phase 5: Effect-row drop

Unchanged from D5. Drop `Sync` from `JsonRpcEndpoint.init`'s effect row at `JsonRpcEndpoint.scala:105`. Single-line change.

**Diff size:** 1 line.

**Affected test classes:** none.

**Blocked-by:** Phase 4.

### Phase 6: Cross-platform UDS fold-in

Unchanged from D5 Phase 6 except for the `Framer` reference, which is now `JsonRpcTransport.Framer`:

```scala
def unixDomain(
    sockPath: java.nio.file.Path,
    framer: JsonRpcTransport.Framer = JsonRpcTransport.Framer.lineDelimited,
    codec: JsonRpcCodec = JsonRpcCodec.Strict2_0
)(using Frame): JsonRpcTransport < (Async & Scope) = ...
```

(Inside the `JsonRpcTransport` companion itself the reference is just `Framer`; from outside it is `JsonRpcTransport.Framer`.)

Per D4 §5:

1. Add `def unixDomain` to `JsonRpcTransport`'s shared companion.
2. New file `kyo-jsonrpc/jvm/src/main/scala/kyo/internal/transport/UdsBackend.scala` (real impl).
3. New file `kyo-jsonrpc/native/src/main/scala/kyo/internal/transport/UdsBackend.scala` (abort stub).
4. New file `kyo-jsonrpc/js/src/main/scala/kyo/internal/transport/UdsBackend.scala` (abort stub).
5. Delete `kyo-jsonrpc/jvm/src/main/scala/kyo/JsonRpcTransportJvm.scala`.
6. Relocate `JsonRpcTransportJvmTest.scala` to `JsonRpcTransportUnixTest.scala` in shared/test/.

**Diff size:** -47 (delete jvm file), +20 (jvm UdsBackend), +12 each (js/native), +12 (`unixDomain` factory), test relocation neutral. Net ~+10 lines.

**Affected test classes:** `JsonRpcTransportJvmTest` deleted; `JsonRpcTransportUnixTest` (new shared location).

**Blocked-by:** Phase 5.

### Phase 7: Final cross-platform green run

Unchanged from D5 Phase 7.

**Scope:** JVM -> JS -> Native sequentially (per `feedback_sequential_test_runs`).

- JVM: `kyo-jsonrpc/Test/compile`, `kyo-jsonrpc/test`, `kyo-jsonrpc-http/Test/compile`, `kyo-jsonrpc-http/test`, `kyo-browser/Test/compile`, `kyo-browser/test`.
- JS: `kyo-jsonrpcJS/test`, `kyo-jsonrpc-httpJS/test`, `kyo-browserJS/test`.
- Native: `kyo-jsonrpcNative/test`, `kyo-jsonrpc-httpNative/test`, `kyo-browserNative/test`.

**Diff size:** 0 to small fix-ups.

**Affected test classes:** all.

**Blocked-by:** Phase 6.

### Phase summary table

| # | Phase | LoC delta | Files touched | Blocked by |
|---|---|---|---|---|
| 1 | Internal subpackage reorg + banner sweep + scaladoc adds | +130 scaladoc / -19 banners | ~33 files | none |
| 2 | Merge `JsonRpcResponse` into `JsonRpcEnvelope` | -24 / +30 / ~10 ref updates | 2 deletes, 1 grow, 2 ref-site files | Phase 1 |
| 3 | NEST 11 types under their owning companions (incl. kyo-browser consumer updates) | +75 net (after absorbed-body migration) | 11 deletes + 4 companion grows + 10 test-renames + ~30 in-module ref-site files + ~9 kyo-browser files | Phase 2 |
| 4 | Config alignment | +60 | 1 grow + 1 test grow | Phase 3 |
| 5 | Drop `Sync` from `JsonRpcEndpoint.init` | 1 line | 1 file | Phase 4 |
| 6 | UDS fold | +10 net | 1 delete + 4 new + 1 grow + 1 test relocate | Phase 5 |
| 7 | Final cross-platform green run | 0 or fix-up | all | Phase 6 |

D6 supersedes D5 §10 based on user's 16-vs-7 challenge: Phase 3 mechanic changes from "PREFIX-rename 9 types" (file rename + symbol rename) to "NEST 11 types under owning companion" (file delete + body absorption + dotted-name reference update). LoC envelope and blast radius are comparable; the user-facing public surface drops from 16 top-level to 6 top-level.

---

## 11. kyo-browser consumer-update plan

All kyo-browser file:line sites that need an import or symbol update for the nesting moves. Compiled from `A3 §5` plus a fresh grep against `kyo-browser/shared/src/`. Import statements change from `import kyo.<Name>` to `import kyo.JsonRpcEndpoint.<Name>` (or via star-import `import kyo.JsonRpcEndpoint.*`).

| File | Line(s) | Symbol affected | Update |
|---|---|---|---|
| `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala` | top of file (imports) | add `import kyo.JsonRpcEndpoint.{ExtrasEncoder, IdStrategy, UnknownMethodPolicy}` | NEW import |
| `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala` | 41 | `ExtrasEncoder.const` | unchanged after import (was `kyo.ExtrasEncoder.const`, now `kyo.JsonRpcEndpoint.ExtrasEncoder.const`) |
| `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala` | 44 | `ExtrasEncoder.empty` | unchanged after import |
| `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala` | 199 | `UnknownMethodPolicy.minimal` | unchanged after import |
| `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala` | 203 | `IdStrategy.SequentialInt` | unchanged after import |
| `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala` | 460 | `UnknownMethodPolicy.minimal` | unchanged after import |
| `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala` | 464 | `IdStrategy.SequentialInt` | unchanged after import |
| `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala` | 576 | `IdStrategy.SequentialInt` | unchanged after import |
| `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala` | 590, 593 | `ExtrasEncoder.const`, `.empty` | unchanged after import |
| `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala` | 607-608 | `HandlerCtx` (doc comment) | reword: "Reads sessionId from JsonRpcMethod.Context.extras..." |
| `kyo-browser/shared/src/test/scala/kyo/internal/CdpBackendTest.scala` | imports | add `import kyo.JsonRpcEndpoint.IdStrategy` | NEW import |
| `kyo-browser/shared/src/test/scala/kyo/internal/CdpBackendTest.scala` | 52 | `IdStrategy.SequentialInt` | unchanged after import |
| `kyo-browser/shared/src/test/scala/kyo/internal/CdpBackendSmokeTest.scala` | imports | add `import kyo.JsonRpcEndpoint.{ExtrasEncoder, IdStrategy}` | NEW import |
| `kyo-browser/shared/src/test/scala/kyo/internal/CdpBackendSmokeTest.scala` | 43 | `IdStrategy.SequentialInt` | unchanged after import |
| `kyo-browser/shared/src/test/scala/kyo/internal/CdpBackendSmokeTest.scala` | 152, 186, 211, 251 | `ExtrasEncoder.const` | unchanged after import |
| `kyo-browser/shared/src/test/scala/kyo/internal/CdpBackendLifecycleTest.scala` | imports | add `import kyo.JsonRpcEndpoint.IdStrategy` | NEW import |
| `kyo-browser/shared/src/test/scala/kyo/internal/CdpBackendLifecycleTest.scala` | 1178 | `IdStrategy.SequentialInt` | unchanged after import |
| `kyo-browser/shared/src/test/scala/kyo/internal/CdpClientDecoderTest.scala` | imports | add `import kyo.JsonRpcEndpoint.IdStrategy` | NEW import |
| `kyo-browser/shared/src/test/scala/kyo/internal/CdpClientDecoderTest.scala` | 45 | `IdStrategy.SequentialInt` | unchanged after import |
| `kyo-browser/shared/src/test/scala/kyo/internal/CdpClientDecoderTest.scala` | 267 | `UnknownMethodPolicy.minimal` (doc) | reword |
| `kyo-browser/shared/src/test/scala/kyo/internal/JsonRpcPortInvariantsSpec.scala` | imports | add `import kyo.JsonRpcEndpoint.{ExtrasEncoder, IdStrategy, UnknownMethodPolicy}` | NEW import |
| `kyo-browser/shared/src/test/scala/kyo/internal/JsonRpcPortInvariantsSpec.scala` | 56, 321 | `IdStrategy.SequentialInt` | unchanged after import |
| `kyo-browser/shared/src/test/scala/kyo/internal/JsonRpcPortInvariantsSpec.scala` | 212 | `ExtrasEncoder.const` | unchanged after import |
| `kyo-browser/shared/src/test/scala/kyo/internal/JsonRpcPortInvariantsSpec.scala` | 304-306 | `UnknownMethodPolicy(...)`, `UnknownMethodPolicy.UnknownAction.Drop` | unchanged after import (the `private[kyo]` constructor remains visible since `kyo.internal` is inside the `kyo` package boundary) |

**Totals:**

- 9 files touched (1 main + 8 tests across 5 test files).
- ~25 line changes plus 5 new `import` lines.
- Zero references to `JsonRpcEnvelope.Id` (kyo-browser uses `JsonRpcEndpoint.callWithProgress` and never names `JsonRpcId` directly; verified against `A3 §2`).
- Zero references to the other nested types (`MessageGate`, `CancellationPolicy`, `ProgressPolicy`, `Framer`, `WireTransport`, `JsonRpcMethod.Context`, `JsonRpcResponse`) so those nest moves are invisible to kyo-browser.
- The kyo-browser changes land in Phase 3 as part of the same atomic commit as the kyo-jsonrpc nestings.

Compared to D5 §11: D5 had 25 line changes mostly via sed-rename to `JsonRpcXxx` top-level names. D6 has 25 line changes plus 5 import additions. Site-by-site, the symbol usages themselves are unchanged (callers already write `IdStrategy.SequentialInt`, `ExtrasEncoder.const`, etc.); only the import header changes.

---

## 12. Risks and verification

### Risks

1. **Atomic-commit cross-module discipline.** Phase 3 nests 11 kyo-jsonrpc public symbols. Every kyo-browser site referencing those symbols must update in the same commit. Mitigation: Phase 3 commit includes both kyo-jsonrpc and kyo-browser changes (per `feedback_commit_between_phases`).
2. **Subpackage rename triggers a one-off cross-platform recompile of `kyo.internal.*`.** Mitigation: run JVM -> JS -> Native sequentially.
3. **`Config.require` may trip pre-existing tests with edge values.** Mitigation: grep all `Config(` constructions; correct test if it's a real bug, relax `require` if test documents an intentional edge case.
4. **`JsonRpcResponse` deletion (Phase 2) loses a Schema-derived type.** No external user imports `kyo.JsonRpcResponse` per `A3 §2.4`. Mitigation: final grep before deletion.
5. **`JsonRpcMethod.Context` rename does not collide with `kyo.Context`** (verified: kyo-core has no top-level `Context` type). Risk is hypothetical.
6. **UDS fold introduces Native/JS abort stubs.** Callers invoking `JsonRpcTransport.unixDomain(...)` on Native or JS will fail with `UnsupportedOperationException`. Mitigation: relocated `JsonRpcTransportUnixTest` catches and cancels on Native/JS, matching `HttpServerUnixTest`'s pattern.
7. **`feedback_no_type_aliases` constraint on `JsonRpcEndpoint.CancellationPolicy`.** The absorbed body exposes `type ParamsEncoder` and `type ParamsDecoder` (was at `CancellationPolicy.scala:20-21`). Inline the function types at each use site within the nested companion. The aliases were internal-to-the-companion already; the nesting move is independent of removing them but the same Phase 3 commit handles both.
8. **`JsonRpcEndpoint.MessageGate` test-extension surface.** D1 §1 cites 8 in-repo `new MessageGate:` subclass sites; every one becomes `new JsonRpcEndpoint.MessageGate:` in Phase 3. Mitigation: sed sweep covers anonymous-class sites identically.
9. **Companion-file size growth.** `JsonRpcEndpoint.scala` grows from ~110 lines (today) to ~400+ lines after absorbing 6 nested types + Config alignment. This is comparable to kyo-http's `HttpRoute.scala` at 554 lines (which nests `RequestDef`, `ResponseDef`, `Field`, `ContentType`, `Metadata`, `ErrorMapping`). Mitigation: file size is not a rule violation; nested ADTs with the same logical owner are acceptable per kyo-http precedent (A4 §3).
10. **`JsonRpcEnvelope.Id` Schema collision.** `JsonRpcId.scala:14-28` carries a hand-rolled Schema. After nesting, the Schema becomes `JsonRpcEnvelope.Id`'s. Verify Schema implicit lookup still resolves; the companion-of-companion pattern is standard Scala 3.

### Verification gates

Per `feedback_targeted_tests_only`:

- After Phase 1: `kyo-jsonrpc/Test/compile`.
- After Phase 2: `kyo-jsonrpc/test` filtered to `JsonRpcEnvelopeTest` and `JsonRpcResponseTest`-derived cases.
- After Phase 3: `kyo-jsonrpc/Test/compile` + `kyo-jsonrpc-http/Test/compile` + `kyo-browser/Test/compile` (the cross-module canary; all three must compile in the same commit). Run the 10 renamed test files plus `JsonRpcEndpointTest`, `JsonRpcMethodTest`, `JsonRpcTransportTest`, `JsonRpcEnvelopeTest`.
- After Phase 4: `JsonRpcEndpointTest` (fluent setter + default + require).
- After Phase 5: `JsonRpcEndpointTest.init` row check.
- After Phase 6: `JsonRpcTransportUnixTest` + `JsonRpcTransportTest` (new `unixDomain` factory).
- After Phase 7 (final cross-platform green): full suite, all platforms, sequential.

---

## 13. Non-goals

1. No kyo-browser behaviour changes.
2. No kyo-core changes.
3. No kyo-net extraction.
4. No real Native or JS UDS implementation in this campaign.
5. No `JsonRpcMethod` / `JsonRpcHandler` split. A4 §12 nice-to-have 13 deferred.
6. No `JsonRpcEndpoint.Unsafe` low-level API. A4 §12 nice-to-have 14 deferred.
7. No sealed `JsonRpcError` hierarchy. D3 §6 rejects this outright.
8. No `JsonRpcEnvelope` Schema derivation. A4 §10 conditional, not required.
9. No JS / Native source population beyond the UDS stub.
10. **No top-level proliferation.** Where C-plan recommended nest and D5 superseded with prefix-and-keep, D6 returns to nest (under the owning companion). D5's prefix-and-keep is superseded by user's 16-vs-7 challenge.
11. No test file consolidation beyond the renames in §10 (each test stays 1:1 with its nested-type semantics per `feedback_test_placement` and Rule 8c).

---

## 14. Approval checkpoints

Three blocking yes/no items before `/flow` execution:

1. **NEST 11 types under their owning companions** (6 under `JsonRpcEndpoint`: `IdStrategy`, `UnknownMethodPolicy`, `MessageGate`, `CancellationPolicy`, `ProgressPolicy`, `ExtrasEncoder`; 2 under `JsonRpcTransport`: `Framer`, `WireTransport`; 1 under `JsonRpcMethod`: `Context` (was `HandlerCtx`); 1 under `JsonRpcEnvelope`: `Id` (was `JsonRpcId`); plus the `JsonRpcResponse` merge into `JsonRpcEnvelope` counts as a 12th NEST when factored, but is bookkept under DELETE)? **Recommend YES** (per D1 §1-§6 keeping all 6 public-and-extensible while honouring user's 16-vs-7 challenge; per D2 §6 for `Framer`/`WireTransport` belonging next to their consumer; per A4 §3 for `Context` and `Id` naming).

2. **Delete `JsonRpcTransportJvm.scala` and fold `unixDomain` into `JsonRpcTransport`** with JVM real backend + JS/Native abort stubs? **Recommend YES** (per D4 §5).

3. **Merge `JsonRpcResponse.scala` into `JsonRpcEnvelope`** (delete the standalone file; `success`/`failure` factories move to `JsonRpcEnvelope` companion)? **Recommend YES** (per D5 §2 + D3-consistent, no D-doc overrides).

If all three are YES, proceed to `/flow` execution starting at Phase 1.
