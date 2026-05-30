# D2. Fork 2: `Framer` / `WireTransport`, prefix now or hold for kyo-net?

Source-grounded resolution of Fork 2 from `C-cleanup-plan.md §11` and `A4-naming-and-nesting.md §12 medium 8`. Every claim cites `file:line` or `memory:line`.

## 1. What the types model

### `Framer` (`kyo-jsonrpc/shared/src/main/scala/kyo/Framer.scala:7`)

A `trait Framer` with two methods (`Framer.scala:8-9`):

- `frame(bytes: Chunk[Byte]): Chunk[Byte] < Sync`, wraps an outbound payload in a transport-level envelope.
- `parse(stream: Stream[Chunk[Byte], Async & Abort[Closed]]): Stream[Chunk[Byte], ...]`, splits an inbound byte stream into discrete payloads.

The companion ships two presets, both byte-level concepts with no JSON-RPC dependency in their bodies:

- `Framer.lineDelimited` (`Framer.scala:17-22`): LF-terminated segments with CR stripping, empty-line skipping, EOF closes without flushing. This is the framing LSP servers use over stdio.
- `Framer.contentLength` (`Framer.scala:28-36`): `Content-Length: N\r\n\r\n<N bytes>` envelopes. This is the framing LSP/DAP/MCP use for binary-safe transport.

The trait body uses only `Chunk[Byte]`, `Stream`, `Sync`, `Async`, `Abort[Closed]`, `Frame`. There is no `JsonRpcEnvelope`, no `JsonRpcCodec`, no `JsonRpcError` in either the trait or its presets. The single backward dependency on `JsonRpcError.parseError(...)` lives inside `internal.FramerImpl` (`Framer.scala:22, 36` route to `internal.FramerImpl.parseLineDelimited` / `parseContentLength`), and that coupling is implementation-side, not interface-side. Structurally, `Framer` is a generic byte-stream chunking abstraction reusable by anyone who needs message-delimited byte transport.

### `WireTransport` (`kyo-jsonrpc/shared/src/main/scala/kyo/WireTransport.scala:6`)

A `trait WireTransport` with three methods (`WireTransport.scala:7-9`):

- `send(bytes: Chunk[Byte]): Unit < (Async & Abort[Closed])`
- `incoming: Stream[Chunk[Byte], Async & Abort[Closed]]`
- `close: Unit < Async`

Companion ships `WireTransport.empty` (`WireTransport.scala:14`), a tests-only no-op. The trait body has zero JSON-RPC vocabulary. It is a verbatim duck-typed byte send/incoming/close seam.

### How `JsonRpcTransport` uses them

`JsonRpcTransport.fromWire(wire: WireTransport, framer: Framer, codec: JsonRpcCodec = ...)` (`JsonRpcTransport.scala:37-42`) is the lift-from-bytes constructor. `JsonRpcTransport.stdio(framer: Framer = Framer.lineDelimited, codec: JsonRpcCodec = ...)` (`JsonRpcTransport.scala:47-53`) defaults the framer. `JsonRpcTransportJvm.unixDomain(sockPath, framer: Framer = Framer.lineDelimited, codec: JsonRpcCodec = ...)` (`JsonRpcTransportJvm.scala:17-19`) is the JVM UDS form. Both `Framer` and `WireTransport` are PUBLIC user-facing extension points: callers may implement `WireTransport` for an exotic transport (named pipe, message queue) and pass it through `fromWire`. The `Framer` presets are user-selectable for LSP-style `contentLength` framing.

Conclusion: both types are **structurally protocol-agnostic byte-stream abstractions**. Nothing in their public interface couples them to JSON-RPC.

## 2. kyo-net extraction status

`MEMORY.md:7` records the active worktree `cheerful-splashing-manatee` as the home of the kyo-net extraction from kyo-http. Verified on disk: `/Users/fwbrasil/workspace/kyo/.claude/worktrees/cheerful-splashing-manatee/kyo-net/shared/src/main/scala/kyo/net/` contains 6 public top-levels: `Transport`, `Connection`, `TransportConfig`, `NetAddress`, `NetPlatform`, `NetTlsConfig`. The package is `package kyo.net` (verified at `kyo-net/shared/src/main/scala/kyo/net/Transport.scala:1`, `Connection.scala:1`), NOT `package kyo`. Internal layout: `kyo.net.internal.{transport, client, util}` mirroring kyo-http's structure.

