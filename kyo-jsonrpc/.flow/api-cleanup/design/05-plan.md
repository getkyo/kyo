# 05. Plan: kyo-jsonrpc + kyo-jsonrpc-http API Cleanup

## Goal

Clean up the public surface of `kyo-jsonrpc` and `kyo-jsonrpc-http` to a final shape of 6 top-level public types in `kyo-jsonrpc/shared/src/main/scala/kyo/` (`JsonRpcEndpoint`, `JsonRpcTransport`, `JsonRpcMethod`, `JsonRpcError`, `JsonRpcEnvelope`, `JsonRpcCodec`) plus 1 top-level type in `kyo-jsonrpc-http` (`JsonRpcHttpTransport`), with 11 currently top-level public types nested under their owning companions (7 under `JsonRpcEndpoint`, 2 under `JsonRpcTransport`, 1 under `JsonRpcMethod`, 1 under `JsonRpcEnvelope`). Delete the redundant `JsonRpcResponse` standalone (factories move to `JsonRpcEnvelope`), fold the JVM-only `JsonRpcTransportJvm` into the multi-platform `JsonRpcTransport.unixDomain` with platform backends, reorganize internals into four subpackages (`codec`, `transport`, `framing`, `engine`), align `JsonRpcEndpoint.Config` with kyo-http's fluent-setter + `default` + `require` + `derives CanEqual` discipline, drop a redundant `Sync` from `JsonRpcEndpoint.init`'s effect row, and polish `JsonRpcError` with two helper constructors.

## Phases

## Phase 1: Internal subpackage reorg, banner sweep, scaladoc adds

**Scope.** Create the four internal subpackage directories under `kyo-jsonrpc/shared/src/main/scala/kyo/internal/` and move every shared-internal file into its target subpackage. Relocate JVM-only `UdsWireTransport.scala` into `kyo/internal/transport/`. Standardize every internal file's package declaration to a single-line `package kyo.internal.<sub>`. Strip every `// PUBLIC ...` banner comment from public source files. Add or expand scaladoc on every public type that lacks one. No semantic changes; this is a mechanical reorg + comment pass that leaves all symbol names intact.

**Files modified.**
- Create directories: `kyo-jsonrpc/shared/src/main/scala/kyo/internal/codec/`, `kyo-jsonrpc/shared/src/main/scala/kyo/internal/transport/`, `kyo-jsonrpc/shared/src/main/scala/kyo/internal/framing/`, `kyo-jsonrpc/shared/src/main/scala/kyo/internal/engine/`.
- Move + package: `kyo-jsonrpc/shared/src/main/scala/kyo/internal/CancellationEngine.scala` -> `.../internal/engine/CancellationEngine.scala`.
- Move + package: `kyo-jsonrpc/shared/src/main/scala/kyo/internal/FramerImpl.scala` -> `.../internal/framing/FramerImpl.scala`.
- Move + package: `kyo-jsonrpc/shared/src/main/scala/kyo/internal/IdStrategyEngine.scala` -> `.../internal/engine/IdStrategyEngine.scala`.
- Move: `kyo-jsonrpc/shared/src/main/scala/kyo/internal/InMemoryTransport.scala` -> `.../internal/transport/InMemoryTransport.scala`.
- Move + package: `kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcCodecImpl.scala` -> `.../internal/codec/JsonRpcCodecImpl.scala`.
- Move + package: `kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcEndpointImpl.scala` -> `.../internal/engine/JsonRpcEndpointImpl.scala`.
- Move: `kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcRequest.scala` -> `.../internal/codec/JsonRpcRequest.scala`.
- Move + package: `kyo-jsonrpc/shared/src/main/scala/kyo/internal/ProgressEngine.scala` -> `.../internal/engine/ProgressEngine.scala`.
- Move + package: `kyo-jsonrpc/shared/src/main/scala/kyo/internal/RateLimitEngine.scala` -> `.../internal/engine/RateLimitEngine.scala`.
- Move: `kyo-jsonrpc/shared/src/main/scala/kyo/internal/RawJsonParser.scala` -> `.../internal/codec/RawJsonParser.scala`.
- Move: `kyo-jsonrpc/shared/src/main/scala/kyo/internal/StdioWireTransport.scala` -> `.../internal/transport/StdioWireTransport.scala`.
- Move: `kyo-jsonrpc/shared/src/main/scala/kyo/internal/WireTransportAdapter.scala` -> `.../internal/transport/WireTransportAdapter.scala`.
- Move: `kyo-jsonrpc/jvm/src/main/scala/kyo/internal/UdsWireTransport.scala` -> `kyo-jsonrpc/jvm/src/main/scala/kyo/internal/transport/UdsWireTransport.scala`.
- Banner strip: every public file in `kyo-jsonrpc/shared/src/main/scala/kyo/` containing `// PUBLIC` lines.
- Scaladoc adds: every Tier-A public type lacking scaladoc.

