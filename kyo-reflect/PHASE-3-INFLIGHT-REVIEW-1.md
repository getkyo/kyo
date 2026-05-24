# Phase 3 In-Flight Review (pulse 1)

Pulse 1: 2026-05-24T00:00Z
Files reviewed:
- execution-plan.md lines 190-263
- PHASE-3-PREP.md (full)
- STEERING.md (full)
- git status --short
- AstUnpickler.scala (full)
- Flags.scala (full)
- Symbol.scala (full)
- SymbolKind.scala (full)
- DeclarationTable.scala (full)
- ClasspathRef.scala (full)
- Annotation.scala (full)
- Constant.scala (full)
- Reflect.scala lines 1-297
- FlagsTest.scala (full)
- DeclarationTableTest.scala (full)
- PROGRESS.md (full)

---

## Plan anchor

### Files to produce (per plan) vs dirty tree

| File | Status |
|---|---|
| `kyo/internal/reflect/symbol/SymbolKind.scala` | PRESENT |
| `kyo/internal/reflect/symbol/Flags.scala` | PRESENT |
| `kyo/internal/reflect/symbol/Symbol.scala` | PRESENT |
| `kyo/internal/reflect/symbol/Annotation.scala` | PRESENT |
| `kyo/internal/reflect/symbol/DeclarationTable.scala` | PRESENT |
| `kyo/internal/reflect/symbol/Constant.scala` | PRESENT |
| `kyo/internal/reflect/tasty/AstUnpickler.scala` | PRESENT |
| `kyo/internal/reflect/query/ClasspathRef.scala` | PRESENT (in query/ subdir) |
| `kyo/AstUnpicklerTest.scala` | **MISSING** |
| `kyo/FlagsTest.scala` | PRESENT |
| `kyo/DeclarationTableTest.scala` | PRESENT |

### Files to modify (per plan)

| File | Status |
|---|---|
| `kyo/Reflect.scala` | PRESENT (M in status) |

### Test count
Plan mandates 23 tests. Present: FlagsTest has 6 (tests 1-6), DeclarationTableTest has 3 (tests 21-23). AstUnpicklerTest.scala is MISSING, which accounts for tests 7-20 (14 tests). Only 9 of 23 tests are present.

---

## Reward-hacking checks

| Pattern | Verdict | Citation |
|---|---|---|
| Verification commands actually run | FLAG: no evidence any sbt command ran; no test output file present; plan verification command lists `kyo.AstUnpicklerTest` but that file does not exist | AstUnpicklerTest absent from test dir |
| Compile-only "success" claim | FLAG: if agent reports tests passing, that claim is structurally impossible for tests 7-20 since their file is absent | AstUnpicklerTest.scala not found |
| Priority inference ("this seems edge-case, skip") | CLEAN: no evidence in code; FlagsTest and DeclarationTableTest are faithful to plan | - |
| Scope substitution ("simpler equivalent") | PARTIAL FLAG: plan specifies `def fromTagAndFlags(tag: Int, flags: Long): SymbolKind` in SymbolKind.scala; impl replaces with three separate methods (`fromTypedefTemplateFlags`, `fromTypedefTypeFlags`, `fromValdefFlags`). No `fromTagAndFlags` exists anywhere. Plan entry in "Files to produce" section says the companion object must have `def fromTagAndFlags(tag: Int, flags: Long): SymbolKind`. | SymbolKind.scala has no `fromTagAndFlags` |
| Foreach-discards-assert | FLAG: DeclarationTableTest test 21 uses `members.foreach { case (name, sym) => assert(...) }` with no explicit assertion that all iterations ran. If members is empty, test trivially passes. However, members is constructed as `(1 to 4).map(...)` which is non-empty, so this is low risk but technically not bullet-proof. | DeclarationTableTest.scala lines 30-34 |
| Stale-state / tautological matchers | FLAG (CRITICAL): DeclarationTableTest test 23 (CAS-swap visibility) has a race condition: writer releases latch, then populates; reader waits on latch then reads. The latch.release happens BEFORE `table.populate`, so after the latch, the table is guaranteed empty. The reader will ALWAYS see size==0, making the "or sz == 4" arm dead code. The test cannot demonstrate the CAS-swap guarantee as written. | DeclarationTableTest.scala lines 77-85 |
| TYPEDEF discrimination peeks for TEMPLATE (STEERING) | CLEAN: AstUnpickler line 180 does `view.peekByte(view.position) & 0xff` and compares to `TastyFormat.TEMPLATE` before reading modifiers. Explicitly references STEERING in comment. | AstUnpickler.scala lines 172-215 |
| Qualified-private/protected sub-tree skip (STEERING) | CLEAN: Both `scanForwardAndCollectFlags` and `readModifiers` handle PRIVATEqualified(98) and PROTECTEDqualified(99) with `skipTree(view)`. Explicitly references STEERING in comment. | AstUnpickler.scala lines 268-322 |

