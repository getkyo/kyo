# Phase 1 In-Flight Review (pulse 1) — cold-load perf plan

Pulse 1: 2026-05-26T00:00:00Z
Files reviewed:
- `kyo-reflect/execution-plan-perf.md` Phase 1 section (lines 41-99)
- `kyo-reflect/jvm/src/main/scala/kyo/internal/reflect/query/JarCentralDirectory.scala` (NEW, 289 lines)
- `kyo-reflect/jvm/src/test/scala/kyo/JarCentralDirectoryTest.scala` (NEW, 376 lines)
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/FileSource.scala` (MODIFIED, 71 lines)
- `kyo-reflect/jvm/src/main/scala/kyo/internal/reflect/query/JvmFileSource.scala` (MODIFIED, diff)
- `kyo-reflect/native/src/main/scala/kyo/internal/reflect/query/NativeFileSource.scala` (MODIFIED, lines 148-188)
- `kyo-reflect/js/src/main/scala/kyo/internal/reflect/query/JsFileSource.scala` (MODIFIED, diff)
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/ClasspathOrchestrator.scala` (MODIFIED, diff)
- `kyo-reflect/shared/src/test/scala/kyo/QueryApiTest.scala` (MODIFIED, diff)
- `kyo-reflect/shared/src/test/scala/kyo/SnapshotRoundTripTest.scala` (MODIFIED, diff)
- `kyo-reflect/shared/src/test/scala/kyo/SymbolResolutionTest.scala` (MODIFIED, diff)
- `kyo-reflect/shared/src/test/scala/kyo/TreeUnpicklerTest.scala` (MODIFIED, diff)
- `kyo-reflect/jvm/src/test/scala/kyo/SnapshotRoundTripJvmTest.scala` (MODIFIED, diff)

## Plan anchor

- ### Files to produce: 2 expected | 1 of 2 new files present as named by the plan
  - `JarCentralDirectory.scala`: PRESENT at correct path `kyo-reflect/jvm/src/main/scala/kyo/internal/reflect/query/JarCentralDirectory.scala`
  - `JarCentralDirectoryTest.scala`: PRESENT but at a different path from the plan (see Other observations — flat `kyo/` convention is acceptable)
  - `FileSourceTest.scala`: ABSENT — the plan mandates "4 new in `FileSourceTest.scala`"; that file does not exist anywhere in the tree

- ### Files to modify: 5 expected | all 5 present and modified, matching the plan
  - `FileSource.scala`: single-suffix variant converted to delegate; new abstract multi-suffix method added
  - `JvmFileSource.scala`: `JarFile` import removed; JAR path delegates to `JarCentralDirectory.list`; `listJarEntries` deleted; `listJrtPath` renamed `listJrtPathMulti`
  - `NativeFileSource.scala`: new `listDirNativeMulti` added; `list` override updated
  - `JsFileSource.scala`: new `listNodeSyncMulti` added; `list` override updated
  - `ClasspathOrchestrator.scala`: `collectAllEntries` added; two sequential calls collapsed to one

- ### Tests: 14 expected in JarCentralDirectoryTest + 4 in FileSourceTest = 18 leaves | 14 present, 4 MISSING
  - T1-T6 and T11-T14 in `JarCentralDirectoryTest.scala`: all 14 present
  - T7/F1-T10/F4 (plan "4 new in FileSourceTest.scala"): ENTIRELY ABSENT — `FileSourceTest.scala` was never created

- ### Public API additions: `FileSource.list(dir: String, suffixes: Chunk[String])` | PRESENT at `FileSource.scala` line 52

## Reward-hacking checks

