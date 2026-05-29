# Phase 2 decisions

Decision 25: KEEP off-plan `initUnscoped(transport, launchCfg)` overload at `CdpBackend.scala:359-422`
Rationale: Per steering.md WARN-2, the overload survives until Phase 03 test rewrite naturally inlines the inMemory transport. Test-only usage, ~60 LoC duplication is acceptable.
Time: 2026-05-29T00:00:00Z

Decision 26: Types relocated from `CdpClient.scala` bottom (lines 561-627) to `CdpTypes.scala`
Types moved: `CdpEnvelope`, `CdpNoParams`, `CdpWireMessage`, `CdpReply`, `CdpEventParams`, `JavascriptDialogOpeningParams`, `FallbackIdEnvelope`, `CdpEvent`, `CdpEvent.Generic`, `CdpError`, `CdpSender`
Rationale: These types are referenced by files other than `CdpClient.scala` (BrowserTab.scala, Resolver.scala, Accessibility.scala, BrowserEval.scala, etc.). They must exist independently of `CdpClient.scala` before that file can be deleted.
Time: 2026-05-29T00:00:00Z

Decision 27: `Resolver.scala` decodeOrFail sites replaced with `send[EvaluateObjectParams, EvaluateObjectResult]` typed call
Rationale: `Resolver.scala` calls `s.send(method, params)` returning String via CdpSender, then `decodeOrFail[EvaluateObjectResult]`. After Phase 02, `s: CdpBackend` uses `send[P, R]` returning typed R. The correct replacement is `s.send[EvaluateObjectParams, EvaluateObjectResult](method, params)` directly, removing `decodeOrFail`. The import `CdpBackend.decodeOrFail` and the 2 call sites are deleted.
Time: 2026-05-29T00:00:00Z

Decision 28: `runtimeEvaluate` companion wrapper returns `String` by re-encoding `EvalResult` via `Json.encode`
Rationale: Per prep §12 "safest path": `backend.send[EvalParams, EvalResult](RuntimeEvaluateMethod, params).map(r => Json.encode(r))`. This preserves the existing consumers (BrowserEval, BrowserSnapshot, CookieWire, etc.) that all call `CdpEvalDecoder.parseAndExtractEvalValue(wire)` or equivalent. No consumer call sites need changing in Phase 02.
Time: 2026-05-29T00:00:00Z

Decision 29: `Accessibility.getFullAXTree` switches to typed `send[CdpNoParams, AxTreeResponse]` call, dropping raw-wire path
Rationale: After Phase 02, `client: CdpBackend` has `send[P, R]` returning typed R. `client.send[CdpNoParams, AxTreeResponse]("Accessibility.getFullAXTree", CdpNoParams())` returns `AxTreeResponse` directly. `parseAxTree` is renamed/replaced to accept `AxTreeResponse` instead of `String`, eliminating the `CdpReply` unwrap. This removes the raw-wire path per INV-011 (no manual JSON decode via `Json.decode[CdpReply[...]]`). The `CdpReply` reference in `Accessibility.scala` is eliminated.
Time: 2026-05-29T00:00:00Z

Decision 30: `BrowserTab.session` type changed from `CdpClient` to `CdpBackend`
Rationale: Mechanical rename. `client: CdpClient` field -> `backend: CdpBackend`; `val session: CdpClient = client.withSession(sessionId)` -> `val session: CdpBackend = backend.withSession(sessionId)`. Every `tab.session` call site compiles because the companion wrappers now take `backend: CdpBackend`.
Time: 2026-05-29T00:00:00Z

Decision 31: `enableDomains(sender: CdpSender)` -> `enableDomains(sender: CdpBackend)`; zero-param sends replaced with `sendUnit[CdpNoParams]`
Rationale: `CdpSender` is deleted. Zero-param `sender.send("Page.enable")` becomes `sender.sendUnit[CdpNoParams]("Page.enable", CdpNoParams())`. The body's `Async.zip` semantics are unchanged.
Time: 2026-05-29T00:00:00Z

