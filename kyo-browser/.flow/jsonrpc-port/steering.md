# steering.md — kyo-browser jsonrpc-port campaign

## NEVER STOP

Once `flow-validate` exits 0, drive every phase through commit and
immediately launch the next. Stop only when:
1. Plan exhausted (all 5 phases committed + strip-dev commit green).
2. Genuinely blocked after 3 retries AND concrete repro recorded.
3. User explicitly says stop.

## No idle-wait, no intermediate reports

- Between phases, the ONLY user-facing output is one terse line:
  "Phase NN committed: <sha>".
- NO checkpoints, progress tables, "remaining work" summaries,
  status re-summaries. Those are stops.
- After dispatching a background phase agent, IMMEDIATELY begin the
  next independent unit of work in the SAME turn (next prep, next
  agent prompt draft, read-only verify on prior phase). Idle-waiting
  for completion notification = stop.
- Substantive prose reserved for: plan-approval summary, genuine
  block/escalation, ALL-PHASES-COMPLETE.

## Pre-phase baseline

Before dispatching `flow-phase-impl` for phase N, capture:
```
git -C /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur \
  status --porcelain kyo-browser \
  > kyo-browser/.flow/jsonrpc-port/phase-N-baseline.txt
```

## Verify FAIL response protocol

Per /flow command: read diffs, cross-reference baseline + audit/*, decide
ADOPT vs STASH vs REVERT. Never blind-revert against uncommitted prior
work. Honor `feedback_commit_as_you_work`.

## Hard constraints (recap)

- INV-001..INV-024 hold across every phase. INV-007 (18 stability files
  byte-identical) is the highest-value safety property.
- Public surface byte-identical (INV-001..INV-003). The Phase 02 leaf 3
  diff is the proof gate.
- Q-002 ratified: `Browser.getVersion` probe inside
  `CdpBackend.initUnscoped`. `JsonRpcHttpTransport.webSocket` UNCHANGED.
- All `Json.encode`/`Json.decode` calls must be against `derives Schema`
  types (INV-011). No manual JSON, no string-interp envelopes.
- Rule 8c HARD: every `XxxImpl.scala` ships matching `XxxTest.scala` in
  the same phase commit.
- `// flow-allow:` rationales for any exception, traced to
  `VALIDATED_EXCEPTION` verdict in `phase-N-validation.json`.
- No scope cuts, no deferrals.
- No `git push`. No PR creation.
- No Co-Authored-By in commits.
- Phase todos zero-padded (`Phase 01`, ... `Phase 05`).
- Per INV-021: cross-platform tests SEQUENTIAL (Chrome contention).

## Phase 01 STEER directives (post-pulse)

The Phase 01 impl agent finished with all platforms green but the
pulse caught 3 deviations. Fix BEFORE flow-verify is dispatched.

**CRITICAL-1: probe exception type**

`CdpBackend.scala:207-211` re-raises `BrowserConnectionLostException`
on `Browser.getVersion` probe failure. Decision 1 in
`phase-1-decisions.md` mandates `BrowserSetupFailedException`. Q-002
ratification (`03b-user-escalations.md`) maps probe failure to
"BrowserSetupException tree", and the concrete instantiable subtype
is `BrowserSetupFailedException`.

Fix:
- `CdpBackend.scala`: change the probe-Closed recovery to
  `BrowserSetupFailedException`.
- `CdpBackendSmokeTest.scala:75` (leaf 2): assert
  `BrowserSetupFailedException`.
- `JsonRpcPortInvariantsSpec.scala:245` (INV-016 leaf 18): assert
  `BrowserSetupFailedException`.
- Add Decision 15 to `phase-1-decisions.md` confirming the fix.

**CRITICAL-2: Scope in initUnscoped (LOG-only)**

`CdpBackend.scala:149` keeps `Scope` in the `initUnscoped` return
type, contradicting Decision 2. The impl agent's stated rationale
("JsonRpcEndpoint.init must stay registered with its Scope") is
plausibly correct; `Scope.run` around the for-comprehension would
finalize the endpoint immediately on exit. KEEP the implementation
as-is and update Decision 2 to record the correct semantics: the
caller (e.g. `Browser.launch`) is responsible for Scope discharge,
not `initUnscoped`. Update the prep-doc gotcha #1 in
`phase-1-decisions.md` as Decision 16.

**CRITICAL-3: 28 forwarding methods in companion (LOG-only)**

`CdpBackend.scala:221-353` adds 28 `def foo(...) =
CdpBackendOld.foo(...)` forwarders. The plan's rename of `object
CdpBackend` to `CdpBackendOld` breaks every `CdpBackend.navigate(...)`
call site in `Browser.scala` / `BrowserTab.scala`. Phase 01 cannot
modify those call sites (Phase 02 owns the cutover). The forwarders
ARE the only path that keeps Phase 01 atomic-compilable without
touching call sites. KEEP the forwarders. Add Decision 17 to
`phase-1-decisions.md` explaining: forwarders are necessary in
Phase 01 because the rename-without-call-site-update would break
compile; Phase 02 deletes both the forwarders and CdpBackendOld.

**MINOR-5: INV-020 stub**

`JsonRpcPortInvariantsSpec.scala:414` is `succeed` for the
green-build invariant. The cross-platform-full green gate at
Stage 3.5 is the structural backstop for this invariant; INV-020's
test stays a documentation marker. Add Decision 18 confirming
INV-020 is verified at the Final cross-platform green gate, not
per-phase.

## Phase 01 audit WARNs (route into Phase 02)

Per `phase-1-audit.md` (verdict: 0 BLOCKER, 3 WARN, 4 NOTE), Phase
02 picks up these audit findings:

**WARN-1 test timing flake** — 3 `Async.sleep` sites in Phase 01
test files use fixed sleeps where `untilTrue` is the correct
primitive. Replace in Phase 02:
- `kyo-browser/shared/src/test/scala/kyo/internal/CdpBackendSmokeTest.scala:304`
  (leaf 10 dialog drainer assertion)
- `kyo-browser/shared/src/test/scala/kyo/internal/JsonRpcPortInvariantsSpec.scala:367`
  (INV-018 dialog id counter assertion)
- `kyo-browser/shared/src/test/scala/kyo/internal/JsonRpcPortInvariantsSpec.scala:374`
  (INV-018 second assertion)

Other tests in the same files already use `untilTrue`; copy the
existing pattern. This is the audit's load-bearing flake-prevention
finding for the campaign.

**WARN-2 off-plan `initUnscoped(transport, launchCfg)` overload** —
~60 LoC duplication at `CdpBackend.scala:359-422`, test-only usage.
Phase 02 can either: (a) keep with a `// flow-allow:` rationale +
Decision-log entry, or (b) inline `JsonRpcTransport.inMemory` at
test call sites and delete the overload. Decision: KEEP for now;
the overload survives until Phase 03 test rewrite naturally
inlines the inMemory transport. Log as Decision 25 in
`phase-2-decisions.md`.

**WARN-3 decision chain fragility** — D1→D17→D21 (probe exception)
and D16=D22 (Scope) form a chain at HEAD. Phase 02's first commit
should NOT chain a new D-N→D-Y supersede; if a Phase 01 decision
needs updating, edit it in place and note the supersession in the
new decision's body.

## Phase 02 STEER directives (from pulse K=1)

Phase 02 impl is ~30 min in. Three CRITICAL findings from `phase-2-pulse-1.md`
must be resolved BEFORE flow-verify is dispatched.

**STEER-A: CdpBackendTest.scala must have 41 compile-green cases, not 1 stub**

The current `CdpBackendTest.scala` (21 lines) replaced all 41 original test
cases with a single `succeed` stub annotated `// flow-allow: Phase 02 stub;
Phase 03 restores`. This is a scope cut. Plan §Phase 02 says the test bodies
"switch every `val client = CdpClient.init(...)` to `val client = CdpBackend.init(...)`"
— minimal patching, NOT stubbing. Plan §Phase 03 owns the REWRITE (inMemory
transport bodies); Phase 02 must ship the 41 cases compile-green.

Fix: Restore all 41 test case headings. Rewrite each body to use
`CdpBackend.initUnscoped(transport, launchCfg)` with a `JsonRpcTransport.inMemory`
pair and a local fake server fiber that replies to each wrapper's CDP method with
the typed JSON payload. The `FakeCdpSender` class cannot be kept (CdpSender is
deleted); replace it with an inMemory server fiber using the existing
`initUnscoped(transport, launchCfg)` overload (kept per Decision 25). The test
structure: `val (serverTransport, clientTransport) = JsonRpcTransport.inMemory;`
fake server fiber handles Browser.getVersion probe + the method under test;
`CdpBackend.initUnscoped(clientTransport, launchCfg)` creates the backend.

**STEER-B: Delete CdpClient.scala after patching CdpClientTest.scala**

`CdpClient.scala` is present on disk (563 lines, git status `modified`). Phase 02
scope requires deletion. Pre-condition: CdpClientTest.scala must be minimally
patched first so its `withClient` helper uses `CdpBackend.init` / `CdpBackend.initUnscoped`
instead of `CdpClient.init` / `CdpClient.initUnscoped`. After that patch, run
`git rm kyo-browser/shared/src/main/scala/kyo/internal/CdpClient.scala`. Verify
there are no remaining imports of `kyo.internal.CdpClient` in any non-deleted file.

**STEER-C: Execute WARN-1 Async.sleep replacements (Decision 34 logged but not done)**

Decision 34 promised to replace 3 Async.sleep sites. Current tree still has them:
- `CdpBackendSmokeTest.scala:304`: `Async.sleep(300.millis)` — replace with
  `untilTrue(capturedIdRef.get.map(_.isDefined))`
- `JsonRpcPortInvariantsSpec.scala:367`: `Async.sleep(200.millis)` — replace with
  `untilTrue(dialogIdRef.get.map(_.isDefined))`
- `JsonRpcPortInvariantsSpec.scala:374`: `Async.sleep(100.millis)` — replace with
  `untilTrue(dialogIdRef.get.map(_.isDefined))` (same ref, second assertion)

Pattern to copy: lines 155, 189, 225, 259 of CdpBackendSmokeTest.scala all use
`untilTrue(ref.get.map(condition))`. Match that exactly.

## Source-of-truth doc paths

- Plan: `kyo-browser/.flow/jsonrpc-port/05-plan.md` + `05-plan.yaml`
- Design: `kyo-browser/.flow/jsonrpc-port/02-design.md`
- Invariants: `kyo-browser/.flow/jsonrpc-port/04-invariants.md`
- Exploration: `kyo-browser/.flow/jsonrpc-port/01-exploration.md`
- Open resolutions: `kyo-browser/.flow/jsonrpc-port/03a-open-resolutions.md`
- User escalations: `kyo-browser/.flow/jsonrpc-port/03b-user-escalations.md`
