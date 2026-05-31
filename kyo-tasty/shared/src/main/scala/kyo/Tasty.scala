package kyo

import kyo.internal.tasty.binary.Utf8
import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.query.ClasspathRef
import kyo.internal.tasty.query.PlatformFileSource
import kyo.internal.tasty.query.TastyStat
import kyo.internal.tasty.snapshot.DigestComputer as SnapshotDigest
import kyo.internal.tasty.snapshot.SnapshotReader
import kyo.internal.tasty.snapshot.SnapshotWriter
import kyo.internal.tasty.symbol.Interner
import kyo.internal.tasty.symbol.SymbolId
import kyo.internal.tasty.type_.TypeArena
import kyo.stats.Attributes
import scala.collection.immutable.IntMap

/** kyo-tasty public entry object.
  *
  * Reads Scala 3 TASTy files and Java classfiles through a unified Symbol/Type API. Cross-platform JVM, JS, Native.
  *
  * Note on naming: all types are nested in `object Tasty` (`Tasty.Type`, `Tasty.Symbol`, etc.) to avoid polluting `kyo.*` and to keep
  * separation from `kyo.Structure.Type` (kyo-schema's value-structure type tree). If both `Structure` and `Tasty` are imported in the same
  * file, reference the types qualified (`Structure.Type`, `Tasty.Type`).
  */