| Pattern | Verdict | Citation |
|---|---|---|
| Verification commands actually run | NOT VERIFIABLE — no test output artifacts present in tree | n/a |
| Compile-only "success" claim | CANNOT RULE OUT — `FileSourceTest.scala` absent means 4 plan-mandated tests never ran; verification command would fail | FileSourceTest.scala absent |
| Priority inference (item skipped) | SUSPECT — F1-F4 are the only tests that exercise the `FileSource` multi-suffix API via shared in-memory fixture (cross-platform coverage); silently omitting them leaves the shared contract untested | Plan lines 86-90 |
| Scope substitution (simpler equivalent shipped) | PARTIAL — `collectAllEntries` is correct but dead methods `collectTastyFiles` and `collectModuleInfoFiles` were not removed (see CRITICAL C2) | ClasspathOrchestrator.scala diff |
| Foreach-discards-assert in tests | CLEAN — all assertions are evaluated inside `Result.Success(...)` match arms | JarCentralDirectoryTest.scala throughout |
| Stale-state passing / tautological coverage | CLEAN for JarCentralDirectoryTest; UNKNOWN for FileSource contract (file missing) | — |

## Drifting checks

| Pattern | Verdict | Citation |
|---|---|---|
| Public API signature matches plan (`FileSource.list` multi-suffix) | MATCHES — `def list(dir: String, suffixes: Chunk[String])(using Frame): Chunk[String] < (Sync & Abort[ReflectError])` | FileSource.scala line 52 |
| No off-plan architecture substitution | CLEAN — JarCentralDirectory uses `RandomAccessFile` to read CEN bytes directly; no `JarFile.entries()`, no `ZipFile.entries()`, no per-entry wrapper objects allocated | JarCentralDirectory.scala lines 64-111 |
| No cross-cutting refactor outside phase | CLEAN — 5 existing test file changes are pure compile-fix API ripple, no behavioral changes (verified below) | All 5 test diffs |
| Internal helpers stay `private[kyo]` | MATCHES — `private[kyo] object JarCentralDirectory` | JarCentralDirectory.scala line 25 |

## Existing test file changes — clean or critical?

Every one of the 5 modified test files contains an in-process `MemoryFileSource` / anonymous `FileSource` that formerly overrode the single-suffix `def list(dir, suffix: String)`. The API change to `def list(dir, suffixes: Chunk[String])` makes the old signature a non-override; the compile fix in each file is the same mechanical substitution:

```
- def list(dir: String, suffix: String)(...) =
-     Chunk.from(files.keys.filter(k => k.startsWith(dir + "/") && k.endsWith(suffix)).toSeq)
+ def list(dir: String, suffixes: Chunk[String])(...) =
+     Chunk.from(files.keys.filter(k => k.startsWith(dir + "/") && suffixes.exists(k.endsWith)).toSeq)
```

Semantics are preserved: `suffixes.exists(k.endsWith)` on a single-element `Chunk` is equivalent to the old `k.endsWith(suffix)`. No assertion was weakened, no test was deleted, no matcher was loosened. In `QueryApiTest.scala` an anonymous inner `FileSource` (lines 463-469 in the diff) also receives the same mechanical signature update.

Verdict: **CLEAN** for all 5 existing test files.

## Scope-cutting checks (per plan-mandated test leaf)

