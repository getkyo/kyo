# kyo-reflect Cold-Load Optimization Plan: Code Verification Report

All file:line citations are verified against HEAD of the `cached-inventing-quasar` worktree.

---

## 1. JAR Enumeration Sites

**File:** `kyo-reflect/jvm/src/main/scala/kyo/internal/reflect/query/JvmFileSource.scala`

`list(dir, suffix)` is at line 71. It dispatches on whether `dir` ends with `.jar`, routing to the private `listJarEntries` at line 145.

`listJarEntries` (lines 145-156) allocates `new JarFile(jarPath)` at line 146, iterates via `jar.entries().asIterator().asScala.foreach` at line 149, and builds path strings with `s"$jarPath!/${entry.getName}"` at line 151. Every call opens, walks, and closes the jar.

Both `.tasty` collection (`collectTastyFiles`) and `.class` collection (`collectModuleInfoFiles`) in `ClasspathOrchestrator.scala` call `source.list(root, ".tasty")` and `source.list(root, "module-info.class")` respectively. These are two independent invocations to the same `list` method, each opening the jar separately. A multi-suffix variant of `list` would merge both passes into a single `JarFile` open per jar.

---

## 2. Native FileSource Jar Handling

**File:** `kyo-reflect/native/src/main/scala/kyo/internal/reflect/query/NativeFileSource.scala`

`NativeFileSource` has no JAR support at all. `list(dir, suffix)` at line 49 delegates to `listDirNative` (line 107), which walks directories using POSIX `opendir`/`readdir`. There is no `new JarFile` or equivalent native zip reader anywhere in this file.

The Native implementation has no two-pass JAR enumeration problem because it cannot process JAR files. If kyo-reflect is ever run under Scala Native against a JAR-based classpath, the `list` call would return an empty `Chunk` (the jar path is just treated as a non-existent directory). The multi-suffix optimization is a JVM-only concern.

---

## 3. DigestComputer

**File:** `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/snapshot/DigestComputer.scala`

`compute` (line 67) calls `collectStats` (line 96), which calls `collectFiles` (line 106) to get `.tasty` paths from roots, then calls `source.stat(path)` per file to get `(mtime, size)`. The hash input is sorted `(path, mtime, size)` tuples (line 69-75). It does NOT hash entry CRCs or content.

`computeParanoid` (line 82) hashes sorted `(path, content)` tuples by reading every file's bytes.

`FileSource.FileStat` is defined at `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/FileSource.scala` line 59 as `final case class FileStat(mtimeMs: Long, size: Long)`. It has exactly `mtimeMs` and `size` -- no CRC field.

The profile claim that "DigestComputer re-enumerates jar entries" is **confirmed**: `compute` calls `collectFiles` which calls `source.list(root, ".tasty")` for each root (line 113), reopening every JAR a third time (after the two passes in `openInto`). In total a 121-jar classpath triggers 3 JAR opens per jar: once for `.tasty`, once for `module-info.class`, and once for the digest.

---

## 4. ClasspathOrchestrator Phase Structure

**File:** `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/ClasspathOrchestrator.scala`

Serialization order in `openInto` (lines 83-92):
1. Root validation via `Kyo.foreach` (lines 85-88)
2. `collectTastyFiles` (line 90)
3. `collectModuleInfoFiles` (line 91) -- sequential, not overlapped with step 2
4. `runPhaseAB` (line 92)

Within `runPhaseAB` (lines 95-113):
- `Async.foreach(tastyFiles, concurrency)` at line 105 -- parallel file read+decode
- `readModuleInfoFiles(moduleFiles, ...)` at line 110 -- sequential, starts only after ALL `Async.foreach` fibers complete
- `mergeResults(fileResults, ...)` at line 112 -- Phase C, single-threaded

`runPhaseAB` signature (line 95-102):
```scala
private def runPhaseAB(
    tastyFiles: Chunk[String],
    moduleFiles: Chunk[String],
    strict: Boolean,
    source: FileSource,
    concurrency: Int,
    cp: Classpath
)(using Frame): Unit < (Sync & Async & Abort[ReflectError])
```

`Async.foreach(tastyFiles, concurrency)` call site is line 105.

`mergeResults` (lines 273-419) runs entirely inside `Sync.defer` (line 278), making it single-threaded. It mutates six `mutable.ArrayBuffer` and two `mutable.HashMap` locals (lines 280-285). It cannot accept a `Channel` of incoming `FileResult` values without restructuring the Phase C loop, because the current design reads all `fileResults` in a single pass over a `Chunk[FileResult]`. A channel-based design is possible but requires replacing the `Async.foreach(...).flatMap { fileResults => mergeResults(fileResults, ...) }` pattern with a producer/consumer pipeline.

