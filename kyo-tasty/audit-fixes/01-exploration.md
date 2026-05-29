# 01 Exploration: Remediate all 48 findings from the kyo-tasty audits (correctness C1-C4, completeness M1-M10, clarity L1-L7, potential bugs B1-B15, test coverage T1-T8, CONTRIBUTING.md adherence A1-A4)

Task type: refactor (with co-dominant test-campaign substrand for T1-T8; see `## Open observations`)
Primary module: kyo-tasty
Scope: kyo-tasty/{shared,jvm,js,native}/src/{main,test}/scala plus kyo-tasty/README.md, kyo-tasty/DESIGN.md (rename context for L1, L2)

## Task statement

> Remediate all 48 findings from the kyo-tasty audits (correctness C1-C4, completeness M1-M10, clarity L1-L7, potential bugs B1-B15, test coverage T1-T8, CONTRIBUTING.md adherence A1-A4). Exploration material has been prepared in this session: six audit reports already cite file:line for every finding. The exploration step does NOT re-audit; it organizes the existing findings into a module map (current shape of the code being touched), citing the same file:line anchors, plus a conventions / prior-art summary anchored to CONTRIBUTING.md sections (especially Unsafe Boundary §792-§897 and overload organization §309-§383). The kyo-reflect → kyo-tasty rename context lives in the README and DESIGN.md headers. The 48 finding codes are the authoritative checklist; subsequent design and plan steps must cover every one.

## Module map

Each entry includes a one-line role and (in parentheses) the finding-code categories whose impact-surface intersects that file. Specific finding-to-file:line mapping must come from the six audit reports listed in `## Open observations`; the categories here are derived from the public-API surface and the steering.md hints (M1 Symbol.body decoder coverage, M4 snapshot format bump, M5 ZLIB cross-platform port, M6 Subtyping under-determination, L1/L2 README/DESIGN rename, A1 AllowUnsafe boundary).

### Public surface

- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala`: top-level `object Tasty` housing every public type (Name, Flags, SymbolKind, Constant, Annotation, JavaMetadata, JavaAnnotation, Position, Type, Tree, Symbol, Pickle, Classpath, Snapshot, classFqn). The single largest file in the module (`Tasty.scala:1-1171`). Carries 12 routine accessors that hide an `import AllowUnsafe.embrace.danger` inside the body (`Tasty.scala:62,547,556,570,584,610,620,631,642`) plus the `_bodyOnce` init lambda (`Tasty.scala:533`) and the body decode boundary (`Tasty.scala:719,995`). A1 (CONTRIBUTING.md adherence), C-family, L-family, B-family.
- `kyo-tasty/shared/src/main/scala/kyo/TastyError.scala`: closed-error ADT for the module (`TastyError.scala:7-22`). Contains 14 cases; relevant to L5 (context enrichment) and to every Abort row in the public surface.

### Reader subsystem (TASTy decode)

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/TastyFormat.scala`: tag/name constants for the TASTy v28 format (`TastyFormat.scala:1-248`).
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/TastyHeader.scala`: TASTy header parser (`TastyHeader.scala:1-148`).
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/SectionIndex.scala`: section table parsing (`SectionIndex.scala:1-61`).
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/NameUnpickler.scala`: NAMES section decoder (`NameUnpickler.scala:1-198`).
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/AstUnpickler.scala`: pass-1 + pass-2 AST traversal that allocates `Tasty.Symbol` and assigns addrMap (`AstUnpickler.scala:1-713`). `import AllowUnsafe.embrace.danger` at `AstUnpickler.scala:161`.
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/TreeUnpickler.scala`: lazy body decode; entrypoints `decodeAnnotationTerm` (`TreeUnpickler.scala:37-49`) and `decodeSync` (`TreeUnpickler.scala:60-90`). Currently emits `Tasty.Tree.Unknown` for many tags (`TreeUnpickler.scala:129,186,216-220,258,264,268,313-317,323,380,512,519,587-591,593-596,707`); the M1 finding family targets this coverage gap.
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/TypeUnpickler.scala`: TYPES section decoder; ANNOTATEDtype captures `argsPickle` for lazy Annotation.args decode (`TypeUnpickler.scala:1-756`).
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/PositionsUnpickler.scala`: POSITIONS section (`PositionsUnpickler.scala:1-140`).
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/AttributeUnpickler.scala`: TASTy ATTRIBUTES section (`AttributeUnpickler.scala:1-103`).
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/CommentsUnpickler.scala`: scaladoc decode (`CommentsUnpickler.scala:1-79`).

### Classfile subsystem (Java decode)

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/classfile/ClassfileFormat.scala`: attribute name constants (`ClassfileFormat.scala:61-71`). Surface includes `AttrSignature`, `AttrInnerClasses`, `AttrEnclosingMethod`, `AttrRecord`, `AttrExceptions`, `AttrCode`, `AttrRuntimeVisibleAnnotations`, `AttrRuntimeInvisibleAnnotations`, `AttrScalaSig`, `AttrScala`. Missing-attribute coverage (M2/M3 family) is grounded here.
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/classfile/ClassfileUnpickler.scala`: top-level classfile parser, 1404 lines. Match arms over attribute names at `ClassfileUnpickler.scala:343` (Signature), `:378` (RuntimeVisibleAnnotations), `:395` (RuntimeInvisibleAnnotations), `:502` (Signature), `:521` (InnerClasses), `:553` (EnclosingMethod), `:577` (Record), `:598/617` (annotation tables), `:636` (ScalaSig), `:656` (Scala/ZLIB), `:750` (Signature). `AttrCode` and `AttrExceptions` are listed in ClassfileFormat but appear not to be matched as cases (only `AttrSignature/Annotations/InnerClasses/EnclosingMethod/Record/ScalaSig/Scala` show up as case arms). `import AllowUnsafe.embrace.danger` at `ClassfileUnpickler.scala:73`. Record component sub-attribute reader at `ClassfileUnpickler.scala:700-755`.
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/classfile/ConstantPool.scala`: pooled CP entry resolution (`ConstantPool.scala:1-305`). `import AllowUnsafe.embrace.danger` at `:86,:92`.
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/classfile/JavaSignatures.scala`: Java generics signature parser (`JavaSignatures.scala:1-355`).
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/classfile/JavaAnnotationUnpickler.scala`: Java annotation values reader (`JavaAnnotationUnpickler.scala:1-230`).
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/classfile/ModuleInfoReader.scala`: JPMS module-info reader (`ModuleInfoReader.scala:1-373`).

### Query (classpath orchestration)

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/query/Classpath.scala`: internal `final class Classpath private[tasty]` (`Classpath.scala:20`) + companion `object Classpath` (`Classpath.scala:166`). Carries the immutable Ready-state HashMaps. `import AllowUnsafe.embrace.danger` at `Classpath.scala:70,80,90,100,110,138,156,214,222`.
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/query/ClasspathOrchestrator.scala`: phase-A/B/C cold-load pipeline (`ClasspathOrchestrator.scala:1-596`). `import AllowUnsafe.embrace.danger` at `ClasspathOrchestrator.scala:377,389,446`.
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/query/ClasspathRef.scala`: forward-reference slot owned by each Symbol (`ClasspathRef.scala:1-39`). `import AllowUnsafe.embrace.danger` at `:21,:28,:35`.
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/query/FileSource.scala`: cross-platform file source typeclass (`FileSource.scala:1-81`).
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/query/UnresolvedRef.scala`: placeholder symbol for unresolved cross-file refs (`UnresolvedRef.scala:1-16`).
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/query/PerfCounters.scala`: instrumentation (`PerfCounters.scala:1-46`).
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/query/TastyStat.scala`: tracing scope (`TastyStat.scala:1-7`).
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/query/ClasspathTestHelpers.scala`: internal test helpers (`ClasspathTestHelpers.scala:1-34`). `import AllowUnsafe.embrace.danger` at `:18`.

### Scala 2 pickle subsystem (legacy stdlib coverage)

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/scala2/Scala2PickleReader.scala`: Scala 2 pickle decoder (`Scala2PickleReader.scala:1-564`). `import AllowUnsafe.embrace.danger` at `:284,:369,:401,:445`.
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/scala2/InflateHook.scala`: cross-platform abstract base for ZLIB inflate (`shared/.../scala2/InflateHook.scala:1-19`). M5 is grounded here.

### Symbol subsystem (in-memory model)

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/symbol/Symbol.scala`: `makeSymbol` factory bridging `Tasty.Symbol.make` (`Symbol.scala:1-31`).
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/symbol/SymbolKind.scala`: kind taxonomy (`SymbolKind.scala:1-77`).
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/symbol/Flags.scala`: flag bit math (`Flags.scala:1-87`).
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/symbol/FqnCanonicalizer.scala`: FQN normalization for fqnIndex keys (`FqnCanonicalizer.scala:1-62`).
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/symbol/Interner.scala`: sharded byte-key interner used by `Tasty.Name` (`Interner.scala:1-167`).
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/symbol/SingleAssign.scala`: write-once slot guarded by AllowUnsafe (`SingleAssign.scala:1-53`). The proof-bearing primitive that A1/A2/A3 turn on.
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/symbol/OnceCell.scala`: lazy cell guarded by AllowUnsafe (`OnceCell.scala:1-53`). Race-and-discard semantics documented at `OnceCell.scala:13-19`.
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/symbol/DeclarationTable.scala`: per-class declaration list (`DeclarationTable.scala:1-70`).
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/symbol/Constant.scala`: constant ADT helpers (`Constant.scala:1-112`).

