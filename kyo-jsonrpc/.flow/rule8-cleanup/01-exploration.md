# 01 Exploration: Rule 8 (Organization) cleanup in kyo-jsonrpc

Task type: refactor
Primary module: kyo-jsonrpc
Scope: kyo-jsonrpc/shared/src/

## Task statement

> Fix all Rule 8 (Organization) violations in `kyo-jsonrpc/shared/src/`:
> 8a (`package kyo` hygiene): 7 files flagged. Class-B judgment needed; relocate genuine internals to `kyo/internal/`.
> 8b (one type + companion per file): 1 hit. `JsonRpcRequest.scala` declares both `JsonRpcRequest` and `JsonRpcResponse` top-level types.
> 8c (test prefix-match, HARD): 5 orphan tests + 8 missing tests. Move scenario tests to `kyo/scenario/` subdir; create matching focused tests for the 8 missing sources.

## Module map

### `kyo-jsonrpc/shared/src/main/scala/kyo/*.scala` (user-facing surface, 15 files)

- `CancellationPolicy.scala` (50 LOC): case class declaring how the endpoint negotiates request cancellation; carries `lsp` and `mcp` presets. No `private[kyo]` marker on the case class itself; private encoder lambdas live in the companion.
- `ExtrasEncoder.scala` (16 LOC): opaque type alias `JsonRpcId => Maybe[Structure.Value] < Sync`; companion holds `apply`, `empty`, `const`, and an `extension resolve` carved out by FLOW Decision #30(b).
- `HandlerCtx.scala` (31 LOC): per-request context object passed to method handlers, carrying `cancelled` Promise, `requestId`, `extras`, and a private `progressSink`. Constructor `private[kyo]`; companion exposes a `private[kyo] forTest` factory used by `JsonRpcMethodTest`.
- `IdStrategy.scala` (25 LOC): user-visible `enum` (`SequentialLong | SequentialInt | Custom(next)`) controlling outbound id allocation; companion has a `private[kyo] mkNextId` that drives the impl.
- `JsonRpcCodec.scala` (19 LOC): trait + companion holding the two canonical codecs `Strict2_0` and `Cdp`, plus a `private[kyo] cdpReservedKeys` Set.
- `JsonRpcEndpoint.scala` (78 LOC): primary user-facing handle. `final class JsonRpcEndpoint private[kyo] (impl: internal.JsonRpcEndpointImpl)` wraps the internal impl; companion holds nested `Pending[Out]` and `Config` case classes and the `init` factory.
- `JsonRpcEnvelope.scala` (25 LOC): pure ADT (`enum`) for the four wire-level shapes; no `private[kyo]`.
- `JsonRpcError.scala` (40 LOC): case class plus a catalog of RFC 32xxx error codes and smart constructors (`methodNotFound`, `invalidRequest`, `invalidParams`, `internalError`, `cancelled`). No `private[kyo]`.
- `JsonRpcId.scala` (28 LOC): `enum JsonRpcId` (`Num | Str`) with a derived `Schema`.
- `JsonRpcMethod.scala` (117 LOC): `sealed trait JsonRpcMethod[+S]` plus companion with `apply` / `notification` smart constructors. Trait carries `private[kyo]` abstract members (`schemaIn`, `schemaOut`, `handle`); companion holds `Kind`, `RequestMethod`, and `NotificationMethod` private classes.
- `JsonRpcRequest.scala` (28 LOC): contains TWO top-level case classes, `JsonRpcRequest` and `JsonRpcResponse`, both `private[kyo]` constructor, both `derives Schema, CanEqual`. `JsonRpcResponse` has a companion with `success` / `failure` factories. This is the 8b name-mismatch hit.
- `JsonRpcTransport.scala` (31 LOC): trait + companion with `inMemory(capacity)` / `inMemory` factory pair returning a cross-wired pair backed by `internal.InMemoryTransport`.
- `MessageGate.scala` (12 LOC): trait + companion with nested `Decision` enum (`Allow | Reject | Drop`). No `private[kyo]`.
- `ProgressPolicy.scala` (62 LOC): case class describing the four progress-protocol callbacks plus `lsp` / `mcp` presets. Private `field` / `merge` helpers in the companion.
- `UnknownMethodPolicy.scala` (33 LOC): case class with `private[kyo]` constructor; nested `UnknownAction` enum; preset vals `minimal`, `lsp`, `strict`.

