# kyo-tasty Design

A from-scratch compile-time reflection library: reads Scala 3 TASTy files and Java classfiles, exposes them through a unified Symbol/Type API, all under Kyo effects. Cross-platform (JVM, JS, Native). Replaces tasty-query for Scala-side use and `scala.reflect.runtime` / `java.lang.reflect` for compile-time tooling that doesn't want runtime classloading.

Targets two use shapes from day one: code generation (whole-classpath enumeration, as in `kyo-ts` today) and IDE-style symbol queries (per-FQN lookup, fast cold start). Both Scala and Java symbols flow through the same API.

This document is the source of truth. The four core design choices in Section 22 are LOCKED after exploration; implementation can proceed from the skeleton phase.

## 1. Goals

* **Unified Java + Scala symbol model**: one `Symbol` type, one `Type` ADT covering both languages. Java symbols are first-class, not second-class wrappers.
* Read TASTy files produced by Scala 3 (pinned to a specific compiler version per release).
* Read Java `.class` files for cross-language symbol resolution (`java.lang.*`, third-party Java deps, the JDK).
* Read Scala 2 pickles embedded in Java classfiles (`ScalaSig` attribute).
* Surface symbols, types, and definitions through a Kyo-shaped API: `Sync`, `Abort[TastyError]`, `Scope`, no exceptions, no `using Context` propagation.
* Single artifact published for JVM, JS, and Native.
* Provide a unified Symbol/Type API accessible on all three platforms.

## 1a. Performance targets

* Materially better cold-load performance than tasty-query on the kyo-ts workload (3 to 5x on a 30 to 50 module classpath).
* Better warm performance via persistent snapshot cache (measured by kyo-tasty-bench).

## Non-Goals (v1)

* Write side. No TASTy production, no classfile production.
* Scala 2 pickle reader.
* C/C++ header parsing (deferred to a future sibling module, see Section 25).
* TASTy-based macro expansion or type checking.
* Runtime classloading (we read bytes from disk, never load classes).
* Source positions (`Positions` section) and comments.
* Tree body decoding for control flow. Bodies remain as opaque `Span[Byte]` slices until v2; signatures and member structure are fully decoded.
* Subtype checking and full type comparison beyond structural equality.
* Multi-Scala-version support in one release.
* Incremental classpath refresh (today: open a new `Classpath`).
* Java module-info.class metadata. JPMS modules are out of scope; we read classes from JARs regardless of module declarations.

## 2. Performance Targets (calibrated)

Honest numbers from the prior-art analysis (Section 23). Object-graph design with parallel decode, lazy bodies, hash-consed types, sharded intern, and persistent snapshot cache.

| Workload | tasty-query baseline | kyo-tasty target | Lever |
|---|---|---|---|
| Cold-load 50-module classpath | 500 to 800 ms | 100 to 200 ms (3 to 5x) | parallel decode + lazy bodies |
| Warm reload (snapshot cache hit, JVM mmap) | n/a | 5 to 15 ms (35 to 50x) | mmap + demand paging |
| Warm reload, JS no-mmap | n/a | 50 to 150 ms (5 to 10x) | read-into-Array fallback |
| Per-FQN lookup, warm | sub-ms | sub-ms (parity, dedup-via-Promise wins under concurrent calls) | `Cache.memo` |
| Symbol-node memory | baseline | 2 to 3x lower | hash-consed types, sharded intern, no parent pointers |
| JS / Native | does not run | runs | cross-platform ground-up |

SoA (struct-of-arrays) was considered and rejected; see Section 23. The wins come from parallel decode + lazy bodies + snapshot cache, not from layout.

## 3. Architectural Overview

```
                  +---------------------------------+
                  |       kyo.Tasty (public)          |
                  |  Classpath, Symbol, Type, Name  |
                  |  Query combinators, Reads       |
                  +---------------------------------+
                              |
                  +---------------------------------+
                  |    kyo.Tasty.Reads (macros)       |
                  |  derives, schema-driven reader  |
                  +---------------------------------+
                              |
                  +---------------------------------+
                  |   kyo.internal.tasty (query)      |
                  |  symbol resolver, type intern   |
                  |  per-thread arenas + merge      |
                  |  symbol cache (Cache.memo)      |
                  +---------------------------------+
                              |
        +---------------------+---------------------+
        |                                           |
  +-------------+                            +-------------+
  | TASTy unpickler |                        | classfile unpickler |
  +-------------+                            +-------------+
                              |
                  +---------------------------------+
                  |    binary primitives (shared)   |
                  |  ByteView, Varint, Utf8         |
                  +---------------------------------+
                              |
                  +---------------------------------+
                  |    snapshot reader / writer     |
                  |  KRFL format, read-into-Array   |
                  +---------------------------------+
                              |
                  +---------------------------------+
                  |  file source (per platform)     |
                  |  uniform: read all bytes        |
                  +---------------------------------+
```

Three layers compose cleanly: binary primitives (pure byte arithmetic, no effects), unpicklers (read bytes into raw structures, `Sync & Abort[TastyError]`), query layer (cached symbol graph, public API).

## 4. Module Layout

Cross-project with `JVMPlatform`, `JSPlatform`, `NativePlatform` (same pattern as `kyo-direct`, `kyo-stm`, `kyo-actor` in `build.sbt`). Depends on `kyo-core`.

```
kyo-tasty/
  shared/src/main/scala/kyo/
    Tasty.scala                    // entry object, public types nested
    TastyError.scala               // closed error ADT (public)
  shared/src/main/scala/kyo/internal/tasty/
    binary/
      ByteView.scala               // Span[Byte] + offset cursor (no effects)
      Varint.scala                 // LEB128 decode
      Utf8.scala                   // bytes -> String, allocation-free path
    tasty/
      TastyFormat.scala            // tag constants, version constants
      TastyHeader.scala            // magic, version, UUID
      NameUnpickler.scala          // name table section
      AttributeUnpickler.scala     // attributes section
      AstUnpickler.scala           // skeleton pass + lazy body pickles
    classfile/
      ClassfileFormat.scala
      ConstantPool.scala
      ClassfileUnpickler.scala
      JavaSignatures.scala         // generic signature parser
    symbol/
      SymbolKind.scala             // enum
      Flags.scala                  // bit-packed Long
      Symbol.scala                 // single concrete type, object-graph
      JavaMetadata.scala           // Java-specific side data
      Annotation.scala             // Scala annotation model
      Constant.scala               // constant types
      Interner.scala               // sharded name intern
    type_/
      Type.scala                   // type ADT
      TypeArena.scala              // per-thread hash-cons + merge
      TypeOps.scala                // normalization helpers
    query/
      Classpath.scala              // file index, per-file loader, symbol cache
      Resolver.scala               // FQN -> Symbol via Cache.memo
      FileSource.scala             // abstract file loader (per platform impl)
    reads/
      Reads.scala                  // Tasty.Reads typeclass
      ReadsMacro.scala             // derivation macro
      RecordReads.scala            // built-in Reads[Record[F]] + symbolToRecord macro
      TouchedFields.scala          // static analysis helper + FieldSet
    snapshot/
      SnapshotFormat.scala         // KRFL binary layout
      SnapshotReader.scala
      SnapshotWriter.scala
  jvm/src/main/scala/kyo/internal/tasty/
    JvmFileSource.scala            // java.nio.file + jrt:/
    JvmClasspathScanner.scala      // jar walking, .tasty extraction
  js/src/main/scala/kyo/internal/tasty/
    JsFileSource.scala             // node:fs (browser path documented)
    JsClasspathScanner.scala
  native/src/main/scala/kyo/internal/tasty/
    NativeFileSource.scala         // POSIX open/read via FFI
    NativeClasspathScanner.scala
  shared/src/test/scala/kyo/
    TastyFormatTest.scala
    NameUnpicklerTest.scala
    SymbolResolutionTest.scala
    TypeModelTest.scala
    QueryApiTest.scala
    ReadsDerivationTest.scala
    RecordInteropTest.scala
    JavaSymbolTest.scala
    UnifiedModelTest.scala
    SnapshotRoundTripTest.scala
```

Package convention follows `feedback_kyo_package`: `kyo` exports public types; everything else lives in `kyo.internal.tasty.*`. Lowercase nested namespace objects per `feedback_lowercase_namespace_objects`.

## 5. Binary Primitives

### ByteView

```scala
final class ByteView(val bytes: Array[Byte], val start: Int, val end: Int):
    def peekByte(at: Int): Byte
    def readByte(): Byte             // mutable cursor wrapper used in parsers
    def readNat(): Int               // LEB128 unsigned
    def readInt(): Int               // signed
    def readLongNat(): Long
    def readEnd(): Int               // length + computed end address
    def subView(from: Int, until: Int): ByteView
    def goto(addr: Int): Unit
```

A thin wrapper over `Array[Byte]` with offset and end bounds. Slicing is zero-copy (same underlying array). Cursor mutation lives in a parser-local instance; outside the parser, `ByteView` is effectively immutable.

Why `Array[Byte]` and not `Memory[Byte]` from `kyo-offheap`: `kyo-offheap` is JVM+Native only (verified in `build.sbt:434-445`). For three-platform support we use `Array[Byte]` uniformly. `Span[Byte]` is `opaque type Span[+A] = Array[? <: A]` in kyo-data, so the public API exposes `Span[Byte]` while internals work with `Array[Byte]` (same runtime representation, zero conversion cost).

Why not mmap: the Scope investigation (Section 15) showed `Scope` is fiber-shared across `Async.foreach` workers, so mmap handles held by parallel decoders all release together at outer-scope exit. At classpath scale this risks FD exhaustion. Reading whole files into `Array[Byte]` with per-file inner `Scope.run` bounds handle lifetime per file. The mmap perf delta is ~50us per file (10ms total for 200 files); not material.

### Varint

LEB128 unsigned (`readNat`) and signed (`readInt`). Tight loop over `ByteView`. ~30 LOC.

### Utf8

Bytes-to-`String` decode. Lazy: only invoked when the user calls `.toString` on a `Name`. Internally, names compare via `Arrays.equals` on byte slices, no `String` allocation. JVM uses `new String(bytes, off, len, UTF_8)`; JS uses `TextDecoder`; Native uses stdlib UTF-8.

## 6. Binary Format Layer

### TASTy header

`TastyHeader.scala` reads the 4-byte magic (`0x5CA1AB1F`), `(major, minor, experimental)` version triple, tooling-version UTF-8, and 16-byte UUID. Version policy matches the compiler: `pickle.major == kyoTasty.major && (pickle.experimental == 0 || pickle.experimental == kyoTasty.experimental) && pickle.minor <= kyoTasty.minor`. Failure produces `TastyError.UnsupportedVersion(pickle, supported)`. UUID surfaces as `Pickle.uuid` for callers that want to detect classpath inconsistency.

### Name table

Eager full decode on first file touch. Entries land in an `Array[Name]` indexed by `NameRef`. Backed by the sharded intern table (Section 8) so the same logical name across two files shares one `Name`.

### Sections

`AttributeUnpickler` is small but mandatory (Scala 3.3+). Controls type interpretation: `explicitNulls`, `captureChecked`, `isJava`, `isOutline`, `scala2StandardLibrary`, `sourceFile`. Stored on per-file metadata.

`Positions` and `Comments` sections: skipped in v1.

### AST unpickling

**Strategy: skeleton-eager + bodies-lazy via length-prefix skipping.**

