package kyo

import kyo.internal.reflect.binary.Utf8
import kyo.internal.reflect.query.ClasspathRef
import kyo.internal.reflect.symbol.Interner

/** kyo-reflect public entry object.
  *
  * Reads Scala 3 TASTy files and Java classfiles through a unified Symbol/Type API. Cross-platform JVM, JS, Native.
  *
  * This file is the Phase 0 skeleton: types compile, methods return `Abort.fail(ReflectError.NotImplemented)` until the real implementation
  * lands per the phased plan in DESIGN.md.
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
        private[kyo] val javaMetadata: Maybe[JavaMetadata] = Absent
    ):
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
        def declaredType(using Frame): Type < (Sync & Abort[ReflectError])          = stub("Symbol.declaredType")
        def parents(using Frame): Chunk[Type] < (Sync & Abort[ReflectError])        = stub("Symbol.parents")
        def typeParams(using Frame): Chunk[Symbol] < (Sync & Abort[ReflectError])   = stub("Symbol.typeParams")
        def declarations(using Frame): Chunk[Symbol] < (Sync & Abort[ReflectError]) = stub("Symbol.declarations")
        def companion(using Frame): Maybe[Symbol] < (Sync & Abort[ReflectError])    = stub("Symbol.companion")

        // Java-specific side door.
        def javaSpecific: Maybe[JavaMetadata] = javaMetadata
    end Symbol

    object Symbol:
        /** Walk the owner chain to build the fully-qualified dotted name.
          *
          * The root sentinel symbol owns itself. Package/class separators are all dots. Binary name uses '$' for nested classes and is
          * computed separately via computeBinaryName.
          */
        private[Reflect] def computeFullName(s: Symbol): Name =
            val parts = new scala.collection.mutable.ArrayBuffer[String]()
            var cur   = s
            while (cur ne null) && (cur.owner ne cur) && cur.owner != null do
                parts.prepend(Name.asString(cur.name))
                cur = cur.owner
            parts.prepend(Name.asString(cur.name))
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
                parts.prepend((Name.asString(cur.name), cur.kind))
                cur = cur.owner
            parts.prepend((Name.asString(cur.name), cur.kind))
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
            javaMetadata: Maybe[JavaMetadata] = Absent
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

    opaque type Classpath = ClasspathState

    final private class ClasspathState // placeholder until Phase 7 implements the real state machine

    object Classpath:
        def open(roots: Seq[String])(using Frame): Classpath < (Sync & Scope & Abort[ReflectError]) = stub("Classpath.open")
        def open(roots: Seq[String], strict: Boolean)(using Frame): Classpath < (Sync & Scope & Abort[ReflectError]) =
            stub("Classpath.open(strict)")
        def openCached(roots: Seq[String], cacheDir: String)(using Frame): Classpath < (Sync & Scope & Abort[ReflectError]) =
            stub("Classpath.openCached")
        def fromPickles(pickles: Seq[Pickle])(using Frame): Classpath < Sync = Sync.defer(new ClasspathState)
    end Classpath

    extension (cp: Classpath)(using Frame)
        def findClass(fqn: String): Maybe[Symbol] < (Sync & Abort[ReflectError])   = stub("Classpath.findClass")
        def findPackage(fqn: String): Maybe[Symbol] < (Sync & Abort[ReflectError]) = stub("Classpath.findPackage")
        def packages: Chunk[Symbol] < (Sync & Abort[ReflectError])                 = stub("Classpath.packages")
        def topLevelClasses: Chunk[Symbol] < (Sync & Abort[ReflectError])          = stub("Classpath.topLevelClasses")
        def errors: Chunk[ReflectError] < Sync                                     = Sync.defer(Chunk.empty)
    end extension

    // ── Reads typeclass (schema-driven projection) ─────────────────────────

    trait Reads[A]:
        val symbolKinds: Set[SymbolKind]
        val needsBodies: Boolean
        val touchedFields: FieldSet
        def read(sym: Symbol)(using Frame): A < (Sync & Abort[ReflectError])
    end Reads

    object Reads extends kyo.internal.reflect.reads.ReadsInstances:
        inline def derived[A]: Reads[A] = ${ kyo.internal.ReflectMacro.derivedImpl[A] }

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

    // Phase 6b implements the macro. In Phase 0 we expose the signature so examples compile.
    inline def symbolToRecord[F](sym: Symbol): Any < (Sync & Abort[ReflectError]) =
        scala.compiletime.error("Reflect.symbolToRecord not implemented in Phase 0; lands in Phase 6b")

    // ── Helpers ─────────────────────────────────────────────────────────────

    private def stub[A](feature: String)(using Frame): A < (Sync & Abort[ReflectError]) =
        Abort.fail(ReflectError.NotImplemented(feature))

end Reflect
