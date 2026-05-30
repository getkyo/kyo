# A3 — Consumer Footprint Audit (kyo-jsonrpc public surface)

Empirical census of every public top-level type in `kyo-jsonrpc/shared/src/main/scala/kyo/*.scala`, cross-referenced against actual repo consumers. This is the input agent C uses to decide which types should move to `kyo.internal.*`.

## Scope & Method

**Public types enumerated** (17 total, from `grep -E '^(sealed |abstract |final )?(case )?(class|object|trait|enum|type) [A-Z]' kyo-jsonrpc/shared/src/main/scala/kyo/*.scala`):

`CancellationPolicy`, `ExtrasEncoder`, `Framer`, `HandlerCtx`, `IdStrategy`, `JsonRpcCodec`, `JsonRpcEndpoint`, `JsonRpcEnvelope`, `JsonRpcError`, `JsonRpcId`, `JsonRpcMethod`, `JsonRpcResponse`, `JsonRpcTransport`, `MessageGate`, `ProgressPolicy`, `UnknownMethodPolicy`, `WireTransport`.

**Consumers searched**: full repo, excluding `kyo-jsonrpc/`, `kyo-jsonrpc-http/`, `target/`, `.flow/`. Apparent hits in `kyo-http/shared/src/test/scala/demo/McpServer.scala` are a locally-defined `case class JsonRpcResponse` (see `McpServer.scala:25`) unrelated to kyo-jsonrpc; that file is not a consumer. Apparent hits in compiled JS bundles under `kyo-browser/js/target/` reflect source consumers already counted in `kyo-browser/shared/`.

**Consumer modules found**: `kyo-browser` (1 main, 5 test files) and `kyo-jsonrpc-http` (1 main, 1 test). No other module touches the kyo-jsonrpc surface.

## 1. Type-by-Type Consumer Table

Counts are distinct consumer source files (excluding kyo-jsonrpc itself). Classification key:

- **HEAVILY** = 3+ consumer files reference it
- **LIGHTLY** = 1-2 consumer files
- **UNUSED** = 0 consumer files (move-to-internal candidate)
- **HTTP-ONLY** = used only by the sibling `kyo-jsonrpc-http`

| Type | Consumer files | Sample file:line | Classification |
|---|---|---|---|
| `CancellationPolicy` | 0 | (none) | UNUSED |
| `ExtrasEncoder` | 3 (browser main + 2 tests) | `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala:41` | HEAVILY |
| `Framer` | 0 | (none) | UNUSED |
| `HandlerCtx` | 1 (browser main, doc-only) | `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala:607` | LIGHTLY |
| `IdStrategy` | 6 (browser main + 5 tests) | `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala:203` | HEAVILY |
| `JsonRpcCodec` | 5 browser + 1 http main + 0 = 6 | `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala:188` | HEAVILY |
| `JsonRpcEndpoint` | 5 (browser main + 4 tests) | `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala:18` | HEAVILY |
| `JsonRpcEnvelope` | 1 browser test + 2 http (1 main + 1 test) = 3 | `kyo-jsonrpc-http/src/main/scala/kyo/JsonRpcHttpTransport.scala:13` | HEAVILY |
| `JsonRpcError` | 5 browser + 1 http main = 6 | `kyo-browser/shared/src/test/scala/kyo/internal/CdpBackendTest.scala:215` | HEAVILY |
| `JsonRpcId` | 4 browser + 1 http test = 5 | `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala:598` | HEAVILY |
| `JsonRpcMethod` | 4 (all browser tests) + 1 browser test smoke = 4 | `kyo-browser/shared/src/test/scala/kyo/internal/CdpBackendTest.scala:46` | HEAVILY |
| `JsonRpcResponse` | 0 | (none; `McpServer.scala:25` is a local same-named class, not a consumer) | UNUSED |
| `JsonRpcTransport` | 5 browser + 1 http main = 6 | `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala:189` | HEAVILY |
| `MessageGate` | 0 | (none) | UNUSED |
| `ProgressPolicy` | 0 | (none) | UNUSED |
| `UnknownMethodPolicy` | 2 browser (1 main + 1 test) | `kyo-browser/shared/src/test/scala/kyo/internal/JsonRpcPortInvariantsSpec.scala:304` | LIGHTLY |
| `WireTransport` | 0 | (none) | UNUSED |

