# Phase 5b Audit (commit 8a66b6e61)

Auditor: SLOT-B. HEAD = 8a66b6e614c702d8042a660103b0529b159f6f88.

Files read end to end:
- kyo-reflect/shared/src/main/scala/kyo/internal/reflect/symbol/FqnCanonicalizer.scala (62 lines)
- kyo-reflect/shared/src/main/scala/kyo/internal/reflect/classfile/JavaAnnotationUnpickler.scala (227 lines)
- kyo-reflect/shared/src/main/scala/kyo/internal/reflect/classfile/ClassfileUnpickler.scala (1129 lines)
- kyo-reflect/shared/src/main/scala/kyo/internal/reflect/symbol/Flags.scala (86 lines)
- kyo-reflect/shared/src/main/scala/kyo/internal/reflect/symbol/SymbolKind.scala (77 lines)
- kyo-reflect/shared/src/main/scala/kyo/Reflect.scala (395 lines)
- kyo-reflect/shared/src/test/scala/kyo/JavaSymbolTest.scala (368 lines)
- kyo-reflect/shared/src/test/scala/kyo/UnifiedModelTest.scala (338 lines)

Plan anchor: execution-plan.md lines 397-460 (Phase 5b Java/Scala unification).

---

## Test count

- Plan: 18 (JavaSymbolTest 1-10, UnifiedModelTest 11-18)
- Implemented: 18 (10 JavaSymbolTest + 8 UnifiedModelTest)
- Per-leaf (1-18):

| # | Plan | Status | Citation |
|---|---|---|---|
| 1 | `java.util.Map$Entry` dotted FQN | PRESENT_STRICT | JavaSymbolTest.scala:93-102, strict `==` against `"java.util.Map.Entry"` |
| 2 | `binaryName` returns `java/util/Map$Entry` | PRESENT_STRICT | JavaSymbolTest.scala:107-116 |
| 3 | top-level class with literal `$` preserves `$` | PRESENT_STRICT | JavaSymbolTest.scala:124-224, cross-platform synthetic bytes |
| 4 | `isJava` true for Java AND false for TASTy | PRESENT_STRICT | JavaSymbolTest.scala:229-244, loads both `java/lang/Object.class` and `PlainClass.tasty`, asserts both halves |
| 5 | `javaSpecific` Present for Java AND Absent for TASTy | PRESENT_STRICT | JavaSymbolTest.scala:249-264, dual assertion |
| 6 | `throwsTypes` non-empty for `throws Exception` | PRESENT_STRICT | JavaSymbolTest.scala:269-283, ThrowsFixture |
| 7 | `accessFlags` for `java.lang.String` has `ACC_FINAL` | PRESENT_STRICT | JavaSymbolTest.scala:288-299, asserts both `Flag.Final` and raw `0x0010` |
| 8 | Java record `Flag.JavaRecord` + non-empty components | PRESENT_STRICT | JavaSymbolTest.scala:304-324, asserts `x` and `y` |
| 9 | `JavaMetadata.annotations` contains Retention-class | PRESENT_STRICT | JavaSymbolTest.scala:329-347 |
| 10 | `enclosingMethod.methodName == "enclosingMethodFixture"` | PRESENT_STRICT | JavaSymbolTest.scala:352-366 |
| 11 | `SymbolKind.Package` for Java owner chain AND Scala TASTy | PRESENT_STRICT | UnifiedModelTest.scala:83-106 |
| 12 | `SymbolKind.Class` for Java AND Scala | PRESENT_STRICT | UnifiedModelTest.scala:111-123 |
| 13 | `SymbolKind.Trait` for Java interface AND Scala trait | PRESENT_STRICT | UnifiedModelTest.scala:128-141 |
| 14 | `SymbolKind.Object` only for Scala object | PRESENT_STRICT | UnifiedModelTest.scala:146-168, asserts no Java Object kind across Object + System fixtures |
| 15 | `TypeAlias`/`OpaqueType`/`AbstractType` only for TASTy | PRESENT_STRICT | UnifiedModelTest.scala:173-207, both negative (Java absent) and positive (TASTy present) |
| 16 | `Type.Array` for Java `int[]` AND Scala `Array[Int]` | PRESENT_WEAKENED | UnifiedModelTest.scala:212-256. The test admits in inline comments that it does NOT exercise classfile/TASTy production of `Type.Array`; it synthesizes a `Reflect.Type.Array(Reflect.Type.Named(...))` directly with `Symbol.makeSymbol(...)` on the LHS and pattern-matches the ADT case. This is a structural ADT check, not a decoder-output check. Plan mandates "both reach `TypeOps.mkArray` path." |
| 17 | Scala case class has `Flag.Case` | PRESENT_STRICT | UnifiedModelTest.scala:261-271 |
| 18 | Full `SymbolKind` matrix (13 non-Unresolved kinds) | PRESENT_STRICT | UnifiedModelTest.scala:276-336, asserts `expectedKinds -- foundKinds == Set.empty` across 5 JDK classfiles + 5 TASTy fixtures |