### `kyo-jsonrpc/shared/src/main/scala/kyo/internal/*.scala` (6 files)

- `CancellationEngine.scala` (139 LOC): `private[kyo] object CancellationEngine` implementing the cancel protocol state machine.
- `InMemoryTransport.scala` (19 LOC): `final private[kyo] class InMemoryTransport` backing `JsonRpcTransport.inMemory`.
- `JsonRpcCodecImpl.scala` (225 LOC): `private[kyo] object JsonRpcCodecImpl` providing the two codec instances exposed via `JsonRpcCodec.Strict2_0` / `JsonRpcCodec.Cdp`.
- `JsonRpcEndpointImpl.scala` (1259 LOC): central runtime. `private[kyo]` case classes (`OutboundReq`, `CallerInfo`), sealed `InboundEntry` / `WriterMsg` ADTs, and the `JsonRpcEndpointImpl` class itself.
- `ProgressEngine.scala` (84 LOC): `private[kyo] object ProgressEngine`.
- `RateLimitEngine.scala` (27 LOC): `private[kyo] object RateLimitEngine` providing the `maxInFlightGuard` combinator used by the impl.

### `kyo-jsonrpc/shared/src/test/scala/kyo/*.scala` (12 files)

- `Test.scala` (16 LOC): shared abstract base extending `AsyncFreeSpec with NonImplicitAssertions with BaseKyoCoreTest`. Convention: every spec extends `Test`. This is the "shared base" 8c-orphan exception.
- `CancellationPolicyTest.scala` (634 LOC): focused tests for the cancellation protocol; covers both `lsp` and `mcp` presets.
- `JsonRpcCodecTest.scala` (216 LOC): focused tests for `Strict2_0` and `Cdp` codecs; also covers `JsonRpcResponse.success` / `failure` factories and round-trip via `Schema[JsonRpcResponse]`.
- `JsonRpcEndpointTest.scala` (459 LOC): focused tests covering endpoint init/call/notify/close, and id-strategy presets.
- `JsonRpcMethodTest.scala` (122 LOC): focused tests covering `JsonRpcMethod.apply` / `notification`; uses `HandlerCtx.forTest` for in-test ctx construction.
- `JsonRpcTransportTest.scala` (73 LOC): focused tests for `JsonRpcTransport.inMemory` pair semantics.
- `ProgressPolicyTest.scala` (434 LOC): focused tests covering progress encoding/decoding for `lsp` and `mcp` presets.
- `UnknownMethodPolicyTest.scala` (212 LOC): focused tests covering `lsp` / `strict` / `minimal` reply behavior.
- `MaxInFlightTest.scala` (347 LOC): tests cover the `JsonRpcEndpoint.Config.maxInFlight` knob end-to-end through a `CapturingTransport`. Source-prefix match: no `MaxInFlight.scala` source. The behavior under test is a Config field plus the `internal.RateLimitEngine`. ORPHAN.
- `ScenarioBidiTest.scala` (208 LOC), `ScenarioHttpStyleTest.scala` (128 LOC), `ScenarioWsStyleTest.scala` (193 LOC): each builds two `JsonRpcEndpoint` instances bridged through `JsonRpcTransport.inMemory` and exercises multi-source flows (codec + endpoint + transport + method). ORPHANS; documented as `<pkg>/scenario/` exception per flow-verify Rule 8c.
- `CancellationPolicyTest.scala`, `ProgressPolicyTest.scala`: covered above.

## Relevant APIs (verbatim signatures)

8a candidates (file:line; quoted):