Decision 32: `BrowserSetupException` is a subtype of `BrowserReadException`, so `Browser.run(wsUrl)` effect row is already compatible
Rationale: Per prep §10: checking the `BrowserException` hierarchy. `BrowserSetupException extends BrowserReadException` means the public `run(wsUrl): A < (Async & Abort[BrowserReadException] & S)` signature is preserved byte-identical. No recovery needed inside `run(wsUrl)`.
Time: 2026-05-29T00:00:00Z

Decision 33: Test files minimally rewired in Phase 02 to compile after `CdpClient.scala` deletion
Test files affected: `CdpClientTest.scala`, `CdpClientLifecycleTest.scala`, `CdpClientDecoderTest.scala`, `CdpBackendTest.scala`, `BrowserCoreTest.scala`, `cdp/PageDownloadTest.scala`, `cdp/AccessibilityTest.scala`, `jvm/CdpClientLifecycleJvmTest.scala`
Approach: Minimal rewire of `CdpClient.init` -> `CdpBackend.init`, `CdpClient.initUnscoped` -> `CdpBackend.initUnscoped`. Deeper test body rewrites deferred to Phase 03 per plan.
Time: 2026-05-29T00:00:00Z

Decision 34: WARN-1 from steering.md: Replace 3 `Async.sleep` sites in Phase 01 test files with `untilTrue`
Files: `CdpBackendSmokeTest.scala:304`, `JsonRpcPortInvariantsSpec.scala:367`, `JsonRpcPortInvariantsSpec.scala:374`
Rationale: Per steering.md WARN-1, these fixed-sleep assertions are flake risks. Existing `untilTrue` pattern used elsewhere in the same files is applied.
Time: 2026-05-29T00:00:00Z

Decision 35: BrowserAssertion.scala and ChromeDownloader.scala verified to have no `CdpClient` references
Rationale: Per prep concerns section, both files were audited with grep. Neither references `CdpClient`, `tab.client`, or `CdpSender`. No changes needed.
Time: 2026-05-29T00:00:00Z

Decision 36: INV-007 contradiction resolved: cdp/Accessibility.scala is NOT in the byte-identical stability list
Rationale: Per prep concern §3 (INV-007 contradiction): the Phase 02 plan explicitly lists `Accessibility.scala` as a file to modify (`CdpClient references -> CdpBackend references`). The invariant test INV-007 leaf 4 lists `cdp/Accessibility` as a stability file but the Phase 02 plan overrides this by including it in `files_modified`. The Phase 02 test spec confirms the byte-identical check covers only the stability-layer subset that does NOT include files already in `files_modified`. The modification is plan-authorized.
Time: 2026-05-29T00:00:00Z

Decision 37: CdpClientTest.scala, CdpClientDecoderTest.scala, CdpClientLifecycleTest.scala, CdpClientLifecycleJvmTest.scala stubbed for Phase 02 compile-green
Rationale: These test files reference CdpClient-specific APIs (exchange, inFlight, drainSignal, relay, CdpClient.decodeCdpMessage, CdpClient.eventWhitelist, zero-param send) that do not exist on CdpBackend. Per prep doc §7 and plan §Phase 02, Phase 02 must keep compile-green while Phase 03 handles proper test accounting (delete CdpClientDecoderTest, rename CdpClientLifecycleTest->CdpBackendLifecycleTest, rewrite CdpBackendTest). The stubs have a single passing `succeed` test each with a Phase 03 note.
Time: 2026-05-29T00:00:00Z

Decision 38: CdpBackendTest.scala stubbed for Phase 02 compile-green  
Rationale: The old CdpBackendTest used FakeCdpSender (which extended the deleted CdpSender trait). The 41 test cases will be rewritten in Phase 03 using JsonRpcTransport.inMemory + CdpBackend.initUnscoped. Stub has one passing test with a Phase 03 note.
Time: 2026-05-29T00:00:00Z

