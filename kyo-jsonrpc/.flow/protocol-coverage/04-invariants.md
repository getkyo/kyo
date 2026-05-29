# Cross-phase invariants ledger

Source: 02-design.md `## Cross-phase invariants (candidates)` (INV-001 through INV-007).
Task type: refactor with new-feature seams. The ledger is non-empty; the
five-phase clustering below is the producer / consumer wiring the
flow-validate gate enforces. Phase clustering matches the v2 ten-item
scope per steering.md `## Design v1 -> v2 directive`.

## Phase index

- Phase 01: Engine refactor plus Config neutrality (design Items 8, 9, 10
  plus the `Config()` no-arg default flip). Touches
  `JsonRpcEnvelope.Malformed` for tolerant fallback id extraction,
  `JsonRpcEndpoint.close(gracePeriod)`, `CancellationPolicy.decodeParams`
  (decoder out of `CancellationEngine`), and the
  `Config().cancellation = Absent` default change.
- Phase 02: API additions (design Items 3, 12, 13). Adds the generic
  `ProgressEngine.allocateProgressToken` putIfAbsent helper, public
  `JsonRpcMethod.dispatch`, and `endpoint.sendUnmatched`.
- Phase 03: Wire transport seam plus stdio (design Items 7, 5). Adds
  `WireTransport`, `Framer` (with `lineDelimited` and `contentLength`
  presets), `JsonRpcTransport.fromWire`, and `JsonRpcTransport.stdio`.
- Phase 04: JVM `unixDomain` transport (design Item 6). Adds
  `JsonRpcTransport.unixDomain` via `extension`-on-companion on the JVM
  axis only.
- Phase 05: `kyo-jsonrpc-http` subproject plus `webSocket` transport
  (design Item 4). New `kyo-jsonrpc-http` sbt subproject; adds
  `JsonRpcHttpTransport.webSocket` bridging `HttpWebSocket` to
  `JsonRpcTransport`.

## Invariants

INV-001: JsonRpcEnvelope.Malformed carries id Maybe[JsonRpcId] and the codec attempts id re-extraction before constructing the case
  produced_by: Phase 01
  consumed_by: Phase 02
  smoke_test_path: kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcCodecTest.scala::INV-001

INV-002: Config() no-arg default is protocol-neutral (cancellation Absent, codec Strict2_0, progress Absent, unknownMethod minimal)
  produced_by: Phase 01
  consumed_by: (none yet)
  smoke_test_path: kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcEndpointTest.scala::INV-002

INV-003: CancellationPolicy.decodeParams is the single source of cancel-id decoding and CancellationEngine carries no method-name fork
  produced_by: Phase 01
  consumed_by: (none yet)
  smoke_test_path: scripts/flow-verify-grep.sh::no-cancelMethod-fork

INV-004: Byte-stream transports route through JsonRpcTransport.fromWire(wire, framer, codec) while envelope-stream transports construct JsonRpcTransport directly
  produced_by: Phase 03
  consumed_by: Phase 04
  smoke_test_path: kyo-jsonrpc/shared/src/test/scala/kyo/WireTransportTest.scala::INV-004

INV-005: close(d) is the only user-facing teardown and Scope finalizer force-closes with no grace
  produced_by: Phase 01
  consumed_by: (none yet)
  smoke_test_path: kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcEndpointTest.scala::INV-005

INV-006: Every new public file carries a `// flow-allow: PUBLIC <reason>` marker per the Rule 8a verdict list in 02-design.md
  produced_by: Phase 03
  consumed_by: Phase 04, Phase 05
  smoke_test_path: scripts/flow-verify-grep.sh::package-surface-markers

INV-007: Every new source file ships in the same commit as its paired test file (Rule 8c HARD)
  produced_by: Phase 03
  consumed_by: Phase 04, Phase 05
  smoke_test_path: scripts/flow-verify-grep.sh::rule-8c-pairing