**Canonical change.**

```scala
package kyo.internal.engine

class CancellationEngine[A](...):
    ...
end CancellationEngine
```

**Estimated LoC delta.** ~+150 scaladoc, -19 banners. Net +131.

**Verification strategy.** `targeted`.

**Verification command.** `sbt 'kyo-jsonrpcJVM/Test/compile'`.

**Blocked-by.** none.

## Phase 2: Merge JsonRpcResponse into JsonRpcEnvelope

**Scope.** Lift `success(id, result)` and `failure(id, error)` factories from `JsonRpcResponse.scala:19-22` onto the `JsonRpcEnvelope` companion. Delete `JsonRpcResponse.scala` and `JsonRpcResponseTest.scala`. Migrate the ~6 affected test cases into `JsonRpcEnvelopeTest.scala`. Update internal references in `internal/engine/JsonRpcEndpointImpl.scala` and `internal/codec/JsonRpcCodecImpl.scala` from `JsonRpcResponse.success`/`failure` to `JsonRpcEnvelope.success`/`failure`.

**Files modified.**
- Edit: `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEnvelope.scala` (+20 LoC factories).
- Delete: `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcResponse.scala` (-24 LoC).
- Delete: `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcResponseTest.scala`.
- Edit: `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcEnvelopeTest.scala` (absorb ~6 cases).
- Edit: `kyo-jsonrpc/shared/src/main/scala/kyo/internal/engine/JsonRpcEndpointImpl.scala` (ref updates).
- Edit: `kyo-jsonrpc/shared/src/main/scala/kyo/internal/codec/JsonRpcCodecImpl.scala` (ref updates).

**Canonical change.**

```scala
object JsonRpcEnvelope:
    object Response:
        def success(id: JsonRpcEnvelope.Id, result: Json): JsonRpcEnvelope.Response = ...
        def failure(id: JsonRpcEnvelope.Id, err: JsonRpcError): JsonRpcEnvelope.Response = ...
    end Response
end JsonRpcEnvelope
```

**Estimated LoC delta.** -24 source + +20 factories + ~10 ref updates. Net ~+6.

**Verification strategy.** `targeted`.

**Verification command.** `sbt 'kyo-jsonrpcJVM/Test/compile' 'kyo-jsonrpcJVM/testOnly kyo.JsonRpcEnvelopeTest'`.

**Blocked-by.** Phase 01.

## Phase 3: NEST 11 types under owning companions

**Scope.** For each of the 11 nested-public types, delete the standalone source file, absorb its body into the owning companion (`JsonRpcEndpoint`, `JsonRpcTransport`, `JsonRpcMethod`, `JsonRpcEnvelope`), and update every reference throughout `kyo-jsonrpc`, `kyo-jsonrpc-http`, and `kyo-browser`. The 11 moves: `IdStrategy`, `UnknownMethodPolicy`, `MessageGate`, `CancellationPolicy`, `ProgressPolicy`, `ExtrasEncoder` -> `JsonRpcEndpoint.*`; `Framer`, `WireTransport` -> `JsonRpcTransport.*`; `HandlerCtx` -> `JsonRpcMethod.Context` (renamed); `JsonRpcId` -> `JsonRpcEnvelope.Id` (renamed). Inline `CancellationPolicy`'s `ParamsEncoder`/`ParamsDecoder` type aliases at each use site (per `feedback_no_type_aliases`). Rename the 10 standalone nested-type test files to reflect their new dotted paths.

