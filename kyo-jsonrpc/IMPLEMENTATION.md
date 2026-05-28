# kyo-jsonrpc Implementation Plan

## 1. Overview

`kyo-jsonrpc` is a cross-platform (JVM, JS, Native) bidirectional JSON-RPC engine for kyo. It layers typed method dispatch, pluggable wire codecs, cancellation policies, and progress policies on top of the existing `kyo.Exchange` primitive from kyo-core. The module targets three consumers (LSP, MCP, CDP) without requiring any of them to wrap or extend the engine. No HTTP/WebSocket/stdio transports are built here; those live in kyo-lsp, kyo-mcp, and kyo-browser.

Phase dependency chain (linear):

| Phase | Title | Depends on |
|---|---|---|
| 0 | Build Scaffold | nothing |
| 1 | Wire Types and Codec | Phase 0 |
| 2 | JsonRpcMethod and HandlerCtx | Phase 1 |
| 3 | JsonRpcTransport and inMemory | Phase 2 |
| 4 | JsonRpcEndpoint Core | Phase 3 |
| 5 | CancellationPolicy | Phase 4 |
| 6 | ProgressPolicy | Phase 5 |
| 7 | UnknownMethodPolicy and MessageGate | Phase 4 (depends on Phase 4's reader-fiber step-2/step-3 routing hooks; placed after Phase 6 to share the same engine-modification edit cycle, since both Phase 6 and Phase 7 modify JsonRpcEndpointImpl.scala reader fiber routing) |
| 8 | maxInFlight and requestTimeout | Phase 5 (requires cancellation engine) |
| 9 | Three-Consumer Scenario Tests | Phase 8 |
| 10 | Cross-Platform Sweep | Phase 9 |

---

## 2. Per-Phase Blocks

---

### Phase 0: Build Scaffold

**Dependency**: none. Phase 0 is the root. All later phases depend on the directory layout and build entry produced here.

**Files to read first**:
- `/Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/build.sbt`: to identify the exact insertion point (after `kyo-flow` at line 661) and copy the crossProject settings pattern from `kyo-schema` (line 451).
- `/Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/DESIGN.md` §18: for module dependency list (`kyo-prelude`, `kyo-core`, `kyo-schema`).
- `/Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/STEERING.md`: for forbidden actions (no extra deps, no other module changes).

**Files to produce**:
- `kyo-jsonrpc/shared/src/main/scala/kyo/package.scala`: empty package object establishing `package kyo`; placeholder for public API re-exports if needed.
- `kyo-jsonrpc/shared/src/main/scala/kyo/internal/package.scala`: empty package object establishing `package kyo.internal`.
- `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcSmokeTest.scala`: single `"kyo-jsonrpc compiles" in { assert(true) }` test to prove the scaffold compiles and the test harness is wired.

**Files to modify**:
- `build.sbt`: insert `lazy val \`kyo-jsonrpc\`` block after `kyo-flow` (line ~675), before `kyo-caliban`. Block mirrors `kyo-schema`: `crossProject(JSPlatform, JVMPlatform, NativePlatform).withoutSuffixFor(JVMPlatform).crossType(CrossType.Full).in(file("kyo-jsonrpc")).dependsOn(\`kyo-prelude\`).dependsOn(\`kyo-core\`).dependsOn(\`kyo-schema\`).settings(\`kyo-settings\`).jvmSettings(mimaCheck(false)).nativeSettings(\`native-settings\`).jsSettings(\`js-settings\`)`.

**Files to delete**: none.

**Public API additions / modifications / removals**: none (scaffold only).

**Tests**:
- Test 1: `"kyo-jsonrpc compiles"`: trivially asserts `true` to confirm test harness bootstraps without error.

Total tests this phase: **1**

**Verification commands**:
```
sbt 'kyo-jsonrpcJVM/compile' 2>&1 | tail -20
sbt 'kyo-jsonrpcJVM/testOnly *JsonRpcSmokeTest' 2>&1 | tail -20
```

**Supervision plan**: Verify `build.sbt` diff contains exactly one new `lazy val \`kyo-jsonrpc\`` block with three `dependsOn` calls and no changes to any other module. Confirm `crossType(CrossType.Full)` is present (required for platform-specific source dirs). Confirm no `% "test->test;compile->compile"` modifiers per `feedback_module_deps`. Convention sweep: `grep -nP '\xe2\x80\x94' <newfiles>` (must be 0, no em-dashes); `grep -n 'AllowUnsafe' <newfiles>` (each occurrence must be on a line within a `// Unsafe:` block); `grep -n ': Option\[' <newfiles>` (must be 0; use Maybe); `grep -nE ';$' <newfiles>` (must be 0, no semicolon line endings); `grep -n 'asInstanceOf' <newfiles>` (must be 0); `grep -n 'private\[kyo\].*=' <newfiles>` (no default params on private[kyo] methods).

**DESIGN.md sections**: §18 (phase 0 sub-bullets), §21 (no extra deps).

---

### Phase 1: Wire Types and Codec

**Dependency**: Phase 0, which requires the module directory structure and build entry to exist.

**Files to read first**:
- `kyo-jsonrpc/DESIGN.md` §3: full wire type definitions, `JsonRpcCodec` trait, `Strict2_0` and `Cdp` behavior, `cdpReservedKeys`, `extras` null vs absent distinction.
- `kyo-jsonrpc/DESIGN.md` §15: `JsonRpcError` constants and factories.
- `kyo-jsonrpc/DESIGN.md` §19 (decisions 1, 5): `extras: Maybe[Structure.Value]` semantics, `JsonRpcId` flat schema decision.
- `kyo-schema/shared/src/main/scala/kyo/Structure.scala`: `Structure.Value` enum cases and `Structure.encode`/`decode` API.
- `kyo-jsonrpc/audit/round2/COMPLETENESS.md` C6: `JsonRpcEnvelope derives CanEqual` only (not `Schema`); the `Malformed` case cannot survive `Schema` round-trip.

**Files to produce**:
- `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcId.scala`: `enum JsonRpcId derives CanEqual` with `Num(Long)` and `Str(String)` cases; hand-written `given Schema[JsonRpcId]` that encodes `Num` as `Structure.Value.Integer` and `Str` as `Structure.Value.Str` (flat, NOT a wrapper Record shape).
- `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcError.scala`: `case class JsonRpcError(code: Int, message: String, data: Maybe[Structure.Value]) derives Schema, CanEqual`; companion with all twelve constants (`ParseError`, `InvalidRequest`, `MethodNotFound`, `InvalidParams`, `InternalError`, `ServerNotInitialized`, `UnknownErrorCode`, `RequestCancelled`, `ContentModified`, `ServerCancelled`, `RequestFailed`) and five factories (`methodNotFound`, `invalidRequest`, `invalidParams`, `internalError`, `cancelled`); `JsonRpcResponse.success(id, result)` and `JsonRpcResponse.failure(id, error)` enforce result-xor-error; raw `apply` is `private[kyo]`.
- `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcRequest.scala`: `case class JsonRpcRequest(id: Maybe[JsonRpcId], method: String, params: Maybe[Structure.Value]) derives Schema, CanEqual`; `case class JsonRpcResponse(id: Maybe[JsonRpcId], result: Maybe[Structure.Value], error: Maybe[JsonRpcError]) derives Schema, CanEqual` with `success`/`failure` factories.
- `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEnvelope.scala`: `enum JsonRpcEnvelope derives CanEqual` with four cases: `Request(id: JsonRpcId, method: String, params: Maybe[Structure.Value], extras: Maybe[Structure.Value])`, `Notification(method: String, params: Maybe[Structure.Value], extras: Maybe[Structure.Value])`, `Response(id: JsonRpcId, result: Maybe[Structure.Value], error: Maybe[JsonRpcError], extras: Maybe[Structure.Value])`, `Malformed(reason: String, raw: Structure.Value)`. No `derives Schema`; codecs handle each case manually.
- `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcCodec.scala`: `trait JsonRpcCodec` with `encode(env: JsonRpcEnvelope)(using Frame): Structure.Value < (Sync & Abort[JsonRpcError])` and `decode(raw: Structure.Value)(using Frame): JsonRpcEnvelope < Sync`; companion `object JsonRpcCodec` with `val Strict2_0: JsonRpcCodec` and `val Cdp: JsonRpcCodec`; `private[kyo] val cdpReservedKeys: Set[String]`.
- `kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcCodecImpl.scala`: concrete implementations of `Strict2_0` and `Cdp`, using `Structure.Value.Record` pattern matching for decode, and `Structure.encode`/`Structure.decode` for typed sub-fields. `Cdp.encode` rejects any key from `cdpReservedKeys` in the caller-supplied `extras` with `Abort.fail(JsonRpcError.invalidRequest(...))`. `Strict2_0.decode` rejects a response that has both `result` and `error` populated as `Malformed`. Both codecs represent spec-null `id` as `Maybe[JsonRpcId] = Absent` on the envelope (the wire has `"id": null`; the codec absorbs it). `Strict2_0.encode` adds `"jsonrpc": "2.0"` to every outbound record; `Cdp.encode` omits it.
- `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcCodecTest.scala`: all Phase 1 tests.

**Files to modify**: none.

**Files to delete**: `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcSmokeTest.scala` (replaced by `JsonRpcCodecTest.scala` as the real first test file).

**Public API additions**:
- `JsonRpcId` (enum, `Num | Str`)
- `given Schema[JsonRpcId]` (hand-written flat)
- `JsonRpcError` (case class + companion constants + factories)
- `JsonRpcRequest`, `JsonRpcResponse` (wire case classes)
- `JsonRpcEnvelope` (ADT, four cases, `derives CanEqual`)
- `JsonRpcCodec` (trait)
- `JsonRpcCodec.Strict2_0`, `JsonRpcCodec.Cdp` (val)
- `private[kyo] JsonRpcCodec.cdpReservedKeys`

DEVIATION from prompt §5: the prompt names 7 public types; the design landed 21 (per DESIGN §17 deviations table). Rationale: CDP needs `JsonRpcCodec`, LSP needs `CancellationPolicy`/`ProgressPolicy`, MCP needs `ExtrasEncoder`. The user acknowledged this deviation during the design phase.

**Tests**:
- Test 2: `Strict2_0.encode(Request(Num(1L), "m", Absent, Absent))` produces a `Record` containing `("jsonrpc", Str("2.0"))`, `("id", Integer(1))`, `("method", Str("m"))` and no `"params"` key.
- Test 3: `Strict2_0.decode` of the record from Test 2 produces `Request(Num(1L), "m", Absent, Absent)`.
- Test 4: `JsonRpcId.Num(1L)` encodes via `Schema[JsonRpcId]` to `Structure.Value.Integer(1L)`, not `Record(("Num", Integer(1L)))`.
- Test 5: `JsonRpcId.Str("req-1")` encodes via `Schema[JsonRpcId]` to `Structure.Value.Str("req-1")`, not a wrapper Record.
- Test 6: `Strict2_0.encode(Notification("ping", Absent, Absent))` produces a Record with no `"id"` key at all (NOT `"id": null`).
- Test 7: `Strict2_0.encode(Response(Num(1L), Present(Str("ok")), Absent, Absent))` produces a Record with `"result"` and no `"error"` key.
- Test 8: `Strict2_0.decode` of a Record with both `"result"` and `"error"` populated produces `Malformed(...)`.
- Test 9: `Strict2_0.decode` of a Record with `"id": null` produces an envelope where the id resolves to `Maybe[JsonRpcId] = Absent` (not a `JsonRpcId.Null` variant).
- Test 10: `Cdp.encode(Request(Num(2L), "m", Absent, Present(Record(Chunk("sessionId" -> Str("s1"))))))` produces a Record with `"sessionId"` at the top level (not nested under `"extras"`).
- Test 11: `Cdp.encode(Request(..., extras = Present(Record(Chunk("method" -> Str("hijack"))))))` fails with `Abort[JsonRpcError]` (reserved key `"method"` rejected).
- Test 12: `Cdp.decode` of a Record with top-level `"sessionId"` field (and no `"jsonrpc"` key) produces `Request(..., extras = Present(Record(Chunk("sessionId" -> Str(...)))))`.
- Test 13: `Strict2_0.decode` of a Record with no `"method"` and no `"id"` and no `"result"` produces `Malformed(...)`.
- Test 14: Envelope `extras = Absent` encodes with no extra keys on the wire; `extras = Present(Structure.Value.Null)` encodes with an explicit `null`-valued extras key. The two are distinct on the wire.
- Test 15: `JsonRpcError.cancelled(Present("user"))` has code `-32800` and non-empty `data`.
- Test 16: `JsonRpcResponse.success(Num(1L), Present(Str("r")))` has `result = Present(...)` and `error = Absent`; `JsonRpcResponse.failure(Num(1L), JsonRpcError.MethodNotFound)` has `error = Present(...)` and `result = Absent`.
- Test 17: `Cdp.encode` omits the `"jsonrpc"` field; `Strict2_0.encode` always includes `"jsonrpc": "2.0"`.
- Test 18 (Schema derivation): `summon[Schema[JsonRpcResponse]]` compiles and round-trips a sample success response value (verifies that `Maybe[JsonRpcError]` inside `JsonRpcResponse` is handled by `Schema` derivation without compile failure, per audit C6 narrowed to Request/Response case classes).

Total tests this phase: **17**

**Verification commands**:
```
sbt 'kyo-jsonrpcJVM/Test/compile' 2>&1 | tail -20
sbt 'kyo-jsonrpcJVM/testOnly *JsonRpcCodecTest' 2>&1 | tail -20
sbt 'kyo-jsonrpcJS/Test/compile' 2>&1 | tail -10
sbt 'kyo-jsonrpcNative/Test/compile' 2>&1 | tail -10
```

**Supervision plan**: Verify the `Schema[JsonRpcId]` implementation does NOT call `derives Schema` on the enum (which would produce a tagged union shape). Inspect the hand-written Schema to confirm `Num(v)` maps directly to `Integer(v)` and `Str(v)` maps directly to `Str(v)`. Confirm `JsonRpcEnvelope` has `derives CanEqual` only (no `derives Schema`). Confirm `Strict2_0.decode` rejects result+error populated response with `Malformed` (not with `Abort`). Confirm `cdpReservedKeys` contains at least `"id"`, `"method"`, `"params"`, `"result"`, `"error"`, `"jsonrpc"`. Confirm `JsonRpcResponse` raw `apply` is `private[kyo]`. Convention sweep: `grep -nP '\xe2\x80\x94' <newfiles>` (must be 0, no em-dashes); `grep -n 'AllowUnsafe' <newfiles>` (each occurrence must be on a line within a `// Unsafe:` block); `grep -n ': Option\[' <newfiles>` (must be 0; use Maybe); `grep -nE ';$' <newfiles>` (must be 0, no semicolon line endings); `grep -n 'asInstanceOf' <newfiles>` (must be 0); `grep -n 'private\[kyo\].*=' <newfiles>` (no default params on private[kyo] methods).

**DESIGN.md sections**: §3 (full), §15 (JsonRpcError constants and factories), §19 (decisions 1, 5), §20 (invariant 11).

---

### Phase 2: JsonRpcMethod and HandlerCtx

**Dependency**: Phase 1. `HandlerCtx` references `JsonRpcId` and `JsonRpcError`; `JsonRpcMethod.handle` produces `Structure.Value`.

**Files to read first**:
- `kyo-jsonrpc/DESIGN.md` §5: full `JsonRpcMethod[+S]` and `HandlerCtx` definitions including `Kind` enum, the two `apply` overloads, `notification` factory, `schemaIn`/`schemaOut` private fields, `handle` private method.
- `kyo-jsonrpc/DESIGN.md` §20 (invariant 9): handler panic must become `JsonRpcError.internalError`; panic message in `error.data`, generic in `error.message`.
- `kyo-core/shared/src/main/scala/kyo/Fiber.scala`: `Fiber.Promise[A, S]` constructor for `HandlerCtx.cancelled`.
- `kyo-core/shared/src/main/scala/kyo/Scope.scala`: `Abort[Closed]` type for `progressSink`.

**Files to produce**:
- `kyo-jsonrpc/shared/src/main/scala/kyo/HandlerCtx.scala`: `final class HandlerCtx` with `val cancelled: Fiber.Promise[Unit, Sync]`, `val requestId: Maybe[JsonRpcId]`, `val extras: Maybe[Structure.Value]`, `private[kyo] val progressSink: Maybe[Structure.Value => Unit < (Async & Abort[Closed])]`, and `def progress(value: Structure.Value)(using Frame): Unit < (Async & Abort[Closed])` that delegates to `progressSink.fold(())(_ (value))`. Constructor is `private[kyo]`.
- `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcMethod.scala`: `sealed trait JsonRpcMethod[+S]` with `name: String`, `kind: JsonRpcMethod.Kind`, `private[kyo] schemaIn: Schema[?]`, `private[kyo] schemaOut: Schema[?]`, `private[kyo] handle(params: Structure.Value, ctx: HandlerCtx)(using Frame): Structure.Value < S`; `object JsonRpcMethod` with `enum Kind derives CanEqual`, two `apply` overloads (with and without `HandlerCtx`), `notification` factory; internal `private` impl classes `RequestMethod` and `NotificationMethod`; handler decode failure becomes `Abort.fail(JsonRpcError.invalidParams(...))` via `Structure.decode`; handler panic caught by wrapping with `Abort.run[JsonRpcError]` and mapping `Result.Panic` to `Abort.fail(JsonRpcError.internalError(t.getMessage, Present(Structure.encode(...))))`.
- `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcMethodTest.scala`: all Phase 2 tests.

**Files to modify**: none.

**Files to delete**: none.

**Public API additions**:
- `HandlerCtx` (class with `cancelled`, `requestId`, `extras`, `progress`)
- `JsonRpcMethod[+S]` (sealed trait)
- `JsonRpcMethod.Kind` (enum `Request | Notification`)
- `JsonRpcMethod.apply[In, Out, S](name)(handler: (In, HandlerCtx) => Out < S)`
- `JsonRpcMethod.apply[In, Out, S](name)(handler: In => Out < S)`
- `JsonRpcMethod.notification[In, S](name)(handler: (In, HandlerCtx) => Unit < S)`

**Tests**:
- Test 19: A `JsonRpcMethod.apply` handler that returns `Out` successfully encodes the result to `Structure.Value` via `schemaOut`.
- Test 20: A `JsonRpcMethod.apply` handler that calls `Abort.fail(JsonRpcError.InvalidParams)` propagates the failure without converting it.
- Test 21: A `JsonRpcMethod.apply` handler that throws (panic) has its panic converted to `Abort.fail(JsonRpcError.internalError(...))` with the panic message in `data`, not in `message`.
- Test 22: Calling `handle` with a `params` value that does not match `schemaIn` results in `Abort.fail(JsonRpcError.invalidParams(...))` before the handler body runs.
- Test 23: A `JsonRpcMethod.notification` handler's `handle` returns `Structure.Value.Null` (unit encoded); the `kind` field equals `JsonRpcMethod.Kind.Notification`.
- Test 24: `HandlerCtx.extras` is forwarded verbatim from the inbound envelope's `extras` into the ctx given to the handler.
- Test 25: `HandlerCtx.progress(v)` with `progressSink = Absent` is a no-op (returns `Unit` without sending anything).
- Test 26: The no-`HandlerCtx` overload `apply[In, Out, S](name)(handler: In => Out < S)` behaves identically to the ctx overload when the handler does not use `ctx`.

Total tests this phase: **8**

**Verification commands**:
```
sbt 'kyo-jsonrpcJVM/Test/compile' 2>&1 | tail -20
sbt 'kyo-jsonrpcJVM/testOnly *JsonRpcMethodTest' 2>&1 | tail -20
```

**Supervision plan**: Verify the panic-to-InternalError conversion uses `Abort.run[JsonRpcError]` wrapping the handler, matches `Result.Panic(t)` explicitly (no catch-all `case _ =>`), and places `t.getMessage` in `error.data` as `Structure.Value.Str`. Confirm `Structure.decode` failure path uses `Abort.fail(JsonRpcError.invalidParams(...))` not `Abort.panic`. Confirm `schemaIn`/`schemaOut`/`handle` are `private[kyo]`. Convention sweep: `grep -nP '\xe2\x80\x94' <newfiles>` (must be 0, no em-dashes); `grep -n 'AllowUnsafe' <newfiles>` (each occurrence must be on a line within a `// Unsafe:` block); `grep -n ': Option\[' <newfiles>` (must be 0; use Maybe); `grep -nE ';$' <newfiles>` (must be 0, no semicolon line endings); `grep -n 'asInstanceOf' <newfiles>` (must be 0); `grep -n 'private\[kyo\].*=' <newfiles>` (no default params on private[kyo] methods).

**DESIGN.md sections**: §5 (full), §20 (invariant 9).

---

### Phase 3: JsonRpcTransport and inMemory

**Dependency**: Phase 2. `JsonRpcTransport.send` and `incoming` carry `JsonRpcEnvelope` which is defined in Phase 1; tests need `JsonRpcMethod` from Phase 2 to verify round-trip.

**Files to read first**:
- `kyo-jsonrpc/DESIGN.md` §4: `JsonRpcTransport` trait definition, `inMemory` factory, rationale for "envelopes in" design.
- `kyo-core/shared/src/main/scala/kyo/Channel.scala`: `Channel.init`, `Channel.safe.put`, `Channel.safe.take` for cross-wired pair.
- `kyo-core/shared/src/main/scala/kyo/Scope.scala`: `Abort[Closed]` for transport close propagation.

**Files to produce**:
- `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcTransport.scala`: `trait JsonRpcTransport` with `def send(env: JsonRpcEnvelope)(using Frame): Unit < (Async & Abort[Closed])`, `def incoming(using Frame): Stream[JsonRpcEnvelope, Async & Abort[Closed]]`, `def close(using Frame): Unit < Async`; companion `object JsonRpcTransport` with `def inMemory(using Frame): (JsonRpcTransport, JsonRpcTransport) < Sync` implemented via two `Channel[JsonRpcEnvelope]` instances cross-wired (A's outbound is B's inbound and vice versa); `close` on either side closes both channels.
- `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcTransportTest.scala`: all Phase 3 tests.

**Files to modify**: none.

**Files to delete**: none.

**Public API additions**:
- `JsonRpcTransport` (trait)
- `JsonRpcTransport.inMemory`

**Tests**:
- Test 27: `inMemory` returns two transports; a `send` on transport A is received via `incoming` on transport B.
- Test 28: `inMemory` returns two transports; a `send` on transport B is received via `incoming` on transport A.
- Test 29: After `close()` on transport A, a subsequent `send` on transport A fails with `Abort[Closed]`.
- Test 30: After `close()` on transport A, the `incoming` stream on transport B terminates (the stream ends, no hang).
- Test 31: Backpressure: if the consumer of `incoming` on transport B is slow, `send` on transport A parks until space is available (verify by racing a slow consumer against a fast producer and confirming ordering is preserved).
- Test 32: Transport close while a `send` is parked in the backpressure case: the parked `send` unblocks with `Abort[Closed]`.

Total tests this phase: **6**

**Verification commands**:
```
sbt 'kyo-jsonrpcJVM/Test/compile' 2>&1 | tail -20
sbt 'kyo-jsonrpcJVM/testOnly *JsonRpcTransportTest' 2>&1 | tail -20
```

**Supervision plan**: Verify `inMemory` uses `Channel.init` (scoped) or `Channel.Unsafe.init` (with `// Unsafe:` comment) for the two cross-wired channels. Confirm `incoming` returns a `Stream` that terminates cleanly when the channel is closed (no hanging). Confirm `close` closes both channels so both `incoming` streams terminate. Verify no `Array`, `Option`, or `List` types appear. Convention sweep: `grep -nP '\xe2\x80\x94' <newfiles>` (must be 0, no em-dashes); `grep -n 'AllowUnsafe' <newfiles>` (each occurrence must be on a line within a `// Unsafe:` block); `grep -n ': Option\[' <newfiles>` (must be 0; use Maybe); `grep -nE ';$' <newfiles>` (must be 0, no semicolon line endings); `grep -n 'asInstanceOf' <newfiles>` (must be 0); `grep -n 'private\[kyo\].*=' <newfiles>` (no default params on private[kyo] methods).

**DESIGN.md sections**: §4 (full), §20 (invariant 2: writer serialization is the engine's job, not the transport's).

---

### Phase 4: JsonRpcEndpoint Core

**Dependency**: Phase 3, which provides `JsonRpcTransport` for engine construction and `JsonRpcMethod` for handler registry.

**Files to read first**:
- `kyo-jsonrpc/DESIGN.md` §6: full `JsonRpcEndpoint` interface, `ExtrasEncoder`, `Config`, `Pending`, `init` signature.
- `kyo-jsonrpc/DESIGN.md` §6.1: Exchange wiring: `callerRegistry`, `pendingInbound` (`Running | Replying | Cancelled`), `progressStreams`, `partialResults`, `idSignal` pattern, `OutboundReq` shape.
- `kyo-jsonrpc/DESIGN.md` §6.2: reader fiber routing order (4 steps).
- `kyo-jsonrpc/DESIGN.md` §6.3: writer fiber single-serializer discipline.
- `kyo-jsonrpc/DESIGN.md` §6.4: scope cleanup finalizer order (8 steps).
- `kyo-jsonrpc/DESIGN.md` §6.5: `pendingInbound` CAS lifecycle, `SuppressIfCancelled`, three-state machine.
- `kyo-jsonrpc/DESIGN.md` §6.6: reader fiber Sync-only discipline, `Fiber.initUnscoped`, `Channel.Unsafe.offer`.
- `kyo-jsonrpc/DESIGN.md` §20 (all 12 invariants), particularly invariants 1, 2, 3, 4, 5, 6, 7, 12.
- `kyo-jsonrpc/audit/round2/COMPLETENESS.md` C2, C3, C4: `Replying` removal under `Sync.ensure`, CAS transition, `callerRegistry` inserted inside Exchange `encode` callback.
- `kyo-core/shared/src/main/scala/kyo/Exchange.scala`: `Exchange.initUnscoped` with custom `nextId`; `apply` method; `Message.Response | Message.Skip`; `Sync.ensure` pattern.

**Files to produce**:
- `kyo-jsonrpc/shared/src/main/scala/kyo/ExtrasEncoder.scala`: `type ExtrasEncoder = JsonRpcId => Maybe[Structure.Value] < Sync`; companion `object ExtrasEncoder` with `val empty: ExtrasEncoder` and `def const(extras: Structure.Value): ExtrasEncoder`.
- `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEndpoint.scala`: `final class JsonRpcEndpoint private (...)` with `call`, `notify`, `callWithProgress`, `callPartialResults`, `subscribeProgress`, `unsubscribeProgress`, `cancel`, `awaitDrain`, `close`; `object JsonRpcEndpoint` with `final class Pending[Out]`, `final case class Config(...)`, `def init(...)`, and `object ExtrasEncoder` re-export for convenience; `IdStrategy` enum.
- `kyo-jsonrpc/shared/src/main/scala/kyo/IdStrategy.scala`: `enum IdStrategy` with `SequentialLong`, `SequentialInt`, `Custom(next: () => JsonRpcId < Sync)`.
- `kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcEndpointImpl.scala`: internal implementation: `OutboundReq` case class (method, encodedParams, idSignal, abortSignal, extrasValue); `CallerInfo` case class (method, extras, abortSignal); `InboundEntry` sealed trait with `Running`, `Replying`, `Cancelled` variants; `WriterMsg` sealed trait with `SendEnvelope` and `SuppressIfCancelled`; writer fiber loop; reader fiber routing (steps 1-4 from §6.2, with the following no-ops in Phase 4: step 1 (cancellation policy intercept) is a no-op; step 1b (progress policy intercept) is a no-op; step 2 (MessageGate) is a no-op because gate is None; step 3 (unknown-method policy) uses `UnknownMethodPolicy.minimal` which replies MethodNotFound for unknown requests and drops unknown notifications, no dollar-prefix override); `callerRegistry` population inside Exchange `encode` callback using `idSignal` pattern; `Sync.ensure`-based cleanup on every exit path; §6.4 finalizer order. Built on `Exchange[JsonRpcId, OutboundReq, Structure.Value, String, Nothing, JsonRpcError]` (six type parameters per DESIGN §6.1: Id=JsonRpcId, Req=OutboundReq engine-internal, Resp=Structure.Value decoded body, Wire=String raw JSON, Event=Nothing because the engine routes notifications via pendingInbound rather than Exchange's Push channel, E=JsonRpcError for transport-level errors).
- `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcEndpointTest.scala`: all Phase 4 tests.

**Files to modify**: none.

**Files to delete**: none.

**Public API additions**:
- `ExtrasEncoder` (type alias + companion)
- `IdStrategy` (enum)
- `JsonRpcEndpoint` (class + companion with `Config`, `Pending`, `init`)
- `JsonRpcEndpoint.Config` (case class with all fields; `cancellation`, `progress`, `gate` default to `Absent` in Phase 4; `unknownMethod` defaults to `UnknownMethodPolicy.minimal`, a Phase-4-only type defined in `UnknownMethodPolicy.scala` with the following behavior: unknown requests reply MethodNotFound, unknown notifications are dropped, no dollar-prefix override; Phase 7 replaces the body of `UnknownMethodPolicy.scala` to add `.lsp` and `.strict` constants while keeping `.minimal` as the Phase-4 default)
- `UnknownMethodPolicy` (Phase-4 skeleton class with `UnknownMethodPolicy.minimal` only; body expanded in Phase 7)
- `JsonRpcEndpoint.Pending[Out]` (final class)
- `JsonRpcEndpoint.init` (returns `JsonRpcEndpoint < (Sync & Async & Scope)`)
- `JsonRpcEndpoint.call`, `notify`, `cancel`, `awaitDrain`, `close` (fully implemented in Phase 4)
- `JsonRpcEndpoint.callWithProgress`, `callPartialResults`, `subscribeProgress`, `unsubscribeProgress` (stub bodies in Phase 4 only; signatures are final and do not change in Phase 6): when `Config.progress = Absent`, each stub returns `Sync.defer(Abort.fail(JsonRpcError.internalError("progress not configured: pass Config.progress = Present(ProgressPolicy.lsp / .mcp)")))`. When `Config.progress = Present(_)`, each stub returns `Sync.defer(Abort.panic(new NotImplementedError("Phase 6 wires this")))`. Phase 6's "Files to modify" replaces these four stub bodies in `JsonRpcEndpoint.scala` with the full routing logic without changing signatures.

**Tests**:
- Test 33: `A.call("add", AddReq(1, 2))` with B registering an `add` handler returns `AddResp(3)`.
- Test 34: `A.notify("log", LogMsg("hello"))` with B registering a `log` notification handler: B's handler runs, A receives no reply, the transport carries exactly one frame (the notification), not two.
- Test 35: A sends a request to B and B sends a request to A simultaneously; both resolve correctly without cross-wiring.
- Test 36: Multiple concurrent `A.call` invocations resolve independently and in correct order.
- Test 37: Unknown method request from A reaches B which has no handler: `A.call` fails with `Abort[JsonRpcError]` whose code is `-32601` (MethodNotFound); the response carries no result.
- Test 38: `Scope.run(JsonRpcEndpoint.init(...))` cleans up Exchange's pending map: after scope exits, Exchange's internal pending-promise map is drained and `A.call(...)` fails with `Abort[Closed]` (verifies §6.4 step 5 Exchange close).
- Test 39: A pending `endpoint.call` with an `abortSignal`-carrying `CallerInfo` registered observes `Abort.fail(Closed)` after `Scope.run` exits (verifies §6.4 step 6 callerRegistry drain, distinct from Exchange's own pending-map drain).
- Test 40: `callerRegistry` is empty after a call completes normally (no leak on success path).
- Test 41: `callerRegistry` is empty after a call is interrupted by its fiber being cancelled externally (Sync.ensure path removes entry).
- Test 42: `A.call(...)` returns `Abort[Closed]` when the transport is closed mid-call (outstanding call completes with Closed, not a hang).
- Test 43: `awaitDrain` returns after all pending calls have resolved and no new calls are in flight.
- Test 44: Late reply for an already-cancelled (interrupted) outbound call is silently dropped by Exchange; `pendingInbound` is not consulted for outbound drops.
- Test 45: `endpoint.cancel(id, Absent)` with no `CancellationPolicy` present: caller's pending `call` fails with `Abort.fail(JsonRpcError.cancelled(Absent))` locally, no cancel notification is sent on the transport (verify frame count).
- Test 46: `ExtrasEncoder.const(v)` causes the extras value to appear in the outbound envelope's `extras` field as seen by the receiving side.
- Test 47: `IdStrategy.SequentialLong` produces ids `Num(1)`, `Num(2)`, `Num(3)` for three sequential calls.
- Test 48: `IdStrategy.SequentialInt` produces `Num(1)`, `Num(2)`, `Num(3)` (same shape but scoped to Int range; verify via exchange envelope inspection).
- Test 49 (I9 exit-after-shutdown drain): after `endpoint.close()` invokes the §6.4 finalizer order, no further outbound writes hit the transport; verify with a counting test transport that the write count does not increase after close.
- Test 50 (I15 Custom.next concurrency): `IdStrategy.Custom(next)` where `next` is called from 100 concurrent fibers produces 100 distinct ids (verify no collisions; covers concurrent caller scenario).

Total tests this phase: **18**

**Verification commands**:
```
sbt 'kyo-jsonrpcJVM/Test/compile' 2>&1 | tail -20
sbt 'kyo-jsonrpcJVM/testOnly *JsonRpcEndpointTest' 2>&1 | tail -20
```

**Supervision plan**: Verify §6.1's `callerRegistry` population happens INSIDE the Exchange `encode` callback (not before or after `exchange(req)`). Verify the three-state `InboundEntry` machine exists with `Running`, `Replying`, and `Cancelled` variants. Verify `Replying` entries are removed under `Sync.ensure { send(env) }(_ => pendingInbound.remove(id))` in the writer fiber (not "after send" without ensure). Verify `notify` writes to the engine's writer channel directly, bypassing `Exchange.apply` (no pending entry created). Verify §6.4 finalizer order has all 8 steps in the documented sequence. Verify `awaitDrain` semantics include `Replying`-state entries (waits until writer drains, not just `Running` entries). Check for any `// Unsafe:` comments on every `Sync.Unsafe.defer`, `Channel.Unsafe.*`, `Promise.Unsafe.*`, `AtomicXxx.Unsafe.*` call site in the impl file. Convention sweep: `grep -nP '\xe2\x80\x94' <newfiles>` (must be 0, no em-dashes); `grep -n 'AllowUnsafe' <newfiles>` (each occurrence must be on a line within a `// Unsafe:` block); `grep -n ': Option\[' <newfiles>` (must be 0; use Maybe); `grep -nE ';$' <newfiles>` (must be 0, no semicolon line endings); `grep -n 'asInstanceOf' <newfiles>` (must be 0); `grep -n 'private\[kyo\].*=' <newfiles>` (no default params on private[kyo] methods).

**DESIGN.md sections**: §6 (full), §6.1 to §6.6 (full), §10, §20 (all invariants), §19 (decisions 3, 6, 14).

---

### Phase 5: CancellationPolicy

**Dependency**: Phase 4. The cancellation engine modifies `JsonRpcEndpoint`'s reader fiber routing (step 1 intercept), writer fiber (suppress path), and `cancel` method; requires the three-state `InboundEntry` machine from Phase 4.

**Files to read first**:
- `kyo-jsonrpc/DESIGN.md` §7: full `CancellationPolicy` definition, `ParamsEncoder`, `.lsp` and `.mcp` values, inbound and outbound flows, `protectedMethods`, timeout auto-fire.
- `kyo-jsonrpc/DESIGN.md` §6.5: CAS race sequence for `Running → Cancelled` and `Running → Replying` transitions, `SuppressIfCancelled` writer check.
- `kyo-jsonrpc/DESIGN.md` §19 (decisions 9, 10): engine-enforced no-Outcome-ADT; `protectedMethods` carve-out.
- `kyo-jsonrpc/audit/round2/COMPLETENESS.md` C3: CAS fix: third `Cancelled` variant prevents the race where cancel arrives between handler completion and map replacement.
- `kyo-jsonrpc/audit/round2/COMPLETENESS.md` C5: cancel for absent id: log warning, do not silently succeed.

**Files to produce**:
- `kyo-jsonrpc/shared/src/main/scala/kyo/CancellationPolicy.scala`: `final case class CancellationPolicy(cancelMethod: String, encodeParams: CancellationPolicy.ParamsEncoder, expectReplyForCancelledRequest: Boolean, cancelledError: Maybe[JsonRpcError], protectedMethods: Set[String])`; companion `object CancellationPolicy` with `type ParamsEncoder`, private `LspCancelParams` / `McpCancelParams` case classes with `derives Schema`, private `lspEncoder`/`mcpEncoder` vals, `val lsp: CancellationPolicy`, `val mcp: CancellationPolicy`.
- `kyo-jsonrpc/shared/src/main/scala/kyo/internal/CancellationEngine.scala`: engine-level wiring: `handleInboundCancel(id, policy, pendingInbound)` implementing the CAS sequence; `handleOutboundCancel(id, reason, policy, callerRegistry, writerChannel)` implementing §7 outbound flow steps 1-5; `handleTimeout(id, policy, ...)` auto-firing the cancellation notification when `policy = Present`.
- `kyo-jsonrpc/shared/src/test/scala/kyo/CancellationPolicyTest.scala`: all Phase 5 tests.

**Files to modify**:
- `kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcEndpointImpl.scala`: wire `CancellationPolicy` into reader fiber step 1 intercept and writer fiber `SuppressIfCancelled` check; update `endpoint.cancel` to consult `callerRegistry`, check `protectedMethods`, fire wire notification when policy present, log warning when id absent; update `Config` defaults to have `cancellation = Present(CancellationPolicy.lsp)` as the default.

**Files to delete**: none.

**Public API additions**:
- `CancellationPolicy` (case class)
- `CancellationPolicy.ParamsEncoder` (type alias)
- `CancellationPolicy.lsp`, `CancellationPolicy.mcp` (val)

**Tests**:
- Test 51: LSP inbound cancel (`$/cancelRequest`): handler fiber receives `ctx.cancelled` completion; `A.call` on the requester side eventually gets `Abort[JsonRpcError]` with code `-32800`.
- Test 52: LSP inbound cancel: a reply IS still sent on the transport (verify frame count includes the response frame from B).
- Test 53: MCP inbound cancel (`notifications/cancelled`): no reply is sent on the transport (verify by counting frames; zero response frames for the cancelled request id).
- Test 54: MCP inbound cancel race with fast handler (cancel arrives while reply is already queued in the writer channel): the reply is suppressed and NOT sent (the `Running → Replying` CAS + `suppress` flag path).
- Test 55: LSP outbound cancel: `endpoint.cancel(id, Absent)` sends a `$/cancelRequest` notification on the transport AND causes the caller's `call` to fail with `-32800`.
- Test 56: MCP outbound cancel: `endpoint.cancel(id, Present("user"))` sends `notifications/cancelled` with `requestId` and `reason` fields, and the caller's `call` fails.
- Test 57: `endpoint.cancel(id)` for an id whose method is in `protectedMethods` (`"initialize"` for MCP): no cancel notification is sent, no caller abort fires, a warning is logged (verify via transport frame count staying the same).
- Test 58: `endpoint.cancel(id)` for an id not in `callerRegistry` (already-completed call): logs a warning and returns `Unit` without sending a cancel notification.
- Test 59: Cancel for an id that no handler is serving (`pendingInbound` lookup returns absent): silently dropped, no error thrown.
- Test 60: Timeout auto-fire with `CancellationPolicy.lsp` present: when `Config.requestTimeout` fires, a `$/cancelRequest` notification appears on the transport AND the caller's `call` fails with `-32800`.
- Test 61: Timeout auto-fire with `cancellation = Absent` (CDP shape): when timeout fires, no cancel notification is sent; caller's `call` fails with `Abort[JsonRpcError]` (local abort only).
- Test 62: Handler aborts with `Abort.fail(JsonRpcError.ContentModified)` on LSP cancel: the wire response carries error code `-32801` verbatim (engine never substitutes its own code).
- Test 63 (C1 extras propagation for cancel): `endpoint.cancel(id)` builds the cancel notification with `extras = callerRegistry[id].extras` (verify by capturing the envelope on the transport and asserting the extras slot matches the source call's extras).
- Test 64 (H2 cancel-during-encode race): with a Latch-paused encode callback, issue an outbound call; while the encode is paused, fire `endpoint.cancel(id)` for the just-assigned id; release the Latch; verify the encoded envelope is still sent but the caller observes `Abort.fail(cancelled)` immediately (proves the idSignal pattern's race resilience).

Total tests this phase: **14**

**Verification commands**:
```
sbt 'kyo-jsonrpcJVM/Test/compile' 2>&1 | tail -20
sbt 'kyo-jsonrpcJVM/testOnly *CancellationPolicyTest' 2>&1 | tail -20
```

**Supervision plan**: Verify the `Running → Cancelled` CAS and `Running → Replying` CAS are both present and use `ConcurrentHashMap.replace(key, expected, update)` or equivalent atomic operation. Verify `SuppressIfCancelled` writer check snapshots `suppress` at dequeue time and removes the `pendingInbound` entry under `Sync.ensure`. Verify `lspEncoder` and `mcpEncoder` use `Structure.encode(LspCancelParams(id))` / `Structure.encode(McpCancelParams(id, reason))` (not manual `Record` construction). Verify `protectedMethods` check in `endpoint.cancel` runs before any wire notification dispatch. Verify `endpoint.cancel` with an absent `callerRegistry` entry logs (not silently succeeds) per audit C5. Convention sweep: `grep -nP '\xe2\x80\x94' <newfiles>` (must be 0, no em-dashes); `grep -n 'AllowUnsafe' <newfiles>` (each occurrence must be on a line within a `// Unsafe:` block); `grep -n ': Option\[' <newfiles>` (must be 0; use Maybe); `grep -nE ';$' <newfiles>` (must be 0, no semicolon line endings); `grep -n 'asInstanceOf' <newfiles>` (must be 0); `grep -n 'private\[kyo\].*=' <newfiles>` (no default params on private[kyo] methods).

**DESIGN.md sections**: §7 (full), §6.5 (CAS race section), §19 (decisions 9, 10), §20 (invariant 3).

---

### Phase 6: ProgressPolicy

**Dependency**: Phase 5. Progress notifications are intercepted in reader fiber step 1 alongside cancellation; `HandlerCtx.progressSink` lifecycle couples to the `Running → Replying` transition already established in Phase 5.

**Files to read first**:
- `kyo-jsonrpc/DESIGN.md` §8: full `ProgressPolicy` definition, `field`/`merge` inline helpers, `.lsp` and `.mcp` values, three outbound flavors, inbound progress routing, out-of-band token registration, handler-side `ctx.progress`.
- `kyo-jsonrpc/DESIGN.md` §19 (decisions 6, 7, 8, 12, 13): `subscribeProgress`/`unsubscribeProgress`, `callPartialResults[T]` separate API, engine-side token allocation, `progressSink` invalidation, `enforceMonotonic`.
- `kyo-jsonrpc/audit/round2/COMPLETENESS.md` I14: monotonicity state: `AtomicRef[Maybe[BigDecimal]]` inside `progressSink` closure.

**Files to produce**:
- `kyo-jsonrpc/shared/src/main/scala/kyo/ProgressPolicy.scala`: `final case class ProgressPolicy(progressMethod: String, extractInboundToken: ..., extractRequestToken: ..., stampOutboundToken: ..., encodeProgressParams: ..., enforceMonotonic: Boolean)`; companion `object ProgressPolicy` with `private inline def field(...)`, `private inline def merge(...)`, `val lsp: ProgressPolicy`, `val mcp: ProgressPolicy`.
- `kyo-jsonrpc/shared/src/main/scala/kyo/internal/ProgressEngine.scala`: `callWithProgress` implementation (token allocation, `progressStreams` registration, `stampOutboundToken` application, `Channel[Structure.Value]` for progress side-channel); `callPartialResults[T]` implementation (stream closed on empty-result final response); `subscribeProgress`/`unsubscribeProgress` (direct `progressStreams` registration); inbound progress routing (step 1b in reader fiber); `ctx.progress` sink builder (captures `AtomicRef[Maybe[BigDecimal]]` for monotonicity; invalidates when entry transitions from `Running` to `Replying`; monotonicity check uses `AtomicRef[Maybe[BigDecimal]].update` with a CAS-loop: read current, compare against new value, if non-monotonic return UnitDrop without updating, if monotonic update and proceed to emit; the sink closure also captures `ctx.extras` and stamps it onto each emitted progress notification envelope so MCP routing can find the originating session). Files to modify in Phase 6 also replace the four stub bodies in `JsonRpcEndpoint.scala` (callWithProgress, callPartialResults, subscribeProgress, unsubscribeProgress) with the full routing logic without changing their signatures.
- `kyo-jsonrpc/shared/src/test/scala/kyo/ProgressPolicyTest.scala`: all Phase 6 tests.

**Files to modify**:
- `kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcEndpointImpl.scala`: wire `ProgressPolicy` into reader fiber step 1b intercept (`progressMethod` notifications routed to `progressStreams`); update `Config` default to `progress = Present(ProgressPolicy.lsp)`.

**Files to delete**: none.

**Public API additions**:
- `ProgressPolicy` (case class + companion)
- `ProgressPolicy.lsp`, `ProgressPolicy.mcp` (val)

**Tests**:
- Test 65: `callWithProgress("longTask", req)` with LSP policy: B's handler calls `ctx.progress(begin)`, `ctx.progress(report)`, `ctx.progress(end)`, then returns result; A observes three progress values on `pending.progress` before `pending.result` completes.
- Test 66: `callWithProgress` with LSP policy: `stampOutboundToken` attaches `workDoneToken` to the outbound params; B can read the token from its inbound params.
- Test 67: `callPartialResults[String]("search", req)` with LSP policy: B sends three `$/progress` notifications then an empty-result final response; A's stream emits three strings then closes.
- Test 68: MCP progress: `callWithProgress("run", req)` with MCP policy: outbound params carry `_meta.progressToken`; B's handler sends `notifications/progress` with `progressToken`; A receives the progress value.
- Test 69: `subscribeProgress(token)` returns a stream; when the server subsequently sends a `$/progress` notification with that token, the stream emits the value.
- Test 70: `unsubscribeProgress(token)` after `subscribeProgress(token)`: subsequent `$/progress` notifications for that token are silently dropped, the stream closes.
- Test 71: `ctx.progress(v)` with `progress = Absent` (CDP shape): returns `Unit` without sending any wire notification (verify frame count).
- Test 72: MCP monotonicity: B emits progress values `10.0`, `5.0` (non-monotonic), `20.0`; only `10.0` and `20.0` appear on A's progress stream (the `5.0` is dropped).
- Test 73: LSP non-monotonic: B emits `10.0`, `5.0`, `20.0`; all three appear on A's progress stream (LSP does not enforce monotonicity).
- Test 74: `ctx.progress(v)` called AFTER the handler has returned a value (entry transitioned from `Running` to `Replying`): the call is a no-op, no wire notification is sent.
- Test 75: Out-of-band progress: `extractInboundToken` returns `Absent` for a progress notification with an unknown token; the notification is silently dropped (no error, no crash).
- Test 76: `callPartialResults[String]` where the final response has a non-absent `result`: it is decoded as a last chunk and the stream closes.
- Test 77 (C1 extras propagation for progress): `HandlerCtx.progress(v)` builds the progress notification with `extras = ctx.extras` (the inbound request's extras); verify by capturing the envelope on the transport and asserting the extras slot matches the source request's extras, so MCP routing can find the originating session.
- Test 78 (H4 CAS-loop concurrent monotonicity): two concurrent `ctx.progress(v1=10)` and `ctx.progress(v2=5)` from the same handler both go through monotonicity; only the larger value emits; the smaller silently drops (verify by counting emitted notifications via the transport frame count).

Total tests this phase: **14**

**Verification commands**:
```
sbt 'kyo-jsonrpcJVM/Test/compile' 2>&1 | tail -20
sbt 'kyo-jsonrpcJVM/testOnly *ProgressPolicyTest' 2>&1 | tail -20
```

**Supervision plan**: Verify the monotonicity `AtomicRef[Maybe[BigDecimal]]` lives inside the `progressSink` closure (per-invocation, not per-token global). Verify `progressSink` invalidation is atomic with the `Running → Replying` transition in the writer fiber. Verify `callPartialResults` stream closes on `result = Absent` final response and NOT on a non-absent result (which becomes the last chunk). Verify `subscribeProgress` uses the same `progressStreams` map as `callWithProgress` (same routing path). Verify the monotonicity CAS-loop uses `AtomicRef.update` and reads the current value before comparing (not a simple flag set). Verify the progress notification envelope captures `ctx.extras` from the inbound request (not `Absent`). Convention sweep: `grep -nP '\xe2\x80\x94' <newfiles>` (must be 0, no em-dashes); `grep -n 'AllowUnsafe' <newfiles>` (each occurrence must be on a line within a `// Unsafe:` block); `grep -n ': Option\[' <newfiles>` (must be 0; use Maybe); `grep -nE ';$' <newfiles>` (must be 0, no semicolon line endings); `grep -n 'asInstanceOf' <newfiles>` (must be 0); `grep -n 'private\[kyo\].*=' <newfiles>` (no default params on private[kyo] methods).

**DESIGN.md sections**: §8 (full), §19 (decisions 6, 7, 8, 12, 13).

---

### Phase 7: UnknownMethodPolicy and MessageGate

**Dependency**: Phase 4. Method dispatch step 3 of the reader fiber is where `UnknownMethodPolicy` fires; `MessageGate` is step 2 of the same routing; requires the engine's reader fiber from Phase 4.

**Files to read first**:
- `kyo-jsonrpc/DESIGN.md` §9: `UnknownMethodPolicy`, `UnknownAction` enum, `.lsp` and `.strict` presets, `dollarPrefixOverride`.
- `kyo-jsonrpc/DESIGN.md` §12: `MessageGate` trait, `Decision` enum (`Allow | Reject | Drop`), `beforeDispatch` contract.
- `kyo-jsonrpc/DESIGN.md` §6.2 (steps 2 and 3): gate and unknown-method dispatch positions in reader fiber.

**Files to produce**:
- `kyo-jsonrpc/shared/src/main/scala/kyo/MessageGate.scala`: `trait MessageGate` with `def beforeDispatch(env: JsonRpcEnvelope)(using Frame): MessageGate.Decision < Sync`; `object MessageGate` with `enum Decision` (`Allow | Reject(error: JsonRpcError) | Drop`).
- `kyo-jsonrpc/shared/src/test/scala/kyo/UnknownMethodPolicyTest.scala`: all Phase 7 tests.

**Files to modify**:
- `kyo-jsonrpc/shared/src/main/scala/kyo/UnknownMethodPolicy.scala`: replace the Phase-4 skeleton body with the full implementation: add `UnknownMethodPolicy.lsp` (dollarPrefixOverride=true, requests get MethodNotFound, notifications dropped) and `UnknownMethodPolicy.strict` (Reject on unknown, closes engine) constants while keeping `UnknownMethodPolicy.minimal` as the Phase-4 default value. When `UnknownAction.Reject` fires for a Notification, the engine logs at WARN via `kyo.Log` (no wire response since notifications have no id); the rejection is observable via the log only.
- `kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcEndpointImpl.scala`: wire `UnknownMethodPolicy` into reader fiber step 3; wire `MessageGate` into reader fiber step 2; update `Config` default `unknownMethod = UnknownMethodPolicy.lsp` and `gate = Absent`.

**Files to delete**: none.

**Public API additions / modifications**:
- `UnknownMethodPolicy.lsp`, `UnknownMethodPolicy.strict` added to the Phase-4 skeleton (modifies existing file)
- `UnknownAction` (enum, added to `UnknownMethodPolicy.scala`)
- `MessageGate` (trait, new file)
- `MessageGate.Decision` (enum)

**Tests**:
- Test 79: `UnknownMethodPolicy.lsp` with an unknown request: `A.call("unknown/method", ...)` fails with `Abort[JsonRpcError]` code `-32601`.
- Test 80: `UnknownMethodPolicy.lsp` with an unknown notification starting with `$/`: the notification is silently dropped (no error, no reply, `dollarPrefixOverride = true`).
- Test 81: `UnknownMethodPolicy.lsp` with an unknown notification NOT starting with `$/`: the notification is silently dropped (standard JSON-RPC: drop unknown notifications).
- Test 82: `UnknownMethodPolicy.strict` with an unknown notification: `Reject` action closes the engine (verify via subsequent `call` returning `Abort[Closed]`).
- Test 83: `MessageGate.Decision.Allow`: the gate returns `Allow` and the request reaches the registered handler normally.
- Test 84: `MessageGate.Decision.Reject(error)` for a Request: A receives the error as `Abort[JsonRpcError]` with the gate's error code; no handler is invoked.
- Test 85: `MessageGate.Decision.Reject(error)` for a Notification: the notification is dropped (no reply sent); the engine logs at WARN via `kyo.Log` (no reply channel for notifications).
- Test 86: `MessageGate.Decision.Drop` for a Request: the request is silently dropped; A's `call` hangs until timeout (verify by running with short timeout and observing `Abort[JsonRpcError]` rather than a MethodNotFound reply).
- Test 87: Gate `Allow` for pre-initialized method, gate `Reject(ServerNotInitialized)` for all others: demonstrates the LSP initialize-gate pattern; `A.call("initialize", ...)` succeeds; `A.call("textDocument/hover", ...)` fails with `-32002`.

Total tests this phase: **9**

**Verification commands**:
```
sbt 'kyo-jsonrpcJVM/Test/compile' 2>&1 | tail -20
sbt 'kyo-jsonrpcJVM/testOnly *UnknownMethodPolicyTest' 2>&1 | tail -20
```

**Supervision plan**: Verify `dollarPrefixOverride = true` applies ONLY to notifications (not to requests): an unknown `$/setTrace` request should still return MethodNotFound under LSP policy, not be silently dropped. Verify `MessageGate.beforeDispatch` is `Sync` only (no `Async` in its return type). Verify gate runs AFTER the policy intercept (step 2 after step 1) per §6.2 ordering. Verify `Reject` for a Notification results in no reply frame on the transport (notifications have no response channel). Verify `UnknownAction.Reject` for a Notification uses `kyo.Log.warn(...)` (not a throw or silent drop). Convention sweep: `grep -nP '\xe2\x80\x94' <newfiles>` (must be 0, no em-dashes); `grep -n 'AllowUnsafe' <newfiles>` (each occurrence must be on a line within a `// Unsafe:` block); `grep -n ': Option\[' <newfiles>` (must be 0; use Maybe); `grep -nE ';$' <newfiles>` (must be 0, no semicolon line endings); `grep -n 'asInstanceOf' <newfiles>` (must be 0); `grep -n 'private\[kyo\].*=' <newfiles>` (no default params on private[kyo] methods).

**DESIGN.md sections**: §9 (full), §12 (full), §6.2 (steps 2 and 3).

---

### Phase 8: maxInFlight and requestTimeout

**Dependency**: Phase 5. `requestTimeout` auto-fires the cancellation policy when present; requires the cancellation engine from Phase 5 to be in place.

**Files to read first**:
- `kyo-jsonrpc/DESIGN.md` §11: `Config.maxInFlight: Maybe[Int]`, `Meter.initSemaphore` usage, `notify` bypasses the semaphore.
- `kyo-jsonrpc/DESIGN.md` §7 (timeout section): `Config.requestTimeout: Duration`, `Async.timeout` wrapper, auto-fire policy presence/absence behavior.
- `kyo-core/shared/src/main/scala/kyo/Meter.scala`: `Meter.initSemaphore(n)`, `meter.run(eff)` for semaphore acquisition.
- `kyo-core/shared/src/main/scala/kyo/Async.scala`: `Async.timeout(duration)(effect)` signature.

**Files to produce**:
- `kyo-jsonrpc/shared/src/main/scala/kyo/internal/RateLimitEngine.scala`: `maxInFlightGuard(meter: Maybe[Meter])(eff: ...)` that wraps `call`/`callWithProgress`/`callPartialResults` in `meter.fold(eff)(_.run(eff))`; `timeoutGuard(duration: Duration, id: JsonRpcId, policy: Maybe[CancellationPolicy], ...)(eff: ...)` that wraps `call` in `Async.timeout(duration)` and on timeout: if policy present fires cancel notification, then fails with `Abort[JsonRpcError]` (code per policy's `cancelledError`); if policy absent (`Config.cancellation = Absent`) fires `Abort.fail(JsonRpcError.cancelled(Absent))` locally without emitting a wire notification.
- `kyo-jsonrpc/shared/src/test/scala/kyo/MaxInFlightTest.scala`: all Phase 8 tests.

**Files to modify**:
- `kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcEndpointImpl.scala`: wire `maxInFlightGuard` around `call`, `callWithProgress`, `callPartialResults` (NOT `notify`); wire `timeoutGuard` around `call`; update `Config` to initialize `Meter` in `JsonRpcEndpoint.init` when `maxInFlight = Present(n)`.

**Files to delete**: none.

**Public API additions / modifications**: `Config.maxInFlight` and `Config.requestTimeout` (already in Phase 4 `Config`; this phase adds the implementation).

**Tests**:
- Test 88: `Config.maxInFlight = Present(2)` with three concurrent `call`s: the third parks until one of the first two completes (verify via timing: third call does not start until a semaphore slot is freed).
- Test 89: `notify` is NOT rate-limited by `maxInFlight`: with semaphore fully acquired by two `call`s, a `notify` still goes through immediately.
- Test 90: `Config.requestTimeout = 100.millis` with a handler that awaits a never-completed Promise (deterministically blocking): the timeout fires and the caller receives `Abort.fail(JsonRpcError.cancelled(Absent))` after exactly one timeout (no sleep-based assertions; the handler is blocked by Promise.get on a never-resolved promise so the timeout is the only path that fires).
- Test 91: `requestTimeout` fires with `CancellationPolicy.lsp` present: a `$/cancelRequest` notification appears on the transport (verify frame count increases by 1 for the cancel notification).
- Test 92: `requestTimeout` fires with `cancellation = Absent`: no cancel notification appears on the transport; call fails with `Abort.fail(JsonRpcError.cancelled(Absent))` as a local abort only (per `RateLimitEngine.timeoutGuard` absent-policy case).
- Test 93: Semaphore release on call failure: if a `call` fails (handler aborts), the semaphore slot is released and the next waiting `call` can proceed.
- Test 94 (I3 timeout reset on progress): when an inbound progress notification arrives for an in-flight outbound `call`, the engine resets the `requestTimeout` deadline (MCP spec §4 MAY clause); verify by issuing a 1-second-timeout call where 4 progress notifications at 800ms intervals each reset the deadline so the call does not time out. Note: this engine implements the MAY-clause reset when `Config.progressResetsTimeout = true`; when false (default), the deadline is not reset.

Total tests this phase: **7**

**Verification commands**:
```
sbt 'kyo-jsonrpcJVM/Test/compile' 2>&1 | tail -20
sbt 'kyo-jsonrpcJVM/testOnly *MaxInFlightTest' 2>&1 | tail -20
```

**Supervision plan**: Verify `notify` does NOT acquire from `maxInFlight` meter (per §11 and §20 invariant 4). Verify `Meter.initSemaphoreUnscoped` (or scoped variant) is used and the meter is closed during endpoint `close()` in §6.4 teardown. Verify timeout error code is `-32800` when `CancellationPolicy.lsp` is present and a different error when policy is absent (the `cancelled` factory). Inbound rate limit is intentionally not bounded by the engine (per DESIGN.md §11); consumers may add a Meter at the `JsonRpcMethod` handler if needed; no engine-level test for inbound rate limit. Convention sweep: `grep -nP '\xe2\x80\x94' <newfiles>` (must be 0, no em-dashes); `grep -n 'AllowUnsafe' <newfiles>` (each occurrence must be on a line within a `// Unsafe:` block); `grep -n ': Option\[' <newfiles>` (must be 0; use Maybe); `grep -nE ';$' <newfiles>` (must be 0, no semicolon line endings); `grep -n 'asInstanceOf' <newfiles>` (must be 0); `grep -n 'private\[kyo\].*=' <newfiles>` (no default params on private[kyo] methods).

**DESIGN.md sections**: §11 (full), §7 (timeout subsection), §20 (invariant 4).

---

### Phase 9: Three-Consumer Scenario Tests

**Dependency**: Phase 8. All engine capabilities (cancellation, progress, rate-limiting, timeout, unknown-method policy, gate) must be available for the scenario tests.

**Files to read first**:
- `kyo-jsonrpc/DESIGN.md` §18 phase 9: three scenario descriptions.
- `kyo-jsonrpc/DESIGN.md` §16: consumer coverage proofs to guide scenario assertion shape.
- `kyo-jsonrpc/research/MCP.md`: cancel semantics, progress token, no-reply requirement.
- `kyo-jsonrpc/research/LSP.md`: symmetric endpoints, `$/cancelRequest` must reply, partialResult.
- `kyo-jsonrpc/research/CDP.md`: `maxInFlight=8`, extras/sessionId shape, `notify` for fire-and-forget.

**Files to produce**:
- `kyo-jsonrpc/shared/src/test/scala/kyo/ScenarioHttpStyleTest.scala`: "HTTP-style server-only" scenario tests.
- `kyo-jsonrpc/shared/src/test/scala/kyo/ScenarioWsStyleTest.scala`: "WebSocket-style client + event stream" scenario tests.
- `kyo-jsonrpc/shared/src/test/scala/kyo/ScenarioBidiTest.scala`: "Stdio-style fully bidirectional with cancellation" scenario tests.

**Files to modify**: none.

**Files to delete**: none.

**Public API additions / modifications / removals**: none (test-only phase).

**Tests**:

ScenarioHttpStyleTest:
- Test 95: Single server endpoint with registered `add` and `greet` methods; two sequential `call`s from client side both succeed with correct typed results.
- Test 96: Server endpoint with registered methods; a notification from client triggers handler; client verifies no reply frame arrived (notification semantics preserved in server-only shape).
- Test 97: Server endpoint with `Config.gate = Present(gate)` simulating LSP pre-init gate: requests before "initialize" arrive with error `-32002`; after "initialize" completes, other requests succeed.

ScenarioWsStyleTest:
- Test 98: Client A sends commands to server B; server B interleaves unsolicited `JsonRpcMethod.notification` calls back to A; A's registered notification handler fires for each; no cross-wiring of notification and response frames occurs.
- Test 99: CDP-shape configuration (`codec = Cdp`, `cancellation = Absent`, `progress = Absent`, `maxInFlight = Present(8)`): A sends 8 concurrent `call`s; all 8 each `Promise.get` a never-completed Promise (deterministically blocking); the 9th's submit-future does not start until one of the first 8's holding promises is manually completed (verify via Latch handoff, not wallclock).
- Test 100: CDP-shape extras: A uses `ExtrasEncoder.const(Record(Chunk("sessionId" -> Str("s1"))))` on each call; B receives envelopes with top-level `sessionId` field in `extras`; `Cdp` codec correctly demuxes extras from wire fields.

ScenarioBidiTest:
- Test 101: Both endpoints A and B register methods; A calls B and B calls A simultaneously; both resolve correctly without id-space collision (per-direction id allocation per §10 / §20 invariant 7).
- Test 102: LSP bidirectional cancellation: A calls B; B registers `$/cancelRequest`; A cancels; B's handler observes `ctx.cancelled`; B sends a response with `JsonRpcError.RequestCancelled`; A receives error code `-32800`; the response IS present on the transport (LSP `expectReplyForCancelledRequest = true`).
- Test 103: MCP bidirectional cancellation: A calls B with MCP policy; B registers `notifications/cancelled`; A cancels; B's handler is interrupted; NO response frame appears on the transport (MCP `expectReplyForCancelledRequest = false`).
- Test 104: Full LSP progress round-trip: A calls B with `callWithProgress`; B uses `ctx.progress` to send three workDone progress notifications; A observes them on `pending.progress`; final typed result arrives on `pending.result`.

Total tests this phase: **10**

**Verification commands**:
```
sbt 'kyo-jsonrpcJVM/Test/compile' 2>&1 | tail -20
sbt 'kyo-jsonrpcJVM/testOnly *ScenarioHttpStyleTest' 2>&1 | tail -20
sbt 'kyo-jsonrpcJVM/testOnly *ScenarioWsStyleTest' 2>&1 | tail -20
sbt 'kyo-jsonrpcJVM/testOnly *ScenarioBidiTest' 2>&1 | tail -20
```

**Supervision plan**: For Test 103 (MCP no-reply), verify by counting frames on the transport after cancellation: zero `Response` frames for the cancelled request id. For Test 102 (LSP must-reply), verify exactly one `Response` frame with the right id. For Test 99 (CDP maxInFlight), verify the 9th call parks by checking the Latch-handoff pattern: the 9th submit-future does not complete before one of the first 8's never-completed promises is manually resolved. Convention sweep: `grep -nP '\xe2\x80\x94' <newfiles>` (must be 0, no em-dashes); `grep -n 'AllowUnsafe' <newfiles>` (each occurrence must be on a line within a `// Unsafe:` block); `grep -n ': Option\[' <newfiles>` (must be 0; use Maybe); `grep -nE ';$' <newfiles>` (must be 0, no semicolon line endings); `grep -n 'asInstanceOf' <newfiles>` (must be 0); `grep -n 'private\[kyo\].*=' <newfiles>` (no default params on private[kyo] methods).

**DESIGN.md sections**: §16 (all three coverage proofs), §18 (phase 9 scenario descriptions).

---

### Phase 10: Cross-Platform Sweep

**Dependency**: Phase 9, which requires all source and test files to be complete before the platform sweep.

**Files to read first**:
- `kyo-jsonrpc/STEERING.md`: sequential test run requirement (`feedback_sequential_test_runs`); no new test files.
- `build.sbt` kyo-jsonrpc block (just written): `nativeSettings`, `jsSettings` entries to confirm.

**Files to produce**: none (this phase produces no new source or test files).

**Files to modify**: `kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcEndpointImpl.scala` or any file where platform-specific issues surface (only if compilation failures are found during the sweep). All fixes must be in `shared/` (no demoting to `jvm/` or `js/`).

**Files to delete**: none.

**Public API additions / modifications / removals**: none.

**Tests**: none (Phase 10 runs all existing tests; it produces no new test leaves).

**Verification commands** (run sequentially, not in parallel):
```
sbt 'kyo-jsonrpcJVM/test' 2>&1 | tail -40
sbt 'kyo-jsonrpcJS/test' 2>&1 | tail -40
sbt 'kyo-jsonrpcNative/test' 2>&1 | tail -40
sbt scalafmtCheckAll 2>&1 | grep -E "kyo-jsonrpc|error" | head -20
```

**Supervision plan**: Verify total test count matches the master table sum (104 test leaves) on all three platforms. If any platform fails, the fix must go into `shared/` (never into a platform folder). `scalafmtCheckAll` must report zero violations in `kyo-jsonrpc/` files. Check that no `// TODO Phase N` markers remain anywhere in `kyo-jsonrpc/`. Convention sweep: `grep -rnP '\xe2\x80\x94' kyo-jsonrpc/shared/src` (must be 0, no em-dashes); `grep -rn 'AllowUnsafe' kyo-jsonrpc/shared/src` (each occurrence must be on a line within a `// Unsafe:` block); `grep -rn ': Option\[' kyo-jsonrpc/shared/src` (must be 0; use Maybe); `grep -rnE ';$' kyo-jsonrpc/shared/src` (must be 0, no semicolon line endings); `grep -rn 'asInstanceOf' kyo-jsonrpc/shared/src` (must be 0); `grep -rn 'private\[kyo\].*=' kyo-jsonrpc/shared/src` (no default params on private[kyo] methods).

**DESIGN.md sections**: §18 (phase 10), §21 (what this design does NOT do).

---

## 3. Cross-Cutting Test Plan

| # | Phase | Class | Scenario | DESIGN section |
|---|---|---|---|---|
| 1 | 0 | JsonRpcSmokeTest | Scaffold compiles and test harness bootstraps | §18 p0 |
| 2 | 1 | JsonRpcCodecTest | Strict2_0 encodes Request with jsonrpc field, id as Integer, no params key | §3 |
| 3 | 1 | JsonRpcCodecTest | Strict2_0 round-trips Request through encode then decode | §3 |
| 4 | 1 | JsonRpcCodecTest | JsonRpcId.Num(1L) Schema encodes to Integer(1L) not Record | §3, §15 |
| 5 | 1 | JsonRpcCodecTest | JsonRpcId.Str("req-1") Schema encodes to Str("req-1") not Record | §3, §15 |
| 6 | 1 | JsonRpcCodecTest | Strict2_0 Notification encodes with no "id" key | §3, §20 inv11 |
| 7 | 1 | JsonRpcCodecTest | Strict2_0 Response with result encodes with no "error" key | §3 |
| 8 | 1 | JsonRpcCodecTest | Strict2_0 decode of result+error populated record produces Malformed | §3 |
| 9 | 1 | JsonRpcCodecTest | Strict2_0 decode of "id: null" produces Maybe[JsonRpcId] = Absent | §3, §19 dec1 |
| 10 | 1 | JsonRpcCodecTest | Cdp encode stamps extras keys at top level | §3 |
| 11 | 1 | JsonRpcCodecTest | Cdp encode rejects reserved key "method" in extras | §3 |
| 12 | 1 | JsonRpcCodecTest | Cdp decode harvests unknown top-level fields into extras | §3 |
| 13 | 1 | JsonRpcCodecTest | Strict2_0 decode of unparseable record produces Malformed | §3, §20 inv5 |
| 14 | 1 | JsonRpcCodecTest | extras Absent vs Present(Null) are distinct on the wire | §3, §19 dec1 |
| 15 | 1 | JsonRpcCodecTest | JsonRpcError.cancelled(Present) has code -32800 with data | §15 |
| 16 | 1 | JsonRpcCodecTest | JsonRpcResponse.success enforces result-present/error-absent; failure enforces error-present/result-absent | §15, M9 |
| 17 | 1 | JsonRpcCodecTest | Cdp omits jsonrpc field; Strict2_0 always includes it | §3 |
| 18 | 1 | JsonRpcCodecTest | summon[Schema[JsonRpcResponse]] compiles and round-trips a sample success response | §3, C6 |
| 19 | 2 | JsonRpcMethodTest | Successful handler encodes result via schemaOut | §5 |
| 20 | 2 | JsonRpcMethodTest | Handler Abort.fail(JsonRpcError) propagates without conversion | §5 |
| 21 | 2 | JsonRpcMethodTest | Handler panic converts to Abort.fail(internalError) with message in data | §5, §20 inv9 |
| 22 | 2 | JsonRpcMethodTest | Params decode failure produces invalidParams before handler runs | §5 |
| 23 | 2 | JsonRpcMethodTest | Notification handler handle returns Null unit; kind equals Notification | §5 |
| 24 | 2 | JsonRpcMethodTest | HandlerCtx.extras forwarded verbatim from inbound envelope | §5 |
| 25 | 2 | JsonRpcMethodTest | HandlerCtx.progress with progressSink Absent is a no-op | §5, §8 |
| 26 | 2 | JsonRpcMethodTest | No-ctx overload behaves identically to ctx overload | §5 |
| 27 | 3 | JsonRpcTransportTest | inMemory send on A received by B | §4 |
| 28 | 3 | JsonRpcTransportTest | inMemory send on B received by A | §4 |
| 29 | 3 | JsonRpcTransportTest | Close A causes subsequent A send to fail with Closed | §4 |
| 30 | 3 | JsonRpcTransportTest | Close A terminates B's incoming stream | §4 |
| 31 | 3 | JsonRpcTransportTest | Backpressure: slow consumer parks fast producer; ordering preserved | §4, §20 inv2 |
| 32 | 3 | JsonRpcTransportTest | Transport close while send is parked unblocks with Closed | §4 |
| 33 | 4 | JsonRpcEndpointTest | A.call(B) round-trip with typed request and response | §6 |
| 34 | 4 | JsonRpcEndpointTest | A.notify(B): handler runs, A gets no reply, exactly one transport frame | §6, §20 inv4 |
| 35 | 4 | JsonRpcEndpointTest | Simultaneous bidirectional calls resolve without cross-wiring | §6 |
| 36 | 4 | JsonRpcEndpointTest | Multiple concurrent calls resolve independently | §6 |
| 37 | 4 | JsonRpcEndpointTest | Unknown method request returns MethodNotFound error code -32601 | §6.2, §9 |
| 38 | 4 | JsonRpcEndpointTest | Scope exit drains Exchange pending map; subsequent call fails with Closed | §6.4 |
| 39 | 4 | JsonRpcEndpointTest | Pending call with abortSignal observes Abort.fail(Closed) after scope exits (callerRegistry drain) | §6.4 |
| 40 | 4 | JsonRpcEndpointTest | callerRegistry is empty after call completes normally | §6.1 |
| 41 | 4 | JsonRpcEndpointTest | callerRegistry is empty after caller fiber is interrupted | §6.1, §6.5 |
| 42 | 4 | JsonRpcEndpointTest | Transport closed mid-call: call fails with Closed not hang | §6.4, §4 |
| 43 | 4 | JsonRpcEndpointTest | awaitDrain returns when all pending resolved and no new calls | §6 |
| 44 | 4 | JsonRpcEndpointTest | Late reply for cancelled outbound dropped by Exchange silently | §6.5, §20 inv3 |
| 45 | 4 | JsonRpcEndpointTest | cancel with no policy: caller fails with cancelled(Absent), no wire notification | §7 |
| 46 | 4 | JsonRpcEndpointTest | ExtrasEncoder.const causes extras in outbound envelope | §6 |
| 47 | 4 | JsonRpcEndpointTest | SequentialLong produces Num(1), Num(2), Num(3) | §10 |
| 48 | 4 | JsonRpcEndpointTest | SequentialInt produces Num(1), Num(2), Num(3) in Int range | §10 |
| 49 | 4 | JsonRpcEndpointTest | After endpoint.close(), no further outbound writes hit transport (I9 exit-after-shutdown drain) | §6.4 |
| 50 | 4 | JsonRpcEndpointTest | Custom(next) called from 100 concurrent fibers produces 100 distinct ids (I15) | §10 |
| 51 | 5 | CancellationPolicyTest | LSP inbound cancel: ctx.cancelled completes, caller gets -32800 | §7 |
| 52 | 5 | CancellationPolicyTest | LSP inbound cancel: response IS sent on transport | §7 |
| 53 | 5 | CancellationPolicyTest | MCP inbound cancel: no response frame on transport | §7, §6.5 |
| 54 | 5 | CancellationPolicyTest | MCP cancel race with fast handler: queued reply suppressed | §6.5 |
| 55 | 5 | CancellationPolicyTest | LSP outbound cancel: $/cancelRequest notification sent, caller gets -32800 | §7 |
| 56 | 5 | CancellationPolicyTest | MCP outbound cancel: notifications/cancelled sent with requestId and reason | §7 |
| 57 | 5 | CancellationPolicyTest | cancel for protected method (initialize): no notification, no abort, warning logged | §7, §19 dec10 |
| 58 | 5 | CancellationPolicyTest | cancel for absent callerRegistry id: warning logged, no notification | §7, C5 |
| 59 | 5 | CancellationPolicyTest | cancel for unknown pendingInbound id: silently dropped | §7 |
| 60 | 5 | CancellationPolicyTest | Timeout with LSP policy: $/cancelRequest sent AND caller gets -32800 | §7 |
| 61 | 5 | CancellationPolicyTest | Timeout with Absent policy: no cancel notification, local abort only | §7 |
| 62 | 5 | CancellationPolicyTest | Handler Abort.fail(ContentModified) sends -32801 verbatim on LSP cancel | §7, I4 |
| 63 | 5 | CancellationPolicyTest | endpoint.cancel(id) propagates callerRegistry[id].extras into cancel notification (C1) | §7, C1 |
| 64 | 5 | CancellationPolicyTest | Latch-paused encode: cancel during encode race; encoded envelope sent, caller sees cancelled (H2) | §6.1, H2 |
| 65 | 6 | ProgressPolicyTest | callWithProgress LSP: three progress values arrive before result | §8 |
| 66 | 6 | ProgressPolicyTest | callWithProgress stamps workDoneToken in outbound params | §8, ProgressPolicy.lsp |
| 67 | 6 | ProgressPolicyTest | callPartialResults LSP: three chunks then empty-result closes stream | §8 |
| 68 | 6 | ProgressPolicyTest | callWithProgress MCP: outbound params carry _meta.progressToken; progress arrives | §8, ProgressPolicy.mcp |
| 69 | 6 | ProgressPolicyTest | subscribeProgress returns stream that emits on matching progress notification | §8 |
| 70 | 6 | ProgressPolicyTest | unsubscribeProgress closes stream; subsequent notifications silently dropped | §8 |
| 71 | 6 | ProgressPolicyTest | progress with Absent policy is no-op; no wire notification sent | §8 |
| 72 | 6 | ProgressPolicyTest | MCP monotonicity: non-monotonic value dropped, only increasing values emitted | §8, I14 |
| 73 | 6 | ProgressPolicyTest | LSP non-monotonic: all values emitted (no monotonicity enforcement) | §8 |
| 74 | 6 | ProgressPolicyTest | ctx.progress after handler returned is suppressed, no wire notification | §8, §19 dec12 |
| 75 | 6 | ProgressPolicyTest | Unknown progress token silently dropped | §8 |
| 76 | 6 | ProgressPolicyTest | callPartialResults: non-absent final result decoded as last chunk then closed | §8, §19 dec7 |
| 77 | 6 | ProgressPolicyTest | ctx.progress stamps ctx.extras onto progress notification (C1) | §8, C1 |
| 78 | 6 | ProgressPolicyTest | Two concurrent ctx.progress(10) and ctx.progress(5): only larger emits (H4 CAS-loop) | §8, H4 |
| 79 | 7 | UnknownMethodPolicyTest | LSP unknown request: caller gets -32601 | §9 |
| 80 | 7 | UnknownMethodPolicyTest | LSP dollarPrefixOverride: unknown $/ notification silently dropped | §9 |
| 81 | 7 | UnknownMethodPolicyTest | LSP unknown notification (non-$/): silently dropped | §9 |
| 82 | 7 | UnknownMethodPolicyTest | Strict unknown notification: Reject closes engine | §9 |
| 83 | 7 | UnknownMethodPolicyTest | Gate Allow: request reaches handler normally | §12 |
| 84 | 7 | UnknownMethodPolicyTest | Gate Reject(error) for Request: caller gets gate error code | §12 |
| 85 | 7 | UnknownMethodPolicyTest | Gate Reject(error) for Notification: silently dropped, no reply, warn logged | §12 |
| 86 | 7 | UnknownMethodPolicyTest | Gate Drop for Request: request dropped, caller times out | §12 |
| 87 | 7 | UnknownMethodPolicyTest | Gate pattern: pre-init Allow/Reject simulates LSP initialize gate | §12, §16.1 |
| 88 | 8 | MaxInFlightTest | maxInFlight=2: third call parks until a slot frees | §11 |
| 89 | 8 | MaxInFlightTest | notify bypasses maxInFlight semaphore | §11, §20 inv4 |
| 90 | 8 | MaxInFlightTest | requestTimeout: handler awaits never-completed Promise; caller gets cancelled(Absent) | §7 |
| 91 | 8 | MaxInFlightTest | Timeout with LSP policy: $/cancelRequest notification sent | §7 |
| 92 | 8 | MaxInFlightTest | Timeout with Absent policy: no cancel notification, local Abort.fail(cancelled(Absent)) | §7 |
| 93 | 8 | MaxInFlightTest | Semaphore released after call failure; next waiting call proceeds | §11 |
| 94 | 8 | MaxInFlightTest | Progress notification resets requestTimeout deadline (I3 MAY-clause) | §7, I3 |
| 95 | 9 | ScenarioHttpStyleTest | Two sequential HTTP-style calls both succeed | §16 |
| 96 | 9 | ScenarioHttpStyleTest | HTTP-style notification: handler runs, no reply frame on transport | §16 |
| 97 | 9 | ScenarioHttpStyleTest | LSP initialize gate: pre-init fails -32002; post-init succeeds | §16.1, §12 |
| 98 | 9 | ScenarioWsStyleTest | WS client+event: interleaved notifications and responses demuxed correctly | §16.3 |
| 99 | 9 | ScenarioWsStyleTest | CDP shape maxInFlight=8: 8 concurrent calls; 9th parks until Latch-released | §16.3, §11 |
| 100 | 9 | ScenarioWsStyleTest | CDP extras sessionId appears at top level via Cdp codec | §16.3, §3 |
| 101 | 9 | ScenarioBidiTest | Both endpoints call each other simultaneously; no id-space collision | §10, §20 inv7 |
| 102 | 9 | ScenarioBidiTest | LSP bidirectional cancel: reply IS sent with RequestCancelled code -32800 | §16.1, §7 |
| 103 | 9 | ScenarioBidiTest | MCP bidirectional cancel: NO reply frame on transport | §16.2, §7 |
| 104 | 9 | ScenarioBidiTest | Full LSP progress: three workDone notifications then typed result | §16.1, §8 |

**Total test count: 104**

---

## 4. Cross-Cutting Public API Surface

Every public name in package `kyo` landed by end of Phase 9, traced to its phase:

| Name | Kind | Phase |
|---|---|---|
| `JsonRpcId` | `enum` | 1 |
| `JsonRpcId.Num` | case | 1 |
| `JsonRpcId.Str` | case | 1 |
| `given Schema[JsonRpcId]` | given | 1 |
| `JsonRpcError` | `case class` | 1 |
| `JsonRpcError.ParseError` | val | 1 |
| `JsonRpcError.InvalidRequest` | val | 1 |
| `JsonRpcError.MethodNotFound` | val | 1 |
| `JsonRpcError.InvalidParams` | val | 1 |
| `JsonRpcError.InternalError` | val | 1 |
| `JsonRpcError.ServerNotInitialized` | val | 1 |
| `JsonRpcError.UnknownErrorCode` | val | 1 |
| `JsonRpcError.RequestCancelled` | val | 1 |
| `JsonRpcError.ContentModified` | val | 1 |
| `JsonRpcError.ServerCancelled` | val | 1 |
| `JsonRpcError.RequestFailed` | val | 1 |
| `JsonRpcError.methodNotFound` | def | 1 |
| `JsonRpcError.invalidRequest` | def | 1 |
| `JsonRpcError.invalidParams` | def | 1 |
| `JsonRpcError.internalError` | def | 1 |
| `JsonRpcError.cancelled` | def | 1 |
| `JsonRpcRequest` | `case class` | 1 |
| `JsonRpcResponse` | `case class` | 1 |
| `JsonRpcResponse.success` | def | 1 |
| `JsonRpcResponse.failure` | def | 1 |
| `JsonRpcEnvelope` | `enum` | 1 |
| `JsonRpcEnvelope.Request` | case | 1 |
| `JsonRpcEnvelope.Notification` | case | 1 |
| `JsonRpcEnvelope.Response` | case | 1 |
| `JsonRpcEnvelope.Malformed` | case | 1 |
| `JsonRpcCodec` | `trait` | 1 |
| `JsonRpcCodec.Strict2_0` | val | 1 |
| `JsonRpcCodec.Cdp` | val | 1 |
| `HandlerCtx` | `final class` | 2 |
| `HandlerCtx.cancelled` | val | 2 |
| `HandlerCtx.requestId` | val | 2 |
| `HandlerCtx.extras` | val | 2 |
| `HandlerCtx.progress` | def | 2 |
| `JsonRpcMethod[+S]` | `sealed trait` | 2 |
| `JsonRpcMethod.Kind` | `enum` | 2 |
| `JsonRpcMethod.Kind.Request` | case | 2 |
| `JsonRpcMethod.Kind.Notification` | case | 2 |
| `JsonRpcMethod.apply` (with ctx) | def | 2 |
| `JsonRpcMethod.apply` (no ctx) | def | 2 |
| `JsonRpcMethod.notification` | def | 2 |
| `JsonRpcTransport` | `trait` | 3 |
| `JsonRpcTransport.inMemory` | def | 3 |
| `ExtrasEncoder` | `type` | 4 |
| `ExtrasEncoder.empty` | val | 4 |
| `ExtrasEncoder.const` | def | 4 |
| `IdStrategy` | `enum` | 4 |
| `IdStrategy.SequentialLong` | case | 4 |
| `IdStrategy.SequentialInt` | case | 4 |
| `IdStrategy.Custom` | case | 4 |
| `JsonRpcEndpoint` | `final class` | 4 |
| `JsonRpcEndpoint.call` | def | 4 |
| `JsonRpcEndpoint.notify` | def | 4 |
| `JsonRpcEndpoint.callWithProgress` | def | 4/6 |
| `JsonRpcEndpoint.callPartialResults` | def | 4/6 |
| `JsonRpcEndpoint.subscribeProgress` | def | 4/6 |
| `JsonRpcEndpoint.unsubscribeProgress` | def | 4/6 |
| `JsonRpcEndpoint.cancel` | def | 4 |
| `JsonRpcEndpoint.awaitDrain` | def | 4 |
| `JsonRpcEndpoint.close` | def | 4 |
| `JsonRpcEndpoint.Config` | `case class` | 4 |
| `JsonRpcEndpoint.Pending[Out]` | `final class` | 4/6 |
| `JsonRpcEndpoint.Pending.id` | val | 4/6 |
| `JsonRpcEndpoint.Pending.result` | val | 4/6 |
| `JsonRpcEndpoint.Pending.progress` | val | 6 |
| `JsonRpcEndpoint.Pending.cancel` | val | 6 |
| `JsonRpcEndpoint.init` | def | 4 |
| `CancellationPolicy` | `final case class` | 5 |
| `CancellationPolicy.ParamsEncoder` | `type` | 5 |
| `CancellationPolicy.lsp` | val | 5 |
| `CancellationPolicy.mcp` | val | 5 |
| `ProgressPolicy` | `final case class` | 6 |
| `ProgressPolicy.lsp` | val | 6 |
| `ProgressPolicy.mcp` | val | 6 |
| `UnknownMethodPolicy` | `final case class` | 7 |
| `UnknownAction` | `enum` | 7 |
| `UnknownAction.ReplyMethodNotFound` | case | 7 |
| `UnknownAction.Drop` | case | 7 |
| `UnknownAction.Reject` | case | 7 |
| `UnknownMethodPolicy.lsp` | val | 7 |
| `UnknownMethodPolicy.strict` | val | 7 |
| `MessageGate` | `trait` | 7 |
| `MessageGate.Decision` | `enum` | 7 |
| `MessageGate.Decision.Allow` | case | 7 |
| `MessageGate.Decision.Reject` | case | 7 |
| `MessageGate.Decision.Drop` | case | 7 |

---

## 5. Risk and Mitigation Per Phase

**Phase 0**: Risk: `CrossType.Full` triggers unexpected directory layout requiring `jvm/`, `js/`, `native/` source dirs even when all source is shared; mitigate by following `kyo-schema` exactly and verifying `shared/src/main/scala/` is picked up before any platform dirs.

**Phase 1**: Risk: hand-written `Schema[JsonRpcId]` produces a wrapper shape (`Record("Num", Integer(v))`) instead of the flat shape (`Integer(v)`); mitigate by adding Test 4 and Test 5 as the very first tests run, and by reading existing hand-written schemas in kyo-schema for the pattern.

**Phase 2**: Risk: the panic-catch in `JsonRpcMethod.handle` uses a catch-all `case _ =>` that accidentally swallows non-panic results; mitigate by using `Abort.run[JsonRpcError]` and matching explicitly on `Result.Panic(t)` only, leaving `Success` and `Failure` untouched.

**Phase 3**: Risk: `inMemory` close propagation causes a deadlock if both transport channels try to close each other re-entrantly; mitigate by using a single atomic flag and closing channels independently (close A's outbound, B's outbound separately, not circularly).

**Phase 4**: Risk: `callerRegistry` population outside the Exchange `encode` callback creates a race window where a cancel arrives before registration; this is the exact C4 critical audit finding. Mitigate: the impl agent MUST read §6.1 and C4 audit before writing a single line of `JsonRpcEndpointImpl.scala` and confirm the `encode` callback pattern.

**Phase 5**: Risk: MCP no-reply guarantee violated when a cancel races a fast handler completing and enqueuing its reply; this is the C3 critical audit finding. Mitigate: the three-state `InboundEntry.Cancelled` variant (Phase 4) and the `SuppressIfCancelled` writer check are the exact fix; Test 50 exercises this race explicitly.

**Phase 6**: Risk: `callPartialResults` stream never closes if the final response has a non-absent `result` (the `result = Absent` close condition is easy to misread as the only close path); mitigate by Test 70 which exercises the non-absent-result close path explicitly.

**Phase 7**: Risk: `dollarPrefixOverride` applied to requests (not just notifications) causing `$/cancelRequest` requests to be silently dropped instead of getting MethodNotFound; mitigate by verifying in supervision that the override is guarded by `env.isInstanceOf[Notification]`.

**Phase 8**: Risk: `Meter.initSemaphoreUnscoped` (or scoped variant) not being closed during endpoint teardown, causing semaphore to leak; mitigate by adding the `meter.close` step to the §6.4 finalizer order in `JsonRpcEndpointImpl.scala`.

**Phase 9**: Risk: scenario tests are too coarse and pass even when individual policy semantics are wrong (e.g. Test 103 verifying no reply by counting frames but missing the case where the reply appears out-of-order); mitigate by also asserting the transport's total outbound frame count after a bounded wait, not just testing for absence of a specific id response.

**Phase 10**: Risk: JS single-threaded scheduler reorders operations in progress monotonicity tests, causing spurious failures; mitigate by ensuring progress emission uses `Channel.Unsafe.offer` (non-blocking) in the reader fiber and the monotonicity CAS is fiber-local (no cross-fiber contention on JS).

---

End of plan. Total phases: 11 (Phase 0 through Phase 10). Total tests: 104.
