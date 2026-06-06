# kyo-tasty contributor guide

This file documents the internal design contracts, invariants, and conventions
specific to `kyo-tasty`. Read the root `CONTRIBUTING.md` first; everything
there applies here, and this file extends it with module-local rules.

---

## Architecture overview

The module has three layers:

1. **Public surface** (`kyo/Tasty.scala`, `kyo/TastyError.scala`): the
   `object Tasty` API, the `Symbol` / `Type` / `Tree` ADTs, `Classpath`,
   `Pickle`, supporting enums.

2. **Loading layer** (`kyo/internal/tasty/`): the TASTy/classfile unpicklers,
   the `LoadingSymbol` intermediate representation, `DecodeContext`,
   `ClasspathOrchestrator`, `FileSource` and its JVM/JS/Native implementations.

3. **Query / snapshot layer**: `TastyState`, `SnapshotWriter`, `SnapshotReader`,
   subtyping, type arena, indices.

---

## INV-IMMUTABLE-ADT: public ADTs must have only immutable fields

Every field on every public case class in `kyo/Tasty.scala` must be immutable.
Mutability is confined to the loading layer (`LoadingSymbol.Materialising` uses
`var` fields that are written during unpickling and become read-only once the
orchestrator materializes them into the final `Symbol` tree).

The enforcement gate is a CI grep that rejects `Array[_]` fields on any type
that derives `Schema` or `CanEqual` in the public surface. Use `Chunk` instead
of `Array`, `Dict` instead of `Map`.

---

## AllowUnsafe four-site list (INV-009)

There are exactly four effectful sites in the public API. Adding a fifth is a
contract violation and requires a plan change and a bump to this list.

**Site 1: `Tasty.withClasspath(roots, cacheDir)`**
- Effect: reads files, forks decode tasks, registers mmap finalizers.
- AllowUnsafe gate: `FileSource.read`, `FileSource.list` inside
  `ClasspathOrchestrator.buildFromRoots`.

**Site 2: `TastyState.global` (lazy val)**
- Effect: first access triggers `PlatformFallback.initFallback`, which on JVM
  discovers the system classpath from `java.class.path`. On JS and Native,
  `initFallback` returns `Binding.empty` without I/O.
- Location: `kyo/internal/tasty/query/TastyState.scala`.
- Note: `Tasty.current` was the old name (removed in Phase 2 of the cleanup
  campaign). The canonical name is now `TastyState.global`.

**Site 3: `Tasty.bodyTree(sym)`**
- Effect: parses raw AST bytes on demand; result memoized per classpath instance.
- AllowUnsafe gate: `Sync.Unsafe.defer` inside the body decode path
  (`ClasspathOrchestrator.decodeBody`).

**Site 4: `Tasty.evictOlderThan(cacheDir, maxAge)`**
- Effect: lists and deletes `.krfl` files older than `maxAge`.
- AllowUnsafe gate: `FileSource.list`, `FileSource.stat`,
  `FileSource.rename` inside `Tasty.evictOlderThan`.

Every call to `AllowUnsafe` or `Sync.Unsafe.defer` anywhere in `src/main` must
name one of these four sites in a `// Unsafe:` comment. A fifth unlabeled site
is a code-review blocker.

---

## Closed TastyError ADT and wire-format minor bump rule

`TastyError` is a `sealed enum`; every variant is visible at the call site and
exhaustive pattern matches can be checked at compile time. Adding a new variant:

1. Add the case to `kyo/TastyError.scala`.
2. Add a writer tag (next integer after the current max) in
   `SnapshotWriter.writeTastyError`.
3. Add the corresponding reader arm in `SnapshotReader.readTastyError`.
4. Increment `SnapshotFormat.minorVersion` by 1.
5. Update `SectionValidator.expectedTastyErrorVariantCount`.
6. Add a round-trip test leaf in `TastyErrorRoundTripTest`.

**Never reuse a tag number.** The tag sequence is append-only; deleted variants
leave a gap. The current minor version is `11` (bumped from `10` in Phase 11 of
the cleanup campaign when `UnhandledSubtypingCase`, `UnresolvedReference`,
`UnknownType`, and `MissingDeclaredType` were added).

A snapshot with the wrong minor version is rejected immediately with
`TastyError.SnapshotVersionMismatch`; there is no downgrade path.

---

## Tasty.Java.* namespace convention

All types that model Java-specific classfile concepts live under `object Java`
inside `object Tasty`. The mapping is:

| Old name (pre-Phase 4)   | Current name                          |
|--------------------------|---------------------------------------|
| `JavaAnnotation`         | `Tasty.Java.Annotation`               |
| `JavaAnnotation.Value`   | `Tasty.Java.Annotation.Value`         |
| `JavaMetadata`           | `Tasty.Java.Metadata`                 |
| `RecordComponent`        | `Tasty.Java.RecordComponent`          |
| `ParamGroup`             | `Tasty.Java.ParamGroup`               |
| `EnclosingMethod`        | `Tasty.Java.EnclosingMethod`          |
| `ModuleDescriptor`       | `Tasty.Java.Module.Descriptor`        |
| `ModuleRequires`         | `Tasty.Java.Module.Requires`          |
| `ModuleExports`          | `Tasty.Java.Module.Exports`           |
| `ModuleOpens`            | `Tasty.Java.Module.Opens`             |
| `ModuleProvides`         | `Tasty.Java.Module.Provides`          |

