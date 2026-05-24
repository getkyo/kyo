# Phase 5 In-Flight Review (pulse 1)

Pulse 1: 2026-05-24T00:00Z
Files reviewed:
- `kyo-reflect/execution-plan.md` lines 334-396
- `kyo-reflect/PHASE-5-PREP.md` lines 1-1046
- `kyo-reflect/STEERING.md` (no active Phase 5 directives)
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/classfile/ClassfileFormat.scala` (1-70)
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/classfile/ConstantPool.scala` (1-228)
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/classfile/JavaSignatures.scala` (1-354)
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/classfile/ClassfileUnpickler.scala` (1-637)
- `kyo-reflect/shared/src/test/scala/kyo/ClassfileReaderTest.scala` (1-181)
- `kyo-reflect/shared/src/test/scala/kyo/JavaSignaturesTest.scala` (1-159)

---

## Plan anchor

| Item | Status |
|---|---|
| ClassfileFormat.scala | PRESENT |
| ConstantPool.scala | PRESENT |
| JavaSignatures.scala | PRESENT |
| ClassfileUnpickler.scala | PRESENT |
| Test file ClassfileReaderTest.scala | PRESENT |
| Test file JavaSignaturesTest.scala | PRESENT |
| Fixture .class file in shared/src/test/resources/ | MISSING — only PlainClass.tasty exists; no .class fixture. Plan section 8.3 calls for a pre-compiled .class binary for cross-platform ConstantPool unit tests. |
| Plan test count (20) | 20 test bodies present (11 in ClassfileReaderTest, 8 in JavaSignaturesTest + test 10 cross-platform = correct per test list) |

---

## Reward-hacking checks

| Pattern | Verdict | Citation |
|---|---|---|
| Verification commands actually run | NOT YET RUN — tree is dirty, no evidence of sbt execution; pulse 1 is pre-commit, so this is expected | git status shows `??` for all Phase 5 files |
| Compile-only "success" claim | NOT APPLICABLE — no claim made yet | — |
| Priority inference / scope substitution | CLEAN — no evidence of substitution | all 4 files present |
| Foreach-discards-assert | CLEAN — tests use `.map` and `assert(...)` chains, no discarded computations found | ClassfileReaderTest passim |
| Stale-state / tautological matchers | FLAG (minor) — test 3 ("String has parent including Object") does NOT test parents at all; it merely re-checks `JavaDefined` and `kind == Class`, which are already tested in test 1. Plan leaf 3 says: "String has parents containing a symbol whose name includes Object". The test body is a tautological substitute. | ClassfileReaderTest.scala lines 59-66 |
| Constant pool is 1-indexed (slot 0 unused per JVMS) | CLEAN — `entries = new Array[Entry | Null](count)`, loop starts at `idx = 1`, `entry()` validates `idx >= 1` | ConstantPool.scala lines 131, 133, 24 |
| Long/Double constant pool entries take 2 slots | CLEAN — `entries(idx+1) = Entry.Hole; idx += 2` for tags 5 and 6 | ConstantPool.scala lines 160-165 |
| Code attribute SKIPPED (only signatures matter) | CLEAN — `case _` in member attribute loop skips unknown attributes via `skipBytes(view, attrLen)` | ClassfileUnpickler.scala lines 253-255. Note: `AttrCode` is defined in ClassfileFormat but NOT matched specially; it falls through to the default skip branch, which is correct. |
| Records detected via Record attribute, NOT ACC_RECORD bit | CLEAN — `isRecord = classAttrs.hasRecord` set only when `AttrRecord` attribute name is matched | ClassfileUnpickler.scala lines 374-411, 452 |
| No ASM dependency added to build.sbt | CLEAN — no build.sbt modifications in dirty tree (`build.sbt` not in `git status`) | git status output |

---

## Drifting checks

