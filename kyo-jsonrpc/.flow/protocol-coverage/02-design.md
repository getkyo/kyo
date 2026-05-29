# 02 Design: kyo-jsonrpc engine-layer coverage (v2, 10 items)

Task type: refactor (with new-feature seams)
Cites exploration: ./01-exploration.md
Cites steering directive: ./steering.md "Design v1 -> v2 directive" (lines 86-140)

## Goal

kyo-jsonrpc is a clean JSON-RPC 2.0 engine plus extensibility seams. The
engine knows the JSON-RPC 2.0 wire envelope, the codec contract, the
policy contracts (cancellation, progress, unknown-method, gate), and the
transport contract. The engine does NOT know LSP, MCP, or CDP. Future
consumer modules (`kyo-mcp`, `kyo-lsp`, `kyo-cdp`) sit on top, wiring
protocol-specific field names, presets, and method libraries through the
existing policy callback shapes.

Concrete scope: ten engine-level changes that close the protocol-agnostic
gaps surfaced by the audits. The seven protocol-named items (1, 2, 11,
14, 15, 16, 17) from v1 are dropped to the consumer modules and listed
in `## Rejected alternatives`. The 17-item plan compresses to 10
engine-level items because the existing `ProgressPolicy` callbacks plus
`endpoint.notify` already cover the LSP-shape items (2, 17) without new
engine surface; the remaining protocol items belong in `kyo-mcp` /
`kyo-cdp`.

A single engine-level default-neutrality fix accompanies the seam work:
`Config()` no-arg constructor's `cancellation` default changes from
`Present(CancellationPolicy.lsp)` to `Absent`, so a consumer module
opts in to a cancellation protocol rather than inheriting LSP semantics
silently. The codec, progress, and unknownMethod defaults are already
neutral and stay as-is.

## API surface

Each entry: full signature, source file, test file, one-line role. Rule
8c HARD: every new source file is paired with exactly one focused test
file. Signatures co-located in an existing public type re-use that
type's source and test file (no orphan additions).

### Item 3: Progress token uniqueness (allocator helper)

New internal helper on the existing internal `ProgressEngine`:

```scala
private[kyo] def allocateProgressToken(
    progressStreams: ConcurrentHashMap[Structure.Value, Channel[?]],
    channel: Channel[?],
    maxAttempts: Int = 32
)(using Frame): Structure.Value < (Sync & Abort[JsonRpcError])
```

Loop: generate via `Random.live.unsafe.nextStringAlphanumeric(32)`, call
`progressStreams.putIfAbsent(token, channel)`, retry on collision up to
`maxAttempts`, then `Abort.fail(JsonRpcError.internalError("progress token exhaustion"))`.
`callWithProgress` and `callPartialResults` route allocation through this
helper instead of the open-coded sequence at
`kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcEndpointImpl.scala:368`
(callWithProgress alloc) and `:451` (callPartialResults alloc). The
collision-safe contract is generic over what the token-string represents
on the wire: the engine sees only a `Structure.Value`, the consumer
module chooses the field name (LSP `workDoneToken`, MCP
`_meta.progressToken`) via `ProgressPolicy.stampOutboundToken`.

Role: collision-safe allocator (generic putIfAbsent primitive).
Source file: `kyo/internal/ProgressEngine.scala` (existing internal,
in-place addition)
Test file: `kyo/ProgressPolicyTest.scala` (engine-facing case in the
existing policy suite, per Rule 8c topic-based placement)
Rationale: exploration Item 3; live MCP spec MUST per
`research-findings/Q-003.md` (token uniqueness clause). Generic over
protocol because the token is opaque to the engine.

### Item 4: `JsonRpcTransport.webSocket` adapter

Lives in the new subproject `kyo-jsonrpc-http` per the steering Q-001
resolution (kyo-http stays out of the kyo-jsonrpc core dep graph).

```scala
def webSocket(
    url: HttpUrl,
    headers: HttpHeaders = HttpHeaders.empty,
    codec:   JsonRpcCodec = JsonRpcCodec.Strict2_0
)(using Frame): JsonRpcTransport < (Async & Scope & Abort[HttpException])
```

