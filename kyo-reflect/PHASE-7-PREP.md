# Phase 7 Preparation Notes: Query API + File Sources + Snapshot Cache + Cross-Platform Orchestration

Phase 7 is the final and largest implementation phase. It wires all prior-phase pieces into the real `Classpath.open` implementation, replaces all stubs in `Reflect.scala`, and delivers Phase A/B/C parallel orchestration (DESIGN.md §15), file sources per platform (DESIGN.md §14), snapshot KRFL read/write (DESIGN.md §16), and the `Query` combinator API (DESIGN.md §12).

---

## 1. Verbatim Public API Signatures

These are the exact signatures from `Reflect.scala` (current stub skeleton) plus the additions Phase 7 must provide.

### `Classpath` factory methods (in `object Classpath`)

```scala
// Current stubs — Phase 7 replaces with real implementations:
def open(roots: Seq[String])(using Frame): Classpath < (Sync & Scope & Abort[ReflectError])
def open(roots: Seq[String], strict: Boolean)(using Frame): Classpath < (Sync & Scope & Abort[ReflectError])
def openCached(roots: Seq[String], cacheDir: String)(using Frame): Classpath < (Sync & Scope & Abort[ReflectError])
def fromPickles(pickles: Seq[Pickle])(using Frame): Classpath < Sync
```

Note: `Reflect.scala` uses `String` for paths (not `java.nio.file.Path`), consistent with the existing skeleton. The DESIGN.md shows `Path` in some places but the skeleton was committed with `String`; Phase 7 follows the skeleton exactly.

### Extension methods on `Classpath` (current stubs)

```scala
extension (cp: Classpath)(using Frame)
    def findClass(fqn: String): Maybe[Symbol] < (Sync & Abort[ReflectError])
    def findPackage(fqn: String): Maybe[Symbol] < (Sync & Abort[ReflectError])
    def packages: Chunk[Symbol] < (Sync & Abort[ReflectError])
    def topLevelClasses: Chunk[Symbol] < (Sync & Abort[ReflectError])
    def errors: Chunk[ReflectError] < Sync
```

### New extensions Phase 7 adds

```scala
extension (cp: Classpath)(using Frame)
    def query[A](using Reads[A]): Query[A]
    def findClassByBinary(binaryName: String): Maybe[Symbol] < (Sync & Abort[ReflectError])
```

`findClassByBinary` canonicalizes its argument via `FqnCanonicalizer.toFullName` (converting `"java/util/Map$Entry"` to `"java.util.Map.Entry"`) then delegates to `findClass`.

### `Query[A]` combinators

```scala
final class Query[A] private[reflect] (impl: Query.Internal[A]):
    def filter(p: A => Boolean): Query[A]
    def where(p: Reflect.Symbol => Boolean): Query[A]
    def withFlag(f: Flag): Query[A]
    def named(name: String): Query[A]
    def extending(parent: Symbol): Query[A]
    def map[B](f: A => B): Query[B]
    def stream: Stream[A, Sync & Abort[ReflectError]]
    def run: Chunk[A] < (Sync & Abort[ReflectError])
```

Combinators compose into an intermediate plan evaluated lazily on `.run` / `.stream`. The plan is a single traversal over the bound classpath's symbol cache, touching only the fields the `Reads[A]` declares via `touchedFields`.

### `Reflect.Snapshot` object (public, in `kyo.reflect` or on `object Reflect`)

```scala
object Reflect:
    object Snapshot:
        def evictOlderThan(d: Duration): Unit < (Sync & Scope)
```

On JS browser (no filesystem), `evictOlderThan` returns immediately without error — the platform `FileSource`'s `list` method returns `Abort.fail(...)` which the implementation absorbs silently.

### `Reflect.classFqn[A]` (already correct from Phase 0)

```scala
inline def classFqn[A](using t: Tag[A]): String = t.show
```

---

## 2. Classpath State Machine Design (DESIGN.md §15)

The `Classpath` opaque type aliases a `final class Classpath` with an `AtomicRef[Classpath.State]`. The class is constructed in `Building` state BEFORE Phase B starts, so every `Symbol` created during decode can immediately receive a non-null, stable `home: Classpath` reference.

### Exact internal class structure

```scala
// kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/Classpath.scala
final class Classpath private[reflect] (
    private val state: AtomicRef[Classpath.State]
):
    private[reflect] def checkOpen(using Frame): Unit < (Sync & Abort[ReflectError]) =
        state.get.map {
            case State.Building(_, _) => Abort.fail(ReflectError.ClasspathBuilding)
            case State.Ready(_, _, _) => Kyo.unit
            case State.Closed         => Abort.fail(ReflectError.ClasspathClosed)
        }

object Classpath:
    private[reflect] enum State:
        case Building(symbols: ChunkBuilder[Symbol], arenas: Chunk[TypeArena])
        case Ready(symbols: Chunk[Symbol], canonical: TypeArena, fqnIndex: Map[String, Symbol])
        case Closed
```