### Snapshot subsystem (KRFL cache)

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/snapshot/SnapshotFormat.scala`: KRFL header + section layout constants (`SnapshotFormat.scala:1-145`). Current version: major=1 minor=2 (`SnapshotFormat.scala:57-58`). M4 (format bump) lands here.
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/snapshot/SnapshotReader.scala`: mmap-or-heap snapshot loader (`SnapshotReader.scala:1-609`). `import AllowUnsafe.embrace.danger` at `:173,:245`.
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/snapshot/SnapshotWriter.scala`: snapshot writer (`SnapshotWriter.scala:1-302`). `import AllowUnsafe.embrace.danger` at `:61`.
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/snapshot/DigestComputer.scala`: FNV-1a digest of classpath inputs (`DigestComputer.scala:1-180`).

### Type subsystem (Type ADT operations)

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/type_/TypeArena.scala`: hash-consed canonical Type interner (`TypeArena.scala:1-246`).
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/type_/TypeOps.scala`: Type manipulation helpers (`TypeOps.scala:1-60`).
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/type_/Subtyping.scala`: structural subtype check used by `Type.isSubtypeOf` (`Subtyping.scala:1-323`). Returns `Boolean` today (`Subtyping.scala:50` signature). M6 grounding: under-determination cases all collapse to `false` (the conservative branch documented at `Subtyping.scala:18,29`).

### Binary primitives (shared)

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/binary/ByteView.scala`: zero-copy byte slice abstraction (`ByteView.scala:1-116`).
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/binary/Utf8.scala`: shared dispatch facade (`shared/.../binary/Utf8.scala:1-15`).
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/binary/Varint.scala`: TASTy varint decode (`Varint.scala:1-88`).

### Examples (public docs)

- `kyo-tasty/shared/src/main/scala/kyo/tasty/examples/CodegenExample.scala` (`:1-?`)
- `kyo-tasty/shared/src/main/scala/kyo/tasty/examples/IdeHoverExample.scala`
- `kyo-tasty/shared/src/main/scala/kyo/tasty/examples/JavaScalaBridgeExample.scala`
- `kyo-tasty/shared/src/main/scala/kyo/tasty/examples/RuntimeReflectionExample.scala`
  All under `package kyo.tasty.examples` (verified at example file headers). May intersect L1/L2 if the rename target package or naming changes.

### Platform implementations

JVM:
- `kyo-tasty/jvm/src/main/scala/kyo/internal/tasty/binary/MappedByteView.scala`
- `kyo-tasty/jvm/src/main/scala/kyo/internal/tasty/binary/Utf8.scala`
- `kyo-tasty/jvm/src/main/scala/kyo/internal/tasty/query/{JarCentralDirectory.scala,JarMappedReader.scala,JarMappedReaderPool.scala,JvmFileSource.scala,PlatformFileSource.scala}`
- `kyo-tasty/jvm/src/main/scala/kyo/internal/tasty/snapshot/{JvmMmapReader.scala,PlatformMmapReader.scala}`
- `kyo-tasty/jvm/src/main/scala/kyo/internal/tasty/scala2/InflateHook.scala`: concrete JVM ZLIB inflate via `java.util.zip.InflaterInputStream` (`jvm/.../scala2/InflateHook.scala:1-27`).
- `kyo-tasty/jvm/src/main/scala/kyo/internal/tasty/type_/PlatformHashingState.scala`

Native:
- `kyo-tasty/native/src/main/scala/kyo/internal/tasty/binary/{MappedByteView.scala,Utf8.scala}`
- `kyo-tasty/native/src/main/scala/kyo/internal/tasty/query/{NativeFileSource.scala,PlatformFileSource.scala}`
- `kyo-tasty/native/src/main/scala/kyo/internal/tasty/snapshot/{NativeMmapReader.scala,PlatformMmapReader.scala}`
- `kyo-tasty/native/src/main/scala/kyo/internal/tasty/scala2/InflateHook.scala`: returns `Abort.fail(TastyError.NotImplemented(...))` (`native/.../scala2/InflateHook.scala:1-10`). M5 grounding.
- `kyo-tasty/native/src/main/scala/kyo/internal/tasty/type_/PlatformHashingState.scala`

JS:
- `kyo-tasty/js/src/main/scala/kyo/internal/tasty/binary/Utf8.scala`
- `kyo-tasty/js/src/main/scala/kyo/internal/tasty/query/{JsFileSource.scala,PlatformFileSource.scala}`
- `kyo-tasty/js/src/main/scala/kyo/internal/tasty/snapshot/PlatformMmapReader.scala`
- `kyo-tasty/js/src/main/scala/kyo/internal/tasty/scala2/InflateHook.scala`: returns `Abort.fail(TastyError.NotImplemented(...))` (`js/.../scala2/InflateHook.scala:1-10`). M5 grounding.
- `kyo-tasty/js/src/main/scala/kyo/internal/tasty/type_/PlatformHashingState.scala`

### Test surface (test-campaign T1-T8)

Shared (cross-platform):
- `kyo-tasty/shared/src/test/scala/kyo/Test.scala`: base test class (`Test.scala:10-21`).
- `kyo-tasty/shared/src/test/scala/kyo/{AstUnpicklerTest.scala, AttributeUnpicklerTest.scala, ByteViewTest.scala, ClassfileReaderTest.scala, ClasspathOrchestratorPipelineTest.scala, ClasspathRefDedupTest.scala, CommentsUnpicklerTest.scala, DeclarationTableTest.scala, FileSourceTest.scala, FlagsTest.scala, InternerTest.scala, JavaSignaturesTest.scala, JavaSymbolTest.scala, ModuleInfoTest.scala, NameUnpicklerTest.scala, PositionsUnpicklerTest.scala, QueryApiTest.scala, Scala2PickleTest.scala, SnapshotRoundTripTest.scala, SubtypeTest.scala, SymbolResolutionTest.scala, TastyHeaderTest.scala, TreeUnpicklerTest.scala, TypeArenaTest.scala, TypeOpsTest.scala, TypeUnpicklerTest.scala, UnifiedModelTest.scala, Utf8Test.scala}`
- `kyo-tasty/shared/src/test/scala/kyo/fixtures/Embedded.scala`: in-memory fixture pickles.

