# Phase 4 Audit (commit ad01c90b7)

Audit timestamp: 2026-05-24
HEAD reviewed: ad01c90b752318e16efe83d31c2dae6b95798da7
Scope: Phase 4 (Type model + per-fiber arenas + Phase C merge), execution-plan.md lines 264-333.

Files read end-to-end at HEAD:
- kyo-reflect/shared/src/main/scala/kyo/internal/reflect/type_/TypeArena.scala (248 lines)
- kyo-reflect/shared/src/main/scala/kyo/internal/reflect/type_/TypeOps.scala (60 lines)
- kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/TypeUnpickler.scala (609 lines)
- kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/UnresolvedRef.scala (16 lines)
- kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/AstUnpickler.scala (452 lines, modified)
- kyo-reflect/shared/src/test/scala/kyo/TypeArenaTest.scala (100 lines)
- kyo-reflect/shared/src/test/scala/kyo/TypeOpsTest.scala (117 lines)
- kyo-reflect/shared/src/test/scala/kyo/TypeUnpicklerTest.scala (441 lines)
- kyo-reflect/shared/src/test/scala/kyo/AstUnpicklerTest.scala (modified; total 396 lines including +2 Phase 4 tests)
- PHASE-4-INFLIGHT-REVIEW-1.md, PHASE-4-INFLIGHT-REVIEW-2.md
- STEERING.md (Phase 4 directive cleared at line 68)

## Test count

