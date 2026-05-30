# Phase 04b Decisions

## D1: Plan approach taken: widen ByteView trait abstract methods to Long

Decision: widened all five abstract cursor methods in `ByteView` (trait) to Long: `peekByte(at: Long)`, `readEnd(): Long`, `subView(from: Long, until: Long)`, `goto(addr: Long)`, `remaining: Long`, `position: Long`. Added five final Int-narrowing wrappers (`positionInt`, `readEndInt`, `remainingInt`, `gotoInt`, `subViewInt`) on the trait for migration sites.

Rationale: matches plan AFTER block exactly. The Int wrappers contain the cascade at TastyOrigin sites without widening the public `Tasty.Symbol.TastyOrigin` API.

## D2: Native MappedByteView touched: YES

Decision: updated `kyo-tasty/native/src/main/scala/kyo/internal/tasty/binary/MappedByteView.scala` to match the JVM widening. All six methods widened to Long signatures. Native-specific: `ptr(at)` takes Long directly (Scala Native pointer indexing accepts Long), so no `.toInt` cast needed for `peekByte`. No overflow guard on Native `readByte` because there is no byte-buffer Int limit; the guard is JVM-specific.

Rationale: the ByteView trait is shared/. Widening the trait's abstract methods forces all concrete implementations to override with Long signatures. Skipping Native would break Native compile per `feedback_all_platforms_all_tests`.

## D3: AstUnpickler cascade: positionInt/Math.toIntExact at TastyOrigin sites

Decision: all seven `new Tasty.Symbol.TastyOrigin(bodyStart, bodyEnd, ...)` call sites in `AstUnpickler` use `Math.toIntExact(...)` or `view.positionInt` to convert Long position/end values back to Int for the `bodyStart: Int, bodyEnd: Int` constructor params. `TastyOrigin` itself is NOT widened; its Int fields index into `sectionBytes: Array[Byte]` which is always Int-bounded.

Decision: `nodeAddr = view.positionInt` (not Long) because `addrMap: mutable.HashMap[Int, Tasty.Symbol]` uses Int keys. TASTy AST addresses are section-relative byte offsets into `Array[Byte]`, always < 2GB.

Decision: `sectionOffset = view.positionInt` for the same reason: fed into `DecodeCtx.sectionOffset: Int` and ultimately used as an Array index.

Decision: `sectionEnd = view.position + view.remaining` (Long arithmetic) because it is passed to `walkStats(end: Long)` which was widened.

## D4: Reader cascade: all end: Int method params widened to end: Long

Decision: widened the `end: Int` parameter to `end: Long` in all private methods across `AstUnpickler`, `TypeUnpickler`, `TreeUnpickler` that receive values from `view.readEnd()` or `view.position`. Specifically:
- AstUnpickler: `walkStats`, `decodeOneTypeIfPresent`, `readDefDefReturnType`, `decodeTemplateParents`, `scanForwardAndCollectFlags`, `readModifiers`
- TypeUnpickler: `readTypeLambdaParams`, `readMethodParams`, `readTypesUntil`, `skipToEnd`; `readTypeNode` uses `positionInt` for Int-keyed addrCache
- TreeUnpickler: `readTreesUntil`, `readCaseDefs`, `readCaseDefGuardAndBody`, `readUnapplyParts`, `readDefDefParamsAndTpt`, `readOneParamClause`, `readTypesUntil`, `readTypeOrSkip`, `readOptionalRhs`, `readTemplateBody`, `skipModifierTags`; `readTree` uses `positionInt` for Int-keyed treeAddrCache

Decision: `decodeSymBody(end: Int)` keeps Int because it receives `origin.bodyEnd: Int` from TastyOrigin (which remains Int). Auto-widening to Long at each callee site.

Decision: `Tasty.Tree.Unknown(tag, end - startAddr)` uses `Math.toIntExact(end - startAddr)` since `Unknown.length: Int` and section-relative deltas are always Int-bounded.

## D5: Other cascade sites

- `SectionIndex.readSync`: `val offset = view.positionInt` for the `Map[String, (Int, Int)]` entry.
- `ConstantPool`: `val off = view.positionInt` for `h.copyBytes(off, ...)` which takes Int.
- `ClassfileUnpickler.captureBytes`: `val start = h.positionInt` for `h.copyBytes(start, ...)`.
- `AttributeUnpickler`: `view.positionInt` for `UnknownTagException(tag, pos: Int)`.
- `TypeUnpickler.ANNOTATEDtype`: `termStart = view.positionInt` and `endInt = Math.toIntExact(end)` for `java.util.Arrays.copyOfRange(sectionBytes, termStart, endInt)`.

## D6: Test design

Two tests in new `MappedByteViewTest.scala`:
1. `goto(3_000_000_000L)` then assert `view.position == 3_000_000_000L`: pins Long return type of position.
2. `goto(Int.MaxValue + 1L)` then assert `readByte()` throws `IllegalStateException` containing "mmap segment overflow": pins the overflow guard.

Both tests create a 1-byte mmap temp file and use logical cursor positioning via `goto`, avoiding any need to create a multi-gigabyte file. This is the most direct way to exercise the bounds check without building a 2GB+ fixture.

## Verification

- `kyo-tasty / Test / compile` PASS (no errors)
- `kyo-tasty / Test / test` PASS (328 tests, 2 new MappedByteViewTest tests)
- `kyo-tastyJS / Test / compile` PASS
- `kyo-tastyNative / Test / compile` PASS
- HEAD: d1cd18e12 (unchanged)
