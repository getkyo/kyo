# Phase 2 pulse 2

Time: 2026-05-29T15:10:00Z
Files reviewed:
- Browser.scala lines 239-303 (effect-row widening)
- CdpBackend.scala lines 247-268 (runtimeEvaluate), lines 44-50 (Structure.Value usage)
- Resolver.scala lines 88-101 (translateContextDestroyed helper)
- CdpBackendTest.scala (all 829 lines, 41 cases)
- CdpBackendLifecycleTest.scala (all 1145 lines, 25 cases)
- CdpClientLifecycleTest.scala (22 lines — stub)
- CdpClientTest.scala (4 lines — empty redirect comment)
- CdpBackendIntegrationTest.scala (all 264 lines, 15 cases)
- CdpClientDecoderTest.scala (all 294 lines, 7 cases)
- CdpBackendLifecycleJvmTest.scala (all 300 lines, 5 cases)
- CdpClientLifecycleJvmTest.scala (3 lines — empty redirect comment)
- cdp/PageDownloadTest.scala (all 149 lines, 3 cases live)
- phase-2-decisions.md (all 126 lines, Decisions 25-55)
Plan cites: ./05-plan.md §Phase 02

## Plan anchor

- Files to produce: CdpBackendTest (41 cases), CdpBackendLifecycleTest (25 cases), CdpBackendIntegrationTest (15 cases), CdpClientDecoderTest (7 cases), CdpBackendLifecycleJvmTest (5 cases), PageDownloadTest 2/3 restored
- Files to modify: Browser.scala (effect-row), CdpBackend.scala (runtimeEvaluate), Resolver.scala (decision on translateContextDestroyed)
- Tests: all present in dirty tree
- Public API additions: Browser.run(wsUrl) effect row widened to include BrowserSetupException

## Verification checklist

### 1. Browser.run(wsUrl) effect row widened?
PASS. Browser.scala line 276:
```
def run[A, S](wsUrl: String)(v: A < (Browser & S))(using Frame): A < (Async & Abort[BrowserReadException | BrowserSetupException] & S) =
```
`Abort.run[BrowserSetupException]` grep returns 0 hits in Browser.scala (only found in SharedChrome.scala which is unrelated). The absorption is gone. `BrowserReadException | BrowserSetupException` appears at all 4 Browser.run overloads (lines 241, 254, 276, 296). CLEAN.

### 2. runtimeEvaluate uses Structure.Value?
PASS. CdpBackend.scala lines 41-44 use `Structure.Value.Record(Chunk("sessionId" -> Structure.Value.Str(sid.value)))` in the ExtrasEncoder.const call. The `send` method is what calls the endpoint with typed R. The runtimeEvaluate wrapper itself at lines 260-268 does not directly reference Structure.Value (that's in `send`'s body). The prompt's intent was that the typed path goes through the `send[P, R]` path which DOES use Structure.Value for extras encoding. PASS (intent satisfied).

### 3. runtimeEvaluate bakes iframe translation?
PASS. CdpBackend.scala lines 260-268:
```scala
private[kyo] def runtimeEvaluate(backend: CdpBackend, params: EvalParams)(using Frame): String < (Async & Abort[BrowserReadException]) =
    Abort.recover[BrowserProtocolErrorException] { e =>
        if e.error.contains(CdpErrorStrings.ContextDestroyedErrorMessage) then
            Abort.fail(BrowserIFrameInvalidException(BrowserIFrameInvalidException.Reason.ContextDestroyed))
        else Abort.fail(e)
    } {
        backend.send[EvalParams, EvalResult](RuntimeEvaluateMethod, params)
            .map(r => Json.encode(CdpReply(result = Present(r))))
    }
```
Both `BrowserIFrameInvalidException` and `CdpErrorStrings.ContextDestroyedErrorMessage` are present in runtimeEvaluate. PASS.

### 4. runtimeEvaluate wraps in CdpReply?
PASS. Line 267: `.map(r => Json.encode(CdpReply(result = Present(r))))`. CLEAN.

