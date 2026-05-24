package kyo

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

    opaque type Name = String
    object Name:
        def apply(s: String): Name = s
        given CanEqual[Name, Name] = CanEqual.derived
        extension (n: Name)
            def asString: String = n
    end Name

    final class Flags(val bits: Long) extends AnyVal:
        def contains(flag: Flag): Boolean = (bits & flag.bit) != 0L
        def |(other: Flags): Flags        = new Flags(bits | other.bits)

    object Flags:
        val empty: Flags = new Flags(0L)

    final class Flag(val bit: Long, val name: String):
        override def toString: String = name

    object Flag:
        // ~42 flags per DESIGN.md Section 7. Phase 0 stubs a subset; full list lands with Phase 3.
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
        private[Reflect] val home: Classpath
    ):
        // Pure accessors (no effect, always present even after classpath close).
        def fullName: Name           = Symbol.computeFullName(this)
        def binaryName: String       = Symbol.computeBinaryName(this)
        def isInline: Boolean        = flags.contains(Flag.Inline)
        def isContextual: Boolean    = flags.contains(Flag.Given)
        def isOpaque: Boolean        = flags.contains(Flag.Opaque)
        def isPackageObject: Boolean = false // Phase 3 wires this from TASTy metadata
        def isModule: Boolean        = flags.contains(Flag.Module)
        def isJava: Boolean          = flags.contains(Flag.JavaDefined)

        // Resolving accessors (return ReflectError.NotImplemented in Phase 0).
        def declaredType(using Frame): Type < (Sync & Abort[ReflectError])          = stub("Symbol.declaredType")
        def parents(using Frame): Chunk[Type] < (Sync & Abort[ReflectError])        = stub("Symbol.parents")
        def typeParams(using Frame): Chunk[Symbol] < (Sync & Abort[ReflectError])   = stub("Symbol.typeParams")
        def declarations(using Frame): Chunk[Symbol] < (Sync & Abort[ReflectError]) = stub("Symbol.declarations")
        def companion(using Frame): Maybe[Symbol] < (Sync & Abort[ReflectError])    = stub("Symbol.companion")

        // Java-specific side door.
        def javaSpecific: Maybe[JavaMetadata] = Absent
    end Symbol

    object Symbol:
        // Sentinel root for ownership chains. Phase 3 replaces with a real package-symbol root.
        private[Reflect] def computeFullName(s: Symbol): Name     = s.name
        private[Reflect] def computeBinaryName(s: Symbol): String = s.name.toString
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
        def read(sym: Symbol): A < (Sync & Abort[ReflectError])
    end Reads

    object Reads:
        inline def derived[A]: Reads[A] = scala.compiletime.error("Reflect.Reads.derived not implemented in Phase 0; lands in Phase 6")

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
