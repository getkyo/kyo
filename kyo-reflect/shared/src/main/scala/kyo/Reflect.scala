package kyo

import kyo.internal.reflect.binary.Utf8
import kyo.internal.reflect.query.ClasspathOrchestrator
import kyo.internal.reflect.query.ClasspathRef
import kyo.internal.reflect.query.PlatformFileSource
import kyo.internal.reflect.query.Query
import kyo.internal.reflect.snapshot.DigestComputer as SnapshotDigest
import kyo.internal.reflect.snapshot.SnapshotReader
import kyo.internal.reflect.snapshot.SnapshotWriter
import kyo.internal.reflect.symbol.Interner
import kyo.internal.reflect.type_.TypeArena

/** kyo-reflect public entry object.
  *
  * Reads Scala 3 TASTy files and Java classfiles through a unified Symbol/Type API. Cross-platform JVM, JS, Native.
  *
  * Note on naming: all types are nested in `object Reflect` (`Reflect.Type`, `Reflect.Symbol`, etc.) to avoid polluting `kyo.*` and to keep
  * separation from `kyo.Structure.Type` (kyo-schema's value-structure type tree). If both `Structure` and `Reflect` are imported in the
  * same file, reference the types qualified (`Structure.Type`, `Reflect.Type`).
  */