`feedback_no_backcompat.md:10` shows kyo-net is already a live concern: the user rejected backcompat aliases `HttpAddress = NetAddress`, `HttpTlsConfig = NetTlsConfig`, `HttpTransportConfig = TransportConfig` during the kyo-net Phase 0 extraction. This means (a) kyo-net IS real and progressing in `cheerful-splashing-manatee`, (b) the public API uses unprefixed names like `Transport` and `Connection` under `kyo.net.*`, (c) the user's stance on cross-module renames is "replace outright, no aliases".

`MEMORY.md:85` references `kyo-net` `ConnectionPool` as already shipping (consumed by kyo-sql via `AllowUnsafe`). `feedback_no_scope_cuts.md:15` confirms kyo-net is the active extraction target for shared transport primitives.

A key fact: kyo-net does NOT currently define a `Framer` or `WireTransport`. Verified: `grep -rn "Framer\|WireTransport" .../cheerful-splashing-manatee/kyo-net/` returns zero hits. kyo-net's abstraction is `Connection` (a connected byte-stream socket), not `WireTransport` (a generic byte send/incoming pair). They are not the same concept: `Connection` is the platform-level socket, `WireTransport` is the protocol-layer adapter on top.

Conclusion: kyo-net is real, active, and progressing. But it does not own these two types today, and there is no public roadmap entry making it do so. Calling kyo-net "imminent" for `Framer`/`WireTransport` specifically would be speculation.

## 3. kyo-http precedent

Verified on disk: `grep "Framer\|WireTransport"` against `kyo-http/shared/src/main/scala/kyo/` returns zero results. kyo-http has no public `HttpFramer`, no public `HttpWire*`. Its frame-level concerns are buried in `internal.http1` (the wire parser) and `HttpWebSocket.Payload` (`HttpWebSocket.scala:31-32`), which is the WebSocket frame ADT and an internal companion type.

The kyo-http template treats framing as a protocol-internal concern: the public API exposes message-level types (`HttpRequest`, `HttpResponse`, `HttpWebSocket`), not framing primitives. If a user needs custom framing, they implement a new transport at the `kyo.net.Transport` level, not at an `HttpFramer` level.

Two implications for kyo-jsonrpc:

1. The "kyo-http precedent" for prefix discipline (`Http*` on every module-specific public type, `A1 §1`) applies to `Framer` and `WireTransport` only if we keep them public-and-module-owned. The precedent does NOT recommend keeping these types public at all; if kyo-jsonrpc followed kyo-http verbatim, framing would move to `internal.framing` and `WireTransport` would be replaced by `kyo.net.Connection`-shaped consumption.

2. The kyo-http template's stronger lesson is "do not own generic byte-stream primitives at the protocol-module level". Both `Framer` and `WireTransport` are protocol-agnostic. Long-term, both belong in `kyo-net` or in `internal.framing`/`internal.transport`. The short-term question is whether to prefix them now or wait.

## 4. Name-squatting risk

`kyo-jsonrpc`'s `package kyo` namespace is shared with every other Kyo module. Today `kyo.Framer` and `kyo.WireTransport` occupy slots that another module may legitimately need. The risk vectors:

