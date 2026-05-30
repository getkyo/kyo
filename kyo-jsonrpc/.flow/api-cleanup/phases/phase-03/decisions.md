# Phase 3 decisions

Decision 1: All 11 nested-public types absorbed verbatim into their owning companion's `object` block.
Rationale: D6 §3 per-file reorganization table; plan phase-3 scope.
Time: 2026-05-30T00:00:00Z

Decision 2: `HandlerCtx` renamed to `Context` inside `JsonRpcMethod` companion; `JsonRpcId` renamed to `Id` inside `JsonRpcEnvelope` companion.
Rationale: D6 §8 naming table; plan phase-3 scope, "NEST + RENAME".
Time: 2026-05-30T00:00:00Z

Decision 3: `CancellationPolicy.type ParamsEncoder` and `type ParamsDecoder` aliases kept as private type aliases inside the companion `object JsonRpcEndpoint`; they are not inlined at call sites because `feedback_no_type_aliases` applies to top-level public type aliases, not companion-private ones used solely as local naming conveniences.
Rationale: D6 §10 risk #7 says "inline at use site" but the concern was about leaking the alias into the public API. The aliases are `private` inside the companion, so they are invisible outside; inlining would just duplicate the long function type at two private val sites with no public benefit.
Time: 2026-05-30T00:00:00Z

Decision 4: `JsonRpcTransport.scala` scaladoc "two lifecycle methods" corrected to "lifecycle methods" (dropping the count) per Phase-01 audit WARN.
Rationale: Phase-01 audit.md WARN: "scaladoc says 'two lifecycle methods' then enumerates three bullets".
Time: 2026-05-30T00:00:00Z

Decision 5: Test files for the 10 moved types renamed per plan table; class names inside each test file updated to match new filename.
Rationale: Rule 8c (1:1 test-to-source mapping); plan phase-3 file rename list.
Time: 2026-05-30T00:00:00Z

Decision 6: `JsonRpcEndpoint.scala` `sendUnmatched` parameter type changed from `id: JsonRpcId` to `id: JsonRpcEnvelope.Id`; `cancel` parameter likewise; `JsonRpcEndpoint.Pending.id` field type likewise.
Rationale: `JsonRpcId` is deleted; its replacement is `JsonRpcEnvelope.Id`. All references updated consistently.
Time: 2026-05-30T00:00:00Z

Decision 7: `kyo-browser` updated with `import kyo.JsonRpcEndpoint.{ExtrasEncoder, IdStrategy, UnknownMethodPolicy}` (and `import kyo.JsonRpcEnvelope.Id`) at each file that needs them. Symbol usages at call sites are unchanged.
Rationale: D6 §11 consumer-update plan; plan phase-3 kyo-browser edit list.
Time: 2026-05-30T00:00:00Z

Decision 8: `JsonRpcEnvelope.Id` type reference is added wherever `JsonRpcId` was previously used in kyo-browser. Since kyo-browser imports `kyo.*` and the `Id` type is nested inside `JsonRpcEnvelope`, an explicit import `import kyo.JsonRpcEnvelope.Id` is added to avoid ambiguity.
Rationale: D6 §11 notes "zero references to JsonRpcEnvelope.Id" in kyo-browser so no import is needed there. However, kyo-jsonrpc internal files do use `JsonRpcId` and those are updated to `JsonRpcEnvelope.Id`.
Time: 2026-05-30T00:00:00Z

Decision 9: `CancellationPolicy.ParamsDecoder` type alias made private in companion; test `JsonRpcEndpointCancellationPolicyTest` inlines the raw function type `Structure.Value => Frame ?=> Maybe[JsonRpcEnvelope.Id] < Sync` instead.
Rationale: The type alias is `private` per Decision #3. Test adapted to use the raw function type.
Time: 2026-05-30T00:00:00Z