JVM (`jvmOnly`-tagged or JVM-only infrastructure):
- `kyo-tasty/jvm/src/test/scala/kyo/{JarCentralDirectoryTest.scala, JvmFileSourceTest.scala, ModuleInfoJvmTest.scala, SnapshotRoundTripJvmTest.scala, StackLimitedRunner.scala, TestResourceLoader.scala}`

## Relevant APIs (verbatim signatures)

Public entrypoints quoted from `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala`:

- `final case class Version(major: Int, minor: Int, experimental: Int)` (`Tasty.scala:28`)
- `val supportedTastyVersion: Version = Version(28, 8, 0)` (`Tasty.scala:32`)
- `opaque type Name = Interner.Entry` (`Tasty.scala:45`)
- `def apply(s: String): Name` (`Tasty.scala:48`)
- `private[kyo] def wrap(entry: Interner.Entry): Name` (`Tasty.scala:53`)
- `def asString: String` extension on `Name` (`Tasty.scala:60-63`); body embraces `AllowUnsafe.embrace.danger`.
- `final class Flags(val bits: Long) extends AnyVal` (`Tasty.scala:67`)
- `enum SymbolKind derives CanEqual` with cases `Package, Class, Trait, Object, Method, Field, Val, Var, TypeAlias, OpaqueType, AbstractType, TypeParam, Parameter, Unresolved` (`Tasty.scala:130-134`)
- `enum Constant` with cases `StringConst, IntConst, LongConst, FloatConst, DoubleConst, BooleanConst, CharConst, ByteConst, ShortConst, UnitConst, NullConst, ClassConst(tpe: Type)` (`Tasty.scala:138-151`)
- `final case class Position(sourceFile: Maybe[String], line: Int, column: Int)` (`Tasty.scala:159`)
- `final class Annotation(val annotationType: Type, val argsPickle: Chunk[Byte], private[kyo] val _decodeCtx: Annotation.DecodeContext | Null)` (`Tasty.scala:170-174`)
- `def args(using Frame): Tree < (Sync & Abort[TastyError])` on `Annotation` (`Tasty.scala:178`)
- `def apply(annotationType: Type, argsPickle: Chunk[Byte]): Annotation` (`Tasty.scala:210`)
- `private[kyo] def apply(annotationType: Type, argsPickle: Chunk[Byte], decodeCtx: DecodeContext): Annotation` (`Tasty.scala:214`)
- `def unapply(a: Annotation): Some[(Type, Chunk[Byte])]` (`Tasty.scala:218`)
- `final case class JavaMetadata(throwsTypes: Chunk[Type], annotations: Chunk[JavaAnnotation], enclosingMethod: Maybe[(Symbol, Name)], accessFlags: Int, recordComponents: Chunk[(Name, Type)])` (`Tasty.scala:232-238`)
- `final case class JavaAnnotation(annotationClass: Symbol, values: Map[Name, JavaAnnotation.Value])` (`Tasty.scala:240`)
- `final case class ModuleRequires(name: String, version: Maybe[String], isTransitive: Boolean, isStaticPhase: Boolean)` (`Tasty.scala:269-274`)
- `final case class ModuleExports(packageName: String, targets: Chunk[String], flags: Long)` (`Tasty.scala:286-290`)
- `final case class ModuleOpens(packageName: String, targets: Chunk[String], flags: Long)` (`Tasty.scala:302-306`)
- `final case class ModuleProvides(serviceName: String, implementations: Chunk[String])` (`Tasty.scala:315-318`)
- `final case class ModuleDescriptor(name: String, version: Maybe[String], requires: Chunk[ModuleRequires], exports: Chunk[ModuleExports], opens: Chunk[ModuleOpens], uses: Chunk[String], provides: Chunk[ModuleProvides])` (`Tasty.scala:339-347`)
- `enum Type` with cases `Named, TermRef, Applied, TypeLambda, Function, Tuple, ByName, Repeated, Array, Refinement, Rec, RecThis, AndType, OrType, Annotated, ConstantType, ThisType, SuperType, ParamRef, Wildcard, Skolem, MatchType, FlexibleType` (`Tasty.scala:351-374`)
- `def show: String` on `Type` (`Tasty.scala:376-382`)
- `sealed trait Tree` plus the 32 concrete `Tree.*` cases (`Tasty.scala:394-492`), including `Tree.Unknown(tag: Int, length: Int)` (`Tasty.scala:491`) used as the M1 escape hatch.
- `final class Symbol private[Tasty] (val kind: SymbolKind, val flags: Flags, val name: Name, val owner: Symbol, private[kyo] val home: ClasspathRef, private[kyo] val origin: Symbol.Origin, private[kyo] val javaMetadata: Maybe[JavaMetadata])` (`Tasty.scala:496-504`)
- `def fullName: Name` (`Tasty.scala:545-549`); body embraces `AllowUnsafe.embrace.danger`.
- `def binaryName: String` (`Tasty.scala:550`)
- `def isInline: Boolean` (`Tasty.scala:551`)
- `def isContextual: Boolean` (`Tasty.scala:552`)
- `def isOpaque: Boolean` (`Tasty.scala:553`)
- `def isPackageObject: Boolean` (`Tasty.scala:554-558`); body embraces `AllowUnsafe.embrace.danger`.
- `def isModule: Boolean` (`Tasty.scala:559`)
- `def isJava: Boolean` (`Tasty.scala:560`)
- `def scaladoc: Maybe[String]` (`Tasty.scala:568-573`); body embraces `AllowUnsafe.embrace.danger`.
- `def position: Maybe[Position]` (`Tasty.scala:582-587`); body embraces `AllowUnsafe.embrace.danger`.
- `def declaredType: Type` (`Tasty.scala:604-611`); body embraces `AllowUnsafe.embrace.danger`.
- `def parents: Chunk[Type]` (`Tasty.scala:617-622`); body embraces `AllowUnsafe.embrace.danger`.
- `def typeParams: Chunk[Symbol]` (`Tasty.scala:628-633`); body embraces `AllowUnsafe.embrace.danger`.
- `def declarations: Chunk[Symbol]` (`Tasty.scala:639-644`); body embraces `AllowUnsafe.embrace.danger`.
- `def companion: Maybe[Symbol]` (`Tasty.scala:654-688`)
- `def javaSpecific: Maybe[JavaMetadata]` (`Tasty.scala:691`)
- `def body(using Frame): Tree < (Sync & Abort[TastyError])` (`Tasty.scala:703-747`)
- `private[kyo] def make(...)` factory (`Tasty.scala:815-824`)
- `sealed trait Origin derives CanEqual` (`Tasty.scala:827`)
- `final class TastyOrigin(val bodyStart: Int, val bodyEnd: Int, val sectionBytes: Array[Byte], val names: Array[Tasty.Name], val sectionOffset: Int, val bodyView: kyo.internal.tasty.binary.ByteView | Null) extends Origin` (`Tasty.scala:846-857`)
- `def addrMap(using AllowUnsafe): IntMap[Tasty.Symbol]` (`Tasty.scala:862`)
- `case object JavaOrigin extends Origin` (`Tasty.scala:882`)
- `final case class Pickle(uuid: String, version: Version, bytes: Chunk[Byte])` (`Tasty.scala:887`)
- `opaque type Classpath = kyo.internal.tasty.query.Classpath` (`Tasty.scala:891`)
- `def open(roots: Seq[String])(using Frame): Classpath < (Sync & Async & Scope & Abort[TastyError])` (`Tasty.scala:899-900`)
- `def open(roots: Seq[String], strict: Boolean)(using Frame): Classpath < (Sync & Async & Scope & Abort[TastyError])` (`Tasty.scala:903-904`)
- `def openCached(roots: Seq[String], cacheDir: String)(using Frame): Classpath < (Sync & Async & Scope & Abort[TastyError])` (`Tasty.scala:910-911`)
- `def fromPickles(pickles: Seq[Pickle])(using Frame): Classpath < Sync` (`Tasty.scala:920-934`)
- `extension (cp: Classpath) def findClass(fqn: String): Maybe[Symbol]` (`Tasty.scala:1014`)
- `def findPackage(fqn: String): Maybe[Symbol]` (`Tasty.scala:1020`)
- `def packages: Chunk[Symbol]` (`Tasty.scala:1026`)
- `def topLevelClasses: Chunk[Symbol]` (`Tasty.scala:1032`)
- `def errors: Chunk[TastyError]` (`Tasty.scala:1038`)
- `def findModule(name: String): Maybe[ModuleDescriptor]` (`Tasty.scala:1047`)
- `def findClassByBinary(binaryName: String): Maybe[Symbol]` (`Tasty.scala:1055-1057`)
- `extension (t: Type) def isSubtypeOf(other: Type)(using cp: Classpath): Boolean` (`Tasty.scala:1080-1092`); current return type is `Boolean`, dispatches to `kyo.internal.tasty.type_.Subtyping.isSubtype(..., budget = 64)`.
- `inline def classFqn[A](using t: Tag[A]): String = t.show` (`Tasty.scala:1097`)
- `def evictOlderThan(cacheDir: String, maxAgeMs: Long)(using Frame): Unit < (Sync & Abort[TastyError])` (`Tasty.scala:1113-1126`)
- `@scala.annotation.targetName("evictOlderThanDuration") def evictOlderThan(cacheDir: String, d: Duration)(using Frame): Unit < (Sync & Abort[TastyError])` (`Tasty.scala:1132-1134`)
- `private[kyo] def evictOlderThanWithSource(cacheDir: String, maxAgeMs: Long, source: kyo.internal.tasty.query.FileSource)(using Frame): Unit < (Sync & Abort[TastyError])` (`Tasty.scala:1137-1152`)