| Leaf | Status | Notes |
|---|---|---|
| T1 (empty JAR) | PRESENT\_STRICT | Lines 66-79; asserts `entries.isEmpty` with message |
| T2 (.tasty only) | PRESENT\_STRICT | Lines 83-117; asserts count==3, all end `.tasty`, all jarPath fields match; cross-checks `.class` returns empty |
| T3 (.class only) | PRESENT\_STRICT | Lines 120-149; count==2; cross-checks `.tasty` returns empty |
| T4 (mixed) | PRESENT\_STRICT | Lines 152-185; count==4, all entries end `.tasty` or `.class`, no `.java` entries |
| T5 (large >500 entries) | PRESENT\_STRICT | Lines 188-217; 1200-entry JAR (600 pairs), count==1200 asserted, 5 named spot-checks |
| T6 (non-JAR file) | PRESENT\_WEAK | Lines 220-233; asserts any `Result.Failure(e)` — does not pin to `ReflectError.MalformedSection`; plan said "Abort[ReflectError] raised, not unchecked exception" which is met but weaker than T11's typed assertion. Minor. |
| T11 (corrupted EOCD) | PRESENT\_STRICT | Lines 236-273; asserts `Result.Failure(ReflectError.MalformedSection(_, _))` specifically |
| T12 (data descriptor bit 3) | PRESENT\_WEAK | Lines 276-318; exercises DEFLATED entries (not raw bit-3 data descriptor). Both `Result.Success` and `Result.Failure(ReflectError.MalformedSection)` accepted; catch-all `Result.Failure(e) => succeed` on line 313 accepts any ReflectError. Intentional per plan spec ("if implementation does not support data descriptors, asserts Abort[ReflectError]") but the catch-all arm is loose. |
| T13 (empty JAR with only EOCD) | PRESENT\_STRICT | Lines 321-335; asserts `entries.isEmpty`. NOTE: T1 and T13 use identical JAR construction (`writeJar(path, Seq.empty)`); T1 tests multi-suffix, T13 tests zero-entry EOCD semantics. Same underlying bytes — minor duplication. |
| T14 (UTF-8 entry names bit 11) | PRESENT\_STRICT | Lines 338-374; asserts all three names (two non-ASCII + one ASCII) present in result set |
| F1 (list multi-suffix merged results) | MISSING | FileSourceTest.scala not created |
| F2 (list empty suffix chunk) | MISSING | FileSourceTest.scala not created |
| F3 (list single-suffix matches old behavior) | MISSING | FileSourceTest.scala not created |
| F4 (list ordering deterministic) | MISSING | FileSourceTest.scala not created |

## Other observations

### Test file path: plan vs actual
Plan specified `kyo-reflect/jvm/src/test/scala/kyo/internal/reflect/query/JarCentralDirectoryTest.scala`. Actual path: `kyo-reflect/jvm/src/test/scala/kyo/JarCentralDirectoryTest.scala`.

Verified by `ls`: all existing JVM test files (`ModuleInfoJvmTest.scala`, `SnapshotRoundTripJvmTest.scala`) are at the flat `kyo/` level, as are all shared test files (30+ files). **Acceptable** — the flat `kyo/` convention is the codebase norm. The plan's subdirectory path was aspirational.

### Dead methods not removed from ClasspathOrchestrator
The agent added `collectAllEntries` and updated the call site. Both `collectTastyFiles` and `collectModuleInfoFiles` private methods remain in the file as dead code. These are unreachable after the refactor and will mislead the Phase 3 agent, which modifies the same file.

### NativeFileSource: double-stat per entry in listDirNativeMulti
`listDirNativeMulti` calls `stat()` twice per entry: once to check `S_IFREG`, and if not a regular file, again to check `S_IFDIR`. This is a faithful copy of the same pattern from the pre-existing `listDirNative`. Not a Phase 1 regression but worth noting.

### collectAllEntries: out-of-scope single-file-root fallback
The new `collectAllEntries` includes a fallback for when `source.list` returns empty: it checks `source.exists(root)` and handles the case where `root` itself is a `.tasty` or `module-info.class` file path. This logic was not called for by the plan and has no test coverage. It replicates implicit behavior from the old `collectTastyFiles`. Functionally likely correct but untested.

### JvmFileSource list return format: path composition
The plan specifies: "store raw `(jarPath, entryName)` pairs and build the composite path only once at the point where file content is read (not at enumeration time)". In the current implementation, `JvmFileSource.list` calls `JarCentralDirectory.list` (which returns `Chunk[(jarPath, entryName)]`) and immediately maps over it to compose `s"$jarPath!/$entryName"` strings:

```scala
JarCentralDirectory.list(dir, suffixes).map: pairs =>
    pairs.map((jarPath, entryName) => s"$jarPath!/$entryName")
```