| Pattern | Verdict | Citation |
|---|---|---|
| Public API signatures match plan | PARTIAL DRIFT — plan specifies `parseFieldSignature(sig, interner, addrMap: Map[Int, Reflect.Symbol])` but implementation uses `typeParamScope: Map[String, Reflect.Symbol] = Map.empty`. PREP doc section 10.2 explicitly calls out this ambiguity and recommends the `typeParamScope` approach. Implementation follows PREP.md recommendation, not raw plan text. Acceptable but note for supervisor. | JavaSignatures.scala lines 61-68; execution-plan.md line 344 |
| No off-plan architecture | CLEAN — structure matches plan exactly: 4 files in classfile/, 2 test files | — |
| No cross-cutting refactor outside Phase 5 | CLEAN — git status shows no modifications to pre-existing Phase 1-4 files | git status output |
| Internal helpers stay in `kyo.internal.reflect.classfile.*` | CLEAN | all 4 source files use `package kyo.internal.reflect.classfile` |
| No `asInstanceOf` introduced | FLAG (CRITICAL) — `view.asInstanceOf[ByteView.Heap]` in ConstantPool.scala line 140. PREP.md section 5 explicitly prohibits this for cross-platform reasons ("No JVM-specific APIs") and MEMORY.md `feedback_no_casts.md` says "never use asInstanceOf". Used to access `heap.bytes` and `heap.position` for lazy UTF-8 storage. This is a hard violation of the no-cast rule. | ConstantPool.scala line 140 |
| No `null` introduced beyond documented sentinels | FLAG (minor) — `null` used as `owner` and `home` parameters in JavaSignatures.scala (lines 25, 27, 55-56) and ClassfileUnpickler.scala (lines 117, 466, 578). For stub/unresolved symbols, null owner is arguably acceptable as a sentinel per the Unresolved kind. However, ClassfileUnpickler.scala line 117 and 466 pass `null` as owner for properly-created class and member symbols. These are not the stub pattern; the class symbol truly has no owner in Phase 5 (owner would be the package, resolved in Phase 7). Supervisor should decide if null owner is an acceptable Phase 5 sentinel. | ClassfileUnpickler.scala lines 117, 466, 578; JavaSignatures.scala lines 25-27 |
| Constant pool UTF-8 decode is LAZY via Interner (per plan) | CLEAN — `Utf8Lazy` stores `(bytes, offset, length)` with `AtomicReference` cache; `decode(interner)` called on first access | ConstantPool.scala lines 81-91 |

---

## Scope-cutting checks (per plan-mandated test leaf, all 20)

