# Cross-platform sweep: kyo-jsonrpc protocol-coverage campaign

Scope: HEAD through `00a562bb0` (Phase 01..05 of the 5-phase plan).
Module roots:
- `/Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/`
- `/Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc-http/`

Contract: `/Users/fwbrasil/.claude/commands/flow-sweep.md`, `--topic cross-platform`.

## 1. Per-suite test counts

All six sbt invocations green. Counts taken from the ScalaTest run summary line.

| Suite                          | Tests | Suites | Failed | Canceled | Ignored | Pending |
|--------------------------------|-------|--------|--------|----------|---------|---------|
| `kyo-jsonrpc` (JVM)            | 173   | 22     | 0      | 0        | 0       | 0       |
| `kyo-jsonrpcJS`                | 169   | 21     | 0      | 0        | 0       | 0       |
| `kyo-jsonrpcNative`            | 169   | 21     | 0      | 0        | 0       | 0       |
| `kyo-jsonrpc-http` (JVM)       | 4     | 1      | 0      | 0        | 0       | 0       |
| `kyo-jsonrpc-httpJS`           | 4     | 1      | 0      | 0        | 0       | 0       |
| `kyo-jsonrpc-httpNative`       | 4     | 1      | 0      | 0        | 0       | 0       |

Aggregate: 523 succeeded across 67 suites, 0 failed.

The 4-test JVM/JS/Native delta on `kyo-jsonrpc` is `JsonRpcTransportJvmTest` (the Phase 04 `unixDomain` suite), which is correctly compiled and run only on the JVM platform per plan declaration `platforms: [jvm]` for Phase 04.

## 2. Pending / ignored markers

None.

Audit method:
- ScalaTest summary lines on all six runs report `pending 0, ignored 0, canceled 0`.
- Source grep for `\bignore(`, `pendingUntilFixed`, `is (pending)`, `cancel(` test-marker forms across `kyo-jsonrpc/{shared,jvm}/src/test` and `kyo-jsonrpc-http/src/test` returned only domain-language hits (`endpoint.cancel(id, ...)`, prose words `pending` / `ignored` in test descriptions or string literals).

No tests are skipped, hidden, or weakened.

## 3. Cross-platform leak check

No leak.

Audit method:
- `grep -rn "java\.nio\|java\.net" kyo-jsonrpc/shared/src kyo-jsonrpc-http/src` returned zero hits.
- `grep -rn "java\.util\.concurrent"` in `shared/src/main` returned the preexisting `ConcurrentHashMap`, `AtomicReference`, `AtomicLong` usage in `ProgressEngine.scala`, `CancellationEngine.scala`, `JsonRpcEndpointImpl.scala`. These types are Scala.js + Scala Native javalib-supported and the JS / Native suites compile and run green, confirming no platform-only API leaked.
- JVM-only `java.nio.*`, `java.net.StandardProtocolFamily`, `java.net.UnixDomainSocketAddress`, `java.nio.file.Files`, `java.nio.file.Path`, `java.nio.channels.{ServerSocketChannel, SocketChannel}` appear only in the jvm/-platform sources `kyo-jsonrpc/jvm/src/main/scala/kyo/JsonRpcTransportJvm.scala` and `kyo-jsonrpc/jvm/src/main/scala/kyo/internal/UdsWireTransport.scala`. Correct placement.
- `java.io.EOFException` in `kyo-jsonrpc/shared/src/main/scala/kyo/internal/StdioWireTransport.scala` is supported in the Scala.js javalib and Scala Native javalib; JS and Native suites compile and run green.

## 4. New public surface vs plan cross-platform declarations

Plan declares (`05-plan.yaml`):
- Phase 03 (WireTransport + Framer + stdio): `platforms: [jvm, js, native]`.
- Phase 04 (UDS transport): `platforms: [jvm]`.
- Phase 05 (kyo-jsonrpc-http subproject): `platforms: [jvm, js, native]`.

Source placement matches:
- `kyo-jsonrpc/shared/src/main/scala/kyo/WireTransport.scala`: shared, imports only `kyo.Stream`. Confirmed cross-platform; tests in shared `WireTransportTest.scala` run on all three platforms.
- `kyo-jsonrpc/shared/src/main/scala/kyo/Framer.scala`: shared, imports only `kyo.Stream`, `kyo.Sync`. Confirmed cross-platform; tests in shared `FramerTest.scala` run on all three platforms.
- `kyo-jsonrpc/shared/src/main/scala/kyo/internal/StdioWireTransport.scala`: shared, single JDK import (`java.io.EOFException`) is supported across all three platforms; JS and Native green.
- `kyo-jsonrpc/jvm/src/main/scala/kyo/JsonRpcTransportJvm.scala` and `kyo-jsonrpc/jvm/src/main/scala/kyo/internal/UdsWireTransport.scala`: correctly under `jvm/`, gated to the JVM platform per Phase 04 declaration. JVM-only `java.net.UnixDomainSocketAddress` etc are appropriate.
- `kyo-jsonrpc-http/src/main/scala/kyo/JsonRpcHttpTransport.scala`: subproject configured as `crossProject(JSPlatform, JVMPlatform, NativePlatform).crossType(CrossType.Pure)` in `build.sbt`. File imports `kyo` only; depends on `kyo-jsonrpc` and `kyo-http`. The 4 tests (`webSocket connects`, `binary frame drop`, `Scope.ensure closes WS`, `codec failure surfaces Malformed`) compile and run green on JVM, JS, and Native.

All four new public files match their plan-declared cross-platform scope.

## Summary

- Six sbt suites: 523 tests, all green.
- Zero pending / ignored / canceled markers.
- Zero cross-platform leak.
- New public surface correctly placed against plan declarations.
