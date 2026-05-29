# 06-validation.md

**Plan**: `kyo-browser/.flow/jsonrpc-port/05-plan.md` (+ `05-plan.yaml`)
**Run ID**: `2026-05-29T11-06-validate-2`
**Verdict**: **PASS**
**Exit code**: 0

---

## Summary

| Gate                              | Result   | Notes |
|-----------------------------------|----------|-------|
| Class-A: rewardhack               | 11 hits  | All 11 review to VALIDATED_EXCEPTION (`Sync.defer` is the canonical Kyo effect-suspension API per `feedback_cio_lift_defer.md`; not the "defer this for later" reward-hack pattern; covered by the file-level `<!-- flow-allow: ... -->` block at `05-plan.md:8-12`). |
| Class-A: vague                    | 0 hits   | PASS (the 3 prior `etc.` hits at L824, L1009, L1472 have been replaced with explicit enumerations). |
| Class-A: counts                   | 0 hits   | PASS |
| Class-A: acceptance               | 0 hits   | PASS |
| Class-A: em-dash / en-dash        | 0 hits   | PASS (no U+2013 / U+2014). |
| Class-A: Co-Authored-By / git push | 0 hits  | PASS (only INV-022 / INV-023 prohibition-clauses; no usage). |
| Structural: coverage matrix       | PASS     | 8/10 sections OK; 2 sections show `files_produced=0` (Phases 03 + 04/05 are pure-modification phases, expected per the plan's design). |
| Structural: cross-phase invariants | PASS    | 0 anonymous data-flow rows; all named invariants. |
| Structural: test-count specificity | PASS    | 60/89 OK declarations; 28 UNCLEAR are `Given:` / `When:` shell lines (not declarations); 0 RANGE / HANDWAVE rows. |
| Structural: open-question count   | PASS     | 0 unresolved tokens. |
| Structural: init-order            | PASS     | All 7 shared-state entries declare `init_site` + `observable_by`. |
| Structural: invariant coverage    | PASS     | All 24 INV-NNN referenced by leaves in `05-plan.yaml`. |
| Structural: convention sweep      | PASS     | All 5 phases declare full sweep keys. |
| Structural: code detail (global)  | PASS     | 22 fenced ```scala blocks vs 22 produced+modified IMPL files. Global parity satisfied. No TEST-CODE-FOUND. |
| Structural: code detail (per-phase) | PASS (script false-positive) | The per-phase script reports phases 1, 2, 4, 5 with zero scala blocks, but substantive review confirms each phase carries the expected blocks (Phase 01: 2 blocks; Phase 02: 15 blocks; Phase 04: 2 blocks; Phase 05: 3 blocks). The script's awk regex `^# Phase[ :]*<id>([^0-9]|$)` cannot match the zero-padded `## Phase 01:` / `## Phase 02:` form used in the plan markdown when the YAML uses bare integer IDs (`1`, `2`). VALIDATED_EXCEPTION: known tooling limitation, not a plan defect. See class-B verdict below. |
| Structural: test substance        | PASS     | 49/49 leaves OK. The prior Phase-02 leaf 3 `then: "empty diff"` (9 chars) has been expanded to `"diff of public-symbol grep output between pre-port-tag and HEAD is empty (zero added, zero removed, zero context lines)"` (130 chars). |

## Remediation of prior FAIL findings

The prior `06-validation.md` (run-id `2026-05-29T10-06-validate-1`) flagged
three issues. Each is verified resolved:

1. **code_detail (global count, 12 vs 22 scala blocks; stray test-class fence)**
   - Verified: 22 fenced ```scala blocks now present in `05-plan.md`; equals
     the 22 produced+modified IMPL files counted from `05-plan.yaml`. The
     YAML `before:` / `after:` snippets have been lifted into markdown
     fenced ```scala blocks under the matching `### Files to produce` /
     `### Files to modify` headings (Phase 01: 2 blocks; Phase 02: 15
     blocks; Phase 04: 2 blocks; Phase 05: 3 blocks).
   - Verified: zero TEST-CODE-FOUND matches. The prior stray
     `class CdpBackendLifecycleTest extends BrowserTest:` fence at the
     old line ~1041 has been replaced with the prose pointer at L1106
     ("Per the code-in-plan contract, no fenced code block for the test
     class is given here; bodies live in the rewritten test file at
     impl time."). No `extends (AsyncFreeSpec|AnyFreeSpec|...|BrowserTest|FutureTest|KyoTest)`
     occurrence anywhere in the plan.

2. **test_substance (Phase-02 leaf 3 `then: "empty diff"` 9 chars)**
   - Verified: the leaf now reads `then: "diff of public-symbol grep
     output between pre-port-tag and HEAD is empty (zero added, zero
     removed, zero context lines)"` (130 chars; threshold 10). 49/49
     leaves pass the substance check.

3. **vague (3 `etc.` hits at L824, L1009, L1472)**
   - Verified: the class-A vague catalog reports 0 hits.
     `grep -nE "(^|[^A-Za-z])etc\.([^A-Za-z]|$)" 05-plan.md` returns no
     matches.

## Class-A findings

### rewardhack (11 hits — all VALIDATED_EXCEPTION)

Hits at `05-plan.md:8, 10, 11, 259, 271, 281, 292, 568, 639, 1014`. All
are references to the Kyo `Sync.defer(...)` effect-suspension API or the
file-level `flow-allow` override comment itself.

- L8, L10, L11: the file-level `<!-- flow-allow: ... -->` block declaring
  the override.
- L259, L271, L281, L292: code-block instances of
  `Sync.defer(JsonRpcMethod.notification[...](...))` in the Phase 01
  CDP notification-handler refactor.
- L568, L639, L1014: text references to INV-013 ("side effects in
  `Sync.defer`") in tests/invariants ledgers.

**Class-B verdict (per-hit): VALIDATED_EXCEPTION.**
**Rationale**: `Sync.defer` is the public Kyo API name for the
effect-suspension boundary (cited authority: user memory
`feedback_cio_lift_defer.md` — "`defer` for plain values" / "`deferLift`
for effect-producers"). The catalog's `deferral-defer` regex fires on
the substring `defer`, which the API uses verbatim. The file-level
`<!-- flow-allow: ... -->` comment at L8-12 documents the override.
**No edit required.**

### vague (0 hits) - PASS

### counts (0 hits) - PASS

### acceptance (0 hits) - PASS

## Class-B verdict on code_detail per-phase script

**Flag**: `code-detail/per-phase`
**Verdict**: **VALIDATED_EXCEPTION**
**Rationale**: The `flow-validate-code-detail.sh` per-phase scanner uses
the awk regex
`^# Phase[ :]*<pid>([^0-9]|$)` where `<pid>` comes from
`yq -r '.phases[].id'` of the YAML (bare integers: `1`, `2`, `3`, `4`,
`5`). The plan markdown follows the campaign convention of zero-padded
headings (`## Phase 01: ...`, `## Phase 02: ...`, etc.). The awk regex
cannot match `## Phase 01:` against `1([^0-9]|$)` because the digit
after `Phase ` is `0`, not `1`. Result: the per-phase counter never
sets `inphase=1` for any phase whose header is zero-padded; every such
phase that has impl changes is reported `0 scala blocks` regardless of
its actual content.

Substantive review confirms each impl-changing phase has the expected
scala blocks adjacent to its file lists (verified by
`awk '/^## Phase 0X/,/^## Phase 0(X+1)/' 05-plan.md | grep -c '^```scala'`):

| Phase | YAML id | Markdown heading       | Impl changes | Scala blocks in section |
|-------|---------|------------------------|--------------|-------------------------|
| 1     | `1`     | `## Phase 01: ...`     | 2            | 2                       |
| 2     | `2`     | `## Phase 02: ...`     | 15           | 15                      |
| 3     | `3`     | `## Phase 03: ...`     | 0            | 0 (test-only phase, OK) |
| 4     | `4`     | `## Phase 04: ...`     | 2            | 2                       |
| 5     | `5`     | `## Phase 05: ...`     | 3            | 3                       |

Substance is satisfied; the gate is a script-tooling regex limitation,
not a plan defect. This is the same VALIDATED_EXCEPTION class the prior
FAIL report should have applied (Decision #32 — validate-before-annotate
gate applies to structural-script false-positives the same as to
class-A regex false-positives).

**Follow-up**: track-2 work item to teach
`flow-validate-code-detail.sh` to normalize zero-padded phase IDs (e.g.,
also match `printf '%02d' <pid>` as an alternative). Not blocking this
plan; recorded for the FLOW-SWEEP backlog.

## Other verifications (per supervisor checklist)

| Check                                                                   | Result |
|-------------------------------------------------------------------------|--------|
| Every Q-NNN in `02-design.md` is non-open; Q-002 RATIFIED              | PASS   |
| Every INV-NNN in `04-invariants.md` has produce+consume                 | PASS   |
| Each phase declares `platforms: [jvm, js, native]`                      | PASS (5/5) |
| Each phase declares `verification_command` exercising all 3 platforms   | PASS (5/5) |
| Rule 8c: every `shared/src/main/...` file in `files_produced` has matching `XxxTest.scala` same phase | PASS (Phase 01 produces `CdpBackend.scala` + same-phase `CdpBackendSmokeTest.scala`; Phases 02-05 produce no new `src/main` files) |
| Test-count math: 1308 - 15 (CdpClientTest delete) - 7 (CdpClientDecoderTest delete) - 10 (CdpClientLifecycleTest rename-shrink) = 1276, net -32 | PASS |
| `Browser.getVersion` probe wired in Phase 01 (Q-002 option b)           | PASS |
| Public surface byte-identical (`kyo.Browser`, `kyo.BrowserException`, `kyo.BrowserTab`, `kyo.BrowserSnapshot`) | PASS |
| No `Co-Authored-By` in plan                                              | PASS |
| No `git push` in plan                                                    | PASS |
| No em-dashes / en-dashes in plan                                         | PASS |
| No nested subagents implied                                              | PASS |
| Scope coherence (kyo-browser only; no silent scope-creep)                | PASS |

## Overrides recorded

| Flag                             | Override class          | Citation                                                                 |
|----------------------------------|-------------------------|--------------------------------------------------------------------------|
| rewardhack `Sync.defer` ×11      | VALIDATED_EXCEPTION     | `feedback_cio_lift_defer.md`; `Sync.defer` is Kyo public-API name        |
| code_detail per-phase 1,2,4,5    | VALIDATED_EXCEPTION     | Tooling regex cannot normalize zero-padded markdown phase IDs vs YAML bare-int IDs; substance verified by direct awk inspection |

## Sidecar JSON

```json
{
  "plan": "kyo-browser/.flow/jsonrpc-port/05-plan.md",
  "run_id": "2026-05-29T11-06-validate-2",
  "class_a": {
    "rewardhack": {"fail_count": 0, "override_count": 11, "matches": ["L8","L10","L11","L259","L271","L281","L292","L568","L639","L1014"]},
    "vague":      {"fail_count": 0, "matches": []},
    "counts":     {"fail_count": 0, "matches": []},
    "acceptance": {"fail_count": 0, "matches": []}
  },
  "class_b": [
    {"flag_id": "rewardhack-Sync.defer-all", "verdict": "VALIDATED_EXCEPTION", "rationale": "Sync.defer is the Kyo effect-suspension API (feedback_cio_lift_defer.md); file-level flow-allow at 05-plan.md:8-12 documents the override"},
    {"flag_id": "code-detail/per-phase",     "verdict": "VALIDATED_EXCEPTION", "rationale": "flow-validate-code-detail.sh per-phase awk regex cannot match zero-padded markdown headings (## Phase 01) against bare-int YAML IDs (1); substance verified directly (Phase 01: 2 blocks, Phase 02: 15, Phase 04: 2, Phase 05: 3)"}
  ],
  "structural": {
    "coverage_matrix":          "PASS",
    "cross_phase_invariants":   "PASS",
    "test_count_specificity":   "PASS",
    "open_question_count":      0,
    "no_nested_subagents":      "PASS",
    "scope_coherence":          "PASS",
    "code_detail":              "PASS",
    "test_substance":           "PASS"
  },
  "exit_code": 0
}
```

## Notes

- The three prior-FAIL findings are all remediated and re-verified:
  global scala-block count parity (22 vs 22), removal of the stray
  test-class fence (zero TEST-CODE-FOUND matches), expansion of the
  Phase-02 leaf 3 `then` clause from 9 chars to 130 chars, and zero
  remaining `etc.` tokens.
- The 11 rewardhack hits are all on the `Sync.defer` Kyo
  effect-suspension API token and are covered by the file-level
  `<!-- flow-allow: ... -->` block at `05-plan.md:8-12` and by the
  per-task VALIDATED_EXCEPTION ruling.
- The per-phase code_detail script reports false-positive PHASE-FAILs
  on phases 1, 2, 4, 5 due to a known regex limitation around
  zero-padded markdown phase headings vs bare-int YAML IDs. Substantive
  re-inspection confirms full parity; recorded as VALIDATED_EXCEPTION
  and a tooling follow-up.
- The plan is now ready for `flow-impl` to consume. Exit code 0.
