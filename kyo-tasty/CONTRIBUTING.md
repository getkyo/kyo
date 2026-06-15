# kyo-tasty contributor guide

This file documents the internal design contracts, invariants, and conventions
specific to `kyo-tasty`. Read the root `CONTRIBUTING.md` first; everything
there applies here, and this file extends it with module-local rules.

---

## Architecture overview

The module has three layers:

1. **Public surface** (`kyo/Tasty.scala`, `kyo/TastyError.scala`): the
   `object Tasty` companion, the `Symbol` / `Type` / `Tree` ADTs, `Classpath`,
   `Pickle`, `Uuid`, supporting enums.

2. **Loading layer** (`kyo/internal/tasty/`): the TASTy, classfile, and Scala 2
   unpicklers (`kyo/internal/tasty/reader/`, `classfile/`, `scala2/`), the
   `LoadingSymbol` intermediate representation, `DecodeContext`,
   `ClasspathOrchestrator`, and the `kyo.Path`-based file walk. Memory-mapped
   reads are per-platform: `JarMappedReader` (JVM), `PlatformMmapReader` (JS),
   `NativeMmapReader` (Native).

3. **Query / snapshot layer**: the `Classpath` instance methods, `SnapshotWriter`,
   `SnapshotReader`, subtyping (`kyo/internal/tasty/type_/Subtyping.scala`), the
   type arena, and the classpath indices.

---

## Module layout

`kyo-tasty` ships as a single published module with its supporting code colocated:

- `kyo-tasty/`: the cross-platform published module (JVM, JS, Native).
- `kyo-tasty/fixtures/`: the internal test-fixtures sub-project (sbt id
  `kyo-tasty-fixtures-internal`, declared in `build.sbt`). It compiles the
  cross-platform fixture classes whose `.tasty` / `.class` output the tests load.
  Not published; depended on by `kyo-tasty` only as a `Test` dependency.
- `kyo-tasty/shared/src/test/scala/kyo/demos/`: the usage demos, run as tests
  (`extends kyo.test.Test[Any]`) so the documented call shapes stay green.

---

## Companion surface contract: lookups and bodyTree only

`object Tasty` is a thin effectful companion over the active `Classpath`. Its
members fall into exactly these groups, and no others:

- **Scope entry points** (`withClasspath`, `withPickles`): bind a `Classpath`
  into `bindingLocal` for the duration of a block.
- **Active-classpath accessor** (`classpath`): reads the active `Binding`.
- **Lookups** (`find*`, `require*`, `all*`, `findModule`, `findConcreteClass`,
  `findClassesByName`, `findMethod`, `symbolsAnnotatedWith`): each delegates to
  the matching pure instance method on `Classpath`, e.g. `findClass` is
  `classpath.map(_.findClass(fullName))`.
- **Body access** (`bodyTree`): the single entry point for on-demand AST body
  decoding.
- **Cache maintenance** (`evictOlderThan`) and the version constant
  (`supportedTastyVersion`), plus the `private[kyo] lazy val global` fallback.

Navigation and rendering (subtyping with `isSubtypeOf`, member traversal,
`show`, type and tree rendering) are **pure instance methods on `Classpath`**,
not companion methods. There are no navigation delegators on `object Tasty`:
the companion never re-exports `isSubtypeOf`, member walks, or rendering. A
caller obtains the `Classpath` value with `Tasty.classpath` and calls those
methods directly; every result is plain immutable data.

When adding a query, decide which group it belongs to. A new lookup is a thin
`classpath.map(_.<name>)` delegator on the companion plus the pure instance
method on `Classpath`. A new navigation or rendering operation is a pure
instance method on `Classpath` only; it does not gain a companion delegator.

---

## INV-IMMUTABLE-ADT: public ADTs must have only immutable fields

Every field on every public case class in `kyo/Tasty.scala` must be immutable.
Mutability is confined to the loading layer: `LoadingSymbol.Materialising` uses
`var` fields that are written during unpickling and become read-only once
`ClasspathOrchestrator.finalizeMerge` converts them into the final `Symbol` tree.

The enforcement gate rejects `Array[_]` fields on any type that derives `Schema`
or `CanEqual` in the public surface. Use `Chunk` instead of `Array`, `Dict`
instead of `Map`, `Span` instead of a raw `Array` field.

---

## AllowUnsafe site list (INV-009)