---

## Drifting checks

| Pattern | Verdict | Citation |
|---|---|---|
| Public API signatures match plan | PARTIAL FLAG: `Pass1Result` is missing the `placeholders: Chunk[UnresolvedRef]` field required by plan ("returns `Pass1Result(symbols, addrMap, placeholders)`"). Impl has `(symbols, addrMap, rootSymbol)` instead. `rootSymbol` is an addition; `placeholders` is absent. | AstUnpickler.scala lines 31-35 vs plan line 202 |
| No off-plan architecture | CLEAN: no unexpected files or architectural additions beyond plan scope | - |
| No cross-cutting refactor outside Phase 3 | CLEAN | - |
| Internal helpers stay in `kyo.internal.reflect.*` | CLEAN: all internal files correctly under `kyo.internal.reflect.{symbol,tasty,query}` | - |
| Symbol home is forward-reference SingleAssign per plan (no accessor calls in Phase 3 path) | CLEAN: `ClasspathRef` wraps `SingleAssign[Reflect.Classpath]`; Phase 3 code only stores/forwards the ref; no `home.get()` calls in AstUnpickler or computeFullName/computeBinaryName | ClasspathRef.scala; AstUnpickler.scala; Reflect.scala lines 237-272 |
| Symbol.Origin ADT has both TastyOrigin AND JavaOrigin cases (per plan) | CLEAN: `Reflect.scala` lines 290-296 declares sealed trait `Origin` with `final case class TastyOrigin(addrMap, bodyStart, bodyEnd)` and `case object JavaOrigin` | Reflect.scala lines 289-296 |

### Additional drift observations

- `fromTagAndFlags(tag: Int, flags: Long): SymbolKind` is mandated in plan (Files to produce, SymbolKind.scala section) but replaced by three narrower helpers. The plan says "companion object in `kyo.internal` with the TASTy-tag-to-SymbolKind mapping table: `def fromTagAndFlags(tag: Int, flags: Long): SymbolKind`". Impl diverges from this signature. This is a scope-substitution, not a neutral refactor.
- `Pass1Result.placeholders: Chunk[UnresolvedRef]` is absent. Plan text says: "returns `Pass1Result(symbols: Chunk[Reflect.Symbol], addrMap: Map[Int, Reflect.Symbol], placeholders: Chunk[UnresolvedRef])`". This field is needed for Phase 4 type resolution. Its absence means either: (a) Phase 4 cannot proceed without it, or (b) the agent silently deferred it. Either is a scope cut.
- `Constant.scala` line 73 uses `null.asInstanceOf[Reflect.Symbol]` as a placeholder for CLASSconst. The memory feedback says "no casts" (feedback_no_casts.md) and "fix the types instead". This is a cast that should be flagged.

---

## Scope-cutting checks (all 23 plan-mandated tests)

| Leaf | Status | Notes |
|---|---|---|
| 1: FlagsTest - Flag.Inline.bit is power-of-two Long | PRESENT_STRICT | FlagsTest.scala line 13 |
| 2: FlagsTest - Flags.empty.contains(Flag.Inline) is false | PRESENT_STRICT | FlagsTest.scala line 21 |
| 3: FlagsTest - Flags from Inline+Private bits contains Flag.Private | PRESENT_STRICT | FlagsTest.scala line 27 |
| 4: FlagsTest - fromTastyModifierTag maps PRIVATE(6) to Flags.Private | PRESENT_STRICT | FlagsTest.scala line 35 |
| 5: FlagsTest - fromTastyModifierTag maps INLINE to Flags.Inline | PRESENT_STRICT | FlagsTest.scala line 43 |
| 6: FlagsTest - all ~42 flag bits are distinct | PRESENT_STRICT: 43 flags listed in test, all verified distinct + power-of-two | FlagsTest.scala line 49 |
| 7: AstUnpicklerTest - pass 1 returns at least one Class symbol | MISSING | AstUnpicklerTest.scala does not exist |
| 8: AstUnpicklerTest - fixture top-level class name in symbol set | MISSING | AstUnpicklerTest.scala does not exist |
| 9: AstUnpicklerTest - def produces Method symbol | MISSING | AstUnpicklerTest.scala does not exist |
| 10: AstUnpicklerTest - val produces Val symbol | MISSING | AstUnpicklerTest.scala does not exist |
| 11: AstUnpicklerTest - trait produces Trait symbol | MISSING | AstUnpicklerTest.scala does not exist |
| 12: AstUnpicklerTest - object produces Object symbol | MISSING | AstUnpicklerTest.scala does not exist |
| 13: AstUnpicklerTest - enum produces Class+Enum flag | MISSING | AstUnpicklerTest.scala does not exist |
| 14: AstUnpicklerTest - inline def has Inline flag | MISSING | AstUnpicklerTest.scala does not exist |
| 15: AstUnpicklerTest - type param produces TypeParam | MISSING | AstUnpicklerTest.scala does not exist |
| 16: AstUnpicklerTest - method sym.owner is class symbol | MISSING | AstUnpicklerTest.scala does not exist |
| 17: AstUnpicklerTest - nested class sym.fullName is dotted form | MISSING | AstUnpicklerTest.scala does not exist |
| 18: AstUnpicklerTest - DEFDEF body slices (bodyStart,bodyEnd) non-zero | MISSING | AstUnpicklerTest.scala does not exist |
| 19: AstUnpicklerTest - cross-forward TypeParam addrMap entries correct | MISSING | AstUnpicklerTest.scala does not exist |
| 20: AstUnpicklerTest - truncated TASTy yields MalformedSection | MISSING | AstUnpicklerTest.scala does not exist |
| 21: DeclarationTableTest - 4 members use flat-array Dict | PRESENT_WEAKENED: test builds table via `DeclarationTable.build()` factory (good), asserts `result == Present(sym)`. Structure correct but no internal representation check (plan says "internal representation uses no HashMap"). Test cannot verify the flat-array/HashMap distinction from outside. | DeclarationTableTest.scala lines 26-35 |
| 22: DeclarationTableTest - 9 members use HashMap path | PRESENT_WEAKENED: same as above - no HashMap path verification, just get() correctness | DeclarationTableTest.scala lines 37-45 |
| 23: DeclarationTableTest - AtomicRef CAS-swap visibility via two-fiber | PRESENT_WEAKENED (BROKEN LOGIC): latch.release occurs BEFORE `table.populate` (lines 77, 83). Reader always sees empty table post-latch. The race scenario the plan requires cannot manifest. Test will pass but proves nothing about CAS visibility. The latch should be released AFTER populating, or reader should observe before/after (not guaranteed empty). | DeclarationTableTest.scala lines 49-88 |

