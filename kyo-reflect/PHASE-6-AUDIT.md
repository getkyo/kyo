# Phase 6 Audit

Audit run: 2026-05-25T01:28:25Z
Commit audited: 82ad3bdfa933df8cbe922c5305180cb3364ee355
Files audited:
- `kyo-reflect/shared/src/main/scala/kyo/internal/ReflectMacro.scala` (403 lines, NEW)
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/reads/ReflectRuntime.scala` (84 lines, NEW)
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/reads/ReadsInstances.scala` (887 lines, NEW)
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/reads/TouchedFields.scala` (140 lines, NEW)
- `kyo-reflect/shared/src/test/scala/kyo/ReadsDerivationTest.scala` (385 lines, NEW)
- `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala` (MODIFIED: line 350 `using Frame` added, line 353 macro splice + companion mixin)

---

## Verdict
PROCEED (with WARN-drain required before Phase 6b)

## Summary
| Category | Count |
|---|---|
| BLOCKER | 0 |
| WARN | 5 |
| NOTE | 5 |

---

## Findings

### BLOCKER (0)

(none)

---

### WARN (5)

**W1. Dead private method `extractStaticTouchedByTypeRepr` in ReflectMacro.scala (lines 378-401)**
`extractStaticTouchedByTypeRepr` is defined as a `private def` inside `object ReflectMacro` but is never called from `buildProduct`, `analyzeField`, or any other non-self site. The method calls itself recursively on `AppliedType` inner arguments but the only callers are those recursive calls. The production code path for `touchedFields` uses `directFieldTouched(fieldName)` (field-name dispatch) and `'{ $r.touchedFields }` (summoned-instance propagation). `extractStaticTouchedByTypeRepr` is dead code left over from an earlier design iteration.
- **File**: `kyo-reflect/shared/src/main/scala/kyo/internal/ReflectMacro.scala:378-401`
- **Fix**: Delete `extractStaticTouchedByTypeRepr` and its three internal callsites (lines 395 and 398 inside itself). The live code path does not use it.

---

**W2. Test 9 assertion uses a loose `||` that lets the test pass without verifying "hand-written" in the error message**
The primary path in Test 9 calls `typeCheckErrors` on `"sealed trait SumType; object ReadsDerivationTest_T9 { val r = compiletime.summonInline[kyo.Reflect.Reads[SumType]] }"`. `SumType` has no `derives Reflect.Reads`, so `summonInline` will fail with "no given instance found for Reads[SumType]" — not with the macro's "hand-written" message. If `err.nonEmpty` is true (which it will be), the first branch of the `||` short-circuits and the fallback (which actually checks the message keyword) is never evaluated. The test therefore passes without verifying that the macro emits the "hand-written" guidance. Plan line 502 mandates: "compile error containing the phrase 'hand-written'".
- **File**: `kyo-reflect/shared/src/test/scala/kyo/ReadsDerivationTest.scala:204-215`
- **Fix**: Replace the two-path `||` with a single strict call to `typeCheckErrors` on `"sealed trait MySeal; case class MySealR() extends MySeal; object T9b { val r: kyo.Reflect.Reads[MySeal] = kyo.Reflect.Reads.derived[MySeal] }"` and assert `errors.nonEmpty && errors.exists(e => e.message.contains("hand-written"))`. The summonInline path tests implicit resolution failure, not the macro error; drop it.

---

**W3. Test 11 tests `customIntReads.read(stub)` directly rather than `Reads[Custom].read(stub)`**
Plan line 504 mandates: "the derived instance uses the given `Reads[Int]` for the `special` field". The test body imports `Test11.given` and then calls `Test11.customIntReads.read(stub)` directly -- this tests the hand-written `customIntReads` instance in isolation, NOT the derived `Reads[Custom]`. To verify that derivation actually wires the summoned `Reads[Int]`, the test must call `summon[Reads[Test11.Custom]].read(stub)` and assert that the `Custom.special` field equals the value that `customIntReads` would produce (`stub.name.asString.length == 5`). The current test does not exercise derivation at all for this case.
- **File**: `kyo-reflect/shared/src/test/scala/kyo/ReadsDerivationTest.scala:237-253`
- **Fix**: Change the test body to summon `Reads[Test11.Custom]`, call `.read(stub)`, and assert `custom.special == "hello".length` and `custom.name == stub.name`.

