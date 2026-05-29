# Phase 2 verify report

Status: **FAIL (class-A test gate + class-B judgment)**

Phase 02 cannot commit. The full JVM test run surfaces 824 failing tests rooted in a wire-decode mismatch between the typed-notification dispatcher and the BrowserTab consumer; the stub-the-hard-test scope cut compounds the diagnosis surface. Both must be addressed before re-verify.

## Inputs read

- `05-plan.md` § Phase 02, `05-plan.yaml` phases[1]
- `phase-2-prep.md`, `phase-2-decisions.md` (Decisions 25-43)
- `phase-2-pulse-1.md` (mid-flight pulse; STEER-A/B/C)
- `phase-1-audit.md` (WARN-1/2/3 routed into Phase 02)
- `steering.md` (Phase 02 STEER-A directives)
- `04-invariants.md`
- HEAD = `c025b00e6 [browser] Phase 01: CdpBackend runtime class on kyo-jsonrpc`
- Dirty tree per `git status`; baseline `phase-02-baseline.txt` (single line)

## Verification commands run

- `sbt kyo-browser/Test/compile` -> green (`runs/phase-02-verify-compile-jvm-001.log`)
- `sbt kyo-browserJS/Test/compile` -> green (`runs/phase-02-verify-compile-js-001.log`)
- `sbt kyo-browserNative/Test/compile` -> green (`runs/phase-02-verify-compile-native-001.log`)
- `sbt kyo-browser/test` -> **RED, 824 failures of 1267 ran** (`runs/phase-02-verify-test-jvm-001.log`)

## Class-A gates (mechanical, commit-blocking)

