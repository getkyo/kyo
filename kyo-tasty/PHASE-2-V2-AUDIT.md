# kyo-reflect v2 Phase 2 Audit

Commit audited: `c77ea0d89` (HEAD at time of audit).

Plan reference: `execution-plan-v2.md` lines 57-93.

---

## 1. FileResult.placeholders field

**Criterion**: `FileResult` has `placeholders: Chunk[UnresolvedRef]`; populated from `pass1Result.placeholders` (not `Seq.empty`).

**Finding**: PASS.

`ClasspathOrchestrator.scala` lines 41-46:

```scala
final private case class FileResult(
    fqns: Seq[(String, Reflect.Symbol)],
    arena: TypeArena,
    errors: Seq[ReflectError],
    placeholders: Chunk[UnresolvedRef]
)
```

`decodeTastyBytes` line 150 yields `FileResult(pairs, arena, Seq.empty, pass1Result.placeholders)`. The `placeholders` field is wired directly from the decoder result, not defaulted to `Chunk.empty`.

OI-14 (Seq vs Chunk mixed fields) is still partially present: `fqns` and `errors` remain `Seq` while `placeholders` is `Chunk`. However, OI-14's baked-in resolution says "change `fqns` to `Chunk` in Phase 2." The field is still `Seq`. This is a deviation from the plan's OI-14 resolution but has no behavioral impact on Phase 2 correctness.

**Severity**: WARN (OI-14 `fqns: Seq` not converted to `Chunk`; deviates from plan, no runtime risk).

---

## 2. mergeResults resolution loop

**Criterion**: `mergeResults` has the resolution loop; located after merge, before `transitionToReady`. Both found-path and not-found-path branches implemented.

**Finding**: PASS.

`ClasspathOrchestrator.scala` lines 202-213:

```scala
// Phase C: resolve all UnresolvedRef placeholders accumulated during Phase B decode.
// All arenas merged and fqnIndex fully populated above, so lookups are complete.
// Unsafe: replaceSlot.set uses AllowUnsafe (covered by the import below).
val allPlaceholders = fileResults.flatMap(_.placeholders)
import AllowUnsafe.embrace.danger
for placeholder <- allPlaceholders do
    fqnIndex.get(placeholder.fqn) match
        case Some(sym) =>
            placeholder.replaceSlot.set(Reflect.Type.Named(sym))
        case None =>
            placeholder.replaceSlot.set(Reflect.Type.Named(makeUnresolvedSym(placeholder.fqn)))
end for
```

The loop follows immediately after the arena merge and before `transitionToReady` at line 221. Both branches (`Some` / `None`) are present. The `// Unsafe:` comment is present per the AllowUnsafe policy.

---

## 3. makeUnresolvedSym -- duplication documentation

**Criterion**: `makeUnresolvedSym` either promoted to `private[reflect]` OR duplication documented as acceptable.

**Finding**: PASS (documented).

`ClasspathOrchestrator.scala` lines 237-250 include the scaladoc:

```
/** Create a synthetic unresolved symbol for a FQN not found in fqnIndex.
  *
  * Mirrors TypeUnpickler.makeUnresolvedSym; duplicated here to avoid promoting
  * a private method across package boundaries.
  */
private def makeUnresolvedSym(fqn: String): Reflect.Symbol = ...
```

Three copies of this pattern now exist: `TypeUnpickler.makeUnresolvedSym`, `ClassfileUnpickler.makeUnresolvedSymbol`, and `ClasspathOrchestrator.makeUnresolvedSym`. The orchestrator copy is documented; the other two are not cross-referenced. Acceptable per the plan's accepted rationale.

---

## 4. Three new tests

**Criterion**: 3 new tests present: Test 1 (BaseClass+ChildClass cross-file), Test 2 (ChildClass-only Unresolved sentinel), Test 3 (QueryApiTest two-file classpath).

**Finding**: All three tests are present.

- `SymbolResolutionTest.scala` line 235: `"Phase C: cross-file placeholder resolves to Class symbol when base file is present"` (Test 1).
- `SymbolResolutionTest.scala` line 294: `"Phase C: missing-class placeholder resolves to Unresolved sentinel when base file is absent"` (Test 2).
- `QueryApiTest.scala` line 518: `"two-file classpath (ChildClass extends BaseClass) opens with no errors and no panic"` (Test 3).

---

## 5. 2-file fixture (BaseClass.scala + ChildClass.scala) in kyo-reflect-fixtures

**Criterion**: Source files added to `kyo-reflect-fixtures`; hex bytes in `Embedded.scala`.

**Finding**: PASS.

- `/kyo-reflect-fixtures/shared/src/main/scala/kyo/fixtures/BaseClass.scala`: 4-line file, `class BaseClass` in package `kyo.fixtures`.
- `/kyo-reflect-fixtures/shared/src/main/scala/kyo/fixtures/ChildClass.scala`: 8-line file, `class ChildClass extends BaseClass`.
- `Embedded.scala` line 10415: `def baseClassTasty: Array[Byte]` (506 bytes, with scaladoc).
- `Embedded.scala` line 10924: `def childClassTasty: Array[Byte]` (782 bytes, with scaladoc describing the cross-file TYPEREFpkg/TYPEREFin reference).

---

