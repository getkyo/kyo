# Phase 5b In-Flight Review (pulse 1)

Pulse 1: 2026-05-24T00:00Z
Files reviewed:
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/symbol/FqnCanonicalizer.scala` (full, 62 lines)
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/classfile/JavaAnnotationUnpickler.scala` (full, 227 lines)
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/classfile/ClassfileUnpickler.scala` (full, 1125 lines)
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/symbol/Flags.scala` (full, 86 lines)
- `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala` (lines 140-310)
- `kyo-reflect/shared/src/test/scala/kyo/JavaSymbolTest.scala` (full, 260 lines)
- `kyo-reflect/shared/src/test/resources/kyo/fixtures/` (directory listing)
- `kyo-reflect/PROGRESS.md` (Phase 5b status row)
- `kyo-reflect/STEERING.md` (Phase 5b concerns section)
- `git status --short kyo-reflect/` (dirty tree snapshot)

---

## Plan anchor

### Files to produce (per execution-plan.md lines 404-408)

| File | Status |
|------|--------|
| `symbol/FqnCanonicalizer.scala` | PRESENT (untracked, confirmed new) |
| `classfile/JavaAnnotationUnpickler.scala` | PRESENT (untracked, confirmed new) |
| `test/scala/kyo/JavaSymbolTest.scala` | PRESENT (untracked, confirmed new) |
| `test/scala/kyo/UnifiedModelTest.scala` | **MISSING** — not in tree at all |

### Files to modify (per execution-plan.md lines 410-412)

| File | Status |
|------|--------|
| `classfile/ClassfileUnpickler.scala` | PRESENT (modified: FqnCanonicalizer wired, JavaAnnotationUnpickler wired, Record attribute parsed, Flag.JavaRecord set) |
| `symbol/Flags.scala` | PRESENT (modified: ACC_INTERFACE -> Flag.Trait added at line 78) |

### Test count

Plan mandates 18 tests (10 in JavaSymbolTest, 8 in UnifiedModelTest).
- `JavaSymbolTest.scala`: 10 tests present (tests 1-10 per plan numbering, verified by manual inspection).
- `UnifiedModelTest.scala`: 0 tests — file does not exist.

**Actual test count: 10 of 18. Delta: -8 (all of UnifiedModelTest missing).**

---

## Reward-hacking checks