---

## 5. TypeUnpickler Scratch HashMap

**File:** `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/TypeUnpickler.scala`

`readTypeIntoSession` (lines 169-184) does NOT allocate a fresh `HashMap` per call. It receives a `DecodeSession` and uses its existing `session.addrCache`, `session.inProgressRec`, and `session.binderAddrMap` (all `mutable.HashMap`s allocated once per file in `DecodeSession`, lines 197-200). The per-call allocation seen in the profile was from the `readType` entry point (line 61), which creates four `mutable.HashMap` scratch objects at lines 69-71 (`addrCache`, `inProgressRec`, `binderAddrMap`).

The `DecodeSession` path (used by `AstUnpickler.readPass1` for the hot Phase A/B pass) reuses one set of maps per file. The `readType` path (less commonly called externally) allocates fresh maps per invocation. The profiled hot path is the `DecodeSession` path; per-session maps are already shared, so the profile finding may refer to the `addrMap.toMap` call in `AstUnpickler` at line 153 which converts the mutable `HashMap` to an immutable `Map` (allocating `BitmapIndexedMapNode` tree nodes). The immutable `Map` is stored in `TastyOrigin._addrMap` for lazy `Symbol.body` decodes.

---

## 6. PositionsUnpickler Integer Boxing

**File:** `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/PositionsUnpickler.scala`

`readSync` (line 58) uses `Map.newBuilder[Reflect.Symbol, Reflect.Position]` at line 86 and adds entries via `builder += (sym -> Reflect.Position(...))` at line 110. There is no explicit Integer boxing. The boxing in the profile (`Integer` autoboxing) originates from `Map.newBuilder` which is an `immutable.HashMap` builder -- Scala's immutable `HashMap` uses `AnyRef` keys, so `Int`-keyed internal structures (`curIndex`, `curStart`, `curEnd` at lines 87-89) are stack-local and unboxed. The map key is `Reflect.Symbol` (a reference type), so no boxing there.

The actual boxing source is most likely `addrMap.get(curIndex)` at line 107 where `curIndex: Int` is autoboxed as the key to `Map[Int, Reflect.Symbol]` (the `addrMap` parameter type). This `Map[Int, ...]` is an `immutable.HashMap[Int, ...]` where each `get` autoboxes the `Int` key to `Integer`.

---

## 7. Interner Pre-Sizing

**File:** `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/symbol/Interner.scala`

The `Interner` has `numShards` shards (default 32, line 16). Each shard starts as `new Array[Interner.Entry](initialCapacity)` where `initialCapacity = 16` (line 19), wrapped in an `AtomicReference` (lines 21-23).

The resize path is `grow` at line 101. It allocates a new table via `new Array[Interner.Entry](expected.length * 2)` at line 104. This is a plain `Array` allocation, not `Arrays.copyOf`. However, the shard's old entries are copied into the new array (lines 106-113) via `newTable(slot) = entry` loop, not `Arrays.copyOf`. The profile's `Arrays.copyOf` site is most likely in a different class; the `Interner` uses manual looping, not `Arrays.copyOf`.

The shards could be pre-sized at construction by accepting an `initialCapacity` parameter. With 5,949 TASTy files and a 32-shard interner constructed with `new Interner(128)` (as done at `ClasspathOrchestrator.scala` line 103), the per-shard initial size of 16 will resize multiple times for a large classpath. Pre-sizing to, e.g., `initialCapacity = nameCount / numShards * 2` would eliminate most resize events.

---

## 8. Kyo Channel API

**File:** `kyo-core/shared/src/main/scala/kyo/Channel.scala`

Public surface:
- `Channel.init[A](capacity, access)(using Frame): Channel[A] < (Sync & Scope)` -- line 324
- `put(value)(using Frame): Unit < (Abort[Closed] & Async)` -- line 127
- `take(using Frame): A < (Abort[Closed] & Async)` -- line 156
- `offer(value)(using Frame): Boolean < (Abort[Closed] & Sync)` -- line 106
- `poll(using Frame): Maybe[A] < (Abort[Closed] & Sync)` -- line 120
- `stream(maxChunkSize)(using Tag[Emit[Chunk[A]]], Frame): Stream[A, Abort[Closed] & Async]` -- line 287
- `close(using Frame): Maybe[Seq[A]] < Sync` -- line 217