TASTy tag categories 128 to 255 are length-prefixed. Any compliant reader can structurally skip a node without understanding it. We exploit this:

1. **Pass 1 (eager)**: walk the AST section, allocate one `Symbol` per definition (`PACKAGE`, `CLASSDEF`, `TYPEDEF`, `VALDEF`, `DEFDEF`). For each definition, eagerly decode name, flags, type signature, parents (for classes), member name list (one level deep). Bodies of `DEFDEF` and class bodies past the member name list are recorded as `(startAddr, endAddr)` slices and skipped via length-prefix.

2. **Pass 2 (lazy, on demand)**: `Symbol.body` accessor decodes its pickled slice into a `Tree`. The `Tree` ADT is deferred to v2; in v1 the accessor is a stub that returns `Abort.fail(TastyError.NotImplemented("tree body decode deferred to v2"))`. Typically never triggered for codegen workloads, which use only signatures and member metadata.

Better than tasty-query's full-eager strategy on the kyo-ts workload because we never decode the bodies of the thousand-plus methods we ignore. Better than the compiler's `LazyType` completers because there is no re-entrancy hazard: symbols are fully created in pass 1, just with body pickles deferred.

Forward references inside signatures (`class C[T1 <: T2, T2]`) resolve via an `Addr -> Symbol` table built during pass 1.

`SHAREDtype` / `SHAREDterm` dedup uses a per-file `Addr -> Type` cache so shared sub-trees decode once.

### Reads-driven pruning

When a `Tasty.Reads[A]` schema declares `needsBodies = false` and `touchedFields` excludes annotations / positions / comments, the unpickler skips those sections entirely. The macro emits this hint at compile time; the unpickler reads it at load time.

If multiple schemas are active in the same session, the unpickler takes the union of their `touchedFields` and `needsBodies` requirements. If any schema needs bodies, all bodies decode lazily but are decodable.

## 7. Symbol Model

One concrete `final class Symbol`. No subtype hierarchy. Following Roslyn's lesson (Section 23): no parent pointers, no source positions on the hot symbol class. Position data, if needed in the future, goes in a side table.

```scala
final class Symbol private[kyo] (
    val kind:   SymbolKind,
    val flags:  Flags,
    val name:   Name,
    val owner:  Symbol,
    private[kyo] val home: Classpath,    // bound to the classpath that decoded this symbol
    private val origin: Symbol.Origin
):
    // Pure accessors: decoded into the symbol record at unpickle time, always present.
    // Work after classpath close (they're just data).
    def fullName:        Name           // dotted: "java.util.Map.Entry", "scala.collection.immutable.List"
    def binaryName:      String         // JVM internal form: "java/util/Map$Entry", "scala/collection/immutable/List"
    def isInline:        Boolean        // Scala only; false for Java symbols
    def isContextual:    Boolean        // Scala only
    def isOpaque:        Boolean        // Scala only
    def isPackageObject: Boolean        // Scala only
    def isModule:        Boolean        // Scala only (true for object companion classes)
    def isJava:          Boolean        // shorthand for flags.contains(Flag.JavaDefined)

    // Resolving accessors: follow cross-file references via home.
    // Fail with TastyError.ClasspathClosed if home has been closed.
    def declaredType: Type                < (Sync & Abort[TastyError])
    def parents:      Chunk[Type]         < (Sync & Abort[TastyError])
    def typeParams:   Chunk[Symbol]       < (Sync & Abort[TastyError])
    def declarations: Chunk[Symbol]       < (Sync & Abort[TastyError])
    def companion:    Maybe[Symbol]       < (Sync & Abort[TastyError])

    // Java-specific metadata side door (Present only when isJava == true).
    // Holds data that doesn't fit the unified Scala model: throws clauses,
    // raw annotation parameter values, EnclosingMethod attribute, etc.
    def javaSpecific: Maybe[JavaMetadata]
end Symbol

final case class JavaMetadata(
    throwsTypes:      Chunk[Type],                  // declared throws clauses on methods
    annotations:      Chunk[JavaAnnotation],        // runtime-visible + invisible
    enclosingMethod:  Maybe[(Symbol, Name)],        // for anonymous/local classes
    accessFlags:      Int,                          // raw JVM access_flags bitmask
    recordComponents: Chunk[(Name, Type)]           // Java 14+ records; empty unless Flag.JavaRecord set
)

final case class JavaAnnotation(
    annotationClass: Symbol,
    values:          Map[Name, JavaAnnotation.Value]
)
object JavaAnnotation:
    enum Value:
        case StringVal(s: String)
        case IntVal(i: Int)
        case LongVal(l: Long)
        case BoolVal(b: Boolean)
        case ClassVal(tpe: Type)
        case EnumVal(enumType: Symbol, constant: Name)
        case ArrayVal(elements: Chunk[Value])
        case AnnotationVal(nested: JavaAnnotation)

enum SymbolKind:
    case Package, Class, Trait, Object, Method, Field, Val, Var,
         TypeAlias, OpaqueType, AbstractType, TypeParam, Parameter,
         Unresolved   // sentinel for cross-file refs whose target file is absent
```

### Scala / Java SymbolKind matrix

Which kinds fire for symbols from each source:

| Kind | Scala (TASTy) | Java (classfile) | Notes |
|---|---|---|---|
| Package | yes | yes | Both have packages. |
| Class | yes | yes | Java `class` and Scala `class`. |
| Trait | yes | yes | Java `interface` maps to Trait. |
| Object | yes | no | Scala `object`. Java has no equivalent. |
| Method | yes | yes | Both have methods. |
| Field | yes | yes | Both have fields (Scala: backing fields for val/var). |
| Val | yes | maps from final | Java `final` field with no setter maps to Val. |
| Var | yes | maps from mutable | Java mutable field maps to Var. |
| TypeAlias | yes | no | Scala `type X = Y`. |
| OpaqueType | yes | no | Scala `opaque type`. |
| AbstractType | yes | no | Scala abstract type members. Java has no abstract type members. |
| TypeParam | yes | yes | Both have generics. |
| Parameter | yes | yes | Method/constructor parameters. |
| Unresolved | yes | yes | Soft-fail partial classpath sentinel. |

Scala-only kinds simply do not appear in Java-sourced symbols. Callers matching on `SymbolKind` for cross-language code should expect the union; for Java-only code they can ignore the Scala-only cases.

### Java records (Java 14+)

Java records compile to regular classes with three distinguishing features:

* The `RECORD` bit set in `access_flags` (we extract it as `Flag.JavaRecord`).
* A `Record` JVM attribute listing each component's name and descriptor.
* Synthetic accessor methods (one per component) and a canonical constructor matching the components.

Surface in kyo-tasty (public API):

* `SymbolKind.Class` with `Flag.JavaRecord` set.
* Standard `declarations` include the accessor methods and the canonical constructor.
* Component metadata exposed via `Symbol.javaSpecific.get.recordComponents: Chunk[(Name, Type)]` (a new field on `JavaMetadata`).

```scala
// Updated JavaMetadata in Section 7:
final case class JavaMetadata(
    throwsTypes:      Chunk[Type],
    annotations:      Chunk[JavaAnnotation],
    enclosingMethod:  Maybe[(Symbol, Name)],
    accessFlags:      Int,
    recordComponents: Chunk[(Name, Type)]      // empty unless flags.contains(Flag.JavaRecord)
)
```

### Scala 3 enum mapping

Scala 3 `enum` declarations compile to a sealed abstract class plus cases. The TASTy representation:

* The enum itself: `CLASSDEF` with `Flag.Sealed | Flag.Enum`.
* Cases with parameters: `CLASSDEF` with `Flag.Case | Flag.Enum`, parent set to the enum.
* Cases without parameters: `VALDEF` with `Flag.Case | Flag.Enum`, lifted to the enum's companion object.
* Generated `values: Array[E]` and `valueOf(name): E` methods on the companion.

Surface in kyo-tasty (covered by existing `SymbolKind` and `Flag` combinations, no new cases needed):

| TASTy emission | SymbolKind | Flags |
|---|---|---|
| `enum Color` | `Class` | `Sealed`, `Enum`, `Abstract` |
| `case Red, Green` (no params) | `Val` | `Case`, `Enum` |
| `case RGB(r,g,b)` (with params) | `Class` | `Case`, `Enum` |
| Companion `values` | `Method` | `Synthetic` |

Callers walking an enum's `declarations` see both styles uniformly. To enumerate "all cases of this enum": filter `decls` by `_.flags.contains(Flag.Case | Flag.Enum)`.

`Flag.Enum` and `Flag.Case` are added to the `Flag` set (Section 7). The complete flag list bumps from ~40 to ~42.

### Unresolved sentinel

When the unpickler encounters a cross-file type reference whose target FQN is not in the classpath (partial-classpath mode), it produces a `Symbol` with `kind = SymbolKind.Unresolved`. Its `name` field carries the unresolved FQN; `flags = Flags.empty`; `owner = Symbol.root`; `home` is the same `Classpath` as any other symbol.

Accessor behavior on an unresolved symbol:

<!-- flow-allow: algorithmic discussion of placeholder symbols returned from unresolved-symbol accessors, not deferral -->
* Pure accessors (`name`, `kind`, `flags`, `owner`, `fullName`) return the placeholder data without error.
* Resolving accessors (`declaredType`, `parents`, `declarations`, `typeParams`, `companion`) return `Abort.fail(TastyError.SymbolNotFound(name.toString))`.

Callers can check `sym.kind == SymbolKind.Unresolved` before touching resolving accessors to avoid the abort.

**Pure vs resolving split**: identity-level data (name, flags, kind, owner) is always present. Relationship-level data (declared type, parents, declarations) may reference symbols in other files; if those files were not loaded (partial classpath mode), the resolving accessor fails with `Abort[TastyError.SymbolNotFound]`. Soft-fail mode is the default for partial classpaths; strict mode is opt-in via `Classpath.open(roots, strict = true)`.

**Symbol home**: every `Symbol` carries a reference to the `Classpath` that decoded it. This is correctness, not convenience: a Symbol's type references, member references, and FQN resolution live in its home's name table and arena. Resolving via a different classpath would be incorrect (the other classpath may have a different version of the same class, or not have it at all). The +8 bytes per Symbol (~40 KB total for kyo-size) is negligible.

Resolving accessors call `home.checkOpen` first; if the home was closed (its outer `Scope.run` has exited), the accessor returns `Abort.fail(TastyError.ClasspathClosed)`. Pure accessors continue working after classpath close because they touch no shared state.

Cross-classpath symbol comparison uses structural equality on FQN (`a.fullName == b.fullName`), not reference equality. Reference equality across classpaths is always false even for same-FQN symbols.

Properties that tasty-query exposes via subtype matching collapse into `kind` checks and direct accessors. The reflection escape hatches in `kyo-ts/.../TastyReader.scala` (private-field access for inline detection, `getMethod("isImplicit").invoke(mt)`) go to zero: `sym.flags.contains(Flag.Inline)` and `sym.flags.contains(Flag.Given)`.

### Flags

```scala
opaque type Flags = Long
object Flag:
    val Inline, Private, Protected, Public, Final, Sealed, Abstract,
        Given, Implicit, Opaque, Open, Case, Trait, Module, Lazy,
        Override, ParamAccessor, Synthetic, Mutable, JavaDefined,
        Erased, Tracked, Tailrec, Infix, Transparent,
        Enum, JavaRecord /* etc */ = ...
```

