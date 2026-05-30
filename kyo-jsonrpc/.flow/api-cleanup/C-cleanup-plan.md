# C. Cleanup Plan: kyo-jsonrpc + kyo-jsonrpc-http public-API alignment with kyo-http

Source-grounded, decision-grade plan. Inputs reconciled: A1 (`A1-kyo-http-template.md`), A2 (`A2-kyo-jsonrpc-current-state.md`), A3 (`A3-consumer-footprint.md`), A4 (`A4-naming-and-nesting.md`). Every claim cites file:line; every reconciliation between conflicting A3/A4 verdicts has an explicit winner with rationale.

---

## 1. Executive summary

Current shape: 17 public top-level types in `kyo-jsonrpc/shared/src/main/scala/kyo/` (`A2 §1`), 1 in `kyo-jsonrpc/jvm/...` (`JsonRpcTransportJvm`), 1 in `kyo-jsonrpc-http/...` (`JsonRpcHttpTransport`), 12 internal types in a single flat `kyo.internal` directory (`A2 §2`, `A2 §13`), plus 1 jvm internal (`UdsWireTransport`). Nine public types skip the `JsonRpc*` prefix (`Framer`, `WireTransport`, `HandlerCtx`, `MessageGate`, `ExtrasEncoder`, `IdStrategy`, `UnknownMethodPolicy`, `CancellationPolicy`, `ProgressPolicy`) (`A4 §1`). `JsonRpcEndpoint.Config` has no fluent setters, no `default` constant, no `require` validation, no `derives CanEqual` (`A4 §6`, `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEndpoint.scala:89`). `JsonRpcResponse.scala` duplicates the wire shape already modelled by `JsonRpcEnvelope.Response` (`kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcResponse.scala:12` vs `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEnvelope.scala:19`). `JsonRpcEndpoint.init` adds redundant `Sync` to the row (`kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEndpoint.scala:105`).

Target shape: 11 public top-level types in shared (`A3 §6` net), every one prefixed, 4 nested under `JsonRpcEndpoint`, 1 nested under `JsonRpcMethod`, internals partitioned into `kyo.internal.{codec, transport, framing, engine}`, `JsonRpcEndpoint.Config` with per-field fluent setters + `default` + `require` + `derives CanEqual`, `JsonRpcResponse` deleted (merged into `JsonRpcEnvelope.Response`), `Sync` dropped from `JsonRpcEndpoint.init`.

