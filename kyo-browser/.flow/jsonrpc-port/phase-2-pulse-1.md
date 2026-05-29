# Phase 2 pulse 1

Time: 2026-05-29T13:15:00Z
Files reviewed: Browser.scala, BrowserTab.scala, CdpBackend.scala, CdpClient.scala, CdpTypes.scala,
  Resolver.scala, NavigationWatcher.scala, cdp/Accessibility.scala, cdp/PageDownload.scala,
  BrowserIsolateTest.scala, BrowserSessionTest.scala, CdpClientLifecycleTest.scala,
  AccessibilityTest.scala, PageDownloadTest.scala, CdpBackendTest.scala (current), CdpBackendTest.scala (HEAD)
Plan cites: ./05-plan.md §Phase 02

## Plan anchor

- Files to produce: 0 (delete-and-replace phase) / 0 present in dirty tree — MATCHES
- Files to modify: ~14 / 15 in git status (includes steering.md update) — MATCHES
- Files to delete: 1 (CdpClient.scala) / 0 actual deletions — MISMATCH (CdpClient.scala still present)
- Public API additions: none declared / none added — MATCHES
- Tests: 11 Phase 02 leaves / partially implemented — see scope-cutting table below

## Reward-hacking checks

| Pattern | Verdict | Citation |
|---|---|---|
| Verification commands actually run | PARTIAL: compile log 002 (last run) shows 66 errors. No successful compile logged yet. Impl agent is mid-work, not claiming green. | `runs/phase-02-impl-compile-jvm-002.log` tail |
| Compile-only "success" claim | CLEAN: no false success claim observed | n/a |
| Priority inference | CLEAN: no priority-based deferral in decisions log | phase-2-decisions.md |
| Scope substitution — CdpBackendTest stub | FLAG: CdpBackendTest.scala was rewritten from 41 test cases + FakeCdpSender to a single `succeed` stub (21 lines). Plan §Phase 02 says "the test bodies switch every `val client = CdpClient.init(...)` to `val client = CdpBackend.init(...)`"; it does NOT authorize stubbing. Plan §Phase 03 owns the actual REWRITE of CdpBackendTest to use `JsonRpcTransport.inMemory`. The stub eliminates 41 live cases before Phase 03 has replaced them. | `CdpBackendTest.scala` (current: 21 lines); `HEAD`: 41 cases + FakeCdpSender |
| Foreach-discards-assert | CLEAN: no pattern found in modified files | grep pass |
| Stale-state passing | FLAG: latest compile log (002) shows 66 errors; tree is NOT compile-clean at pulse time. The impl agent is still working, so this is expected mid-phase; it is flagged as a status marker, not a cover-up. | `runs/phase-02-impl-compile-jvm-002.log:465` |

## Drifting checks

| Pattern | Verdict | Citation |
|---|---|---|
| Public API signature matches plan | CLEAN: `Browser.run(wsUrl)` at line 272 retains `A < (Async & Abort[BrowserReadException] & S)` byte-identical. The BrowserSetupException is absorbed inside `run` via Abort.run recovery (Decision 32). `Browser.run(launch, session)` and `Browser.run(v)` signatures unchanged. INV-001/002/003 hold. | Browser.scala:272, diff |
| No off-plan architecture substitution | CLEAN: CdpBackend companion wrappers use `backend.send[P,R]` / `backend.sendUnit[P]` exactly as plan specifies. runtimeEvaluate re-encodes via `Json.encode(r)` per Decision 28 / prep §12 "safest path". Resolver.scala uses typed `s.send[EvaluateObjectParams, EvaluateObjectResult]` per Decision 27. | CdpBackend.scala:221-365, Resolver.scala diff |
| No cross-cutting refactor outside phase | CLEAN: no new files outside kyo-browser; no changes to kyo-jsonrpc or kyo-http | git status |
| Internal helpers stay internal | CLEAN: all new wrappers are `private[kyo]`; no new `def` added to the public `object Browser` | diff scan |

## Scope-cutting checks