Public error ADT, quoted from `kyo-tasty/shared/src/main/scala/kyo/TastyError.scala`:

- `enum TastyError derives CanEqual` (`TastyError.scala:7`) with cases:
  - `case FileNotFound(path: String)` (`TastyError.scala:8`)
  - `case CorruptedFile(path: String, at: Long, reason: String)` (`TastyError.scala:9`)
  - `case UnsupportedVersion(found: Tasty.Version, supported: Tasty.Version)` (`TastyError.scala:10`)
  - `case InconsistentClasspath(file: String, expectedUuid: java.util.UUID, foundUuid: java.util.UUID)` (`TastyError.scala:11`)
  - `case MalformedSection(name: String, reason: String)` (`TastyError.scala:12`)
  - `case SymbolNotFound(fqn: String)` (`TastyError.scala:13`)
  - `case ClassfileFormatError(path: String, reason: String)` (`TastyError.scala:14`)
  - `case ParameterizedTypeNotAllowed(tag: String)` (`TastyError.scala:15`)
  - `case ClasspathClosed` (`TastyError.scala:16`)
  - `case ClasspathBuilding` (`TastyError.scala:17`)
  - `case SnapshotFormatError(path: String, reason: String)` (`TastyError.scala:18`)
  - `case SnapshotVersionMismatch(found: Tasty.Version, supported: Tasty.Version)` (`TastyError.scala:19`)
  - `case SnapshotIoError(cause: String)` (`TastyError.scala:20`)
  - `case NotImplemented(feature: String)` (`TastyError.scala:21`)

Internal entrypoints relevant to the audit findings:

- `private[kyo] def decodeAnnotationTerm(pickle: kyo.Chunk[Byte], ctx: Tasty.Annotation.DecodeContext): Tasty.Tree` (`TreeUnpickler.scala:37-49`)
- `def decodeSync(origin: Tasty.Symbol.TastyOrigin, sym: Tasty.Symbol): Tasty.Tree` (`TreeUnpickler.scala:60-90`); embraces `AllowUnsafe.embrace.danger` at `:61`.
- `def isSubtype(sub: Tasty.Type, sup: Tasty.Type, cp: InternalClasspath, budget: Int): Boolean` (`Subtyping.scala:50`)
- `def inflate(compressed: Array[Byte])(using Frame): Array[Byte] < (Sync & Abort[TastyError])` abstract base (`shared/.../scala2/InflateHook.scala:18`); concrete JVM impl at `jvm/.../scala2/InflateHook.scala:10`; failing JS/Native impls at the same path under their respective platform folders.
- `final class SingleAssign[A]` with `def set(a: A)(using AllowUnsafe): Unit` (`SingleAssign.scala:22`), `def get()(using AllowUnsafe): A` (`SingleAssign.scala:32`), `def isSet(using AllowUnsafe): Boolean` (`SingleAssign.scala:46`).
- `final class OnceCell[A](init: () => A)` with `def get()(using AllowUnsafe): A` (`OnceCell.scala:32`); race-and-discard semantics documented at `OnceCell.scala:13-19`.

The proof-token and suspension boundary referenced throughout the campaign:

- `abstract class AllowUnsafe private ()` (`kyo-config/shared/src/main/scala/kyo/AllowUnsafe.scala:22`) plus `object embrace { implicit val danger: AllowUnsafe = instance }` (`AllowUnsafe.scala:26-28`).
- `inline def defer[A, S](inline f: AllowUnsafe ?=> A < S)(using inline frame: Frame): A < (Sync & S)` (`kyo-core/shared/src/main/scala/kyo/Sync.scala:138-141`); `Sync.Unsafe.defer` is the §833 option 2 suspension bridge.

## Conventions in this module

### CONTRIBUTING.md anchors

