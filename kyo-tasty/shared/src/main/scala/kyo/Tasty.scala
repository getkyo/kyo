package kyo

import kyo.internal.tasty.binary.Utf8
import kyo.internal.tasty.query.ClasspathOrchestrator
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
        // §839 case 3: module-load Interner construction (single global value at class init)
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
            // §839 case 3 -- monotone interner; same input produces the same Name forever.
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

    // ── Error mode ───────────────────────────────────────────────────────────

    /** Controls error handling during classpath open.
      *
      * `SoftFail`: decode errors accumulate in `cp.errors`; the classpath is returned regardless. `FailFast`: any decode error immediately
      * raises `Abort[TastyError]`.
      */
    enum ErrorMode derives CanEqual:
        case SoftFail
        case FailFast
    end ErrorMode

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

    enum Constant derives CanEqual:
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

        /** Human-readable representation. Pure; requires no Classpath. */
        def show: String = this match
            case StringConst(s)  => "\"" + s + "\""
            case IntConst(i)     => i.toString
            case LongConst(l)    => l.toString + "L"
            case FloatConst(f)   => f.toString + "f"
            case DoubleConst(d)  => d.toString
            case BooleanConst(b) => b.toString
            case CharConst(c)    => "'" + c + "'"
            case ByteConst(b)    => b.toString
            case ShortConst(s)   => s.toString
            case UnitConst       => "()"
            case NullConst       => "null"
            case ClassConst(t)   => "classOf[" + t.toString + "]"
        end show
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
      * Both `annotationType` and `args` are populated during Classpath open Pass B. A decode failure produces `args = Maybe.Absent` and
      * appends a TastyError.MalformedSection to the file-result error list, which flows into cp.errors.
      *
      * `annotationType` is resolved best-effort during pass 1 and may be a placeholder symbol. Equality and hashing are structural over
      * both fields (case class auto-generation).
      */
    final case class Annotation(annotationType: Type, args: Maybe[Tree])

    object Annotation:
        /** CanEqual instance for structural equality comparisons in tests. */
        given CanEqual[Annotation, Annotation] = CanEqual.canEqualAny
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
        case Named(symbolId: SymbolId)
        case TermRef(prefix: Type, name: Name)
        case Applied(base: Type, args: Chunk[Type])
        case TypeLambda(paramIds: Chunk[SymbolId], body: Type)
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
        case ThisType(clsId: SymbolId)
        case SuperType(self: Type, mixin: Type)
        case ParamRef(binderId: SymbolId, idx: Int)
        case Wildcard(lo: Type, hi: Type)
        case Skolem(underlying: Type)
        case MatchType(bound: Type, scrutinee: Type, cases: Chunk[Type])
        case FlexibleType(underlying: Type)

        /** Structural subtype check.
          *
          * Pure: walks Type cases recursively; uses cp.symbol(id) to resolve parents when needed. Returns Sub when this is a subtype of
          * other, NotSub when definitely not, Unknown when the relation cannot be decided from the loaded classpath (e.g. a Named refers to
          * an Unresolved symbol).
          */
        def isSubtypeOf(other: Type)(using cp: Classpath): SubtypeVerdict =
            kyo.internal.tasty.type_.Subtyping.isSubtype(this, other, cp, budget = 64)

        /** Human-readable formatting; resolves Named ids to symbol names via the Classpath. */
        def show(using cp: Classpath): String =
            import Name.asString
            this match
                case Named(id)              => cp.symbol(id).name.asString
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
    sealed trait Tree:

        /** Direct structural child trees of this node. Leaf nodes return `Chunk.empty`. */
        def children: Chunk[Tree]

        /** Pre-order traversal: visits this node then all descendants. */
        def foreach(f: Tree => Unit): Unit =
            f(this)
            children.foreach(_.foreach(f))
        end foreach

        /** Collect all nodes matching `pf` in pre-order. */
        def collect[A](pf: PartialFunction[Tree, A]): Chunk[A] =
            val b = Chunk.newBuilder[A]
            foreach: t =>
                if pf.isDefinedAt(t) then b += pf(t)
            b.result()
        end collect

        /** Find first node satisfying `p` in pre-order. */
        def find(p: Tree => Boolean): Maybe[Tree] =
            var found: Tree | Null = null
            foreach: t =>
                if (found eq null) && p(t) then found = t
            // safe: null-guarded
            if found eq null then Maybe.Absent else Maybe(found.asInstanceOf[Tree])
        end find

        /** Left-fold over all nodes in pre-order. */
        def foldLeft[A](z: A)(f: (A, Tree) => A): A =
            var acc = z
            foreach((t: Tree) => acc = f(acc, t))
            acc
        end foldLeft

        /** Human-readable formatting; resolves symbols and types via the Classpath. */
        def show(using cp: Classpath): String =
            kyo.internal.tasty.reader.TreeShow.show(this, cp)
    end Tree

    object Tree:
        /** Term reference by name (IDENT tag). */
        final case class Ident(name: Name, tpe: Type) extends Tree:
            def children: Chunk[Tree] = Chunk.empty

        /** Member selection (SELECT tag). */
        final case class Select(qualifier: Tree, name: Name, tpe: Type) extends Tree:
            def children: Chunk[Tree] = Chunk(qualifier)

        /** Function application (APPLY tag). */
        final case class Apply(fun: Tree, args: Chunk[Tree]) extends Tree:
            def children: Chunk[Tree] = Chunk(fun) ++ args

        /** Type application (TYPEAPPLY tag). */
        final case class TypeApply(fun: Tree, args: Chunk[Type]) extends Tree:
            def children: Chunk[Tree] = Chunk(fun)

        /** Block of statements followed by an expression (BLOCK tag). */
        final case class Block(stats: Chunk[Tree], expr: Tree) extends Tree:
            def children: Chunk[Tree] = stats :+ expr

        /** Conditional expression (IF tag). */
        final case class If(cond: Tree, thenp: Tree, elsep: Tree) extends Tree:
            def children: Chunk[Tree] = Chunk(cond, thenp, elsep)

        /** Pattern match (MATCH tag). */
        final case class Match(selector: Tree, cases: Chunk[CaseDef]) extends Tree:
            def children: Chunk[Tree] = Chunk(selector) ++ cases

        /** Single case in a match (CASEDEF tag). */
        final case class CaseDef(pattern: Tree, guard: Maybe[Tree], body: Tree) extends Tree:
            def children: Chunk[Tree] =
                val guardChunk = guard match
                    case Maybe.Present(t) => Chunk(t)
                    case Maybe.Absent     => Chunk.empty
                Chunk(pattern) ++ guardChunk :+ body
            end children
        end CaseDef

        /** Literal constant (various const tags). */
        final case class Literal(constant: Constant) extends Tree:
            def children: Chunk[Tree] = Chunk.empty

        /** Object allocation (NEW tag). */
        final case class New(tpe: Type) extends Tree:
            def children: Chunk[Tree] = Chunk.empty

        /** Assignment (ASSIGN tag). */
        final case class Assign(lhs: Tree, rhs: Tree) extends Tree:
            def children: Chunk[Tree] = Chunk(lhs, rhs)

        /** Return statement (RETURN tag). */
        final case class Return(expr: Maybe[Tree], from: Symbol) extends Tree:
            def children: Chunk[Tree] =
                expr match
                    case Maybe.Present(t) => Chunk(t)
                    case Maybe.Absent     => Chunk.empty
        end Return

        /** Throw expression (THROW tag). */
        final case class Throw(expr: Tree) extends Tree:
            def children: Chunk[Tree] = Chunk(expr)

        /** Lambda / anonymous function (LAMBDA tag). */
        final case class Lambda(method: Tree, tpe: Maybe[Type]) extends Tree:
            def children: Chunk[Tree] = Chunk(method)

        /** Type ascription (TYPED tag). */
        final case class Typed(expr: Tree, tpe: Type) extends Tree:
            def children: Chunk[Tree] = Chunk(expr)

        /** Inlined call expansion (INLINED tag). */
        final case class Inlined(call: Maybe[Tree], bindings: Chunk[Tree], body: Tree) extends Tree:
            def children: Chunk[Tree] =
                val callChunk = call match
                    case Maybe.Present(t) => Chunk(t)
                    case Maybe.Absent     => Chunk.empty
                callChunk ++ bindings :+ body
            end children
        end Inlined

        /** Try/catch/finally (TRY tag). */
        final case class Try(expr: Tree, cases: Chunk[CaseDef], finalizer: Maybe[Tree]) extends Tree:
            def children: Chunk[Tree] =
                val finChunk = finalizer match
                    case Maybe.Present(t) => Chunk(t)
                    case Maybe.Absent     => Chunk.empty
                Chunk(expr) ++ cases ++ finChunk
            end children
        end Try

        /** While loop (WHILE tag). */
        final case class While(cond: Tree, body: Tree) extends Tree:
            def children: Chunk[Tree] = Chunk(cond, body)

        /** Pattern binding (BIND tag). */
        final case class Bind(name: Name, pattern: Tree) extends Tree:
            def children: Chunk[Tree] = Chunk(pattern)

        /** Alternative patterns in a case (ALTERNATIVE tag). */
        final case class Alternative(patterns: Chunk[Tree]) extends Tree:
            def children: Chunk[Tree] = patterns

        /** Unapply extractor call (UNAPPLY tag). */
        final case class Unapply(fun: Tree, implicits: Chunk[Tree], patterns: Chunk[Tree]) extends Tree:
            def children: Chunk[Tree] = Chunk(fun) ++ implicits ++ patterns

        /** Val or var definition (VALDEF tag). */
        final case class ValDef(sym: Symbol, tpt: Type, rhs: Maybe[Tree]) extends Tree:
            def children: Chunk[Tree] =
                rhs match
                    case Maybe.Present(t) => Chunk(t)
                    case Maybe.Absent     => Chunk.empty
        end ValDef

        /** Method definition (DEFDEF tag). */
        final case class DefDef(sym: Symbol, paramss: Chunk[Chunk[Tree]], tpt: Type, rhs: Maybe[Tree]) extends Tree:
            def children: Chunk[Tree] =
                val params = paramss.flatMap(identity)
                rhs match
                    case Maybe.Present(t) => params :+ t
                    case Maybe.Absent     => params
            end children
        end DefDef

        /** Type alias or abstract type definition (TYPEDEF tag). */
        final case class TypeDef(sym: Symbol, rhs: Type) extends Tree:
            def children: Chunk[Tree] = Chunk.empty

        /** Package definition (PACKAGE tag). */
        final case class PackageDef(sym: Symbol, stats: Chunk[Tree]) extends Tree:
            def children: Chunk[Tree] = stats

        /** Class definition (TYPEDEF with TEMPLATE). */
        final case class ClassDef(sym: Symbol, template: Template) extends Tree:
            def children: Chunk[Tree] = Chunk(template)

        /** Class template body (TEMPLATE tag). */
        final case class Template(parents: Chunk[Tree], self: Maybe[Symbol], body: Chunk[Tree]) extends Tree:
            def children: Chunk[Tree] = parents ++ body

        /** Super reference (SUPER tag). */
        final case class Super(qual: Tree, mix: Maybe[Name]) extends Tree:
            def children: Chunk[Tree] = Chunk(qual)

        /** This reference (THIS tag). */
        final case class This(cls: Symbol) extends Tree:
            def children: Chunk[Tree] = Chunk.empty

        /** Named argument in an application (NAMEDARG tag). */
        final case class NamedArg(name: Name, value: Tree) extends Tree:
            def children: Chunk[Tree] = Chunk(value)

        /** Annotated tree (ANNOTATEDtpt/ANNOTATEDtype). */
        final case class Annotated(expr: Tree, annotation: Tree) extends Tree:
            def children: Chunk[Tree] = Chunk(expr, annotation)

        /** Shared sub-tree back-reference (SHAREDtype or SHAREDterm tag). `addr` is the byte address of the original node. */
        final case class Shared(addr: Int) extends Tree:
            def children: Chunk[Tree] = Chunk.empty

        /** TASTy category-1 modifier tag (single-byte, no payload; tag in range [1, 59]). */
        final case class Modifier(flag: Flag) extends Tree:
            def children: Chunk[Tree] = Chunk.empty

        /** Recursive type wrapper (RECtype tag). */
        final case class RecType(parent: Tree) extends Tree:
            def children: Chunk[Tree] = Chunk(parent)

        /** Super type pair (SUPERtype tag). */
        final case class SuperType(thistpe: Tree, supertpe: Tree) extends Tree:
            def children: Chunk[Tree] = Chunk(thistpe, supertpe)

        /** Structural refinement type (REFINEDtype tag). */
        final case class RefinedType(parent: Tree, name: Name, info: Tree) extends Tree:
            def children: Chunk[Tree] = Chunk(parent, info)

        /** Type constructor applied to arguments (APPLIEDtype tag). */
        final case class AppliedType(tycon: Tree, args: Chunk[Tree]) extends Tree:
            def children: Chunk[Tree] = Chunk(tycon) ++ args

        /** Type bounds (TYPEBOUNDS tag). */
        final case class TypeBounds(lo: Tree, hi: Tree) extends Tree:
            def children: Chunk[Tree] = Chunk(lo, hi)

        /** Annotated type (ANNOTATEDtype tag). */
        final case class AnnotatedType(parent: Tree, annot: Tree) extends Tree:
            def children: Chunk[Tree] = Chunk(parent, annot)

        /** Intersection type (ANDtype tag). */
        final case class AndType(left: Tree, right: Tree) extends Tree:
            def children: Chunk[Tree] = Chunk(left, right)

        /** Union type (ORtype tag). */
        final case class OrType(left: Tree, right: Tree) extends Tree:
            def children: Chunk[Tree] = Chunk(left, right)

        /** By-name type (BYNAMEtype tag). */
        final case class ByNameType(arg: Tree) extends Tree:
            def children: Chunk[Tree] = Chunk(arg)

        /** Match type with scrutinee and cases (MATCHtype tag). */
        final case class MatchType(bound: Tree, scrutinee: Tree, cases: Chunk[Tree]) extends Tree:
            def children: Chunk[Tree] = Chunk(bound, scrutinee) ++ cases

        /** Flexible (Java-nullable) type (FLEXIBLEtype tag). */
        final case class FlexibleType(arg: Tree) extends Tree:
            def children: Chunk[Tree] = Chunk(arg)

        /** Type-position identifier (IDENTtpt tag): nameRef + type. */
        final case class IdentTpt(name: Name, tpe: Type) extends Tree:
            def children: Chunk[Tree] = Chunk.empty

        /** Type-position selection (SELECTtpt tag): qualifier + name. */
        final case class SelectTpt(qual: Tree, name: Name) extends Tree:
            def children: Chunk[Tree] = Chunk(qual)

        /** Singleton type (SINGLETONtpt tag): ref tree. */
        final case class SingletonTpt(tpe: Tree) extends Tree:
            def children: Chunk[Tree] = Chunk(tpe)

        /** Package-level term reference (TERMREFpkg tag): package name only. */
        final case class TermRefPkg(name: Name) extends Tree:
            def children: Chunk[Tree] = Chunk.empty

        /** Package-level type reference (TYPEREFpkg tag): package name only. */
        final case class TypeRefPkg(name: Name) extends Tree:
            def children: Chunk[Tree] = Chunk.empty

        /** Symbol-addressed term reference (TERMREFsymbol tag): addr + qualifier. */
        final case class TermRefSymbol(addr: Int, qual: Tree) extends Tree:
            def children: Chunk[Tree] = Chunk(qual)

        /** Symbol-addressed type reference (TYPEREFsymbol tag): addr + qualifier. */
        final case class TypeRefSymbol(addr: Int, qual: Tree) extends Tree:
            def children: Chunk[Tree] = Chunk(qual)

        /** Direct-address term reference (TERMREFdirect tag): symbol address. */
        final case class TermRefDirect(addr: Int) extends Tree:
            def children: Chunk[Tree] = Chunk.empty

        /** Direct-address type reference (TYPEREFdirect tag): symbol address. */
        final case class TypeRefDirect(addr: Int) extends Tree:
            def children: Chunk[Tree] = Chunk.empty

        /** Owner-qualified selection (SELECTin tag): qualifier + name + owner. */
        final case class SelectIn(qual: Tree, name: Name, owner: Tree) extends Tree:
            def children: Chunk[Tree] = Chunk(qual, owner)

        /** Import statement (IMPORT tag): qualifier expression and selector trees. */
        final case class Import(qual: Tree, selectors: Chunk[Tree]) extends Tree:
            def children: Chunk[Tree] = Chunk(qual) ++ selectors

        /** Export clause (EXPORT tag): qualifier expression and selector trees. */
        final case class Export(qual: Tree, selectors: Chunk[Tree]) extends Tree:
            def children: Chunk[Tree] = Chunk(qual) ++ selectors

        /** In-tree annotation node (ANNOTATION tag): annotation class type tree and annotation argument tree. */
        final case class AnnotationNode(annotType: Tree, arg: Tree) extends Tree:
            def children: Chunk[Tree] = Chunk(annotType, arg)

        /** Unknown tag -- encountered a tag not covered by this ADT version. */
        final case class Unknown(tag: Int, length: Int) extends Tree:
            def children: Chunk[Tree] = Chunk.empty
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
      * Resolution methods (`owner`, `parents`, `declarations`, `fullName`, etc.) require a `Classpath` in scope and are pure data accessors
      * with no effect row.
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

        def isCaseClass: Boolean  = isClass && isCase
        def isCaseObject: Boolean = isObject && isCase
        def isClassLike: Boolean  = isClass || isTrait || isObject
        def isTypeLike: Boolean   = isTypeAlias || isOpaqueTypeKind || isAbstractType || isTypeParam
        def isTerm: Boolean       = isMethod || isVal || isVar || isField || isParameter

        // Resolution accessors -- all pure; require a Classpath to resolve SymbolId references.

        /** Resolve the owning symbol of this symbol.
          *
          * Pure O(1) lookup in the immutable `cp.symbols` array. Returns the sentinel unresolved symbol for out-of-range ids.
          */
        def owner(using cp: Classpath): Symbol = cp.symbol(ownerId)

        /** Resolve the direct parent symbols by extracting only `Type.Named` entries from `parentTypes`.
          *
          * Applied parents (e.g. `List[Int]`) are skipped; only the top-level `Named` case is unwrapped. Returns an empty Chunk for Package
          * symbols and symbols with no parents.
          */
        def parents(using cp: Classpath): Chunk[Symbol] =
            parentTypes.collect { case Type.Named(pid) => cp.symbol(pid) }

        /** Resolve the type parameter symbols recorded during Pass C. */
        def typeParams(using cp: Classpath): Chunk[Symbol] = typeParamIds.map(cp.symbol)

        /** Resolve all direct member symbols (declarations) of this symbol. */
        def declarations(using cp: Classpath): Chunk[Symbol] = declarationIds.map(cp.symbol)

        /** Resolve the permitted direct subclasses for sealed / enum symbols. Returns `Absent` when this symbol has no sealed subclass
          * list.
          */
        def permittedSubclasses(using cp: Classpath): Maybe[Chunk[Symbol]] =
            permittedSubclassIds.map(_.map(cp.symbol))

        /** Look up the companion symbol (class companion object or object companion class) via the classpath companion index.
          *
          * Returns `Absent` when no companion is registered in the classpath (e.g. plain classes with no companion, Java symbols).
          */
        def companion(using cp: Classpath): Maybe[Symbol] = cp.companion(this)

        /** Compute the fully-qualified dotted name of this symbol by walking the owner chain. */
        def fullName(using cp: Classpath): Name = cp.fullName(this)

        /** Compute the JVM binary name (slash-separated packages, dollar-sign-separated nested types). */
        def binaryName(using cp: Classpath): String =
            kyo.internal.tasty.symbol.BinaryName.compute(this, cp)

        /** All method-kind declarations of this symbol. */
        def methods(using cp: Classpath): Chunk[Symbol] = declarations.filter(_.isMethod)

        /** All val-kind declarations of this symbol. */
        def vals(using cp: Classpath): Chunk[Symbol] = declarations.filter(_.isVal)

        /** All var-kind declarations of this symbol. */
        def vars(using cp: Classpath): Chunk[Symbol] = declarations.filter(_.isVar)

        /** All field-kind declarations of this symbol. */
        def fields(using cp: Classpath): Chunk[Symbol] = declarations.filter(_.isField)

        /** All nested class, trait, and object declarations of this symbol. */
        def nestedTypes(using cp: Classpath): Chunk[Symbol] =
            declarations.filter(s => s.isClass || s.isTrait || s.isObject)

        /** All type-like declarations (type aliases, opaque types, abstract types, type parameters) of this symbol. */
        def typeMembers(using cp: Classpath): Chunk[Symbol] = declarations.filter(_.isTypeLike)

        /** Find a direct member by simple string name.
          *
          * Returns `Absent` when no member with the given name exists. Uses `Name.asString` for comparison.
          */
        def findMember(name: String)(using cp: Classpath): Maybe[Symbol] =
            import Name.asString
            declarations.find(_.name.asString == name) match
                case Some(s) => Maybe(s)
                case None    => Maybe.Absent
        end findMember

        /** Find a direct member by `Name` value. */
        def findMemberByName(n: Name)(using cp: Classpath): Maybe[Symbol] =
            import Name.given
            declarations.find(_.name == n) match
                case Some(s) => Maybe(s)
                case None    => Maybe.Absent
        end findMemberByName

        /** All declarations of the given kind. */
        def membersByKind(k: SymbolKind)(using cp: Classpath): Chunk[Symbol] = declarations.filter(_.kind == k)

        /** Human-readable representation: `"<kind> <fullName>"`. */
        def show(using cp: Classpath): String = kyo.internal.tasty.symbol.SymbolShow.show(this, cp)

        /** Test-accessible field override helper. Called from test code in package kyo.
          *
          * Equivalent to the auto-generated copy method but accessible from package kyo (not just object Tasty). Only fields relevant to
          * tests are overridable; extend as needed.
          */
        private[kyo] def withParentTypes(pts: Chunk[Type]): Symbol =
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
                parentTypes = pts,
                typeParamIds = typeParamIds,
                declarationIds = declarationIds,
                permittedSubclassIds = permittedSubclassIds,
                bodyRecord = bodyRecord
            )

        private[kyo] def withDeclarationIds(ids: Chunk[SymbolId]): Symbol =
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
                declarationIds = ids,
                permittedSubclassIds = permittedSubclassIds,
                bodyRecord = bodyRecord
            )

        private[kyo] def withTypeParamIds(ids: Chunk[SymbolId]): Symbol =
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
                typeParamIds = ids,
                declarationIds = declarationIds,
                permittedSubclassIds = permittedSubclassIds,
                bodyRecord = bodyRecord
            )

        private[kyo] def withId(newId: SymbolId, newOwnerId: SymbolId): Symbol =
            Symbol(
                id = newId,
                kind = kind,
                flags = flags,
                name = name,
                ownerId = newOwnerId,
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

        /** Decode the body of this symbol into a `Tree`.
          *
          * Returns `Absent` for Package symbols, Java symbols, and any symbol whose body record is empty. Fails with
          * `TastyError.MalformedSection` on corrupt body bytes.
          *
          * This is the ONE Symbol method that carries a kyo effect row. All other Symbol members return plain values. The `bodyRecord`
          * constructor parameter (a raw `Maybe[SymbolBody]` byte record) and this method coexist because they have different signatures:
          * the field takes no arguments while this method requires `using` clauses.
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
          * plan: phase-02 factory; used by ClasspathOrchestrator.materializeSymbols to build final Symbols from SymbolDescriptors.
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

        /** Construct a partial placeholder Symbol for Pass A/B tree-decode use only.
          *
          * Produces a Symbol with id = SymbolId(-1) and only kind, flags, and name populated. All relational fields (parentTypes,
          * declarationIds, etc.) are left at empty defaults. Pass C replaces partial symbols with fully-populated ones via
          * `materializeSymbols`.
          *
          * Retained as a factory shim for AstUnpickler, TypeUnpickler, TreeUnpickler, ClasspathOrchestrator, ClassfileUnpickler, and
          * JavaAnnotationUnpickler. Deletion requires migrating all Pass A/B callers to SymbolDescriptor-based construction, which is out
          * of scope for this campaign.
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

    /** Immutable snapshot of a fully-loaded TASTy classpath.
      *
      * All fields are plain immutable values populated once during `open` and never mutated. Reading any field after `open` returns is a
      * pure operation with no effect row and no `AllowUnsafe`. The sole exception is `decodeBody`, which decodes AST bytes on demand and
      * carries `Sync & Abort[TastyError]`.
      *
      * Constructor is `private[Tasty]`; instances are obtained exclusively via `Classpath.open`, `Classpath.openCached`,
      * `Classpath.fromPickles`, or `Classpath.fromPicklesWithSymbols`.
      *
      * Pins: INV-003 (Classpath case class fields immutable post-construction), INV-004 (bodyMemo excluded from equality).
      */
    final case class Classpath private[Tasty] (
        symbols: Chunk[Symbol],
        rootSymbolId: SymbolId,
        topLevelClassIds: Chunk[SymbolId],
        packageIds: Chunk[SymbolId],
        fqnIndex: Map[String, SymbolId],
        packageIndex: Map[String, SymbolId],
        subclassIndex: Map[SymbolId, Chunk[SymbolId]],
        companionIndex: Map[SymbolId, SymbolId],
        moduleIndex: Map[String, ModuleDescriptor],
        errors: Chunk[TastyError],
        canonical: kyo.internal.tasty.type_.TypeArena
    ):
        // NOT a constructor parameter -- excluded from auto-generated equals / hashCode / copy / unapply.
        // A cp.copy(...) call produces a new Classpath with a fresh empty memo; this is correct because
        // memoized decode results are an optimization, not observable state.
        private lazy val bodyMemo: java.util.concurrent.ConcurrentHashMap[SymbolId, Either[TastyError, Tree]] =
            new java.util.concurrent.ConcurrentHashMap()

        /** O(1) Symbol lookup by SymbolId. Returns the Symbol at index `id.value`. Returns a sentinel Unresolved symbol for out-of-range or
          * unassigned ids (id.value == -1, or id.value >= symbols.length).
          */
        def symbol(id: SymbolId): Symbol =
            val idx = SymbolId.value(id)
            if idx >= 0 && idx < symbols.length then symbols(idx)
            else Classpath.sentinelUnresolved
        end symbol

        /** Look up a class symbol by fully-qualified dotted name.
          *
          * Pure O(1) lookup in the immutable fqnIndex. Returns `Absent` if the FQN is not registered in this classpath.
          *
          * Example:
          * {{{
          *   val sym = cp.findClass("scala.Predef")
          *   sym.isPresent == true
          * }}}
          */
        def findClass(fqn: String): Maybe[Symbol] =
            fqnIndex.get(fqn) match
                case Some(id) => Maybe(symbol(id))
                case None     => Maybe.Absent

        /** Look up a package symbol by fully-qualified dotted name.
          *
          * Pure O(1) lookup in the immutable packageIndex. Returns `Absent` if the package is not in this classpath.
          */
        def findPackage(fqn: String): Maybe[Symbol] =
            packageIndex.get(fqn) match
                case Some(id) => Maybe(symbol(id))
                case None     => Maybe.Absent

        /** All package symbols in this classpath.
          *
          * Pure accessor over the immutable `packageIds` Chunk. Each id is resolved to a Symbol via `symbol(id)`.
          *
          * Example:
          * {{{
          *   val pkgs = cp.packages
          *   pkgs.nonEmpty == true
          * }}}
          */
        def packages: Chunk[Symbol] = packageIds.map(symbol)

        /** All top-level class symbols (not packages) in this classpath.
          *
          * Pure accessor over the immutable `topLevelClassIds` Chunk. Each id is resolved to a Symbol via `symbol(id)`.
          *
          * Example:
          * {{{
          *   val classes = cp.topLevelClasses
          *   classes.nonEmpty == true
          * }}}
          */
        def topLevelClasses: Chunk[Symbol] = topLevelClassIds.map(symbol)

        /** Look up a JPMS module descriptor by module name (e.g., "java.base").
          *
          * Returns `Present(descriptor)` if a `module-info.class` with the given module name was found in the classpath roots. Returns
          * `Absent` if no matching module was found.
          *
          * Pure O(1) lookup in the immutable moduleIndex.
          */
        def findModule(name: String): Maybe[ModuleDescriptor] =
            Maybe(moduleIndex.get(name).orNull)

        /** Find a class symbol by JVM binary name (e.g., "com/example/Foo$Inner").
          *
          * Converts the binary name to a dotted FQN and delegates to `findClass`.
          *
          * Pure O(1) lookup; no I/O.
          */
        def findClassByBinary(binaryName: String): Maybe[Symbol] =
            val fqn = binaryName.replace('/', '.').replace('$', '.')
            findClass(fqn)

        /** Look up the companion symbol (companion object for a class/trait; companion class for an object).
          *
          * Pure O(1) lookup in the immutable `companionIndex`. Returns `Absent` when no companion is registered.
          */
        def companion(sym: Symbol): Maybe[Symbol] =
            companionIndex.get(sym.id) match
                case Some(cid) => Maybe(symbol(cid))
                case None      => Maybe.Absent

        /** Compute the fully-qualified dotted name of `sym` by walking the owner chain.
          *
          * Walks upward collecting non-empty segment names; stops when the symbol owns itself (root sentinel), when ownerId is -1, or when
          * the same symbol appears twice (cycle guard). Depth limit of 64 prevents unbounded loops.
          */
        def fullName(sym: Symbol): Name =
            import Name.asString
            val parts   = new scala.collection.mutable.ArrayBuffer[String]()
            var cur     = sym
            var depth   = 0
            var stop    = false
            val visited = new java.util.HashSet[Int]()
            while !stop && depth < 64 && visited.add(cur.id.value) do
                val n = cur.name.asString
                if n.nonEmpty then parts.prepend(n)
                val ownerId = cur.ownerId
                if ownerId == cur.id || ownerId.value == -1 then
                    stop = true
                else
                    val ownerSym = symbol(ownerId)
                    if ownerSym.id == cur.id || ownerSym.name.asString.isEmpty then
                        stop = true
                    else
                        cur = ownerSym
                        depth += 1
                    end if
                end if
            end while
            Name(parts.mkString("."))
        end fullName

        /** Decode the body bytes of `sym` into a `Tree`, memoizing the result.
          *
          * Returns `Absent` for symbols whose `bodyRecord` slot is `Absent` (Package, Java, and symbols without an AST body slice). Fails
          * with `TastyError.MalformedSection` on corrupt body bytes.
          *
          * Memoization: the first call for a given `sym` decodes the bytes and stores the result (success or failure) in `bodyMemo`. All
          * subsequent calls for the same `sym` return the stored result without re-decoding. The memo is keyed by `sym.id` (SymbolId) and
          * is per-Classpath instance; `cp.copy(...)` produces a fresh memo.
          *
          * Called by `Symbol.body(using cp, frame)`. INV-010: AllowUnsafe does not appear on this signature.
          */
        def decodeBody(sym: Symbol)(using Frame): Maybe[Tree] < (Sync & Abort[TastyError]) =
            sym.bodyRecord match
                case Maybe.Absent => Sync.defer(Maybe.Absent)
                case Maybe.Present(blob) =>
                    Sync.Unsafe.defer:
                        val cached = bodyMemo.get(sym.id)
                        if cached ne null then
                            cached match
                                case Right(t) => Maybe(t)
                                case Left(e)  => Abort.fail(e)
                        else
                            val result: Either[TastyError, Tree] =
                                try
                                    val syms = symbols
                                    Right(kyo.internal.tasty.reader.TreeUnpickler.decodeSync(
                                        blob,
                                        sym,
                                        idx => if idx >= 0 && idx < syms.length then syms(idx) else sym
                                    ))
                                catch
                                    case ex: kyo.internal.tasty.reader.TreeUnpickler.DecodeException =>
                                        Left(TastyError.MalformedSection("ASTs", ex.getMessage, ex.byteOffset))
                                    case _: ArrayIndexOutOfBoundsException =>
                                        Left(TastyError.MalformedSection("ASTs", "truncated body", 0L))
                            bodyMemo.put(sym.id, result)
                            result match
                                case Right(t) => Maybe(t)
                                case Left(e)  => Abort.fail(e)
                        end if

        /** Package-private memo size for test verification. NOT part of the public API. */
        private[kyo] def bodyMemoSize: Int = bodyMemo.size()

        /** All direct subclasses of `sym` (one hop, from the subclass index).
          *
          * Pure O(k) lookup where k is the number of direct subclasses. Returns an empty Chunk when `sym` has no registered subclasses.
          */
        def directSubclassesOf(sym: Symbol): Chunk[Symbol] =
            subclassIndex.getOrElse(sym.id, Chunk.empty).map(symbol)

        /** All transitive subclasses of `sym` (BFS closure over the subclass index).
          *
          * Returns an empty Chunk when `sym` has no registered subclasses. The BFS visited set prevents infinite loops on malformed
          * (cyclic) classpath data.
          */
        def subclassesOf(sym: Symbol): Chunk[Symbol] = transitiveSubclasses(sym)

        /** All concrete class symbols that are transitive subclasses of `sym`.
          *
          * Equivalent to `subclassesOf(sym).filter(s => s.isClass && !s.isAbstract)`.
          */
        def implementationsOf(sym: Symbol): Chunk[Symbol] =
            subclassesOf(sym).filter(s => s.isClass && !s.isAbstract)

        private def transitiveSubclasses(root: Symbol): Chunk[Symbol] =
            val visited = scala.collection.mutable.HashSet.empty[SymbolId]
            val out     = Chunk.newBuilder[Symbol]
            val queue   = scala.collection.mutable.Queue(root.id)
            while queue.nonEmpty do
                val curId = queue.dequeue()
                subclassIndex.getOrElse(curId, Chunk.empty).foreach: childId =>
                    if visited.add(childId) then
                        val child = symbol(childId)
                        out += child
                        queue.enqueue(childId)
            end while
            out.result()
        end transitiveSubclasses

    end Classpath

    object Classpath:

        /** Sentinel symbol returned by `Classpath.symbol` for out-of-range or unassigned ids. */
        val sentinelUnresolved: Symbol =
            Symbol(
                id = SymbolId(-1),
                kind = SymbolKind.Unresolved,
                flags = Flags.empty,
                name = Name("<unresolved>"),
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

        /** Open a classpath from directory/file roots using `ErrorMode.SoftFail` (errors accumulate in `cp.errors`).
          *
          * Effect row rationale:
          *   - `Async`: parallel per-file decode across the workgroup (subsumes Sync).
          *   - `Scope`: registers a finalizer that closes JAR pools and mmap arenas on scope exit.
          *   - `Abort[TastyError]`: fatal errors (classpath build state, snapshot mismatch).
          *
          * One-arg variant: delegates to the canonical two-arg form with `ErrorMode.SoftFail`.
          */
        def open(roots: Seq[String])(using Frame): Classpath < (Async & Scope & Abort[TastyError]) =
            open(roots, ErrorMode.SoftFail)

        /** Open a classpath from directory/file roots with the given `ErrorMode`.
          *
          * Effect row rationale:
          *   - `Async`: parallel per-file decode across the workgroup (subsumes Sync).
          *   - `Scope`: registers a finalizer that closes JAR pools and mmap arenas on scope exit.
          *   - `Abort[TastyError]`: fatal errors (classpath build state, snapshot mismatch).
          *
          * `ErrorMode.SoftFail`: decode errors accumulate in `cp.errors`; classpath is returned. `ErrorMode.FailFast`: any decode error
          * immediately raises `Abort[TastyError]`.
          */
        def open(roots: Seq[String], mode: ErrorMode)(using Frame): Classpath < (Async & Scope & Abort[TastyError]) =
            openImpl(roots, mode)

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

        /** Create a classpath from pre-parsed in-memory pickles. */
        def fromPickles(pickles: Seq[Pickle])(using Frame): Classpath < Sync =
            Sync.defer:
                Classpath(
                    symbols = Chunk.empty,
                    rootSymbolId = SymbolId(-1),
                    topLevelClassIds = Chunk.empty,
                    packageIds = Chunk.empty,
                    fqnIndex = Map.empty,
                    packageIndex = Map.empty,
                    subclassIndex = Map.empty,
                    companionIndex = Map.empty,
                    moduleIndex = Map.empty,
                    errors = Chunk.empty,
                    canonical = TypeArena.canonical()
                )

        /** Create a test-only classpath from a pre-built symbols array.
          *
          * symbols(i).id.value must equal i for cp.symbol(id) to resolve correctly. Only callable from within package kyo (private[kyo]).
          */
        private[kyo] def fromPicklesWithSymbols(symbols: Chunk[Symbol])(using Frame): Classpath < Sync =
            Sync.defer:
                Classpath(
                    symbols = symbols,
                    rootSymbolId = if symbols.nonEmpty then SymbolId(0) else SymbolId(-1),
                    topLevelClassIds = Chunk.empty,
                    packageIds = Chunk.empty,
                    fqnIndex = Map.empty,
                    packageIndex = Map.empty,
                    subclassIndex = Map.empty,
                    companionIndex = Map.empty,
                    moduleIndex = Map.empty,
                    errors = Chunk.empty,
                    canonical = TypeArena.canonical()
                )

        /** Internal: open implementation, delegates to ClasspathOrchestrator. */
        private def openImpl(
            roots: Seq[String],
            mode: ErrorMode
        )(using Frame): Classpath < (Sync & Async & Scope & Abort[TastyError]) =
            val source      = PlatformFileSource.get
            val concurrency = Runtime.getRuntime.availableProcessors().max(1)
            TastyStat.scope.traceSpan(
                "coldLoad",
                Attributes.empty.add("roots", roots.size.toString)
            ) {
                ClasspathOrchestrator.open(roots, mode, source, concurrency)
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
                    openImpl(roots, ErrorMode.SoftFail)
                case Result.Panic(_) =>
                    openImpl(roots, ErrorMode.SoftFail)
                case Result.Success(digest) =>
                    val hexDigest    = SnapshotDigest.toHexString(digest)
                    val snapshotPath = s"$cacheDir/$hexDigest.krfl"
                    source.exists(snapshotPath).flatMap: exists =>
                        if exists then
                            // Try to load from snapshot using mmap on JVM/Native, heap on JS.
                            Abort.run[TastyError](SnapshotReader.readMapped(snapshotPath, source)).flatMap:
                                case Result.Success(cp) =>
                                    cp
                                case Result.Failure(_) | Result.Panic(_) =>
                                    // Snapshot unreadable; fall through to normal open
                                    openImpl(roots, ErrorMode.SoftFail)
                        else
                            // No snapshot; open normally then write snapshot
                            openImpl(roots, ErrorMode.SoftFail).flatMap: cp =>
                                Abort.run[TastyError](SnapshotWriter.write(cp, cacheDir, digest, source)).andThen(cp)
        end openCachedImpl

        /** Internal factory for constructing a Tasty.Classpath case class from the finalized data produced by ClasspathOrchestrator or
          * SnapshotReader.
          *
          * Called from ClasspathOrchestrator.finalizeMerge and SnapshotReader.deserialize (package kyo.internal.tasty.query and
          * kyo.internal.tasty.snapshot) which cannot access the private[Tasty] constructor directly.
          */
        private[kyo] def make(
            symbols: Chunk[Symbol],
            rootSymbolId: SymbolId,
            topLevelClassIds: Chunk[SymbolId],
            packageIds: Chunk[SymbolId],
            fqnIndex: Map[String, SymbolId],
            packageIndex: Map[String, SymbolId],
            subclassIndex: Map[SymbolId, Chunk[SymbolId]],
            companionIndex: Map[SymbolId, SymbolId],
            moduleIndex: Map[String, ModuleDescriptor],
            errors: Chunk[TastyError],
            canonical: kyo.internal.tasty.type_.TypeArena
        ): Classpath =
            new Classpath(
                symbols = symbols,
                rootSymbolId = rootSymbolId,
                topLevelClassIds = topLevelClassIds,
                packageIds = packageIds,
                fqnIndex = fqnIndex,
                packageIndex = packageIndex,
                subclassIndex = subclassIndex,
                companionIndex = companionIndex,
                moduleIndex = moduleIndex,
                errors = errors,
                canonical = canonical
            )
        end make

        /** CanEqual instance for structural equality comparisons in tests. */
        given CanEqual[Classpath, Classpath] = CanEqual.canEqualAny

        /** Test helper: copy a Classpath with a new errors field.
          *
          * Equivalent to cp.copy(errors = newErrors) but accessible from tests outside object Tasty. Phase 07 removes this once the copy
          * method becomes public.
          */
        private[kyo] def copyWithErrors(cp: Classpath, newErrors: Chunk[TastyError]): Classpath =
            cp.copy(errors = newErrors)

    end Classpath

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