`close()` behavior: at line 608-613 (zero-capacity path) and line 767-771 (non-zero path), `close()` marks the channel closed and calls `flush()`, which at lines 629-634 completes all pending `take` fibers and `put` fibers with a `Closed` error -- it does NOT drain buffered elements to consumers. In-flight buffered elements are returned in the `Maybe[Seq[A]]` result of `close()`, not automatically delivered. A pipeline that calls `close()` on the work channel and then calls `stream` to drain will fail: `stream` emits `Abort[Closed]` on the next `take`. Use `streamUntilClosed` (line 298) or manually `drain` after `close`.

`Channel` is in `kyo-core`, which is a `crossProject(JSPlatform, JVMPlatform, NativePlatform)` at `build.sbt` line 424. Channel is available on all three platforms.

Effect row: `put` is `Abort[Closed] & Async`; `take` is `Abort[Closed] & Async`. Both require a scheduler (Async).

---

## 9. Snapshot Writer/Reader

**File:** `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/snapshot/SnapshotWriter.scala`

The digest is written as 8 raw bytes at byte offset 16 of the file header (line 188: `java.lang.System.arraycopy(digest, 0, buf, 16, 8)`). The file layout is: magic(4) + version(2) + padding(2) + flags(8) + digest(8) + reserved(8) + sectionCount(4) + sectionIndex.

**File:** `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/snapshot/SnapshotReader.scala`

In `openCachedImpl` (`Reflect.scala` lines 865-897), digest comparison is done by computing `DigestComputer.compute(roots, source)` to get `hexDigest`, then constructing the expected path `s"$cacheDir/$hexDigest.krfl"` (line 880), and checking `source.exists(snapshotPath)` (line 881). There is no byte-level comparison of the digest stored inside the snapshot file versus the computed digest. The comparison is implicit via filename. On a hit, `SnapshotReader.readMapped` reads the file without re-verifying the embedded digest against a freshly computed value.

---

## 10. sbt Plugin Pattern in This Repo

**File:** `build.sbt` lines 1269-1296

There is one existing sbt plugin: `kyo-compat` at line 1275 (`lazy val `kyo-compat` = (project in file("kyo-compat/plugin")).enablePlugins(SbtPlugin)`). It is a Scala 2.12 / sbt 1.x plugin that adds `ProjectMatrix` rows.

A `kyo-reflect-sbt` plugin would follow the same pattern but would be a new module with no existing skeleton. The `kyo-compat` plugin can serve as a reference, but no code is shared.

---

## 11. mergeResults HAMT Cost

**File:** `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/ClasspathOrchestrator.scala` and `AstUnpickler.scala`

`mergeResults` builds `mutable.HashMap.empty[String, Reflect.Symbol]` (line 280) and `mutable.HashMap.empty[String, Reflect.Symbol]` (line 281). It iterates `fr.fqns: Chunk[(String, Reflect.Symbol)]` at line 288 with plain `for` loops and direct `fqnIndex(indexKey) = sym` updates. No `Map.++` or `Map.merge` calls.

The `Tuple2` and `BitmapIndexedMapNode` allocations in the profile originate from `AstUnpickler.scala` lines 153-178, specifically:
- Line 153: `addrMap.toMap` -- converts `mutable.HashMap[Int, Reflect.Symbol]` to an immutable `HashMap`, allocating `BitmapIndexedMapNode` tree nodes per entry.
- Line 176: `parentsBySymbol.view.mapValues(identity).toMap` -- another `mutable.HashMap` to immutable conversion.
- Line 177: `childrenByOwner.view.mapValues(buf => Chunk.from(buf.toSeq)).toMap` -- same.
- Line 178: `typeBySymbol.view.mapValues(identity).toMap` -- same.

Each of the four `.toMap` calls per file produces a new `immutable.HashMap` with `(BitmapIndexedMapNode, Tuple2)` pairs as internal nodes. These are stored in each `FileResult` and used by `mergeResults` as read-only maps for assignment. They are not reused after `mergeResults` completes.

---

## 12. Tests Covering open/openCached

