# Phase 4 In-Flight Review (pulse 2)

Pulse 2: 2026-05-24T00:00Z
Files reviewed:
- kyo-reflect/STEERING.md (full, 80 lines)
- kyo-reflect/PHASE-4-INFLIGHT-REVIEW-1.md (full, 132 lines)
- kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/AstUnpickler.scala (full, 392 lines)
- kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/TypeUnpickler.scala (lines 22-145, grep scan)
- kyo-reflect/shared/src/test/scala/kyo/TypeUnpicklerTest.scala (lines 1-441, grep scan)
- kyo-reflect/shared/src/test/scala/kyo/AstUnpicklerTest.scala (grep scan)
- git status (kyo-reflect subtree)

---

## STEERING compliance

| # | Directive | Verdict | Citation |
|---|---|---|---|
| 1 | `runPass1` wires `TypeUnpickler.readType` for sig/parent positions | COMPLIED | AstUnpickler.scala line 97: `val typeSession = new TypeUnpickler.DecodeSession(names, addrMap, arena, home)`; line 293: `TypeUnpickler.readTypeIntoSession(view, typeSession)` called from `decodeOneTypeIfPresent` which is invoked for VALDEF (line 160), TYPEPARAM (line 244), and PARAM (line 259) type positions. TYPEDEF/TEMPLATE parent positions are skipped at line 209 with a `// For Phase 4: skip the template payload` comment (see New Drift below). |
| 2 | `placeholders` populated from accumulated results, not `Chunk.empty` | COMPLIED | AstUnpickler.scala line 103: `placeholders = Chunk.from(typeSession.placeholders)`. The old `Chunk.empty` literal is gone. `typeSession.placeholders` is an `ArrayBuffer[UnresolvedRef]` populated incrementally by `TypeUnpickler.readTypeIntoSession` during the walk (TypeUnpickler.scala lines 199, 206, 413). |
| 3 | Test verifies `placeholders.nonEmpty` on real fixture | COMPLIED (two locations) | (a) TypeUnpicklerTest.scala line 433: `assert(placeholders.nonEmpty, ...)` in test 24 "decoding TYPEREFin with unknown FQN produces UnresolvedRef placeholder" (synthetic fixture, not PlainClass.tasty). (b) AstUnpicklerTest.scala lines 337-345: test "pass 1 on PlainClass.tasty with arena produces non-empty placeholders" asserts `r.placeholders.nonEmpty` on the real PlainClass.tasty fixture. |

All three STEERING directives: COMPLIED.

---

## New drift since pulse 1

1. **TYPEDEF/TEMPLATE parent types still skipped.** AstUnpickler.scala lines 207-209 enter the class-like TYPEDEF branch but immediately skip to `templatePayloadEnd` with the comment "For Phase 4: skip the template payload (parents are decoded lazily)." The STEERING directive says "for each definition's signature / parent type positions, call TypeUnpickler.readType." Parent types inside TEMPLATE are type nodes at the start of the template payload. They are currently not fed to `decodeOneTypeIfPresent`. For a class with parents referencing cross-file types (e.g., `class Foo extends java.io.Serializable`), those parent refs will NOT appear in `placeholders`. The PlainClass.tasty test may still pass if the scalar type refs (field types) provide enough placeholders, but the STEERING directive is not fully satisfied for parent positions. This is partial compliance -- not a regression from pulse 1 (it was already unimplemented), but it was not fixed by the phase 4 wiring agent. The agent added the skip comment, acknowledging it, but did not implement the decode.

2. **TypeUnpicklerTest.scala test 24 uses a synthetic fixture, not a real TASTy file.** The STEERING directive says "at least one test scenario that verifies a real fixture (PlainClass.tasty already has cross-file refs to `scala.Int`, etc.) produces a non-empty `placeholders` chunk." Test 24 in TypeUnpicklerTest uses a hand-crafted byte buffer (not PlainClass.tasty). The AstUnpicklerTest test (test 21) does use PlainClass.tasty and asserts `placeholders.nonEmpty`, satisfying the "real fixture" requirement via a different test file than the directive implies. This is acceptable but worth noting -- the real-fixture test lives in AstUnpicklerTest, not TypeUnpicklerTest.

3. **No new `asInstanceOf` introduced.** Zero occurrences found in TypeArena.scala, TypeOps.scala, TypeUnpickler.scala, or TypeUnpicklerTest.scala.

4. **`null` usage unchanged from pulse 1.** No new null sites introduced. TypeUnpickler.scala lines 39, 146, 516, 558 are the same synthetic-sentinel pattern flagged in pulse 1. AstUnpickler.scala line 90 is the same root-symbol sentinel. No new nulls added.

5. **No files renamed or moved off-plan.** All four new files remain at their plan-specified paths. AstUnpickler.scala remains modified in-place.

6. **No tests weakened or removed.** TypeUnpicklerTest.scala has exactly 13 tests (tests 12-24 per plan), matching the plan count. All 13 test titles are present and none are trivially tautological.

7. **TypeArenaTest test 3 weakness (pulse 1 MINOR) not fixed.** Still uses `canon.intern(t) eq canon.intern(t)` (same object) instead of two separately-constructed structurally-equal objects from two arenas. Not a regression; unchanged.

---

## CRITICAL (steer immediately)

1. **TEMPLATE parent types are skipped, not decoded.** AstUnpickler.scala lines 207-209 skip the entire template payload including parent type nodes. The STEERING directive requires "signature/parent positions" to be wired. Parent types of classes are the primary source of cross-file type references (e.g., inheriting from `scala.collection.AbstractSeq`). These refs are not accumulating into `placeholders`. The test passes only because field/parameter types (VALDEF/TYPEPARAM/PARAM) provide enough placeholders from PlainClass.tasty. The TEMPLATE parent decode path must be implemented to fully comply with the directive. Suggested fix: after consuming the TEMPLATE tag and reading `templatePayloadEnd`, walk the template payload's leading type nodes via `decodeOneTypeIfPresent` before skipping to `templatePayloadEnd`.

---

## MINOR (queue for post-commit audit)

1. **TypeUnpicklerTest test 24 real-fixture gap.** The test uses a hand-crafted byte buffer. The STEERING directive's intent (PlainClass.tasty cross-file refs) is covered by AstUnpicklerTest test 21, but TypeUnpicklerTest itself has no real-TASTy-based test. Lower priority given the AstUnpicklerTest coverage, but worth adding a TypeUnpicklerTest test that loads PlainClass.tasty and runs readType directly.

2. **TypeArenaTest test 3 weakened assertion** (carried from pulse 1). `canon.intern(t) eq canon.intern(t)` is trivially true. Should be strengthened to use two separately-allocated-but-equal values from two arenas.

3. **`null` as owner in four TypeUnpickler synthetic symbols** (carried from pulse 1). Same pattern as AstUnpickler root, appears intentional; not documented as approved.

---

## Recommendation: STEER: Wire TEMPLATE parent type positions through `decodeOneTypeIfPresent` before skipping to `templatePayloadEnd` so class parent refs accumulate into `placeholders` (CRITICAL #1)