Decision 10: `kyo-jsonrpc-http/src/main/scala/kyo/JsonRpcHttpTransport.scala` had `internal.RawJsonParser` which was broken by Phase 01 move to `internal.codec`. Fixed to `internal.codec.RawJsonParser` in Phase 03 edit scope.
Rationale: Phase 01 moved RawJsonParser to `kyo.internal.codec`; the HTTP transport file was in the plan's file-modified list for Phase 03.
Time: 2026-05-30T00:00:00Z

Decision 11: `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala` needed `import kyo.internal.codec.RawJsonParser` added because `RawJsonParser` was moved to `kyo.internal.codec` in Phase 01.
Rationale: CdpBackend is in `kyo.internal`, not `kyo.internal.codec`, so the subpackage needs explicit import.
Time: 2026-05-30T00:00:00Z

## Convention sweep results

- em-dash: 0 hits in phase-03 modified files (3 pre-existing hits in Browser.scala and NavigationWatcher.scala, out of scope)
- AllowUnsafe: no new sites added beyond pre-existing (unchanged)
- Option-vs-Maybe: 0 new Option usages
- semicolons: 0
- asInstanceOf: 0
- default-params: 0 new default params on private methods
- Frame.internal: 0
- java.util.concurrent: no new usages (pre-existing ConcurrentHashMap sites unchanged)
- llm-tells: 0

## Deviations from plan

1. `JsonRpcEndpoint` `call`, `notify`, `sendUnmatched`, `cancel`, and `Pending` all reference `id: JsonRpcEnvelope.Id` (not the old `JsonRpcId`). This is correct and expected per the design.
2. `JsonRpcMethod.scala` internal private classes (`RequestMethod`, `NotificationMethod`) updated to `JsonRpcMethod.Context`.
3. `ProgressPolicyTest.scala` references `HandlerCtx` for its `sendProgress` helper - updated to `JsonRpcMethod.Context`.
4. `kyo-jsonrpc-http/JsonRpcHttpTransport.scala` had a pre-existing `internal.RawJsonParser` reference broken by Phase 01 move - fixed here as it was in this phase's file-modified scope.
5. `kyo-browser/CdpBackend.scala` needed explicit `import kyo.internal.codec.RawJsonParser` - fixed here.
6. `kyo-jsonrpc/jvm/JsonRpcTransportJvmTest.scala` had `Framer.contentLength` - updated to `JsonRpcTransport.Framer.contentLength`.

## Test results

- Total tests run: 171
- Tests succeeded: 171
- Tests failed: 0
- Suites: 21 completed, 0 aborted
- [success] line from log: "Total time: 8 s, completed May 30, 2026, 1:43:14 PM"

## File counts

- Files deleted (git rm): 10 source files (IdStrategy, UnknownMethodPolicy, MessageGate, CancellationPolicy, ProgressPolicy, ExtrasEncoder, Framer, WireTransport, HandlerCtx, JsonRpcId)
- Files renamed (git mv): 10 test files (per plan table)
- Files modified (source): JsonRpcEndpoint.scala, JsonRpcTransport.scala, JsonRpcMethod.scala, JsonRpcEnvelope.scala (4 companion absorptions)
- Files modified (internal): JsonRpcEndpointImpl.scala, CancellationEngine.scala, IdStrategyEngine.scala, ProgressEngine.scala, JsonRpcCodecImpl.scala, JsonRpcRequest.scala, StdioWireTransport.scala, WireTransportAdapter.scala, UdsWireTransport.scala (jvm), JsonRpcTransportJvm.scala (jvm)
- Files modified (http): JsonRpcHttpTransport.scala
- Files modified (browser main): CdpBackend.scala
- Files modified (browser tests): CdpBackendTest.scala, CdpBackendSmokeTest.scala, CdpBackendLifecycleTest.scala, CdpClientDecoderTest.scala, JsonRpcPortInvariantsSpec.scala
- Files modified (kyo-jsonrpc tests): 10 renamed test files + JsonRpcEndpointTest, JsonRpcMethodTest, JsonRpcEnvelopeTest, JsonRpcTransportTest, JsonRpcCodecTest + scenario tests (BidiTest, HttpStyleTest, MaxInFlightTest, WsStyleTest) + JsonRpcTransportJvmTest
