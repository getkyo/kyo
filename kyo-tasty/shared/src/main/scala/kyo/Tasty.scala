package kyo

import kyo.internal.tasty.binary.Utf8
import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.query.PlatformFileSource
import kyo.internal.tasty.query.PlatformModuleOps
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

            /** True when the decoded string is empty. */
            def isEmpty: Boolean =
                import Name.asString
                n.asString.isEmpty
        end extension
    end Name

    final class Flags(val bits: Long) extends AnyVal:
        def contains(flag: Flag): Boolean = (bits & flag.bit) != 0L
        def |(other: Flags): Flags        = new Flags(bits | other.bits)

        /** True when no flag bits are set. Equivalent to `this == Flags.empty`. */
        def isEmpty: Boolean = bits == 0L

        /** Human-readable representation: `Flags.empty.show == "Flags()"`. Delegates to `toString`. */
        def show: String = toString

    end Flags

    object Flags:
        /** The empty flag set (no modifiers).
          *
          * Example:
          * {{{
          *   Tasty.Flags.empty.bits == 0L
          * }}}
          */
        val empty: Flags = new Flags(0L)

        /** Convenience constructor: combine one or more flags into a `Flags` value. */
        def apply(head: Flag, rest: Flag*): Flags =
            var bits = head.bit
            rest.foreach(f => bits |= f.bit)
            new Flags(bits)
        end apply
    end Flags

    final class Flag(val bit: Long, val name: String):
        override def toString: String = name

        /** Human-readable flag name. Equivalent to `toString`. */
        def show: String = name
    end Flag

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
            EnumCase, Unresolved
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
    final case class Position(sourceFile: Maybe[String], line: Int, column: Int):
        /** Human-readable representation: `file:line:column`, or `<unknown>:line:column` when `sourceFile` is absent. */
        def show: String =
            val file = sourceFile match
                case Maybe.Present(f) => f
                case Maybe.Absent     => "<unknown>"
            s"$file:$line:$column"
        end show
    end Position

    /** A Scala annotation as it appears on a [[Type]] (`Type.Annotated`).
      *
      * Both `annotationType` and `args` are populated during Classpath open Pass B. A decode failure produces `args = Maybe.Absent` and
      * appends a TastyError.MalformedSection to the file-result error list, which flows into cp.errors.
      *
      * `annotationType` is resolved best-effort during pass 1 and may be a placeholder symbol. Equality and hashing are structural over
      * both fields (case class auto-generation).
      */
    final case class Annotation(annotationType: Type, args: Maybe[Tree]):

        /** Decoded annotation argument trees as a typed Chunk.
          *
          * When `args` holds a `Tree.Apply(_, applyArgs)`, returns the argument list. When `args` holds any other tree, wraps it in a
          * single-element Chunk. When `args` is `Absent`, returns an empty Chunk.
          */
        def argList: Chunk[Tree] = args match
            case Maybe.Present(tree) =>
                tree match
                    case Tree.Apply(_, applyArgs) => applyArgs
                    case _                        => Chunk(tree)
            case Maybe.Absent => Chunk.empty

    end Annotation

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
    ):
        /** True when `ACC_PUBLIC` (0x0001) is set in `accessFlags`. */
        def isJvmPublic: Boolean = (accessFlags & 0x0001) != 0

        /** True when `ACC_PRIVATE` (0x0002) is set in `accessFlags`. */
        def isJvmPrivate: Boolean = (accessFlags & 0x0002) != 0

        /** True when `ACC_PROTECTED` (0x0004) is set in `accessFlags`. */
        def isJvmProtected: Boolean = (accessFlags & 0x0004) != 0

        /** True when `ACC_STATIC` (0x0008) is set in `accessFlags`. */
        def isJvmStatic: Boolean = (accessFlags & 0x0008) != 0

        /** True when `ACC_FINAL` (0x0010) is set in `accessFlags`. */
        def isJvmFinal: Boolean = (accessFlags & 0x0010) != 0

    end JavaMetadata

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

        /** Context function type: `(A1, ..., AN) ?=> R`.
          *
          * Wire-level: `APPLIEDtype` whose constructor has FQN `scala.ContextFunctionN`. Previously decoded as
          * `Type.Function(params, result, isContext = true)`. This dedicated case is structurally distinct so callers can
          * pattern-match `?=>` vs `=>` without testing a Boolean flag, and `Type.Function` remains unchanged for
          * backward compatibility (HARD RULE 4 layered preservation).
          *
          * F-A2-005: every method decoded from a `scala.ContextFunctionN` applied type now produces this case;
          * methods decoded from `scala.FunctionN` continue to produce `Type.Function(_, _, isContext = false)`.
          */
        case ContextFunction(params: Chunk[Type], result: Type)
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

        /** Match-type case: `pat => rhs`. Wire-level TASTy tag MATCHCASEtype (192).
          *
          * First-class ADT case (F-A-006). The prior decoder emitted
          * `Applied(Named(MatchCaseSentinel), Chunk(pat, rhs))` because no first-class
          * case existed. Additive; existing exhaustive matches via Type.children remain
          * total via the wildcard fallback.
          */
        case MatchCase(pat: Type, rhs: Type)

        /** Type-position reference (F-A-009). Wire tag TYPEREF (117).
          *
          * Semantically distinct from TermRef (term-position reference). Previously all TYPEREF
          * nodes were decoded as Type.TermRef; this case corrects the shape. Callers that need
          * to distinguish type references from term references should match on TypeRef.
          * typeFqnString handles both TermRef and TypeRef for annotation FQN matching.
          */
        case TypeRef(qual: Type, name: Name)

        /** Explicit type bounds (F-A-010). Wire tags TYPEBOUNDS (163) and TYPEBOUNDStpt (164).
          *
          * Represents `lo .. hi` as declared in source. Previously both tags decoded as
          * Type.Wildcard(lo, hi); Type.Bounds is semantically distinct and allows callers to
          * identify explicit bounds positions vs wildcard usage sites.
          */
        case Bounds(lo: Type, hi: Type)

        /** Structural subtype check.
          *
          * Pure: walks Type cases recursively; uses cp.symbol(id) to resolve parents when needed. Returns Sub when this is a subtype of
          * other, NotSub when definitely not, Unknown when the relation cannot be decided from the loaded classpath (e.g. a Named refers to
          * an Unresolved symbol).
          */
        def isSubtypeOf(other: Type)(using cp: Classpath): SubtypeVerdict =
            kyo.internal.tasty.type_.Subtyping.isSubtype(this, other, cp, budget = 64)

        /** First-level structural children of this Type. Leaf cases return an empty Chunk. */
        def children: Chunk[Type] = this match
            case Applied(base, args)          => base +: args
            case TypeLambda(_, body)          => Chunk(body)
            case Function(params, ret, _)     => params :+ ret
            case ContextFunction(params, ret) => params :+ ret
            case Tuple(elements)              => elements
            case ByName(t)                    => Chunk(t)
            case Repeated(t)                  => Chunk(t)
            case Array(t)                     => Chunk(t)
            case Refinement(p, _, i)          => Chunk(p, i)
            case Rec(p)                       => Chunk(p)
            case RecThis(rec)                 => Chunk(rec)
            case AndType(l, r)                => Chunk(l, r)
            case OrType(l, r)                 => Chunk(l, r)
            case Annotated(u, _)              => Chunk(u)
            case SuperType(s, m)              => Chunk(s, m)
            case Wildcard(lo, hi)             => Chunk(lo, hi)
            case Skolem(u)                    => Chunk(u)
            case MatchType(b, sc, cases)      => Chunk(b, sc) ++ cases
            case FlexibleType(u)              => Chunk(u)
            case MatchCase(p, r)              => Chunk(p, r)
            case TypeRef(qual, _)             => Chunk(qual)
            case Bounds(lo, hi)               => Chunk(lo, hi)
            case _                            => Chunk.empty

        /** Visit this type and every structural descendant in pre-order (self first). */
        def foreach(f: Type => Unit): Unit =
            f(this)
            children.foreach(_.foreach(f))
        end foreach

        /** Resolve the symbol referenced by this Type's nominal head, when present.
          *
          * `Type.Named(id)` resolves to `cp.symbol(id)`. All other shapes return `Maybe.Absent`.
          */
        def symbolMaybe(using cp: Classpath): Maybe[Symbol] = this match
            case Type.Named(id) => Maybe(cp.symbol(id))
            case _              => Maybe.Absent

        /** Human-readable formatting; resolves Named ids to symbol names via the Classpath. */
        def show(using cp: Classpath): String =
            import Name.asString
            this match
                case Named(id)              => cp.symbol(id).name.asString
                case Applied(base, args)    => s"${base.show}[${args.map(_.show).mkString(", ")}]"
                case Array(elem)            => s"${elem.show}[]"
                case Function(ps, r, isCtx) => s"(${ps.map(_.show).mkString(", ")}) ${if isCtx then "?=>" else "=>"} ${r.show}"
                case ContextFunction(ps, r) => s"(${ps.map(_.show).mkString(", ")}) ?=> ${r.show}"
                case Tuple(es)              => s"(${es.map(_.show).mkString(", ")})"
                case other                  => other.toString
            end match
        end show
    end Type

    object Type:
        /** Sentinel lower-bound type used in `TypeBounds` when no concrete lower bound is known.
          *
          * Represented as `Named(SymbolId(-100))`. Phase 01 bridge; Phase 08 may replace with a real Nothing lookup.
          */
        val Nothing: Type = Type.Named(SymbolId(-100))

        /** Sentinel upper-bound type used in `TypeBounds` when no concrete upper bound is known.
          *
          * Represented as `Named(SymbolId(-101))`. Phase 01 bridge; Phase 08 may replace with a real Any lookup.
          */
        val Any: Type = Type.Named(SymbolId(-101))

        /** Sentinel unknown type used in `fromFlat` when `declaredType` is absent but a concrete `Type` field is required.
          *
          * Represented as `Named(SymbolId(-102))`. Phase 01 bridge.
          */
        val Unknown: Type = Type.Named(SymbolId(-102))

        given CanEqual[Type, Type] = CanEqual.canEqualAny
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

        /** True when any node in the subtree (including this node) satisfies `p`. Pre-order short-circuits on first match. */
        def exists(p: Tree => Boolean): Boolean =
            p(this) || children.exists(_.exists(p))

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

        /** Recursive-this reference (RECthis tag): address of the enclosing Rec frame. */
        final case class RecThisAddr(addr: Int) extends Tree:
            def children: Chunk[Tree] = Chunk.empty

        /** Import selector: the imported name (IMPORTED tag). */
        final case class Imported(qual: Tree) extends Tree:
            def children: Chunk[Tree] = Chunk(qual)

        /** Import rename: the renamed-to name (RENAMED tag). */
        final case class Renamed(name: Name) extends Tree:
            def children: Chunk[Tree] = Chunk.empty

        /** By-name type annotation in type position (BYNAMEtpt tag). */
        final case class ByNameTpt(inner: Type) extends Tree:
            def children: Chunk[Tree] = Chunk.empty

        /** Bounded wildcard type (BOUNDED tag): the bound tree. */
        final case class Bounded(bound: Tree) extends Tree:
            def children: Chunk[Tree] = Chunk(bound)

        /** Explicit type annotation in type position (EXPLICITtpt tag). */
        final case class ExplicitTpt(inner: Type) extends Tree:
            def children: Chunk[Tree] = Chunk.empty

        /** Elided (inferred) type position (ELIDED tag). */
        final case class Elided(inner: Type) extends Tree:
            def children: Chunk[Tree] = Chunk.empty

        /** Type-position reference by name and qualifier (TYPEREF tag). */
        final case class TypeRefTree(qual: Tree, name: Name) extends Tree:
            def children: Chunk[Tree] = Chunk(qual)

        /** Term-position path-dependent reference (F-B-004). Wire tag TERMREFin (174).
          *
          * prefix is the qualifier tree (encoded as Tree.Ident(name, qualType)). name identifies the
          * referenced member. Replaces the fabricated Tree.Select placeholder from Phase 05.
          */
        final case class TermRef(prefix: Tree, name: Name) extends Tree:
            def children: Chunk[Tree] = Chunk(prefix)

        /** Repeated (varargs) sequence literal (F-B-005). Wire tag REPEATED (149).
          *
          * elems are the element trees. tpe is Type.Wildcard(Nothing, Any) as a placeholder until
          * a future phase infers the element type from context.
          * Replaces the fabricated Tree.Apply(Ident("_repeated", ...), trees) placeholder.
          */
        final case class SeqLiteral(elems: Chunk[Tree], tpe: Type) extends Tree:
            def children: Chunk[Tree] = elems

        /** Self type definition in a class template (SELFDEF tag). */
        final case class SelfDef(name: Name, tpe: Tree) extends Tree:
            def children: Chunk[Tree] = Chunk(tpe)

        /** Outer reference (SELECTouter tag): outer class at given level. */
        final case class SelectOuter(qual: Tree, name: Name, levels: Int, tpe: Type) extends Tree:
            def children: Chunk[Tree] = Chunk(qual)

        /** Unknown tag -- encountered a tag not covered by this ADT version. */
        final case class Unknown(tag: Int, length: Int) extends Tree:
            def children: Chunk[Tree] = Chunk.empty
    end Tree

    // ── Supporting ADTs for the Symbol hierarchy ────────────────────────────

    /** Variance of a type parameter or abstract type member. */
    enum Variance derives CanEqual:
        case Invariant, Covariant, Contravariant

    /** Lower / upper bounds at the Symbol layer (typed against `Type`, distinct from `Tree.TypeBounds` which is bytecode-level). */
    final case class TypeBounds(lower: Type, upper: Type) derives CanEqual

    /** Visibility of a symbol, derived from `flags`. ScopedPrivate / ScopedProtected indicate the `Local` flag is also set. */
    enum Visibility derives CanEqual:
        case Private, Protected, Public, ScopedPrivate, ScopedProtected

    /** Inheritance-openness level, derived from `flags`. */
    enum OpenLevel derives CanEqual:
        case Open, Default, Sealed, Final

    /** Format selector for `Symbol.show(format)`. */
    enum ShowFormat derives CanEqual:
        case FullyQualified, Simple, Code

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

    /** Sealed-trait root of the typed Symbol hierarchy. Every symbol is one of 14 final case classes; pattern matching is exhaustive under
      * `-Xfatal-warnings`.
      *
      * Constructed by `ClasspathOrchestrator.materializeSymbols` during Pass C of `Classpath.init`. After construction, every field is
      * immutable. Cross-symbol references use `SymbolId` (an opaque Int) rather than direct `Symbol` pointers to avoid case-class cycles.
      *
      * Resolution methods (`owner`, `fullName`, `binaryName`, etc.) require a `Classpath` in scope and are pure data accessors with no
      * effect row.
      */
    sealed trait Symbol derives CanEqual:
        def id: SymbolId
        def name: Name
        def flags: Flags
        def ownerId: SymbolId
        def scaladoc: Maybe[String]
        def sourcePosition: Maybe[Position]

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

        // 40 flag predicates on base trait (identical bodies, preserved per layered/no-restriction rule)
        def isFinal: Boolean       = flags.contains(Flag.Final)
        def isAbstract: Boolean    = flags.contains(Flag.Abstract)
        def isSealed: Boolean      = flags.contains(Flag.Sealed)
        def isCase: Boolean        = flags.contains(Flag.Case)
        def isLazy: Boolean        = flags.contains(Flag.Lazy)
        def isOverride: Boolean    = flags.contains(Flag.Override)
        def isPrivate: Boolean     = flags.contains(Flag.Private)
        def isProtected: Boolean   = flags.contains(Flag.Protected)
        def isPublic: Boolean      = flags.contains(Flag.Public)
        def isStatic: Boolean      = flags.contains(Flag.Static)
        def isMutable: Boolean     = flags.contains(Flag.Mutable)
        def isErased: Boolean      = flags.contains(Flag.Erased)
        def isInfix: Boolean       = flags.contains(Flag.Infix)
        def isOpen: Boolean        = flags.contains(Flag.Open)
        def isTransparent: Boolean = flags.contains(Flag.Transparent)

        /** Symbol marked as a macro method.
          *
          * F-E-006 fix: dotty emits Flag.Macro on enum-case synthetic methods (ordinal, productElement, etc.). These are NOT
          * user-defined macros. Excluding symbols that also carry Flag.Synthetic avoids false positives. Real macro methods
          * (defined with the `macro` keyword or `inline`) are not synthetic. The `isInstanceOf[Symbol.Method]` gate
          * prevents non-method symbols from matching even if they somehow carry Flag.Macro.
          *
          * Deviation from Phase 11 plan AFTER snippet: the plan used `Symbol.EnumCase` pattern match which does not exist
          * until Phase 13. This implementation uses `!flags.contains(Flag.Synthetic)` as a conservative substitute;
          * see phase-11/decisions.md.
          */
        def isMacro: Boolean =
            flags.contains(Flag.Macro) && !flags.contains(Flag.Synthetic) && this.isInstanceOf[Symbol.Method]
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

        /** Symbol marked as a `given` instance.
          *
          * F-E-004 fix: using-clause parameters also carry Flag.Given but are NOT `given` instances in the user-facing sense. Excluding
          * `isParameter` prevents false positives for every `using foo: Foo` parameter.
          */
        def isGiven: Boolean      = flags.contains(Flag.Given) && !isParameter
        def isContextual: Boolean = flags.contains(Flag.Given) // alias retained per layered/no-restriction
        def isOpaque: Boolean     = flags.contains(Flag.Opaque)

        // 14 kind discriminators computed structurally
        def isPackage: Boolean        = this.isInstanceOf[Symbol.Package]
        def isClass: Boolean          = this.isInstanceOf[Symbol.Class]
        def isTrait: Boolean          = this.isInstanceOf[Symbol.Trait]
        def isObject: Boolean         = this.isInstanceOf[Symbol.Object]
        def isMethod: Boolean         = this.isInstanceOf[Symbol.Method]
        def isField: Boolean          = this.isInstanceOf[Symbol.Field]
        def isVal: Boolean            = this.isInstanceOf[Symbol.Val]
        def isVar: Boolean            = this.isInstanceOf[Symbol.Var]
        def isTypeAlias: Boolean      = this.isInstanceOf[Symbol.TypeAlias]
        def isOpaqueTypeKind: Boolean = this.isInstanceOf[Symbol.OpaqueType]
        def isAbstractType: Boolean   = this.isInstanceOf[Symbol.AbstractType]
        def isTypeParam: Boolean      = this.isInstanceOf[Symbol.TypeParam]
        def isParameter: Boolean      = this.isInstanceOf[Symbol.Parameter]
        def isUnresolved: Boolean     = this.isInstanceOf[Symbol.Unresolved]

        // 5 composite predicates
        def isClassLike: Boolean = this.isInstanceOf[Symbol.ClassLike]
        def isTypeLike: Boolean  = this.isInstanceOf[Symbol.TypeLike]
        def isTerm: Boolean      = this.isInstanceOf[Symbol.TermLike]
        def isCaseClass: Boolean = this match
            case c: Symbol.Class => c.flags.contains(Flag.Case);
            case _               => false
        def isCaseObject: Boolean = this match
            case o: Symbol.Object => o.flags.contains(Flag.Case);
            case _                => false

        // SymbolKind retained for callers that use `sym.kind`
        def kind: SymbolKind = this match
            case _: Symbol.Package => SymbolKind.Package
            // EnumCase extends Class; check EnumCase before Class so the subtype takes priority.
            case _: Symbol.EnumCase     => SymbolKind.EnumCase
            case _: Symbol.Class        => SymbolKind.Class
            case _: Symbol.Trait        => SymbolKind.Trait
            case _: Symbol.Object       => SymbolKind.Object
            case _: Symbol.Method       => SymbolKind.Method
            case _: Symbol.Field        => SymbolKind.Field
            case _: Symbol.Val          => SymbolKind.Val
            case _: Symbol.Var          => SymbolKind.Var
            case _: Symbol.TypeAlias    => SymbolKind.TypeAlias
            case _: Symbol.OpaqueType   => SymbolKind.OpaqueType
            case _: Symbol.AbstractType => SymbolKind.AbstractType
            case _: Symbol.TypeParam    => SymbolKind.TypeParam
            case _: Symbol.Parameter    => SymbolKind.Parameter
            case _: Symbol.Unresolved   => SymbolKind.Unresolved

        // Resolution accessors common to every subtype
        def owner(using cp: Classpath): Symbol = cp.symbol(ownerId)

        def companion(using cp: Classpath): Maybe[Symbol] = cp.companion(this)

        def fullName(using cp: Classpath): Name = cp.fullName(this)

        def binaryName(using cp: Classpath): String =
            kyo.internal.tasty.symbol.BinaryName.compute(this, cp)

        def show(using cp: Classpath): String = kyo.internal.tasty.symbol.SymbolShow.show(this, cp)

        // Typed grouped queries derived from flags
        def visibility: Visibility =
            val priv = flags.contains(Flag.Private)
            val prot = flags.contains(Flag.Protected)
            val loc  = flags.contains(Flag.Local)
            (priv, prot, loc) match
                case (true, _, true) => Visibility.ScopedPrivate
                case (_, true, true) => Visibility.ScopedProtected
                case (true, _, _)    => Visibility.Private
                case (_, true, _)    => Visibility.Protected
                case _               => Visibility.Public
            end match
        end visibility

        def openLevel: OpenLevel =
            if flags.contains(Flag.Final) then OpenLevel.Final
            else if flags.contains(Flag.Sealed) then OpenLevel.Sealed
            else if flags.contains(Flag.Open) then OpenLevel.Open
            else OpenLevel.Default

        /** Dotted fully-qualified name as a `String`. Mirrors `fullName(using cp).asString`. */
        def fullNameString(using cp: Classpath): String =
            import Name.asString
            fullName.asString

        /** Simple name as a `String`. Mirrors `name.asString`. */
        def simpleName: String =
            import Name.asString
            name.asString

        /** Owners-chain self-first walk; depth bound 64; visited-set guards cycles. Returns `Chunk(self, owner1, ..., root)`. */
        def ownersChain(using cp: Classpath): Chunk[Symbol] =
            val out     = Chunk.newBuilder[Symbol]
            val visited = new java.util.HashSet[Int]()
            var cur     = this: Symbol
            var depth   = 0
            var stop    = false
            while !stop && depth < 64 && visited.add(cur.id.value) do
                out += cur
                val ownerSym = cp.symbol(cur.ownerId)
                if ownerSym.id == cur.id || ownerSym.id.value == -1 then stop = true
                else
                    cur = ownerSym
                    depth += 1
                end if
            end while
            out.result()
        end ownersChain

        /** Direct parent symbol of this symbol. `Absent` if `ownerId == -1` or the owner is the sentinel. */
        def directParent(using cp: Classpath): Maybe[Symbol] =
            if ownerId.value == -1 then Maybe.Absent
            else Maybe(cp.symbol(ownerId))

        /** Subtype-aware annotation query. ClassLike / Method / Val / Var / TypeAlias / OpaqueType / AbstractType / Parameter walk both
          * Scala and Java annotation lists; Field walks `javaAnnotations` only; TypeParam / Package / Unresolved return `false`.
          */
        def hasAnnotation(annotationFqn: String)(using cp: Classpath): Boolean =
            def matchScala(a: Annotation): Boolean =
                import Name.asString
                cp.typeFqnString(a.annotationType) == annotationFqn
            def matchJava(a: JavaAnnotation): Boolean =
                import Name.asString
                cp.fullName(a.annotationClass).asString == annotationFqn
            this match
                case c: Symbol.Class        => c.annotations.exists(matchScala) || c.javaAnnotations.exists(matchJava)
                case t: Symbol.Trait        => t.annotations.exists(matchScala) || t.javaAnnotations.exists(matchJava)
                case o: Symbol.Object       => o.annotations.exists(matchScala) || o.javaAnnotations.exists(matchJava)
                case m: Symbol.Method       => m.annotations.exists(matchScala)
                case v: Symbol.Val          => v.annotations.exists(matchScala)
                case w: Symbol.Var          => w.annotations.exists(matchScala)
                case f: Symbol.Field        => f.javaAnnotations.exists(matchJava)
                case t: Symbol.TypeAlias    => t.annotations.exists(matchScala)
                case t: Symbol.OpaqueType   => t.annotations.exists(matchScala)
                case t: Symbol.AbstractType => t.annotations.exists(matchScala)
                case p: Symbol.Parameter    => p.annotations.exists(matchScala)
                case _                      => false
            end match
        end hasAnnotation

        /** Subtype-aware annotation getter; first Scala match preferred, then first Java match. */
        def getAnnotation(annotationFqn: String)(using cp: Classpath): Maybe[Annotation | JavaAnnotation] =
            def matchScala(a: Annotation): Boolean =
                import Name.asString
                cp.typeFqnString(a.annotationType) == annotationFqn
            def matchJava(a: JavaAnnotation): Boolean =
                import Name.asString
                cp.fullName(a.annotationClass).asString == annotationFqn
            this match
                case c: Symbol.Class =>
                    Maybe(c.annotations.find(matchScala).orElse(c.javaAnnotations.find(matchJava)).orNull)
                case t: Symbol.Trait =>
                    Maybe(t.annotations.find(matchScala).orElse(t.javaAnnotations.find(matchJava)).orNull)
                case o: Symbol.Object =>
                    Maybe(o.annotations.find(matchScala).orElse(o.javaAnnotations.find(matchJava)).orNull)
                case m: Symbol.Method       => Maybe(m.annotations.find(matchScala).orNull)
                case v: Symbol.Val          => Maybe(v.annotations.find(matchScala).orNull)
                case w: Symbol.Var          => Maybe(w.annotations.find(matchScala).orNull)
                case f: Symbol.Field        => Maybe(f.javaAnnotations.find(matchJava).orNull)
                case t: Symbol.TypeAlias    => Maybe(t.annotations.find(matchScala).orNull)
                case t: Symbol.OpaqueType   => Maybe(t.annotations.find(matchScala).orNull)
                case t: Symbol.AbstractType => Maybe(t.annotations.find(matchScala).orNull)
                case p: Symbol.Parameter    => Maybe(p.annotations.find(matchScala).orNull)
                case _                      => Maybe.Absent
            end match
        end getAnnotation

        /** Human-readable signature. For Method: `def name[Tps](p1: T1, ...): R`. For Class / Trait / Object: `kind name[Tps] extends
          * parents`. For Val / Var / Field: `kind name: T`. For TypeAlias / OpaqueType: `name = body`. Other subtypes return `simpleName`.
          */
        def signature(using cp: Classpath): String =
            kyo.internal.tasty.symbol.SymbolSignature.compute(this, cp)

        /** Format-selectable show. `FullyQualified` returns `fullNameString`; `Simple` returns `simpleName`; `Code` returns `signature`. */
        def show(format: ShowFormat)(using cp: Classpath): String = format match
            case ShowFormat.FullyQualified => fullNameString
            case ShowFormat.Simple         => simpleName
            case ShowFormat.Code           => signature

        /** Direct declarations only (for ClassLike); empty for non-classlike. */
        def declaredMembers(using cp: Classpath): Chunk[Symbol] = this match
            case c: Symbol.ClassLike => c.declarations
            case _                   => Chunk.empty

        /** Declarations of this symbol plus all inherited declarations from parent ClassLikes, deduplicated by simple name keeping the
          * most-specific occurrence. Non-ClassLike receivers return empty.
          */
        def allMembers(using cp: Classpath): Chunk[Symbol] = this match
            case c: Symbol.ClassLike =>
                val seen = scala.collection.mutable.HashSet.empty[String]
                val out  = Chunk.newBuilder[Symbol]
                def visit(cl: Symbol.ClassLike): Unit =
                    cl.declarations.foreach: d =>
                        val nm = d.simpleName
                        if seen.add(nm) then out += d
                    cl.parents.foreach(visit)
                end visit
                visit(c)
                out.result()
            case _ => Chunk.empty

        /** Find a direct declaration by simple name. */
        def findDeclaredMember(name: String)(using cp: Classpath): Maybe[Symbol] =
            Maybe(declaredMembers.find(_.simpleName == name).orNull)

        /** Find an inherited (parent-or-deeper) declaration by simple name. Not direct. */
        def findInheritedMember(name: String)(using cp: Classpath): Maybe[Symbol] = this match
            case c: Symbol.ClassLike =>
                val directOpt = declaredMembers.find(_.simpleName == name)
                val allOpt    = allMembers.find(_.simpleName == name)
                (directOpt, allOpt) match
                    case (None, Some(m))    => Maybe(m)
                    case (Some(d), Some(m)) => if d eq m then Maybe.Absent else Maybe(m)
                    case _                  => Maybe.Absent
                end match
            case _ => Maybe.Absent

        /** Find any (direct or inherited) member by simple name. */
        def findAnyMember(name: String)(using cp: Classpath): Maybe[Symbol] =
            Maybe(allMembers.find(_.simpleName == name).orNull)

        // Resolution accessors preserved from the flat Symbol API (used by existing callers)

        /** Resolve the direct parent symbols by extracting only `Type.Named` entries from `parentTypes`. */
        def parents(using cp: Classpath): Chunk[Symbol] =
            (this match
                case c: Symbol.ClassLike => c.parentTypes
                case _                   => Chunk.empty
            ).collect { case Type.Named(pid) => cp.symbol(pid) }

        /** Resolve the type parameter symbols recorded during Pass C. */
        def typeParams(using cp: Classpath): Chunk[Symbol] =
            (this match
                case c: Symbol.ClassLike   => c.typeParamIds
                case m: Symbol.Method      => m.typeParamIds
                case ta: Symbol.TypeAlias  => ta.typeParamIds
                case ot: Symbol.OpaqueType => ot.typeParamIds
                case _                     => Chunk.empty
            ).map(cp.symbol)

        /** Resolve all direct member symbols (declarations) of this symbol. */
        def declarations(using cp: Classpath): Chunk[Symbol] =
            (this match
                case c: Symbol.ClassLike => c.declarationIds
                case p: Symbol.Package   => p.memberIds
                case _                   => Chunk.empty
            ).map(cp.symbol)

        /** Resolve the permitted direct subclasses for sealed / enum symbols. Returns `Absent` when this symbol has no sealed subclass
          * list.
          */
        def permittedSubclasses(using cp: Classpath): Maybe[Chunk[Symbol]] =
            (this match
                case c: Symbol.Class => c.permittedSubclassIds
                case t: Symbol.Trait => t.permittedSubclassIds
                case _               => Maybe.Absent
            ).map(_.map(cp.symbol))

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

        /** Find a direct member by simple string name. */
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

    end Symbol

    /** Companion of `Symbol`; carries the intermediate sealed traits, the 14 final case classes, and the Phase 01-only bridge factories. */
    object Symbol:

        // ── Intermediate sealed traits ────────────────────────────────────────

        /** Type-system layer marker (ClassLike, TypeAlias, OpaqueType, AbstractType, TypeParam). */
        sealed trait TypeLike extends Symbol

        /** Term layer marker (Method, Val, Var, Field, Parameter). */
        sealed trait TermLike extends Symbol

        /** Common Class / Trait / Object contract: raw fields plus typed resolution accessors.
          *
          * Raw fields are the data as decoded from TASTy/classfile bytes. Typed accessors resolve SymbolId references via the implicit
          * Classpath and return narrowed Chunk types (Chunk[Method], Chunk[Val], etc.) per INV-005. The base-Symbol accessors (methods,
          * vals, etc.) that return Chunk[Symbol] remain on Symbol and still work for flat-Symbol callers; these overrides narrow the return
          * type for ClassLike-typed references via Chunk covariance.
          */
        sealed trait ClassLike extends TypeLike:
            def javaMetadata: Maybe[JavaMetadata]
            def parentTypes: Chunk[Type]
            def typeParamIds: Chunk[SymbolId]
            def declarationIds: Chunk[SymbolId]
            def annotations: Chunk[Annotation]
            def javaAnnotations: Chunk[JavaAnnotation]
            def body: Maybe[SymbolBody]

            /** Resolve direct parent ClassLike symbols, collecting only Type.Named entries from parentTypes. */
            override def parents(using cp: Classpath): Chunk[ClassLike] =
                parentTypes.flatMap:
                    case Type.Named(pid) =>
                        cp.symbol(pid) match
                            case c: ClassLike => Chunk(c)
                            case _            => Chunk.empty
                    case _ => Chunk.empty

            /** Resolve type parameter symbols recorded for this classlike. */
            override def typeParams(using cp: Classpath): Chunk[TypeParam] =
                typeParamIds.flatMap: id =>
                    cp.symbol(id) match
                        case t: TypeParam => Chunk(t)
                        case _            => Chunk.empty

            /** All direct declarations of this classlike as an unfiltered Chunk[Symbol]. */
            override def declarations(using cp: Classpath): Chunk[Symbol] = declarationIds.map(cp.symbol)

            /** All method-kind declarations of this classlike. */
            override def methods(using cp: Classpath): Chunk[Method] =
                declarationIds.flatMap: id =>
                    cp.symbol(id) match
                        case m: Method => Chunk(m)
                        case _         => Chunk.empty

            /** All constructor declarations (name == "<init>") of this classlike. */
            def constructors(using cp: Classpath): Chunk[Method] =
                import Name.asString
                methods.filter(m => m.name.asString == "<init>")

            /** All val-kind declarations of this classlike. */
            override def vals(using cp: Classpath): Chunk[Val] =
                declarationIds.flatMap: id =>
                    cp.symbol(id) match
                        case v: Val => Chunk(v)
                        case _      => Chunk.empty

            /** All var-kind declarations of this classlike. */
            override def vars(using cp: Classpath): Chunk[Var] =
                declarationIds.flatMap: id =>
                    cp.symbol(id) match
                        case v: Var => Chunk(v)
                        case _      => Chunk.empty

            /** All field-kind declarations (Java-only) of this classlike. */
            override def fields(using cp: Classpath): Chunk[Field] =
                declarationIds.flatMap: id =>
                    cp.symbol(id) match
                        case f: Field => Chunk(f)
                        case _        => Chunk.empty

            /** All nested class, trait, and object declarations of this classlike. */
            override def nestedTypes(using cp: Classpath): Chunk[ClassLike] =
                declarationIds.flatMap: id =>
                    cp.symbol(id) match
                        case c: ClassLike => Chunk(c)
                        case _            => Chunk.empty

            /** All type alias declarations of this classlike. */
            def typeAliases(using cp: Classpath): Chunk[TypeAlias] =
                declarationIds.flatMap: id =>
                    cp.symbol(id) match
                        case t: TypeAlias => Chunk(t)
                        case _            => Chunk.empty

            /** All abstract type declarations of this classlike. */
            def abstractTypes(using cp: Classpath): Chunk[AbstractType] =
                declarationIds.flatMap: id =>
                    cp.symbol(id) match
                        case t: AbstractType => Chunk(t)
                        case _               => Chunk.empty

            /** All opaque type declarations of this classlike. */
            def opaqueTypes(using cp: Classpath): Chunk[OpaqueType] =
                declarationIds.flatMap: id =>
                    cp.symbol(id) match
                        case t: OpaqueType => Chunk(t)
                        case _             => Chunk.empty

            /** Resolve the companion of this classlike (companion object for a Class or Trait; companion class for an Object). */
            override def companion(using cp: Classpath): Maybe[Symbol] = cp.companion(this)

            /** Find every direct declaration whose simple name equals `name`. The singular `findMember` returns the first match. */
            def findMembers(name: String)(using cp: Classpath): Chunk[Symbol] =
                import Name.asString
                declarations.filter(_.name.asString == name)

            /** Find the first direct declaration whose simple name equals `name`. */
            override def findMember(name: String)(using cp: Classpath): Maybe[Symbol] =
                import Name.asString
                declarations.find(_.name.asString == name) match
                    case Some(s) => Maybe(s)
                    case None    => Maybe.Absent
            end findMember

            /** Find a direct declaration by typed `Name` value. */
            override def findMemberByName(n: Name)(using cp: Classpath): Maybe[Symbol] =
                import Name.given
                declarations.find(_.name == n) match
                    case Some(s) => Maybe(s)
                    case None    => Maybe.Absent
            end findMemberByName

        end ClassLike

        // ── 14 final case classes ─────────────────────────────────────────────

        case class Class private[kyo] (
            id: SymbolId,
            name: Name,
            flags: Flags,
            ownerId: SymbolId,
            scaladoc: Maybe[String],
            sourcePosition: Maybe[Position],
            javaMetadata: Maybe[JavaMetadata],
            parentTypes: Chunk[Type],
            typeParamIds: Chunk[SymbolId],
            declarationIds: Chunk[SymbolId],
            permittedSubclassIds: Maybe[Chunk[SymbolId]],
            annotations: Chunk[Annotation],
            javaAnnotations: Chunk[JavaAnnotation],
            body: Maybe[SymbolBody]
        ) extends ClassLike:
            /** Resolve the permitted direct subclasses for sealed / enum classes. Returns Absent when no sealed subclass list is present.
              */
            override def permittedSubclasses(using cp: Classpath): Maybe[Chunk[ClassLike]] =
                permittedSubclassIds.map: ids =>
                    ids.flatMap: id =>
                        cp.symbol(id) match
                            case c: ClassLike => Chunk(c)
                            case _            => Chunk.empty
        end Class

        /** Enum-case symbol (F-E-007). Represents a single case of a Scala 3 enum.
          *
          * Extends Symbol.Class so that callers treating enum cases as class-like symbols continue to
          * work without changes. The Enum and Case flags are always set on this symbol. ownerId is the
          * enclosing enum sealed class.
          *
          * Pattern-match on Symbol.EnumCase before Symbol.Class in an exhaustive match to specialize
          * enum-case handling. Any Symbol.EnumCase also matches Symbol.Class (it is a subtype).
          *
          * @param id
          *   Unique symbol identifier within this Classpath.
          * @param name
          *   Simple name of this enum case as it appears in source.
          * @param flags
          *   Flags; always includes Flag.Enum and Flag.Case.
          * @param ownerId
          *   SymbolId of the enclosing enum class.
          * @param parentTypes
          *   Parent types (the enum class itself is always among them).
          */
        // Scala 3 prohibits case-to-case inheritance; EnumCase is a plain final class extending Class.
        // It has structural equals/hashCode/toString manually implemented to match case-class behavior.
        // Use the companion object unapply for pattern matching: case Symbol.EnumCase(id, name, ...).
        final class EnumCase private[kyo] (
            override val id: SymbolId,
            override val name: Name,
            override val flags: Flags,
            override val ownerId: SymbolId,
            override val scaladoc: Maybe[String],
            override val sourcePosition: Maybe[Position],
            override val javaMetadata: Maybe[JavaMetadata],
            override val parentTypes: Chunk[Type],
            override val typeParamIds: Chunk[SymbolId],
            override val declarationIds: Chunk[SymbolId],
            override val permittedSubclassIds: Maybe[Chunk[SymbolId]],
            override val annotations: Chunk[Annotation],
            override val javaAnnotations: Chunk[JavaAnnotation],
            override val body: Maybe[SymbolBody]
        ) extends Class(
                id,
                name,
                flags,
                ownerId,
                scaladoc,
                sourcePosition,
                javaMetadata,
                parentTypes,
                typeParamIds,
                declarationIds,
                permittedSubclassIds,
                annotations,
                javaAnnotations,
                body
            ):
            override def permittedSubclasses(using cp: Classpath): Maybe[Chunk[ClassLike]] =
                permittedSubclassIds.map: ids =>
                    ids.flatMap: id =>
                        cp.symbol(id) match
                            case c: ClassLike => Chunk(c)
                            case _            => Chunk.empty
            override def equals(that: Any): Boolean = that match
                case t: EnumCase => id == t.id
                case _           => false
            override def hashCode(): Int = id.hashCode
            override def toString: String =
                import Name.asString
                s"Symbol.EnumCase(${id.value}, ${name.asString})"
        end EnumCase

        object EnumCase:
            private[kyo] def apply(
                id: SymbolId,
                name: Name,
                flags: Flags,
                ownerId: SymbolId,
                scaladoc: Maybe[String],
                sourcePosition: Maybe[Position],
                javaMetadata: Maybe[JavaMetadata],
                parentTypes: Chunk[Type],
                typeParamIds: Chunk[SymbolId],
                declarationIds: Chunk[SymbolId],
                permittedSubclassIds: Maybe[Chunk[SymbolId]],
                annotations: Chunk[Annotation],
                javaAnnotations: Chunk[JavaAnnotation],
                body: Maybe[SymbolBody]
            ): EnumCase =
                new EnumCase(
                    id,
                    name,
                    flags,
                    ownerId,
                    scaladoc,
                    sourcePosition,
                    javaMetadata,
                    parentTypes,
                    typeParamIds,
                    declarationIds,
                    permittedSubclassIds,
                    annotations,
                    javaAnnotations,
                    body
                )
            def unapply(e: EnumCase): Some[SymbolId] = Some(e.id)
        end EnumCase

        final case class Trait private[kyo] (
            id: SymbolId,
            name: Name,
            flags: Flags,
            ownerId: SymbolId,
            scaladoc: Maybe[String],
            sourcePosition: Maybe[Position],
            javaMetadata: Maybe[JavaMetadata],
            parentTypes: Chunk[Type],
            typeParamIds: Chunk[SymbolId],
            declarationIds: Chunk[SymbolId],
            permittedSubclassIds: Maybe[Chunk[SymbolId]],
            annotations: Chunk[Annotation],
            javaAnnotations: Chunk[JavaAnnotation],
            body: Maybe[SymbolBody]
        ) extends ClassLike:
            /** Resolve the permitted direct subclasses for sealed traits. Returns Absent when no sealed subclass list is present. */
            override def permittedSubclasses(using cp: Classpath): Maybe[Chunk[ClassLike]] =
                permittedSubclassIds.map: ids =>
                    ids.flatMap: id =>
                        cp.symbol(id) match
                            case c: ClassLike => Chunk(c)
                            case _            => Chunk.empty
        end Trait

        final case class Object private[kyo] (
            id: SymbolId,
            name: Name,
            flags: Flags,
            ownerId: SymbolId,
            scaladoc: Maybe[String],
            sourcePosition: Maybe[Position],
            javaMetadata: Maybe[JavaMetadata],
            parentTypes: Chunk[Type],
            typeParamIds: Chunk[SymbolId],
            declarationIds: Chunk[SymbolId],
            annotations: Chunk[Annotation],
            javaAnnotations: Chunk[JavaAnnotation],
            body: Maybe[SymbolBody]
        ) extends ClassLike

        final case class Method private[kyo] (
            id: SymbolId,
            name: Name,
            flags: Flags,
            ownerId: SymbolId,
            scaladoc: Maybe[String],
            sourcePosition: Maybe[Position],
            declaredType: Maybe[Type],
            paramListIds: Chunk[Chunk[SymbolId]],
            typeParamIds: Chunk[SymbolId],
            annotations: Chunk[Annotation],
            body: Maybe[SymbolBody],
            javaMetadata: Maybe[JavaMetadata]
        ) extends TermLike:

            /** Resolve parameter lists, narrowing each element to Symbol.Parameter. */
            def paramLists(using cp: Classpath): Chunk[Chunk[Parameter]] =
                paramListIds.map: list =>
                    list.flatMap: id =>
                        cp.symbol(id) match
                            case p: Parameter => Chunk(p)
                            case _            => Chunk.empty

            /** Resolve type parameter symbols for this method. */
            override def typeParams(using cp: Classpath): Chunk[TypeParam] =
                typeParamIds.flatMap: id =>
                    cp.symbol(id) match
                        case tp: TypeParam => Chunk(tp)
                        case _             => Chunk.empty

            /** The return type derived from `declaredType`.
              *
              * When `declaredType` is `Type.Function(params, result, isContext)` or `Type.ContextFunction(params, result)`, returns
              * `result`. For any other declared type shape the value is returned as-is. Best-effort per Q-002 resolution: a method whose
              * type is not yet a Function (e.g., a Scala 2 stub) returns the raw declared type.
              */
            def returnType(using cp: Classpath): Maybe[Type] =
                declaredType.map:
                    case Type.Function(_, result, _)     => result
                    case Type.ContextFunction(_, result) => result
                    case other                           => other

            /** True when this method is a constructor (name == "<init>"). */
            def isConstructor: Boolean =
                import Name.asString
                name.asString == "<init>"

            /** Decode the body bytes into a Tree, memoizing the result. Returns Absent when no body is present. */
            def bodyTree(using cp: Classpath, frame: Frame): Maybe[Tree] < (Sync & Abort[TastyError]) =
                cp.decodeBody(this)

        end Method

        final case class Val private[kyo] (
            id: SymbolId,
            name: Name,
            flags: Flags,
            ownerId: SymbolId,
            scaladoc: Maybe[String],
            sourcePosition: Maybe[Position],
            declaredType: Maybe[Type],
            annotations: Chunk[Annotation],
            body: Maybe[SymbolBody]
        ) extends TermLike:

            /** Decode the body bytes into a Tree, memoizing the result. Returns Absent when no body is present. */
            def bodyTree(using cp: Classpath, frame: Frame): Maybe[Tree] < (Sync & Abort[TastyError]) =
                cp.decodeBody(this)

        end Val

        final case class Var private[kyo] (
            id: SymbolId,
            name: Name,
            flags: Flags,
            ownerId: SymbolId,
            scaladoc: Maybe[String],
            sourcePosition: Maybe[Position],
            declaredType: Maybe[Type],
            annotations: Chunk[Annotation],
            body: Maybe[SymbolBody]
        ) extends TermLike:

            /** Decode the body bytes into a Tree, memoizing the result. Returns Absent when no body is present. */
            def bodyTree(using cp: Classpath, frame: Frame): Maybe[Tree] < (Sync & Abort[TastyError]) =
                cp.decodeBody(this)

        end Var

        final case class Field private[kyo] (
            id: SymbolId,
            name: Name,
            flags: Flags,
            ownerId: SymbolId,
            scaladoc: Maybe[String],
            sourcePosition: Maybe[Position],
            declaredType: Maybe[Type],
            javaMetadata: Maybe[JavaMetadata],
            javaAnnotations: Chunk[JavaAnnotation]
        ) extends TermLike:

            /** True when the JVM field access flags include ACC_PUBLIC (0x0001). Returns false when javaMetadata is absent. */
            def isJvmPublic: Boolean =
                javaMetadata.map(m => (m.accessFlags & 0x0001) != 0).getOrElse(false)

            /** True when the JVM field access flags include ACC_PRIVATE (0x0002). Returns false when javaMetadata is absent. */
            def isJvmPrivate: Boolean =
                javaMetadata.map(m => (m.accessFlags & 0x0002) != 0).getOrElse(false)

            /** True when the JVM field access flags include ACC_PROTECTED (0x0004). Returns false when javaMetadata is absent. */
            def isJvmProtected: Boolean =
                javaMetadata.map(m => (m.accessFlags & 0x0004) != 0).getOrElse(false)

            /** True when the JVM field access flags include ACC_STATIC (0x0008). Returns false when javaMetadata is absent. */
            def isJvmStatic: Boolean =
                javaMetadata.map(m => (m.accessFlags & 0x0008) != 0).getOrElse(false)

            /** True when the JVM field access flags include ACC_FINAL (0x0010). Returns false when javaMetadata is absent. */
            def isJvmFinal: Boolean =
                javaMetadata.map(m => (m.accessFlags & 0x0010) != 0).getOrElse(false)

        end Field

        final case class TypeAlias private[kyo] (
            id: SymbolId,
            name: Name,
            flags: Flags,
            ownerId: SymbolId,
            scaladoc: Maybe[String],
            sourcePosition: Maybe[Position],
            body: Type,
            typeParamIds: Chunk[SymbolId],
            annotations: Chunk[Annotation]
        ) extends TypeLike:

            /** Resolve the type parameter symbols of this type alias. */
            override def typeParams(using cp: Classpath): Chunk[TypeParam] =
                typeParamIds.flatMap: id =>
                    cp.symbol(id) match
                        case tp: TypeParam => Chunk(tp)
                        case _             => Chunk.empty

        end TypeAlias

        final case class OpaqueType private[kyo] (
            id: SymbolId,
            name: Name,
            flags: Flags,
            ownerId: SymbolId,
            scaladoc: Maybe[String],
            sourcePosition: Maybe[Position],
            body: Type,
            bounds: TypeBounds,
            typeParamIds: Chunk[SymbolId],
            annotations: Chunk[Annotation]
        ) extends TypeLike:

            /** Resolve the type parameter symbols of this opaque type. */
            override def typeParams(using cp: Classpath): Chunk[TypeParam] =
                typeParamIds.flatMap: id =>
                    cp.symbol(id) match
                        case tp: TypeParam => Chunk(tp)
                        case _             => Chunk.empty

        end OpaqueType

        final case class AbstractType private[kyo] (
            id: SymbolId,
            name: Name,
            flags: Flags,
            ownerId: SymbolId,
            scaladoc: Maybe[String],
            sourcePosition: Maybe[Position],
            bounds: TypeBounds,
            annotations: Chunk[Annotation]
        ) extends TypeLike

        final case class TypeParam private[kyo] (
            id: SymbolId,
            name: Name,
            flags: Flags,
            ownerId: SymbolId,
            sourcePosition: Maybe[Position],
            bounds: TypeBounds,
            variance: Variance
        ) extends TypeLike:
            def scaladoc: Maybe[String] = Maybe.Absent

            /** The variance sigil as a one-character String: `""` for Invariant, `"+"` for Covariant, `"-"` for Contravariant. */
            def varianceLabel: String = variance match
                case Variance.Invariant     => ""
                case Variance.Covariant     => "+"
                case Variance.Contravariant => "-"

        end TypeParam

        final case class Parameter private[kyo] (
            id: SymbolId,
            name: Name,
            flags: Flags,
            ownerId: SymbolId,
            sourcePosition: Maybe[Position],
            declaredType: Type,
            defaultArgId: Maybe[SymbolId],
            annotations: Chunk[Annotation]
        ) extends TermLike:
            def scaladoc: Maybe[String] = Maybe.Absent

            /** Resolve the default argument symbol for this parameter, if any. */
            def defaultArg(using cp: Classpath): Maybe[Symbol] =
                defaultArgId.map(cp.symbol)

            /** True when this parameter is an implicit / given parameter. */
            def isImplicit: Boolean = flags.contains(Flag.Given)

            /** True when this parameter's declared type is a by-name type (Type.ByName wrapper). */
            def isByName: Boolean =
                declaredType match
                    case _: Type.ByName => true
                    case _              => false

            /** True when this parameter's declared type is a repeated (varargs) type (Type.Repeated wrapper). */
            def isRepeated: Boolean =
                declaredType match
                    case _: Type.Repeated => true
                    case _                => false

        end Parameter

        final case class Package private[kyo] (
            id: SymbolId,
            name: Name,
            flags: Flags,
            ownerId: SymbolId,
            memberIds: Chunk[SymbolId]
        ) extends Symbol:
            def scaladoc: Maybe[String]         = Maybe.Absent
            def sourcePosition: Maybe[Position] = Maybe.Absent

            /** All direct member symbols of this package as an unfiltered Chunk[Symbol]. */
            def members(using cp: Classpath): Chunk[Symbol] = memberIds.map(cp.symbol)

            /** All class members of this package. */
            def classes(using cp: Classpath): Chunk[Class] =
                memberIds.flatMap: id =>
                    cp.symbol(id) match
                        case c: Class => Chunk(c)
                        case _        => Chunk.empty

            /** All trait members of this package. */
            def traits(using cp: Classpath): Chunk[Trait] =
                memberIds.flatMap: id =>
                    cp.symbol(id) match
                        case t: Trait => Chunk(t)
                        case _        => Chunk.empty

            /** All object members of this package. */
            def objects(using cp: Classpath): Chunk[Object] =
                memberIds.flatMap: id =>
                    cp.symbol(id) match
                        case o: Object => Chunk(o)
                        case _         => Chunk.empty

            /** All class-like members (classes, traits, objects) of this package. */
            def classLike(using cp: Classpath): Chunk[ClassLike] =
                memberIds.flatMap: id =>
                    cp.symbol(id) match
                        case c: ClassLike => Chunk(c)
                        case _            => Chunk.empty

            /** All sub-package members of this package. */
            def subpackages(using cp: Classpath): Chunk[Package] =
                memberIds.flatMap: id =>
                    cp.symbol(id) match
                        case p: Package => Chunk(p)
                        case _          => Chunk.empty

        end Package

        /** An unresolved or placeholder symbol.
          *
          * `flags` always equals `Flags.empty` in practice; the default is intentional so that call sites that construct a minimal sentinel
          * (id, name, ownerId) do not need to supply flags explicitly. The `copy` method will preserve `Flags.empty` when flags is omitted,
          * which is the correct behavior for unresolved symbols.
          */
        final case class Unresolved private[kyo] (
            id: SymbolId,
            name: Name,
            ownerId: SymbolId,
            flags: Flags = Flags.empty
        ) extends Symbol:
            def scaladoc: Maybe[String]         = Maybe.Absent
            def sourcePosition: Maybe[Position] = Maybe.Absent
        end Unresolved

        // ── Placeholder factory ────────────────────────────────────────────────

        /** Produce a placeholder typed Symbol with `id=SymbolId(-1)` and only `kind`, `flags`, and `name` populated.
          *
          * All relational fields are left at empty defaults. Pass C replaces placeholder symbols with fully-populated ones via
          * `materializeSymbols`. Used by AstUnpickler, TypeUnpickler, TreeUnpickler, ClassfileUnpickler, JavaAnnotationUnpickler, and the
          * `InternalSymbol.makeSymbol` shim.
          *
          * Delegates to `TypedSymbolFactory.from` with a minimal descriptor so that `isClass` / `isTrait` / `isObject` predicates return
          * the correct value on placeholder symbols.
          */
        private[kyo] def makePlaceholder(kind: SymbolKind, flags: Flags, name: Name): Symbol =
            import kyo.internal.tasty.symbol.SymbolDescriptor
            import kyo.internal.tasty.symbol.TypedSymbolFactory
            TypedSymbolFactory.from(new SymbolDescriptor(
                id = -1,
                kind = kind,
                flags = flags,
                name = name,
                ownerId = -1,
                declaredType = Maybe.Absent,
                scaladoc = Maybe.Absent,
                sourcePosition = Maybe.Absent,
                javaMetadata = Maybe.Absent,
                parentTypes = Chunk.empty,
                typeParamIds = Chunk.empty,
                declarationIds = Chunk.empty,
                permittedSubclassIds = Maybe.Absent,
                body = Maybe.Absent
            ))
        end makePlaceholder

    end Symbol

    // ── Pickle (in-memory TASTy + classfile bytes) ──────────────────────────

    final case class Pickle(uuid: String, version: Version, bytes: Chunk[Byte]):
        /** Human-readable summary: `Pickle(<uuid> v<version> <n>B)`. */
        def show: String = s"Pickle($uuid v${version.show} ${bytes.length}B)"
    end Pickle

    // ── Classpath ───────────────────────────────────────────────────────────

    /** Immutable snapshot of a fully-loaded TASTy classpath.
      *
      * All fields are plain immutable values populated once during `init` and never mutated. Reading any field after `init` returns is a
      * pure operation with no effect row and no `AllowUnsafe`. The sole exception is `decodeBody`, which decodes AST bytes on demand and
      * carries `Sync & Abort[TastyError]`.
      *
      * Constructor is `private[Tasty]`; instances are obtained exclusively via `Classpath.init`, `Classpath.initCached`,
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

        /** Look up any symbol by fully-qualified dotted name.
          *
          * Pure O(1) lookup in the immutable fqnIndex. Returns `Absent` if the FQN is not registered. For typed lookups that narrow to a
          * specific subtype, use `findClass`, `findTrait`, `findObject`, `findClassLike`, or `findPackage`.
          */
        def findSymbol(fqn: String): Maybe[Symbol] =
            fqnIndex.get(fqn) match
                case Some(id) => Maybe(symbol(id))
                case None     => Maybe.Absent

        /** Look up a class symbol by fully-qualified dotted name.
          *
          * Pure O(1) lookup in the immutable fqnIndex. Returns `Absent` if the FQN is not registered or resolves to a non-Class symbol
          * (e.g., a Trait or Object). Use `findClassLike` to match any class-like symbol regardless of subtype.
          *
          * Includes sealed abstract classes (e.g. `scala.Option`); use `findConcreteClass` to restrict to non-abstract classes.
          *
          * Example:
          * {{{
          *   val sym: Maybe[Symbol.Class] = cp.findClass("scala.collection.List")
          * }}}
          */
        def findClass(fqn: String): Maybe[Symbol.Class] =
            fqnIndex.get(fqn) match
                case Some(id) =>
                    symbol(id) match
                        case c: Symbol.Class => Maybe(c)
                        case _               => Maybe.Absent
                case None => Maybe.Absent

        /** Look up a concrete (non-abstract) class symbol by fully-qualified dotted name.
          *
          * Returns `Absent` when the FQN is not registered, when the symbol is not a Class, or when the matched Class has the Abstract
          * flag set (e.g. `scala.Option`, `scala.Either`). Use `findClass` when abstract classes are acceptable.
          *
          * Q-004 layered addition: `findClass` remains permissive per HARD RULE 4; this method is the narrow accessor for callers that
          * need a concrete, instantiable class.
          *
          * Example:
          * {{{
          *   cp.findConcreteClass("scala.Some")    // Present(_)
          *   cp.findConcreteClass("scala.Option")  // Absent (abstract)
          * }}}
          */
        def findConcreteClass(fqn: String): Maybe[Symbol.Class] =
            findClass(fqn).filter(!_.isAbstract)

        /** Count of type references that could not be resolved to a final SymbolId after all resolution passes.
          *
          * Nonzero values indicate cross-file TYPEREFsymbol targets not found in the loaded classpath
          * (e.g., JDK types when no JDK roots are passed to Classpath.init). This metric provides
          * visibility into how many Named(-1) sentinels remain in parentTypes after the cross-file
          * resolution pass.
          *
          * Note: a count > 0 is expected behavior when the classpath does not include all transitive
          * dependencies. It is not an error condition.
          */
        def unresolvedTypeReferenceCount: Int =
            val sentinelId = Classpath.sentinelUnresolved.id.value
            symbols.foldLeft(0): (acc, sym) =>
                sym match
                    case c: Symbol.ClassLike =>
                        acc + c.parentTypes.count:
                            case Type.Named(id) => id.value == sentinelId
                            case _              => false
                    case _ => acc
        end unresolvedTypeReferenceCount

        /** Look up a trait symbol by fully-qualified dotted name.
          *
          * Returns `Absent` when the FQN resolves to a non-Trait symbol. Use `findClassLike` for the broader case.
          */
        def findTrait(fqn: String): Maybe[Symbol.Trait] =
            fqnIndex.get(fqn) match
                case Some(id) =>
                    symbol(id) match
                        case t: Symbol.Trait => Maybe(t)
                        case _               => Maybe.Absent
                case None => Maybe.Absent

        /** Look up an object symbol by fully-qualified dotted name.
          *
          * Returns `Absent` when the FQN resolves to a non-Object symbol.
          */
        def findObject(fqn: String): Maybe[Symbol.Object] =
            fqnIndex.get(fqn) match
                case Some(id) =>
                    symbol(id) match
                        case o: Symbol.Object => Maybe(o)
                        case _                => Maybe.Absent
                case None => Maybe.Absent

        /** Look up a class-like symbol (Class, Trait, or Object) by fully-qualified dotted name.
          *
          * Returns `Absent` when the FQN resolves to a Package or other non-ClassLike symbol.
          */
        def findClassLike(fqn: String): Maybe[Symbol.ClassLike] =
            fqnIndex.get(fqn) match
                case Some(id) =>
                    symbol(id) match
                        case c: Symbol.ClassLike => Maybe(c)
                        case _                   => Maybe.Absent
                case None => Maybe.Absent

        /** Look up a package symbol by fully-qualified dotted name.
          *
          * Pure O(1) lookup in the immutable packageIndex. Returns `Absent` if the package is not in this classpath.
          */
        def findPackage(fqn: String): Maybe[Symbol.Package] =
            packageIndex.get(fqn) match
                case Some(id) =>
                    symbol(id) match
                        case p: Symbol.Package => Maybe(p)
                        case _                 => Maybe.Absent
                case None => Maybe.Absent

        /** Find all `Symbol.Class` instances whose simple name equals `simpleName`.
          *
          * Linear scan over all symbols. Returns an empty Chunk when no match is found.
          */
        def findClassByName(simpleName: String): Chunk[Symbol.Class] =
            import Name.asString
            symbols.flatMap: s =>
                s match
                    case c: Symbol.Class if c.name.asString == simpleName => Chunk(c)
                    case _                                                => Chunk.empty
        end findClassByName

        /** All package symbols in this classpath.
          *
          * Pure accessor over the immutable `packageIds` Chunk. Each id is resolved and narrowed to `Symbol.Package`; ids that resolve to
          * non-Package symbols are excluded.
          */
        def packages: Chunk[Symbol.Package] =
            packageIds.flatMap: id =>
                symbol(id) match
                    case p: Symbol.Package => Chunk(p)
                    case _                 => Chunk.empty

        /** All top-level class-like symbols (not packages) in this classpath.
          *
          * Pure accessor over the immutable `topLevelClassIds` Chunk. Each id is resolved and narrowed to `Symbol.ClassLike`; ids that
          * resolve to non-ClassLike symbols are excluded.
          */
        def topLevelClasses: Chunk[Symbol.ClassLike] =
            topLevelClassIds.flatMap: id =>
                symbol(id) match
                    case c: Symbol.ClassLike => Chunk(c)
                    case _                   => Chunk.empty

        /** Look up a JPMS module descriptor by module name (e.g., "java.base").
          *
          * Returns `Present(descriptor)` if a `module-info.class` with the given module name was found in the classpath roots. Returns
          * `Absent` if no matching module was found.
          *
          * Pure O(1) lookup in the immutable moduleIndex.
          */
        def findModule(name: String): Maybe[ModuleDescriptor] =
            Maybe(moduleIndex.get(name).orNull)

        /** All JPMS module descriptors loaded into this classpath.
          *
          * Pure O(n) accessor over the immutable `moduleIndex` values where n is the number of loaded `module-info.class` files.
          */
        def modules: Chunk[ModuleDescriptor] =
            Chunk.from(moduleIndex.values.toSeq)

        /** Find a class symbol by JVM binary name (e.g., "com/example/Foo$Inner").
          *
          * Converts the binary name to a dotted FQN and delegates to `findClass`. Returns `Maybe[Symbol.Class]`.
          *
          * Pure O(1) lookup; no I/O.
          */
        def findClassByBinary(binaryName: String): Maybe[Symbol.Class] =
            val fqn = binaryName.replace('/', '.').replace('$', '.')
            findClass(fqn)

        // ── require* throwing variants (INV-010: sole new effect-row additions in this phase) ──

        /** Require a class by FQN; fails with `TastyError.NotFound` when absent or when the symbol is not a Class. */
        def requireClass(fqn: String)(using Frame): Symbol.Class < Abort[TastyError] =
            findClass(fqn) match
                case Maybe.Present(c) => c
                case Maybe.Absent     => Abort.fail(TastyError.NotFound(fqn))

        /** Require a trait by FQN; fails with `TastyError.NotFound` when absent or when the symbol is not a Trait. */
        def requireTrait(fqn: String)(using Frame): Symbol.Trait < Abort[TastyError] =
            findTrait(fqn) match
                case Maybe.Present(t) => t
                case Maybe.Absent     => Abort.fail(TastyError.NotFound(fqn))

        /** Require an object by FQN; fails with `TastyError.NotFound` when absent or when the symbol is not an Object. */
        def requireObject(fqn: String)(using Frame): Symbol.Object < Abort[TastyError] =
            findObject(fqn) match
                case Maybe.Present(o) => o
                case Maybe.Absent     => Abort.fail(TastyError.NotFound(fqn))

        /** Require a class-like by FQN; fails with `TastyError.NotFound` when absent or when the symbol is not a ClassLike. */
        def requireClassLike(fqn: String)(using Frame): Symbol.ClassLike < Abort[TastyError] =
            findClassLike(fqn) match
                case Maybe.Present(c) => c
                case Maybe.Absent     => Abort.fail(TastyError.NotFound(fqn))

        /** Require a package by FQN; fails with `TastyError.NotFound` when absent or when the symbol is not a Package. */
        def requirePackage(fqn: String)(using Frame): Symbol.Package < Abort[TastyError] =
            findPackage(fqn) match
                case Maybe.Present(p) => p
                case Maybe.Absent     => Abort.fail(TastyError.NotFound(fqn))

        /** Require a JPMS module descriptor by name; fails with `TastyError.NotFound` when absent. */
        def requireModule(name: String)(using Frame): ModuleDescriptor < Abort[TastyError] =
            findModule(name) match
                case Maybe.Present(m) => m
                case Maybe.Absent     => Abort.fail(TastyError.NotFound(name))

        // ── typed Classpath-wide all* aggregations ──

        /** All ClassLike symbols (Class, Trait, Object, EnumCase) at any nesting depth.
          *
          * F-G-006 fix: the prior implementation returned `Chunk[Symbol.Class]`, excluding Trait and Object. Widening to `Symbol.ClassLike`
          * restores the invariant `allClasses.size >= topLevelClasses.size`. The return type widening is additive per HARD RULE 4 (ClassLike
          * is a supertype of Class; existing code that pattern-matches on `Symbol.Class` continues to work over the subset).
          */
        def allClasses: Chunk[Symbol.ClassLike] =
            symbols.flatMap { case c: Symbol.ClassLike => Chunk(c); case _ => Chunk.empty }

        /** All Trait symbols in the classpath. Linear scan. */
        def allTraits: Chunk[Symbol.Trait] =
            symbols.flatMap { case t: Symbol.Trait => Chunk(t); case _ => Chunk.empty }

        /** All Object symbols in the classpath. Linear scan. */
        def allObjects: Chunk[Symbol.Object] =
            symbols.flatMap { case o: Symbol.Object => Chunk(o); case _ => Chunk.empty }

        /** All ClassLike symbols (Class, Trait, Object) in the classpath. Linear scan. */
        def allClassLike: Chunk[Symbol.ClassLike] =
            symbols.flatMap { case c: Symbol.ClassLike => Chunk(c); case _ => Chunk.empty }

        /** All Method symbols in the classpath. Linear scan. */
        def allMethods: Chunk[Symbol.Method] =
            symbols.flatMap { case m: Symbol.Method => Chunk(m); case _ => Chunk.empty }

        /** All Val symbols in the classpath. Linear scan. */
        def allVals: Chunk[Symbol.Val] =
            symbols.flatMap { case v: Symbol.Val => Chunk(v); case _ => Chunk.empty }

        /** All Var symbols in the classpath. Linear scan. */
        def allVars: Chunk[Symbol.Var] =
            symbols.flatMap { case v: Symbol.Var => Chunk(v); case _ => Chunk.empty }

        /** All Field symbols (Java-level) in the classpath. Linear scan. */
        def allFields: Chunk[Symbol.Field] =
            symbols.flatMap { case f: Symbol.Field => Chunk(f); case _ => Chunk.empty }

        /** All TypeAlias symbols in the classpath. Linear scan. */
        def allTypeAliases: Chunk[Symbol.TypeAlias] =
            symbols.flatMap { case t: Symbol.TypeAlias => Chunk(t); case _ => Chunk.empty }

        /** All OpaqueType symbols in the classpath. Linear scan. */
        def allOpaqueTypes: Chunk[Symbol.OpaqueType] =
            symbols.flatMap { case t: Symbol.OpaqueType => Chunk(t); case _ => Chunk.empty }

        /** All AbstractType symbols in the classpath. Linear scan. */
        def allAbstractTypes: Chunk[Symbol.AbstractType] =
            symbols.flatMap { case t: Symbol.AbstractType => Chunk(t); case _ => Chunk.empty }

        /** All TypeParam symbols in the classpath. Linear scan. */
        def allTypeParams: Chunk[Symbol.TypeParam] =
            symbols.flatMap { case t: Symbol.TypeParam => Chunk(t); case _ => Chunk.empty }

        /** All Parameter symbols in the classpath. Linear scan. */
        def allParameters: Chunk[Symbol.Parameter] =
            symbols.flatMap { case p: Symbol.Parameter => Chunk(p); case _ => Chunk.empty }

        /** All Package symbols in the classpath. Linear scan. */
        def allPackages: Chunk[Symbol.Package] =
            symbols.flatMap { case p: Symbol.Package => Chunk(p); case _ => Chunk.empty }

        /** All Unresolved symbols in the classpath. Linear scan. */
        def allUnresolved: Chunk[Symbol.Unresolved] =
            symbols.flatMap { case u: Symbol.Unresolved => Chunk(u); case _ => Chunk.empty }

        /** All symbols carrying the Scala or Java annotation whose fully-qualified name is `annotationFqn`.
          *
          * Checks Scala `annotations` (via `Annotation.annotationType`: must be `Type.Named(id)` whose FQN matches `annotationFqn`) and
          * Java `javaAnnotations` (via `JavaAnnotation.annotationClass` FQN). Symbols that carry neither field (TypeParam, Package,
          * Unresolved) are excluded.
          */
        def symbolsAnnotatedWith(annotationFqn: String): Chunk[Symbol] =
            import Name.asString
            symbols.filter: sym =>
                val scalaMatch: Boolean = sym match
                    case c: Symbol.ClassLike     => c.annotations.exists(ann => annotationFqnMatches(ann, annotationFqn))
                    case m: Symbol.Method        => m.annotations.exists(ann => annotationFqnMatches(ann, annotationFqn))
                    case v: Symbol.Val           => v.annotations.exists(ann => annotationFqnMatches(ann, annotationFqn))
                    case w: Symbol.Var           => w.annotations.exists(ann => annotationFqnMatches(ann, annotationFqn))
                    case ta: Symbol.TypeAlias    => ta.annotations.exists(ann => annotationFqnMatches(ann, annotationFqn))
                    case ot: Symbol.OpaqueType   => ot.annotations.exists(ann => annotationFqnMatches(ann, annotationFqn))
                    case at: Symbol.AbstractType => at.annotations.exists(ann => annotationFqnMatches(ann, annotationFqn))
                    case p: Symbol.Parameter     => p.annotations.exists(ann => annotationFqnMatches(ann, annotationFqn))
                    case _                       => false
                val javaMatch: Boolean = sym match
                    case c: Symbol.ClassLike => c.javaAnnotations.exists(ja => fullName(ja.annotationClass).asString == annotationFqn)
                    case f: Symbol.Field     => f.javaAnnotations.exists(ja => fullName(ja.annotationClass).asString == annotationFqn)
                    case _                   => false
                scalaMatch || javaMatch
        end symbolsAnnotatedWith

        private def annotationFqnMatches(ann: Annotation, fqn: String): Boolean =
            import Name.asString
            typeFqnString(ann.annotationType) == fqn
        end annotationFqnMatches

        /** Reconstruct a dotted FQN string from a Type.Named or Type.TermRef tycon, or empty string when unavailable.
          *
          * Used by annotation FQN matching to support both Type.Named(id) and Type.TermRef(qual, name) tycon forms.
          * F-G-001 fix: annotation tycons decoded from TYPEREF wire tag arrive as Type.TermRef.
          */
        private[Tasty] def typeFqnString(t: Type): String =
            import Name.asString
            t match
                case Type.Named(id) => fullName(symbol(id)).asString
                case Type.TermRef(qual, name) =>
                    val q = typeFqnString(qual)
                    if q.nonEmpty then q + "." + name.asString else name.asString
                case Type.TypeRef(qual, name) =>
                    // F-A-009: TYPEREF now emits TypeRef; annotation FQN matching must handle both forms.
                    val q = typeFqnString(qual)
                    if q.nonEmpty then q + "." + name.asString else name.asString
                case Type.Applied(base, _) =>
                    // F-I-003 fix: @Child[T] enrichment wraps the TermRef tycon in Applied(tycon, Chunk(T)).
                    // For FQN matching, use the unapplied base type.
                    typeFqnString(base)
                case _ => ""
            end match
        end typeFqnString

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
            val maybeBody: Maybe[SymbolBody] = sym match
                case c: Symbol.Class  => c.body
                case t: Symbol.Trait  => t.body
                case o: Symbol.Object => o.body
                case m: Symbol.Method => m.body
                case v: Symbol.Val    => v.body
                case w: Symbol.Var    => w.body
                case _                => Maybe.Absent
            maybeBody match
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
            end match
        end decodeBody

        /** Package-private memo size for test verification. NOT part of the public API. */
        private[kyo] def bodyMemoSize: Int = bodyMemo.size()

        /** All direct `ClassLike` subclasses of `sym` (one hop, from the subclass index).
          *
          * Pure O(k) lookup where k is the number of direct subclasses. Returns an empty Chunk when `sym` has no registered subclasses.
          * Non-ClassLike entries in the index are silently excluded (defensive; should not occur in well-formed classpath data).
          */
        def directSubclassesOf(sym: Symbol.ClassLike): Chunk[Symbol.ClassLike] =
            subclassIndex.getOrElse(sym.id, Chunk.empty).flatMap: id =>
                symbol(id) match
                    case c: Symbol.ClassLike => Chunk(c)
                    case _                   => Chunk.empty

        /** All transitive `ClassLike` subclasses of `sym` (BFS closure over the subclass index).
          *
          * Returns an empty Chunk when `sym` has no registered subclasses. The BFS visited set prevents infinite loops on malformed
          * (cyclic) classpath data.
          */
        def subclassesOf(sym: Symbol.ClassLike): Chunk[Symbol.ClassLike] =
            transitiveClassLikeSubclasses(sym)

        /** All concrete `Symbol.Class` instances that are transitive subclasses of `sym` and not abstract.
          *
          * Equivalent to `subclassesOf(sym).collect { case c: Symbol.Class if !c.isAbstract => c }`.
          */
        def implementationsOf(sym: Symbol.ClassLike): Chunk[Symbol.Class] =
            subclassesOf(sym).flatMap:
                case c: Symbol.Class if !c.isAbstract => Chunk(c)
                case _                                => Chunk.empty

        private def transitiveClassLikeSubclasses(root: Symbol): Chunk[Symbol.ClassLike] =
            val visited = scala.collection.mutable.HashSet.empty[SymbolId]
            val out     = Chunk.newBuilder[Symbol.ClassLike]
            val queue   = scala.collection.mutable.Queue(root.id)
            while queue.nonEmpty do
                val curId = queue.dequeue()
                subclassIndex.getOrElse(curId, Chunk.empty).foreach: childId =>
                    if visited.add(childId) then
                        symbol(childId) match
                            case c: Symbol.ClassLike =>
                                out += c
                                queue.enqueue(childId)
                            case _ =>
            end while
            out.result()
        end transitiveClassLikeSubclasses

    end Classpath

    object Classpath:

        /** Sentinel symbol returned by `Classpath.symbol` for out-of-range or unassigned ids. */
        val sentinelUnresolved: Symbol =
            Symbol.Unresolved(SymbolId(-1), Name("<unresolved>"), SymbolId(-1))

        /** Init a classpath from directory/file roots using `ErrorMode.SoftFail` (errors accumulate in `cp.errors`).
          *
          * Effect row rationale:
          *   - `Async`: parallel per-file decode across the workgroup (subsumes Sync).
          *   - `Scope`: registers a finalizer that closes JAR pools and mmap arenas on scope exit.
          *   - `Abort[TastyError]`: fatal errors (classpath build state, snapshot mismatch).
          *
          * One-arg variant: delegates to the canonical two-arg form with `ErrorMode.SoftFail`.
          */
        def init(roots: Seq[String])(using Frame): Classpath < (Async & Scope & Abort[TastyError]) =
            init(roots, ErrorMode.SoftFail)

        /** Init a classpath from directory/file roots with the given `ErrorMode`.
          *
          * Effect row rationale:
          *   - `Async`: parallel per-file decode across the workgroup (subsumes Sync).
          *   - `Scope`: registers a finalizer that closes JAR pools and mmap arenas on scope exit.
          *   - `Abort[TastyError]`: fatal errors (classpath build state, snapshot mismatch).
          *
          * `ErrorMode.SoftFail`: decode errors accumulate in `cp.errors`; classpath is returned. `ErrorMode.FailFast`: any decode error
          * immediately raises `Abort[TastyError]`.
          */
        def init(roots: Seq[String], mode: ErrorMode)(using Frame): Classpath < (Async & Scope & Abort[TastyError]) =
            initImpl(roots, mode)

        /** Init the classpath and additionally pre-load JDK `module-info.class` entries from the JDK module image.
          *
          * Q-006 / F-D-001: opt-in JDK auto-discovery. On JVM, reads module-info.class files for all JDK modules from the `jrt:/`
          * virtual filesystem (mounted automatically by the JVM) and merges them into the returned classpath's `moduleIndex`. After the
          * call, `cp.findModule("java.base")` and other JDK modules resolve. On Scala.js and Scala Native, `jrt:/` is not available;
          * this method fails with `TastyError.UnsupportedPlatform`.
          *
          * Effect row: identical to `init` (Async + Scope + Abort[TastyError]).
          */
        def initWithPlatformModules(roots: Seq[String])(using Frame): Classpath < (Async & Scope & Abort[TastyError]) =
            initWithPlatformModulesFiltered(roots, Set.empty)

        /** Variant of `initWithPlatformModules` that walks only the specified JPMS modules from the `jrt:/` filesystem.
          *
          * When `moduleFilter` is non-empty, only the named modules (e.g. `Set("java.base")`) are scanned for classfiles. This reduces
          * decode time from ~27,000 classfiles (all JDK modules) to ~7,000 (java.base only), making it suitable for test fixtures. The
          * production `initWithPlatformModules` always passes an empty filter, which walks all modules.
          *
          * HARD RULE 7: the returned Classpath is immutable; this overload does not weaken that invariant.
          */
        private[kyo] def initWithPlatformModulesFiltered(
            roots: Seq[String],
            moduleFilter: Set[String]
        )(using Frame): Classpath < (Async & Scope & Abort[TastyError]) =
            // F-A3-001..004 fix: prepend every `.class` path under `jrt:/modules/<m>/...` to the user's
            // roots so JDK class symbols decode alongside user TASTy. The shape of `roots` is preserved
            // (a Seq[String] of file-system paths); the new entries use the `jrt:/` URI scheme that
            // JvmFileSource already handles. PlatformModuleOps.listJdkClassFiles is JVM-only; JS/Native
            // return Chunk.empty so this method degrades to the module-descriptor-only path.
            val jdkClassFiles =
                // Unsafe: AllowUnsafe.embrace.danger for the lazy jrtFileSystem access in PlatformModuleOps.
                kyo.internal.tasty.query.PlatformModuleOps.listJdkClassFiles(moduleFilter)(using AllowUnsafe.embrace.danger)
            for
                cp         <- init(jdkClassFiles.toSeq ++ roots, ErrorMode.SoftFail)
                jdkModules <- PlatformModuleOps.readJdkModuleDescriptors
            yield cp.copy(moduleIndex = cp.moduleIndex ++ jdkModules)
            end for
        end initWithPlatformModulesFiltered

        /** Init a classpath from directory/file roots, using a snapshot cache in `cacheDir`.
          *
          * On a cache hit (digest match), deserializes the snapshot directly. On a miss, initializes normally then writes a new snapshot.
          *
          * Effect row rationale:
          *   - `Sync`: file I/O for snapshot read/write plus JAR, classfile, and TASTy reads on a cache miss.
          *   - `Async`: parallel per-file decode on a cache miss.
          *   - `Scope`: registers a finalizer that closes JAR pools and mmap arenas on scope exit.
          *   - `Abort[TastyError]`: fatal errors (snapshot mismatch, classpath build failures).
          */
        def initCached(roots: Seq[String], cacheDir: String)(using Frame): Classpath < (Sync & Async & Scope & Abort[TastyError]) =
            initCachedImpl(roots, cacheDir)

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

        /** Internal: init implementation, delegates to ClasspathOrchestrator. */
        private def initImpl(
            roots: Seq[String],
            mode: ErrorMode
        )(using Frame): Classpath < (Sync & Async & Scope & Abort[TastyError]) =
            val source      = PlatformFileSource.get
            val concurrency = Runtime.getRuntime.availableProcessors().max(1)
            TastyStat.scope.traceSpan(
                "coldLoad",
                Attributes.empty.add("roots", roots.size.toString)
            ) {
                ClasspathOrchestrator.init(roots, mode, source, concurrency)
            }
        end initImpl

        private def initCachedImpl(
            roots: Seq[String],
            cacheDir: String
        )(using Frame): Classpath < (Sync & Async & Scope & Abort[TastyError]) =
            val source      = PlatformFileSource.get
            val concurrency = Runtime.getRuntime.availableProcessors().max(1)
            // Compute digest of root metadata
            Abort.run[TastyError](SnapshotDigest.compute(roots, source)).flatMap:
                case Result.Failure(_) =>
                    // Digest computation failed (e.g., browser): fall through to normal init
                    initImpl(roots, ErrorMode.SoftFail)
                case Result.Panic(_) =>
                    initImpl(roots, ErrorMode.SoftFail)
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
                                    // Snapshot unreadable; fall through to normal init
                                    initImpl(roots, ErrorMode.SoftFail)
                        else
                            // No snapshot; init normally then write snapshot
                            initImpl(roots, ErrorMode.SoftFail).flatMap: cp =>
                                Abort.run[TastyError](SnapshotWriter.write(cp, cacheDir, digest, source)).andThen(cp)
        end initCachedImpl

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

    // ── SymbolId re-export ───────────────────────────────────────────────────

    /** Re-export `kyo.internal.tasty.symbol.SymbolId` so callers can write `Tasty.SymbolId` without importing the internal package. */
    type SymbolId = kyo.internal.tasty.symbol.SymbolId

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
