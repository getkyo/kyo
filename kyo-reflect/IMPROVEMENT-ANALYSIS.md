# kyo-reflect v2 Improvement Analysis

Companion document to `execution-plan-v2.md`. Records per-gap investigation findings before the plan was written.

---

## G13: Phase C UnresolvedRef placeholder resolution (DESIGN.md §15)

**What the design says**: Phase C iterates `result.placeholders`, looks up each FQN in the `fqnIndex`, and writes `Type.Named(resolvedSym)` into the `SingleAssign` slot inside `UnresolvedRef`. After Phase C, no `UnresolvedRef` survives in the symbol graph.

**Current state**: `AstUnpickler.Pass1Result.placeholders: Chunk[UnresolvedRef]` is populated during Pass 1 / TypeUnpickler decode (PROGRESS Phase 3 cleanup: "Pass1Result.placeholders now populated via typeSession.placeholders"). `UnresolvedRef.scala` defines `final case class UnresolvedRef(fqn: String, replaceSlot: SingleAssign[Reflect.Type])`. `ClasspathOrchestrator.mergeResults` builds the `fqnIndex` HashMap and calls `canonical.merge(fr.arena)` but NEVER iterates `pass1Result.placeholders`. There is no `result.placeholders` field on `FileResult`; the `FileResult` case class holds only `fqns`, `arena`, and `errors`. The `placeholders` produced by `AstUnpickler.readPass1` are discarded when `decodeTastyBytes` yields its result. No resolution code exists anywhere.

**Exact gap**: `decodeTastyBytes` must thread `pass1Result.placeholders` out into `FileResult`, and `mergeResults` must iterate them, resolve each against the merged `fqnIndex`, and write into `replaceSlot`. Five lines of code in `FileResult`, two lines in `decodeTastyBytes`, and a twelve-line loop in `mergeResults`.

**Impact on G20-G24**: Cross-file parent types (every class that inherits from a class in another file) are stored as uninitiated `SingleAssign` slots. Wiring G13 first means G21 (`parents`) can return real `Type.Named(sym)` references for cross-file parents, not broken slots.

---

## G20: Symbol.declaredType stub (Reflect.scala:244)

**Internal data, TASTy path**: `AstUnpickler` stores `bodyStart: Int` and `bodyEnd: Int` on each symbol via `Symbol.Origin.TastyOrigin`. The declared type (method signature or field type) is encoded in the body bytes for simple definitions. For `DEFDEF` and `VALDEF`, the type appears immediately before the body in the TASTy tree, so it is recoverable from the body-slice region. However, Pass 1 reads the type as a `TypeUnpickler` call for parent types; individual member types are recorded as part of the member's sub-tree which is currently skipped past. To surface `declaredType` without full tree body decode, the type byte range for each member must be eagerly read during Pass 1 (not deferred). This is achievable: the TASTy tag layout places the type immediately after the name+modifiers and before the body. Pass 1 can read the type for each `DEFDEF`/`VALDEF`/`TYPEDEF` member before recording the body slice.

**Internal data, classfile path**: `ClassfileUnpickler` produces each method/field `Symbol` with its `Reflect.Type` already decoded from the `Signature` attribute or field/method descriptor. The type is available in the `TypeArena` after Phase C. The gap is purely the wire-up: `Symbol.declaredType` must retrieve the canonical type from the symbol's internal type slot.

**Design decision**: For the classfile path, wire the type slot directly (no tree body decode needed). For the TASTy path, add an eager type-slice read in Pass 1 for member definitions, separate from the lazy body decode. This avoids G1 (full tree body decode) for the common `declaredType` case. G1 is only needed for body/expression decode (control flow, literals, etc.), not for the type signature.

**Conclusion**: G20 can be closed in v2 without G1. The TASTy pass must be extended to eagerly read the declared type per member.

---

## G21: Symbol.parents stub (Reflect.scala:252)

**Internal data, TASTy path**: Pass 1 already calls `TypeUnpickler.readType` for parent type positions in `CLASSDEF` templates. The parents are stored as `Chunk[Reflect.Type]` values (or `UnresolvedRef` placeholders for cross-file parents) in `Pass1Result`. Currently this data is in the `TypeArena` and in the per-file placeholder list, but is NOT stored on the `Symbol` itself. `Symbol` has no `parents` field; the data exists in the `TypeArena` but is unreachable after Phase C.

