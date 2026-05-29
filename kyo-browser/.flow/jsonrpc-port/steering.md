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

## Source-of-truth doc paths

- Plan: `kyo-browser/.flow/jsonrpc-port/05-plan.md` + `05-plan.yaml`
- Design: `kyo-browser/.flow/jsonrpc-port/02-design.md`
- Invariants: `kyo-browser/.flow/jsonrpc-port/04-invariants.md`
- Exploration: `kyo-browser/.flow/jsonrpc-port/01-exploration.md`
- Open resolutions: `kyo-browser/.flow/jsonrpc-port/03a-open-resolutions.md`
- User escalations: `kyo-browser/.flow/jsonrpc-port/03b-user-escalations.md`
