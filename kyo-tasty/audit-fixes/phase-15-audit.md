# Phase 15 Audit

## Summary

Phase 15 is a clean implementation. The three-way lattice is correctly specified and coded for both OrType and AndType; Unknown propagates faithfully through Applied base-type resolution, checkParents transitive walks, and checkArgPairs. All 9 existing Boolean assertions were correctly migrated. No surviving Boolean coercions exist in production callsites. External modules (kyo-tasty-bench, kyo-tasty-fixtures, kyo-tasty-sbt) have zero usages of `isSubtypeOf`, so there is no API breakage. Two test coverage gaps are noted (not blockers): the budget-exhaustion test uses a `budget=0` shortcut rather than real deep recursion, and the unset-parents fixture is synthetic rather than derived from a documented production path. Code quality is clean; no em-dashes, no asInstanceOf, no Option/Some/None, no semicolons in new code; `derives CanEqual` is correct for a three-case enum.

## Findings

### 1. Three-way lattice - OK

OrType (line 69-76): `leftVerdict == Sub` short-circuits to Sub; else right is checked; NotSub only when both sides NotSub; else Unknown. Matches the spec exactly.

AndType (lines 111-119): same lattice shape, same short-circuit. The decisions doc notes this as dual: `A & B <: T` holds if either A or B is a subtype of T, which is correct (intersection types project onto either component). The combineAnd helper (line 164-167) is used for Wildcard and invariant Applied args with correct AND semantics. No confusion between combineAnd and combineOr at the call sites.

Applied base-type with Unknown base (lines 97-99): `if baseVerdict != Sub then if baseVerdict == Unknown then Unknown else NotSub`. Unknown base propagates Unknown correctly.

checkParents transitive walk (lines 207-224): when a transitive verdict is Unknown and no Sub was found, the code propagates Unknown rather than collapsing to NotSub. The tail is still walked so a later direct Sub can override.

Nothing/Null/refinements: Nothing is handled before the Named reflexivity case (line 81). Any is handled as the first sup match (line 66). Refinements, TermRef, and Function types fall to the catch-all `case _ => NotSub`, which is conservative (could in principle return Unknown for future ADT cases, but the current ADT is fully enumerated).

### 2. Boolean callsite migration - OK

Production grep found exactly two callsites: `Tasty.scala:1155` (extension method, return type `SubtypeVerdict`) and `Subtyping.scala:59` (internal `isSubtype`, return type `SubtypeVerdict`). All recursive calls inside `Subtyping.isSubtype` use the returned verdict in pattern matches or equality comparisons (`== Sub`, `== NotSub`, `== Unknown`). No `if a.isSubtypeOf(b) then` construct is present in production code; any such form would fail to compile because `SubtypeVerdict` is not `Boolean`. The compiler enforces this; no manual audit risk.

### 3. Existing test migration accuracy - OK

Tests 1-8 use `== Tasty.SubtypeVerdict.Sub` for positive cases and `== Tasty.SubtypeVerdict.NotSub` for negative cases. The negative cases (Test 3: String <: Int, Test 11: String <: Int) involve two fully-wired symbols with `_parents` set to empty chunks, so the parent walk terminates with NotSub rather than Unknown. The migration is semantically correct: these tests will not spuriously receive Unknown. Test 9 (Rec safety) was migrated to accept all three verdicts, which is the correct approach for a non-deterministic termination test.

### 4. Budget exhaustion test (Test 12) - NOTE

Test 12 calls `Subtyping.isSubtype(..., budget = 0)` directly. This exercises the branch at line 62 but does not prove that a real 65-deep Rec traversal would exhaust budget before diverging. The budget start is 64, so a Rec nested more than 64 levels would exhaust budget in production. Recommend adding a real-recursion test (construct a Rec chain of depth >= 65) in Phase 22 edge tests to close this gap. Not a blocker for Phase 15.

### 5. Unset parents test (Test 13) - NOTE

The fixture constructs a `fooSym` without calling `_parents.set(...)`. The decisions doc confirms this is the detection mechanism: a SingleAssign slot that has never been written. In a real classpath loading scenario, this would arise for a Symbol decoded from a .tasty file where the parent tree was present but not yet loaded (lazy decode or a stub symbol from a missing dependency). The test is synthetic but covers the operative code path. The documented production scenario (stub from missing dependency) is plausible. Not a blocker; a comment referencing the real production trigger would strengthen the test.

### 6. API compat - OK

Grep across kyo-tasty-bench, kyo-tasty-fixtures, and kyo-tasty-sbt found zero usages of `isSubtypeOf`. No external breakage.

### 7. Code quality - OK

No em-dashes in new code. No asInstanceOf, no raw Option/Some/None (Maybe is used throughout). No semicolons. `enum SubtypeVerdict derives CanEqual` is correct: CanEqual enables `==` comparisons without warning under strict equality; three enum cases are all value-comparable. `combineOr` is defined but used only in the two places in isSubtype where OrType and AndType are handled inline; the helper exists for potential future reuse. Not a quality issue.

## Recommendations

- Route to Phase 22 edge tests: add a real deeply-nested Rec chain (depth >= 65) to SubtypeTest to confirm the budget-decrement path terminates with Unknown in practice (not just via the `budget=0` shortcut).
- Consider adding a comment to Test 13 citing the production scenario (stub symbol from a missing classpath dependency) so the fixture's realism is self-documenting.