**Summary counts**: 9 HEAVILY, 2 LIGHTLY, 6 UNUSED, 0 HTTP-ONLY-exclusive. `JsonRpcEnvelope` is the only type whose external use is concentrated in `kyo-jsonrpc-http`; one browser test also references `JsonRpcEnvelope.Malformed`, so it is not HTTP-exclusive.

## 2. Move-to-Internal Candidates (UNUSED externally)

Six types have zero external consumers. For each I checked whether (a) it is still wired through some other public type as a parameter (so removing visibility would break public surface compilation), and (b) why it became public in the first place.

### 2.1 `CancellationPolicy`

- Zero consumer references outside kyo-jsonrpc.
- Surfaced via `JsonRpcEndpoint.Config.cancellation: Maybe[CancellationPolicy]` (`kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEndpoint.scala:91`).
- Public because `Config` exposes it as a field type. Internally consumed by `internal.CancellationEngine` (`CancellationEngine.scala:12`).
- **Rule 8a status**: violation. The type was introduced as a configuration knob but no consumer in this repo configures it. Could move to `kyo.internal.CancellationPolicy` and re-export from `Config` only if some consumer ever needs to set it; today, `Config.cancellation` always sits at `Absent`.
- **Recommendation**: move to `kyo.internal.CancellationPolicy`; keep `JsonRpcEndpoint.Config.cancellation` typed as `Maybe[internal.CancellationPolicy]` (or drop the field outright until a real consumer arrives).

### 2.2 `Framer`

- Zero consumer references.
- Public because `JsonRpcTransport.fromWire` and `JsonRpcTransport.stdio` accept a `framer: Framer` parameter with default `Framer.lineDelimited` (`JsonRpcTransport.scala:39, 48`), and `JsonRpcTransportJvm.unixDomain` does the same (`JsonRpcTransportJvm.scala:18, 39, 41`).
- No external caller has yet selected a non-default framer. The default `Framer.lineDelimited` is the only value referenced from public bindings.
- **Rule 8a status**: violation. Default-only knob whose user-visible variant is hardcoded.
- **Recommendation**: move to `kyo.internal.Framer`; the JsonRpcTransport factories can keep a `framer` parameter typed `internal.Framer` (or drop the parameter and inline `internal.Framer.lineDelimited`).

### 2.3 `HandlerCtx`

- Browser main file `CdpBackend.scala:607-608` mentions `HandlerCtx` in a doc comment only ("Reads sessionId from HandlerCtx.extras"). No code reference uses the type symbol.
- Surfaced into handler bodies via `JsonRpcMethod` apply-block context. Handlers reach `ctx.extras` through implicit/structural access, not by naming `HandlerCtx` directly.
- Classified as LIGHTLY above because one consumer file mentions the name; this is doc-only and would not block making the type internal so long as the structural `ctx.extras` access path remains.
- **Recommendation**: keep public for now (the documentation in CdpBackend.scala:607 is load-bearing for readers, and handler bodies receive a `HandlerCtx` value). Revisit if A4 renames it.

### 2.4 `JsonRpcResponse`

- Zero consumer references (the `JsonRpcResponse` token in `kyo-http/shared/src/test/scala/demo/McpServer.scala:25` is `case class JsonRpcResponse(jsonrpc: String, id: Option[Int], result: Option[RpcResult], error: Option[RpcError])` declared locally; that file has no `import kyo.JsonRpcResponse` and is not a consumer of this module).
- Internal use: factory methods `JsonRpcResponse.success` / `.failure` are referenced inside `kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcEndpointImpl.scala` (not externally).
- The constructor is already `private[kyo]` (`JsonRpcResponse.scala:12`), so end-users cannot construct it directly; the smart factories are the only API path.
- **Rule 8a status**: violation. The type is public but no external code names it. Codec/endpoint internals construct it; transport delivers it as part of `JsonRpcEnvelope.Response`, but `JsonRpcEnvelope.Response` already wraps the same payload, so the standalone `JsonRpcResponse` adds no consumer-visible surface.
- **Recommendation**: move to `kyo.internal.JsonRpcResponse`. `JsonRpcEnvelope.Response` is the externally observed wire shape.

