# Phase 6 In-Flight Review (pulse 4)

Pulse 4: 2026-05-24T22:09:00Z
Files reviewed:
- kyo-reflect/STEERING.md (Phase 6 critical fixes + lazy design + overnight directive)
- kyo-reflect/PHASE-6-INFLIGHT-REVIEW-3.md (prior pulse findings)
- kyo-reflect/shared/src/test/scala/kyo/ReadsDerivationTest.scala (full, 381 lines)
- kyo-reflect/shared/src/main/scala/kyo/internal/ReflectMacro.scala (grep scan: buildProduct, caseFields.length, 64-cap)

## Remaining directives

| Directive | Verdict | Citation |
|---|---|---|
| ReadsDerivationTest.scala exists with all 18 plan-mandated tests, strict assertions | COMPLIED (with 2 minor rigor notes — see below) | File exists at 381 lines; 18 tests present, all named per plan; `sbt 'kyo-reflect/testOnly *ReadsDerivationTest'` reports 18/18 PASSED |
| 64-field cap guard (report.errorAndAbort at macro time, NOT runtime, for caseFields.length > 64) | NOT_COMPLIED | `buildProduct` (lines 124-175) has no `caseFields.length > 64` check; grep for `"64"`, `"> 64"`, `"maxField"`, `"FieldCap"` returns zero hits in ReflectMacro.scala |

## Test rigor

- Tests using public API (`derives Reflect.Reads`) on LHS: 13/18 (tests 1-8, 11, 15, 17; tests 5/12/13/14/18 construct via `stubSymbol` or `summon` and call `.read`, which is acceptable as the value-under-test is the derived instance itself)
- Weakened assertions (details):

  **Test 1** (`assert(r != null)`) and **Test 8** (`assert(r != null, ...)`): Null-only checks. Test 8 is especially weak -- the plan spec says "compiles (recursive case class handled via `lazy val`)" which at minimum means a compile-time check, but the assertion only verifies non-null, not that read actually works. The plan does NOT mandate a decode-equality test for Node (only a compile test), so this is acceptable per spec for test 8. Test 1's null check is redundant (summon would fail at compile time if the instance cannot be derived) but not harmful.

  **Test 9** has a loose disjunction: `err.nonEmpty || typeCheckErrors(...).exists(...)`. The first arm `err.nonEmpty` fires if `summonInline[kyo.Reflect.Reads[SumType]]` fails for ANY reason (not specifically the "hand-written" message), meaning the test could pass even if the error message is generic. The plan spec says the error must contain "hand-written". The `||` guard means this test is technically rigor-weak: it passes even if the macro emits a wrong error message, as long as *some* error is produced. The underlying behavior is correct (sealed traits do produce an error), but the message check is not guaranteed.

  **Tests 12 and 13**: Both have a `succeed` branch for `ReflectError.NotImplemented`, meaning the test passes when the stub's `declarations` / `companion` throws `NotImplemented`. This is honest (the comment explains it proves the correct call path was taken) and acceptable for Phase 6 scope.

- Recursive fixture coverage: Test 8 only checks `r != null` (compile-level). No decode-equality check for `Node`. The plan spec for test 8 says only "compiles", so this is within spec.

- Sum-type guard assertions: Tests 9 and 10 both use `typeCheckErrors`. Test 9's assertion is weak (see above). Test 10's assertion is strict: `errors2.nonEmpty && errors2.exists(e => e.message.contains("abstract type parameter") || e.message.contains("monomorphic"))` -- both predicate halves required, correct.

## Compile/test status

- Compile: PASS (`sbt 'kyo-reflect/Test/compile'` -- success in 3 s)
- Tests: 18/18 passing (`sbt 'kyo-reflect/testOnly *ReadsDerivationTest'` -- all 18 passed, 631 ms total)

## Drift / regressions

- `asInstanceOf` at ReflectMacro.scala:347 is `TypeApply(Select.unique(apply, "asInstanceOf"), List(TypeTree.of[t]))` -- this is a quoted AST construction node (generates user-side `.asInstanceOf[t]` in the macro output), NOT a Scala runtime cast in the macro body. Per pulse 3 analysis this is correct-by-construction. No new raw Scala casts.
- No `null.asInstanceOf[T]` found.
- No TODO, FIXME, "for now", or "skipped" in any reads/ or test file.
- PHASE-6-IMPL-NOTES.md still absent (agent did not create it, same as pulse 3). Minor operationally.

## CRITICAL (steer immediately)

1. **64-field cap still absent** (directive from STEERING.md "Phase 6 lazy self-reference design"). `buildProduct` does not check `caseFields.length > 64` before constructing bitmasks. A product with 65+ fields will silently produce overflowed bitmasks at macro expansion time with no user-visible error. Required fix: add `if caseFields.length > 64 then report.errorAndAbort(s"Reflect.Reads.derived supports at most 64 fields; ${aSym.name} has ${caseFields.length}")` inside `buildProduct` immediately after `val caseFields = aSym.caseFields` and before the `fieldAnalyses` map. This is the sole remaining BLOCKING gap.

## MINOR (queue for post-commit audit)

- Test 9's assertion is weak: the `err.nonEmpty || ...` disjunction allows the test to pass even if the "hand-written" phrase is absent from the error message. The right form is to assert exclusively on the second arm (which checks the message). Does not affect correctness of the macro -- the sealed-trait guard fires correctly -- but the test does not verify the *message* robustly. Flag for Phase 6 audit.

- Test 1 and Test 8 use `assert(r != null)`. For Test 1 this is harmless (summon fails at compile if the instance cannot be derived; the null check is redundant). For Test 8 the null check is the correct spec level (compile-only per plan). Both acceptable.

- `readFieldsLazy` uses `self: => Reflect.Reads[A]` (call-by-name) vs plain `self: Reflect.Reads[A]` in the blessed template. CBN is strictly safer; accept as improvement, document in audit.

- Stub `booleanReads`/`intReads`/`longReads` in ReadsInstances return placeholder values (false/0/0L); these are Phase 6 scope stubs. Flag for Phase 6 audit.

- PHASE-6-IMPL-NOTES.md absent; does not block commit.

## Recommendation: STEER: add 64-field cap guard in buildProduct (one line), then CLEAN to commit