Any new classfile-specific type belongs under `Tasty.Java.*` or
`Tasty.Java.Module.*`. Do not introduce top-level names in `object Tasty` for
Java-only concepts.

---

## Producer / consumer split

### LoadingSymbol

`kyo.internal.tasty.symbol.LoadingSymbol` is the mutable intermediate
representation used during unpickling. Its two subtypes are:

- `LoadingSymbol.Materialising`: holds `var` fields that the unpicklers
  write incrementally.
- `LoadingSymbol.Placeholder`: used when a forward reference is encountered
  before the target has been seen. Placeholders that survive to the
  conversion boundary become `TastyError.UnresolvedReference` entries in
  `cp.errors`.

`LoadingSymbol` is `private[kyo]` and must never appear in the public surface.
The conversion boundary is `ClasspathOrchestrator.materializeSymbols`, which
converts every `Materialising` to a `Tasty.Symbol` and promotes every surviving
`Placeholder` to an error.

### DecodeContext

`DecodeContext` is the mutable accumulator threaded through the loading phase.
Its key fields:

- `bodyMemo: IntMap[SymbolBody]`: address map for on-demand body decoding.
  Populated by the TASTy unpickler. Consumed by `Tasty.bodyTree`.
- `bodyStore`: the raw TASTy bytes kept alive for on-demand decode.
- `errors: ArrayBuffer[TastyError]`: per-load error accumulator. Folded into
  `cp.errors` by the orchestrator at scope exit.
- `subtypingErrors: ArrayBuffer[TastyError]`: subtyping-specific accumulator.
  Also folded into `cp.errors` at scope exit.

### Orchestrator boundary

`ClasspathOrchestrator` is the single point where mutable loading state
transitions to the immutable `Classpath`. Nothing downstream of this boundary
should hold a reference to `DecodeContext`, `LoadingSymbol`, or any other
loading-layer type.

---

## Scaladoc bar

Every public type in `kyo/Tasty.scala` and `kyo/TastyError.scala` must carry
a scaladoc comment between 8 and 35 lines. The bar is defined in the root
`CONTRIBUTING.md` at lines 434-455. The requirements in summary:

- First line: one-sentence purpose statement (what, not how).
- Middle lines: concrete usage examples or field semantics; no prose padding.
- Last line (optional): `@param`, `@return`, or cross-reference.

Below 8 lines is insufficient for a public type; above 35 lines is padding
and should be moved to the README or trimmed.

---

## Cross-platform stance

`kyo-tasty` is JVM-primary for filesystem access but cross-platform for
in-memory operations.

**Cross-platform paths:**
- `Tasty.withPickles(pickles)`: fully cross-platform; no filesystem.
- `Tasty.withClasspath(cp: Classpath)`: fully cross-platform; binds a
  pre-loaded pure-data classpath.
- All query methods (`findClass`, `allMethods`, `isSubtypeOf`, etc.):
  cross-platform once a classpath is bound.
- `SnapshotWriter` / `SnapshotReader`: cross-platform; operates on
  in-memory byte arrays.

**JVM-primary paths:**
- `Tasty.withClasspath(roots, cacheDir)`: requires `java.nio.file.*` for
  directory walking and mmap. Raises `TastyError.UnsupportedPlatform` on
  JS and Native.
- `TastyState.global` lazy init: on JVM reads `java.class.path` system
  property to build a fallback `Binding`. On JS and Native, `initFallback`
  returns `Binding.empty` without I/O.
- `Classpath.initWithPlatformModules(roots)`: requires `jrt-fs.jar`. JVM-only.

New work defaults to `shared/src/main/scala` and `shared/src/test/scala`.
Move a file to `jvm/src/` only when a concrete, named JVM-only dependency
is identified and cannot be worked around. "Easier to test" is not a valid
reason.

**Test platform gating:** use `runJVM { ... }` for leaves that genuinely
require JVM APIs (e.g. `java.io.File` reads of fixtures on disk). Do not
use `taggedAs jvmOnly` for new leaves; `runJVM` is the canonical form
(BIND-010 / Q-012).

---

## Kyo primitives mandate

Prefer Kyo types over scala stdlib equivalents throughout:

| Use this   | Not this             |
|------------|----------------------|
| `Maybe`    | `Option`             |
| `Result`   | `Either` / `Try`     |
| `Chunk`    | `List` / `Seq`       |
| `Dict`     | `Map`                |
| `Span`     | `Array` (public ADT) |

`java.util.concurrent.*` is banned from `shared/src/main`. Use Kyo's
`AtomicRef`, `AtomicLong`, `AtomicInt`, `AtomicBoolean` for shared state.

---

## Per-platform stubs

`PlatformFallback` has three implementations:

- `jvm/src/main/scala/kyo/internal/tasty/query/PlatformFallback.scala`:
  reads `java.class.path` and `jrt:/` to build the JVM standard-library binding.
- `js/src/main/scala/kyo/internal/tasty/query/PlatformFallback.scala`:
  returns `Binding.empty`.
- `native/src/main/scala/kyo/internal/tasty/query/PlatformFallback.scala`:
  returns `Binding.empty`.

Adding a new platform-specific capability requires a stub in all three leaves.
The stub on non-supporting platforms must raise `TastyError.UnsupportedPlatform`
or return a documented no-op; it must never throw.
