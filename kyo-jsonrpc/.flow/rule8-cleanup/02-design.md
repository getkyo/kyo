# 02 Design: Rule 8 (Organization) cleanup in kyo-jsonrpc

Task type: refactor
Cites exploration: ./01-exploration.md
Primary module: kyo-jsonrpc
Scope: `kyo-jsonrpc/shared/src/`

## Goal

After this campaign, `flow-verify-organization.sh` exits 0 against `kyo-jsonrpc/shared/src/`. Every `package kyo` source file in `shared/src/main/scala/kyo/` carries an explicit PUBLIC, SPLIT, or INTERNAL verdict (per the design contract `## Package surface verdicts` section), with PUBLIC files marked at the top by `// flow-allow: PUBLIC <rationale>`, SPLIT files keeping a public head and relocating named sub-symbols to `kyo/internal/`, and INTERNAL files moved into `kyo/internal/` wholesale. Every file holds exactly one top-level type plus its companion (8b). Every main source has a matching focused test file in `shared/src/test/scala/kyo/` (8c HARD). The three multi-source scenario specs and `MaxInFlightTest` move to `shared/src/test/scala/kyo/scenario/`. The shared base spec renames from `Test` to `JsonRpcTestBase`. No user-visible API changes (no public symbol's FQN or signature shifts).

## Package surface verdicts (Rule 8a, MANDATORY)

Every `kyo-jsonrpc/shared/src/main/scala/kyo/*.scala` file (excluding `kyo/internal/`) ships into `import kyo.*` and is verdicted explicitly. The 15 files at HEAD:

- `CancellationPolicy.scala`: PUBLIC ; user constructs `JsonRpcEndpoint.Config(cancellation = Present(CancellationPolicy.lsp))` (JsonRpcEndpoint.scala:61, with user call sites at CancellationPolicyTest.scala:11, ScenarioBidiTest.scala:63, MaxInFlightTest.scala:185, ProgressPolicyTest.scala:32). The `lsp` and `mcp` presets are the documented surface. Marker: `// flow-allow: PUBLIC config-policy type referenced by JsonRpcEndpoint.Config.cancellation field`.

- `ExtrasEncoder.scala`: PUBLIC ; opaque type used as `JsonRpcEndpoint.call/notify/callWithProgress/callPartialResults` parameter with default `ExtrasEncoder.empty` (JsonRpcEndpoint.scala:10, :17, :24, :31). User construction via `ExtrasEncoder.const(...)` (JsonRpcEndpointTest.scala:353, ScenarioWsStyleTest.scala:170) and the apply-with-lambda form (JsonRpcEndpointTest.scala:248, :284, :364; CancellationPolicyTest.scala:70 plus 10 more sites). Existing top-of-file marker `// flow-allow: opaque-type companion carve-out (FLOW Decision #30 (b))` on the extension at line 13 stays; add a PUBLIC marker at file top.

- `HandlerCtx.scala`: PUBLIC ; second parameter of every `JsonRpcMethod.apply` / `JsonRpcMethod.notification` handler (JsonRpcMethod.scala:25, :44). Users receive `HandlerCtx` instances in their handlers. The `private[kyo]` constructor at HandlerCtx.scala:12 stays with a `// flow-allow:` citing Hub.scala:22 (smart-constructor: framework creates instances, users only consume). The `forTest` factory at HandlerCtx.scala:25 stays `private[kyo]` with `// flow-allow:` rationale "test-only construction escape hatch". Marker: `// flow-allow: PUBLIC handler-context receiver consumed by user JsonRpcMethod handlers`.

- `IdStrategy.scala`: SPLIT ; `enum IdStrategy` stays PUBLIC (referenced as `JsonRpcEndpoint.Config.idStrategy` field default `IdStrategy.SequentialLong` at JsonRpcEndpoint.scala:67). `private[kyo] def mkNextId` (IdStrategy.scala:10) moves to a new `private[kyo] object IdStrategyEngine` in `kyo/internal/IdStrategyEngine.scala`; sole caller at `internal/JsonRpcEndpointImpl.scala` (line 735 per v1 design) rewrites to `IdStrategyEngine.mkNextId(config.idStrategy)`. Marker on the remaining file: `// flow-allow: PUBLIC config-strategy sum type referenced by JsonRpcEndpoint.Config.idStrategy field`.

- `JsonRpcCodec.scala`: SPLIT ; `trait JsonRpcCodec` plus companion presets `Strict2_0` and `Cdp` stay PUBLIC (referenced as `JsonRpcEndpoint.Config.codec` field default `JsonRpcCodec.Strict2_0` at JsonRpcEndpoint.scala:60; test references at JsonRpcCodecTest.scala). `private[kyo] val cdpReservedKeys` (JsonRpcCodec.scala:17) moves into `internal/JsonRpcCodecImpl.scala` as a `private val` on the impl object (its sole consumer at JsonRpcCodecImpl.scala lines 151 and 172 per v1 design). Marker: `// flow-allow: PUBLIC codec interface referenced by JsonRpcEndpoint.Config.codec field`.

- `JsonRpcEndpoint.scala`: PUBLIC ; the entire public endpoint surface (`call`, `notify`, `callWithProgress`, `callPartialResults`, `subscribeProgress`, `unsubscribeProgress`, `cancel`, `awaitDrain`, `close` plus `Config` and `Pending`). User entry point is `JsonRpcEndpoint.init(transport, methods, config)` at JsonRpcEndpoint.scala:71, exercised by every test (JsonRpcEndpointTest.scala, MaxInFlightTest.scala, ScenarioBidiTest.scala, ScenarioHttpStyleTest.scala, ScenarioWsStyleTest.scala, CancellationPolicyTest.scala, ProgressPolicyTest.scala, UnknownMethodPolicyTest.scala). The `private[kyo]` constructor at JsonRpcEndpoint.scala:5 and the `private[kyo]` constructor on `Pending[Out]` at JsonRpcEndpoint.scala:52 both stay with `// flow-allow:` rationales citing Hub.scala:22 (smart-constructor: `init` is the only public path). Marker: `// flow-allow: PUBLIC primary user-facing endpoint surface`.

- `JsonRpcEnvelope.scala`: PUBLIC ; appears in user-facing `JsonRpcTransport` interface (`send(env: JsonRpcEnvelope)` and `incoming: Stream[JsonRpcEnvelope, ...]` at JsonRpcTransport.scala:6, :7) which users implement (ScenarioHttpStyleTest.scala:88, UnknownMethodPolicyTest.scala:95 build custom transports that handle `JsonRpcEnvelope` values; MessageGate's `beforeDispatch(env: JsonRpcEnvelope)` at MessageGate.scala:4 also exposes envelopes to user test doubles, e.g. UnknownMethodPolicyTest.scala:96). Tests construct envelopes directly (JsonRpcTransportTest.scala:7-9, JsonRpcCodecTest.scala:27, :43, :74, :120). Marker: `// flow-allow: PUBLIC wire-shape ADT exposed through JsonRpcTransport and MessageGate user implementations`.

- `JsonRpcError.scala`: PUBLIC ; the documented error-channel ADT. Users construct via constants (`JsonRpcError.InvalidParams`, `JsonRpcError.MethodNotFound`, `JsonRpcError.ContentModified`) and smart constructors (`JsonRpcError.cancelled`, `JsonRpcError.methodNotFound`, `JsonRpcError.invalidRequest`, `JsonRpcError.invalidParams`, `JsonRpcError.internalError`). Test usage: JsonRpcMethodTest.scala:39, :43; CancellationPolicyTest.scala:507; JsonRpcCodecTest.scala:180, :187, :190; MaxInFlightTest.scala:147, :170, :258. Referenced in public effect rows as `Abort[JsonRpcError | Closed]` (JsonRpcEndpoint.scala:11, :25, :32). Case class constructor is fully public (no `private[kyo]`). Marker: `// flow-allow: PUBLIC error-channel ADT appearing in JsonRpcEndpoint Abort rows and user error matching`.

- `JsonRpcId.scala`: PUBLIC ; appears in `JsonRpcEndpoint.cancel(id: JsonRpcId, ...)` (JsonRpcEndpoint.scala:41), in `JsonRpcEndpoint.Pending[Out].id` (JsonRpcEndpoint.scala:53), in `ExtrasEncoder` signatures (ExtrasEncoder.scala:3, :6), in `HandlerCtx.requestId` field (HandlerCtx.scala:14), and in test constructions (CancellationPolicyTest.scala:61 and many more; JsonRpcTransportTest.scala:7-9; JsonRpcCodecTest.scala:51, :56; ScenarioBidiTest.scala:56). Enum cases are fully public (no `private[kyo]`). The `// flow-allow:` at JsonRpcId.scala:22 (existing) covers a string-literal grep false positive and stays. Marker: `// flow-allow: PUBLIC id ADT referenced by JsonRpcEndpoint.cancel, Pending.id, ExtrasEncoder, HandlerCtx.requestId`.

- `JsonRpcMethod.scala`: PUBLIC ; built by user via `JsonRpcMethod.apply(name)(handler)` and `JsonRpcMethod.notification(name)(handler)` (JsonRpcMethod.scala:25, :44), passed to `JsonRpcEndpoint.init(transport, methods, ...)` (JsonRpcEndpoint.scala:73). Used in every spec (JsonRpcMethodTest.scala, JsonRpcEndpointTest.scala, ScenarioBidiTest.scala, etc). Sealed trait with `private[kyo]` abstract members (`schemaIn`, `schemaOut`, `handle` at JsonRpcMethod.scala:16-18) carries a `// flow-allow:` rationale citing Stream.scala:48 (sealed-protocol-with-framework-only-abstract-members precedent). The two `final private class` impls (`RequestMethod`, `NotificationMethod` at JsonRpcMethod.scala:55, :87) are file-private and need no `// flow-allow:` marker (the per-file marker covers the trait's protected members). Marker: `// flow-allow: PUBLIC method-binding surface built by user and passed to JsonRpcEndpoint.init`.

- `JsonRpcRequest.scala`: SPLIT (8b structural split, with INTERNAL verdict on the JsonRpcRequest case-class half).

  - `case class JsonRpcResponse` and its companion (current lines 16-28) stay PUBLIC and move to a NEW `kyo/JsonRpcResponse.scala`. Evidence: `JsonRpcResponse.success`/`JsonRpcResponse.failure` are called by users at JsonRpcCodecTest.scala:186, :187, :210; `Schema[JsonRpcResponse]` is summoned at JsonRpcCodecTest.scala:209; `Json.encode/decode[JsonRpcResponse]` at JsonRpcCodecTest.scala:211, :212. The `private[kyo]` ctor stays with `// flow-allow:` citing Hub.scala:22 (smart-constructor: `success`/`failure` are the entry points).
  - `case class JsonRpcRequest` (current lines 10-14) moves to `kyo/internal/JsonRpcRequest.scala` as `private[kyo]`. Evidence: grep `rg -nw "JsonRpcRequest"` against `shared/src/` returns only the declaration site (JsonRpcRequest.scala:10); no test, no impl, no scenario constructs or references the type. The `derives Schema, CanEqual` derivation has no in-tree consumer. Moving it to internal avoids leaving an unused public type in `kyo.*`. Marker on the relocated `kyo/JsonRpcResponse.scala`: `// flow-allow: PUBLIC response wire-shape with success/failure smart constructors and Schema derivation`.

- `JsonRpcTransport.scala`: PUBLIC ; user passes a `JsonRpcTransport` to `JsonRpcEndpoint.init(transport, ...)` (JsonRpcEndpoint.scala:73); tests build custom implementations (ScenarioHttpStyleTest.scala:88-96 wraps another transport, UnknownMethodPolicyTest.scala uses gates that interact with envelopes; MaxInFlightTest.scala:12-26 declares `CapturingTransport extends JsonRpcTransport`). The `JsonRpcTransport.inMemory(capacity)` factory at JsonRpcTransport.scala:17 returns the pair used across every endpoint test. Existing `// flow-allow:` lines at :24 and :26 (type-widening on returned tuple) stay. Marker: `// flow-allow: PUBLIC transport interface implemented by users and consumed by JsonRpcEndpoint.init`.

- `MessageGate.scala`: PUBLIC ; user-implemented `trait` passed to `JsonRpcEndpoint.Config(gate = Present(...))` (JsonRpcEndpoint.scala:64). Users implement `beforeDispatch(env: JsonRpcEnvelope): Decision < Sync` and return `Allow`/`Reject(err)`/`Drop` (ScenarioHttpStyleTest.scala:88-96; UnknownMethodPolicyTest.scala:95-97, :112-114, :133-135, :163-165, :184-185). The `Decision` enum is the documented surface for user gate implementations. Marker: `// flow-allow: PUBLIC gate trait implemented by users and consumed via JsonRpcEndpoint.Config.gate`.

- `ProgressPolicy.scala`: PUBLIC ; user constructs `JsonRpcEndpoint.Config(progress = Present(ProgressPolicy.lsp))` or `ProgressPolicy.mcp` (ProgressPolicyTest.scala:29, :31; ScenarioBidiTest.scala:190; MaxInFlightTest.scala:318). The `lsp` and `mcp` presets are the documented surface. The case-class constructor is fully public (no `private[kyo]`); users could theoretically construct custom policies via the public ctor, which mirrors `CancellationPolicy`'s open shape. Marker: `// flow-allow: PUBLIC config-policy type referenced by JsonRpcEndpoint.Config.progress field`.

- `UnknownMethodPolicy.scala`: PUBLIC ; user passes `JsonRpcEndpoint.Config(unknownMethod = UnknownMethodPolicy.minimal)` with presets `minimal`/`lsp`/`strict` (UnknownMethodPolicyTest.scala:27, :42, :59, :74). The `UnknownAction` enum (cases `ReplyMethodNotFound`, `Drop`, `Reject`) is the user-visible action ADT. The `private[kyo]` constructor on the case class (UnknownMethodPolicy.scala:3) stays with `// flow-allow:` citing Hub.scala:22 (preset-only construction: users pick from the three presets; the codec dispatcher pattern-matches on `UnknownAction` cases and is not tested against user-constructed combinations). Marker: `// flow-allow: PUBLIC config-policy type referenced by JsonRpcEndpoint.Config.unknownMethod field with three documented presets`.

### Verdict counts

- PUBLIC = 13 files (CancellationPolicy, ExtrasEncoder, HandlerCtx, JsonRpcEndpoint, JsonRpcEnvelope, JsonRpcError, JsonRpcId, JsonRpcMethod, JsonRpcResponse [post-split, new file], JsonRpcTransport, MessageGate, ProgressPolicy, UnknownMethodPolicy).
- SPLIT = 2 files (IdStrategy and JsonRpcCodec sub-symbol moves only; the head types stay PUBLIC).
- INTERNAL = 1 file (JsonRpcRequest case class relocates to `kyo/internal/JsonRpcRequest.scala`; sibling JsonRpcResponse stays public in its new file). The pre-split JsonRpcRequest.scala file is dissolved.

The verdict that surprised the v1 audit: `JsonRpcRequest` (the case-class type) has zero user references in the entire `shared/src/` tree; the v1 design assumed it would be PUBLIC because users "might want to inspect wire shapes" but evidence does not support that. Moving it to `kyo/internal/` is the honest call.

## Target-state semantics

### No public-symbol changes (modulo the JsonRpcRequest relocation)

Every PUBLIC symbol keeps the same name, package, signature, and visibility. The two sub-symbol relocations (`IdStrategy.mkNextId`, `JsonRpcCodec.cdpReservedKeys`) move framework-internal helpers from `kyo/*.scala` companions into existing `kyo/internal/*.scala` objects with no impact on user callers. The `JsonRpcResponse` case class (currently in `JsonRpcRequest.scala`) gains its own source file but keeps the same FQN `kyo.JsonRpcResponse`. The `JsonRpcRequest` case class moves from `kyo.JsonRpcRequest` to `kyo.internal.JsonRpcRequest`; this changes its FQN, which IS a user-observable break IF any external code imports it, but the in-tree grep shows zero in-tree users, so within this repository the move is mechanical.

### 8b structural split

`kyo/JsonRpcRequest.scala` currently holds two top-level case classes (JsonRpcRequest.scala:10, :16). Post-refactor layout:

- `kyo/internal/JsonRpcRequest.scala` (NEW, INTERNAL): holds `private[kyo] case class JsonRpcRequest` plus its `derives Schema, CanEqual`.
- `kyo/JsonRpcResponse.scala` (NEW, PUBLIC): holds `case class JsonRpcResponse private[kyo] (...)` plus its companion with `success`/`failure` smart constructors.
- `kyo/JsonRpcRequest.scala` is removed entirely (its top-of-file `package kyo` declaration and the case-class type relocate; the file does not survive the split).

### Final post-refactor layout: source files with matching test files (Rule 8c HARD)

| Main source | Matching test |
|---|---|
| `kyo/CancellationPolicy.scala` | `kyo/CancellationPolicyTest.scala` (already exists, 634 LOC) |
| `kyo/ExtrasEncoder.scala` | `kyo/ExtrasEncoderTest.scala` (NEW) |
| `kyo/HandlerCtx.scala` | `kyo/HandlerCtxTest.scala` (NEW) |
| `kyo/IdStrategy.scala` | `kyo/IdStrategyTest.scala` (NEW) |
| `kyo/JsonRpcCodec.scala` | `kyo/JsonRpcCodecTest.scala` (already exists, 216 LOC) |
| `kyo/JsonRpcEndpoint.scala` | `kyo/JsonRpcEndpointTest.scala` (already exists, 459 LOC) |
| `kyo/JsonRpcEnvelope.scala` | `kyo/JsonRpcEnvelopeTest.scala` (NEW) |
| `kyo/JsonRpcError.scala` | `kyo/JsonRpcErrorTest.scala` (NEW) |
| `kyo/JsonRpcId.scala` | `kyo/JsonRpcIdTest.scala` (NEW) |
| `kyo/JsonRpcMethod.scala` | `kyo/JsonRpcMethodTest.scala` (already exists, 122 LOC) |
| `kyo/JsonRpcResponse.scala` (NEW) | `kyo/JsonRpcResponseTest.scala` (NEW; absorbs the response-specific cases currently in JsonRpcCodecTest.scala:185-213) |
| `kyo/JsonRpcTransport.scala` | `kyo/JsonRpcTransportTest.scala` (already exists, 73 LOC) |
| `kyo/MessageGate.scala` | `kyo/MessageGateTest.scala` (NEW) |
| `kyo/ProgressPolicy.scala` | `kyo/ProgressPolicyTest.scala` (already exists, 434 LOC) |
| `kyo/UnknownMethodPolicy.scala` | `kyo/UnknownMethodPolicyTest.scala` (already exists, 212 LOC) |
| `kyo/internal/CancellationEngine.scala` | (internal, no Rule 8c requirement) |
| `kyo/internal/IdStrategyEngine.scala` (NEW) | (internal) |
| `kyo/internal/InMemoryTransport.scala` | (internal) |
| `kyo/internal/JsonRpcCodecImpl.scala` | (internal) |
| `kyo/internal/JsonRpcEndpointImpl.scala` | (internal) |
| `kyo/internal/JsonRpcRequest.scala` (NEW, INTERNAL relocation) | (internal) |
| `kyo/internal/ProgressEngine.scala` | (internal) |
| `kyo/internal/RateLimitEngine.scala` | (internal) |

Test-only files (multi-source scenarios and shared base):

| File | Final location | Notes |
|---|---|---|
| `kyo/Test.scala` | `kyo/JsonRpcTestBase.scala` | Shared base spec; rename so the prefix-match heuristic stops flagging it as an orphan. All 11 existing specs change their `extends Test` to `extends JsonRpcTestBase`. |
| `kyo/scenario/BidiTest.scala` | Move from `kyo/ScenarioBidiTest.scala`; class renames to `BidiTest`. |
| `kyo/scenario/HttpStyleTest.scala` | Move from `kyo/ScenarioHttpStyleTest.scala`; class renames to `HttpStyleTest`. |
| `kyo/scenario/WsStyleTest.scala` | Move from `kyo/ScenarioWsStyleTest.scala`; class renames to `WsStyleTest`. |
| `kyo/scenario/MaxInFlightTest.scala` | Move from `kyo/MaxInFlightTest.scala`. Class name stays `MaxInFlightTest`. The tests exercise `JsonRpcEndpoint.Config.maxInFlight` through a `CapturingTransport` end-to-end (MaxInFlightTest.scala:12-26): scenario-shaped (multi-source, end-to-end through a fake transport). |

### 8c missing-test detail (9 new files)

Each new test file lives in `kyo-jsonrpc/shared/src/test/scala/kyo/` with class name `<basename>Test extends JsonRpcTestBase`.

1. `kyo/JsonRpcErrorTest.scala` (source: `kyo/JsonRpcError.scala`)
   - "RFC code constants match the spec catalog"
   - "methodNotFound stamps the method name into data"
   - "invalidRequest, invalidParams, internalError carry reason into data"
   - "cancelled smart constructor reports RequestCancelled with reason"
   - "Schema[JsonRpcError] round-trips through Json"

2. `kyo/MessageGateTest.scala` (source: `kyo/MessageGate.scala`)
   - "Decision values are CanEqual-distinguishable across Allow/Reject/Drop"
   - "Reject decision carries the supplied JsonRpcError"
   - "a test-double gate returning Drop suppresses delivery"
   - "a test-double gate returning Allow forwards the envelope unchanged"

3. `kyo/IdStrategyTest.scala` (source: `kyo/IdStrategy.scala`; targets `internal.IdStrategyEngine.mkNextId` since `IdStrategy` itself has no behavior beyond the enum)
   - "SequentialLong allocates monotonically increasing JsonRpcId.Num starting at 1"
   - "SequentialInt allocates monotonically increasing JsonRpcId.Num starting at 1"
   - "Custom forwards verbatim to the supplied next function"

4. `kyo/JsonRpcResponseTest.scala` (source: `kyo/JsonRpcResponse.scala`; absorbs the two cases currently in JsonRpcCodecTest.scala:185-213)
   - "success factory enforces result-present and error-Absent"
   - "failure factory enforces error-present and result-Absent"
   - "Schema[JsonRpcResponse] round-trips a success through Json"
   - "Schema[JsonRpcResponse] round-trips a failure through Json"

5. `kyo/JsonRpcEnvelopeTest.scala` (source: `kyo/JsonRpcEnvelope.scala`)
   - "Request, Notification, Response, Malformed are CanEqual-distinguishable"
   - "Request preserves the extras field on round-trip"
   - "Notification preserves the extras field on round-trip"
   - "Response with Present id and Present result is constructible"
   - "Malformed retains both reason and raw payload"

6. `kyo/JsonRpcIdTest.scala` (source: `kyo/JsonRpcId.scala`)
   - "Num case round-trips through Json.encode/decode"
   - "Str case round-trips through Json.encode/decode"
   - "decoding a JSON number yields Num"
   - "decoding a JSON string yields Str"
   - "decoding JSON null raises TypeMismatchException"

7. `kyo/HandlerCtxTest.scala` (source: `kyo/HandlerCtx.scala`)
   - "progress with a Present sink invokes the captured callback"
   - "progress with an Absent sink is a no-op"
   - "extras and requestId are surfaced verbatim from forTest"

8. `kyo/ExtrasEncoderTest.scala` (source: `kyo/ExtrasEncoder.scala`)
   - "empty.resolve always yields Absent regardless of id"
   - "const(v).resolve always yields Present(v) regardless of id"
   - "apply(f).resolve forwards id to f"
   - "apply(f) lifts a Sync-effectful body through .resolve"

Total new focused test files: 8 (the JsonRpcRequestTest from v1 is dropped because the `JsonRpcRequest` case class is now INTERNAL and no longer requires a Rule 8c test at the public surface). `JsonRpcResponseTest.scala` is required by the 8b split. Net test-case additions: roughly 33 cases, plus the migrated 2 from JsonRpcCodecTest.

## Cross-phase invariants (candidates)

Downstream phases (impl, verify, audit) consume these structural invariants:

- INV-001: Public-API stability. Every PUBLIC symbol enumerated in the `## Package surface verdicts` section (every type marked PUBLIC, and the public-half of every SPLIT) keeps the same fully-qualified name and signature post-refactor. Verifier: grep for each public symbol's `def`/`val`/`class`/`enum`/`trait`/`case class` declaration in the post-refactor tree and assert presence. The one expected FQN change is `kyo.JsonRpcRequest` to `kyo.internal.JsonRpcRequest` (INTERNAL verdict); the public symbol enumeration explicitly excludes it.
  produced_by: Phase 1
  consumed_by: Phase 2, Phase 3, Phase 4

- INV-002: PUBLIC marker coverage. Every `kyo-jsonrpc/shared/src/main/scala/kyo/*.scala` file post-refactor (excluding `kyo/internal/`) carries a top-of-file `// flow-allow: PUBLIC <rationale>` marker. Verifier: grep for `// flow-allow: PUBLIC` near the top of every file in `kyo-jsonrpc/shared/src/main/scala/kyo/*.scala` and assert the file count matches the PUBLIC-verdict count (13 post-refactor).
  produced_by: Phase 1
  consumed_by: Phase 2, Phase 3, Phase 4

- INV-003: Sub-symbol relocations leave no dangling references. After Phase 1, no source under `kyo-jsonrpc/shared/src/main/scala/kyo/` references `IdStrategy.mkNextId` or `JsonRpcCodec.cdpReservedKeys` at their pre-move FQNs. Verifier: grep for `IdStrategy.mkNextId` and `JsonRpcCodec.cdpReservedKeys` outside `internal/` and assert no hits.
  produced_by: Phase 1
  consumed_by: Phase 2, Phase 3, Phase 4

- INV-004: `private[kyo]` rationale coverage. Every `private[kyo]` declaration that remains in a `kyo/*.scala` file after Phase 1 carries a `// flow-allow:` rationale on the same or immediately preceding line. The expected set: HandlerCtx ctor + forTest, JsonRpcEndpoint primary ctor + Pending ctor, JsonRpcMethod sealed-trait abstract members (schemaIn / schemaOut / handle), UnknownMethodPolicy ctor, JsonRpcResponse ctor (post-split). Verifier: `flow-verify-grep.sh --catalog organization-8a --target kyo-jsonrpc/shared/src/main/scala/kyo/` exits 0.
  produced_by: Phase 1
  consumed_by: Phase 2, Phase 3, Phase 4

- INV-005: 8b one-type-per-file. After Phase 2, every `kyo/*.scala` file in `kyo-jsonrpc/shared/src/main/scala/kyo/` holds exactly one top-level `class`/`trait`/`enum`/`object`/`case class` plus an optional companion of the same name. The pre-split `JsonRpcRequest.scala` is dissolved; `kyo/JsonRpcResponse.scala` (PUBLIC) and `kyo/internal/JsonRpcRequest.scala` (INTERNAL) each hold one type. Verifier: `flow-verify-grep.sh --catalog organization-8b` exits 0.
  produced_by: Phase 2
  consumed_by: Phase 3, Phase 4

- INV-006: Wire-format and Schema derivation stability. After Phase 2, `Schema[JsonRpcResponse]` still derives successfully and JSON round-trip through `Json.encode`/`Json.decode` produces byte-equivalent output to the pre-split baseline. `Schema[kyo.internal.JsonRpcRequest]` continues to derive (the case class keeps `derives Schema, CanEqual`). Verifier: existing tests at JsonRpcCodecTest.scala:208-213 pass; the new JsonRpcResponseTest also exercises the round-trip.
  produced_by: Phase 2
  consumed_by: Phase 3, Phase 4

- INV-007: Scenario relocations preserve test semantics. After Phase 3, the four relocated specs (`scenario.BidiTest`, `scenario.HttpStyleTest`, `scenario.WsStyleTest`, `scenario.MaxInFlightTest`) execute the same assertions. The shared base `JsonRpcTestBase` resolves for all 11 specs. No spec is dropped, skipped, or marked pending.
  produced_by: Phase 3
  consumed_by: Phase 4

- INV-008: 8c test coverage. After Phase 4, every `kyo/*.scala` source has a matching `kyo/<basename>Test.scala` in `shared/src/test/scala/kyo/`, and every test file either matches a source basename or lives under `kyo/scenario/`. Verifier: `flow-verify-grep.sh --catalog organization-8c --target kyo-jsonrpc/shared/src/` exits 0.
  produced_by: Phase 4
  consumed_by: (none yet; terminal)

- INV-009: All existing tests still compile and pass. Refactor preserves behavior; no test regresses on JVM, JS, or Native. Verifier: `sbt kyo-jsonrpcJVM/test kyo-jsonrpcJS/test kyo-jsonrpcNative/test` exits 0.
  produced_by: Phase 1, Phase 2, Phase 3, Phase 4 (each phase boundary verifies)
  consumed_by: (terminal at every phase)

## Rejected alternatives

1. **JsonRpcMethod: split into public sealed trait + internal Ops extension** (alternative (b) from exploration observation 5). Rejected because Stream.scala:48 establishes that sealed user-facing protocol types carrying framework-only abstract members are the established Kyo idiom; splitting would create a second type to track and offer no concrete payoff. The `// flow-allow:` rationale captures the design intent.

2. **HandlerCtx.forTest: relocate to `kyo.internal.HandlerCtxTestKit`** (alternative (b) from exploration observation 1). Rejected because the factory is genuinely tied to the public `HandlerCtx` shape (it accepts the same four constructor params); the test file already lives under `package kyo` so visibility is satisfied without a new internal object.

3. **MaxInFlight: merge into `JsonRpcEndpointTest.scala`** (alternative from exploration). Rejected because merging 347 LOC of scenario tests into the already-459-LOC focused endpoint test would balloon it past readable size, and the `CapturingTransport` machinery is genuinely scenario-shaped.

4. **Test.scala: keep at top-level with a config-driven allowlist** (option (iii) from exploration). Rejected because extending `flow-verify-organization.sh` with a per-module allowlist adds maintenance burden; the rename to `JsonRpcTestBase.scala` aligns with the convention used in other Kyo modules (`BaseKyoCoreTest`).

5. **UnknownMethodPolicy: drop `private[kyo]` so users construct custom values**. Rejected because the codec dispatcher pattern-matches on `UnknownAction` cases (JsonRpcEndpointImpl.scala) and is not tested against user-constructed combinations; the preset triad is the curated surface.

6. **JsonRpcResponse: move to `kyo.internal`** (alternative considered alongside JsonRpcRequest). Rejected because `JsonRpcResponse.success`/`failure` are called by users (JsonRpcCodecTest.scala:186, :187, :210) and `Schema[JsonRpcResponse]` is summoned at JsonRpcCodecTest.scala:209. Evidence supports PUBLIC.

7. **JsonRpcRequest: keep PUBLIC** (the v1 verdict). Rejected because `rg -nw "JsonRpcRequest"` against `kyo-jsonrpc/shared/src/` returns only the declaration site at JsonRpcRequest.scala:10; no test, no impl, no scenario constructs or references the type. The v1 "users will legitimately want to inspect wire shapes" rationale is speculative and does not survive evidence. INTERNAL is the honest call.

8. **CancellationPolicy, ProgressPolicy: relocate to internal** (considered for completeness). Rejected because each appears as a `JsonRpcEndpoint.Config` field type with documented `lsp`/`mcp` presets that users explicitly construct in tests (CancellationPolicyTest.scala:11, ProgressPolicyTest.scala:29, ScenarioBidiTest.scala:63, MaxInFlightTest.scala:185). Users wire them up; PUBLIC stands.

9. **JsonRpcEnvelope: relocate to internal** (considered because no test currently constructs every case; some files only pattern-match in impl). Rejected because user-implemented `JsonRpcTransport` (ScenarioHttpStyleTest.scala:88) and `MessageGate` (UnknownMethodPolicyTest.scala:95) both receive `JsonRpcEnvelope` values, and tests directly construct envelopes (JsonRpcTransportTest.scala:7-9, JsonRpcCodecTest.scala:27). Users wire envelopes through their own transport/gate code; PUBLIC stands.

10. **MessageGate: relocate to internal**. Rejected because users implement the trait and pass it to `JsonRpcEndpoint.Config(gate = Present(...))` (UnknownMethodPolicyTest.scala:95-97 plus four more sites; ScenarioHttpStyleTest.scala:88-96). PUBLIC stands.

11. **JsonRpcError: lock down the case-class constructor with `private[kyo]`** (considered for symmetry with UnknownMethodPolicy and JsonRpcResponse). Rejected because users construct custom errors in tests (e.g., MessageGate gate returns `JsonRpcError(-32000, "rejected", Absent)` at UnknownMethodPolicyTest.scala:135, and JsonRpcEndpointTest demonstrates the error is part of the public Abort row). Custom error codes outside the RFC catalog are a legitimate user need (custom protocol extensions). The ctor stays public.

12. **ExtrasEncoder, JsonRpcId, JsonRpcEndpoint Pending: also lock constructors** (sweep for symmetry). Rejected case by case based on whether users need to construct values directly. ExtrasEncoder uses `apply(f)` as the user entry; JsonRpcId enum cases are constructed directly by users (JsonRpcId.Num, JsonRpcId.Str); JsonRpcEndpoint.Pending is constructed only by framework code so its `private[kyo]` ctor stays as in v1.

## Open questions

None remain. The v1 design's Q-001 through Q-005 are resolved (the Q-NNN summaries below match v1 verdicts where the v1 verdict survived evidence re-verification in this pass):

- Q-001 [resolved, value-underdetermined resolved by single recommendation]: rename `Test.scala` to `JsonRpcTestBase.scala`.
- Q-002 [resolved, research-knowable, evidence at UnknownMethodPolicyTest.scala:27, :42, :59, :74 and JsonRpcEndpointImpl pattern-match sites]: UnknownMethodPolicy stays preset-only with `private[kyo]` ctor.
- Q-003 [resolved, research-knowable]: JsonRpcRequest is INTERNAL (no in-tree user references); JsonRpcResponse is PUBLIC. The v1 verdict on JsonRpcRequest was speculative; v2 corrects it based on grep evidence.
- Q-004 [resolved, research-knowable, MaxInFlightTest.scala:12-26 declares CapturingTransport]: MaxInFlightTest relocates to `kyo/scenario/`.
- Q-005 [resolved, research-knowable, naming convention from CancellationEngine.scala, ProgressEngine.scala, RateLimitEngine.scala]: relocate target is `kyo.internal.IdStrategyEngine`.

## Validation hooks for flow-validate

- `## API surface` is implicit in the PUBLIC entries of the `## Package surface verdicts` section (no new public API in this refactor; each PUBLIC verdict cites the public entry point and the source file).
- `## Cross-phase invariants (candidates)` INV-001 through INV-009 feed the `flow-invariants` ledger.
- `## Open questions` is empty; `flow-resolve-open` has nothing to dispatch.
- `## Package surface verdicts` enumerates every file on disk; `flow-validate` confirms the verdict list covers exactly the 15 files at HEAD and emits the post-refactor file moves (one INTERNAL relocation: `JsonRpcRequest.scala` to `kyo/internal/JsonRpcRequest.scala`).
- Phase boundaries: `flow-verify-grep.sh --catalog organization-8a --target kyo-jsonrpc/shared/src/main/scala/kyo/` exits 0 after Phase 1; `organization-8b` exits 0 after Phase 2; `organization-8c --target kyo-jsonrpc/shared/src/` exits 0 after Phase 4. `sbt kyo-jsonrpcJVM/test kyo-jsonrpcJS/test kyo-jsonrpcNative/test` exits 0 at every phase boundary.
