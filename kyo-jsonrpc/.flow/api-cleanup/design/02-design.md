---
prefix: JsonRpc
subject: kyo-jsonrpc + kyo-jsonrpc-http API cleanup
template: kyo-http
module: kyo-jsonrpc
crossPlatforms: [jvm, js, native]
---

# 02. Design: kyo-jsonrpc + kyo-jsonrpc-http API Cleanup

This document is the self-contained design contract for the kyo-jsonrpc API cleanup. It supersedes the D-series exploration notes for downstream agents (flow-validate, flow-phase-prep, flow-phase-impl, flow-verify). Decisions below are DONE; downstream agents must not relitigate.

## 1. Subject

The campaign cleans up the public surface of `kyo-jsonrpc` and `kyo-jsonrpc-http`. Final shape: **6 top-level public types** in `kyo-jsonrpc/shared/src/main/scala/kyo/` plus **1 top-level public type** in the `kyo-jsonrpc-http` sibling module, with **11 nested-public types** living under their owning companions. Two existing types are deleted (one merge, one platform fold). The campaign also aligns `JsonRpcEndpoint.Config` with the kyo-http config discipline, drops a redundant effect from one return type, polishes `JsonRpcError` helpers, and folds the JVM-only UDS transport into the multi-platform companion with JS/Native abort stubs.

## 2. Template

`kyo-http`, per A1 conventions. Specifically the `HttpServerConfig.Cors` and `HttpTlsConfig.{ClientAuth,Version}` precedents for nesting open-extension types under their owning companion, and `HttpServerConfig`'s fluent-setter / `default` / `require` / `derives CanEqual` discipline.

## 3. Module prefix

`JsonRpc`. Every top-level public type in `kyo-jsonrpc/shared/src/main/scala/kyo/` and `kyo-jsonrpc-http/src/main/scala/kyo/` must start with this prefix. Nested-public types live under a prefixed companion and inherit prefix compliance through their dotted path. flow-verify-organization Rule 8d reads this front-matter field.

## 4. Public-surface table

One row per public type currently in the module. Tier A = top-level. Tier B = nested under a prefixed companion. Both tiers are module-prefix-compliant.

| Type | Current placement | New placement | Decision | Tier |
|---|---|---|---|---|
| `JsonRpcEndpoint` | `kyo.JsonRpcEndpoint` | `kyo.JsonRpcEndpoint` | KEEP | A |
| `JsonRpcTransport` | `kyo.JsonRpcTransport` | `kyo.JsonRpcTransport` (gains `unixDomain`) | KEEP | A |
| `JsonRpcMethod` | `kyo.JsonRpcMethod` | `kyo.JsonRpcMethod` | KEEP | A |
| `JsonRpcError` | `kyo.JsonRpcError` | `kyo.JsonRpcError` (+10 LoC helpers) | KEEP | A |
| `JsonRpcEnvelope` | `kyo.JsonRpcEnvelope` | `kyo.JsonRpcEnvelope` (absorbs `JsonRpcResponse`) | KEEP+MERGE | A |
| `JsonRpcCodec` | `kyo.JsonRpcCodec` | `kyo.JsonRpcCodec` | KEEP | A |
| `JsonRpcHttpTransport` | `kyo.JsonRpcHttpTransport` (sibling module) | same | KEEP | A |
| `JsonRpcResponse` | `kyo.JsonRpcResponse` | gone (factories on `JsonRpcEnvelope`) | DELETE | n/a |
| `JsonRpcTransportJvm` | `kyo.JsonRpcTransportJvm` (jvm) | gone (folded into `JsonRpcTransport.unixDomain`) | DELETE | n/a |
| `IdStrategy` | `kyo.IdStrategy` | `kyo.JsonRpcEndpoint.IdStrategy` | NEST | B |
| `UnknownMethodPolicy` | `kyo.UnknownMethodPolicy` | `kyo.JsonRpcEndpoint.UnknownMethodPolicy` | NEST | B |
| `MessageGate` | `kyo.MessageGate` | `kyo.JsonRpcEndpoint.MessageGate` | NEST | B |
| `CancellationPolicy` | `kyo.CancellationPolicy` | `kyo.JsonRpcEndpoint.CancellationPolicy` | NEST | B |
| `ProgressPolicy` | `kyo.ProgressPolicy` | `kyo.JsonRpcEndpoint.ProgressPolicy` | NEST | B |
| `ExtrasEncoder` | `kyo.ExtrasEncoder` | `kyo.JsonRpcEndpoint.ExtrasEncoder` | NEST | B |
| `Framer` | `kyo.Framer` | `kyo.JsonRpcTransport.Framer` | NEST | B |
| `WireTransport` | `kyo.WireTransport` | `kyo.JsonRpcTransport.WireTransport` | NEST | B |
| `HandlerCtx` | `kyo.HandlerCtx` | `kyo.JsonRpcMethod.Context` | NEST+RENAME | B |
| `JsonRpcId` | `kyo.JsonRpcId` | `kyo.JsonRpcEnvelope.Id` | NEST+RENAME | B |