Calls `HttpClient.webSocket(url, headers, HttpWebSocket.Config())(ws => ...)`
(signature at `kyo-http/shared/src/main/scala/kyo/HttpClient.scala:737-758`),
bridges Text frames through `codec`, drops Binary frames with a warn log,
and connects `incoming` and `send` via two relay channels under
`Scope.ensure` cleanup. The live recipe at
`concurrent-imagining-stroustrup/kyo-browser/shared/src/main/scala/kyo/internal/CdpClient.scala:230-266`
is the migration source.

Role: lift `HttpWebSocket` to `JsonRpcTransport` over the JSON-RPC 2.0
codec (protocol-agnostic).
Source file: `kyo-jsonrpc-http/shared/src/main/scala/kyo/JsonRpcHttpTransport.scala`
Test file: `kyo-jsonrpc-http/shared/src/test/scala/kyo/JsonRpcHttpTransportTest.scala`

### Item 5: `JsonRpcTransport.stdio` adapter

```scala
def stdio(
    codec:  JsonRpcCodec = JsonRpcCodec.Strict2_0,
    framer: Framer       = Framer.lineDelimited
)(using Frame): JsonRpcTransport < (Async & Scope)
```

Implemented via Item 7 as `JsonRpcTransport.fromWire(stdioWire, framer, codec)`.
`stdioWire` reads `Console.readLine`
(`kyo-core/shared/src/main/scala/kyo/Console.scala:25`) into the byte
stream and writes `Console.printLine` per outbound chunk. The `framer`
parameter is the byte-stream framing strategy (line-delimited default,
Content-Length opt-in for LSP-shaped consumers). The engine knows the
framer-and-codec composition is generic; consumer modules pick a
specific framer.

Role: line-framed stdio JSON-RPC for CLI-style RPC servers (protocol-agnostic).
Source file: `kyo/JsonRpcTransport.scala` (companion-only addition)
Test file: `kyo/JsonRpcTransportTest.scala`
Rationale: live recipe documented in `01-exploration.md` Item 5
prior-art at `HarnessApp.scala:240-303`.

### Item 6: `JsonRpcTransport.unixDomain` adapter (JVM-only)

```scala
def unixDomain(
    sockPath: Path,
    codec:    JsonRpcCodec = JsonRpcCodec.Strict2_0,
    framer:   Framer       = Framer.lineDelimited
)(using Frame): JsonRpcTransport < (Async & Scope)
```

JVM-only. Lives in `kyo-jsonrpc/jvm/src/main/scala/kyo/JsonRpcTransportJvm.scala`
(a sibling file under the same `kyo.JsonRpcTransport` companion via
`extension`-on-companion). Mirrors the `HookSocketServer.scala:31-54`
bind + `Scope.ensure` + accept-loop recipe documented in
`01-exploration.md` Item 6 prior-art.

The file is named `JsonRpcTransportJvm.scala` (not `JsonRpcTransport.scala`)
to avoid a same-name conflict with the shared file under cross-build.
The added method sits on the shared `JsonRpcTransport` companion via
`extension`-on-singleton (cleanest form for a singleton augmentation on
a trait-with-companion).

Role: UDS JSON-RPC transport (JVM-only, protocol-agnostic).
Source file: `kyo-jsonrpc/jvm/src/main/scala/kyo/JsonRpcTransportJvm.scala`
Test file: `kyo-jsonrpc/jvm/src/test/scala/kyo/JsonRpcTransportJvmTest.scala`

### Item 7: `WireTransport` + `Framer` (byte-stream seam)

```scala
trait WireTransport:
    def send(bytes: Chunk[Byte])(using Frame): Unit < (Async & Abort[Closed])
    def incoming(using Frame): Stream[Chunk[Byte], Async & Abort[Closed]]
    def close(using Frame): Unit < Async

object WireTransport:
    val empty: WireTransport
```

```scala
trait Framer:
    def frame(bytes: Chunk[Byte])(using Frame): Chunk[Byte] < Sync
    def parse(stream: Stream[Chunk[Byte], Async & Abort[Closed]])(using Frame):
        Stream[Chunk[Byte], Async & Abort[Closed]]

object Framer:
    val lineDelimited:  Framer
    val contentLength:  Framer
```

```scala
// on JsonRpcTransport companion
def fromWire(
    wire:   WireTransport,
    framer: Framer,
    codec:  JsonRpcCodec = JsonRpcCodec.Strict2_0
)(using Frame): JsonRpcTransport < (Async & Scope)
```