### 5. CdpBackendTest mechanical swap — FakeCdpSender count = 0?
PASS. `grep -rn "FakeCdpSender"` against test source files (shared + jvm) returns 0 hits. `JsonRpcTransport.inMemory` is used in CdpBackendTest via `mkBackendWithServer` helper (lines 37-50). The helper is correct: it creates a paired in-memory transport, starts a server endpoint handling Browser.getVersion + extra methods, then calls `CdpBackend.initUnscoped(client, testLaunchCfg)`. All 41 cases use this helper. CLEAN.

### 6. CdpBackendLifecycleTest class name + old stub deleted?
PARTIAL FAIL.
- CdpBackendLifecycleTest.scala: class is `CdpBackendLifecycleTest extends kyo.BrowserTest` — CORRECT.
- CdpClientLifecycleTest.scala: still present as a 22-line stub with a single `succeed` test. It was NOT deleted. The task said the stub "should be DELETED since new file exists."
- However: the stub has a `// flow-allow: Phase 02 stub; Phase 03 rewrites as CdpBackendLifecycleTest` comment and is a different class name (`CdpClientLifecycleTest`). Two test classes with the same logical coverage are now shipping: the live 25-case file and a dead 1-case stub. This is not a correctness issue (the stub passes trivially) but it's an artifact that should not exist post-Phase 02.

VERDICT: FLAG — stub is not deleted. The old `CdpClientLifecycleTest.scala` is 22 lines, still compiles a trivial class. It should be removed.

### 7. CdpBackendIntegrationTest exists with 15 cases?
PASS. CdpBackendIntegrationTest.scala: 264 lines, class `CdpBackendIntegrationTest extends Test`, 15 test cases verified by grep count. The old CdpClientTest.scala is now a 4-line empty redirect comment (no class definition). CLEAN.

### 8. CdpClientDecoderTest: 7 cases, JsonRpcEnvelope.Malformed recovery path?
PASS. CdpClientDecoderTest.scala: 294 lines, 7 test cases. Cases 2 and 4 inject `JsonRpcEnvelope.Malformed(Present(JsonRpcId.Num(2L)), "error field is not a Record", Structure.Value.Str(...))` directly on the server transport. Case 5 injects `JsonRpcEnvelope.Malformed(Absent, "expected a Record", Structure.Value.Array(...))`. Case 6 injects `JsonRpcEnvelope.Malformed(Absent, "json parse failed", Structure.Value.Str("not-json"))`. Case 7 injects a `JsonRpcEnvelope.Notification(method = "NotAWhitelistedEvent", ...)`. Wire shapes correctly map to the original 7 behaviors from `CdpClient.decodeCdpMessage`. CLEAN.

### 9. PageDownloadTest: 2 stubbed cases restored via Browser.onDownload?
PASS. PageDownloadTest.scala has 3 live tests (the 3 Behavior wire-constant tests were already live pre-stub; the 2 exchange-event tests are now restored). But wait: the pre-stub file had 3 live cases (2 exchange-event stubs + 1 live closeTarget test). Now all 3 domain tests are live plus the 3 Behavior.*.wire constants = 6 total tests. The 2 previously stubbed cases ("setDownloadBehavior(Allow)..." and "downloadWillBegin and downloadProgress share the same guid") are restored using `Browser.onDownload`. The third test "setDownloadBehavior propagates BrowserConnectionException" was always live. All 3 domain tests use the `captureEvents` helper and `Browser.onDownload` subscription API. PASS.

## Reward-hacking checks

| Pattern | Verdict | Citation |
|---|---|---|
| Verification commands actually run | FLAG: No new test run since STEER-A/B/C were applied. Last run was `phase-02-impl-test-jvm-002.log` at 13:38 (75+ min ago, 604 lines — truncated, many BrowserMutationTest failures visible). The 5 newly restored test files have NOT been run against a live browser since restoration. | runs/ directory |
| Compile-only "success" claim | CLEAN: No evidence of compile-only claim; decisions 50-55 describe full rewrites, not compile-gating | phase-2-decisions.md |
| Priority inference | CLEAN | — |
| Scope substitution | MINOR: CdpClientLifecycleTest.scala stub left on disk instead of deleted. Creates a 1-case class that passes trivially; doubles the lifecycle test file count without adding coverage. | CdpClientLifecycleTest.scala:13-22 |
| Foreach-discards-assert | CLEAN: No `.foreach` result discards spotted in restored tests | — |
| Stale-state passing | FLAG: Test run 13:38 shows BrowserMutationTest failures (element-not-attached). These are pre-existing unrelated failures OR a regression from Phase 02 changes. Agent has not run tests after the final-fix restoration work (~14:38 per file timestamps). The stale 75-min-old run log is being used as the pass gate. | phase-02-impl-test-jvm-002.log |