Counts: 6 Tier-A in `kyo-jsonrpc/shared`, 1 Tier-A in `kyo-jsonrpc-http`, 11 Tier-B nested, 2 DELETE. Post-cleanup public surface: 18 named public types.

## 5. Nesting roster

The 11 NEST moves with their owning companion. Each preserves the full original surface (factories, presets, sub-enums, `private[kyo]` constructors) under a new dotted path.

| Original | New dotted name | Companion |
|---|---|---|
| `IdStrategy` | `JsonRpcEndpoint.IdStrategy` | `JsonRpcEndpoint` |
| `UnknownMethodPolicy` | `JsonRpcEndpoint.UnknownMethodPolicy` | `JsonRpcEndpoint` |
| `MessageGate` | `JsonRpcEndpoint.MessageGate` | `JsonRpcEndpoint` |
| `CancellationPolicy` | `JsonRpcEndpoint.CancellationPolicy` | `JsonRpcEndpoint` |
| `ProgressPolicy` | `JsonRpcEndpoint.ProgressPolicy` | `JsonRpcEndpoint` |
| `ExtrasEncoder` | `JsonRpcEndpoint.ExtrasEncoder` | `JsonRpcEndpoint` |
| `Framer` | `JsonRpcTransport.Framer` | `JsonRpcTransport` |
| `WireTransport` | `JsonRpcTransport.WireTransport` | `JsonRpcTransport` |
| `HandlerCtx` | `JsonRpcMethod.Context` (renamed) | `JsonRpcMethod` |
| `JsonRpcId` | `JsonRpcEnvelope.Id` (renamed) | `JsonRpcEnvelope` |

`JsonRpcEndpoint` companion absorbs 6 types. `JsonRpcTransport` absorbs 2. `JsonRpcMethod` absorbs 1 (renamed). `JsonRpcEnvelope` absorbs 1 (renamed) plus the `JsonRpcResponse` merge factories.

## 6. Internal subpackage layout

Every file under `kyo-jsonrpc/shared/src/main/scala/kyo/internal/` moves into one of four subpackages. Single-line `package kyo.internal.<sub>` declaration on every file (no `package kyo` then `package internal` split).

| Subpackage | Contents |
|---|---|
| `kyo.internal.codec` | `JsonRpcCodecImpl.scala`, `RawJsonParser.scala`, `JsonRpcRequest.scala` |
| `kyo.internal.transport` | `InMemoryTransport.scala`, `StdioWireTransport.scala`, `WireTransportAdapter.scala`, JVM `UdsBackend.scala`, JVM `UdsWireTransport.scala`, JS `UdsBackend.scala`, Native `UdsBackend.scala` |
| `kyo.internal.framing` | `FramerImpl.scala` |
| `kyo.internal.engine` | `JsonRpcEndpointImpl.scala`, `CancellationEngine.scala`, `IdStrategyEngine.scala`, `ProgressEngine.scala`, `RateLimitEngine.scala` |

The engine subpackage references the 11 nested-public types via their new dotted names (e.g. `JsonRpcEndpoint.CancellationPolicy`, `JsonRpcEndpoint.MessageGate.Decision`).

## 7. Cross-platform plan

`JsonRpcTransportJvm.scala` is deleted; `unixDomain` folds into the shared `JsonRpcTransport` companion with platform-specific internal backends.

| Platform | File | Role |
|---|---|---|
| Shared | `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcTransport.scala` | Adds `def unixDomain(sockPath: Path, framer: JsonRpcTransport.Framer = Framer.lineDelimited, codec: JsonRpcCodec = JsonRpcCodec.Strict2_0)(using Frame): JsonRpcTransport < (Async & Scope)` delegating to `internal.transport.UdsBackend.open(sockPath)`. |
| JVM | `kyo-jsonrpc/jvm/src/main/scala/kyo/internal/transport/UdsBackend.scala` | Real implementation using `java.net.UnixDomainSocketAddress` + `ServerSocketChannel`. Body lifted from current `JsonRpcTransportJvm.scala:21-33`. |
| JVM | `kyo-jsonrpc/jvm/src/main/scala/kyo/internal/transport/UdsWireTransport.scala` | Relocated from `kyo/internal/UdsWireTransport.scala`; references `JsonRpcTransport.WireTransport`. |
| Native | `kyo-jsonrpc/native/src/main/scala/kyo/internal/transport/UdsBackend.scala` | Abort stub: `Abort.fail(new UnsupportedOperationException("UDS not yet implemented on Scala Native"))`. |
| JS | `kyo-jsonrpc/js/src/main/scala/kyo/internal/transport/UdsBackend.scala` | Abort stub: `Abort.fail(new UnsupportedOperationException("UDS not yet implemented on Scala.js"))`. |

The current `kyo-jsonrpc/jvm/src/test/scala/kyo/JsonRpcTransportJvmTest.scala` relocates to `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcTransportUnixTest.scala`. On JVM it runs the full UDS flow; on JS and Native it catches the `UnsupportedOperationException` and cancels, matching `kyo-http/shared/src/test/scala/kyo/HttpServerUnixTest.scala:17`.