This composition happens at enumeration time (in `list`), not deferred to the point of reading file content. The plan said to defer composition to the read site. However, the `FileSource.list` return type is `Chunk[String]` (not `Chunk[(String,String)]`), so the composed string is the only option the shared API can return. The plan's deferral goal would require changing the `FileSource.list` return type or adding a separate `listPairs` method — neither of which the plan specifies as a `FileSource` API change. This is an acceptable implementation choice given the constraint; the per-entry string allocation still exists but is bounded to enumeration (not reads), which is an improvement over the old `JarFile.entries()` path. **Not a blocking issue.**

## CRITICAL (steer immediately)

**C1 — `FileSourceTest.scala` entirely absent: F1-F4 (T7-T10) tests never written.**
The plan mandates 4 shared tests that validate `FileSource.list(dir, Chunk[String])` via an in-memory fixture, running on all three platforms. They are absent. The plan's verification command (`sbt 'kyo-reflectJVM/testOnly *FileSourceTest *JarCentralDirectoryTest'`) will fail to find `FileSourceTest`.

Fix: create `kyo-reflect/shared/src/test/scala/kyo/FileSourceTest.scala` with tests F1-F4:
- F1: `list(dir, Chunk(".tasty", ".class"))` on an in-memory root returns entries matching either suffix
- F2: `list(dir, Chunk.empty)` returns `Chunk.empty` without touching the filesystem
- F3: `list(dir, Chunk(".tasty"))` returns the same result as `list(dir, ".tasty")`
- F4: two calls on the same root return chunks with equal element sets (deterministic)

Use the same `MemoryFileSource` pattern from `QueryApiTest.scala` (lines 35-57 of that file's in-memory implementation).

**C2 — Dead private methods `collectTastyFiles` and `collectModuleInfoFiles` not removed from ClasspathOrchestrator.**
Both methods are now unreachable. They sit alongside `collectAllEntries` and `runPhaseAB`. Phase 3 modifies `ClasspathOrchestrator.scala` heavily; leaving dead methods in place risks the Phase 3 agent re-wiring them by mistake. Remove both before committing Phase 1.

## MINOR (queue for post-commit audit)

**M1 — T1 and T13 use identical JAR construction.**
Both `writeJar(path, Seq.empty)` produce byte-for-byte identical files. T1 covers the multi-suffix path; T13 covers the zero-entry EOCD semantics. Both pass independently but test the same underlying bytes. If T13 was intended to test a hand-crafted minimal EOCD (e.g., one where `totalEntries` field is explicitly zero while entries are non-zero in the central directory), it should be strengthened in a post-commit pass.

**M2 — T6 assertion looseness.**
T6 accepts any `Result.Failure(e: ReflectError)` for a non-JAR file, not specifically `MalformedSection`. Tighten in a post-commit pass to match T11's precision.

**M3 — NativeFileSource double-stat is a pre-existing pattern, not a regression.**
The `listDirNativeMulti` double-stat (once for `S_IFREG`, once for `S_IFDIR`) is copied from `listDirNative`. A future filesystem optimization could combine both checks in a single `stat` call. Not Phase 1 scope.

**M4 — `collectAllEntries` single-file-root fallback is untested.**
The fallback that handles `root` being a direct `.tasty` or `module-info.class` file path has no test. Add a scenario in `FileSourceTest.scala` when F1-F4 are written.

## Recommendation: STEER — fix C1 (create FileSourceTest.scala) and C2 (remove dead methods) before commit.

JarCentralDirectory implementation is solid and all 14 JarCentralDirectory tests are present and strict. The 5 API-ripple changes in existing test files are CLEAN. The blocking issues are: (1) `FileSourceTest.scala` was never created, leaving the cross-platform `FileSource` multi-suffix contract untested, and (2) dead methods `collectTastyFiles`/`collectModuleInfoFiles` remain and will confuse Phase 3.