Role: byte-level seam beneath the envelope codec. The `Framer.contentLength`
preset is a generic `Content-Length: N\r\n\r\n` envelope framer; it is
not LSP-specific (HTTP, LSP, and other Content-Length-framed wires
share the shape). `Framer.lineDelimited` covers stdio MCP and any other
one-message-per-line wire.

Source files (each paired with its own test):
  - `kyo/WireTransport.scala`  paired with `kyo/WireTransportTest.scala`
  - `kyo/Framer.scala`         paired with `kyo/FramerTest.scala`

The `fromWire` companion addition lives on the existing
`JsonRpcTransport.scala` (already paired with `JsonRpcTransportTest.scala`
per Item 5).

Rationale: exploration Item 7; the type name omits any protocol prefix
because the seam is engine-level.

### Item 8: Tolerant fallback id extraction on malformed responses

Mutate `JsonRpcEnvelope.Malformed`:

```scala
case Malformed(
    id:     Maybe[JsonRpcId],
    reason: String,
    raw:    Structure.Value
)
```

Codec decoders (`Strict2_0` at `JsonRpcCodecImpl.scala:104` and `Cdp` at
`:219`) attempt id re-extraction before constructing the `Malformed`
case via the existing `decodeId` helper at `:72-77` / `:185-190`. Engine
`decodeCallback` at `JsonRpcEndpointImpl.scala:1181-1182` routes:

- `Malformed(Present(id), reason, _)` -> fail caller's pending promise
  with `JsonRpcError.invalidRequest("malformed response: " + reason)`.
- `Malformed(Absent, _, _)` -> retain current Skip semantic.

Role: replace timeout-hang on malformed responses with eager decode
failure. Protocol-agnostic: the codec recovers the id field from any
JSON-RPC 2.0 envelope shape independent of method-name semantics.
Source files:
  - `kyo/JsonRpcEnvelope.scala`               paired with `kyo/JsonRpcEnvelopeTest.scala`
  - `kyo/internal/JsonRpcCodecImpl.scala`     paired with `kyo/JsonRpcCodecTest.scala`
  - `kyo/internal/JsonRpcEndpointImpl.scala`  paired with `kyo/JsonRpcEndpointTest.scala`
Rationale: live precedent at
`concurrent-imagining-stroustrup/kyo-browser/.../CdpClient.scala:548-557`
(`fallbackDecode`).

### Item 9: Two-phase `close(gracePeriod)`

```scala
def close(gracePeriod: Duration)(using Frame): Unit < Async
def close(using Frame):  Unit < Async        // = close(Duration.Zero)
def closeNow(using Frame): Unit < Async      // = close(Duration.Zero), alias
```

Matches the `HttpClient` shape at
`kyo-http/shared/src/main/scala/kyo/HttpClient.scala:86-93`. Impl: run
`awaitDrain` under `Async.timeout(gracePeriod)`, then run current
`finalizer` regardless. The `Scope.acquireRelease` finalizer at
`JsonRpcEndpointImpl.scala:719` retains force-close (Scope semantic
stays crisp; grace lives on explicit user call).

Role: drain-then-force teardown (engine-level lifecycle).
Source file: `kyo/JsonRpcEndpoint.scala` (companion plus engine impl in
`internal/JsonRpcEndpointImpl.scala`)
Test file: `kyo/JsonRpcEndpointTest.scala`

### Item 10: `CancellationPolicy` owns its decoder

```scala
final case class CancellationPolicy(
    cancelMethod:                    String,
    encodeParams:                    CancellationPolicy.ParamsEncoder,
    decodeParams:                    Structure.Value => Maybe[JsonRpcId] < Sync,
    expectReplyForCancelledRequest:  Boolean,
    cancelledError:                  Maybe[JsonRpcError],
    protectedMethods:                Set[String]
)
```

`CancellationPolicy.lsp` and `.mcp` presets ship matching decoders; the
generic primitive is the new `decodeParams` field. `CancellationEngine.extractCancelId`
at `kyo-jsonrpc/shared/src/main/scala/kyo/internal/CancellationEngine.scala:14-27`
delegates to `policy.decodeParams` and drops the method-name branch at
`:19`. A third-party policy supplies its own decoder; the engine never
hardcodes a method-name fork.

Role: per-policy decoder; removes the engine-baked LSP/MCP method-name
branch.
Source files:
  - `kyo/CancellationPolicy.scala`           paired with `kyo/CancellationPolicyTest.scala`
  - `kyo/internal/CancellationEngine.scala`  paired with `kyo/CancellationPolicyTest.scala` (engine delegation case)

