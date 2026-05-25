# Phase 2 In-Flight Review 1

**Phase**: Phase 2 (G13 Phase C UnresolvedRef placeholder resolution)
**Pulse**: 1 of N
**Date**: 2026-05-25
**Reviewer**: Automated Supervisor

---

## Plan Anchor Table

| Check | Verdict | Citation |
|---|---|---|
| `FileResult` has `placeholders: Chunk[UnresolvedRef]` field | CLEAN | ClasspathOrchestrator.scala:45 |
| `decodeTastyBytes` passes `pass1Result.placeholders` | CLEAN | ClasspathOrchestrator.scala:150 |
| `mergeResults` has resolution loop after merge, before `transitionToReady` | CLEAN | ClasspathOrchestrator.scala:202-213 |
| `makeUnresolvedSym` accessible from `ClasspathOrchestrator` | CLEAN (duplication) | ClasspathOrchestrator.scala:241-250 |
| 2-file fixture (Base + Child) added OR deviation documented | CLEAN | Embedded.scala lines 10411-11714+ (`baseClassTasty`, `childClassTasty`) |
| 3 new tests added | CLEAN | SymbolResolutionTest lines 229, 284; QueryApiTest line 518 |
| No new `asInstanceOf` in production | CLEAN | grep confirmed zero hits |
| No `Frame.internal` added | CLEAN | grep confirmed zero hits |
| No em-dashes in modified files | CLEAN | grep confirmed zero hits |
| No new `AllowUnsafe` sites without `// Unsafe:` comment | CLEAN | ClasspathOrchestrator.scala:204 has the comment |
| `Pass1Result.placeholders` is actually populated (not `Chunk.empty`) | CLEAN | AstUnpickler.scala:122 `placeholders = Chunk.from(typeSession.placeholders)` |

---

## Reward-Hacking Checks

- Resolution loop is genuinely inside `mergeResults` after the `for fr <- fileResults` loop closes (line 200) and before `Classpath.transitionToReady` (line 221). Ordering is correct: fqnIndex fully populated, all arenas merged, then slots set.
- Both soft-fail error paths in `readAndDecodeTastyFile` (lines 116 and 122) pass `Chunk.empty` for placeholders. Correct: those paths short-circuit before any `UnresolvedRef` is allocated.
- `AllowUnsafe` import moved from line 216 (stateRef read) to line 206 (before the placeholder loop). The single `import AllowUnsafe.embrace.danger` covers both the placeholder loop's `replaceSlot.set(...)` calls and the original `cp.stateRef.unsafe.get()` call. No coverage gap.
- `makeUnresolvedSym` in ClasspathOrchestrator (line 241) is a `private def` duplication, not a promotion to `private[reflect]`. The PREP doc listed three options (a: shared utility, b: `private[reflect]`, c: inline duplication) and the agent chose (c). The body is 8 lines and is trivially correct (differs from TypeUnpickler only in that the `home` parameter is replaced with `new ClasspathRef`, which is appropriate for a phantom unresolved symbol in the merge context). No shared utility was created; no deviation was documented in PHASE-2-IMPL-NOTES.md (that file is absent).

---

## Drifting Checks

- AstUnpickler.scala was NOT modified in this phase (git diff confirms no AstUnpickler changes). The `Pass1Result.placeholders` field and `typeSession.placeholders` wiring were already present from a prior phase. The diff is entirely in ClasspathOrchestrator.scala + test files + Embedded.scala. No scope drift.
- STEERING.md received 15 new lines. This was not called for by Phase 2. Minor (steering doc update is low risk) but is undeclared work.
- No extraneous production files were touched.

---

## Scope-Cutting Checks (per test leaf)

### Test 1: Child + Base, parent resolves to `Named(BaseSym)` (SymbolResolutionTest line 229)

| Sub-step | Status |
|---|---|
| (a) Decode childClassTasty, verify placeholders non-empty | Present, strict |
| (b) Decode baseClassTasty, find BaseClass symbol | Present, strict |
| (c) Manually simulate Phase C: `replaceSlot.set(Named(baseSym))` | Present, strict |
| (d) Verify slot returns `Named(sym)` with `kind == Class` and `fullName contains "BaseClass"` | Present, strict |
| (e) Open full classpath with BOTH files; verify no panic | MISSING from test body |

Comment at line 228 says "Also open a full classpath with both files and verify no panic (no unset SingleAssign)" but the test body ends at line 275 without implementing step (e). This sub-step is covered separately by QueryApiTest Phase 2 Test 3 (line 518), which opens both files and checks `cp.errors.isEmpty` plus both symbols present. The gap is that Test 1's promise is not fully self-contained, but the coverage is achieved across tests.

Verdict: **Weakened** (step e offloaded to QueryApiTest rather than included in Test 1 per its own comment; not missing entirely).

