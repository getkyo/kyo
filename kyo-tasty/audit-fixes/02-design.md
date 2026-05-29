# 02 Design: Remediate all 48 findings from the kyo-tasty audits (correctness C1-C4, completeness M1-M10, clarity L1-L7, potential bugs B1-B15, test coverage T1-T8, CONTRIBUTING.md adherence A1-A4)

Task type: refactor
Cites exploration: ./01-exploration.md
Cites inventory: ./audit-findings.md
Cites steering: ./steering.md

## Goal

Bring `kyo-tasty` into full compliance with the 48-finding audit inventory while preserving the tuned cold-load and warm-cache performance budget. The user-visible target state: every routine `Symbol` and `Classpath` accessor carries an explicit `(using AllowUnsafe)` proof rather than hiding it via `import AllowUnsafe.embrace.danger`; lazy `Symbol.body` and `Annotation.args` decoders cover every TASTy AST and Type tag the Scala 3.6+ compiler emits at v28.8; cross-platform `InflateHook` no longer returns `NotImplemented` on JS or Native; subtype queries surface the under-determination signal cleanly; snapshot warm-restore reconstructs the full `_parents` / `_typeParams` / `_declarations` chunks; binary-decode primitives reject out-of-bounds inputs with a structured `TastyError.MalformedSection`; JAR offset arithmetic handles Zip64 and 64-bit offsets without `.toInt` truncation; the README and DESIGN reflect the `kyo.Tasty.*` namespace consistently with the source; classfile parsing covers `BootstrapMethods`, `NestHost`, `NestMembers`, `PermittedSubclasses`, `MethodParameters`, and `RuntimeTypeAnnotations`. Test coverage extends to every internal primitive (`Varint`, `ConstantPool`, `OnceCell`, `SingleAssign`, `SnapshotFormat`, `DigestComputer`, platform `InflateHook`) and the existing topic test files gain numbered scenarios for every untested ADT case, edge input, and concurrency interaction in `T1` through `T8`.

## API surface

Every entry quotes the new public signature verbatim, names the source file and the prefix-matched test file, and cites the finding code plus the steering or CONTRIBUTING.md rationale.

### Symbol accessor signatures take `(using AllowUnsafe)` (A4, A2)

Per `steering.md:35-49` and CONTRIBUTING.md §828 option 1 (`CONTRIBUTING.md:828-831`). The accessor body unwraps the underlying `OnceCell` / `SingleAssign` storage directly; no `Sync.Unsafe.defer` wrap, no `import AllowUnsafe.embrace.danger` inside the body.

- `def asString(using AllowUnsafe): String` (extension on `Name`)
  Role: decode the interned name's bytes to a String.
  Rationale: A4 against `Tasty.scala:60-63`; current body uses `import AllowUnsafe.embrace.danger` at `Tasty.scala:62`.
  Source file: `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala`
  Test file: `kyo-tasty/shared/src/test/scala/kyo/TastyTest.scala` (new, prefix `Tasty`) or extends `kyo/UnifiedModelTest.scala`.
- `def fullName(using AllowUnsafe): Name`
  Role: canonical dotted FQN of the Symbol.
  Rationale: A4 against `Tasty.scala:545-549`; current body embraces at `:547`.
  Source file: `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala`
  Test file: `kyo-tasty/shared/src/test/scala/kyo/TastySymbolTest.scala`
- `def isPackageObject(using AllowUnsafe): Boolean`
  Role: distinguishes package-object Symbols.
  Rationale: A4 against `Tasty.scala:554-558`; current body embraces at `:556`.
  Source file: `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala`
  Test file: `kyo-tasty/shared/src/test/scala/kyo/TastySymbolTest.scala`
- `def scaladoc(using AllowUnsafe): Maybe[String]`
  Role: scaladoc text for definitions that carry comments.
  Rationale: A4 against `Tasty.scala:568-573`; current body embraces at `:570`.
  Source file: `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala`
  Test file: `kyo-tasty/shared/src/test/scala/kyo/TastySymbolTest.scala`
- `def position(using AllowUnsafe): Maybe[Position]`
  Role: source-file location for the definition.
  Rationale: A4 against `Tasty.scala:582-587`; current body embraces at `:584`.
  Source file: `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala`
  Test file: `kyo-tasty/shared/src/test/scala/kyo/TastySymbolTest.scala`
- `def declaredType(using AllowUnsafe): Type`
  Role: declared type of a Val, Var, Method, Field, TypeAlias, OpaqueType, AbstractType, TypeParam, or Parameter.
  Rationale: A4 against `Tasty.scala:604-611`; current body embraces at `:610`.
  Source file: `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala`
  Test file: `kyo-tasty/shared/src/test/scala/kyo/TastySymbolTest.scala`
- `def parents(using AllowUnsafe): Chunk[Type]`
  Role: parent types of a Class, Trait, or Object.
  Rationale: A4 against `Tasty.scala:617-622`; current body embraces at `:620`.
  Source file: `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala`
  Test file: `kyo-tasty/shared/src/test/scala/kyo/TastySymbolTest.scala`
- `def typeParams(using AllowUnsafe): Chunk[Symbol]`
  Role: declared type parameters.
  Rationale: A4 against `Tasty.scala:628-633`; current body embraces at `:631`.
  Source file: `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala`
  Test file: `kyo-tasty/shared/src/test/scala/kyo/TastySymbolTest.scala`
- `def declarations(using AllowUnsafe): Chunk[Symbol]`
  Role: members declared inside this Class or Object.
  Rationale: A4 against `Tasty.scala:639-644`; current body embraces at `:642`.
  Source file: `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala`
  Test file: `kyo-tasty/shared/src/test/scala/kyo/TastySymbolTest.scala`
- `def companion(using AllowUnsafe): Maybe[Symbol]`
  Role: companion class or module Symbol, if any.
  Rationale: A4 against `Tasty.scala:654-688`; current body relies on internal accessors that themselves embrace.
  Source file: `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala`
  Test file: `kyo-tasty/shared/src/test/scala/kyo/TastySymbolTest.scala`

### Symbol.body internal bridge (A4)

`Symbol.body` keeps the existing public effect-row signature; the internal `_bodyOnce.get()` call moves under a `Sync.Unsafe.defer { _bodyOnce.get() }` bridge per CONTRIBUTING.md §833 option 2 (`CONTRIBUTING.md:833-837`). No `import AllowUnsafe.embrace.danger` inside the accessor body.

- `def body(using Frame): Tree < (Sync & Abort[TastyError])`
  Role: lazily decode the Symbol's body Tree.
  Rationale: A4 against `Tasty.scala:703-747`; current body embraces at `:719` and `:995`.
  Source file: `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala`
  Test file: `kyo-tasty/shared/src/test/scala/kyo/TreeUnpicklerTest.scala`

### Symbol.TastyOrigin.addrMap visibility (A4)

`addrMap` no longer appears in the public Origin surface; it moves to `private[kyo]` per `steering.md:54`. The accessor's signature loses the public `(using AllowUnsafe)` slot because the only callers live inside `kyo.internal.tasty.*`.

- `private[kyo] def addrMap: IntMap[Tasty.Symbol]`
  Role: address-keyed Symbol table used by `TreeUnpickler` during lazy body decode.
  Rationale: A4 against `Tasty.scala:862`.
  Source file: `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala`
  Test file: covered indirectly by `kyo-tasty/shared/src/test/scala/kyo/TreeUnpicklerTest.scala` body-decode scenarios.