### Item 12: Public `JsonRpcMethod.dispatch`

```scala
object JsonRpcMethod:
    def dispatch[S](
        name:    String,
        methods: Seq[JsonRpcMethod[S]],
        params:  Structure.Value,
        ctx:     HandlerCtx
    )(using Frame): Maybe[Structure.Value] < (S & Abort[JsonRpcError])
```

Builds the methodMap once (re-using the pattern at
`JsonRpcEndpointImpl.scala:888`), looks up `name`, returns `Absent` for
unknown method (caller decides how to encode that), and otherwise
delegates to the existing `private[kyo] def handle`. `handle` stays
`private[kyo]`; `dispatch` is the public reach-in.

Role: non-engine consumers (one-shot stdio loop, HTTP POST endpoints,
custom routers) skip the engine's queueing and id-allocation without
violating `private[kyo]`. Engine-level: knows only `JsonRpcMethod[S]`
and `Structure.Value`.
Source file: `kyo/JsonRpcMethod.scala`
Test file: `kyo/JsonRpcMethodTest.scala`

### Item 13: `endpoint.sendUnmatched`

```scala
def sendUnmatched[In: Schema](
    method: String,
    params: In,
    id:     JsonRpcId,
    extras: ExtrasEncoder = ExtrasEncoder.empty
)(using Frame): Unit < (Async & Abort[Closed])
```

Encodes via configured codec as `JsonRpcEnvelope.Request(id, method, params, extras)`
and pushes onto the engine's writer channel directly, bypassing
`callerRegistry` and `inFlight`. No pending promise registration. The
peer's reply (if any) flows through the normal decode path; with Item 8
in place, an unmatched-id reply enters the `Malformed(Present(id), ...)`
or `Skip` branch.

Role: id-present-but-unmatched fire-and-forget; supports patterns like
CDP dialog drain (sentinel id) without protocol-specific surface.
Source file: `kyo/JsonRpcEndpoint.scala`
Test file: `kyo/JsonRpcEndpointTest.scala`
Rationale: live recipe at
`concurrent-imagining-stroustrup/kyo-browser/.../CdpClient.scala:324-340`.

### Engine default-neutrality fix (touches `JsonRpcEndpoint.Config`)

The current `Config()` defaults at
`kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEndpoint.scala:62-72`
ship `cancellation = Present(CancellationPolicy.lsp)`, which is NOT
protocol-neutral. Change to:

```scala
final case class Config(
    codec:                 JsonRpcCodec              = JsonRpcCodec.Strict2_0,
    cancellation:          Maybe[CancellationPolicy] = Absent,
    progress:              Maybe[ProgressPolicy]     = Absent,
    unknownMethod:         UnknownMethodPolicy       = UnknownMethodPolicy.minimal,
    gate:                  Maybe[MessageGate]        = Absent,
    maxInFlight:           Maybe[Int]                = Absent,
    requestTimeout:        Duration                  = Duration.Infinity,
    idStrategy:            IdStrategy                = IdStrategy.SequentialLong,
    progressResetsTimeout: Boolean                   = false
)
```

`codec = Strict2_0` is already the protocol-neutral JSON-RPC 2.0 codec.
`progress = Absent` is already neutral. `unknownMethod = UnknownMethodPolicy.minimal`
is already neutral. Only the `cancellation` default needs to change.
Source file: `kyo/JsonRpcEndpoint.scala`
Test file: `kyo/JsonRpcEndpointTest.scala` (assert `Config().cancellation == Absent`)

This is engine work, not a Config preset; it lives in the same phase as
the existing `JsonRpcEndpoint` companion edits.

## Package surface verdicts (Rule 8a, MANDATORY)

NEW files under `kyo-jsonrpc/shared/src/main/scala/kyo/`:

- `WireTransport.scala`: PUBLIC. User-callable from
  `JsonRpcTransport.fromWire(wire, framer, codec)` and the byte-stream
  adapter ecosystem; preset entry point `WireTransport.empty` exists for
  tests. Marker: `// flow-allow: PUBLIC byte-level user-facing transport seam`.
- `Framer.scala`: PUBLIC. User-callable as `Framer.lineDelimited` and
  `Framer.contentLength` from `JsonRpcTransport.fromWire`. Marker:
  `// flow-allow: PUBLIC framer preset library for byte-stream transports`.

