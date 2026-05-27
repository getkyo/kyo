# Phase 1 Audit: kyo-reflect cold-load perf

Commit audited: `4184b62f6` ("kyo-reflect Phase 1: single-pass JAR enum via direct CEN reader"). Sources read at that revision; current dirty tree (Phase 2) ignored.

Plan contract: `kyo-reflect/execution-plan-perf.md`, Phase 1 section.

## Test count

JarCentralDirectoryTest (JVM, `kyo-reflect/jvm/src/test/scala/kyo/JarCentralDirectoryTest.scala`):

- T1 empty JAR: PRESENT_STRICT (line 70).
- T2 only `.tasty`: PRESENT_STRICT (line 86).
- T3 only `.class`: PRESENT_STRICT (line 118).
- T4 mixed: PRESENT_STRICT (line 146).
- T5 large >500 entries: PRESENT_STRICT (line 181, 600 jars * 2 suffixes, spot-checks 5 names).
- T6 non-JAR file: PRESENT_WEAKENED (line 213). Accepts any `Result.Failure(_)`; plan asks for `Abort[ReflectError]`. The failure case is bare `case Result.Failure(e) => succeed` with no kind check.
- T11 corrupted EOCD: PRESENT_STRICT (line 230). Mangles signature to `0xdeadbeef`, asserts `Result.Failure(ReflectError.MalformedSection(_, _))`.
- T12 data-descriptor bit 3: PRESENT_WEAKENED (line 271). Plan says "general-purpose-bit-3 set on an entry (data descriptor present)". Implementation triggers DEFLATED via `setMethod(ZipEntry.DEFLATED)`. ZipOutputStream does NOT set bit-3 for entries with known sizes; it only sets bit-3 when local-file-header sizes are unknown at write time. The test comment acknowledges this ("bit-3 is not set by default"), so T12 effectively only proves DEFLATED entries enumerate, not that bit-3 is handled. The plan allows pass-or-abort, but the construction does not exercise the actual hazard.
- T13 EOCD-only empty JAR: PRESENT_STRICT (line 308).
- T14 UTF-8 bit 11: PRESENT_STRICT (line 326). Uses `ZipOutputStream(fos, UTF_8)` which sets bit 11.

FileSourceTest (shared, `kyo-reflect/shared/src/test/scala/kyo/FileSourceTest.scala`):

- F1 multi-suffix merged (plan label T7): PRESENT_STRICT (line 65).
- F2 empty suffix chunk (plan label T8): PRESENT_STRICT (line 95).
- F3 single-suffix parity (plan label T9): PRESENT_STRICT (line 110).
- F4 deterministic ordering (plan label T10): PRESENT_WEAKENED (line 138). Proves order matches across two calls on an in-memory source whose `list` already does `.sorted`. Pass is trivial. No determinism test for `JvmFileSource.list` or `JarCentralDirectory` over a real JAR.

Total: 10 + 4 = 14. Numbers match plan; T6, T12, F4 carry weakening notes.

## CONTRIBUTING.md violations

1. `JarCentralDirectory.scala:65` uses `var raf: RandomAccessFile = null` with try/finally, plus pervasive `var` + `while` + `throw new ReflectErrorWrapper` for control flow (line 89, 99, 137, 162, 177, 190 throw; `ReflectErrorWrapper` is a checked-exception sentinel caught at line 70-71). CONTRIBUTING (Scala Conventions): "no mutable `var`s, no `while` loops, no `throw`/`catch` for control flow"; allowance: "performance-critical internals where it's encapsulated behind a pure interface". The public surface is a single `def list` returning `Sync & Abort[ReflectError]`, which qualifies. The `throw`/`catch` for control flow is the weakest adherence: a `Loop`/`Result` encoding would be the canonical Kyo pattern. WARN.

2. `JarCentralDirectoryTest.scala` lives at `kyo-reflect/jvm/src/test/scala/kyo/JarCentralDirectoryTest.scala` but the plan specified `kyo-reflect/jvm/src/test/scala/kyo/internal/reflect/query/JarCentralDirectoryTest.scala`. CONTRIBUTING (Framework): test files "mirror the main source structure". Main source is at `kyo/internal/reflect/query/`. WARN (CONTRIBUTING + steering deviation).

3. `JarCentralDirectoryTest.scala` `case Result.Panic(t) => throw t` repeated at lines 74, 80, 105, 142, 172, 205, 220, 263, 297, 319, 367 (11 sites). General Kyo test norm: surface panic via `fail(s"unexpected panic: $t")`; raw `throw` discards diagnostic context and is brittle under Async runners. WARN.