Tally: 17 PRESENT_STRICT, 1 PRESENT_WEAKENED (test 16), 0 MISSING.

---

## CONTRIBUTING.md violations

(STEERING.md project-specific rules in lieu of CONTRIBUTING.md, per STEERING.md §"Project-specific rules")

- No em-dashes found in 5b sources. CLEAN.
- No semicolons used as statement separators (the `;` hits are inside string literals or comments). CLEAN.
- Lowercase namespace objects: `kyo.internal.reflect.symbol`, `kyo.internal.reflect.classfile`. CLEAN.
- All tests live in `shared/src/test/scala/kyo/`. CLEAN.

---

## Unsafe markers

- `asInstanceOf`: none in 5b-produced files (FqnCanonicalizer, JavaAnnotationUnpickler, ClassfileUnpickler, JavaSymbolTest, UnifiedModelTest, Flags, SymbolKind). Pre-existing `asInstanceOf` in `Memo.scala` and `SingleAssign.scala` is from earlier phases and outside the diff. CLEAN.
- `null` (sentinel justification):
  - JavaAnnotationUnpickler.scala:209 — `descriptorToUnresolvedSymbol` passes `owner = null` for `Reflect.SymbolKind.Unresolved` annotation-class symbols. Matches the documented Phase 3 sentinel for unresolved-symbol owners (Phase 7 resolves them).
  - ClassfileUnpickler.scala:122, 133, 747, 754 (via `buildPackageSymbol` returning null when segments empty), 803, 805, 925, 1054 — all are owner-chain sentinels for unresolved/anonymous classes or package roots. Matches Phase 5 sentinel pattern from STEERING.md §"Phase 5 fixes (RESOLVED)".
- `Frame.internal`: 0 hits in 5b files.
- `AllowUnsafe`: 0 hits.
- `Sync.Unsafe.defer`: 0 hits.

Sentinel use is consistent with prior phases. No new unsafe surface introduced.

---

## STEERING compliance