- `final class HandlerCtx private[kyo] (` at kyo-jsonrpc/shared/src/main/scala/kyo/HandlerCtx.scala:12
- `private[kyo] val progressSink: Maybe[Structure.Value => Unit < (Async & Abort[Closed])]` at HandlerCtx.scala:16
- `private[kyo] def forTest(` at HandlerCtx.scala:25
- `enum IdStrategy derives CanEqual:` at kyo-jsonrpc/shared/src/main/scala/kyo/IdStrategy.scala:3
- `private[kyo] def mkNextId(strategy: IdStrategy)(using Frame): () => JsonRpcId < Sync` at IdStrategy.scala:10
- `trait JsonRpcCodec:` at kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcCodec.scala:8
- `private[kyo] val cdpReservedKeys: Set[String]` at JsonRpcCodec.scala:17
- `final class JsonRpcEndpoint private[kyo] (private[kyo] val impl: internal.JsonRpcEndpointImpl):` at kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEndpoint.scala:5
- `final class Pending[Out] private[kyo] (` at JsonRpcEndpoint.scala:52
- `sealed trait JsonRpcMethod[+S]:` at kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcMethod.scala:13
- `private[kyo] def schemaIn: Schema[?]` at JsonRpcMethod.scala:16
- `private[kyo] def schemaOut: Schema[?]` at JsonRpcMethod.scala:17
- `private[kyo] def handle(params: Structure.Value, ctx: HandlerCtx)(using Frame): Structure.Value < (Async & Abort[JsonRpcError])` at JsonRpcMethod.scala:18
- `case class JsonRpcRequest private[kyo] (` at kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcRequest.scala:10
- `case class JsonRpcResponse private[kyo] (` at JsonRpcRequest.scala:16
- `object JsonRpcResponse:` at JsonRpcRequest.scala:22
- `final case class UnknownMethodPolicy private[kyo] (` at kyo-jsonrpc/shared/src/main/scala/kyo/UnknownMethodPolicy.scala:3

User-facing call sites confirming each suspect type is referenced via public API:

- `HandlerCtx` appears as the second parameter of every handler signature: `def apply[In: Schema, Out: Schema, S](name: String)(handler: (In, HandlerCtx) => Out < S)` at JsonRpcMethod.scala:25
- `IdStrategy` is a public `Config` field: `idStrategy: IdStrategy = IdStrategy.SequentialLong` at JsonRpcEndpoint.scala:67
- `JsonRpcCodec` is a public `Config` field: `codec: JsonRpcCodec = JsonRpcCodec.Strict2_0` at JsonRpcEndpoint.scala:60
- `JsonRpcMethod[Async & Abort[JsonRpcError]]` is the type of `JsonRpcEndpoint.init`'s `methods` parameter at JsonRpcEndpoint.scala:73
- `UnknownMethodPolicy` is a public `Config` field at JsonRpcEndpoint.scala:63
- `JsonRpcEndpoint.init(...)` factory at JsonRpcEndpoint.scala:71 returns a `JsonRpcEndpoint`.

## Conventions in this module

Naming and structure:
- Smart-constructor pattern: a user-facing class whose only legitimate construction path is a companion factory uses `final class Foo private[kyo] (...)` with the companion exposing `def init(...)`. Citation: `JsonRpcEndpoint` at JsonRpcEndpoint.scala:5 + `init` at JsonRpcEndpoint.scala:71.
- Sealed protocol abstractions surface as `sealed trait` with private case classes nested in the companion (`JsonRpcMethod.RequestMethod`, `JsonRpcMethod.NotificationMethod` at JsonRpcMethod.scala:55-116).
- Presets are named `val`s on the companion: `lsp`, `mcp`, `Strict2_0`, `Cdp`, `minimal`, `strict` (Citations: CancellationPolicy.scala:35-49, JsonRpcCodec.scala:14-15, UnknownMethodPolicy.scala:16-32).
- Effect rows in user-facing return types: `Async & Abort[JsonRpcError | Closed]`, `Sync & Async & Scope`. Citations at JsonRpcEndpoint.scala:11, 75.
- Internal subpackage: every non-user-facing class is under `package kyo.internal` with `private[kyo]` modifier (`InMemoryTransport.scala:7`, `JsonRpcEndpointImpl.scala`, etc.).

Test infrastructure:
- Every spec extends `kyo.Test` (shared base at Test.scala:9). `Test` inherits `AsyncFreeSpec with NonImplicitAssertions with BaseKyoCoreTest`.
- Spec naming convention: `<SourceBasename>Test.scala` colocated in `shared/src/test/scala/kyo/`.
- Test bodies use ScalaTest `"description" in run { ... }` and the `kyo.Test` helpers (`run`, `untilTrue`, etc.).
- `CapturingTransport` wrapper class is repeated across `ScenarioBidiTest`, `ScenarioWsStyleTest`, `ScenarioHttpStyleTest`, `MaxInFlightTest` (each redeclares a private inner class). This is duplication callouts inherited from the existing tests; not a Rule 8 problem.

## Prior art for the task type