4. `JarCentralDirectory.scala:179` early `return (totalEntries, cenOffset)` from within `if`. Conventions discourage early `return`. NOTE.

5. `ClasspathOrchestrator.scala` `FileResult` (line 289) still uses `immutable.Map` for the three map fields. Plan defers the switch to `mutable.HashMap` to Phase 4. NOTE.

## Unsafe markers

Grep across Phase 1 files (`JarCentralDirectory.scala`, `JvmFileSource.scala`, `FileSource.scala`, `ClasspathOrchestrator.scala`, `NativeFileSource.scala`, `JsFileSource.scala`) for `asInstanceOf`, `Frame.internal`, `AllowUnsafe`, `Sync.Unsafe.defer`:

- `JarCentralDirectory.scala`: zero hits. Clean.
- `FileSource.scala`: zero hits. Clean.
- `JvmFileSource.scala`: zero hits in the Phase 1 diff.
- `ClasspathOrchestrator.scala:317-319`: `// Unsafe: replaceSlot.set uses AllowUnsafe (covered by the import below).` + `import AllowUnsafe.embrace.danger` + `cp.stateRef.unsafe.get()`. PRE-EXISTING placeholder-resolution block, not introduced by Phase 1. NOTE.
- `JsFileSource.scala`: 19 `asInstanceOf` hits. Pre-existing pattern for `js.Dynamic` interop. Phase 1 added `listNodeSyncMulti` with 6 new hits mirroring the existing `listNodeSync`. JS interop is the established codebase exception (no typed alternative for `js.Dynamic`). NOTE.
- `NativeFileSource.scala`: zero new unsafe hits; existing `Zone` + `extern` are FFI-required.

No `Frame.internal`, no `Sync.Unsafe.defer` introduced. No new `AllowUnsafe`.

## Cross-platform consistency

`FileSource.list(dir, suffixes: Chunk[String])` added as abstract on the trait (`FileSource.scala:50`). Single-suffix `list(dir, suffix)` becomes a delegating default (`FileSource.scala:42`).

Multi-suffix implementations:
- `JvmFileSource.scala:62` — present, routes JAR roots through `JarCentralDirectory.list`.
- `NativeFileSource.scala:46` — present, single-walk `listDirNativeMulti`.
- `JsFileSource.scala:80` — present, single-walk `listNodeSyncMulti`.
- `MultiSuffixMemorySource` in `FileSourceTest.scala:41` — present.
- Test fixtures in `QueryApiTest.scala`, `SnapshotRoundTripTest.scala` (two inner fixtures), `SnapshotRoundTripJvmTest.scala`, `SymbolResolutionTest.scala`, `TreeUnpicklerTest.scala` — all updated. Verified in `git show 4184b62f6`.

All 4 prod sources + all 5 test fixtures implement the multi-suffix variant. Consistent.

`JarCentralDirectory` is JVM-only. Grep across `shared/`, `native/`, `js/` returns zero references. Consistent.

## Naming convention compliance

- `JarCentralDirectory`: `private[kyo] object` under `kyo.internal.reflect.query`. Matches kyo convention (internals in `kyo.internal`). The name describes the artifact (the ZIP central directory), which is a value-singleton. OK.
- `JarCentralDirectoryTest`: `class JarCentralDirectoryTest extends Test`. Extends the module's `Test` base. OK.
- File organization in `JarCentralDirectory.scala`: scaladoc, constants, then the single public `def list` at line 53, then private helpers (`listEntries`, `findEocd`, `readCenLocation`, `parseCenRecords`, byte-helpers). Public-API-first, internals-last. OK.

One naming nit: `ReflectErrorWrapper` is a control-flow exception. The Kyo norm prefers a sealed return-type union, but the local idiom (mapping `java.io.IOException` to `Abort.fail`) is consistent with `JvmFileSource`. NOTE.

## Steering deviation

`git diff --name-only 4184b62f6^..4184b62f6`:

- `kyo-reflect/js/src/main/scala/kyo/internal/reflect/query/JsFileSource.scala` (expected MODIFY)
- `kyo-reflect/jvm/src/main/scala/kyo/internal/reflect/query/JarCentralDirectory.scala` (expected PRODUCE)
- `kyo-reflect/jvm/src/main/scala/kyo/internal/reflect/query/JvmFileSource.scala` (expected MODIFY)
- `kyo-reflect/jvm/src/test/scala/kyo/JarCentralDirectoryTest.scala` (expected PRODUCE; path-deviated from plan, see CONTRIBUTING #2)
- `kyo-reflect/jvm/src/test/scala/kyo/SnapshotRoundTripJvmTest.scala` (expected compile-ripple)
- `kyo-reflect/native/src/main/scala/kyo/internal/reflect/query/NativeFileSource.scala` (expected MODIFY)
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/ClasspathOrchestrator.scala` (expected MODIFY)
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/FileSource.scala` (expected MODIFY)
- `kyo-reflect/shared/src/test/scala/kyo/FileSourceTest.scala` (expected PRODUCE)
- `kyo-reflect/shared/src/test/scala/kyo/QueryApiTest.scala` (expected compile-ripple)
- `kyo-reflect/shared/src/test/scala/kyo/SnapshotRoundTripTest.scala` (expected compile-ripple)
- `kyo-reflect/shared/src/test/scala/kyo/SymbolResolutionTest.scala` (expected compile-ripple)
- `kyo-reflect/shared/src/test/scala/kyo/TreeUnpicklerTest.scala` (expected compile-ripple)

No files outside the expected list. Path-deviation: `JarCentralDirectoryTest.scala` placed at `kyo/JarCentralDirectoryTest.scala` instead of plan-specified `kyo/internal/reflect/query/JarCentralDirectoryTest.scala`. WARN.

## Anti-flakiness measures

- T11 (corrupted EOCD): builds JAR, then in-place byte mutation. No timing dependency. Asserts `MalformedSection(_, _)` strictly. Safe.
- T12: no timing; DEFLATED writes. Flakiness risk is conceptual (test does not exercise bit-3 as the plan intended), not time-based.
- T13: empty `ZipOutputStream`, deterministic.
- T14: `ZipOutputStream(_, UTF_8)`, deterministic. No mtime/locale coupling.
- F4: two calls on an in-memory source whose `list` sorts. The pre-sort makes F4 trivially pass and hides any nondeterminism in `JvmFileSource.list` / `JarCentralDirectory` over a real JAR. WARN: F4 is structurally weak. Recommend a follow-up test that calls `JarCentralDirectory.list` twice on a real JAR and asserts sequence equality.

No `Thread.sleep`, no clock/system-property mutation. I/O on temp-fs only.

---

## Findings index

- WARN: T6 panic-throw + lax failure match (test rigor).
- WARN: T12 does not exercise data-descriptor bit 3; ZipOutputStream's DEFLATED with known sizes does not set bit 3. Plan intent unmet.
- WARN: F4 in-memory fixture pre-sorts, trivializing the assertion. Add a `JarCentralDirectory`-level determinism test on a real JAR.
- WARN: `JarCentralDirectoryTest.scala` placed at `jvm/.../kyo/` instead of `jvm/.../kyo/internal/reflect/query/`. Mirrors-source rule.
- WARN: 11 sites in `JarCentralDirectoryTest` rethrow panics with `throw t` instead of `fail(...)`.
- WARN: `JarCentralDirectory` uses `throw`/`catch` (`ReflectErrorWrapper`) as control flow. Performance allowance applies, but it is the lowest-confidence convention adherence in the diff.
- NOTE: `var` + `while` + early `return` in `JarCentralDirectory`, accepted under "performance-critical internals".
- NOTE: `ClasspathOrchestrator.scala:317-319` `AllowUnsafe.embrace.danger` is pre-existing.
- NOTE: `JsFileSource.scala` added 6 new `asInstanceOf` hits in `listNodeSyncMulti`, mirroring the established `js.Dynamic` interop pattern.
- NOTE: `FileResult` map fields still `immutable.Map` (Phase 4 will switch).

## Categorization

No BLOCKERS. SLOT-A for Phase 3 may proceed. All findings are WARN or NOTE.

Suggested WARN cleanup queue (does not gate Phase 3):
1. Rewrite T12 to actually set bit 3 (requires a raw ZIP writer; `ZipOutputStream` with known sizes will not), or rename it to reflect DEFLATED-only coverage and add a separate T12b that exercises real bit-3 entries.
2. Replace `case Result.Panic(t) => throw t` with `fail(...)` across `JarCentralDirectoryTest`.
3. Move `JarCentralDirectoryTest.scala` to `kyo-reflect/jvm/src/test/scala/kyo/internal/reflect/query/`.
4. Add a real-JAR determinism test for `JarCentralDirectory.list` (strengthens F4).
5. Tighten T6: assert a specific `ReflectError` kind instead of any failure.