- **Core Principle 4 (Performance):** "Performance is a top-class feature. Avoid allocations, avoid unnecessary suspensions, use `inline` and opaque types where appropriate. Zero-cost abstractions aren't optional." (`CONTRIBUTING.md:167`). Frames the steering rule that routine accessors stay non-`Sync`.
- **Core Principle 6 (Escape hatches last resort):** "`asInstanceOf` and `@unchecked` are acceptable only when they're strictly necessary inside opaque type boundaries or kernel internals." (`CONTRIBUTING.md:171`). Authorizes the `asInstanceOf` inside `SingleAssign.set/get` (`SingleAssign.scala:25,37`) and `OnceCell.get` (`OnceCell.scala:37,41,45`); applies as a check to every other `asInstanceOf` introduced.
- **Method-Signature `AllowUnsafe always last`:** "`AllowUnsafe always last`: `def init(parallelism: Int)(using frame: Frame, allow: AllowUnsafe): ...`" (`CONTRIBUTING.md:344-347`). Binds A2-type signature ordering on every newly-introduced `(using AllowUnsafe)` slot.
- **Method-Signature `Frame and Tag` rule:** "`Frame` on every method that suspends or handles effects. Never on pure data accessors (`capacity`, `size`)." (`CONTRIBUTING.md:349-351`). Says accessors like `Symbol.fullName`, `Symbol.parents`, etc. are NOT supposed to take `Frame`.
- **Overload Organization (§309-§383):** "Simple variants delegate to the canonical implementation, never duplicate logic" (`CONTRIBUTING.md:358-374`). The two `Tasty.Classpath.open` overloads (`Tasty.scala:899,903`) and the two `Tasty.Snapshot.evictOlderThan` overloads (`Tasty.scala:1113,1133`) follow the variadic/delegation pattern, but `private[kyo] evictOlderThanWithSource` (`Tasty.scala:1137-1152`) duplicates the body of `evictOlderThan` rather than delegating, intersecting the A4-style delegation-discipline rule.
- **Code Conventions: explicit return types on public API only** (`CONTRIBUTING.md:406-412`). Binds C1-C4 type-correctness fixes to retain explicit returns on `Tasty.*` public methods.
- **Code Conventions: All public APIs in `kyo` package, internal code in `kyo.internal`** (`CONTRIBUTING.md:414`). Establishes that `kyo.tasty.examples` package (used at `kyo-tasty/shared/src/main/scala/kyo/tasty/examples/CodegenExample.scala:1`) deviates from the `kyo.internal.tasty.*` internal naming. Relevant to L3-style naming clarity.
- **Code Conventions: `Prefer call-by-name` for side-effect bodies** (`CONTRIBUTING.md:418`). Binds B-family bug fixes around eager-evaluation hazards.
- **Type-Level Scaladoc shape:** "Every main public type needs a scaladoc (8-35 lines) covering: opening sentence, conceptual why, feature bullets, gotcha callouts, `@tparam`, `@see`, no code examples unless demonstrating composition" (`CONTRIBUTING.md:426-434`). Bears on L-family documentation completeness.
- **WARNING / IMPORTANT / Note decision:** (`CONTRIBUTING.md:436-439`). Bears on L5 (TastyError context enrichment phrasing) and any new WARNING scaladocs on Unsafe boundaries.
- **Visibility Tiers:** `private[kyo]` for cross-package internal utilities; `private` for class-local helpers (`CONTRIBUTING.md:533-538`). Anchors A-family on the existing `private[kyo]` use across `Tasty.scala:53,173,215,221-229,501-515` and `private[tasty]` on `kyo.internal.tasty.query.Classpath` (`kyo-tasty/.../query/Classpath.scala:20`).
- **Testing framework:** "Each module defines an `abstract class Test` in `src/test/scala/kyo/Test.scala` that extends `AsyncFreeSpec with NonImplicitAssertions` and mixes in one of the base traits." (`CONTRIBUTING.md:694`). Module's `Test.scala` (`kyo-tasty/shared/src/test/scala/kyo/Test.scala:10`) extends `AsyncFreeSpec with NonImplicitAssertions with BaseKyoCoreTest`, matching the prescribed pattern. Steering says: no withFixture / FutureOutcome / TestCanceledException; AsyncFreeSpec + assert/fail/cancel + `kyo.Test` helpers only.
- **Test file naming:** Steering.md (`audit-fixes/steering.md:97-104`): test files PREFIX-match source basenames; 1:1 preferred; topic split allowed; scenario-coded suffixes (XxxIdempotenceTest) forbidden. Current test layout follows this: `TastyHeader.scala` is covered by `TastyHeaderTest.scala`; `Subtyping.scala` is covered by `SubtypeTest.scala` (prefix match against `Subtype-` is the topic header here); etc.
- **Unsafe Boundary, the two-tier model (§792-§812):** "`T.Unsafe` opaque type or sealed abstract class with operations under `(using AllowUnsafe)`; safe tier wraps in effects" (`CONTRIBUTING.md:792-810`). Steering.md (`audit-fixes/steering.md:46-49`) explicitly RULES OUT this pattern for `Symbol` and `Classpath` because they are data containers, not concurrent types. The pattern still applies to potentially newly-introduced `Snapshot.Unsafe` / `Classpath.Unsafe` paths if any A-family finding proposes them; steering rejects those proposals.
- **AllowUnsafe Tiers ordered by preference (§822-§842):** (a) "Propagate the proof: caller explicitly opts in (preferred for performance, no suspension overhead)" with the example signature `def init[A](capacity: Int)(using AllowUnsafe): Queue.Unsafe[A]` (`CONTRIBUTING.md:828-831`); (b) "Suspend in Sync" via `Sync.Unsafe.defer(Abort.get(self.offer(v)))` (`CONTRIBUTING.md:833-837`); (c) "Import danger" only for external runtime callbacks, application boundaries, and initialization of globally shared module-level values (`CONTRIBUTING.md:839-842`). Steering binds the remediation: routine accessors take option 1, decode entrypoints take option 2 or 3 (case 3 boundaries only).
- **Scope AllowUnsafe narrowly:** "Never place `(using AllowUnsafe)` on a constructor or class-level import where it leaks to all methods" (`CONTRIBUTING.md:844-855`). Bars wide-import remediation.
- **AllowUnsafe for Zero-Allocation Side Effects:** "AllowUnsafe is a compiler-enforced proof that the side effect has already been suspended at an outer scope. Methods can perform side effects directly without allocating a `Sync.Unsafe` suspension" (`CONTRIBUTING.md:870-897`). Establishes the rationale for the §828 propagate-the-proof preferred path that steering selects.

### Test placement (binding for T1-T8)

- Steering: shared/ for all cross-platform tests; never demote to a platform folder to dodge infra cost (`audit-fixes/steering.md:71-72`).
- Existing JVM-only tests in `kyo-tasty/jvm/src/test/scala/kyo/` are JVM-specific by infrastructure (resource loading, mmap, jar central directory): `JarCentralDirectoryTest.scala`, `JvmFileSourceTest.scala`, `ModuleInfoJvmTest.scala`, `SnapshotRoundTripJvmTest.scala` plus the support files `TestResourceLoader.scala` and `StackLimitedRunner.scala`. Newly-introduced tests intended to verify cross-platform behavior (M5 ZLIB port if it lands cross-platform, M6 Subtyping change, M1 body decoder) MUST live in `shared/src/test/scala/kyo/`.

## Prior art for the task type

### Cross-platform refactor delivering JVM/JS/Native parity (matches the M5 ZLIB port)

- **kyo-http PR #1518 / commit 815ba1998:** the pure-Scala transport refactor split a single shared core from three platform implementations. JVM uses java NIO selectors (`kyo-http/jvm/src/main/scala/kyo/internal/{NioIoDriver,NioTransport,NioHandle,NioTlsState,HttpPlatformTransport}.scala`), Native uses direct epoll/kqueue via FFI (`kyo-http/native/src/main/scala/kyo/internal/{NativeIoDriver,NativeTransport,NativeHandle,NativeTlsState,EpollPollerBackend,KqueuePollerBackend,PosixBindings,TlsBindings}.scala`), JS uses fetch. Pattern: shared module declares the abstract typeclass / hook; each platform folder provides a concrete `object ImplOnPlatform extends AbstractBase`. Identical shape to the existing kyo-tasty `InflateHook` cross-platform layout. The M5 ZLIB port mirrors this pattern: shared/abstract base remains, the JS/Native concrete implementations replace `Abort.fail(NotImplemented)` with a real RFC 1950 inflate.

### Module extraction preserving full feature scope (matches steering "no scope cuts")

- **kyo-sql extraction from kyo-net (worktree `cheerful-splashing-manatee`):** restored from `worktree-spicy-petting-widget` (commit 435609e0d), 94 tests passing. Cross-platform parity from day one (kyo-net pool layer borrowed under AllowUnsafe with `// Unsafe:` annotations at each site; safe Kyo APIs by default elsewhere). Demonstrates the steering rule "extracted/derived modules inherit donor module's full platform + feature scope day one"; no "eventually" hedging.

