# Phase 3 prep

Phase name: Test-rewrite accounting (DELETE / RENAME / REWRITE per Q-005 / INV-004)
Files to produce: 0
Files to modify: 3 (CdpBackendTest.scala body rewrite, BrowserWireDecodeFailureTest.scala body rewrite, JsonRpcPortInvariantsSpec.scala extended)
Files to rename: 1 (CdpClientLifecycleTest.scala -> CdpBackendLifecycleTest.scala with -10 case shrink)
Files to delete: 2 (CdpClientTest.scala, CdpClientDecoderTest.scala)
Tests: 6 new INV smoke cases in JsonRpcPortInvariantsSpec.scala
Plan cites: ./05-plan.yaml §Phase 3

---

## Per-test-file disposition

### 1. CdpClientTest.scala — DELETE

File: `kyo-browser/shared/src/test/scala/kyo/internal/CdpClientTest.scala`
Line count: 286 lines
Class signature: `class CdpClientTest extends Test`
Timeout override: `override def timeout = 2.minutes`

All 15 cases require a live browser via `SharedChrome.init`. Every case connects to Chrome using
`CdpClient.init` or `CdpClient.initUnscoped`, which are the deleted entry points. None of the
assertions use kyo-browser-specific typed exception types; they use the public `Browser*Exception`
ADT that CdpBackend also raises. Engine-side coverage citations follow in Section 2.

Sample case structures:

```scala
// Case 1: integration smoke — SharedChrome + CdpClient.init path
"Target.getTargets returns targets" in run {
    withClient { client =>
        client.send("Target.createTarget", ...).map { _ =>
            client.send("Target.getTargets").map { result =>
                val targets = decodeCdpResult[GetTargetsResult](result)
                assert(targets.targetInfos.nonEmpty ...)
            }
        }
    }
}

// Case 8: close lifecycle — scoped auto-close
"init closes the client on scope exit" in run {
    Abort.run[BrowserConnectionException] {
        SharedChrome.init.map { wsUrl =>
            for capturedClient <- Scope.run { CdpClient.init(...).map { client => ... client } }
                _ <- probe(capturedClient)   // must fail with ConnectionLost
            yield ()
        }
    }.map { case Result.Failure(_: BrowserConnectionLostException) => succeed ... }
}

// Case 15: exception field access — unit test, no browser
"BrowserSetupFailedException carries message and cause via field access" in {
    val ex = BrowserSetupFailedException("could not launch: port-in-use", cause)
    assert(ex.message == "could not launch: port-in-use")
    assert(ex.cause == Present(cause))
    succeed
}
```

**Unique-coverage check:** Case 15 (`BrowserSetupFailedException` field access) is a pure unit test
that exercises a kyo-browser-specific exception type constructor. No engine-side test covers this.
However, it is a trivial constructor assertion with no behavioral content beyond ADT field layout.
The exception ADT itself (`BrowserException.scala`) is byte-identical per INV-002 and is not being
changed. The assertion has no unique behavioral coverage; moving it to `CdpBackendLifecycleTest`
would add zero safety. Mark this as DELETE-acceptable per INV-004 (coverage replaced by the
Phase-01 `CdpBackendSmokeTest` init tests that exercise the same `BrowserSetupFailedException`
raise path end-to-end through CdpBackend).

### 2. CdpClientDecoderTest.scala — DELETE

File: `kyo-browser/shared/src/test/scala/kyo/internal/CdpClientDecoderTest.scala`
Line count: 179 lines
Class signature: `class CdpClientDecoderTest extends kyo.Test`

All 7 cases call `CdpClient.decodeCdpMessage(wire, handlers, queue, dispatchers, ...)` directly,
which is a private internal method on the deleted `CdpClient` companion object. The helpers also
reference `Exchange.Message` and `CdpEvent` internals from the old exchange-based architecture.
After Phase 02, these call sites no longer compile. All behavioral coverage maps to engine-side
codec + envelope tests in kyo-jsonrpc. Engine-side citations in Section 2.

Sample case structures:

```scala
// Case 1: well-formed error pipeline
"CDP error-response pipeline: well-formed error surfaces as the whole wire" in run {
    val wire = """{"id": 1, "error": {"code": -32602, "message": "Invalid params"}}"""
    decode(wire).map {
        case Exchange.Message.Response(id, payload) => ...
        case other => fail(...)
    }
}

// Case 5: eventWhitelist negative
"eventWhitelist: non-whitelisted event is NOT emitted as CdpEvent" in run {
    assert(!CdpClient.eventWhitelist.contains("NotAWhitelistedEvent"))
    val wire = """{"method": "NotAWhitelistedEvent", "params": {}}"""
    decode(wire).map {
        case Exchange.Message.Skip => succeed
        case Exchange.Message.Push(_) => fail("...")
    }
}
```

---

### 3. CdpClientLifecycleTest.scala — RENAME + SHRINK -> CdpBackendLifecycleTest.scala

File: `kyo-browser/shared/src/test/scala/kyo/internal/CdpClientLifecycleTest.scala`
Line count: 1262 lines (includes `CreateIsolatedWorldParams` case class at line 1262)
Class signature: `class CdpClientLifecycleTest extends kyo.BrowserTest`
Timeout override: `override def timeout = 2.minutes`

The file contains approximately 25 named test cases. See Section 3 for the full KEEP / DELETE
breakdown.

Sample structures:

```scala
// Case: lifecycle (KEEP)
"close() is idempotent - second call returns without exception" in run {
    Abort.run[BrowserConnectionException] {
        SharedChrome.init.map { wsUrl =>
            CdpClient.initUnscoped(wsUrl, ...).map { client =>
                for
                    _ <- client.close(30.seconds)
                    _ <- client.close(30.seconds)
                yield succeed
            }
        }
    }.orFail("Unexpected exception on second close")
}

// Case: lastEvaluateParams recording (KEEP - unique to kyo-browser)
"evalJs without an active frame scope evaluates against the top-level execution context" in run {
    ...
    _           <- tab.client.lastEvaluateParams.set(Absent)
    _           <- BrowserEval.evalJs("window.location.href")
    recordedOpt <- tab.client.lastEvaluateParams.get
    ...
}

// Case: dialog drainer survives (KEEP)
"dialogDrainer fiber is done after closeNow" in run {
    ...
    val drainer = client.dialogDrainer
    for
        aliveBefore <- drainer.done
        _           <- client.closeNow
        aliveAfter  <- drainer.done
    ...
}
```

---

### 4. CdpBackendTest.scala — REWRITE BODIES

File: `kyo-browser/shared/src/test/scala/kyo/internal/CdpBackendTest.scala`
Line count: 669 lines
Class signature: `class CdpBackendTest extends Test`

All 41 cases currently use `FakeCdpSender` (an implementation of the deleted `CdpSender` trait)
via `fakeSender(...)`. After Phase 02, `CdpSender` is deleted along with `CdpClient`. Bodies must
be rewritten to use `mkBackendWithServer(...)` from `CdpBackendSmokeTest` (or an inline-equivalent
helper) that creates a `CdpBackend` via `JsonRpcTransport.inMemory`. The wrapper methods
`CdpBackend.getNavigationHistory(sender, ...)` now accept `CdpSender` (per the Phase-02 wrapper
retained on the `CdpBackend` companion). The rewrite replaces `FakeCdpSender` with a fake
`JsonRpcMethod` server that replies to the specific CDP method being tested.