### Lifecycle transitions

1. Orchestrator creates `cp = new Classpath(AtomicRef.init(State.Building(...)))`.
2. Phase B fibers create `Symbol` instances with `home = cp`. State is `Building`; resolving accessors would fail with `ClasspathBuilding` (defense-in-depth; user code cannot reach the classpath yet).
3. Phase C completes; orchestrator CAS-transitions state from `Building` to `Ready` with the merged data.
4. `cp` is returned from `Classpath.open`. All resolving accessors now return real data.
5. Outer `Scope.run` exit fires a `Scope.ensure` finalizer that CAS-transitions state from `Ready` to `Closed`. Subsequent resolving calls return `Abort.fail(ReflectError.ClasspathClosed)`.

The `Scope.ensure` finalizer is registered on the enclosing `Scope` (the one wrapping the `Classpath.open` call), not on a per-file inner scope.

### CAS transition code (sketched)

```scala
// Phase C completion:
state.compareAndSet(State.Building(...), State.Ready(symbols, canonical, fqnIndex))

// Scope.ensure finalizer:
Scope.ensure {
    Sync.defer { state.unsafe.set(State.Closed) }
}
```

---

## 3. Phase A/B/C Orchestration (DESIGN.md §15)

### Phase A: header sweep (~100 µs per file)

Per-file inner `Scope.run` is load-bearing. Without it, every `Scope.acquireRelease` registers into the outer scope's finalizer queue; file descriptors pile up and FD exhaustion occurs at large classpath sizes (200+ files). With the inner scope, each handle is released as soon as that file's Phase A completes.

```scala
// DESIGN.md §15 pseudocode (verbatim):
val fqnIndex: Map[FQN, FileRef] < (Sync & Abort[ReflectError] & Scope) =
    Async.foreach(tastyFiles, concurrency = cores) { file =>
        Scope.run {
            for
                handle <- Scope.acquireRelease(openFile(file))(close)
                header <- parseHeader(handle)                  // magic, version, UUID
                names  <- parseNameTable(handle)               // into per-file Array[Name]
                topRefs = indexTopLevelDecls(header, names)    // FQN -> (file, addr)
            yield topRefs
        }
    }.map(_.foldLeft(Map.empty)(_ ++ _))
```

Output: a global `Map[FQN, FileRef]` mapping each top-level FQN to the file and byte offset of its definition.

### Phase B: parallel body decode

Each fiber owns its own `TypeArena` — no cross-fiber contention. Bodies are stored as `Span[Byte]` views into the heap-resident `Array[Byte]` (which lives as long as the `Classpath`). Cross-file type references are recorded as internal `UnresolvedRef(fqn)` placeholders, not yet public `Type` values.

```scala
// DESIGN.md §15 pseudocode (verbatim):
Async.foreach(tastyFiles, concurrency = cores) { file =>
    Scope.run {
        for
            handle  <- Scope.acquireRelease(openFile(file))(close)
            bytes   <- Sync.defer(readAllBytes(handle))         // heap-resident copy
            arena   <- Sync.defer(TypeArena())                  // per-fiber, no synchronization
            symbols <- decodeAstSection(bytes, arena, fqnIndex) // skeleton-eager + lazy bodies
        yield PerFileResult(file, symbols, arena)
    }
}
```

### Phase C: single-threaded merge (~50ms for 200 files)

Runs after all Phase B fibers complete. Resolves placeholders via O(1) FQN hash lookup. Structurally-equal types from different fibers collapse to one canonical instance.

```scala
// DESIGN.md §15 pseudocode (verbatim):
val canonical = TypeArena.canonical()
for (file, result) <- allResults do
    for ph @ UnresolvedRef(fqn) <- result.placeholders do
        val targetSym = fqnIndex(fqn).resolveSymbol()
        result.replacePlaceholder(ph, Type.Named(targetSym))
    canonical.mergeFrom(result.arena)
```

After Phase C, the orchestrator CAS-transitions the `Classpath` state to `Ready`.

### Failure modes

- Phase A failure: file marked unreadable; Phase B skips it. Error appended to accumulator.
- Phase B failure inside one file: that file's symbols become `SymbolKind.Unresolved`; error appended. All other files continue.
- Phase C placeholder miss: `Type.Named(unresolvedSymbol)` where `unresolvedSymbol.kind == SymbolKind.Unresolved`; resolving accessors on it return `Abort.fail(ReflectError.SymbolNotFound)`.
- Soft-fail mode (default): errors accumulate in `Classpath.errors`. Strict mode: `Classpath.open(roots, strict = true)` fails the entire load on first error.

---

## 4. KRFL Snapshot Format (DESIGN.md §16)

