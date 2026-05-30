# Phase 06 Decisions: Cross-platform UDS fold-in

## Changes made

### Deleted
- `kyo-jsonrpc/jvm/src/main/scala/kyo/JsonRpcTransportJvm.scala` — JVM-only object with `unixDomain` factory and extension methods on `JsonRpcTransport`. Factories moved to shared companion; extension methods dropped (direct method on companion makes them redundant).
- `kyo-jsonrpc/jvm/src/test/scala/kyo/JsonRpcTransportJvmTest.scala` — renamed to `JsonRpcTransportUnixTest.scala` (see below).

### Created
- `kyo-jsonrpc/jvm/src/main/scala/kyo/internal/transport/UdsBackend.scala` — JVM real implementation: binds `ServerSocketChannel(StandardProtocolFamily.UNIX)`, registers `Scope.ensure` cleanup, delegates to `UdsWireTransport` + `JsonRpcTransport.fromWire`.
- `kyo-jsonrpc/js/src/main/scala/kyo/internal/transport/UdsBackend.scala` — Scala.js stub: `Abort.fail(UnsupportedOperationException(...))`.
- `kyo-jsonrpc/native/src/main/scala/kyo/internal/transport/UdsBackend.scala` — Scala Native stub: `Abort.fail(UnsupportedOperationException(...))`.
- `kyo-jsonrpc/jvm/src/test/scala/kyo/JsonRpcTransportUnixTest.scala` — renamed from `JsonRpcTransportJvmTest.scala`; removed `import kyo.JsonRpcTransportJvm.unixDomain` import; calls now go directly to `JsonRpcTransport.unixDomain(...)`.

### Edited
- `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcTransport.scala` — added `unixDomain(sockPath, framer, codec)` factory with return type `JsonRpcTransport < (Async & Scope & Abort[Throwable])`, delegating to `internal.transport.UdsBackend.connect`.
- `kyo-jsonrpc/jvm/src/main/scala/kyo/internal/transport/UdsWireTransport.scala` — updated stale comment referencing `JsonRpcTransportJvm.unixDomain` to reference `UdsBackend.connect`.

## Decisions

### Return type: `Async & Scope & Abort[Throwable]` not `Async & Scope`
The stub implementations on JS/Native must use `Abort.fail(...)` to signal the unsupported operation. `Abort.fail` produces `Abort[E]` in the effect row. Rather than hiding this behind `Abort.run` inside the backend (which would discard the error and make the API silently diverge), the decision is to surface `Abort[Throwable]` in the public factory's return type. This is consistent with the plan's note (`Abort[Throwable]` in Phase 06 canonical change signature) and with `BaseKyoCoreTest.run` already accepting `Abort[Any]`. The JVM backend also declares `Abort[Throwable]` in its signature for type-level uniformity even though it never produces that effect in practice.

### Test placement: JVM-only directory, platform-neutral name
The test uses JVM-only NIO classes (`SocketChannel`, `UnixDomainSocketAddress`, `ByteBuffer`) as a direct client to connect to the UDS server. Moving this to `shared/test` would require platform-specific test helpers (a JS/Native stub client that catches the `UnsupportedOperationException`), adding complexity for minimal value. The plan explicitly notes "simpler: keep the test JVM-only by leaving it in kyo-jsonrpc/jvm/src/test/ but rename to JsonRpcTransportUnixTest.scala". File is now at `kyo-jsonrpc/jvm/src/test/scala/kyo/JsonRpcTransportUnixTest.scala`.

### Extension methods dropped
`JsonRpcTransportJvm` exposed 4 extension methods on `JsonRpcTransport.type` (`unixDomain` overloads). These are no longer needed since `JsonRpcTransport.unixDomain` is now a direct method on the companion with default parameters. Callers previously using `JsonRpcTransport.unixDomain(path)` (via the extension) now resolve to the companion method directly — no source change required at call sites.

## Platform parity confirmation

| Platform | Compile | Tests |
|----------|---------|-------|
| JVM      | [success] | 179 tests, 0 failed |
| JS       | [success] (1 pre-existing warning in JsonRpcEndpointImpl.scala unrelated to this phase) | not run (Phase 07 gate) |
| Native   | [success] (same pre-existing warning) | not run (Phase 07 gate) |

Convention sweep: 9/9 clean (0 hits on `JsonRpcTransportJvm` anywhere in codebase).