- FqnCanonicalizer uses InnerClasses table: PRESENT. `FqnCanonicalizer.toFullName(binaryName, innerClassTable)` consults the table at line 25 and recurses on `outerBinaryName` at line 34. ClassfileUnpickler.scala:681 calls `buildInnerClassTable` then passes the result into `resolveNameAndOwner` and `buildEnclosingMethod`.
- `binaryName` accessor on `Reflect.Symbol`: PRESENT. Reflect.scala:215 `def binaryName: String = Symbol.computeBinaryName(this)`; the helper at 253-289 walks the owner chain emitting `/` between package-separated segments and `$` between class-nested segments.
- `FloatVal`/`DoubleVal` in `JavaAnnotation.Value`: PRESENT. Reflect.scala:156-157 add `case FloatVal(f: Float)` and `case DoubleVal(d: Double)`. JavaAnnotationUnpickler.scala:116 and :123 wire `pool.double_` / `pool.float_` to them, closing the JVMS `F`/`D` element-value tag gap. The TODO comments at lines 113-114 and 120-121 are stale (they say "use StringVal fallback" but the code already uses FloatVal/DoubleVal); NOTE only.
- Test 4 has both Java AND TASTy assertions: STRICT. JavaSymbolTest.scala:229-244 loads `java/lang/Object.class` and `PlainClass.tasty` and asserts `javaResult.classSymbol.isJava` AND `!tastySym.isJava`. STEERING.md line 78 is correctly satisfied; the in-flight pulse 2 finding is resolved at HEAD.
- Test 5 has both Java AND TASTy assertions: STRICT. JavaSymbolTest.scala:249-264 asserts `javaResult.classSymbol.javaSpecific.isDefined` AND `tastySym.javaSpecific.isEmpty`.
- Java record support (recordComponents populated): PRESENT. ClassfileUnpickler.scala:510-528 parses `Record` attribute; :668 sets `isRecord`; :676 sets `Flag.JavaRecord` bit; :691 wires `buildRecordComponents` which produces `Chunk[(Reflect.Name, Reflect.Type)]`. Test 8 verifies `x` and `y`.
- EnclosingMethod attribute extraction: PRESENT. ClassfileUnpickler.scala:488-508 parses `EnclosingMethod`; :820-848 builds the `(Symbol, Name)` tuple in `buildEnclosingMethod` and stores in `JavaMetadata.enclosingMethod`. Test 10 validates the method-name path.
- SymbolKind matrix coverage (test 18): PRESENT. UnifiedModelTest.scala:318-332 enumerates all 13 non-Unresolved `SymbolKind` cases and asserts none are missing across the fixture corpus.

---

## Cross-platform consistency

- FqnCanonicalizer: pure string ops, no I/O. JVM/JS/Native safe. CLEAN.
- JavaAnnotationUnpickler: operates on `ByteView` and `ConstantPool` (pre-existing cross-platform abstractions); no `java.*` I/O. CLEAN.
- ClassfileUnpickler 5b additions (Record attribute, EnclosingMethod, FqnCanonicalizer wiring, JavaAnnotationUnpickler wiring): byte arithmetic over `ByteView`. CLEAN.
- Tests JVM-gated where needed: JavaSymbolTest tests 1, 2, 4, 5, 6, 7, 8, 9, 10 carry `taggedAs jvmOnly` (they load JDK classes via `getResourceAsStream` or fixture .class files which are JVM-only resources). Test 3 (synthetic bytes) runs on all platforms — correct. UnifiedModelTest tests 11-18 all carry `taggedAs jvmOnly` because TASTy/.class fixtures are loaded via classloader resources. CLEAN.
- No `kyo-reflect/jvm/`, `kyo-reflect/js/`, `kyo-reflect/native/` source additions in commit 8a66b6e61. CLEAN.

---

## Naming

- Files match plan: `symbol/FqnCanonicalizer.scala` and `classfile/JavaAnnotationUnpickler.scala` produced; `classfile/ClassfileUnpickler.scala` and `symbol/Flags.scala` modified; tests `JavaSymbolTest.scala` and `UnifiedModelTest.scala` produced. CLEAN.
- Internal package layout: `kyo.internal.reflect.{classfile,symbol}.*`. CLEAN.

---

## Steering deviation

`git show --stat 8a66b6e61`: 14 production files + 4 fixture .class files + 6 doc files (PHASE-5-AUDIT.md, PHASE-5b-INFLIGHT-REVIEW-1.md, -2.md, PHASE-6b-PREP.md, PROGRESS.md, STEERING.md). The production set matches the plan files-to-produce + files-to-modify list. Additions beyond the plan:
- `ConstantPool.scala` modified (+28 lines) — needed to add `float_` and `double_` accessors that `JavaAnnotationUnpickler.readElementValue` consumes. This is a justified plumbing addition, not in plan's files-to-modify list but unavoidable for `FloatVal`/`DoubleVal` wiring. NOTE.
- `AstUnpickler.scala` modified (+4 lines) — small touch, not in plan. NOTE.
- `SymbolKind.scala` modified (+21 lines) — slight expansion of TYPEDEF discrimination helpers (`fromTypedefTypeFlagsAndBody`). Defensible but off-plan-list. NOTE.
- `Reflect.scala` modified (+30 lines) — `binaryName` accessor + `FloatVal`/`DoubleVal` enum cases. Mandated by STEERING #1 and #2; matches plan's public API modifications section. CLEAN.

