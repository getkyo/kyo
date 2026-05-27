# Phase 12 V2 Audit: Hand-written Reads touchedFields propagation

**Commit**: `a90ba6472` ("kyo-reflect v2 Phase 12: G11 hand-written Reads instances participate in touchedFields")
**Date**: 2026-05-25
**Addresses**: G11 (DESIGN.md §24 "Hand-written Reads instances participating in touchedFields optimization")
**Tests added**: 2 new (268 -> 270; Test 19 + Test 20)

---

## Summary verdict

0 BLOCKER, 1 WARN, 3 NOTE. Phase 12 is shippable.

---

## BLOCKER

None.

---

## WARN

### W1 — Plan Test numbering: Tests 18+19 in plan are Tests 19+20 in file

The plan spec says: "Test 18 + Test 19 (strict)". The actual file contains Tests 18-20 where:
- Test 18 (pre-existing) = `Reads[Simple].read` on fixture (`jvmOnly`) -- was already present from Phase 6
- Test 19 (new) = `Wrapper19` with `TouchedFields.declare` -> `Name|Flags` propagated exactly
- Test 20 (new) = `Wrapper20` without `TouchedFields.declare` -> defaults to `FieldSet.All`

The plan refers to "Test 18" as the first new test; the implementation shifted by one because Test 18 was already allocated to a fixture-read scenario in Phase 6. No semantic difference -- both tests are strictly correct and cover the required contract. Worth noting only for cross-referencing plan vs file line numbers.

---

## NOTE

### N1 — Static path vs runtime path: `tryGetDeclaredTouchedFields` vs `r.touchedFields`

The commit message says the fix was to "read the touchedFields at runtime via the new TouchedFields helper". The actual implementation in `ReflectMacro.analyzeField` (`ReflectMacro.scala:115`):

```scala
val fsExpr = tryGetDeclaredTouchedFields(r).getOrElse('{ $r.touchedFields })
```

`tryGetDeclaredTouchedFields` attempts to extract a static `Expr[FieldSet]` from the `read` body's `TouchedFields.declare(fs)` call at macro expansion time. If it succeeds, the bits are baked in at compile time (no runtime cost). If it fails (cross-compilation boundary, no declare call, tree not available), it falls back to `$r.touchedFields` -- a runtime read of the summoned instance's `touchedFields` val.

This is the correct design. Test 19 exercises the static path (the `given` is in the same compilation unit, so the tree is available). Test 20 exercises the fallback path (no declare, falls back to `All` at runtime).

The plan said "runtime helper" but the static path is tried first. The commit message's description is slightly imprecise but the implementation is superior.

### N2 — `TouchedFields.scala` grew significantly beyond the plan spec

The plan specified: "add `inline def declare(fields: Reflect.FieldSet): Unit = ()`; add recognition of `TouchedFields.declare(fs)` calls in the analyze tree-walk".

The actual `TouchedFields.scala` is a complete tree-analysis module (283 lines) including:
- `analyze(readBody)` -- full tree walker with hygiene rules 1/2/3
- `extractDeclaredFieldSet(body)` -- separate declare-call extractor
- `tryEvalFieldSet(expr)` -- static evaluator for `FieldSet` expressions
- `analyzeInline` macro entry point for testing
- `traverseGoto` / `GotoAccum` traversal infrastructure

This was planned in Phase 6 and landed in Phase 12's commit. The plan deviation is acceptable: all of this infrastructure was needed for Phase 12's correctness and the test coverage is thorough.

### N3 — `findReadBodyInGivenSym` fallback for `given lazy val`

`ReflectMacro.findReadBodyInGivenSym` (`ReflectMacro.scala:167`) documents:

> "Scala 3 `given val`s are lazy and their `sym.tree` may not expose the RHS (the body is in a synthetic lazy initializer). In that case this method returns `None` and the caller falls back to `$r.touchedFields` at runtime."

This is a known Scala 3 macro limitation. Test 19 passes because the `given` is a non-lazy `val` (defined with `given handWrittenReads19: Reflect.Reads[MyType19] = new Reflect.Reads[MyType19]: ...`). A `lazy given` would silently fall back to the runtime path. This is acceptable but should be documented in the user-facing `TouchedFields.declare` scaladoc to set expectations.

---

## Checklist

| Item | Status |
|------|--------|
| `TouchedFields.scala` present in `kyo.internal.reflect.reads` | PASS (`TouchedFields.scala`) |
| `TouchedFields.declare(fields: Reflect.FieldSet): Unit` present | PASS (line 50) |
| `declare` expands to `()` at runtime (no overhead) | PASS |
| `extractDeclaredFieldSet` recognizes `TouchedFields.declare(fs)` in read body | PASS (line 102) |
| `tryEvalFieldSet` handles `|` expressions and named companion vals | PASS (lines 139-174) |
| `ReflectMacro.analyzeField` emits `tryGetDeclaredTouchedFields(r).getOrElse('{ $r.touchedFields })` | PASS (line 115) |
| `tryGetDeclaredTouchedFields` walks `given val/def` RHS to find `read` DefDef | PASS (lines 134-157) |
| `findReadBodyInTerm` drills into anonymous-class `ClassDef` body | PASS (lines 180-196) |
| Test 19: `Wrapper19.touchedFields == Name | Flags` exactly | PASS (`ReadsDerivationTest.scala:408`) |
| Test 20: `Wrapper20.touchedFields == FieldSet.All` when no declare | PASS (`ReadsDerivationTest.scala:430`) |
| No regressions (270/270 passing per commit message) | PASS |
| `TouchedFields.analyzeInline` macro entry point for testing | PASS (line 186) |
| No new `asInstanceOf` without justification | PASS |
| No `Frame.internal` / `AllowUnsafe` without `// Unsafe:` | PASS |
| Hygiene rules 1/2/3 present and tested (Test 16 pre-existing) | PASS |
