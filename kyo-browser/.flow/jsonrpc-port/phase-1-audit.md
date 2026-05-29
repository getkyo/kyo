# Phase 1 audit

Time: 2026-05-29T13:00:00Z
HEAD: c025b00e6
Phase commit: c025b00e6 `[browser] Phase 01: CdpBackend runtime class on kyo-jsonrpc`
Plan cites: ./05-plan.md §Phase 01 (mirrored in ./05-plan.yaml phases[0])
Design cites: ./02-design.md §"Phase 01" (lines 879-918), §"Per-sessionId routing", §"Wiring"

This audit reads HEAD vs the design contract and 05-plan.yaml phases[0],
post-pulse-1, post-verify. Pulse 1 caught three deviations (CRITICAL-1
probe-exception type, CRITICAL-2 Scope-in-initUnscoped, CRITICAL-3
28 forwarders); Decisions 21-24 ratify the fixes. Audit confirms the
ratifications landed and surfaces the class-C residue verify did not
catch.

## Test count
| Leaf | Status | Notes |
|---|---|---|
| 1: init via inMemory returns live backend | PRESENT_STRICT | `CdpBackendSmokeTest.scala:64-73` |
| 2: init fails fast with BrowserSetupFailedException when probe returns Closed | PRESENT_STRICT (post D21) | `CdpBackendSmokeTest.scala:75-86` ; assertion matches `BrowserSetupFailedException` |
| 3: send writes wire bytes matching legacy CDP envelope | PRESENT_STRICT | `CdpBackendSmokeTest.scala:88-111` |
| 4: session-scoped backend stamps sessionId via ExtrasEncoder | PRESENT_STRICT | `CdpBackendSmokeTest.scala:113-140` |
| 5: Page.javascriptDialogOpening routes via ctx.extras | PRESENT_STRICT | `CdpBackendSmokeTest.scala:142-168` |
| 6: Page.javascriptDialogOpening auto-dismisses when no handler | PRESENT_STRICT | `CdpBackendSmokeTest.scala:170-199` |
| 7: Runtime.executionContextCreated routes via ctx.extras | PRESENT_STRICT | `CdpBackendSmokeTest.scala:201-237` |
| 8: Page.downloadWillBegin routes via ctx.extras | PRESENT_STRICT | `CdpBackendSmokeTest.scala:239-271` |
| 9: close(gracePeriod) sequences endpoint.close + queue.close + drainer.interrupt | PRESENT_STRICT | `CdpBackendSmokeTest.scala:273-287` |
| 10: dialog drainer issues sendUnmatched with negative JsonRpcId.Num | WEAKENED (timing) | `CdpBackendSmokeTest.scala:289-317` ; uses fixed `Async.sleep(300.millis)` rather than `untilTrue` ; see Findings WARN-1 |
| 11: INV-008 source + matching test in same commit | PRESENT_STRICT | `JsonRpcPortInvariantsSpec.scala:75-88` |
| 12: INV-009 package + top-level type check | PRESENT_STRICT | `JsonRpcPortInvariantsSpec.scala:92-106` |
| 13: INV-011 no manual JSON | PRESENT_STRICT | `JsonRpcPortInvariantsSpec.scala:110-119` |
| 14: INV-012 no `var` for shared state | PRESENT_STRICT | `JsonRpcPortInvariantsSpec.scala:123-138` |
| 15: INV-013 no unannotated AllowUnsafe | PRESENT_STRICT | `JsonRpcPortInvariantsSpec.scala:142-162` |
| 16: INV-014 no em/en-dashes | PRESENT_STRICT | `JsonRpcPortInvariantsSpec.scala:166-176` |
| 17: INV-015 round-trip ExtrasEncoder + ctx.extras | PRESENT_STRICT | `JsonRpcPortInvariantsSpec.scala:180-237` |
| 18: INV-016 Browser.getVersion probe -> BrowserSetupFailedException | PRESENT_STRICT (post D21) | `JsonRpcPortInvariantsSpec.scala:241-254` |
| 19: INV-017 Closed -> BrowserConnectionLostException | PRESENT_STRICT | `JsonRpcPortInvariantsSpec.scala:258-278` |
| 20: INV-017 JsonRpcError -> BrowserProtocolErrorException | PRESENT_STRICT | `JsonRpcPortInvariantsSpec.scala:280-298` |
| 21: INV-017 Timeout -> BrowserConnectionLostException | WEAKENED (timing + indirect) | `JsonRpcPortInvariantsSpec.scala:300-340` ; relies on engine internally mapping `Timeout -> JsonRpcError.RequestCancelled` per Decision 14 ; see Findings NOTE-1 |
| 22: INV-018 dialogIdCounter negative ids disjoint from SequentialInt | WEAKENED (timing) | `JsonRpcPortInvariantsSpec.scala:344-395` ; two fixed `Async.sleep` calls (200ms + 100ms) ; see Findings WARN-1 |
| 23: INV-019 no Fiber.block | PRESENT_STRICT | `JsonRpcPortInvariantsSpec.scala:399-406` |
| 24: INV-020 green-build JVM/JS/Native | STUB | `JsonRpcPortInvariantsSpec.scala:410-412` is `succeed` ; ratified D24 ; out-of-process supervisor responsibility |
| (extra) INV-022 no Co-Authored-By | PRESENT_STRICT | `JsonRpcPortInvariantsSpec.scala:416-423` |
| (extra) INV-023 no git push | STUB | `JsonRpcPortInvariantsSpec.scala:427-429` ; ratified D10/D24 |
| (extra) INV-024 every flow-allow has rationale | PRESENT_STRICT | `JsonRpcPortInvariantsSpec.scala:433-449` |