| Leaf | Status | Notes |
|---|---|---|
| 1: Object.class kind=Class, name="Object", flags.JavaDefined | PRESENT_STRICT | ClassfileReaderTest lines 38-44 |
| 2: String.length() method present | PRESENT_STRICT | ClassfileReaderTest lines 49-54 |
| 3: String has parents containing symbol named "Object" | PRESENT_WEAKENED | Test body does NOT verify parents; re-checks JavaDefined+kind instead. Plan says "parents containing a symbol whose name includes Object". The field `super_class` is read (superIdx at line 96) but immediately discarded — neither stored in ClassfileResult nor surfaced in the symbol. This is a DOUBLE issue: the test is weakened AND the implementation doesn't wire super_class into parents at all. |
| 4: ArrayList typeParams has at least one TypeParam symbol | PRESENT_WEAKENED | Test only checks name="ArrayList" and JavaDefined flag. Plan says "typeParams with at least one TypeParam symbol". The implementation does not expose typeParams from ClassfileResult; generic signature is not parsed during ClassfileUnpickler.buildResult. |
| 5: ACC_INTERFACE produces kind=Trait | PRESENT_STRICT | ClassfileReaderTest lines 81-85 |
| 6: ACC_ENUM produces Flag.Enum | PRESENT_STRICT | ClassfileReaderTest lines 90-94 |
| 7: static field: kind=Field, flags.JavaDefined | PRESENT_STRICT | ClassfileReaderTest lines 99-105 |
| 8: final non-static field: kind=Val | PRESENT_STRICT | ClassfileReaderTest lines 110-115 |
| 9: mutable non-final field: kind=Var | PRESENT_STRICT | ClassfileReaderTest lines 120-128 |
| 10: wrong magic => ClassfileFormatError | PRESENT_STRICT | ClassfileReaderTest lines 133-150; cross-platform (no jvmOnly tag) |
| 11: javaSpecific is Present for Java symbol, Absent for TASTy | PRESENT_WEAKENED | Test only checks JavaOrigin for the Java symbol. Plan says "javaSpecific is Present for a Java-sourced symbol and Absent for a TASTy-sourced symbol". The second half (Absent for TASTy) is not tested at all. Additionally, `javaSpecific` is a field on Reflect.Symbol; the test accesses `sym.origin` (an internal field), not `sym.javaSpecific`. If the public API is `sym.javaSpecific`, this test uses the wrong accessor. |
| 12: throwsTypes non-empty for method throws IOException | PRESENT_WEAKENED | Test body does NOT verify throwsTypes; it only checks `methodSyms.nonEmpty` and `result.symbols.nonEmpty`. The comment in the test itself admits "JavaMetadata is stored in ClassfileResult, not yet wired to Symbol.javaSpecific in Phase 5". This is a scope cut: throwsTypes is resolved (resolveThrowsTypes builds Chunk[Type]) but not surfaced in any verifiable way in the test. |
| 13: List<String> => Applied(Named(list), Chunk(Named(str))) | PRESENT_STRICT | JavaSignaturesTest lines 17-31 |
| 14: [I => Array(Named(intSym)) | PRESENT_STRICT | JavaSignaturesTest lines 36-43 |
| 15: [[Ljava/lang/String; => Array(Array(Named)) | PRESENT_STRICT | JavaSignaturesTest lines 48-55 |
| 16: List<+Number> covariant => Wildcard(Nothing, Number) | PRESENT_STRICT | JavaSignaturesTest lines 60-82 |
| 17: List<-Number> contravariant => Wildcard(Number, Object) | PRESENT_STRICT | JavaSignaturesTest lines 87-109 |
| 18: raw Ljava/util/List; => Named (not Applied) | PRESENT_STRICT | JavaSignaturesTest lines 114-121 |
| 19: method sig with T => Function with TypeParam for T | PRESENT_STRICT | JavaSignaturesTest lines 126-145 |
| 20: corrupt signature => ClassfileFormatError | PRESENT_STRICT | JavaSignaturesTest lines 150-157 |

---

## CRITICAL (steer immediately)

1. **asInstanceOf cast in ConstantPool.scala line 140**: `view.asInstanceOf[ByteView.Heap]` to access `heap.bytes` and `heap.position`. This violates `feedback_no_casts.md` (hard project rule) and breaks cross-platform safety: if `ByteView` is ever a `Mapped` or another subtype, this will throw at runtime on JVM and may silently corrupt on JS/Native. Fix: `ByteView.read` should expose a way to access the underlying bytes without a cast, OR the lazy UTF-8 design must copy the bytes at read time into a separately allocated buffer instead of storing a pointer into the view. Consult PREP.md section 4 — the `Utf8Lazy` design there stores `(bytes, offset, length)` pointing into the file byte array; Phase 5 must either extend the `ByteView` API to expose a slice accessor, or allocate a copy. Do NOT leave the cast.

2. **Tests 3, 4, 11, 12 are weakened scope cuts that must be fixed**:
   - Test 3: `super_class` is read from the classfile but discarded. Neither ClassfileResult nor the class Symbol exposes parent types. The plan mandates that parents be surfaced. The implementation structurally omits this.
   - Test 4: typeParams are not parsed or surfaced anywhere in the result. The plan mandates at least one TypeParam symbol for ArrayList. The implementation structurally omits this.
   - Test 11: `sym.javaSpecific` (the plan-mandated accessor) vs `sym.origin` (what the test uses) — verify the correct public API accessor.
   - Test 12: `throwsTypes` is built internally but is discarded (not attached to the symbol or ClassfileResult in any accessible way for the test to verify).

---

## MINOR (queue for post-commit audit)

1. Null `owner` passed to `SymbolFactory.makeSymbol` for stub symbols in JavaSignatures.scala and for the class symbol in ClassfileUnpickler.scala. For Phase 5 stubs/unresolved symbols this may be an intentional sentinel (consistent with prior phases), but it should be confirmed against the plan text. The `Symbol.make` call in Phase 1-4 may have a designated null-owner convention.

2. Test 3's test body is a pure tautology (re-tests Test 1 properties instead of parents). Even if the structural issue (parents not surfaced) is fixed, the test body needs to be rewritten to actually assert on the parent chain.

3. No pre-compiled `.class` fixture checked into `shared/src/test/resources/` beyond `PlainClass.tasty`. The plan (PREP.md §8.3) calls for a pre-compiled `.class` binary for cross-platform ConstantPool unit tests. This is acceptable for pulse 1 if JVM-only tests pass, but should be resolved before the phase is considered complete.

4. In the Record attribute handler (ClassfileUnpickler.scala lines 383-395), component attributes (including Signature) are skipped entirely without decoding. The comment admits "For simplicity: skip all component attributes inline." The plan mandates that record component signatures be decoded (PREP.md §2.7 Record attribute section). This is a silent scope cut for record-component type information.

---

## Recommendation: STEER: fix asInstanceOf cast in ConstantPool + fill in parents/typeParams surfacing for tests 3 and 4 before commit
