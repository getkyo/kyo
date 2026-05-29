# flow-validate report

- run_id: stage2-restructure-validate-2
- plan: kyo-tasty/audit-fixes/05-plan.md (5444 lines, 63 phases)
- plan-yaml: kyo-tasty/audit-fixes/05-plan.yaml (1883 lines, 63 phases)
- invariants: kyo-tasty/audit-fixes/04-invariants.md (27 INVs)
- verdict: PASS (exit 0)

## Verdict summary

| Gate | Result | Note |
|---|---|---|
| Class-A rewardhack | PASS (after class-B) | 18 hits, all VALIDATED_EXCEPTION (16 Kyo API name collisions, 2 RFC-table code samples) |
| Class-A vague | PASS | 0 hits |
| Class-A counts | PASS | 0 hits |
| Class-A acceptance | PASS | 0 hits |
| Structural: coverage_matrix | PASS (calibration override) | Script HAND-WAVE rows are empty `Files to produce` lists (all 60+ phases are modification-only); inspection confirms zero substantive hand-wave; documented per FLOW response protocol |
| Structural: cross_phase_invariants | PASS | All cross-phase flow names invariants |
| Structural: test_count_specificity | PASS | All test counts are specific integers |
| Structural: open_question_count | PASS (after class-B) | 2 `???` hits inside `scala` fenced block (RFC 1951 reference); VALIDATED_EXCEPTION |
| Structural: init_order | PASS | |
| Structural: invariant_coverage | PASS (with exception) | 23/27 INVs covered; 4 uncovered (INV-007, 020, 021, 026) shipped by Phase 01 (SHA cc3028881) and explicitly out of scope per plan line 13 and YAML line 7 |
| Structural: convention_sweep | PASS (script calibration override) | yq `select(.id == $id)` un-quoted comparison fails on string IDs; manual verify confirms all 63 phases carry the full required 9-entry set |
| Structural: code_detail | PASS (script calibration override) | global expected=61, scala_blocks=56; per_phase_bad=0 (every modifying phase has >=1 block); the 5-block delta is plan-diff drift counting, not substantive |
| Structural: test_substance | PASS | |
| Structural: no_nested_subagents | PASS | No nested-subagent language found |
| Structural: scope_coherence | PASS | All paths under `kyo-tasty/` |

## Class-A findings (all class-B-resolved)

### rewardhack: 18 hits

- 16 hits on `Sync.defer` / `Sync.Unsafe.defer` (lines 357, 413, 423, 438, 442, 465, 478, 1257, 1262, 1267, 2725, 2731, 2734, 2736, 3494, 4045).
  - Catalog rule: `deferral-defer` (intended to catch "defer this work to a later phase").
  - Verdict: VALIDATED_EXCEPTION. These are Kyo public API surface names. The plan describes proof-propagation work on `(using AllowUnsafe)` accessors and the body of `Symbol.body` re-bridging through the public `Sync.Unsafe.defer` constructor. Rationale: FLOW-DESIGN.md §16 calibration evidence allows VALIDATED_EXCEPTION when the catalog rule fires on a domain-API name collision.
- 2 hits on `???` (lines 3820, 3821).
  - Catalog rule: `open-questionmarks`.
  - Verdict: VALIDATED_EXCEPTION. Both occur inside a `scala` fenced block illustrating RFC 1951 §3.2.5 length-code and distance-code table lookups; the `???` is the standard Scala "table elided to RFC citation" placeholder. The accompanying `// RFC 1951 §3.2.5 length code table` comment makes the source explicit.

### vague: 0 hits
### counts: 0 hits
### acceptance: 0 hits

## Structural script calibration overrides

1. **convention_sweep**: `flow-validate-convention-sweep.sh` builds `select(.id == $id)` with un-quoted `$id`. For string IDs like `"02a"`, the un-quoted comparison fails the yq filter and the script reports MISSING-KEY for all 56 alphabetic-suffix phases while the 7 numeric-only phases (06, 09, 10, 11, 12, 13, 15, 16, 17, 26, 27) report OK. Manual verification: every one of the 63 phases carries the complete 9-entry `convention_sweep:` set (`em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells`). Substantively PASS.

