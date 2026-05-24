# Phase 5 In-Flight Review (pulse 2)

Pulse 2: 2026-05-24T00:10Z
Files reviewed:
- `kyo-reflect/STEERING.md` lines 72-91 (Phase 5 fixes BLOCKING section)
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/classfile/ConstantPool.scala` lines 125-165
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/binary/ByteView.scala` lines 1-94
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/classfile/ClassfileUnpickler.scala` lines 80-160, 430-600
- `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala` lines 200-300
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/symbol/Symbol.scala` lines 1-28
- `kyo-reflect/shared/src/test/scala/kyo/ClassfileReaderTest.scala` lines 1-183

---

## STEERING compliance

| # | Directive | Verdict | Citation |
|---|---|---|---|
| 1 | `asInstanceOf` at `ConstantPool.scala:140` removed (via `copyBytes` or eager copy) | COMPLIED | `ConstantPool.scala` line 158-161: now uses `case h: ByteView.Heap => h.copyBytes(off, off + len)`. Pattern-match replaces cast. `ByteView.Heap.copyBytes(from, until)` added at `ByteView.scala` lines 79-84. No `asInstanceOf` remains in classfile sources (global grep confirms). |
| 2 | super/interfaces wired into Symbol parents; test 3 strict | NOT_COMPLIED | `ClassfileUnpickler.scala` lines 133-140: `superIdx` is read but passed nowhere; interfaces loop reads and discards each index (`discard(readU2(view))`). `readClassBody` does NOT pass `superIdx` or any interface indices to `buildResult`. `buildResult` (lines 433-477) constructs `classSym` with no parents information. `ClassfileResult` has no `parents` field. `Reflect.Symbol.parents` at line 223 remains a stub returning `NotImplemented`. Test 3 (lines 60-67) still checks only `JavaDefined` flag and `kind==Class` — an exact tautological copy of test 1, not a parents assertion. |
| 3 | `javaSpecific` populated on each Symbol; test 11 strict | NOT_COMPLIED | `buildOneMemberSymbol` (lines 530-553): constructs `val metadata = Reflect.JavaMetadata(...)` on line 532 but then calls `SymbolFactory.makeSymbol(kind, memberFlags, name, owner, home, Reflect.Symbol.JavaOrigin)` on line 545 WITHOUT passing `metadata`. The `metadata` val is created then immediately discarded (never used). `Reflect.Symbol.make` signature (line 280-288) accepts no `javaMetadata` parameter; `Symbol.make` passes through to `new Symbol(..., origin)` which defaults `javaMetadata = Absent`. `SymbolFactory.makeSymbol` (Symbol.scala lines 17-25) also accepts no `javaMetadata` param. The `classSym` for the class itself (lines 462-469) also passes no `javaMetadata`. Test 11 (lines 156-165) checks `sym.origin match case Reflect.Symbol.JavaOrigin` only, not `sym.javaSpecific.isDefined`. The TASTy-symbol half ("Absent for TASTy-sourced symbol") is still absent. |
| 4 | `throwsTypes` wired into `JavaMetadata`; test 12 strict | NOT_COMPLIED (structural) | `resolveThrowsTypes` builds `Chunk[Reflect.Type]` correctly (lines 555-582). `JavaMetadata` is constructed with `throwsTypes = throwsTypes` (line 533). BUT `metadata` is never passed into the symbol (see directive 3 above), so `throwsTypes` are computed then discarded with `metadata`. Test 12 (lines 170-180) admits this explicitly in a comment: "JavaMetadata is stored in ClassfileResult, not yet wired to Symbol.javaSpecific in Phase 5". The test asserts only `methodSyms.nonEmpty` and `result.symbols.nonEmpty` — a pure non-assertion. |

---

## New drift since pulse 1

1. **New pattern-match cast on `ByteView.Heap`** (ConstantPool.scala line 158-160): `case h: ByteView.Heap => h.copyBytes(...)` followed by `case _ => throw new IllegalStateException(...)`. The cast is gone, which is progress. However, the `case _` branch throws a JVM `IllegalStateException` (not a `ReflectError`). For cross-platform correctness it should be `Abort.fail(ReflectError.ClassfileFormatError(path, "Unexpected ByteView variant"))`. Minor, but cross-platform throw escapes the Kyo effect layer.

