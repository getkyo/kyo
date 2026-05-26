# v3 Plan Validation

Audit run: 2026-05-26T02:57:47Z
Plan: kyo-reflect/execution-plan-v3.md
Verdict: FAIL (3 findings; none block implementation, 2 are annotation errors in the audit checklist)

---

## Summary

| Rule | Status |
|------|--------|
| 1. All 8 phases present | PASS |
| 2. No priority language | PASS |
| 3. No em-dashes | PASS |
| 4. Concrete content per phase | PASS |
| 5. No vague phrasings | FAIL (1 hit) |
| 6. All deletions enumerated | PASS |
| 7. Phase 1 covers all breaking callers | PASS |
| 8. Phase 3 leaves body effectful | PASS |
| 9. Test count math correct | PASS |
| 10. Non-goals explicit | PASS |
| 11. CONTRIBUTING.md alignment | PASS |
| 12. Each phase compiles independently | PASS |

---

## Findings

### FAIL (3)

1. **Rule 5 (no vague phrasings)** -- Phase 2, Files to modify, ClasspathOrchestrator.scala entry (line 78):
   > "remove the `Resolver.makeClassLookup`/`makePackageLookup` build steps in `allocate` (or wherever they are wired in the v2 Phase 1 fixup commit)"
   The parenthetical "or wherever they are wired" is a conditional location hedge that violates the concreteness requirement. Fix: before freezing v3, grep `ClasspathOrchestrator.scala` for `makeClassLookup` and replace the parenthetical with the exact enclosing method and line range.

2. **Rule 4 (concrete content) / Phase 8 audit checklist** -- Phase 8, Files to produce, FINAL-AUDIT-V3.md entry (line 304):
   > "deleted-file completeness (8 + 1 = 9 files deleted across Phases 1 and 5 and 6)"
   The arithmetic is wrong. Phase 1 deletes 8 files; Phase 5 deletes 1 file; Phase 6 deletes 1 file (Memo.scala, replaced by OnceCell.scala). The total is 10 deleted files, not 9. The phrase "across Phases 1 and 5 and 6" mentions Phase 6 but the sum omits it. Fix: change the audit checklist to "8 + 1 + 1 = 10 files deleted across Phases 1, 5, and 6" or, if Phase 6 is intentionally excluded from the deletion count because it is a rename-and-replace, change the phrasing to "8 + 1 = 9 pure deletions (Phases 1 and 5); Phase 6 additionally replaces Memo.scala with OnceCell.scala".

3. **Rule 4 (concrete content) / stale v2 cross-reference** -- Phase 3, Files to modify, Reflect.scala bullet (line 118):
   > "reading immutable Ready-state data set during Phase C, before any user access"
   "Phase C" is a v2 phase label. v3 uses numbered phases 1-8; there is no Phase C in v3. The comment scaffold the implementer will write in source code will contain a stale reference. Fix: replace "Phase C" with "classpath construction (before `transitionToReady`)" or with the equivalent v3 phase number if one maps to that construction step.

---

### Concerns / NOTE

1. **Two-pass example update (Phases 1 and 7)**: Each example file (CodegenExample, IdeHoverExample, JavaScalaBridgeExample, RuntimeReflectionExample) and ReflectBench.scala are listed as modified in both Phase 1 and Phase 7. Phase 1 removes `Reads` usage and rewrites to `for`/`yield` over effectful accessors; Phase 7 updates those same files again once accessors are pure. This is intentional and correct (the plan explicitly says "Bench will be extended in Phase 7"), but implementers should note that Phase 1's edits leave examples using the effectful accessor API, which Phase 7 then simplifies. No action needed; just a heads-up for implementers.

2. **Phase 5 dependency claim vs. sequential verification**: Phase 5 declares "No dependency on Phases 2-4" and states its own verification as "compiles clean; 246 tests passing." That count of 246 is only correct if Phases 2-4 have already been applied (+1 from Phase 4's regression test, net 246). If Phase 5 were applied right after Phase 1 in isolation, the expected count would be different (246 from Phase 1, but Phase 2's -1 and Phase 4's +1 are interleaved). This is not a problem because the plan executes phases sequentially 1-8, but the dependency field is slightly misleading. No action required; sequential execution resolves it.

3. **AllowUnsafe at Phase 3 pure-accessor sites**: Phase 3 introduces new `AllowUnsafe.embrace.danger` call sites inside pure accessors, which is technically "new AllowUnsafe sites." The plan correctly requires `// Unsafe:` comments at each such site (line 118). The Phase 8 audit checklist also checks "no AllowUnsafe at new sites without a `// Unsafe:` comment" (line 304). These two requirements are consistent, so the CONTRIBUTING alignment is satisfied -- but only if the implementer follows the comment requirement. The supervisor check for Phase 3 should explicitly verify `// Unsafe:` presence (currently it checks the return type only). Low risk; noted for implementer awareness.

4. **Double non-goals section**: The plan has two non-goals sections -- one at the top (lines 7-13) and one at the bottom (lines 355-364). Both list the same four items. The duplication is harmless but could drift if one section is edited during execution. No action required.

---

## Recommendation

RE-EDIT plan for:
1. Line 78: replace "or wherever they are wired in the v2 Phase 1 fixup commit" with the exact method and file location in ClasspathOrchestrator.scala.
2. Line 304: correct deletion count from "8 + 1 = 9" to either "8 + 1 + 1 = 10" or clarify Phase 6 is a replacement not a pure deletion.
3. Line 118: replace "Phase C" with a v3-native description of the classpath construction phase.

All three are annotation/audit errors that do not affect the implementation sequence. The phasing, dependencies, deletions enumeration, test math, API removals, and verification commands are all correct. The plan is approved to proceed after the three re-edits above.