| Pattern | Verdict | Citation |
|---------|---------|---------|
| Verification commands actually run | UNKNOWN — no sbt output artifacts; PROGRESS.md shows Phase 5b status as `pending` (not committed/verified) | PROGRESS.md row: `5b | pending | - | 18 | -` |
| Compile-only success claim | N/A — Phase 5b not yet claimed complete in PROGRESS.md | |
| Priority inference / scope substitution | CLEAN — no evidence of intentional substitution; UnifiedModelTest simply absent, not replaced | |
| Foreach-discards-assert in tests | CLEAN — all 10 JavaSymbolTest cases use `assert(...)` directly; no dangling computations observed | JavaSymbolTest.scala lines 58-258 |
| Stale-state / tautological matchers | CLEAN — all assertions compare against specific expected strings or flag bits; no trivially-true checks | |
| FqnCanonicalizer uses InnerClasses table (per STEERING #1) | CLEAN — `FqnCanonicalizer.toFullName` takes and consults `innerClassTable: Map[String, (String, String)]`; ClassfileUnpickler calls `buildInnerClassTable` before `resolveNameAndOwner` and passes the table | FqnCanonicalizer.scala:24; ClassfileUnpickler.scala:681-685 |
| FloatVal/DoubleVal added to JavaAnnotation.Value (per STEERING #2) | **PARTIAL COMPLY / RISK** — `FloatVal(f: Float)` and `DoubleVal(d: Double)` ARE added to `Reflect.JavaAnnotation.Value` (Reflect.scala lines 156-157); `JavaAnnotationUnpickler` uses them (lines 116, 123). STEERING #2 said "add or document the gap." They were added. However, the PREP doc (§5.3) described this as requiring supervisor approval and recommended the `StringVal` fallback. The agent chose to add the new enum cases without explicit approval. This is the correct behavior per STEERING #2's "apply #1 and #2 in Phase 5b impl." CLEAN per STEERING directive; minor process note only. | Reflect.scala:156-157; JavaAnnotationUnpickler.scala:116,123; STEERING.md:82 |
| Record components actually populated (not Chunk.empty per Phase 5 W) | CLEAN — `buildRecordComponents` is wired in `buildResult` (ClassfileUnpickler.scala:691); `PointRecord.class` fixture present; test 8 asserts `components.nonEmpty` and checks for "x" and "y" | ClassfileUnpickler.scala:691; JavaSymbolTest.scala:196-216 |
| symbolKind matrix tests cover all 14 kinds | **FLAG** — `UnifiedModelTest.scala` is entirely absent; test 18 (full SymbolKind matrix) is the primary vehicle for this check. 0 of 8 UnifiedModel tests exist. | File listing confirms absence |

---

## Drifting checks

| Pattern | Verdict | Citation |
|---------|---------|---------|
| Public API signatures match plan | **DRIFT** — `readAnnotations` plan signature takes `addrMap: Map[Int, Reflect.Symbol]` as 4th param; actual impl takes `home: ClasspathRef` as 4th param. Param name and type differ. PREP §5.4 specifies `addrMap`. The change is pragmatically sensible (classfile context does not use TASTy addrMap), but it departs from the specified signature without escalation. | PHASE-5b-PREP.md §5.4; JavaAnnotationUnpickler.scala:30-35 |
| No off-plan architecture | CLEAN — `buildReverseIndex` helper added to `FqnCanonicalizer` (not in plan); this is additive and consistent with PREP §2.3 which explicitly describes building a reverse index. Minor addition, not off-plan. | FqnCanonicalizer.scala:53-60 |
| No new `asInstanceOf` | CLEAN — no `asInstanceOf` found in FqnCanonicalizer or JavaAnnotationUnpickler | grep confirms zero hits |
| No new `null` beyond documented sentinels | CLEAN — `null` appears once in `JavaAnnotationUnpickler.scala` line 209 as the `owner` sentinel for Unresolved symbols, matching the documented sentinel pattern from PREP §12.5 | JavaAnnotationUnpickler.scala:209; PHASE-5b-PREP.md §12.5 |
| No new default params on internal APIs (policy: feedback_no_default_params_internal) | **FLAG** — `Reflect.Symbol.make` factory at Reflect.scala line 303 has `javaMetadata: Maybe[JavaMetadata] = Absent` (default param). This was already flagged in Phase 5 audit (PROGRESS.md TaskCreate #77) and was pre-existing at Phase 5b start. Phase 5b did NOT add new default params. The existing violation is inherited, not new. | PROGRESS.md "javaMetadata default param (also TaskCreate #77)" |
| `binaryName` accessor exists on Reflect.Symbol | CLEAN — `def binaryName: String = Symbol.computeBinaryName(this)` at Reflect.scala line 215. `computeBinaryName` uses kind-aware separator logic (Package -> `/`, Class/Trait/Object -> `$`) per plan concern §9.9/§12.1 | Reflect.scala:215,253-289 |

### Additional drift note: readAnnotations signature mismatch

The plan (execution-plan.md line 406 and PREP §5.4) mandates:
```
def readAnnotations(view, constantPool, interner, addrMap: Map[Int, Reflect.Symbol]): ...
```
The implementation has:
```
def readAnnotations(view, pool, interner, home: ClasspathRef): ...
```
`addrMap` replaced by `home: ClasspathRef`. The parameter is actually needed for symbol construction (PREP §5.5 creates Unresolved symbols from it), so `home` is functionally correct. But the name `pool` (not `constantPool` as specified) and replacing `addrMap` with `home` constitutes API drift from spec. PREP §12.4 acknowledges addrMap naming confusion. The supervisor should confirm this substitution is acceptable before commit.

---

## Scope-cutting checks (all 18 plan-mandated tests)

| # | Plan description | Status |
|---|-----------------|--------|
| 1 | `sym.fullName.asString` for `Map$Entry.class` returns `"java.util.Map.Entry"` | PRESENT_STRICT — JavaSymbolTest.scala:54-63, strict string equality |
| 2 | `sym.binaryName` returns `"java/util/Map$Entry"` | PRESENT_STRICT — JavaSymbolTest.scala:68-77, strict string equality |
| 3 | Top-level class with literal `$` in binary name (no InnerClasses entry) preserves `$` in `fullName` | PRESENT_STRICT — JavaSymbolTest.scala:85-138, synthetic classfile bytes, cross-platform |
| 4 | `sym.isJava == true` for Java; `sym.isJava == false` for TASTy | PRESENT_WEAKENED — test 4 (JavaSymbolTest.scala:143-148) only checks `isJava == true`; the `isJava == false` half (TASTy symbol) is NOT asserted. Plan mandates both halves. |
| 5 | `sym.javaSpecific` is `Present` for Java, `Absent` for Scala | PRESENT_WEAKENED — test 5 (JavaSymbolTest.scala:153-158) only asserts `Present` for Java; no TASTy-side `Absent` check within this test. (Note: test 11 in `ClassfileReaderTest` covers the Absent side, but that is a pre-Phase-5b test in a different class.) |
| 6 | `JavaMetadata.throwsTypes` non-empty for `throws Exception` method (fixture bytes) | PRESENT_STRICT — JavaSymbolTest.scala:163-175, uses `ThrowsFixture.class`, asserts `throwsMethod` name |
| 7 | `JavaMetadata.accessFlags` for `java.lang.String` has `ACC_FINAL` (0x0010) set | PRESENT_STRICT — JavaSymbolTest.scala:180-191, checks both `flags.contains(Flag.Final)` and raw `accessFlags & 0x0010` |
| 8 | Java record fixture produces `Flag.JavaRecord` + non-empty `recordComponents` with `x`, `y` | PRESENT_STRICT — JavaSymbolTest.scala:196-216, uses `PointRecord.class` fixture |
| 9 | `JavaMetadata.annotations` for `@Deprecated` class contains `@Retention` | PRESENT_STRICT — JavaSymbolTest.scala:221-239 |
| 10 | `JavaMetadata.enclosingMethod` is `Present` for anonymous class; method name is `"enclosingMethodFixture"` | PRESENT_STRICT — JavaSymbolTest.scala:244-258, uses `AnonymousFixture$1.class` fixture |
| 11 | `SymbolKind.Package` for both Java and Scala package symbols | MISSING — `UnifiedModelTest.scala` absent |
| 12 | `SymbolKind.Class` for Java `class` and Scala `class` | MISSING — `UnifiedModelTest.scala` absent |
| 13 | `SymbolKind.Trait` for Java `interface` and Scala `trait` | MISSING — `UnifiedModelTest.scala` absent |
| 14 | `SymbolKind.Object` only for Scala `object`; no Java symbol has `kind == Object` | MISSING — `UnifiedModelTest.scala` absent |
| 15 | `SymbolKind.TypeAlias`, `OpaqueType`, `AbstractType` only for TASTy symbols | MISSING — `UnifiedModelTest.scala` absent |
| 16 | `Type.Array(elem)` for both Java `int[]` and Scala `Array[Int]` | MISSING — `UnifiedModelTest.scala` absent |
| 17 | Scala `case class` from TASTy has `flags.contains(Flag.Case)` | MISSING — `UnifiedModelTest.scala` absent |
| 18 | Full SymbolKind matrix: all 13 non-Unresolved kinds covered by at least one fixture symbol | MISSING — `UnifiedModelTest.scala` absent |

**Present strict**: 8 (tests 1-3, 6-10)
**Present weakened**: 2 (tests 4, 5)
**Missing**: 8 (tests 11-18)

### Missing Scala TASTy fixtures for UnifiedModelTest

Per PREP §10.5, tests 11-18 require additional `.tasty` fixture files not yet on disk:
- `PlainTrait.tasty` — MISSING
- `PlainObject.tasty` — MISSING
- `PlainAlias.tasty` — MISSING
- `PlainOpaque.tasty` — MISSING
- `PlainAbstractType.tasty` — MISSING

Only `PlainClass.tasty` exists. These are required for test 18's SymbolKind matrix coverage.

---

## CRITICAL (steer immediately)

1. **`UnifiedModelTest.scala` entirely absent** — 8 of 18 plan-mandated tests (tests 11-18) do not exist. This is the primary deliverable gap for Phase 5b. The agent must create this file with all 8 tests AND the 5 missing TASTy fixtures (`PlainTrait.tasty`, `PlainObject.tasty`, `PlainAlias.tasty`, `PlainOpaque.tasty`, `PlainAbstractType.tasty`).

2. **Tests 4 and 5 weakened** — both omit the TASTy-side assertion. Test 4 must also assert `sym.isJava == false` for a TASTy-sourced symbol. Test 5 must also assert `sym.javaSpecific.isEmpty` for a TASTy-sourced symbol. Fixtures already available (`PlainClass.tasty`).

3. **`readAnnotations` signature drift from plan** — implementation uses `home: ClasspathRef` where plan specifies `addrMap: Map[Int, Reflect.Symbol]`. Supervisor must either accept this substitution (annotating STEERING.md) or require the agent to align the parameter name. The functional behavior is sound, but the deviation is undocumented.

---

## MINOR (queue for post-commit audit)

1. `FqnCanonicalizer.buildReverseIndex` is an off-plan helper method. Functionally consistent with PREP §2.3, but not mandated by the plan. Harmless; acceptable unless plan strictly prohibits unlisted additions.

2. `readAnnotations` parameter name changed from `constantPool` (plan) to `pool` (impl). Cosmetic but a drift from spec.

3. The `ACC_INTERFACE -> Flag.Trait` mapping (Flags.scala line 78) also inherits the `Abstract` bit from line 77 (`ACC_ABSTRACT = 0x0200` same bit). PREP §4.3 note says "it falls out naturally from `fromJvmAccessFlags`." This is correct per spec (ACC_INTERFACE always has ACC_ABSTRACT set simultaneously). No action needed, but worth noting the double-mask is intentional.

4. `ClassfileUnpickler.buildPackageOwnerChain` for the enclosing class (line 830) calls the function passing `enclosingBinaryName` directly, then overwrites the computed owner with an `Unresolved` symbol (line 831-838). The returned symbol uses `SymbolKind.Unresolved` but with an owner chain built by `buildPackageOwnerChain`. This means `enclosingClassSym.fullName` would walk the owner chain and return a non-empty dotted FQN. Phase 7 resolves these anyway; no action needed now.

5. `enclosingMethod` when `method_index == 0` stores `Present((enclosingClassSym, Reflect.Name("")))` (empty method name) rather than `Absent` (ClassfileUnpickler.scala:840). PREP §6.2 says `enclosingMethod = Absent` for that case. This is a minor semantic deviation. Test 10 only tests the non-zero case so this goes undetected. Queue for fix.

---

## Recommendation: STEER — create UnifiedModelTest + fix weakened tests 4 and 5

The Phase 5b implementation is structurally sound (FqnCanonicalizer, JavaAnnotationUnpickler, ClassfileUnpickler wiring, Flags update, JavaSymbolTest tests 1-3 and 6-10 all present and strict). The critical gap is `UnifiedModelTest.scala` (8 missing tests) and two weakened assertions in JavaSymbolTest. The agent must produce these before Phase 5b can be committed.
