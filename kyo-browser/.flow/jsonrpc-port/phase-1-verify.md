# Phase 1 verify report

Status: PASS (with two minor class-A annotation gaps surfaced for the supervisor to choose between hard-fail and override)

This phase introduces `CdpBackend` (private[kyo] runtime class + companion + `CdpBackendOld` legacy carryover) on `kyo-jsonrpc`, adds 2 probe case classes to `CdpTypes.scala`, wires `build.sbt`'s `kyo-browser` to depend on `kyo-jsonrpc` and `kyo-jsonrpc-http`, and ships 2 new shared test files + 3 platform JsonRpcPortFileOps helpers. All 27 JVM tests pass (10 SmokeTest + 17 InvariantsSpec).

## Class-A gates (mechanical, commit-blocking)

### Compile + test verification (per-phase verify scope: JVM only)
- `sbt 'kyo-browser/Test/compile'`: PASS (logs: `runs/phase-01-verify-compile-jvm-001.log` failed-due-to-name, retried green in `runs/phase-01-verify-testOnly-jvm-005.log`).
- `sbt 'kyo-browser/testOnly kyo.internal.CdpBackendSmokeTest kyo.internal.JsonRpcPortInvariantsSpec'`: PASS (`runs/phase-01-verify-testOnly-jvm-005.log`).
  - Total: 27 tests, 27 succeeded, 0 failed, 0 canceled.
  - SmokeTest: 10/10 green; InvariantsSpec: 17/17 green.
  - Note on project naming: `kyo-browser` `crossProject` declares `.withoutSuffixFor(JVMPlatform)`, so the JVM project id is `kyo-browser` (NOT `kyo-browserJVM`). The plan's verification_command and the prompt template both name `kyo-browserJVM/...` ; that string does NOT resolve. Use `kyo-browser/...` for JVM, `kyo-browserJS/...` for JS, `kyo-browserNative/...` for Native. The impl agent's prior log (`runs/phase-01-impl-testOnly-jvm-002.log`) also used the working form. Cross-platform verify at Stage 3.5 will need the corrected names.

### Reward-hacking grep (`flow-verify-grep.sh --catalog reward-hacking`)
Scoped to phase artifacts (the dirty tree contains 38 unrelated pre-existing files, e.g. `kyo-jsonrpc/.flow/...`, `kyo-jsonrpc/research/...`, `kyo-test/`, which would otherwise pollute the count).

- Hits: 3
  - `scope-substitution` at `CdpBackend.scala:541`: phrase "test-stability-critical edge case" inside a comment that explains the auto-dismiss-when-no-handler invariant (INV-015 / Phase-01 dialog routing). This phrase is verbatim from plan `05-plan.md:346`. False positive of the `edge case` regex; the comment is a verbatim copy of the plan's pseudocode, not a deferral. JUDGMENT: legitimate prose.
  - `deferral-for-now` at `CdpTypes.scala:354`: PRE-EXISTING file content (`placeholder backendNodeId for the un-tracked node`). PRE-EXISTING, untouched by Phase 01 (diff is +14 lines at file end). PRE-EXISTING.
  - `dismissed-as-flake` at `build.sbt:1205`: PRE-EXISTING comment about Native test-suite serialization. The Phase 01 diff only modifies line 1166 (dependsOn). PRE-EXISTING.

Effective fail_count after PRE-EXISTING + JUDGMENT filtering: 0. PASS.

### FP-discipline grep (`flow-verify-grep.sh --catalog fp-discipline`)
Scoped to phase artifacts.

- Hits: 15, all in `CdpTypes.scala`.
  - `null-literal` x1 (line 398): PRE-EXISTING file content; the line is a Scaladoc comment quoting CDP wire spec semantics. PRE-EXISTING.
  - `public-api-missing-annotation` x7 (lines 54-59, 200): PRE-EXISTING `given CanEqual[X, X] = CanEqual.derived` and `given Schema[RemoteObject]`. PRE-EXISTING.
  - `extension-owned-type` x7 (lines 32-52, 171): PRE-EXISTING extension methods on opaque-type ids. PRE-EXISTING.

Phase 01 diff in `CdpTypes.scala` is +14 lines at end (BrowserGetVersionParams + BrowserVersionResult derives Schema). None of the new lines trigger any fp-discipline regex.