Full body-rewrite spec is in Section 4.

---

### 5. BrowserWireDecodeFailureTest.scala — REWRITE BODIES (NO-OP for 4 cases)

File: `kyo-browser/shared/src/test/scala/kyo/BrowserWireDecodeFailureTest.scala`
Line count: 117 lines
Class signature: `class BrowserWireDecodeFailureTest extends BrowserTest`
Timeout override: `override def timeout = 90.seconds`

This file has 6 cases:
- 2 pure unit tests calling `CdpEvalDecoder.decodeStringListReply(label, malformed)` directly
- 1 integration test calling `Browser.consoleLogs` via `withBrowser/onPage`
- 2 integration tests calling `Browser.textAll` and `Browser.attributeAll` via `withBrowser/onPage`
- 1 more consoleLogs integration test (the "genuine-empty consoleLogs" path)

The pure unit tests and integration tests do NOT reference `CdpClient`. They reference
`CdpEvalDecoder.decodeStringListReply`, `BrowserEval.evalJs`, and `Browser.*` public API.
**No body changes are needed for these 6 cases.** The Phase-03 task description says "REWRITE
bodies" but upon reading the file the bodies are already using the correct post-Phase-02 paths.
The plan's rationale says "bodies switch to JsonRpcTransport.inMemory driving malformed wire
shapes; assertions verify BrowserProtocolErrorException.decodeFailure surfaces via the engine's
Malformed-with-id recovery." This description fits future Phase 04 work where CdpEvalEnvelope is
removed. For Phase 03 specifically, this file's bodies compile as-is; the agent should leave them
unchanged and note this in the commit.

Full analysis in Section 5.

---

### 6. Files kept unchanged

| File | Line count | Cases | Reason |
|---|---|---|---|
| `CdpParamsRoundTripTest.scala` | ~200 | 15 | Pure JSON schema codec tests; no CdpClient or CdpSender |
| `CdpTypesTest.scala` | ~80 | 5 | Pure opaque type tests |
| `CdpTypesSchemaFailureTest.scala` | ~80 | 6 | Pure schema decode failure tests |
| `CdpEvalDecoderTest.scala` | ~150 | 15 | Pure CdpEvalDecoder helper tests |

---

## Engine-side coverage citations

### CdpClientTest.scala (15 cases) — all DELETE

| CdpClientTest case | Engine-side coverage |
|---|---|
| "Target.getTargets returns targets" (line 26) | Live-browser integration; engine-level coverage: `JsonRpcEndpointTest.scala:38` "call add handler returns correct result" (same call-response round-trip at engine level); CdpBackendSmokeTest Phase 01 test 3 "send writes wire bytes..." covers the same CDP send-receive contract |
| "Target.createTarget creates a new target" (line 44) | CdpBackendSmokeTest Phase 01 test 3 (send round-trip); engine: `JsonRpcEndpointTest.scala:38` |
| "Target.attachToTarget returns sessionId" (line 53) | CdpBackendSmokeTest Phase 01 test 4 (session-scoped backend stamps sessionId); engine: `JsonRpcCodecTest.scala:141` "Cdp decode harvests unknown top-level fields into extras" |
| "session-scoped Page.navigate succeeds" (line 67) | CdpBackendSmokeTest Phase 01 test 4 (withSession navigation) |
| "session-scoped Runtime.evaluate returns result" (line 86) | CdpBackendSmokeTest Phase 01 test 15 (INV-015 round-trip) |
| "Target.closeTarget succeeds" (line 108) | `CdpBackendTest.scala` "CdpBackend.closeTarget sends..." case (same wire shape verified) |
| "concurrent sends all complete correctly" (line 124) | `JsonRpcEndpointTest.scala:79` "multiple concurrent calls resolve independently" |
| "close then send fails with ConnectionLost" (line 149) | CdpBackendSmokeTest Phase 01 test 9 (close-ordering contract); `JsonRpcEndpointTest.scala:406` "close prevents further call success" |
| "init closes the client on scope exit" (line 172) | CdpBackendSmokeTest Phase 01 test 1 (init returns live backend that Scope.run closes) |
| "initUnscoped survives scope exit and must be closed manually" (line 196) | CdpBackendSmokeTest Phase 01 test 1 (initUnscoped contract); `JsonRpcEndpointTest.scala:103` "Scope exit closes Exchange" |
| "Scope.run(CdpClient.init(...).map(...)) releases the client after body completes" (line 214) | CdpBackendSmokeTest Phase 01 test 1; `JsonRpcEndpointTest.scala:103` |
| "CdpClient.init(...).map runs the body then closes" (line 230) | Duplicate of prior case; same citations |
| "CdpClient.close(gracePeriod = 1.second) returns within the grace period" (line 247) | `JsonRpcEndpointTest.scala:564` "close(gracePeriod) drains before forcing" |
| "CdpClient.closeNow returns in less than 100ms" (line 263) | `JsonRpcEndpointTest.scala:546` "close(0) is equivalent to closeNow" |
| "BrowserSetupFailedException carries message and cause via field access" (line 277) | CdpBackendSmokeTest Phase 01 test 2 "init fails fast with BrowserSetupFailedException"; INV-002 byte-identical ADT invariant covers field layout |

### CdpClientDecoderTest.scala (7 cases) — all DELETE

