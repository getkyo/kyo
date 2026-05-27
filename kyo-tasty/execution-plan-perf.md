# kyo-reflect cold-load performance: execution plan

## Profile baseline (verified)

- Cold-load: 55 ms median, p95 68 ms (full classpath: 121 jars, 5,949 TASTy, 34,580 class files)
- Snapshot: 57 ms median (no speedup at full scale; see Finding below)
- Source: `kyo-reflect/COLD-LOAD-PROFILE-FULL.md` (profiled 2026-05-26, forked JVM, async-profiler 4.3)

Key findings from COLD-LOAD-PROFILE-FULL.md:

- `__psynch_mutexwait` is 29.4% of CPU samples: mutex waits from kyo scheduler plus `JarFile.entries()` synchronized access.
- `__open` is 6.4% of CPU samples: 121 jar opens per iteration.
- `JarFile$JarFileEntry` is 12.3% of allocation samples: per-entry object allocation during jar listing.
- `byte[]` from `ZipFile$Source.initCEN` is the dominant allocation path (two variants at 1.97% and 1.74% each): CEN buffer allocated per `JarFile` open.
- Path string building (`jarPath + "!/" + entryName`) is 2.20% of allocation samples across 35,809 entries.
- Snapshot slowness is structural: `DigestComputer.compute` calls `collectFiles` (line 106 of `DigestComputer.scala`) which calls `source.list(root, ".tasty")` per root, reopening every JAR a third time (PERF-VERIFICATION.md §3).
- Async.foreach already uses `min(size, concurrency)` worker fibers (PERF-VERIFICATION.md §13): no per-file fiber allocation, 8 fibers for 5,949 files at `concurrency = 8`.

## Phase dependency graph

```
Phase 1 (single-pass JAR enum)
  -> Phase 2 (digest by jar metadata)
  -> Phase 3 (streaming pipeline)
       -> Phase 4 (HAMT reduction)

Phase 5 (PositionsUnpickler boxing)   [independent]
Phase 6 (Interner pre-sizing)         [independent]

Phase 7 (sbt plugin)
  -> Phase 8 (re-profile)
       -> END
```

Phases 5 and 6 may be executed in any order relative to each other and relative to Phases 2-4, provided Phase 1 has committed first (Phase 6 depends on the entry count produced in Phase 1).

---

## Phases

### Phase 1: Single-pass JAR enumeration via direct CEN reader

**Depends on:** nothing (first phase)

