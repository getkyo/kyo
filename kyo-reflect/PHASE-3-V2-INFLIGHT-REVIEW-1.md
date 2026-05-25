# Phase 3 v2 In-Flight Review 1 (Pulse 1)

**Scope**: G21 (Symbol.parents) + G22 (Symbol.typeParams) + G23 (Symbol.declarations)
**Date**: 2026-05-25

---

## Checklist

| Pattern | Verdict | Citation |
|---|---|---|
| Reflect.scala line 252 stub removed | CLEAN | `Reflect.scala:254` -- `parents` reads `_parents.get()` via `SingleAssign`, no `stub(...)` call |
| Reflect.scala line 260 stub removed | CLEAN | `Reflect.scala:261` -- `typeParams` reads `_typeParams.get()` |
| Reflect.scala line 268 stub removed | CLEAN | `Reflect.scala:268` -- `declarations` reads `_declarations.get()` |
| 3 stubs replaced with real read | CLEAN | All three call `home.get().checkOpen.andThen` then `AllowUnsafe.embrace.danger` then `SingleAssign.get()` |
| Internal Symbol has populated fields | CLEAN | `Reflect.scala:224-227` -- `_parents`, `_typeParams`, `_declarations` as `SingleAssign` fields with `private[kyo]` visibility |
| AstUnpickler populates parents/typeParams/declarations | CLEAN | `AstUnpickler.scala:116-146` -- `parentsBySymbol` and `childrenByOwner` maps built during walk, carried in `Pass1Result`; `ClasspathOrchestrator.scala:220-243` consumes them |
| ClassfileUnpickler populates the same | CLEAN | `ClassfileUnpickler.scala:67-82` -- `result.classSymbol._parents.set(result.parents)`, `_typeParams`, `_declarations` set; member/typeParam symbols get `Chunk.empty` for all three |
| 7 new tests added | FLAG -- see below | Only 3 of 7 plan-specified tests are present |
| No new asInstanceOf in production | CLEAN | New `asInstanceOf` hits are in JS/test files only; `SingleAssign.scala` has a justified cast with comment |
| No Frame.internal added | CLEAN | No grep hits |
| No em-dashes in modified files | CLEAN | No hits |
| No new AllowUnsafe sites without // Unsafe: comment | CLEAN | All new `embrace.danger` blocks preceded by `// Unsafe:` comment |
| No `null` introduced in new code | CLEAN | `null` in production files is in pre-existing classfile/snapshot code, not in Phase 3 new blocks |
| No `var` for shared state | CLEAN | `var` uses in `ClassfileUnpickler` are loop counters (`var i`, `var k`), not shared mutable state |

---

## Test Leaf Audit (plan: 7 tests)

The plan specifies tests in two files. Current state:

### QueryApiTest (plan: 4 new tests)

| Plan test | Status |
|---|---|
| T1: `sym.parents` returns non-empty `Chunk[Type]` with `AnyRef` parent for fixture class | MISSING |
| T2: generic fixture class `GenFoo[T,U]` produces `sym.typeParams` of length 2 with correct names | MISSING |
| T3: `sym.declarations` for a class with two known methods returns both; each decl's `owner eq sym` | MISSING |
| T4: `sym.parents` after classpath close returns `ClasspathClosed` | MISSING |
| (bonus, plan T5): Java `String` proxy -- `sym.parents` has Object, `sym.typeParams` empty, `sym.declarations` non-empty | MISSING |

QueryApiTest has a test verifying `ClasspathClosed` (Test 15) but it exercises `findClass`, not `sym.parents`. No test exercises the three new public accessors end-to-end through `Classpath`.

### AstUnpicklerTest (plan: 2 new tests)

| Plan test | Status |
|---|---|
| T6: `Pass1Result.parentsBySymbol` for fixture class contains entry with non-empty parents | PRESENT (Test 22 -- "TEMPLATE parents flow into placeholders" verifies `r.placeholders.nonEmpty` which is the indirect evidence, but does NOT directly assert `parentsBySymbol`) -- WEAKENED |
| T7: `Pass1Result.childrenByOwner` for fixture class maps class symbol to all declared members | MISSING |

### ClassfileReaderTest (extra -- not in plan, present)

Three tests in `ClassfileReaderTest.scala` (lines 71, 81, 94) do cover `parents`, `typeParams`, `declarations` at the `ClassfileResult` layer (raw result fields, not the public `Symbol` accessors). These are not the plan's 7 tests and test `ClassfileResult` internals, not the public API.

### Summary

- Tests present (plan-specified): 0 strictly clean, 1 weakened (T6 via indirect placeholder check).
- Tests missing from plan: T1, T2, T3, T4 (QueryApiTest), T7 (AstUnpicklerTest) = 5 missing.
- Extra coverage present: 3 ClassfileReaderTest tests at the raw result layer.

---

## Scope-Cutting Observations

1. The core wiring (SingleAssign fields, AstUnpickler maps, ClasspathOrchestrator assignment loop, ClassfileUnpickler direct set) is fully implemented and structurally correct.
2. The public accessor tests -- the primary validation surface for G21/G22/G23 from a user perspective -- are absent. The plan's 4 QueryApiTest tests (T1-T4) and 1 AstUnpicklerTest test (T7) need to be written.
3. T6 (AstUnpicklerTest parentsBySymbol) exists in weakened form: the test asserts `r.placeholders.nonEmpty` as a proxy for parent decoding rather than directly asserting `r.parentsBySymbol` contains the expected entry. This should be strengthened to assert directly on `r.parentsBySymbol`.

---

## Action Items for Agent

- Add 4 tests to `QueryApiTest` per plan T1-T4.
- Add 1 test to `AstUnpicklerTest` per plan T7 (childrenByOwner assertion).
- Strengthen existing `AstUnpicklerTest` Test 22 to also assert `r.parentsBySymbol` directly (T6 strengthening).
- No production code changes required -- implementation is complete and clean.