Total: 27 (plan 24 + 3 extras). 24 PRESENT_STRICT / 3 STUB-by-design / 3 WEAKENED-timing.

## CONTRIBUTING.md violations
None observed at HEAD. All campaign-added lines respect the project's no-em-dash, no-Co-Authored-By, no-Thread.sleep, no-Fiber.block conventions.

## Unsafe markers
None added by this phase. `BaseKyoCoreTest.run` (kyo-core, pre-existing) consumes `Sync.Unsafe.evalOrThrow` internally; no Phase-01-introduced `AllowUnsafe`, `Frame.internal`, or `Sync.Unsafe.*` site exists in `kyo-browser/`. Verified at `CdpBackend.scala` and both test files: 0 matches.

## Cross-platform consistency
- Platforms checked: jvm (verified test-green), js (compile-only deferred to Stage 3.5 per verification_strategy: targeted), native (compile-only deferred).
- Per-platform deltas:
  - `kyo-browser/jvm/src/test/scala/kyo/internal/JsonRpcPortFileOps.scala` (30 LoC): real `java.nio.file.{Paths,Files}` reads with candidate-path probing for `.`, `..`, `../..`. Returns `Option[String]` / `Boolean`.
  - `kyo-browser/js/src/test/scala/kyo/internal/JsonRpcPortFileOps.scala` (10 LoC): always returns `None` / `false`.
  - `kyo-browser/native/src/test/scala/kyo/internal/JsonRpcPortFileOps.scala` (10 LoC): always returns `None` / `false`.
- The InvariantsSpec consumes these via `readFile` (line 35-40): on JVM, asserts non-empty; on JS/Native, returns "" and the spec body short-circuits via `if content.isEmpty then succeed`. So 8 source-grep tests (INV-009, INV-011, INV-012, INV-013, INV-014, INV-019, INV-022, INV-024) trivially pass on JS/Native without verifying the invariant. INV-008 (file-existence check) is gated by `if Platform.isJVM` and also trivially passes on JS/Native (line 86). Runtime-behavior tests (INV-015, INV-016, INV-017 x3, INV-018) DO exercise cross-platform via `JsonRpcTransport.inMemory` round-trips and are the load-bearing cross-platform gates.

## Naming convention compliance
- File `JsonRpcPortFileOps.scala` (test) — basename matches sole top-level type `JsonRpcPortFileOps`. PASS.
- File `CdpBackend.scala` — basename matches `CdpBackend` class + `CdpBackend` companion + `CdpBackendOld` object (3 top-level types, one of which is the phase-bounded `CdpBackendOld` per Rule 8a/8b). The InvariantsSpec INV-009 explicitly asserts all three. PASS-by-design.
- File `CdpBackendSmokeTest.scala` — basename matches `CdpBackendSmokeTest` class. PASS.
- File `JsonRpcPortInvariantsSpec.scala` — basename matches `JsonRpcPortInvariantsSpec` class. PASS.

## Steering deviation
`git diff --name-only HEAD~1 HEAD` vs phase's `files_produced` / `files_modified`:

| File | Bucket | Verdict |
|---|---|---|
| `build.sbt` | `files_modified` | MATCH |
| `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala` | `files_produced[0].source` | MATCH |
| `kyo-browser/shared/src/main/scala/kyo/internal/CdpTypes.scala` | NOT in plan (D5 ratifies for `BrowserGetVersionParams` / `BrowserVersionResult` probe types) | RATIFIED |
| `kyo-browser/shared/src/test/scala/kyo/internal/CdpBackendSmokeTest.scala` | `files_produced[0].test` | MATCH |
| `kyo-browser/shared/src/test/scala/kyo/internal/JsonRpcPortInvariantsSpec.scala` | `files_produced[1].source` | MATCH |
| `kyo-browser/{jvm,js,native}/src/test/scala/kyo/internal/JsonRpcPortFileOps.scala` x3 | NOT in plan (D19 ratifies for cross-platform expect/actual on file I/O) | RATIFIED |
| `kyo-browser/.flow/jsonrpc-port/*` | flow-state artifacts | EXPECTED-in-commit (Phase 01 is when these get committed) |

No drift. Phase 02-scoped files (`Browser.scala`, `BrowserTab.scala`, etc.) untouched — verified `git diff HEAD~1 HEAD -- kyo-browser/shared/src/main/scala/kyo/Browser.scala kyo-browser/shared/src/main/scala/kyo/BrowserException.scala kyo-browser/shared/src/main/scala/kyo/internal/BrowserTab.scala kyo-browser/shared/src/main/scala/kyo/internal/BrowserSnapshot.scala` = 0 lines. INV-002 public-surface byte-identity preserved.

## Anti-flakiness measures
- `untilTrue` (kyo-core `BaseKyoCoreTest:22`) is the polling retry primitive used in 5 sites (SmokeTest leaves 5, 6, 7, 8 and InvariantsSpec INV-015). PASS.
- Fixed `Async.sleep` is used in 4 sites:
  - `CdpBackendSmokeTest.scala:304` (leaf 10): `Async.sleep(300.millis)` BEFORE checking `capturedIdRef.get`. Should use `untilTrue(capturedIdRef.get.map(_.isDefined))`. WARN-1.
  - `JsonRpcPortInvariantsSpec.scala:262` (INV-017 Closed): `Async.sleep(50.millis)` after `serverEndpoint.closeNow` to let the close propagate before the client `send`. Defensible (close propagation requires fiber yields), but could be replaced by checking endpoint state if such an API exists. NOTE.
  - `JsonRpcPortInvariantsSpec.scala:367, 374` (INV-018): two fixed sleeps gate the order of "drainer fires" vs "regular send fires". Should use `untilTrue(dialogIdRef.get.map(_.isDefined))` and `untilTrue(regularIdRef.get.map(_.isDefined))`. WARN-1.
- No `Thread.sleep` anywhere. PASS.
- No `cancel()` / `pending()` / `@nowarn` test-suppression. PASS.

## Architecture substitution check

### A1. Scope-in-initUnscoped (Decision 22, post-pulse ratification)
- Design intent: prep doc gotcha #1 + plan §6 + Decision 2 say `Scope.run { ... }` wraps the for-comprehension body so `initUnscoped` returns `Scope`-free.
- HEAD reality: `CdpBackend.scala:147-149` declares `initUnscoped(wsUrl, launchCfg)(using Frame): CdpBackend < (Async & Scope & Abort[BrowserReadException | BrowserSetupException])`. `init` (line 138-141) wraps with `Scope.acquireRelease(initUnscoped(...))(_.close(launchCfg.closeGrace))`.
- Verdict: MATCH-design-intent (corrected by Decision 22). The original prep doc was technically wrong: `JsonRpcEndpoint.init` returns `< (Sync & Async & Scope)` (`kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEndpoint.scala:101-105`) and uses `Scope.acquireRelease` internally to register the endpoint's lifecycle finalizer. Wrapping the for-comprehension in `Scope.run` WOULD finalize the endpoint immediately on `initUnscoped` exit, before the caller used it. Decision 22 ratifies the correct architecture. The design intent stands; the prep doc misread the engine API.
- Audit-judgment: ARCHITECTURE-CORRECT. The decisions log captures the rationale. Confirmed by re-reading `JsonRpcEndpoint.init` at HEAD.