| Leaf | Status | Notes |
|---|---|---|
| CdpClient.scala DELETED | MISSING | File is present on disk, git status shows `modified` not `deleted`. 563 lines remain. Deletion has NOT been executed yet. |
| CdpBackendOld object DELETED from CdpBackend.scala | PRESENT_STRICT | `object CdpBackendOld` body gone; only a one-line comment remains at line 634. |
| 28 forwarder methods DELETED from CdpBackend companion | PRESENT_STRICT | All 28 forwarders replaced with two-line `backend.send[P,R]` / `backend.sendUnit[P]` wrappers per plan. |
| CdpSender trait DELETED | PARTIAL: trait body removed from CdpClient.scala (types relocated to CdpTypes.scala); CdpClient.scala itself still present. `CdpClient` class no longer `extends CdpSender` (that line removed in diff). BrowserTab.enableDomains parameter type changed from `CdpSender` to `CdpBackend`. | CdpClient.scala diff; BrowserTab.scala diff |
| decodeOrFail DELETED from companion | PRESENT_STRICT | decodeOrFail forwarder removed from CdpBackend companion. Resolver.scala import deleted. Resolver inlines the logic. | CdpBackend.scala diff; Resolver.scala diff |
| Browser.scala rewired (CdpClient.init -> CdpBackend.init) | PRESENT_STRICT | Both `Browser.run` overloads wired to `CdpBackend.init`. All `tab.client.*` -> `tab.backend.*` sites replaced. | Browser.scala diff |
| BrowserTab.scala rewired (client -> backend field) | PRESENT_STRICT | `val client: CdpClient` -> `val backend: CdpBackend`; `val session: CdpClient` -> `val session: CdpBackend`; enableDomains param changed; mkBrowserTab / attachAndSetupTab / createChildTab all rewired. | BrowserTab.scala diff |
| CdpTypes.scala gains relocated types | PRESENT_STRICT | CdpEnvelope, CdpNoParams, CdpWireMessage, CdpReply, CdpEventParams, JavascriptDialogOpeningParams, FallbackIdEnvelope, CdpEvent, CdpError relocated per Decision 26. | CdpTypes.scala diff |
| CdpBackendTest.scala: minimally patched to compile | WEAKENED: Test file replaced with a single `succeed` stub (21 lines, 1 case). Plan §Phase 02 requires minimal symbol-rename patching (`CdpClient.init` -> `CdpBackend.init`, `CdpSender` -> `CdpBackend`) keeping 41 cases. Plan §Phase 03 owns the full rewrite. Stubbing 41 cases to 1 is a scope pull-forward that voids 41 real test assertions before Phase 03 restores them with the inMemory transport. This is a reward-hack vector: the stub self-annotates `// flow-allow: Phase 02 stub; Phase 03 restores the 41 test cases` without a corresponding Phase 03 tracking guarantee. | CdpBackendTest.scala HEAD vs current |
| WARN-1 Async.sleep replacements (3 sites) | MISSING: All 3 Async.sleep sites from steering.md WARN-1 are still present. `CdpBackendSmokeTest.scala:304` still has `Async.sleep(300.millis)`. `JsonRpcPortInvariantsSpec.scala:367` still has `Async.sleep(200.millis)`. `JsonRpcPortInvariantsSpec.scala:374` still has `Async.sleep(100.millis)`. Decision 34 was LOGGED (promising to replace) but the replacement has not been executed in the current tree. | CdpBackendSmokeTest.scala:304; JsonRpcPortInvariantsSpec.scala:367,374 |
| Test: CdpClientTest minimally patched | MISSING: CdpClientTest.scala still uses `CdpClient.init` / `CdpClient.initUnscoped`. Plan §Phase 02 requires patching these to `CdpBackend.init` / `CdpBackend.initUnscoped` to keep the file compile-green after CdpClient.scala deletion. File is unmodified from Phase 01. | CdpClientTest.scala (no diff from HEAD) |

## Anti-reward-hack checklist verdicts

