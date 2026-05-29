# Phase 1 decisions log

Phase name: Rewrite documentation
Date: 2026-05-29

## Decision 1: ClassfileUnpickler.scala - deviation from plan BEFORE block

**Plan's stale BEFORE:** `// Phase C: classfile attribute decoder` (a standalone comment the plan said existed at line 19).

**Actual file contents at lines 14-26 (per prep concern #1):** The file has no standalone `// Phase C:` comment. The phase references are embedded inside `@param` scaladoc lines:
- Line 19: `@param parents Unresolved parent types: super class (if any) followed by implemented interfaces. Phase 7 resolver replaces with real symbols.`
- Line 25: `@param arena The TypeArena passed in; included for Phase 7 merge.`

**Action taken:** Rewrote both `@param` lines to remove phase tracking language, replacing with architectural descriptions:
- Line 19 (new): `Unresolved parent types: super class (if any) followed by implemented interfaces. The classpath orchestrator resolves these to real symbols during the merge pass.`
- Line 25 (new): `The TypeArena used during decoding; retained so the orchestrator can merge per-file arenas into the canonical arena.`

**Justification:** The plan AFTER block said the target was "Classfile attribute decoder (one match arm per JVMS attribute name)." which describes a file-level comment that doesn't exist. The actual phase references are in `@param` scaladoc on `ClassfileResult`. The new text preserves the semantic meaning while removing phase-tracking language.

---

## Decision 2: DESIGN.md line 1475 - locked decision inversion (prep concern #2)

**Original text (after the initial `kyo-reflect` → `kyo-tasty` sweep produced a contradiction):**
`7. **Module name `kyo-tasty`** (replaces `kyo-tasty`). LOCKED. Reflects the unified Java+Scala scope. TASTy and classfile are implementation formats, not the module's identity.`

**Problem:** The bulk rename pass turned `kyo-reflect` into `kyo-tasty` everywhere, including inside the locked decision that named the old module name. This left `"replaces kyo-tasty"` pointing at itself.

**Action taken:** Rewrote decision #7 to reflect the actual current state:
`7. **Module name `kyo-tasty`** (replaces the legacy `kyo-reflect` proposal). LOCKED. The unified Java+Scala scope is captured by the API surface (Symbol, Type, Classpath), not by the module name. TASTy and classfile are the two primary input formats; kyo-tasty names the primary format while classfile support is implied by scope.`

This correctly records that `kyo-tasty` is the final name and `kyo-reflect` was an earlier proposal, matching the inversion the prep doc called for.

---

## Decision 3: README sections 188-215 and 219-252 removed entirely (prep concern #3)

**Section 188-215 head:** `## Schema-driven projection via `derives``

**Section 219-252 head:** `## Cross-language bridging via `kyo.Record``

**Why removed:** Both sections used `Reflect.Reads` / `derives Reflect.Reads` and `Reflect.symbolToRecord` APIs that have no source implementation (confirmed by Q-008 research). The code blocks in both sections would fail README doctest extraction (test 4, INV-020). The plan explicitly said "remove"; the prep confirmed this is all-or-nothing.

**Connecting prose:** After removal of both sections the document flows directly from `## Use case 4: Java + Scala unified` (with FQN/inner-class note) to `## Snapshot cache`. This flow is logical: the use cases end, then the underlying mechanism (snapshot cache) is explained. No connecting prose was needed.

**Post-removal line ranges (approximate, in the new file):** The new README has no schema-driven or Record-bridging section. The removal deleted approximately 65 lines of text and code from the original.

---

## Decision 4: Phase references in AstUnpickler.scala and ClasspathOrchestrator.scala

**Scope boundary:** Phase 1 modifies only four files. AstUnpickler.scala and ClasspathOrchestrator.scala contain `// Phase [0-9CB]` comments (Phase 1/2 decode passes in AstUnpickler; Phase C/5/6/7 orchestration in ClasspathOrchestrator). These are not in the Phase 1 `files_modified` list.

**Test 3 (INV-021) implication:** Test 3 scans all of `shared/src/main/scala/kyo` and `jvm/src/main/scala/kyo`. It will fail because those two files still have phase comments. This is expected and correct: the failing test exposes work remaining for M10 (assigned to Phase 19 per the plan).

**No action taken:** The prep does not list these files in the Phase 1 scope. Modifying them here would exceed Phase 1 scope. The test failure is the deliverable.

---

## Decision 5: AstUnpickler Phase 1/2 vs campaign Phase references distinction

The phase references in `AstUnpickler.scala` (`// Phase 1: collect symbols`, `// Phase 2: decode parent_Type*`) and `ClasspathOrchestrator.scala` (`// Phase C: resolve UnresolvedRef`, `// Phase 5 (G20)`, etc.) refer to the internal decode protocol passes (A, B, C) and AST decode sub-passes, not campaign tracking metadata. However, the regex `// Phase [0-9CB]` matches both categories. The plan's M10 campaign sweep (Phase 19) covers removing all such comments from these files as well.

---

## Decision 6: DESIGN.md Section 1 restructuring - `### Non-Goals` demoted to `## Non-Goals`

The original had `## 1. Goals and Non-Goals` with `### Goals` and `### Non-Goals (v1)` as subsections. The plan's after block introduced `## 1. Goals` and `## 1a. Performance targets` as top-level `##` sections. The Non-Goals no longer have a parent `## 1.` section so the `###` subsection must become a `## Non-Goals (v1)` top-level section to maintain heading hierarchy. This is a structural consequence of the split, not a content change.

---

## Decision 7: README navigation check after section removals

After removing schema-driven (lines 188-215) and cross-language bridging (lines 219-252), the "Learn more" link `kyo/reflect/examples/` was also updated to `kyo/tasty/examples/` (actual directory confirmed by prep). The "Project status" section now references `TastyError.NotImplemented` instead of `ReflectError.NotImplemented`, and the "full phased plan" reference was preserved as-is since DESIGN.md Section 18 still exists. The "Phase 0 is checked in" prose was reworded to "The skeleton build is checked in" to avoid campaign-phase language in user-facing documentation.

---

## Decision 8: TastyTest.scala created with 4 narrow-scope scenarios

**Created:** `kyo-tasty/shared/src/test/scala/kyo/TastyTest.scala`

The 4 scenarios fulfill the test leaves declared in `05-plan.yaml` Phase 01:

1. **README rename consistency** (pins: INV-020, INV-026): asserts `kyo-reflect`, `Reflect.`, `ReflectError`, `.kyo-reflect-cache` each appear 0 times in README.md; asserts DESIGN.md has exactly 1 `kyo-reflect` occurrence (the locked historical reference at line 1477 section 22 decision #7); asserts README.md has at least 1 `kyo-tasty` occurrence.

2. **DESIGN section split** (pins: L7): asserts `## 1. Goals` and `## 1a. Performance targets` each appear exactly once in DESIGN.md, that `## 1a. Performance targets` follows `## 1. Goals`, and the Goals section body is under 20 lines.

3. **No phase-metadata comments in INV-021 production source sites** (pins: INV-021): narrowly scoped to three windows: Tasty.scala lines 73-88, Tasty.scala lines 1006-1022, and ClassfileUnpickler.scala lines 14-29. Uses regex `// Phase [0-9A-Z]`.

4. **README doctest extraction** (pins: INV-020, L6): asserts README has at least 1 fenced `scala` block containing `import kyo.Tasty.*`, and zero fenced `scala` blocks containing `Reflect.` or `ReflectError`.

---

## Decision 9: INV-021 Test 3 narrowly scoped; AstUnpickler.scala and ClasspathOrchestrator.scala excluded

**Scope pin:** Test 3 (INV-021) is scoped to the 3 specific L4-identified production source sites only.

**Excluded:** `AstUnpickler.scala` and `ClasspathOrchestrator.scala` are intentionally outside Test 3's scan scope.

**Rationale:** `AstUnpickler.scala` uses `// Phase 1:` and `// Phase 2:` to name its two-pass AST decoding protocol (skeleton pass, then lazy body decode). `ClasspathOrchestrator.scala` uses `// Phase A`, `// Phase B`, `// Phase C` and numbered variants to name its orchestration stages (file discovery, parallel decode, single-threaded merge). These are algorithmic pipeline stage names embedded in the classpath orchestrator design (DESIGN.md sections 7, 16, 17, 18), not delivery-tracking metadata introduced by the audit campaign. Scanning them under INV-021 would be a false positive. Cleanup of those comments is governed by Phase 19 (M10 in the plan).

---

## Decision 10: DESIGN.md flow-allow markers added for bench-harness and algorithmic-discussion hits

**Markers added:**

- `DESIGN.md` before line 1379 (`**Phase 0: skeleton + bench harness.**`): `<!-- flow-allow: bench-harness is the canonical name for kyo-tasty-bench, not an LLM-tell hedge -->`
- `DESIGN.md` before line 1411 (`Bench harness compares kyo-tasty vs tasty-query on JVM`): `<!-- flow-allow: bench-harness is the canonical name for kyo-tasty-bench, not an LLM-tell hedge -->`
- `DESIGN.md` before line 383 (`placeholder data`): `<!-- flow-allow: algorithmic discussion of placeholder symbols returned from unresolved-symbol accessors, not deferral -->`
- `DESIGN.md` inside code block before `val inProgress`: `<!-- flow-allow: inProgress is an algorithmic cycle-breaking map in the TypeArena merge pseudocode, not a status flag -->`
- `DESIGN.md` before line 1135 (`UnresolvedRef placeholder ... Phase C resolution`): `<!-- flow-allow: Phase C is the classpath orchestrator merge stage name; placeholder is the UnresolvedRef stand-in type; both are algorithmic terms, not delivery deferral -->`
- `DESIGN.md` before line 1200 (`Phase C fails to resolve a placeholder`): `<!-- flow-allow: Phase C is the classpath orchestrator merge stage; placeholder is the UnresolvedRef stand-in type; both are algorithmic terms, not delivery deferral -->`
