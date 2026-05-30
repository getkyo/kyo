# Phase 17 Audit

## Summary

Phase 17 cleanly removes `TastyError.NotImplemented` from the two `Annotation.args` branches and routes both non-decode-context and empty-pickle cases to `Sync.defer(Tree.Unknown(-1, 0))`. The error shape rewrite from `Either` to a direct `val result: Tree < Abort[TastyError]` type annotation inside `Sync.defer` is safe and consistent with how Kyo effect covariance works. The 11 remaining `NotImplemented` sites are all legitimate for distinct reasons. The `byteOffset = 0L` gap is a known debt that should be addressed in the next phase. Two of the three new tests are meaningful; the third (Test B) is structurally valid but exercises only a trivial early-exit guard.

## Findings

### 1. NotImplemented residual - OK

All 11 sites are legitimate and fall into three distinct categories, none of which is a lazy-decode hatch like `args` was.

- `Tasty.scala:732` -- `Symbol.body` for `JavaOrigin`: a deliberate capability boundary (Java classfiles carry no TASTy body).
- `Tasty.scala:741` -- `Symbol.body` for symbols without a body slice (`bodyStart == 0`, Package, etc.): a structural boundary, not a deferral.
- `Tasty.scala:1239` -- the private `stub` helper, called from `Tasty.scala:737` when `home` is unassigned (pre-orchestration symbol state). Legitimate guard.
- `SnapshotReader.scala:576,579` -- `parseErrorString` fallback for unrecognized serialized error strings: genuinely not restorable, `NotImplemented` is the correct fallback.
- `InflateHook` (shared declaration + JS + Native implementations): platform capability gap (ZLIB not available on JS/Native); this is permanently not-implemented on those targets.

None of the 11 sites are lazy-decode paths that should be converted to `Tree.Unknown` or similar. No phase routing is needed.

### 2. DecodeException.byteOffset gap - WARN

`DecodeException` is defined as `final class DecodeException(msg: String)` with no `byteOffset` field. Both catch branches in `Annotation.args` (and the mirrored branches in `Symbol.body`) pass `0L` to `MalformedSection.byteOffset`, defeating INV-006. Adding a `val byteOffset: Long = 0L` field and populating it at the three or four throw sites inside `TreeUnpickler` is low effort (one field + a handful of call-site changes). Route to Phase 18a; the `phase-18a-baseline.txt` file already exists, suggesting this is anticipated.

### 3. Synthetic fixture for Test A (UNITconst) - OK

The substitute covers the critical path. `UNITconst` (tag value 2) is a category-1 single-byte tag decoded at `TreeUnpickler.scala:179` via `decodeTreeTag` called from `readTree`, which is invoked by `decodeAnnotationTerm`. The pickle `Chunk(TastyFormat.UNITconst.toByte)` is passed through the full `decodeAnnotationTerm` entry point -- constructing `ByteView`, `TypeArena`, `TypeUnpickler.TreeTypeSession`, and `DecodeCtx`, then calling `readTree` -- so the INV-014 path (non-null `_decodeCtx` + non-empty `argsPickle`) is fully exercised. The decoded result `Tree.Literal(UnitConst)` is verified by the test assertion. The substitute is not a strawman.

### 4. Test B substance - NOTE

Test B ("non-null DecodeContext + empty argsPickle returns Tree.Unknown") asserts the `argsPickle.isEmpty` guard at `Tasty.scala:212`. That guard is a two-line early exit that does not reach `decodeAnnotationTerm` at all. The `DecodeContext` construction is never used in the execution. The test is correct and pins the contract, but it has low behavioral coverage; it cannot catch a regression that mistakenly skips the guard and falls through to decode an empty `Chunk`. Acceptable as a contract pin test, but note that strengthening it (e.g. by asserting on a 1-byte pickle that the decode branch is entered) would improve confidence.

### 5. Tree.Unknown shape correctness - OK

`Tree.Unknown(tag: Int, length: Int)` at `Tasty.scala:536` is the ADT's explicit "unknown tag" case. Normal real-decode call sites pass positive tag values (e.g. `TastyFormat.SHAREDterm = 23`, `TastyFormat.IMPORT = ...`, category-5 tags). The sentinel value `-1` is not a valid TASTy tag (all TASTy tags are in the range 0-255 read as unsigned bytes, so the real range is 0 to 255; `-1` as a signed `Int` cannot be produced by `view.readByte() & 0xff`). There is no ambiguity. Downstream pattern matches on `Tree.Unknown` that check `tag` will not confuse `-1` with any real decoded tag. Shape is correct.

### 6. Try/catch inside Sync.defer correctness - OK

The shape is:
```scala
Sync.defer:
    val result: Tree < Abort[TastyError] =
        try <decodeAnnotationTerm(...)>
        catch
            case ex: DecodeException  => Abort.fail(TastyError.MalformedSection(...))
            case ex: AIOOBE           => Abort.fail(TastyError.MalformedSection(...))
    result
```

`Abort.fail` returns a `Nothing < Abort[TastyError]`, which is a subtype of `Tree < Abort[TastyError]` by covariance, so the type annotation is satisfied. The `result` value is returned from the `Sync.defer` block, which lifts the `Tree < Abort[TastyError]` effect into `Tree < (Sync & Abort[TastyError])`. This is the standard Kyo pattern for mixed synchronous/effectful results; the `val result: Tree < Abort[TastyError]` annotation is required to guide the type checker and is correct. The analogous `Symbol.body` path uses `Either` + `decoded match` (pre-Phase-17 scaffolding), but the Phase-17 shape is a valid simplification. No issue.

### 7. Code quality - OK

No em-dashes found in the changed lines. No semicolons, `asInstanceOf`, `Option`/`Some`/`None`, or default-param misuse introduced. The `private[kyo] val _decodeCtx` naming convention (leading underscore) is consistent with other write-once fields in the file. The only minor observation is that the `Symbol.body` path still uses `Either` scaffolding (Decision 1 in `phase-17-decisions.md` acknowledges this as out-of-scope). No action needed.

## Recommendations

- Route to Phase 18a: extend `TreeUnpickler.DecodeException` with a `byteOffset: Long` field and populate it at throw sites, then remove the `0L` placeholders in both `Annotation.args` and `Symbol.body`. This closes INV-006 fully.
- Optional, low priority: when a `@deprecated` TASTy fixture is available (Phase 18+ fixture expansion), replace Test A with a real annotation decode to strengthen end-to-end coverage.
- Optional: strengthen Test B by asserting that a 1-byte pickle with a real DecodeContext successfully enters the decode branch, confirming the isEmpty guard is not inadvertently bypassed.