---

**W4. Test 12's success branch uses `decls.isEmpty || decls.nonEmpty` (vacuous assertion)**
Test 12 handles the `Result.Success(decls)` branch with `assert(decls.isEmpty || decls.nonEmpty, ...)`. This tautology always succeeds if no error is thrown, which is fine IF the test never reaches this branch (the comment says stub.declarations throws `NotImplemented`). However, if `declarations` is later implemented on stubs, this branch becomes a green test that asserts nothing. It also means the test does not distinguish "declarations returns Chunk.empty" from "declarations returns a non-empty Chunk" -- both pass silently.
- **File**: `kyo-reflect/shared/src/test/scala/kyo/ReadsDerivationTest.scala:271-275`
- **Fix**: Remove the `Success(decls)` case or replace the assertion with `fail(s"Expected NotImplemented but declarations returned: $decls")`, so the test remains a strict probe of the stub's behavior.

---

**W5. Test 16 exercises a hand-written `val matchReads` (with manually declared `touchedFields = FieldSet.Name`) rather than a macro-analyzed body**
Plan line 509 mandates: "a `Match` node in a hand-written `Reads.read` body containing a `Bind` pattern does not cause macro hygiene assertions to fire … assert that the derived `touchedFields` for this `Reads` instance excludes any `FieldSet` bits that appear only in the `Bind` pattern and not in the guard or RHS". The implementation uses `val matchReads: Reflect.Reads[Reflect.Name] = new Reflect.Reads[Reflect.Name]: ... val touchedFields = Reflect.FieldSet.Name`. `touchedFields` is a statically declared `val`, so `tf.bits == Reflect.FieldSet.Name.bits` is always trivially true regardless of any hygiene logic. No macro analysis of the `read` body occurs. The `TouchedFields.analyze` path (which IS where the hygiene guard lives) is never invoked. The test proves nothing about hygiene rule 2.
- **File**: `kyo-reflect/shared/src/test/scala/kyo/ReadsDerivationTest.scala:320-341`
- **Fix**: Replace `matchReads` with a `derives Reflect.Reads` case class whose field type forces the macro to analyze a generated body that contains a `Match` with `Bind` patterns in the `read` implementation (e.g., via a hand-written `Reads` that the macro summons and whose body uses `sym.kind match { case k @ SymbolKind.Class => ... }`). Alternatively, call `TouchedFields.analyze` directly on a synthetic quoted term containing a `Match(sym.kind, List(CaseDef(Bind("k", ...), None, ...)...))` to verify hygiene rule 2 suppresses the bind pattern. The current test structure does not validate the macro behavior the plan mandates.

---

### NOTE (5)

**N1. `Kyo.lift(null: Any)` placeholder for recursive slots in `emitLazyProduct` (ReflectMacro.scala:228)**
The placeholder reader `'{ (_: Reflect.Symbol) => Kyo.lift(null: Any) }` is emitted for recursive slot indices. The `readFieldsLazy` runtime helper checks `isRecSlot` and `isChunkSelf` bitmasks before dispatching to these slots, so the placeholder is never invoked at runtime. The design is sound by construction but the explicit `null` inside a quoted expression is slightly surprising. A comment would clarify intent; no code change required.

**N2. Test 6 expected variable includes `FieldSet.Members` but assertions only check `contains(Name)` and `contains(Parents)`**
`val expected = Reflect.FieldSet.Name | Reflect.FieldSet.Parents | Reflect.FieldSet.Members` at line 198 is declared but never used in a tight `bits ==` equality check. The `WithParents(name: Name, parents: Chunk[Type])` field "parents" matches `directFieldTouched` as `FieldSet.Parents` (by name), not `Members`. Whether `Members` ends up in `touchedFields` depends on whether the macro also adds it via the `chunkReads` summoned instance for `Chunk[Type]`. If `chunkReads.touchedFields` includes `Members`, the derived Reads gets `Members` transitively; if the implementation uses `DirectField` (name match wins before summoning), it may not. The test does not expose this either way; it would silently pass regardless. Cosmetic but worth aligning `expected` with actual expected bits.