### 2.5 `MessageGate`

- Zero consumer references.
- Public because `JsonRpcEndpoint.Config.gate: Maybe[MessageGate]` (`JsonRpcEndpoint.scala:94`). No external caller sets this.
- The `JsonRpcEnvelope.scala:1` comment notes the type is "exposed through JsonRpcTransport and MessageGate user implementations", but no user implementation exists in the repo.
- **Rule 8a status**: violation. Speculative extension hook with no consumer.
- **Recommendation**: move to `kyo.internal.MessageGate`; either drop `Config.gate` or re-type it `Maybe[internal.MessageGate]`.

### 2.6 `ProgressPolicy`

- Zero consumer references.
- Public because `JsonRpcEndpoint.Config.progress: Maybe[ProgressPolicy]` (`JsonRpcEndpoint.scala:92`). Error messages in `internal/JsonRpcEndpointImpl.scala:374, 454` advise callers to "pass `Config.progress = Present(ProgressPolicy.lsp / .mcp)`", but no caller in this repo does.
- **Rule 8a status**: violation, same shape as `MessageGate`.
- **Recommendation**: move to `kyo.internal.ProgressPolicy`; re-type the `Config.progress` field or remove the field until a real consumer (MCP/LSP server) lands.

### 2.7 `WireTransport`

- Zero consumer references.
- Public because `JsonRpcTransport.fromWire(wire: WireTransport, framer: Framer, codec: JsonRpcCodec)` accepts it (`JsonRpcTransport.scala:38`).
- The only `WireTransport` implementations are `internal.StdioWireTransport`, `internal.UdsWireTransport`, and adapter glue. No external code instantiates or extends `WireTransport`.
- **Rule 8a status**: violation. Extension point with no external implementer.
- **Recommendation**: move to `kyo.internal.WireTransport`. Either inline `fromWire` into the stdio/uds factories, or keep `fromWire` taking `internal.WireTransport` and accept that it becomes an internal-only constructor.

## 3. Lightly-Used Types

### 3.1 `HandlerCtx` (1 doc-only mention)

`kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala:607` mentions `HandlerCtx` in a documentation comment for the per-session router. The handler bodies themselves receive an implicit/structural `ctx` and read `ctx.extras`; they do not name `HandlerCtx` as a type. Removing `HandlerCtx` from public surface would not break compilation, but the doc comment would dangle.

**Risk if moved**: low. The doc comment can be reworded to refer to `internal.HandlerCtx` or to the structural shape.

### 3.2 `UnknownMethodPolicy` (2 files)

- `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala:199, 460` uses `UnknownMethodPolicy.minimal` as the default policy in two `JsonRpcEndpoint.Config` constructions.
- `kyo-browser/shared/src/test/scala/kyo/internal/JsonRpcPortInvariantsSpec.scala:304-306` constructs a custom `UnknownMethodPolicy(onUnknownRequest = UnknownMethodPolicy.UnknownAction.Drop, onUnknownNotification = UnknownMethodPolicy.UnknownAction.Drop)` to verify INV-013.

The kyo-browser main code only references the `.minimal` preset, but the test constructs a bespoke policy. Removing the public constructor would force the test to drop the bespoke-policy assertion, weakening invariant coverage.

**Risk if moved**: medium. `.minimal` could be exposed as a method on `JsonRpcEndpoint.Config` directly, but the test's custom policy construction is consumer-relevant.

**Recommendation**: keep `UnknownMethodPolicy` public. It is a real configuration surface with a real consumer test exercising the non-default path.

## 4. kyo-jsonrpc-http Exclusive Usage