Bit-packed flag set. ~40 flags fit in a `Long`. We extract flag bits during pass 1 from TASTy `Modifier` tags directly; no reflection on tasty-query objects.

### Lazy initialization helpers

```scala
final class Memo[A](init: () => A):
    private val ref = new AtomicReference[A | Null](null)
    def get(): A     // double-checked CAS, idempotent

final class SingleAssign[A]:
    private val ref = new AtomicReference[A | Null](null)
    def set(a: A): Unit   // throws if already set
    def get(): A
```

Same shape as tasty-query's primitives. Read path is non-effectful (no `< Sync`) for a fast inline path; the effect appears at the resolving-accessor boundary.

### Symbol cache

Top-level FQN lookup (`Classpath.findClass(fqn)`) goes through `Cache.memo` from `kyo-core/Cache.scala`. Bounded (`maxSize` configurable, default 4096) with CLOCK eviction. `Cache.memo` deduplicates concurrent callers via `Promise`: two fibers asking for the same FQN trigger one file load.

Per-class declaration tables: `Dict[Name, Symbol]` for ≤8 members (flat-array, no hash), `mutable.HashMap[Name, Symbol]` above. Wrapped in `AtomicRef[Map]` and CAS-swapped on completion; readers see either the empty or fully-populated map, never partial.

## 8. Name Intern Table

Sharded by name hash, 32 segments. Each segment is an `AtomicRef[Array[Entry]]` linear-probe table. Entry stores `(hash, byteOffset, length, name)` over a shared `Span[Byte]` backing store per file. Cross-file dedup happens at the symbol resolution layer.

Names compare via `Arrays.equals` on byte slices. `Name.toString` triggers a one-time UTF-8 decode and caches the `String` in the entry via `Memo[String]`. Workloads that compare names structurally never materialize a `String`.

This avoids the single-`ConcurrentHashMap` contention point in tasty-query's `NameCache` under parallel decode (Section 14).

## 9. Type Model

```scala
enum Type:
    case Named(symbol: Symbol)
    case TermRef(prefix: Type, name: Name)
    case Applied(base: Type, args: Chunk[Type])
    case TypeLambda(params: Chunk[Symbol], body: Type)
    case Function(params: Chunk[Type], result: Type, isContext: Boolean)
    case Tuple(elements: Chunk[Type])
    case ByName(underlying: Type)
    case Repeated(elem: Type)
    case Array(elem: Type)                      // Scala Array[T] and Java T[]; distinct from Applied
    case Refinement(parent: Type, name: Name, info: Type)
    case Rec(parent: Type)
    case RecThis(rec: Type)
    case AndType(left: Type, right: Type)
    case OrType(left: Type, right: Type)
    case Annotated(underlying: Type, annotation: Annotation)
    case ConstantType(value: Constant)
    case ThisType(cls: Symbol)
    case SuperType(self: Type, mixin: Type)
    case ParamRef(binder: Symbol, idx: Int)
    case Wildcard(lo: Type, hi: Type)
    case Skolem(underlying: Type)
    case MatchType(bound: Type, scrutinee: Type, cases: Chunk[Type])
    case FlexibleType(underlying: Type)
end Type
```

Normalization happens at construction time, not the call site: `AppliedType(FunctionN, args)` builds `Function(args.init, args.last)`; `AppliedType(TupleN, args)` builds `Tuple(args)`; `AppliedType(ContextFunctionN, args)` builds `Function(_, _, isContext = true)`; `AppliedType(scala.Array, Chunk(t))` builds `Array(t)`; `AndType(Singleton, X)` collapses to `X`. Normalizations the kyo-ts `TastyReader.scala` does one-off in `resolveType` live here in smart constructors.

Java arrays (`int[]`, `String[]`, multi-dimensional) decode directly to `Type.Array(elem)` from the classfile signature attribute, without round-tripping through `Applied(Named(scala.Array), _)`. Consumers can match on `Type.Array(elem)` uniformly regardless of source language.

### Hash-consing via per-thread arenas + merge

Each Phase B fiber owns a `TypeArena`: a `mutable.HashMap[TypeKey, Type]` keyed on the structural shape of the type. No cross-fiber synchronization during decode.

Phase C merges all per-thread arenas into a single canonical arena, single-threaded:

```scala
val canonical = TypeArena.canonical()
for arena <- perThreadArenas do
    arena.merge(canonical)            // dedup by structural hash
```

Structurally equal types from different fibers collapse to the same canonical reference. Equality checks across the symbol graph reduce to reference equality on canonical types after merge.

### Intern algorithm

Each per-thread `TypeArena` is `mutable.HashMap[TypeKey, Type]` where `TypeKey` is the structural hash. Phase B fibers intern within their own arena, no synchronization.

Phase C merge is bottom-up recursive:

```scala
val canonical = TypeArena.canonical()             // mutable.HashMap[TypeKey, Type]
<!-- flow-allow: inProgress is an algorithmic cycle-breaking map in the TypeArena merge pseudocode, not a status flag -->
val inProgress = mutable.HashMap[TypeKey, Type]() // cycle-break placeholders

def intern(t: Type): Type = canonical.get(structuralKey(t)) match
    case Some(canon) => canon
    case None =>
        inProgress.get(structuralKey(t)) match
            case Some(placeholder) => placeholder            // cycle: return the placeholder
            case None =>
                val placeholder = Type.RecPlaceholder(t)
                inProgress(structuralKey(t)) = placeholder
                val recurInterned = t match
                    case Named(sym)            => t          // sym is already canonical (Phase B)
                    case Applied(base, args)   => Applied(intern(base), args.map(intern))
                    case Function(ps, r, ctx)  => Function(ps.map(intern), intern(r), ctx)
                    case AndType(l, r)         => AndType(intern(l), intern(r))
                    case OrType(l, r)          => OrType(intern(l), intern(r))
                    case Refinement(p, n, i)   => Refinement(intern(p), n, intern(i))
                    case Rec(p)                => Rec(intern(p))                // RecThis points back via cycle
                    case other                 => other      // primitives, RecThis, etc.
                inProgress.remove(structuralKey(t))
                canonical(structuralKey(t)) = recurInterned
                recurInterned

for arena <- perThreadArenas do
    arena.values.foreach(intern)
```

**Cycle handling**: `Rec(parent: Type)` types contain `RecThis(rec)` references pointing back to themselves. The `inProgress` map breaks the cycle: when intern recurses into a `Rec` parent that references the same `Rec` again via `RecThis`, the lookup finds the placeholder and returns it.

**Symbols are pre-canonical**: by the end of Phase B, the `ClasspathBuilder` ensures one `Symbol` instance per `(fileId, treeOffset)` via `Addr -> Symbol` indexing. `Named(sym1)` from thread A and `Named(sym1)` from thread B reference the same `sym1` instance, so their structural keys agree without needing to canonicalize symbols separately.

**Complexity**: O(total types across all arenas). The merge is single-threaded but sequential and cache-friendly. For kyo-size classpath (~50K types), ~30ms estimated.

`Named` preserves the resolved `Symbol`; types in the surface API carry resolved references unless the consumer is in soft-fail partial-classpath mode (in which case unresolved references surface as a `Symbol` with `kind = SymbolKind.Unresolved` per Section 7; resolving accessors return `Abort.fail(TastyError.SymbolNotFound)`).

## 10. Classfile Reader

`kyo.internal.reflect.classfile.*`. Hand-rolled, no ASM. ~1500 LOC budget.

* `ClassfileFormat` is the constant table.
* `ClassfileUnpickler` reads magic, version, constant pool, access flags, this/super, interfaces, fields, methods, attributes.
* `ConstantPool` stores entries lazily: UTF-8 entries hold `(offset, length)` into the file `Span[Byte]` and decode to `Name` (interned via Section 8) on first access.
* `JavaSignatures` parses the `Signature` JVM attribute into the same `Type` ADT.

Java symbols surface through the same `Symbol` API with `flags.contains(JavaDefined) == true`. Method overloads, generic bounds, varargs all supported.

Cross-platform: classfile parsing is pure byte arithmetic. The hand-rolled reader runs on all three platforms without modification.

### Java FQN canonicalization

The dotted `fullName` for Java symbols requires care because `$` is a valid Java identifier character. `Map$Entry` could be either:

* An inner class `Entry` defined inside `Map` (the common case), or
* A regular top-level class literally named `Map$Entry` (legal but rare).

Heuristic splitting on `$` is wrong. The authoritative source is the **InnerClasses attribute** in the bytecode, which records every inner class with three fields: `inner_class_info_index`, `outer_class_info_index`, `inner_name_index`. For each Java class loaded:

1. Parse `InnerClasses` if present. Build a `Map[BinaryName, (OuterBinaryName, InnerSimpleName)]`.
2. To compute `fullName(sym)`: if `sym.binaryName` appears as an inner entry, recurse on outer and append `.innerName`. Otherwise treat `$` as a literal name character.
3. Anonymous and local classes (no `outer_class_info_index`) keep their raw `binaryName` as both `fullName` and `binaryName`.

The `kyo.internal.tasty.classfile.ClassfileUnpickler` reads the attribute during Phase B and stores the mapping on per-file metadata; `Symbol.fullName` materializes lazily by consulting it.

For TASTy-sourced symbols, the question doesn't arise: TASTy stores names structurally with explicit parent/child relationships, no `$` ambiguity. `fullName` is built by walking the owner chain.

## 11. Java / Scala Unified Model

Both source languages flow through the same `Symbol` (Section 7) and `Type` (Section 9) abstractions. This section documents the unification contract: what's the same, what's source-specific, and how callers write code that handles both.

### Same surface, source-tagged via `flags`

Every `Symbol` has a `flags.contains(Flag.JavaDefined)` predicate (alias: `sym.isJava`). All other accessors behave the same regardless of origin. A query like `cp.findClass("java.util.HashMap")` and `cp.findClass("scala.collection.mutable.HashMap")` produce comparable `Symbol` instances; differences appear only via the language-specific predicates and the `javaSpecific` side accessor.

### FQN canonicalization

Caller-facing names use dotted form regardless of source language:

* Top-level: `java.lang.String`, `scala.collection.immutable.List`.
* Inner class / nested object: `java.util.Map.Entry`, `kyo.Sync.Join` (NOT `java.util.Map$Entry` or `kyo.Sync$Join`).
* Package object members: `kyo.foo` (no `$package` suffix).

The JVM internal form (`java/util/Map$Entry`) is available via `Symbol.binaryName: String` for callers that need it (bytecode emission, FFI, JNI signatures). Lookup via `cp.findClass` accepts only dotted form; `cp.findClassByBinary(binaryName)` is a separate entry point for the JVM form.

### Type ADT coverage

Java types fit in the Scala-shaped `Type` ADT:

| Java type | Maps to |
|---|---|
| `int`, `long`, `boolean`, etc. | `Named(symbol)` to `scala.Int`, `scala.Long`, `scala.Boolean` (boxed types stay as `Named` to `java.lang.Integer`, etc.) |
| `String`, `Object` | `Named(symbol)` to `java.lang.String`, `java.lang.Object` |
| `T[]`, `int[]`, `String[][]` | `Array(elem)`, nested for multi-dim |
| `List<String>` | `Applied(Named(java.util.List), Chunk(Named(java.lang.String)))` |
| `? extends Number` | `Wildcard(lo = Named(scala.Nothing), hi = Named(java.lang.Number))` |
| `? super T` | `Wildcard(lo = Named(T), hi = Named(java.lang.Object))` |
| Raw type `List` | `Named(symbol)` without args (`Applied` with zero args is the same shape) |
| Generic method param `<T>` | `TypeParam` symbol; uses appear as `Named(typeParamSymbol)` |
| Method `throws X, Y` | not in the main type; surfaces via `Symbol.javaSpecific.get.throwsTypes` |