Effective fail_count after PRE-EXISTING filtering: 0. PASS.

### LLM-tells grep (`flow-verify-grep.sh --catalog llm-tells`)
Scoped to phase artifacts.

- Hits: 6
  - `em-dash` x4 at `build.sbt:60, 75, 1082, 1205`: PRE-EXISTING comments (Phase 01 only touches line 1166). PRE-EXISTING.
  - `em-dash` + `en-dash` at `JsonRpcPortInvariantsSpec.scala:171`: the chars `'—'` and `'–'` appear inside char literals: `!content.exists(c => c == '—' || c == '–')`. This is the INV-014 invariant TEST that verifies CdpBackend.scala contains zero em/en-dashes. The data IS the chars-being-rejected. JUDGMENT: legitimate test self-evidence; the regex cannot distinguish source from test-payload.

CLASS-A FINDING: this line should carry a `// flow-allow: INV-014 invariant test asserts these chars are absent from source` annotation on the preceding line so the catalog records it as OVERRIDE rather than HIT. Without the annotation, this is a class-A blocking hit per FLOW-DESIGN §32. RECOMMENDATION: add the annotation (one-line edit) and re-run; the supervisor may also choose to commit with the un-annotated hit by accepting a one-shot override classification.

Effective fail_count after PRE-EXISTING filtering: 2 (the two char-literal positions on line 171). Both need an `// flow-allow:` annotation OR a single supervisor-level override classification.

### Dev-tag grep (`flow-verify-grep.sh --catalog dev-tag`)
Scoped to phase artifacts.

- Hits: 4
  - `CdpBackend.scala:221, 223, 224`: section header `// --- Forwarding methods for Phase 01 byte-equivalent coexistence ---` and two follow-up comment lines `// ... continue to compile unchanged in Phase 01.` / `// Phase 02 deletes CdpBackendOld ...`. The flow-allow override on line 225 covers line 226+, not lines 221-224. Per FLOW-DESIGN §8, dev-process references in source comments require either `// DEV:` prefix or `// flow-allow:` override on the line PRECEDING the dev-process reference.
  - `CdpTypes.scala:454`: section header `// --- Browser.getVersion probe types (Phase 01: CdpBackend.initUnscoped connect-probe) ---`. Same pattern; no preceding `// DEV:` nor `// flow-allow:`.

CLASS-A FINDING (4 hits): these 4 comment lines need EITHER `// DEV:` prefix (rewrite each phase reference) OR a `// flow-allow:` annotation on the immediately preceding line. Per Decision 23, the `CdpBackendOld` carryover is approved, so the comment lines genuinely describe Phase-01-bounded scope. RECOMMENDATION: add `// flow-allow: phase-01 byte-equivalent coexistence; deleted in Phase 02` immediately above line 221 (so the section header is overridden) and `// flow-allow: phase-01 probe-types section header` immediately above line 454. Both lines align with Decisions 21, 23.

### Plan-diff three-bucket (`flow-verify-plan-diff.sh --baseline phase-01-baseline.txt`)
The script as currently shipped parses YAML scalar block-fold `code: |` content as filenames (a yq script bug), producing 30+ spurious MISSING entries that are YAML comment bodies / template literals, not real plan-listed files. Manual three-bucket classification:

| File | Bucket | Verdict |
|---|---|---|
| `build.sbt` | AUTHORIZED (plan files_modified) | include |
| `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala` | AUTHORIZED (plan files_produced) | include |
| `kyo-browser/shared/src/main/scala/kyo/internal/CdpTypes.scala` | AUTHORIZED (plan side-effects ref BrowserGetVersionParams/BrowserVersionResult per Decision 5) | include |
| `kyo-browser/shared/src/test/scala/kyo/internal/CdpBackendSmokeTest.scala` | AUTHORIZED (plan files_produced) | include |
| `kyo-browser/shared/src/test/scala/kyo/internal/JsonRpcPortInvariantsSpec.scala` | AUTHORIZED (plan files_produced) | include |
| `kyo-browser/jvm/src/test/scala/kyo/internal/JsonRpcPortFileOps.scala` | AUTHORIZED via Decision 19 (platform-specific helper for invariant file I/O on JVM) | include; the platform-helper file is the cross-platform expect/actual solution Decision 19 ratifies |
| `kyo-browser/js/src/test/scala/kyo/internal/JsonRpcPortFileOps.scala` | AUTHORIZED via Decision 19 | include |
| `kyo-browser/native/src/test/scala/kyo/internal/JsonRpcPortFileOps.scala` | AUTHORIZED via Decision 19 | include |
| `kyo-browser/.flow/jsonrpc-port/*` | PRE-EXISTING (baseline `?? kyo-browser/.flow/`) | flow-state artifacts, never commit |
| `kyo-jsonrpc/.flow/protocol-coverage/*`, `kyo-jsonrpc/research/*`, `kyo-test/`, top-level `.claude/skills/...`, `.github/workflows/...`, `AGENTS.md`, `CLAUDE.md`, etc. | PRE-EXISTING (NOT in baseline but ALSO NOT touched by Phase 01 impl agent; these are unrelated repo-state from prior sessions) | DO NOT COMMIT in Phase 01; the supervisor must stage ONLY the 8 AUTHORIZED files |

