# Phase 16 Decisions: CLASSconst real type decoding (INV-013, M10)

## decodeCtx parameter shape chosen

`fromTastyTag` signature changed from `names: Array[Tasty.Name]` to `session: TypeUnpickler.DecodeSession`.

`TypeUnpickler.DecodeSession` is `private[kyo]` and accessible from `kyo.internal.tasty.symbol.Constant` (same `kyo` package scope). It carries `names`, `liveAddrMap`, `arena`, `home`, and mutable decode caches -- everything `readTypeIntoSession` needs.

`Frame` is threaded explicitly: the outer `fromTastyTag(using frame: Frame)` captures `frame` and passes it as a value parameter to `decodeConstant(tag, view, session, frame)`, which calls `TypeUnpickler.readTypeIntoSession(view, session)(using frame)`.

## callsite migration count

0 external callsites. `Constant.fromTastyTag` had no callers outside of `Constant.scala` itself. The CLASSconst decode path in production code runs through `TreeUnpickler.decodeTreeTag` (line 241) and `TypeUnpickler.decodeTag` (line 385), both of which already called `readTypeForTree` / `readTypeNode` directly. `fromTastyTag` was dead code; this phase corrects its implementation so it is no longer incorrect.

## fixture path for Tests 1 and 2

No TASTy fixture with `classOf[String]` existed. Fallback strategy: hand-built byte arrays using the dotty TASTy Nat encoding (same approach as `TypeUnpicklerTest`). The bytes encode a `CLASSconst (92)` category-3 node wrapping either a `TYPEREFdirect (63)` (Test 19, known symbol in addrMap) or a `TYPEREFpkg (65)` (Test 20, unresolved name). Both tests exercise the live `TypeUnpickler.readType` path and require no disk access.

Test 21 (source text) calls `TestResourceLoader.readText("kyo/internal/tasty/symbol/Constant.scala")`. To make this run on all platforms, `Constant.scala` was added to:
- `build.sbt` `kyoTastyEmbeddedTextGenerator` `filesToEmbed` (JS/Native)
- `build.sbt` `Test / resourceGenerators` `filesToCopy` (JVM)

## jvmOnly tags

None. Tests 19, 20, and 21 all run on JVM, JS, and Native. Tests 19 and 20 use hand-built bytes (no JAR loading). Test 21 uses the embedded text path added to `build.sbt`.

## sentinel removal

The `classConstSentinel` val (previously lines 20-30 of `Constant.scala`) and the `skipTree`/`skipTreeBody` helper methods (previously lines 93-115) were both removed. The CLASSconst branch now calls `TypeUnpickler.readTypeIntoSession(view, session)(using frame)` directly.

## files modified

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/symbol/Constant.scala`
- `kyo-tasty/shared/src/test/scala/kyo/UnifiedModelTest.scala`
- `build.sbt` (EmbeddedText + resource copy lists)
