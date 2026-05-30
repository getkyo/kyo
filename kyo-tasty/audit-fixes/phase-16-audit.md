# Phase 16 Audit

## Summary

Phase 16 correctly replaces the `classConstSentinel` placeholder with live type decoding for CLASSconst nodes. The core decode path is sound: `readTypeIntoSession` receives the enclosing session (not a fresh one), the cursor advances exactly right because CLASSconst is category 3 (tag + AST, no length prefix), and `readTypeNode` consumes the inner tag plus its payload atomically. Sentinel removal is complete in the source tree. The `fromTastyTag` function is dead code in production (zero callers outside Constant.scala itself), so the signature change carries no migration risk. Test byte arrays match the actual encoding. The build.sbt embed is consistent with prior entries. One NOTE-level concern about allocations per CLASSconst occurrence; no BLOCKERs.

## Findings

### 1. Type sub-AST decode correctness - OK

CLASSconst is category 3 (tag 90-109, no length prefix). The layout is: outer tag byte (92) already consumed by the caller, then the inner type node starting at the current cursor. `readTypeIntoSession` calls `readTypeNode`, which reads the inner tag byte then its payload atomically. No double-read and no skipped bytes. The `session` passed in is the file-level `DecodeSession` created by `AstUnpickler`; it carries `names`, `liveAddrMap`, `arena`, `home`, and all mutable caches. This is the same session used for every other type decode in the same pass, so address bindings are consistent. The TASTy spec has no length prefix for category-3 nodes, and the code does not attempt to read one; alignment is correct.

### 2. Sentinel removal completeness - OK

`grep -rn 'classConstSentinel|skipTree\b'` over `kyo-tasty/shared/src` and `kyo-tasty/jvm/src` returns: (a) the three test lines in `UnifiedModelTest.scala` that reference the string literal `"classConstSentinel"` as the searched text (expected), and (b) `skipTree` occurrences exclusively in `AstUnpickler.scala`, where a separate `skipTree` function has always lived and is unrelated to the removed helper. No residue of the old `Constant.skipTree` or `Constant.skipTreeBody` remains. The sentinel val is gone.

### 3. fromTastyTag signature migration - OK

The decisions doc claims zero external callsites and the claim is verified. The only occurrences of `fromTastyTag` in the production source tree are the definition and `end fromTastyTag` in `Constant.scala` itself. Production CLASSconst decoding goes through `TypeUnpickler.decodeTag` at line 385 (the `readTypeNode` path), which handles CLASSconst independently of `Constant.fromTastyTag`. The function is dead in production; the signature change is safe.

### 4. Unresolved-class FQN correctness (Test 20) - OK

Test 20 uses `TYPEREFpkg` (tag 65), which is category 2 (tag + Nat). The encoded bytes are `cat2(65, 0)` = `[65, 0x80]` (Nat 0 with stop bit), wrapped by `cat3(92, ...)` = `[92, 65, 0x80]`. `readTypeNode` reads tag 92 (CLASSconst), calls `readTypeNode` recursively, reads tag 65 (TYPEREFpkg), reads Nat 0, looks up `names(0)` = `Tasty.Name("com.missing.X")`, calls `nameAt(...).asString` = `"com.missing.X"`, creates an unresolved symbol with that FQN, and returns `Named(unresolvedSym)`. The test then asserts `s.kind == Unresolved` and `s.name.asString == "com.missing.X"`. The derivation chain is direct and correct; the test does not fudge the expected value.

### 5. Cross-platform Constant.scala embed - OK

The `build.sbt` diff adds `"constant" -> (path, IO.read(...))` to `kyoTastyEmbeddedTextGenerator`'s `filesToEmbed` sequence (for JS/Native) and the matching `srcBase/.../Constant.scala -> "kyo/internal/tasty/symbol/Constant.scala"` entry to the `Test / resourceGenerators` `filesToCopy` sequence (for JVM). Both entries follow the same pattern as the existing `onceCell` entry from Phase 03a-debt. The JS/Native managed `EmbeddedText.scala` files in the target directories already contain the embedded `constant` field, confirming the generator ran. Test 21 uses `TestResourceLoader.readText`, which resolves to the embedded text on JS/Native and the copied resource file on JVM.

### 6. Code quality - OK

No em-dashes, no semicolons, no `asInstanceOf`, no raw `Option`/`Some`/`None` usage, and no default-parameter additions in the diff. The `AllowUnsafe.embrace.danger` import is retained with its existing justification comment. Code quality is clean.

### 7. Performance - NOTE

CLASSconst now performs a full `readTypeNode` call per occurrence (allocates `DecodeCtx`, runs the recursive descent, may touch `addrMap`). The old path allocated a shared sentinel object once plus a new `ClassConst(Named(sentinel))` wrapper per occurrence. The new path allocates a `DecodeCtx` per call site in addition to the `ClassConst` wrapper. For a class with many `classOf[X]` literals this is a small regression per constant. The session-shared `addrCache` and `arena` bound repeat work, so the actual allocation is bounded by the number of distinct class references. Not a BLOCKER; worth noting for future profiling.

## Recommendations

- NOTE (item 7): if CLASSconst appears frequently in hot decode paths, consider caching the decoded `Tasty.Type` for a given inner-type address in `DecodeSession.addrCache`, consistent with how `SHAREDtype` is handled. No action required now.