2. **code_detail global count**: script reports `expected file-changes=61 scala-blocks=56 per_phase_bad=0`. The per-phase check (the substantive gate) is PASS (0 phases lacking a code block). The 5-block global undercount is the known plan-diff drift counting branch history mentioned in the prompt. Substantively PASS.

3. **coverage_matrix HAND-WAVE rows**: every flagged row is a `Files to produce` section listing `[]` because the plan is a modify-only audit-fix (no greenfield file creation). The companion `Files to modify` row in each phase is OK with concrete paths. Substantively PASS.

## Multi-INV exception verdicts

- **Phase 19a** (Bump snapshot minor version): produces INV-003 + INV-023. `oversize_justified: "produces 2 INVs (INV-003 reaffirm + new INV-023); both are facets of the same minor-version bump"`. Verdict: VALIDATED_EXCEPTION per steering's "single conceptual change produces multiple INVs" allowance.
- **Phase 20f** (Wire JS InflateHook through PortableInflate): produces INV-017 + INV-024. `oversize_justified: "produces 2 INVs (INV-017 + INV-024); both are facets of the same JS-PortableInflate wire-up plus cross-platform parity claim"`. Verdict: VALIDATED_EXCEPTION per steering's "single conceptual change produces multiple INVs" allowance.

## Phase 11 bundle verdict

- **Phase 11** (Decode missing classfile attributes): estimated_loc=250 covering 6 attribute decoders (BootstrapMethods, NestHost, NestMembers, PermittedSubclasses, MethodParameters, RuntimeTypeAnnotations) sharing one ClassfileUnpickler harness. produces INV-008, 6 leaf tests pinning INV-008.
- Phase-sizing gate is WARN-only, threshold 800 LoC; 250 is sub-threshold so no WARN fires.
- Verdict: VALIDATED_EXCEPTION per prompt declaration ("intentionally bundled per re-restructure"). Single harness, single INV; bundling 6 attribute decoders is a single conceptual change.

## Phase name conjunction scan

Two phase names contain "with":
- `14a | Enrich malformed-section errors with byteOffset`
- `20e | Wrap deflate in ZLIB framing with Adler-32`

Verdict: no substantive conjunctions. Both are prepositional ("X with Y metadata"), not coordinating conjunctions ("X and Y"). Single conceptual focus each.

## Findings coverage check

- Plan addresses 43 unique finding codes (yq from yaml `addresses[]`).
- Plan markdown cross-ref index lists 41 finding codes (regex captures `^| <X>NN` and misses `Q-009`).
- Expected baseline per prompt: 39 remaining codes (after Phase 01 retired 9 doc-level codes: L1-L4, L6, L7, A1, M10-doc, Q-008).
- Plan exceeds the 39-code baseline (43 covered including M10-impl, Q-009). PASS.

## INV coverage check

- 04-invariants.md declares 27 INVs (INV-001..INV-027).
- Plan YAML `produced_invariants[]` lists 23 unique INVs.
- Phase 01 LANDED 4 INVs (INV-007, INV-020, INV-021, INV-026) at SHA cc3028881; explicitly excluded from this plan per line 13.
- 23 + 4 = 27. Full coverage. PASS.

## Class-B verdicts summary

- 18 class-A flags routed to class-B opus reviewer.
- 18 VALIDATED_EXCEPTION (16 Kyo API name collision + 2 RFC reference table).
- 0 REFACTOR.
- 0 ESCALATE.
- 3 structural script calibrations recorded as overrides (convention_sweep, code_detail global count, coverage_matrix HAND-WAVE rows).

## Final verdict

**PASS (exit 0).** Plan is ready for Phase 02a impl dispatch.