No type in the public surface is used *only* by `kyo-jsonrpc-http`. Every type that `kyo-jsonrpc-http` references is also referenced by `kyo-browser`:

| Type | kyo-jsonrpc-http use | Also used by kyo-browser? |
|---|---|---|
| `JsonRpcCodec` | `JsonRpcHttpTransport.scala:9, 101, 105` | yes (`CdpBackend.scala:196`) |
| `JsonRpcEnvelope` | `JsonRpcHttpTransport.scala:13, 22, 35, 68, 77`, test 87, 92, 154 | yes (`CdpClientDecoderTest.scala:100`) |
| `JsonRpcError` | `JsonRpcHttpTransport.scala:23, 62` | yes (`CdpClientDecoderTest.scala:65`) |
| `JsonRpcId` | `JsonRpcHttpTransportTest.scala:87, 92` | yes (`CdpBackend.scala:598`) |
| `JsonRpcTransport` | `JsonRpcHttpTransport.scala:1, 10, 21, 96, 97, 99, 103, 105` | yes (`CdpBackend.scala:189`) |

`JsonRpcEnvelope` is the closest call: kyo-jsonrpc-http uses all three branches (Request, Response, Malformed) on both encode and decode paths, while kyo-browser only references `JsonRpcEnvelope.Malformed` in a single decoder test (`CdpClientDecoderTest.scala:100, 166, 202, 242, 279`). It remains genuinely shared.

**Conclusion**: no candidates to move into `kyo-jsonrpc-http`'s own internal package on the basis of "sibling-exclusive use". The five shared types form the public surface that both real consumers depend on.

## 5. kyo-browser's Actual Usage (Canonical Consumer Set)

Every kyo-jsonrpc type that kyo-browser names, with file:line citations. This is the "what does a real consumer need?" reference.

### Main (`kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala`)

- `ExtrasEncoder` — `:41, 44, 590, 593` (per-session encoder via `ExtrasEncoder.const` / `.empty`)
- `IdStrategy.SequentialInt` — `:203, 464, 576`
- `JsonRpcCodec.Cdp` — `:188, 196, 457`
- `JsonRpcEndpoint` — `:7, 13, 18, 206, 467` (the endpoint instance held by `CdpBackend`)
- `JsonRpcEndpoint.Config` — `:195, 456`
- `JsonRpcEndpoint.init` — `:206, 467`
- `JsonRpcId.Num` — `:598` (negative-id dialog drainer)
- `JsonRpcTransport` — `:189, 436, 440` (parameter type for `initUnscoped`)
- `UnknownMethodPolicy.minimal` — `:199, 460`
- `HandlerCtx` — `:607` (doc comment only)

### Test (`kyo-browser/shared/src/test/scala/kyo/internal/*`)

- `CdpBackendTest.scala`: `JsonRpcCodec.Cdp:50`, `JsonRpcEndpoint:45,49,54,62`, `JsonRpcMethod[...]:46,77,103,122,137,156,171,190,212,233,256,275,298,317,332`, `JsonRpcError:44,46,...,215` (`JsonRpcError.methodNotFound`), `JsonRpcTransport.inMemory:43,63`, `IdStrategy.SequentialInt:52`.
- `CdpBackendSmokeTest.scala`: `ExtrasEncoder.const:152,186,211,251`, `ExtrasEncoder.empty` (implicit via the same call sites), `JsonRpcId.Num:177,289,295`, `JsonRpcCodec.Cdp:41`, `JsonRpcEndpoint:36,45,49,53`, `JsonRpcMethod[...]:35,37,52,90,115,172,291`, `JsonRpcError:35,37,52,90,115,172,291`, `JsonRpcTransport.inMemory:54,76`, `IdStrategy.SequentialInt:43`.
- `CdpBackendLifecycleTest.scala`: `JsonRpcMethod:1172`, `JsonRpcError:204,1172`, `JsonRpcEndpoint.Config:1175`, `JsonRpcEndpoint.init:1180`, `JsonRpcCodec.Cdp:1176`, `IdStrategy.SequentialInt:1178`, `JsonRpcTransport.inMemory:1171`.
- `CdpClientDecoderTest.scala`: `JsonRpcCodec.Cdp:43`, `JsonRpcEnvelope.Malformed/Notification:100,166,202,242,279`, `JsonRpcEndpoint:14,15,42,47`, `JsonRpcError:12,36,39,62,65,130,133,270`, `JsonRpcId.Num:101,167`, `JsonRpcMethod:36,39,62,130,270`, `JsonRpcTransport:9,37,38`, `UnknownMethodPolicy.minimal:267` (doc).
- `JsonRpcPortInvariantsSpec.scala`: `ExtrasEncoder.const:212`, `JsonRpcCodec.Cdp:54,318`, `JsonRpcEndpoint:46,50,53,64,314,317`, `JsonRpcEndpoint.Config:53,317`, `JsonRpcId.Num:351,358`, `JsonRpcMethod` (transitive), `JsonRpcTransport.inMemory:65,242,310`, `IdStrategy.SequentialInt:56,321`, `UnknownMethodPolicy:304-306`.