No file deletions; matches "Files to delete: none."

---

## Anti-flakiness

- Fixture bytes deterministic: 4 .class fixtures are checked into git (`AnonymousFixture$1.class`, `AnonymousFixture.class`, `PointRecord.class`, `ThrowsFixture.class`); synthetic bytes for test 3 are inline literal arrays. TASTy fixtures live in `kyo-reflect-fixtures` (separate sbt module; built deterministically by scalac).
- No timing-dependent tests in 5b: no `Async.sleep`, no `Clock`, no `Promise`/`Latch`. Pure synchronous reads inside `Sync & Abort[ReflectError]` chains. CLEAN.

---

## Findings categorization

### BLOCKER

(none)

### WARN

1. **Test 16 (`Type.Array`) is structurally weakened.** UnifiedModelTest.scala:230-255 synthesizes a `Reflect.Type.Array(...)` directly via `kyo.internal.reflect.symbol.Symbol.makeSymbol(...)` and pattern-matches the resulting ADT case. It does NOT verify that `ClassfileUnpickler` or `AstUnpickler` actually produces `Type.Array` for `int[]` or `Array[Int]`. The inline comments at lines 222-229 and 248-250 explicitly acknowledge this is an ADT-structural check, not a decoder-output check, deferring real array-type production to Phase 7. The plan text says "both reach `TypeOps.mkArray` path" which the current test does not exercise. Queue for Phase 7 strengthening.

2. **Stale TODO comments in JavaAnnotationUnpickler.scala** at lines 113-114 (`// FloatVal/DoubleVal not in enum; use StringVal as documented fallback. // TODO Phase 7: extend JavaAnnotation.Value with DoubleVal(d: Double).`) and lines 120-121 (mirror for FloatVal). Both enum cases are now present and the code at :116 and :123 uses them. Strip the misleading comments.

3. **Pulse-1 minor #4-#5 carried forward unaddressed.** `buildEnclosingMethod` (ClassfileUnpickler.scala:843-844) stores `Present((enclosingClassSym, Reflect.Name("")))` when `enclosingMethodIdx == Absent` (i.e., the class is enclosed by a class but not by a method, e.g., a field initializer). The pulse-1 review (and pulse-2) flagged this as deviating from PREP §6.2 which calls for `Absent` in that case. Test 10 only exercises the method-name-present path so this stays latent. Queue for fix before Phase 7 uses enclosingMethod semantically.