User-facing class in `package kyo` with `private[kyo]` constructor (the canonical pattern):

- `final class Hub[A] private[kyo] (...)` at kyo-core/shared/src/main/scala/kyo/Hub.scala:22. `Hub` is unambiguously user-facing (`Hub.init` is the entrypoint), yet declares `private[kyo]` on the primary constructor to force callers through the factory. Companion-resident `init` factory is the entry point; class lives in `package kyo`, NOT `kyo.internal`.
- `abstract class Meter private[kyo] ():` at kyo-core/shared/src/main/scala/kyo/Meter.scala:33. Same pattern: user-facing abstract class, smart-constructor only, lives in `package kyo`.
- `final case class FailureException private[kyo] (error: Any)(using Frame)` at kyo-core/shared/src/main/scala/kyo/KyoApp.scala:13. User-facing exception thrown by `KyoApp`, constructor restricted to the framework.
- `abstract class Stream[+V, -S] @publicInBinary private[kyo] () extends Serializable:` at kyo-prelude/shared/src/main/scala/kyo/Stream.scala:48. Stream is the marquee user-facing type in kyo-prelude; declares `private[kyo]` constructor and explicitly carries `@publicInBinary` so the runtime can still synthesize subclasses, while users only construct via factories.

Together: `private[kyo]` on the primary constructor of a user-facing type is the canonical Kyo idiom for "construction is framework-only, but the resulting value flows freely through user code". Rule 8a's heuristic is a SUSPECT-detector, not a verdict; the design step must override per file based on whether the type appears in public method signatures.

Mixed-type-per-file (8b) prior art:
- Each `*Policy.scala`, `*Codec.scala`, `*Endpoint.scala` file in kyo-jsonrpc already keeps exactly one top-level type. `JsonRpcRequest.scala` is the lone deviation: it bundles `JsonRpcRequest` + `JsonRpcResponse` because they form a request/response pair, but the file basename only matches one.

Scenario-test subdir (8c) prior art:
- The flow-verify Rule 8c spec itself names `kyo-jsonrpc/shared/src/test/scala/kyo/scenario/BidiTest.scala` as the canonical example (flow-verify.md:249). No other kyo module currently exercises this subdir; this campaign is the first to land it.

## Open observations

Per-file class-B verdict-hints for 8a (each entry summarizes the evidence and proposes a verdict the design step must lock in):