**Canonical set (what kyo-browser actually requires from the public surface)**: `ExtrasEncoder`, `IdStrategy`, `JsonRpcCodec`, `JsonRpcEndpoint` (+ `.Config`, `.init`), `JsonRpcEnvelope` (Malformed, Notification), `JsonRpcError` (+ `.methodNotFound`, `.invalidRequest`), `JsonRpcId` (`.Num`), `JsonRpcMethod`, `JsonRpcTransport` (+ `.inMemory`), `UnknownMethodPolicy` (+ `.minimal`, `.UnknownAction`). That is 10 types.

`HandlerCtx` is referenced only in a doc comment and is structurally used (handler bodies access `ctx.extras` via the implicit context). The remaining 6 public types (`CancellationPolicy`, `Framer`, `JsonRpcResponse`, `MessageGate`, `ProgressPolicy`, `WireTransport`) are not referenced anywhere by either consumer.

## 6. Recommendation Summary

Of 17 current public top-level types:

| Move to `kyo.internal.*` (6) | Keep public (11) |
|---|---|
| `CancellationPolicy` (UNUSED, Rule 8a default-only knob) | `ExtrasEncoder` (HEAVY, 3 files) |
| `Framer` (UNUSED, default-only knob) | `IdStrategy` (HEAVY, 6 files) |
| `JsonRpcResponse` (UNUSED; `JsonRpcEnvelope.Response` is the observed wire shape) | `JsonRpcCodec` (HEAVY, 6 files incl. http) |
| `MessageGate` (UNUSED, speculative extension hook) | `JsonRpcEndpoint` (HEAVY, 5 files) |
| `ProgressPolicy` (UNUSED, advertised in error messages only) | `JsonRpcEnvelope` (HEAVY, 3 files inc. http) |
| `WireTransport` (UNUSED, no external implementer) | `JsonRpcError` (HEAVY, 6 files) |
| | `JsonRpcId` (HEAVY, 5 files) |
| | `JsonRpcMethod` (HEAVY, 4 files) |
| | `JsonRpcTransport` (HEAVY, 6 files) |
| | `UnknownMethodPolicy` (LIGHT-but-load-bearing, 2 files, real custom-policy usage at `JsonRpcPortInvariantsSpec.scala:304`) |
| | `HandlerCtx` (LIGHT, doc-only mention but structurally received by every handler body) |

### Per-move justification (citation-backed criterion)