1. kyo-net could add a `kyo.net.Framer`. That would NOT collide (different package), but it would create two `Framer` types with overlapping intent, forcing callers to disambiguate via import alias.
2. A future kyo-grpc or kyo-thrift module would face the same question. If it lands as `kyo.Framer` (no module prefix, mirroring today's kyo-jsonrpc), the top-level slot is already occupied. If it lands prefixed (`kyo.GrpcFramer`), it visually contradicts the precedent set by `kyo.Framer`.
3. kyo-browser currently has no `Framer`/`WireTransport` reference (`A3 §2.2, §2.7`, verified by `grep -rn "Framer\|WireTransport" .../kyo-browser/`), so today's name occupation is harmless. The harm accrues when a sibling module wants the slot.

The kyo-http template (`A1 §1`) and the user's "no aliases" stance (`feedback_no_backcompat.md`) together mandate: every module-owned public type carries the module prefix. The unprefixed `Framer`/`WireTransport` violate that rule.

## 5. Migration cost now vs later

**Rename now (Phase 5 of `C-cleanup-plan.md §12`)**:

- 2 source files renamed (`Framer.scala` → `JsonRpcFramer.scala`, `WireTransport.scala` → `JsonRpcWireTransport.scala`).
- ~15 reference updates inside kyo-jsonrpc: `JsonRpcTransport.scala:38, 39, 48`; `JsonRpcTransportJvm.scala:14, 18, 39, 41`; `internal/framing/FramerImpl.scala` (whatever Framer references it makes after Phase 1 reorg); `internal/transport/WireTransportAdapter.scala`; `internal/transport/StdioWireTransport.scala`.
- 2 test files renamed (`FramerTest.scala` → `JsonRpcFramerTest.scala`; `WireTransportTest.scala` → `JsonRpcWireTransportTest.scala`).
- Zero kyo-browser updates: kyo-browser does not reference either type (verified via grep, §4).
- Zero kyo-http updates: kyo-http does not reference either type (verified via grep, §3).
- Total: ~30 line changes plus 4 file renames. Mechanical.

**Hold for kyo-net** (defer until extraction):

- If kyo-net later absorbs framing as `kyo.net.Framer`, the move is a cross-module migration touching the same ~15 sites in kyo-jsonrpc plus new kyo-net package routing. The work is comparable in mechanical cost regardless of whether the rename happens first.
- The "hold" cost is the continued occupation of `kyo.Framer` and `kyo.WireTransport` slots until kyo-net extracts them. During that window, every reader of `kyo-jsonrpc` sees a precedent that contradicts the kyo-http prefix discipline.
- There is no concrete trigger date for kyo-net to absorb framing. kyo-net's current scope (`kyo-net/shared/src/main/scala/kyo/net/`) is socket-level (`Transport`, `Connection`), not framing-level. Adding framing to kyo-net is a separate design discussion.

The "rename now" cost is bounded and one-shot. The "hold" cost is open-ended namespace squatting with no scheduled resolution.

## 6. Decision per type

### `Framer`

**VERDICT**: **RENAME-WITH-PREFIX-NOW** to `JsonRpcFramer`.

**Rationale**:

1. Current shape: protocol-agnostic byte-chunking trait (`Framer.scala:7-10`), referenced from three public signatures (`JsonRpcTransport.scala:39, 48`, `JsonRpcTransportJvm.scala:18, 39, 41`). It IS a real extension point users select between via `lineDelimited` vs `contentLength`. Not unused; not a candidate for `MOVE-TO-INTERNAL`.
2. kyo-net realism: kyo-net exists in `cheerful-splashing-manatee` (`MEMORY.md:7`) but has no `Framer` today and no committed plan to add one. kyo-net's abstraction is `Connection` (socket-level), not `Framer` (protocol-framing-level). Calling kyo-net the future home of `Framer` is speculation; honour the current kyo-jsonrpc module boundary.
3. Name-squatting risk: keeping `kyo.Framer` unprefixed reserves a top-level slot for a JSON-RPC-specific type. Any future protocol module (`kyo-grpc`, `kyo-thrift`, hypothetical `kyo-amqp`) would face the same naming question; the precedent of an unprefixed `kyo.Framer` would force them to either contradict their own module prefix or alias-import.
4. kyo-http template: `A1 §1` documents the rule "every module-specific public type carries the module prefix". kyo-http has no public framer type, but it does have `HttpCodec`, `HttpFormCodec`, `HttpTransportConfig`, all prefixed. The rule is general, not contingent on kyo-http having a framer.
5. Migration cost: rename costs ~15 line changes plus a file rename, all inside kyo-jsonrpc (`§5`). If kyo-net later absorbs framing, the migration from `JsonRpcFramer` to `kyo.net.Framer` (or `kyo.net.Framing`) is the same mechanical sweep, with the added cost of cross-module package routing, identical whether the type was prefixed first or not.

**Concrete action** (Phase 5 of `C-cleanup-plan.md §12`):

- `git mv kyo-jsonrpc/shared/src/main/scala/kyo/Framer.scala kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcFramer.scala`
- In-file replacement: `trait Framer` → `trait JsonRpcFramer`, `object Framer` → `object JsonRpcFramer`, all `: Framer` type ascriptions → `: JsonRpcFramer`.
- Sed sweep across kyo-jsonrpc: `Framer\b` → `JsonRpcFramer` in `JsonRpcTransport.scala`, `JsonRpcTransportJvm.scala`, `internal/framing/FramerImpl.scala`, `internal/transport/WireTransportAdapter.scala`, `internal/transport/StdioWireTransport.scala`, `FramerTest.scala` → `JsonRpcFramerTest.scala`.
- Re-evaluation trigger: if/when kyo-net opens an RFC to absorb framing primitives, revisit the rename to either `kyo.net.Framing` or keep `JsonRpcFramer` as a JSON-RPC-specific subtype.

### `WireTransport`

**VERDICT**: **RENAME-WITH-PREFIX-NOW** to `JsonRpcWireTransport`.

**Rationale**:

1. Current shape: protocol-agnostic byte send/incoming/close trait (`WireTransport.scala:6-10`), referenced from the public `JsonRpcTransport.fromWire(wire: WireTransport, ...)` signature (`JsonRpcTransport.scala:38`) and used as the implementation base of `internal.StdioWireTransport`, `internal.UdsWireTransport`, `internal.WireTransportAdapter`. It IS a real user-implementable extension point: a caller could implement `WireTransport` for a named-pipe or message-queue transport and pass it through `fromWire`. Not unused.
2. kyo-net realism: kyo-net's `Connection` (`kyo-net/shared/src/main/scala/kyo/net/Connection.scala:1`) is the closest analogue, but `Connection` exposes a connected-socket API (read/write byte chunks with backpressure), whereas `WireTransport` is a thinner abstraction (send, stream of incoming, close) deliberately decoupled from connection lifecycle. `WireTransport` could be retrofitted as a thin wrapper over `kyo.net.Connection`, but that retrofit is a separate design task. No public commitment to that retrofit exists in the cheerful-splashing-manatee worktree (verified via grep: zero `WireTransport` references in kyo-net).
3. Name-squatting risk: `kyo.WireTransport` occupies a top-level slot in the `kyo` package. Any future module needing a generic byte-stream wire abstraction faces a collision. Even kyo-net itself might want `kyo.net.WireTransport` as a name; that would be fine package-wise but visually overlapping in user code.
4. kyo-http template: kyo-http has no `WireTransport` (verified via grep). Its transport abstraction is `internal.transport.Transport` (package-private). The closest public analogue is `HttpRawConnection` (`HttpRawConnection.scala:11`), which is heavily prefixed. The rule "prefix module-owned public types" applies.
5. Migration cost: rename costs ~10 line changes plus a file rename, all inside kyo-jsonrpc (`§5`). Identical structure to `Framer`. If kyo-net later absorbs the wire abstraction, the rename `JsonRpcWireTransport` → `kyo.net.X` is the same mechanical sweep.

**Concrete action** (Phase 5 of `C-cleanup-plan.md §12`):

- `git mv kyo-jsonrpc/shared/src/main/scala/kyo/WireTransport.scala kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcWireTransport.scala`
- In-file replacement: `trait WireTransport` → `trait JsonRpcWireTransport`, `object WireTransport` → `object JsonRpcWireTransport`.
- Sed sweep across kyo-jsonrpc: `WireTransport\b` → `JsonRpcWireTransport` in `JsonRpcTransport.scala:38`, `JsonRpcTransportJvm.scala` (verify if it references `WireTransport` directly; it references `UdsWireTransport` which is internal and stays), `internal/transport/WireTransportAdapter.scala`, `internal/transport/StdioWireTransport.scala`, `WireTransportTest.scala` → `JsonRpcWireTransportTest.scala`.
- Re-evaluation trigger: if kyo-net adds a `Connection`-shaped public wire abstraction, replace `JsonRpcWireTransport` with `kyo.net.Connection` (or an adapter type) in a separate cross-module campaign.

## 7. Executive summary

Recommend **RENAME-WITH-PREFIX-NOW** for both `Framer` and `WireTransport`. Concretely: `kyo.Framer` → `kyo.JsonRpcFramer`, `kyo.WireTransport` → `kyo.JsonRpcWireTransport`. Execute as Phase 5 of `C-cleanup-plan.md §12`.

Justification distilled:

- Both types are real, user-touched extension points; they cannot move to internal (`Framer` selectable presets, `WireTransport` implementable trait).
- kyo-net exists in `cheerful-splashing-manatee` (`MEMORY.md:7`) but does not own framing or wire abstractions today, and has no scheduled RFC to absorb them.
- The unprefixed slots `kyo.Framer` and `kyo.WireTransport` squat on the shared `kyo` package, contradicting the kyo-http template's prefix discipline (`A1 §1`) and the user's "no aliases / no backcompat" stance (`feedback_no_backcompat.md:10`).
- Rename cost is bounded (~30 lines, 4 file renames, zero cross-module updates); hold cost is open-ended namespace occupation with no scheduled resolution.
- If kyo-net later extracts framing or wire primitives, the migration from `JsonRpc<X>` to `kyo.net.<Y>` is the same mechanical sweep we would do today, so the rename does not foreclose any future move.

Proceed with Phase 5 as planned in `C-cleanup-plan.md §12`.