object Reflect:

    // ── Version ─────────────────────────────────────────────────────────────

    final case class Version(major: Int, minor: Int, experimental: Int):
        def show: String = s"$major.$minor.$experimental"

    // The Scala 3 TASTy version this kyo-reflect release targets. Updated per release.
    val supportedTastyVersion: Version = Version(28, 8, 0)

    // ── Names and flags ─────────────────────────────────────────────────────

    // A module-level interner used by Name.apply(String) so the public API stays unchanged.
    private val globalInterner: Interner = new Interner(32)

    /** An interned name backed by a byte sequence.
      *
      * The internal representation is `Interner.Entry`, which stores raw UTF-8 bytes and decodes to a `String` lazily via `Memo[String]`.
      * Reference equality on two `Name` values implies byte-level equality because the interner guarantees a unique `Entry` per unique byte
      * sequence. The `CanEqual` instance delegates to reference equality, which is therefore correct.
      */
    opaque type Name = Interner.Entry
    object Name:
        /** Construct a `Name` from a `String` by encoding to UTF-8 and interning the bytes. */
        def apply(s: String): Name =
            val bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            globalInterner.intern(bytes, 0, bytes.length)

        /** Wrap an already-interned `Entry` as a `Name`. For use by kyo-internal unpicklers only. */
        private[kyo] def wrap(entry: Interner.Entry): Name = entry

        /** Reference equality is correct because the interner guarantees unique Entry per unique byte sequence. */
        given CanEqual[Name, Name] = CanEqual.canEqualAny

        extension (n: Name)
            /** Decode the interned bytes to a String (lazily cached). */
            def asString: String =
                // Unsafe: Memo.get() is an unsafe-tier helper; AllowUnsafe is embraced here at the public API boundary.
                import AllowUnsafe.embrace.danger
                n.string.get()
        end extension
    end Name

    final class Flags(val bits: Long) extends AnyVal:
        def contains(flag: Flag): Boolean = (bits & flag.bit) != 0L
        def |(other: Flags): Flags        = new Flags(bits | other.bits)

    object Flags:
        val empty: Flags = new Flags(0L)

    final class Flag(val bit: Long, val name: String):
        override def toString: String = name

    object Flag:
        // Phase 0 flags (bits 0-15)
        val Inline: Flag      = Flag(1L << 0, "Inline")
        val Private: Flag     = Flag(1L << 1, "Private")
        val Protected: Flag   = Flag(1L << 2, "Protected")
        val Public: Flag      = Flag(1L << 3, "Public")
        val Final: Flag       = Flag(1L << 4, "Final")
        val Sealed: Flag      = Flag(1L << 5, "Sealed")
        val Abstract: Flag    = Flag(1L << 6, "Abstract")
        val Given: Flag       = Flag(1L << 7, "Given")
        val Implicit: Flag    = Flag(1L << 8, "Implicit")
        val Opaque: Flag      = Flag(1L << 9, "Opaque")
        val Case: Flag        = Flag(1L << 10, "Case")
        val Module: Flag      = Flag(1L << 11, "Module")
        val Synthetic: Flag   = Flag(1L << 12, "Synthetic")
        val JavaDefined: Flag = Flag(1L << 13, "JavaDefined")
        val Enum: Flag        = Flag(1L << 14, "Enum")
        val JavaRecord: Flag  = Flag(1L << 15, "JavaRecord")
        // Phase 3 flags (bits 16+)
        val Open: Flag          = Flag(1L << 16, "Open")
        val ParamAccessor: Flag = Flag(1L << 17, "ParamAccessor")
        val Lazy: Flag          = Flag(1L << 18, "Lazy")
        val Override: Flag      = Flag(1L << 19, "Override")
        val Mutable: Flag       = Flag(1L << 20, "Mutable")
        val Erased: Flag        = Flag(1L << 21, "Erased")
        val Tracked: Flag       = Flag(1L << 22, "Tracked")
        val Tailrec: Flag       = Flag(1L << 23, "Tailrec")
        val Infix: Flag         = Flag(1L << 24, "Infix")
        val Transparent: Flag   = Flag(1L << 25, "Transparent")
        val Trait: Flag         = Flag(1L << 26, "Trait")
        val CaseAccessor: Flag  = Flag(1L << 27, "CaseAccessor")
        val FieldAccessor: Flag = Flag(1L << 28, "FieldAccessor")
        val Macro: Flag         = Flag(1L << 29, "Macro")
        val InlineProxy: Flag   = Flag(1L << 30, "InlineProxy")
        val Extension: Flag     = Flag(1L << 31, "Extension")
        val Exported: Flag      = Flag(1L << 32, "Exported")
        val CoVariant: Flag     = Flag(1L << 33, "CoVariant")
        val ContraVariant: Flag = Flag(1L << 34, "ContraVariant")
        val HasDefault: Flag    = Flag(1L << 35, "HasDefault")
        val Stable: Flag        = Flag(1L << 36, "Stable")
        val Local: Flag         = Flag(1L << 37, "Local")
        val Artifact: Flag      = Flag(1L << 38, "Artifact")
        val Invisible: Flag     = Flag(1L << 39, "Invisible")
        val Into: Flag          = Flag(1L << 40, "Into")
        val PARAMsetter: Flag   = Flag(1L << 41, "PARAMsetter")
        val PARAMalias: Flag    = Flag(1L << 42, "PARAMalias")
        val Static: Flag        = Flag(1L << 43, "Static")
    end Flag

    // ── Symbol kinds ────────────────────────────────────────────────────────

    enum SymbolKind derives CanEqual:
        case Package, Class, Trait, Object, Method, Field, Val, Var,
            TypeAlias, OpaqueType, AbstractType, TypeParam, Parameter,
            Unresolved
    end SymbolKind

    // ── Constants and annotations ───────────────────────────────────────────

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
    end Constant

    final case class Annotation(annotationType: Type, argsPickle: Chunk[Byte])

    final case class JavaMetadata(
        throwsTypes: Chunk[Type],
        annotations: Chunk[JavaAnnotation],
        enclosingMethod: Maybe[(Symbol, Name)],
        accessFlags: Int,
        recordComponents: Chunk[(Name, Type)]
    )

    final case class JavaAnnotation(annotationClass: Symbol, values: Map[Name, JavaAnnotation.Value])
    object JavaAnnotation:
        enum Value:
            case StringVal(s: String)
            case IntVal(i: Int)
            case LongVal(l: Long)
            case FloatVal(f: Float)
            case DoubleVal(d: Double)
            case BoolVal(b: Boolean)
            case ClassVal(tpe: Type)
            case EnumVal(enumType: Symbol, constant: Name)
            case ArrayVal(elements: Chunk[Value])
            case AnnotationVal(nested: JavaAnnotation)
        end Value
    end JavaAnnotation

    // ── Type ADT ────────────────────────────────────────────────────────────

    enum Type:
        case Named(symbol: Symbol)
        case TermRef(prefix: Type, name: Name)
        case Applied(base: Type, args: Chunk[Type])
        case TypeLambda(params: Chunk[Symbol], body: Type)
        case Function(params: Chunk[Type], result: Type, isContext: Boolean)
        case Tuple(elements: Chunk[Type])
        case ByName(underlying: Type)
        case Repeated(elem: Type)
        case Array(elem: Type)
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

        def show: String = this match
            case Named(sym)             => sym.fullName.toString
            case Applied(base, args)    => s"${base.show}[${args.map(_.show).mkString(", ")}]"
            case Array(elem)            => s"${elem.show}[]"
            case Function(ps, r, isCtx) => s"(${ps.map(_.show).mkString(", ")}) ${if isCtx then "?=>" else "=>"} ${r.show}"
            case Tuple(es)              => s"(${es.map(_.show).mkString(", ")})"
            case other                  => other.toString
    end Type

    // ── Symbol ──────────────────────────────────────────────────────────────

    final class Symbol private[Reflect] (
        val kind: SymbolKind,
        val flags: Flags,
        val name: Name,
        val owner: Symbol,
        private[Reflect] val home: ClasspathRef,
        private[kyo] val origin: Symbol.Origin,
        private[kyo] val javaMetadata: Maybe[JavaMetadata]
    ):
        // Write-once slots populated during classpath orchestration (Phase 3).
        // Unsafe: SingleAssign is an unsafe-tier helper; callers in mergeResults / ClassfileUnpickler hold AllowUnsafe.
        private[kyo] val _parents: kyo.internal.reflect.symbol.SingleAssign[Chunk[Type]]      = new kyo.internal.reflect.symbol.SingleAssign
        private[kyo] val _typeParams: kyo.internal.reflect.symbol.SingleAssign[Chunk[Symbol]] = new kyo.internal.reflect.symbol.SingleAssign
        private[kyo] val _declarations: kyo.internal.reflect.symbol.SingleAssign[Chunk[Symbol]] =
            new kyo.internal.reflect.symbol.SingleAssign

        // Pure accessors (no effect, always present even after classpath close).
        def fullName: Name        = Symbol.computeFullName(this)
        def binaryName: String    = Symbol.computeBinaryName(this)
        def isInline: Boolean     = flags.contains(Flag.Inline)
        def isContextual: Boolean = flags.contains(Flag.Given)
        def isOpaque: Boolean     = flags.contains(Flag.Opaque)
        def isPackageObject: Boolean =
            // Unsafe: Memo.get() is an unsafe-tier helper; AllowUnsafe is embraced here at the public API boundary.
            import AllowUnsafe.embrace.danger
            flags.contains(Flag.Module) && name.string.get() == "package"
        end isPackageObject
        def isModule: Boolean = flags.contains(Flag.Module)
        def isJava: Boolean   = flags.contains(Flag.JavaDefined)

        // Resolving accessors (return ReflectError.NotImplemented in Phase 0).

        /** The declared type of this symbol.
          *
          * @note
          *   Not implemented in v1. Always fails at runtime with `ReflectError.NotImplemented`. Deferred per DESIGN.md §24 ("Tree body
          *   decode" is out of scope for v1).
          */
        def declaredType(using Frame): Type < (Sync & Abort[ReflectError]) = stub("Symbol.declaredType")

        /** The parent types of this symbol (superclass and mixed-in traits). */
        def parents(using Frame): Chunk[Type] < (Sync & Abort[ReflectError]) =
            if !home.isAssigned then stub("Symbol.parents")
            else
                home.get().checkOpen.andThen:
                    // Unsafe: SingleAssign.get() is an unsafe-tier helper; AllowUnsafe is embraced at the public accessor boundary.
                    import AllowUnsafe.embrace.danger
                    _parents.get()

        /** The type parameters of this symbol. */
        def typeParams(using Frame): Chunk[Symbol] < (Sync & Abort[ReflectError]) =
            if !home.isAssigned then stub("Symbol.typeParams")
            else
                home.get().checkOpen.andThen:
                    // Unsafe: SingleAssign.get() is an unsafe-tier helper; AllowUnsafe is embraced at the public accessor boundary.
                    import AllowUnsafe.embrace.danger
                    _typeParams.get()

        /** The member declarations of this symbol (methods, fields, nested types). */
        def declarations(using Frame): Chunk[Symbol] < (Sync & Abort[ReflectError]) =
            if !home.isAssigned then stub("Symbol.declarations")
            else
                home.get().checkOpen.andThen:
                    // Unsafe: SingleAssign.get() is an unsafe-tier helper; AllowUnsafe is embraced at the public accessor boundary.
                    import AllowUnsafe.embrace.danger
                    _declarations.get()

        /** The companion object symbol of this class or trait, if one exists.
          *
          * For a `Class` or `Trait` symbol, looks up the companion object via FQN `owner.fqn + "." + name + "$"`. For an `Object` symbol,
          * looks up the companion class via the owner FQN and the simple name with any trailing `$` stripped. Java symbols always return
          * `Absent`. All other kinds return `Absent`.
          */
        def companion(using Frame): Maybe[Symbol] < (Sync & Async & Abort[ReflectError]) =
            if isJava then Kyo.lift(Maybe.Absent)
            else if !home.isAssigned then Kyo.lift(Maybe.Absent)
            else
                home.get().checkOpen.andThen:
                    import Name.asString
                    kind match
                        case SymbolKind.Class | SymbolKind.Trait =>
                            // Companion object FQN uses the "$"-suffixed key convention established in fqnIndex.
                            // fqnIndex stores Object-kind symbols under "OwnerFqn.SimpleName$".
                            val ownerFqn = if owner != null && (owner.owner ne owner) then owner.fullName.asString else owner.name.asString
                            val companionFqn = ownerFqn + "." + name.asString + "$"
                            home.get().lookupClass(companionFqn).map:
                                case Present(s) if s.kind == SymbolKind.Object => Maybe(s)
                                case _                                         => Maybe.Absent
                        case SymbolKind.Object =>
                            // Companion class FQN: owner FQN + simple name without trailing "$".
                            // The simple name may or may not end in "$" depending on TASTy encoding;
                            // strip it and look up the class symbol by the plain dotted FQN.
                            val simpleName = name.asString.stripSuffix("$")
                            val ownerFqn = if owner != null && (owner.owner ne owner) then owner.fullName.asString else owner.name.asString
                            val companionFqn = ownerFqn + "." + simpleName
                            home.get().lookupClass(companionFqn).map:
                                case Present(s) if s.kind == SymbolKind.Class || s.kind == SymbolKind.Trait => Maybe(s)
                                case _                                                                      => Maybe.Absent
                        case _ => Kyo.lift(Maybe.Absent)
                    end match

        // Java-specific side door.
        def javaSpecific: Maybe[JavaMetadata] = javaMetadata
    end Symbol

    object Symbol:
        // Bring Name.asString extension into scope for use within computeFullName and computeBinaryName.
        import Name.asString

        /** Walk the owner chain to build the fully-qualified dotted name.
          *
          * The root sentinel symbol owns itself. Package/class separators are all dots. Binary name uses '$' for nested classes and is
          * computed separately via computeBinaryName.
          */
        private[Reflect] def computeFullName(s: Symbol): Name =
            val parts = new scala.collection.mutable.ArrayBuffer[String]()
            var cur   = s
            while (cur ne null) && (cur.owner ne cur) && cur.owner != null do
                parts.prepend(cur.name.asString)
                cur = cur.owner
            parts.prepend(cur.name.asString)
            // Filter empty segments (root sentinel name may be empty)
            val filtered = parts.filter(_.nonEmpty)
            val full     = filtered.mkString(".")
            Name(full)
        end computeFullName

        private[Reflect] def computeBinaryName(s: Symbol): String =
            // Walk owner chain producing JVM binary form:
            //   - packages (kind == Package) contribute segments separated by '/'
            //   - class/trait/object segments are separated by '$' from a preceding class segment
            //   - the overall package prefix uses '/', inner class transitions use '$'
            // Example: java.util.Map.Entry -> "java/util/Map$Entry"
            // Example: com.example.Foo (top-level) -> "com/example/Foo"
            val parts = new scala.collection.mutable.ArrayBuffer[(String, SymbolKind)]()
            var cur   = s
            while (cur ne null) && (cur.owner ne cur) && cur.owner != null do
                parts.prepend((cur.name.asString, cur.kind))
                cur = cur.owner
            parts.prepend((cur.name.asString, cur.kind))
            val filtered = parts.filter(_._1.nonEmpty)
            if filtered.isEmpty then ""
            else
                val sb = new StringBuilder()
                var i  = 0
                while i < filtered.length do
                    val (segment, kind) = filtered(i)
                    if i > 0 then
                        // Use '$' if the previous segment was a class-like kind, '/' otherwise
                        val prevKind = filtered(i - 1)._2
                        if prevKind == SymbolKind.Class || prevKind == SymbolKind.Trait ||
                            prevKind == SymbolKind.Object
                        then
                            sb.append('$')
                        else
                            sb.append('/')
                        end if
                    end if
                    sb.append(segment)
                    i += 1
                end while
                sb.toString
            end if
        end computeBinaryName

        /** Internal factory used by kyo.internal.reflect.symbol.Symbol to construct Symbol instances.
          *
          * The Symbol constructor is private[Reflect] so only code inside object Reflect can call it. This factory bridges that access
          * boundary for internal unpickler code.
          */
        private[kyo] def make(
            kind: SymbolKind,
            flags: Flags,
            name: Name,
            owner: Symbol,
            home: ClasspathRef,
            origin: Origin,
            javaMetadata: Maybe[JavaMetadata]
        ): Symbol =
            new Symbol(kind, flags, name, owner, home, origin, javaMetadata)

        /** The complete Symbol.Origin ADT. Phase 5 adds JavaOrigin construction sites; the ADT itself is sealed here. */
        sealed trait Origin derives CanEqual
        final case class TastyOrigin(
            addrMap: Map[Int, Reflect.Symbol],
            bodyStart: Int,
            bodyEnd: Int
        ) extends Origin
        case object JavaOrigin extends Origin
    end Symbol

    // ── Pickle (in-memory TASTy + classfile bytes) ──────────────────────────

    final case class Pickle(uuid: String, version: Version, bytes: Chunk[Byte])

    // ── Classpath ───────────────────────────────────────────────────────────

    opaque type Classpath = kyo.internal.reflect.query.Classpath

    object Classpath:

        /** Open a classpath from directory/file roots. Soft-fail mode (errors accumulate in `cp.errors`).
          *
          * Registers a finalizer on the enclosing `Scope` to close the classpath.
          */
        def open(roots: Seq[String])(using Frame): Classpath < (Sync & Async & Scope & Abort[ReflectError]) =
            openImpl(roots, strict = false)

        /** Open a classpath from directory/file roots. Strict mode: any file error aborts immediately. */
        def open(roots: Seq[String], strict: Boolean)(using Frame): Classpath < (Sync & Async & Scope & Abort[ReflectError]) =
            openImpl(roots, strict)

        /** Open a classpath from directory/file roots, using a snapshot cache in `cacheDir`.
          *
          * On a cache hit (digest match), deserializes the snapshot directly. On a miss, opens normally then writes a new snapshot.
          */
        def openCached(roots: Seq[String], cacheDir: String)(using Frame): Classpath < (Sync & Async & Scope & Abort[ReflectError]) =
            openCachedImpl(roots, cacheDir)

        /** Wrap a raw InternalClasspath as the public Classpath opaque type. For use by internal test helpers only. */
        private[kyo] def wrap(cp: kyo.internal.reflect.query.Classpath): Classpath = cp

        /** Unwrap the public Classpath opaque type to the internal representation. For use by internal test helpers only. */
        private[kyo] def unwrap(cp: Classpath): kyo.internal.reflect.query.Classpath = cp

        /** Create a classpath from pre-parsed in-memory pickles. */
        def fromPickles(pickles: Seq[Pickle])(using Frame): Classpath < Sync =
            kyo.internal.reflect.query.Classpath.allocate.map: cp =>
                kyo.internal.reflect.query.Classpath.transitionToReady(
                    cp,
                    allSymbols = Chunk.empty,
                    topLevelClasses = Chunk.empty,
                    packages = Chunk.empty,
                    fqnIndex = Map.empty,
                    packageIndex = Map.empty,
                    canonical = TypeArena.canonical(),
                    errors = Chunk.empty
                )
                assignHomes(cp, cp)
                cp

        /** Internal: open implementation, delegates to ClasspathOrchestrator. */
        private def openImpl(
            roots: Seq[String],
            strict: Boolean
        )(using Frame): Classpath < (Sync & Async & Scope & Abort[ReflectError]) =
            val source      = PlatformFileSource.get
            val concurrency = Runtime.getRuntime.availableProcessors().max(1)
            ClasspathOrchestrator.open(roots, strict, source, concurrency).map: cp =>
                assignHomes(cp, cp)
                cp
        end openImpl

        private def openCachedImpl(
            roots: Seq[String],
            cacheDir: String
        )(using Frame): Classpath < (Sync & Async & Scope & Abort[ReflectError]) =
            val source      = PlatformFileSource.get
            val concurrency = Runtime.getRuntime.availableProcessors().max(1)
            // Compute digest of root metadata
            Abort.run[ReflectError](SnapshotDigest.compute(roots, source)).flatMap:
                case Result.Failure(_) =>
                    // Digest computation failed (e.g., browser): fall through to normal open
                    openImpl(roots, strict = false)
                case Result.Panic(_) =>
                    openImpl(roots, strict = false)
                case Result.Success(digest) =>
                    val hexDigest    = SnapshotDigest.toHexString(digest)
                    val snapshotPath = s"$cacheDir/$hexDigest.krfl"
                    source.exists(snapshotPath).flatMap: exists =>
                        if exists then
                            // Try to load from snapshot
                            kyo.internal.reflect.query.Classpath.allocate.flatMap: cp =>
                                Scope.ensure(Sync.defer(kyo.internal.reflect.query.Classpath.close(cp))).andThen:
                                    Abort.run[ReflectError](SnapshotReader.read(snapshotPath, source, cp)).flatMap:
                                        case Result.Success(_) =>
                                            assignHomes(cp, cp)
                                            cp
                                        case Result.Failure(_) | Result.Panic(_) =>
                                            // Snapshot unreadable; fall through to normal open
                                            openImpl(roots, strict = false)
                        else
                            // No snapshot; open normally then write snapshot
                            openImpl(roots, strict = false).flatMap: cp =>
                                Abort.run[ReflectError](SnapshotWriter.write(cp, cacheDir, digest, source)).andThen(cp)
        end openCachedImpl

        /** Assign each symbol's `ClasspathRef` to this classpath. Called once, after the classpath transitions to Ready.
          *
          * Multiple symbols from the same TASTy file share a single `ClasspathRef` instance (one per file). The seen set deduplicates so
          * each slot is assigned exactly once.
          */
        private def assignHomes(cp: kyo.internal.reflect.query.Classpath, cpPublic: Classpath): Unit =
            // Inside object Reflect, Classpath is transparent: cp (internal) == cpPublic (opaque) at runtime.
            // We use AllowUnsafe to read allSymbols without an effect context.
            import AllowUnsafe.embrace.danger
            val syms = cp.allSymbols
            val seen = new java.util.HashSet[kyo.internal.reflect.query.ClasspathRef]()
            var i    = 0
            while i < syms.length do
                val ref = syms(i).home
                if seen.add(ref) then ref.assign(cpPublic)
                i += 1
            end while
        end assignHomes

        /** For internal test helpers: assign homes for all symbols in `cp` to `cp`. */
        private[kyo] def assignHomesForTest(cp: kyo.internal.reflect.query.Classpath): Unit =
            assignHomes(cp, cp)

        /** For internal test helpers: assign the given extra symbols' ClasspathRef slots to `cp`. */
        private[kyo] def assignExtraHomes(cp: Classpath, extra: Seq[Symbol]): Unit =
            for sym <- extra do
                if !sym.home.isAssigned then sym.home.assign(cp)

    end Classpath

    extension (cp: Classpath)(using Frame)
        def findClass(fqn: String): Maybe[Symbol] < (Sync & Async & Abort[ReflectError])   = cp.lookupClass(fqn)
        def findPackage(fqn: String): Maybe[Symbol] < (Sync & Async & Abort[ReflectError]) = cp.lookupPackage(fqn)
        def packages: Chunk[Symbol] < (Sync & Abort[ReflectError])                         = cp.allPackages
        def topLevelClasses: Chunk[Symbol] < (Sync & Abort[ReflectError])                  = cp.allTopLevelClasses
        def errors: Chunk[ReflectError] < Sync                                             = Sync.defer(cp.accumulatedErrors)

        /** Find a class symbol by JVM binary name (e.g., "com/example/Foo$Inner").
          *
          * Converts the binary name to a dotted FQN and delegates to `findClass`.
          */
        def findClassByBinary(binaryName: String): Maybe[Symbol] < (Sync & Async & Abort[ReflectError]) =
            val fqn = binaryName.replace('/', '.').replace('$', '.')
            cp.lookupClass(fqn)

        /** Build a type-safe query over this classpath.
          *
          * The query is lazily evaluated on `.run` or `.stream`.
          */
        def query[A](using reads: Reads[A]): Query[A] =
            Query.make(cp, reads)
    end extension

    // ── Reads typeclass (schema-driven projection) ─────────────────────────

    trait Reads[A]:
        val symbolKinds: Set[SymbolKind]
        val needsBodies: Boolean
        val touchedFields: FieldSet
        def read(sym: Symbol)(using Frame): A < (Sync & Async & Abort[ReflectError])
    end Reads

    object Reads extends kyo.internal.reflect.reads.ReadsInstances:
        inline def derived[A]: Reads[A] = ${ kyo.internal.ReflectMacro.derivedImpl[A] }
        export kyo.internal.reflect.reads.RecordReads.recordReads

    // ── FieldSet (touched-fields hint) ─────────────────────────────────────

    final class FieldSet(val bits: Long) extends AnyVal:
        def |(other: FieldSet): FieldSet       = new FieldSet(bits | other.bits)
        def contains(other: FieldSet): Boolean = (bits & other.bits) == other.bits

    object FieldSet:
        val Empty: FieldSet        = new FieldSet(0L)
        val Name: FieldSet         = new FieldSet(1L << 0)
        val BinaryName: FieldSet   = new FieldSet(1L << 1)
        val Flags: FieldSet        = new FieldSet(1L << 2)
        val Kind: FieldSet         = new FieldSet(1L << 3)
        val Owner: FieldSet        = new FieldSet(1L << 4)
        val DeclaredType: FieldSet = new FieldSet(1L << 5)
        val Parents: FieldSet      = new FieldSet(1L << 6)
        val TypeParams: FieldSet   = new FieldSet(1L << 7)
        val Members: FieldSet      = new FieldSet(1L << 8)
        val Companion: FieldSet    = new FieldSet(1L << 9)
        val JavaSpecific: FieldSet = new FieldSet(1L << 10)
        val ParamTypes: FieldSet   = new FieldSet(1L << 11)
        val Annotations: FieldSet  = new FieldSet(1L << 12)
        val All: FieldSet          = new FieldSet((1L << 32) - 1)
    end FieldSet

    // ── FQN helper ──────────────────────────────────────────────────────────

    inline def classFqn[A](using t: Tag[A]): String = t.show

    // ── symbolToRecord (compile-time projection into kyo.Record) ───────────

    inline def symbolToRecord[F: Fields](sym: Symbol)(using Frame): Record[F] < (Sync & Async & Abort[ReflectError]) =
        ${ kyo.internal.SymbolToRecordMacro.symbolToRecordImpl[F]('sym) }

    // ── Snapshot management ─────────────────────────────────────────────────

    /** Snapshot cache management utilities. */
    object Snapshot:

        /** Delete snapshot files in `cacheDir` whose modification time is older than `maxAge` milliseconds.
          *
          * Only deletes files matching `*.krfl`. Does not recurse into subdirectories.
          *
          * @param cacheDir
          *   directory containing snapshot files
          * @param maxAgeMs
          *   maximum age in milliseconds; files older than this are deleted
          */
        def evictOlderThan(cacheDir: String, maxAgeMs: Long)(using Frame): Unit < (Sync & Abort[ReflectError]) =
            val source = PlatformFileSource.get
            source.list(cacheDir, ".krfl").flatMap: files =>
                Kyo.foreach(files): path =>
                    source.stat(path).flatMap: st =>
                        val now = java.lang.System.currentTimeMillis()
                        if now - st.mtimeMs > maxAgeMs then
                            // Try to delete; ignore errors (concurrent writers may already have replaced the file)
                            Abort.run[ReflectError](deleteFile(source, path)).andThen(Kyo.unit)
                        else
                            Kyo.unit
                        end if
                .andThen(Kyo.unit)
        end evictOlderThan

        /** Delete snapshot files in `cacheDir` whose modification time is older than `d`.
          *
          * Only deletes files matching `*.krfl`. Does not recurse into subdirectories.
          */
        @scala.annotation.targetName("evictOlderThanDuration")
        def evictOlderThan(cacheDir: String, d: Duration)(using Frame): Unit < (Sync & Abort[ReflectError]) =
            evictOlderThan(cacheDir, d.toMillis)

        /** Internal overload that accepts a custom FileSource for testing. */
        private[kyo] def evictOlderThanWithSource(
            cacheDir: String,
            maxAgeMs: Long,
            source: kyo.internal.reflect.query.FileSource
        )(using Frame): Unit < (Sync & Abort[ReflectError]) =
            source.list(cacheDir, ".krfl").flatMap: files =>
                Kyo.foreach(files): path =>
                    source.stat(path).flatMap: st =>
                        val now = java.lang.System.currentTimeMillis()
                        if now - st.mtimeMs > maxAgeMs then
                            Abort.run[ReflectError](deleteFile(source, path)).andThen(Kyo.unit)
                        else
                            Kyo.unit
                        end if
                .andThen(Kyo.unit)
        end evictOlderThanWithSource

        private def deleteFile(
            source: kyo.internal.reflect.query.FileSource,
            path: String
        )(using Frame): Unit < (Sync & Abort[ReflectError]) =
            // Rename to a tombstone then discard; if rename fails (already gone) we just continue.
            val tombstone = path + ".deleting"
            source.rename(path, tombstone).andThen:
                source.rename(tombstone, tombstone + ".gone").andThen(Kyo.unit)
        end deleteFile

    end Snapshot

    // ── Helpers ─────────────────────────────────────────────────────────────

    private def stub[A](feature: String)(using Frame): A < (Sync & Abort[ReflectError]) =
        Abort.fail(ReflectError.NotImplemented(feature))

end Reflect