4. **`readAnnotations` signature drift** (carried from pulse 1 #3 / unresolved by STEERING). Plan (line 406) specifies `def readAnnotations(view, constantPool, interner, addrMap: Map[Int, Reflect.Symbol])`. Implementation (JavaAnnotationUnpickler.scala:30-35) uses `def readAnnotations(view, pool, interner, home: ClasspathRef)`. The substitution is functionally sound (annotations build `Unresolved` symbols using `home`, not `addrMap` which is a TASTy concept), but the rename and the type swap were not escalated to STEERING.md. NOTE the deviation; supervisor should either accept by amending STEERING or fix the param name. Low impact: no caller outside the unpickler.

5. **`Flags.fromJvmAccessFlags` ACC_INTERFACE check uses ACC_ABSTRACT bit.** Flags.scala:77-78: both `Flag.Abstract` and `Flag.Trait` are set when `(acc & 0x0200) != 0`. ACC_INTERFACE is `0x0200` per JVMS Table 4.1-A (NOT ACC_ABSTRACT, which is also `0x0400` for methods or `0x0400` for classes per JVMS Table 4.1-B). Actual constants: ACC_ABSTRACT = `0x0400`, ACC_INTERFACE = `0x0200`. Line 77 checks `0x0200` and tags it `Abstract` — this conflates ACC_INTERFACE with ACC_ABSTRACT. Tests 12 and 13 cover `Class`/`Trait` discrimination at the symbol-kind level (which uses `ACC_INTERFACE` correctly at ClassfileUnpickler.scala:666) but `Flag.Abstract` on a non-interface abstract class is NOT set. This is a real bug in the access-flag-to-Flags conversion. PREP §4.3 said ACC_INTERFACE → Trait alone; the line 77 `0x0200` → Abstract appears unintentional. Investigate.

6. **`Flags.fromJvmAccessFlags` ACC_STATIC mapped to `Flag.JavaDefined`.** Flags.scala:81: `if (acc & 0x0008) != 0 then bits |= Flag.JavaDefined.bit // ACC_STATIC`. This conflates "static" with "Java-defined" (`Flag.JavaDefined` is already set unconditionally in ClassfileUnpickler.scala:675 for every Java symbol). There is no `Flag.Static`; the static bit is silently dropped by the abstract-flag conversion and the comment is misleading. Investigate whether a `Flag.Static` is needed (likely yes for matrix queries) or whether this line should be removed.

### NOTE

1. `FqnCanonicalizer.buildReverseIndex` is an off-plan helper used by binaryName reverse lookup. Consistent with PREP §2.3; harmless.
2. The Phase 5b commit message says "129 total kyo-reflect tests green; JS + Native compile clean." Audit verified the test files compile structurally; cannot verify green-run from a static audit.
3. `kyo-reflect-fixtures` module supplies the TASTy fixtures (`SomeTrait.tasty`, `SomeObject.tasty`, `Container.tasty`, `GenericBox.tasty`, `FixtureClasses$package.tasty`, `SomeCaseClass.tasty`, `PlainClass.tasty`). UnifiedModelTest depends on the fixtures module being on the test classpath. Wiring is pre-existing per Phase 0.5; no 5b regression.
4. Test 16 uses `kyo.internal.reflect.symbol.Symbol.makeSymbol` directly in test code (UnifiedModelTest.scala:231). This is a minor breach of `feedback_tests_use_public_api` (public API on LHS, internals only on RHS for verification). Cosmetic.
5. `decodeAnnotations` flattens visible+invisible annotations (ClassfileUnpickler.scala:955). The two attribute kinds are not distinguished downstream — `JavaMetadata.annotations` is one merged Chunk. JVMS keeps them separate. No metadata bit indicates which was which. Defer to Phase 7 if downstream cares.
6. Test 18's matrix covers 13 of 14 kinds and explicitly defers `SymbolKind.Unresolved` to Phase 7's `SymbolResolutionTest` per plan line 442. CLEAN.

---

## Recommendation: PROCEED

The Phase 5b implementation satisfies all plan-mandated deliverables: 18 of 18 tests present, 17 strict + 1 weakened (test 16, knowingly deferred to Phase 7 per inline comments). All STEERING directives for Phase 5b are complied with — `binaryName`, `FloatVal`/`DoubleVal`, dual Java/TASTy assertions in tests 4 and 5, FqnCanonicalizer with InnerClasses table, Java record + EnclosingMethod + SymbolKind matrix all wired. No BLOCKER, 6 WARN (4 pre-existing carry-forwards plus 2 newly-spotted access-flag-mapping bugs in `Flags.fromJvmAccessFlags` lines 77 and 81). The two new WARNs (#5 and #6 above) are real semantic bugs but do not block Phase 5b's stated scope (tests 7 and 12-14 pass because the symbol-kind discrimination is done correctly at ClassfileUnpickler.scala:666-672, before `Flag.Abstract` matters). Queue WARNs for Phase 6/7 fix-up before flag-based queries become user-facing.