### A2. 28 forwarder methods on companion (Decision 23, post-pulse ratification)
- Design intent: §"Migration / cutover" / Phase 01 (lines 879-918): "Make `CdpClient` a thin delegate that builds the new `CdpBackend` and forwards every method to it... NO scoped delegation... Decision: NOT a type alias; this phase introduces both `CdpBackend` AND `CdpClient` as distinct classes for ONE phase only, with all `CdpClient` call sites at this point still calling the OLD `CdpClient` (which the phase preserves byte-equivalent)."
- HEAD reality: the legacy `CdpClient.scala` (627 LoC) is UNCHANGED (`git diff HEAD~1 HEAD -- kyo-browser/shared/src/main/scala/kyo/internal/CdpClient.scala` = 0). The old `object CdpBackend` is renamed to `object CdpBackendOld` and 28 forwarder methods from the new `object CdpBackend` to `CdpBackendOld` (`CdpBackend.scala:221-353`) are added to keep `CdpBackend.foo(sender, params)` call sites resolving. These call sites are in `Browser.scala`, `Resolver.scala`, `BrowserSnapshot.scala`, etc. — Phase 02 cuts them over.
- Verdict: SUBSTITUTED but RATIFIED. The plan said "Make `CdpClient` a thin delegate", but the impl kept `CdpClient.scala` byte-equivalent AND renamed the old `CdpBackend` namespace object to `CdpBackendOld` to free the `CdpBackend` symbol for the new class. This is structurally different from the plan's wording (which assumed only `CdpClient` needed delegation, missing the `CdpBackend` namespace-object name collision). Decision 23 ratifies the architectural substitution; verify B7 ratifies the test-stability impact (none, since the 28 forwarders are trivial passthroughs).
- Audit-judgment: ARCHITECTURE-OK-AS-RATIFIED. The substitution is necessary (the old `CdpBackend.scala` *is* the method-namespace object; renaming the class collides). The plan's wording was technically incomplete; Decision 23 captures the structural truth. Phase 02 must delete BOTH the forwarder block AND `CdpBackendOld`, which Phase 02's plan slice already says.

### A3. Off-plan `initUnscoped(transport, launchCfg)` overload
- Design intent: plan specifies ONE `initUnscoped(wsUrl, launchCfg)` overload only.
- HEAD reality: `CdpBackend.scala:359-422` adds a second overload taking `JsonRpcTransport` directly. `private[kyo]` visibility. Used by smoke tests (`CdpBackendSmokeTest.scala:56`) and InvariantsSpec (`JsonRpcPortInvariantsSpec.scala:67`).
- Verdict: SUBSTITUTED. The decisions log has NO entry for this overload. Verify B7 acknowledges the gap and suggests a "Decision 25" for documentation. The overload duplicates ~60 LoC of init code with the only difference being the URL-parse/transport-setup steps replaced by an injected transport.
- Audit-judgment: This is a class-C drift. The overload exists solely as test infrastructure. The cleaner solution would be a `JsonRpcTransport.inMemory` test helper that returns the pair pre-wired, with tests using the production `initUnscoped(wsUrl, launchCfg)` path against a stub WS URL — but that would require introducing a WS-URL-bypass seam in the production code too. The current overload is a pragmatic test seam at the cost of ~60 LoC duplication. WARN-2.

## Documentation drift
- Scaladoc additions in this phase:
  - `CdpBackend.scala:7-16`: class doc explains responsibilities (dispatcher tables, dialog drainer, lastEvaluateParams, ExtrasEncoder, 5 notification handlers). Matches design §"Architecture overview" §"Wiring". No drift.
  - `CdpBackend.scala:29-32`: `send` doc cites INV-017. No drift.
  - `CdpBackend.scala:96-103`: `close` / `closeNow` ordering documented as "endpoint.close, dialogQueue.close, dialogDrainer.interrupt". Matches design §"Wiring" `close(gracePeriod)` pseudocode. No drift.
  - `CdpBackend.scala:116-122`, 633-639: `CdpBackendOld` Scaladoc explains "228 LoC of typed wrappers + decodeOrFail + CdpSender trait" and the Phase-01-only carryover rationale. Matches Decision 23 ratification. No drift.
  - `CdpBackend.scala:537-544`: auto-dismiss invariant documented as "test-stability-critical edge case... byte-equivalent to CdpClient.decodeCdpMessage's dialog-opening branch". Matches design §"Risks & mitigations" first bullet. No drift.
  - `CdpTypes.scala:454-466`: `BrowserGetVersionParams` / `BrowserVersionResult` Scaladoc cites the CDP spec. No drift.
