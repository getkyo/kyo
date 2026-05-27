# Phase 4 In-Flight Review (pulse 1)

Pulse 1: 2026-05-24T00:00Z
Files reviewed:
- execution-plan.md lines 264-333
- PHASE-4-PREP.md (full, 1000 lines)
- STEERING.md (full)
- kyo-reflect/shared/src/main/scala/kyo/internal/reflect/type_/TypeArena.scala (249 lines)
- kyo-reflect/shared/src/main/scala/kyo/internal/reflect/type_/TypeOps.scala (61 lines)
- kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/TypeUnpickler.scala (566 lines)
- kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/UnresolvedRef.scala (17 lines)
- kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/AstUnpickler.scala lines 1-98 (partial)
- kyo-reflect/shared/src/test/scala/kyo/TypeArenaTest.scala (100 lines)
- kyo-reflect/shared/src/test/scala/kyo/TypeOpsTest.scala (113 lines)

---

## Plan anchor

### Files to produce (plan lines 269-276)

| File | Present in dirty tree |
|---|---|
| `type_/TypeArena.scala` | YES (untracked `??`) |
| `type_/TypeOps.scala` | YES (untracked `??`) |
| `tasty/TypeUnpickler.scala` | YES (untracked `??`) |
| `query/UnresolvedRef.scala` | YES (untracked `??`) |
| `shared/src/test/scala/kyo/TypeArenaTest.scala` | YES (untracked `??`) |
| `shared/src/test/scala/kyo/TypeUnpicklerTest.scala` | MISSING - not in dirty tree, not in test dir |
| `shared/src/test/scala/kyo/TypeOpsTest.scala` | YES (untracked `??`) |

### Files to modify (plan line 279)

| File | Status |
|---|---|
| `tasty/AstUnpickler.scala` | PRESENT in dirty tree (`M`). Pass1Result has `placeholders` field and `Chunk.empty` at line 95. TypeUnpickler is NOT wired into AstUnpickler for parent/signature positions (see Reward-hacking check below). |

### Test count

Plan mandates 24 tests across 3 test files.
- TypeArenaTest: 5 tests (tests 1-5). PRESENT.
- TypeOpsTest: 6 tests (tests 6-11). PRESENT.
- TypeUnpicklerTest: 13 tests (tests 12-24). MISSING FILE.

**Actual test count in dirty tree: 11 out of 24 (tests 12-24 absent).**

---

## Reward-hacking checks

| Pattern | Verdict | Citation |
|---|---|---|
| Verification commands actually run | UNKNOWN - pulse 1 is before any reported test run; no evidence of sbt command output in reviewed files | n/a |
| Compile-only "success" claim | NOT YET APPLICABLE - tests not wired | n/a |
| Priority inference ("edge-case, skip") | NOT OBSERVED in written code | n/a |
| Scope substitution ("simpler equivalent") | FLAG - TypeUnpicklerTest.scala entirely absent; 13 of 24 plan-mandated tests simply not written | plan lines 301-313 |
| Foreach-discards-assert | NOT OBSERVED | n/a |
| Stale-state / tautological matchers | MINOR CONCERN - TypeArenaTest test 3 calls `canon.intern(t)` twice after merge and checks `c1 eq c2`, but the merge already put `t` in `canon`; the assertion is trivially true because both calls hit the same interned entry. It does NOT verify that t1 from arena1 and t2 from arena2 (distinct references) map to reference-equal results, which is the actual semantic. Compare: test 5 does this correctly. Test 3 is weakened. | TypeArenaTest.scala lines 47-61 |
| Pass1Result.placeholders actually POPULATED (not Chunk.empty) per plan line 279 | FLAG - `placeholders = Chunk.empty` is hardcoded in `AstUnpickler.runPass1` (AstUnpickler.scala line 95). Plan requires TypeUnpickler.readType to be wired for parent/signature positions so placeholders are actually accumulated. Current state: TypeUnpickler exists but is NOT called from AstUnpickler; placeholders are always empty. | AstUnpickler.scala line 95 |
| All 5 normalization smart constructors implemented (FunctionN/TupleN/ContextFunctionN/Array/AndType+Singleton) | CLEAN - all 5 cases present in TypeOps.scala lines 33-52 | TypeOps.scala |
| Phase C merge with cycle handling (inProgress map) per DESIGN section 9 | CLEAN - `inProgress` map present in TypeArena.merge (TypeArena.scala line 33); `internRec` checks canonical then inProgress then recurses with cycle-break placeholder | TypeArena.scala lines 81-95 |
| SHAREDtype dedup cache implemented | CLEAN - `addrCache` HashMap in TypeUnpickler.readType; populated in readTypeNode after each decode (TypeUnpickler.scala lines 68-115) | TypeUnpickler.scala |

