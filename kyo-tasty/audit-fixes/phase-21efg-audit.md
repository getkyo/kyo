# Phase 21e + 21f + 21g Combined Audit

SHAs:
- Phase 21e: e9ed8dc61
- Phase 21f: 0a851f0e0
- Phase 21g: e019773e0

## Summary

PASS with two NOTEs. No blockers. The cycle-guard is defensible given the design intent,
the local-parent round-trip test is substantive, DataFormatException ordering is correct,
the SymbolKind count is verified, the OnceCell null test exercises the right code path,
and the makeNamed dedup is accurate with one pre-existing residual. Code quality is clean.

## Findings

### 1. Phase 21e cycle-guard semantics - OK

`resolveExtFqn` returns `""` on a back-edge. The `filter(_.nonEmpty)` at both call sites
in `decodeExtRef` and `decodeExtModClassRef` converts `""` to `Absent`, so the cycle
contributes nothing to the owner FQN and the symbol receives whatever partial name was
assembled before the back-edge. This is not silent corruption: the resulting symbol carries
`SymbolKind.Unresolved`, which is the correct kind for any externally-referenced pickle
entry. Downstream consumers that receive an `Unresolved` symbol already expect incomplete
resolution. Returning `""` rather than throwing is consistent with the general best-effort
pickle decode posture (malformed pickle entries are silently skipped throughout
`buildResult`). The decisions log documents the visited-set choice over a depth counter
and traces the exact resolution steps for the self-loop fixture (`"Foo.Foo"`). The FQN is
structurally unusual but bounded and attributed to Unresolved, which satisfies the audit
invariant that corrupt input does not produce a plausibly-valid resolved symbol.

### 2. Phase 21f parents round-trip substance - OK

Test P1 (lines 565-656) is substantive. It builds a synthetic two-symbol classpath
(`test.Bar`, `test.Foo`) using `Tasty.Symbol.make` and `InternalClasspath.transitionToReady`,
sets `fooSym._parents = Chunk(Tasty.Type.Named(barSym))` directly, writes a snapshot via
`SnapshotWriter.write`, reads it back via `SnapshotReader.read`, then asserts both that
`test.Foo.parents` is non-empty and that the recovered `Named` type's `fullName` equals
`"test.Bar"`. This exercises the full `SnapshotWriter` symbolId-map path (local symbol
assigned an integer ID, written to the PARENTS section) and the `SnapshotReader`
restoration path. The decisions log explains why a TASTy fixture could not be used (TASTy
`Unknown`-type parents are filtered by the writer). The Phase 19b audit WARN is closed.

### 3. Phase 21f DataFormatException - NOTE

The Native `InflateHook` now has three catch arms: `DataFormatException` first, then
`ZipException`, then `IOException`. Ordering is correct: `DataFormatException` is caught
before the broader `IOException`. Both `DataFormatException` and `ZipException` map to
`MalformedSection`; `IOException` maps to `CorruptedFile`. The decisions log confirms
`DataFormatException` availability in scala-native javalib and states the ordering intent.

However, no test exercises the `DataFormatException` path specifically on Native. The
shared `InflateHookTest` tests a bad CMF byte (which triggers a `ZipException` or
`MalformedSection` path depending on platform) and a valid empty stream. Neither test
injects a payload that would cause the decompressor to throw `DataFormatException`
specifically (e.g. a well-formed ZLIB header followed by corrupted DEFLATE data). The
Phase 20f corruption test uses a broad `_: TastyError` match so it does not distinguish
which error subtype the Native path produces. The new arm is production-correct but has
no targeted test coverage. This is a NOTE rather than a WARN because the missing path is
a platform-specific catch arm, not a logic change.

### 4. Phase 21g SymbolKind enum count - OK

`SymbolKindTest` asserts `values.length == 14` and enumerates all 14 cases explicitly.
A second test asserts the four plan-draft phantom names (Module, ParamVal, ParamType,
Constructor) are absent. The decisions log documents the plan-vs-reality reconciliation.
Both tests are structural guards that will fail at compile or runtime if the enum changes.

### 5. Phase 21g OnceCell null test - OK

Test 7 uses `OnceCell[String | Null](() => null)`. Internally `OnceCell` stores values as
`AnyRef` and uses a distinct `Unset` sentinel object to represent the uninitialized state.
Because `null` and `Unset` are distinct `AnyRef` references, `null ne OnceCell.Unset` is
true, so the null return from the init lambda is stored and subsequently returned as-is.
The union type at the call site does not change this; no cast is inserted by the compiler.
The test verifies `first == null` and `second == null` (idempotence with a null value).
This exercises exactly the same `AnyRef` storage and CAS path as any non-null value, which
is the correct code path. The decisions log confirms the no-cast rationale.

### 6. Phase 21g makeNamed dedup completeness - NOTE

The dedup covered the two verbatim duplicates: `TastyAnnotationTest` and `TastyTypeTest`
both now `extend TastyTestSupport` and call the inherited `makeNamed`. This is correct and
complete for the stated scope.

A third variant, `makeNamedSym`, exists in `TypeOpsTest` with a slightly different
structure (it returns the final `Tasty.Symbol` rather than `Tasty.Type.Named`). The
decisions log acknowledges this and explicitly states `TypeOpsTest` was not changed because
the method has a different name and is not a verbatim duplicate. That judgment is sound.

`TastySymbolTest` does not use `makeNamed` at all -- it constructs symbols inline using
`Tasty.Symbol.make` directly -- so no dedup opportunity was missed there. The dedup scope
is complete as stated.

### 7. Code quality across all three phases - OK

- Em-dashes: none found in any of the three phase diffs.
- Semicolons: none introduced.
- `asInstanceOf`: `OnceCellTest` references `asInstanceOf` only in comments and in Test 1
  which is a whitebox structural guard on the production `OnceCell.scala` source (checking
  that all casts are preceded by `// Unsafe:` comments). No new unchecked casts in test
  or production code.
- `Option/Some/None`: used in `SnapshotRoundTripTest.MemoryFileSource` as the adapter
  return type for `getBytes` and in `find` post-processing, matching the pre-existing
  pattern in `QueryApiTest`. Consistent with established test infrastructure.
- `return`: none introduced.
- Default parameters: none introduced.
- `Either`: none introduced.

## Recommendations

- Phase 21f DataFormatException (NOTE 3): add a targeted `InflateHookTest` case that
  passes a valid ZLIB header followed by corrupted DEFLATE payload bytes (e.g.
  `Array(0x78, 0x9c, 0xff, 0xff, ...)`) and asserts the result is a `TastyError`
  (or `MalformedSection` specifically on Native). This would close the coverage gap
  without requiring platform-specific test splitting.
- Phase 21g makeNamed residual (NOTE 6): no action required. `TypeOpsTest.makeNamedSym`
  is structurally different and the decisions log records the deliberate non-merge.