- Beyond plan intent: NO.

## Documentation drift in `phase-1-decisions.md` (24 entries)
Internal consistency check:
- D1 (BrowserSetupException sealed-trait realization): superseded by D17 (uses `BrowserConnectionLostException` because `BrowserSetupFailedException` would break `Abort[BrowserReadException]` row), then RE-superseded by D21 (return-type widened to `Abort[BrowserReadException | BrowserSetupException]`, probe maps to `BrowserSetupFailedException`). The chain is internally consistent: D1 -> D17 (rejected D1) -> D21 (rejected D17, accepted D1's spirit). At HEAD, the impl matches D21. NOTE-2: this 3-step decision chain (D1, D17, D21) is fragile; D17's rejection of D1 is not explicit, and a future reader might assume D17 is the latest. Consider a meta-decision documenting the chain.
- D2 (Scope.run wrap): superseded by D22 (Scope kept in row). At HEAD, the impl matches D22. The chain is two steps; D22 explicitly cites prep doc gotcha #1 and supersedes D2. Internally consistent.
- D14 (Timeout removed; `Abort.recover[JsonRpcError]` checks `RequestCancelled.code`): impl at `CdpBackend.scala:52-67` matches. Internally consistent.
- D16 (initUnscoped keeps Scope, supersedes D2): SAME as D22. Two decisions cover the same correction. NOTE-3: D16 and D22 are duplicative; the post-pulse re-ratification at D22 was likely unaware of D16. The two entries do not conflict but document the same call twice.
- D21-D24 (post-pulse): verified against HEAD impl, all four match.

Verdict: the decisions log is consistent at HEAD. Two minor documentation issues (NOTE-2, NOTE-3) involve duplicate / chained decisions that future readers may find confusing, but neither contradicts the impl.

## Class-C judgment failures

### Hidden invariant violations
- INV-012 (no new `var` for shared state): VERIFIED. `grep "\\bvar " kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala` = 0 matches. PASS.
- INV-019 (no `Fiber.block`): VERIFIED. PASS.
- INV-014 (no em/en-dashes in added lines): VERIFIED. The two `'—'` / `'–'` char-literals on `JsonRpcPortInvariantsSpec.scala:171` are the INV-014 invariant test self-evidence (the data IS the chars-being-rejected). Verify recommended a `// flow-allow:` annotation but the supervisor committed without it — accepted via override classification (verify section "Class-A annotation gaps"). PASS-by-override.

### Reward-hack patterns
- `Result.Success(...)` ignoring the value:
  - `CdpBackend.scala:427` (inside `parseWsUrl`): `case Result.Success(u) => u` — extracts and returns. Not a value-ignore. PASS.
  - `CdpBackend.scala:653` (inside `CdpBackendOld.decodeOrFail`): `case Result.Success(reply) => reply.result match ...` — extracts and pattern-matches. Not a value-ignore. PASS.
  - `CdpBackendSmokeTest.scala:100, 126`: `case Result.Success(_) => ...` — explicitly discards the body of the response, but then checks `capturedExtrasRef.get` (a side-channel) for the assertion. NOT a reward-hack because the side-channel IS the assertion target (the test verifies "wire bytes match", which is reflected in `ctx.extras` at the server side, not in the response body). PASS-by-design.
- Stub tests (INV-020, INV-023): ratified by D24 and D10. The pulse verified the rationale is logged. PASS-by-design.
- `succeed` short-circuits in 8 INV grep tests when `content.isEmpty` (JS/Native): these are not reward-hacks because INV-009/011/012/013/014/019/022/024 are SOURCE-FILE GREPS that only have meaning on the JVM where the file is on disk. On JS/Native the file isn't shipped with the test runtime by design. NOTE-4: the test name does not convey "JVM-only" — a reader could mistake "passed" for "verified" on JS/Native. Consider renaming to `"INV-014 [JVM-only]"` or skipping via `cancel("only-on-JVM")` to surface intent.

### Subtle public-API drift
- All new symbols in `CdpBackend.scala` are `private[kyo]`. PASS for public-API.
- Caveat: `private[kyo]` in `package kyo.internal` means accessible from ANY file in `package kyo` or `kyo.internal` across ALL kyo modules (e.g. `kyo-core`, `kyo-jsonrpc`, `kyo-http`). The `private[kyo]` constructor of `CdpBackend` is reachable from outside `kyo-browser`'s module boundary. This is the project's standard convention (see same pattern in `CdpClient.scala` and across `kyo-jsonrpc/.../JsonRpcEndpointImpl.scala`), so not a regression — but the design's claim "public surface unchanged" is technically true (no NEW public symbols) but the new internal-but-cross-module-accessible symbols are surfaceable to any kyo module. NOTE-5: not a finding to act on; documenting for completeness.
- `BrowserGetVersionParams` and `BrowserVersionResult` (`CdpTypes.scala:457, 460-466`): both `private[kyo]`. PASS.

### Dependency-injection violations
- `CdpBackend` holds: `endpoint`, `dialogHandlers`, `dialogDrainer`, `dialogQueue`, `frameEventDispatchers`, `downloadEventDispatchers`, `dialogRecorders`, `lastEvaluateParams`, `sessionId`. All are kyo-browser-owned per the design § "Architecture overview" diagram (lines 87-95). None should live in `Browser` or `BrowserTab`. VERIFIED.
- The dispatcher tables (`frameEventDispatchers` / `downloadEventDispatchers` / `dialogHandlers` / `dialogRecorders`) are keyed by sessionId string and are SHARED across all sessions of one backend. Per the design § "Per-sessionId routing strategy" (lines 644-661), keying is at the dispatcher-map level, not at the CdpBackend instance level. `withSession` shares the maps and only differs in the `sessionId` field. VERIFIED at `CdpBackend.scala:83-94`.
- Verdict: NO dependency-injection violations. PASS.

### Test stability concerns
- `Thread.sleep`: 0 occurrences. PASS.
- `Async.sleep` with fixed wait (3 sites): WARN-1 routed to Phase 02 prep (the next phase will modify the same tests when call sites move to `tab.backend`, so the prep can pair the cutover with the `untilTrue` conversion).
- `untilTrue` use (5 sites): the polling retry primitive `BaseKyoCoreTest.untilTrue` (kyo-core/.../BaseKyoCoreTest.scala:22-31) retries on `AssertionError` via `Retry[AssertionError](Schedule.fixed(10.millis))`. The default `Retry.Schedule` truncation is configured globally; the tests don't set an explicit ceiling. NOTE-6: a stuck `untilTrue` would manifest as an `Async.timeout` from the outer `run(...)` after the default 60s test timeout — failure mode is detectable but slow.

## Findings (categorized)

### BLOCKER
None.

### WARN (routed to Phase 02 prep)

**WARN-1: Three fixed `Async.sleep` sites should use `untilTrue`.**
- `CdpBackendSmokeTest.scala:304` — leaf 10 dialog drainer test. Currently `Async.sleep(300.millis)` then check `capturedIdRef.get`. Replace with `untilTrue(capturedIdRef.get.map(_.isDefined))` before the `.get` assertion. Same pattern as leaves 5, 6, 7, 8 in the same file (which already use `untilTrue`).
- `JsonRpcPortInvariantsSpec.scala:367` — INV-018 first sleep before regular send. Replace with `untilTrue(dialogIdRef.get.map(_.isDefined))`.
- `JsonRpcPortInvariantsSpec.scala:374` — INV-018 second sleep before assertion. Replace with `untilTrue(regularIdRef.get.map(_.isDefined))` then assert.
- Phase 02 will modify these tests when call-site cutover renames `tab.client` to `tab.backend`; the prep can pair the cutover with the `untilTrue` conversion for zero added phase-cost.

**WARN-2: Off-plan `initUnscoped(transport, launchCfg)` overload should be documented (D25) or refactored out.**
- `CdpBackend.scala:359-422` is a second overload not in the plan. Verify B7 ratifies it as test infrastructure but the decisions log lacks an entry. Phase 02 prep should EITHER add a "Decision 25" formally documenting the overload's purpose and `private[kyo]` visibility OR refactor tests to use a single-overload pattern. If kept, consider promoting to a dedicated `CdpBackendTestSupport` object so the production `CdpBackend` companion does not carry test-only API surface. ~60 LoC of duplication that compounds if Phase 02 modifies init wiring.

**WARN-3: NOTE-2 / NOTE-3 decision chains in `phase-1-decisions.md`.**
- D1 -> D17 (rejected D1) -> D21 (rejected D17): 3-step probe-exception chain. The middle entry (D17) is silently superseded; a reader scanning the log top-down might apply D17's reasoning and miss D21.
- D16 vs D22: both ratify "keep Scope in initUnscoped"; D22 was written without referencing D16. Minor duplication.
- Phase 02 prep should consolidate the decisions log: append a meta-entry "D25: D1 superseded by D17; D17 superseded by D21" and "D26: D16 = D22 (duplicate; D22 is canonical)". Five lines of housekeeping that prevents future-phase decision-mining confusion.

### NOTE (end-of-project cleanup)

**NOTE-1: INV-017 Timeout test is indirect.**
- `JsonRpcPortInvariantsSpec.scala:300-340` asserts the engine internally maps `Timeout` to `JsonRpcError.RequestCancelled` (code -32800) and the `CdpBackend.send` recovery (`CdpBackend.scala:53-60`) checks `err.code == JsonRpcError.RequestCancelled.code`. The test relies on TWO engine behaviors (the `Async.timeout` -> `Abort.fail(JsonRpcError.cancelled)` mapping inside `JsonRpcEndpointImpl`, AND the dispatch of that error back to the awaiting caller) without a kyo-jsonrpc-side test that pins the mapping. If `JsonRpcError.RequestCancelled.code` ever changes (or the engine starts surfacing `Timeout` directly), this test passes for the wrong reason. The kyo-jsonrpc team should own an engine-side test of the `Timeout -> JsonRpcError.RequestCancelled` contract; cleanup phase should verify it exists.

**NOTE-4: 8 INV grep tests `succeed` silently on JS/Native.**
- INV-009/011/012/013/014/019/022/024 short-circuit via `if content.isEmpty then succeed` when `JsonRpcPortFileOps.readFileIfExists` returns `None` (which is unconditional on JS/Native). The test name does not convey "JVM-only". Two ergonomic options for end-of-project cleanup: (a) rename test labels to `"INV-014 [JVM-only]: ..."` so test reports show intent, (b) `cancel("source-file grep is JVM-only")` so the test reports as `canceled` rather than `succeeded`. Either way, future readers don't mistake "passed on JS" for "verified on JS".

**NOTE-5: `private[kyo]` symbols are cross-module-reachable.**
- All new `CdpBackend` internals + the 2 probe case classes in `CdpTypes.scala` are `private[kyo]`. This makes them accessible from any module in the `kyo` package tree (`kyo-core`, `kyo-jsonrpc`, `kyo-http`, etc.). The project convention already accepts this trade-off (see same pattern in legacy `CdpClient.scala`), so not a regression. Documenting for the end-of-project pass that may consider tighter `private[internal]` if Scala's nested-package private becomes a project convention.

**NOTE-6: `untilTrue` ceiling is the outer `run(...)` test timeout.**
- A stuck `untilTrue` polls every 10ms forever; failure mode is "test times out at the kyo-Test default" (60s). Adequate but verbose on failure. Cleanup phase may consider an explicit `untilTrue(..., maxRetries = N)` overload in `BaseKyoCoreTest` for tighter feedback.

## Routing
- BLOCKER findings: NONE. Phase 02 launch is unblocked.
- WARN findings (3): TaskCreate routed to `phase-2-prep` input:
  - WARN-1 (3 `Async.sleep` -> `untilTrue`): paired with the call-site rename in Phase 02.
  - WARN-2 (overload Decision 25 doc or refactor): paired with Phase 02's `CdpBackend.send` final inline.
  - WARN-3 (decisions-log housekeeping D25/D26): one-line consolidation at the start of `phase-2-decisions.md`.
- NOTE findings (3 NOTE-1, NOTE-4, NOTE-5, NOTE-6): TaskCreate routed to end-of-project cleanup. The kyo-jsonrpc-side engine-test for `Timeout -> RequestCancelled` mapping (NOTE-1) is the only finding that crosses module boundaries; route to kyo-jsonrpc owner via the handoff queue per `feedback_nothing_out_of_scope`.

## Summary
HEAD vs design: MATCH-with-three-ratified-substitutions. The pulse already caught the substitutions; D21/D22/D23/D24 ratify them; verify confirmed the fixes landed. This audit's class-C surface adds three WARN (test-timing flake risk + decision-log housekeeping + off-plan overload documentation) and four NOTE (indirect Timeout test, silent-pass JS/Native, cross-module private[kyo], untilTrue ceiling). Zero BLOCKER. Phase 02 can launch.