## Drifting checks

| Pattern | Verdict | Citation |
|---|---|---|
| Public API signature matches plan | PASS: `Browser.run(wsUrl)` effect row = `Async & Abort[BrowserReadException | BrowserSetupException]` — matches Decision 46 | Browser.scala:276 |
| No off-plan architecture substitution | PASS: runtimeEvaluate uses typed send path, not raw JSON. Structure.Value in ExtrasEncoder for sessionId. No substitution. | CdpBackend.scala:260-268 |
| No cross-cutting refactor outside phase | MINOR: Decision 49 notes `BrowserEval.translateContextDestroyed` was left intact as a "now-no-op pass-through." If truly a no-op, it is dead code that could confuse Phase 03. Not a blocking issue but should be documented. | phase-2-decisions.md:Decision 49 |
| Internal helpers stay internal | PASS: All new helpers are `private[kyo]` or `private`. | CdpBackend.scala, Resolver.scala |

## Scope-cutting checks

| Leaf | Status | Notes |
|---|---|---|
| Browser.run(wsUrl) effect-row widened | PRESENT_STRICT | Browser.scala:276, all 4 run overloads return BrowserReadException \| BrowserSetupException |
| runtimeEvaluate with Structure.Value + iframe translation + CdpReply | PRESENT_STRICT | CdpBackend.scala:260-268, all three requirements met |
| Resolver.translateContextDestroyed kept | PRESENT_STRICT | Resolver.scala:93-101, Decision 48 logged with rationale (Resolver uses raw EvaluateObjectParams send, not runtimeEvaluate wrapper) |
| CdpBackendTest 41 cases (FakeCdpSender -> inMemory swap) | PRESENT_STRICT | 41 cases verified by grep count; no FakeCdpSender in source; mkBackendWithServer helper correctly wires inMemory transport |
| CdpBackendLifecycleTest 25 cases (renamed from CdpClientLifecycleTest) | PRESENT_STRICT | 25 cases, correct class name |
| Old CdpClientLifecycleTest.scala deleted | WEAKENED | File still present (22 lines stub). Agent created the new file but did NOT delete the old stub. |
| CdpBackendIntegrationTest 15 cases (renamed from CdpClientTest) | PRESENT_STRICT | 15 cases, CdpClientTest.scala is empty redirect comment |
| CdpClientDecoderTest 7 cases (Malformed envelope assertions) | PRESENT_STRICT | 7 cases with correct JsonRpcEnvelope.Malformed injection patterns |
| CdpBackendLifecycleJvmTest (5 cases) | PRESENT_STRICT | 5 cases; CdpClientLifecycleJvmTest.scala is empty redirect comment |
| PageDownloadTest 2 of 3 stubs restored | PRESENT_STRICT | Both previously-stubbed exchange-event tests now live via Browser.onDownload |
| Full JVM test suite run after restoration | MISSING | No test run since 13:38; restoration work completed ~14:38-14:58; run is required to confirm green |
| JS + Native compile after restoration | MISSING | Last compile logs are from 13:12-13:13; restoration added ~200+ lines to shared/ tests that affect all platforms |

## Decisions log adequacy (Decisions 45-54)