NEW files under `kyo-jsonrpc/jvm/src/main/scala/kyo/`:

- `JsonRpcTransportJvm.scala`: PUBLIC. User-callable as
  `JsonRpcTransport.unixDomain(path, codec, framer)` via `extension`-on-companion.
  Marker: `// flow-allow: PUBLIC JVM-only UDS transport extension`.

NEW files under `kyo-jsonrpc-http/shared/src/main/scala/kyo/` (new
subproject):

- `JsonRpcHttpTransport.scala`: PUBLIC. User-callable as
  `JsonRpcHttpTransport.webSocket(url, headers, codec)` (or
  `JsonRpcTransport.webSocket(...)` via extension). Marker:
  `// flow-allow: PUBLIC kyo-http-backed WebSocket transport`.

Count: 4 NEW public files. 0 new internal files. The Item 3 allocator
helper lives on the existing `kyo/internal/ProgressEngine.scala`,
already INTERNAL by location.

Existing-file additions (verdicts persist from the prior Rule 8 cleanup
commit; no new verdicts required):

- `kyo/JsonRpcEndpoint.scala`: gains `close(gracePeriod)`, `closeNow`,
  `sendUnmatched`, plus the `Config` cancellation-default change.
  Verdict PUBLIC.
- `kyo/JsonRpcEnvelope.scala`: `Malformed` gains optional id field.
  Verdict PUBLIC.
- `kyo/JsonRpcMethod.scala`: gains `dispatch`. Verdict PUBLIC.
- `kyo/CancellationPolicy.scala`: gains `decodeParams`. Verdict PUBLIC.
- `kyo/JsonRpcTransport.scala`: gains `stdio` and `fromWire`. Verdict
  PUBLIC.
- `kyo/internal/JsonRpcCodecImpl.scala`: codec decoders re-extract id
  on `Malformed`. Verdict INTERNAL.
- `kyo/internal/JsonRpcEndpointImpl.scala`: engine wiring for `close(gracePeriod)`,
  `sendUnmatched`, malformed-id branch routing, allocator helper call
  sites. Verdict INTERNAL.
- `kyo/internal/ProgressEngine.scala`: gains `allocateProgressToken`.
  Verdict INTERNAL.
- `kyo/internal/CancellationEngine.scala`: drops the method-name branch
  at `:19`; delegates to `policy.decodeParams`. Verdict INTERNAL.

The 15-file existing kyo-jsonrpc package layout (carried from the Rule 8
cleanup commit per the exploration's module map at
`01-exploration.md:13-39`) needs no new verdicts.

## Target-state semantics

### Item 3: progress-token uniqueness
- Two concurrent `callWithProgress` from any number of fibers never see
  the same token (modulo `maxAttempts` exhaustion; default 32).
- On `maxAttempts` exhaustion: `Abort.fail(JsonRpcError.internalError("progress token exhaustion"))`.
- Effect row: `Sync & Abort[JsonRpcError]`.
- Edge case: if `progressStreams` is at capacity, allocator still fails
  with `internalError`, not silently drops.
- Token is an opaque `Structure.Value`; the engine does not interpret
  its contents.

### Item 4: WebSocket transport
- Text frames decoded via `codec.decode`; envelopes emitted on
  `transport.incoming`.
- Binary frames dropped with a warn log.
- `Scope.ensure` registers WS-close on scope exit.
- Effect row: `Async & Scope & Abort[HttpException]`.

### Item 5: stdio transport
- One envelope per line under `Framer.lineDelimited`; empty lines
  skipped.
- EOF on stdin closes `incoming`.
- `send` writes one envelope per `Console.printLine`.
- Effect row: `Async & Scope`.

### Item 6: UDS transport
- JVM-only.
- Bind under `Scope.ensure` that deletes the socket file on close
  (matches `HookSocketServer.scala:31-54`).
- Accept-loop fiber yields per-connection envelopes onto `incoming`.
- Effect row: `Async & Scope`.

### Item 7: byte-stream seam
- `Framer.lineDelimited.parse` yields one `Chunk[Byte]` per LF-terminated
  segment; CR is stripped if preceding LF.
