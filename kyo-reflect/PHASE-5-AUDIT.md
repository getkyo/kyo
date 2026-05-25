# Phase 5 Audit (commit 79bea87b1)

Scope: classfile reader (`ClassfileFormat`, `ConstantPool`, `JavaSignatures`, `ClassfileUnpickler`) plus the cross-cutting `Reflect.Symbol.javaMetadata` plumbing introduced by the pulse-2 fix-up agent, plus `ByteView.Heap.copyBytes`. Files read end-to-end:

- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/classfile/ClassfileFormat.scala` (1-70)
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/classfile/ConstantPool.scala` (1-242)
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/classfile/JavaSignatures.scala` (1-350)
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/classfile/ClassfileUnpickler.scala` (1-691)
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/symbol/Symbol.scala` (1-31)
- `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala` (diff: lines 205-300 — `Symbol`, `Symbol.make`)
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/binary/ByteView.scala` (diff: lines 72-94 — `copyBytes`)
- `kyo-reflect/shared/src/test/scala/kyo/ClassfileReaderTest.scala` (1-226)
- `kyo-reflect/shared/src/test/scala/kyo/JavaSignaturesTest.scala` (1-165)

## Test count

- Plan: 20 (ClassfileReaderTest tests 1-12 JVM-only, JavaSignaturesTest tests 13-20 cross-platform)
- Implemented: 20 (12 in `ClassfileReaderTest` + 8 in `JavaSignaturesTest`)

Per-leaf (1-20):