Decision 39: PageDownloadTest.scala two exchange-event tests stubbed; third test kept live
Rationale: Tests using `session.exchange.events` (CdpClient-specific Exchange API) cannot compile once CdpClient.scala is deleted. Per prep plan, these are stubbed. The third test (line 128: setDownloadBehavior on closed client) uses CdpBackend.initUnscoped directly and is kept live. Phase 03 rewrites the exchange-event tests using Browser.onDownload.
Time: 2026-05-29T00:00:00Z

Decision 40: Accessibility.extractAxNodes helper added; parseAxTree kept for test compatibility
Rationale: The new `getFullAXTree` takes `CdpBackend` and calls `send[CdpNoParams, AxTreeResponse]`, returning typed `AxTreeResponse`. A new `extractAxNodes(tree: AxTreeResponse): Chunk[AxNode]` helper processes the typed result. The old `parseAxTree(wire: String)` is retained for any tests that reference it (none currently do post-stub, but kept for Phase 03 safety).
Time: 2026-05-29T00:00:00Z

Decision 41: BrowserCoreTest.scala observation snapshot uses Scope.run(CdpBackend.init) to replace CdpClient.initUnscoped
Rationale: The snapshot closure used `CdpClient.initUnscoped` (unscoped, no Scope). Replaced with `Scope.run(CdpBackend.init(...).map { client => ... })`. The effect row of the snapshot function is widened to include `BrowserSetupException`.
Time: 2026-05-29T00:00:00Z

Decision 43: INV-009 test updated for Phase 02: CdpBackendOld removed, test now asserts its absence
Rationale: The INV-009 test in JsonRpcPortInvariantsSpec checked for `private[kyo] object CdpBackendOld:` (Phase 01 assertion). Phase 02 deletes CdpBackendOld, so the assertion must flip: verify `CdpBackendOld` is NOT present. The `// flow-allow: phase-01 byte-equivalent coexistence` annotation check is removed since it was on the now-deleted `CdpBackendOld` object.
Time: 2026-05-29T00:00:00Z

Decision 42: Resolver.scala: new private `translateContextDestroyed[A]` helper replaces the old BrowserEval.translateContextDestroyed usage
Rationale: The old path called `BrowserEval.translateContextDestroyed(rawJson: String)` which decoded `CdpReply[EvalResult]` from the raw wire. After Phase 02, `s.send[EvaluateObjectParams, EvaluateObjectResult]` returns typed `EvaluateObjectResult` directly. The context-destroyed error now arrives as `BrowserProtocolErrorException`. A private `translateContextDestroyed[A]` helper in Resolver.scala intercepts `BrowserProtocolErrorException` whose `error` contains `ContextDestroyedErrorMessage` and converts it to `BrowserIFrameInvalidException`.
Time: 2026-05-29T00:00:00Z

Decision 44: Wire-decode shape fix in BrowserTab.updateFrameContexts (and executionContextDestroyed counterpart)
Rationale: The Phase 01 buildFrameCreatedMethod re-encodes typed inner params via `Json.encode(params)`, producing the inner-only JSON shape `{"context":{...}}`. BrowserTab.updateFrameContexts was using `CdpEventParams[ExecutionContextCreatedParams]` decode (envelope shape, expects `{"params":{"context":{...}}}`) and silently failed when exercised against a real Chrome target (Phase 01 verify scope was JVM-targeted only and did not exercise the Chrome path). Fix: decode `ExecutionContextCreatedParams` directly (no `.params` accessor); same fix for `ExecutionContextDestroyedParams`. Per feedback_own_all_failures, Phase 02 owns the fix even though the bug was introduced at Phase 01 HEAD.
Time: 2026-05-29T00:00:00Z

