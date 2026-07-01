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
synchronous decode inside a `defer`). Five of those `Sync.Unsafe.defer` boundaries
back the public effect entry points below; the remaining `defer` blocks are
internal helpers under them. This list counts `Sync.Unsafe.defer` BOUNDARIES, not
public methods: `symbolsInFile`, `symbolsByName`, and `symbolsByPrefix` are pure
reads over the resident `Indices` maps through thin `classpath.map` delegators and
reach no boundary, while `symbolAt` and `references` share the one boundary at Site 5.
Three further sites live in `jvm/src/main` and carry their own `embrace.danger` proof.
Adding a new public entry point that introduces a `Sync.Unsafe.defer` boundary, or a
new platform-bridging site, is a contract violation and requires a plan change plus a
bump to this list.

### Five public effect entry points

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
- Test rule: never force the JVM `global` from a test. In the forked test JVM
  `java.class.path` is the full transitive test classpath; cold-loading all of it
  exhausts the test heap, so forcing `global` OOMs (the singleton then caches
  `Binding.empty`) or goes STUCK and times out, and the workaround that narrowed
  `java.class.path` mid-run also corrupted concurrently-loading suites. Tests MUST
  bind a fixture classpath via `Tasty.withClasspath` and never read `Tasty.global`
  or a scope-less `Tasty.classpath` / `Tasty.*` query on the JVM. The JVM scope-less
  fallback stays a production behavior; it is left unexercised by the suite. JS and
  Native may read `global` freely since it is the empty binding with no I/O.

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

**Site 5: `Tasty.symbolAt(position)` / `Tasty.references(symbol)` (shared)**

The symbol index's decode-backed pair shares ONE `Sync.Unsafe.defer` boundary
(`Tasty.occurrencesInFile`, the per-file occurrence resolver), mirroring `bodyTree`'s
synchronous decode. It carries no `embrace.danger`; the lazy TASTy decode runs under the
propagated `AllowUnsafe`. Both `symbolAt` and `references` route through this single shared
decode helper, so together they account for one boundary, not two. The three pure-index
lookups `symbolsInFile`, `symbolsByName`, and `symbolsByPrefix` hold no boundary: they are
pure reads over the resident `Indices` maps through thin `classpath.map` delegators and reach
no `Sync.Unsafe.defer`. Across the symbol-index query surface, then, three lookups are pure
reads with no boundary and the `symbolAt` / `references` pair shares this one.

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
the `jvm/src/main` site count is exactly 3. The observable contract of Sites 1-4 is
exercised by `InvariantsSpec` (`kyo-tasty/shared/src/test/scala/kyo/InvariantsSpec.scala:8-12`
enumerates exactly those four), which asserts each site's behavior (`Tasty.global` is a
stable lazy singleton; `Tasty.bodyTree` returns `Maybe.Absent` for a classpath installed
without a `DecodeContext`; and so on). Site 5's decode-backed pair is exercised by
`SymbolAtTest`, `ReferencesTest`, and `OccurrenceIndexTest`.

---

## Decode-error discrimination: MalformedSection, ClasspathClosed, graceful degrade

`Tasty.bodyTree` (`kyo/Tasty.scala:666-752`) decodes a body slice inside its
`Sync.Unsafe.defer` block and maps every failure mode to one of three outcomes on
the `Abort[TastyError]` row. All three must stay distinct:

- **`TastyError.MalformedSection`**, and only for corrupt input: a body whose
  `(bodyStart, bodyEnd)` bounds are malformed (validated up front at `Tasty.scala:694`,
  `b.bodyStart < 0 || b.bodyEnd > sectionLen || b.bodyStart > b.bodyEnd`), or a
  genuinely-malformed byte ENCODING caught as a `NonFatal` throwable (`Tasty.scala:734`,
  e.g. a `MalformedVarintException` where the varint guard fires on too many continuation
  bytes). Bounds are computed from the integers themselves rather than relying on a thrown
  `ArrayIndexOutOfBoundsException`, because Scala.js turns the same out-of-bounds read into
  an `UndefinedBehaviorError` (extends `java.lang.Error`, which `NonFatal` filters out) that
  escapes every catch arm.
- **`TastyError.ClasspathClosed`** when the backing mmap arena was already closed (the scope
  finalizer flipped the closed flag), detected via the `isArenaClosed(IllegalStateException)`
  predicate (`Tasty.scala:662-664`, matched at `Tasty.scala:721`).