### Header layout

```
+------------------+
| magic    "KRFL"  | 4 bytes (4 ASCII chars, little-endian)
| version  M.m.p.0 | 4 bytes (kyo-reflect version triple)
| flags            | 8 bytes (byteOrder in bit 0: 0=LE, 1=BE)
+------------------+
| inputDigest      | 32 bytes (FNV-1a 64-bit hash, zero-padded to 32)
| compilerVersion  | 16 bytes (Scala major.minor.exp.0 + 12 reserved bytes)
+------------------+
| sectionCount     | 4 bytes
| sectionIndex     | sectionCount * 24 bytes each:
|   name           |   8 bytes (fixed-length section ID, zero-padded ASCII)
|   offset         |   8 bytes (byte offset from start of file)
|   length         |   8 bytes (byte length of section)
+------------------+
```

### Nine sections

| Section ID   | Content |
|---|---|
| `NAMES`      | Shared byte arena + `(offset: Int, length: Int)` table indexed by `NameId`. |
| `SYMBOLS`    | Packed fixed-size records: `(kindByte, flags: Long, nameId, ownerId, declaredTypeId, parentsListId, membersListId, bodyFileId, bodyStart, bodyEnd)`. `home` is NOT serialized; restored from enclosing `Classpath` on load. |
| `TYPES`      | Packed records indexed by canonical type ID: `(kindByte, operandAId, operandBId, extraDataOffset)`. Multi-operand types reference `TYPES_EXTRA` by offset. |
| `TYPES_EXTRA`| Variable-length operand data for types needing more than two operands (Applied, Function, Tuple, MatchType). |
| `PARENTS`    | Int arrays of type IDs for class parent lists. |
| `MEMBERS`    | Int arrays of symbol IDs for class member lists. |
| `FILES`      | Per-source-file metadata: `(path: String, mtime: Long, size: Long, uuid: String)`. |
| `BODY_BYTES` | Inline byte storage for lazy body decode. Symbol records in `SYMBOLS` reference offsets into this section. |
| `ERRORS`     | Serialized `ReflectError` cases accumulated during decode. Restored into `Classpath.errors` on snapshot load. |

### Atomic-rename concurrent write strategy

```
write   -> tmp-${digest}-${pid}-${nonce}.krfl
fsync
rename  -> ${digest}.krfl   (atomic on POSIX; MOVEFILE_REPLACE_EXISTING on Windows)
```

Two concurrent processes decoding the same input both produce identical tmp files (decode is deterministic) and both attempt rename. The last rename wins; the loser's tmp is silently discarded. No file locking, no corruption. Documented in `SnapshotWriter.scala`.

Stale tmp files (from crashed writers) are removed by `Reflect.Snapshot.evictOlderThan(d)`.

### Versioning policy

- Major bump: invalidates all old snapshots; full re-decode + fresh write; reader emits `ReflectError.SnapshotVersionMismatch`.
- Minor bump: add-only sections; old snapshots load (new sections are empty).
- Patch bump: format-stable.

### Endianness

All multi-byte integers are little-endian. Header `flags` byte encodes `byteOrder` (bit 0: 0=LE, 1=BE). Reader checks; mismatch triggers byte-swap or rejection.

### Open path pseudocode

```scala
def openCached(roots: Seq[String], cacheDir: String) =
    for
        currentDigest <- computeDigest(roots)
        snapshot      <- findSnapshot(cacheDir, currentDigest)
        cp <- snapshot match
            case Present(file) => loadSnapshot(file)
            case Absent        =>
                for
                    cp <- openFresh(roots)
                    _  <- writeSnapshotAtomically(cp, cacheDir, currentDigest)
                yield cp
    yield cp
```

### Browser no-op

`Reflect.Classpath.openCached` on JS browser degrades to `open(roots)` (always-miss, never-write). The `JsFileSource` returns `Abort.fail(ReflectError.FileNotFound("browser: use fromPickles"))` for all `read` and `list` calls when no Node.js `process` object is detected.

---

## 5. FNV-1a 64-bit Digest (Supervisor Override)

The supervisor has overridden the agent's default recommendation of SHA-256 for the input digest. Phase 7 uses **FNV-1a 64-bit**, a pure-Scala non-cryptographic hash. Approximately 30 LOC, identical on all platforms, zero external dependencies.

### Algorithm

```
FNV-1a 64-bit:
  offset_basis = 14695981039346656037UL (as Long = -3750763034362895579L)
  prime        = 1099511628211L

  def hash(data: Array[Byte]): Long =
      var h = offset_basis
      var i = 0
      while i < data.length do
          h ^= (data(i) & 0xff).toLong
          h *= prime
          i += 1
      h
```

### Application to input digest