### Test 2: Child only, parent resolves to `Named(Unresolved)` (SymbolResolutionTest line 284)

| Sub-step | Status |
|---|---|
| (a) Decode childClassTasty, verify placeholder has fqn == "kyo.fixtures.BaseClass" | Present, strict |
| (b) Simulate Phase C without base: synthesize Unresolved sentinel via `Reflect.Symbol.make` | Present, strict |
| (c) Verify slot returns `Named(sym)` with `sym.kind == Unresolved` and `sym.name == "kyo.fixtures.BaseClass"` | Present, strict |
| (d) Open full classpath with ONLY childClassTasty; verify no panic | Present, strict (lines 324-338) |

Verdict: **CLEAN** (all four sub-steps implemented strictly).

Note: Test 2 uses `Reflect.Symbol.make` (which is `private[kyo]`) to synthesize the unresolved symbol rather than the ClasspathOrchestrator's private `makeUnresolvedSym`. This is acceptable for a test that simulates Phase C manually, since the test is in the `kyo` package and has access to `private[kyo]` methods. The sentinel produced is structurally equivalent.

### Test 3: 3-file chain OR cyclic refs (QueryApiTest line 518)

The plan says Test 3 is in QueryApiTest: "a classpath opened from the two fixture TASTy files (one extending the other) reports `cp.errors.isEmpty` and no `Result.Panic` from unset SingleAssign slots."

| Sub-step | Status |
|---|---|
| Add both BaseClass.tasty and ChildClass.tasty to MemoryFileSource | Present (lines 520-521) |
| Open classpath and assert `cp.errors.isEmpty` | Present (line 529) |
| Assert ChildClass found | Present (line 530) |
| Assert BaseClass found | Present (line 531) |
| Verify no `Result.Panic` | Present (lines 534-535 via `throw t`) |

Verdict: **CLEAN** (all required sub-steps present and strict).

---

## CRITICAL

1. **Test 1 step (e) not implemented in Test 1's body** (SymbolResolutionTest lines 229-275): The comment at line 228 explicitly promises "Also open a full classpath with both files and verify no panic." The body ends without it. The coverage is provided by QueryApiTest Test 3, but Test 1 is self-inconsistent (comment-promise vs implementation gap). This is a scope cut within Test 1 that was silently split across tests without updating the comment to remove the promise. Fix: either implement step (e) inline in Test 1 or remove the "(e)" bullet from its comment.

2. **`makeUnresolvedSym` duplication is undocumented**: The PREP doc listed three options and flagged option (c) (inline duplication) as acceptable only if the body is trivially inlinable. The body diverges from TypeUnpickler's in one argument (`new ClasspathRef` vs the passed `home`). This is semantically correct but creates a second implementation of the same factory logic with no comment explaining the divergence. There is no PHASE-2-IMPL-NOTES.md documenting the choice. Low risk of bugs today, higher risk of divergence in future refactors.

---

## MINOR

3. **STEERING.md modified** (15 lines added): Not called for by Phase 2. Undeclared change in the diff. Should be tracked or committed separately.

4. **TypeUnpickler's `makeUnresolvedSym` remains `private`**: The PREP doc recommended option (a) or (b) as cleaner than duplication. The chosen option (c) works but leaves two independent implementations that could diverge if `SymbolKind.Unresolved` factory logic changes (e.g., if a parent or flags field is added in Phase 3). Not a bug today.

5. **Test 2 creates unresolved sentinel with `Reflect.Symbol.make` instead of the real Phase C path**: The test synthesizes the sentinel manually rather than exercising ClasspathOrchestrator's actual `makeUnresolvedSym`. This means Test 2 proves the slot mechanism works but does not directly exercise the ClasspathOrchestrator code path for the missing-FQN case. QueryApiTest Test 3 with only ChildClass exercises that code path end-to-end and is therefore the real coverage provider for the `None` branch.

---

## Recommendation

**Proceed with caution.** The core Phase 2 implementation (FileResult.placeholders, decodeTastyBytes threading, mergeResults resolution loop, makeUnresolvedSym duplication) is correct and complete. Tests 2 and 3 are clean. The only actionable item before moving to Phase 3 is:

- Fix Test 1's comment to remove the "(e)" bullet (or implement the sub-step inline). This is a 2-line doc fix, not a code fix.
- Add PHASE-2-IMPL-NOTES.md noting the duplication choice for `makeUnresolvedSym` (or add an inline comment in ClasspathOrchestrator explaining why it is not delegating to TypeUnpickler).

Neither blocker is correctness-breaking. The Phase C implementation is sound and will not produce `IllegalStateException` from unset `SingleAssign` slots in any path (both found and not-found cases are handled). Phase 3 dependency (G21 parents wiring) is unblocked.