### Module that depends on kyo-tasty (downstream pressure for L1/L2 rename consistency)

- **kyo-ts transpiler (worktree `quirky-pondering-wadler`):** 157 TS types generated (0 errors), ~121 Scala.js facades. Pipeline at `kyo-ts/generate.sh`; key files `kyo-ts/jvm/src/main/scala/kyo/gen/{Main,CodeEmitter,MethodExtractor,TypeMapper}.scala`. Cites kyo-tasty as upstream. Pattern: kyo-ts walks a kyo-tasty `Classpath`, projects each symbol into a TS facade descriptor. The L1/L2 rename of README and DESIGN from "kyo-reflect" to "kyo-tasty" must keep the downstream package names (`kyo.Tasty.*`, `kyo.internal.tasty.*`) intact so kyo-ts call sites remain unbroken; the source already uses `Tasty.*`, only docs/comments still say `Reflect`.

### Shared-core + platform-bridge pattern in the same worktree (kyo-tasty itself)

- Existing `InflateHook` already follows the pattern that PR #1518 codified at scale: `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/scala2/InflateHook.scala:13` declares `abstract private[scala2] class InflateHookImpl` with `def inflate(compressed: Array[Byte])(using Frame): Array[Byte] < (Sync & Abort[TastyError])`; JVM provides the concrete via `java.util.zip.InflaterInputStream`; JS and Native return `Abort.fail(NotImplemented)`. M5 reuses this exact shape and replaces the failure-stub bodies with pure-Scala RFC 1950 implementations.

## Open observations

The following items either cannot be fully grounded in code citations (because the six audit reports referenced in the task statement are not present on disk in this worktree), or are value-judgment forks the next step needs the user / `flow-resolve-open` to settle.