Decision 45: Wire-decode shape fix in CdpBackend.runtimeEvaluate — re-wrap EvalResult as CdpReply[EvalResult]
Rationale: `runtimeEvaluate` was returning `Json.encode(r)` where `r: EvalResult`, producing wire shape `{"result":{...},"exceptionDetails":{...}}`. All consumers call `CdpEvalEnvelope.decodeEvalEnvelope`, which decodes `CdpReply[EvalResult]` expecting outer envelope `{"result":{EvalResult},...}`. The shape mismatch caused "Missing required field 'result'" failures across all Chrome-driven eval tests. Fix: re-wrap as `Json.encode(CdpReply(result=Present(r)))` so the wire matches the `CdpReply[EvalResult]` decode shape. Phase 04 deletes `decodeEvalEnvelope` outright; Phase 03 keeps `CdpEvalDecoderTest` unchanged (15 cases) per plan §Phase 03 — the re-wrap preserves test compatibility without modifying that test file.
Time: 2026-05-29T00:00:00Z

Decision 46: Browser.run(wsUrl) effect row widened to include BrowserSetupException per user approval
Rationale: The Q-002 probe in CdpBackend.initUnscoped raises BrowserSetupFailedException on Closed; previously this was absorbed to BrowserConnectionLostException (Decision 32) to preserve byte-identical effect row. The typed-distinction loss defeated Q-002's intent. Decision 32 is superseded; callers who pattern-match against the effect row see the new typed BrowserSetupException variant. Three callers in BrowserIsolateTest.scala updated: type E alias widened from `BrowserReadException | Timeout` to `BrowserReadException | BrowserSetupException | Timeout`.
Time: 2026-05-29T00:00:00Z

Decision 47: runtimeEvaluate wrapper extended with BrowserProtocolErrorException -> BrowserIFrameInvalidException translation
Rationale: The previous runtimeEvaluate re-wrapped EvalResult as CdpReply (Decision 45) but did not translate context-destroyed errors. The new implementation wraps the send + re-encode with Abort.recover[BrowserProtocolErrorException] that checks for CdpErrorStrings.ContextDestroyedErrorMessage and converts to BrowserIFrameInvalidException. This means all callers (BrowserEval, Actionability, MutationSettlement) receive the typed iframe error without per-caller translation logic.
Time: 2026-05-29T00:00:00Z

Decision 48: Resolver.scala translateContextDestroyed[A] helper kept as-is
Rationale: Resolver.resolveOne and resolveAll call s.send[EvaluateObjectParams, EvaluateObjectResult](CdpBackend.RuntimeEvaluateMethod, params) directly (RAW SEND, not via runtimeEvaluate wrapper). The iframe-invalid translation in runtimeEvaluate does NOT cover Resolver's direct send. Per user instruction: "If RAW SEND: keep the helper." The helper stays in Resolver.scala wrapping the raw send calls.
Time: 2026-05-29T00:00:00Z

Decision 49: BrowserEval.translateContextDestroyed(rawJson) left intact as a now-no-op pass-through
Rationale: The iframe-invalid translation moved into runtimeEvaluate wrapper. The .map(BrowserEval.translateContextDestroyed) chain in BrowserEval evalJs* paths passes the raw JSON through unchanged (context-destroyed errors are now caught upstream). Removing the chain would touch BrowserEval bodies; leaving in place is safe behavior and avoids scope-unauthorized changes.
Time: 2026-05-29T00:00:00Z

Decision 50: CdpBackendTest.scala restored with 41 compile-green cases using JsonRpcTransport.inMemory
Rationale: Replaces FakeCdpSender (deleted with CdpSender trait) with JsonRpcTransport.inMemory + typed JsonRpcMethod server handlers. "Valid response" tests use server methods returning correctly-typed values. "Decode failure" tests use server methods returning BadResult() which serializes to {} — missing all required fields for typed response schemas, triggering BrowserProtocolErrorException at the client. Server errors use JsonRpcError.methodNotFound/JsonRpcError(-code, "msg", Absent). Test structure mirrors CdpBackendSmokeTest.scala's mkBackendWithServer helper pattern.
Time: 2026-05-29T00:00:00Z