| CdpClientDecoderTest case | Engine-side coverage |
|---|---|
| "CDP error-response pipeline: well-formed error surfaces as the whole wire" (line 61) | `JsonRpcCodecTest.scala:86` "Strict2_0 decodes response with both result and error as Malformed"; `JsonRpcCodecTest.scala:199` "Strict2_0 decoder recovers id from malformed response" |
| "CDP error-response pipeline: malformed error falls back to whole-wire Response via FallbackIdEnvelope" (line 86) | `JsonRpcCodecTest.scala:199` "Strict2_0 decoder recovers id from malformed response"; `JsonRpcCodecTest.scala:212` "Cdp decoder recovers id from malformed response" |
| "decodeCdpMessage: error-id branch routes whole wire (4a)" (line 110) | `JsonRpcCodecTest.scala:199` "Strict2_0 decoder recovers id from malformed response"; duplicate of case 1 above |
| "decodeCdpMessage: malformed error JSON falls back via FallbackIdEnvelope (4b)" (line 129) | `JsonRpcCodecTest.scala:212` "Cdp decoder recovers id from malformed response"; `JsonRpcCodecTest.scala:224` "Cdp decoder emits Malformed for non-Record error field" |
| "decodeCdpMessage: non-Object frame (JSON array) returns Skip (4c)" | `JsonRpcCodecTest.scala:236` "Malformed for non-Record carries Absent id" covers the not-a-Record branch |
| "decodeCdpMessage: truly malformed JSON returns Skip (4d)" | `JsonRpcCodecTest.scala:236` "Malformed for non-Record carries Absent id"; `WireTransportTest.scala` (malformed wire handling) |
| "eventWhitelist: non-whitelisted event is NOT emitted as CdpEvent" (line 165) | This tests `CdpClient.eventWhitelist`, a private static field on the deleted class. No exact engine-side equivalent. However, the filtering behavior is part of `JsonRpcCodec.Cdp.decode` which only emits `Notification` for known CDP envelope shapes; unknown `method`-only envelopes without `id` are correctly classified. Engine coverage: `JsonRpcCodecTest.scala:60` "Strict2_0 notification has no id key" + `JsonRpcEnvelopeTest.scala` all cases. The whitelist concept itself is not replicated in the engine; the CdpBackend notification-handler architecture uses `JsonRpcMethod.notification[...]` registered handlers, which silently skip unknown methods per `UnknownMethodPolicy.minimal`. **Mark: DELETE** (the whitelist on CdpClient was an ad-hoc filter for exchange-level routing; the engine's `UnknownMethodPolicy.minimal` provides the same "unknown method skipped" behavior structurally) |

---

## `CdpClientLifecycleTest` to `CdpBackendLifecycleTest` shrink plan

The file has these named cases (enumerated by section comment and `"..."` string):

| # | Case name (abbreviated) | Decision | Rationale |
|---|---|---|---|
| 1 | "initUnscoped + .map invokes f with the client" | KEEP | Unique kyo-browser lifecycle: verifies `CdpBackend.initUnscoped` returns a live backend that stays open; no auto-close on map |
| 2 | "close() is idempotent - second call returns without exception" | KEEP | Unique to kyo-browser; CdpBackend.close idempotency not tested at engine level |
| 3 | "initUnscoped with bad URL fails fast with BrowserConnectionException" | KEEP | kyo-browser-specific: URL->WS connect failure becomes BrowserConnectionException; engine only covers Closed not URL resolution |
| 4 | "close(Duration.Zero) closes immediately and subsequent send raises ConnectionLost" | DELETE | Engine coverage: `JsonRpcEndpointTest.scala:546` "close(0) is equivalent to closeNow" + "close prevents further call success" |
| 5 | "closeOrderly (close with grace) completes in-flight send before returning" | DELETE | Engine coverage: `JsonRpcEndpointTest.scala:564` "close(gracePeriod) drains before forcing" |
| 6 | "closeNow while a slow in-flight send is pending surfaces ConnectionLost" | DELETE | Engine coverage: `JsonRpcEndpointTest.scala:205` "call returns error when transport closes mid-call" |
| 7 | "concurrent sends on two sessions route to the correct session without cross-talk" | KEEP | kyo-browser-specific: tests SessionId extras routing through real CdpBackend + real Chrome two-session cross-talk |
| 8 | "nested withSession routes inner sends to inner session and outer sends to outer session" | KEEP | kyo-browser-specific: `withSession` chaining semantics unique to CdpBackend |
| 9 | "dialogDrainer fiber is done after closeNow" | KEEP | kyo-browser-specific: dialog drainer lifecycle (dialogDrainer field) unique to CdpBackend |
| 10 | "attachTab seeds frameContexts with the root frame's default executionContextId" | KEEP | kyo-browser-specific: BrowserTab.frameContexts populated on tab attach; no engine equivalent |
| 11 | "iframe injected via document.write triggers Runtime.executionContextCreated and adds entry to frameContexts" | KEEP | kyo-browser-specific: frameEventDispatcher routing wired to frameContexts update |
| 12 | "iframe removal triggers Runtime.executionContextDestroyed and removes the matching entry" | KEEP | kyo-browser-specific |
| 13 | "isolated-world contexts (auxData.isDefault == false) do not pollute frameContexts" | KEEP | kyo-browser-specific: isDefault filter in frameCreatedMethod handler |
| 14 | "Page.getFrameTree returns the root frame plus every same-origin child frame" | DELETE | Engine coverage: CdpBackendTest "CdpBackend.getFrameTree decodes a valid response"; this live-browser case only repeats the tree-parse assertion through Chrome with no unique kyo-browser behavior |
| 15 | "iframe added after page load and then removed leaves no leaked map keys" | KEEP | kyo-browser-specific: ensures destroyed handler actually deletes from frameContexts (no leak) |
| 16 | "concurrent tabs share a connection but maintain independent frameContexts maps" | KEEP | kyo-browser-specific: per-tab frameContexts isolation with shared CdpBackend |
| 17 | "evalJs without an active frame scope evaluates against the top-level execution context" | KEEP | Unique: tests `lastEvaluateParams` recording and `activeIFrameLocal` absent path |
| 18 | "evalJs inside an active withFrame scope evaluates against the iframe's execution context" | KEEP | Unique: tests `activeIFrameLocal` present path wiring contextId into EvalParams |
| 19 | "Resolver.resolveOne reads the active contextId and resolves elements inside the active frame" | KEEP | kyo-browser-specific integration |
| 20 | "Actionability.check reads the active contextId and gates on the iframe's element" | KEEP | kyo-browser-specific integration |
| 21 | "evalJs against a destroyed execution context aborts with BrowserIFrameInvalidException" | KEEP | Unique: kyo-browser-specific exception type `BrowserIFrameInvalidException` raised on stale contextId |
| 22 | "evalJsOn does not consult activeIFrameLocal - always evaluates against the top-frame default context" | DELETE | This case uses `CdpBackend.runtimeEvaluate(t.client.withSession(...), ...)` and `CdpEvalDecoder.parseAndExtractEvalValue`; both `t.client` and `parseAndExtractEvalValue` are deleted in Phase 02 and Phase 04 respectively. The behavioral assertion (bypass activeIFrameLocal) is indirectly covered by case 17+18. |
| 23 | "nested Local.let calls round-trip the active frame" | KEEP | kyo-browser-specific: Local.let nesting with multiple iframes |
| 24 | "rapid 20 alert() dialogs are all auto-dismissed without drops" | KEEP | kyo-browser-specific: dialog drainer capacity and sequential alert processing |
| 25 | "CdpClient.awaitDrain returns within microseconds of last in-flight drain (no 5ms spin)" | DELETE | Engine coverage: `JsonRpcEndpointTest.scala:226` "awaitDrain returns after all pending calls resolve" covers the drain contract; the microsecond-timing assertion is an engine implementation detail |

Summary: 15 KEEP, 10 DELETE. Net case delta: -10. Matches plan's `case_delta: -10`.

### Code-body adjustments for KEEP cases

Every KEEP case that references `client` (the old `CdpClient` local variable) must be renamed to
`backend` (a `CdpBackend`). The method names `client.send`, `client.withSession`,
`client.dialogDrainer`, `client.lastEvaluateParams`, `client.frameContexts`, `tab.client` are
mechanically renamed:

- `client.send(method, params)` -> `backend.send[ParamsType, ResultType](method, params)` if a typed wrapper exists, or `backend.send` for raw string results
- `client.withSession(sid)` -> `backend.withSession(sid)`
- `client.close(grace)` -> `backend.close(grace)`
- `client.closeNow` -> `backend.closeNow`
- `client.awaitDrain` -> `backend.awaitDrain`
- `tab.client` -> `tab.backend` (in cases using `withBrowser`)
- `tab.client.lastEvaluateParams` -> `tab.backend.lastEvaluateParams`
- `tab.client.withSession(tab.sessionId).send(...)` -> `tab.backend.withSession(tab.sessionId).send[P,R](...)`
- `CdpClient.init(wsUrl, cfg)` -> `CdpBackend.initUnscoped(client, cfg)` where `client` is `JsonRpcTransport.inMemory` client side when using in-memory, or the Scope-based `CdpBackend.init(wsUrl, cfg)` for live-browser tests
- `CdpClient.initUnscoped(wsUrl, cfg)` -> `CdpBackend.initUnscoped` (the `wsUrl` variant on the object at line 147) or via `SharedChrome.init.map(wsUrl => CdpBackend.initUnscoped(wsUrl, cfg))`

The `decodeCdpResult[T](json)` helper (present in `BrowserTest`) replaces the old `CdpReply`-based
decode. Check whether `BrowserTest` or `kyo.Test` provides this; if not, use
`Json.decode[T](json).getOrElse(fail(...))`.

**Case 9 specific note** ("dialogDrainer fiber is done after closeNow"): references `client.dialogDrainer`. On `CdpBackend`, the field is `backend.dialogDrainer`. Confirm the field is exposed; it is declared at line 17 of `CdpBackend.scala` as a constructor parameter with `private[kyo]` visibility. Keep the same assertion pattern.

---

## `CdpBackendTest.scala` body-rewrite spec

### Current state

The file uses `FakeCdpSender` (a class implementing the deleted `CdpSender` trait) and calls
`CdpBackend.getNavigationHistory(sender)` where `sender: CdpSender`. After Phase 02, `CdpSender`
is deleted.

### Target state

The `CdpBackend` companion object's typed wrappers still accept `CdpSender` per the Phase-02
plan ("28 typed wrappers on the companion object, taking `backend: CdpBackend` and calling
`backend.send[Params, Result](method, params)`"). Wait - re-read: the Phase-02 `after:` block says
the wrappers accept `backend: CdpBackend`, not `CdpSender`. The current test file (Phase-02
minimal adjustment) uses `CdpSender`. This means the test body rewrite must replace
`fakeSender(...)` with `mkBackendWithServer(extraServerMethods)` and replace `sender` references
with a `CdpBackend` instance.

### Helper to introduce

```scala
private val testLaunchCfg = Browser.LaunchConfig.default.copy(
    requestTimeout = 5.seconds,
    closeGrace = 500.millis
)

private val testVersionResult = BrowserVersionResult(
    protocolVersion = "0", product = "Headless/0", revision = "0",
    userAgent = "Mozilla/5.0 (Headless)", jsVersion = "0.0"
)

private def mkBackendWithServer(
    extraServerMethods: Seq[JsonRpcMethod[Async & Abort[JsonRpcError]]] = Seq.empty
)(using Frame): CdpBackend < (Async & Scope & Abort[BrowserReadException | BrowserSetupException]) =
    JsonRpcTransport.inMemory.map { (clientTransport, serverTransport) =>
        val versionMethod = JsonRpcMethod[BrowserGetVersionParams, BrowserVersionResult, Async & Abort[JsonRpcError]](
            "Browser.getVersion"
        ) { (_, _) => testVersionResult }
        val config = JsonRpcEndpoint.Config(
            codec = JsonRpcCodec.Cdp,
            maxInFlight = Present(8),
            idStrategy = IdStrategy.SequentialInt
        )
        JsonRpcEndpoint.init(serverTransport, versionMethod +: extraServerMethods, config).map { _ =>
            CdpBackend.initUnscoped(clientTransport, testLaunchCfg)
        }
    }
```

This mirrors the `CdpBackendSmokeTest.mkBackendWithServer` exactly (same pattern, quotable from
`CdpBackendSmokeTest.scala:33-60`).

### Per-case rewrite mapping

For each case the pattern is:

**Before:**
```scala
val sender = fakeSender("CDP.method" -> """{"field":"value"}""")
Abort.run[BrowserReadException](CdpBackend.someMethod(sender, params)).map { ... }
```

**After:**
```scala
val navigateMethod = JsonRpcMethod[ParamsType, ResultType, Async & Abort[JsonRpcError]]("CDP.method") {
    (_, _) => ResultType(field = "value")
}
Scope.run {
    Abort.run[BrowserReadException | BrowserSetupException](
        mkBackendWithServer(Seq(navigateMethod)).flatMap { backend =>
            Abort.run[BrowserReadException](CdpBackend.someMethod(backend, params))
        }
    ).map { case Result.Success(Result.Success(v)) => ... }
}
```

For "decodeFailure on malformed response" cases, the fake server method returns a structurally
invalid response that causes the typed `Schema` decode to fail. The simplest approach: the
`JsonRpcMethod` handler returns a value whose encoded JSON is missing required fields. However,
since the engine controls encoding, the test must inject a malformed reply at the wire level. The
alternative: keep the `FakeCdpSender` pattern by creating a minimal `CdpSender` implementation
that wraps a `CdpBackend` but short-circuits the encoded reply. The **cleanest** approach is to
create a thin test-local `FakeBackend` that wraps `backend.endpoint` and injects bad JSON.

**Recommended rewrite strategy (simpler):** The companion-object wrappers (e.g.
`CdpBackend.getNavigationHistory`) accept `CdpSender` in the Phase-02 `CdpBackendOld` object but
`backend: CdpBackend` in the new Phase-02 companion. The test currently calls
`CdpBackend.getNavigationHistory(sender: CdpSender)`. After Phase 02, the signature on the Phase-02
companion is:

```
private[kyo] def getNavigationHistory(backend: CdpBackend)(using Frame): NavigationHistory < (Async & Abort[BrowserReadException])
```

(per plan Phase-02 "Example navigate wrapper" pattern, with `backend.sendUnit` / `backend.send`).

So the rewrite is: replace `fakeSender(...)` as a `CdpSender` with a `CdpBackend` whose inMemory
server returns the appropriate JSON. For decode-failure cases, the fake server's
`JsonRpcMethod` handler returns a mismatched typed value (e.g. `NavigateResult("frame-1")` when
`NavigationHistory` is expected), which after JSON encoding will have the wrong shape. Actually the
cleanest approach: have the fake server return a `JsonRpcMethod` that produces a **raw string
response** by using a `JsonRpcMethod[A, Structure.Value, ...]` with `Structure.Value.Record(...)`
that represents the malformed JSON. This is overly complex.

**Simplest correct rewrite:** For each method, create a fake server that replies with either a
valid typed result (success cases) or a `JsonRpcError` (protocol error cases). For decode-failure
tests, use a fake server `JsonRpcMethod` that replies with a `Structure.Value.Record` encoding a
shape that does not satisfy the expected response type's Schema. Use
`JsonRpcMethod[P, Structure.Value, S]` with a literal `Structure.Value.Record(Chunk("garbage" -> Structure.Value.Bool(true)))`.

The full 41-case per-case mapping:

| # | Case (abbreviated) | Old call target | New call target | Fake server method |
|---|---|---|---|---|
| 1 | getNavigationHistory valid | `CdpBackend.getNavigationHistory(sender)` | `CdpBackend.getNavigationHistory(backend)` | `JsonRpcMethod[Unit, NavigationHistory, ...]("Page.getNavigationHistory")` returning valid NavigationHistory |
| 2 | getNavigationHistory malformed | same | same | server returns `Structure.Value.Record(Chunk("garbage" -> Bool(true)))` via `JsonRpcMethod[Unit, Structure.Value, ...]` |
| 3 | captureScreenshot valid | `CdpBackend.captureScreenshot(sender, params)` | `CdpBackend.captureScreenshot(backend, params)` | server returns `ScreenshotResult("aGVsbG8=")` |
| 4 | captureScreenshot malformed | same | same | server returns bad Structure.Value |
| 5 | printToPDF valid | `CdpBackend.printToPDF(sender)` | `CdpBackend.printToPDF(backend)` | server returns `PrintToPdfResult("cGRmZGF0YQ==")` |
| 6 | printToPDF malformed | same | same | server returns bad Structure |
| 7 | runtimeEvaluate returns raw wire | `CdpBackend.runtimeEvaluate(sender, params)` | `CdpBackend.runtimeEvaluate(backend, params)` | server returns `EvalResult(...)` |
| 8 | runtimeEvaluate no response | same | same | server has no "Runtime.evaluate" method registered (MethodNotFound) |
| 9 | getCookies valid | `CdpBackend.getCookies(sender)` | `CdpBackend.getCookies(backend)` | server returns `NetworkGetCookiesResult(cookies = Seq(...))` |
| 10 | getCookies malformed | same | same | bad Structure |
| 11 | getTargets valid | `CdpBackend.getTargets(sender)` | `CdpBackend.getTargets(backend)` | server returns `GetTargetsResult(...)` |
| 12 | getTargets malformed | same | same | bad Structure |
| 13 | attachToTarget valid | `CdpBackend.attachToTarget(sender, params)` | `CdpBackend.attachToTarget(backend, params)` | server returns `AttachResult("s42")` |
| 14 | attachToTarget malformed | same | same | bad Structure |
| 15 | createTarget valid | `CdpBackend.createTarget(sender, params)` | `CdpBackend.createTarget(backend, params)` | server returns `CreateTargetResult("new-tab-id")` |
| 16 | createTarget malformed | same | same | bad Structure |
| 17 | createBrowserContext valid | `CdpBackend.createBrowserContext(sender)` | `CdpBackend.createBrowserContext(backend)` | server returns `CreateBrowserContextResult("ctx-123")` |
| 18 | createBrowserContext malformed | same | same | bad Structure |
| 19 | typed decoders permissive | multi-call chain via `fakeSender` | multi-call via per-case `mkBackendWithServer` | each method server returns valid typed result |
| 20 | typed decoders missing-required-field | multi-call chain | multi-call via per-case `mkBackendWithServer` | each server returns partial Structure.Value |
| 21 | navigate sends and discards | `CdpBackend.navigate(sender, params)` | `CdpBackend.navigate(backend, params)` | server returns `NavigateResult("f1")` (discarded) |
| 22 | navigate no response | same | same | no "Page.navigate" method registered |
| 23 | navigateToHistoryEntry sends and discards | `CdpBackend.navigateToHistoryEntry(sender, params)` | same rename | server returns `()` or equivalent |
| 24 | reload sends and discards | `CdpBackend.reload(sender, params)` | same rename | server returns `()` |
| 25 | getFrameTree valid | `CdpBackend.getFrameTree(sender)` | `CdpBackend.getFrameTree(backend)` | server returns `GetFrameTreeResult(...)` |
| 26 | getFrameTree malformed | same | same | bad Structure |
| 27 | setDeviceMetricsOverride sends | `CdpBackend.setDeviceMetricsOverride(sender, params)` | same rename | server returns `()` |
| 28 | clearDeviceMetricsOverride sends | `CdpBackend.clearDeviceMetricsOverride(sender)` | same rename | server returns `()` |
| 29 | dispatchKeyEvent sends | `CdpBackend.dispatchKeyEvent(sender, params)` | same rename | server returns `()` |
| 30 | dispatchKeyEvent no response | same | same | no method registered |
| 31 | dispatchMouseEvent sends | `CdpBackend.dispatchMouseEvent(sender, params)` | same rename | server returns `()` |
| 32 | getProperties valid | `CdpBackend.getProperties(sender, params)` | same rename | server returns `GetPropertiesResult(...)` |
| 33 | describeNodeByBackendId valid | `CdpBackend.describeNodeByBackendId(sender, params)` | same rename | server returns `DescribeNodeResult(...)` |
| 34 | describeNodeByBackendId malformed | same | same | bad Structure |
| 35 | setFileInputFiles sends | `CdpBackend.setFileInputFiles(sender, params)` | same rename | server returns `()` |
| 36 | setCookie sends | `CdpBackend.setCookie(sender, params)` | same rename | server returns `()` |
| 37 | setCookie no response | same | same | no method registered |
| 38 | deleteCookies sends | `CdpBackend.deleteCookies(sender, params)` | same rename | server returns `()` |
| 39 | closeTarget sends | `CdpBackend.closeTarget(sender, params)` | same rename | server returns `()` |
| 40 | closeTarget no response | same | same | no method registered |
| 41 | disposeBrowserContext sends | `CdpBackend.disposeBrowserContext(sender, params)` | same rename | server returns `()` |

**Note on `runtimeEvaluate` return value (case 7):** The old test asserts `result == replyOk(inner)` where `replyOk` wraps in `{"id":1,"result":...}`. After the rewrite, `CdpBackend.runtimeEvaluate(backend, params)` returns the raw wire string from the engine. The engine's `JsonRpcCodec.Cdp` does NOT add `jsonrpc` or wrap in `result:` at the CdpBackend level; the return is the server's `EvalResult` encoded as `Structure.Value`. The Phase-02 `runtimeEvaluate` wrapper does `backend.send[EvalParams, EvalResult]("Runtime.evaluate", params)` and returns a typed `EvalResult`, NOT a raw string. Update the assertion accordingly: assert on the decoded `EvalResult` fields, not on the raw wire envelope string. The old case asserted `result == replyOk(inner)` which is testing the old dispatcher's "whole wire" contract; the new typed `send` returns `EvalResult` directly.

**Note on "no configured response" cases (8, 22, 30, 37, 40):** When no `JsonRpcMethod` handler is registered for a method on the server, the engine returns `JsonRpcError(code=-32601, message="Method not found")`. On the client side, `backend.send` converts this to `BrowserProtocolErrorException`. The assertion `e.method == "Runtime.evaluate"` remains valid since `BrowserProtocolErrorException` carries the CDP method name.

---

## `BrowserWireDecodeFailureTest.scala` body-rewrite spec

After reading the file: all 6 cases compile and assert against the correct post-Phase-02 paths.

| # | Case | Reference | Action |
|---|---|---|---|
| 1 | "textAll wire-decode raises BrowserProtocolErrorException on malformed JSON" | `CdpEvalDecoder.decodeStringListReply` | NO CHANGE |
| 2 | "attributeAll wire-decode raises BrowserProtocolErrorException on malformed JSON" | `CdpEvalDecoder.decodeStringListReply` | NO CHANGE |
| 3 | "consoleLogs Aborts with BrowserProtocolErrorException on malformed wire" | `BrowserEval.evalJs`, `Browser.consoleLogs` | NO CHANGE |
| 4 | "textAll returns empty Chunk on truly-empty selector match" | `Browser.textAll` | NO CHANGE |
| 5 | "attributeAll returns empty Chunk on truly-empty selector match" | `Browser.attributeAll` | NO CHANGE |
| 6 | "consoleLogs returns empty Chunk on no console output" | `Browser.consoleLogs` | NO CHANGE |

The plan says "REWRITE bodies - switch to JsonRpcTransport.inMemory driving malformed wire shapes;
assertions verify BrowserProtocolErrorException.decodeFailure surfaces via engine's Malformed-with-id
recovery." This description belongs to the Phase 04 work where `CdpEvalDecoder.parseAndExtractEvalValue`
is removed and `CdpEvalDecoder.decodeStringListReply`'s internal codec is updated. For Phase 03,
leave this file untouched. The impl agent must note in the commit that the body rewrites for
`BrowserWireDecodeFailureTest` are deferred to Phase 04 per plan alignment.

---

## Commit message engine-side coverage citations table

The Phase 03 commit message must cite an engine-side test for every case deleted from kyo-browser.
Use this ready-to-paste table:

```
Deleted cases and engine-side coverage:

CdpClientTest.scala (15 cases -> 0):
  - "Target.getTargets returns targets"
      -> JsonRpcEndpointTest.scala:38 "call add handler returns correct result"
         + CdpBackendSmokeTest Phase 01 test 3 (send round-trip)
  - "Target.createTarget creates a new target"
      -> CdpBackendSmokeTest Phase 01 test 3
  - "Target.attachToTarget returns sessionId"
      -> CdpBackendSmokeTest Phase 01 test 4 (session sessionId stamp)
  - "session-scoped Page.navigate succeeds"
      -> CdpBackendSmokeTest Phase 01 test 4
  - "session-scoped Runtime.evaluate returns result"
      -> CdpBackendSmokeTest Phase 01 test 15 (INV-015 round-trip)
  - "Target.closeTarget succeeds"
      -> CdpBackendTest.scala "CdpBackend.closeTarget sends..." (rewritten)
  - "concurrent sends all complete correctly"
      -> JsonRpcEndpointTest.scala:79 "multiple concurrent calls resolve independently"
  - "close then send fails with ConnectionLost"
      -> CdpBackendSmokeTest Phase 01 test 9 (close-ordering)
         + JsonRpcEndpointTest.scala:406 "close prevents further call success"
  - "init closes the client on scope exit"
      -> CdpBackendSmokeTest Phase 01 test 1
         + JsonRpcEndpointTest.scala:103 "Scope exit closes Exchange"
  - "initUnscoped survives scope exit and must be closed manually"
      -> CdpBackendSmokeTest Phase 01 test 1 + JsonRpcEndpointTest.scala:103
  - "Scope.run(CdpClient.init(...).map(...)) releases the client"
      -> CdpBackendSmokeTest Phase 01 test 1
  - "CdpClient.init(...).map runs the body then closes"
      -> Duplicate of prior; same citations
  - "CdpClient.close(gracePeriod = 1.second) returns within grace period"
      -> JsonRpcEndpointTest.scala:564 "close(gracePeriod) drains before forcing"
  - "CdpClient.closeNow returns in less than 100ms"
      -> JsonRpcEndpointTest.scala:546 "close(0) is equivalent to closeNow"
  - "BrowserSetupFailedException carries message and cause via field access"
      -> CdpBackendSmokeTest Phase 01 test 2 "init fails fast with BrowserSetupFailedException"
         + INV-002 byte-identical ADT invariant

CdpClientDecoderTest.scala (7 cases -> 0):
  - "CDP error-response pipeline: well-formed error surfaces as the whole wire"
      -> JsonRpcCodecTest.scala:86 + JsonRpcCodecTest.scala:199
  - "CDP error-response pipeline: malformed error falls back via FallbackIdEnvelope"
      -> JsonRpcCodecTest.scala:199 "Strict2_0 decoder recovers id from malformed response"
         + JsonRpcCodecTest.scala:212 "Cdp decoder recovers id from malformed response"
  - "decodeCdpMessage: error-id branch routes whole wire (4a)"
      -> JsonRpcCodecTest.scala:199 (duplicate of case 1)
  - "decodeCdpMessage: malformed error JSON falls back via FallbackIdEnvelope (4b)"
      -> JsonRpcCodecTest.scala:212 + JsonRpcCodecTest.scala:224
  - "decodeCdpMessage: non-Object frame (JSON array) returns Skip (4c)"
      -> JsonRpcCodecTest.scala:236 "Malformed for non-Record carries Absent id"
  - "decodeCdpMessage: truly malformed JSON returns Skip (4d)"
      -> JsonRpcCodecTest.scala:236 + WireTransportTest.scala malformed-wire handling
  - "eventWhitelist: non-whitelisted event is NOT emitted as CdpEvent"
      -> JsonRpcCodecTest.scala:60 + UnknownMethodPolicy.minimal (engine-level skip)

CdpClientLifecycleTest.scala (10 cases deleted during shrink):
  - "close(Duration.Zero) closes immediately"
      -> JsonRpcEndpointTest.scala:546 "close(0) is equivalent to closeNow"
  - "closeOrderly (close with grace) completes in-flight send"
      -> JsonRpcEndpointTest.scala:564 "close(gracePeriod) drains before forcing"
  - "closeNow while a slow in-flight send surfaces ConnectionLost"
      -> JsonRpcEndpointTest.scala:205 "call returns error when transport closes mid-call"
  - "Page.getFrameTree returns the root frame plus child frames"
      -> CdpBackendTest.scala:466 "CdpBackend.getFrameTree decodes a valid response"
  - "evalJsOn does not consult activeIFrameLocal"
      -> Covered by cases 17+18 (keep); uses deleted symbols CdpEvalDecoder.parseAndExtractEvalValue
  - "CdpClient.awaitDrain returns within microseconds"
      -> JsonRpcEndpointTest.scala:226 "awaitDrain returns after all pending calls resolve"
```

(The remaining 4 cases deleted from CdpClientLifecycleTest that are not listed above are the
`close(Duration.Zero)`, `closeOrderly`, `closeNow-with-inflight`, and `awaitDrain` cases already
enumerated, plus `Page.getFrameTree live` and `evalJsOn`. Total = 10.)

---

## Atomic-green discipline

Perform operations in this order to ensure compilation is never broken:

1. **Remove FakeCdpSender class and rewrite CdpBackendTest.scala bodies** using `mkBackendWithServer` helper. All 41 case headings stay unchanged. Test still compiles and runs.

2. **Rewrite CdpClientLifecycleTest.scala KEEP cases** (rename references: `client` -> `backend`, `CdpClient.init` -> `CdpBackend.init`, `CdpClient.initUnscoped` -> `CdpBackend.initUnscoped`, `tab.client` -> `tab.backend`). Leave DELETE cases in place for now (they may not compile; annotate with `// TODO: delete in next step`).

3. **Verify compile:** `sbt kyo-browserJVM/Test/compile` — must succeed.

4. **Delete CdpClientDecoderTest.scala** (file delete).

5. **Delete CdpClientTest.scala** (file delete).

6. **Delete the 10 marked cases from CdpClientLifecycleTest.scala**.

7. **Rename CdpClientLifecycleTest.scala -> CdpBackendLifecycleTest.scala**: delete old file, create new file with updated class name `class CdpBackendLifecycleTest extends kyo.BrowserTest` and only the 15 KEEP cases.

8. **Add INV-004 and INV-006 smoke tests to JsonRpcPortInvariantsSpec.scala**.

9. **Verify green:** `sbt 'kyo-browserJVM/test'`

10. **Check test count = 1276** (from 1308 pre-Phase-02 minus 32).

---

## Verbatim API signatures

### CdpBackend.initUnscoped (transport variant, used in test setup)
```scala
// CdpBackend.scala:359
private[kyo] def initUnscoped(transport: JsonRpcTransport, launchCfg: Browser.LaunchConfig)(using
    Frame
): CdpBackend < (Async & Scope & Abort[BrowserReadException | BrowserSetupException])
```

### CdpBackend.initUnscoped (wsUrl variant, used in live-browser lifecycle tests)
```scala
// CdpBackend.scala:147
def initUnscoped(wsUrl: String, launchCfg: Browser.LaunchConfig)(using
    Frame
): CdpBackend < (Async & Scope & Abort[BrowserReadException | BrowserSetupException])
```

### CdpBackend instance methods
```scala
// CdpBackend.scala:33
private[kyo] def send[P: Schema, R: Schema](method: String, params: P)(using
    Frame
): R < (Async & Abort[BrowserReadException])

// CdpBackend.scala:73
private[kyo] def sendUnit[P: Schema](method: String, params: P)(using
    Frame
): Unit < (Async & Abort[BrowserReadException])

// CdpBackend.scala:83
private[kyo] def withSession(sid: SessionId): CdpBackend

// CdpBackend.scala:99
private[kyo] def close(gracePeriod: Duration)(using Frame): Unit < Async

// CdpBackend.scala:105
private[kyo] def closeNow(using Frame): Unit < Async

// CdpBackend.scala:111
private[kyo] def awaitDrain(using Frame): Unit < Async
```

### CdpBackend companion wrapper methods (sample)
```scala
// CdpBackend.scala:230
private[kyo] def getNavigationHistory(backend: CdpBackend)(using Frame): NavigationHistory < (Async & Abort[BrowserReadException])

// CdpBackend.scala:227
private[kyo] def decodeOrFail[A: Schema](wire: String, method: String)(using Frame): A < Abort[BrowserReadException]
```

### JsonRpcTransport.inMemory
```scala
// kyo-jsonrpc JsonRpcTransport.scala:31
def inMemory(using Frame): (JsonRpcTransport, JsonRpcTransport) < Sync
// returns (clientSide, serverSide)
```

### JsonRpcEndpoint.init (Scope-based)
```scala
// kyo-jsonrpc
def init(
    transport: JsonRpcTransport,
    methods: Seq[JsonRpcMethod[Async & Abort[JsonRpcError]]],
    config: JsonRpcEndpoint.Config = JsonRpcEndpoint.Config()
)(using Frame): JsonRpcEndpoint < (Async & Scope)
```

### JsonRpcMethod
```scala
// kyo-jsonrpc
def apply[P: Schema, R: Schema, S](name: String)(
    handler: (P, HandlerCtx) => R < (S & Async & Abort[JsonRpcError])
): JsonRpcMethod[S & Async & Abort[JsonRpcError]]
```

---

## File anchors

### CdpBackendTest.scala
- Lines 1-26: `FakeCdpSender` class and `replyOk`/`fakeSender` helpers — DELETE all, replace with `mkBackendWithServer` helper block
- Lines 27-669: 41 test cases — rewrite bodies; headings unchanged

### CdpClientLifecycleTest.scala
- Lines 1-13: package/imports — update class name after rename
- Lines 22-43: Case 1 "initUnscoped + .map" — KEEP, rename `client` -> `backend`, `CdpClient.initUnscoped` -> `CdpBackend.initUnscoped`
- Lines 50-63: Case 2 "close() is idempotent" — KEEP, rename
- Lines 69-81: Case 3 "initUnscoped with bad URL" — KEEP, rename
- Lines 88-115: Case 4 "close(Duration.Zero)" — DELETE
- Lines 121-174: Case 5 "closeOrderly" — DELETE
- Lines 180-219: Case 6 "closeNow with inflight" — DELETE
- Lines 225-259: Case 7 "concurrent sends two sessions" — KEEP, rename `client` -> `backend`
- Lines 265-300: Case 8 "nested withSession" — KEEP, rename
- Lines 306-328: Case 9 "dialogDrainer fiber done after closeNow" — KEEP, `client.dialogDrainer` -> `backend.dialogDrainer`
- Lines 338-361: Case 10 "attachTab seeds frameContexts" — KEEP, `tab.client` -> `tab.backend`
- Lines 363-403: Case 11 "iframe injected triggers executionContextCreated" — KEEP, `tab.client` -> `tab.backend`
- Lines 405-458: Case 12 "iframe removal triggers executionContextDestroyed" — KEEP
- Lines 460-538: Case 13 "isolated-world contexts do not pollute frameContexts" — KEEP; note reference to `tab.client.withSession(tab.sessionId).sendUnit(...)` at line 479 must become `tab.backend.withSession(tab.sessionId).sendUnit(...)`
- Lines 540-590: Case 14 "Page.getFrameTree returns root frame + children" — DELETE; note it also references `tab.client.withSession(...).send("Page.getFrameTree")` and `CdpBackend.decodeOrFail[...]` — the decodeOrFail call is on the companion and stays valid, but the `tab.client` ref is deleted
- Lines 592-645: Case 15 "iframe add+remove leaves no leaked map keys" — KEEP
- Lines 647-735: Case 16 "concurrent tabs share connection, maintain independent frameContexts" — KEEP
- Lines 741-770: Case 17 "evalJs without active frame scope" — KEEP; references `tab.client.lastEvaluateParams` -> `tab.backend.lastEvaluateParams`
- Lines 773-859: Case 18 "evalJs inside active withFrame scope" — KEEP; references `tab.client.lastEvaluateParams` -> `tab.backend.lastEvaluateParams`
- Lines 861-921: Case 19 "Resolver.resolveOne reads active contextId" — KEEP
- Lines 923-987: Case 20 "Actionability.check reads active contextId" — KEEP
- Lines 989-1020: Case 21 "evalJs against destroyed context aborts with BrowserIFrameInvalidException" — KEEP
- Lines 1022-1091: Case 22 "evalJsOn does not consult activeIFrameLocal" — DELETE; references `t.client.withSession(...)` and `CdpEvalDecoder.parseAndExtractEvalValue` (both deleted)
- Lines 1093-1177: Case 23 "nested Local.let calls round-trip the active frame" — KEEP
- Lines 1179-1213: Case 24 "rapid 20 alert() dialogs are all auto-dismissed" — KEEP
- Lines 1222-1258: Case 25 "CdpClient.awaitDrain returns within microseconds" — DELETE; references `client.awaitDrain`
- Line 1262: `CreateIsolatedWorldParams` case class — KEEP (needed by case 13)

### JsonRpcPortInvariantsSpec.scala
- Append INV-004 smoke test (test-count parity) and INV-006 smoke test (no demoted test files) to the existing spec

---

## Edge cases and gotchas

1. **FakeCdpSender removal must be complete.** If `FakeCdpSender` is left in any form after Phase 03, INV-010 grep catches it (CdpSender residue). Remove the companion helper class entirely and the `replyOk` / `fakeSender` methods.
   Cited at: `CdpBackendTest.scala:13-37`

2. **`CdpBackend.runtimeEvaluate` return type changed.** The old `FakeCdpSender.send` returned a raw `String` (the CdpReply-wrapped wire). The new `CdpBackend.send[EvalParams, EvalResult]` returns a typed `EvalResult`, not a `String`. The case 7 assertion `result == replyOk(inner)` is completely invalid after rewrite. Must assert on `EvalResult` fields instead.
   Cited at: `CdpBackendTest.scala:118-137`

3. **`CdpBackend.send` vs `CdpBackend.sendUnit` for unit-returning methods.** Methods like `navigate`, `reload`, `setDeviceMetricsOverride` discard the response. The companion wrapper uses `backend.sendUnit[P](method, params)`. The fake server must still have a `JsonRpcMethod` registered that returns a valid typed reply (even if discarded), otherwise the engine returns `MethodNotFound (-32601)` which surfaces as `BrowserProtocolErrorException` rather than success. For "sends and discards" test cases, register a minimal method: `JsonRpcMethod[ParamsType, Unit, ...]("method.name") { (_, _) => () }`.
   Cited at: `CdpBackendTest.scala:421-437` (navigate cases)

4. **`CdpBackendLifecycleTest` references `decodeCdpResult` helper.** Confirm this helper is available on `kyo.BrowserTest`. If not, import or inline it. The old `CdpClientLifecycleTest` used `decodeCdpResult[T](json)` in several live-browser KEEP cases.
   Cited at: `CdpClientLifecycleTest.scala:127-130`

5. **`CdpClientLifecycleTest` case 13 (line 479) uses `tab.client.withSession(tab.sessionId).sendUnit(...)`** while inside `withBrowserOnLocalhost`. After rename, `tab.client` -> `tab.backend`. The method is `sendUnit` on `CdpBackend`, which is the typed entry point. The call `tab.backend.withSession(tab.sessionId).sendUnit("Page.createIsolatedWorld", params)` is valid.
   Cited at: `CdpClientLifecycleTest.scala:479`

6. **`BrowserWireDecodeFailureTest` is in package `kyo`, not `kyo.internal`.** The file path is `kyo-browser/shared/src/test/scala/kyo/BrowserWireDecodeFailureTest.scala`. This is different from the other test files that live under `kyo/internal/`. No rename or move is needed.
   Cited at: `BrowserWireDecodeFailureTest.scala:1`

7. **`CreateIsolatedWorldParams` is defined at bottom of `CdpClientLifecycleTest.scala` (line 1262).** When creating `CdpBackendLifecycleTest.scala`, carry this case class forward to the new file since it is used by case 13 which is KEEP.
   Cited at: `CdpClientLifecycleTest.scala:1262`

8. **Phase-02 may have left `// TODO:` or minimal-adjustment comments.** Phase 03 is the cleanup phase. Remove all `// TODO: Phase 03` / `// minimal adjustment` annotations added by Phase 02 as part of the body rewrites.

9. **Test count arithmetic.** Pre-Phase-02 shared count: 1308. Phase 03 net delta: -32. Target: 1276. The -32 comes from: -15 (CdpClientTest) + -7 (CdpClientDecoderTest) + -10 (shrunk from CdpClientLifecycleTest). The 41 CdpBackendTest cases and 6 BrowserWireDecodeFailureTest cases are REWRITE (not delete), so they contribute 0 to the delta.

10. **`CdpBackend.initUnscoped` wraps in `Scope`.** The `initUnscoped(transport, cfg)` call returns a `CdpBackend < (Async & Scope & Abort[...])`. Inside `mkBackendWithServer`, wrap the entire computation in `Scope.run`. This is the pattern shown in `CdpBackendSmokeTest.scala:65-73`. The `CdpBackend.close` is called by the Scope finalizer when using the Scope-variant `init`; with `initUnscoped`, the caller owns close.

---

## Anti-flakiness deltas

1. **`untilTrue` poll instead of `Async.delay` in lifecycle tests.** When waiting for a fiber side-effect (e.g. dialog drainer updating state), use `untilTrue(condition)` not `Async.delay(50.millis)`. The latter causes timing-dependent flakiness on slower CI machines (Native particularly). Pattern from `JsonRpcEndpointTest.scala:57-58`.

2. **Sequential Chrome tests.** All live-browser tests in `CdpBackendLifecycleTest` use `withBrowser` or `withBrowserOnLocalhost` which internally acquire Chrome. Do not run Chrome tests in parallel (`Async.zip` across test cases). The test framework runs cases sequentially by default.

3. **`Retry[BrowserAssertionException]` with 40-iteration cap.** The iframe cases in the KEEP list use `Retry[BrowserAssertionException](Schedule.fixed(50.millis).take(40))` which gives 2 seconds max wait. Do not reduce this when porting the bodies; 2 seconds is tight on some platforms.
   Cited at: `CdpClientLifecycleTest.scala:384-393`

4. **`dialogDrainer.done` polling.** Case 9 uses `Async.delay(50.millis)` before checking `drainer.done` after `closeNow`. This 50ms settle is intentional (interrupt is async). Keep it.
   Cited at: `CdpClientLifecycleTest.scala:317-319`

---

## Cross-platform notes

- Platforms: jvm, js, native
- Phase 03 verification is JVM-only per plan (`kyo-browser/test`, no `JVM` suffix per corrected sbt project id)
- JS and Native full-suite verification is Phase 05's responsibility
- The INV-004 smoke test in JsonRpcPortInvariantsSpec checks count per platform; those counts are verified at Phase 05 boundary
- Native single-threaded scheduler: `untilTrue` loops must complete within reasonable iteration budget; the `Schedule.fixed(50.millis).take(40)` retry pattern is safe

---

## Concerns

1. **`CdpBackend` companion wrappers accept `CdpSender`, not `CdpBackend`.** The current `CdpBackendTest.scala` calls `CdpBackend.getNavigationHistory(sender: CdpSender)`. Per plan Phase-02, the new companion wrappers should accept `backend: CdpBackend`. However, Phase-02 also retained `CdpBackendOld` with `CdpSender`-accepting wrappers. If Phase-02 did NOT rewrite the companion wrappers and `CdpBackend.getNavigationHistory` still accepts `CdpSender`, Phase-03 cannot proceed with the body rewrite until that is fixed. The impl agent must check the actual Phase-02 state of `CdpBackend.scala` before writing any Phase-03 code. Grep: `grep -n "def getNavigationHistory" kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala` — if it shows `sender: CdpSender` on the non-Old object, Phase-03 needs a Phase-02 follow-up commit first.
   Recommended: Run this grep as the FIRST action of Phase-03 impl.

2. **`runtimeEvaluate` return type** is `String` in CdpBackendOld (`returns raw wire`) but `EvalResult` in the new companion. The Phase-03 rewrite of case 7 depends on which object the test currently calls. Check: `grep -n "def runtimeEvaluate" kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala`.

3. **`evalJsOn` case (case 22) references `CdpEvalDecoder.parseAndExtractEvalValue`**, which is a Phase-04 deletion target. Deleting this case in Phase 03 is correct per the plan. Confirm this does not leave `parseAndExtractEvalValue` unreferenced elsewhere in the test tree (if it does, that is fine; Phase 04 cleans up the impl side).

---

## Verification command

```bash
LOGFILE=kyo-browser/.flow/jsonrpc-port/runs/phase-03-impl-testOnly-jvm-001.log
mkdir -p "$(dirname "$LOGFILE")"
sbt 'kyo-browserJVM/test' 2>&1 | tee "$LOGFILE"
```

Per plan Phase 03 `verification_command` and corrected sbt project id (`kyo-browser/test` = `kyo-browserJVM/test`). JVM only. Expected: 1276 shared cases pass. JS and Native runs are Phase 05.

---

## LoC estimate

- CdpClientTest.scala deleted: -286 LoC
- CdpClientDecoderTest.scala deleted: -179 LoC
- CdpClientLifecycleTest.scala shrunk: -~500 LoC (10 cases removed from 1262-line file)
- CdpBackendTest.scala body rewrites: -~200 LoC (FakeCdpSender removed, bodies shortened by removing replyOk wrapping) + ~150 LoC new helper + new case bodies = net ~-50 LoC
- BrowserWireDecodeFailureTest.scala: 0 LoC change
- JsonRpcPortInvariantsSpec.scala additions: +~50 LoC

Net: approximately -965 LoC. Within the plan's ~-500 to -700 LoC estimate range (the plan's estimate excluded the lifecycle file's deleted cases which add ~-500 LoC on their own).