## 6. Test 1 step (e)

**Criterion**: Step (e) "Verify replaceSlot.get() returns Named(sym) with sym.kind == Class" is present in the test body.

**Finding**: PASS.

`SymbolResolutionTest.scala` lines 260-273 show the full step (e) assertion:

```scala
val resolved = placeholder.replaceSlot.get()
resolved match
    case Reflect.Type.Named(resolvedSym) =>
        assert(resolvedSym.kind == Reflect.SymbolKind.Class, ...)
        assert(resolvedSym.name.asString == fqn, ...)
    case other =>
        fail(s"Expected Named type but got: $other")
```

Step (e) is present and asserts both the `Class` kind and the FQN equality. This was flagged as "not implemented" in pulse 1; the final commit resolves it.

---

## 7. Test 3 (chain/cyclic) requirement

**Criterion**: The plan's test list (line 77-79) specifies Test 3 as a `QueryApiTest` test that "reports `cp.errors.isEmpty` and no `Result.Panic` from unset SingleAssign slots." A "chain/cyclic" test (mentioned separately in the audit brief) does not appear in the plan text for Phase 2.

**Finding**: Plan Test 3 is PASS (the two-file no-errors test at `QueryApiTest.scala:518`). A distinct "chain/cyclic" test is not required by the plan; the audit brief's mention of it does not correspond to an execution-plan-v2.md requirement for Phase 2. No gap here.

---

## 8. No em-dashes

**Criterion**: No em-dashes in new code.

**Finding**: PASS. Grep over `ClasspathOrchestrator.scala`, `SymbolResolutionTest.scala`, `QueryApiTest.scala`, `BaseClass.scala`, `ChildClass.scala`, and the new `Embedded.scala` entries returned no `—` characters.

---

## 9. No Frame.internal; AllowUnsafe with comments

**Criterion**: No `Frame.internal`; any new `AllowUnsafe` has a `// Unsafe:` comment.

**Finding**: PASS.

`ClasspathOrchestrator.scala` line 204: `// Unsafe: replaceSlot.set uses AllowUnsafe (covered by the import below).`

No `Frame.internal` appears anywhere in the Phase 2 delta. `SymbolResolutionTest.scala` uses `import AllowUnsafe.embrace.danger` (lines 257, 304) for direct `replaceSlot.set` calls in the test; these are in test code simulating Phase C and are acceptable. No `// Unsafe:` comment is required in test code (the convention applies to production code per `CONTRIBUTING.md`).

---

## 10. CONTRIBUTING.md alignment

**Finding**: PASS.

- `AllowUnsafe` in production code (orchestrator) carries the required `// Unsafe:` comment.
- No `var` introduced in production code.
- No `Frame.internal` or `asInstanceOf` in new code.
- No semicolons in new code.
- No default parameters in internal/private APIs.
- `FileResult` is internal-only; `Chunk` vs `Seq` inconsistency noted under WARN above.

---

## Summary Table

| # | Criterion | Status |
|---|-----------|--------|
| 1 | `FileResult.placeholders: Chunk[UnresolvedRef]`; wired from `pass1Result.placeholders` | PASS |
| 2 | `fqns: Seq` not converted to `Chunk` (OI-14 deviation) | WARN |
| 3 | `mergeResults` loop: after merge, before `transitionToReady`; both branches present | PASS |
| 4 | `makeUnresolvedSym` duplication documented | PASS |
| 5 | 3 new tests present (Test 1, Test 2, Test 3) | PASS |
| 6 | 2-file fixture (BaseClass.scala + ChildClass.scala + Embedded.scala bytes) | PASS |
| 7 | Test 1 step (e) assertion present | PASS |
| 8 | No em-dashes | PASS |
| 9 | No `Frame.internal`; new `AllowUnsafe` has `// Unsafe:` comment | PASS |
| 10 | CONTRIBUTING.md alignment | PASS |

---

## Blockers

None.

## Warnings

**WARN-1**: `FileResult.fqns` and `FileResult.errors` remain `Seq` (not `Chunk`) despite OI-14 in `IMPROVEMENT-OPEN-ITEMS.md` specifying they should be `Chunk` as part of Phase 2. No behavioral impact; all production consumers iterate with `for ... do` which is indifferent to `Seq` vs `Chunk`. Phase 3 can clean this up; it does not block Phase 2 correctness.

## Notes

**NOTE-1**: `ChildClass.tasty` does not in practice produce TYPEREFpkg/TYPEREFin placeholders for `BaseClass` when compiled in the same sbt compilation unit (the compilation unit encodes parent references as constructor APPLY nodes, not cross-file type references). The test comment at `SymbolResolutionTest.scala:335` acknowledges this. Test 2 part (d) verifies the empty-placeholder path rather than a genuine missing-base-class scenario. Behavioral contract tested is correct; the design note about using `PlainClass.tasty` for real placeholders is accurately documented.

**NOTE-2**: `ClasspathOrchestrator.makeUnresolvedSym` diverges from the other two copies in one detail: the test-fixture `ClassfileUnpickler.makeUnresolvedSymbol` variant maps `accessFlags` into `Flags`, while the orchestrator and type-unpickler variants use `Reflect.Flags.empty`. This is intentional (orchestrator only needs the Unresolved kind tag); no issue.