| # | Plan leaf | Status | Citation |
|---|-----------|--------|----------|
| 1 | Object.class kind=Class, name=Object, JavaDefined | PRESENT_STRICT | ClassfileReaderTest.scala 78-84 |
| 2 | String has 'length' method symbol | PRESENT_STRICT | ClassfileReaderTest.scala 89-94 |
| 3 | String parents contains Named whose name includes "Object" | PRESENT_STRICT | ClassfileReaderTest.scala 99-107 — now asserts on `result.parents`, matches on `Type.Named(sym)` with `sym.name.asString.contains("Object")` |
| 4 | ArrayList typeParams has at least one TypeParam | PRESENT_WEAKENED | ClassfileReaderTest.scala 112-117 — only checks `sym.name.asString == "ArrayList"` and JavaDefined flag; does NOT load or assert on typeParams. Plan says "typeParams with at least one TypeParam symbol". Class-level Signature attribute parsing is never invoked from `buildResult` (`classAttrs.signatureIdx` is decoded into `ClassAttributes` but never fed into `JavaSignatures.parseClassSignature`). |
| 5 | ACC_INTERFACE => kind=Trait | PRESENT_STRICT | ClassfileReaderTest.scala 122-126 |
| 6 | ACC_ENUM => Flag.Enum | PRESENT_STRICT | ClassfileReaderTest.scala 131-135 |
| 7 | static field: kind=Field, JavaDefined | PRESENT_STRICT | ClassfileReaderTest.scala 140-146 |
| 8 | final non-static => kind=Val | PRESENT_STRICT | ClassfileReaderTest.scala 151-156 |
| 9 | mutable non-final => kind=Var | PRESENT_STRICT | ClassfileReaderTest.scala 161-169 |
| 10 | Wrong magic => ClassfileFormatError | PRESENT_STRICT | ClassfileReaderTest.scala 174-191 — cross-platform (no `jvmOnly` tag), uses raw byte array, matches `Result.Failure(ReflectError.ClassfileFormatError(_, reason))` |
| 11 | javaSpecific Present for Java, Absent for TASTy | PRESENT_STRICT | ClassfileReaderTest.scala 196-203 — asserts `javaSym.javaSpecific.isDefined` AND loads `PlainClass.tasty`, asserts `tastySym.javaSpecific.isEmpty` |
| 12 | throws IOException populates throwsTypes | PRESENT_STRICT | ClassfileReaderTest.scala 208-224 — filters `sym.javaSpecific.map(_.throwsTypes.nonEmpty).getOrElse(false)`, asserts non-empty |
| 13 | List<String> => Applied(Named(list), Chunk(Named(str))) | PRESENT_STRICT | JavaSignaturesTest.scala 17-31 |
| 14 | [I => Array(Named(intSym)) | PRESENT_STRICT | JavaSignaturesTest.scala 36-43 |
| 15 | [[Ljava/lang/String; => Array(Array(Named)) | PRESENT_STRICT | JavaSignaturesTest.scala 48-55 |
| 16 | List<+Number> => Wildcard(Nothing, Number) | PRESENT_STRICT | JavaSignaturesTest.scala 60-85 |
| 17 | List<-Number> => Wildcard(Number, Object) | PRESENT_STRICT | JavaSignaturesTest.scala 90-115 |
| 18 | Raw Ljava/util/List; => Named (not Applied) | PRESENT_STRICT | JavaSignaturesTest.scala 120-127 |
| 19 | Method sig with T => Function with TypeParam for T | PRESENT_STRICT | JavaSignaturesTest.scala 132-151 |
| 20 | Corrupt sig => ClassfileFormatError | PRESENT_STRICT | JavaSignaturesTest.scala 156-163 |

Summary: 19/20 PRESENT_STRICT, 1/20 PRESENT_WEAKENED (test 4).

## CONTRIBUTING.md violations

- **`throw new IllegalStateException` in production code** at `ConstantPool.scala:160` and `ConstantPool.scala:233`. CONTRIBUTING and `feedback_log_unexpected_failures` require unexpected failures to be surfaced as effects (`Abort.fail(ReflectError.ClassfileFormatError(...))`), not thrown. Both throws are reachable on malformed input (line 160: any non-`Heap` `ByteView` variant; line 233: unknown CP tag from a corrupt or future classfile). They escape the Kyo effect layer cross-platform.
- **`throw new RuntimeException` in tests** at `ClassfileReaderTest.scala:28` and `:45`. Acceptable in test harness for "resource missing" precondition (not the unit under test), but still a stylistic violation; could be `fail(...)`.
- **`null` as `owner`** (see Unsafe markers section). The Reflect.Symbol constructor's `owner` is non-Maybe `Reflect.Symbol`; passing `null` is a sentinel without compiler guard. Phase 1-4 already established the sentinel pattern for stubs, so this is consistent, but it remains an unsafe contract — see `feedback_no_unsafe`.
- **Lowercased namespace object**: not applicable — `Reflect.Symbol`, `Reflect.SymbolKind`, `Reflect.Flag`, `JavaMetadata`, `ClassfileFormat`, `ConstantPool`, `JavaSignatures`, `ClassfileUnpickler` are all legitimate type/factory companions, not namespace containers per `feedback_lowercase_namespace_objects`.

## Unsafe markers

- **asInstanceOf**: NONE in any Phase 5 file. The pulse-1 violation at the former `ConstantPool.scala:140` (`view.asInstanceOf[ByteView.Heap]`) has been replaced with a pattern match `case h: ByteView.Heap => h.copyBytes(off, off + len)` (line 158-160). Verified via `grep -nE "asInstanceOf"` over all Phase 5 sources: zero hits.
- **null**:
  - `ConstantPool.scala:19`: `AtomicReference[Interner.Entry | Null](null)` — justified as cache sentinel for lazy UTF-8 decode (matches `feedback_no_unsafe`'s "documented sentinel" carve-out).
  - `ConstantPool.scala:23`, `:71`: null-checks against the cache and the entries array — pair with the AtomicRef sentinel.
  - `ClassfileUnpickler.scala:121, 132, 519`: `null` passed as `owner` to `SymbolFactory.makeSymbol` for the class symbol, unresolved-class type stub, and `makeUnresolvedSymbol`. Phase 7 resolver replaces these. Pre-existing Phase 5 sentinel pattern.
  - `ClassfileUnpickler.scala:632`: `null` as `owner` inside `resolveThrowsList` — same sentinel pattern.
  - `JavaSignatures.scala:25, 54`: `null` as `owner` in `makeStub`/`classStub` — same sentinel pattern.
- **Frame.internal**: NONE introduced.
- **AllowUnsafe**: NONE introduced.
- **Sync.Unsafe.defer**: NONE — all side-effects use the safe `Sync.defer`.

## STEERING compliance

| Directive | Status | Evidence |
|---|---|---|
| `asInstanceOf` in ConstantPool.scala removed | PRESENT | ConstantPool.scala:158-160 — pattern match `case h: ByteView.Heap => h.copyBytes(off, off+len)`; `ByteView.scala:79-84` adds `copyBytes`. |
| `ClassfileResult.parents` wired from super_class + interfaces | PRESENT | ClassfileUnpickler.scala:25-31 (`parents: Chunk[Reflect.Type]` field), 151-164 (`resolveOptionalSuperType` + `resolveInterfaceTypes` then `parents = superTypeOpt match ...`), 530 (passed into `ClassfileResult`). |
| `Symbol.javaSpecific` populated for Java symbols | PRESENT | `Reflect.scala` Symbol now has `javaMetadata: Maybe[JavaMetadata] = Absent` constructor param; `def javaSpecific = javaMetadata`. `Symbol.make` and `SymbolFactory.makeSymbol` both accept `javaMetadata`. `ClassfileUnpickler.scala:507-523` builds `classMetadata` and passes `Present(classMetadata)`. `:586-607` builds per-member `metadata` and passes `Present(metadata)`. |
| `JavaMetadata.throwsTypes` from Exceptions attribute | PRESENT | `ClassfileUnpickler.scala:282-292` reads `AttrExceptions` into `exceptionIdxs`. `:609-636` `resolveThrowsTypes` / `resolveThrowsList` maps to `Chunk[Reflect.Type.Named(unresolvedSym)]`. `:585-606` wires into `JavaMetadata` then into the symbol. |
| Test 3 strict (parents.contains Object) | PRESENT | ClassfileReaderTest.scala 99-107. |
| Test 11 strict (Java has javaSpecific Present, TASTy has Absent) | PRESENT | ClassfileReaderTest.scala 196-203 — covers both halves via `firstClassSymbolFromTasty` helper running pass-1 over `PlainClass.tasty`. |
| Test 12 strict (throws IOException populates throwsTypes) | PRESENT | ClassfileReaderTest.scala 208-224. |

All four STEERING blockers from pulse 1/2 are now complied. STEERING section "Phase 5 fixes (BLOCKING before commit)" should be cleared in the next phase prep.

## Cross-platform consistency

- `ClassfileReaderTest` tests 1-9, 11, 12 are tagged `jvmOnly` because they use `getClass.getClassLoader.getResourceAsStream("java/...")` which requires the JDK classpath. Plan §"Verification" line 396 explicitly allows this dependency and acknowledges no `kyo-reflect-fixtures` exists yet.
- `ClassfileReaderTest` test 10 (wrong magic) is **cross-platform** (no `jvmOnly` tag) and uses a hand-built `Array[Byte]`.
- `JavaSignaturesTest` tests 13-20 are all cross-platform (pure string parsing, no I/O).
- No pre-baked `.class` byte fixtures in `shared/src/test/resources` for Phase 5; the JVM-only resource path is the chosen tradeoff. JS/Native tests for ClassfileUnpickler are deferred (no plan-mandated cross-platform test loads a `.class`). Acceptable per the plan.
- No `kyo-browser`/Chrome-style test dependency added.

## Naming

- Files match plan exactly:
  - `kyo/internal/reflect/classfile/ClassfileFormat.scala` ✓
  - `kyo/internal/reflect/classfile/ConstantPool.scala` ✓
  - `kyo/internal/reflect/classfile/JavaSignatures.scala` ✓
  - `kyo/internal/reflect/classfile/ClassfileUnpickler.scala` ✓
  - `shared/src/test/scala/kyo/ClassfileReaderTest.scala` ✓
  - `shared/src/test/scala/kyo/JavaSignaturesTest.scala` ✓
- Internal package: `package kyo.internal.reflect.classfile` everywhere. Compliant with `feedback_kyo_package`.

## Steering deviation (off-plan file modifications)

`git diff --name-only 79bea87b1~1 79bea87b1` reports 15 files changed. Plan-allowed file set was: 4 new sources + 2 new tests = 6. Beyond that:

- Edited (NOT in plan's "Files to modify: none"):
  - `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala` — added `javaMetadata` ctor param to `Symbol` + extended `Symbol.make`. STEERING directive 3 mandated this; plan text was inconsistent (plan §"Public API modifications: none" but plan tests 11/12 require `javaSpecific` to be Present, which structurally requires this change). Justified, but technically a plan deviation. Should be noted in PHASE-6-PREP if not already.
  - `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/symbol/Symbol.scala` — added `javaMetadata` param to `makeSymbol`. Mandated by STEERING directive 3.
  - `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/binary/ByteView.scala` — added `copyBytes`. Mandated by STEERING directive 1 option (a).
  - `kyo-reflect/STEERING.md`, `kyo-reflect/PROGRESS.md`, `kyo-reflect/PHASE-4-AUDIT.md`, `kyo-reflect/PHASE-5-INFLIGHT-REVIEW-1.md`, `kyo-reflect/PHASE-5-INFLIGHT-REVIEW-2.md`, `kyo-reflect/PHASE-6-PREP.md` — process artefacts.

- **Default parameter violation**: `Symbol.make(..., javaMetadata: Maybe[JavaMetadata] = Absent)` and `SymbolFactory.makeSymbol(..., javaMetadata: Maybe[JavaMetadata] = Absent)` both add a default-`Absent` parameter to an *internal* API. This violates `feedback_no_default_params_internal` ("never add `= default` to internal/private APIs; update every caller explicitly"). All existing call sites in Phases 1-4 quietly inherit `Absent` via the default; the change is non-auditable. WARN, queue for cleanup.

## Anti-flakiness

- No timing-dependent tests, no `Thread.sleep`, no `Async`/`Fiber` race scaffolding in any Phase 5 test.
- `ClassfileReaderTest` deterministic: same JDK 25 classpath bytes every run.
- `JavaSignaturesTest` deterministic: pure string input.
- `Interner` is constructed fresh per test class (private `val interner = new Interner(32)` / `Interner(8)`); state not shared across test files.

## Findings categorization

### BLOCKER
None.

### WARN
1. **Test 4 weakened**: `ClassfileReaderTest.scala:112-117` does not assert anything about typeParams. The implementation also does not parse the class-level Signature attribute into typeParams: `classAttrs.signatureIdx` is decoded but never passed to `JavaSignatures.parseClassSignature` inside `buildResult`. To make Test 4 strict, `buildResult` would need to call `JavaSignatures.parseClassSignature` and surface the resulting `Chunk[Reflect.Symbol]` onto `ClassfileResult` (or onto `classSymbol.typeParams`). Currently the surface for "class typeParams" does not exist in `ClassfileResult` at all. This is the only remaining structural gap from the plan's test list. Track for Phase 6 or a Phase 5 follow-up.
2. **Default params added to internal `Symbol.make` / `SymbolFactory.makeSymbol`** with `javaMetadata = Absent`. Violates `feedback_no_default_params_internal`. Cleanup: remove the default; pass `Absent` explicitly at every existing TASTy call site, `Present(metadata)` at classfile call sites.
3. **`throw new IllegalStateException`** in `ConstantPool.scala:160` (unexpected ByteView variant) and `:233` (unknown CP tag). Both should be `Abort.fail(ReflectError.ClassfileFormatError(path, "..."))` to keep the effect boundary intact cross-platform. Reachable on corrupt/unknown classfile input; throw bypasses the Kyo error channel.
4. **Dead-comment / dead-val** at `ClassfileUnpickler.scala:626`: `val sym = JavaSignatures.nothingSym // placeholder: real sym from binaryName` — the `sym` val is never used (the next line constructs `exSym` separately). Misleading; remove.
5. **Record-component attributes silently skipped** at `ClassfileUnpickler.scala:430-440` (comment: "For simplicity: skip all component attributes inline"). `recordComponents` in the constructed `JavaMetadata` is always `Chunk.empty`. Plan §"populates JavaMetadata" + STEERING fix 3 expected real values. Not blocking (no plan test asserts on record components), but is a scope cut flagged in pulse 1 minor #4 and not resolved.

### NOTE
1. **`null` as `owner`** in five sites — sentinel pattern inherited from Phases 1-4, accepted by prior audits. Phase 7's resolver must replace these with real owners. Documented in pulse 1 and pulse 2.
2. **`classfile/JavaSignatures.classStub`** unconditionally creates a fresh stub per call (no interning); two parses of `Ljava/util/List;` produce two distinct Symbol identities. Phase 7's classpath-level intern is expected to canonicalize. Currently irrelevant to all test assertions because tests compare `sym.name.asString` not identity.
3. **`unresolvedType` and `makeUnresolvedSymbol`** in `ClassfileUnpickler` each create fresh `ClasspathRef` for stubs via `new ClasspathRef` rather than passing the `home` argument through (see `resolveThrowsList` line 633: `new ClasspathRef` instead of the `home` already in scope). Minor consistency issue.
4. **Constant `Sync.defer` wrapping** is present for every classfile-read step. This matches `feedback_cio_lift_defer` ("`defer` for plain values, `deferLift` for effect producers") — the body of each `Sync.defer` is a side-effecting cursor advance, so `Sync.defer` is the right choice. No usage of the forbidden `CIO.lift`.
5. **`memberName.getBytes(...)` called twice** at `ClassfileUnpickler.scala:594-597` — minor allocation duplication, not a correctness issue. Could be hoisted to one local.
6. **STEERING.md** still carries the "Phase 5 fixes (BLOCKING before commit)" section as if not yet applied. All four directives now PRESENT; section should be marked cleared in PHASE-6-PREP. Process artefact, not a code issue.

## Recommendation: PROCEED

19/20 plan-mandated tests are PRESENT_STRICT; the lone weakening (test 4 typeParams) is symmetric with a real structural gap (`ClassfileResult` does not surface class-level typeParams, and the Signature attribute is decoded but unused inside `buildResult`). All four STEERING blockers from pulse 1/2 are complied: `asInstanceOf` removed via `ByteView.copyBytes`, parents wired, `javaSpecific` populated, `throwsTypes` populated. Zero new `asInstanceOf`/`AllowUnsafe`/`Frame.internal`/`Sync.Unsafe.defer`. Five WARNs (test 4 strictness, default params on internal API, two `throw new IllegalStateException` cross-effect escapes, dead val, record components skipped) and six NOTEs. None are individually blocking for Phase 6, and they cluster naturally as a Phase 5 cleanup pass (already implied by WARN #1 needing class Signature parsing, which Phase 6 may already plan).

Proceed to Phase 6 with WARN list filed as a tracked cleanup queue.