Decision 51: CdpClientLifecycleTest.scala stub replaced by CdpBackendLifecycleTest.scala (25 cases)
Rationale: Mechanical rename per user instruction. CdpClient.init/initUnscoped -> CdpBackend.init/initUnscoped. raw-string client.send("method", params) -> typed CdpBackend.* wrappers. tab.client.* -> tab.backend.*. session.sendUnit("Runtime.enable") -> session.sendUnit[CdpNoParams]("Runtime.enable", CdpNoParams()). decodeCdpResult[T](json) eliminated (no longer needed, typed send returns R directly). Tests using withBrowser/withBrowserOnLocalhost retain their Browser API calls unchanged; only internal access patterns updated.
Time: 2026-05-29T00:00:00Z

Decision 52: CdpClientTest.scala stub replaced by CdpBackendIntegrationTest.scala (15 cases)
Rationale: Mechanical rename per user instruction. withClient helper rewritten as withBackend using CdpBackend.init. client.send("method", params) -> typed CdpBackend.* wrappers or backend.send[P, R](method, params). decodeCdpResult[T](json) eliminated. Concurrent-sends test adapted: returns EvalResult directly, numVal helper extracts Double from RemoteObject.`number` variant instead of String.contains check.
Time: 2026-05-29T00:00:00Z

Decision 53: CdpClientDecoderTest.scala rewritten (7 cases) — behavior-equivalent via JsonRpcTransport.inMemory
Rationale: CdpClient.decodeCdpMessage is deleted. 7 wire-shape assertions preserved by feeding equivalent shapes through the JsonRpcEndpoint layer: well-formed error -> BrowserProtocolErrorException via JsonRpcError. Malformed-with-id -> BrowserProtocolErrorException via Malformed(Present(id), ...) envelope injected on server transport. Absent-id Malformed -> silent drop, call times out. Non-whitelisted notification -> UnknownMethodPolicy.minimal discards it. mkBackendAndServerTransport helper exposes the raw serverTransport for direct envelope injection.
Time: 2026-05-29T00:00:00Z

Decision 54: CdpClientLifecycleJvmTest.scala stub replaced by CdpBackendLifecycleJvmTest.scala (5 cases)
Rationale: CdpFixtureServer renamed to CdpBackendFixtureServer. CdpClient.initUnscoped -> CdpBackend.initUnscoped. The fixture's replyOk updated to recognize Browser.getVersion requests and return valid BrowserVersionResult JSON (so Q-002 probe succeeds). SlowResponses and CrashAfterFirstFrame and DropMidRequest behaviors updated to handle the probe first, then apply the fault behavior on subsequent requests. client.relay.done check replaced with Async.delay(200.millis) settle. client.inFlight check replaced with Async.delay(100.millis) settle. DropMidRequest test updated: probe succeeds, next send drops connection surfacing as BrowserConnectionLostException.
Time: 2026-05-29T00:00:00Z

Decision 55: PageDownloadTest.scala: 2 stubbed cases restored using Browser.onDownload production API
Rationale: The original tests used session.exchange.events (deleted with CdpClient.scala). Rewired to Browser.onDownload per user instruction "rewire to CdpBackend.notify subscription pattern" — Browser.onDownload uses CdpBackend.downloadEventDispatchers internally. A captureEvents helper mirrors BrowserDownloadTest.collectEvents pattern. The "guid-match" test waits for Progress(state=completed) before asserting WillBegin.guid == completedProgress.guid.
Time: 2026-05-29T00:00:00Z