```scala
// Sorted (path, mtime, size) tuples — deterministic across builds
val tuples: Seq[(String, Long, Long)] = roots.flatMap { root =>
    source.list(root, ".tasty").map { path =>
        val stat = source.stat(path)   // mtime + size
        (path, stat.mtime, stat.size)
    }
}.sortBy(_._1)

// Hash each tuple sequentially into a 64-bit accumulator
val digest = tuples.foldLeft(fnv1aOffset) { case (h, (path, mtime, size)) =>
    fnv1aUpdate(fnv1aUpdate(fnv1aUpdate(h, path.getBytes("UTF-8")), longToBytes(mtime)), longToBytes(size))
}
```

The 64-bit hash is stored as a hex string in the snapshot filename: `${digest.toHexString}.krfl`.

### `computeParanoid`

Uses FNV-1a 64-bit of file **contents** rather than mtime+size. Slower; detects content changes that leave mtime and size unchanged (e.g., `touch -t` + in-place content edit).

### Per-platform mtime source

- JVM: `java.nio.file.Files.getLastModifiedTime(path).toMillis`
- Native: POSIX `stat()` via FFI, `st_mtimespec.tv_sec * 1000 + tv_nsec / 1000000`
- JS-Node: `fs.statSync(path).mtimeMs`
- JS-browser: `openCached` returns `Abort.fail(ReflectError.FileNotFound("browser: use fromPickles"))` before any digest computation

---

## 6. mmap on JVM/Native, read-into-Array on JS (DESIGN.md §16)

### JVM snapshot read path (preferred)

From DESIGN.md §16:

> `FileChannel.map(MapMode.READ_ONLY, 0, size)` returns a `MappedByteBuffer`, or on JDK 22+ a `MemorySegment` via `Arena.ofShared.allocate(...)`. The Classpath holds one Arena; `Scope.ensure` closes it at scope exit. Body slices reference offsets into the mapped region directly; demand paging brings them in lazily on first touch.

The execution plan (line 616) clarifies: `java.lang.foreign.Arena.ofShared().allocate(size, 1)` returns a `MemorySegment`; file bytes are loaded via `MemorySegment.copy`; body slices reference offsets into the segment directly (zero-copy); the `Arena` is closed explicitly by `Scope.ensure` at scope exit — deterministic release, not GC-dependent.

This differs from the `MappedByteBuffer` pattern in `kyo-examples/jvm/...ledger/db/Index.scala` (which uses `file.map(READ_WRITE, 0, fileSize)` returning a `MappedByteBuffer`). The snapshot uses `MemorySegment` from `java.lang.foreign` because JDK 25 is required and `MemorySegment` supports snapshots larger than 2 GB cleanly.

### Native snapshot read path

POSIX `mmap()` via Scala Native FFI. Single handle, demand paging, `munmap` on `Scope.run` exit (via `Scope.ensure`).

### JS snapshot read path

`fs.readFileSync(path)` on Node.js reads into an `Array[Byte]` fallback. No mmap available on JS. JS browser falls through to `Reflect.Classpath.fromPickles` — no snapshot use at all.

### For .tasty files during decode (Phase B)

Tasty files use `readAllBytes` into heap-resident `Array[Byte]` on ALL platforms (not mmap). This is intentional: mmap of 200+ small files causes FD exhaustion risk. The heap `Array[Byte]` lives as long as the `Classpath` and is what enables lazy body slices without needing open file handles.

The `ByteView` sealed adapter handles both cases:

```scala
sealed trait ByteView:
    def peekByte(at: Int): Byte
    def subView(from: Int, until: Int): ByteView

object ByteView:
    final class Heap(bytes: Array[Byte], start: Int, end: Int) extends ByteView
    final class Mapped(segment: java.lang.foreign.MemorySegment, start: Long, end: Long) extends ByteView
    // Mapped is only compiled in jvm/ and native/ platform-specific source trees
```

---

## 7. JS Browser vs Node Detection

The exact guard from `kyo-core/js/src/main/scala/kyo/internal/SystemPlatformSpecific.scala` is the canonical precedent:

```scala
js.typeOf(js.Dynamic.global.process) != "undefined" &&
js.typeOf(js.Dynamic.global.process.platform) != "undefined"
```

The execution plan (line 607) specifies the extended form for `JsFileSource`:

```scala
js.typeOf(js.Dynamic.global.process) != "undefined" &&
js.typeOf(js.Dynamic.global.process.platform) != "undefined"
```

When this guard is `true`: Node.js path — `fs.readFileSync`, `fs.readdirSync`, `fs.statSync`.
When this guard is `false`: browser path — all `read` and `list` calls return `Abort.fail(ReflectError.FileNotFound("browser: use fromPickles"))`.

The `fromPickles` path is the supported browser entry point. Consumers provide `Span[Byte]` blobs via `fetch` or bundled imports.

---

## 8. FileSource Per-Platform Interfaces (DESIGN.md §14)

