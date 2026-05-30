# Phase 01 Decisions

## Files moved (Task A)

13 files moved via `git mv` (12 shared + 1 JVM):

| File | New subpackage |
|---|---|
| CancellationEngine.scala | engine |
| FramerImpl.scala | framing |
| IdStrategyEngine.scala | engine |
| InMemoryTransport.scala | transport |
| JsonRpcCodecImpl.scala | codec |
| JsonRpcEndpointImpl.scala | engine |
| JsonRpcRequest.scala | codec |
| ProgressEngine.scala | engine |
| RateLimitEngine.scala | engine |
| RawJsonParser.scala | codec |
| StdioWireTransport.scala | transport |
| WireTransportAdapter.scala | transport |
| jvm/internal/UdsWireTransport.scala | transport |

New directories created: `internal/codec`, `internal/transport`, `internal/framing`, `internal/engine` (shared); `jvm/internal/transport`.

## Package declaration changes

Files that had `package kyo \n package internal` (chained form) were converted to single-line `package kyo.internal.<sub>`. These files (CancellationEngine, JsonRpcEndpointImpl, ProgressEngine, RateLimitEngine) required adding `import kyo.*` since the chained form previously gave them `kyo` scope automatically.

## Public reference updates

7 references in public source files updated to use fully-qualified subpackage paths:
- `JsonRpcEndpoint.scala`: 2 refs to `internal.engine.JsonRpcEndpointImpl`
- `JsonRpcTransport.scala`: 3 refs to `internal.transport.{InMemoryTransport, WireTransportAdapter, StdioWireTransport}`
- `JsonRpcCodec.scala`: 2 refs to `internal.codec.JsonRpcCodecImpl`
- `Framer.scala`: 2 refs to `internal.framing.FramerImpl`
- `JsonRpcTransportJvm.scala`: 1 ref to `internal.transport.UdsWireTransport`

## Cross-subpackage import added

`WireTransportAdapter.scala` (`transport` subpackage) references `RawJsonParser` (`codec` subpackage). Added explicit `import kyo.internal.codec.RawJsonParser`.

## Banner-comment count removed (Task B)

17 banner comment violations cleared (0 remaining per `flow-verify-organization.sh`). The plan anticipated 7, but all 17 `// PUBLIC ...` line-1 comments across the `kyo/` package were stripped.

## Scaladoc additions (Task C)

New scaladoc added to 19 public types that had none or only a banner comment:
- Tier A: JsonRpcEndpoint, JsonRpcTransport, JsonRpcMethod, JsonRpcError, JsonRpcEnvelope, JsonRpcCodec
- Tier A nested: JsonRpcEndpoint.Pending, JsonRpcEndpoint.Config
- Tier B: JsonRpcId, UnknownMethodPolicy, HandlerCtx, ExtrasEncoder, JsonRpcResponse, Framer, WireTransport, MessageGate, ProgressPolicy, IdStrategy, CancellationPolicy

## Test import updates

5 test files required import path updates:
- `IdStrategyTest.scala:3`: `kyo.internal.IdStrategyEngine` -> `kyo.internal.engine.IdStrategyEngine`
- `ProgressPolicyTest.scala:441-442`: `internal.ProgressEngine.allocateProgressToken` -> `internal.engine.ProgressEngine.allocateProgressToken`
- `JsonRpcTransportTest.scala:88`: `internal.RawJsonParser.parse` -> `internal.codec.RawJsonParser.parse`
- `CancellationPolicyTest.scala:648`: `internal.CancellationEngine.extractCancelIdForTest` -> `internal.engine.CancellationEngine.extractCancelIdForTest`
- `WireTransportTest.scala:33-34`: `new internal.WireTransportAdapter` -> `new internal.transport.WireTransportAdapter`

## Convention sweep findings

- em-dash: 0 hits in changed files
- AllowUnsafe: no new occurrences
- Option-vs-Maybe: no new Option in public APIs
- semicolons: 0 code-line hits (3 comment-line hits are pre-existing)
- asInstanceOf: no new casts
- default-params: no new defaults added
- Frame.internal: no use
- java.util.concurrent: no new imports (existing ConcurrentHashMap imports unchanged)
- llm-tells: 0 hits in new scaladoc

## Deviations from plan

1. Plan said UdsWireTransport move is NOT part of Phase 01 (only the 12 shared files). However, it was listed in `files_modified` and the design 02 table included it. The move was performed to keep the JVM transport reference in `JsonRpcTransportJvm.scala` working after we updated it from `internal.UdsWireTransport` to `internal.transport.UdsWireTransport`. Deviation is plan-aligned per the YAML `files_modified` list.

2. 17 banners cleared vs the 7 specifically called out in the task description. All 17 were `// PUBLIC` line-1 comments; clearing all of them is correct and leaves 0 violations.

3. The verification command `sbt 'kyo-jsonrpcJVM/Test/compile'` in the plan uses a project name that does not exist. The actual project is `kyo-jsonrpc` (JVM is the default). Used `kyo-jsonrpc/Test/compile` which succeeds.