- `Framer.contentLength.parse` yields one chunk per Content-Length frame;
  header parse errors raise `Abort.fail(JsonRpcError.parseError(reason))`.
  Tolerant parse: accepts `\r\n\r\n` and `\n\n` header terminators
  (resolves Q-008 from v1 design). Strict emit: writes `\r\n\r\n` only.
- `Framer.frame` is the inverse of `parse`: lineDelimited appends `\n`;
  contentLength prepends `Content-Length: N\r\n\r\n`.
- `fromWire(wire, framer, codec)` composes the three layers.
- The framer presets are protocol-agnostic byte-level utilities; the
  consumer module selects which framer matches its wire.

### Item 8: tolerant fallback id
- `Malformed(Present(id), reason, raw)` iff the codec could recover an
  id from the raw record's `id` field.
- Engine `decodeCallback` fails the pending caller's promise with
  `JsonRpcError.invalidRequest("malformed response: " + reason)`.
- `Malformed(Absent, _, _)` retains Skip semantic.
- Invariant: any in-flight caller awaiting `id == k` cannot hang past
  the next received envelope whose raw `id` field decodes to `k`.

### Item 9: close(gracePeriod)
- `close(d)` runs `awaitDrain` under `Async.timeout(d)`, swallows the
  timeout, then runs `finalizer`.
- `close()` = `close(Duration.Zero)` = `closeNow`.
- Scope-managed endpoint always force-closes on scope exit (no grace).
- Effect row: `Async`.

### Item 10: CancellationPolicy.decodeParams
- `lsp.decodeParams` extracts `params.id` and decodes via the existing
  LspCancelParams shape.
- `mcp.decodeParams` extracts `params.requestId` and decodes via
  McpCancelParams.
- Third-party policies supply their own decoder.
- Effect row: `Sync`.
- Invariant: no `cancelMethod ==` literal survives in
  `CancellationEngine.scala`.

### Item 12: JsonRpcMethod.dispatch
- Unknown method: returns `Absent`.
- Known notification: returns `Present(Structure.Value.Null)` after the
  handler completes.
- Known request: returns `Present(handler-result)`.
- Effect row: `S & Abort[JsonRpcError]`.

### Item 13: sendUnmatched
- Encodes as request shape (id present), pushes onto writer without
  registering a pending promise.
- Peer reply (if any) routes through Item 8's Malformed-with-id or Skip
  branches.
- Effect row: `Async & Abort[Closed]`.

### Engine default-neutrality fix
- `Config().cancellation == Absent`.
- `Config().codec == JsonRpcCodec.Strict2_0` (unchanged).
- `Config().progress == Absent` (unchanged).
- `Config().unknownMethod == UnknownMethodPolicy.minimal` (unchanged).
- Invariant: no `Config()` no-arg call on the user side produces an
  endpoint that knows any specific protocol's cancellation, progress, or
  meta semantics.

## Cross-phase invariants (candidates)

- INV-001: `JsonRpcEnvelope.Malformed` carries `id: Maybe[JsonRpcId]`
  and the codec attempts id re-extraction before construction.
  produced_by: Phase covering Item 8.
  consumed_by: Phase covering Item 13 (sendUnmatched relies on the
  Malformed-with-id branch routing peer replies).

- INV-002: `Config()` no-arg default is protocol-neutral
  (`cancellation = Absent`, `codec = Strict2_0`, `progress = Absent`,
  `unknownMethod = UnknownMethodPolicy.minimal`).
  produced_by: Phase covering the engine default-neutrality fix.
  consumed_by: Plan-as-contract validation (every Cdp / LSP / MCP
  scenario in tests explicitly opts in via `Config().copy(...)`).

- INV-003: `CancellationPolicy.decodeParams` is the SINGLE source of
  cancel-id decoding; no method-name branch survives in
  `CancellationEngine`.
  produced_by: Phase covering Item 10.
  consumed_by: Plan-as-contract validation (grep for `cancelMethod ==`
  in `CancellationEngine.scala` returns zero matches after Item 10).

- INV-004: Byte-stream transports (Items 5, 6) and envelope-stream
  transports (Item 4, in-memory) share `JsonRpcTransport` as the
  user-facing seam. Byte-stream paths route through
  `fromWire(wire, framer, codec)`; envelope-stream paths construct
  `JsonRpcTransport` directly.
  produced_by: Phase covering Item 7.
  consumed_by: Phases covering Items 5, 6 (stdio, unixDomain).