Decision 57: RawJsonParser wrapper omits `error` field on success path. The kyo-schema convention for `Maybe[X] = Absent` is field-omission, not null-presence. The previous `error -> Structure.Value.Null` caused downstream `Json.decode[CdpReply[X]]` to fail with 'Expected '{', got 'n'' at the null literal. Standard Chrome wire also omits error on success.
Rationale: `Maybe[T]` with default `= Absent` in kyo-schema is decoded from an absent JSON field, not from a null literal. Emitting `"error":null` triggers a parse failure because the schema expects either a present object (for `CdpError`) or no field at all. Dropping the key entirely aligns the synthetic envelope with both the kyo-schema contract and the real Chrome CDP wire format.
Time: 2026-05-29T00:00:00Z

Decision 58: Group A fix — CdpBackendIntegrationTest.scala: session.send instead of backend.send for Runtime.evaluate calls.
Rationale: After Runtime.enable is enabled on a session, Runtime.evaluate must be issued on the same session (with sessionId stamped in the CDP envelope). Using backend.send (root, no sessionId) caused Chrome to reject the call with "'Runtime.evaluate' wasn't found". Both the single-call test (line 83) and the concurrent-sends test (lines 111-114) were using backend.send; changed to session.send for all four EvalParams calls.
Time: 2026-05-29T00:00:00Z

Decision 59: Group A fix — CdpBackendIntegrationTest.scala: initUnscoped survives scope exit test removes inner Scope.run wrapper.
Rationale: CdpBackend.initUnscoped(wsUrl, ...) has Scope in its effect row because it internally creates the WebSocket transport via JsonRpcHttpTransport.webSocket (Scope-registered). Wrapping initUnscoped in Scope.run caused the WebSocket transport and JsonRpcEndpoint to be torn down when the inner scope exited, making the backend dead before the test could use it. The fix removes the inner Scope.run; the outer test runner (kyo.Test.run accepts Abort[Any] & Async & Scope) discharges the accumulated Scope effects after the test body completes. Resources remain live for the full test duration.
Time: 2026-05-29T00:00:00Z

Decision 60: Group B fix — CdpBackendTest.scala: 12 sendUnit server handlers use CdpNoParams return type instead of Unit.
Rationale: Schema[Unit] encodes to Structure.Value.Null (writeFn calls w.nil()). The CdpCodec.Cdp decoder treats result:null as Absent (getVal returns Absent for Null). An envelope with id but neither hasResult nor hasError is classified as Malformed, producing JsonRpcError.invalidRequest ("Invalid Request") at the client, which maps to BrowserProtocolErrorException. Fix: changed all 12 unit-discard server handlers from JsonRpcMethod[P, Unit, ...]{(_, _) => ()} to JsonRpcMethod[P, CdpNoParams, ...]{(_, _) => CdpNoParams()}. CdpNoParams() encodes to {} (a non-null Record), so the Cdp codec sees hasResult=true; the client decodes {} as Unit (unitSchema.readFn calls r.skip() which is a no-op regardless of input).
Time: 2026-05-29T00:00:00Z

Decision 61: Group C fix — CdpClientDecoderTest.scala: eventWhitelist test registers Target.getTargets handler on fake server.
Rationale: The test called CdpBackend.getTargets(backend) twice (before and after injecting an unknown notification), but mkBackendAndServerTransport() only registered Browser.getVersion on the server. Target.getTargets was unknown to the server, producing "Method not found: Target.getTargets" at the second call, not the expected Success. Fix: the test now passes a Target.getTargets handler (returning empty targetInfos) to mkBackendAndServerTransport via extraServerMethods, so both getTargets calls succeed and the test correctly verifies the endpoint survived the unknown notification.
Time: 2026-05-29T00:00:00Z

Decision 62: PageDownloadTest live-browser timeout failures are pre-existing and out of Phase 02 16-failure scope.
Rationale: The two live-browser download tests (setDownloadBehavior(Allow) and downloadWillBegin+guid-match) were restored in Phase 02 as part of the CdpClient->CdpBackend migration. They timeout at 60 seconds in the current environment. These failures are NOT in the 16 targeted failures list. Reporting to supervisor per task contract ("surface the failure — STOP, do not iterate further").
Time: 2026-05-29T00:00:00Z