### Classpath pure accessors take `(using AllowUnsafe)` (A4)

Per `steering.md:35-49`. Each internal `final class Classpath` accessor referenced by the opaque-type `Tasty.Classpath` surface adopts the §828 propagate-the-proof signature.

- `def pureClass(fqn: String)(using AllowUnsafe): Maybe[Tasty.Symbol]`
  Role: synchronous class lookup against the Ready-state index.
  Rationale: A4 against `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/query/Classpath.scala:70`.
  Source file: `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/query/Classpath.scala`
  Test file: `kyo-tasty/shared/src/test/scala/kyo/ClasspathOrchestratorPipelineTest.scala`
- `def purePackage(fqn: String)(using AllowUnsafe): Maybe[Tasty.Symbol]`
  Role: synchronous package lookup.
  Rationale: A4 against `Classpath.scala:80`.
  Source file: `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/query/Classpath.scala`
  Test file: `kyo-tasty/shared/src/test/scala/kyo/ClasspathOrchestratorPipelineTest.scala`
- `def pureModule(name: String)(using AllowUnsafe): Maybe[Tasty.ModuleDescriptor]`
  Role: synchronous JPMS module lookup.
  Rationale: A4 against `Classpath.scala:90`.
  Source file: `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/query/Classpath.scala`
  Test file: `kyo-tasty/shared/src/test/scala/kyo/ModuleInfoTest.scala`
- `def pureTopLevelClasses(using AllowUnsafe): Chunk[Tasty.Symbol]`
  Role: every top-level class registered in the classpath.
  Rationale: A4 against `Classpath.scala:100`.
  Source file: `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/query/Classpath.scala`
  Test file: `kyo-tasty/shared/src/test/scala/kyo/ClasspathOrchestratorPipelineTest.scala`
- `def purePackages(using AllowUnsafe): Chunk[Tasty.Symbol]`
  Role: every package Symbol.
  Rationale: A4 against `Classpath.scala:110`.
  Source file: `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/query/Classpath.scala`
  Test file: `kyo-tasty/shared/src/test/scala/kyo/ClasspathOrchestratorPipelineTest.scala`
- `def accumulatedErrors(using AllowUnsafe): Chunk[TastyError]`
  Role: every non-fatal error captured during cold-load.
  Rationale: A4 against `Classpath.scala:138`.
  Source file: `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/query/Classpath.scala`
  Test file: `kyo-tasty/shared/src/test/scala/kyo/ClasspathOrchestratorPipelineTest.scala`
- `def allSymbols(using AllowUnsafe): Iterator[Tasty.Symbol]`
  Role: iteration helper used by snapshot writer.
  Rationale: A4 against `Classpath.scala:156`.
  Source file: `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/query/Classpath.scala`
  Test file: `kyo-tasty/shared/src/test/scala/kyo/SnapshotRoundTripTest.scala`
- `def transitionToReady(state: Classpath.ReadyState)(using AllowUnsafe): Unit`
  Role: orchestrator-only state transition.
  Rationale: A4 against `Classpath.scala:214`.
  Source file: `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/query/Classpath.scala`
  Test file: covered via `kyo-tasty/shared/src/test/scala/kyo/ClasspathOrchestratorPipelineTest.scala`
- `def close()(using AllowUnsafe): Unit`
  Role: release held file handles.
  Rationale: A4 against `Classpath.scala:222`.
  Source file: `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/query/Classpath.scala`
  Test file: `kyo-tasty/shared/src/test/scala/kyo/ClasspathOrchestratorPipelineTest.scala`
- `def isClosed(using AllowUnsafe): Boolean`
  Role: closed-state check.
  Rationale: A4 against `Classpath.scala` close-state accessor.
  Source file: `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/query/Classpath.scala`
  Test file: `kyo-tasty/shared/src/test/scala/kyo/ClasspathOrchestratorPipelineTest.scala`

### ClasspathRef accessors take `(using AllowUnsafe)` (A4)

- `def get(using AllowUnsafe): Tasty.Classpath`
  Rationale: A4 against `ClasspathRef.scala:21`.
  Source file: `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/query/ClasspathRef.scala`
  Test file: `kyo-tasty/shared/src/test/scala/kyo/ClasspathRefDedupTest.scala`
- `def assign(cp: Tasty.Classpath)(using AllowUnsafe): Unit`
  Rationale: A4 against `ClasspathRef.scala:28`. The `assign` site is also a §839 case 3 (initialization) candidate; design keeps the `(using AllowUnsafe)` signature so callers pass the proof rather than the body embracing it.
  Source file: `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/query/ClasspathRef.scala`
  Test file: `kyo-tasty/shared/src/test/scala/kyo/ClasspathRefDedupTest.scala`
- `def isAssigned(using AllowUnsafe): Boolean`
  Rationale: A4 against `ClasspathRef.scala:35`.
  Source file: `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/query/ClasspathRef.scala`
  Test file: `kyo-tasty/shared/src/test/scala/kyo/ClasspathRefDedupTest.scala`

### Classpath.open overload organization (A2)

Per CONTRIBUTING.md §358-§374 (canonical impl + variants delegation). The canonical implementation owns the body; the simple variant delegates by passing the missing argument explicitly. Q-007 decides which overload is canonical.

- `def open(roots: Seq[String])(using Frame): Tasty.Classpath < (Sync & Async & Scope & Abort[TastyError])`
  Rationale: A2 against `Tasty.scala:899-900`.
- `def open(roots: Seq[String], strict: Boolean)(using Frame): Tasty.Classpath < (Sync & Async & Scope & Abort[TastyError])`
  Rationale: A2 against `Tasty.scala:903-904`.
  Source file: `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala`
  Test file: `kyo-tasty/shared/src/test/scala/kyo/QueryApiTest.scala`

### Snapshot.evictOlderThan overload organization (A4)

Per CONTRIBUTING.md §362-§374. The current `evictOlderThanWithSource` duplicates the body of `evictOlderThan` instead of one delegating to the other.

- `def evictOlderThan(cacheDir: String, maxAgeMs: Long)(using Frame): Unit < (Sync & Abort[TastyError])`
- `@scala.annotation.targetName("evictOlderThanDuration") def evictOlderThan(cacheDir: String, d: Duration)(using Frame): Unit < (Sync & Abort[TastyError])`
- `private[kyo] def evictOlderThanWithSource(cacheDir: String, maxAgeMs: Long, source: kyo.internal.tasty.query.FileSource)(using Frame): Unit < (Sync & Abort[TastyError])`
  Rationale: A4 against `Tasty.scala:1113-1152`. `evictOlderThanWithSource` is the canonical implementation; the two public overloads delegate to it by supplying the default `kyo.internal.tasty.query.PlatformFileSource`.
  Source file: `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala`
  Test file: `kyo-tasty/shared/src/test/scala/kyo/SnapshotRoundTripTest.scala`

### Subtyping under-determination signal (M6)

The public extension method's return shape changes; the legacy `Boolean` form is replaced outright per `steering.md:63` (no backwards-compat shims). Q-001 decides between `Maybe[Boolean]` and a custom `SubtypeVerdict` enum; the design records both options and binds the final selection at the resolve-open step.

- `extension (t: Type) def isSubtypeOf(other: Type)(using cp: Classpath): <verdict shape per Q-001>`
  Rationale: M6 against `Tasty.scala:1080-1092`; M6 against `Subtyping.scala:18,144`.
  Source file: `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala` plus `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/type_/Subtyping.scala`
  Test file: `kyo-tasty/shared/src/test/scala/kyo/SubtypeTest.scala`