- INV-005: `close(d)` is the only user-facing teardown; `Scope`
  finalizer force-closes (no grace).
  produced_by: Phase covering Item 9.
  consumed_by: Plan-as-contract validation (every public close path
  goes through `close(d)`).

- INV-006: New public files all carry `// flow-allow: PUBLIC <reason>`
  markers per the Rule 8a verdict list.
  produced_by: Every new-file phase.
  consumed_by: flow-verify's `package-surface` catalog at commit time.

- INV-007: Every new source file ships in the same commit as its paired
  test file (Rule 8c HARD).
  produced_by: Every new-file phase.
  consumed_by: flow-verify's `rule-8c` catalog at commit time.

Count: 7 invariants (down from 9 in v1, matching the 10-item scope).

## Rejected alternatives

### Items removed from engine scope (live in consumer modules)

- Item 1 (`Config.cdp` preset): REJECTED for engine. Lives in
  `kyo-cdp` as a `Config` preset (e.g. `KyoCdp.config: JsonRpcEndpoint.Config`)
  that sets `codec = JsonRpcCodec.Cdp`, `cancellation = Absent`,
  `idStrategy = IdStrategy.SequentialInt`, `maxInFlight = Present(8)`.
  The engine does not know what CDP is; bundling a CDP-named preset on
  `JsonRpcEndpoint.Config` would couple the engine to a specific
  protocol. The engine work that DOES belong here is the default-neutrality
  fix above, which removes the LSP cancellation from `Config()` so a
  naive CDP user no longer inherits LSP semantics.

- Item 2 (LSP `partialResultToken` stamping): REJECTED for engine,
  NO-OP at engine level. The existing
  `ProgressPolicy.extractRequestToken` and `stampOutboundToken`
  callbacks at `kyo-jsonrpc/shared/src/main/scala/kyo/ProgressPolicy.scala:13-14`
  already take and return `Structure.Value`, opaque to wire field names.
  A future `kyo-lsp` ships an enriched `ProgressPolicy.lsp` variant
  (or a sibling `ProgressPolicy.lspPartialResults` preset) that stamps
  `partialResultToken` instead of `workDoneToken`. The engine's
  `callPartialResults` path can route through either variant by
  selecting policy at endpoint construction; no new engine field is
  needed because the callback shape already encodes the wire-name
  choice. Confirmed by reading `ProgressPolicy.scala` (the `lsp` preset
  at `:38-46` hardcodes `workDoneToken`; a partial-result variant is a
  preset substitution, not a new field on the policy record).

- Item 11 (per-sessionId notification dispatch): REJECTED for engine.
  Lives in `kyo-cdp`. The engine's existing `ExtrasEncoder` plus the
  Cdp codec's extras population at `JsonRpcCodecImpl.scala:217` and
  `endpoint.notify(method, params, extras)` already give per-session
  routing; a CDP consumer module wires the sid -> handler map on top
  using `HandlerCtx.extras`. The `scopedNotification` builder is a
  CDP-specific routing convenience that belongs in `kyo-cdp`.

- Item 14 (null-id response semantic): REJECTED for engine. Lives in
  `kyo-mcp`. Item 8 (Malformed-with-id) handles the generic malformed-envelope
  case at engine level; the symmetric `respondToMalformed` flag plus
  the `Maybe[JsonRpcId]` field on `JsonRpcEnvelope.Response` are
  MCP-bundle concerns (MCP spec mandates the null-id reply; CDP and
  bare JSON-RPC 2.0 do not). MCP wires the response synthesis on top
  of Item 8's branch.

- Item 15 (`MetaPolicy` for `_meta`): REJECTED for engine. Lives in
  `kyo-mcp`. The existing `ExtrasEncoder` at
  `kyo-jsonrpc/shared/src/main/scala/kyo/ExtrasEncoder.scala:4` already
  provides arbitrary per-request extras; `kyo-mcp` builds the reserved-prefix
  enforcement on top via a custom `ExtrasEncoder` plus a
  `HandlerCtx.extras` reader pattern. A `MetaPolicy`-named record is an
  MCP wire-shape; it does not belong on the engine.

- Item 16 (`JsonSchema2020_12.encode[A]`): REJECTED for engine. Lives
  in `kyo-mcp` (or stays as a `kyo-schema` consumer call). kyo-schema
  already ships JSON Schema Draft 2020-12 emission per
  `01-exploration.md` Q-006; the wrapper that emits the `$schema`
  envelope is an MCP tool-surface concern and belongs in `kyo-mcp`.

