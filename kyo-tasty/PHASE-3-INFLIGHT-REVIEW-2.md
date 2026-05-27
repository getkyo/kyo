# Phase 3 In-Flight Review (pulse 2)

Pulse 2: 2026-05-24T00:30Z
Files reviewed:
- STEERING.md (full, "Phase 3 pulse 1 critical fixes" section)
- PHASE-3-INFLIGHT-REVIEW-1.md (full, for context)
- AstUnpickler.scala lines 1-60 (Pass1Result definition)
- Constant.scala (full, 100 lines)
- SymbolKind.scala (full, 41 lines)
- DeclarationTableTest.scala (full, 82 lines)
- AstUnpicklerTest.scala (head + line count: 328 lines, 14 test scenarios confirmed)

## STEERING compliance

| # | Directive | Verdict | Citation |
|---|---|---|---|
| 1 | Pass1Result.placeholders field added | NOT_COMPLIED | AstUnpickler.scala lines 31-35: `case class Pass1Result(symbols, addrMap, rootSymbol)` — `placeholders` is absent, `rootSymbol` is still the third field |
| 2 | null.asInstanceOf removal at Constant.scala:73 | NOT_COMPLIED | Constant.scala line 73 still reads: `Reflect.Constant.ClassConst(Reflect.Type.Named(null.asInstanceOf[Reflect.Symbol]))` — identical to pulse 1 state |
| 3 | fromTagAndFlags(tag: Int, flags: Long): SymbolKind added | NOT_COMPLIED | SymbolKind.scala has only `fromTypedefTemplateFlags`, `fromTypedefTypeFlags`, `fromValdefFlags` — no `def fromTagAndFlags` anywhere in the file |
| 4 | Test 23 latch ordering: release AFTER populate | NOT_COMPLIED | DeclarationTableTest.scala line 70: `_ <- latch.release` precedes line 71-75: `_ <- Sync.defer { ... table.populate(...) }` — reader is unblocked before populate runs; broken ordering unchanged from pulse 1 |

## New drift since pulse 1

- AstUnpicklerTest.scala has been created (328 lines, 14 test scenarios confirmed by `grep -c` returning 14). This resolves the CRITICAL from pulse 1 (tests 7-20 missing). The file now exists with the correct count.
- No new `null.asInstanceOf` patterns found beyond the pre-existing Constant.scala:73 violation (Memo.scala and SingleAssign.scala casts are pre-Phase-3 infrastructure and use the established internal unsafe pattern for sentinel-value tricks, not the same category of violation).
- No files renamed or moved off-plan.
- No test count reduction in AstUnpicklerTest (14 scenarios match the plan mandate for tests 7-20).
- `Pass1Result.rootSymbol` field added beyond plan spec remains; supervisor must confirm whether intentional.

## CRITICAL (steer immediately)

All four STEERING directives from pulse 1 remain unapplied. The agent progressed on AstUnpicklerTest.scala (CRITICAL from pulse 1 resolved) but did NOT apply any of the four BLOCKING fixes written into STEERING.md. All four must be applied before commit:

1. **Pass1Result.placeholders** — field is absent. Add `placeholders: Chunk[(Int, Reflect.Name)]` (or `Chunk[UnresolvedRef]` once that type exists) as the third field per plan line 202. `rootSymbol` may stay as an additional field if the agent can justify it, but `placeholders` is non-negotiable.

2. **null.asInstanceOf at Constant.scala:73** — still present verbatim. Replace with an approach that avoids the cast: either `Abort.fail(ReflectError.NotImplemented("CLASSconst resolution deferred to Phase 4"))` (simplest, honest), or a dedicated `Reflect.Constant.UnresolvedClassConst` placeholder case if the design can absorb it. No `asInstanceOf`, no `null`.

3. **fromTagAndFlags missing** — still absent. Add `def fromTagAndFlags(tag: Int, flags: Long): Reflect.SymbolKind` to `SymbolKind.scala` as a top-level dispatcher that delegates to the three existing narrower helpers based on `tag`. The three helpers may remain as private implementation detail.

4. **Test 23 latch ordering broken** — still broken. Move `latch.release` to AFTER `table.populate`. The for-comprehension must sequence: `table.populate(...)` then `latch.release`. Reader fiber awaits latch, then reads. This ensures the race the test is designed to verify is actually possible.

## MINOR (queue for post-commit audit)

- `Pass1Result.rootSymbol` is an unplanned field addition. Supervisor should confirm whether it is intentional or incidental drift; if incidental, remove it.
- AstUnpicklerTest.scala now exists but has not been verified to compile and run green (no test output file present). Targeted compile + run must be done before the supervisor commits.
- `computeBinaryName` uses '.' for all separators (not '$' for nested classes); likely deferred to Phase 4 but should be confirmed in PROGRESS.md.
- `AstUnpickler.readPass1` returns `Sync & Abort[ReflectError]` where the plan specifies `Abort[ReflectError]` only; minor effect-row widening, confirm intentional.

## Recommendation: STEER — all four STEERING directives from pulse 1 are still open; agent must apply them before this phase can reach commit-ready state
