# Phase 6 In-Flight Review (pulse 1)

Pulse 1: 2026-05-24T00:00Z
Files reviewed:
- `execution-plan.md` lines 461-530 (Phase 6 spec)
- `PHASE-6-PREP.md` (full)
- `STEERING.md` (full)
- `shared/src/main/scala/kyo/internal/ReflectMacro.scala` (lines 1-459)
- `shared/src/main/scala/kyo/internal/reflect/reads/ReadsInstances.scala` (lines 1-856)
- `shared/src/main/scala/kyo/internal/reflect/reads/TouchedFields.scala` (lines 1-129)
- `shared/src/main/scala/kyo/Reflect.scala` (lines 328-395, targeted)
- Test directory scan (no `ReadsDerivationTest.scala` found)

---

## Plan anchor

| Item | Status | Notes |
|---|---|---|
| `ReflectMacro.scala` (flat at `kyo.internal`) | PRESENT | `kyo/internal/ReflectMacro.scala`, `object ReflectMacro`, `def derivedImpl[A: Type](using q: Quotes)` |
| `ReadsInstances.scala` (at `kyo.internal.reflect.reads`) | PRESENT | All 10 base givens + tuples 2-22 |
| `TouchedFields.scala` (at `kyo.internal.reflect.reads`) | PRESENT | `object TouchedFields`, `def analyze(using Quotes)(readBody: Term): Reflect.FieldSet` |
| `Reflect.scala` wiring: replace `compiletime.error` stub | MISSING | Line 354 still reads `scala.compiletime.error(...)`. Macro splice NOT applied. |
| `ReadsDerivationTest.scala` | MISSING | File not found anywhere under `shared/src/test/`. Zero of 18 tests present. |

---

## Reward-hacking checks

| Pattern | Verdict | Citation |
|---|---|---|
| Verification commands actually run | NOT VERIFIABLE (tree dirty, no output log) | No test output or compilation record visible in dirty tree |
| Compile-only success claim | NOT APPLICABLE (no claim visible; macro not even wired) | Reflect.scala still has stub |
| Priority inference / scope substitution | FLAG: the hardest deliverables are missing | Both `Reflect.scala` wiring and `ReadsDerivationTest.scala` absent |
| Foreach-discards-assert | NOT APPLICABLE (no test file to inspect) | |
| Stale-state / tautological matchers | NOT APPLICABLE (no test file) | |
| Sum-type derive produces compile-time error per DESIGN.md §13 | CLEAN (in macro) | ReflectMacro.scala lines 36-54: `Flags.Sealed` and `Flags.Enum` guard with `report.errorAndAbort`, message contains "hand-written" |
| Higher-kinded derive produces compile-time error | CLEAN (in macro) | Lines 57-64: `abstractTypeParams` detection via `typeMembers.filter(_.isAbstract && _.isTypeParam)` |
| Recursive case classes use `lazy val instance` pattern | CLEAN | `emitLazyProduct` at lines 180-197 emits `lazy val instance: Reflect.Reads[A]` |
| `given` override picked up via `Expr.summon[Reflect.Reads[FieldType]]` | CLEAN | Lines 119-129 in `buildProduct`, and lines 249-254 in `buildReadExpr` |
| Hygiene rules applied (Trees.exists guard, Match pattern skip) | PARTIAL | `TouchedFields` implements its own `traverseGoto`/`existsSymbolSelect` inline (NOT using `kyo.internal.Trees`). The PREP doc (Section 4, 5) explicitly prescribes `Trees.traverseGoto` from kyo-direct. The re-implemented version is functionally equivalent but diverges from the prescribed architecture. Also, `GotoQueue.drain` uses `asInstanceOf[List[quotes.reflect.Tree]]` (line 102). |
| Built-in Reads instances present: Name, Flags, Type, Symbol, Chunk[T], Maybe[T], primitives, tuples | CLEAN | All listed in ReadsInstances.scala; tuples 2-22 all present |

---

## Drifting checks

| Pattern | Verdict | Citation |
|---|---|---|
| Public API signatures match plan | FLAG | `Reflect.Reads.derived` still emits `compiletime.error`; the public API is NOT wired to the macro yet (Reflect.scala line 354) |
| No off-plan architecture | FLAG | `TouchedFields` re-implements `traverseGoto`/`existsSymbolSelect` inline rather than delegating to `kyo.internal.Trees` as prescribed by PREP doc sections 4 and 5. PREP explicitly calls out `Trees.traverseGoto` from `kyo-direct/shared/src/main/scala/kyo/internal/Trees.scala`. The re-impl is functionally OK but violates the prescribed architecture. |
| No `asInstanceOf` introduced | FLAG (two sites) | `ReflectMacro.scala:343`: `paramss.head.head.asInstanceOf[Term]` inside `makeLambdaCall`. `TouchedFields.scala:102`: `items.asInstanceOf[List[quotes.reflect.Tree]]` in `GotoQueue.drain`. Both violate `feedback_no_casts`. |
| No new default params on internal APIs | CLEAN | No default params found on private methods |
| Macro entry at `kyo.internal.ReflectMacro` (flat, not nested) | CLEAN | Package is `kyo.internal`, object is `ReflectMacro`; not nested under reads sub-package |
| `ReadsInstances` exported/reachable without explicit import | FLAG | `Reflect.scala` lines 353-354 (`object Reads`) contain only the stub `derived`. No `export ReadsInstances.*` or `given` delegation. The built-in instances are unreachable from user code without explicit `import kyo.internal.reflect.reads.ReadsInstances.*`. PREP doc section 8 (C5) notes this is an implementation decision; the plan says "must be reachable without explicit import". NOT wired. |