### Tree decoder coverage (M1)

`TreeUnpickler` adds explicit decode branches for every TASTy AST tag the v28.8 format defines; the `case _ => Tasty.Tree.Unknown(...)` fallback remains only for forward-compatibility (future TASTy minor versions). Q-003 decides the decomposition axis. The public `Tree` ADT in `Tasty.scala:394-492` gains new concrete cases for every category currently routed to `Tree.Unknown`, matching what kyo-ts and kyo-flow consume.

- `enum Tree` extended with the missing concrete cases.
  Rationale: M1 against `Tasty.scala:394-492` plus the `Tree.Unknown` emission sites at `TreeUnpickler.scala:129,186,216-220,258,264,268,313-317,323,380,512,519,587-596,707`.
  Source file: `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala` plus `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/TreeUnpickler.scala`
  Test file: `kyo-tasty/shared/src/test/scala/kyo/TreeUnpicklerTest.scala`

### Annotation.argsPickle lazy-decode context lifecycle (M2)

The `Annotation` case carries the `DecodeContext` reference required to materialize `argsPickle` outside the initial unpickler boundary. The public `def args(using Frame): Tree < (Sync & Abort[TastyError])` keeps its signature; the body's `NotImplemented` branch is removed.

- `def args(using Frame): Tree < (Sync & Abort[TastyError])`
  Rationale: M2 against `Tasty.scala:178` and `Tasty.scala:181-184` (the `NotImplemented` fallback).
  Source file: `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala`
  Test file: `kyo-tasty/shared/src/test/scala/kyo/TreeUnpicklerTest.scala`

The internal factory `private[kyo] def apply(annotationType: Type, argsPickle: Chunk[Byte], decodeCtx: DecodeContext): Annotation` (`Tasty.scala:214`) keeps its signature; the `DecodeContext` field on `Annotation` becomes mandatory rather than nullable for any annotation discovered through the classpath orchestrator.

### Constant.ClassConst carries the real referenced Type (M3)

The ADT shape stays; the decoder produces a real `Type` rather than the `classConstSentinel` placeholder.

- `case ClassConst(tpe: Type)` (unchanged shape)
  Rationale: M3 against `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/symbol/Constant.scala:81-85`. The decoder no longer calls `skipTree` at line 83; it decodes the CLASSconst tree and projects the referenced Type.
  Source file: `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/symbol/Constant.scala`
  Test file: `kyo-tasty/shared/src/test/scala/kyo/UnifiedModelTest.scala`

### Snapshot format bump (M4)

`SnapshotFormat` gains new sections (`Parents`, `TypeParams`, `Declarations`) so warm-cache restore reconstructs them. The version bump scope (major vs minor) depends on Q-005. Public surface (`Tasty.Snapshot.evictOlderThan`, `Snapshot.write`, `Snapshot.read`) keeps its signatures; the on-disk format changes.

- `SnapshotFormat.majorVersion` and `SnapshotFormat.minorVersion` updated per Q-005.
  Rationale: M4 against `SnapshotReader.scala:170-177` (`Chunk.empty` for parents/typeParams/declarations) and `SnapshotFormat.scala:42-44,57-58`.
  Source file: `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/snapshot/SnapshotFormat.scala` plus `SnapshotReader.scala`, `SnapshotWriter.scala`
  Test file: `kyo-tasty/shared/src/test/scala/kyo/SnapshotRoundTripTest.scala`, `kyo-tasty/jvm/src/test/scala/kyo/SnapshotRoundTripJvmTest.scala`

### InflateHook cross-platform parity (M5)

The shared abstract base (`shared/.../scala2/InflateHook.scala:13-19`) is unchanged. The JS and Native concrete `object InflateHook extends InflateHookImpl` provide a real RFC 1950 inflate. Q-002 decides between an in-tree port and an external pure-Scala library.

- `def inflate(compressed: Array[Byte])(using Frame): Array[Byte] < (Sync & Abort[TastyError])`
  Rationale: M5 against `js/.../scala2/InflateHook.scala:1-10` and `native/.../scala2/InflateHook.scala:1-10`.
  Source file (shared base): `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/scala2/InflateHook.scala`
  Source file (JS impl): `kyo-tasty/js/src/main/scala/kyo/internal/tasty/scala2/InflateHook.scala`
  Source file (Native impl): `kyo-tasty/native/src/main/scala/kyo/internal/tasty/scala2/InflateHook.scala`
  Test file: `kyo-tasty/shared/src/test/scala/kyo/Scala2PickleTest.scala` plus a new `kyo-tasty/shared/src/test/scala/kyo/InflateHookTest.scala`.

### Unknown TASTy type tag warning hook (M7)

`TypeUnpickler` records every unknown tag occurrence via `kyo.Log`; no new public type-level surface. The fallback `Type.Named(makeUnresolvedSym(s"unknown-type-tag-$other", ...))` keeps the structural shape so downstream code does not crash, but the log line names the tag id, classfile path, and byte offset.

- (internal) `def warnUnknownTypeTag(tag: Int, path: String, offset: Long): Unit < Sync` (or `Async`)
  Rationale: M7 against `TypeUnpickler.scala:593,598`.
  Source file: `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/TypeUnpickler.scala`
  Test file: `kyo-tasty/shared/src/test/scala/kyo/TypeUnpicklerTest.scala`

### Classfile attribute coverage (M8)

`ClassfileUnpickler` adds match arms for the six missing attributes. New Symbol-level surface: `PermittedSubclasses` populates a `Maybe[Chunk[Tasty.Symbol]]` accessor on the Symbol for sealed-hierarchy enumeration; `MethodParameters` extends the existing `JavaMetadata` payload with parameter names; the other four attributes feed into existing accessors (`BootstrapMethods` into the constant-pool resolution for invokedynamic call sites; `NestHost` and `NestMembers` into Symbol kinship metadata; `RuntimeTypeAnnotations` into `JavaMetadata.annotations`).

- `def permittedSubclasses(using AllowUnsafe): Maybe[Chunk[Tasty.Symbol]]` (new accessor on Symbol)
  Rationale: M8 covering `PermittedSubclasses` (sealed-hierarchy support).
  Source file: `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala` plus `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/classfile/ClassfileUnpickler.scala`
  Test file: `kyo-tasty/shared/src/test/scala/kyo/JavaSymbolTest.scala`
- (internal) attribute decoders for `BootstrapMethods`, `NestHost`, `NestMembers`, `MethodParameters`, `RuntimeTypeAnnotations` in `ClassfileUnpickler`.
  Rationale: M8 against `ClassfileFormat.scala:61-71` plus the attribute match arms at `ClassfileUnpickler.scala:343,378,395,502,521,553,577,598,617,636,656,750`.
  Source file: `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/classfile/ClassfileUnpickler.scala`
  Test file: `kyo-tasty/shared/src/test/scala/kyo/ClassfileReaderTest.scala`, `kyo-tasty/shared/src/test/scala/kyo/JavaSymbolTest.scala`

### Scala 2 pickle decoder coverage (M9)

`Scala2PickleReader` adds match arms for `EXTref (7)` and `EXTMODCLASSref (8)`. No new public surface; the decoder's output flows through existing `Tasty.Symbol` and `Tasty.Type` paths.