**Internal data, classfile path**: `ClassfileResult.parents: Chunk[Reflect.Type]` is populated (PROGRESS Phase 5 audit: "Phase 5 WARN drain: ClassfileResult.typeParams field added and populated from class-level Signature attribute"). Parents are in the result but are not threaded into the `Symbol`.

**Exact gap**: `Symbol` needs a `parents: Chunk[Reflect.Type]` slot (a `SingleAssign` or pre-populated field), populated during Pass 1 / Phase C from the already-decoded parent types. After G13 wires placeholder resolution, cross-file parents are resolved before being written to the slot.

**Conclusion**: G21 depends on G13 for correctness (cross-file parents). For same-file parents the data is already decoded; the gap is wiring it onto the Symbol.

---

## G22: Symbol.typeParams stub (Reflect.scala:260)

**Internal data, TASTy path**: `AstUnpickler.readPass1` allocates `TypeParam` symbols for each type parameter in generic classes and methods. These are children of the parent symbol in the owner chain. The parent symbol has no `typeParams` field; the TypeParam symbols are in `Pass1Result.symbols` but are not separately indexed by parent.

**Internal data, classfile path**: `ClassfileResult.typeParams: Chunk[Reflect.Symbol]` is explicitly populated ("ClassfileResult.typeParams field added and populated from class-level Signature attribute" per PROGRESS Phase 5). The data exists but is not threaded into the `Symbol`.

**Exact gap**: `Symbol` needs a `typeParams: Chunk[Symbol]` slot. For TASTy symbols, it is populated during Pass 1 by collecting TypeParam-kinded children immediately after creating their parent. For classfile symbols, the `ClassfileResult.typeParams` is assigned to the parent class symbol. No new decode logic needed; pure wiring.

**Conclusion**: G22 does not depend on G13. It is a pure wiring gap.

---

## G23: Symbol.declarations stub (Reflect.scala:268)

**Internal data, TASTy path**: Pass 1 allocates all member symbols and links each to its owner via `sym.owner`. The declarations of class `C` are all symbols in `Pass1Result.symbols` whose `owner eq C`. They are not indexed separately per owner.

**Internal data, classfile path**: `ClassfileResult.symbols: Chunk[Reflect.Symbol]` holds all field and method symbols. The class symbol is `ClassfileResult.classSymbol`. These are the declarations.

**Exact gap**: `Symbol` needs a `declarations: Chunk[Symbol]` slot. For TASTy symbols, populate it during Pass 1 by appending each allocated member to its owner's declaration list (using a per-symbol `ChunkBuilder`). For classfile symbols, assign `ClassfileResult.symbols` directly. No new decode needed.

**Conclusion**: G23 does not depend on G13.

---

## G24: Symbol.companion stub (Reflect.scala:276)

**Internal data**: There is no companion field anywhere internally. In TASTy, companion objects are encoded as sibling symbols at the same package level: a `class Foo` companion `object Foo` is a `SymbolKind.Object` symbol with the same simple name but `Flag.Module` set. In classfiles, the companion is the `$` class or the class without `$`.

**Exact gap**: After Phase C builds the `fqnIndex`, a companion lookup is a pure FQN computation: given `sym.fullName.asString`, look up `fqn + "$"` (module companion) or strip `"$"` (class companion). This is an O(1) HashMap lookup against the already-built `fqnIndex`. No new decode needed. The `companion` accessor performs this lookup via `home.checkOpen` + `fqnIndex.get(companionFqn)`.

**Conclusion**: G24 depends on Phase C completing (so `fqnIndex` is populated) but otherwise needs no new data. It is a simple accessor implementation.

---

## G14: BODY_BYTES KRFL section absent

**Current state**: `SnapshotWriter.serialize` explicitly sets `bodyBytes = Array.empty[Byte]` (line 118) and `SnapshotReader` never reads the `BODYBYTE` section. Body slices are stored on `Symbol.origin: TastyOrigin(bodyStart, bodyEnd)` referencing offsets in the heap-resident `Array[Byte]` from the original TASTy parse. When a snapshot is loaded, there are no original bytes; `bodyStart`/`bodyEnd` are meaningless. This prevents lazy body decode on snapshot-loaded symbols.

