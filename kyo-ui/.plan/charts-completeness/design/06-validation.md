# 06 — Plan validation: kyo-ui chart feature-completeness (14 gaps)

**Verdict: PASS** (all 8 gates PASS). No remediation required; the plan is ready for review.

Validator: `flow-validate` subskill. Inputs read: `control/steering.md`, `design/00-guides.md`,
`design/02-public-api.md`, `design/02-design.md` (via plan/invariants cross-refs), `design/04-invariants.md`,
`design/05-plan.md`, `design/05-plan.yaml`. YAML re-parsed; dependency graph re-derived; paths, leaves,
severity-language, and new-feature leakage scanned programmatically. No production source modified, no commit.

---

## Gate-by-gate

### Gate 1 — NO SCOPE CUTS (all 14 gaps a concrete phase deliverable) — **PASS**

The `coverage:` map in `05-plan.yaml` (lines 492-506) re-parses to exactly 14 entries, one GAP-ID -> one
phase, matching the markdown coverage table (05-plan.md:352-367) and steering §1. GAP-ID -> phase map:

| GAP | Phase | Concrete deliverable (leaf) |
|-----|-------|------------------------------|
| GAP-ERRORBAR-BANDCENTER | P3 | L21 (band-center px = apply + bandwidth/2) |
| GAP-COLOR-GROUPEDBAR | P4 | L1 + L10 (resolvePalette route) |
| GAP-COLOR-AREA-SIMPLE | P5 | L4 + L9 (per-series split + colorScale) |
| GAP-COLOR-TEXT | P6 | L2 (spec param thread-through) |
| GAP-COLOR-ERRORBAR | P6 | L3 (one stroke per row, 3 sub-shapes) |
| GAP-TRANS-BAR-CHANNELS | P7 | L18 (animated bar opacity/label/tooltip) |
| GAP-YAXIS-ROTATION | P8 | L14 + L15 (rotation + anchor) |
| GAP-THEME-FONT | P8 | L16 (font on ticks/titles/legend) |
| GAP-HIGHLIGHT-COVERAGE | P9 | L20 (line/area/text/errorBar highlight) |
| GAP-LEGEND-MARGIN-TEXT-ERRORBAR | P10 | L22 (hasLegend reservation) |
| GAP-RIGHTY-SCALE | P11 | L11 + L19 (yScaleRight resolution) |
| GAP-RIGHTY-GRID | P11 | L13 (right gridlines, left-wins) |
| GAP-AXISCONFIG-SIDE | P12 | L24 (remove side + setters + Side enum) |
| GAP-LABELALLBANDS | P12 | L23 (remove dead field) |

**14/14 covered.** None deferred, none vague, none "revisit later". Each carries a concrete reproduce-first
leaf with a concrete rendered/API-shape assertion. P1, P2 (enabling refactors) and P13 (final gate) close no
gap directly, correctly declared `gaps_closed: []`, and every gap they unblock is itself mapped above.

### Gate 2 — NO INFERRED PRIORITIES (technical-dependency ordering, real & acyclic) — **PASS**

- **No priority language in load-bearing positions.** Grep of all titles/dependency-rationales/ordering text
  in both `05-plan.yaml` and `05-plan.md` finds zero priority/importance/severity term used to order or to
  justify a cut. `severity` appears only as the neutral `severity_attr:` field (taken verbatim from the gap
  file) and in the explicit disclaimer lines (05-plan.md:11-15, 369-371; yaml:3-4) stating it is "never used
  to order or to justify a cut". The "Unplaceable gaps" section (05-plan.md:383-388) confirms no gap needed a
  priority inference to be positioned, and dependency-equal gaps (P3/P4, the two P12 removals) were grouped by
  SHARED CODE LOCUS, not importance.
- **Dependency graph acyclic.** Re-derived the `precedes` edges: P1->P7, P2->P8, P3->P6, P5->P9, P6->P9,
  P6->P10, P12->P13. Kahn topo-sort visits all 13 nodes (no cycle). Every edge runs forward in the listed
  phase order (predecessor precedes consumer in the file).
- **Every stated dependency is REAL (predecessor produces something the consumer consumes):**
  - P1->P7: P7 calls `applyBarChannels`, the helper P1 extracts. Real.
  - P2->P8: P8 routes y tick labels through `tickLabel`/`withFont`/`toSvgAnchor`, the helpers P2 hoists. Real.
  - P5->P9: P9 tags the per-series area `<path>` elements P5 produces. Real.
  - P6->P9: P9 reuses the `spec` param P6 adds to `lowerText`/`lowerErrorBar`. Real.
  - P6->P10: P10's `hasLegend` reserves the strip precisely because P6 makes text/errorBar render a meaningful
    legend (R-6 shared keep-legend decision). Real semantic dependency.
  - P12->P13: P13's clean-build + doctest gate must run after P12's ~84-site call-site rewrite for the green to
    be real. Real.
  - P3->P6: the plan itself states this is an EDIT-ORDERING convenience on the shared `lowerErrorBar` signature,
    "not a logical dependency" (yaml:95-96, md:101-104). Disclosed honestly as a conflict-avoidance ordering,
    not a fabricated logical dependency. Acceptable: it is labeled as such and does not invent a false producer.

### Gate 3 — REPRODUCE-BEFORE-FIX (24 leaves covered; L8 byte-identity held) — **PASS**