ALL PRESENT AND LOGGED. Decisions 45 through 55 are in phase-2-decisions.md with timestamps and rationales:
- Decision 45: runtimeEvaluate CdpReply re-wrap fix — LOGGED
- Decision 46: Browser.run(wsUrl) effect row widening — LOGGED
- Decision 47: runtimeEvaluate iframe translation added — LOGGED
- Decision 48: Resolver.translateContextDestroyed kept (not removed) — LOGGED
- Decision 49: BrowserEval.translateContextDestroyed left as no-op — LOGGED
- Decision 50: CdpBackendTest 41 cases via inMemory — LOGGED
- Decision 51: CdpBackendLifecycleTest rename (25 cases) — LOGGED
- Decision 52: CdpBackendIntegrationTest rename (15 cases) — LOGGED
- Decision 53: CdpClientDecoderTest rewrite (7 cases) — LOGGED
- Decision 54: CdpBackendLifecycleJvmTest rename (5 cases) — LOGGED
- Decision 55: PageDownloadTest 2 cases restored — LOGGED

Decision logging is COMPLETE for this pulse interval. The anti-pattern from pulse K=1 (no decision logging after 80+ min) is resolved.

## CRITICAL (steer immediately)

1. **No test run after final-fix restoration**: The agent restored 5 test files and edited Browser.scala + CdpBackend.scala after 13:38, but the last test run is 75+ min old (`phase-02-impl-test-jvm-002.log` at 604 lines, incomplete). The restored test bodies have NOT been compiled OR run. A compile failure in any of the 5 restored files would block Phase 03. Run `sbt "kyo-browser/Test/compile"` (or `kyo-browser/jvm/Test/compile` for a faster check) immediately before declaring Phase 02 done.

2. **CdpClientLifecycleTest.scala stub not deleted**: `kyo-browser/shared/src/test/scala/kyo/internal/CdpClientLifecycleTest.scala` (22 lines) was NOT deleted after CdpBackendLifecycleTest.scala was created. Two test files now define lifecycle tests: one real (25 cases) and one stub (1 trivial succeed). The stub must be removed: `git rm kyo-browser/shared/src/test/scala/kyo/internal/CdpClientLifecycleTest.scala`. Verify no other file imports or references `CdpClientLifecycleTest`.

## MINOR (queue for post-commit audit)

1. Decision 49 notes `BrowserEval.translateContextDestroyed` is now a no-op pass-through. If it is truly a no-op (context-destroyed errors absorbed by runtimeEvaluate before reaching BrowserEval), it is dead code. Phase 03 audit should verify and remove or document.

2. The `phase-02-impl-test-jvm-002.log` (604 lines, 13:38) shows multiple BrowserMutationTest `NotAttached` failures across `fill` operations. These are either pre-existing (were failing before Phase 02) or a Phase 02 regression. Per `feedback_own_all_failures`, Phase 02 must own all failures regardless. The agent has not diagnosed these failures.

3. The `Scope` effect in `CdpBackend.initUnscoped` return type remains (Decision 22 / Decision 2 supersede chain, Decision 16). This is plan-approved but the `// flow-allow:` annotation for the overload at CdpBackend.scala:359-436 (Decision 25) should be verified present in the source.

## Recommendation: STEER

**STEER: Run test compile + JVM suite; delete CdpClientLifecycleTest.scala stub; diagnose BrowserMutationTest NotAttached failures before Phase 02 commit.**

Specific steers to write to steering.md:

```
STEER-D (CRITICAL): Run kyo-browser Test/compile (all platforms) after the final-fix restoration changes. The 5 restored test files + Browser.scala/CdpBackend.scala changes since 13:38 have NOT been validated by a compile or test run. Failing to run before commit risks a broken-compile Phase 02.

STEER-E (CRITICAL): Delete CdpClientLifecycleTest.scala (22-line stub). The new CdpBackendLifecycleTest.scala (1145 lines, 25 cases) replaces it entirely. Command: git rm kyo-browser/shared/src/test/scala/kyo/internal/CdpClientLifecycleTest.scala. Add Decision 56 noting deletion.

STEER-F (REQUIRED): Investigate BrowserMutationTest NotAttached failures from phase-02-impl-test-jvm-002.log. Multiple `fill` tests fail with "element is not attached to the DOM". Determine if these are pre-Phase-02 (check HEAD~1) or a Phase 02 regression from the CdpReply re-wrap change (Decision 45) or runtimeEvaluate iframe translation (Decision 47). Per feedback_own_all_failures, Phase 02 owns the diagnosis and fix if it's a regression.
```