**What is needed**: The BODY_BYTES section must contain the concatenation of all body slices from all TASTy files, with each symbol's `(bodyStart, bodyEnd)` rewritten to offsets within this section. On load, `SnapshotReader` maps the section into a single byte array and restores each symbol's origin with the correct offsets.

**Dependency**: G14 is needed before G1 (tree body decode) can work on snapshot-loaded symbols. G14 is also a prerequisite for G20 (declaredType from snapshot) and G21 (parents from snapshot) if those are implemented via body-slice re-decode rather than direct field storage.

**Decision**: G20/G21/G22/G23 store their data as direct fields (`Chunk[Type]`, `Chunk[Symbol]`), not body-slice pointers. This means G14 is only needed for full tree body decode (G1) and G3 (comments). G14 is implemented after G1 since it serves G1's lazy decode path.

---

## G15: inputDigest field always zeros

**Current state**: `SnapshotWriter.serialize` calls `assembleSections(sections, digest = Array.empty[Byte])` (line 132). The `write` method receives the correct `digest: Array[Byte]` parameter but does not thread it into `serialize`. `assembleSections` checks `if digest.length >= 8 then copy else zeros`. The 32-byte `inputDigest` header field is always zeros.

**Impact**: Snapshot filenames are derived from the correct digest (computed externally by `DigestComputer` and passed to `write`). Cache invalidation via filename is correct. An in-header digest check (if ever added) would always see zeros. Snapshot readers that validate the header digest would reject all existing snapshots when the fix is applied (bumping the minor version handles this gracefully per the versioning policy).

**Fix**: Thread `digest` from `write` into `serialize` and from `serialize` into `assembleSections`. One-line fix in `serialize` signature; one-line fix in its caller. Minor-version bump required.

**Dependency**: None (standalone fix).

---

## G16: JVM MemorySegment mmap absent

**Current state**: `JvmFileSource.read` uses `java.nio.file.Files.readAllBytes`, loading the snapshot into a heap `Array[Byte]`. DESIGN.md §16 specifies `FileChannel.map(MapMode.READ_ONLY, 0, size)` or JDK 22+ `MemorySegment` via `Arena.ofShared.allocate(...)`.

**What is needed**: A JVM-specific `SnapshotReader` path that uses `java.lang.foreign.MemorySegment` (JDK 22+ API, available on Temurin 25 per project requirements). The `ByteView` sealed hierarchy already has a stub `Mapped` case (defined in DESIGN.md §16; FINAL-AUDIT §6 notes it as present in the ByteView design). Verify whether `ByteView.Mapped` was implemented or only the `Heap` case. If `Mapped` is absent, it must be added to `ByteView` and the JVM-specific reader must produce `Mapped` instances. The `Arena` (or `MemorySegment`) is closed via `Scope.ensure` when the enclosing scope exits.

**Performance impact**: For a 5 MB snapshot, mmap vs `readAllBytes`: startup saves ~10ms, demand-paging means BODY_BYTES section is never loaded for codegen workloads.

**Dependency**: G16 depends on G14 (BODY_BYTES section) for its main benefit (demand-paging bodies). G16 is logically independent of G14 (mmap can be added without BODY_BYTES), but the ordering is G14 then G16 because G16's performance case needs G14.

---

## G17: Native POSIX mmap absent

**Current state**: `NativeFileSource.read` uses POSIX `read(2)` into `Array[Byte]`. DESIGN.md §16 specifies POSIX `mmap()` FFI for the snapshot case.

**What is needed**: An additional FFI binding for `mmap(2)` and `munmap(2)` in `NativeFileSource` or a dedicated `NativeSnapshotReader`. The `mmap` call returns a `Ptr[Byte]` that is wrapped in a `ByteView.Mapped` (Native-specific subtype). `munmap` is called via `Scope.ensure` on scope exit.

**Dependency**: Same as G16 - depends on G14 conceptually; can be ordered after G16 for platform symmetry.

---

## G18: Benchmarking absent (DESIGN.md §20)

**Current state**: No benchmark harness exists. DESIGN.md §20 describes six workloads comparing kyo-reflect vs tasty-query on JVM.

**Smallest plausible scope**: kyo does not have a project-wide JMH setup visible in the repository. A `System.nanoTime`-based micro-bench in a dedicated `kyo-reflect-bench` module (JVM only) is sufficient. It must: (a) open a fixed fixture classpath (the `kyo-reflect-fixtures` TASTy files), (b) time cold decode, (c) time snapshot warm hit, (d) compare against tasty-query if available. The bench does NOT need to be in the test suite. It is a runnable main class in a separate module.

