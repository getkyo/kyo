# Phase 2 Prep — Digest by jar metadata

## Verbatim API signatures (no impl agent need re-read)

### FileSource.stat
```scala
def stat(path: String)(using Frame): FileSource.FileStat < (Sync & Abort[ReflectError])
```
Defined in `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/FileSource.scala` line 54.

### FileSource.FileStat
```scala
final case class FileStat(mtimeMs: Long, size: Long)
```
Defined in `FileSource.scala` line 59 (inside `object FileSource`). Two fields only: `mtimeMs: Long` and `size: Long`. No CRC field.

### FileSource.list (single-suffix and Phase 1 multi-suffix)
Single-suffix (current, line 42):
```scala
def list(dir: String, suffix: String)(using Frame): Chunk[String] < (Sync & Abort[ReflectError])
```
Multi-suffix (Phase 1 addition, to `FileSource` trait):
```scala
def list(dir: String, suffixes: Chunk[String])(using Frame): Chunk[String] < (Sync & Abort[ReflectError])
```
Phase 1 adds the multi-suffix variant and keeps the single-suffix variant as a delegate. By the time Phase 2 runs, both overloads exist on the trait and all platform implementations.

### FileSource.exists
```scala
def exists(path: String)(using Frame): Boolean < Sync
```
Defined in `FileSource.scala` line 48. Returns `Boolean < Sync` (no Abort) — a missing path is `false`, not an error.

### DigestComputer.compute / computeParanoid
```scala
def compute(roots: Seq[String], source: FileSource)(using Frame): Array[Byte] < (Sync & Abort[ReflectError])
def computeParanoid(roots: Seq[String], source: FileSource)(using Frame): Array[Byte] < (Sync & Abort[ReflectError])
```
Both in `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/snapshot/DigestComputer.scala`, lines 67 and 82. Return an 8-byte little-endian `Array[Byte]`.

### DigestComputer fnv1a helpers
```scala
private def fnv1aUpdate(h: Long, data: Array[Byte]): Long
private def fnv1aUpdateLong(h: Long, v: Long): Long
```
`fnv1aUpdate` (line 29): XORs and multiplies each byte in `data` into the running hash.
`fnv1aUpdateLong` (line 41): XORs and multiplies all 8 bytes of `v` little-endian into the running hash.
`fnv1aOffset: Long = -3750763034362895579L` (line 23) is the starting seed.

### SnapshotFormat.encodeString
```scala
def encodeString(s: String): Array[Byte] = s.getBytes(java.nio.charset.StandardCharsets.UTF_8)
```
Defined in `SnapshotFormat.scala` line 139. Used in `compute` at line 72:
```scala
hash = fnv1aUpdate(hash, SnapshotFormat.encodeString(path))
```
Phase 2 retains this exact call for the path contribution to the digest, including for jar roots.

---

## File:line anchors

- `DigestComputer.compute` lives at lines 67-76.
- `DigestComputer.computeParanoid` lives at lines 82-93.
- `collectStats` at lines 96-103: calls `collectFiles`, then stats each file.
- `collectFiles` at lines 106-114: calls `source.list(root, ".tasty")` per root — this is the third JAR open per iteration that Phase 2 eliminates for jar roots.
- The single call site for `compute`: `Reflect.scala` line 872 calls `SnapshotDigest.compute(roots, source)` (where `SnapshotDigest` is the import alias for `DigestComputer`). No other call sites exist.

---

## What Phase 2 changes in DigestComputer

`compute` (line 67): add a per-root branch inside the collection step. Replace the unconditional `collectStats(roots, source)` call with logic that iterates roots and, for each root:
- If `root.startsWith("jrt:/")`: fall through to `collectFiles`/`collectStats` per-file enumeration (see jrt note below).
- Else if `root.toLowerCase.endsWith(".jar")`: call `source.stat(root)` to obtain `FileStat(mtimeMs, size)`. Contribute `(root, mtimeMs, size)` as the hash input for this root — do NOT call `source.list` for this root.
- Otherwise (directory): fall through to `collectFiles`/`collectStats` per-file enumeration.