| Gate | Result | Notes |
|---|---|---|
| reward-hacking grep | 79 hits, all out-of-scope (kyo-test/, prose) or pre-existing scaladoc; 0 hits added by Phase 02 diff | filtered against added lines; clean |
| fp-discipline grep | 53 hits scoped to kyo-browser, all pre-existing patterns (`case Some(i)` from `String.toIntOption`, scaladoc `null` references); diff confirms zero new fp-discipline regressions | added-line grep `(Some\(|None|asInstanceOf|isInstanceOf|null|var |java\.util\.concurrent\|Try)` = 0 source hits |
| llm-tells grep | em-dash/en-dash on added lines: 0 (verified `git diff HEAD \| grep '^\+' \| grep -E '\xe2\x80\x9[34]'` = empty) | INV-014 added-line invariant holds |
| dev-tag grep | 19 hits; all `// Phase N` comments inside source/test files lacking the `// DEV:` tag | OVERRIDE-NEEDED, see Overrides section |
| em-dash/en-dash added-lines | 0 | clean |
| plan-diff (kyo-browser scope) | 14 files modified + 1 deleted vs plan's 14 modified + 1 deleted | AUTHORIZED; the script's raw output (69 MISSING, 28 DRIFT-FROM-IMPL) is corrupted: MISSING entries are YAML `code:` markdown lines parsed as file paths, DRIFT entries are kyo-jsonrpc/ research artifacts that pre-date the campaign launch and are present in baseline-truth (pre-existing untracked, gitStatus snapshot at task start) |
| test-count | expected=11 Phase 02 leaves; actual=4 INV leaves added to JsonRpcPortInvariantsSpec (INV-001, INV-003, INV-010, INV-014-Phase02). Missing: INV-002 (BrowserException byte-identity), INV-007 (stability files), INV-008 (Phase 02 source+test pairing), INV-009 (Cdp\*.scala basename), INV-011 (no manual JSON Phase 02), INV-012 (no var Phase 02), INV-013 (no AllowUnsafe Phase 02). Some are covered transitively by Phase 01 versions of the same INV that scan `CdpBackend.scala` (which was Phase 02-modified); but INV-002 and INV-007 are scope-specific to Phase 02 files and are NOT present in any form. **FAIL**: 4/11. |
| stowaway-commit | NONE detected; no impl-side `git add`/`git commit` evidence | clean |
| Rule 8 organization | 52 violations (11 BrowserException 8b-name-mismatch, 38 8c-orphan-test, 3 misc); ALL pre-existing donor-module shape; campaign plan does not include Rule 8 cleanup | NOT introduced by Phase 02; out-of-scope-by-plan, NOT out-of-scope-by-dismissal |
| Cross-platform compile | JVM green, JS green, Native green | PASS |
| Cross-platform tests (Chrome-dependent JVM) | JVM **FAIL** (824/1267 failing) | **FAIL** |
| Validate-before-annotate (Decision #32 framework rule) | `phase-2-validation.json` not produced; class-B opus reviewer not run by this verify | skipped |

## Class-B findings (opus judgment)

1. **CRITICAL — Pre-existing-in-HEAD wire-decode mismatch surfaces under live-Chrome tests.** `CdpBackend.buildFrameCreatedMethod` (and `buildFrameDestroyedMethod`) at `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala:445-468` decodes typed `ExecutionContextCreatedParams` from the notification, then re-encodes via `Json.encode(params)` producing wire `{"context":{...}}`. Consumer `BrowserTab.updateFrameContexts` at `kyo-browser/shared/src/main/scala/kyo/internal/BrowserTab.scala:108` decodes that wire via `Json.decode[CdpEventParams[ExecutionContextCreatedParams]](wire)`, which expects the OUTER envelope `{"method":..., "params":{"context":...}}` — a shape the dispatcher never produces. Every test that exercises a real Chrome target context creation fails with `Missing required field 'params'`. Bug exists at HEAD (Phase 01); confirmed via `git show HEAD:.../BrowserTab.scala` and `git show HEAD:.../CdpBackend.scala` — both ALREADY have this mismatch. Phase 01 verify ran only `testOnly *Smoke* *Invariants*`, which does not drive a real Chrome and so missed it. **Per `feedback_nothing_out_of_scope`: this is NOT pre-existing-and-deferrable; Phase 02 must own the regression because it's the first time the campaign runs the full live suite.** Two equally valid fixes: (a) change `paramsJson = Json.encode(params)` to construct `Json.encode(CdpEventParams(method, params, sid))` so the dispatched JSON IS a full envelope; (b) change `BrowserTab.updateFrameContexts` to decode `Json.decode[ExecutionContextCreatedParams](wire)` directly (the typed dispatch already unwrapped `params`). Option (b) is simpler and consistent with the typed flow.

2. **Test stubs front-loaded from Phase 03.** Five test files stubbed to `succeed`-only single-case bodies:
   - `kyo-browser/shared/src/test/scala/kyo/internal/CdpBackendTest.scala` (21 LoC, was 669 LoC, 41 cases)
   - `kyo-browser/shared/src/test/scala/kyo/internal/CdpClientLifecycleTest.scala` (22 LoC, was 1262 LoC, 25 cases)
   - `kyo-browser/shared/src/test/scala/kyo/internal/CdpClientTest.scala` (24 LoC, was 15 cases)
   - `kyo-browser/shared/src/test/scala/kyo/internal/CdpClientDecoderTest.scala` (19 LoC; Phase 03 deletes this file outright per plan)
   - `kyo-browser/jvm/src/test/scala/kyo/internal/CdpClientLifecycleJvmTest.scala` (20 LoC, JVM-only lifecycle)
   - Plus `kyo-browser/shared/src/test/scala/kyo/internal/cdp/PageDownloadTest.scala` two-of-three cases stubbed (per Decision 39)
   
   Pulse-1 STEER-A flagged this as a scope cut; impl agent chose option A (stub) over option B (mechanical CdpClient->CdpBackend rename + FakeBackend shim) and committed to that choice in Decisions 37-39, 41. **Judgment call**: front-loading deletion to Phase 02 is acceptable IFF Phase 03's prep doc has the exact restoration spec AND the net test count restores at Phase 03. `phase-3-prep.md` confirms the per-case spec (41-case CdpBackendTest rewrite, 25->15 CdpBackendLifecycleTest keep/delete, etc.). HOWEVER, this carries real verification cost: 80+ test cases vanish from the green-build for the Phase 02 commit cycle, so any further regression introduced in the affected production paths between Phase 02 and Phase 03 will not be caught by the suite during that window. **The pull-forward is plan-deviant but recoverable**; the bigger concern is the wire-decode bug (finding 1) which IS catchable by the live suite that just ran.

3. **WARN-1 Async.sleep replacements landed.** Decision 34's three sites (`CdpBackendSmokeTest.scala:304`, `JsonRpcPortInvariantsSpec.scala:367/374`) are clean per `grep -n "Async.sleep"`. The only remaining `Async.sleep` in test files (line 262 of the invariants spec) is the dialog drainer auto-dismiss path, which is a different, plan-authorized sleep. WARN-1 is resolved.

4. **CdpClient.scala deletion landed.** `git status` shows `D kyo-browser/shared/src/main/scala/kyo/internal/CdpClient.scala`. STEER-B resolved.

5. **Plan deviations from off-plan-overload kept.** Decision 25 keeps the `initUnscoped(transport, launchCfg)` overload (test-only ~60 LoC) per WARN-2 explicit allow. CdpBackend.scala still has this overload (lines 361+). No issue, recorded.

6. **Decisions 25-43 trace fully** to either the plan, the pulse, or the steering directives; no unexplained scope adds.

## Overrides (for commit-message body when verify passes)

If Phase 02 re-runs green:

```
// flow-allow: Phase 02 stub; Phase 03 restores the 41 test cases with typed CdpBackend path
  -- kyo-browser/shared/src/test/scala/kyo/internal/CdpBackendTest.scala:12
// flow-allow: Phase 02 stub; Phase 03 rewrites as CdpBackendLifecycleTest
  -- kyo-browser/shared/src/test/scala/kyo/internal/CdpClientLifecycleTest.scala:11
// flow-allow: Phase 02 stub; Phase 03 restores CdpClient tests as CdpBackend-based tests
  -- kyo-browser/shared/src/test/scala/kyo/internal/CdpClientTest.scala:13
// flow-allow: Phase 02 stub; Phase 03 deletes this file per plan
  -- kyo-browser/shared/src/test/scala/kyo/internal/CdpClientDecoderTest.scala:10
// flow-allow: Phase 02 stub; Phase 03 restores download-event round-trip tests
  -- kyo-browser/shared/src/test/scala/kyo/internal/cdp/PageDownloadTest.scala:29
// flow-allow: Phase 02 stub; Phase 03 rewrites JVM lifecycle tests
  -- kyo-browser/jvm/src/test/scala/kyo/internal/CdpClientLifecycleJvmTest.scala:11
```

Per `// Phase N` comment pattern hits inside `CdpBackend.scala`, `CdpTypes.scala`, and `JsonRpcPortInvariantsSpec.scala` (the "phase-reference-in-comment" dev-tag hits): these are explanatory anchors in source/test files; they should be re-flagged as `// DEV:` prefix or removed via `flow-strip-dev` at end of campaign per FLOW §8.

## Remediation list (FAIL response)

1. **MUST FIX BEFORE COMMIT** — Wire-decode mismatch in `CdpBackend.buildFrameCreatedMethod` / `buildFrameDestroyedMethod` vs `BrowserTab.updateFrameContexts`. Recommend option (b): change `BrowserTab.updateFrameContexts` to `Json.decode[ExecutionContextCreatedParams](wire)` (and `ExecutionContextDestroyedParams`) directly, matching the wire shape the dispatch produces. Verify with `sbt 'kyo-browser/testOnly *BrowserCoreTest*'` (a Chrome-driving canary suite). Add a Decision-N entry tracking the fix, and route it as a Phase 02 fix despite the bug originating at HEAD.

2. **MUST FIX BEFORE COMMIT** — Add the 7 missing Phase 02 invariant leaves to `JsonRpcPortInvariantsSpec.scala`: INV-002 (BrowserException byte-identity vs pre-port tag), INV-007 (subset stability-file sha256 list), INV-008 (Phase 02 commit pairs sources with tests), INV-009 (`Cdp*.scala` basename rule, CdpBackendOld absent), INV-011 (no manual JSON post-Phase-02), INV-012 (no var in Phase 02 diff), INV-013 (no unannotated AllowUnsafe in Phase 02 diff). Some may transitively share assertions with their Phase 01 counterparts; the spec must still list each leaf explicitly per the plan's `tests.total: 11` contract.

3. **VERIFY AFTER FIX** — re-run `sbt kyo-browser/test` end-to-end, expect green or only `cancel()`-via-skill-judgment failures (live-Chrome flakes that recur across runs MUST be diagnosed at root, not dismissed).

4. **DO NOT FIX HERE — track for Phase 03 prep** — the stubbed test files reduce net test count by ~80 cases. Phase 03's prep doc has the restoration spec; supervisor should confirm `phase-3-prep.md`'s body-rewrite plan covers each stubbed file end-to-end before dispatching Phase 03.

## Class-A summary

- reward-hacking: 0 added-line hits in kyo-browser source/test (pass)
- fp-discipline: 0 added-line hits in kyo-browser source/test (pass)
- llm-tells (em/en-dash added): 0 (pass)
- dev-tag: 19 phase-reference hits in source/test (pass with override-required at strip-dev stage)
- plan-diff: AUTHORIZED matches per kyo-browser scope (pass; script's raw output is corrupted by YAML embedded-code parsing and by pre-baseline kyo-jsonrpc/ research artifacts)
- test-count: 4/11 (**FAIL**)
- stowaway-commit: NONE (pass)
- cross-platform compile JVM/JS/Native: PASS / PASS / PASS
- cross-platform JVM test: **FAIL (824/1267 failing, root cause is HEAD-introduced wire-decode bug)**

## Verdict

**FAIL.** Two class-A gates trip: test-count (4/11 leaves) and JVM test-run (824 failures). The 824 failures derive from one root-cause: the wire-decode shape mismatch at the typed-notification -> BrowserTab consumer boundary. This is mechanically fixable in <30 LoC.

Re-verify command after fix: `sbt 'kyo-browser/Test/compile' 'kyo-browser/test'` and re-grep test-count.

## Exit code: 1