**Files modified.**
- Delete: `kyo-jsonrpc/shared/src/main/scala/kyo/IdStrategy.scala` (-8).
- Delete: `kyo-jsonrpc/shared/src/main/scala/kyo/UnknownMethodPolicy.scala` (-35).
- Delete: `kyo-jsonrpc/shared/src/main/scala/kyo/MessageGate.scala` (-13).
- Delete: `kyo-jsonrpc/shared/src/main/scala/kyo/CancellationPolicy.scala` (-76).
- Delete: `kyo-jsonrpc/shared/src/main/scala/kyo/ProgressPolicy.scala` (-55).
- Delete: `kyo-jsonrpc/shared/src/main/scala/kyo/ExtrasEncoder.scala` (-17).
- Delete: `kyo-jsonrpc/shared/src/main/scala/kyo/Framer.scala` (-37).
- Delete: `kyo-jsonrpc/shared/src/main/scala/kyo/WireTransport.scala` (-18).
- Delete: `kyo-jsonrpc/shared/src/main/scala/kyo/HandlerCtx.scala` (-32).
- Delete: `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcId.scala` (-29).
- Edit: `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEndpoint.scala` (+290 absorbed bodies; 6 nested types).
- Edit: `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcTransport.scala` (+50 absorbed `Framer`/`WireTransport`).
- Edit: `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcMethod.scala` (+30 absorbed `Context`; update handler signatures at lines 29, 48, 68, 85, 92, 116, 123).
- Edit: `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEnvelope.scala` (+30 absorbed `Id`; update field types at lines 8-25).
- Edit: `kyo-jsonrpc/shared/src/main/scala/kyo/internal/engine/JsonRpcEndpointImpl.scala` (~20 ref updates including `MessageGate.Decision` at lines 967, 969, 976, 1134, 1136, 1148).
- Edit: `kyo-jsonrpc/shared/src/main/scala/kyo/internal/engine/CancellationEngine.scala` (ref updates).
- Edit: `kyo-jsonrpc/shared/src/main/scala/kyo/internal/engine/IdStrategyEngine.scala` (ref updates; return type changes to `JsonRpcEnvelope.Id`).
- Edit: `kyo-jsonrpc/shared/src/main/scala/kyo/internal/engine/ProgressEngine.scala` (ref updates).
- Edit: `kyo-jsonrpc/shared/src/main/scala/kyo/internal/codec/JsonRpcCodecImpl.scala` (ref updates).
- Edit: `kyo-jsonrpc/shared/src/main/scala/kyo/internal/codec/JsonRpcRequest.scala` (ref updates).
- Edit: `kyo-jsonrpc/shared/src/main/scala/kyo/internal/transport/StdioWireTransport.scala` (ref updates).
- Edit: `kyo-jsonrpc/shared/src/main/scala/kyo/internal/transport/WireTransportAdapter.scala` (ref updates).
- Edit: `kyo-jsonrpc/shared/src/main/scala/kyo/internal/framing/FramerImpl.scala` (ref updates).
- Edit: `kyo-jsonrpc/jvm/src/main/scala/kyo/internal/transport/UdsWireTransport.scala` (ref updates).
- Edit: `kyo-jsonrpc-http/src/main/scala/kyo/JsonRpcHttpTransport.scala` (`import kyo.JsonRpcId` -> `import kyo.JsonRpcEnvelope.Id`, -1).
- Rename: `IdStrategyTest.scala` -> `JsonRpcEndpointIdStrategyTest.scala`.
- Rename: `UnknownMethodPolicyTest.scala` -> `JsonRpcEndpointUnknownMethodPolicyTest.scala`.
- Rename: `MessageGateTest.scala` -> `JsonRpcEndpointMessageGateTest.scala`.
- Rename: `CancellationPolicyTest.scala` -> `JsonRpcEndpointCancellationPolicyTest.scala`.
- Rename: `ProgressPolicyTest.scala` -> `JsonRpcEndpointProgressPolicyTest.scala`.
- Rename: `ExtrasEncoderTest.scala` -> `JsonRpcEndpointExtrasEncoderTest.scala`.
- Rename: `FramerTest.scala` -> `JsonRpcTransportFramerTest.scala`.
- Rename: `WireTransportTest.scala` -> `JsonRpcTransportWireTransportTest.scala`.
- Rename: `HandlerCtxTest.scala` -> `JsonRpcMethodContextTest.scala`.
- Rename: `JsonRpcIdTest.scala` -> `JsonRpcEnvelopeIdTest.scala`.
- Edit: `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala` (add `import kyo.JsonRpcEndpoint.{ExtrasEncoder, IdStrategy, UnknownMethodPolicy}`; update doc comment at lines 607-608 from `HandlerCtx` to `JsonRpcMethod.Context`).
- Edit: `kyo-browser/shared/src/test/scala/kyo/internal/CdpBackendTest.scala` (add import).
- Edit: `kyo-browser/shared/src/test/scala/kyo/internal/CdpBackendSmokeTest.scala` (add import).
- Edit: `kyo-browser/shared/src/test/scala/kyo/internal/CdpBackendLifecycleTest.scala` (add import).
- Edit: `kyo-browser/shared/src/test/scala/kyo/internal/CdpClientDecoderTest.scala` (add import; reword doc at line 267).
- Edit: `kyo-browser/shared/src/test/scala/kyo/internal/JsonRpcPortInvariantsSpec.scala` (add import).

**Canonical change.**