---

## CRITICAL (steer immediately)

1. **AstUnpicklerTest.scala is entirely absent.** 14 of 23 required tests (tests 7-20) are missing. The implementation cannot be declared complete. AstUnpicklerTest.scala must be created with all 14 tests using the TASTy fixture resource-loading pattern from TastyHeaderTest.scala.

2. **Test 23 (CAS-swap) is logically broken.** `latch.release` fires before `table.populate`, making the race scenario dead code. The reader always observes size=0. Reorder: populate first, then release, OR interleave correctly so a racing reader could see either state.

3. **`Pass1Result` is missing the `placeholders: Chunk[UnresolvedRef]` field.** Plan specifies this field is required for Phase 4 type resolution handoff. Its absence means Phase 4 will have no mechanism to receive unresolved parent type references from pass 1.

4. **`fromTagAndFlags(tag: Int, flags: Long): SymbolKind` is absent.** Plan mandates this as the public API of `SymbolKind.scala`. Impl uses three private helpers instead. This is a scope substitution without escalation.

5. **`Constant.scala` uses `null.asInstanceOf[Reflect.Symbol]` (line 73).** This violates `feedback_no_casts.md`. Use a proper placeholder type (e.g., `Reflect.Type.Named` with a sentinel, or a dedicated `UnresolvedClassConst` case, or defer CLASSconst to Phase 4 with `Abort.fail(ReflectError.NotImplemented(...))`).

---

## MINOR (queue for post-commit audit)

- `Tailrec` flag exists in `Reflect.Flag` and `FlagsTest` (line 74) but has no corresponding TASTy modifier tag and no `fromTastyModifierTag` mapping. This is correct (TAILREC is a compiler-internal flag not TASTy-encoded) but the doc comment in Flags.scala implies it should have a mapping. Clarify the comment to note it is not TASTy-encoded.
- `DeclarationTableTest` tests 21 and 22 cannot distinguish Dict's internal flat-array vs HashMap paths from outside. Tests are correctness-only, not structural. This is acceptable if Dict's own tests cover the internal split (kyo-data), but the plan's parenthetical "and the table's internal representation uses no HashMap" for test 21 is unmet.
- `Pass1Result` adds `rootSymbol` field not in plan. The supervisor should confirm whether this is an intentional addition or incidental drift.
- The `computeBinaryName` walk (Reflect.scala lines 250-272) uses '.' for all separators, not '$' for nested classes. Plan says "Binary name uses '$' for nested classes". This is likely deferred to Phase 4 but should be confirmed.
- `AstUnpickler.readPass1` returns `Sync & Abort[ReflectError]` instead of plan's `Abort[ReflectError]` only. The addition of `Sync` is a minor effect-row widening; confirm it is intentional.

---

## Recommendation: STEER — AstUnpicklerTest.scala (14 tests) must be written; test 23 must be fixed; Pass1Result.placeholders must be added; fromTagAndFlags signature must be present or escalated.
