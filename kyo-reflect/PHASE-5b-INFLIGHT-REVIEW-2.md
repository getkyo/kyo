# Phase 5b In-Flight Review (pulse 2)

Pulse 2: 2026-05-24T00:00Z
Files reviewed:
- `kyo-reflect/STEERING.md` (full, 91 lines; section "Phase 5b test fixes (BLOCKING before commit)" lines 80-91)
- `kyo-reflect/PHASE-5b-INFLIGHT-REVIEW-1.md` (full, 154 lines)
- `kyo-reflect/shared/src/test/scala/kyo/JavaSymbolTest.scala` (lines 186-204, tests 4 and 5)
- `kyo-reflect/shared/src/test/scala/kyo/UnifiedModelTest.scala` (lines 85-141, sampled for context)

---

## STEERING compliance

| # | Directive | Verdict | Citation |
|---|---|---|---|
| 1 | Test 4 contains TASTy-side `!sym.isJava` assertion | NOT_COMPLIED | JavaSymbolTest.scala:189-194 — test body has `assert(sym.isJava, ...)` only; no TASTy symbol loaded, no `!sym.isJava` assertion present |
| 2 | Test 5 contains TASTy-side `tastySym.javaSpecific.isEmpty` assertion | NOT_COMPLIED | JavaSymbolTest.scala:199-204 — test body has `assert(sym.javaSpecific.isDefined, ...)` only; no TASTy symbol loaded, no `.isEmpty` assertion present |

Both directives from STEERING.md lines 82-88 remain unaddressed. The tests are identical to what pulse 1 observed and flagged as PRESENT_WEAKENED.

---

## New drift since pulse 1

- `UnifiedModelTest.scala` now EXISTS with 8 tests (lines counted: 8 `taggedAs` hits). Pulse 1 reported this file absent. The critical gap identified in pulse 1 has been resolved by a subsequent agent pass. Tests 11-18 are present.
- No new `asInstanceOf` introduced in `JavaSymbolTest.scala` or `UnifiedModelTest.scala` (grep returned empty).
- No tests removed from either file (JavaSymbolTest: 10 tests, UnifiedModelTest: 8 tests, both matching plan counts).
- No plan items dropped relative to pulse 1 findings.

---

## CRITICAL (steer immediately)

1. **Test 4 still missing TASTy-side assertion** (STEERING line 84): `JavaSymbolTest.scala:189-194` loads `java/lang/Object.class` and asserts `sym.isJava == true`, but does NOT load `PlainClass.tasty` and does NOT assert `!sym.isJava`. STEERING requires both halves in the same test method. `PlainClass.tasty` is already a fixture on disk. The TASTy-loading pattern is established in `UnifiedModelTest.tastySymbols` (UnifiedModelTest.scala:60-73) and in `AstUnpicklerTest.runPass1`. Use the same pattern. Fix required before commit.

2. **Test 5 still missing TASTy-side assertion** (STEERING line 86): `JavaSymbolTest.scala:199-204` loads `java/lang/String.class` and asserts `sym.javaSpecific.isDefined`, but does NOT load `PlainClass.tasty` and does NOT assert `tastySym.javaSpecific.isEmpty`. STEERING requires both halves in the same test method. Fix required before commit.

---

## MINOR (queue for post-commit audit)

1. Test 4 title says "sym.isJava is true for a Java classfile symbol" — after adding the TASTy half, the title should be broadened to reflect the dual assertion (e.g., "sym.isJava is true for Java-sourced and false for TASTy-sourced symbols"). Cosmetic, but aids readability.

2. Test 5 title says "sym.javaSpecific is Present for a Java classfile symbol" — same issue; broaden after fix.

3. `enclosingMethod` when `method_index == 0` stores `Present((enclosingClassSym, Reflect.Name("")))` rather than `Absent` (carried from pulse 1 minor #5, ClassfileUnpickler.scala:840). Still unaddressed. Queue for post-5b fix.

---

## Recommendation: STEER — add TASTy-side assertions to tests 4 and 5 before commit

The two BLOCKING directives from STEERING.md lines 80-88 are NOT_COMPLIED. The rest of Phase 5b has converged (UnifiedModelTest now present with 8 tests, no new asInstanceOf, no tests removed). Only tests 4 and 5 in JavaSymbolTest.scala need the TASTy contrast assertions added. Fix both, then the phase is ready to commit.