```scala
object JsonRpcEndpoint:
    trait MessageGate:
        def admit[A](msg: JsonRpcEnvelope, body: => A < Async): A < Async
    end MessageGate

    object MessageGate:
        val always: MessageGate = new MessageGate { ... }
    end MessageGate

    trait IdStrategy:
        def next(): JsonRpcEnvelope.Id < Sync
    end IdStrategy

    // ... 5 more nested types
end JsonRpcEndpoint
```

**Estimated LoC delta.** ~+75 net (after absorbed bodies migrate; absorbed-body LoC is migrated, not added).

**Verification strategy.** `module` (cross-module canary: `kyo-jsonrpc`, `kyo-jsonrpc-http`, `kyo-browser` must all compile in the same commit).

**Verification command.** `sbt 'kyo-jsonrpcJVM/Test/compile' 'kyo-jsonrpc-httpJVM/Test/compile' 'kyo-browserJVM/Test/compile' 'kyo-jsonrpcJVM/test'`.

**Blocked-by.** Phase 02.

## Phase 4: Config alignment

**Scope.** Apply the 5 mechanical deltas to `JsonRpcEndpoint.Config` in alignment with `HttpServerConfig`: drop primary-constructor defaults, add 9 fluent setters, add `Config.default` constant, add 2 `require(...)` guards on `maxInFlight` and `requestTimeout`, add `derives CanEqual`, and update `JsonRpcEndpoint.init(...)`'s default argument to `config: Config = Config.default`. Add tests covering fluent-setter round-trip, `Config.default` equality, `require` throws on edge values, and `CanEqual` derivation.

**Files modified.**
- Edit: `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEndpoint.scala` (+60 LoC: fluent setters + `default` + `require` + `derives CanEqual` + `init` signature).
- Edit: `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcEndpointTest.scala` (~+10 LoC tests).

**Canonical change.**

```scala
object JsonRpcEndpoint:
    case class Config(
        idStrategy: IdStrategy = IdStrategy.SequentialLong,
        unknownMethodPolicy: UnknownMethodPolicy = UnknownMethodPolicy.strict,
        // ... other fields
    ) derives CanEqual:
        def idStrategy(s: IdStrategy): Config = copy(idStrategy = s)
        def unknownMethodPolicy(p: UnknownMethodPolicy): Config = copy(unknownMethodPolicy = p)
        // ... per-field setters
    end Config

    object Config:
        val default: Config = Config()
        def require(c: Config): Unit = { /* validate */ }
    end Config
end JsonRpcEndpoint
```

**Estimated LoC delta.** +70.

**Verification strategy.** `targeted`.

**Verification command.** `sbt 'kyo-jsonrpcJVM/Test/compile' 'kyo-jsonrpcJVM/testOnly kyo.JsonRpcEndpointTest'`.

**Blocked-by.** Phase 03.

## Phase 5: Drop Sync from JsonRpcEndpoint.init effect row

**Scope.** Single-line change at `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEndpoint.scala:105`: change the return type of `init` from `JsonRpcEndpoint < (Sync & Async & Scope)` to `JsonRpcEndpoint < (Async & Scope)`. `Sync` is subsumed by `Async`.

**Files modified.**
- Edit: `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEndpoint.scala` (1 line).

**Canonical change.**

```scala
// Before: def init(transport: JsonRpcTransport, methods: Seq[JsonRpcMethod], config: Config): JsonRpcEndpoint < (Async & Sync & Resource & Abort[Throwable])
// After:  def init(transport: JsonRpcTransport, methods: Seq[JsonRpcMethod], config: Config): JsonRpcEndpoint < (Async & Resource & Abort[Throwable])
```

**Estimated LoC delta.** 0 net (one-line edit).

**Verification strategy.** `targeted`.

**Verification command.** `sbt 'kyo-jsonrpcJVM/Test/compile' 'kyo-jsonrpcJVM/testOnly kyo.JsonRpcEndpointTest'`.

**Blocked-by.** Phase 04.

## Phase 6: Cross-platform UDS fold-in

**Scope.** Delete `JsonRpcTransportJvm.scala`. Add `def unixDomain(sockPath: java.nio.file.Path, framer: JsonRpcTransport.Framer = JsonRpcTransport.Framer.lineDelimited, codec: JsonRpcCodec = JsonRpcCodec.Strict2_0)(using Frame): JsonRpcTransport < (Async & Scope)` to the shared `JsonRpcTransport` companion, delegating to `internal.transport.UdsBackend.open(sockPath)`. Create three platform `UdsBackend.scala` files: JVM with real `UnixDomainSocketAddress` + `ServerSocketChannel` body lifted from the deleted JVM file; Native and JS with abort stubs returning `Abort.fail(new UnsupportedOperationException("UDS not yet implemented on Scala <Native|js>"))`. Relocate `JsonRpcTransportJvmTest.scala` to `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcTransportUnixTest.scala` with platform-aware test setup (JVM runs the real flow; JS/Native catch the abort and cancel).