### Shared trait

```scala
// kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/FileSource.scala
trait FileSource:
    def read(path: String): Array[Byte] < (Sync & Abort[ReflectError])
    def list(dir: String, suffix: String): Chunk[String] < (Sync & Abort[ReflectError])
    def exists(path: String): Boolean < Sync
```

`exists` returns `Boolean < Sync` (no `Abort`) — a non-existent path is a valid `false`, not an error. Callers that need an error on absence use `read` directly. This keeps the call-site effect row lighter for common use (short-circuit guard before attempting `read`).

### JVM implementation

```scala
// kyo-reflect/jvm/src/main/scala/kyo/internal/reflect/query/JvmFileSource.scala
object JvmFileSource extends FileSource:
    def read(path: String): Array[Byte] < (Sync & Abort[ReflectError]) =
        // primary: java.nio.file.Files.readAllBytes(Path.of(path))
        // jrt:/ support: java.nio.file.FileSystems.getFileSystem(URI.create("jrt:/"))
        //   used for JDK module resolution (java.lang.*, java.base module in JDK 25)
        //   detected via: path.startsWith("jrt:/") or via System.getProperty("java.home")
    def list(dir: String, suffix: String): Chunk[String] < (Sync & Abort[ReflectError]) =
        // Walks JAR entries for .tasty and .class suffix via JarFile/ZipEntry
        // For plain directories: java.nio.file.Files.walk(...)
    def exists(path: String): Boolean < Sync = Sync.defer(Files.exists(Path.of(path)))
```

### JS implementation

```scala
// kyo-reflect/js/src/main/scala/kyo/internal/reflect/query/JsFileSource.scala
object JsFileSource extends FileSource:
    private val isNode: Boolean =
        js.typeOf(js.Dynamic.global.process) != "undefined" &&
        js.typeOf(js.Dynamic.global.process.platform) != "undefined"

    def read(path: String): Array[Byte] < (Sync & Abort[ReflectError]) =
        if isNode then
            Sync.defer {
                // fs.readFileSync(path) returns a Node.js Buffer (Int8Array view)
                // Copy element-by-element into Array[Byte]
                val buf = js.Dynamic.global.require("fs").readFileSync(path)
                val arr = new Array[Byte](buf.length.asInstanceOf[Int])
                var i = 0
                while i < arr.length do
                    arr(i) = buf(i).asInstanceOf[Byte]
                    i += 1
                arr
            }
        else
            Abort.fail(ReflectError.FileNotFound("browser: use fromPickles"))
    // list: fs.readdirSync on node; Abort.fail on browser
    // exists: fs.existsSync on node; Sync.defer(false) on browser
```

### Native implementation

```scala
// kyo-reflect/native/src/main/scala/kyo/internal/reflect/query/NativeFileSource.scala
object NativeFileSource extends FileSource:
    // read: POSIX open(path, O_RDONLY) -> read(fd, buf, size) -> close(fd)
    //   via @extern FFI bindings
    // list: opendir(path) -> readdir(dir) -> closedir(dir)
    //   follows symlinks via stat() to resolve real type
    // exists: stat(path, statBuf) returning 0 = exists
```

---

## 9. Query Combinator Design

`Query[A]` is obtained via `cp.query[A](using Reads[A])` and closes over its source `Classpath`. Terminal operations `.run` and `.stream` do not need an implicit `Classpath`; the binding is captured at construction.

### Combinator plan composition

Combinators compose into an intermediate `Query.Internal[A]` plan. On `.run` or `.stream`, the plan executes as a single traversal over the classpath symbol cache, applying:

1. `symbolKinds` filter from `Reads[A]` — prunes entire symbol categories at scan time
2. `where(p)` and `withFlag(f)` predicates — applied before `Reads.read`
3. `named(name)` — matches symbol name string
4. `extending(parent)` — matches symbols with `parent` in their parent list
5. `filter(p: A => Boolean)` — applied after `Reads.read`
6. `map[B](f: A => B)` — applied after `filter`

### Effect rows

- `stream` returns `Stream[A, Sync & Abort[ReflectError]]` — lazy pull-based stream
- `run` returns `Chunk[A] < (Sync & Abort[ReflectError])` — strict materialization

### Builder pattern flow

```scala
// Typical usage:
cp.query[Simple]                    // Query[Simple] — no Async yet
    .where(_.kind == SymbolKind.Method)  // Query[Simple]
    .withFlag(Flag.Inline)               // Query[Simple]
    .named("toString")                   // Query[Simple]
    .map(_.name)                         // Query[Name]
    .run                                 // Chunk[Name] < (Sync & Abort[ReflectError])
```

No `Async` effect in `Query` combinators or terminal operations — the query layer is synchronous after Phase C completes.

---

## 10. All 38 Tests Enumerated

### `QueryApiTest`