**Decision**: Use `System.nanoTime` micro-bench, not JMH. JMH would require adding a significant build plugin dependency not present elsewhere in the project. `System.nanoTime` with multiple iterations and warm-up passes is adequate for order-of-magnitude comparison. The bench module has one runnable main class and no test assertions.

---

## G19: ReflectError.InconsistentClasspath UUID type

**Current state**: `ReflectError.InconsistentClasspath` uses `(String, String)` for the two UUID fields (the file path and expected/found UUIDs both as `String`). DESIGN.md §12 shows:
```scala
case InconsistentClasspath(file: String, expectedUuid: UUID, foundUuid: UUID)
```
using `java.util.UUID` for the two UUID fields. The impl used `String` for serialization safety.

**Breaking change assessment**: `InconsistentClasspath` is part of the sealed `ReflectError` enum. Changing `(String, String)` UUIDs to `(String, UUID, UUID)` is a binary-breaking change for callers that pattern-match on `InconsistentClasspath`. Since kyo-reflect is pre-1.0 and no downstream migration exists, the change is acceptable. The migration step: change the two `String` fields to `java.util.UUID` at the definition site; update every construction site. `InconsistentClasspath` is currently never constructed anywhere in the codebase (it is defined but unused -- the classpath consistency check that would produce it is not yet implemented). The UUID change is therefore a pure definition change with zero construction-site updates required.