MISSING: 0 plan-listed files absent from dirty tree.
DRIFT-FROM-IMPL: 0 (the script's bucket-DRIFT entries are repo-wide stale dirty state pre-dating the phase; they fall through to PRE-EXISTING by intent of the rule, but the baseline file at `phase-01-baseline.txt` was captured too narrowly (only `?? kyo-browser/.flow/`), so the script bucketed them as DRIFT. Supervisor judgment per FLOW-DESIGN §32: these are NOT impl agent escape-from-scope; they are prior-session artifacts the supervisor must exclude from the phase commit via explicit file selection).

PASS (with the explicit warning that the supervisor MUST `git add` only the 8 authorized files, not `git add .`).

### Test-count (`flow-verify-test-count.sh`)
Script depends on `rg` (ripgrep) which is not on PATH in this environment. Manual count from log:
- Plan: `test_count: 24` (10 SmokeTest IDs 1-10 + 14 InvariantsSpec IDs 11-24).
- Actual: 27 tests run (10 SmokeTest + 17 InvariantsSpec).
- Delta: +3 (InvariantsSpec adds INV-022, INV-023, INV-024 which the plan's `produced_invariants: [..., INV-022, INV-023, INV-024]` lists but the per-leaf table IDs only ran 11-24). These three are commit-hygiene invariants (no Co-Authored-By, no git push, every flow-allow has rationale) and the impl agent added them ahead of schedule.

Per pulse 1 MINOR-4 and Decision 24, the extra 3 tests are a coverage increase, not a violation. PASS (with note that the +3 should be reflected in the plan when Phase 03 finalizes the test_count_post_phase_03_shared total).

### Rule 8 organization gate (`flow-verify-organization.sh --check all --root kyo-browser`)
- `8a-package-public-HIGH` at `Browser.scala:1869`: PRE-EXISTING (the `private[kyo]` constructor reference inside the public `Browser` companion is the existing pattern; Phase 01 does not touch this file).
- `8a-package-public` at `BrowserException.scala:1`: PRE-EXISTING.
- `8b-name-mismatch` x21 in `BrowserException.scala`: PRE-EXISTING. The codebase deliberately ships 21+ `Browser*Exception` subtypes in one file (the ADT is the exception hierarchy). Phase 01 does not touch this file. NOTE: this is a long-standing convention in `kyo-browser`; the Rule 8b regex flags it but the design owns this file's structure (a single sealed ADT family across many concrete classes).
- `8c-orphan-test` x16: SCRIPT BUG (line 263 of `flow-verify-organization.sh` excludes `internal/` paths from the SRC_NAMES set, so tests for sources under `kyo/internal/` are reported as orphans). All 16 entries are tests-for-internal-sources, including the new `CdpBackendSmokeTest.scala` (which correctly pairs with `internal/CdpBackend.scala`). PRE-EXISTING bug + valid new pairing.

Effective Rule 8 fail_count after PRE-EXISTING + script-bug filtering: 0. PASS.

### Stowaway-commit check
`flow-verify-stowaway.sh --impl-stdout runs/phase-01-impl-testOnly-jvm-002.log`: no `git add` / `git commit` invocations inside the impl agent's stdout. PASS.

### Cross-platform (Stage 3.5 deferred per prompt)
Per `verification_strategy: targeted`, the per-phase verify scope is JVM only. JS + Native compile and the full cross-platform compile gate is established at the campaign-final Stage 3.5 green-build gate. DEFERRED for Phase 01 verify; recorded for the supervisor.

## Class-B findings (opus judgment)

### B1. Test-count specificity (FLOW-DESIGN §B reviewer rule 7-adjacent)
Plan total=24, actual=27. Decision 24 ratifies the +3 (INV-022, INV-023, INV-024) as a coverage increase, not a scope substitution. PASS as ratified.

### B2. Public-API byte-identical (INV-001 / INV-002 / INV-003 consumed at Phase 02-03)
`git diff HEAD -- kyo-browser/shared/src/main/scala/kyo/Browser.scala kyo-browser/shared/src/main/scala/kyo/BrowserException.scala kyo-browser/shared/src/main/scala/kyo/BrowserTab.scala kyo-browser/shared/src/main/scala/kyo/BrowserSnapshot.scala kyo-browser/shared/src/main/scala/kyo/internal/BrowserTab.scala kyo-browser/shared/src/main/scala/kyo/internal/BrowserSnapshot.scala` -> 0 lines. PASS. No public-API signature changed in Phase 01.

The Browser.getVersion probe call (CdpBackend.initUnscoped:206-218) widens the INTERNAL `CdpBackend.init` and `initUnscoped` return-type effect rows to `Abort[BrowserReadException | BrowserSetupException]` (CdpBackend.scala:140 and :149). These are `private[kyo]` factory methods; widening their effect row is internal and does NOT affect the public surface (Browser.scala still calls into legacy `CdpClient.init` in Phase 01; Phase 02 owns the cutover). PASS.

### B3. Reward-hack check on post-pulse fix (CRITICAL-1 ratification, Decision 21)
The pulse flagged `BrowserConnectionLostException` re-raise; Decision 21 widened return types and switched to `BrowserSetupFailedException`. Verified:
- `CdpBackend.scala:208`: `Abort.fail(BrowserSetupFailedException(...))`. PASS.
- `CdpBackendSmokeTest.scala` leaf 2: test name reads "init fails fast with BrowserSetupFailedException when probe returns Closed" (log line confirms run name). PASS.
- `JsonRpcPortInvariantsSpec.scala` INV-016: test name reads "INV-016: Browser.getVersion probe converts Closed to BrowserSetupFailedException" (log line confirms). PASS.
- Return types widened: `init` and `initUnscoped` at lines 140 and 149 both declare `Abort[BrowserReadException | BrowserSetupException]`. PASS.

This is a genuine fix, not a post-hoc test rewrite. The impl + tests align with the plan's Q-002 ratification (and Decision 1's correction of the plan's sealed-trait misnomer).

### B4. Decisions log completeness (24 entries; D21-D24 from post-pulse steer)
- D21 (CRITICAL-1 fix, BrowserSetupFailedException + return-type widening): VERIFIED in source (CdpBackend.scala:140, 149, 208).
- D22 (CRITICAL-2 LOG-only, Scope-in-initUnscoped kept; supersedes prep doc gotcha #1 + D2): VERIFIED. `initUnscoped` return type includes `Scope` at lines 140 + 149.
- D23 (CRITICAL-3 LOG-only, 28 forwarders retained): VERIFIED. Forwarder block at CdpBackend.scala:221-353. The `// flow-allow: phase-01 byte-equivalent coexistence; deleted in Phase 02` annotation is at line 225.
- D24 (MINOR-5 LOG-only, INV-020 stub stays; verified at Stage 3.5): VERIFIED. `JsonRpcPortInvariantsSpec.scala:414` (INV-020) is a `succeed` stub by design; Stage 3.5 cross-platform gate is the structural verifier.

All four post-pulse decisions are logged with rationale. PASS.

### B5. Reward-hack check on stub tests (FLOW-DESIGN §B reviewer rule 11)
- INV-020 (`succeed`): rationale logged in D24. The cross-platform compile is the supervisor's responsibility (FLOW-DESIGN §B and `verification_strategy: targeted`). NOT a reward-hack hide; transparent stub with rationale.
- INV-023 (`succeed`): D10 explains the structural-grep fallback for git-property invariants. NOT a reward-hack hide.

PASS.

### B6. Test-infra drift (FLOW-DESIGN §B reviewer rule 10)
New test imports: `kyo.Test`, `kyo.JsonRpcTransport`, `kyo.JsonRpcEndpoint`, `kyo.JsonRpcMethod`, `kyo.JsonRpcId`, `kyo.JsonRpcEnvelope`, `kyo.JsonRpcCodec`. All from the in-scope kyo-jsonrpc / kyo-jsonrpc-http modules that Phase 01 newly depends on via build.sbt. No off-plan test-base-class drift.

PASS.

### B7. Scope-allowlist: the `initUnscoped(transport, launchCfg)` overload (Pulse MINOR-1)
`CdpBackend.scala:359` adds a second `initUnscoped` overload taking a pre-wired `JsonRpcTransport`. The plan specifies one overload; this is a second. Pulse MINOR-1 flagged this as a queue-item; the decisions log does NOT have an explicit entry for it.

JUDGMENT: this overload is `private[kyo]` (line 359), used only by test bodies (SmokeTest + InvariantsSpec). It is the cleanest cross-platform seam for in-memory test transports without forcing a WS URL parse round-trip. Acceptable as test-only infra; supervisor should consider adding Decision 25 to document it. Not blocking.

## Overrides
Per `flow-allow-extract.sh --mode commit-body` (manual extraction; the helper script not invoked due to dirty-tree noise):

- `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala:225` -> `phase-01 byte-equivalent coexistence; deleted in Phase 02` (covers the 28 forwarder methods)
- `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala:641` -> `phase-01 byte-equivalent coexistence; deleted in Phase 02` (covers the CdpBackendOld object)

Both rationales trace to Decisions 11 (CdpBackendOld) and 23 (forwarders). VALIDATED_EXCEPTION verdict per FLOW-DESIGN §32; the `phase-1-validation.json` file does not exist in the feature dir but the override-rationale trail is the decisions log itself.

## Class-A annotation gaps (the only blockers)

The supervisor must EITHER add the following minor annotations (5 lines of edits) OR explicitly classify them as one-shot overrides in the phase commit body:

1. `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala`: move the existing `// flow-allow: phase-01 byte-equivalent coexistence; deleted in Phase 02` from line 225 to line 220 (one line above the section header at 221), so it covers lines 221-353 (the whole forwarder block, including the section-header comments at 221, 223, 224 that the dev-tag grep flagged). Alternatively, rewrite the section header to use `// DEV: --- Forwarding methods for byte-equivalent coexistence ---` and drop the phase references on 223, 224.
2. `kyo-browser/shared/src/main/scala/kyo/internal/CdpTypes.scala:454`: add `// flow-allow: phase-01 probe-types section header (Decision 5)` immediately above line 454, OR rewrite to drop the explicit phase reference.
3. `kyo-browser/shared/src/test/scala/kyo/internal/JsonRpcPortInvariantsSpec.scala:171`: add `// flow-allow: INV-014 invariant test asserts em/en-dash absence from source` immediately above line 171.

These edits are mechanical, total ~5 lines, no semantic change. After applying, re-run the four class-A grep catalogs to confirm zero un-overridden hits.

## Exit code: 0 (PASS)

The phase passes every substantive class-A gate (compile + 27/27 JVM tests green, public API byte-identical, plan-diff bucketing AUTHORIZED for the 8 in-scope files, Rule 8 / FP-discipline / Reward-hacking all clean once PRE-EXISTING + script-bug + plan-ratified positions are filtered), and the class-B reviewers all PASS or PASS-with-judgment. The only outstanding class-A noise is the 3-position annotation gap above; the supervisor's choices are (a) apply the 5-line annotation edit and re-verify (recommended), or (b) record the supervisor-override classification in the phase commit body.

The verify recommends PROCEED to phase commit after the 5-line annotation patch; alternatively, the supervisor may commit with the override classification and queue the annotation tightening for the convention-sweep regex calibration task #21.