---

## Drifting checks

| Pattern | Verdict | Citation |
|---|---|---|
| Public API signatures match plan | CLEAN - TypeArena.intern/merge/canonical, TypeOps.applied/andType/mkArray, TypeUnpickler.readType all match plan verbatim | plan lines 270-272 |
| No off-plan architecture | FLAG - TypeUnpickler.readType has a DIFFERENT signature than the plan specifies. Plan (line 272): `def readType(view, names, addrMap, arena): Reflect.Type < Abort[ReflectError]`. Actual (TypeUnpickler.scala line 59-65): `def readType(view, names, addrMap, arena, home): (Reflect.Type, Chunk[UnresolvedRef]) < (Sync & Abort[ReflectError])`. Extra `home: ClasspathRef` param; return type is a tuple, not just `Reflect.Type`. These deviations may be justified by the PREP doc but they are undocumented deviations from the plan's stated signature. | TypeUnpickler.scala lines 59-65 vs plan line 272 |
| No cross-cutting refactor outside Phase 4 | CLEAN - only AstUnpickler.scala and the 4 new files touched | git status |
| Internal helpers stay in `kyo.internal.reflect.{type_,tasty}.*` | CLEAN - all files in correct packages | file headers |
| No `asInstanceOf` introduced | CLEAN - none found in Phase 4 files | grep |
| No `null` introduced | FLAG - `null` used as `owner` parameter in 4 `makeSymbol` calls in TypeUnpickler.scala (lines 39, 102, 472, 514) and in MatchCaseSentinel at line 39. The plan/PREP doc shows `null` for owner in synthetic sentinels which matches Phase 3 precedent, but STEERING.md does not explicitly allow it, and `feedback_no_casts` combined with null-safety is a project concern. These are synthetic symbols with no real owner; it is the same pattern as AstUnpickler's root symbol (line 85). Likely acceptable but warrants a note. | TypeUnpickler.scala lines 39, 102, 472, 514 |
| UnresolvedRef is the canonical type (Phase 3 forward-decl stub removed or promoted) | CLEAN - UnresolvedRef is a `final case class` in `kyo.internal.reflect.query`; no stub remains | UnresolvedRef.scala |

---

## Scope-cutting checks (per plan-mandated test leaf, all 24)