Java has no equivalent for: `TypeLambda`, `MatchType`, `OrType`, `AndType` beyond `Object`, `OpaqueType`, `Refinement`, `ContextFunction`, `ByName`, `Repeated`, `Skolem`, `FlexibleType`. Java symbols will never produce these cases.

### Java-specific metadata side door

`Symbol.javaSpecific: Maybe[JavaMetadata]` is `Present` only when `isJava == true`. Holds data with no Scala equivalent:

* `throwsTypes`: declared throws clauses on methods.
* `annotations`: full `JavaAnnotation` records including parameter values (Scala annotations are surfaced through TASTy's `AnnotatedType` and via `Annotation` side data; Java annotations need explicit access to retention-policy info and raw values).
* `enclosingMethod`: for anonymous and local classes synthesized by `javac`.
* `accessFlags`: raw JVM `access_flags` bitmask for callers that need the exact bytecode-level view.

Scala-only metadata (e.g., the `@experimental` annotation, the `Selectable` machinery) appears through standard `Type.Annotated` and `Symbol.flags` paths. The Scala/Java split lives in the JVM access-flags and JVM-attribute-shaped data, not in the structural symbol/type graph.

### Cross-classpath uniformity

`Classpath.findClass(fqn)` works regardless of whether the named class is in a `.tasty` or a `.class` file. The resolver checks both sources (per the file-source layer in Section 14); whichever yields a hit, the resulting `Symbol` is uniform.

For mixed classpaths (Scala app depending on Java libraries, or Java app depending on Scala libraries) this is the common case: most lookups cross the boundary. The unification means callers never branch on source language at the query layer.

### What this NOT does

* **Does not unify Scala and Java semantics**: a Java method overload returning `Object` is not the same as a Scala method returning `Any`, even though `java.lang.Object` and `scala.Any` are related. We surface what's pickled; semantic equivalence is the caller's problem.
* **Does not perform erasure**: `List<String>` and `List` are different `Type` values, not unified to one erased form.
* **Does not synthesize missing Java metadata**: if a classfile lacks the `Signature` attribute (pre-Java-5 generics), we surface the raw signature without generic recovery.

### Record interop and the bridging idiom

`kyo.Record` (from kyo-data) is a structural typed record with named string-singleton fields, `&`-composable types, compile-time field-presence checking, and a `stage[T].using[TypeClass]` macro that iterates fields at compile time and summons a per-field type class instance. Real-world usage spans kyo-http (typed route fields), kyo-stm (TTable indexed queries), kyo-flow (accumulating workflow context), and kyo-schema (`Schema.toRecord`).

For kyo-tasty, Records are the right substrate for compile-time-typed API descriptors and language-bridging code generation. The pattern:

```scala
// 1. Declare an API descriptor as a Record type
type FunctionSig =
    "name"       ~ String &
    "params"     ~ Chunk[Type] &
    "returnType" ~ Type

// 2. Read a Symbol into a Record value (Section 12)
val sig: Record[FunctionSig] < (Sync & Abort[TastyError]) =
    Tasty.symbolToRecord[FunctionSig](symbol)

// 3. Declare a per-field translation type class
trait CSignature[A]:
    def render(a: A): String
given CSignature[String]      = _.toString
given CSignature[Type]        = t => mapTypeToC(t)             // Type -> "int", "long", "void*"
given CSignature[Chunk[Type]] = ts => ts.map(mapTypeToC).mkString("(", ", ", ")")

// 4. Record.mapFields traverses, summoning CSignature per field
val cSig: Record["name" ~ String & "params" ~ String & "returnType" ~ String] =
    sig.mapFields([v] => (field, value) => summon[CSignature[v]].render(value))

// 5. Emit
val cHeader = s"${cSig.returnType} ${cSig.name}${cSig.params};"
// e.g. "int add(int, int);"
```

**Compile-time guarantee**: if any field type in `FunctionSig` lacks a `CSignature` instance, the `mapFields` call fails to compile with `report.errorAndAbort`. The translation table is verified exhaustive at the call site. No runtime "unsupported type" errors. No dynamic dispatch.

**Reverse direction**: same machinery with `ToScalaType[CType]` instead of `CSignature[ScalaType]`. The Record is the lingua franca between languages; per-field type classes carry the translation logic.

**Why Records, not case classes, for this use case**:

| Concern | Case class | Record |
|---|---|---|
| Pattern matching | yes | no |
| IDE autocomplete on field names | yes | yes (via Dynamic) |
| Compile-time field iteration with type-class lookup | no (needs Mirror + manual machinery) | yes via `stage.using[TC]` |
| Structural intersection composition (`&`) | no | yes |
| Compile-time field projection (subset of fields) | no | yes via `widen` + `compact` |
| Type-level field renaming, dropping | no | yes via `mapFields` |

Case-class-based `Reads` remains the default for codegen and IDE-style queries. Records are the right choice for FFI bridging, ABI translation, API migration tooling, and anywhere fields need iteration with per-field-type dispatch.

## 12. Public API

Single top-level entry point `kyo.Tasty` with nested types. Avoids polluting `kyo.*` with `Symbol` (which would clash with `scala.Symbol`) or `Type` (which would clash with `scala.reflect.runtime.universe.Type`).

```scala
object Reflect:

    opaque type Classpath = Classpath.Internal
    object Classpath:
        def open(roots: Seq[Path]): Classpath < (Sync & Scope & Abort[TastyError])
        def openCached(roots: Seq[Path], cacheDir: Path): Classpath < (Sync & Scope & Abort[TastyError])
        def fromPickles(pickles: Seq[Pickle]): Classpath < Sync
        def open(roots: Seq[Path], strict: Boolean): Classpath < (Sync & Scope & Abort[TastyError])

    final class Symbol /* see Section 7 */
    enum SymbolKind            /* see Section 7 */
    enum Type                  /* see Section 9 */
    opaque type Name
    opaque type Flags
    type FQN = String                          // dotted form; binary form via Symbol.binaryName
    final case class Pickle(uuid: UUID, version: Version, bytes: Span[Byte])
    final case class Version(major: Int, minor: Int, experimental: Int)
    final case class Annotation(annotationType: Type, argsPickle: Span[Byte])  // Scala-side; argsPickle is the unparsed body pickle (deferred per Section 6's lazy-body strategy). For Java see JavaAnnotation in Section 7.
    enum Constant:
        case StringConst(s: String)
        case IntConst(i: Int)
        case LongConst(l: Long)
        case FloatConst(f: Float)
        case DoubleConst(d: Double)
        case BooleanConst(b: Boolean)
        case CharConst(c: Char)
        case ByteConst(b: Byte)
        case ShortConst(s: Short)
        case UnitConst
        case NullConst
        case ClassConst(tpe: Type)
    opaque type FieldSet = Long                // bit-packed field markers; ops: |, &, contains
    object FieldSet:
        val Name, BinaryName, Flags, Kind, Owner, DeclaredType,
            Parents, TypeParams, Members, Companion,
            JavaSpecific, ParamTypes, Annotations, Positions, Comments,
            All, Empty: FieldSet

    // Schema-driven reading. Reads.read is effectful because Symbol's resolving
    // accessors (declaredType, parents, declarations) return < Sync & Abort[TastyError].
    // Symbol carries its home Classpath internally; no implicit needed.
    trait Reads[A]:
        def read(sym: Symbol): A < (Sync & Abort[TastyError])
        val symbolKinds: Set[SymbolKind]
        val needsBodies: Boolean
        val touchedFields: FieldSet

    object Reads:
        inline def derived[A]: Reads[A]   // see Section 13

    // FQN helpers
    def classFqn[A](using Tag[A]): String < Abort[TastyError]
    // Non-parameterized types only. Restricted because Tag[A].show
    // produces structured rendering for parameterized types
    // (e.g. "scala.List[scala.Int]"), not a clean FQN.

    // Record interop: project a Symbol's fields into a typed Record (Section 11).
    // The macro maps each field name in F to the corresponding Symbol accessor
    // and composes effectful accessors via for/yield.
    inline def symbolToRecord[F: Fields](sym: Symbol): Record[F] < (Sync & Abort[TastyError])

    // Queries
    extension (cp: Classpath)
        def findClass(fqn: String):     Maybe[Symbol] < (Sync & Abort[TastyError])
        def findPackage(fqn: String):   Maybe[Symbol] < (Sync & Abort[TastyError])
        def packages:                   Chunk[Symbol] < (Sync & Abort[TastyError])
        def topLevelClasses:            Chunk[Symbol] < (Sync & Abort[TastyError])
        def query[A](using Reads[A]):   Query[A]
        def errors:                     Chunk[TastyError] < Sync  // partial-classpath mode
end Reflect

enum TastyError:
    case FileNotFound(path: String)
    case CorruptedFile(path: String, at: Long, reason: String)
    case UnsupportedVersion(found: Tasty.Version, supported: Tasty.Version)
    case InconsistentClasspath(file: String, expectedUuid: UUID, foundUuid: UUID)
    case MalformedSection(name: String, reason: String)
    case SymbolNotFound(fqn: String)
    case ClassfileFormatError(path: String, reason: String)
    case ParameterizedTypeNotAllowed(tag: String)
    case ClasspathClosed
    case ClasspathBuilding   // defense-in-depth; never observable in correct usage
    case SnapshotFormatError(path: String, reason: String)
    case SnapshotVersionMismatch(found: Tasty.Version, supported: Tasty.Version)
    case NotImplemented(feature: String)         // v1 stub for deferred features (e.g., tree body decode)
end TastyError
```

### `symbolToRecord` field-to-accessor mapping

The macro for `symbolToRecord[F]` walks the field intersection in `F` and emits one Symbol-accessor call per field. Supported field names map deterministically:

| Field name in F | Accessor | Value type | Effectful? |
|---|---|---|---|
| `"name"` | `sym.name` | `Name` | no |
| `"binaryName"` | `sym.binaryName` | `String` | no |
| `"flags"` | `sym.flags` | `Flags` | no |
| `"kind"` | `sym.kind` | `SymbolKind` | no |
| `"owner"` | `sym.owner` | `Symbol` | no |
| `"isInline"` / `"isContextual"` / `"isOpaque"` / `"isPackageObject"` / `"isModule"` / `"isJava"` | the predicate | `Boolean` | no |
| `"declaredType"` | `sym.declaredType` | `Type` | yes |
| `"parents"` | `sym.parents` | `Chunk[Type]` | yes |
| `"typeParams"` | `sym.typeParams` | `Chunk[Symbol]` | yes |
| `"declarations"` | `sym.declarations` | `Chunk[Symbol]` | yes |
| `"companion"` | `sym.companion` | `Maybe[Symbol]` | yes |
| `"javaSpecific"` | `sym.javaSpecific` | `Maybe[JavaMetadata]` | no |
| any other name | macro error | | |

The macro generates `for/yield` to thread the `Sync & Abort[TastyError]` effect across the resolving accessors, and direct reads for pure accessors. Field value types in `F` must match the accessor's return type or the macro fails with `report.errorAndAbort`.

Example:

```scala
type ClassView =
    "name"         ~ Name &
    "flags"        ~ Flags &
    "parents"      ~ Chunk[Type] &
    "declarations" ~ Chunk[Symbol]

val view: Record[ClassView] < (Sync & Abort[TastyError]) =
    Tasty.symbolToRecord[ClassView](classSym)
```

The macro propagates `touchedFields` to the unpickler (Section 13), so `symbolToRecord[F]` participates in the same skeleton-pruning optimization as `derives Tasty.Reads`.

### Query combinators

```scala
// Query is obtained via cp.query[A] and closes over its source Classpath.
// .run and .stream do not need an implicit Classpath; the binding is captured at construction.
final class Query[A] private[kyo] (impl: Query.Internal[A]):
    def filter(p: A => Boolean):           Query[A]
    def where(p: Tasty.Symbol => Boolean): Query[A]
    def withFlag(f: Flag):                 Query[A]
    def named(name: String):               Query[A]
    def extending(parent: Symbol):         Query[A]
    def map[B](f: A => B):                 Query[B]
    def stream: Stream[A, Sync & Abort[TastyError]]
    def run:    Chunk[A] < (Sync & Abort[TastyError])
```

Implementation: combinators compose into an intermediate plan; `.run` and `.stream` translate the plan into a single traversal over the bound classpath's symbol cache, touching only the fields the `Reads[A]` declares.

### Three usage examples

**Codegen (kyo-ts shape):**

```scala
case class FacadeType(name: Name, pkg: String, flags: Flags,
                      parents: Chunk[Type], methods: Chunk[FacadeMethod]) derives Tasty.Reads
case class FacadeMethod(name: Name, flags: Flags, returnType: Type,
                        params: Chunk[Type]) derives Tasty.Reads

def run: Unit < (Sync & Abort[TastyError] & Scope) =
    for
        cp    <- Tasty.Classpath.openCached(jsTargetDirs, cacheDir = ".kyo-tasty-cache")
        types <- cp.query[FacadeType].where(_.flags.contains(Flag.Public)).run
        _     <- Kyo.foreach(types)(emitFacade)
    yield ()
```

**IDE hover** (Symbol accessors do not require an implicit Classpath; the Symbol's `home` carries it):

```scala
def hover(fqn: String, member: String): Maybe[String] < (Sync & Abort[TastyError] & Scope) =
    for
        cp  <- Tasty.Classpath.openCached(roots, cacheDir)
        cls <- cp.findClass(fqn)
        out <- cls.fold(Kyo.pure(Absent)) { c =>
                   c.declarations.map { decls =>
                       decls.find(_.name.toString == member).fold(Kyo.pure(Absent)) { s =>
                           s.declaredType.map(t => Present(s"${s.name}: ${t.show}"))
                       }
                   }
               }
    yield out
```

**Runtime reflection:**

```scala
def fieldsOf[A: Tag]: Chunk[(String, Type)] < (Sync & Abort[TastyError] & Scope) =
    for
        cp     <- Tasty.Classpath.openCached(runtimeRoots, cacheDir)
        fqn    <- Tasty.classFqn[A]
        clsOpt <- cp.findClass(fqn)
        cls    <- clsOpt.fold(Abort.fail(TastyError.SymbolNotFound(fqn)))(Kyo.pure)
        ds     <- cls.declarations
        flds    = ds.filter(_.kind == SymbolKind.Val)
        out    <- Kyo.foreach(flds)(f => f.declaredType.map(t => (f.name.toString, t)))
    yield out
```

`Maybe.fold(ifEmpty: B)(ifDefined: A => B)` takes a value for the empty branch, not a function. The `Kyo.pure` wraps the `Symbol` so both branches have type `Symbol < (Sync & Abort[TastyError])`.

## 13. `Tasty.Reads` Derivation Macro

### What the user writes

```scala
case class MethodSig(
    name:       Name,
    flags:      Flags,
    returnType: Type,
    params:     Chunk[Type]
) derives Tasty.Reads

case class ClassInfo(
    name:    Name,
    flags:   Flags,
    parents: Chunk[Type],
    methods: Chunk[MethodSig]
) derives Tasty.Reads
```

### What the macro generates (sketched)

Symbol's resolving accessors return `< (Sync & Abort[TastyError])`, so the generated `read` body composes them via `for/yield`:

```scala
given Tasty.Reads[MethodSig] = new Tasty.Reads[MethodSig]:
    val symbolKinds   = Set(SymbolKind.Method)
    val needsBodies   = false
    val touchedFields = FieldSet.Name | Flags | DeclaredType | ParamTypes
    def read(sym: Symbol): MethodSig < (Sync & Abort[TastyError]) =
        for
            sig <- sym.declaredType
        yield MethodSig(
            name       = sym.name,                          // pure accessor
            flags      = sym.flags,                         // pure accessor
            returnType = sig.asMethod.resultType,
            params     = sig.asMethod.paramTypes
        )

given Tasty.Reads[ClassInfo] = new Tasty.Reads[ClassInfo]:
    val symbolKinds   = Set(SymbolKind.Class, SymbolKind.Trait, SymbolKind.Object)
    val needsBodies   = false
    val touchedFields = FieldSet.Name | Flags | Parents | Members
    val methodReads   = summon[Tasty.Reads[MethodSig]]
    def read(sym: Symbol): ClassInfo < (Sync & Abort[TastyError]) =
        for
            parents <- sym.parents
            decls   <- sym.declarations
            methods <- Kyo.foreach(decls.filter(d => methodReads.symbolKinds.contains(d.kind)))(methodReads.read)
        yield ClassInfo(
            name    = sym.name,
            flags   = sym.flags,
            parents = parents,
            methods = methods
        )
```

### Three load-bearing bits

1. **`symbolKinds`** lets the query layer filter at scan time without instantiating wrong-kind symbols.
2. **`needsBodies = false`** propagates into the unpickler. If no schema in a session needs body decode, the unpickler skips body materialization entirely.
3. **`touchedFields`** is a static set of which `Symbol` accessors the schema reads. The unpickler skips eagerly decoding annotations, source positions, comments if no schema touches them.

### Implementation approach

Pattern matches existing kyo-schema and kyo-direct macros:

* `quotes.reflect.TypeRepr` direct inspection, not `Mirror.ProductOf`. Same as `StructureMacro` (kyo-schema, ~230 LOC) and `TagMacro` (kyo-data, ~190 LOC).
* `FocusMacro.extractAllFocusFieldNames` (kyo-schema, FocusMacro.scala:1362) is the precedent for `touchedFields` analysis: walk a `Term` via `Trees.traverseGoto`, pattern-match `Select(qualifier, methodName)` where `qualifier.tpe <:< TypeRepr.of[Symbol]`, collect method names.
* `Trees.exists` from kyo-direct's `Trees.scala` for cheap pre-checks.

### Hygiene precautions

From the PR #1633 (kyo-direct macro fixes) analysis, two patterns apply:

1. **`TreeMap` re-copy guard**: when walking the generated `read` body for touched-field analysis, add a `Trees.exists` pre-check that returns the original term verbatim if the subtree contains no `Symbol`-typed `Select`. Without this, recursing into every `Apply`/`Select` triggers dotty's internal `xCheckMacro*` assertions on edge-case shapes.

2. **`Match` pattern internals**: skip `.pattern` when traversing `Match` nodes; visit only `scrutinee`, `guard`, `rhs`. `Bind` / `Wildcard` / `Unapply` subtrees carry effect-tagged types from generic destructuring that look like effect uses but are not.

### Recursive case classes

```scala
case class Node(name: Name, children: Chunk[Node]) derives Tasty.Reads
```

Macro emits `lazy val instance: Reads[Node]` and uses `instance` for child reads. Pattern matches kyo-schema's `StructureMacro` recursion handling. Self-referential schemas Just Work.

### Sealed ADTs / enums (v1 scope)

The macro derives `Reads` only for product types (case classes). Sum types require a hand-written `given Reads[E]`. The macro detects sum-type derives at expansion and emits a clear error pointing at the worked-example template (see Caveats below).

Rationale: automatic ADT derivation requires user hints (annotations linking cases to `SymbolKind`/`Flag` predicates, or naming conventions). Adding that machinery to v1 increases macro complexity without enough value over the 5-to-15-line hand-written instance. Revisit if v2 surfaces a common usage pattern.

### Custom field reads via `given` override

```scala
case class Custom(special: MyType, name: Name) derives Tasty.Reads
given Tasty.Reads[MyType] = customMyTypeReader
```

Derivation uses `Expr.summon[Tasty.Reads[FieldType]]` for each field. Scope-provided `given` instances win over derived ones automatically. No annotation magic needed.

### Higher-kinded case classes

```scala
case class Foo[A](xs: Chunk[A]) derives Tasty.Reads        // ERROR at expansion
val r: Reads[Foo[Type]] = summon                            // OK: A concrete
```

The macro requires monomorphic instantiation for derivation. Abstract type parameters fail at macro expansion with a clear error. Users can build polymorphic factories explicitly: `def fooReads[A](using Reads[A]): Reads[Foo[A]] = ...`.

### Built-in `Reads` instances (Phase 6 deliverable)

| Type | Built-in | Notes |
|---|---|---|
| `Name`, `Flags`, `SymbolKind` | yes | direct from Symbol record |
| `Type`, `Symbol` | yes | references into the graph |
| `Boolean`, `Int`, `Long`, `String` | yes | for `Flags.contains` predicates etc. |
| `Chunk[T]` when `Reads[T]` exists | yes | maps over symbol collections |
| `Maybe[T]` when `Reads[T]` exists | yes | for optional fields like `companion` |
| `Record[F]` for any field intersection `F` | yes | delegates to `symbolToRecord[F]` (Section 12); composes with `stage.using[TC]` for FFI bridging (Section 11) |
| Tuples up to arity 22 | derived | structural |
| `Either[L, R]` | hand-written required | no canonical mapping |

### Transitive `touchedFields` analysis

Composed `Reads` instances compose their touched-fields sets. If `Reads[ClassInfo]` invokes `methodReads.read(...)` inside its generated body, the macro:

1. Walks the body via `Trees.traverseGoto`.
2. For each `Select(sym, methodName)` where `sym.tpe <:< Symbol`, records `methodName` in this Reads's set.
3. For each call to another `Reads[X]`'s `read`, unions in `summon[Reads[X]].touchedFields` at compile time.

Control flow: `if/else` branches union their touched-fields. Pattern matching: union across cases.

This is a recursive macro-time tree walk closed over the schema's transitive `Reads` instances. Hand-written `Reads` instances declare their own `touchedFields` explicitly; the macro respects that declaration when composing.

### Streaming variant

`cp.query[A].stream` returns `Stream[A, Sync & Abort[TastyError]]`. Streaming is a query-layer concern, not a `Reads`-trait concern. `Reads` produces one `A` per `Symbol`; the stream operator emits results as Phase B fibers finish per-file batches.

### Estimated effort

400 to 600 LOC including ADT derivation paths, recursive case-class handling, transitive `touchedFields` analysis, and hygiene guards. StructureMacro is 230 LOC for similar surface area; we add ~150 LOC for touched-fields analysis (including transitivity), ~80 LOC for ADT shape recognition, ~50 LOC for recursion handling, ~50 LOC for hygiene.

### Reads × symbolToRecord composition

Case classes can have `Record[F]` fields:

```scala
case class Wrap(api: Record[ExportedAPI], notes: String) derives Tasty.Reads
```

The derivation macro summons `Reads[Record[ExportedAPI]]` (the built-in instance, per Section 13's instance table) for the `api` field. All field readers share the SAME `sym` passed to `Wrap.read(sym)`. The Record built-in delegates to `symbolToRecord[ExportedAPI](sym)` which generates per-accessor calls on that one symbol.

Generated shape (sketched):

```scala
given Reads[Wrap] = new Reads[Wrap]:
    val symbolKinds   = Set(SymbolKind.Class, ...)            // from inner-most Reads constraints
    val needsBodies   = false
    val touchedFields = FieldSet.Name | DeclaredType | ...    // union of all inner touched-fields
    val recordReads   = summon[Reads[Record[ExportedAPI]]]
    def read(sym: Symbol): Wrap < (Sync & Abort[TastyError]) =
        for
            api <- recordReads.read(sym)                       // delegates to symbolToRecord
        yield Wrap(api = api, notes = sym.name.toString)
```

No recursion hazard: `Reads[Record[F]]` is a built-in, not derived. `symbolToRecord` is a macro that emits direct accessor calls, no further `Reads` dispatch. Composition is one-level deep.

### Caveats

* The `touchedFields` analysis is best-effort. If a user hand-writes a `Reads` instance the macro can't see through, the hand-written declaration of `touchedFields` is honored (defaults to `FieldSet.All`).
* The `derives` clause is the supported entry point for product types (case classes). Hand-written `Reads` instances work but the macro can't inspect their bodies.
* **ADTs require hand-written `Reads` instances in v1.** The earlier exploration described three discrimination patterns (kind-, flag-, type-based) as automatic derivation candidates; in practice these need user hints (annotations or convention) that complicate the macro without enough value for v1. Hand-written ADT instances are 5 to 15 lines, follow a clear template (see worked examples below), and stay explicit at the call site. Re-evaluate automatic ADT derivation in v2 if real usage shows a clear common pattern.

### Worked example: hand-written ADT Reads

```scala
sealed trait DeclKind
case object ClassDecl  extends DeclKind
case object TraitDecl  extends DeclKind
case object MethodDecl extends DeclKind

given Tasty.Reads[DeclKind] = new Tasty.Reads[DeclKind]:
    val symbolKinds   = Set(SymbolKind.Class, SymbolKind.Trait, SymbolKind.Method)
    val needsBodies   = false
    val touchedFields = FieldSet.Kind
    def read(sym: Symbol): DeclKind < (Sync & Abort[TastyError]) =
        Kyo.pure(sym.kind match
            case SymbolKind.Class  => ClassDecl
            case SymbolKind.Trait  => TraitDecl
            case SymbolKind.Method => MethodDecl
            case other             => return Abort.fail(TastyError.SymbolNotFound(s"unexpected kind: $other"))
        )
```

## 14. Platform File Source

Uniform shape across all three platforms after dropping mmap.

```scala
// shared
trait FileSource:
    def read(path: String): Array[Byte] < (Sync & Abort[TastyError])
    def list(dir: String, suffix: String): Chunk[String] < (Sync & Abort[TastyError])
    def exists(path: String): Boolean < Sync
```

* **JVM** (`jvm/.../JvmFileSource.scala`): `Files.readAllBytes` plus `jrt:/` URI support for the JDK module system (needed for `java.lang.*` resolution from JDK 25's `java.base` module).
* **JS** (`js/.../JsFileSource.scala`): on node, `fs.readFileSync` and copy `Buffer` to `Array[Byte]`. On browser, no path: `Tasty.Classpath.fromPickles(Seq[Pickle])` is the supported entry, with the consumer producing `Span[Byte]` blobs via `fetch` or other means.
* **Native** (`native/.../NativeFileSource.scala`): POSIX `open` / `read` via Scala Native FFI.

Classpath discovery (`list`, classpath scanning) is the platform-specific concern that varies:

* JVM: walk jars, extract `.tasty` entries.
* Native: walk directories, follow symlinks via FFI.
* JS: not supported in browser; node walks the local fs.

## 15. Concurrency Model: Phase A / B / C

Three phases for parallel classpath decode.

### Phase A: header sweep (parallel, ~100us per file)

```scala
val fqnIndex: Map[FQN, FileRef] < (Sync & Abort[TastyError] & Scope) =
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

Output: a global `Map[FQN, FileRef]`. Files keep their decoded name tables; nothing else materialized. ~10ms total for 200 files.

**Per-file inner `Scope.run`** is the load-bearing detail. `Scope` is a `ContextEffect` shared across all `Async.foreach` workers; without the inner scope, every `acquireRelease` registers into the outer scope's finalizer queue and handles release together at outer-scope exit, risking FD exhaustion. With the inner scope, the file handle releases as soon as that file's Phase A completes.

### Phase B: parallel body decode

```scala
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

Each fiber:

* Reads the file into `Array[Byte]` (handle released at end of inner scope).
* Owns its own `TypeArena` (no cross-fiber contention).
* Decodes definitions, signatures, parents, member-name lists eagerly.
* Records body slices as `Span[Byte]` views over the heap-resident bytes (the `Array[Byte]` lives as long as the symbol graph).
<!-- flow-allow: Phase C is the classpath orchestrator merge stage name; placeholder is the UnresolvedRef stand-in type; both are algorithmic terms, not delivery deferral -->
* Cross-file type references appear as an internal `UnresolvedRef(fqn)` placeholder (decoder-internal, NOT part of the public `Type` ADT), recorded in `result.placeholders` for Phase C resolution.
* Sets each symbol's `home: Classpath` to the eventual finalized Classpath via a shared `ClasspathBuilder` reference (see below).

### Symbol home assignment

Symbols carry `home: Classpath` (Section 7). To avoid a "null until finalize" state in `home`, the orchestrator constructs the `Classpath` instance BEFORE Phase B starts and passes it as the `home` reference to all symbols. The `Classpath` is a single mutable object whose internal state transitions through three phases:

```scala
final class Classpath private[kyo] (
    private val state: AtomicRef[Classpath.State]
):
    private[kyo] def checkOpen: Unit < (Sync & Abort[TastyError]) =
        state.get.map {
            case State.Building => Abort.fail(TastyError.ClasspathBuilding)
            case State.Ready    => Kyo.unit
            case State.Closed   => Abort.fail(TastyError.ClasspathClosed)
        }

object Classpath:
    private[kyo] enum State:
        case Building(symbols: ChunkBuilder[Symbol], arenas: Chunk[TypeArena])
        case Ready(symbols: Chunk[Symbol], canonical: TypeArena, fqnIndex: Map[FQN, Symbol])
        case Closed
```

Lifecycle:

1. Orchestrator constructs `cp = new Classpath(AtomicRef.init(State.Building(...)))`.
2. Phase B fibers create Symbols with `home = cp`. State is `Building`; resolving accessors would fail with `ClasspathBuilding` (but no user code can touch them yet, the `cp` is not exposed).
3. Phase C runs to completion, then orchestrator CAS-transitions state to `Ready` with the fully merged data.
4. `cp` is returned to user code from `Classpath.open`. From this point, accessors return real data.
5. Outer `Scope.run` exit fires a finalizer that CAS-transitions state to `Closed`. Subsequent accessor calls return `ClasspathClosed`.

The `home` reference is always the same `Classpath` instance from construction onward; no null, no re-assignment. `Symbol.home` is genuinely a stable `Classpath`, not `Classpath | Null`. The `ClasspathBuilding` state is internal and never observable from user code because the `Classpath` is not returned from `open` until Phase C completes the transition to `Ready`.

A new `TastyError.ClasspathBuilding` case is added for defense-in-depth (should never fire in practice; if it does, that's a bug in the orchestrator).

The heap-resident `Array[Byte]` is what makes "lazy bodies + no mmap" work: body slices reference heap memory that lives as long as the `Classpath`, not the file handle.

### Phase C: single-threaded merge + resolve (~50ms for 200 files)

```scala
val canonical = TypeArena.canonical()
for (file, result) <- allResults do
    for ph @ UnresolvedRef(fqn) <- result.placeholders do
        val targetSym = fqnIndex(fqn).resolveSymbol()
        result.replacePlaceholder(ph, Type.Named(targetSym))
    canonical.mergeFrom(result.arena)
```

* Cross-file references resolve through FQN lookup (O(1) hash).
* Structurally-equal types from different fibers collapse to one canonical instance.
* No I/O, sequential, cache-friendly.

### Why this is materially faster than tasty-query

* tasty-query loads per-package serially under `synchronized` locks. We load per-file in parallel with no locks during decode.
* On 8 cores, Phase B is ~5x throughput of tasty-query's serial loader.
* Phase A and C overheads are sub-linear in file count.
* Realistic delta: 3 to 5x faster cold load on a 30 to 50 module classpath.

### Failure modes

* Phase A fails on header: typed `TastyError.UnsupportedVersion`, file marked unreadable. Phase B skips it.
* Phase B fails inside one file: that file's symbols become entries with `kind = SymbolKind.Unresolved` (Section 7), name set to the originating FQN, error appended to `Classpath.errors`. Other files still decode.
<!-- flow-allow: Phase C is the classpath orchestrator merge stage; placeholder is the UnresolvedRef stand-in type; both are algorithmic terms, not delivery deferral -->
* Phase C fails to resolve a placeholder: cross-file ref becomes `Type.Named(unresolvedSymbol)`. Resolving accessors on the unresolved symbol return `Abort.fail(TastyError.SymbolNotFound)`.

Soft-fail mode is default; errors accumulate in `Classpath.errors`. Strict mode (`Classpath.open(roots, strict = true)`) fails the whole load on first error.

### Scope of `Async.foreach` interruption

If one file decode fails:

* `state.interruptDiscard(e)` interrupts all other surviving worker fibers.
* Workers' inner `Scope.run` finalizers fire on interrupt, releasing their file handles.
* The outer `Scope.run` (around the whole `openCached` call) eventually fires its finalizers too.

No handle leaks. The inner-scope pattern is what makes this work.

## 16. Snapshot Format (KRFL)

Skip unpickle entirely on warm runs.

```
+------------------+
| magic    "KRFL"  | 4
| version  M.m.p.0 | 4
| flags            | 8
+------------------+
| inputDigest      | 32   (SHA-256 of sorted file paths + mtimes + sizes)
| compilerVersion  | 16   (Scala major.minor.exp.0 + reserved)
+------------------+
| sectionCount     | 4
| sectionIndex     | sectionCount * 24
|   name           | 8    (fixed-length section ID)
|   offset         | 8
|   length         | 8
+------------------+
| section: NAMES      | byte arena + (offset, length) table
| section: SYMBOLS    | packed records, fixed size each
| section: TYPES      | packed records, fixed size each
| section: TYPES_EXTRA| variable-length operand data for multi-operand types
| section: PARENTS    | int arrays
| section: MEMBERS    | int arrays
| section: FILES      | (path, mtime, size, uuid) records
| section: BODY_BYTES | inline byte storage for lazy body decode
| section: ERRORS     | serialized TastyError cases
+------------------+
```

### Open path

```scala
def openCached(roots: Seq[Path], cacheDir: Path) =
    for
        currentDigest <- computeDigest(roots)
        snapshot      <- findSnapshot(cacheDir, currentDigest)
        cp <- snapshot match
            case Present(file) => loadSnapshot(file)             // ~5ms for kyo-size
            case Absent        =>
                for
                    cp <- openFresh(roots)                        // full decode
                    _  <- writeSnapshotAtomically(cp, cacheDir, currentDigest)
                yield cp
    yield cp
```

### Sections in detail

* **NAMES**: shared byte arena plus `(offset, length)` table indexed by `NameId`.
* **SYMBOLS**: packed records, fixed size each. Each record contains `(kindByte, flags: Long, nameId, ownerId, declaredTypeId, parentsListId, membersListId, bodyFileId, bodyStart, bodyEnd)`.
* **TYPES**: packed records indexed by canonical type ID. Each record contains `(kindByte, operandAId, operandBId, extraDataOffset)`. Multi-operand types (Applied, Function, Tuple, MatchType) reference the `TYPES_EXTRA` section by offset.
* **TYPES_EXTRA**: variable-length operand storage for types that need more than two operands.
* **PARENTS**: int arrays of type IDs for class parent lists.
* **MEMBERS**: int arrays of symbol IDs for class member lists.
* **FILES**: per-source-file metadata `(path, mtime, size, uuid)`.
* **BODY_BYTES**: inline byte storage for lazy body decode (slices in SYMBOLS reference offsets here).
* **ERRORS**: serialized `TastyError` cases accumulated during decode. Snapshot reload restores them in `Classpath.errors`.

### Symbol home and serialization

Snapshots serialize symbols WITHOUT their `home` reference (it's session-bound). On load, the snapshot reader fills in `home` to point at the freshly-constructed `Classpath`. The snapshot is Classpath-relative on serialize, absolute on deserialize. Pattern matches the `ClasspathBuilder` design in Section 15.

### Concurrent process access

Multiple processes (sbt, IntelliJ, CLI tooling) may hit the same cache directory concurrently.

Strategy: tmp-file plus atomic rename.

```
write   -> tmp-${digest}-${pid}-${nonce}.krfl
fsync
rename  -> ${digest}.krfl   (atomic on POSIX, Windows uses MOVEFILE_REPLACE_EXISTING)
```

If two processes decode the same input concurrently, both write tmp files with identical content (decode is deterministic) and both rename. The last rename wins; the loser's tmp is silently discarded. No file locking. Documented.

Cleanup of stale tmp files (e.g., from crashed writers): `Tasty.Snapshot.evictOlderThan(d)` also removes tmp files older than 1 hour.

### Input digest policy

For directories of `.tasty` files: SHA-256 of sorted `(path, mtime, size)` tuples. Fast.

For JAR files: SHA-256 of sorted `(jar path, jar mtime, jar size)`. Does not hash JAR contents.

Failure mode: a file with the same mtime and size but different content (e.g., `touch -t` followed by content edit through filesystem manipulation) is not detected. Build systems do not produce this case; manual file manipulation can.

Mitigation: `Tasty.Classpath.openCached(roots, mode = ParanoidContent)` opts into content hashing (SHA-256 of file bytes per input). Slower but detects any change. Documented trade-off.

### Endianness

All multi-byte integers in the snapshot are little-endian. Modern platforms (x86_64, ARM64) are LE; reading on a BE platform performs explicit byte swap.

The header carries a `byteOrder: Byte` field (0 = LE, 1 = BE). Reader checks; mismatch triggers byte swap or rejects depending on platform support.

### Versioning policy

Snapshot header carries `(kyoTastyMajor, kyoTastyMinor, kyoTastyPatch)`. Read path requires `major == compilerSupportMajor && minor <= compilerSupportMinor`.

* Major bump (format break): invalidate all old snapshots, full re-decode, write new.
* Minor bump: add-only sections, old snapshots load (and may have empty new sections).
* Patch bump: format-stable.

On mismatch, the loader produces `TastyError.SnapshotVersionMismatch` and falls through to full decode + fresh write.

### Eviction

Automatic eviction: none. Snapshot files are user data; we don't delete them silently.

Explicit eviction: `Tasty.Snapshot.evictOlderThan(d: Duration): Unit < (Sync & Scope)`. Walks the cache directory, removes snapshots and tmp files older than `d`.

### Browser no-op cache

`Tasty.Classpath.openCached` is universal across platforms. On JS browser (no filesystem), the platform module detects browser at runtime and degrades to `open(roots)` directly: always-miss, never-write. On JS node, full cache works via `fs.readFileSync` / `fs.writeFileSync`. Documented.

### Cross-platform read path

The earlier objection to mmap in Section 5 (FD exhaustion with many small `.tasty` files) does not apply to the snapshot: it's one big file, one handle, one Arena finalizer. mmap with demand paging avoids reading sections we never touch (typical workload: `BODY_BYTES` section is never paged in for codegen workloads, saving ~60% of total bytes).

* **JVM (preferred path)**: `FileChannel.map(MapMode.READ_ONLY, 0, size)` returns a `MappedByteBuffer`, or on JDK 22+ a `MemorySegment` via `Arena.ofShared.allocate(...)`. The Classpath holds one Arena; `Scope.ensure` closes it at scope exit. Body slices reference offsets into the mapped region directly; demand paging brings them in lazily on first touch.
* **Native**: POSIX `mmap()` via Scala Native FFI. Same pattern: single handle, demand paging, munmap on Scope exit.
* **JS**: no mmap. `fs.readFileSync` on node, browser falls through to `Tasty.Classpath.fromPickles` which doesn't use the cache at all. Read path is ~3x slower than mmap (50ms for 5MB vs ~10ms), but still 10x faster than full cold decode.

The reader abstraction (`ByteView`) accepts either an `Array[Byte]` (JS) or a mmap'd region (JVM/Native). The `Memory[Byte]` from `kyo-offheap` is the JVM+Native API; for JS we degrade to `Array[Byte]`. ByteView gains a sealed adapter:

```scala
sealed trait ByteView:
    def peekByte(at: Int): Byte
    def subView(from: Int, until: Int): ByteView
    // ... etc

object ByteView:
    final class Heap(bytes: Array[Byte], start: Int, end: Int) extends ByteView
    final class Mapped(segment: java.lang.foreign.MemorySegment, start: Long, end: Long) extends ByteView
    // (Mapped only available on JVM/Native via platform-specific compilation)
```

On JS the `Mapped` case doesn't exist (platform-specific source files). On JVM/Native the snapshot loader returns `Mapped`; the .tasty file loader returns `Heap` (because of the FD exhaustion concern).

### Snapshot expected times (revised)

| Workload | Target |
|---|---|
| JVM mmap reload, codegen workload (bodies not touched) | 5 to 15 ms |
| JVM mmap reload, IDE workload (all sections touched) | 20 to 40 ms |
| Native mmap reload | 10 to 30 ms |
| JS node read-into-Array | 50 to 150 ms |

Compare to full cold decode at 100 to 200 ms (Section 2): snapshot wins everywhere by 3 to 30x.

### Size

For a kyo-size classpath (30 modules, ~600 classes, ~5000 methods), 2 to 5 MB on disk. Small.

## 17. Versioning

We pin to a specific Scala 3 minor release per kyo-tasty release. The version triple in `TastyFormat.scala` matches `dotty.tools.tasty.TastyFormat.{MajorVersion, MinorVersion, ExperimentalVersion}` of the chosen Scala version. CI runs against TASTy files produced by that exact compiler; we test both backward (older minor) and forward (newer minor produces `TastyError.UnsupportedVersion`) compatibility.

Bumping the supported Scala version is a versioned change: kyo-tasty `X.Y.Z` supports Scala `S.M.E`; updating to `S.(M+1)` ships as kyo-tasty `X.(Y+1).0`. Multi-version support is out of scope for v1.

## 18. Phased Implementation

Nine phases (numbered 0 through 7 with 5b and 6b inserted as cohesive add-ons). Each phase ships green tests and a commit. No phase compresses multiple deliverables.

<!-- flow-allow: bench-harness is the canonical name for kyo-tasty-bench, not an LLM-tell hedge -->
**Phase 0: skeleton + bench harness.** Module setup in `build.sbt` (cross JVM/JS/Native). `kyo.Tasty` stub object. Bench harness module (`kyo-tasty-bench`) targeting tasty-query for comparison. Golden TASTy fixture files (one tiny module producing all tag categories) checked in.

**Phase 1: binary primitives and TASTy header.** `ByteView`, `Varint`, `Utf8`. Tag table constants. Magic and version check. Pickle UUID. `TastyError.UnsupportedVersion` and `MalformedSection`. Tests: golden TASTy round-trip header reads, version-mismatch failure, corrupted-magic failure.

**Phase 2: name table and section index.** Name unpickler. Sharded intern. Section index. Attributes section. Tests: name decode against compiler-known names, intern equality, attribute flag extraction.

**Phase 3: symbol pass 1 + skeleton AST.** AST unpickler skeleton pass: definitions, names, flags, parents, member names. `Addr -> Symbol` map. Body pickles stored as `(start, end)` slices. Tests: enumerate top-level classes / members of fixture module; cross-reference forward type params.

**Phase 4: type model + per-fiber arenas + Phase C merge.** `Type` ADT. Type construction in unpickler. Type normalization. Per-thread `TypeArena`. Phase C merge canonicalization. `SHAREDtype` dedup. Tests: every type form represented; structural equality across files.

**Phase 5: classfile reader.** Constant pool, class/method/field structure, generic signature parsing. Surface as `Symbol` with `JavaDefined`. Tests: resolve `java.lang.String.length`, generic signatures (`java.util.List`).

**Phase 5b: Java/Scala unification.** FQN canonicalization (dotted form, `binaryName` for JVM form), Type.Array normalization for Java arrays, `JavaMetadata` side door, SymbolKind matrix coverage in tests. Tests: cross-language `findClass` round-trip, `java.util.Map.Entry` lookup, generic signatures preserve type params.

**Phase 6: `Tasty.Reads` derivation macro.** `Reads` trait. `derived` macro implementation. `touchedFields` static analysis. Hygiene guards. Built-in `Reads` instances per Section 13 table. Tests: derive readers for fixture case classes, verify `touchedFields` is correct, verify unpickler pruning works.

**Phase 6b: Record interop.** `Tasty.symbolToRecord[F]` macro. Built-in `Reads[Record[F]]`. Field-to-accessor mapping table per Section 12. Tests: project Symbols into Records, verify compile-time errors on bad field names, exercise `Record.stage[T].using[TypeClass]` bridging pattern.

**Phase 7: query API, file sources, snapshot cache, cross-platform.** Classpath open/close lifecycle. `findClass`, `query`, `stream`. JVM file source (jrt support). JS file source (node, fromPickles for browser). Native file source. Snapshot read/write. Phase A/B/C orchestration. Tests: full surface cross-platform on kyo modules' TASTy files; benchmarks against tasty-query.

## 19. Testing

Per `feedback_all_platforms_all_tests`: shared/ for all cross-platform tests. No platform-specific test demotion. Tests use the public API on the left-hand side; internals only on the right-hand side for verification (`feedback_tests_use_public_api`).

Fixtures: a small `kyo-tasty-fixtures` sub-module compiled by the build, producing TASTy bytes that exercise all node tags. Bytes checked in as golden files (one file per Scala version) so CI is hermetic.

Snapshot tests verify round-trip identity: full decode -> write snapshot -> read snapshot -> compare structural equality.

Cross-version: matrix the test suite over supported Scala versions when more than one is supported. v1 is single-version.

## 20. Benchmarking

<!-- flow-allow: bench-harness is the canonical name for kyo-tasty-bench, not an LLM-tell hedge -->
Bench harness compares kyo-tasty vs tasty-query on JVM (the only platform tasty-query supports). Workloads:

1. Cold-load whole kyo classpath, enumerate all top-level classes.
2. Cold-load with snapshot cache miss.
3. Warm-load with snapshot cache hit.
4. Per-FQN lookup of 100 random classes (warm cache).
5. Member enumeration on `kyo.Sync`.
6. Schema-driven traversal (`derives Tasty.Reads`) collecting `FacadeType`.

Pass criteria for v1:

| Workload | Target vs tasty-query |
|---|---|
| Cold-load enum (no cache) | 3 to 5x faster |
| Cold-load enum (snapshot miss, write) | 3 to 5x faster |
| Warm-load (snapshot hit) | 25 to 100x faster (tasty-query has no equivalent) |
| Per-FQN lookup, warm | parity to 1.5x |
| Schema-driven traversal | 2 to 4x faster (touched-fields pruning) |
| Memory (cold load) | 2 to 3x lower |

## 21. Risks

* **Scala version churn**: the compiler bumps the format every couple of minor releases. Single `TastyFormat.scala` constants file tracks this; maintenance is real but bounded.
* **JS file enumeration**: browser environments do not have classpath enumeration. Consumers must enumerate explicitly via `fromPickles`. Documented limitation.
* **Cross-platform UTF-8 decode**: three paths (JVM `String`, JS `TextDecoder`, Native libc), three test cases.
* **Classfile edge cases**: Java generic signatures have edge cases (inner-class signatures, intersection bounds). v1 may have gaps documented via failing tests.
* **Macro hygiene**: covered by the precautions in Section 13, but corner cases are inevitable. Reserve test budget for failing-then-fixed hygiene cases.
* **Phase C scaling**: at very large classpaths (~10K files, ~500K cross-file refs), Phase C becomes the bottleneck (~500ms estimated). Mitigation by sharding the merge is deferred to v2.
* **Snapshot format evolution pain**: every breaking change burns a major version. Users see "first build after upgrade is slow." Documented.
* **Macro hand-write fallback**: hand-written `Reads` instances lose the touched-fields optimization, defaulting to "needs everything." Conservative but reverts to baseline perf.

## 22. Resolved Decisions

All four decisions locked under the "complete and correct, no scope cuts" constraint. Specifics are folded into the relevant sections; this section summarizes the resolutions.

1. **Object-graph design with parallel + lazy + snapshot + macro stack** (replaces the SoA / arena / opaque-Int proposal). LOCKED. The prior-art analysis (Section 23) showed SoA contributes 1.5 to 2x at best and adds substantial maintenance cost; object-graph with parallel decode, lazy bodies, hash-consed types, sharded intern, and snapshot cache delivers the 3 to 5x target without the structural risk. Interactions:
   * Snapshot stores fully-decoded skeleton (Memo/SingleAssign pre-populated, bodies inline).
   * Snapshot is always full, not schema-pruned. Schemas change between sessions; we want snapshot reuse.
   * Phase C produces a canonical type arena; snapshot writes it index-based on disk; reload reconstructs in one walk, no Phase C needed.

2. **`derives Tasty.Reads` as v1**. LOCKED. Feasibility confirmed against `StructureMacro` and `FocusMacro.extractAllFocusFieldNames` precedents (Section 13). 400 to 600 LOC including:
   * Recursive case classes via `lazy val instance`.
   * Product-type derivation only in v1; ADTs require hand-written instances (5 to 15 lines, worked example in Section 13).
   * `given` override for custom field readers (no annotation magic).
   * Built-in `Reads` instances for Name, Flags, Type, Symbol, Chunk, Maybe, primitives, tuples to arity 22.
   * Transitive `touchedFields` static analysis across composed `Reads`.
   * Hygiene guards (Trees.exists pre-check, skip Match pattern internals) per PR #1633 patterns.

3. **Persistent snapshot cache as v1**. LOCKED. KRFL format (Section 16) with:
   * Atomic-rename concurrent write strategy, no file locking.
   * mtime+size digest by default, content-hash via `ParanoidContent` mode.
   * Little-endian byte order with explicit byte-order flag in header.
   * Cached `TastyError`s in dedicated ERRORS section.
   * Browser no-op cache (degrades to `open` directly).
   * Version-compat policy: major bumps invalidate, minor bumps add-only.

4. **Symbol home + classpath ergonomics** (revised from `Local[Classpath]`). LOCKED. Every `Symbol` carries a `home: Classpath` reference. Resolving accessors use `home` internally; pure accessors don't touch `home` and work after classpath close. Cross-classpath comparison via structural FQN equality, not reference equality. `Local[Classpath]` magic is NOT introduced; queries take `Classpath` explicitly via `extension (cp: Classpath)`. This is correctness, not convenience: Symbols are semantically tied to their decoding classpath. +8 bytes per Symbol, ~40 KB for kyo-size, negligible.

Cross-platform scope (JVM + JS + Native) is locked. Java classfile reader in v1 is locked. Skeleton-eager + lazy bodies is locked.

### Other locked decisions surfaced during exploration

5. **Unified Java + Scala model** (Section 11). LOCKED. Single `Symbol` and `Type` ADT; source-discriminated via `flags`. Java-specific data lives behind `Symbol.javaSpecific`. FQN canonicalization to dotted form; JVM binary form via `Symbol.binaryName`.

6. **Closed `SymbolKind` enum**. LOCKED. Favors exhaustive matching for Scala/Java callers over open extensibility. A future `kyo-cbindings` sibling (Section 25) would define its own parallel `CSymbolKind` rather than extending this one. Trade-off accepted: keeps the kyo-tasty API tight; minor cross-module duplication when adding the C bridge.

7. **Module name `kyo-tasty`** (replaces the legacy `kyo-reflect` proposal). LOCKED. The unified Java+Scala scope is captured by the API surface (Symbol, Type, Classpath), not by the module name. TASTy and classfile are the two primary input formats; `kyo-tasty` names the primary format while classfile support is implied by scope.

8. **Snapshot magic `KRFL`** (replaces `KTSY`). LOCKED. Snapshot contains both TASTy and classfile symbols; TASTy-specific magic would be misleading.

9. **Record interop for compile-time field iteration** (Sections 11, 12, 13). LOCKED. `Tasty.symbolToRecord[F]` projects a Symbol into a typed `Record[F]`; `Reads[Record[F]]` is a built-in derivation target; the `Record.stage[T].using[TypeClass]` pattern is the canonical bridging idiom for FFI, ABI translation, and codegen. Case-class-based `Reads` remains the default; Records supplement it for use cases that need compile-time field iteration with per-field type-class dispatch.

## 23. Prior-Art Analysis Summary

Research (separate document, summarized here) into how production compiler frontends represent symbol graphs:

* **dotty** (Scala 3 compiler): object-graph. `Symbol` + `SymDenotation` + `Type`. Hash-consed types via `uniques: WeakHashSet[Type]`. Single-threaded, no synchronization. Per-symbol ~100 to 140 bytes for `SymDenotation`.
* **Roslyn** (C# compiler): object-graph with Red/Green tree split. ~45% indexing speedup from removing parent pointers and positions from hot nodes. Object pooling for short-lived builders. `ITypeSymbol` not value-internable.
* **IntelliJ stubs**: on-disk integer-indexed name tables; materialize into objects on demand. No in-memory SoA.
* **Kotlin compiler**: hashing-based name dependency graphs for incremental; full symbol model is object-graph.
* **rustc**: object-graph with interning + arenas. Performance campaigns: bitset changes, hash table tweaks, allocation avoidance. No SoA. Explicit choice.
* **GHC `.hi` files**: integer-indexed name tables on disk, lazy type decode, materialize into object graph in memory.

Conclusion: every production compiler that has considered SoA picked object-graph. The dominant perf levers are interning, hash-consing, lazy decode, and parallelism. SoA contributes 1.5 to 2x at best on JVM and adds substantial maintenance cost. Object-graph with the right optimizations delivers 3 to 5x without the structural risk.

The closest analog to kyo-tasty's design is GHC's `.hi` format combined with Roslyn's hot-node minimization (no parent pointers, no positions). Both are mature, both validate the architectural choices here.

## 24. Out of Scope (v1, may revisit)

* Scala 2 pickle reader.
* Position section. Comments section.
* Tree body decoding (slice stored, decoding unimplemented).
* Subtype checking and type comparison beyond structural equality.
* TASTy writing. Classfile writing.
* Multi-Scala-version support in one release.
* Incremental classpath refresh.
* Phase C sharding for very large monorepos.
* Hand-written `Reads` instances participating in `touchedFields` optimization.
* Java module-info.class (JPMS).
* C/C++ header parsing (sibling module, see Section 25).

## 25. Future Siblings

`kyo-tasty` is the first member of an intended family of compile-time metadata libraries sharing architectural patterns. Documenting the anticipated siblings here so the design doesn't paint itself into a corner.

### `kyo-cbindings` (anticipated)

Reads C headers via libclang (LLVM-based) and surfaces them through a `CSymbol` + `CType` model that parallels kyo-tasty's `Symbol` + `Type` but tracks C-specific concerns (pointers, qualifiers, calling conventions, bit-fields).

**Why a sibling and not a subpackage of kyo-tasty**:

* **Platform constraints**: libclang is JVM and Native only (no browser JS path). kyo-tasty is JVM+JS+Native. Bundling C breaks the cross-platform guarantee.
* **Type-system mismatch**: C types need cases that don't exist in Scala (`Pointer`, `CArray(size)`, `FunctionPointer(callingConv)`, `CQualifier`, bit-field structs). Forcing them into Scala's `Type` ADT corrupts both.
* **Dependency footprint**: libclang is a large native dep. kyo-tasty should remain dep-free.
* **Lifecycle**: C bindings are emitted at build time and consumed at runtime; that's a different shape than the always-on classpath query kyo-tasty provides.

**What's shared**:

* The `derives X.Reads`-style macro pattern (kyo-tasty's `ReadsMacro` is the template).
* The KRFL-style snapshot cache architecture (atomic-rename, mtime+size digest, ERRORS section, version-compat policy).
* The Phase A / B / C parallel decode protocol.
* The closed-error-ADT + Kyo-effects API shape.
* Cross-platform binary primitives (`ByteView`, `Varint`, `Utf8`) if promoted to `kyo-data`.

**What's NOT shared**:

* `Symbol` and `Type` types (parallel hierarchies, not extension of kyo-tasty's).
* `SymbolKind` enum (kyo-tasty's stays closed for exhaustive matching; `kyo-cbindings` defines `CSymbolKind`).
* File source abstraction (C headers are text + include paths; TASTy/classfile are binary).

### `kyo-scaladoc` (speculative)

Reads scaladoc comments from TASTy's `Comments` section (which kyo-tasty skips in v1). Could share kyo-tasty's symbol model directly since the comments attach to existing `Symbol` instances. Likely shipped as an `extension (sym: Symbol)` enrichment in a separate module that depends on kyo-tasty.

### What this means for kyo-tasty today

The future-siblings consideration shapes three present-day choices already locked in Section 22:

1. **Closed `SymbolKind`**: better Scala/Java ergonomics; siblings parallel rather than extend.
2. **Architectural patterns** documented explicitly (Phase A/B/C, snapshot KRFL layout, `derives X.Reads` macro shape) so siblings can copy them, not just code.
3. **Cross-platform binary primitives kept extractable**: `ByteView`/`Varint`/`Utf8` are pure functions with no kyo-tasty-specific dependencies. Promoting to `kyo-data` when a second consumer materializes is a small change.