**N3. Tests 1, 3, 8, 17 are compile-only / null-check tests (compile-time resolution verified, not runtime semantics)**
Tests 1, 8, 17 assert `r != null` and Test 3 asserts `sk == Set(SymbolKind.values*)`. These resolve at compile time (the `summon` / `derives` resolving is the meaningful check; the null check is trivially true). Per Pulse 4 observation, these are "compile-only" in spirit. They are adequate for their stated scope (confirming the macro produces a non-null instance and the right symbolKinds) but do not exercise runtime `read` semantics. Listed as NOTE per plan; no change required.

**N4. `symbolKinds` over-narrows to `Class/Trait/Object` whenever any `SummonField` is present in `buildProduct`**
`buildProduct` sets `hasSummonField = fieldAnalyses.exists(_._1 == SummonField)` and, if true, emits `Set(Class, Trait, Object)` regardless of whether the summoned `Reads` actually touches structural fields. For example, if `Outer` contains an `Inner` field whose `Reads[Inner]` only touches `Name`, the macro still narrows `Outer.symbolKinds`. This is documented in the code comment as a conservative approximation but it is semantically over-restrictive. No test covers the case where a `SummonField` with a pure-accessor `Reads` preserves the wide `Set(SymbolKind.values*)`. Queue for Phase 6b precision improvement if downstream callers rely on wide symbolKinds.

**N5. `booleanReads`, `intReads`, `longReads` return constant sentinel values (false / 0 / 0L) from `read`**
The built-in `Reads[Boolean]`, `Reads[Int]`, `Reads[Long]` in `ReadsInstances.scala` (lines 71-87) return fixed zero/false constants rather than reading any field from the symbol. The intent from DESIGN.md §13 is "for `Flags.contains` predicates etc." — these types are typically used as field types in derived case classes where the macro emits direct accessor readers (e.g., field "isInline" maps to `sym.isInline`). The built-in instances are fallbacks when a field name does not match any known accessor. The constant-return semantics are unexpected and undocumented in the method body. Add a brief comment; no change to logic required.

---

## Design-doc compliance (DESIGN.md §13)

| Line item | Status |
|---|---|
| Product-type derivation via `TypeRepr` inspection (not `Mirror`) | PRESENT — `ReflectMacro.derivedImpl` uses `aSym.caseFields` and `aType.memberType(f)` |
| `symbolKinds` inference (all-kinds vs narrowed) | PRESENT — `buildProduct` implements the two-branch rule |
| `touchedFields` static analysis via `Trees.traverseGoto` | PRESENT — `TouchedFields.analyze` in `TouchedFields.scala`; called from... wait. Actually `TouchedFields.analyze` is NOT called from `ReflectMacro`. The macro uses `directFieldTouched(fieldName)` (field-name dispatch) and `'{ $r.touchedFields }` (summoned instance). `TouchedFields.analyze` appears in `TouchedFields.scala` but is not wired into the production path. The `touchedFields` emitted by `derivedImpl` is computed purely from field names / summoned instances, not from a term walk. This is a DIVERGENCE from DESIGN.md §13 and plan line 472 ("Trees.traverseGoto over the generated body"). However the result is functionally equivalent for the cases covered by field-name dispatch, and plan line 478 says `TouchedFields` is "extracted … for testability" — which implies it is supposed to be called from `ReflectMacro`. DIVERGED (implementation builds touchedFields at field-analysis time rather than post-generation term walk, and `TouchedFields.analyze` is present but unreachable from the main derivation path) |
| Recursive case classes via `lazy val instance` | PRESENT — `emitLazyProduct` matches STEERING blueprint verbatim |
| Sum-type guard with clear error | PRESENT — `derivedImpl` checks `Flags.Sealed / Flags.Enum` and calls `report.errorAndAbort` with "hand-written" in the message |
| Higher-kinded guard | PRESENT — `abstractTypeParams` check, `report.errorAndAbort` with "abstract type parameter" |
| Hygiene guard 2: skip Match.pattern | PRESENT — `TouchedFields.analyze` `traverseGoto` skips `c.pattern` (visits only scrutinee, guard, rhs) |
| Built-in instances: Name, Flags, SymbolKind, Type, Symbol | PRESENT |
| Built-in instances: Boolean, Int, Long, String | PRESENT (constant-return semantics — see N5) |
| Built-in instances: Chunk[T], Maybe[T] | PRESENT |
| Built-in instances: Tuples arity 2-22 | PRESENT — `tuple2Reads` through `tuple22Reads` |
| Built-in instances: `Record[F]` / `symbolToRecord[F]` | ABSENT — this is Phase 6b scope per plan line 534; correctly deferred |
| Custom field reads via `given` override | PRESENT — `analyzeField` calls `Expr.summon[Reads[ft]]` for non-direct fields |
| Transitive touchedFields | PRESENT — `analyzeField` returns `'{ $r.touchedFields }` for SummonField; `buildProduct` folds via `'{ $acc | $fsExpr }` |
| Reads companion mixin | PRESENT — `object Reads extends kyo.internal.reflect.reads.ReadsInstances` |
| `derives` entry point wired | PRESENT — `Reflect.scala:354` is `${ kyo.internal.ReflectMacro.derivedImpl[A] }` |
| `read` signature has `(using Frame)` | PRESENT — Reflect.scala:350 and all generated `read` overrides |