The resulting `Seq[(String, Long, Long)]` from all roots (jar-root triples + per-file triples from directory/jrt roots) must be collected into one list and sorted by `_._1` before hashing — same sort as the existing line 69. Do not hash jar roots in a separate pass.

`computeParanoid` (line 82): same branching. For jar roots, call `source.read(root)` to read the entire jar file's raw bytes and hash them directly. For non-jar roots, retain per-file content hashing.

`collectStats` and `collectFiles`: these become directory-only helpers called only for non-jar roots. No change to their bodies is required; the guard sits at the `compute`/`computeParanoid` level.

---

## Edge cases and gotchas

### Jar root detection
Use `root.toLowerCase.endsWith(".jar")` — matching the exact guard in `JvmFileSource.list` at line 76. This is the canonical check in this codebase for jar vs directory dispatch.

### jrt:/ paths
JRT roots start with `"jrt:/"`. `JvmFileSource.stat` at lines 104-113 calls `Paths.get(path)` unconditionally. `Paths.get("jrt:/modules/java.base")` throws `InvalidPathException` on some JDKs (the JRT filesystem is not the default provider). Do NOT call `source.stat(root)` for `jrt:/` roots. Retain the existing per-file enumeration for any root whose string starts with `"jrt:/"`. Branch order in `compute`:
1. `root.startsWith("jrt:/")` — per-file enumeration (same as today).
2. `root.toLowerCase.endsWith(".jar")` — `source.stat(root)` metadata digest.
3. Otherwise — per-file enumeration.

### Mixed roots
A classpath contains a mix of jar files, directories, and `jrt:/` roots. All `(path, mtime, size)` triples — whether from jar-root stat or from per-file stat of directory/jrt entries — must be collected into one `Seq` and sorted together by path before hashing. Do not hash jar-root entries in a separate pass before or after directory entries.

### MemoryFileSource.stat in existing tests
`SnapshotRoundTripTest.MemoryFileSource.stat` (line 60-64) does `files.get(path)` and returns `FileStat(0L, bytes.length.toLong)`. For jar-root tests, the root path itself (e.g., `"libs/foo.jar"`) must be added to the source via `src.add("libs/foo.jar", someBytes)` so that `stat("libs/foo.jar")` resolves. Tests T14 and T18 that exercise the jar-root path must either add this key or use `PlatformFileSource` (real filesystem).

### Snapshot invalidation
Pre-existing `.krfl` snapshots carry digests computed from per-file `(path, mtime, size)` tuples enumerated from jar entries. After Phase 2 the digest for jar roots changes to `(jarPath, jarMtime, jarSize)`. Old snapshots are unreachable (filename mismatch). The next cold load triggers fresh snapshot writing. This is intentional per the plan; no migration code is needed.

### Platform stat resolution
- JVM `JvmFileSource.stat` (lines 104-113): `Files.getLastModifiedTime(Paths.get(path)).toMillis` — millisecond precision on APFS/ext4, 1-second precision on HFS+.
- Native `NativeFileSource.statFile` (lines 148-155): reads `st_mtimespec.tv_sec` only, multiplied by `1000` (`mtime * 1000L`). Resolution is 1 second on all POSIX platforms via this implementation.
- JS `JsFileSource.stat` (lines 117-129): `stat.mtimeMs.asInstanceOf[Double].toLong` — millisecond precision on Node.js.

The Native implementation returns second-granularity mtime. This is sufficient for invalidation because the tests use a `+1 hour` offset, not sub-second deltas.

---

## Test-data suggestions

### T14 (jar-root digest deterministic)
Use `MemoryFileSource` with the jar path added as a key:
```scala
val src = MemoryFileSource()
src.add("libs/foo.jar", Array[Byte](0x50, 0x4B, 0x05, 0x06) ++ Array.fill(18)(0.toByte)) // minimal EOCD
DigestComputer.compute(Seq("libs/foo.jar"), src)  // twice
assert(d1.sameElements(d2))
```
`stat("libs/foo.jar")` returns `FileStat(0L, 22L)` from `MemoryFileSource`, which is deterministic.