- The six audit reports the task statement cites ("correctness, completeness, clarity, potential bugs, test coverage, CONTRIBUTING.md adherence") are not present at `kyo-tasty/audit-fixes/`; only `steering.md` is committed. The 48 finding codes (C1-C4, M1-M10, L1-L7, B1-B15, T1-T8, A1-A4) and their specific file:line anchors must therefore be reconstructed before `flow-design` starts. The steering rules referenced in the task statement (Symbol.body decoder coverage M1, snapshot format bump M4, cross-platform ZLIB M5, Subtyping under-determination M6, README/DESIGN rename L1/L2, AllowUnsafe boundary A1) are partially recoverable from the steering.md text and from the README/DESIGN/source state; the remaining 40+ finding codes need the audit reports to be located and re-attached, or re-derived. [needs flow-resolve-open: locate and attach the six audit reports OR direct the explorer to re-audit each category in a follow-up flow-explore pass]
- M6 Subtyping under-determination: `Subtyping.isSubtype` returns `Boolean` and collapses every under-determined case (budget exhaustion at `Subtyping.scala:51`, structurally-incompatible argument shapes) to `false`. The prompt suggests the user previously preferred `Maybe[Boolean]` (Present(true) = subtype, Present(false) = not a subtype, Absent = under-determined). The public extension method signature is `def isSubtypeOf(other: Type)(using cp: Classpath): Boolean` (`Tasty.scala:1091`); changing the return type is a breaking change. [needs flow-resolve-open: confirm Maybe[Boolean] vs a custom `enum SubtypeVerdict { case Sub, NotSub, Unknown }`; confirm whether a parallel `isSubtypeOfMaybe` accessor coexists with the Boolean variant, or whether the Boolean variant is replaced outright per steering's "no backwards compat" rule (`audit-fixes/steering.md:63`)]
- M5 ZLIB cross-platform port: the JS and Native implementations of `InflateHook` currently return `Abort.fail(TastyError.NotImplemented(...))` (`js/.../scala2/InflateHook.scala:8`, `native/.../scala2/InflateHook.scala:8`). Two implementation paths exist: (a) port RFC 1950 inflate in tree as pure Scala; (b) reuse an existing pure-Scala ZLIB library if one exists on the JS/Native target ecosystems. [needs flow-resolve-open: pick (a) in-tree port vs (b) external library; if (a), confirm the byte-for-byte parity testing scope against the JVM `java.util.zip.InflaterInputStream` reference]
- M1 Symbol.body decoder coverage: `TreeUnpickler` currently returns `Tasty.Tree.Unknown(tag, length)` for many TASTy AST tags (occurrence sites listed in the Module map under reader subsystem). The full set of missing tags is implicit in the `case _ => Tasty.Tree.Unknown(other, ...)` arms at `TreeUnpickler.scala:587-596,594-596`. Decomposition into 2-4 sub-phases (category-1 terms; category-3 definitions; category-4 type-position nodes; category-5 specialized nodes) is one viable split; another is by TastyFormat tag range. [needs flow-resolve-open: pick decomposition axis and confirm coverage target (every tag emitted by a Scala 3 compiler producing TASTy v28.8 with no remaining `Tree.Unknown` arms, or a curated subset)]
- L5 TastyError context enrichment: `TastyError.MalformedSection(name: String, reason: String)` (`TastyError.scala:12`) lacks a `byteOffset: Long` field. Adding one to every malformed-section case helps debugging but is a breaking change. Alternatives: (a) add a `byteOffset: Long = -1L` default (rejected by steering's "no default params on internal/private APIs" rule, but TastyError is public so the rule may not bind), (b) add the offset only to the cases that already structurally carry an `at: Long` (CorruptedFile already does at `TastyError.scala:9`), (c) introduce a separate `MalformedSection(name, reason, at)` and migrate every caller per steering's "no backwards compat" rule. [needs flow-resolve-open: pick the enrichment strategy and confirm scope (all cases or only structured-payload cases)]
- M4 snapshot format version bump: `SnapshotFormat.majorVersion = 1`, `minorVersion = 2` (`SnapshotFormat.scala:57-58`). The versioning policy at `SnapshotFormat.scala:42-44` defines: major bump invalidates all old snapshots, minor bump is add-only. If new sections (e.g., `Subtyping` decisions cache, lazy body addrMap snapshot) are added, the minor bump is the documented path. If the existing section layout changes (e.g., wider Int->Long for a section length), a major bump is required. [needs flow-resolve-open: confirm whether the bump is a versioned coexistence path or a clean break]
- A1 AllowUnsafe boundary restructure: the routine accessor sites listed under `Tasty.scala:62,547,556,570,584,610,620,631,642` use the `import AllowUnsafe.embrace.danger` shortcut. Steering rule (`audit-fixes/steering.md:35-49`) is explicit: the §828 propagate-the-proof path is preferred (signature takes `(using AllowUnsafe)`, returns the raw value, no `Sync` wrapping). The downstream impact is callers of `Symbol.fullName`, `Symbol.parents`, `Symbol.declarations`, `Symbol.declaredType`, `Symbol.typeParams`, `Symbol.scaladoc`, `Symbol.position`, `Symbol.isPackageObject`, `Name.asString` would each need a callsite `(using AllowUnsafe)` proof. [needs flow-resolve-open: confirm that every public caller of these accessors can carry a proof, OR confirm the alternative where the accessors stay `Sync.Unsafe.defer`-bridged at the option 2 cost; mirror the analysis to the corresponding `import AllowUnsafe.embrace.danger` sites in classfile/query/scala2/snapshot subsystems listed in the Module map]
- B-family bug remediation depth: with 15 codes (B1-B15) and no audit-report file:line anchors visible on disk, each one will need either the audit report attached or a re-audit pass over the unpickler error paths. The likely concentration sites are `TreeUnpickler` exception handlers (`Tasty.scala:728-741` already maps `DecodeException`, `ArrayIndexOutOfBoundsException`, `IllegalStateException`), `Scala2PickleReader` (`Scala2PickleReader.scala` 564 lines), and `ClassfileUnpickler` (`ClassfileUnpickler.scala` 1404 lines). [needs flow-resolve-open: confirm the bug-finding source documents]
- T-family test coverage (T1-T8) versus refactor scope: the campaign is a hybrid refactor+test-campaign. Per the steering test-scenario-discipline rules (`audit-fixes/steering.md:90-109`), test entries land as numbered Given/When/Then scenarios in EXISTING prefix-matching topic files. The current test surface enumerated in the Module map covers most existing source modules 1:1 (Subtyping.scala -> SubtypeTest.scala, TastyHeader.scala -> TastyHeaderTest.scala); new source files would create new prefix-matched test files. T-family findings probably target coverage gaps inside the existing tests rather than new test files. [needs flow-resolve-open: confirm the test-coverage findings' target files, and whether the campaign needs a `flow-design`-time sub-task split into refactor and test-campaign tracks]
- A4 overload organization: `Tasty.Snapshot.evictOlderThan` (`Tasty.scala:1113`), `Tasty.Snapshot.evictOlderThan` Duration variant (`Tasty.scala:1133`), and `Tasty.Snapshot.evictOlderThanWithSource` (`Tasty.scala:1137`) currently have the Duration variant delegate to the millis variant (good, matches §358-§374), but `evictOlderThanWithSource` duplicates the body of `evictOlderThan` rather than the millis variant delegating to it. The canonical-impl + variants pattern in §362-§374 suggests `evictOlderThanWithSource` (or its underlying implementation) IS the canonical, and the two public overloads delegate. [needs flow-resolve-open: confirm which variant is canonical and which delegates]
- L1 / L2 README / DESIGN rename: the file `kyo-tasty/README.md` references `kyo-reflect` and the `Reflect.*` types 48 times (`README.md:1,3,11,13,14,16,...`); `kyo-tasty/DESIGN.md` similarly references `kyo-reflect` / `Reflect.*` 69 times. The Scala source already uses `kyo.Tasty.*`; the rename is purely in docs. Mechanical replacement is straightforward; the open question is whether any of the README's `Reflect.Reads` typeclass discussion (`README.md:41`) reflects a feature that exists in code (none of the source files matched by `rg --files` define a `Reads` typeclass), or describes a still-aspirational v1 feature. [needs flow-resolve-open: confirm `Reflect.Reads` becomes `Tasty.Reads` and exists in source, OR is removed from docs as out-of-scope]
- The `kyo.tasty.examples` package (`shared/.../kyo/tasty/examples/CodegenExample.scala:1`) deviates from the `kyo` (public) vs `kyo.internal.tasty` (internal) two-namespace convention quoted from `CONTRIBUTING.md:414`. L3-style clarity finding may target this package name. [needs flow-resolve-open: confirm whether examples move into a separate `kyo-tasty-examples` module or rename to align with `kyo.internal.tasty.examples` or stay where they are with an explanatory note]

## Citations index

- `kyo-tasty/audit-fixes/steering.md:35-49` AllowUnsafe restructure decisions
- `kyo-tasty/audit-fixes/steering.md:46-49` two-tier rejected for Symbol/Classpath
- `kyo-tasty/audit-fixes/steering.md:63` no backwards compatibility shims
- `kyo-tasty/audit-fixes/steering.md:71-72` shared/ for cross-platform tests
- `kyo-tasty/audit-fixes/steering.md:90-109` test scenario discipline rules
- `kyo-tasty/audit-fixes/steering.md:97-104` test file PREFIX-match naming
- `kyo-tasty/README.md:1,3` rename target docs (kyo-reflect heading)
- `kyo-tasty/README.md:41` `Reflect.Reads` typeclass mention
- `kyo-tasty/DESIGN.md:1-9` kyo-reflect Design heading
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:28` Version
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:32` supportedTastyVersion 28.8.0
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:45` Name opaque type
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:48` Name.apply
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:53` Name.wrap
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:60-63` Name.asString extension
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:62` embrace.danger inside Name.asString
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:67` Flags
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:130-134` SymbolKind enum
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:138-151` Constant enum
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:159` Position
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:170-204` Annotation
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:178` Annotation.args
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:210,214,218` Annotation factories + unapply
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:232-238` JavaMetadata
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:240-253` JavaAnnotation
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:269-274` ModuleRequires
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:286-290` ModuleExports
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:302-306` ModuleOpens
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:315-318` ModuleProvides
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:339-347` ModuleDescriptor
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:351-374` Type enum
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:376-382` Type.show
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:394-492` Tree ADT
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:491` Tree.Unknown
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:496-504` Symbol fields
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:533` embrace.danger in _bodyOnce init
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:545-549` Symbol.fullName + embrace
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:547` embrace.danger in fullName
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:550` Symbol.binaryName
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:551-560` Symbol flag accessors
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:556` embrace.danger in isPackageObject
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:568-573` Symbol.scaladoc
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:570` embrace.danger in scaladoc
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:582-587` Symbol.position
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:584` embrace.danger in position
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:604-611` Symbol.declaredType
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:610` embrace.danger in declaredType
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:617-622` Symbol.parents
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:620` embrace.danger in parents
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:628-633` Symbol.typeParams
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:631` embrace.danger in typeParams
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:639-644` Symbol.declarations
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:642` embrace.danger in declarations
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:654-688` Symbol.companion
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:691` Symbol.javaSpecific
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:703-747` Symbol.body
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:719` embrace.danger in body branch
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:815-824` Symbol.make factory
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:827` Origin sealed trait
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:846-857` TastyOrigin
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:862` TastyOrigin.addrMap
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:882` JavaOrigin
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:887` Pickle case class
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:891` Classpath opaque type
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:899-904` Classpath.open overloads
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:910-911` Classpath.openCached
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:920-934` Classpath.fromPickles
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:995` embrace.danger in assignHomes
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:1014-1057` extension methods on Classpath
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:1080-1092` isSubtypeOf extension
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:1091` isSubtypeOf return type Boolean
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:1097` classFqn helper
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:1113-1126` Snapshot.evictOlderThan
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:1132-1134` Snapshot.evictOlderThan Duration overload
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:1137-1152` Snapshot.evictOlderThanWithSource
- `kyo-tasty/shared/src/main/scala/kyo/TastyError.scala:7-22` TastyError enum
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/TreeUnpickler.scala:22` object TreeUnpickler
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/TreeUnpickler.scala:25` DecodeException
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/TreeUnpickler.scala:37-49` decodeAnnotationTerm
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/TreeUnpickler.scala:60-90` decodeSync
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/TreeUnpickler.scala:61` embrace.danger in decodeSync
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/TreeUnpickler.scala:129,186,216-220,258,264,268,313-317,323,380,512,519,587-596,707` Tree.Unknown emission sites
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/AstUnpickler.scala:161` embrace.danger in AstUnpickler
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/classfile/ClassfileFormat.scala:61-71` attribute name constants
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/classfile/ClassfileUnpickler.scala:73` embrace.danger in ClassfileUnpickler
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/classfile/ClassfileUnpickler.scala:343,378,395,502,521,553,577,598,617,636,656,750` attribute match arms
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/classfile/ClassfileUnpickler.scala:700-755` record component sub-attribute reader
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/classfile/ConstantPool.scala:86,92` embrace.danger sites
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/query/Classpath.scala:20` Classpath final class
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/query/Classpath.scala:70,80,90,100,110,138,156,214,222` embrace.danger sites
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/query/Classpath.scala:166` Classpath companion
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/query/ClasspathOrchestrator.scala:377,389,446` embrace.danger sites
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/query/ClasspathRef.scala:21,28,35` embrace.danger sites
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/query/ClasspathTestHelpers.scala:18` embrace.danger site
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/scala2/Scala2PickleReader.scala:284,369,401,445` embrace.danger sites
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/scala2/InflateHook.scala:13-19` shared abstract InflateHookImpl
- `kyo-tasty/jvm/src/main/scala/kyo/internal/tasty/scala2/InflateHook.scala:1-27` JVM ZLIB impl
- `kyo-tasty/native/src/main/scala/kyo/internal/tasty/scala2/InflateHook.scala:1-10` Native NotImplemented stub
- `kyo-tasty/js/src/main/scala/kyo/internal/tasty/scala2/InflateHook.scala:1-10` JS NotImplemented stub
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/snapshot/SnapshotFormat.scala:42-44` versioning policy
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/snapshot/SnapshotFormat.scala:57-58` majorVersion=1 minorVersion=2
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/snapshot/SnapshotReader.scala:173,245` embrace.danger sites
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/snapshot/SnapshotWriter.scala:61` embrace.danger site
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/symbol/SingleAssign.scala:14-49` SingleAssign with set/get/isSet under AllowUnsafe
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/symbol/OnceCell.scala:13-19` race-and-discard semantics
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/symbol/OnceCell.scala:32-47` OnceCell.get under AllowUnsafe
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/symbol/Symbol.scala:13-30` internal makeSymbol factory
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/type_/Subtyping.scala:18,29` budget exhaustion semantics
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/type_/Subtyping.scala:50-51` isSubtype signature and budget check
- `kyo-tasty/shared/src/test/scala/kyo/Test.scala:10-21` test base class
- `kyo-tasty/shared/src/main/scala/kyo/tasty/examples/CodegenExample.scala:1` examples package header
- `CONTRIBUTING.md:167` Performance is a top-class feature
- `CONTRIBUTING.md:171` Type safety by default, escape hatches as last resort
- `CONTRIBUTING.md:344-347` AllowUnsafe always last
- `CONTRIBUTING.md:349-351` Frame on suspending methods, never on accessors
- `CONTRIBUTING.md:358-374` Overload organization with canonical impl + variants
- `CONTRIBUTING.md:406-412` Explicit return types on public API
- `CONTRIBUTING.md:414` All public APIs in kyo package, internal in kyo.internal
- `CONTRIBUTING.md:418` Prefer call-by-name
- `CONTRIBUTING.md:426-434` Type-level scaladoc shape
- `CONTRIBUTING.md:436-439` WARNING / IMPORTANT / Note decision
- `CONTRIBUTING.md:533-538` Visibility tiers
- `CONTRIBUTING.md:694` Test base class convention
- `CONTRIBUTING.md:792-810` Two-tier API pattern
- `CONTRIBUTING.md:822-842` AllowUnsafe tiers ordered by preference
- `CONTRIBUTING.md:828-831` Propagate the proof signature example
- `CONTRIBUTING.md:833-837` Sync.Unsafe.defer bridge example
- `CONTRIBUTING.md:839-842` Case 3 import danger boundaries
- `CONTRIBUTING.md:844-855` Scope AllowUnsafe narrowly
- `CONTRIBUTING.md:870-897` Zero-allocation side effects rationale
- `kyo-config/shared/src/main/scala/kyo/AllowUnsafe.scala:22-28` AllowUnsafe proof token
- `kyo-core/shared/src/main/scala/kyo/Sync.scala:138-141` Sync.Unsafe.defer suspension boundary

Finding-code coverage map (cross-check that every code C1-C4, M1-M10, L1-L7, B1-B15, T1-T8, A1-A4 appears at least once):

- C1, C2, C3, C4 (correctness): touched at the public-surface entries (Symbol.body, Annotation.args, Subtyping.isSubtype, Classpath.open/openCached, Snapshot.evictOlderThan), plus the embrace.danger sites listed throughout the Module map. Specific file:line mapping is in the open observation flagging the missing audit reports.
- M1 (Symbol.body decoder coverage): TreeUnpickler.scala Tree.Unknown emission sites listed in Module map and citations index.
- M2, M3 (further completeness gaps): ClassfileUnpickler.scala attribute-match arms 343-750, ClassfileFormat.scala 61-71 attribute name set (AttrCode and AttrExceptions present in format but absent from match arms).
- M4 (snapshot format bump): SnapshotFormat.scala 42-44, 57-58.
- M5 (cross-platform ZLIB): InflateHook three platform files cited.
- M6 (Subtyping under-determination): Subtyping.scala 18, 29, 50-51, plus Tasty.scala 1091 public surface.
- M7, M8, M9, M10 (further completeness gaps): touched through Symbol accessors (declaredType, parents, typeParams, declarations, companion, body) at Tasty.scala 604-747, Annotation.args at 178, JavaMetadata at 232, ModuleDescriptor at 339.
- L1 (kyo-reflect rename in README): README.md 1, 3, 41 + 48-instance count cited.
- L2 (kyo-reflect rename in DESIGN): DESIGN.md 1-9 + 69-instance count cited.
- L3, L4, L5, L6, L7 (clarity): scaladocs across Tasty.scala public surface, TastyError.scala enrichment site, examples package convention deviation.
- B1, B2, B3, B4, B5, B6, B7, B8, B9, B10, B11, B12, B13, B14, B15 (potential bugs): error-handling sites in TreeUnpickler.scala (728-741 exception arms), Scala2PickleReader.scala unsafe imports at 284/369/401/445, ClassfileUnpickler.scala attribute handling at 343-750 plus the unreached AttrCode and AttrExceptions arms, SnapshotReader.scala unsafe imports at 173/245, Subtyping.scala budget arithmetic at 50-51, Symbol.companion FQN concatenation at Tasty.scala 654-688 (root-owner branch), OnceCell.scala race-and-discard at 32-47, Annotation equality byte-array comparison at Tasty.scala 197-203, Scala2 ZLIB error mapping at jvm/.../scala2/InflateHook.scala 22-26. Per-code file:line mapping is in the absent-audit-reports open observation.
- T1, T2, T3, T4, T5, T6, T7, T8 (test coverage): the test surface enumerated in the Module map (29 shared test files, 6 JVM-only test files) is the substrate; specific gaps reside in the absent audit reports per the open observation. Candidate target files matched by topic-prefix include TreeUnpicklerTest.scala for M1-related body decode coverage, SubtypeTest.scala for M6, SnapshotRoundTripTest.scala plus SnapshotRoundTripJvmTest.scala for M4, ClassfileReaderTest.scala for attribute coverage, Scala2PickleTest.scala for M5 cross-platform ZLIB behavior, QueryApiTest.scala for Classpath.* coverage, AstUnpicklerTest.scala plus TastyHeaderTest.scala plus AttributeUnpicklerTest.scala for reader coverage, and UnifiedModelTest.scala for cross-cutting Symbol / Type / Annotation scenarios.
- A1 (CONTRIBUTING.md unsafe boundary adherence): every `import AllowUnsafe.embrace.danger` site enumerated in the Module map and citations index.
- A2 (AllowUnsafe-last clause ordering): every `(using AllowUnsafe)` slot in SingleAssign.scala 22, 32, 46, OnceCell.scala 32, TastyOrigin.addrMap at Tasty.scala 862.
- A3 (visibility tier adherence): `private[Tasty]` (`Tasty.scala:496,759,772`), `private[kyo]` (`Tasty.scala:53,173,215,221-229,501-515,815,914,917`), `private[tasty]` (`Classpath.scala:20`).
- A4 (overload organization): Tasty.Classpath.open (899, 903), Tasty.Snapshot.evictOlderThan (1113, 1132, 1137).