- `kyo-reflect/shared/src/test/scala/kyo/QueryApiTest.scala` -- 38 tests; uses `ClasspathOrchestrator.openInto` extensively (lines 76, 288, 310). Tests `Classpath.open` behaviour indirectly via `openFixtureClasspath`.
- `kyo-reflect/shared/src/test/scala/kyo/SnapshotRoundTripTest.scala` -- 13 tests; Tests 26 (`openCached warm cache hit`, line 238) and 27 (`cold miss writes snapshot`, line 272) directly exercise the `openCached`/`openCachedImpl` code path.
- JVM-specific: `kyo-reflect/jvm/src/test/scala/kyo/SnapshotRoundTripJvmTest.scala` -- covers mmap reader path.

Total: ~54 tests touch `open`/`openCached` paths across three files.

---

## 13. Fiber/Async Batching Opportunity

**File:** `kyo-core/shared/src/main/scala/kyo/Fiber.scala` lines 687-736 and `kyo-core/shared/src/main/scala/kyo/Async.scala` lines 398-433.

`Async.foreach(tastyFiles, concurrency)` delegates to `foreachIndexed` (Async.scala line 433), which delegates to `Fiber.internal.foreachIndexed` (Fiber.scala line 687). The internal implementation at lines 692-733 creates exactly `min(size, concurrency)` worker fibers (line 693: `val numWorkers = Math.min(size, concurrency)`). Each worker runs a self-scheduling loop (`workerLoop` at line 716) that pulls items atomically via `state.counter.getAndIncrement()` (line 718) until items are exhausted.

With 5,949 files and, e.g., `concurrency = 8`, only **8 fibers** are created, not 5,949. Each fiber processes multiple files in a self-draining work-stealing loop. The profile claim that there is "one fiber per file" is **incorrect**. The actual dispatch overhead is `O(concurrency)` fiber allocations, not `O(files)`.

---

## Feasibility Table

| Optimization | Verdict |
|---|---|
| Single-pass JAR enumeration (multi-suffix `list`) | FEASIBLE: `listJarEntries` is the exact site; add `list(dir, suffixes: Seq[String])` overload |
| Merge digest pass with enumeration pass (3 JAR opens → 1) | FEASIBLE: `collectFiles` in `DigestComputer` calls `source.list` independently; pass pre-computed file list from `collectTastyFiles` instead |
| DigestComputer snapshot speedup via mtime+size | FEASIBLE: already the default `compute` path; snapshot is slow only because it calls `collectFiles` which re-enumerates |
| Pre-size Interner shards at construction | FEASIBLE: `initialCapacity` is a private val at line 19; add constructor parameter with default `16` |
| Interner `Arrays.copyOf` site | NEEDS DESIGN: `Interner.grow` (line 104) uses `new Array` + manual copy, not `Arrays.copyOf`. Profile annotation may refer to a different call site (check `mutable.HashMap` internal resize, which does use `Arrays.copyOf` on JVM). |
| Channel-based streaming merge (replace Chunk[FileResult]) | NEEDS DESIGN: `close()` short-circuits buffered elements to consumers (line 608-613); use `streamUntilClosed` or explicit `drain`+`close` ordering. Structural change to `runPhaseAB` required. |
| Channel cross-platform availability | FEASIBLE: `kyo-core` is JVM+JS+Native (build.sbt line 424) |
| `readType` fresh-HashMap-per-call optimization | NEEDS DESIGN: hot path (`readTypeIntoSession`) already reuses per-file maps via `DecodeSession`; profile finding was likely `addrMap.toMap` in `AstUnpickler` line 153, not `readType`. Optimize by keeping `addrMap` mutable longer and snapshotting lazily. |
| PositionsUnpickler Integer boxing fix | FEASIBLE: boxing is from `addrMap.get(curIndex: Int)` at line 107; change `addrMap` parameter type from `Map[Int, Reflect.Symbol]` to `mutable.HashMap[Int, Reflect.Symbol]` or an `IntMap` to avoid boxing |
| Per-file `.toMap` HAMT allocations in AstUnpickler | FEASIBLE: lines 153,176,177,178; defer `toMap` conversion by passing mutable maps into `FileResult` and converting once in `mergeResults` under single-threaded Phase C |
| Async.foreach batching (1 fiber per file claim) | BLOCKED (claim was wrong): implementation already uses `min(size, concurrency)` worker fibers; no batching change needed |
| sbt plugin for kyo-reflect | FEASIBLE: `kyo-compat` at build.sbt line 1275 is the only precedent; new module needed from scratch following that pattern |
| Snapshot digest comparison byte-level verification | NEEDS DESIGN: `openCachedImpl` compares by filename only (line 880); the embedded digest at snapshot offset 16 is never re-verified against the computed digest on load |