1. `HandlerCtx.scala`. verdict-hint: USER-FACING. Type appears as the second parameter of every handler the user supplies to `JsonRpcMethod.apply` / `JsonRpcMethod.notification` (JsonRpcMethod.scala:25, 44). The `private[kyo] val progressSink` field is genuinely framework-internal; the public `progress(value)` method (HandlerCtx.scala:18) is the user surface. Resolution: keep in `package kyo`; the `private[kyo] (...)` constructor and `private[kyo] forTest` are correct (matches `Hub` precedent at Hub.scala:22). The 8a flag is a false positive that needs a `// flow-allow:` rationale on the constructor line. [needs class-B verdict + flow-allow rationale]
2. `IdStrategy.scala`. verdict-hint: USER-FACING. Type is a public field of `JsonRpcEndpoint.Config` (JsonRpcEndpoint.scala:67); users name `IdStrategy.SequentialLong | IdStrategy.SequentialInt | IdStrategy.Custom(...)` at the call site. `private[kyo] def mkNextId` is genuinely framework-internal and belongs in `kyo.internal`. Resolution: relocate `mkNextId` to `kyo.internal.IdStrategyEngine` (or a fresh `kyo.internal` object); the `enum` itself stays in `package kyo`. [needs flow-resolve-open on name for the engine object]
3. `JsonRpcCodec.scala`. verdict-hint: USER-FACING (trait). Trait is a public field of `JsonRpcEndpoint.Config` (JsonRpcEndpoint.scala:60). `private[kyo] val cdpReservedKeys` is a genuine internal constant consumed only by `internal.JsonRpcCodecImpl`. Resolution: relocate `cdpReservedKeys` into `internal.JsonRpcCodecImpl` (where it is used); trait + companion stay in `package kyo`. [needs class-B verdict]
4. `JsonRpcEndpoint.scala`. verdict-hint: USER-FACING. The marquee entrypoint. `private[kyo]` on the primary constructor is correct (Hub precedent at Hub.scala:22); `private[kyo] val impl` exposes the internal impl to the package for the wrapper's own methods. Resolution: keep in `package kyo`; add `// flow-allow:` rationale citing Hub. The nested `Pending[Out] private[kyo] (...)` is the same pattern. [needs class-B verdict + flow-allow rationale]
5. `JsonRpcMethod.scala`. verdict-hint: USER-FACING (trait). Type is the element type of `JsonRpcEndpoint.init`'s `methods: Seq[...]` parameter (JsonRpcEndpoint.scala:73). The `private[kyo]` abstract members `schemaIn`, `schemaOut`, `handle` are consumed only by `internal.JsonRpcEndpointImpl`. Two resolution paths: (a) keep as-is with a `// flow-allow:` rationale on each `private[kyo]` member (matches the JVM convention of internal-extension points on a sealed user-facing trait); (b) split into a public `sealed trait JsonRpcMethod[+S]` (no `private[kyo]` members) and an `internal.JsonRpcMethodOps` extension that exposes the trio. Option (a) is simpler and matches the precedent of `Stream` carrying internal-only abstract members through `@publicInBinary`. [needs class-B verdict choosing (a) or (b)]
6. `JsonRpcRequest.scala`. verdict-hint: AMBIGUOUS. The two case classes here represent the legacy "flat" wire shapes (request/response), but the actively-used wire ADT is `JsonRpcEnvelope` (JsonRpcEnvelope.scala:6), which has `Request`, `Notification`, `Response`, `Malformed` cases AND carries `extras`. Need to determine whether `JsonRpcRequest` / `JsonRpcResponse` are still part of the public API or are vestigial. Only test reference is `JsonRpcCodecTest` exercising `JsonRpcResponse.success` / `failure` (JsonRpcCodecTest.scala). but that test is the only consumer. Resolution candidates: (a) move both to `kyo.internal` and let `JsonRpcEnvelope` be the sole public wire ADT; (b) keep `JsonRpcResponse` public (it has user-facing smart constructors) and inline `JsonRpcRequest` into the codec internals. [needs flow-resolve-open: are JsonRpcRequest/Response still user-facing?]
7. `UnknownMethodPolicy.scala`. verdict-hint: USER-FACING. Type is a public field of `JsonRpcEndpoint.Config` (JsonRpcEndpoint.scala:63); users select `UnknownMethodPolicy.minimal | lsp | strict` or construct a custom value. `private[kyo]` on the case-class constructor enforces preset-only construction. Resolution: keep in `package kyo`; add `// flow-allow:` rationale citing Hub. Alternative: drop the `private[kyo]` so users can construct custom policies (currently they can only pick among the three presets). [needs flow-resolve-open: should custom UnknownMethodPolicy values be user-constructable?]