---

## Scope-cutting checks (18 plan-mandated test leaves)

| Leaf | Status | Notes |
|---|---|---|
| 1: `case class Simple(...) derives Reflect.Reads` compiles | MISSING | No test file exists |
| 2: `touchedFields` contains `FieldSet.Name | FieldSet.Flags` and no other bits | MISSING | |
| 3: `symbolKinds` is `Set(SymbolKind.values*)` for pure accessors | MISSING | |
| 4: `needsBodies` is `false` | MISSING | |
| 5: `Reads[Simple].read(sym)` returns correct Simple value | MISSING | |
| 6: `case class WithParents(...)` compiles and correct `touchedFields` | MISSING | |
| 7: `Reads[WithParents].symbolKinds` narrowed to Class/Trait/Object | MISSING | |
| 8: Recursive `case class Node(...)` compiles (lazy val) | MISSING | |
| 9: Sealed trait derive produces compile error with "hand-written" | MISSING | |
| 10: Abstract type param derive produces compile error | MISSING | |
| 11: Custom `given Reads[Int]` override is used by derived instance | MISSING | |
| 12: `Reads[Chunk[Reflect.Symbol]].read(sym)` maps over declarations | MISSING | |
| 13: `Reads[Maybe[Reflect.Symbol]].read(sym)` returns Absent for companion-less sym | MISSING | |
| 14: `Reads[(Reflect.Name, Reflect.Flags)]` tuple reads both fields | MISSING | |
| 15: Transitive touchedFields: `Outer` includes Parents from `Inner` | MISSING | |
| 16: Match/Bind hygiene guard: no false-positive FieldSet bits from pattern | MISSING | |
| 17: All built-in Reads instances resolve implicitly via summon | MISSING | |
| 18: `Reads.read` on real fixture symbol returns expected value | MISSING | |

All 18 tests are absent. `ReadsDerivationTest.scala` does not exist.

---

## CRITICAL (steer immediately)

1. **`Reflect.scala` line 354 NOT wired**: The `compiletime.error` stub was NOT replaced with `${ kyo.internal.ReflectMacro.derivedImpl[A] }`. This is the primary deliverable of Phase 6 and must be done before any test can compile. The macro implementation is present but disconnected.

2. **`ReadsDerivationTest.scala` entirely absent**: All 18 plan-mandated tests are missing. The test file must be created at `shared/src/test/scala/kyo/ReadsDerivationTest.scala`. This is not a partial implementation; the file does not exist at all.

3. **`asInstanceOf` in `ReflectMacro.scala` line 343**: `paramss.head.head.asInstanceOf[Term]` violates `feedback_no_casts`. In Scala 3 quote macros the correct approach is to pattern-match `paramss` as `List(List(v: Term))` or use `paramss.head.head.asInstanceOf` only with the correct TASTy reflect type -- but the preferred pattern is `paramss.head.head match { case t: Term => t }` or to use `Ref`-based approach instead of the `DefDef` SAM callback. Must be fixed.

4. **`asInstanceOf` in `TouchedFields.scala` line 102**: `items.asInstanceOf[List[quotes.reflect.Tree]]` in `GotoQueue.drain`. Same violation. Use typed storage instead.

5. **`ReadsInstances` not exported from `Reflect.Reads` companion**: The built-in `given` instances in `ReadsInstances` are unreachable from user code. `object Reads` in `Reflect.scala` must `export kyo.internal.reflect.reads.ReadsInstances.*` (or `given` delegation) so instances are in implicit scope without explicit import.

---

## MINOR (queue for post-commit audit)

1. **`TouchedFields` does not use `kyo.internal.Trees`**: The PREP doc prescribes `Trees.traverseGoto` and `Trees.exists` from `kyo-direct/shared/src/main/scala/kyo/internal/Trees.scala`. The current implementation re-implements both inline. Functionally equivalent, but the stated architectural intent (reuse of kyo-direct primitives) is not fulfilled. Post-commit: verify whether kyo-direct is a compile-scope dependency of kyo-reflect; if not, the inline impl may be the correct pragmatic choice, but the PREP doc should be updated to reflect this.

2. **`extractStaticTouched` is conservative for user-derived instances**: For composed case classes where an inner field type has its own `derives Reflect.Reads`, `extractStaticTouched` returns `FieldSet.Empty` (lines 443-446). This means transitive `touchedFields` propagation (test 15) may undercount bits. The PREP doc (Section 12, C1) notes an alternative tracking approach. Risk: test 15 may fail even after wiring. Flag for early testing.

3. **`Flags` in `directFieldTouched` for boolean flags**: `isInline`, `isContextual`, etc. are mapped to `FieldSet.Flags` but NOT included in `directAccessorExpr`'s listing; they ARE in `directFieldTouched`. Cross-check that the accessor names used in generated read bodies for boolean-flag fields match the Symbol API surface (PREP doc section 1).

---

## Recommendation: STEER: Wire Reflect.scala + create ReadsDerivationTest.scala + fix two asInstanceOf sites + export ReadsInstances