**Note on `java.util.UUID` cross-platform**: `java.util.UUID` is available on JVM. On Scala.js and Scala Native, `java.util.UUID` is provided by the standard library compatibity layer (Scala.js std library and Scala Native's Java stdlib). Verified safe for cross-platform use.

---

## G1: Tree body / AST decoding

**Scope**: Decode the body byte slice (stored on `Symbol.origin.TastyOrigin.bodyStart/End`) into a `Tree` ADT. The `Tree` ADT is a new type (not yet defined). This is the largest v2 item because the TASTy AST tag set covers ~100 tag categories. Full body decode is needed for: control flow analysis, constant folding, inline expansion, and any tool that needs to inspect method bodies. For kyo-ts codegen purposes, `declaredType` (G20) is sufficient and does not require G1.

**Dependency**: G1 requires G20 (so that type references inside tree nodes resolve correctly). G1 requires a new `Tree` ADT definition. G1 gates G2 (positions use tree-node source ranges) and G3 (comments attach to tree nodes).

---

## G2: Position section reader

**Current state**: The `Positions` section is skipped in v1. TASTy positions encode line/column spans per node using delta-encoded address pairs.

**What is needed**: A `PositionUnpickler` that reads the Positions section and produces a `Map[Int, (Int, Int)]` mapping tree-node address to `(line, column)`. The `Symbol` would gain a `position: Maybe[(Int, Int)]` pure accessor (or `(line, column, sourceFile)` triple). No new effect needed.

**Dependency**: G2 depends on G1 because positions attach to tree nodes; without the tree node addresses, there is nothing to map positions to.

---

## G3: Comments section reader

**Current state**: The `Comments` section is skipped in v1. TASTy comments encode scaladoc text per symbol address.

**What is needed**: A `CommentsUnpickler` that reads the Comments section and produces a `Map[Int, String]` mapping symbol address to scaladoc text. The `Symbol` would gain a `scaladoc: Maybe[String]` pure accessor (data is pre-decoded, no effect needed).

**Dependency**: G3 depends on Pass 1 having the symbol address map (available from Phase 3), but does NOT depend on G1 (tree body decode). Comments section is indexed by definition address, not tree-node address. G3 can be implemented independently of G1/G2.

---

## G4: Scala 2 pickle reader

**Scope**: Reads the binary pickle format from `.class` files compiled by Scala 2. Scala 2 pickles are stored in the `ScalaSig` attribute (compact) or as a separate `.sig` file in some tools. The format is documented at scala/scala: `scala.reflect.internal.pickling`.

**Dependency**: G4 requires the classfile reader (Phase 5 already present) to locate the `ScalaSig` attribute. G4 is otherwise independent.

---

## G5: Subtype checking and type comparison

**Scope**: `Type.isSubtypeOf(other: Type): Boolean` plus structural normalization beyond current smart constructors. Requires handling of: variance, type bounds, type lambdas, recursive types (Rec/RecThis), existential types (Wildcard bounds).

**Dependency**: G5 requires the canonical type arena (Phase C) to be complete so type references are resolved. G5 can be implemented after Phase C is correct.

---

## G6: Java module-info.class (JPMS)

**Scope**: Parse `module-info.class` files to extract module name, requires, exports, opens, uses, provides directives. Surface as a new `ModuleDescriptor` type with a new entry point `Classpath.findModule(name)`.

**Dependency**: G6 requires the classfile reader (Phase 5) for the binary format. JPMS module-info uses a specialized constant-pool subset and the `Module` attribute. G6 is otherwise independent.

---

## G7: TASTy writing (explicit non-goal)

**DESIGN.md §1**: "Write side. No TASTy production, no classfile production." This is a hard non-goal per the architecture: kyo-reflect is a reader. TASTy writing would invert the read-only design (the library holds heap-resident `Array[Byte]` body slices, not a structured tree that could be re-serialized). No phase addresses this.

---

## G8: Multi-Scala-version support (v3 non-goal)

**DESIGN.md §24**: "Multi-Scala-version support in one release." kyo-reflect pins to one Scala minor version per release. Supporting multiple TASTy versions simultaneously requires version-dispatched readers, a version-union type model, and separate fixture sets. Non-goal for v2 per DESIGN.md.

---

## G9: Incremental classpath refresh (v3 non-goal)

**DESIGN.md §24 and Phase 7 plan**: "Incremental classpath refresh (today: open a new Classpath)." Full-digest strategy (FNV-1a of all input mtimes+sizes) is provably correct. Incremental would require per-file invalidation, cascading cross-file type reference re-resolution, and partial-arena merges. Non-goal for v2.

---

## G10: Phase C sharding for very large monorepos (v3 non-goal)

**DESIGN.md §24**: "Phase C sharding for very large monorepos." Phase C is currently single-threaded merge. For ~10K files it could take ~500ms. Sharding the merge is a v3 optimization. Non-goal for v2.

---

## G11: Hand-written Reads instances participating in touchedFields

**DESIGN.md §24**: "Hand-written `Reads` instances participating in `touchedFields` optimization." Currently, hand-written `Reads` instances default to `FieldSet.All` (conservative); the macro cannot inspect their bodies. The optimization would require either: (a) a DSL for declaring `touchedFields` in hand-written instances (a method override that returns a compile-time constant), or (b) a macro that analyzes hand-written instance bodies similarly to the derived macro.

**Decision**: Implement as a compile-time-safe explicit `touchedFields` declaration on hand-written instances via a companion helper. No body analysis of hand-written code. This avoids the unreliable macro-body-inspection path.

---

## G12: C/C++ header parsing (explicit non-goal)

**DESIGN.md §25**: Separate `kyo-cbindings` sibling module. Uses libclang which is JVM+Native only. kyo-reflect must remain dep-free and cross-platform. No phase addresses this.

---

## AllowUnsafe comment cleanup (FINAL-AUDIT W1)

Five sites in `Classpath.scala` (lines 73, 125, 132), `ClasspathOrchestrator.scala` (line 200), and `SnapshotWriter.scala` (line 60) are missing the `// Unsafe:` prefix comment. This is a cleanup item, not a gap in functionality.

## Resolver dead code (FINAL-AUDIT W4+W5)

`Resolver.scala` with `Cache.memo`-based deduplication is never called. `Classpath.lookupClass` does a direct HashMap read. For the v2 plan, the Resolver is wired before the accessors that depend on it (G21-G24 call `home.resolve`). After the wire-up, `SymbolResolutionTest` test 19 can be strengthened to `sym1 eq sym2`.

## findClassByBinary inline vs FqnCanonicalizer (FINAL-AUDIT W3)

The inline `replace('/', '.').replace('$', '.')` at `Reflect.scala:455` vs `FqnCanonicalizer.toFullName`. The inner-class table is not available at the extension-method level. For v2, this divergence is documented as an accepted deviation in PROGRESS.md; no code change is needed because the divergence only affects anonymous/local class names containing `$1LocalClass`-style suffixes, which are not addressable via `findClassByBinary` anyway.