- Item 17 (`emitProgress` / `$/progress` shape): REJECTED for engine,
  NO-OP at engine level. The existing
  `endpoint.notify(method, params, extras)` at
  `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEndpoint.scala:16-21`
  plus a `ProgressPolicy` configured on the endpoint already let a
  future `kyo-lsp` emit `$/progress` notifications with policy-shaped
  params (server picks `policy.progressMethod` and stamps via
  `policy.encodeProgressParams`). The engine's notify path
  (`internal/JsonRpcEndpointImpl.scala:339-350`) is the seam; no
  symmetric `emitProgress` API is needed. Confirmed by reading the
  notify impl: it encodes `JsonRpcEnvelope.Notification(method, params, extras)`
  and pushes to the writer channel.

### Design alternatives considered and rejected

- Q-001 alternative (depend on `kyo-http` directly from kyo-jsonrpc):
  REJECTED. The consumer set is mixed (stdio-only, WS-only, Content-Length-stdio-only);
  forcing the kyo-http dep on every consumer is overreach. The new
  subproject `kyo-jsonrpc-http` mirrors `kyo-caliban`'s `dependsOn(kyo-http)`
  shape without pulling kyo-http into the core dep graph.

- `Config.closeGracePeriod` field alternative: REJECTED because it ties
  grace to construction time rather than teardown decision. The user
  who wants drain-on-scope-exit can wrap `Scope.run` with their own
  `close(d)` call before scope ends. Citation:
  `kyo-core/shared/src/main/scala/kyo/Scope.scala:86-91` `acquireRelease`
  takes a release function, not a duration.

- Public `JsonRpcMethod.handle` alternative (drop `private[kyo]`):
  REJECTED. The engine call sites at `JsonRpcEndpointImpl.scala:905, :992`
  are the only existing consumers, and `dispatch` is the safer reach-in
  that builds methodMap once. Keeps `handle` `private[kyo]`.

- Per-sessionId routing via native methodMap keyed by `(method, sessionId)`:
  REJECTED per CDP-vs-kyo-browser BACKPORT #5 option (a): the
  extras-based recipe in `kyo-cdp` is lower-risk and preserves the
  existing single-key methodMap shape on the engine.

## Open questions

(target: 0; achieved: 0)

None. Every value-underdetermined fork from v1 is resolved in this
design:

- Q-001 (subproject placement for WebSocket transport): resolved
  in-place. The new `kyo-jsonrpc-http` subproject hosts the kyo-http-
  backed adapter. See Item 4 and the rejected alternative.
- Q-008 (Framer.contentLength strictness): resolved in-place. Tolerant
  parse (accepts `\r\n\r\n` and `\n\n`), strict emit (writes `\r\n\r\n`
  only). See Item 7 target-state semantics.

## Validation hooks for flow-validate

- Public API signatures in `## API surface` are the contract; each
  signature must appear verbatim in the impl phase.
- Cross-phase invariants in `## Cross-phase invariants (candidates)`
  feed `flow-invariants`'s ledger (INV-001 through INV-007).
- `## Open questions` is empty; no input to `flow-resolve-open`.
- `## Package surface verdicts` covers every NEW file the campaign
  introduces (4 PUBLIC, 0 INTERNAL); flow-validate confirms the list
  matches the eventual disk tree and every PUBLIC file gains its
  `// flow-allow: PUBLIC ...` marker.
- Rule 8c HARD: every source file in `## API surface` carries a
  matching test file; flow-validate confirms 1:1 source-test pairing
  per commit.
- Engine-neutrality grep contract: after the default-neutrality fix
  phase, `grep -n "cancellation: Maybe\[CancellationPolicy\] = Present" kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEndpoint.scala`
  must return zero matches.
- Protocol-name grep contract: after the campaign, the engine source
  tree (`kyo-jsonrpc/{shared,jvm}/src/main/scala/kyo/**`) must not
  introduce any new type whose name starts with `Lsp`, `Mcp`, `Cdp`,
  `MetaPolicy`, or `JsonSchema2020`. Existing protocol-named items
  inside `CancellationPolicy.lsp` / `.mcp` and `ProgressPolicy.lsp` /
  `.mcp` are presets, not types, and remain in place.