2. **`metadata` dangling computation**: `val metadata = Reflect.JavaMetadata(...)` on line 532 is constructed inside a monadic `.map` block but never bound into `sym`. In Kyo's no-dangling-computation rule, this is not a dangling Kyo computation (it's a pure val construction), so it doesn't violate `feedback_dangling_computations.md`. However, it is dead code that the compiler may warn about, and it creates a misleading reading that javaMetadata is wired.

3. **No new `asInstanceOf` introduced** in classfile or binary packages outside the `Memo.scala` / `SingleAssign.scala` AtomicRef bridging pattern that pre-dates Phase 5. Clean.

4. **No new weakening of previously-strict tests** observed. Tests 1, 2, 5-10, 13-20 remain strict.

5. **`SymbolFactory.makeSymbol` / `Reflect.Symbol.make` signatures unchanged**: Neither accepts a `javaMetadata` parameter. Wiring `javaMetadata` into the symbol requires extending both signatures. This was the root blocker for directives 3 and 4 and was not addressed.

6. **`superIdx` still flows nowhere**: read at line 96, bound in the tuple `(accessFlags, thisIdx, superIdx)`, then `readClassBody` (line 123) does not receive or use `superIdx` at all. The `readBody` function at lines 85-109 reads `superIdx` but passes only `accessFlags` and `thisBinaryName` to `readClassBody`. `superIdx` is silently dropped in the tuple destructuring pattern.

---

## CRITICAL (steer immediately)

1. **Directives 2, 3, 4 not complied** — three of four STEERING fixes are structurally absent:
   - Parents wiring: `superIdx` and interface indices read but discarded. `ClassfileResult` has no `parents` field. `Symbol.parents` remains a stub. Test 3 still tautological.
   - `javaSpecific` wiring: `metadata` computed then thrown away. `SymbolFactory.makeSymbol` / `Reflect.Symbol.make` accept no `javaMetadata` param. `javaSpecific` will always return `Absent`. Test 11 does not test `javaSpecific.isDefined`.
   - `throwsTypes` wiring: dependent on directive 3 (same root cause: `metadata` discarded). Test 12 is a non-assertion.

2. **Three changes needed before commit**:
   a. Extend `Reflect.Symbol.make` and `SymbolFactory.makeSymbol` with `javaMetadata: Maybe[JavaMetadata] = Absent` param; pass `Present(metadata)` in `buildOneMemberSymbol` and `Present(classMetadata)` in `buildResult` (class-level metadata with `accessFlags + recordComponents`).
   b. Add `parentTypes: Chunk[Reflect.Type]` to `ClassfileResult`; in `readClassBody` pass `superIdx` + interface indices to `buildResult`; build `Reflect.Type.Named(unresolvedStub)` entries from `pool.classRef`; wire into the Symbol or ClassfileResult so test 3 can assert on them.
   c. Rewrite tests 3, 11, 12 to be strict: test 3 asserts `result.parentTypes.exists(t => ...)` with name including "Object"; test 11 asserts `sym.javaSpecific.isDefined == true` AND loads a TASTy symbol to assert `javaSpecific.isDefined == false`; test 12 asserts a method's `sym.javaSpecific.map(_.throwsTypes).getOrElse(Chunk.empty).nonEmpty`.

3. **`IllegalStateException` cross-platform escape**: `case _ => throw new IllegalStateException(...)` in ConstantPool.scala line 160 should be `Abort.fail(ReflectError.ClassfileFormatError(path, "..."))`.

---

## MINOR (queue for post-commit audit)

1. `Memo.scala` and `SingleAssign.scala` use `asInstanceOf` for `AtomicReference[AnyRef]` bridging — pre-Phase-5 pattern, not introduced by this agent. Note for eventual audit.

2. `null` passed as `owner` in `makeUnresolvedSymbol`, `buildResult` (classSym), and `resolveThrowsList` — pre-existing Phase 5 pattern, acceptable Phase 5 sentinel pending Phase 7 resolution. Flagged in pulse 1; unchanged.

3. Record-component attributes still skipped without decoding (pulse 1 minor #4) — unchanged.

4. No `.class` fixture pre-compiled into `shared/src/test/resources/` — unchanged from pulse 1 minor #3.

---

## Recommendation: STEER: wire javaMetadata param into Symbol.make + SymbolFactory.makeSymbol, wire parentTypes into ClassfileResult from superIdx + interface indices, rewrite tests 3/11/12 to strict assertions before commit