**Rationale:** PERF-VERIFICATION.md §1 confirms `JvmFileSource.listJarEntries` (lines 145-156) opens a new `JarFile` per call via `new JarFile(jarPath)` at line 146, allocates one `JarFile$JarFileEntry` per entry via `jar.entries().asIterator()` at line 149, and builds a path string via `s"$jarPath!/${entry.getName}"` at line 151. PERF-VERIFICATION.md §1 also confirms that `ClasspathOrchestrator.scala` calls `source.list(root, ".tasty")` at line 90 and `source.list(root, "module-info.class")` at line 91 as two independent invocations, each opening the JAR separately. Combined with PERF-VERIFICATION.md §3 (DigestComputer's third JAR open addressed in Phase 2), this phase eliminates the two redundant opens in Phase A and removes per-entry `JarFile$JarFileEntry` allocation.

**Files to produce:**

`kyo-reflect/jvm/src/main/scala/kyo/internal/reflect/query/JarCentralDirectory.scala`
- Direct ZIP CEN reader. Opens the JAR via `java.util.zip.ZipFile` and iterates its central directory. Does NOT call `JarFile.entries()` or `ZipFile.entries()` (both allocate per-entry wrapper objects); instead reads the CEN bytes directly via `ZipFile.getInputStream` on a zero-byte read or by parsing the file's tail directly. Exposes a single method: `def list(jarPath: String, suffixes: Chunk[String])(using Frame): Chunk[(String, String)] < (Sync & Abort[ReflectError])` returning `(jarPath, entryName)` pairs for entries whose names end with any of the given suffixes. Does not allocate `JarFile$JarFileEntry`. Handles: Zip64 (extended end-of-central-directory locator), multi-disk jars (returns `Abort[ReflectError]` for multi-disk jars, which are unsupported by the JVM standard library too), deflated entries (entry names from CEN are always uncompressed names regardless of content encoding). Approximately 150 LOC.

`kyo-reflect/jvm/src/test/scala/kyo/internal/reflect/query/JarCentralDirectoryTest.scala`
- JVM-only test file (`taggedAs jvmOnly`).
- 14 tests:
  1. Empty JAR returns empty Chunk for any suffix list.
  2. JAR containing only `.tasty` entries: `list(path, Chunk(".tasty"))` returns all entries; `list(path, Chunk(".class"))` returns empty.
  3. JAR containing only `.class` entries: suffix list `Chunk(".class")` returns all; `Chunk(".tasty")` returns empty.
  4. JAR with mixed `.tasty`, `.class`, `.java` entries: multi-suffix `Chunk(".tasty", ".class")` returns only `.tasty` and `.class` entries, not `.java`.
  5. Large JAR with more than 500 entries: list returns all matching entries without missing any (verified by count and spot-checking 5 known entry names).
  6. Non-JAR file path: `Abort[ReflectError]` is raised, not an unchecked exception.
  7-10. (T7-T10 live in `FileSourceTest.scala` per the Tests section below.)
  11. T11: JAR with corrupted End-Of-Central-Directory signature (`0x06054b50` mangled to `0xdeadbeef`) returns `Abort[ReflectError.MalformedSection]`.
  12. T12: JAR with general-purpose-bit-3 set on an entry (data descriptor present) still enumerates correctly. If the implementation does not support data descriptors, the test asserts `Abort[ReflectError]` with a documented message.
  13. T13: Empty JAR (only EOCD record, zero entries) returns `Chunk.empty` without throwing.
  14. T14: JAR with general-purpose-bit-11 set (UTF-8 entry names containing non-ASCII chars like `münchen.tasty`) decodes entry names correctly.

**Files to modify:**

`kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/FileSource.scala`
- Add method to the `FileSource` trait: `def list(dir: String, suffixes: Chunk[String])(using Frame): Chunk[String] < (Sync & Abort[ReflectError])`.
- Keep existing single-suffix `list(dir: String, suffix: String)` as a delegate that calls `list(dir, Chunk(suffix))` and returns the result unchanged, so all existing callers compile without modification.

`kyo-reflect/jvm/src/main/scala/kyo/internal/reflect/query/JvmFileSource.scala`
- Rewrite `listJarEntries` (lines 145-156) to delegate to `JarCentralDirectory.list`. No `new JarFile(...)` at this call site. No `jar.entries().asIterator()`. No `s"$jarPath!/${entry.getName}"` string concatenation per entry; store raw `(jarPath, entryName)` pairs and build the composite path only once at the point where file content is read (not at enumeration time).
- Implement `list(dir: String, suffixes: Chunk[String])` override that dispatches to the new `JarCentralDirectory.list` for jar roots.

`kyo-reflect/native/src/main/scala/kyo/internal/reflect/query/NativeFileSource.scala`
- Add `list(dir: String, suffixes: Chunk[String])` override. PERF-VERIFICATION.md §2 confirms NativeFileSource has no JAR support; the multi-suffix implementation simply calls the existing `listDirNative` once and filters for any entry whose name ends with any suffix in the list.

`kyo-reflect/js/src/main/scala/kyo/internal/reflect/query/JsFileSource.scala`
- Add the `list(dir: String, suffixes: Chunk[String])` override following the same pattern as Native: single directory walk filtered by suffix list.

`kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/ClasspathOrchestrator.scala`
- Replace the two sequential calls at lines 90-91 (`collectTastyFiles` and `collectModuleInfoFiles`) with a single `collectAllEntries` call that uses `source.list(root, Chunk(".tasty", "module-info.class"))`. Partition the resulting `Chunk[String]` into two sub-chunks (`.tasty` entries and `module-info.class` entries) by suffix check before passing to `runPhaseAB`. The downstream consumers of `tastyFiles: Chunk[String]` and `moduleFiles: Chunk[String]` in `runPhaseAB` (lines 95-113) are unchanged.

**Tests:** 14 new in `JarCentralDirectoryTest.scala` (listed above, T1-T6 and T11-T14) + 4 new in `FileSourceTest.scala` (or the nearest existing shared FileSource test file):
- T7: multi-suffix `list(dir, Chunk(".tasty", ".class"))` on a directory root returns entries matching either suffix.
- T8: empty suffix list `list(dir, Chunk.empty)` returns empty Chunk.
- T9: single-element suffix list `list(dir, Chunk(".tasty"))` returns the same result as `list(dir, ".tasty")` (delegate behavior preserved).
- T10: result ordering is deterministic across two calls on the same root.

**Public API additions:** `FileSource.list(dir: String, suffixes: Chunk[String])`.
**Public API modifications:** none beyond the addition (single-suffix variant preserved as delegate).
**Public API removals:** none.

**Verification command:**
```
sbt 'kyo-reflectJVM/testOnly *FileSourceTest *JarCentralDirectoryTest' 2>&1 | tail -20
```

---

### Phase 2: Digest by jar metadata (eliminate the third JAR walk)

**Depends on:** Phase 1 (for `source.list` multi-suffix API and the Phase A single-walk result, which is plumbed to DigestComputer to avoid a redundant list call).

**Rationale:** PERF-VERIFICATION.md §3 confirms `DigestComputer.compute` (line 67) calls `collectFiles` (line 106), which calls `source.list(root, ".tasty")` per root, opening every JAR a third time per iteration (after the two passes in Phase A prior to Phase 1, reduced to one pass after Phase 1). With Phase 1 in place, Phase A already has the full entry list. This phase makes `DigestComputer` accept a pre-computed file list for jar roots, and for jar roots computes the digest from jar-file `stat()` data (mtime + size) instead of re-enumerating entries. `FileSource.FileStat` already carries `mtimeMs: Long` and `size: Long` per PERF-VERIFICATION.md §3. Any change to a jar entry changes the jar's mtime and size, making `stat()`-based invalidation correct for jar roots. Directory roots retain the existing per-file enumeration. Pre-existing `.krfl` snapshots become unreachable because the digest semantics change for jar roots; the next load triggers cold-load + re-snapshot. Cache invalidation is intentional and acceptable; users may manually delete obsolete `.krfl` files from `cacheDir` if disk usage is a concern.

**Files to produce:** none.

**Files to modify:**

`kyo-reflect/shared/src/main/scala/kyo/internal/reflect/snapshot/DigestComputer.scala`
- `compute` (line 67): for each root, branch on whether `root` ends with `.jar`. For jar roots, call `source.stat(root)` to obtain `(mtime, size)` and include `(root, mtime, size)` as the hash input for that root. For directory roots, retain the existing `collectStats` / `collectFiles` per-file enumeration.
- `computeParanoid` (line 82): same branching. For jar roots, open and hash each jar's raw bytes (already possible via `source.readBytes(root)`). For directory roots, retain per-file content hashing.
- `collectStats` (line 96) and `collectFiles` (line 106): refactor or remove the jar-root branch; the jar-root case is handled above. Keep the directory-root branch intact.

**Tests:** 5 new in `SnapshotRoundTripTest.scala`:
- T14: two successive `DigestComputer.compute` calls on the same jar-rooted classpath return identical hex digests.
- T15: two successive `DigestComputer.compute` calls on a directory-rooted classpath return identical hex digests.
- T16: a jar whose file mtime is bumped (via `Files.setLastModifiedTime` with a clearly-different value: current time + 1 hour) produces a different digest on the next `compute` call. Use `Files.setLastModifiedTime` with an explicit +1h offset so filesystem-resolution rounding cannot mask the change. Do not use `Thread.sleep`.
- T17: a jar whose file size changes (by copying a modified jar to the same path) produces a different digest. Use `Files.setLastModifiedTime` with a clearly-different value (current time + 1 hour) when also bumping mtime to prevent resolution-rounding flakiness. Do not use `Thread.sleep`.
- T18: a classpath with both a jar root and a directory root produces a consistent digest across two calls.

**Public API:** none changed.

**Verification command:**
```
sbt 'kyo-reflectJVM/testOnly *SnapshotRoundTripTest' 2>&1 | tail -20
```

---

### Phase 3: Streaming pipeline via Channels (overlap enumeration with decode and merge)

**Depends on:** Phase 1 (entry enumeration now produces entries via a single walk; Channel-based pipeline builds on the same per-root walk abstraction) and Phase 2 (digest no longer blocks on a separate enumeration pass, so the pipeline's first action can begin immediately).

**Rationale:** PERF-VERIFICATION.md §4 confirms `runPhaseAB` (lines 95-113) runs `Async.foreach(tastyFiles, concurrency)` at line 105 and then sequentially calls `readModuleInfoFiles` at line 110, then `mergeResults` at line 112 only after all Phase B fibers complete. Overlapping enumeration with decode and merge eliminates two sequential phase boundaries. PERF-VERIFICATION.md §8 confirms `Channel` is cross-platform (kyo-core is JVM+JS+Native, build.sbt line 424) and that `close()` (line 217) fails pending takes with `Closed` without draining buffered elements; therefore `streamUntilClosed` (line 298) must be used for the consumer side, not `close()`+`stream()`.

**Files to produce:** none.

**Files to modify:**

`kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/ClasspathOrchestrator.scala`
- Replace `runPhaseAB` (lines 95-113) with a three-stage pipeline:
  - Stage 1 (producer): one fiber per root via `Async.foreach(roots, rootCount): root => walkRoot(root, entryCh)`. Each fiber walks its single root and puts `(entryPath, rootPath)` pairs to a bounded `entryCh: Channel[(String, String)]` of capacity `decodeConcurrency * 4`. After `Async.foreach` returns, the supervisor coroutine calls `entryCh.close()`. The producer uses the Phase 1 single-pass `source.list(root, Chunk(".tasty"))` per root.
  - Stage 2 (decoders): `decodeConcurrency` fibers each call `entryCh.streamUntilClosed` (Channel.scala line 298), read and decode each `.tasty` file, and put the resulting `FileResult` to a bounded `resultCh: Channel[FileResult]` of capacity `decodeConcurrency * 2`. Each decoder fiber calls `resultCh.close()` only after draining `entryCh` to completion; coordinate via a `Fiber.gather` on all decoder fibers so `resultCh.close()` is called once after all decoder fibers complete.
  - Stage 3 (merger): one fiber calls `resultCh.streamUntilClosed` and merges each `FileResult` into the Classpath state via the existing per-record merge logic extracted from `mergeResults`. The merger fiber owns all Classpath state mutation; it is the sole writer.
  - All three stages are gathered via `Async.gather`. Phase C placeholder resolution (lines 322-378) runs once after the merger fiber completes, before `openInto` returns.
  - Channel capacities: entry channel = `decodeConcurrency * 4`; result channel = `decodeConcurrency * 2`. Both are bounded to prevent unbounded memory growth under skewed-jar inputs.
  - `moduleFiles` (module-info.class entries) continue to be collected via the Phase 1 single-pass multi-suffix walk; `readModuleInfoFiles` runs after the merger fiber completes and before Phase C placeholder resolution, unchanged.
- `mergeResults` (lines 273-419) is refactored to accept a single `FileResult` and merge it incrementally, rather than receiving a `Chunk[FileResult]`. The merger fiber calls this per-result variant in a loop. The six `mutable.ArrayBuffer` and two `mutable.HashMap` locals (lines 280-285) are lifted to fields on a `MergeState` inner class owned by the merger fiber.

**Tests:** 8 new in `ClasspathOrchestratorPipelineTest.scala` (new shared file):
- T1: pipeline-produced Classpath contains the same symbol set as the pre-pipeline implementation on the fixture classpath.
- T2: pipeline-produced FQN index matches the pre-pipeline FQN index key-for-key.
- T3: arena (Chunk buffers) merging is deterministic: two runs on the same inputs produce structurally equal Classpath values.
- T4: a soft-fail file error (unreadable `.tasty` in a directory root) appends to `Classpath.errors` and does not abort the pipeline.
- T5: a strict-fail file error raises `Abort[ReflectError]` and terminates the pipeline without hanging.
- T6: entry channel does not grow unboundedly under a skewed-jar input (one jar with 3,000 entries, `decodeConcurrency = 2`): peak `entryCh` queue depth never exceeds `(decodeConcurrency * 4) + rootCount` (capacity + rootCount producer-blocked items).
- T7: two pipeline runs on identical inputs produce Classpath values with the same FQN set (ordering-independence).
- T8: decoder concurrency respects the `concurrency` parameter: with `concurrency = 2` and 100 entries, exactly 2 decoder fibers are spawned.

**Public API:** none changed (`openInto` signature stable).

**Verification command:**
```
sbt 'kyo-reflectJVM/testOnly *QueryApiTest *ClasspathOrchestratorPipelineTest' 2>&1 | tail -20
```

---

### Phase 4: Defer toMap in AstUnpickler (HAMT reduction in Phase C)

**Depends on:** Phase 3 (the streaming merger receives `FileResult` values one at a time; with the Phase 3 incremental merge, the merger can consume `mutable.HashMap` fields directly without an intermediate immutable Map, making the deferral natural).

**Rationale:** PERF-VERIFICATION.md §11 confirms four `.toMap` conversions in `AstUnpickler.scala` at lines 153, 176, 177, 178 each produce a new `immutable.HashMap` with `BitmapIndexedMapNode` + `Tuple2` internals per file. Combined, `BitmapIndexedMapNode` is 1.07% of allocation samples and `Tuple2` is 2.95%, both from these four sites. The maps are consumed by `mergeResults` (now the per-result merger from Phase 3) under the single-threaded merger fiber. Keeping them as `mutable.HashMap` in `FileResult` and merging directly eliminates all four `.toMap` allocations.

**Files to produce:** none.

**Files to modify:**

`kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/AstUnpickler.scala`
- Remove the four `.toMap` conversions at lines 153, 176, 177, 178.
- Change `Pass1Result` (AstUnpickler.scala:58) field types:
  - `addrMap: Map[Int, Reflect.Symbol]` to `addrMap: mutable.HashMap[Int, Reflect.Symbol]`
  - `parentsBySymbol: Map[Reflect.Symbol, …]` to `parentsBySymbol: mutable.HashMap[Reflect.Symbol, …]`
  - `childrenByOwner: Map[Reflect.Symbol, Chunk[…]]` to `childrenByOwner: mutable.HashMap[Reflect.Symbol, Chunk[…]]`
  - `typeBySymbol: Map[Reflect.Symbol, …]` to `typeBySymbol: mutable.HashMap[Reflect.Symbol, …]`

`kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/ClasspathOrchestrator.scala`
- Change `FileResult` (ClasspathOrchestrator.scala:48-58) field types:
  - `parentsBySymbol: Map[…]` to `parentsBySymbol: mutable.HashMap[…]`
  - `childrenByOwner: Map[…]` to `childrenByOwner: mutable.HashMap[…]`
  - `typeBySymbol: Map[…]` to `typeBySymbol: mutable.HashMap[…]`
- `FileResult` does NOT carry `addrMap`. Do not add an `addrMap` field.
- Update the merger logic to read `mutable.HashMap` directly.

`kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/TastyOrigin.scala`
- Change `_addrMap: Map[Int, Reflect.Symbol]` to `_addrMap: mutable.HashMap[Int, Reflect.Symbol]`. Read access remains single-threaded after Phase C (existing guarantee).

The addrMap path is: `AstUnpickler` produces `Pass1Result.addrMap: mutable.HashMap` stored into `TastyOrigin._addrMap: mutable.HashMap`, consumed by `PositionsUnpickler` and lazy-body decode. `FileResult` does NOT participate in the addrMap path.

**Tests:** 3 new in `AstUnpicklerTest.scala`:
- T1: after `AstUnpickler.read(bytes)`, the returned `Pass1Result` holds `mutable.HashMap` instances, not `immutable.HashMap` instances, for the four map fields (`addrMap`, `parentsBySymbol`, `childrenByOwner`, `typeBySymbol`).
- T2: the merger accepts and correctly processes `FileResult` values whose map fields are `mutable.HashMap`.
- T3: symbol lookup via `TastyOrigin.addrMap` after Phase C returns the same symbol as before this change (behavioral equivalence).

**Public API:** `FileResult` is `private[kyo]`; `AstUnpickler` and `TastyOrigin` are under `kyo.internal`. No public API delta.

**Verification command:**
```
sbt 'kyo-reflectJVM/testOnly *AstUnpicklerTest *QueryApiTest' 2>&1 | tail -20
```

---

### Phase 5: PositionsUnpickler Integer boxing elimination

**Depends on:** Phase 1 (no structural dependency; can execute after Phase 1 commits). Phases 5 and 6 are independent of each other and of Phases 2-4.

**Rationale:** PERF-VERIFICATION.md §6 confirms the boxing source is `addrMap.get(curIndex: Int)` at `PositionsUnpickler.scala` line 107 where `addrMap: Map[Int, Reflect.Symbol]` is an `immutable.HashMap[Int, Reflect.Symbol]`. Each `get` call autoboxes the `Int` key to `java.lang.Integer`. The profile shows `java.lang.Integer` at 0.73% of allocation samples and `scala.runtime.BoxesRunTime.boxToInteger` at 0.32% of CPU samples. Changing `addrMap` to `scala.collection.immutable.IntMap[Reflect.Symbol]` eliminates all autoboxing on this path. `IntMap` uses primitive `Int` keys internally. `mutable.HashMap[Int, Reflect.Symbol]` is NOT the right choice here: Scala's `mutable.HashMap` also autoboxes `Int` keys (it uses `AnyRef`-typed buckets). `IntMap` is the correct non-boxing immutable structure.

Note: PERF-VERIFICATION.md §7 confirms `Interner.grow` (line 104) uses `new Array` + manual copy, not `Arrays.copyOf`. The `Arrays.copyOf` in the profile originates from `mutable.HashMap` internal resize, which is addressed indirectly by Phase 6 (Interner pre-sizing reduces resize events).

**Files to produce:** none.

**Files to modify:**

`kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/PositionsUnpickler.scala`
- Change `addrMap` parameter type at line 58 from `Map[Int, Reflect.Symbol]` to `scala.collection.immutable.IntMap[Reflect.Symbol]`.
- Line 107: `addrMap.get(curIndex)` now operates on `IntMap`, which uses primitive Int lookup without autoboxing.

`kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/AstUnpickler.scala`
- Change the construction of the addrMap passed to `PositionsUnpickler` (at or after line 153) from `mutable.HashMap[Int, Reflect.Symbol].toMap` (Phase 4 left it as `mutable.HashMap`) to `scala.collection.immutable.IntMap(entries*)` or `entries.foldLeft(IntMap.empty[Reflect.Symbol])(_ + _)`. The `mutable.HashMap` from Phase 4 is converted to `IntMap` once per file, replacing the `.toMap` that Phase 4 removed.
- The field type of `addrMap` in `Pass1Result` reverts from `mutable.HashMap[Int, Reflect.Symbol]` (Phase 4) to `IntMap[Reflect.Symbol]`. The Phase 4 change to that single field is superseded by this phase. `FileResult` is unchanged in this phase (it never carried `addrMap`).

`kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/CommentsUnpickler.scala`
- `CommentsUnpickler.read` (verified: receives the addrMap as the `addrMap` parameter) changes its parameter type from `Map[Int, Reflect.Symbol]` to `IntMap[Reflect.Symbol]`.

`kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/TastyOrigin.scala`
- Update `_addrMap` stored type to `IntMap[Reflect.Symbol]` (supersedes Phase 4's `mutable.HashMap` for this field, since `IntMap` is still immutable and safe for lazy `body` decode access).

**Tests:** 1 new in `PositionsUnpicklerTest.scala`:
- T1: `PositionsUnpickler.readSync` with an `IntMap` addrMap containing 10,000 entries returns correct position mappings for all 10,000 entries. (Functional correctness at scale; allocation profile assertion is deferred to Phase 8 re-profiling.)

Existing `PositionsUnpicklerTest` suite must pass unchanged (no behavioral change, only type change).

**Public API:** `AstUnpickler`, `PositionsUnpickler`, `CommentsUnpickler`, `TastyOrigin` are all `private[kyo]` or `kyo.internal`. No public API delta.

**Verification command:**
```
sbt 'kyo-reflectJVM/testOnly *PositionsUnpicklerTest *AstUnpicklerTest' 2>&1 | tail -20
```

---

### Phase 6: Interner pre-sizing

**Depends on:** Phase 1 (entry count produced during Phase A single-pass enumeration is required to compute the `sizeHint`; the hint flows from `ClasspathOrchestrator` to `Interner` at construction time).

**Rationale:** PERF-VERIFICATION.md §7 confirms `Interner.scala` line 19 hard-codes `initialCapacity = 16` per shard, and the `Interner` is constructed with `new Interner(128)` at `ClasspathOrchestrator.scala` line 103. With 5,949 TASTy files and 32 shards, each shard processes approximately 186 files' worth of names. Starting at capacity 16 causes multiple resize events per shard. `Interner$Entry[]` is 0.94% of allocation samples. Pre-sizing to `(estimatedNameCount / numShards) * 2` eliminates most resize events. STEERING.md prohibits default parameters on internal/private APIs; therefore the `initialShardCapacity` parameter has no default value and all existing call sites must be updated explicitly.

**Files to produce:** none.

**Files to modify:**

`kyo-reflect/shared/src/main/scala/kyo/internal/reflect/symbol/Interner.scala`
- Add constructor parameter `initialShardCapacity: Int` (no default value, per STEERING.md rule on no default params in internal code).
- Use `initialShardCapacity` at line 22 in place of the hard-coded `initialCapacity` value.
- The sole production caller is `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/ClasspathOrchestrator.scala:103`. Update that single call site from `new Interner(128)` to `new Interner(numShards = 128, initialShardCapacity = sizeHint)` where `sizeHint = (entryCount / 128).max(16)` and `entryCount` is the count returned from the Phase 1 single-pass enumeration. Test files that instantiate `Interner` directly (enumerate during fix application via `grep -rn 'new Interner' kyo-reflect`) are updated to pass an explicit value or use the default.

`kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/ClasspathOrchestrator.scala`
- Line 103: change `new Interner(128)` to `new Interner(numShards = 128, initialShardCapacity = sizeHint)`.
- Compute `sizeHint`: after Phase 1 produces `allEntries: Chunk[String]` from the single-pass walk, compute `val sizeHint = Math.max(16, (allEntries.size / 128) * 2)` before constructing the Interner. The divisor 128 matches the `numShards` argument.

**Tests:** 3 new in `InternerTest.scala`:
- T1: Extend `Interner` with a package-private `growCount: java.util.concurrent.atomic.AtomicInteger` incremented from inside the `grow` method (line 101). T1 instantiates `new Interner(numShards = 4, initialShardCapacity = 256)`, interns 1,000 distinct byte sequences, and asserts `interner.growCount.get() == 0`. The `growCount` field is `private[kyo]` (test-observable, not part of public API).
- T2: `new Interner(numShards = 4, initialShardCapacity = 8)` and `new Interner(numShards = 4, initialShardCapacity = 256)` both return the same canonical object for the same interned string (pre-sizing does not affect identity semantics).
- T3: `new Interner(numShards = 4, initialShardCapacity = k)` with `k` = exact number of entries per shard interns all entries without resizing (capacity exactly filled).

**Public API:** `Interner` constructor signature changes. `Interner` is `private[kyo]`. No public API delta.

**Verification command:**
```
sbt 'kyo-reflectJVM/testOnly *InternerTest *QueryApiTest' 2>&1 | tail -20
```

---

### Phase 7: sbt plugin for build-time snapshot

**Depends on:** Phases 1-6 (the plugin calls `Reflect.Classpath.openCached`, which benefits from all prior optimizations; it should be written against the final optimized API). No ordering dependency on Phase 8 (re-profiling), but Phase 7 must commit before Phase 8 can profile the full pipeline including the plugin path.

**Rationale:** PERF-VERIFICATION.md §10 confirms `kyo-compat` at `build.sbt` line 1275 (`lazy val \`kyo-compat\` = (project in file("kyo-compat/plugin")).enablePlugins(SbtPlugin)`) is the only existing sbt plugin in this repo and serves as the structural reference. PERF-VERIFICATION.md §9 confirms `openCachedImpl` compares by filename only (line 880 of `Reflect.scala`); the embedded digest at snapshot byte offset 16 is never re-verified on load. The plugin writes a snapshot at compile time so subsequent `openCached` calls find it without paying the cold-path cost. With Phase 2 in place, the snapshot check costs O(jars) in `stat` calls rather than O(entries) in CEN reads.

**Approach: fork-JVM.** `kyo-reflect` is Scala 3 only. A Scala 2.12 sbt 1.x plugin cannot invoke it directly. Cross-building `kyo-reflect` to 2.12 is intractable (depends on Scala 3 metaprogramming). Reflective dispatch via a separate classloader requires the plugin to bundle kyo-reflect as a resource and unpack it at runtime, which is fragile. Fork-JVM is the same pattern sbt itself uses for `run` and `runMain`. JVM startup cost (~1-2s) is paid once per build and amortized over all subsequent dev sessions (snapshot file persists). For an LSP-target use case where the snapshot is the win, this is acceptable.

**Files to produce:**

`kyo-reflect-sbt/plugin/src/main/scala/kyo/KyoReflectPlugin.scala`
- sbt `AutoPlugin` written in Scala 2.12 (the only Scala version sbt 1.x supports). `autoImport` exposes: `reflectSnapshotDir: SettingKey[File]` (default: `target.value / "kyo-reflect-snapshots"`), `reflectSnapshot: TaskKey[File]` (produces the snapshot file).
- `reflectSnapshot` task implementation: uses `sbt.Fork.java` to spawn a JVM with `kyo-reflect` on its classpath and `kyo-reflect-sbt-runner.jar` as the main JAR. Arguments passed to the forked JVM: the project's `fullClasspath` entries (roots) and the output path. The forked JVM runs `kyo.internal.reflect.sbt.SnapshotRunner.main`. Plugin parses stdout for success/failure and returns the produced snapshot `File`.
- The plugin does NOT add itself as a compile or test dependency to the project it is applied to; it is a build-tool-only artifact.

`kyo-reflect-sbt/runner/src/main/scala/kyo/internal/reflect/sbt/SnapshotRunner.scala`
- Scala 3 entrypoint, in a separate sbt subproject `kyo-reflect-sbt-runner`. Depends on `kyo-reflect`. Single `main` method: parses argv (roots + output path), calls `Reflect.Classpath.openCached` via `KyoApp.run`, exits with code 0/1 indicating success/failure.

`kyo-reflect-sbt/plugin/src/sbt-test/kyo-reflect-sbt/basic/test`
- Scripted test script. Steps: (1) `> compile`, (2) `> reflectSnapshot`, (3) verify snapshot file exists at `target/kyo-reflect-snapshots/*.krfl`, (4) verify snapshot is non-empty (size > 0), (5) verify snapshot is loadable by calling `Reflect.Classpath.openCached` on the produced snapshot and asserting it returns a non-empty classpath.

`kyo-reflect-sbt/plugin/src/sbt-test/kyo-reflect-sbt/basic/project/plugins.sbt`
- Adds the `kyo-reflect-sbt-plugin` to the scripted test project.

`kyo-reflect-sbt/plugin/src/sbt-test/kyo-reflect-sbt/basic/build.sbt`
- Minimal build: `scalaVersion := "3.x.y"`, `enablePlugins(KyoReflectPlugin)`, `libraryDependencies += "io.getkyo" %% "kyo-reflect" % version`.

**Files to modify:**

`build.sbt`
- Add two new subprojects: `kyo-reflect-sbt-plugin` (Scala 2.12, sbt plugin) following the `kyo-compat` pattern at line 1275 (`lazy val \`kyo-reflect-sbt-plugin\` = (project in file("kyo-reflect-sbt/plugin")).enablePlugins(SbtPlugin).settings(...)`); `kyo-reflect-sbt-runner` (Scala 3, depends on `kyo-reflect.jvm`).

**Tests:** 2 scripted tests in `kyo-reflect-sbt/plugin/src/sbt-test/kyo-reflect-sbt/`:
- `basic/test`: verifies the plugin generates a non-empty `.krfl` snapshot and that the snapshot is loadable by `Reflect.Classpath.openCached`.
- `missing-runner/test`: verifies that when the runner JAR is absent, the plugin task fails with a useful error message (not a bare `NullPointerException` or silent exit code 1).

**Public API additions:** `KyoReflectPlugin` (sbt `AutoPlugin`); `reflectSnapshot: TaskKey[File]`; `reflectSnapshotDir: SettingKey[File]`; `kyo.internal.reflect.sbt.SnapshotRunner` (`private[kyo]` Scala 3 entry point).
**Public API modifications:** none.
**Public API removals:** none.

**Verification command:**
```
sbt 'kyo-reflect-sbt-plugin/scripted'
```

---

### Phase 8: Re-profile and verification

**Depends on:** all prior phases committed. Phase 8 cannot begin until Phases 1-7 are all committed and the full test suite is green.

**Rationale:** The profile baseline in `COLD-LOAD-PROFILE-FULL.md` was measured before any of the above changes. Acceptance criteria are: cold-load median at or below 25 ms (from 55 ms baseline), snapshot median at or below 5 ms (from 57 ms baseline, achievable because Phase 2 makes digest computation O(jars) in `stat` calls rather than O(entries) in CEN reads, and Phases 1 and 3 eliminate redundant JAR opens and overlap I/O with decode). All 245+ existing kyo-reflect tests must pass on JVM, JS, and Native.

**Files to produce:**

`kyo-reflect/COLD-LOAD-PROFILE-AFTER.md`
- Before/after table comparing COLD-LOAD-PROFILE-FULL.md baseline values against post-optimization measurements.
- New CPU and allocation breakdowns from async-profiler on the same harness (`kyo-reflect-bench/jvm/src/main/scala/kyo/bench/ColdLoadFullBench.scala`) with the same classpath (`/tmp/kyo-bench-cp.txt`).
- Narrative findings: which phases contributed how much, any remaining hotspots, any new hotspots introduced by Channel mutex overhead (see Risk notes below).

**Files to modify:** none.

**Tests:** full kyo-reflect suite on all three platforms, run sequentially per STEERING.md rule (never JVM/JS/Native in parallel):
```
sbt 'kyo-reflectJVM/test'
sbt 'kyo-reflectJS/test'
sbt 'kyo-reflectNative/test'
```

**Public API:** none changed.

**Verification command:**
```
sbt 'kyo-reflect/test' 2>&1 | tail -20
```
Plus the harness for before/after numbers:
```
sbt -J-Xmx4G -J-Xss8m 'kyo-reflect-bench/runMain kyo.bench.ColdLoadFullBench 5 10'
```

---

## Considered but not included

**TypeUnpickler.readType per-call HashMaps:** PERF-VERIFICATION.md §5 confirms the hot Phase A/B path uses `readTypeIntoSession` which receives a `DecodeSession` and reuses its existing `session.addrCache`, `session.inProgressRec`, and `session.binderAddrMap` allocated once per file. The `readType` entry point at line 61 allocates fresh maps, but it is not the hot path. No change needed.

**Interner Arrays.copyOf:** PERF-VERIFICATION.md §7 confirms `Interner.grow` (line 104) uses `new Array[Interner.Entry](expected.length * 2)` plus a manual copy loop, not `Arrays.copyOf`. The `Arrays.copyOf` in the profile originates from `mutable.HashMap` internal resize, which is addressed indirectly by Phase 6 (pre-sizing reduces the number of resize events). No direct change to `Interner.grow` is needed.

**Async.foreach fiber batching:** PERF-VERIFICATION.md §13 confirms `Async.foreach(tastyFiles, concurrency)` already creates exactly `min(size, concurrency)` worker fibers (line 693 of `Fiber.scala`: `val numWorkers = Math.min(size, concurrency)`). The claim of "one fiber per file" in the profile narrative is incorrect. No batching change needed or possible.

**mmap of JAR bytes:** COLD-LOAD-PROFILE-FULL.md Findings confirms mmap would not reduce `JarFile$JarFileEntry` allocation or `__open` syscall count. JAR entries are deflate-compressed; mmap does not skip decompression. `inflate_fast` is only 0.19% of CPU samples. Not a viable optimization target.

**Snapshot embedded-digest re-verification:** PERF-VERIFICATION.md §9 confirms `openCachedImpl` compares by filename only (line 880 of `Reflect.scala`) and never re-verifies the embedded digest at snapshot byte offset 16. This is a pre-existing correctness gap, not a performance issue. It is out of scope for this performance plan and should be tracked as a separate correctness task.

**Per-file path string deduplication via Interner:** The 2.20% allocation from `jarPath + "!/" + entryName` string concatenation across 35,809 entries is addressed indirectly by Phase 1 (which stores raw `(jarPath, entryName)` pairs and builds the composite path only when content is read, not at enumeration time). A dedicated path Interner would be an additional optimization but adds complexity; Phase 1's approach is sufficient given the Phase 8 acceptance target.

---

## Verification approach

Each phase has a targeted sbt verification command and an explicit test count. Implementation agents run only the listed verification command for that phase; broad regression suites are reserved for phase-group boundaries and the final Phase 8 green run.

Phase-group boundaries (run all tests on JVM before proceeding):
- After Phase 2 commits (Phases 1-2 form the JAR enumeration group).
- After Phase 4 commits (Phases 3-4 form the pipeline and HAMT group).
- After Phase 6 commits (Phases 5-6 form the boxing and pre-sizing group).
- After Phase 7 commits (plugin group).
- Phase 8 is the final platform-wide green run.

All phases must keep all 245+ existing kyo-reflect tests green on JVM, JS, and Native. Sequential cross-platform runs are mandatory (STEERING.md rule).

---

## Risk notes

**Pipelining attacks the existing idle-time fraction:** COLD-LOAD-PROFILE-FULL.md CPU breakdown shows `__psynch_mutexwait` at 29.4%. This is the OS pthread mutex wait syscall used by the Kyo scheduler to park idle worker threads (not contention on a shared data structure). The current sequential phase boundaries (enumerate `.tasty` → enumerate `module-info.class` → `Async.foreach(decode)` → `mergeResults`) create idle gaps where workers park between stages. Pipelining keeps work flowing across stages, so worker threads stay running and the park/unpark count drops. Channel operations are brief in-process synchronized regions, not park/unpark cycles; they do not contribute to the 29.4%. The residual risk for Phase 3 is normal scheduler overhead from the extra fibers (producer + decoders + merger), which Phase 8 re-profiling validates.

**Direct CEN reader correctness:** ZIP CEN parsing is format-sensitive. `JarCentralDirectory.scala` must handle Zip64 extended end-of-central-directory locator (jars larger than 4 GB or with more than 65535 entries), and must handle deflated vs stored entries in the CEN (entry names in the CEN are always uncompressed regardless of content encoding, but compressed and uncompressed sizes differ). Multi-disk JARs are unsupported by the JVM standard library as well; `JarCentralDirectory` returns `Abort[ReflectError]` for multi-disk JARs rather than silently returning partial results. Phase 1 test T5 (large JAR, >500 entries) and T6 (non-JAR file) cover the critical edge cases.

**sbt plugin cross-platform snapshot loadability:** The plugin runs on JVM only (sbt runs on JVM). The `.krfl` snapshot it produces must be loadable by `Reflect.Classpath.openCached` on JVM, JS, and Native. The scripted test covers JVM loadability. JS and Native loadability is not covered by the scripted test (scripted tests run only in sbt/JVM); it is covered by the existing `SnapshotRoundTripTest` (which exercises the snapshot read path on all three platforms and already passes at the Phase 7 baseline).

**Supervisor commits, one commit per phase:** The implementation agent leaves the tree dirty after each phase. The supervisor reads `git diff`, verifies the diff matches the plan exactly, and creates one commit per phase. No `Co-Authored-By` lines in any commit.