object Tasty:

    // ── Version ─────────────────────────────────────────────────────────────

    final case class Version(major: Int, minor: Int, experimental: Int):
        def show: String = s"$major.$minor.$experimental"

    // The Scala 3 TASTy version this kyo-tasty release targets. Updated per release.
    val supportedTastyVersion: Version = Version(28, 8, 0)

    // ── Names and flags ─────────────────────────────────────────────────────

    // A module-level interner used by Name.apply(String) so the public API stays unchanged.
    private val globalInterner: Interner =
        // flow-allow: §839 case 3: module-load Interner construction (single global value at class init)
        import AllowUnsafe.embrace.danger
        Interner.init(numShards = 32, initialShardCapacity = 512)
    end globalInterner

    /** An interned name backed by a byte sequence.
      *
      * The internal representation is `Interner.Entry`, which stores raw UTF-8 bytes and decodes to a `String` lazily via
      * `OnceCell[String]`. Reference equality on two `Name` values implies byte-level equality because the interner guarantees a unique
      * `Entry` per unique byte sequence. The `CanEqual` instance delegates to reference equality, which is therefore correct.
      */
    opaque type Name = Interner.Entry
    object Name:
        /** Construct a `Name` from a `String` by encoding to UTF-8 and interning the bytes.
          *
          * Example:
          * {{{
          *   val n = Tasty.Name("scala.Predef")
          *   n.asString == "scala.Predef"
          * }}}
          */
        def apply(s: String): Name =
            // flow-allow: §839 case 3 -- monotone interner; same input produces the same Name forever.
            import AllowUnsafe.embrace.danger
            val bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            globalInterner.intern(bytes, 0, bytes.length)
        end apply

        /** Wrap an already-interned `Entry` as a `Name`. For use by kyo-internal unpicklers only. */
        private[kyo] def wrap(entry: Interner.Entry): Name = entry

        /** Reference equality is correct because the interner guarantees unique Entry per unique byte sequence. */
        given CanEqual[Name, Name] = CanEqual.canEqualAny

        extension (n: Name)
            /** Decode the interned bytes to a String (lazily cached). Pure post-init: OnceCell.get() is referentially transparent. */
            def asString: String = n.string.get()
        end extension
    end Name

    final class Flags(val bits: Long) extends AnyVal:
        def contains(flag: Flag): Boolean = (bits & flag.bit) != 0L
        def |(other: Flags): Flags        = new Flags(bits | other.bits)

    object Flags:
        /** The empty flag set (no modifiers).
          *
          * Example:
          * {{{
          *   Tasty.Flags.empty.bits == 0L
          * }}}
          */
        val empty: Flags = new Flags(0L)
    end Flags

    final class Flag(val bit: Long, val name: String):
        override def toString: String = name

    object Flag:
        // Core access flags (bits 0-15)
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
        // Extended modifier flags (bits 16+)
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
        // Scala 2 origin flag (bit 44): identifies symbols decoded from Scala 2 pickles embedded in classfiles.
        val Scala2: Flag = Flag(1L << 44, "Scala2")
    end Flag

    // ── Symbol kinds ────────────────────────────────────────────────────────

    enum SymbolKind derives CanEqual:
        case Package, Class, Trait, Object, Method, Field, Val, Var,
            TypeAlias, OpaqueType, AbstractType, TypeParam, Parameter,
            Unresolved
    end SymbolKind

    // ── Subtype verdict ──────────────────────────────────────────────────────

    /** Three-way result of a subtype check.
      *
      *   - `Sub`: the subtype relation definitively holds (`t <: other`).
      *   - `NotSub`: the subtype relation definitively does not hold.
      *   - `Unknown`: the check could not reach a definitive verdict (budget exhausted, or parent chain absent from the classpath).
      */
    enum SubtypeVerdict derives CanEqual:
        case Sub, NotSub, Unknown
    end SubtypeVerdict

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

    /** Source position attached to a TASTy symbol.
      *
      * `sourceFile` is the file name from the Attributes section (if present). `line` and `column` are 1-based (line 1 = first line of the
      * file; column 1 = first character of the line). `Absent` for classfile symbols and for TASTy symbols in a file without a Positions
      * section.
      */
    final case class Position(sourceFile: Maybe[String], line: Int, column: Int)

    /** A Scala annotation as it appears on a [[Type]] (`Type.Annotated`).
      *
      * `annotationType` is the annotation class type (resolved best-effort during pass 1; may be a placeholder symbol). `argsPickle` is the
      * raw TASTy term bytes for the annotation's argument expression (e.g. `new Foo(1, "x")`). Call [[args]] to lazily decode those bytes
      * into a [[Tree]] via TreeUnpickler.
      *
      * Equality is structural over (annotationType, argsPickle); the internal decode context is intentionally excluded so two annotations
      * with identical type and pickle are equal regardless of which file they came from.
      */
    final class Annotation(
        val annotationType: Type,
        val argsPickle: Chunk[Byte],
        private[kyo] val _decodeCtx: Annotation.DecodeContext | Null
    ):
        /** Decode the annotation's argument expression into a [[Tree]].
          *
          * Returns `Tree.Unknown(-1, 0)` when the annotation has no decode context (e.g. synthetic annotations built via the public
          * factory) or when `argsPickle` is empty. The only remaining failure paths are `MalformedSection` (corrupt or truncated pickle
          * bytes) -- never `NotImplemented`.
          */
        def args(using Frame): Tree < (Sync & Abort[TastyError]) =
            _decodeCtx match
                case null =>
                    Sync.defer(Tree.Unknown(-1, 0))
                case ctx: Annotation.DecodeContext =>
                    if argsPickle.isEmpty then
                        Sync.defer(Tree.Unknown(-1, 0))
                    else
                        // flow-allow: §839 case 3; decodeAnnotationTerm is a pure-compute tree decode with no shared state.
                        Sync.Unsafe.defer:
                            val result: Tree < Abort[TastyError] =
                                try kyo.internal.tasty.reader.TreeUnpickler.decodeAnnotationTerm(argsPickle, ctx)
                                catch
                                    case ex: kyo.internal.tasty.reader.TreeUnpickler.DecodeException =>
                                        Abort.fail(TastyError.MalformedSection(
                                            "ASTs",
                                            s"annotation arg decode failed: ${ex.getMessage}",
                                            ex.byteOffset
                                        ))
                                    case ex: ArrayIndexOutOfBoundsException =>
                                        // no cursor available from AIOOBE
                                        Abort.fail(TastyError.MalformedSection("ASTs", s"annotation arg truncated: ${ex.getMessage}", 0L))
                            result

        override def equals(other: Any): Boolean = other match
            case a: Annotation =>
                // Types are interned via TypeArena, so structural equality reduces to reference equality.
                (annotationType eq a.annotationType) && argsPickle.toArray.sameElements(a.argsPickle.toArray)
            case _ => false
        override def hashCode(): Int  = annotationType.hashCode * 31 + java.util.Arrays.hashCode(argsPickle.toArray)
        override def toString: String = s"Annotation($annotationType, argsPickle=${argsPickle.length} bytes)"
    end Annotation

    object Annotation:
        /** Public factory for tests / synthetic annotations. The resulting annotation has no decode context, so [[Annotation.args]] returns
          * `Tree.Unknown(-1, 0)`.
          */
        def apply(annotationType: Type, argsPickle: Chunk[Byte]): Annotation =
            new Annotation(annotationType, argsPickle, null)

        /** Internal factory used by TypeUnpickler.ANNOTATEDtype to construct an annotation that knows how to decode itself. */
        private[kyo] def apply(annotationType: Type, argsPickle: Chunk[Byte], decodeCtx: DecodeContext): Annotation =
            new Annotation(annotationType, argsPickle, decodeCtx)

        /** Pattern-match extractor: `case Tasty.Annotation(t, p) =>`. Internal decode context is hidden. */
        def unapply(a: Annotation): Some[(Type, Chunk[Byte])] = Some((a.annotationType, a.argsPickle))

        /** File-scoped decode context. Held only by annotations constructed during real TASTy reads. The `addrMap` reference is the live
          * file-level map that gets fully populated during pass 1; reading it after `Classpath.open` returns is safe.
          */
        final private[kyo] class DecodeContext(
            val names: Array[Name],
            val addrMap: scala.collection.Map[Int, Symbol],
            val home: kyo.internal.tasty.query.ClasspathRef,
            val sectionBytes: Array[Byte],
            val sectionOffset: Int
        )
    end Annotation

    final case class JavaMetadata(
        throwsTypes: Chunk[Type],
        annotations: Chunk[JavaAnnotation],
        enclosingMethod: Maybe[(Symbol, Name)],
        accessFlags: Int,
        recordComponents: Chunk[(Name, Type)],
        bootstrapMethods: Chunk[Chunk[Int]],
        nestHost: Maybe[Symbol],
        nestMembers: Chunk[Symbol],
        paramNames: Chunk[(Name, Chunk[Name])],
        runtimeTypeAnnotations: Chunk[JavaAnnotation]
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

    // ── JPMS Module ADT ─────────────────────────────────────────────────────

    /** Describes a JPMS `requires` directive (one dependency of a module).
      *
      * @param name
      *   The required module name (e.g., "java.base").
      * @param version
      *   The required module version, if specified.
      * @param isTransitive
      *   True if the requires has `ACC_TRANSITIVE` (0x0020) set.
      * @param isStaticPhase
      *   True if the requires has `ACC_STATIC_PHASE` (0x0040) set.
      */
    final case class ModuleRequires(
        name: String,
        version: Maybe[String],
        isTransitive: Boolean,
        isStaticPhase: Boolean
    )

    /** Describes a JPMS `exports` directive (a package exported to zero or more modules).
      *
      * @param packageName
      *   The exported package name in dotted form (e.g., "java.lang").
      * @param targets
      *   The module names this package is exported to. Empty chunk means exported unconditionally (unqualified export).
      * @param flags
      *   The raw `exports_flags` value from the classfile Module attribute (JVMS §4.7.25). The `ACC_EXPORTS_SYNTHETIC` bit (0x1000)
      *   indicates the directive was generated by the compiler and not present in the source.
      */
    final case class ModuleExports(
        packageName: String,
        targets: Chunk[String],
        flags: Long
    )

    /** Describes a JPMS `opens` directive (a package opened for deep reflection).
      *
      * @param packageName
      *   The opened package name in dotted form.
      * @param targets
      *   The module names this package is opened to. Empty chunk means opened unconditionally.
      * @param flags
      *   The raw `opens_flags` value from the classfile Module attribute (JVMS §4.7.25). The `ACC_OPENS_SYNTHETIC` bit (0x1000) indicates
      *   the directive was generated by the compiler and not present in the source.
      */
    final case class ModuleOpens(
        packageName: String,
        targets: Chunk[String],
        flags: Long
    )

    /** Describes a JPMS `provides` directive (a service implementation).
      *
      * @param serviceName
      *   The service interface class name in dotted form.
      * @param implementations
      *   The implementation class names in dotted form.
      */
    final case class ModuleProvides(
        serviceName: String,
        implementations: Chunk[String]
    )

    /** Parsed content of a module-info.class file.
      *
      * Produced by `ModuleInfoReader.read` and stored in the classpath `moduleIndex` after a successful parse.
      *
      * @param name
      *   The module name (e.g., "java.base").
      * @param version
      *   The module version string, if present in the `module-info.class`.
      * @param requires
      *   All requires directives.
      * @param exports
      *   All exports directives.
      * @param opens
      *   All opens directives.
      * @param uses
      *   Service interfaces used by this module (dotted class names).
      * @param provides
      *   Service implementations provided by this module.
      */
    final case class ModuleDescriptor(
        name: String,
        version: Maybe[String],
        requires: Chunk[ModuleRequires],
        exports: Chunk[ModuleExports],
        opens: Chunk[ModuleOpens],
        uses: Chunk[String],
        provides: Chunk[ModuleProvides]
    )

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

        def show: String =
            // flow-allow: §839 case 3; diagnostic display boundary; reads immutable interned Name strings.
            import AllowUnsafe.embrace.danger
            import Name.asString
            this match
                // plan: phase-02 inline; lifts to sym.fullName.asString in phase 09 once resolution methods land.
                case Named(sym)             => sym.name.asString
                case Applied(base, args)    => s"${base.show}[${args.map(_.show).mkString(", ")}]"
                case Array(elem)            => s"${elem.show}[]"
                case Function(ps, r, isCtx) => s"(${ps.map(_.show).mkString(", ")}) ${if isCtx then "?=>" else "=>"} ${r.show}"
                case Tuple(es)              => s"(${es.map(_.show).mkString(", ")})"
                case other                  => other.toString
            end match
        end show
    end Type

    // ── Tree ADT ────────────────────────────────────────────────────────────

    /** Structural representation of a TASTy expression or definition body.
      *
      * Produced by `Symbol.body` (lazy, memoized). Each case mirrors a TASTy AST tag. Trees may reference `Tasty.Type` and `Tasty.Symbol`
      * values. All sub-trees are strict (no lazy slots); memoization is handled at the `body` accessor level.
      *
      * Reference: dotty TastyFormat.scala AST tag layout.
      */
    sealed trait Tree

    object Tree:
        /** Term reference by name (IDENT tag). */
        final case class Ident(name: Name, tpe: Type) extends Tree

        /** Member selection (SELECT tag). */
        final case class Select(qualifier: Tree, name: Name, tpe: Type) extends Tree

        /** Function application (APPLY tag). */
        final case class Apply(fun: Tree, args: Chunk[Tree]) extends Tree

        /** Type application (TYPEAPPLY tag). */
        final case class TypeApply(fun: Tree, args: Chunk[Type]) extends Tree

        /** Block of statements followed by an expression (BLOCK tag). */
        final case class Block(stats: Chunk[Tree], expr: Tree) extends Tree

        /** Conditional expression (IF tag). */
        final case class If(cond: Tree, thenp: Tree, elsep: Tree) extends Tree

        /** Pattern match (MATCH tag). */
        final case class Match(selector: Tree, cases: Chunk[CaseDef]) extends Tree

        /** Single case in a match (CASEDEF tag). */
        final case class CaseDef(pattern: Tree, guard: Maybe[Tree], body: Tree) extends Tree

        /** Literal constant (various const tags). */
        final case class Literal(constant: Constant) extends Tree

        /** Object allocation (NEW tag). */
        final case class New(tpe: Type) extends Tree

        /** Assignment (ASSIGN tag). */
        final case class Assign(lhs: Tree, rhs: Tree) extends Tree

        /** Return statement (RETURN tag). */
        final case class Return(expr: Maybe[Tree], from: Symbol) extends Tree

        /** Throw expression (THROW tag). */
        final case class Throw(expr: Tree) extends Tree

        /** Lambda / anonymous function (LAMBDA tag). */
        final case class Lambda(method: Tree, tpe: Maybe[Type]) extends Tree

        /** Type ascription (TYPED tag). */
        final case class Typed(expr: Tree, tpe: Type) extends Tree

        /** Inlined call expansion (INLINED tag). */
        final case class Inlined(call: Maybe[Tree], bindings: Chunk[Tree], body: Tree) extends Tree

        /** Try/catch/finally (TRY tag). */
        final case class Try(expr: Tree, cases: Chunk[CaseDef], finalizer: Maybe[Tree]) extends Tree

        /** While loop (WHILE tag). */
        final case class While(cond: Tree, body: Tree) extends Tree

        /** Pattern binding (BIND tag). */
        final case class Bind(name: Name, pattern: Tree) extends Tree

        /** Alternative patterns in a case (ALTERNATIVE tag). */
        final case class Alternative(patterns: Chunk[Tree]) extends Tree

        /** Unapply extractor call (UNAPPLY tag). */
        final case class Unapply(fun: Tree, implicits: Chunk[Tree], patterns: Chunk[Tree]) extends Tree

        /** Val or var definition (VALDEF tag). */
        final case class ValDef(sym: Symbol, tpt: Type, rhs: Maybe[Tree]) extends Tree

        /** Method definition (DEFDEF tag). */
        final case class DefDef(sym: Symbol, paramss: Chunk[Chunk[Tree]], tpt: Type, rhs: Maybe[Tree]) extends Tree

        /** Type alias or abstract type definition (TYPEDEF tag). */
        final case class TypeDef(sym: Symbol, rhs: Type) extends Tree

        /** Package definition (PACKAGE tag). */
        final case class PackageDef(sym: Symbol, stats: Chunk[Tree]) extends Tree

        /** Class definition (TYPEDEF with TEMPLATE). */
        final case class ClassDef(sym: Symbol, template: Template) extends Tree

        /** Class template body (TEMPLATE tag). */
        final case class Template(parents: Chunk[Tree], self: Maybe[Symbol], body: Chunk[Tree]) extends Tree

        /** Super reference (SUPER tag). */
        final case class Super(qual: Tree, mix: Maybe[Name]) extends Tree

        /** This reference (THIS tag). */
        final case class This(cls: Symbol) extends Tree

        /** Named argument in an application (NAMEDARG tag). */
        final case class NamedArg(name: Name, value: Tree) extends Tree

        /** Annotated tree (ANNOTATEDtpt/ANNOTATEDtype). */
        final case class Annotated(expr: Tree, annotation: Tree) extends Tree

        /** Shared sub-tree back-reference (SHAREDtype or SHAREDterm tag). `addr` is the byte address of the original node. */
        final case class Shared(addr: Int) extends Tree

        /** TASTy category-1 modifier tag (single-byte, no payload; tag in range [1, 59]). */
        final case class Modifier(flag: Flag) extends Tree

        /** Recursive type wrapper (RECtype tag). */
        final case class RecType(parent: Tree) extends Tree

        /** Super type pair (SUPERtype tag). */
        final case class SuperType(thistpe: Tree, supertpe: Tree) extends Tree

        /** Structural refinement type (REFINEDtype tag). */
        final case class RefinedType(parent: Tree, name: Name, info: Tree) extends Tree

        /** Type constructor applied to arguments (APPLIEDtype tag). */
        final case class AppliedType(tycon: Tree, args: Chunk[Tree]) extends Tree

        /** Type bounds (TYPEBOUNDS tag). */
        final case class TypeBounds(lo: Tree, hi: Tree) extends Tree

        /** Annotated type (ANNOTATEDtype tag). */
        final case class AnnotatedType(parent: Tree, annot: Tree) extends Tree

        /** Intersection type (ANDtype tag). */
        final case class AndType(left: Tree, right: Tree) extends Tree

        /** Union type (ORtype tag). */
        final case class OrType(left: Tree, right: Tree) extends Tree

        /** By-name type (BYNAMEtype tag). */
        final case class ByNameType(arg: Tree) extends Tree

        /** Match type with scrutinee and cases (MATCHtype tag). */
        final case class MatchType(bound: Tree, scrutinee: Tree, cases: Chunk[Tree]) extends Tree

        /** Flexible (Java-nullable) type (FLEXIBLEtype tag). */
        final case class FlexibleType(arg: Tree) extends Tree

        /** Type-position identifier (IDENTtpt tag): nameRef + type. */
        final case class IdentTpt(name: Name, tpe: Type) extends Tree

        /** Type-position selection (SELECTtpt tag): qualifier + name. */
        final case class SelectTpt(qual: Tree, name: Name) extends Tree

        /** Singleton type (SINGLETONtpt tag): ref tree. */
        final case class SingletonTpt(tpe: Tree) extends Tree

        /** Package-level term reference (TERMREFpkg tag): package name only. */
        final case class TermRefPkg(name: Name) extends Tree

        /** Package-level type reference (TYPEREFpkg tag): package name only. */
        final case class TypeRefPkg(name: Name) extends Tree

        /** Symbol-addressed term reference (TERMREFsymbol tag): addr + qualifier. */
        final case class TermRefSymbol(addr: Int, qual: Tree) extends Tree

        /** Symbol-addressed type reference (TYPEREFsymbol tag): addr + qualifier. */
        final case class TypeRefSymbol(addr: Int, qual: Tree) extends Tree

        /** Direct-address term reference (TERMREFdirect tag): symbol address. */
        final case class TermRefDirect(addr: Int) extends Tree

        /** Direct-address type reference (TYPEREFdirect tag): symbol address. */
        final case class TypeRefDirect(addr: Int) extends Tree

        /** Owner-qualified selection (SELECTin tag): qualifier + name + owner. */
        final case class SelectIn(qual: Tree, name: Name, owner: Tree) extends Tree

        /** Import statement (IMPORT tag): qualifier expression and selector trees. */
        final case class Import(qual: Tree, selectors: Chunk[Tree]) extends Tree

        /** Export clause (EXPORT tag): qualifier expression and selector trees. */
        final case class Export(qual: Tree, selectors: Chunk[Tree]) extends Tree

        /** In-tree annotation node (ANNOTATION tag): annotation class type tree and annotation argument tree. */
        final case class AnnotationNode(annotType: Tree, arg: Tree) extends Tree

        /** Unknown tag -- encountered a tag not covered by this ADT version. */
        final case class Unknown(tag: Int, length: Int) extends Tree
    end Tree

    // ── SymbolBody ──────────────────────────────────────────────────────────

    /** Byte slice and decode context for a TASTy symbol body. Carried by `Symbol.body: Maybe[SymbolBody]`.
      *
      * All symbols with a TASTy body (DEFDEF, VALDEF, class TYPEDEF with a non-trivial template) carry a `Present(SymbolBody)`. Java
      * symbols, Package symbols, and abstract type stubs carry `Absent`.
      *
      * @param bodyStart
      *   Absolute byte offset into `sectionBytes` where this symbol's body payload begins.
      * @param bodyEnd
      *   Absolute byte offset into `sectionBytes` where this symbol's body payload ends.
      * @param sectionBytes
      *   The raw AST section bytes for this file. Shared (not copied) across all symbols from the same file.
      * @param names
      *   The name table for this file, as decoded by NameUnpickler. Shared across all symbols from the same file.
      * @param sectionOffset
      *   Absolute byte offset where the AST section starts in the original TASTy file. Used to convert section-relative addrs to absolute.
      * @param addrMap
      *   Maps TASTy byte address to SymbolId for IDENT/SELECT tree references during lazy body decode.
      */
    final case class SymbolBody(
        bodyStart: Int,
        bodyEnd: Int,
        sectionBytes: Array[Byte],
        names: Array[Name],
        sectionOffset: Int,
        addrMap: IntMap[SymbolId]
    )

    // ── Symbol ──────────────────────────────────────────────────────────────

    /** Pure-data representation of a TASTy or Java classfile symbol.
      *
      * Constructed exactly once per symbol by `ClasspathOrchestrator.materializeSymbols` during Pass C of `Classpath.open`. After
      * construction, every field is immutable. Cross-symbol references use `SymbolId` (an opaque Int) rather than direct `Symbol` pointers
      * to avoid case-class cycles.
      *
      * Resolution methods (`owner`, `parents`, `declarations`, `fullName`, etc.) are added in Phase 09 after `Classpath` becomes a pure
      * case class. In this phase (Phase 02) only the 14 constructor parameters and auto-generated case-class members are available.
      */
    final case class Symbol private[Tasty] (
        id: SymbolId,
        kind: SymbolKind,
        flags: Flags,
        name: Name,
        ownerId: SymbolId,
        declaredType: Maybe[Type],
        scaladoc: Maybe[String],
        sourcePosition: Maybe[Position],
        javaMetadata: Maybe[JavaMetadata],
        parentTypes: Chunk[Type],
        typeParamIds: Chunk[SymbolId],
        declarationIds: Chunk[SymbolId],
        permittedSubclassIds: Maybe[Chunk[SymbolId]],
        bodyRecord: Maybe[SymbolBody]
    ) derives CanEqual:
        // plan: phase-02; partial symbols (id == SymbolId(-1)) produced during Pass 1 need reference
        // identity so that HashMap[Symbol, V] keys are distinct even when two symbols share the same
        // kind/flags/name. Finalized symbols (id >= 0) use id-based equality: two symbols with the same
        // id are the same symbol (matches case-class semantics for test fixtures with explicit ids).
        override def equals(other: Any): Boolean =
            other.isInstanceOf[Symbol] && {
                val that = other.asInstanceOf[Symbol]
                import kyo.internal.tasty.symbol.SymbolId.value
                if id.value == -1 || that.id.value == -1 then this eq that
                else id == that.id
            }
        override def hashCode(): Int =
            import kyo.internal.tasty.symbol.SymbolId.value
            if id.value == -1 then java.lang.System.identityHashCode(this)
            else id.value
        end hashCode

        // plan: phase-04; 41 flag predicates -- pure Boolean reads on the Flags long bitmask.
        // No effect row, no Classpath, no AllowUnsafe.
        def isFinal: Boolean         = flags.contains(Flag.Final)
        def isAbstract: Boolean      = flags.contains(Flag.Abstract)
        def isSealed: Boolean        = flags.contains(Flag.Sealed)
        def isCase: Boolean          = flags.contains(Flag.Case)
        def isLazy: Boolean          = flags.contains(Flag.Lazy)
        def isOverride: Boolean      = flags.contains(Flag.Override)
        def isPrivate: Boolean       = flags.contains(Flag.Private)
        def isProtected: Boolean     = flags.contains(Flag.Protected)
        def isPublic: Boolean        = flags.contains(Flag.Public)
        def isStatic: Boolean        = flags.contains(Flag.Static)
        def isMutable: Boolean       = flags.contains(Flag.Mutable)
        def isErased: Boolean        = flags.contains(Flag.Erased)
        def isInfix: Boolean         = flags.contains(Flag.Infix)
        def isOpen: Boolean          = flags.contains(Flag.Open)
        def isTransparent: Boolean   = flags.contains(Flag.Transparent)
        def isMacro: Boolean         = flags.contains(Flag.Macro)
        def isSynthetic: Boolean     = flags.contains(Flag.Synthetic)
        def isArtifact: Boolean      = flags.contains(Flag.Artifact)
        def isCovariant: Boolean     = flags.contains(Flag.CoVariant)
        def isContravariant: Boolean = flags.contains(Flag.ContraVariant)
        def isExtension: Boolean     = flags.contains(Flag.Extension)
        def isTracked: Boolean       = flags.contains(Flag.Tracked)
        def isStable: Boolean        = flags.contains(Flag.Stable)
        def isParamAccessor: Boolean = flags.contains(Flag.ParamAccessor)
        def isCaseAccessor: Boolean  = flags.contains(Flag.CaseAccessor)
        def isFieldAccessor: Boolean = flags.contains(Flag.FieldAccessor)
        def isExported: Boolean      = flags.contains(Flag.Exported)
        def isLocal: Boolean         = flags.contains(Flag.Local)
        def isHasDefault: Boolean    = flags.contains(Flag.HasDefault)
        def isInvisible: Boolean     = flags.contains(Flag.Invisible)
        def isInto: Boolean          = flags.contains(Flag.Into)
        def isInlineProxy: Boolean   = flags.contains(Flag.InlineProxy)
        def isTailrec: Boolean       = flags.contains(Flag.Tailrec)
        def isScala2: Boolean        = flags.contains(Flag.Scala2)
        def isJavaRecord: Boolean    = flags.contains(Flag.JavaRecord)
        def isEnum: Boolean          = flags.contains(Flag.Enum)
        def isModule: Boolean        = flags.contains(Flag.Module)
        def isJava: Boolean          = flags.contains(Flag.JavaDefined)
        def isInline: Boolean        = flags.contains(Flag.Inline)
        def isContextual: Boolean    = flags.contains(Flag.Given)
        def isOpaque: Boolean        = flags.contains(Flag.Opaque)

        // plan: phase-04; 14 single-kind discriminators -- pure Boolean equality on the SymbolKind enum.
        def isPackage: Boolean        = kind == SymbolKind.Package
        def isClass: Boolean          = kind == SymbolKind.Class
        def isTrait: Boolean          = kind == SymbolKind.Trait
        def isObject: Boolean         = kind == SymbolKind.Object
        def isMethod: Boolean         = kind == SymbolKind.Method
        def isField: Boolean          = kind == SymbolKind.Field
        def isVal: Boolean            = kind == SymbolKind.Val
        def isVar: Boolean            = kind == SymbolKind.Var
        def isTypeAlias: Boolean      = kind == SymbolKind.TypeAlias
        def isOpaqueTypeKind: Boolean = kind == SymbolKind.OpaqueType
        def isAbstractType: Boolean   = kind == SymbolKind.AbstractType
        def isTypeParam: Boolean      = kind == SymbolKind.TypeParam
        def isParameter: Boolean      = kind == SymbolKind.Parameter
        def isUnresolved: Boolean     = kind == SymbolKind.Unresolved

        // plan: phase-04; 5 composite kind predicates -- pure Boolean combinations.
        def isCaseClass: Boolean  = isClass && isCase
        def isCaseObject: Boolean = isObject && isCase
        def isClassLike: Boolean  = isClass || isTrait || isObject
        def isTypeLike: Boolean   = isTypeAlias || isOpaqueTypeKind || isAbstractType || isTypeParam
        def isTerm: Boolean       = isMethod || isVal || isVar || isField || isParameter

        /** Decode the body of this symbol into a `Tree`.
          *
          * Returns `Absent` for Package symbols, Java symbols, and any symbol whose body record is empty. Fails with
          * `TastyError.MalformedSection` on corrupt body bytes.
          *
          * This is the ONE Symbol method that carries a kyo effect row. All other Symbol members return plain values. The `body`
          * constructor parameter (a raw `Maybe[SymbolBody]` byte record) and this method coexist because they have different signatures:
          * the field takes no arguments while this method requires `using` clauses.
          *
          * plan: phase-04; decoding is NOT memoized in this phase. Memoization via ConcurrentHashMap arrives in Phase 06 when Classpath
          * becomes a pure case class with a private lazy val bodyMemo.
          */
        def body(using cp: Classpath, frame: Frame): Maybe[Tree] < (Sync & Abort[TastyError]) =
            cp.decodeBody(this)
    end Symbol

    object Symbol:
        // The Origin sealed trait and TastyOrigin/JavaOrigin cases are removed; the body byte slice
        // is carried inside `body: Maybe[SymbolBody]`. ClasspathOrchestrator Pass C invokes
        // apply via the full factory below.

        /** Full factory for ClasspathOrchestrator Pass C (materializeSymbols).
          *
          * Called by ClasspathOrchestrator.materializeSymbols to construct final immutable Symbols from SymbolDescriptors. Returns a Symbol
          * with all 14 fields populated.
          *
          * plan: phase-02 factory; stays in Phase 07 when Pass C is fully rewritten with SymbolDescriptor pipeline. After Phase 07 the
          * `private[Tasty]` apply is called directly from inside ClasspathOrchestrator (which will be inside object Tasty by then).
          */
        private[kyo] def fromDescriptor(
            id: SymbolId,
            kind: SymbolKind,
            flags: Flags,
            name: Name,
            ownerId: SymbolId,
            declaredType: Maybe[Type],
            scaladoc: Maybe[String],
            sourcePosition: Maybe[Position],
            javaMetadata: Maybe[JavaMetadata],
            parentTypes: Chunk[Type],
            typeParamIds: Chunk[SymbolId],
            declarationIds: Chunk[SymbolId],
            permittedSubclassIds: Maybe[Chunk[SymbolId]],
            bodyRecord: Maybe[SymbolBody]
        ): Symbol =
            Symbol(
                id = id,
                kind = kind,
                flags = flags,
                name = name,
                ownerId = ownerId,
                declaredType = declaredType,
                scaladoc = scaladoc,
                sourcePosition = sourcePosition,
                javaMetadata = javaMetadata,
                parentTypes = parentTypes,
                typeParamIds = typeParamIds,
                declarationIds = declarationIds,
                permittedSubclassIds = permittedSubclassIds,
                bodyRecord = bodyRecord
            )
        end fromDescriptor

        /** Construct a synthetic placeholder Symbol for internal tree-decode use only.
          *
          * Used by TreeUnpickler when an address lookup fails (error recovery) or when building synthetic tree nodes that do not correspond
          * to real classpath symbols. The returned Symbol has id = SymbolId(-1) and empty relational fields.
          *
          * plan: phase-02 bridge factory; migrates to a phase-09 resolution approach once Classpath becomes a pure case class and
          * TreeUnpickler can look up SymbolId->Symbol.
          */
        private[kyo] def make(
            kind: SymbolKind,
            flags: Flags,
            name: Name
        ): Symbol =
            Symbol(
                id = SymbolId(-1),
                kind = kind,
                flags = flags,
                name = name,
                ownerId = SymbolId(-1),
                declaredType = Maybe.Absent,
                scaladoc = Maybe.Absent,
                sourcePosition = Maybe.Absent,
                javaMetadata = Maybe.Absent,
                parentTypes = Chunk.empty,
                typeParamIds = Chunk.empty,
                declarationIds = Chunk.empty,
                permittedSubclassIds = Maybe.Absent,
                bodyRecord = Maybe.Absent
            )
        end make
    end Symbol

    // ── Pickle (in-memory TASTy + classfile bytes) ──────────────────────────

    final case class Pickle(uuid: String, version: Version, bytes: Chunk[Byte])

    // ── Classpath ───────────────────────────────────────────────────────────

    opaque type Classpath = kyo.internal.tasty.query.Classpath

    object Classpath:

        /** Open a classpath from directory/file roots. Soft-fail mode (errors accumulate in `cp.errors`).
          *
          * Effect row rationale:
          *   - `Sync`: file I/O for JAR, classfile, and TASTy reads.
          *   - `Async`: parallel per-file decode across the workgroup.
          *   - `Scope`: registers a finalizer that closes JAR pools and mmap arenas on scope exit.
          *   - `Abort[TastyError]`: fatal errors (classpath build state, snapshot mismatch).
          *
          * One-arg variant: delegates to the canonical two-arg form with `strict = false`.
          */
        def open(roots: Seq[String])(using Frame): Classpath < (Sync & Async & Scope & Abort[TastyError]) =
            open(roots, strict = false)

        /** Open a classpath from directory/file roots.
          *
          * Effect row rationale:
          *   - `Sync`: file I/O for JAR, classfile, and TASTy reads.
          *   - `Async`: parallel per-file decode across the workgroup.
          *   - `Scope`: registers a finalizer that closes JAR pools and mmap arenas on scope exit.
          *   - `Abort[TastyError]`: fatal errors (classpath build state, snapshot mismatch).
          *
          * Canonical two-arg form. Soft-fail when `strict = false`; fail-fast when `strict = true`.
          */
        def open(roots: Seq[String], strict: Boolean)(using Frame): Classpath < (Sync & Async & Scope & Abort[TastyError]) =
            openImpl(roots, strict)

        /** Open a classpath from directory/file roots, using a snapshot cache in `cacheDir`.
          *
          * On a cache hit (digest match), deserializes the snapshot directly. On a miss, opens normally then writes a new snapshot.
          *
          * Effect row rationale:
          *   - `Sync`: file I/O for snapshot read/write plus JAR, classfile, and TASTy reads on a cache miss.
          *   - `Async`: parallel per-file decode on a cache miss.
          *   - `Scope`: registers a finalizer that closes JAR pools and mmap arenas on scope exit.
          *   - `Abort[TastyError]`: fatal errors (snapshot mismatch, classpath build failures).
          */
        def openCached(roots: Seq[String], cacheDir: String)(using Frame): Classpath < (Sync & Async & Scope & Abort[TastyError]) =
            openCachedImpl(roots, cacheDir)

        /** Wrap a raw InternalClasspath as the public Classpath opaque type. For use by internal test helpers only. */
        private[kyo] def wrap(cp: kyo.internal.tasty.query.Classpath): Classpath = cp

        /** Unwrap the public Classpath opaque type to the internal representation. For use by internal test helpers only. */
        private[kyo] def unwrap(cp: Classpath): kyo.internal.tasty.query.Classpath = cp

        /** Create a classpath from pre-parsed in-memory pickles. */
        def fromPickles(pickles: Seq[Pickle])(using Frame): Classpath < Sync =
            kyo.internal.tasty.query.Classpath.allocate.map: cp =>
                // flow-allow: §839 case 3; single-threaded Sync.map lambda; atomic state write to fresh Classpath.
                import AllowUnsafe.embrace.danger
                kyo.internal.tasty.query.Classpath.transitionToReady(
                    cp,
                    allSymbols = Chunk.empty,
                    topLevelClasses = Chunk.empty,
                    packages = Chunk.empty,
                    fqnIndex = Map.empty,
                    packageIndex = Map.empty,
                    canonical = TypeArena.canonical(),
                    errors = Chunk.empty,
                    moduleIndex = Map.empty
                )
                cp

        /** Internal: open implementation, delegates to ClasspathOrchestrator. */
        private def openImpl(
            roots: Seq[String],
            strict: Boolean
        )(using Frame): Classpath < (Sync & Async & Scope & Abort[TastyError]) =
            val source      = PlatformFileSource.get
            val concurrency = Runtime.getRuntime.availableProcessors().max(1)
            TastyStat.scope.traceSpan(
                "coldLoad",
                Attributes.empty.add("roots", roots.size.toString)
            ) {
                ClasspathOrchestrator.open(roots, strict, source, concurrency)
            }
        end openImpl

        private def openCachedImpl(
            roots: Seq[String],
            cacheDir: String
        )(using Frame): Classpath < (Sync & Async & Scope & Abort[TastyError]) =
            val source      = PlatformFileSource.get
            val concurrency = Runtime.getRuntime.availableProcessors().max(1)
            // Compute digest of root metadata
            Abort.run[TastyError](SnapshotDigest.compute(roots, source)).flatMap:
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
                            // Try to load from snapshot using mmap on JVM/Native, heap on JS.
                            kyo.internal.tasty.query.Classpath.allocate.flatMap: cp =>
                                Scope.ensure(Sync.Unsafe.defer {
                                    // flow-allow: §839 case 3; Scope finalizer; atomic state write to close Classpath.
                                    kyo.internal.tasty.query.Classpath.close(cp)
                                }).andThen:
                                    Abort.run[TastyError](SnapshotReader.readMapped(snapshotPath, source, cp)).flatMap:
                                        case Result.Success(_) =>
                                            cp
                                        case Result.Failure(_) | Result.Panic(_) =>
                                            // Snapshot unreadable; fall through to normal open
                                            openImpl(roots, strict = false)
                        else
                            // No snapshot; open normally then write snapshot
                            openImpl(roots, strict = false).flatMap: cp =>
                                Abort.run[TastyError](SnapshotWriter.write(cp, cacheDir, digest, source)).andThen(cp)
        end openCachedImpl

    end Classpath

    extension (cp: Classpath)
        /** Look up a class symbol by fully-qualified dotted name.
          *
          * Pure accessor: reads from the immutable fqnIndex HashMap in Ready state. Valid after `open` returns. After close, returns
          * whatever heap state is there (closed-state enforcement is Symbol.body only).
          *
          * Example:
          * {{{
          *   val sym = cp.findClass("scala.Predef")
          *   sym.isPresent == true
          * }}}
          */
        def findClass(fqn: String): Maybe[Symbol] =
            // flow-allow: §839 case 3 -- post-open immutable fqnIndex lookup; populated during open, before any user access.
            given AllowUnsafe = AllowUnsafe.embrace.danger
            cp.pureClass(fqn)
        end findClass

        /** Look up a package symbol by fully-qualified dotted name.
          *
          * Pure accessor: reads from the immutable packageIndex HashMap in Ready state. Valid after `open` returns.
          */
        def findPackage(fqn: String): Maybe[Symbol] =
            // flow-allow: §839 case 3 -- post-open immutable packageIndex lookup; populated during open, before any user access.
            given AllowUnsafe = AllowUnsafe.embrace.danger
            cp.purePackage(fqn)
        end findPackage

        /** All package symbols in this classpath.
          *
          * Pure accessor: reads from the immutable packages Chunk in Ready state. Valid after `open` returns.
          *
          * Example:
          * {{{
          *   val pkgs = cp.packages
          *   pkgs.nonEmpty == true
          * }}}
          */
        def packages: Chunk[Symbol] =
            // flow-allow: §839 case 3 -- post-open immutable packages Chunk lookup; populated during open, before any user access.
            given AllowUnsafe = AllowUnsafe.embrace.danger
            cp.purePackages
        end packages

        /** All top-level class symbols (not packages) in this classpath.
          *
          * Pure accessor: reads from the immutable topLevelClasses Chunk in Ready state. Valid after `open` returns.
          *
          * Example:
          * {{{
          *   val classes = cp.topLevelClasses
          *   classes.nonEmpty == true
          * }}}
          */
        def topLevelClasses: Chunk[Symbol] =
            // flow-allow: §839 case 3 -- post-open immutable topLevelClasses Chunk lookup; populated during open, before any user access.
            given AllowUnsafe = AllowUnsafe.embrace.danger
            cp.pureTopLevelClasses
        end topLevelClasses

        /** Errors accumulated during loading (soft-fail mode).
          *
          * Pure accessor: reads from immutable error state populated during classpath open. Empty for clean classpaths.
          */
        def errors: Chunk[TastyError] =
            // flow-allow: §839 case 3 -- post-open accumulated errors; monotone append-only during open, immutable after.
            given AllowUnsafe = AllowUnsafe.embrace.danger
            cp.accumulatedErrors
        end errors

        /** Look up a JPMS module descriptor by module name (e.g., "java.base").
          *
          * Returns `Present(descriptor)` if a `module-info.class` with the given module name was found in the classpath roots. Returns
          * `Absent` if no matching module was found.
          *
          * Pure accessor: reads from the immutable moduleIndex HashMap in Ready state. Valid after `open` returns.
          */
        def findModule(name: String): Maybe[ModuleDescriptor] =
            // flow-allow: §839 case 3 -- post-open immutable moduleIndex lookup; populated during open, before any user access.
            given AllowUnsafe = AllowUnsafe.embrace.danger
            cp.pureModule(name)
        end findModule

        /** Find a class symbol by JVM binary name (e.g., "com/example/Foo$Inner").
          *
          * Converts the binary name to a dotted FQN and delegates to `findClass`.
          *
          * Pure accessor: reads from the immutable fqnIndex HashMap in Ready state. Valid after `open` returns.
          */
        def findClassByBinary(binaryName: String): Maybe[Symbol] =
            // flow-allow: §839 case 3 -- post-open immutable fqnIndex lookup via binary name conversion; populated during open.
            given AllowUnsafe = AllowUnsafe.embrace.danger
            val fqn           = binaryName.replace('/', '.').replace('$', '.')
            cp.pureClass(fqn)
        end findClassByBinary

        /** Decode the body bytes of `sym` into a `Tree`.
          *
          * Returns `Absent` for symbols whose `body` constructor parameter is `Absent` (Package, Java, and symbols without an AST body
          * slice). Fails with `TastyError.MalformedSection` on corrupt body bytes.
          *
          * Called by `Symbol.body(using cp, frame)`. Phase 06 replaces this non-memoizing bridge with a memoizing implementation backed by
          * a `private lazy val bodyMemo: ConcurrentHashMap` on the Classpath case class introduced in that phase.
          *
          * plan: phase-04 bridge; no memoization. AllowUnsafe is acquired internally to read the Ready state and to invoke
          * TreeUnpickler.decodeSync; it does NOT appear on the public signature (INV-010).
          */
        def decodeBody(sym: Symbol)(using Frame): Maybe[Tree] < (Sync & Abort[TastyError]) =
            sym.bodyRecord match
                case Maybe.Absent => Sync.defer(Maybe.Absent)
                case Maybe.Present(blob) =>
                    Sync.Unsafe.defer:
                        // flow-allow: §839 case 3; post-open AllowUnsafe read to obtain allSymbols for
                        // the symbol-lookup lambda passed to TreeUnpickler.decodeSync.
                        given AllowUnsafe             = AllowUnsafe.embrace.danger
                        val syms: Chunk[Tasty.Symbol] = cp.allSymbols
                        try
                            val tree = kyo.internal.tasty.reader.TreeUnpickler.decodeSync(
                                blob,
                                sym,
                                idx => if idx >= 0 && idx < syms.length then syms(idx) else sym
                            )
                            Maybe(tree)
                        catch
                            case ex: kyo.internal.tasty.reader.TreeUnpickler.DecodeException =>
                                Abort.fail(TastyError.MalformedSection("ASTs", ex.getMessage, ex.byteOffset))
                            case _: ArrayIndexOutOfBoundsException =>
                                Abort.fail(TastyError.MalformedSection("ASTs", "truncated body", 0L))
                        end try
        end decodeBody

    end extension

    // ── Type subtyping extension ─────────────────────────────────────────────

    /** Extension method for subtype checking on `Tasty.Type` values.
      *
      * Checks whether `t` is a subtype of `other` using the structural covariant rules implemented in `kyo.internal.tasty.type_.Subtyping`.
      * Parent-chain lookups use the provided `cp` classpath (explicit, per `feedback_no_implicit_handlers`).
      *
      * ==Rec depth budget==
      *
      * A `Rec` type contains a recursive back-reference (`RecThis`). To avoid infinite recursion, each `Rec` unfolding decrements an
      * internal budget counter that starts at 64. If the budget is exhausted before a definitive subtype verdict is reached, the method
      * returns `SubtypeVerdict.Unknown`. Normal type hierarchies are nowhere near 64 levels deep; the budget is a safety net for
      * adversarial or machine-generated type structures.
      *
      * @param other
      *   the candidate supertype
      * @param cp
      *   the classpath used for transitive parent-chain resolution
      */
    extension (t: Type)
        /** Check whether `t` is a subtype of `other` using the structural covariant rules in `kyo.internal.tasty.type_.Subtyping`.
          *
          * Returns `SubtypeVerdict.Sub` when the relation definitively holds, `SubtypeVerdict.NotSub` when it definitively does not hold,
          * and `SubtypeVerdict.Unknown` when the budget was exhausted or the parent chain was not fully available on the classpath.
          *
          * Pure accessor: parent-chain lookups use the pre-populated `_parents` SingleAssign slots in each Symbol, which are set during
          * classpath open and are immutable thereafter. No classpath I/O is performed.
          *
          * @param other
          *   the candidate supertype
          * @param cp
          *   the classpath used for transitive parent-chain resolution (reads immutable post-open parent-chain slots)
          */
        def isSubtypeOf(other: Type)(using cp: Classpath): SubtypeVerdict =
            kyo.internal.tasty.type_.Subtyping.isSubtype(t, other, cp, budget = 64)
    end extension

    // ── FQN helper ──────────────────────────────────────────────────────────

    inline def classFqn[A](using t: Tag[A]): String = t.show

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
        def evictOlderThan(cacheDir: String, maxAgeMs: Long)(using Frame): Unit < (Sync & Abort[TastyError]) =
            val source = PlatformFileSource.get
            source.list(cacheDir, ".krfl").flatMap: files =>
                Kyo.foreach(files): path =>
                    source.stat(path).flatMap: st =>
                        val now = java.lang.System.currentTimeMillis()
                        if now - st.mtimeMs > maxAgeMs then
                            // Try to delete; ignore errors (concurrent writers may already have replaced the file)
                            Abort.run[TastyError](deleteFile(source, path)).andThen(Kyo.unit)
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
        def evictOlderThan(cacheDir: String, d: Duration)(using Frame): Unit < (Sync & Abort[TastyError]) =
            evictOlderThan(cacheDir, d.toMillis)

        /** Internal overload that accepts a custom FileSource for testing. */
        private[kyo] def evictOlderThanWithSource(
            cacheDir: String,
            maxAgeMs: Long,
            source: kyo.internal.tasty.query.FileSource
        )(using Frame): Unit < (Sync & Abort[TastyError]) =
            source.list(cacheDir, ".krfl").flatMap: files =>
                Kyo.foreach(files): path =>
                    source.stat(path).flatMap: st =>
                        val now = java.lang.System.currentTimeMillis()
                        if now - st.mtimeMs > maxAgeMs then
                            Abort.run[TastyError](deleteFile(source, path)).andThen(Kyo.unit)
                        else
                            Kyo.unit
                        end if
                .andThen(Kyo.unit)
        end evictOlderThanWithSource

        private def deleteFile(
            source: kyo.internal.tasty.query.FileSource,
            path: String
        )(using Frame): Unit < (Sync & Abort[TastyError]) =
            // Rename to a tombstone then discard; if rename fails (already gone) we just continue.
            val tombstone = path + ".deleting"
            source.rename(path, tombstone).andThen:
                source.rename(tombstone, tombstone + ".gone").andThen(Kyo.unit)
        end deleteFile

    end Snapshot

end Tasty