`AllowUnsafe.embrace.danger` is absent from `shared/src/main`; every internal use
of an unsafe operation there is supplied by an enclosing `Sync.Unsafe.defer` at
the boundary above, and each such block carries a `// Unsafe:` comment naming the
local reason (a bare-Java mutable allocation, a zero-copy `toArrayUnsafe`, a
synchronous decode inside a `defer`). Four of those `Sync.Unsafe.defer` boundaries
are the public effect entry points below; the remaining `defer` blocks are
internal helpers under them. Three further sites live in `jvm/src/main` and carry
their own `embrace.danger` proof. Adding a new public entry point or a new
platform-bridging site is a contract violation and requires a plan change plus a
bump to this list.

### Four public effect entry points

**Site 1: `Tasty.withClasspath(roots, cacheDir)`**
- Effect: walks files via `kyo.Path`, decodes TASTy / classfile bytes, registers
  finalizers for any mapped readers.
- AllowUnsafe gate: `Sync.Unsafe.defer` inside
  `ClasspathOrchestrator.initWithBodies` / `runPhaseAB` (and the in-memory
  `initWithBodiesFromBytesMap` / `runPhaseABFromBytesMap` for the pickle path),
  supplying `AllowUnsafe` for the bare-Java `ConcurrentHashMap` body store.

**Site 2: `Tasty.global` (lazy val)**
- Effect: first access calls `PlatformFallback.initFallback`, which on JVM
  discovers the system classpath from `java.class.path`. On JS and Native,
  `initFallback` returns `Binding.empty` without I/O.
- Location: `kyo/Tasty.scala`, the `private[kyo] lazy val global` under
  `object Tasty`.

**Site 3: `Tasty.bodyTree(symbol)`**
- Effect: parses raw AST bytes on demand; result memoized per classpath instance
  in `DecodeContext.bodyMemo`.
- AllowUnsafe gate: a `Sync.Unsafe.defer` block inline in `Tasty.bodyTree`
  (`kyo/Tasty.scala`), the only `AllowUnsafe` in the public query layer.
- Note: `Tasty.bodyTree` is the ONLY public entry point for AST body access. Do
  not expose `DecodeContext.bodyMemo` or `DecodeContext.bodyStore` to callers.

**Site 4: `Tasty.evictOlderThan(cacheDir, maxAge)`**
- Effect: lists and deletes `.krfl` files older than `maxAge`.
- AllowUnsafe gate: the `kyo.Path` operations `Path(cacheDir).list("*.krfl")`,
  `p.stat`, and `p.remove` inside `Tasty.evictOlderThan`; their `FileFsException`
  / `FileReadException` failures are recovered to `TastyError.SnapshotIoError`.

### Three JVM-only platform-bridging sites (`jvm/src/main`)

The following sites carry an `AllowUnsafe.embrace.danger` proof because they
sit on a JVM-only platform-bridging boundary that has no Kyo effect entry above
them. Each carries an inline `// Unsafe:` comment explaining the boundary.

- `kyo/internal/tasty/snapshot/PlatformDigest.scala` ;
  `JarCentralDirectory.read(jarPath)(using AllowUnsafe.embrace.danger)` inside
  `digestForJarRoot`. The method has no effect row (it returns `Long` for use
  from the lazy classpath-bootstrap path).
- `kyo/internal/tasty/query/PlatformFallback.scala` ;
  `given AllowUnsafe = AllowUnsafe.embrace.danger` inside `initFallback`. The
  fallback is itself the bootstrap entry point that invokes
  `KyoApp.Unsafe.runAndBlock`; no caller can supply `AllowUnsafe` above it.
- `kyo/internal/tasty/query/ZipHandlePlatform.scala` ;
  `val au: AllowUnsafe = AllowUnsafe.embrace.danger` inside `readJarEntry`,
  used to disambiguate implicit resolution inside a `try` block under the
  enclosing `Sync.Unsafe.defer`.

### Enforcement

`rg 'AllowUnsafe.embrace.danger' kyo-tasty/shared/src/main` returns 0 lines, and
the `jvm/src/main` site count is exactly 3. The behavioral four-site invariant is
exercised by `InvariantsSpec`, which asserts the observable contract of each site
(`Tasty.global` is a stable lazy singleton; `Tasty.bodyTree` returns
`Maybe.Absent` for a classpath installed without a `DecodeContext`; and so on):
`kyo-tasty/shared/src/test/scala/kyo/InvariantsSpec.scala`.

---

## bodyTree decode: arena-closed vs decoder-error discrimination