- **Graceful degrade** to a top-level `Tree.Unknown(0, 0)` (a `Result.Success`) for a reader
  gap on IN-BOUNDS bytes: a `TreeUnpickler.DecodeException` (`Tasty.scala:715`), an
  `ArrayIndexOutOfBoundsException` from a nested read past its slice (`Tasty.scala:717`), or
  any NON-arena `IllegalStateException` (`Tasty.scala:725`). These are well-formed TASTy
  carrying a construct the reader does not yet model, or a cursor desync, not corrupt input;
  the README "Errors and diagnostics" contract is degradation, not abort. A non-arena
  `IllegalStateException` degrades here; it is NOT reported as `MalformedSection`.

Keep these arms separate when touching `bodyTree`: collapsing the closed-arena arm into the
degrade arm loses the distinction callers rely on, and promoting a reader-gap degrade into
`MalformedSection` mislabels a modelling gap as corruption.

`Tasty.occurrencesInFile` (`kyo/Tasty.scala:765-872`, the Site 5 shared boundary) is the
SECOND site running this exact discrimination, with the same five catch arms in the same
order (`Tasty.scala:844-859`). Two differences only: the degrade VALUE is an empty
`Chunk[Occurrence]` (`Tasty.scala:847-848, 853`) where `bodyTree` yields `Tree.Unknown`, and
the upfront bounds check (`Tasty.scala:788-790`) runs over EVERY body in the file before any
decode rather than one body. A non-arena `IllegalStateException` degrades to empty
(`Tasty.scala:853`), not `MalformedSection`; only bad bounds (`Tasty.scala:815-821`) and a
`NonFatal` throwable (`Tasty.scala:854-859`) reach `MalformedSection`. When editing either
site, edit both: it is one discipline at two call sites.

---

## SourceRange, Occurrence, and the 1-based position contract

`Tasty.SourceRange` (`kyo/Tasty.scala:1453-1462`) is the element type of `Tasty.references`:
a contiguous span within ONE source file,
`SourceRange(sourceFile, startLine, startColumn, endLine, endColumn)`. All four coordinates
are 1-based, matching `Tasty.Position`. The start `(startLine, startColumn)` is inclusive;
the end `(endLine, endColumn)` is end-exclusive (the 1-based column one past the last
character), so a half-open `[start, end)` reading maps onto an editor range. The end is read
directly from the TASTy Positions section, never reconstructed from a name length
(`Tasty.scala:1448-1449`). A single `sourceFile` for the whole span makes a cross-file range
unrepresentable by construction; equality is structural across all five fields (`derives
Schema, CanEqual`).

`Tasty.Occurrence` (`kyo/Tasty.scala:1470`, `final private[kyo] case class Occurrence(range:
SourceRange, symbolId: SymbolId)`) is the internal use-site carrier: a `SourceRange` plus the
`SymbolId` it resolves to. It is produced by `OccurrenceScanner.scanFile` and memoized per
file in `DecodeContext.occurrenceMemo`; it never reaches the public surface (`symbolAt`
returns `Maybe[Symbol]`, `references` returns `Chunk[SourceRange]`).

**Positions are 1-based; the `+1` conversion is the caller's job.** `symbolAt`'s documented
contract (`Tasty.scala:879-881`) states that the LSP 0-based wire position is converted with
`+1` at the call site, never inside kyo-tasty. This is a FORWARD contract, not an exercised
integration: no LSP call site into kyo-tasty exists in this tree. `kyo-lsp` depends only on
`kyo-jsonrpc`, not `kyo-tasty` (`build.sbt:1300`), and imports no `kyo.Tasty`; the
`.references(` / `.symbolAt(` names present in kyo-lsp are its own LSP-protocol client
methods, unrelated to these. kyo-tasty owns the 1-based invariant; the eventual consumer owns
the wire-offset conversion.

---

## OccurrenceScanner: use-site reference resolution

The lazy body decoder does NOT resolve use-site references to final classpath `SymbolId`s
(`OccurrenceScanner.scala:13-20`): a same-pickle reference decodes as
`Tree.TermRefDirect(address)` (a raw section-relative address, no `Type`), a member selection
as `Tree.Select(qualifier, name, Type.Wildcard)` (the `Select` carries no symbol info), and
any `Type.Named` a node holds is still `PHASE_B_ADDR_OFFSET`-encoded because the lazy decode
never runs `finalizeMerge`'s offset->final-id remap.
`OccurrenceScanner.scanFile` (`kyo/internal/tasty/query/OccurrenceScanner.scala`) resolves
each shape to a genuine final `SymbolId` itself:

- `TermRefDirect(address)` / `TermRefSymbol(address, _)`: through the load-populated final-id
  `addrMap`, `body.addrMap.get(body.sectionOffset + address)` (`OccurrenceScanner.scala:138-141`);
  the map's keys are absolute, `address` is section-relative, so `sectionOffset` is added.
- `Ident(_, Type.Named(id))`: the lazy remap mirroring `ClasspathOrchestrator.remapType`,
  `body.addrMap.get(id.value - phaseBOffset)` for a PHASE_B-encoded id
  (`OccurrenceScanner.scala:152-161`); an already-final id is kept, a negId dropped.
- `Select` / `SelectIn(qual, name, _)`: from the QUALIFIER's resolved type, never the
  (`Type.Wildcard`) `Select.tpe` (`OccurrenceScanner.scala:172-187`). The qualifier resolves
  to a symbol, its declared type widens to the class-like whose members are in scope (a type
  parameter widens to its upper bound; `classLikeOf`, `OccurrenceScanner.scala:271-293`), and
  the member is found via `classpath.findMember(_, _, MemberScope.All)`.
- A bare module qualifier (a top-level module selection like `Foo.bar`, decoded as
  `Ident(name, Named(id))` reconstructing the TASTy `TERMREFpkg` / `TYPEREFpkg` tags): resolved
  directly to its owning package by its fully-qualified name via `classpath.findPackage`, using
  `unresolvedIdToFullName` tracked on the lazy `TypeUnpickler.TreeTypeSession`
  (`OccurrenceScanner.scala:197-205`, `packageOwnerOf`; the tracker is
  `TreeTypeSession.unresolvedIdToFullName`, `TypeUnpickler.scala:130-146`, the
  lazy-body-decode counterpart of Pass 1's `DecodeSession.unresolvedIdToFullName`).
- A TYPE-position reference (a symbol used AS A TYPE: `val x: Foo`, `def f(a: Foo): Bar`, a `Foo`
  type argument) is a decoded `Type` in the file's `TreeTypeSession.addrCache`, surfaced by
  `decodeWithAddrs` keyed in the same address space and resolved by `resolveTypeUse`: a
  `Type.Named` resolves as `Ident` does, a `Type.TypeRef(qual, name)` as `Select` does (the
  qualifier's package/class container plus its member `name`), with the generic `Named(-1)`
  qualifier taken as the owner's enclosing package. An `extends`/`with` parent clause is stripped
  before the per-file body slice, so the scanner never sees it; instead the eager parent decode
  (`AstUnpickler.decodeTemplateParents`) records the parent type-ref addresses at cold load into
  `DecodeContext.parentOccurrenceStore` (keyed by pickle index, resolved to the final parent
  `SymbolId` in `finalizeMerge`), and `Tasty.occurrencesInFile` joins those addresses against the
  pickle's `PositionMap` and merges them into the occurrence index. A superclass relationship is
  therefore reported by source location as well as by symbol via `implementationsOf`/`parents`.

Every resolved id is bounds-checked against `classpath.symbols.size`, NOT a bare `id.value >=
0` (`OccurrenceScanner.scala:101`, `if id.value >= 0 && id.value < syms.size` where `syms =
classpath.symbols`, `OccurrenceScanner.scala:76`): a leaked PHASE_B temp id or an unresolved
cross-pickle negId is dropped, never emitted as an occurrence. An id that resolves but has no
Positions entry for its address (a synthetic node) is dropped too (`OccurrenceScanner.scala:105-106`).
`scanFile` runs pure under the propagated `AllowUnsafe`: the single `Sync.Unsafe.defer` is the
Site 5 boundary in the Tasty query layer, and this object adds no `embrace.danger`
(`OccurrenceScanner.scala:48-50`).

---

## INV-PRIVATE-NO-SHADOW: a private member never hides an inherited public one

`MemberScope.Inherited` and `MemberScope.All` drop a private own-declared member so it cannot
shadow a same-named inherited PUBLIC member; `MemberScope.Declared` keeps it. The consequence:
`MemberScope.All` is NOT a strict superset of `MemberScope.Declared`.

In `Classpath.members` (`kyo/Tasty.scala:4478-4497`), the `Inherited` arm builds its
`directNames` shadow-set skipping any private own declaration (`Tasty.scala:4494`, `if
!s.isPrivate then discard(directNames.add(s.simpleName))`), so an inherited public member with
that name survives the trailing `filter`. `allMembersOf` (backing `MemberScope.All`,
`Tasty.scala:4622-4641`) applies the same skip for BOTH its `seen` shadow-set and its output
(`Tasty.scala:4637-4639`, `if !d.isPrivate then ...`), so `All` emits the inherited public
member and omits the private own one. `Declared` (`Tasty.scala:4480-4483`) reads
`declarationIds` unfiltered, so it DOES include the private member. The cited shape is
`tasty-query#195` (`Tasty.scala:4492-4493`, `4635-4636`): a `Child(y: Int)` primary-constructor
param retained as a private field must never hide the inherited public `Parent.y`. This is why
`OccurrenceScanner.selectTarget` resolves a use-site selection with `MemberScope.All`: a `.y`
selection through an external reference must reach `Parent.y`, not the private ctor artifact.

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

`DecodeContext` (`kyo/internal/tasty/query/Binding.scala:36-52`) carries the decode-time
context needed to decode TASTy body bytes on demand. It is wrapped in
`Binding.decodeCtx` and is `Maybe.Absent` for a `Binding` built from a
pre-existing `Classpath` or from the empty fallback. Each `withClasspath` /
`withPickles` invocation creates a fresh `DecodeContext`, so memos are never shared
across calls. Its four fields:

- `bodyMemo: ConcurrentHashMap[SymbolId, Result[TastyError, Tree]]`: caches the
  decoded `Tree` (or its decode failure) per symbol. Populated by `bodyTree`.
- `bodyStore: ConcurrentHashMap[SymbolId, SymbolBody]`: raw body blobs populated
  during the merge. `bodyTree` reads it to locate the byte slice.
- `occurrenceMemo: ConcurrentHashMap[String, Chunk[Occurrence]]`: per-source-file lazy
  use-site occurrence cache, keyed by source-file path. Populated by `occurrencesInFile`.
- `positionsStore: ConcurrentHashMap[Int, Span[Byte]]`: per-PICKLE raw Positions-section
  byte slice retained at load, keyed by `pickleId` (Int), NOT by source-file path. Two
  top-level decls of one `.scala` compile to two `.tasty` pickles sharing one source file,
  each with its own Positions bytes and `sectionOffset`, so a String key would
  last-write-wins-collide (`Binding.scala:45-51`). `readSpans` runs lazily at first query;
  retaining the slice runs no decode at load.

**INV-MEMO-ASYMMETRY: `bodyMemo` caches every result; `occurrenceMemo` caches only
successes.** `bodyTree` writes EVERY result into `bodyMemo`, failures included
(`Tasty.scala:742`, `ctx.bodyMemo.put(symbol.id, result)` runs before the Success/Failure
split), so a deterministically-corrupt body re-aborts from the memo without re-decoding.
`occurrencesInFile` writes ONLY a `Result.Success` into `occurrenceMemo`
(`Tasty.scala:860-864`, where the `Result.Failure` arm aborts without a `put`), by deliberate
design (`Tasty.scala:759-761`): a deterministically-corrupt file re-decodes and re-aborts on
each query, keeping the cache free of poisoned entries and leaving a cancelled `references`
drain with a consistent partial cache (each file's entry is written whole or not at all).
Both caches sit on the same `DecodeContext`; when extending either, do not assume the other's
policy. The asymmetry is intentional, not an oversight.

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
- Symbol-index lookups (`symbolsInFile`, `symbolsByName`, `symbolsByPrefix`): pure `Sync`
  reads over the resident `indices` maps once a classpath is bound; no boundary.
- Symbol-index decode-backed queries (`symbolAt`, `references`): decode lazily through the
  shared `occurrencesInFile` boundary (Site 5) on every platform. Their bounds and
  decode-error discipline is platform-agnostic (see "Decode-error discrimination"). Both live
  in `shared/src/main`, as do `SourceRange`, `Occurrence`, and `OccurrenceScanner`.
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