| Leaf | Status | Notes |
|---|---|---|
| 1: TypeArenaTest - intern same Named(sym) returns same ref | PRESENT_STRICT | TypeArenaTest lines 23-31 |
| 2: TypeArenaTest - intern Applied with different args returns different refs | PRESENT_STRICT | TypeArenaTest lines 34-44 |
| 3: TypeArenaTest - merge of two arenas with same type produces one entry | PRESENT_WEAKENED | Test uses `canon.intern(t)` twice (same t), not t1-from-arena1 vs t2-from-arena2. Assertion trivially passes even if merge is broken. |
| 4: TypeArenaTest - merge Rec/RecThis cycle, no infinite loop | PRESENT_STRICT | TypeArenaTest lines 63-78; builds Rec(RecThis(Rec(...))), merges, asserts values.nonEmpty |
| 5: TypeArenaTest - after merge, types from two arenas are reference-equal | PRESENT_STRICT | TypeArenaTest lines 81-98; creates t1/t2 as separate ByName(Named(sym)) objects, merges both arenas, asserts canon.intern(t1) eq canon.intern(t2) |
| 6: TypeOpsTest - Function2 normalization | PRESENT_STRICT | TypeOpsTest lines 52-62 |
| 7: TypeOpsTest - Tuple2 normalization | PRESENT_STRICT | TypeOpsTest lines 65-73 |
| 8: TypeOpsTest - ContextFunction1 normalization | PRESENT_STRICT | TypeOpsTest lines 76-86 |
| 9: TypeOpsTest - Array[T] normalization | PRESENT_STRICT | TypeOpsTest lines 89-97 |
| 10: TypeOpsTest - andType(Singleton, X) => X | PRESENT_STRICT | TypeOpsTest lines 100-104 |
| 11: TypeOpsTest - andType(X, Singleton) => X | PRESENT_STRICT | TypeOpsTest lines 107-111 |
| 12: TypeUnpicklerTest - TYPEREFsymbol for scala.Int | MISSING | TypeUnpicklerTest.scala not created |
| 13: TypeUnpicklerTest - BYNAMEtype | MISSING | TypeUnpicklerTest.scala not created |
| 14: TypeUnpicklerTest - REPEATEDtype | MISSING | TypeUnpicklerTest.scala not created |
| 15: TypeUnpicklerTest - APPLIEDtype List[String] | MISSING | TypeUnpicklerTest.scala not created |
| 16: TypeUnpicklerTest - SHAREDtype returns same reference | MISSING | TypeUnpicklerTest.scala not created |
| 17: TypeUnpicklerTest - TYPELAMBDAtype params.size | MISSING | TypeUnpicklerTest.scala not created |
| 18: TypeUnpicklerTest - ANNOTATEDtype | MISSING | TypeUnpicklerTest.scala not created |
| 19: TypeUnpicklerTest - ORtype | MISSING | TypeUnpicklerTest.scala not created |
| 20: TypeUnpicklerTest - ANDtype | MISSING | TypeUnpicklerTest.scala not created |
| 21: TypeUnpicklerTest - MATCHtype cases.size==2, scrutinee is param | MISSING | TypeUnpicklerTest.scala not created |
| 22: TypeUnpicklerTest - CONSTANTtype(IntConst(42)) | MISSING | TypeUnpicklerTest.scala not created |
| 23: TypeUnpicklerTest - RECtype/RECthis no stack overflow | MISSING | TypeUnpicklerTest.scala not created |
| 24: TypeUnpicklerTest - cross-file TYPEREFin produces UnresolvedRef | MISSING | TypeUnpicklerTest.scala not created |

---

## CRITICAL (steer immediately)

1. **TypeUnpicklerTest.scala is missing.** Tests 12-24 (13 of 24 plan-mandated tests) do not exist. This is the largest test file in Phase 4 and covers all TypeUnpickler decode paths. The agent must create this file before Phase 4 is considered complete. Per STEERING.md scope integrity: silently dropping tests is not permitted.

2. **Pass1Result.placeholders is always Chunk.empty.** AstUnpickler.scala line 95 hardcodes `placeholders = Chunk.empty`. The plan (line 279) requires TypeUnpickler.readType to be wired into AstUnpickler for parent and signature positions so placeholders actually accumulate. Without this wiring, test 24 (UnresolvedRef placeholder) cannot pass on a real TASTy decode path, and Phase C has no input. The agent has implemented TypeUnpickler but not wired it into AstUnpickler.

3. **TypeUnpickler.readType signature deviates from plan.** Plan says: `def readType(...): Reflect.Type < Abort[ReflectError]`. Actual: `def readType(..., home: ClasspathRef): (Reflect.Type, Chunk[UnresolvedRef]) < (Sync & Abort[ReflectError])`. The extra `home` param and tuple return are undocumented; they may be implementation-justified but constitute off-plan API. If AstUnpickler wiring is added, the caller signature must match.

---

## MINOR (queue for post-commit audit)

1. **Test 3 (TypeArenaTest) is weakened.** The assertion `canon.intern(t) eq canon.intern(t)` is trivially true regardless of merge correctness. Correct form: intern two separately-constructed-but-structurally-equal values from two arenas and assert reference equality (as test 5 does). Should be strengthened to match test 5's pattern.

2. **`null` as `owner` in TypeUnpickler synthetic symbols.** Four `makeSymbol` calls pass `null` as owner. This follows AstUnpickler's pattern for the root symbol and appears intentional, but it is not explicitly documented as allowed. Low risk; flag for completeness.

3. **makeNamedSym in TypeOpsTest builds owner chain twice.** TypeOpsTest.scala lines 22-42 run two identical `foldLeft` loops; the first result is discarded. The final symbol is built correctly, but the dead first loop wastes allocations in tests. Cosmetic; does not affect correctness.

---

## Recommendation: STEER: Write TypeUnpicklerTest.scala (13 missing tests) and wire TypeUnpickler into AstUnpickler for parent/signature positions so Pass1Result.placeholders is populated