### T15 (directory-root digest deterministic)
Use the existing `fixtureSource()` pattern (roots = `Seq("root")`, file at `"root/PlainClass.tasty"`). Same as current Test 29; T15 is an explicit copy per the Phase 2 spec. The root does not end in `.jar`, so the directory branch runs.

### T16 (mtime change detected for jar root)
Requires real filesystem. Tag `jvmOnly`:
```scala
val jarPath = java.nio.file.Files.createTempFile("test", ".jar")
// Write zero-entry zip
val zos = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(jarPath.toFile))
zos.close()
val d1 = DigestComputer.compute(Seq(jarPath.toString), PlatformFileSource.get)
// Bump mtime +1 hour, do NOT use Thread.sleep
java.nio.file.Files.setLastModifiedTime(
  jarPath,
  java.nio.file.attribute.FileTime.from(
    java.time.Instant.now().plus(1, java.time.temporal.ChronoUnit.HOURS)
  )
)
val d2 = DigestComputer.compute(Seq(jarPath.toString), PlatformFileSource.get)
assert(!d1.sameElements(d2))
```

### T17 (size change detected for jar root)
Same pattern as T16. After getting `d1`, rewrite the jar with one extra byte appended AND set mtime +1 hour (to prevent 1-second-resolution masking on HFS+). Assert `!d1.sameElements(d2)`.

### T18 (mixed roots consistent)
Create a temp jar file and a temp directory containing a copy of `kyo.fixtures.Embedded.plainClassTasty` (copy to temp dir, do not mutate the original). Compute digest twice with `roots = Seq(jarPath.toString, dirPath.toString)`. Assert `d1.sameElements(d2)`. Tag `jvmOnly`.

### Anti-flakiness
- Never use `Thread.sleep` for mtime tests. Use `Files.setLastModifiedTime` with `Instant.now().plus(1, ChronoUnit.HOURS)`.
- Never mutate a fixture `.tasty` file directly. Copy to a temp directory first.
- Zero-entry jar (minimal valid ZIP): write with `ZipOutputStream` and immediately close before any test that needs a valid jar path.

---

## Concerns

1. **`JvmFileSource.stat` does not handle `jrt:/` paths.** It calls `Paths.get(path)` unconditionally (line 107), which throws for `jrt:/` prefixed paths on JDKs where the JRT filesystem is not the default provider. Phase 2 must guard `root.startsWith("jrt:/")` before calling `source.stat`. This guard is not stated explicitly in the plan; the impl agent must add it.

2. **`MemoryFileSource` stat key semantics.** The in-memory test helper looks up `files.get(path)`. For tests exercising the jar-root branch, the test must call `src.add(jarRootPath, someBytes)` so that `stat(jarRootPath)` resolves. Tests T14 and T18 must include this setup step.

3. **Sort correctness under mixed roots.** If the impl agent collects jar-root triples and per-file directory triples in two separate sorted passes and concatenates them, the resulting hash is NOT the same as collecting all triples into one list and sorting globally. The existing `sortBy(_._1)` at line 69 must apply to the combined list. The impl agent must not sort the two sub-lists independently.

4. **`computeParanoid` for jar root reads the entire jar.** On a 121-jar classpath, `computeParanoid` will read all 121 jar files sequentially. This is intentional (paranoid path). The impl agent must not accidentally use this `read(root)` pattern in the normal `compute` path.

5. **Test T15 vs existing Test 29.** Test 29 in `SnapshotRoundTripTest.scala` already exercises digest determinism for a directory root (`fixtureSource()`, roots = `Seq("root")`). T15 from the plan is effectively the same assertion. The impl agent may either add a new labeled test or note that Test 29 covers T15. Do not delete Test 29.