8b verdict-hint:
- `JsonRpcRequest.scala` must split into `JsonRpcRequest.scala` (one case class) and `JsonRpcResponse.scala` (case class + companion). The contents are mechanical; no design decision needed once the 8a outcome for these two types is locked. [tied to observation #6]

8c verdict-hints for orphan tests (5):
- `Test.scala`. KEEP at top-level. Shared spec base; exception is documented in `steering.md` and in flow-verify-organization.sh's behavior (it would never have a matching `Test.scala` source). The `8c-orphan-test` flag here is a false positive. Resolution: the script flags it but the verdict is "exception"; the cleanest fix is to RENAME `Test.scala` to `BaseTest.scala` or `JsonRpcTestBase.scala` so it stops tripping the prefix-match heuristic. The latter is more honest. [needs flow-resolve-open: rename Test.scala to BaseTest.scala or annotate as exception?]
- `MaxInFlightTest.scala`. RELOCATE to `kyo/scenario/`. The tests exercise the `JsonRpcEndpoint.Config.maxInFlight` knob end-to-end through a `CapturingTransport`; behavior is multi-source (Config + RateLimitEngine + endpoint dispatch). There is no `MaxInFlight.scala` source and creating one would be a forced rename of an internal engine. The honest classification is "scenario test exercising a Config knob". [needs class-B verdict: relocate to scenario/ vs rename to MaxInFlightConfigTest.scala and merge into JsonRpcEndpointTest.scala]
- `ScenarioBidiTest.scala`, `ScenarioHttpStyleTest.scala`, `ScenarioWsStyleTest.scala`. RELOCATE to `kyo/scenario/`. These match the flow-verify-organization.sh allowlist (line 259 "`*/scenario/*`"). Once moved, they will pass the prefix-match check without needing a matching source. Naming convention inside `scenario/`: drop the `Scenario` prefix per the flow-verify example `kyo/scenario/BidiTest.scala`. So move-and-rename: `BidiTest.scala`, `HttpStyleTest.scala`, `WsStyleTest.scala`.

8c verdict-hints for missing tests (8 sources):
- `JsonRpcError.scala`. natural surface: companion `val`s (`ParseError`, `InvalidRequest`, `MethodNotFound`, `InvalidParams`, `InternalError`, `ServerNotInitialized`, `UnknownErrorCode`, `RequestCancelled`, `ContentModified`, `ServerCancelled`, `RequestFailed`) carry RFC 32xxx code constants; smart constructors (`methodNotFound`, `invalidRequest`, `invalidParams`, `internalError`, `cancelled`) attach reason data. Tests are trivial assertions: each preset has the right `code` per RFC, each smart constructor stamps reason into `data` correctly, `Schema[JsonRpcError]` round-trips. ~10 cases.
- `MessageGate.scala`. natural surface: trait + `Decision` enum (`Allow | Reject(error) | Drop`). Tests verify `Decision` CanEqual semantics and that a stub `MessageGate` returning `Reject` / `Drop` results in the expected `JsonRpcEndpoint` behavior. Most behavioral tests already live in `JsonRpcEndpointTest`; the missing focused test covers the ADT and a single round-trip of each `Decision` case through a mock gate. ~4 cases.
- `IdStrategy.scala`. natural surface: the enum has three cases. `SequentialLong` / `SequentialInt` test that `mkNextId` returns monotonic ids from 1 (already tested in `JsonRpcEndpointTest.scala` end-to-end; focused test isolates `mkNextId`); `Custom(next)` test that `mkNextId` forwards verbatim. Note: `mkNextId` is `private[kyo]` so the focused test lives in `package kyo` and calls it directly. ~3 cases.
- `JsonRpcRequest.scala`. natural surface: case-class equality, `derives Schema, CanEqual` round-trips. Sparse but possible if these types remain public. Depends on observation #6. If they migrate to `internal`, the test moves to internal-facing white-box tests. ~3 cases.
- `JsonRpcEnvelope.scala`. natural surface: the four-case ADT (`Request | Notification | Response | Malformed`) is constructed all over the codebase but never has dedicated coverage. Tests verify `CanEqual` discrimination across cases, optional `extras` passthrough, `Malformed.reason / raw` retention. ~5 cases.
- `JsonRpcId.scala`. natural surface: enum + derived `Schema`. The `Schema` round-trip is non-trivial: writeFn dispatches on Num/Str, readFn tries `long()` then falls back to `string()` on `TypeMismatchException`, and throws `TypeMismatchException` on `null`. Tests cover: each case round-trips through `Json.encode`/`Json.decode`; `null` raises `TypeMismatchException`; numeric overflow path. ~5 cases.
- `HandlerCtx.scala`. natural surface: `progress(value)` dispatch (Present sink invokes, Absent sink is a no-op). Tests use `HandlerCtx.forTest` to construct a ctx with a `Present` sink that captures and an `Absent` sink that verifies no callback. ~3 cases.
- `ExtrasEncoder.scala`. natural surface: `apply`, `empty`, `const`, `resolve` extension. Tests verify `empty.resolve(_) == Absent`, `const(v).resolve(_) == Present(v)`, `apply(f).resolve(id)` forwards to `f(id)`. ~4 cases.

Total focused test additions: 8 files, ~37 cases.

Cross-cutting observations the design step must address:
- The flow-verify-organization.sh script's 8a check fires on the first `private[kyo]` line in any `kyo/*.scala` file. Several flagged files have a USER-FACING verdict (HandlerCtx, JsonRpcEndpoint, JsonRpcMethod, UnknownMethodPolicy) and require `// flow-allow:` rationale at the construction line citing the `Hub.scala:22` / `Stream.scala:48` precedent. The validator that downstream-resolves these comments is `flow-verify-validation-check.sh`; the design step must produce a `phase-N-validation.json` blob recording each VALIDATED_EXCEPTION.
- Two files have GENUINE internal symbols that move to `kyo.internal`: `IdStrategy.mkNextId` and `JsonRpcCodec.cdpReservedKeys`. These are sub-symbol relocations, not whole-file moves, and the design must name the target objects.
- The `private[kyo] forTest` factory on `HandlerCtx` (HandlerCtx.scala:25) is used only by `JsonRpcMethodTest` (line 24). It is genuinely a test-only escape hatch. Two options: (a) leave it, with a `// flow-allow:` citing "test-only construction"; (b) move it to a `kyo.internal.HandlerCtxTestKit` object and have the test import that. Option (a) is the lower-risk path. [needs class-B verdict]
- `JsonRpcMethod.scala`'s three `private[kyo]` abstract members are the trickiest case: they are required to keep the trait sealed-protocol style (so `internal.JsonRpcEndpointImpl` can call them) but every public reference to the trait is structurally typed. The Stream-precedent (`@publicInBinary private[kyo]` + abstract internal-only members at Stream.scala:48-51) applies directly. [needs class-B verdict choosing Stream-style annotation vs split-trait]

## Citations index

- Hub user-facing precedent: kyo-core/shared/src/main/scala/kyo/Hub.scala:22
- Meter user-facing precedent: kyo-core/shared/src/main/scala/kyo/Meter.scala:33
- Stream user-facing precedent: kyo-prelude/shared/src/main/scala/kyo/Stream.scala:48
- FailureException user-facing precedent: kyo-core/shared/src/main/scala/kyo/KyoApp.scala:13
- HandlerCtx primary constructor: kyo-jsonrpc/shared/src/main/scala/kyo/HandlerCtx.scala:12
- HandlerCtx forTest factory: HandlerCtx.scala:25
- IdStrategy enum: kyo-jsonrpc/shared/src/main/scala/kyo/IdStrategy.scala:3
- IdStrategy.mkNextId: IdStrategy.scala:10
- JsonRpcCodec trait: kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcCodec.scala:8
- JsonRpcCodec.cdpReservedKeys: JsonRpcCodec.scala:17
- JsonRpcEndpoint class: kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEndpoint.scala:5
- JsonRpcEndpoint.Pending nested class: JsonRpcEndpoint.scala:52
- JsonRpcEndpoint.Config case class: JsonRpcEndpoint.scala:59
- JsonRpcEndpoint.init: JsonRpcEndpoint.scala:71
- JsonRpcMethod trait: kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcMethod.scala:13
- JsonRpcMethod.apply handler signature: JsonRpcMethod.scala:25
- JsonRpcRequest two top-types: kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcRequest.scala:10, :16
- JsonRpcResponse companion factories: JsonRpcRequest.scala:22-28
- UnknownMethodPolicy case class: kyo-jsonrpc/shared/src/main/scala/kyo/UnknownMethodPolicy.scala:3
- JsonRpcEnvelope ADT: kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEnvelope.scala:6
- JsonRpcError catalog vals: kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcError.scala:13-23
- JsonRpcError smart constructors: JsonRpcError.scala:25-39
- JsonRpcId Schema dispatch: kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcId.scala:15-27
- JsonRpcTransport.inMemory factory: kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcTransport.scala:17
- MessageGate.Decision enum: kyo-jsonrpc/shared/src/main/scala/kyo/MessageGate.scala:7-11
- ProgressPolicy lsp / mcp presets: kyo-jsonrpc/shared/src/main/scala/kyo/ProgressPolicy.scala:37, 47
- ExtrasEncoder opaque type: kyo-jsonrpc/shared/src/main/scala/kyo/ExtrasEncoder.scala:3
- Internal: JsonRpcEndpointImpl ADTs: kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcEndpointImpl.scala:7-44
- Test base: kyo-jsonrpc/shared/src/test/scala/kyo/Test.scala:9
- MaxInFlightTest first scenario: kyo-jsonrpc/shared/src/test/scala/kyo/MaxInFlightTest.scala:6
- ScenarioBidiTest first scenario: kyo-jsonrpc/shared/src/test/scala/kyo/ScenarioBidiTest.scala:6
- JsonRpcMethodTest HandlerCtx.forTest usage: kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcMethodTest.scala:24
- flow-verify Rule 8c canonical example: ~/.claude/commands/flow-verify.md:249
- flow-verify-organization.sh scenario allowlist: ~/.claude/commands/flow-verify-organization.sh:259