- **`CancellationPolicy` → internal**: zero consumer references; sole binding is `JsonRpcEndpoint.Config.cancellation: Maybe[CancellationPolicy]` at `JsonRpcEndpoint.scala:91`, always defaulted to `Absent`. Rule 8a: speculative configuration surface.
- **`Framer` → internal**: zero consumer references; bindings at `JsonRpcTransport.scala:39, 48` and `JsonRpcTransportJvm.scala:18, 39, 41` only carry the default `Framer.lineDelimited`. Rule 8a: default-only knob.
- **`JsonRpcResponse` → internal**: zero consumer references; constructor already `private[kyo]` at `JsonRpcResponse.scala:12`. The externally observed wire shape is `JsonRpcEnvelope.Response`. Rule 8a: redundant public wrapper.
- **`MessageGate` → internal**: zero consumer references; sole binding is `JsonRpcEndpoint.Config.gate: Maybe[MessageGate]` at `JsonRpcEndpoint.scala:94`, never set. Rule 8a: speculative extension hook.
- **`ProgressPolicy` → internal**: zero consumer references; sole binding is `JsonRpcEndpoint.Config.progress: Maybe[ProgressPolicy]` at `JsonRpcEndpoint.scala:92`, never set; error messages at `internal/JsonRpcEndpointImpl.scala:374, 454` advertise it but no consumer takes the bait. Rule 8a: speculative extension hook.
- **`WireTransport` → internal**: zero consumer references; sole binding is `JsonRpcTransport.fromWire` at `JsonRpcTransport.scala:38`. All implementations live under `kyo.internal`. Rule 8a: extension point with no external implementer.

### Per-keep justification

- **`ExtrasEncoder`**: 4 distinct call sites in kyo-browser main + smoke test (`CdpBackend.scala:41, 44, 590, 593`; `CdpBackendSmokeTest.scala:152, 186, 211, 251`; `JsonRpcPortInvariantsSpec.scala:212`). Per-session sessionId routing depends on it.
- **`IdStrategy`**: 6 files reference `IdStrategy.SequentialInt`. Real configuration choice.
- **`JsonRpcCodec`**: 6 files reference `JsonRpcCodec.Cdp` (and `JsonRpcCodec.Strict2_0` default in `JsonRpcHttpTransport.scala:9`). Real codec selection.
- **`JsonRpcEndpoint`** (+ `.Config`, `.init`): the public construction API. 5 consumer files.
- **`JsonRpcEnvelope`**: 3 consumer files; both the http transport encode/decode (`JsonRpcHttpTransport.scala:13, 22, 35`) and the browser decoder test (`CdpClientDecoderTest.scala:100, 166, 202, 242, 279`) name its variants.
- **`JsonRpcError`**: 6 files; both as a `Abort[JsonRpcError]` effect tag in handler signatures (`CdpBackendTest.scala:46` and many others) and as a constructed value (`JsonRpcError.methodNotFound` at `CdpBackendTest.scala:215`, `JsonRpcError(-32602, ...)` at `CdpClientDecoderTest.scala:65`).
- **`JsonRpcId`**: 5 files; `JsonRpcId.Num` matched in dialog drainer logic and tests.
- **`JsonRpcMethod`**: 4 files; the parametric handler-constructor API.
- **`JsonRpcTransport`**: 6 files; both `JsonRpcTransport.inMemory` (tests) and direct parameter typing in `initUnscoped(transport: JsonRpcTransport, ...)` at `CdpBackend.scala:440`.
- **`UnknownMethodPolicy`**: 2 files but load-bearing. The custom-policy construction at `JsonRpcPortInvariantsSpec.scala:304-306` exercises a non-default path; moving the type would force dropping or weakening INV-013 coverage.
- **`HandlerCtx`**: doc-only consumer mention (`CdpBackend.scala:607`) but every registered handler body receives a `HandlerCtx` value implicitly; the type name appears on no consumer LHS, yet removing it would dangle a doc comment that ties session-routing behavior to `HandlerCtx.extras`. Cheap to keep; A4 may rename or tighten.

### Net effect

- Public surface: 17 -> 11 types (-6, a 35% reduction).
- All six moves are zero-cost to current consumers: no consumer file references any of the six moved types.
- Three of the moves (`CancellationPolicy`, `MessageGate`, `ProgressPolicy`) also unlock simplification of `JsonRpcEndpoint.Config`, whose `cancellation`, `gate`, and `progress` fields can either be re-typed against `internal.*` or dropped until a real consumer arrives. That secondary cleanup is out of A3 scope and handled by agent C.

End of A3 report.