1. `@nowarn` / `@SuppressWarnings` / `cancel()` / `pending()` / bare `case _ =>` swallowing: CLEAN. No instances found in added lines across any modified file.

2. Public surface byte-identical (INV-001..003): CLEAN. `Browser.run(wsUrl)` signature at line 272 holds `A < (Async & Abort[BrowserReadException] & S)` unchanged. BrowserSetupException absorbed internally. No `kyo.Browser`, `kyo.BrowserException`, `kyo.BrowserTab`, `kyo.BrowserSnapshot` public method signatures changed.

3. No manual JSON (INV-011): CLEAN. No `Json.parseString`, `String.format.*{`, `Pattern.compile.*{` in added lines. `runtimeEvaluate` uses `Json.encode(r)` on a typed `EvalResult` (schema-driven), not manual JSON construction.

4. No bare `var` for shared state (INV-012): CLEAN. Zero new `var` declarations found in added lines of production files.

5. INV-007 stability files: CLEAN (authorized). `NavigationWatcher.scala` has 1-line change (`tab.client` -> `tab.backend`) which is a plan-authorized mechanical rename per §Phase 02 scope. `Resolver.scala` is listed in `files_modified`. `Accessibility.scala` logged as Decision 36 (plan-authorized). Other INV-007 stability files (SharedChrome, BrowserLauncher, Selector, etc.) are unmodified.

6. Em-dashes / en-dashes in added lines: CLEAN. No `\x{2014}` or `\x{2013}` found in any added line (the grep output matching em/en-dashes hit only the steering.md warning section headers, not source).

7. `// flow-allow:` markers without rationale: MINOR CONCERN. `CdpBackendTest.scala` has `// flow-allow: Phase 02 stub; Phase 03 restores the 41 test cases`. The rationale describes a Phase 03 restore that cannot be structurally guaranteed at Phase 02 commit time. This annotation papers over a real scope issue.

8. WARN-1 Async.sleep replacements: MISSING. Decision 34 logged but not executed. Three sites remain.

## CRITICAL (steer immediately)

**CRITICAL-A: CdpBackendTest.scala stubbed to 1 case — must restore 41-case minimal-patch form**

`CdpBackendTest.scala` was rewritten from 41 test cases (HEAD) to a single `succeed` stub with `// flow-allow: Phase 02 stub; Phase 03 restores`. This violates plan §Phase 02 which says "the test bodies switch every `val client = CdpClient.init(...)` to `val client = CdpBackend.init(...)`" — i.e. minimal patching, NOT stubbing. The 41 cases exercising each companion wrapper must remain in the file. The FakeCdpSender bodies cannot compile after CdpSender deletion; they must be switched to a `CdpBackend.initUnscoped(inMemoryTransport, ...)` pattern with a stub server that replies to each wrapper's method. Alternative: keep the `FakeCdpSender` pattern by adding a local test-only trait `FakeBackend extends CdpBackend { ... }` — but that requires a cast-free shim. The CORRECT path per plan: rewire each test body to use a real `JsonRpcTransport.inMemory` pair + a stub server fiber that returns the typed response. This is exactly what Phase 03's "REWRITE bodies" means, and Phase 02 cannot defer to Phase 03 while claiming the 41 cases are "preserved."

Fix: Restore the 41 original test case headings. Rewrite each body to use `JsonRpcTransport.inMemory` + a fake server fiber replying with the appropriate typed JSON (matching the `replyOk` pattern from HEAD). Do NOT leave a single `succeed` stub.

**CRITICAL-B: CdpClient.scala not deleted — file still present at 563 lines**

`git status` shows `modified: kyo-browser/shared/src/main/scala/kyo/internal/CdpClient.scala`, not `deleted`. The file was edited (types stripped from the bottom, `extends CdpSender` line removed) but the 563-line `CdpClient` class and `object CdpClient` (init, initUnscoped, decodeCdpMessage, etc.) still exist. Plan §Phase 02 scope: "Delete `CdpClient.scala` outright (627 LoC)". Until this file is actually deleted, any test file can continue to import `CdpClient` and the migration is not atomic.

