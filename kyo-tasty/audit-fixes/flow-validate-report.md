# flow-validate report -- run-id: stage1-validate-3

**Plan**: `kyo-tasty/audit-fixes/05-plan.md`
**YAML**: `kyo-tasty/audit-fixes/05-plan.yaml`
**Invariants**: `kyo-tasty/audit-fixes/04-invariants.md`

## Verdict

**PASS** (exit 0)

All gates green. Plan is ready for `flow-impl`.

---

## Fixes applied in this run

### Fix 1: byte-range false positive (test_count_specificity gate)

**Before (line 4143):**
```
bytes 100-200 raise a SIGBUS-equivalent
```

**After:**
```
the next 100 bytes (starting at offset 100) raise a SIGBUS-equivalent
```

The hyphenated pair `100-200` matched the RANGE detector regex
`[0-9]+[[:space:]]*(-|to|or)[[:space:]]*[0-9]+`. Reworded to avoid any
numeric pair connected by a dash or `to`.

### Fix 2: code_detail per-phase counter (Path A)

**Script patched:** `/Users/fwbrasil/.claude/commands/flow-validate-code-detail.sh`

The awk pattern for phase-section detection was `^# Phase[ :]` (level-1
heading only). This plan uses `## Phase NN:` (level-2 headings) per the
flow-plan.md contract. Patched to `^#{1,2} Phase[ :]` to accept either
heading level, keeping the script backward-compatible with existing plans
that use level-1 headings (confirmed by grepping other worktree plans).

Additionally, YAML phase `id` fields for phases 18-31 were misaligned with
the markdown heading labels. The YAML used sequential integers (18, 19, ...,
31) while the markdown uses `18a`-`18e`, `19`-`27`. The script uses the YAML
`id` to search markdown sections, so the mismatch caused every sub-phase
(`18a`-`18e`) and subsequent phases to report zero code blocks. Fixed by
renaming YAML ids and updating all `depends_on` cross-references to match
the markdown labels. No phase content changed.

Path A chosen because the script is only called by this flow and `flow-validate.md`
does not share it with any other workflow using level-1 headings exclusively.
(The only other live plans found DO use `# Phase` level-1, so the backward
compatibility is maintained by the `^#{1,2}` pattern.)

---

## Gate results

| Gate | Result | Detail |
|---|---|---|
| rewardhack | PASS | fail_count=0, override_count=21 |
| vague | PASS | fail_count=0, override_count=1 |
| counts | PASS | fail_count=0, override_count=0 |
| acceptance | PASS | fail_count=0, override_count=0 |
| coverage_matrix | PASS | all Deliverable rows have concrete signals |
| cross_phase_invariants | PASS | references=0 anonymous=0 |
| test_count_specificity | PASS | declarations=174 ok=61 range=0 handwave=0 unclear=113 |
| open_question_count | PASS | open-questions=0 |
| no_nested_subagents | PASS | no matching language found |
| scope_coherence | PASS | all paths within kyo-tasty / kyo-tasty-examples scope |
| init_order | PASS | no shared_state entries |
| invariant_coverage | PASS | all INV-NNN ids covered by test leaves |
| convention_sweep | PASS | all phases declare required entries |
| code_detail | PASS | expected_changes=57 scala-blocks=75 per_phase_bad=0 |
| test_substance | PASS | leaves=159 ok=159 bad=0 |

**Exit code: 0**