1. `Reflect.Classpath.fromPickles(Seq.empty)` succeeds and returns a classpath where `findClass("anything")` returns `Absent`.
2. `cp.findClass("kyo.fixtures.FixtureClasses")` on a classpath opened from fixture TASTy returns `Present(sym)` with `sym.kind == SymbolKind.Class`.
3. `cp.findClass("nonexistent.Class.XYZ")` returns `Absent`.
4. `cp.findPackage("kyo.fixtures")` returns `Present(pkg)` with `pkg.kind == SymbolKind.Package`.
5. `cp.topLevelClasses` returns a non-empty `Chunk[Symbol]` for a classpath with fixture TASTy.
6. `cp.packages` returns at least `"kyo.fixtures"` as a package symbol.
7. `cp.errors` returns `Chunk.empty` for a clean classpath.
8. `cp.errors` returns at least one `ReflectError` for a classpath with one corrupt TASTy file (synthesized fixture).
9. `cp.query[Simple].run` (where `case class Simple(name: Name, flags: Flags) derives Reads`) returns all symbols in the fixture classpath.
10. `cp.query[Simple].where(_.kind == SymbolKind.Method).run` returns only method symbols.
11. `cp.query[Simple].withFlag(Flag.Inline).run` returns only symbols with `Flag.Inline`.
12. `cp.query[Simple].named("toString").run` returns only symbols named `toString`.
13. `cp.query[Simple].map(_.name).run` applies the mapping and returns `Chunk[Name]`.
14. `cp.query[Simple].stream.run` returns the same result as `.run`.
15. Classpath after its outer `Scope.run` has exited: `sym.declaredType` returns `Abort.fail(ReflectError.ClasspathClosed)`.
16. Classpath `state` transitions: `Building` before Phase C completes, `Ready` after `open` returns, `Closed` after scope exits.
17. Strict mode: `Classpath.open(roots, strict = true)` on a classpath with one corrupt file fails with `Abort.fail(ReflectError.CorruptedFile(...))`.
18. Soft-fail (default) mode: `Classpath.open(roots)` with one corrupt file succeeds; `cp.errors` is non-empty; other symbols still resolve.
19. (Skipped — numbered in SymbolResolutionTest block below, this slot belongs to test 31.)
31. Phase A/B/C orchestration: a classpath with 3 fixture TASTy files is loaded with `concurrency = 3`; all symbols are present after `open` returns.
32. Phase B interruption: a classpath is opened with n fixture TASTy files where exactly one file is synthetically corrupted; after `open` returns, assert `cp.topLevelClasses.size == n-1` AND `cp.errors.size == 1`.
33. `cp.findClassByBinary("java/util/Map$Entry")` returns the same `Symbol` as `cp.findClass("java.util.Map.Entry")` (reference-equal after canonicalization).
34. `cp.findClassByBinary("no/such/Class$Nested")` returns `Absent`.
36. Phase B interrupt with file-handle release: simulate 200 files where file number 3 throws during decode; each file's inner `Scope.run` increments an `AtomicInt` counter on acquire and decrements it on finalizer; after parallel decode returns, assert counter equals `0` (no descriptor leak).
24b. `Classpath.open(Seq("/nonexistent/root"), ...)` produces `Abort.fail(ReflectError.FileNotFound("/nonexistent/root"))` (missing root is immediate error in both strict and soft-fail modes).

### `SymbolResolutionTest`

19. Two concurrent `findClass("kyo.fixtures.FixtureClasses")` calls produce reference-equal `Symbol` instances (deduplication via `Cache.memo`).
20. Two concurrent `findClass` calls for different FQNs both resolve independently.
21. `Unresolved` sentinel: `cp.findClass("no.such.Class")` returns `Absent` in soft-fail mode; a symbol in a partial-classpath fixture has `kind == SymbolKind.Unresolved` and `sym.declaredType` returns `Abort.fail(ReflectError.SymbolNotFound(...))`.
35. Cross-classpath FQN structural equality: open two `Classpath` instances over the same roots; look up the same FQN in each; verify `sym1 ne sym2` (not reference-equal across classpaths) AND `sym1.fullName == sym2.fullName` (structural equality by FQN).

### `SnapshotRoundTripTest`

22. Write snapshot to a temp dir, read it back, compare `topLevelClasses` by FQN (structural equality).
23. Reading a snapshot with wrong magic produces `ReflectError.SnapshotFormatError`.
24. Reading a snapshot with different major version produces `ReflectError.SnapshotVersionMismatch` and falls through to full decode.
24a. Attempting to write a snapshot to an unwritable directory (e.g., a path under `/dev/null/impossible`) produces `Abort.fail(ReflectError.SnapshotIoError(...))`.
25. Two concurrent snapshot writers for the same input produce one valid snapshot file (last-write-wins atomic rename; no corrupt output). Implementation: `Async.parallel(2)`, bound with `Async.timeout(1.second)`. Flakiness budget: zero.
26. `openCached` on a warm cache hit returns the same symbol graph as cold `open` (structural equality by FQN).
27. `openCached` on a cold miss writes a snapshot file to the cache dir.
28. `Reflect.Snapshot.evictOlderThan(0.millis)` removes all snapshot and tmp files from the cache dir.
29. `DigestComputer.compute` for the same roots returns the same digest byte array (deterministic).
30. `DigestComputer.compute` for two different file sets returns different digest byte arrays.