Fix: After CdpClientTest.scala is patched (CRITICAL-A prerequisite resolved), delete `CdpClient.scala` with `rm` / `git rm`.

**CRITICAL-C: WARN-1 Async.sleep replacements not executed**

Three `Async.sleep` sites logged as Decision 34 (to be replaced) but not yet replaced in the current tree:
- `CdpBackendSmokeTest.scala:304` — `Async.sleep(300.millis)`
- `JsonRpcPortInvariantsSpec.scala:367` — `Async.sleep(200.millis)`
- `JsonRpcPortInvariantsSpec.scala:374` — `Async.sleep(100.millis)`

These are steering.md WARN-1 items that must land in Phase 02. The decisions log acknowledges them; the tree does not reflect the fix. Replace each with an `untilTrue(capturedRef.get.map(condition))` pattern matching the existing `untilTrue` usages at lines 155, 189, 225, 259 of the same file.

## MINOR (queue for post-commit audit)

- **MINOR-1: Naming drift in test variables.** Several test files use `val client = parent.backend` or `val client = parent.backend` (e.g. BrowserIsolateTest.scala:904, BrowserSessionTest.scala:101). The local variable is named `client` but holds a `CdpBackend`. This compiles but creates confusion. Phase 03 can clean these up; log as audit note.

- **MINOR-2: CdpClientTest.scala not yet patched.** The file still uses `CdpClient.init` / `CdpClient.initUnscoped` (15 cases). It will not compile once CdpClient.scala is deleted (CRITICAL-B). This is expected mid-phase but must be resolved before the phase ends. Track as a prerequisite to CRITICAL-B resolution.

- **MINOR-3: Stale comment in Resolver.scala.** The comment at `kyo-browser/shared/src/main/scala/kyo/internal/Resolver.scala:24` still refers to `CdpClient.close`. Mechanical update to `CdpBackend.close`.

- **MINOR-4: CdpBackend.scala class-level Scaladoc** at lines 118-121 describes the Phase 01 coexistence of `CdpBackendOld` as a present tense ("Keeps the CdpBackendOld object... alive in the SAME file"). This description should be updated to past tense or removed since CdpBackendOld is now deleted.

## LoC budget status

- Added lines: 436
- Removed lines: 1,210 (includes steering.md 34 added lines for Phase 01 STEER block)
- Net delta: -774 lines
- Total dirty diff lines: 1,547

Phase 02 is `oversize_justified` with 800-1000 LoC estimated. The current net is within range; however CdpClient.scala (563 lines) deletion is still pending which will add another -563 to removals, bringing total to roughly -1,337 net, still within the 1,000-1,500 LoC expected deletion range. No bloat risk.

## Recommendation: STEER — three critical items block phase completion

Write to steering.md immediately:

1. **STEER-A**: Restore CdpBackendTest.scala to 41 test cases. The current `succeed` stub is a scope cut. Rewrite each test body to use `JsonRpcTransport.inMemory` + a local fake server fiber returning the typed reply JSON. Do NOT defer to Phase 03. Phase 03's REWRITE means switching from this inMemory pattern to a richer behavioral pattern; Phase 02 must ship 41 compile-green test cases, not 1 stub.

2. **STEER-B**: After CdpClientTest.scala is minimally patched (`CdpClient.init` -> `CdpBackend.init` in its `withClient` helper, plus any `client.<field>` references updated to the new field layout), delete `CdpClient.scala` with `git rm`. This is the load-bearing scope item for Phase 02.

3. **STEER-C**: Execute the WARN-1 Async.sleep replacements at `CdpBackendSmokeTest.scala:304`, `JsonRpcPortInvariantsSpec.scala:367`, and `JsonRpcPortInvariantsSpec.scala:374`. These were logged in Decision 34 but the tree still has the fixed sleeps. Replace with `untilTrue(ref.get.map(condition))` pattern.