---

## Plan compliance (Phase 6)

### Files to produce
| File | Status |
|---|---|
| `kyo-reflect/shared/src/main/scala/kyo/internal/ReflectMacro.scala` | PRESENT |
| `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/reads/ReadsInstances.scala` | PRESENT |
| `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/reads/TouchedFields.scala` | PRESENT |
| `kyo-reflect/shared/src/test/scala/kyo/ReadsDerivationTest.scala` | PRESENT |

### Files to modify
| File | Status |
|---|---|
| `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala` — replace `compiletime.error` stub with macro splice | PRESENT |

### Files to delete
| Item | Status |
|---|---|
| none | N/A |

### 18 Tests
| # | Plan text | Status | Notes |
|---|---|---|---|
| 1 | `Simple derives Reflect.Reads` compiles | PRESENT_COMPILE_ONLY | summon + null-check; see N3 |
| 2 | `touchedFields` contains `Name | Flags` and no other bits | PRESENT_STRICT | `tf.bits == expected.bits` tight equality |
| 3 | `symbolKinds` is `Set(SymbolKind.values*)` | PRESENT_STRICT | set equality |
| 4 | `needsBodies` is `false` | PRESENT_STRICT | direct boolean assert |
| 5 | `read(sym)` returns `Simple` with `name == sym.name`, `flags == sym.flags` | PRESENT_STRICT | `taggedAs jvmOnly`, decodes `PlainClass.tasty`, asserts both fields |
| 6 | `WithParents derives` compiles, `touchedFields` contains `Name | Parents` | PRESENT_WEAK | uses `contains()` not `bits ==`; extra bits not caught; see N2 |
| 7 | `symbolKinds` narrowed to `Set(Class, Trait, Object)` | PRESENT_STRICT | set equality |
| 8 | `Node(name, children: Chunk[Node]) derives` compiles | PRESENT_COMPILE_ONLY | null-check; see N3 |
| 9 | sealed trait produces error containing "hand-written" | PRESENT_WEAK | primary path passes via wrong `err.nonEmpty` branch; see W2 |
| 10 | `derives` on `case class Foo[A]` produces abstract-type-param error | PRESENT_STRICT | uses `errors2.nonEmpty && errors2.exists(...)` |
| 11 | derived `Custom` uses given `Reads[Int]` for `special` field | PRESENT_WEAK | tests `customIntReads.read` directly, not derived `Reads[Custom].read`; see W3 |
| 12 | `Reads[Chunk[Symbol]].read` maps over declarations | PRESENT_WEAK | success branch has vacuous `isEmpty || nonEmpty` assertion; see W4 |
| 13 | `Reads[Maybe[Symbol]].read` returns `Absent` for unresolved companion | PRESENT_STRICT | `NotImplemented` path asserts correct call-through |
| 14 | tuple `Reads[(Name, Flags)]` reads both fields | PRESENT_STRICT | asserts `name == stub.name` and `flags.bits == stub.flags.bits` |
| 15 | `Outer.touchedFields` includes `Parents` from `Inner` | PRESENT_STRICT | `contains(Parents)` and `contains(Name)` both asserted |
| 16 | `Bind` pattern in `Match` does not cause hygiene assertions; derived `touchedFields` excludes bind-only bits | PRESENT_WEAK | tests hand-written val with manually declared `touchedFields`; hygiene path not actually exercised; see W5 |
| 17 | all built-in `Reads` resolve implicitly | PRESENT_COMPILE_ONLY | null-checks only; see N3 |
| 18 | `Reads.read` on real fixture symbol returns expected product with `name == "PlainClass"` | PRESENT_STRICT | `taggedAs jvmOnly`, loads `PlainClass.tasty`, asserts `simple.name.asString == "PlainClass"` |