Net delta: 6 type relocations to companions or internal, 1 type deletion (merge), 2 prefix renames (`Framer`, `WireTransport`), 1 internal sub-package reorg (12 files), 0 file splits (Rule 8b clean at public surface; the engine helper bundle in `JsonRpcEndpointImpl.scala` stays grouped per A2's deferral note), ~5 new fluent setters on `Config`, 1 effect-row drop. Open forks: 3 (resolved in §11 with recommended defaults). Recommended phasing: 8 phases (§12).

---

## 2. The 15 kyo-http template rules applied to kyo-jsonrpc

| Rule | kyo-http source | Current kyo-jsonrpc | Target | Action |
|---|---|---|---|---|
| 1. Prefix every public type | All 27 `Http*` types (`A1 §2`) | 9 unprefixed (`A4 §1`) | All public top-level prefixed `JsonRpc*` | Rename `Framer` → `JsonRpcFramer`, `WireTransport` → `JsonRpcWireTransport`; nest `HandlerCtx`/`MessageGate`/`ExtrasEncoder`/`IdStrategy`/`UnknownMethodPolicy` into `JsonRpcEndpoint` companion; move `CancellationPolicy`/`ProgressPolicy` to internal (see §3) |
| 2. Opaque-typed entry-point per transport | `opaque type HttpServer = HttpServer.Unsafe` (`HttpServer.scala:37`) | `final class JsonRpcEndpoint private[kyo] (...)` (`JsonRpcEndpoint.scala:7`) | KEEP — JsonRpcEndpoint is a bidirectional handle (call + serve), not a transport. Opaque-type pattern not warranted. The smart-ctor pattern matches kyo-http `HttpRawConnection` (`HttpRawConnection.scala:11`) | No action |
| 3. Sealed error root + categories + leaves | `HttpException` (`HttpException.scala:28-347`) | Flat `case class JsonRpcError(code, message, data)` (`JsonRpcError.scala:11`) | KEEP FLAT — wire-faithful to JSON-RPC 2.0 (`A4 §7`). Document the open-extension rationale in scaladoc | Add scaladoc rationale; no structural change. Standard-code constants and helpers already present (`JsonRpcError.scala:14-40`) — see §8 |
| 4. `Maybe`/`Result`/`Chunk`/`Span` only | All kyo-http public types use `Maybe` (`A1 §4`) | `Maybe` universal (`A2 §6`) | Already aligned | No action |
| 5. Effect rows at every use site, no aliases | Standard rows on every method (`A1 §4`) | Aligned except `JsonRpcEndpoint.init` carries redundant `Sync` (`JsonRpcEndpoint.scala:105`) | Drop `Sync` from `init` | Phase 7 |
| 6. Config = case class + per-field fluent + `default` | `HttpServerConfig.scala:52-92` | `JsonRpcEndpoint.Config` has Scala defaults only, no fluent, no `default`, no validation, no `CanEqual` (`JsonRpcEndpoint.scala:89-99`) | Add 9 per-field fluent setters, `Config.default`, `require(maxInFlight.forall(_ > 0))`, `derives CanEqual` | Phase 6 |
| 7. Sub-records nest in owning config's companion | `HttpServerConfig.Cors` (`HttpServerConfig.scala:117`) | `Config` fields reference top-level types | Nest `IdStrategy`, `UnknownMethodPolicy`, `MessageGate`, `ExtrasEncoder` into `JsonRpcEndpoint` | Phase 3 |
| 8. Impl classes in `kyo.internal.<sub>` | 7 subpackages (`A1 §6`) | 1 flat `kyo.internal` directory (12 files) (`A2 §13`) | Mirror into `kyo.internal.{codec, transport, framing, engine}` | Phase 1 (see §5) |
| 9. Framework-only ctors use `final class … private[kyo] (...)` | `HttpRawConnection.scala:11` | Already used on `JsonRpcEndpoint` (`JsonRpcEndpoint.scala:7`), `JsonRpcEndpoint.Pending` (`JsonRpcEndpoint.scala:82`), `HandlerCtx` (`HandlerCtx.scala:14`) | Aligned | No action |
| 10. `derives CanEqual` on public value types; `Schema, CanEqual` on wire records | Near-universal (`A1 §11`) | Aligned except `JsonRpcEndpoint.Config` missing `derives CanEqual` (`JsonRpcEndpoint.scala:89`) | Add `derives CanEqual` to `Config` | Phase 6 |
| 11. One file per top-level public type | 28 files / 28 types in shared (`A1 appendix`) | 17 files / 17 types in shared — already 1:1 (`A2 §5 8b`) | Aligned at public surface; engine file `JsonRpcEndpointImpl.scala` carries 4 internal types (deferred per A2 audit trail) | No public-surface action; internal split deferred |
| 12. Lowercase namespace objects | `HttpFilter.server` (`HttpFilter.scala:108`), `.client` (`:324`) | No lowercase namespace objects in kyo-jsonrpc (`A2 §8`) | Not warranted yet — no families of related defs need namespacing | No action |
| 13. Cross-platform: shared + `<platform>/HttpPlatformTransport.scala` | `A1 §9` | `shared` carries everything; jvm has 1 public + 1 internal; js/native source dirs empty (`A2 §11`) | Decision: keep js/native empty source trees (kyo-jsonrpc-http already cross-builds for WebSocket via kyo-http platform layer); consider folding `JsonRpcTransportJvm.unixDomain` into a multi-platform `JsonRpcTransport.unixDomain` (see §10) | Phase 8 (decision); no impl change in this campaign |
| 14. Scaladoc on every public type | Every kyo-http file (`A1 §13`) | Top-of-file `// PUBLIC X consumed by Y` comments (`A2 §8`); some types have scaladoc, some don't | Add scaladoc block per type during touch | Inline with each rename/move phase |
| 15. No `// PUBLIC` banners | Confirmed absent (`A1 §12`) | Every kyo-jsonrpc public file has a `// PUBLIC ...` header line 1 (e.g. `JsonRpcEndpoint.scala:1`, `JsonRpcError.scala:1`, `JsonRpcEnvelope.scala:1`, `JsonRpcResponse.scala:1`, `JsonRpcTransport.scala:1`, `JsonRpcHttpTransport.scala:1`) | Strip every `// PUBLIC ...` line; replace with scaladoc | Phase 1 (mechanical sweep) |

---

## 3. Per-type DECISION table

One row per public type. "Final decision" reconciles A3 (consumer-footprint verdict) with A4 (naming/nesting suggestion). Where they conflict, the deciding criterion is: **does any caller in the repo construct a value of this type, or could a future external caller plausibly need to?** If yes → NEST or PREFIX (keep public). If no, and the type is purely a `Config` field reference → MOVE-TO-INTERNAL.

| Current name (file:line) | A3 verdict | A4 suggestion | Final decision | Target name & path | Rationale |
|---|---|---|---|---|---|
| `JsonRpcEndpoint` (`JsonRpcEndpoint.scala:7`) | HEAVILY (5 files) | keep | KEEP-PUBLIC-AS-IS | `kyo.JsonRpcEndpoint` — `shared/.../JsonRpcEndpoint.scala` | Primary handle, 5 consumer files (`A3 §5`) |
| `JsonRpcEnvelope` (`JsonRpcEnvelope.scala:7`) | HEAVILY (3 files incl. http) | keep, absorb `JsonRpcResponse` | KEEP-PUBLIC + MERGE-IN `JsonRpcResponse` | `kyo.JsonRpcEnvelope` — `shared/.../JsonRpcEnvelope.scala` | Already the externally observed wire shape (`A3 §2.4`). `JsonRpcEnvelope.Response` (`JsonRpcEnvelope.scala:19`) is identical to `JsonRpcResponse` (`JsonRpcResponse.scala:12`) except for the optional `id` (Envelope.Response requires id, JsonRpcResponse permits `Maybe[JsonRpcId]` for parse-error case — preserve the optional-id branch via a `Maybe[JsonRpcId]` field on `Response` or via `Malformed`) |
| `JsonRpcResponse` (`JsonRpcResponse.scala:12`) | UNUSED | merge into `JsonRpcEnvelope.Response` | DELETE (merge) | gone | A4 §3 wins outright over A3's move-to-internal: zero consumer ref AND already a structural duplicate of `JsonRpcEnvelope.Response`. Lift `success`/`failure` factories onto `JsonRpcEnvelope.Response`'s companion |
| `JsonRpcError` (`JsonRpcError.scala:11`) | HEAVILY (6 files) | keep flat | KEEP-PUBLIC-AS-IS | `kyo.JsonRpcError` — `shared/.../JsonRpcError.scala` | Wire-faithful (§8); already has 11 named constants + 5 helpers (`JsonRpcError.scala:14-40`) |
| `JsonRpcId` (`JsonRpcId.scala:9`) | HEAVILY (5 files) | keep | KEEP-PUBLIC-AS-IS | `kyo.JsonRpcId` — `shared/.../JsonRpcId.scala` | Two-case enum with custom `Schema` for num-or-string union (`JsonRpcId.scala:14`) |
| `JsonRpcMethod` (`JsonRpcMethod.scala:14`) | HEAVILY (4 files) | keep | KEEP-PUBLIC-AS-IS | `kyo.JsonRpcMethod` — `shared/.../JsonRpcMethod.scala` | Sealed trait with private impls; the `JsonRpcMethod` vs `JsonRpcHandler` split (`A4 §12 nice-to-have 13`) is deferred — `JsonRpcMethod.apply` doing both jobs is acceptable for now |
| `JsonRpcCodec` (`JsonRpcCodec.scala:9`) | HEAVILY (6 files) | keep | KEEP-PUBLIC-AS-IS | `kyo.JsonRpcCodec` — `shared/.../JsonRpcCodec.scala` | Codec extension point, mirrors `HttpCodec` (`A1 §1`) |
| `JsonRpcTransport` (`JsonRpcTransport.scala:6`) | HEAVILY (6 files) | keep | KEEP-PUBLIC-AS-IS | `kyo.JsonRpcTransport` — `shared/.../JsonRpcTransport.scala` | Primary user-implementable seam |
| `JsonRpcTransportJvm` (`JsonRpcTransportJvm.scala:10`) | n/a (jvm only) | rename / fold | KEEP-PUBLIC-AS-IS (rename deferred — open fork) | `kyo.JsonRpcTransportJvm` — `jvm/.../JsonRpcTransportJvm.scala` | The `unixDomain` extension is JVM-specific (uses `java.net.UnixDomainSocketAddress`, `JsonRpcTransportJvm.scala:5`); folding into a multi-platform `JsonRpcTransport.unixDomain` requires JS/Native shims that abort on unsupported. Defer to a separate task (§10, §11) |
| `JsonRpcHttpTransport` (`kyo-jsonrpc-http/.../JsonRpcHttpTransport.scala:4`) | n/a (sibling module) | keep | KEEP-PUBLIC-AS-IS | `kyo.JsonRpcHttpTransport` — `kyo-jsonrpc-http/src/main/scala/kyo/JsonRpcHttpTransport.scala` | Module-extension idiom; matches the kyo-jsonrpc-http separation |
| `HandlerCtx` (`HandlerCtx.scala:14`) | LIGHTLY (1 doc) | nest as `JsonRpcMethod.Context` | NEST-IN-COMPANION | `kyo.JsonRpcMethod.Context` — `shared/.../JsonRpcMethod.scala` | Handler bodies receive a `HandlerCtx` value (structural use across all consumer tests; `A3 §3.1`). It IS a caller-touched type (parameter on handler lambda) but exists only as a `JsonRpcMethod` adjunct. A4 wins: nest. Rename to `Context` since the prefix becomes `JsonRpcMethod.` |
| `MessageGate` (`MessageGate.scala:4`) | UNUSED | nest as `JsonRpcEndpoint.Gate` | MOVE-TO-INTERNAL | `kyo.internal.engine.MessageGate` — `shared/.../internal/engine/MessageGate.scala` | A3 wins over A4: zero consumer references AND no caller has implemented this trait anywhere in the repo (`A3 §2.5`). Type is a `Config` field (`JsonRpcEndpoint.scala:94`); re-type to `Maybe[internal.engine.MessageGate]`. Per `feedback_export_only_when_warranted`, hidden until a real extender arrives |
| `ExtrasEncoder` (`ExtrasEncoder.scala:4`) | HEAVILY (3 files; `kyo-browser/.../CdpBackend.scala:41` ff) | nest as `JsonRpcEndpoint.Extras` | NEST-IN-COMPANION | `kyo.JsonRpcEndpoint.Extras` — `shared/.../JsonRpcEndpoint.scala` | A3 says keep public, A4 says nest. RECONCILE: it IS heavily caller-touched (every kyo-browser handler that needs per-session routing uses `ExtrasEncoder.const`). But its only meaningful binding is as the `extras` parameter on `JsonRpcEndpoint.call`/`notify`/`sendUnmatched`. Nesting under `JsonRpcEndpoint` preserves the public surface but cleans up `kyo.*` namespace. Rename to `Extras` since the prefix becomes `JsonRpcEndpoint.` |
| `IdStrategy` (`IdStrategy.scala:4`) | HEAVILY (6 files) | nest as `JsonRpcEndpoint.IdStrategy` | NEST-IN-COMPANION | `kyo.JsonRpcEndpoint.IdStrategy` — `shared/.../JsonRpcEndpoint.scala` | A3 says heavy-use, A4 says nest. RECONCILE: kyo-browser's 6 references are all `IdStrategy.SequentialInt` (`A3 §5`), which is purely a `Config.idStrategy` selector. Nest cleanly: `JsonRpcEndpoint.IdStrategy.SequentialInt`. The `kyo.IdStrategy` short alias is not load-bearing |
| `UnknownMethodPolicy` (`UnknownMethodPolicy.scala:5`) | LIGHTLY (2 files, load-bearing) | nest as `JsonRpcEndpoint.UnknownMethodPolicy` | NEST-IN-COMPANION | `kyo.JsonRpcEndpoint.UnknownMethodPolicy` — `shared/.../JsonRpcEndpoint.scala` | A3 §3.2 explicitly recommends KEEP-PUBLIC because the invariants spec at `kyo-browser/.../JsonRpcPortInvariantsSpec.scala:304-306` constructs a custom policy. A4 recommends nest. RECONCILE: nest. `JsonRpcEndpoint.UnknownMethodPolicy(...)` is still callable from the test; nesting only changes the import path |
| `CancellationPolicy` (`CancellationPolicy.scala:10`) | UNUSED | nest or prefix | MOVE-TO-INTERNAL | `kyo.internal.engine.CancellationPolicy` — `shared/.../internal/engine/CancellationPolicy.scala` | A3 wins: zero consumer references, `Config.cancellation` always `Absent` in repo (`A3 §2.1`). Per `feedback_export_only_when_warranted`, hide until a real LSP/MCP server demands it. Re-type `Config.cancellation` to `Maybe[internal.engine.CancellationPolicy]` |
| `ProgressPolicy` (`ProgressPolicy.scala:10`) | UNUSED | nest or prefix | MOVE-TO-INTERNAL | `kyo.internal.engine.ProgressPolicy` — `shared/.../internal/engine/ProgressPolicy.scala` | Same shape as `CancellationPolicy` (`A3 §2.6`). Re-type `Config.progress` to `Maybe[internal.engine.ProgressPolicy]` |
| `Framer` (`Framer.scala:7`) | UNUSED | prefix or relocate to kyo-net | RENAME-WITH-PREFIX | `kyo.JsonRpcFramer` — `shared/.../JsonRpcFramer.scala` | A3 says move-to-internal (zero refs); A4 says prefix or defer to kyo-net extraction. RECONCILE: keep public, prefix. `Framer.lineDelimited`/`.contentLength` (`Framer.scala:17, :28`) are real LSP/CDP framings any binary-protocol caller may select. The unused-today verdict masks "intended-extension-point". Per `feedback_export_only_when_warranted` the prefix-and-keep-public choice is defensible because callers MAY plausibly select a non-default framer (e.g., LSP requires `Framer.contentLength`). kyo-net extraction is hypothetical (`A4 §12 medium 8`); honour the current module boundary. Prefix now |
| `WireTransport` (`WireTransport.scala:6`) | UNUSED | prefix or relocate | RENAME-WITH-PREFIX | `kyo.JsonRpcWireTransport` — `shared/.../JsonRpcWireTransport.scala` | Same reasoning: a real byte-level extension point with public `JsonRpcTransport.fromWire(wire: WireTransport, ...)` signature (`JsonRpcTransport.scala:38`). Prefix now |

Counts: 7 KEEP-PUBLIC-AS-IS, 4 NEST-IN-COMPANION (`HandlerCtx`, `ExtrasEncoder`, `IdStrategy`, `UnknownMethodPolicy`), 3 MOVE-TO-INTERNAL (`MessageGate`, `CancellationPolicy`, `ProgressPolicy`), 2 RENAME-WITH-PREFIX (`Framer`, `WireTransport`), 1 DELETE-via-MERGE (`JsonRpcResponse`). Final public top-level count: 17 - 4 (nested) - 3 (moved) - 1 (deleted) + 0 = 9 top-level types, with `JsonRpcEndpoint`'s companion gaining 4 nested members and `JsonRpcMethod`'s companion gaining `Context`.

---

## 4. Per-file REORGANIZATION table

Source files in `kyo-jsonrpc/shared/src/main/scala/kyo/`:

| Source file | Target file | Action | LoC delta (approx) |
|---|---|---|---|
| `CancellationPolicy.scala` (79 lines) | `shared/.../kyo/internal/engine/CancellationPolicy.scala` | MOVE | 0 (same body, new package, drop `// PUBLIC` banner) |
| `ExtrasEncoder.scala` (17 lines) | inline into `JsonRpcEndpoint.scala` as `JsonRpcEndpoint.Extras` | MERGE | -17 in source / +14 in JsonRpcEndpoint.scala |
| `Framer.scala` (37 lines) | `shared/.../kyo/JsonRpcFramer.scala` | RENAME | 0 (rename + sed `Framer` → `JsonRpcFramer`) |
| `HandlerCtx.scala` (~30 lines) | inline into `JsonRpcMethod.scala` as `JsonRpcMethod.Context` | MERGE | -30 in source / +28 in JsonRpcMethod.scala |
| `IdStrategy.scala` (~8 lines) | inline into `JsonRpcEndpoint.scala` as `JsonRpcEndpoint.IdStrategy` | MERGE | -8 / +8 |
| `JsonRpcCodec.scala` (17 lines) | KEEP | KEEP | 0 (drop banner) |
| `JsonRpcEndpoint.scala` (108 lines) | KEEP, grows by ~70 lines from nested members + fluent setters | KEEP | +70 |
| `JsonRpcEnvelope.scala` (26 lines) | KEEP, absorbs `JsonRpcResponse.success`/`failure` factories on `JsonRpcEnvelope.Response` companion | KEEP | +20 (factories + scaladoc) |
| `JsonRpcError.scala` (41 lines) | KEEP | KEEP | 0 (drop banner) |
| `JsonRpcId.scala` (29 lines) | KEEP | KEEP | 0 (drop banner) |
| `JsonRpcMethod.scala` (~135 lines) | KEEP, gains `JsonRpcMethod.Context` nested type | KEEP | +28 |
| `JsonRpcResponse.scala` (24 lines) | DELETE | DELETE | -24 |
| `JsonRpcTransport.scala` (54 lines) | KEEP | KEEP | 0 (banner drop, `Framer` and `WireTransport` parameter type refs become `JsonRpcFramer`/`JsonRpcWireTransport`) |
| `MessageGate.scala` (13 lines) | `shared/.../kyo/internal/engine/MessageGate.scala` | MOVE | 0 (package change, drop banner) |
| `ProgressPolicy.scala` (~50 lines) | `shared/.../kyo/internal/engine/ProgressPolicy.scala` | MOVE | 0 |
| `UnknownMethodPolicy.scala` (~35 lines) | inline into `JsonRpcEndpoint.scala` as `JsonRpcEndpoint.UnknownMethodPolicy` | MERGE | -35 / +33 |
| `WireTransport.scala` (18 lines) | `shared/.../kyo/JsonRpcWireTransport.scala` | RENAME | 0 |

Internal files in `kyo-jsonrpc/shared/src/main/scala/kyo/internal/`:

| Source file | Target file | Action | LoC delta |
|---|---|---|---|
| `CancellationEngine.scala` | `shared/.../kyo/internal/engine/CancellationEngine.scala` | MOVE + package change | 0 |
| `FramerImpl.scala` | `shared/.../kyo/internal/framing/FramerImpl.scala` | MOVE + package change | 0 |
| `IdStrategyEngine.scala` | `shared/.../kyo/internal/engine/IdStrategyEngine.scala` | MOVE | 0 |
| `InMemoryTransport.scala` | `shared/.../kyo/internal/transport/InMemoryTransport.scala` | MOVE | 0 |
| `JsonRpcCodecImpl.scala` | `shared/.../kyo/internal/codec/JsonRpcCodecImpl.scala` | MOVE | 0 |
| `JsonRpcEndpointImpl.scala` | `shared/.../kyo/internal/engine/JsonRpcEndpointImpl.scala` | MOVE | 0 |
| `JsonRpcRequest.scala` | `shared/.../kyo/internal/codec/JsonRpcRequest.scala` | MOVE | 0 |
| `ProgressEngine.scala` | `shared/.../kyo/internal/engine/ProgressEngine.scala` | MOVE | 0 |
| `RateLimitEngine.scala` | `shared/.../kyo/internal/engine/RateLimitEngine.scala` | MOVE | 0 |
| `RawJsonParser.scala` | `shared/.../kyo/internal/codec/RawJsonParser.scala` | MOVE | 0 |
| `StdioWireTransport.scala` | `shared/.../kyo/internal/transport/StdioWireTransport.scala` | MOVE | 0 |
| `WireTransportAdapter.scala` | `shared/.../kyo/internal/transport/WireTransportAdapter.scala` | MOVE | 0 |
| `kyo-jsonrpc/jvm/.../kyo/JsonRpcTransportJvm.scala` | KEEP path | KEEP | 0 (drop banner; `Framer` refs → `JsonRpcFramer`) |
| `kyo-jsonrpc/jvm/.../kyo/internal/UdsWireTransport.scala` | `kyo-jsonrpc/jvm/.../kyo/internal/transport/UdsWireTransport.scala` | MOVE | 0 |
| `kyo-jsonrpc-http/.../kyo/JsonRpcHttpTransport.scala` | KEEP | KEEP | 0 (drop banner) |

Net file count: 17 → 11 shared public files; 12 → 12 shared internal files but spread across 4 subdirectories.

---

## 5. Subpackage structure for `kyo.internal.*`

Mirror of kyo-http's 7-subpackage scheme (`A1 §6`), specialised for the JSON-RPC bidirectional model (no client/server split). 4 subpackages chosen:

| Subpackage | Role | Files |
|---|---|---|
| `kyo.internal.codec` | Wire-encoding/decoding implementations | `JsonRpcCodecImpl.scala`, `RawJsonParser.scala`, `JsonRpcRequest.scala` |
| `kyo.internal.transport` | Cross-cutting transport seams + concrete transports | `InMemoryTransport.scala`, `StdioWireTransport.scala`, `WireTransportAdapter.scala`, jvm `UdsWireTransport.scala` |
| `kyo.internal.framing` | Byte-stream framers (line-delimited / content-length) | `FramerImpl.scala` |
| `kyo.internal.engine` | Endpoint engine + policy types + per-feature engine helpers | `JsonRpcEndpointImpl.scala`, `CancellationEngine.scala`, `IdStrategyEngine.scala`, `ProgressEngine.scala`, `RateLimitEngine.scala`, demoted `MessageGate.scala`, demoted `CancellationPolicy.scala`, demoted `ProgressPolicy.scala` |

Rationale for omitting `kyo.internal.util`: only 3 small helpers candidate (`RawJsonParser` is already classed under `codec`); no other miscellany. Rationale for `framing` as its own package: JSON-RPC has no kyo-http analogue (kyo-http uses HTTP-level chunking + content-length headers as part of `http1` protocol parsing). Framing is JSON-RPC-specific and rich enough (line-delimited vs content-length) to warrant its own subpackage rather than living under `codec`.

Standardise every internal file to use `package kyo.internal.<sub>` form (`A4 §11` flags the current mix of `package kyo\npackage internal` vs `package kyo.internal`).

---

## 6. Config alignment (`JsonRpcEndpoint.Config`)

Current shape (`kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEndpoint.scala:89-99`):

```scala
final case class Config(
    codec: JsonRpcCodec = JsonRpcCodec.Strict2_0,
    cancellation: Maybe[CancellationPolicy] = Absent,
    progress: Maybe[ProgressPolicy] = Absent,
    unknownMethod: UnknownMethodPolicy = UnknownMethodPolicy.minimal,
    gate: Maybe[MessageGate] = Absent,
    maxInFlight: Maybe[Int] = Absent,
    requestTimeout: Duration = Duration.Infinity,
    idStrategy: IdStrategy = IdStrategy.SequentialLong,
    progressResetsTimeout: Boolean = false
)
```

Target shape (matches `HttpServerConfig.scala:52-92`):

```scala
final case class Config(
    codec: JsonRpcCodec,
    cancellation: Maybe[internal.engine.CancellationPolicy],
    progress: Maybe[internal.engine.ProgressPolicy],
    unknownMethod: JsonRpcEndpoint.UnknownMethodPolicy,
    gate: Maybe[internal.engine.MessageGate],
    maxInFlight: Maybe[Int],
    requestTimeout: Duration,
    idStrategy: JsonRpcEndpoint.IdStrategy,
    progressResetsTimeout: Boolean
) derives CanEqual:
    require(maxInFlight.forall(_ > 0), s"maxInFlight must be > 0, got ${maxInFlight}")
    require(
        requestTimeout > Duration.Zero || requestTimeout == Duration.Infinity,
        s"requestTimeout must be positive or Duration.Infinity, got ${requestTimeout}"
    )

    def codec(c: JsonRpcCodec): Config                                                = copy(codec = c)
    def cancellation(p: internal.engine.CancellationPolicy): Config                   = copy(cancellation = Present(p))
    def progress(p: internal.engine.ProgressPolicy): Config                           = copy(progress = Present(p))
    def unknownMethod(p: JsonRpcEndpoint.UnknownMethodPolicy): Config                 = copy(unknownMethod = p)
    def gate(g: internal.engine.MessageGate): Config                                  = copy(gate = Present(g))
    def maxInFlight(n: Int): Config                                                   = copy(maxInFlight = Present(n))
    def requestTimeout(d: Duration): Config                                           = copy(requestTimeout = d)
    def idStrategy(s: JsonRpcEndpoint.IdStrategy): Config                             = copy(idStrategy = s)
    def progressResetsTimeout(b: Boolean): Config                                     = copy(progressResetsTimeout = b)
end Config

object Config:
    val default: Config = Config(
        codec = JsonRpcCodec.Strict2_0,
        cancellation = Absent,
        progress = Absent,
        unknownMethod = JsonRpcEndpoint.UnknownMethodPolicy.minimal,
        gate = Absent,
        maxInFlight = Absent,
        requestTimeout = Duration.Infinity,
        idStrategy = JsonRpcEndpoint.IdStrategy.SequentialLong,
        progressResetsTimeout = false
    )
end Config
```

Deltas:

1. Drop Scala primary-constructor defaults; defaults live exclusively on `Config.default`. Matches `HttpServerConfig.default` (`HttpServerConfig.scala:92`).
2. Add 9 per-field fluent setters. `Maybe`-wrapping setters (`cancellation`, `progress`, `gate`, `maxInFlight`) take a bare value and wrap to `Present`, matching `HttpServerConfig.cors` (`HttpServerConfig.scala:76`).
3. Add `derives CanEqual` (matches `HttpServerConfig.scala:67`).
4. Add `require(maxInFlight.forall(_ > 0), ...)` and `require(requestTimeout > Duration.Zero || requestTimeout == Duration.Infinity, ...)` (matches `HttpClientConfig.scala:65-69`).
5. Update `JsonRpcEndpoint.init`'s signature to `config: Config = Config.default` (`JsonRpcEndpoint.scala:104`).
6. Types referenced by `Config` are `JsonRpcEndpoint.UnknownMethodPolicy` / `JsonRpcEndpoint.IdStrategy` (nested, §3) and `internal.engine.{CancellationPolicy, ProgressPolicy, MessageGate}` (moved-to-internal, §3).

---

## 7. Effect-row alignment

Verified at `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEndpoint.scala:101-106`:

```scala
def init(
    transport: JsonRpcTransport,
    methods: Seq[JsonRpcMethod[Async & Abort[JsonRpcError]]],
    config: Config = Config()
)(using Frame): JsonRpcEndpoint < (Sync & Async & Scope) =
    internal.JsonRpcEndpointImpl.init(transport, methods, config).map(new JsonRpcEndpoint(_))
```

`Sync` is subsumed by `Async`. kyo-http's `HttpServer.init` is `< (Async & Scope)` (`HttpServer.scala:71`), `HttpClient.init` is `< (Async & Scope)` (`HttpClient.scala:130`). Drop `Sync` from `JsonRpcEndpoint.init`. Target row: `JsonRpcEndpoint < (Async & Scope)`.

Also verify the two other constructor methods that carry the same redundant `Sync`:

- `JsonRpcTransport.fromWire` (`JsonRpcTransport.scala:41`) — current row is `< (Async & Scope)`. Already clean.
- `JsonRpcTransport.stdio` (`JsonRpcTransport.scala:50`) — current row is `< (Async & Scope)`. Already clean.
- `JsonRpcTransportJvm.unixDomain` (`JsonRpcTransportJvm.scala:20`) — current row is `< (Async & Scope)`. Already clean.

Only `JsonRpcEndpoint.init` carries the redundant `Sync`. Single-line change.

---

## 8. Error-tree alignment (open fork resolution)

A4 flagged the question: should `JsonRpcError` become a sealed hierarchy mirroring `HttpException`? Recommendation: **keep flat**. Rationale:

1. The JSON-RPC 2.0 wire format defines `error` as `{code: Int, message: String, data?: Any}` (`JsonRpcError.scala:11`). The `code` discriminator IS the open extension point; every JSON-RPC dialect (LSP, MCP, CDP) defines its own server-range codes. A sealed Scala hierarchy would force a `Custom(code, message)` leaf that defeats exhaustive matching anyway.
2. kyo-http's hierarchy is wire-faithful too — HTTP has no analogous on-wire enum; HTTP errors are wrapped Throwables (`HttpException.scala:28` extends `KyoException`). JSON-RPC errors are not Throwables; they travel on `Abort[JsonRpcError]` only (`A4 §7`).

Existing standard-code surface (verified at `JsonRpcError.scala:14-40`):

- 11 named constants for standard codes: `ParseError`, `InvalidRequest`, `MethodNotFound`, `InvalidParams`, `InternalError`, `ServerNotInitialized`, `UnknownErrorCode`, `RequestCancelled`, `ContentModified`, `ServerCancelled`, `RequestFailed`.
- 5 parametric helpers: `methodNotFound(name)`, `invalidRequest(reason)`, `invalidParams(reason)`, `internalError(cause, data)`, `cancelled(reason)`.

The Section 8 "open fork" from the brief is already satisfied. Action: add scaladoc to the `JsonRpcError.scala:11` declaration documenting the wire-faithful rationale and pointing to the constants. No structural change.

---

## 9. Naming-prefix decision

Decision per unprefixed type (consolidating §3):

| Type | Decision | Rationale |
|---|---|---|
| `Framer` | PREFIX → `JsonRpcFramer` | Genuine extension point (caller may select `lineDelimited` vs `contentLength`), referenced from public `JsonRpcTransport.fromWire`/`stdio` signatures (`JsonRpcTransport.scala:39, 48`) and `JsonRpcTransportJvm.unixDomain` (`JsonRpcTransportJvm.scala:18`). Per `A4 §12 medium 8`, kyo-net extraction is hypothetical; prefix now |
| `WireTransport` | PREFIX → `JsonRpcWireTransport` | Same reasoning. User-implementable trait referenced from `JsonRpcTransport.fromWire(wire: WireTransport, ...)` (`JsonRpcTransport.scala:38`) |
| `HandlerCtx` | NEST-IN-COMPANION → `JsonRpcMethod.Context` | Only meaningful as a handler-body parameter; per A4 §3 mirrors `HttpRoute.RequestDef`/`ResponseDef` |
| `MessageGate` | MOVE-TO-INTERNAL → `kyo.internal.engine.MessageGate` | Zero callers; speculative extension hook (`A3 §2.5`) |
| `ExtrasEncoder` | NEST-IN-COMPANION → `JsonRpcEndpoint.Extras` | Caller-touched but exists only as `JsonRpcEndpoint` method parameter |
| `IdStrategy` | NEST-IN-COMPANION → `JsonRpcEndpoint.IdStrategy` | Pure `Config` field type, callers always reference via `.SequentialInt` etc. |
| `UnknownMethodPolicy` | NEST-IN-COMPANION → `JsonRpcEndpoint.UnknownMethodPolicy` | Caller constructs in `JsonRpcPortInvariantsSpec.scala:304`; nesting preserves the construction path |
| `CancellationPolicy` | MOVE-TO-INTERNAL → `kyo.internal.engine.CancellationPolicy` | Zero callers; `Config.cancellation` always `Absent` |
| `ProgressPolicy` | MOVE-TO-INTERNAL → `kyo.internal.engine.ProgressPolicy` | Zero callers; `Config.progress` always `Absent` |

The kyo-net memory note (`feedback_kyo_net_extraction`) suggests `Framer`/`WireTransport` might one day move to `kyo-net`. That migration is independent and should happen as a separate, cross-module refactor. Until then, the kyo-http template rule wins: every module-specific public type carries the prefix.

---

## 10. Cross-platform alignment

Two questions:

**(a) JS / Native empty source trees.**
Verified: `ls kyo-jsonrpc/js/src/main` and `ls kyo-jsonrpc/native/src/main` both empty. The cross-build (kyo-jsonrpc is `CrossType.Full`, `A2 §11`) compiles `shared/` for JS and Native; nothing platform-specific is required because every public type uses only kyo-core / kyo-stream / kyo-schema primitives (Channel, Stream, Promise) that already cross-build.

Decision: **KEEP empty**. No source files needed. The build config (`CrossType.Full`) already produces JS and Native artifacts from `shared/`. Removing the empty trees (`CrossType.Pure`) would break `JsonRpcTransportJvm` because that JVM-only file uses `java.nio` (`JsonRpcTransportJvm.scala:5-8`). Status quo is correct.

**(b) `JsonRpcTransportJvm` platform suffix.**
A4 §12 medium 12 proposes folding `JsonRpcTransportJvm.unixDomain` into `JsonRpcTransport.unixDomain` with platform-specific internal backends, matching kyo-http's `HttpPlatformTransport` pattern (`A1 §9`).

Decision: **defer (open fork — see §11)**. The kyo-http parallel folds `unixDomain` behind `HttpServerConfig.unixSocket` (`HttpServerConfig.scala:78`); UDS support on JS and Native would require shimming. JS has no UDS; Native could use kqueue / epoll local sockets. Implementing JS / Native UDS shims is a separate task. For this campaign, keep `JsonRpcTransportJvm.unixDomain` as a JVM-only factory and the existing `extension (self: JsonRpcTransport.type) def unixDomain(...)` block (`JsonRpcTransportJvm.scala:36-45`). Rename consideration (`JsonRpcUds`) is escalated.

---

## 11. Open forks (user escalation)

Three forks identified by A4 §12. All have a recommended default; user must confirm.

### Fork 1: policy types nest vs move-to-internal vs keep-public-prefixed

A4 says nest under `JsonRpcEndpoint`; A3 says move-to-internal (for the unused ones).

**Recommendation per type** (consolidated in §3):
- `MessageGate` → move-to-internal (A3 wins; zero callers)
- `CancellationPolicy` → move-to-internal (A3 wins)
- `ProgressPolicy` → move-to-internal (A3 wins)
- `UnknownMethodPolicy` → nest (A4 wins; load-bearing in tests)
- `IdStrategy` → nest (A4 wins; load-bearing in 6 files)
- `ExtrasEncoder` → nest (A4 wins; load-bearing in 3 files)

**Escalation question for user**: "Confirm: move `MessageGate`, `CancellationPolicy`, `ProgressPolicy` to `kyo.internal.engine.*` (hidden from external callers) AND nest `UnknownMethodPolicy`, `IdStrategy`, `ExtrasEncoder` under `JsonRpcEndpoint.*` (still public, namespaced)? YES = proceed with §3 table. NO = revisit."

### Fork 2: `Framer` / `WireTransport` — prefix now or wait for kyo-net

A4 §12 medium 8 flags this.

**Recommendation**: prefix now. The kyo-net extraction is not on any active roadmap; honour the current kyo-jsonrpc module boundary. If kyo-net later extracts these, a rename is a mechanical move-and-cite operation.

**Escalation question**: "Confirm: rename `Framer` → `JsonRpcFramer` and `WireTransport` → `JsonRpcWireTransport` now? YES = proceed. NO = hold for a later kyo-net extraction RFC."

### Fork 3: `JsonRpcError` flat vs sealed hierarchy

A4 §7 flags this.

**Recommendation**: keep flat (§8). Wire shape is `{code: Int, ...}`; sealed hierarchy fights the JSON-RPC 2.0 grammar.

**Escalation question**: "Confirm: keep `JsonRpcError` as a flat `case class` with the existing 11 named-code constants + 5 helpers (`JsonRpcError.scala:14-40`)? YES = proceed (no structural change; add scaladoc rationale). NO = design sealed hierarchy in a separate RFC."

Additional fork (open from §10):

### Fork 4 (bonus): rename `JsonRpcTransportJvm`?

**Recommendation**: leave as-is for this campaign. The current shape (object + extension on `JsonRpcTransport.type`) is idiomatic; the `*Jvm` suffix is honest about platform constraint.

**Escalation question**: "Rename `JsonRpcTransportJvm` to `JsonRpcUds` (clearer about purpose) OR leave as-is? RECOMMEND leave."

---

## 12. Migration phases

Each phase is one atomic commit, ending green on `kyo-jsonrpc/Test/compile` + `kyo-jsonrpc-http/Test/compile` + targeted scenario tests for affected types. Per `feedback_targeted_tests_only`, broad runs reserved for phase-group boundaries.

### Phase 1 — Internal subpackage reorg + banner sweep

Scope:
- Create `kyo-jsonrpc/shared/src/main/scala/kyo/internal/{codec,transport,framing,engine}` directories.
- Move 12 internal files per §4 table.
- Move `kyo-jsonrpc/jvm/.../kyo/internal/UdsWireTransport.scala` to `.../kyo/internal/transport/`.
- Standardise all internal files to `package kyo.internal.<sub>` form (replaces the mixed forms in `CancellationEngine.scala`, `JsonRpcEndpointImpl.scala`, `ProgressEngine.scala`, `RateLimitEngine.scala` that currently use `package kyo\npackage internal`; `A4 §11`).
- Strip every `// PUBLIC ...` banner line (line 1 of every public-surface file). Each banner becomes the first line of a scaladoc `/** ... */` block if its content is descriptive; otherwise dropped.

Diff size: ~12 internal files renamed, ~17 public files lose banner line, ~5 internal files re-package. Pure mechanical.

Affected tests: none. Public surface unchanged.

Blocked-by: none.

### Phase 2 — Move A3-UNUSED types to internal

Scope:
- Move `MessageGate.scala` to `kyo/internal/engine/MessageGate.scala`.
- Move `CancellationPolicy.scala` to `kyo/internal/engine/CancellationPolicy.scala`.
- Move `ProgressPolicy.scala` to `kyo/internal/engine/ProgressPolicy.scala`.
- Re-type `JsonRpcEndpoint.Config` fields: `gate: Maybe[internal.engine.MessageGate]`, `cancellation: Maybe[internal.engine.CancellationPolicy]`, `progress: Maybe[internal.engine.ProgressPolicy]`.
- Update engine code that references these types: `internal/engine/CancellationEngine.scala`, `internal/engine/ProgressEngine.scala`, `internal/engine/JsonRpcEndpointImpl.scala`.

Diff size: 3 file moves + ~6 reference updates inside `JsonRpcEndpoint.scala` and the engine files.

Affected tests: `CancellationPolicyTest.scala`, `MessageGateTest.scala`, `ProgressPolicyTest.scala` (`A2 §5 8c`). These test files import the demoted types. Since they live in `kyo-jsonrpc/shared/src/test/scala/kyo/`, they still have `package kyo.internal.engine` visibility (kyo-package privilege). Update imports.

Blocked-by: Phase 1 (the engine subpackage must exist).

### Phase 3 — Nest types in companions

Scope:
- Move `HandlerCtx` source body into `JsonRpcMethod.scala` as `JsonRpcMethod.Context`. Delete `HandlerCtx.scala`.
- Move `ExtrasEncoder` source body into `JsonRpcEndpoint.scala` as `JsonRpcEndpoint.Extras`. Delete `ExtrasEncoder.scala`.
- Move `IdStrategy` source body into `JsonRpcEndpoint.scala` as `JsonRpcEndpoint.IdStrategy`. Delete `IdStrategy.scala`.
- Move `UnknownMethodPolicy` source body into `JsonRpcEndpoint.scala` as `JsonRpcEndpoint.UnknownMethodPolicy`. Delete `UnknownMethodPolicy.scala`.
- Update engine code (`internal/engine/JsonRpcEndpointImpl.scala`, `internal/engine/IdStrategyEngine.scala`) and consumer test files (`HandlerCtxTest.scala`, `ExtrasEncoderTest.scala`, `IdStrategyTest.scala`, `UnknownMethodPolicyTest.scala`).
- Update kyo-browser imports (§14).

Diff size: 4 file deletes, 2 grow (JsonRpcEndpoint.scala, JsonRpcMethod.scala) by ~80 + ~28 lines, ~30 reference updates across engine + tests + kyo-browser.

Affected tests: the 4 corresponding test files plus any cross-test references. `JsonRpcEndpoint.scala` grows from 108 to ~190 lines, still under `HttpRoute.scala`'s 554-line precedent (`A4 §3`).

Blocked-by: Phase 2 (Config field types updated).

### Phase 4 — Merge `JsonRpcResponse` into `JsonRpcEnvelope.Response`

Scope:
- Add `success(id: JsonRpcId, result: Structure.Value): JsonRpcEnvelope.Response` and `failure(id: JsonRpcId, error: JsonRpcError): JsonRpcEnvelope.Response` factories on `JsonRpcEnvelope` companion (lifted from `JsonRpcResponse.scala:19-22`).
- Reconcile the `id` field shape: `JsonRpcResponse.id: Maybe[JsonRpcId]` (`JsonRpcResponse.scala:13`) vs `JsonRpcEnvelope.Response.id: JsonRpcId` (`JsonRpcEnvelope.scala:20`). The `Maybe[JsonRpcId]` covers the parse-error-id-unknown branch already covered by `JsonRpcEnvelope.Malformed`. So `Response.id` stays as `JsonRpcId` (non-Maybe); parse-error path uses `Malformed`. No change to `JsonRpcEnvelope` schema.
- Delete `JsonRpcResponse.scala` and `JsonRpcResponseTest.scala`.
- Update `internal/engine/JsonRpcEndpointImpl.scala` references from `JsonRpcResponse.success/failure` to `JsonRpcEnvelope.Response` constructor / `JsonRpcEnvelope.success/failure`.
- Update `internal/codec/JsonRpcCodecImpl.scala` if it references `JsonRpcResponse` (verify).

Diff size: 2 file deletes, ~10 reference updates.

Affected tests: `JsonRpcEnvelopeTest.scala` gains test cases (success/failure factories); `JsonRpcResponseTest.scala` deleted.

Blocked-by: Phase 3.

### Phase 5 — Prefix-rename `Framer` and `WireTransport`

Scope:
- Rename `Framer.scala` → `JsonRpcFramer.scala`, type `Framer` → `JsonRpcFramer`.
- Rename `WireTransport.scala` → `JsonRpcWireTransport.scala`, type `WireTransport` → `JsonRpcWireTransport`.
- Update `JsonRpcTransport.scala` parameter types (`fromWire(wire: JsonRpcWireTransport, framer: JsonRpcFramer, codec: JsonRpcCodec = ...)`).
- Update `JsonRpcTransportJvm.scala` parameter types.
- Update `internal/transport/*` and `internal/framing/*` to reference the renamed types.
- Update test files `FramerTest.scala` → `JsonRpcFramerTest.scala`, `WireTransportTest.scala` → `JsonRpcWireTransportTest.scala`.

Diff size: 2 file renames, ~15 reference updates.

Affected tests: 2 renamed tests; no other consumer in the repo references `Framer`/`WireTransport` (`A3 §2.2`, `§2.7`).

Blocked-by: Phase 4.

### Phase 6 — Config alignment (fluent setters, default, require, CanEqual)

Scope: implement the target shape from §6.
- Drop primary-ctor defaults from `JsonRpcEndpoint.Config`.
- Add 9 fluent setters.
- Add `Config.default` constant.
- Add 2 `require(...)` validations.
- Add `derives CanEqual`.
- Update `JsonRpcEndpoint.init` to `config: Config = Config.default`.
- Update all callers in tests and kyo-browser that construct `Config(...)` positionally (verify; `CdpBackend.scala:195, 456` use `Config(codec = ..., idStrategy = ..., unknownMethod = ..., gate = Absent)` named-arg form — should still work).

Diff size: `JsonRpcEndpoint.scala` grows by ~40 lines (setters + default + require + CanEqual); ~3 caller files possibly updated.

Affected tests: `JsonRpcEndpointTest.scala` gains coverage for fluent setters, default, validation.

Blocked-by: Phase 5.

### Phase 7 — Effect-row drops

Scope: drop `Sync` from `JsonRpcEndpoint.init` (`JsonRpcEndpoint.scala:105`). Verify no other public method carries redundant `Sync`. Single-line change.

Diff size: 1 line.

Affected tests: any test asserting the return type might need an update; none currently asserts the exact row (verify on commit).

Blocked-by: Phase 6.

### Phase 8 — Final cross-platform green run

Scope:
- Run full test suite: `kyo-jsonrpc/Test/compile`, `kyo-jsonrpc/test`, `kyo-jsonrpc-http/Test/compile`, `kyo-jsonrpc-http/test`, `kyo-browser/Test/compile`, `kyo-browser/test`.
- Cross-platform: JVM + JS + Native for `kyo-jsonrpc` and `kyo-jsonrpc-http`. Per `feedback_sequential_test_runs`, run JVM → JS → Native sequentially, not in parallel.
- If any test fails, fix in place and re-run; do not weaken tests (per `feedback_test_rigor`).

Diff size: 0 or small fix-ups.

Affected tests: all.

Blocked-by: Phase 7.

---

## 13. Test impact

Per `A2 §5 8c`, every kyo-jsonrpc main file has a matching `*Test.scala` in `kyo-jsonrpc/shared/src/test/scala/kyo/`. Per phase:

| Test file | Phase | Touch type | Lines changed (est.) |
|---|---|---|---|
| `CancellationPolicyTest.scala` | Phase 2 | import update + `internal.engine.CancellationPolicy` ref | ~5 |
| `MessageGateTest.scala` | Phase 2 | import update | ~5 |
| `ProgressPolicyTest.scala` | Phase 2 | import update | ~5 |
| `HandlerCtxTest.scala` | Phase 3 | type rename `HandlerCtx` → `JsonRpcMethod.Context` everywhere; possibly rename file to `JsonRpcMethodContextTest.scala` if 8c strictness applies (or absorb into `JsonRpcMethodTest.scala`) | ~30 |
| `ExtrasEncoderTest.scala` | Phase 3 | rename refs `ExtrasEncoder` → `JsonRpcEndpoint.Extras`; rename file `JsonRpcEndpointExtrasTest.scala` or absorb into `JsonRpcEndpointTest.scala` | ~30 |
| `IdStrategyTest.scala` | Phase 3 | similar | ~20 |
| `UnknownMethodPolicyTest.scala` | Phase 3 | similar | ~30 |
| `JsonRpcResponseTest.scala` | Phase 4 | DELETED (cases absorbed into `JsonRpcEnvelopeTest.scala`) | -all |
| `JsonRpcEnvelopeTest.scala` | Phase 4 | gain success/failure factory cases | +30 |
| `FramerTest.scala` | Phase 5 | rename file to `JsonRpcFramerTest.scala`, rename ref `Framer` → `JsonRpcFramer` | ~15 |
| `WireTransportTest.scala` | Phase 5 | rename file to `JsonRpcWireTransportTest.scala` | ~15 |
| `JsonRpcEndpointTest.scala` | Phase 3, 6 | add cases for nested members + fluent setters / default / require | +50 |
| `JsonRpcMethodTest.scala` | Phase 3 | add `Context` cases (absorbing former `HandlerCtxTest.scala`) | +30 |
| `JsonRpcTransportTest.scala` | Phase 5 | ref renames for `JsonRpcFramer`/`JsonRpcWireTransport` | ~10 |
| `JsonRpcTransportJvmTest.scala` (jvm) | Phase 5 | ref renames | ~5 |
| `JsonRpcHttpTransportTest.scala` (http) | Phase 1 (banner only) | banner removal | ~1 |
| Scenario tests (`BidiTest`, `HttpStyleTest`, `MaxInFlightTest`, `WsStyleTest`) | Phase 3, 5 | likely ref renames if they construct policy types | ~10 each |
| `JsonRpcTestBase.scala` | none | unchanged | 0 |

Total in-module test churn: ~280 lines across ~16 test files. Most touches are pure imports + symbol renames; no test logic changes.

Per `feedback_test_placement`: when absorbing `HandlerCtxTest`/`ExtrasEncoderTest`/`IdStrategyTest`/`UnknownMethodPolicyTest` into the owner's test file, follow the existing topic-based organisation rather than creating phase-coded test files. Recommendation: KEEP separate test files mirroring the nested-type structure (e.g., `JsonRpcEndpointExtrasTest.scala` for `JsonRpcEndpoint.Extras`), preserving Rule 8c's per-symbol coverage discipline.

---

## 14. Consumer-update plan (kyo-browser)

kyo-browser is the canonical external consumer (`A3 §5`). 6 files reference kyo-jsonrpc types:

1. `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala`
2. `kyo-browser/shared/src/main/scala/kyo/internal/Resolver.scala`
3. `kyo-browser/shared/src/main/scala/kyo/internal/cdp/Accessibility.scala`
4. `kyo-browser/shared/src/test/scala/kyo/internal/CdpBackendTest.scala`
5. `kyo-browser/shared/src/test/scala/kyo/internal/CdpBackendSmokeTest.scala`
6. `kyo-browser/shared/src/test/scala/kyo/internal/CdpBackendLifecycleTest.scala`
7. `kyo-browser/shared/src/test/scala/kyo/internal/CdpClientDecoderTest.scala`
8. `kyo-browser/shared/src/test/scala/kyo/internal/JsonRpcPortInvariantsSpec.scala`

Per-type breakdown of kyo-browser-side updates:

| kyo-jsonrpc type change | kyo-browser site | Update |
|---|---|---|
| `ExtrasEncoder` → `JsonRpcEndpoint.Extras` | `CdpBackend.scala:41,44,590,593`; `CdpBackendSmokeTest.scala:152,186,211,251`; `JsonRpcPortInvariantsSpec.scala:212` | Rename: `ExtrasEncoder.const` → `JsonRpcEndpoint.Extras.const`, `ExtrasEncoder.empty` → `JsonRpcEndpoint.Extras.empty`. ~10 sites |
| `IdStrategy` → `JsonRpcEndpoint.IdStrategy` | `CdpBackend.scala:203,464,576`; tests at `CdpBackendTest.scala:52`, `CdpBackendSmokeTest.scala:43`, `CdpBackendLifecycleTest.scala:1178`, `JsonRpcPortInvariantsSpec.scala:56,321` | Rename `IdStrategy.SequentialInt` → `JsonRpcEndpoint.IdStrategy.SequentialInt`. ~8 sites |
| `UnknownMethodPolicy` → `JsonRpcEndpoint.UnknownMethodPolicy` | `CdpBackend.scala:199,460`; `JsonRpcPortInvariantsSpec.scala:304-306` | Rename `UnknownMethodPolicy.minimal` → `JsonRpcEndpoint.UnknownMethodPolicy.minimal`; `UnknownMethodPolicy.UnknownAction.Drop` → `JsonRpcEndpoint.UnknownMethodPolicy.UnknownAction.Drop`. ~5 sites |
| `HandlerCtx` → `JsonRpcMethod.Context` | `CdpBackend.scala:607` (doc comment) | Reword doc comment. 1 site |
| `MessageGate`, `CancellationPolicy`, `ProgressPolicy` → internal | No kyo-browser site references these (`A3 §2.5, §2.1, §2.6`) | No update needed |
| `Framer` → `JsonRpcFramer`, `WireTransport` → `JsonRpcWireTransport` | No kyo-browser site references these (`A3 §2.2, §2.7`) | No update needed |
| `JsonRpcResponse` → merged into `JsonRpcEnvelope.Response` | No kyo-browser site references `JsonRpcResponse` (`A3 §2.4`) | No update needed |

Net kyo-browser update: ~25 lines across 6 files. All are mechanical `find-and-replace` operations.

**Package-visibility note on moved-to-internal types**: kyo-browser's `kyo/internal/CdpBackend.scala` lives in `package kyo.internal`, so it CAN import `kyo.internal.engine.MessageGate` etc. if it ever needs to. The move-to-internal does NOT break kyo-browser today because kyo-browser does not reference any of `MessageGate`/`CancellationPolicy`/`ProgressPolicy`. If a future kyo-browser feature requires these, the `kyo.internal.engine.*` symbols remain reachable.

---

## 15. Risks

1. **kyo-browser as canary**: kyo-browser must update simultaneously with kyo-jsonrpc Phases 3 and 5 (the renames). The kyo-browser update cannot ship as a separate commit because the renamed symbols would break kyo-browser compile on the kyo-jsonrpc commit. Mitigation: each phase commit includes the kyo-browser change in the same atomic commit. This is correct per `feedback_commit_between_phases` and the user's atomic-green discipline.
2. **Cross-platform compile churn during subpackage rename (Phase 1)**: Native and JS rebuild the whole `kyo.internal.*` namespace when subpackage names change. Compile time impact is one-off; runtime impact is zero. Mitigation: run JVM → JS → Native sequentially per `feedback_sequential_test_runs` and confirm green per platform before moving to Phase 2.
3. **Backward incompatibility**: every kyo-jsonrpc user breaks on the rename. The only external user is kyo-browser, which is updated in lockstep. No other modules consume kyo-jsonrpc (`A3 §0 scope`). Acceptable per `feedback_no_backcompat`.
4. **`Config` validation `require` may break tests with edge values**: tests that construct `Config(maxInFlight = Present(0))` or `Config(requestTimeout = Duration.Zero)` will now throw `IllegalArgumentException`. Mitigation: grep all `Config(` constructions in tests; either fix the test values or relax the `require` predicate. Verified `JsonRpcEndpointTest.scala` and scenario tests as priority.
5. **`JsonRpcResponse` deletion (Phase 4) loses a Schema-derived type**: any user that imports `kyo.JsonRpcResponse` to encode/decode via `kyo.Json` directly would break. No such user exists (`A3 §2.4`). Internal code paths (engine, codec impl) all go through `JsonRpcEnvelope.Response`. Mitigation: grep for `JsonRpcResponse` references one final time before deletion.
6. **`HandlerCtx` rename to `JsonRpcMethod.Context` may collide** with kyo-core's `kyo.Context` if any. Verify by grepping `kyo-core` for `Context` at `package kyo`. If collision, fall back to `JsonRpcMethod.HandlerCtx` (verbose but unambiguous).
7. **`feedback_no_type_aliases` violation risk**: `CancellationPolicy` companion exposes `type ParamsEncoder` and `type ParamsDecoder` (`A4 §6`). After Phase 2 moves `CancellationPolicy` to `internal.engine`, these aliases remain in the internal type. Per the feedback the rule is "never `type X = ...` at user-facing scope"; internal type aliases are acceptable as long as no public method references them. Verify the public surface doesn't expose them.

---

## 16. Non-goals (explicit)

1. **kyo-browser product changes**: only mechanical import updates required to keep kyo-browser compiling. No kyo-browser API changes, no CDP method additions, no behaviour changes.
2. **kyo-core or other modules**: this cleanup touches kyo-jsonrpc + kyo-jsonrpc-http + (mechanically) kyo-browser only.
3. **kyo-net extraction**: §11 Fork 2 honours kyo-net as a future constraint on `Framer`/`WireTransport`, but does NOT extract them in this campaign. The chosen action (prefix-rename) is a pure rename; if kyo-net later extracts the renamed types, the move is mechanical.
4. **`JsonRpcMethod` / `JsonRpcHandler` split** (A4 §12 nice-to-have 13): deferred. Current `JsonRpcMethod.apply` doing both contract + handler-pairing jobs is acceptable; the split is a deeper refactor.
5. **`JsonRpcEndpoint.Unsafe` low-level API** (A4 §12 nice-to-have 14): deferred. No low-level construction need today.
6. **`JsonRpcError` sealed hierarchy**: rejected (§8). Wire-faithful flat case class wins.
7. **JS / Native source population**: not needed (§10). `CrossType.Full` builds JS/Native from `shared/`; the empty platform source trees are correct.
8. **`JsonRpcTransportJvm` → `JsonRpcUds` rename or multi-platform fold**: deferred to a separate task (§11 Fork 4).
9. **Test-file restructuring beyond what each phase demands**: keep per-symbol `*Test.scala` files (Rule 8c). Nested-type tests get matching `*Test.scala` (e.g., `JsonRpcEndpointExtrasTest.scala`) rather than absorbing into the parent test.

---

## Appendix: phase summary table

| Phase | Scope | Files touched | LoC delta | Blocked by | Atomic-green? |
|---|---|---|---|---|---|
| 1 | Internal subpackage reorg + banner sweep | ~30 files | ~50 banner-line removals + 13 file moves | none | yes |
| 2 | Move A3-UNUSED policy types to internal | 3 moves + ~6 ref updates | ~25 lines | Phase 1 | yes |
| 3 | Nest types in companions | 4 deletes + 2 grows + tests + kyo-browser | ~150 + ~50 in kyo-browser | Phase 2 | yes |
| 4 | Merge `JsonRpcResponse` into `JsonRpcEnvelope.Response` | 2 deletes + ~10 ref updates | -24 + 30 | Phase 3 | yes |
| 5 | Prefix-rename `Framer`/`WireTransport` | 2 renames + ~15 ref updates | ~30 lines | Phase 4 | yes |
| 6 | Config alignment | 1 file grows + tests | ~70 lines | Phase 5 | yes |
| 7 | Drop `Sync` from `JsonRpcEndpoint.init` | 1 line | 1 line | Phase 6 | yes |
| 8 | Cross-platform green run | 0 source changes | 0 | Phase 7 | yes |

Total LoC delta (source): ~+200 lines net (mostly fluent setters + nested-type bodies relocating into `JsonRpcEndpoint.scala`). Total file count delta: 17 → 11 public shared files (-6), 12 → 12 internal shared files but in 4 subdirectories.

End of cleanup plan. User decision required on §11 Forks 1, 2, 3, 4 before execution.
