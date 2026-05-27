# Phase 6 In-Flight Review (pulse 2)

Pulse 2: 2026-05-24T05:00Z
Files reviewed:
- `STEERING.md` section "Phase 6 critical fixes (BLOCKING before commit)"
- `PHASE-6-INFLIGHT-REVIEW-1.md` (pulse 1 context)
- `shared/src/main/scala/kyo/Reflect.scala` lines 344-356 (object Reads)
- `shared/src/main/scala/kyo/internal/ReflectMacro.scala` lines 295-309 (makeLambdaCall)
- `shared/src/main/scala/kyo/internal/reflect/reads/TouchedFields.scala` lines 98-106 (GotoQueue)
- `shared/src/main/scala/kyo/internal/reflect/reads/ReadsInstances.scala` lines 1-30
- `shared/src/test/scala/kyo/` directory listing

---

## STEERING compliance

| # | Directive | Verdict | Citation |
|---|---|---|---|
| 1 | Macro splice `${ kyo.internal.ReflectMacro.derivedImpl[A] }` wired in Reflect.scala | NOT_COMPLIED | Line 354 still reads `scala.compiletime.error("Reflect.Reads.derived not implemented in Phase 0; lands in Phase 6")`. No splice present. |
| 2 | `ReadsDerivationTest.scala` exists with 18 tests | NOT_COMPLIED | File absent. Directory listing shows 17 other test files; `ReadsDerivationTest.scala` is not among them. Zero of 18 tests present. |
| 3 | `asInstanceOf[Term]` at ReflectMacro.scala (originally :343, now :300) removed | NOT_COMPLIED | `paramss.head.head.asInstanceOf[Term]` is still present at line 300. Line number shifted by -43 since pulse 1 (some code moved) but the cast is unchanged. |
| 4 | `asInstanceOf` at TouchedFields.scala:102 removed | NOT_COMPLIED | `items.asInstanceOf[List[quotes.reflect.Tree]]` is still present at line 102 (same line). |
| 5 | `ReadsInstances` given/export wired into `Reflect.Reads` companion | NOT_COMPLIED | `object Reads` in Reflect.scala line 353-354 contains only the stub `derived`. No `export`, no `given` delegation. `ReadsInstances` is unreachable from user scope. |

All 5 BLOCKING directives remain unresolved. Zero of 5 are complied.

---

## New drift since pulse 1

1. **ReflectMacro.scala line shift**: The `asInstanceOf[Term]` site moved from line 343 to line 300 (net -43 lines). The cast is unchanged. There is also `TypeApply(Select.unique(apply, "asInstanceOf"), ...)` at line 304; this is a runtime `.asInstanceOf` call emitted as a TASTy tree node, NOT a compile-time cast in the macro itself. It is the array element cast in the generated constructor bridge. The comment at original line 358 (now line 303 area) reads "The asInstanceOf casts in the constructor bridge are..." confirming this is intentional generated-code, not a macro-author cast. This is NOT a new violation: the `feedback_no_casts` rule applies to Scala source, not to TASTY-tree nodes built by the macro. Only line 300 is a true violation.

2. **`Memo.scala` and `SingleAssign.scala` use `asInstanceOf`**: Two files in `kyo.internal.reflect.symbol` have type-erased sentinel patterns (Memo.Unset / SingleAssign.Unset) that use `.asInstanceOf` for AnyRef erasure bridge. These are pre-existing and not introduced by the Phase 6 agent. They are erased-type sentinel patterns, a standard JVM idiom. Flagged for post-commit audit; not a new regression from Phase 6.

3. **No files renamed off-plan**: The 17 test files present are all from prior phases. No plan-required file was renamed or removed.

4. **No test weakening**: Since `ReadsDerivationTest.scala` does not exist, there is no test to weaken. No existing test file shows new tautological assertions (test files were not re-read in full but the 17 filenames match prior phases exactly).

5. **No plan items dropped from source files**: All prior-phase deliverables (ReadsInstances.scala with all 10 base givens + tuples 2-22, TouchedFields.scala, ReflectMacro.scala with `derivedImpl`) are present at their expected paths.

---

## CRITICAL (steer immediately)

All 5 BLOCKING directives from pulse 1 are STILL open. The Phase 6 agent has not applied any of them. The tree appears unchanged with respect to all 5 issues since pulse 1.

1. **Directive 1 - OPEN**: Reflect.scala line 354: replace `scala.compiletime.error(...)` stub with `${ kyo.internal.ReflectMacro.derivedImpl[A] }`.

2. **Directive 2 - OPEN**: Create `kyo-reflect/shared/src/test/scala/kyo/ReadsDerivationTest.scala` with all 18 plan-mandated tests.

3. **Directive 3 - OPEN**: ReflectMacro.scala line 300: remove `paramss.head.head.asInstanceOf[Term]`. Use a pattern match: `paramss match { case List(List(v: Term)) => v }` or equivalent.

4. **Directive 4 - OPEN**: TouchedFields.scala line 102: remove `items.asInstanceOf[List[quotes.reflect.Tree]]`. Parameterize `GotoQueue` so that `items` is declared as `List[quotes.reflect.Tree]` from the start, or use a sealed queue payload type.

5. **Directive 5 - OPEN**: `object Reads` in Reflect.scala must `export kyo.internal.reflect.reads.ReadsInstances.*` (or equivalent `given` delegation) so built-in instances are in implicit scope without explicit import.

---

## MINOR (queue for post-commit audit)

1. **`Memo.scala` / `SingleAssign.scala` asInstanceOf**: Erased-sentinel pattern at `kyo.internal.reflect.symbol`. Pre-existing, not Phase 6 regressions. Post-commit: confirm each is properly guarded by a null/sentinel check and cannot throw ClassCastException.

2. **`TouchedFields` does not use `kyo.internal.Trees`**: Same issue flagged in pulse 1. Inline re-implementation is functionally equivalent but diverges from PREP doc architectural intent. Post-commit: verify whether kyo-direct is a compile dependency and update PREP doc accordingly.

3. **Transitive touchedFields conservatism** (test 15 risk): `extractStaticTouchedByTypeRepr` returns `FieldSet.Empty` for user-derived inner types. Test 15 (transitive touchedFields from composed case classes) may fail. The line-304 `.asInstanceOf` tree node in the generated constructor bridge (NOT a source cast) is intentional. Post-commit: run test 15 specifically and confirm.

---

## Recommendation: STEER: Apply all 5 BLOCKING directives before any test run; none have been addressed since pulse 1