- (internal) match arms for `EXTref` and `EXTMODCLASSref`.
  Rationale: M9 against `Scala2PickleReader.scala:260-275`.
  Source file: `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/scala2/Scala2PickleReader.scala`
  Test file: `kyo-tasty/shared/src/test/scala/kyo/Scala2PickleTest.scala`

### TODO/FIXME inventory resolution (M10)

No new public surface. Each TODO/FIXME entry is resolved by the M3 (Constant.scala:81), M4 (SnapshotReader.scala:170-171), or L4 (Tasty.scala:709) change.

- Source file: as named per entry.
  Rationale: M10 against `audit-findings.md:36`.
  Test file: covered by the M3, M4, and L4 test changes.

### TastyError context enrichment (L5)

Per Q-004. The likely outcome: `MalformedSection` replaced outright with a `byteOffset: Long` field added per `steering.md:63` (no backwards-compat shim). Public ADT shape changes; every caller updated.

- `case MalformedSection(name: String, reason: String, byteOffset: Long)`
  Rationale: L5 against `TastyError.scala:12`. Q-004 decides whether the `byteOffset` enrichment is added uniformly or only to structured-payload cases.
  Source file: `kyo-tasty/shared/src/main/scala/kyo/TastyError.scala`
  Test file: `kyo-tasty/shared/src/test/scala/kyo/TastyErrorTest.scala` (new, prefix-match against `TastyError.scala`).

### Bounds-checking primitives (B1, B4, B7, B10, C4)

No new public API. The internal `Varint`, `ByteView`, `NameUnpickler`, `SectionIndex`, and `Interner` reject out-of-bounds inputs with a structured `TastyError.MalformedSection`. The exception arms at `Tasty.scala:728-741` already map `ArrayIndexOutOfBoundsException` to a TastyError; the new bounds checks emit the error directly with the byte offset attached.

- (internal) input validation in `Varint.readLongNat`, `Varint.readNat`, `ByteView.subView`, `NameUnpickler` indexed name resolution, `SectionIndex` name resolution, `Interner.bytesEqual`.
  Rationale: B1, B4, B7, B10, C4 against the cited file:line.
  Source files: `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/binary/Varint.scala`, `ByteView.scala`; `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/NameUnpickler.scala`, `SectionIndex.scala`; `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/symbol/Interner.scala`.
  Test files: `kyo-tasty/shared/src/test/scala/kyo/VarintTest.scala` (new, prefix `Varint`), `ByteViewTest.scala`, `NameUnpicklerTest.scala`, `SectionIndexTest.scala` (new, prefix `SectionIndex`), `InternerTest.scala`.

### JAR 64-bit offset handling (C1, B2, B3, B11)

No new public API. `JarCentralDirectory` and `JarMappedReader` operate on `Long` offsets throughout; `.toInt` truncations at the listed file:line replaced with `Long` arithmetic and explicit Zip64 EOCD locator detection.

- (internal) Zip64 EOCD locator, central-directory offset, local-file-header offset arithmetic in `Long`.
  Rationale: C1 against `JarCentralDirectory.scala:140,142,174,189,342,345,526,560,570`; B2 against `JarMappedReader.scala:65,72,85`; B3 against `JarCentralDirectory.scala:140,174`; B11 against `JarCentralDirectory.scala parseAllEntries`.
  Source files: `kyo-tasty/jvm/src/main/scala/kyo/internal/tasty/query/JarCentralDirectory.scala`, `JarMappedReader.scala`.
  Test file: `kyo-tasty/jvm/src/test/scala/kyo/JarCentralDirectoryTest.scala`.

### MappedByteView Long-aware accessors (B6)

`MappedByteView` int-returning accessors widen to `Long` where the on-disk offsets exceed 2GB.

- (internal) `def position: Long`, `def goto(addr: Long): Unit`, etc.
  Rationale: B6 against `MappedByteView.scala:41,48,56,58`.
  Source file: `kyo-tasty/jvm/src/main/scala/kyo/internal/tasty/binary/MappedByteView.scala`
  Test file: `kyo-tasty/jvm/src/test/scala/kyo/JvmFileSourceTest.scala` plus `kyo-tasty/jvm/src/test/scala/kyo/MappedByteViewTest.scala` (new, prefix `MappedByteView`).

### PositionsUnpickler cumulative line-start arithmetic (B9)

`PositionsUnpickler` line-start cumulative accumulator widens to `Long` or detects Int overflow with a `TastyError.MalformedSection`.

- (internal) `lineStarts` widened or guarded.
  Rationale: B9 against `PositionsUnpickler.scala:82`.
  Source file: `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/PositionsUnpickler.scala`
  Test file: `kyo-tasty/shared/src/test/scala/kyo/PositionsUnpicklerTest.scala`

### TypeArena depth bound (B8)

`TypeArena.internRec` enforces a depth cap and reports a structured error when exceeded.

- (internal) recursion-depth guard in `TypeArena.internRec`.
  Rationale: B8 against `TypeArena.scala:81-96`.
  Source file: `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/type_/TypeArena.scala`
  Test file: `kyo-tasty/shared/src/test/scala/kyo/TypeArenaTest.scala`

### ConstantPool cross-entry type validation (B5, C3)

`ConstantPool.entry` validates that cross-entry references match the expected pool tag (ClassRef.nameIdx targets Utf8, etc.); `Utf8Lazy` accepts `Mapped` `ByteView` rather than rejecting it.

- (internal) typed entry accessors.
  Rationale: B5 against `ConstantPool.scala:66-77,82,102`; C3 against `ConstantPool.scala:217-223`.
  Source file: `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/classfile/ConstantPool.scala`
  Test file: `kyo-tasty/shared/src/test/scala/kyo/ConstantPoolTest.scala` (new, prefix `ConstantPool`).

### Interner growShard race remediation (B12)

`Interner.growShard` widens its synchronization scope so the double-checked observe never crosses the un-synchronized boundary.

- (internal) growShard window closure.
  Rationale: B12 against `Interner.scala:101-122`.
  Source file: `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/symbol/Interner.scala`
  Test file: `kyo-tasty/shared/src/test/scala/kyo/InternerTest.scala`

### PerfCounters atomic snapshot (B13)

`PerfCounters.reset` and `PerfCounters.snapshot` ensure consistent multi-counter reads.

- (internal) snapshot helper.
  Rationale: B13 against `PerfCounters.scala:32-45`.
  Source file: `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/query/PerfCounters.scala`
  Test file: `kyo-tasty/shared/src/test/scala/kyo/PerfCountersTest.scala` (new, prefix `PerfCounters`).

### JvmFileSource registration atomicity (B14, B15)

`JvmFileSource.activePool.set` and `Scope.ensure` registration become atomic via the existing `Scope.acquireRelease` idiom; `JarMappedReader.channel.map` failure no longer retains a channel reference.

- (internal) acquire/release pairing.
  Rationale: B14 against `JvmFileSource.scala:149-156`; B15 against `JarMappedReader.scala:119-132`.
  Source files: `kyo-tasty/jvm/src/main/scala/kyo/internal/tasty/query/JvmFileSource.scala`, `JarMappedReader.scala`.
  Test file: `kyo-tasty/jvm/src/test/scala/kyo/JvmFileSourceTest.scala`.

### OnceCell idempotence marker (C2, A3)

`OnceCell` documents the idempotence requirement on `init()` with a `@throws`-style scaladoc clause and a debug-mode duplicate-result detection flag. The `asInstanceOf` comments at lines 37, 41, 45 adopt the canonical `// Unsafe: ...` prefix per CONTRIBUTING.md §415.