`Tasty.bodyTree` decodes a body slice inside its `Sync.Unsafe.defer` block and
maps every failure mode to a typed `TastyError` on the `Abort[TastyError]` row.
Two failure classes must stay distinct:

- A body whose `(bodyStart, bodyEnd)` bounds are malformed, or any decoder bug
  reached while reading the slice, surfaces as `TastyError.MalformedSection`.
  Bounds are validated up front from the integers themselves rather than relying
  on a thrown `ArrayIndexOutOfBoundsException`, because Scala.js turns the same
  out-of-bounds read into an `UndefinedBehaviorError` that escapes every catch.
- A read that fails because the backing mmap arena was already closed (the scope
  finalizer flipped the closed flag) surfaces as `TastyError.ClasspathClosed`,
  detected via the `isArenaClosed(IllegalStateException)` predicate.

Any other `IllegalStateException` is a decoder gap, not a closed arena, and is
reported as `MalformedSection` so it is never mislabeled as `ClasspathClosed`.
Keep these arms separate when touching `bodyTree`: collapsing the closed-arena
case into the decoder-error case loses the distinction callers rely on.

---

## Closed TastyError ADT and snapshot minor-version rule

`TastyError` is a `sealed enum`; every variant is visible at the call site and
exhaustive matches are checked at compile time. Snapshot persistence encodes each
error as a **string tag**, not an ordinal: `serializeErrors` /
`readErrors` (`SnapshotWriter.scala` / `SnapshotReader.scala`) write the variant's
`productPrefix` as a length-prefixed UTF-8 varint followed by the variant fields.
The string-tag encoding is stable against enum reordering and new-variant
additions, so a new variant does not invalidate existing snapshots by itself.

Adding a new `TastyError` variant:

1. Add the case to `kyo/TastyError.scala`.
2. Add its field-serialization arm in `SnapshotWriter.serializeErrors` and the
   matching read arm in `SnapshotReader.readErrors` (tag is the `productPrefix`,
   so no integer to assign).
3. Update `SectionValidator.expectedTastyErrorVariantCount` (currently `23`).
4. Add a round-trip test leaf in `TastyErrorRoundTripTest`.

A snapshot whose minor version is older than the reader's is rejected immediately
with `TastyError.SnapshotVersionMismatch`; there is no downgrade path. The current
`SnapshotFormat.minorVersion` is `12`.

Wire-format bump history (append-only; do not reuse or renumber):
- `10 -> 11`: added the `UnhandledSubtypingCase`, `UnresolvedReference`,
  `UnknownType`, and `MissingDeclaredType` error variants.
- `11 -> 12`: added the `PLISTS__` section persisting `Symbol.Method.paramListIds`.
  Minor-11 snapshots lack the section and return
  `TastyError.SnapshotVersionMismatch`; regenerate the cache.

### Type tag extensions also require a minor-version bump

`Tasty.Type` values are serialized with a one-byte integer tag in
`SnapshotWriter.writeType` / `SnapshotReader.readType`
(`kyo/internal/tasty/snapshot/SnapshotWriter.scala` and `SnapshotReader.scala`).
Unlike the string-tagged errors, this is an ordinal scheme. Adding a new
`Tasty.Type` case that must survive a snapshot round-trip requires:

1. Assign the next free integer tag in `SnapshotWriter.writeType`.
2. Add the matching reader arm in `SnapshotReader.readType`.
3. Increment `SnapshotFormat.minorVersion` by 1
   (`kyo/internal/tasty/snapshot/SnapshotFormat.scala`, currently `12`).
4. Add a round-trip test leaf in `SnapshotTypedRoundTripTest` or a dedicated test.

**Never reuse or renumber an existing integer type tag.** The sequence is
append-only; removed cases leave a gap, and old snapshot files are rejected by
the minor-version guard rather than silently mis-decoded. The snapshot format does
not serialize `Tree` nodes directly; if that changes, the same append-only integer
tag discipline applies.

---

## Tasty.Java.* namespace convention

All types that model Java-specific classfile concepts live under `object Java`
inside `object Tasty`:

| Concept              | Type                                  |
|----------------------|---------------------------------------|
| Java annotation      | `Tasty.Java.Annotation`               |
| Annotation value     | `Tasty.Java.Annotation.Value`         |
| Classfile metadata   | `Tasty.Java.Metadata`                 |
| Record component     | `Tasty.Java.RecordComponent`          |
| Parameter-name group | `Tasty.Java.ParamGroup`               |
| Enclosing method     | `Tasty.Java.EnclosingMethod`          |
| Module descriptor    | `Tasty.Java.Module.Descriptor`        |
| Module requires      | `Tasty.Java.Module.Requires`          |
| Module exports       | `Tasty.Java.Module.Exports`           |
| Module opens         | `Tasty.Java.Module.Opens`             |
| Module provides      | `Tasty.Java.Module.Provides`          |