- All 24 invariant leaves L1..L24 appear in `leaf_coverage` (yaml:511-535); none missing.
- Every gap-closing phase lists its failing leaf test(s) with `reproduce_before_fix: true` (P3-P12), each
  citing the actual symptom (wrong fill, left-edge px, missing transform, overlapping legend, missing override).
  Refactor phases P1/P2 correctly use `reproduce_before_fix: false` with existing-test regression guards (no new
  behavior). The 6 CO-PINs (L5/L6/L7/L8/L12/L17) are existing-behavior guards, correctly not reproduce-first.
- **L8 (COLOR-ABSENT byte-identity / INV-004 golden co-pin) held across EVERY color phase.** Re-derived: P4,
  P5, P6 each carry L8 in their leaf set, and each phase body re-asserts the Absent arm is the existing
  by-index `themePalette`/`DefaultPalette` code verbatim with the INV-004 golden unchanged. The
  `byte_identity_copins_held_every_phase` block (yaml:18-22) pins L8 explicitly. The fix never weakens the
  Maybe-encoded Absent fallback.

### Gate 4 — CROSS-PLATFORM PLACEMENT — **PASS**

- Every `files_modified` path is `kyo-ui/shared/src/main/...` (UI.scala, internal/ChartLower.scala,
  internal/Scale.scala) or `kyo-ui/README.md` (P13, public-doc only). Every `tests` path is
  `kyo-ui/shared/src/test/...`. Programmatic scan finds zero jvm/js/native-only placement; no per-source
  exception is needed.
- A FINAL cross-platform-full gate exists: P13 `verification_strategy: cross-platform-full`, command
  `sbt 'clean' 'kyo-ui/doctest' 'kyo-ui/test' 'kyo-uiJS/test' 'kyo-uiNative/test'`, with the explicit
  clean-build/"no-Compiling-log-is-RED" guard against the stale `.sjsir`/`.tasty` false green (00-guides Trap 6)
  and the correct project ids (`kyo-ui` not `kyo-uiJVM`).

### Gate 5 — PUBLIC-DECISION HANDLING — **PASS**

- The 3 forks are baked as recommended defaults in `meta.public_decision_defaults` (yaml:14-17):
  Q-1 = candidate B (`yScaleRight` + `yScaleRightOverride`), Q-2 = REMOVE labelAllBands, Q-3 = REMOVE side.
- Each affected phase states the alternative's impact so the plan is reviewable against either answer:
  P11 (yaml:382-387, md:275-279) gives the candidate-A removal path (drop L19, re-express L11, §6 re-audit);
  P12 (yaml:438-448, md:313-320) gives Q-2 WIRE and Q-3 KEEP-AS-MARKER / WIRE paths; P13 (yaml:485-488,
  md:344-346) adjusts the README example set per Q-1/Q-3.
- The ~84-site side-removal churn is explicitly scoped into P12: ~42 demos + ~36 chart tests + the two named
  edits (ChartSpecTest:90, ChartAxisTest:1068), plus README lines 1088/1168/1169/1186/1187/1291 in P13, with
  the doctest re-run as P13's guard. The R-2 disambiguation (do NOT touch `.legend(_.top)` / `.margins(_.left)`)
  is called out in both P12 and P13.

### Gate 6 — NEW-FEATURE BOUNDARY — **PASS**

- No DEFAULT-path phase introduces a new mark type, scale kind, channel, or capability. Each makes an EXISTING
  knob consistent: colorScale routing through the existing `resolvePalette`; band-center geometry; existing
  `rotateTicks`/`anchor`/`theme.font` knobs read on the y axis; existing `.yAxisRight(_.grid)` honored; existing
  `axis = Axis.Right` binding given a scale override that mirrors the existing left-axis resolution.
- Every "new feature" mention is correctly fenced inside the alternative branches (Q-2 WIRE band-thinning,
  Q-3 WIRE positional placement) and explicitly labeled "a new feature" requiring a §6 re-audit, exactly the
  out-of-scope items steering §3 excludes. The recommended defaults do NOT cross that boundary: labelAllBands is
  REMOVED (not wired to thinning), side is REMOVED (not built into free placement). yScaleRight (candidate B) is
  a one-field + one-method completion mirroring `yScale` 1:1, nesting under existing owners (public-api §1
  budget 0 preserved), not a new capability kind.

### Gate 7 — INTERNAL SOUNDNESS — **PASS**

- `05-plan.yaml` re-parses cleanly (PyYAML safe_load). `meta.total_phases` (13) == actual phase count (13).
- Every phase carries `estimated_loc`, `estimated_wall_clock_min`, and `verification_command`.
- Shared-helper-introducing phases precede their consumers: applyBarChannels (P1) before P7; tickLabel/withFont
  (P2) before P8; buildSimpleAreaPath (P5) before P9; resolveYScale is introduced WITHIN P11 before the
  right-grid arm of the same phase (scale resolved, then grid reads its tick pixels). All confirmed.

---

## Summary (6 lines)

1. Gates: G1 PASS, G2 PASS, G3 PASS, G4 PASS, G5 PASS, G6 PASS, G7 PASS — and the soundness sub-checks all PASS.
2. GAP-ID -> phase coverage: **14/14** (P3,P4,P5,P6,P6,P7,P8,P8,P9,P10,P11,P11,P12,P12); none deferred/vague.
3. Priority-language leakage: **none** — `severity` appears only as the neutral `severity_attr` + disclaimers.
4. New-feature leakage: **none on default paths**; all "new feature" text is fenced in the WIRE alternatives.
5. Dependency graph: **acyclic** (Kahn visits all 13 nodes), all edges forward, every dependency real (P3->P6
   honestly labeled an edit-ordering convenience, not a fabricated logical dep).
6. Overall verdict: **PASS** — exit 0, no loop-back to flow-plan required.