**Total: 38 tests** (plan numbering has 1-18, 19-21, 22-36 with 24a+24b inserted, and 31-36; matches the plan's "Total tests: 38").

### Verification command

```
sbt 'project kyo-reflect; testOnly kyo.QueryApiTest kyo.SymbolResolutionTest kyo.SnapshotRoundTripTest'
```

Cross-platform (one at a time per `feedback_sequential_test_runs`):

```
sbt 'kyo-reflectJS/test'
sbt 'kyo-reflectNative/test'
```

---

## 11. Edge Cases and Gotchas

### `Async.foreach` + per-file inner `Scope.run` (critical)

Per `feedback_kyo_scope_fiber_shared`: `Scope` is a `ContextEffect` shared across all `Async.foreach` worker fibers. An `acquireRelease` inside a worker fiber WITHOUT an inner `Scope.run` registers into the **outer** scope's finalizer queue — the handle is not released until the outer `Scope.run` completes. With 200 files, this exhausts file descriptors.

**Rule**: every `Scope.acquireRelease` inside `Async.foreach` workers MUST be wrapped in its own `Scope.run`.

Correct pattern:

```scala
Async.foreach(files, concurrency = cores) { file =>
    Scope.run {          // inner scope — releases handle when this lambda completes
        for
            handle <- Scope.acquireRelease(open(file))(close)
            ...
        yield result
    }
}
```

Wrong pattern (DO NOT DO):

```scala
Async.foreach(files, concurrency = cores) { file =>
    for
        handle <- Scope.acquireRelease(open(file))(close)   // registers in OUTER scope!
        ...
    yield result
}
```

### FD exhaustion if outer `Scope.run` holds all handles

Even with inner `Scope.run` per file, if Phase B opens all 200 files simultaneously with full concurrency, the 200 open handles exist concurrently. This is bounded by the `concurrency = cores` parameter (typically 8-32). Not an issue in practice because `concurrency` is bounded, but document the invariant: `concurrency` must be much less than the system FD limit.

### Browser `fromPickles` fallback

When `JsFileSource.read` returns `Abort.fail(ReflectError.FileNotFound("browser: use fromPickles"))`, the orchestrator must NOT treat this as a hard failure that aborts the whole classpath load in soft-fail mode. Browser consumers are expected to use `fromPickles` directly. `Classpath.open` on browser with real paths will accumulate `FileNotFound` errors in `Classpath.errors`.

### Cross-classpath structural FQN equality test (Test 35)

Two `Classpath` instances over the same roots produce distinct `Symbol` instances (not reference-equal across classpaths). Structural equality is by `sym.fullName`, which is a pure accessor (works even after `Closed`). Test 35 verifies: `sym1 ne sym2` AND `sym1.fullName == sym2.fullName`.

### `Symbol.Unresolved` sentinel (partial-classpath mode)

When Phase C cannot resolve a placeholder FQN (the FQN does not appear in the classpath), the placeholder is replaced with `Type.Named(unresolvedSym)` where `unresolvedSym.kind == SymbolKind.Unresolved`. Resolving accessors on `unresolvedSym` return `Abort.fail(ReflectError.SymbolNotFound(fqn))`. This is the soft-fail path. `cp.findClass("missing.FQN")` returns `Absent` (not an error); an unresolved cross-reference embedded in a type returns `SymbolNotFound` only when the accessor is called.

### `ReflectError.SnapshotIoError` — missing from current `ReflectError.scala`

The current committed `ReflectError.scala` does NOT include `SnapshotIoError`. The execution plan (line 615) requires it. Phase 7 must add:

```scala
case SnapshotIoError(cause: String)
```

to `ReflectError.scala`. This is a modification to an existing file.

### `Cache.memo` actual signature

The actual `Cache.memo` signature in `Cache.scala` is:

```scala
def memo[A](
    maxSize: Int,
    expireAfterAccess: Duration = Duration.Zero,
    expireAfterWrite: Duration = Duration.Zero
)[B, S](
    f: A => B < S
)(using Frame): (A => B < (Async & S)) < Sync
```

Note the extra `Async` in the result: `Cache.memo` makes the memoized function `A => B < (Async & S)`, not `A => B < S`. This is because callers that lose the Promise race wait on the winner's result via `promise.get`, which is `Async`. `Resolver.scala` must account for this extra `Async` in its type signatures.

### `Async.foreach` isolate requirement

`Async.foreach` signature:

```scala
def foreach[E, A, B, S](
    using isolate: Isolate[S, Abort[E] & Async, S]
)(iterable: Iterable[A], concurrency: Int = defaultConcurrency)(
    f: A => B < (Abort[E] & Async & S)
)(using Frame): Chunk[B] < (Abort[E] & Async & S)
```

`S` must have an `Isolate` instance. Effects that don't isolate cleanly (e.g., `Scope` directly) must be handled via the inner `Scope.run` pattern to remove `Scope` from the effect row before passing the lambda to `foreach`.

### `Async.parallel` for test 25

The plan specifies `Async.parallel(2)` for the concurrent-writers test. Check the actual `Async` API — if `parallel` doesn't exist, use `Fiber.init` + `fiber.get` twice or `Async.foreach(Seq(writer1, writer2), concurrency = 2)(identity)`.

---

## 12. Concerns Surfaced During Prep

### Concern 1: `SnapshotIoError` absent from `ReflectError.scala`

The committed `ReflectError.scala` has no `SnapshotIoError` case. The execution plan mandates it (test 24a). Phase 7 MUST add this case as the first modification step.

### Concern 2: DESIGN.md §16 says SHA-256; supervisor says FNV-1a

DESIGN.md §16 explicitly says "SHA-256 of sorted file paths + mtimes + sizes" in the header diagram and again in the Input Digest Policy section. The execution plan (Phase 7 spec, line 617 and 781) says "FNV-1a 64-bit hash". The execution plan supersedes the DESIGN.md for implementation purposes (it contains the supervisor's corrections). **Use FNV-1a 64-bit** — do not use SHA-256.

### Concern 3: `inputDigest` field size

DESIGN.md §16 shows `inputDigest | 32` (32 bytes) in the header layout, which matches SHA-256. FNV-1a 64-bit produces only 8 bytes. The agent must decide: use 8 bytes (FNV-1a native size) or zero-pad to 32. Recommendation: use 8 bytes for the actual digest and document that the field is 8 bytes for FNV-1a (not 32). This is a minor format decision; document clearly in `SnapshotFormat.scala` constants.

### Concern 4: `Async.parallel` API existence

The plan references `Async.parallel(2)` for test 25. This API may not exist in the current kyo-core. Verify before use. Fallback: `Async.foreach(Seq(writer1, writer2), concurrency = 2)(f)`.

### Concern 5: `Classpath` opaque type aliasing

`Reflect.scala` currently declares:

```scala
opaque type Classpath = ClasspathState
final private class ClasspathState
```

Phase 7 replaces `ClasspathState` with the real `final class Classpath` from `kyo.internal.reflect.query.Classpath`. The opaque alias in `Reflect.scala` must be updated to alias the internal class. Since `Classpath` is defined in `internal`, the opaque alias crosses package boundaries — verify Scala 3 allows `opaque type Classpath = kyo.internal.reflect.query.Classpath` and that the internal class remains accessible.

### Concern 6: `Reflect.Snapshot` object placement

The execution plan says `kyo-reflect/shared/src/main/scala/kyo/reflect/Snapshot.scala` for `object Reflect.Snapshot`. This places `Snapshot` as a nested object inside `Reflect` but in a separate file — Scala 3 allows `object Reflect` to span multiple files via `package object`-style extension. Verify the correct pattern for extending `object Reflect` across files (likely requires `extension object` or placing the `Snapshot` definition inside the main `Reflect.scala` object).

### Concern 7: `NativeFileSource` FFI scope

The plan calls for POSIX `open`/`read`/`close` and `opendir`/`readdir`/`closedir` via `@extern`. These require `scala.scalanative.unsafe.*` imports and `@extern` objects. The agent must ensure the `@extern` FFI bindings compile with Scala Native 0.5.x (JDK 25 target). Check the existing `kyo-reflect/native/` Utf8.scala for FFI precedent.

### Concern 8: `JvmFileSource` `jrt:/` support

`jrt:/` filesystem requires `java.nio.file.FileSystems.getFileSystem(URI.create("jrt:/"))`. This call can throw if the JRT filesystem is not mounted (e.g., non-JDK JRE). The implementation must catch and convert to `Abort.fail(ReflectError.FileNotFound(...))`.

### Concern 9: Test 36 uses `AtomicInt` in test fixture

Test 36 needs an `AtomicInt` (or `AtomicBoolean`) counter that increments in `Scope.acquireRelease`'s acquire thunk and decrements in the release thunk. This is a test-only construct. Use `kyo.AtomicInt` (or `java.util.concurrent.atomic.AtomicInteger` if `AtomicInt` is not in kyo-core's API).