Real Native and JS UDS implementations are out of scope; they defer to a future kyo-net extraction.

## 8. Effect-row decisions

Single change: drop `Sync` from `JsonRpcEndpoint.init`'s return type at `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEndpoint.scala:105`.

- Current: `JsonRpcEndpoint < (Sync & Async & Scope)`.
- Target: `JsonRpcEndpoint < (Async & Scope)`.

`Sync` is subsumed by `Async`. `JsonRpcTransport.fromWire`, `JsonRpcTransport.stdio`, and the new `JsonRpcTransport.unixDomain` are already `< (Async & Scope)`, so the alignment is consistent across the transport-factory family.

## 9. Error layering

`JsonRpcError` stays a flat case class. No sealed hierarchy. No subtypes. Two new helper constructors added with `require(...)` guards on the JSON-RPC 2.0 reserved code range, plus scaladoc on the case class citing JSON-RPC 2.0 §5.1 and LSP §3.16. Total: +10 LoC.

```scala
def serverError(code: Int, message: String, data: Maybe[Structure.Value] = Absent)(using Frame): JsonRpcError =
    require(code >= -32099 && code <= -32000, s"serverError code must be in [-32099, -32000], got $code; use applicationError for application-defined codes")
    JsonRpcError(code, message, data)

def applicationError(code: Int, message: String, data: Maybe[Structure.Value] = Absent)(using Frame): JsonRpcError =
    require(code < -32768 || code > -32000, s"applicationError code must be outside [-32768, -32000] (reserved range), got $code; use a standard constant or serverError")
    JsonRpcError(code, message, data)
```

## 10. Config alignment

`JsonRpcEndpoint.Config` aligns with the `HttpServerConfig` discipline. Five mechanical deltas:

1. Drop primary-constructor defaults; defaults centralize on `Config.default`.
2. Add 9 per-field fluent setters; `Maybe`-typed fields take bare values and wrap in `Present`.
3. Add `derives CanEqual`.
4. Add 2 `require(...)` guards on `maxInFlight` (must be > 0) and `requestTimeout` (must be positive or `Duration.Infinity`).
5. Update `JsonRpcEndpoint.init(transport, methods, config: Config = Config.default)(using Frame)`.

Field types inside the companion reference the nested types by short name (`UnknownMethodPolicy.minimal`, `IdStrategy.SequentialLong`); external callers spell them fully (`JsonRpcEndpoint.UnknownMethodPolicy.minimal`, `JsonRpcEndpoint.IdStrategy.SequentialLong`).

## 11. Test placement

Per Rule 8c, every source file maps 1:1 to a test file. The 10 standalone test files for nested types rename to reflect their new dotted path; their bodies stay 1:1 with the nested-type semantics.

| Old | New |
|---|---|
| `IdStrategyTest.scala` | `JsonRpcEndpointIdStrategyTest.scala` |
| `UnknownMethodPolicyTest.scala` | `JsonRpcEndpointUnknownMethodPolicyTest.scala` |
| `MessageGateTest.scala` | `JsonRpcEndpointMessageGateTest.scala` |
| `CancellationPolicyTest.scala` | `JsonRpcEndpointCancellationPolicyTest.scala` |
| `ProgressPolicyTest.scala` | `JsonRpcEndpointProgressPolicyTest.scala` |
| `ExtrasEncoderTest.scala` | `JsonRpcEndpointExtrasEncoderTest.scala` |
| `FramerTest.scala` | `JsonRpcTransportFramerTest.scala` |
| `WireTransportTest.scala` | `JsonRpcTransportWireTransportTest.scala` |
| `HandlerCtxTest.scala` | `JsonRpcMethodContextTest.scala` |
| `JsonRpcIdTest.scala` | `JsonRpcEnvelopeIdTest.scala` |

`JsonRpcResponseTest.scala` deletes; ~6 cases migrate into `JsonRpcEnvelopeTest.scala`.

## 12. Consumer impact

`kyo-browser` is the only in-tree consumer touched. 9 files (1 main + 8 tests across 5 test files), ~25 line changes plus 5 new `import` lines. Symbol usages are unchanged (`IdStrategy.SequentialInt`, `ExtrasEncoder.const`, etc.); only the import headers change from `import kyo.<Name>` to `import kyo.JsonRpcEndpoint.<Name>`. All kyo-browser updates land in the same atomic commit as Phase 3.

## 13. Non-goals

1. No kyo-browser behaviour changes.
2. No kyo-core changes.
3. No kyo-net extraction.
4. No real Native or JS UDS implementation.
5. No `JsonRpcMethod` / `JsonRpcHandler` split.
6. No `JsonRpcEndpoint.Unsafe` low-level API.
7. No sealed `JsonRpcError` hierarchy.
8. No `JsonRpcEnvelope` Schema derivation.
9. No JS / Native source population beyond UDS stubs.
10. No top-level proliferation; all open-extension policy types nest under their owning companion.
11. No test file consolidation beyond the renames in §11; each test stays 1:1 with its nested-type semantics.