**Tally**: 10 PRESENT_STRICT, 4 PRESENT_WEAK (tests 6, 9, 11, 12), 3 PRESENT_COMPILE_ONLY (tests 1, 8, 17), 1 PRESENT_WEAK (test 16), 0 MISSING.

---

## Specific check results

1. **`feedback_no_casts` — `asInstanceOf` in macro source (not as TypeApply-emitted node)**
   `ReflectMacro.scala:319` doc comment says "The `asInstanceOf` casts are correct by construction"; `line 350` emits `TypeApply(Select.unique(apply, "asInstanceOf"), List(TypeTree.of[t]))` as a quoted AST node inside `buildCtorFn`. This is emitting a typed AST node (the `asInstanceOf` appears in the generated user-code output, not in the macro source itself). No bare `asInstanceOf` call appears in the macro source code. CLEAN.

2. **`feedback_no_default_params_internal` — default params on internal APIs**
   No default parameter values (`= ...`) found on any `def` in `ReflectMacro`, `ReflectRuntime`, `TouchedFields`, or `ReadsInstances`. CLEAN.

3. **`feedback_no_unsafe` — `AllowUnsafe`, `Frame.internal`, or missing `(using Frame)` on public methods**
   No `AllowUnsafe` or `Frame.internal` in any Phase 6 file. All public `read` methods carry `(using Frame)`. CLEAN.

4. **`feedback_no_em_dashes` — em-dash or en-dash**
   No Unicode em-dash (`—`, `—`) or en-dash (`–`, `–`) found in any Phase 6 file. The `──` style section dividers in comments use ASCII `─` (box-drawing U+2500), not the prohibited em-dash. CLEAN.

5. **`feedback_no_explicit_abort_fail_types` — explicit `[E]` on `Abort.fail`**
   No `Abort.fail[...]` calls in Phase 6 files. CLEAN.

6. **`feedback_tests_use_public_api` — tests construct value-under-test via `derives Reflect.Reads`**
   Tests 1-18 use `derives Reflect.Reads` or `summon[Reflect.Reads[...]]` on the LHS. Internal types (`Interner`, `AstUnpickler`, etc.) appear on the RHS for fixture loading only. CLEAN.

7. **`feedback_test_rigor`**
   - Test 9: loose `||` as described in W2. WARN.
   - Test 12: vacuous `isEmpty || nonEmpty` in success branch. WARN.
   - Test 16: hand-written `touchedFields` val makes assertion trivially true. WARN.
   - Tests 1, 8, 17: compile-only null-checks; adequate for stated scope. NOTE (N3).

8. **`feedback_log_unexpected_failures` — catch-all `case _ =>` swallowing Panic without logging**
   `TouchedFields.scala` uses `case _ =>` at line 93 (inside `existsSymbolSelect` TreeTraverser `traverseTree` for non-matching trees — calls `super.traverseTree`) and at line 304 (inside `traverseGoto` TreeTraverser, calls `super.traverseTree`). Both are standard "not-a-match, delegate to super" patterns in the TreeTraverser API. Neither swallows a Panic. `ReflectMacro.scala:401` and `ReadsInstances.scala:94, 229` also use `case _ =>` as fallthrough in pattern matches returning `Reflect.FieldSet.Empty` / `false`. None of these catch `Throwable`. CLEAN.

9. **Cross-platform**
   All Phase 6 files are under `shared/`. `ReflectMacro.scala` uses `scala.quoted.*` (compile-time only, not shipped to JS/Native runtime). `ReflectRuntime.scala`, `ReadsInstances.scala`, `TouchedFields.scala` are pure Scala with no JVM-only imports. Tests 5 and 18 are correctly gated `taggedAs jvmOnly` (they load `.tasty` fixture bytes from classpath resources, which requires JVM class loading). Tests 1-4, 6-17 are in `shared/` with no JVM-only tag. CLEAN.