- `final class OnceCell[A](init: () => A)` (signature unchanged).
  Rationale: C2 against `OnceCell.scala:42`; A3 against `OnceCell.scala:37,41,45`.
  Source file: `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/symbol/OnceCell.scala`
  Test file: `kyo-tasty/shared/src/test/scala/kyo/OnceCellTest.scala` (new, prefix `OnceCell`).

### README and DESIGN rename (L1, L2, A1)

Docs replace every `kyo-reflect`, `Reflect.*`, `ReflectError`, `.kyo-reflect-cache` occurrence with the corresponding `kyo-tasty`, `Tasty.*`, `TastyError`, `.kyo-tasty-cache` name. The `Reflect.Reads` mention at `README.md:41` is resolved per Q-008.

- `kyo-tasty/README.md` and `kyo-tasty/DESIGN.md` rewritten with the `Tasty.*` namespace throughout.
  Rationale: L1, L2, A1 against `README.md:1,3,11,13-15,35-41,49,52-76`, `DESIGN.md:1-9`.
  Source files: `kyo-tasty/README.md`, `kyo-tasty/DESIGN.md`.
  Test file: (doc rename, no test change beyond doctest extraction).

### Public scaladoc and effect-row commentary (L3, L4, L6, L7)

`Classpath.open`, `openCached`, `findClass`, `topLevelClasses`, `packages`, `Name.apply`, `Flags.empty`, and other public methods get scaladocs matching CONTRIBUTING.md §426-§434. The `Phase 3` / `Phase C` / `Phase 0` comments in `Tasty.scala:78,1012` and `ClassfileUnpickler.scala:19` are rewritten to reference the architectural concern (cold-load orchestration, attribute decoding) instead of campaign phase metadata. `DESIGN.md` Section 1 separates user-facing goals from performance targets.

- No signature changes; scaladoc bodies updated.
  Rationale: L3 against `Tasty.scala:899-911`; L4 against `Tasty.scala:78,1012` and `ClassfileUnpickler.scala:19`; L6 against `Tasty.scala:48,72,1014-1047`; L7 against `DESIGN.md:9-34`.
  Source files: `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala`, `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/classfile/ClassfileUnpickler.scala`, `kyo-tasty/DESIGN.md`.
  Test file: covered by existing TastyTest and SymbolTest scenarios (doctest extraction in `kyo.Test`).

### Test-coverage surface (T1-T8)

Per `steering.md:96-100`, test entries land as numbered Given/When/Then/Pins scenarios inside the prefix-matched topic files. The plan step records the scenarios; the design surfaces only the test-file landing zones.

- T1 (untested public API methods): land in `TastySymbolTest.scala` (binaryName scenarios for Scala nested classes, isPackageObject), `TastyTypeTest.scala` (Type.show), `TastyAnnotationTest.scala` (synthetic Annotation factory).
- T2 (internal classes with no dedicated test file): land in `ConstantPoolTest.scala`, `JavaAnnotationUnpicklerTest.scala`, `VarintTest.scala`, `ClasspathRefDedupTest.scala` (extend), `UnresolvedRefTest.scala`, `TastyStatTest.scala`, `PerfCountersTest.scala`, `SectionIndexTest.scala`, `InflateHookTest.scala`, `DigestComputerTest.scala`, `SnapshotFormatTest.scala`, `SnapshotReaderTest.scala`, `SnapshotWriterTest.scala`, `ConstantTest.scala`, `FqnCanonicalizerTest.scala`, `OnceCellTest.scala`, `SingleAssignTest.scala`, `SymbolKindTest.scala`, `PlatformHashingStateTest.scala`.
- T3 (untested `TastyError` ADT cases `SymbolNotFound`, `ParameterizedTypeNotAllowed`): land in `TastyErrorTest.scala`.
- T4 (edge inputs): land in `VarintTest.scala`, `Utf8Test.scala`, `TastyTypeTest.scala`, `TypeArenaTest.scala`, `TastySymbolTest.scala`, `JarCentralDirectoryTest.scala`, `JvmFileSourceTest.scala`, `SnapshotRoundTripTest.scala`, `MappedByteViewTest.scala` (new, prefix `MappedByteView`).
- T5 (cross-platform parity): JS-only and Native-only scenarios land in the existing topic test files inside `shared/src/test/scala/kyo/` per `steering.md:71-72`, gated by a cross-platform `Platform` selector.
- T6 (no scalacheck): replaced with seeded `scala.util.Random` generative scenarios inside the relevant topic test files. No new dependency.
- T7 (concurrency tests for `OnceCell`, `SingleAssign`, `TypeArena`): land in `OnceCellTest.scala`, `SingleAssignTest.scala`, `TypeArenaTest.scala`.
- T8 (resource-cleanup tests): land in `JvmFileSourceTest.scala` (JAR pool exhaustion), `ClasspathOrchestratorPipelineTest.scala` (classpath close during pending body decode), `JvmFileSourceTest.scala` (mmap arena close during `Symbol.body` access).

## Package surface verdicts

Every file under `kyo-tasty/shared/src/main/scala/kyo/*.scala` (excluding `kyo/internal/`) is enumerated below. No file currently under `kyo/internal/` is promoted by this campaign.

- `Tasty.scala`: PUBLIC. User-callable entry points: `Tasty.Classpath.open` (`Tasty.scala:899`), `Tasty.classFqn` (`Tasty.scala:1097`), the `Symbol`, `Type`, `Constant`, `Annotation`, `Position`, `JavaMetadata`, `ModuleDescriptor`, `Pickle`, `Snapshot` types.
- `TastyError.scala`: PUBLIC. Returned in every `Abort[TastyError]` row across the module's public surface (`Tasty.scala:703,899,910,1113`).
- `tasty/examples/CodegenExample.scala`: PUBLIC (subject to Q-009). User-facing documentation example.
- `tasty/examples/IdeHoverExample.scala`: PUBLIC (subject to Q-009).
- `tasty/examples/JavaScalaBridgeExample.scala`: PUBLIC (subject to Q-009).
- `tasty/examples/RuntimeReflectionExample.scala`: PUBLIC (subject to Q-009).

The `kyo.tasty.examples` package deviates from the `kyo` / `kyo.internal.tasty` two-namespace convention recorded at CONTRIBUTING.md §414. Q-009 records the three options for resolving the deviation (in-package with explanatory comment, rename to `kyo.internal.tasty.examples`, or extract to a sibling `kyo-tasty-examples` module). The verdict binds at the resolve-open step.

All files under `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/*` remain INTERNAL (no promotion to `kyo/`).

## Target-state semantics

For each public API, the invariants, error channels, effect rows, and edge cases.

### Symbol accessors propagating `(using AllowUnsafe)`

- Invariant: the proof token is provided by the caller; the accessor body never invokes `import AllowUnsafe.embrace.danger`.
- Invariant: the return type is the raw underlying value (no `Sync` wrap).
- Invariant: the body remains zero-allocation per call beyond what the underlying `OnceCell` / `SingleAssign` storage already costs.
- Error channel: none. Accessors are pure data reads against in-memory storage; any decode failure already surfaced during cold-load.
- Effect row: the synchronous accessor has no effect row; callers wrap in `Sync.Unsafe.defer` at the boundary where the effect is staged.
- Edge cases: a Symbol constructed by `make` but never assigned its body produces an unset `OnceCell`; accessor behavior in that state is governed by `SingleAssign.get` raising an unchecked exception, which the caller's outer `Sync.Unsafe.defer` boundary translates to a `TastyError.MalformedSection`.