- Plan: 24 tests across TypeArenaTest (5), TypeOpsTest (6), TypeUnpicklerTest (13).
- Implemented: 26 (5 + 6 + 13 + 2 Phase 4 wiring tests in AstUnpicklerTest for TEMPLATE-parents and PlainClass placeholders).
- Per-leaf status:
  - 1 TypeArenaTest "intern same Named(sym) returns same ref" — PRESENT_STRICT (lines 23-31)
  - 2 TypeArenaTest "intern Applied with different args" — PRESENT_STRICT (34-44)
  - 3 TypeArenaTest "merge two arenas same type one entry" — PRESENT_WEAKENED. Lines 47-61 intern the SAME object `t` into a1 and a2, merge both into canon, then assert `canon.intern(t) eq canon.intern(t)`. The two intern calls hit the same key with the same object; the assertion is trivially true regardless of merge correctness. Should use two separately-allocated structurally-equal values (the pattern test 5 uses).
  - 4 TypeArenaTest "merge Rec/RecThis cycle" — PRESENT_STRICT (63-78)
  - 5 TypeArenaTest "after merge two arenas reference-equal" — PRESENT_STRICT (80-98)
  - 6 TypeOpsTest "Function2 normalization" — PRESENT_STRICT (51-63)
  - 7 TypeOpsTest "Tuple2 normalization" — PRESENT_STRICT (65-75)
  - 8 TypeOpsTest "ContextFunction1 normalization" — PRESENT_STRICT (77-89)
  - 9 TypeOpsTest "Array[T] normalization" — PRESENT_STRICT (91-101)
  - 10 TypeOpsTest "andType(Singleton, X) => X" — PRESENT_STRICT (103-108)
  - 11 TypeOpsTest "andType(X, Singleton) => X" — PRESENT_STRICT (110-115)
  - 12 TypeUnpicklerTest "TYPEREFsymbol for scala.Int" — PRESENT_STRICT (92-110)
  - 13 TypeUnpicklerTest "BYNAMEtype" — PRESENT_STRICT (112-130)
  - 14 TypeUnpicklerTest "REPEATEDtype" — PRESENT_STRICT (132-150). Note: plan says REPEATEDtype; impl decodes via the REPEATED tag (CASE 5, line 448 of TypeUnpickler). Test uses TastyFormat.REPEATED; matches impl. Functionally equivalent.
  - 15 TypeUnpicklerTest "APPLIEDtype List[String]" — PRESENT_STRICT (152-179)
  - 16 TypeUnpicklerTest "SHAREDtype same reference" — PRESENT_WEAKENED. Test 16 (lines 181-202) does NOT exercise the SHAREDtype decode path. It substitutes a TypeArena intern round-trip (`arena.intern` called twice on structurally-equal Named values) as a proxy. The actual SHAREDtype tag handling in TypeUnpickler.decodeTag (lines 175-181) is never executed by this test. Comments inside the test acknowledge the substitution ("Since readType reads one type node, we test via TypeArena.intern round-trip"). The behavioral claim "SHAREDtype returns same reference as the originally-decoded type" is unverified.
  - 17 TypeUnpicklerTest "TYPELAMBDAtype params.size == 1" — PRESENT_STRICT (204-230)
  - 18 TypeUnpicklerTest "ANNOTATEDtype" — PRESENT_STRICT (232-252)
  - 19 TypeUnpicklerTest "ORtype" — PRESENT_STRICT (254-273)
  - 20 TypeUnpicklerTest "ANDtype" — PRESENT_WEAKENED. Assertion uses `t.isInstanceOf[Reflect.Type.AndType] || t.isInstanceOf[Reflect.Type.Named]` (lines 290-291). The disjunction with `Named` accepts ANY Named result. The plan asserts AndType or normalized form. Since the synthetic test data uses non-Singleton symbols, normalization should NOT collapse, so the strict assertion would be `AndType` only. The OR clause makes the test tautologically near-passing.
  - 21 TypeUnpicklerTest "MATCHtype cases.size==2, scrutinee is param" — PRESENT_WEAKENED. Lines 299-325 assert `cases.size == 2` but do NOT assert "scrutinee is the named type parameter X" as plan line 310 requires. The scrutinee binding is read but never matched. Plan literal: "assert cases.size == 2 and scrutinee is the named type parameter X". Half-fulfilled.
  - 22 TypeUnpicklerTest "CONSTANTtype IntConst(42)" — PRESENT_STRICT (327-341)
  - 23 TypeUnpicklerTest "RECtype/RECthis no stack overflow" — PRESENT_WEAKENED. Lines 343-417: the test spawns a Thread with 64KB stack, but inside that thread it does NOT call `TypeUnpickler.readType` on the synthesized RECtype/RECthis bytes. It only asserts the byte layout is structurally valid (`bytes(0) == RECtype.toByte`, etc.) and manually constructs a `Reflect.Type.Rec(Reflect.Type.RecThis(...))` value. The "cycle decode timed out or failed" log message lies — no decode runs. The second half of the test (lines 401-416) exercises `TypeArena.merge` cycle-handling on a constructed cycle, which is also covered by TypeArenaTest test 4. Comments in-test acknowledge the substitution: "The readType API takes a Sync effect which we can't easily run on a separate thread. So we just verify that the byte sequence is structurally valid". The actual claim "decoding RECtype with RECthis produces cycle-safe result (no stack overflow)" through TypeUnpickler.readType is unverified.
  - 24 TypeUnpicklerTest "cross-file TYPEREFin produces UnresolvedRef" — PRESENT_STRICT (419-439). Uses synthetic bytes, not PlainClass.tasty; STEERING-compliant via AstUnpicklerTest test 21 on real fixture.

Additional (not on the 1-24 list but committed):
- AstUnpicklerTest test 21 "pass 1 on PlainClass.tasty with arena produces non-empty placeholders" — PRESENT_STRICT (real fixture).
- AstUnpicklerTest test 22 "TEMPLATE parents flow into placeholders: SomeCaseClass.tasty" — PRESENT_STRICT.

## CONTRIBUTING.md violations