Any new classfile-specific type belongs under `Tasty.Java.*` or
`Tasty.Java.Module.*`. Do not introduce a top-level name in `object Tasty` for a
Java-only concept.

---

## Uuid opaque type

`Tasty.Uuid` is an `opaque type Uuid = String` carrying a canonical lowercase
36-character hex string. Construct it only through `Uuid.parse(input)`, which
accepts upper- or lowercase hex and normalises to lowercase; malformed input
returns `Result.fail(TastyError.InvalidUuid(input))`. The companion exposes
`private[kyo]` internals (`unsafeWrap`, `msb`, `lsb`) for the snapshot wire
boundary and a `given Schema[Uuid]` that delegates to `Schema[String]` so the
wire encoding is the canonical hex form. `TastyError.InconsistentClasspath`
reports UUID mismatches as `Tasty.Uuid` values. Two `Uuid` values are equal when
their canonical forms match (`given CanEqual[Uuid, Uuid]`).

---

## Producer / consumer split

### LoadingSymbol

`kyo.internal.tasty.symbol.LoadingSymbol` is the mutable intermediate
representation used during unpickling (Pass A and Pass B). It has one subtype,
`LoadingSymbol.Materialising`, whose `var` fields the unpicklers write
incrementally as they scan AST, type, and position sections.

Cross-file references (whose defining file is not in the classpath) travel as
`Tasty.Type.Named(SymbolId(negId))` with `negId < -1`. During decoding the
mapping from each negative id to its fully-qualified name accumulates in
`TypeUnpickler.DecodeSession.unresolvedIdToFullName`; `finalizeMerge` lands the
surviving mappings in `Classpath.Indices.unresolvedFullNameByNegId` and resolves
or filters out the references. No `Named(SymbolId(-1))` sentinel survives in the
produced `Tasty.Symbol` fields: an unresolved reference becomes
`TastyError.UnresolvedReference` (soft-fail) or raises
`TastyError.ClasspathBuilding` (fail-fast), and an unresolvable parent type is
filtered at the ADT boundary.

`LoadingSymbol` is `private[kyo]` and must never appear in the public surface.
The conversion boundary is `ClasspathOrchestrator.finalizeMerge`, which builds a
`SymbolDescriptor` from each `Materialising` and converts it to a `Tasty.Symbol`
via `TypedSymbolFactory.from`.

### DecodeContext

`DecodeContext` (`kyo/internal/tasty/query/Binding.scala`) carries the decode-time
context needed to decode TASTy body bytes on demand. It is wrapped in
`Binding.decodeCtx` and is `Maybe.Absent` for a `Binding` built from a
pre-existing `Classpath` or from the empty fallback. Each `withClasspath` /
`withPickles` invocation creates a fresh `DecodeContext`, so memos are never shared
across calls. Its two fields:

- `bodyStore: ConcurrentHashMap[SymbolId, SymbolBody]`: raw body blobs populated
  during the merge. `bodyTree` reads it to locate the byte slice.
- `bodyMemo: ConcurrentHashMap[SymbolId, Result[TastyError, Tree]]`: caches the
  decoded `Tree` (or its decode failure) per symbol. Populated by `bodyTree`.

Per-load error accumulation does NOT live on `DecodeContext`. Errors collect in
`ClasspathOrchestrator.MergeState.accErrors` (`mutable.ArrayBuffer[TastyError]`)
during the merge and are folded into `classpath.errors` before the `Binding` is
handed to the caller.

### Orchestrator boundary

`ClasspathOrchestrator` is the single point where mutable loading state
transitions to the immutable `Classpath`. Nothing downstream of this boundary
holds a reference to `DecodeContext`, `LoadingSymbol`, `MergeState`, or any other
loading-layer type.

---

## Scaladoc bar

Every public type in `kyo/Tasty.scala` and `kyo/TastyError.scala` carries a
scaladoc comment between 8 and 35 lines. The bar is defined in the root
`CONTRIBUTING.md` under "Type-Level Scaladoc" (lines 434-455). In summary:

- First line: one-sentence purpose statement (what, not how).
- Middle lines: concrete field semantics or composition examples; no prose padding.
- Closing: `@param`, `@return`, `@tparam`, or `@see` cross-references.

Below 8 lines is insufficient for a public type; above 35 lines is padding and
should move to the README or be trimmed.

---

## Cross-platform stance

`kyo-tasty` is cross-platform: source and tests default to `shared/src/main/scala`
and `shared/src/test/scala`. Filesystem access goes through `kyo.Path` (which has
JVM, JS, and Native backends), and memory-mapped reads dispatch to per-platform
readers, so the loading and query paths work on all three platforms.

**Cross-platform entry points:**
- `Tasty.withClasspath(roots, cacheDir)`: walks `roots` via `kyo.Path` and decodes
  on every platform. JVM, JS, and Native each map files with their own backend.
- `Tasty.withClasspath(classpath: Classpath)`: binds a pre-loaded pure-data
  classpath; no filesystem access.
- `Tasty.withPickles(pickles)`: decodes in-memory pickle bytes; no filesystem.
- All lookup and navigation methods (`findClass`, `allMethods`, `isSubtypeOf`,
  ...): pure once a classpath is bound.
- `SnapshotWriter` / `SnapshotReader`: operate on in-memory byte arrays.

**The single JVM-only entry point:**
- `Classpath.initWithPlatformModules(roots)`: walks the JDK module image behind
  `jrt:/` to merge `module-info` descriptors. JS and Native have no `jrt:/`; the
  `PlatformModuleOps` stub on those platforms raises
  `TastyError.UnsupportedPlatform`.

Move a file to `jvm/src/` (or `js/`, `native/`) only when a concrete, named
platform-specific dependency is identified and cannot be worked around. "Easier to
test" is not a valid reason. The `PlatformFallback`, `PlatformModuleOps`, and mmap
readers are the genuine per-platform leaves.

**Test platform gating:** gate a leaf that genuinely requires a JVM-only path with
the `kyo-test` filter `"name".onlyJvm in { ... }` (and the symmetric `.onlyJs` /
`.onlyNative`). These phantom-typed filters compile-exclude the leaf on other
platforms. Use them only for platform-specific MECHANICS (a real `jrt:/` walk, a
real on-disk stdlib classpath), never to dodge a cross-platform contract gap.

---

## Kyo primitives mandate

Prefer Kyo types over scala stdlib equivalents throughout:

| Use this                     | Not this                              |
|------------------------------|---------------------------------------|
| `Maybe`                      | `Option`                              |
| `Result`                     | `Either` / `Try`                      |
| `Chunk`                      | `List` / `Seq`                        |
| `Dict`                       | `Map`                                 |
| `Span`                       | `Array` (public ADT)                  |
| `kyo.Path`                   | `java.nio.file.*` (file walk, list)   |
| `ChunkBuilder.init[Byte]`    | `java.io.ByteArrayOutputStream`       |

`java.util.concurrent.ConcurrentHashMap` is permitted in `shared/src/main` (it is
cross-platform: Scala.js and Scala Native both provide implementations) and is the
backing store for `DecodeContext.bodyStore` / `bodyMemo`. All other
`java.util.concurrent.*` types are banned; use Kyo's `AtomicRef`, `AtomicLong`,
`AtomicInt`, `AtomicBoolean` for shared state instead.

Identifiers in `shared/src/main` are spelled out in full, with no internal-kernel
abbreviations. The rename map (for example `unresolvedIdToFqn` ->
`unresolvedIdToFullName` on `DecodeSession`) is enforced; `InvariantsSpec` carries
a compile witness for a representative pair.

---

## Per-platform stubs

`PlatformFallback` has three implementations:

- `jvm/src/main/scala/kyo/internal/tasty/query/PlatformFallback.scala`:
  reads `java.class.path` and `jrt:/` to build the JVM standard-library binding.
- `js/src/main/scala/kyo/internal/tasty/query/PlatformFallback.scala`:
  returns `Binding.empty`.
- `native/src/main/scala/kyo/internal/tasty/query/PlatformFallback.scala`:
  returns `Binding.empty`.

`PlatformModuleOps` and the mmap readers (`JarMappedReader`,
`PlatformMmapReader`, `NativeMmapReader`) follow the same per-platform pattern.
Adding a new platform-specific capability requires a stub in all three leaves. The
stub on a non-supporting platform either raises `TastyError.UnsupportedPlatform`
or returns a documented no-op; it must never throw.