### Symbol.body lazy decode boundary

- Invariant: `body` decodes lazily on first access; subsequent calls observe the same `Tree`.
- Invariant: the decode boundary runs inside `Sync.Unsafe.defer`; the body is never re-entered after the first successful decode.
- Error channel: `TastyError.MalformedSection`, `TastyError.NotImplemented` (for not-yet-decoded tag categories per Q-003 phasing), `TastyError.CorruptedFile`.
- Effect row: `Sync & Abort[TastyError]`.
- Edge cases: a Symbol whose `TastyOrigin.bodyView` is `null` returns `Tree.Unknown(-1, 0)` to signal "no body" (matches the current `:709 stub("Symbol.body")` guard's intent).

### TreeUnpickler full tag coverage

- Invariant: every TASTy v28.8 AST tag has either an explicit decode branch or routes through the `case _ => Tree.Unknown(...)` forward-compatibility fallback.
- Invariant: the fallback never fires for any tag emitted by Scala 3.6+ compilers producing v28.8 output.
- Error channel: `TastyError.MalformedSection(name, reason, byteOffset)` for tag-shape violations; `TastyError.CorruptedFile` for buffer underruns.
- Effect row: synchronous decode, suspended at the outer `Symbol.body` boundary.
- Edge cases: a tag legal in the format spec but unimplemented in the decoder (e.g., experimental category-5 nodes) emits the `Tree.Unknown` fallback and logs via `kyo.Log`.

### Annotation.args eager-context decode

- Invariant: `Annotation.args` decode succeeds for every annotation accessed via the public classpath orchestrator.
- Invariant: the `_decodeCtx` field is non-null for any annotation discovered through `Classpath.open` or `Classpath.openCached`.
- Error channel: `TastyError.MalformedSection`, `TastyError.CorruptedFile`.
- Effect row: `Sync & Abort[TastyError]`.
- Edge cases: an `Annotation` constructed via the public synthetic factory `def apply(annotationType: Type, argsPickle: Chunk[Byte])` (`Tasty.scala:210`) with a null `argsPickle` returns `Tree.Unknown(-1, 0)`.

### Constant.ClassConst real type

- Invariant: `ClassConst(tpe)` carries the actual `Type` referenced by the CLASSconst tree.
- Invariant: the `classConstSentinel` placeholder is removed from the source tree.
- Error channel: `TastyError.MalformedSection` if the CLASSconst tree fails to decode.
- Edge cases: a CLASSconst whose referenced class is not yet resolved at decode time produces a `Type.Named(unresolvedRef)`; downstream `Classpath.findClass` may resolve it later.

### Snapshot format restore

- Invariant: warm-cache restore returns the same `_parents`, `_typeParams`, `_declarations` chunks the cold-load wrote; no `Chunk.empty` substitutions.
- Invariant: the new sections are addressed by stable offsets in the snapshot file, with section lengths in `Long`.
- Error channel: `TastyError.SnapshotFormatError`, `TastyError.SnapshotVersionMismatch`.
- Effect row: `Sync & Abort[TastyError]`.
- Edge cases: a snapshot written by a previous version (per Q-005 major-vs-minor bump) is rejected with `SnapshotVersionMismatch`; the orchestrator falls back to cold-load.

### InflateHook cross-platform

- Invariant: JS and Native implementations return `Right(bytes)` for valid RFC 1950 input.
- Invariant: byte-for-byte parity with `java.util.zip.InflaterInputStream` output on the same input.
- Error channel: `TastyError.MalformedSection` for corrupted ZLIB streams; `TastyError.CorruptedFile` for truncated streams.
- Effect row: `Sync & Abort[TastyError]`.
- Edge cases: empty input returns `Right(Array.emptyByteArray)`; oversized input (> Int.MaxValue inflated) returns `TastyError.MalformedSection` with the byte-offset context.

### Subtyping verdict signal

- Invariant: callers can distinguish "not a subtype" from "under-determined" (budget exhausted, partial classpath, structurally-incompatible arguments).
- Invariant: the verdict shape per Q-001 is total (no exceptions thrown).
- Error channel: none (verdict shape covers under-determination).
- Effect row: synchronous, `using Classpath` capability.
- Edge cases: budget exhaustion at the documented `Subtyping.scala:51` site produces the under-determined verdict; same for cycles past depth bound.

### Classfile attribute coverage

- Invariant: `BootstrapMethods`, `NestHost`, `NestMembers`, `PermittedSubclasses`, `MethodParameters`, `RuntimeTypeAnnotations` all parsed and surfaced via `Tasty.Symbol` or `Tasty.JavaMetadata`.
- Invariant: `PermittedSubclasses` enumerates the sealed-hierarchy children directly accessible from a sealed class Symbol.
- Error channel: `TastyError.ClassfileFormatError` for malformed attribute bodies; `TastyError.MalformedSection` for cross-attribute structural failures.
- Effect row: synchronous classfile decode (suspended at the cold-load orchestrator boundary).
- Edge cases: a classfile produced by a JDK newer than the running runtime may carry attributes the decoder does not recognize; those route to a log line via `kyo.Log` and the decode continues.

### Scala 2 decoder coverage

- Invariant: `EXTref (7)` and `EXTMODCLASSref (8)` are decoded and produce `Tasty.Symbol` entries that resolve via the same classpath orchestrator pipeline as Scala 3 symbols.
- Error channel: `TastyError.MalformedSection`.
- Effect row: synchronous, suspended at the cold-load boundary.
- Edge cases: a Scala 2 pickle whose `EXTref` targets a classpath-missing symbol routes to an `UnresolvedRef` so downstream queries surface the gap.

### TastyError context enrichment

- Invariant: every malformed-section path carries the `byteOffset: Long` of the failure.
- Invariant: callers can localize the failure inside the source classfile or TASTy section without re-decoding.
- Error channel: this is the error type itself.
- Edge cases: pre-existing `at: Long` cases (`CorruptedFile`) retain their structural payload.

### Bounds-checked binary primitives

- Invariant: `Varint`, `ByteView`, `NameUnpickler`, `SectionIndex`, `Interner` reject out-of-bounds reads with a structured `TastyError.MalformedSection`; no uncaught `ArrayIndexOutOfBoundsException` reaches the caller.
- Invariant: integer-overflow paths (cursor + len wrapping past `Int.MaxValue`) detected and rejected.
- Error channel: `TastyError.MalformedSection(name, reason, byteOffset)`.
- Effect row: the surrounding decode wrapper carries `Sync & Abort[TastyError]`.

### JAR 64-bit offsets and Zip64

- Invariant: `JarCentralDirectory` and `JarMappedReader` operate on `Long` offsets throughout; Zip64 EOCD locator detected and consumed.
- Invariant: no `.toInt` truncation on a value that may exceed `Int.MaxValue`.
- Error channel: `TastyError.MalformedSection` for malformed offset arithmetic; `TastyError.CorruptedFile` for buffer underruns.
- Edge cases: a Zip64 JAR with central directory at exactly `Int.MaxValue + 1` parses correctly; a JAR with a Zip64 EOCD locator but no Zip64 EOCD record returns `MalformedSection`.

### MappedByteView Long-aware accessors

- Invariant: positions, cursors, and offsets are `Long` throughout; reads beyond `Int.MaxValue` succeed when backed by Long-addressable mmap regions.
- Edge cases: the Int-returning legacy callers convert via explicit `.toIntExact` at the boundary; overflow throws `TastyError.MalformedSection`.

### TypeArena depth bound

- Invariant: `internRec` recursion bounded; pathological nesting reports a structured error instead of `StackOverflowError`.
- Error channel: `TastyError.MalformedSection`.

### ConstantPool cross-entry typing

- Invariant: `ConstantPool.entry(idx)` validates that the referenced entry matches the expected pool tag; mismatched references surface a structured error instead of a cryptic match failure.
- Invariant: `Utf8Lazy` accepts both Heap and Mapped `ByteView`.
- Error channel: `TastyError.ClassfileFormatError`.

### Interner growShard race remediation

- Invariant: the un-synchronized observe at line 72 cannot cross the synchronized re-entry at line 101 with a stale shard reference.
- Edge cases: a shard grow that races with another shard read returns the new shard's value.

### PerfCounters atomic snapshot

- Invariant: `snapshot()` returns a coherent view of every counter; partial-reset windows do not surface.
- Edge cases: instrumentation use remains lock-free; the snapshot is a one-shot copy.

### JvmFileSource registration atomicity

- Invariant: `activePool.set` and `Scope.ensure` registration occur via a single `Scope.acquireRelease` so failure between them never leaks mapped buffers.
- Edge cases: an interrupt between phases triggers the release branch.

### OnceCell idempotence

- Invariant: `init()` lambdas are required to be idempotent; first-callers compute the same value modulo equality.
- Invariant: debug-mode duplicate-result detection (a runtime assertion flag) flags any non-idempotent lambda.

### README and DESIGN naming

- Invariant: every `Reflect.*` reference in the docs resolves to a real `Tasty.*` API.
- Invariant: doctest extraction (via `kyo.Test`) compiles every code block in the README.
- Edge cases: the `Reflect.Reads` mention (Q-008) is either replaced with the real API or removed.

## Cross-phase invariants (candidates)

- INV-001: AllowUnsafe routine-accessor signatures take `(using AllowUnsafe)` with no `import danger` inside the body.
  produced_by: Phase ?
  consumed_by: Phase ?
- INV-002: Cold-load and warm-cache paths allocate zero `Sync.Unsafe.defer` closures per Symbol or Classpath accessor call.
  produced_by: Phase ?
  consumed_by: Phase ?
- INV-003: Snapshot format major bump invalidates old snapshots; minor bump is add-only per `SnapshotFormat.scala:42-44`.
  produced_by: Phase ?
  consumed_by: Phase ?
- INV-004: Every TASTy type tag in `TypeUnpickler.decodeTag` has either an explicit decode branch or routes through the unknown-tag warning hook.
  produced_by: Phase ?
  consumed_by: Phase ?
- INV-005: TASTy AST tag coverage in `TreeUnpickler` matches the decomposition axis per Q-003 with no remaining `Tree.Unknown` emission for tags emitted by Scala 3.6+ TASTy v28.8 output.
  produced_by: Phase ?
  consumed_by: Phase ?
- INV-006: Every `TastyError.MalformedSection` event carries the byte offset of the failure.
  produced_by: Phase ?
  consumed_by: Phase ?
- INV-007: Test files prefix-match their source basename; 1:1 preferred per `steering.md:97-104`.
  produced_by: Phase ?
  consumed_by: Phase ?
- INV-008: Java classfile attributes `BootstrapMethods`, `NestHost`, `NestMembers`, `PermittedSubclasses`, `MethodParameters`, `RuntimeTypeAnnotations` parsed and exposed via `Symbol` or `JavaMetadata`.
  produced_by: Phase ?
  consumed_by: Phase ?
- INV-009: `OnceCell.init` lambdas are idempotent; concurrent first-callers compute the same value modulo equality.
  produced_by: Phase ?
  consumed_by: Phase ?
- INV-010: `Varint`, `ByteView`, `NameUnpickler`, `SectionIndex`, `Interner` reject out-of-bounds reads with a structured `TastyError.MalformedSection` rather than an uncaught exception.
  produced_by: Phase ?
  consumed_by: Phase ?
- INV-011: `Symbol.TastyOrigin.addrMap` is not publicly accessible.
  produced_by: Phase ?
  consumed_by: Phase ?
- INV-012: `JarCentralDirectory` and `JarMappedReader` handle 64-bit offsets and Zip64 archives correctly with no Int truncation past 2GB.
  produced_by: Phase ?
  consumed_by: Phase ?
- INV-013: `Constant.ClassConst` constants carry the real referenced `Type` rather than the `classConstSentinel` placeholder.
  produced_by: Phase ?
  consumed_by: Phase ?
- INV-014: `Annotation.args` decode succeeds for any annotation discovered through the classpath orchestrator, including accesses after the initial decode boundary.
  produced_by: Phase ?
  consumed_by: Phase ?
- INV-015: Snapshot warm-cache restore returns the full `_parents`, `_typeParams`, `_declarations` chunks.
  produced_by: Phase ?
  consumed_by: Phase ?
- INV-016: Subtyping verdict shape per Q-001 cleanly distinguishes "not a subtype" from "under-determined" with no exceptions thrown.
  produced_by: Phase ?
  consumed_by: Phase ?
- INV-017: JS and Native `InflateHook` implementations produce byte-for-byte parity with the JVM `java.util.zip.InflaterInputStream` reference on valid RFC 1950 input.
  produced_by: Phase ?
  consumed_by: Phase ?
- INV-018: `MappedByteView` accessors that may address > 2GB regions return or accept `Long` offsets.
  produced_by: Phase ?
  consumed_by: Phase ?
- INV-019: `TypeArena.internRec` enforces a recursion-depth cap and reports a structured error on overflow.
  produced_by: Phase ?
  consumed_by: Phase ?
- INV-020: README, DESIGN, and code blocks therein reference `kyo.Tasty.*` and `kyo.TastyError` consistently; no `Reflect` or `kyo-reflect` occurrences remain.
  produced_by: Phase ?
  consumed_by: Phase ?
- INV-021: Production-code comments never reference campaign phase metadata (`Phase 3`, `Phase C`, `Phase 0`).
  produced_by: Phase ?
  consumed_by: Phase ?

## Rejected alternatives

- Two-tier `Symbol.Unsafe` / `Classpath.Unsafe` opaque-type companion: rejected because CONTRIBUTING.md §794-§812 reserves that pattern for concurrent types (Channel, Queue, Hub) with effectful operations on both tiers; Symbol and Classpath are data containers, not concurrent types (cite `steering.md:42-49`).
- `Sync.Unsafe.defer { sym.fullName }` wrapping on every routine accessor: rejected because it allocates a closure per call, regressing the tuned cold-load and warm-cache performance budget (cite `steering.md:23-34`).
- Default parameter `Classpath.open(roots, strict = false)`: rejected because the canonical-impl-plus-variants pattern at CONTRIBUTING.md §358-§374 makes delegation explicit, and `feedback_no_default_params_internal` rules out defaults on internal APIs (the canonical-impl is `private[kyo]`).
- Scalacheck or hedgehog dependency for property tests: rejected per steering's no-new-test-framework-dependencies rule; seeded `scala.util.Random` in plain `AsyncFreeSpec` scenarios covers the same ground (cite `steering.md:65-67`).
- `OnceCell` Promise-based dedup (kyo.Cache.memo style): rejected because OnceCell's race-and-discard model is documented as faster (no Async, no Promise allocation) for idempotent init lambdas at `OnceCell.scala:13-19`; the campaign enforces idempotence via INV-009 instead of switching the mechanism.
- Custom platform-specific ZLIB bindings (FFI to system `libz` on Native, `pako` via JS interop on JS): rejected if a pure-Scala in-tree port is workable per Q-002, because the FFI route fragments the build across platforms and conflicts with the kyo-tasty cross-platform parity model that `InflateHook` already follows.
- Adding `byteOffset: Long = -1L` as a default parameter to `TastyError.MalformedSection`: rejected because steering's no-backwards-compat rule (`steering.md:63`) requires the migration to be a clean replacement; the existing callers all get updated atomically.
- Keeping `Symbol.body` decoder coverage at "best-effort `Tree.Unknown` fallback": rejected because M1 is in scope; the audit-fix campaign's no-scope-cuts rule (`steering.md:7-15`) binds the design to full coverage.
- A separate `kyo-tasty-examples` test module for the four example files: this option remains on the table per Q-009; the design records it as a viable alternative but does not select it.
- Moving `Symbol.TastyOrigin.addrMap` to `kyo.internal.tasty.symbol` as part of a wider Origin refactor: rejected because the `private[kyo]` visibility tier at the existing site is sufficient and the wider move would expand the diff without finding-coded justification.
- Reusing the `tasty-query` library's TASTy tag decoder: rejected because the kyo-tasty module's goal is to replace `tasty-query` (per `project_kyo_reflect` memory note); the in-tree port maintains independent control over the cold-load and warm-cache paths.

## Open questions

- Q-001: M6 Subtyping under-determination signal shape. Options: (a) `Maybe[Boolean]` (Present(true) = subtype, Present(false) = not subtype, Absent = under-determined); (b) custom `enum SubtypeVerdict { case Sub, NotSub, Unknown }`; (c) keep `Boolean` and add a parallel `def isSubtypeOfMaybe` accessor. Steering's no-backwards-compat rule (`steering.md:63`) disfavors (c).
  [value-underdetermined]
  context: `01-exploration.md` M6 open observation; `Subtyping.scala:18,144`; `Tasty.scala:1091`.
- Q-002: M5 ZLIB cross-platform path. Options: (a) port RFC 1950 inflate in tree as pure Scala (controlled dependency surface, more code in tree); (b) reuse an existing pure-Scala ZLIB library if one exists with cross-platform Scala.js and Scala Native support (smaller diff, external dependency). Research item: enumerate pure-Scala ZLIB libraries with current maintenance and cross-platform support.
  [research-knowable]
  context: `01-exploration.md` M5 open observation; `js/.../scala2/InflateHook.scala:1-10`; `native/.../scala2/InflateHook.scala:1-10`.
- Q-003: M1 `Symbol.body` Tree decoder coverage decomposition axis. Options: (a) category-1 / category-3 / category-4 / category-5 split per TASTy spec layers; (b) TastyFormat tag-range split; (c) by semantic group (terms, definitions, types-in-trees, patterns). Coverage target: every tag emitted by Scala 3.6+ TASTy v28.8.
  [value-underdetermined]
  context: `01-exploration.md` M1 open observation; `TreeUnpickler.scala:129,186,216-220,258,264,268,313-317,323,380,512,519,587-596,707` Tree.Unknown emission sites; `Tasty.scala:394-492` Tree ADT.
- Q-004: L5 `TastyError` context-enrichment scope. Options: (a) add `byteOffset: Long` to every malformed-section case; (b) add only to cases that already carry an `at: Long` structural payload; (c) introduce a `MalformedSectionAt(name, reason, byteOffset)` case alongside `MalformedSection(name, reason)`. Steering's no-backwards-compat rule disfavors (c).
  [value-underdetermined]
  context: `01-exploration.md` L5 open observation; `TastyError.scala:7-22`.
- Q-005: M4 snapshot format version bump scope. Options: (a) minor bump (add-only) if new sections are added without changing existing layout; (b) major bump (invalidates old snapshots) if section widths or order change. Research item: audit which `SnapshotFormat` fields need to widen (e.g., Int to Long for section length) to support parent / typeParam / declaration serialization.
  [research-knowable]
  context: `01-exploration.md` M4 open observation; `SnapshotFormat.scala:42-44,57-58`; `SnapshotReader.scala:170-177`.
- Q-006: A4 `AllowUnsafe` callsite proof availability. After `Symbol` and `Classpath` accessors take `(using AllowUnsafe)`, every caller needs a proof in scope. Research item: enumerate every public-facing caller of `Symbol.fullName`, `Symbol.parents`, `Symbol.declarations`, `Symbol.declaredType`, `Symbol.typeParams`, `Symbol.scaladoc`, `Symbol.position`, `Symbol.isPackageObject`, `Name.asString` in kyo-tasty source plus the kyo-ts and kyo-flow downstream uses (cite per-file). Confirm every callsite either has `(using AllowUnsafe)` in scope, is wrapped in `Sync.Unsafe.defer`, or can adopt one of these. Sites that cannot adopt either become a steering escalation.
  [research-knowable]
  context: `01-exploration.md` A1 / A4 open observation; finding A4 callsite list at `audit-findings.md:82`.
- Q-007: A2 `Classpath.open` canonical-impl selection. Research item: inspect which of the two `Classpath.open` overloads is called by which caller; the more-called one is canonical, the other delegates by name with the parameter expansion explicit (no default-param shim).
  [research-knowable]
  context: `01-exploration.md` A4 open observation; `Tasty.scala:899,903`.
- Q-008: L1 README `Reflect.Reads` reference. Research item: grep the kyo-tasty source tree for any `Reads` typeclass or facade. If absent, the README reference is aspirational and gets removed in the rewrite; if present, document its real name in the rewrite.
  [research-knowable]
  context: `01-exploration.md` L1/L2 open observation; `README.md:41`.
- Q-009: `kyo.tasty.examples` package placement. Options: (a) keep at `kyo.tasty.examples` with a top-of-file explanatory comment justifying the deviation from the `kyo` / `kyo.internal.tasty` two-namespace convention; (b) rename to `kyo.internal.tasty.examples` acknowledging the demo nature; (c) extract to a sibling `kyo-tasty-examples` test module.
  [value-underdetermined]
  context: `01-exploration.md` final open observation; `CONTRIBUTING.md:414`; `shared/.../kyo/tasty/examples/CodegenExample.scala:1`.

## Resolved questions

All nine Q-NNN open questions are resolved. See `03a-open-resolutions.md`
for the per-question decision with citations. Two resolutions overrode
the earlier defaults under the safety-of-APIs principle: Q-001 picks
`enum SubtypeVerdict` over `Maybe[Boolean]`; Q-009 extracts examples to
a sibling sbt module.

## Validation hooks for flow-validate

- public API signatures listed in `## API surface` are the contract; `flow-validate` extracts them per Q-001, Q-003, Q-004 resolution.
- invariant candidates listed in `## Cross-phase invariants (candidates)` feed `flow-invariants`'s ledger; the 21 INV-NNN ids are the canonical references downstream phases bind against.
- `## Open questions` is the input to `flow-resolve-open` pass 1; each Q-NNN becomes a research-item or value-fork record.
- `## Package surface verdicts` covers every file under `kyo-tasty/shared/src/main/scala/kyo/*.scala` (excluding `kyo/internal/`); `flow-validate` confirms the list matches the on-disk file set.
- `## Target-state semantics` carries the per-API invariants, error channels, and edge cases the per-phase tests bind to.