- TypeArena.scala line 134 uses `return 0` (early return in `computeHash`'s in-progress guard). CONTRIBUTING does not explicitly forbid `return`, but the codebase convention favours expression-returning style. NOTE-level.
- TypeUnpicklerTest test 20 uses `isInstanceOf` (lines 290-291). CONTRIBUTING (line 415) advises avoiding asInstanceOf; isInstanceOf is not explicitly listed but is the same family. The disjunction is also a test weakening (see test 20 above). WARN-level (combined with the weakened-assertion finding).
- No `asInstanceOf` introduced in Phase 4 main sources.
- No emoji, no em-dashes, no semicolons used as statement separators introduced (grep clean for ; in non-string contexts on diff).

## Unsafe markers (Phase 4 additions only)

- `asInstanceOf`: 0 in main sources. 0 in tests (test 20 uses `isInstanceOf`, see above).
- `null` literals (main sources): 4 in TypeUnpickler.scala (lines 39, 146, 516, 558) as `owner` parameter to `Symbol.make`/`makeSymbol` for synthetic sentinels (MatchCaseSentinel, makeUnresolvedSym, readTypeLambdaParams TypeParam, readMethodParams Parameter). 0 in TypeArena.scala, TypeOps.scala, UnresolvedRef.scala.
- `null` literals (tests): 4 in TypeArenaTest.scala / TypeOpsTest.scala / TypeUnpicklerTest.scala as `owner` parameter to test-helper symbol factories.
- `Frame.internal`: 0.
- `AllowUnsafe`: 0.
- `Sync.Unsafe.defer` / Sync.Unsafe.*: 0.

The 4 `null` owners in TypeUnpickler match the pattern in Phase 3 AstUnpickler (root symbol gets `null` owner as termination sentinel, line 90). These are synthetic decoder sentinels (unresolved cross-file refs, match-case markers, lambda binder params) with no semantic owner. Pattern is consistent with prior phase. NOT a regression but undocumented as an approved exception in STEERING.md.

## STEERING compliance

- `Pass1Result.placeholders` populated (not Chunk.empty): YES. AstUnpickler.scala line 103: `placeholders = Chunk.from(typeSession.placeholders)`. Verified non-empty on real fixture by AstUnpicklerTest test 21 (PlainClass.tasty).
- TEMPLATE parents wired via `decodeTemplateParents`: YES. AstUnpickler.scala lines 213, 317-358. Tested by AstUnpicklerTest test 22 (SomeCaseClass.tasty, case class extends Product/Serializable). STEERING directive at line 68 was cleared with reference to this commit.
- All 5 normalization smart constructors (FunctionN, ContextFunctionN, TupleN, Array, AndType+Singleton): PRESENT.
  - FunctionN — TypeOps.scala lines 33-34
  - ContextFunctionN — lines 35-36
  - TupleN — lines 37-38
  - Array — lines 39-40
  - AndType(Singleton, X) and (X, Singleton) — lines 50-51
- Phase C merge cycle handling (`inProgress` map): PRESENT. TypeArena.scala line 33 (`inProgress`), lines 86-93 (cycle-break via placeholder insertion before recurse; removal after).
- SHAREDtype dedup (per-file `Addr -> Type` cache): PRESENT in main source. TypeUnpickler.scala line 159 records `addrCache(startAddr) = interned` after each non-SHAREDtype decode, and line 175-181 looks up SHAREDtype back-references. NOT exercised by any test (test 16 substitutes intern round-trip; see weakened test).

## Cross-platform consistency

- All 4 produced files live in `shared/src/main/scala/`. All 3 produced test files in `shared/src/test/scala/`. No `jvm/` or `js/` or `native/` placements introduced.
- JVM-specific imports in Phase 4 code:
  - TypeArena.scala uses `java.lang.System.identityHashCode` (lines 145, 178, 182). `System.identityHashCode` is available on Scala.js (https://www.scala-js.org/doc/internals/javalib.html) and Scala Native; this is acceptable. The reference to `java.lang.System` is explicit (per kyo gotcha: `kyo.System` shadows `java.lang.System`).
  - TypeUnpickler.scala uses `java.lang.Float.intBitsToFloat` (line 234), `java.lang.Double.longBitsToDouble` (line 236). Both Scala.js/Native supported.
  - No `sun.*` or `com.sun.*` imports.
- Pass/Fail: cross-platform-safe per inspection. Commit message claims "JS + Native compile clean"; not re-verified by this audit.

## Naming

- All files match plan names exactly:
  - `type_/TypeArena.scala`
  - `type_/TypeOps.scala`
  - `tasty/TypeUnpickler.scala`
  - `query/UnresolvedRef.scala`
  - `TypeArenaTest.scala`, `TypeOpsTest.scala`, `TypeUnpicklerTest.scala` in `shared/src/test/scala/kyo/`
- Internal package prefix `kyo.internal.reflect.{type_,tasty,query}.*` followed in all four new sources.
- Tests reside in `package kyo` per kyo convention (Test base trait import).

## Steering deviation

- `git diff --name-only ad01c90b7~1 ad01c90b7` covers (main code, ignoring .md): the 4 new `.scala` files + modified `AstUnpickler.scala` + AstUnpicklerTest.scala + the 3 new test files. Matches plan "Files to produce" + "Files to modify" exactly. No off-plan source touched.
- TypeUnpickler.readType signature deviates from plan:
  - Plan (line 272): `def readType(view: ByteView, names: Array[Reflect.Name], addrMap: Map[Int, Reflect.Symbol], arena: TypeArena): Reflect.Type < Abort[ReflectError]`
  - Actual (TypeUnpickler.scala lines 59-65): `def readType(view, names, addrMap, arena, home: ClasspathRef)(using Frame): (Reflect.Type, Chunk[UnresolvedRef]) < (Sync & Abort[ReflectError])`
  - Deviation 1: extra `home: ClasspathRef` parameter. Justified because synthetic placeholder symbols (UnresolvedSym) carry a `ClasspathRef`. Without it, the unpickler would have to synthesize a fresh ClasspathRef per call, breaking identity. Acceptable deviation.
  - Deviation 2: return is `(Reflect.Type, Chunk[UnresolvedRef])` not just `Reflect.Type`. Justified because the test API needs visibility into placeholders, which would otherwise be hidden inside the arena. The internal call site uses `readTypeIntoSession` (lines 97-110) which threads placeholders through the shared DecodeSession, so the production path is unaffected. Acceptable deviation.
  - Deviation 3: effect row adds `Sync`. The Sync wrap (line 81: `Sync.defer(r)`) is purely cosmetic; the body runs synchronously inside a try/catch. Acceptable.
  - All three deviations were flagged in PHASE-4-INFLIGHT-REVIEW-1 §3 and acknowledged.

## Anti-flakiness

- No timing-dependent tests in TypeArenaTest, TypeOpsTest.
- TypeUnpicklerTest test 23 uses `thread.join(5000)` with a 64KB stack thread; the timeout is 5s but the test does NOT actually call into TypeUnpickler.readType inside the thread (it only sanity-checks byte layout). The thread completes immediately; no real flake risk, but the test name and 5s join wait misrepresent what is verified. NOTE-level.
- TypeArena merge tests use deterministic input (in-memory HashMap, no IO).
- No `Async.delay`, `Thread.sleep` outside the bounded join, no random seeds.

## Findings categorization

### BLOCKER
None.

### WARN
1. TypeUnpicklerTest test 16 (SHAREDtype) substitutes TypeArena.intern round-trip for SHAREDtype byte-decode. The SHAREDtype decode path (TypeUnpickler.scala lines 175-181, plus the cache write at line 159) has ZERO direct test coverage. This is the cache-correctness path that distinguishes hash-cons from naive decode; silent regression here would not be caught. Strongly recommend adding a test that decodes two consecutive types in one session where the second is a SHAREDtype Nat reference to the first, then asserts both decode to the same `eq`-reference.
2. TypeUnpicklerTest test 23 (RECtype/RECthis no overflow) does not call TypeUnpickler.readType on the constructed RECtype bytes inside its bounded thread. The cycle-safety claim for the decoder is unverified. TypeArena.merge cycle handling is verified (test covers it), but the decoder's `inProgressRec` / addrCache RECthis lookup logic (TypeUnpickler.scala lines 209-220, 257-269) is untested. Recommend adding a test that runs the actual readType decode on RECtype/RECthis bytes.
3. TypeUnpicklerTest test 20 (ANDtype) accepts `Named` as a valid result via disjunction. With non-Singleton symbols, the strict expectation is `AndType` only. The disjunction defeats the test's purpose. Recommend tightening to `AndType` only.
4. TypeUnpicklerTest test 21 (MATCHtype) does not assert "scrutinee is the named type parameter X" as plan line 310 requires. Recommend adding the scrutinee identity check.
5. TypeArenaTest test 3 is trivially passing (carried from PHASE-4-INFLIGHT-REVIEW-1 minor #1, never addressed). Recommend strengthening to mirror test 5's pattern.

### NOTE
1. `return` keyword in TypeArena.computeHash (line 134). Style nit per kyo convention. Logically correct.
2. `null` as `owner` parameter in 4 synthetic-symbol sites in TypeUnpickler (matches Phase 3 root-symbol pattern). Not approved in STEERING.md explicitly.
3. `Sync` effect in `readType` public signature is unused; the body is purely synchronous. Could be `Abort[ReflectError]` only. The internal `readTypeIntoSession` is non-Kyo and is the path actually used by AstUnpickler.
4. TypeOpsTest `makeNamedSym` runs two `foldLeft` loops where the first result is discarded (lines 22-31, then 32-41). Cosmetic; does not affect test correctness.
5. The plan listed `METHODtype`, `ERASEDMETHODtype`, `IMPLICITMETHODtype`, `METHODtypes variants` as decoder cases (line 272). Only `METHODtype` (TypeUnpickler.scala line 388) is implemented. ERASEDMETHODtype/IMPLICITMETHODtype/POLYtype are partially handled via POLYtype dispatch (line 380). ERASEDMETHODtype/IMPLICITMETHODtype tags are NOT listed in the match arms — they would hit the `other` fallback at line 460 and produce an unknown-tag placeholder. Plan-level mismatch; not tested by any of the 13 TypeUnpickler tests. Note for Phase 5/6 follow-up.
6. The plan listed `TERMREFin`, `TERMREFsymbol`, `SUPERtype`, `THIStype`, `SKOLEMtype`, `WILDCARDtype`, `PARAMref`, `FLEXIBLEtype` as decoder cases. THIS is mapped via THIS (line 243); SUPERtype via SUPERtype (333); SKOLEMtype is NOT in the match. PARAMref is via PARAMtype (line 396), not PARAMref. WILDCARDtype is via TYPEBOUNDS handling (line 424) producing Wildcard, not a dedicated WILDCARDtype tag. Functional coverage approximately matches plan intent but tag names diverge from plan list. Not tested; should be re-verified when real-TASTy fixtures with these tags land.
7. AstUnpicklerTest test 21 ("non-empty placeholders") is a strict claim but doesn't assert any specific FQN. A stricter test would assert e.g. "scala.Int" appears in placeholders.fqn for PlainClass. NOTE only.
8. TypeUnpicklerTest test 14 uses `TastyFormat.REPEATED` (tag 149) which is the term-level REPEATED in dotty's TastyFormat (used for varargs application). The plan calls for `REPEATEDtype`. The TypeUnpickler decode arm at line 448 case-matches `TastyFormat.REPEATED`. This is consistent within the module but may not be the correct tag for a type-level repeated annotation. If dotty's TASTy uses a different tag for type-level repeated (e.g., the `REPEATEDtype` constant), the decoder will not handle real-fixture data. Worth verifying against a real TASTy fixture with `T*` parameters when one exists.

## Recommendation: PROCEED

Phase 4 is functionally complete with 5 weakened tests and 8 lower-severity notes. None of the weaknesses block Phase 5 work, which depends on the Type ADT (intact), TypeArena/TypeOps surface (intact), and the `Reflect.Type.Array` smart constructor (used by JavaSignatures per plan line 337). The SHAREDtype-decode and RECtype-decode coverage gaps (WARN #1, #2) are queued as follow-ups; they do not affect Phase 5's classfile reader. The TEMPLATE-parent and placeholders-population wiring (STEERING directives) ARE verified on real fixtures.

Outstanding work (carry into Phase 5 or a Phase 4 cleanup PR):
- WARN #1: real SHAREDtype decode test.
- WARN #2: real readType RECtype decode test.
- WARN #3, #4, #5: strengthen tests 20, 21, TypeArena test 3.
- NOTE #5, #6: cover or document ERASEDMETHODtype, IMPLICITMETHODtype, SKOLEMtype tag handling.