**Files modified.**
- Delete: `kyo-jsonrpc/jvm/src/main/scala/kyo/JsonRpcTransportJvm.scala` (-47).
- Create: `kyo-jsonrpc/jvm/src/main/scala/kyo/internal/transport/UdsBackend.scala` (+20 real impl).
- Create: `kyo-jsonrpc/native/src/main/scala/kyo/internal/transport/UdsBackend.scala` (+12 stub).
- Create: `kyo-jsonrpc/js/src/main/scala/kyo/internal/transport/UdsBackend.scala` (+12 stub).
- Edit: `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcTransport.scala` (+12 `unixDomain` factory).
- Move: `kyo-jsonrpc/jvm/src/test/scala/kyo/JsonRpcTransportJvmTest.scala` -> `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcTransportUnixTest.scala` (platform-aware cancel on JS/Native).

**Canonical change.**

```scala
object JsonRpcTransport:
    def unixDomain(path: String): JsonRpcTransport < (Async & Resource & Abort[Throwable]) =
        kyo.internal.transport.UdsBackend.connect(path)
end JsonRpcTransport
```

```scala
// js + native UdsBackend.scala
object UdsBackend:
    def connect(path: String): JsonRpcTransport < (Async & Resource & Abort[Throwable]) =
        Abort.fail(UnsupportedOperationException("Unix domain sockets are not supported on this platform"))
end UdsBackend
```

**Estimated LoC delta.** +9 net.

**Verification strategy.** `targeted`.

**Verification command.** `sbt 'kyo-jsonrpcJVM/Test/compile' 'kyo-jsonrpcJVM/testOnly kyo.JsonRpcTransportTest kyo.JsonRpcTransportUnixTest'`.

**Blocked-by.** Phase 05.

## Phase 7: Final cross-platform green run

**Scope.** Sequential green-gate run across JVM, JS, and Native (per `feedback_sequential_test_runs`). No source changes expected; any fix-ups landing here remain in this phase's commit. This phase certifies that the cumulative state of Phases 1-6 builds and tests pass on all three platforms.

**Files modified.** none expected; small fix-ups if compilation surfaces any platform-specific issue.

**Estimated LoC delta.** 0 (or small fix-up).

**Verification strategy.** `cross-platform-full`.

**Verification command.** `sbt 'kyo-jsonrpcJVM/test' 'kyo-jsonrpc-httpJVM/test' 'kyo-browserJVM/test' 'kyo-jsonrpcJS/test' 'kyo-jsonrpc-httpJS/test' 'kyo-browserJS/test' 'kyo-jsonrpcNative/test' 'kyo-jsonrpc-httpNative/test' 'kyo-browserNative/test'`.

**Blocked-by.** Phase 06.

## Cross-platform gate

Per `feedback_sequential_test_runs`, the final cross-platform run executes JVM, then JS, then Native, sequentially. Phase 7 is the dedicated cross-platform gate; in addition, Phase 3 includes a JVM-only cross-module canary that compiles `kyo-jsonrpc`, `kyo-jsonrpc-http`, and `kyo-browser` together to confirm the nesting refactor does not break downstream consumers. Phase 6 introduces platform-specific UDS backends; the new `JsonRpcTransportUnixTest` runs the real flow on JVM and cancels on JS/Native by catching the `UnsupportedOperationException` thrown by the abort-stub backends.

## Acceptance criteria

Three blocking yes/no items, all approved by the user before plan generation:

1. **NEST 11 types under their owning companions.** 6 under `JsonRpcEndpoint` (`IdStrategy`, `UnknownMethodPolicy`, `MessageGate`, `CancellationPolicy`, `ProgressPolicy`, `ExtrasEncoder`); 2 under `JsonRpcTransport` (`Framer`, `WireTransport`); 1 under `JsonRpcMethod` (`Context`, renamed from `HandlerCtx`); 1 under `JsonRpcEnvelope` (`Id`, renamed from `JsonRpcId`). **APPROVED.**

2. **Delete `JsonRpcTransportJvm.scala` and fold `unixDomain` into `JsonRpcTransport`** with a JVM real backend plus JS and Native abort stubs. **APPROVED.**

3. **Merge `JsonRpcResponse.scala` into `JsonRpcEnvelope`.** Delete the standalone file; `success`/`failure` factories move to the `JsonRpcEnvelope` companion. **APPROVED.**