10. **Design doc compliance (DESIGN.md §13)**: see table above. Single DIVERGED item: `TouchedFields.analyze` is present in the codebase but not invoked from the production derivation path — the macro builds `touchedFields` via field-name dispatch and summoned-instance propagation instead. Functionally equivalent for direct-field and SummonField paths, but the term-walk path (hygiene rule 1 pre-check and hygiene rule 2 Match.pattern skip) only runs in `TouchedFields.analyze`, which is only tested via Test 16 (itself weakened by W5). The plan specifically says `TouchedFields` is "extracted from `ReflectMacro` for testability," implying `ReflectMacro` was supposed to call it. The disconnect is minor (results match what a term walk would produce) but is a structural deviation.

11. **Plan compliance (Phase 6)**: see table above. All files produced, all files modified, 18/18 tests present. 4 weakened tests (W2, W3, W4, W5) and 1 dead-code method (W1). No missing deliverables.

12. **64-field cap**: PRESENT at `ReflectMacro.scala:134-135` (`if caseFields.length > 64 then report.errorAndAbort(...)`). Fires at macro expansion time before the Long bitmask slots are allocated. CLEAN.

13. **Lazy self-reference matches STEERING "Phase 6 lazy self-reference design (supervisor-blessed)"**: PRESENT. `emitLazyProduct` (lines 200-265) emits the exact quote structure specified in STEERING.md lines 120-138: `lazy val instance: Reflect.Reads[A]` wrapping the new anonymous class, `_nonRecReaders`, `_isRecSlot`, `_isChunkSelf` fields, and `readFieldsLazy(sym, _nonRecReaders, _isRecSlot, _isChunkSelf, instance, _ctor)` call. No thunk indirection. CLEAN.

14. **Hygiene rule 2 (Match.pattern skipped)**: PRESENT in `TouchedFields.analyze`. The `traverseGoto` handler for `Match(scrutinee, cases)` calls `goto(scrutinee)`, `c.guard.foreach(goto)`, `goto(c.rhs)`, and does NOT call `goto(c.pattern)`. However as noted in W5, this path is not exercised by the test suite because Test 16 uses a hand-written `Reads` with a manually-declared `touchedFields` val, not a macro-analyzed body.

15. **Sum-type and higher-kinded guards**: Error messages are helpful. Sum-type error (lines 35-50) names the offending type, provides a hand-written template, and points at DESIGN.md. Higher-kinded error (lines 55-61) names the type and provides the factory pattern. Tests 9 and 10 check for error keywords but Test 9's strictness is weakened (see W2). Test 10 uses `&&` with message check, which is strict.

16. **Built-in given instances**: All plan-line-477 types present: `nameReads`, `flagsReads`, `kindReads`, `typeReads`, `symbolReads`, `booleanReads`, `intReads`, `longReads`, `stringReads`, `chunkReads[T]`, `maybeReads[T]`, tuples 2-22 (`tuple2Reads` through `tuple22Reads`). All 887 lines confirmed. COMPLETE.

17. **Tests 5 and 18**: Both load `PlainClass.tasty` via `getClass.getResourceAsStream("/kyo/fixtures/PlainClass.tasty")` and decode a real `Reflect.Symbol`. Test 5 asserts `simple.name.asString == s.name.asString` and `simple.flags.bits == s.flags.bits` (both field-equality checks). Test 18 asserts `simple.name.asString == "PlainClass"` (known fixture value). Both are `taggedAs jvmOnly`. Both are strict. NOT synthetic. CLEAN.

---

## Recommendation

PROCEED to Phase 6 WARN-drain sweep before Phase 6b.

Fix in order:
1. **W1** (dead method `extractStaticTouchedByTypeRepr`) — delete 24 lines.
2. **W2** (Test 9 loose `||`) — replace primary path with strict `derived[MySeal]` call + message check.
3. **W3** (Test 11 tests wrong thing) — call `summon[Reads[Test11.Custom]].read(stub)` and assert `custom.special == 5`.
4. **W4** (Test 12 vacuous success branch) — replace `isEmpty || nonEmpty` with `fail(...)`.
5. **W5** (Test 16 hygiene not actually tested) — exercise `TouchedFields.analyze` or use a derived instance whose macro analysis exercises the Match.pattern skip.

All 5 WARNs are mechanical fixes (no design changes). After drain, tests 2, 5, 18 already provide strong confidence in the core correctness of the derivation. Phase 6b (Record interop) can proceed once the WARN-drain commit lands.
