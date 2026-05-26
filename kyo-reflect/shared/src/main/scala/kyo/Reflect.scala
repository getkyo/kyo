package kyo

import kyo.internal.reflect.binary.Utf8
import kyo.internal.reflect.query.ClasspathOrchestrator
import kyo.internal.reflect.query.ClasspathRef
import kyo.internal.reflect.query.PlatformFileSource
import kyo.internal.reflect.snapshot.DigestComputer as SnapshotDigest
import kyo.internal.reflect.snapshot.SnapshotReader
import kyo.internal.reflect.snapshot.SnapshotWriter
import kyo.internal.reflect.symbol.Interner
import kyo.internal.reflect.type_.TypeArena
import scala.collection.immutable.IntMap

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
    private val globalInterner: Interner = new Interner(numShards = 32, initialShardCapacity = 16)

    /** An interned name backed by a byte sequence.
      *
      * The internal representation is `Interner.Entry`, which stores raw UTF-8 bytes and decodes to a `String` lazily via
      * `OnceCell[String]`. Reference equality on two `Name` values implies byte-level equality because the interner guarantees a unique
      * `Entry` per unique byte sequence. The `CanEqual` instance delegates to reference equality, which is therefore correct.
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
                // Unsafe: OnceCell.get() is an unsafe-tier helper; AllowUnsafe is embraced here at the public API boundary.
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
        // Phase 10 flag (bit 44): identifies symbols decoded from Scala 2 pickles embedded in classfiles.
        val Scala2: Flag = Flag(1L << 44, "Scala2")
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

    /** Source position attached to a TASTy symbol.
      *
      * `sourceFile` is the file name from the Attributes section (if present). `line` and `column` are 1-based (line 1 = first line of the
      * file; column 1 = first character of the line). `Absent` for classfile symbols and for TASTy symbols in a file without a Positions
      * section.
      */
    final case class Position(sourceFile: Maybe[String], line: Int, column: Int)

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

        def show: String = this match
            case Named(sym)             => sym.fullName.toString
            case Applied(base, args)    => s"${base.show}[${args.map(_.show).mkString(", ")}]"
            case Array(elem)            => s"${elem.show}[]"
            case Function(ps, r, isCtx) => s"(${ps.map(_.show).mkString(", ")}) ${if isCtx then "?=>" else "=>"} ${r.show}"
            case Tuple(es)              => s"(${es.map(_.show).mkString(", ")})"
            case other                  => other.toString
    end Type

    // ── Tree ADT ────────────────────────────────────────────────────────────

    /** Structural representation of a TASTy expression or definition body.
      *
      * Produced by `Symbol.body` (lazy, memoized). Each case mirrors a TASTy AST tag. Trees may reference `Reflect.Type` and
      * `Reflect.Symbol` values. All sub-trees are strict (no lazy slots); memoization is handled at the `body` accessor level.
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

        /** Unknown tag -- encountered a tag not covered by this ADT version. */
        final case class Unknown(tag: Int, length: Int) extends Tree
    end Tree

    // ── Symbol ──────────────────────────────────────────────────────────────

    final class Symbol private[Reflect] (
        val kind: SymbolKind,
        val flags: Flags,
        val name: Name,
        val owner: Symbol,
        private[kyo] val home: ClasspathRef,
        private[kyo] val origin: Symbol.Origin,
        private[kyo] val javaMetadata: Maybe[JavaMetadata]
    ):
        // Write-once slots populated during classpath orchestration (Phase 3 / Phase 5).
        // Unsafe: SingleAssign is an unsafe-tier helper; callers in mergeResults / ClassfileUnpickler hold AllowUnsafe.
        private[kyo] val _parents: kyo.internal.reflect.symbol.SingleAssign[Chunk[Type]]      = new kyo.internal.reflect.symbol.SingleAssign
        private[kyo] val _typeParams: kyo.internal.reflect.symbol.SingleAssign[Chunk[Symbol]] = new kyo.internal.reflect.symbol.SingleAssign
        private[kyo] val _declarations: kyo.internal.reflect.symbol.SingleAssign[Chunk[Symbol]] =
            new kyo.internal.reflect.symbol.SingleAssign
        private[kyo] val _declaredType: kyo.internal.reflect.symbol.SingleAssign[Type] = new kyo.internal.reflect.symbol.SingleAssign
        private[kyo] val _scaladoc: kyo.internal.reflect.symbol.SingleAssign[Maybe[String]] =
            new kyo.internal.reflect.symbol.SingleAssign
        private[kyo] val _position: kyo.internal.reflect.symbol.SingleAssign[Maybe[Position]] =
            new kyo.internal.reflect.symbol.SingleAssign

        // Lazy body cell: populated on first call to Symbol.body. Not a write-once slot because the
        // computation is driven by the caller, not by classpath orchestration. OnceCell handles thread safety.
        // Unsafe: OnceCell is an unsafe-tier helper; AllowUnsafe is embraced at the body accessor boundary.
        // The init lambda may throw TreeUnpickler.DecodeException for corrupt byte slices; body() catches it.
        private[kyo] val _bodyOnce: kyo.internal.reflect.symbol.OnceCell[Tree] =
            new kyo.internal.reflect.symbol.OnceCell[Tree](() =>
                // This init lambda is called at most once per symbol. TreeUnpickler.decodeSync throws
                // TreeUnpickler.DecodeException on corrupt/truncated slices; body() catches and wraps it.
                // Unsafe: AllowUnsafe is needed for TastyOrigin.addrMap SingleAssign read.
                import AllowUnsafe.embrace.danger
                origin match
                    case Reflect.Symbol.JavaOrigin =>
                        throw new kyo.internal.reflect.tasty.TreeUnpickler.DecodeException(
                            "body not available for Java symbols"
                        )
                    case o: Reflect.Symbol.TastyOrigin =>
                        kyo.internal.reflect.tasty.TreeUnpickler.decodeSync(o, this)
                end match
            )

        // Pure accessors (no effect, always present even after classpath close).
        def fullName: Name        = Symbol.computeFullName(this)
        def binaryName: String    = Symbol.computeBinaryName(this)
        def isInline: Boolean     = flags.contains(Flag.Inline)
        def isContextual: Boolean = flags.contains(Flag.Given)
        def isOpaque: Boolean     = flags.contains(Flag.Opaque)
        def isPackageObject: Boolean =
            // Unsafe: OnceCell.get() is an unsafe-tier helper; AllowUnsafe is embraced here at the public API boundary.
            import AllowUnsafe.embrace.danger
            flags.contains(Flag.Module) && name.string.get() == "package"
        end isPackageObject
        def isModule: Boolean = flags.contains(Flag.Module)
        def isJava: Boolean   = flags.contains(Flag.JavaDefined)

        /** The scaladoc comment associated with this symbol, if any.
          *
          * Returns `Present(text)` for TASTy symbols with a scaladoc comment decoded from the Comments section. Returns `Absent` for TASTy
          * symbols without a comment, for Java-sourced classfile symbols (classfiles have no Comments section), and for symbols whose home
          * classpath has not yet been loaded. This is a pure accessor: it reads from a pre-populated write-once slot with no classpath I/O.
          */
        def scaladoc: Maybe[String] =
            // Unsafe: SingleAssign.get() is an unsafe-tier helper; AllowUnsafe is embraced here at the public accessor boundary.
            import AllowUnsafe.embrace.danger
            if _scaladoc.isSet then _scaladoc.get()
            else Maybe.Absent
        end scaladoc

        /** The source position of this symbol, if known.
          *
          * Returns `Present(pos)` for TASTy symbols with position data decoded from the Positions section. Returns `Absent` for
          * Java-sourced classfile symbols (classfiles have no TASTy Positions section), for TASTy symbols in files without a Positions
          * section, and for symbols whose home classpath has not yet been loaded. This is a pure accessor: it reads from a pre-populated
          * write-once slot with no classpath I/O.
          */
        def position: Maybe[Position] =
            // Unsafe: SingleAssign.get() is an unsafe-tier helper; AllowUnsafe is embraced here at the public accessor boundary.
            import AllowUnsafe.embrace.danger
            if _position.isSet then _position.get()
            else Maybe.Absent
        end position

        // Resolving accessors (return ReflectError.NotImplemented in Phase 0).

        /** The declared type of this symbol.
          *
          * Returns the type annotation decoded from the symbol's TASTy or classfile definition:
          *   - For a VALDEF (val/var field): the declared type (e.g. Int, String).
          *   - For a PARAM: the parameter type.
          *   - For a TYPEPARAM: the type parameter bounds (Type.Wildcard or Type.Named).
          *   - For a TYPEDEF (type alias or abstract type): the alias body or bounds type.
          *   - For a class/trait/object TYPEDEF: Type.Named(sym) (the class type itself).
          *   - For a DEFDEF: the return type (reconstructed in mergeResults from Pass 1 data).
          *   - For Package symbols: throws IllegalArgumentException (pure accessor; programmer error).
          * @note
          *   Implemented in v2 Phase 5. Populated eagerly during Pass 1 / mergeResults. Pure in v3 Phase 3.
          */
        def declaredType: Type =
            if kind == SymbolKind.Package then
                throw new IllegalArgumentException("Symbol.declaredType is not available for Package symbols")
            else
                // Unsafe: SingleAssign.get() is an unsafe-tier helper; AllowUnsafe is embraced here at the public accessor boundary.
                // Reading immutable Ready-state data populated during open, before any user access.
                import AllowUnsafe.embrace.danger
                _declaredType.get()

        /** The parent types of this symbol (superclass and mixed-in traits).
          *
          * Pure accessor: reads from an immutable write-once slot populated during classpath open. Valid after `open` returns.
          */
        def parents: Chunk[Type] =
            // Unsafe: SingleAssign.get() is an unsafe-tier helper; AllowUnsafe is embraced here at the public accessor boundary.
            // Reading immutable Ready-state data set during open, before any user access.
            import AllowUnsafe.embrace.danger
            _parents.get()
        end parents

        /** The type parameters of this symbol.
          *
          * Pure accessor: reads from an immutable write-once slot populated during classpath open. Valid after `open` returns.
          */
        def typeParams: Chunk[Symbol] =
            // Unsafe: SingleAssign.get() is an unsafe-tier helper; AllowUnsafe is embraced here at the public accessor boundary.
            // Reading immutable Ready-state data set during open, before any user access.
            import AllowUnsafe.embrace.danger
            _typeParams.get()
        end typeParams

        /** The member declarations of this symbol (methods, fields, nested types).
          *
          * Pure accessor: reads from an immutable write-once slot populated during classpath open. Valid after `open` returns.
          */
        def declarations: Chunk[Symbol] =
            // Unsafe: SingleAssign.get() is an unsafe-tier helper; AllowUnsafe is embraced here at the public accessor boundary.
            // Reading immutable Ready-state data set during open, before any user access.
            import AllowUnsafe.embrace.danger
            _declarations.get()
        end declarations

        /** The companion object symbol of this class or trait, if one exists.
          *
          * For a `Class` or `Trait` symbol, looks up the companion object via FQN `owner.fqn + "." + name + "$"`. For an `Object` symbol,
          * looks up the companion class via the owner FQN and the simple name with any trailing `$` stripped. Java symbols always return
          * `Absent`. All other kinds return `Absent`.
          *
          * Pure accessor: reads from the fqnIndex HashMap in the immutable Ready state via AllowUnsafe. Valid after `open` returns.
          */
        def companion: Maybe[Symbol] =
            if isJava then Maybe.Absent
            else if !home.isAssigned then Maybe.Absent
            else
                import Name.asString
                // Unsafe: reading immutable Ready-state fqnIndex via AllowUnsafe; populated during open, before any user access.
                // Helper: true when the owner is null or is the synthetic root-package sentinel
                // (identified by owner.owner eq owner, i.e. the root owns itself).
                // For root-owned or unowned symbols the owner FQN is empty, so we use the
                // symbol's own fullName to form the companion FQN rather than concatenating
                // an empty prefix (which would produce ".ClassName$").
                def isRootOwner: Boolean = owner == null || (owner.owner eq owner)
                kind match
                    case SymbolKind.Class | SymbolKind.Trait =>
                        // Companion object FQN uses the "$"-suffixed key convention established in fqnIndex.
                        // fqnIndex stores Object-kind symbols under "OwnerFqn.SimpleName$".
                        val companionFqn =
                            if isRootOwner then fullName.asString + "$"
                            else owner.fullName.asString + "." + name.asString + "$"
                        home.get().pureClass(companionFqn) match
                            case Present(s) if s.kind == SymbolKind.Object => Maybe(s)
                            case _                                         => Maybe.Absent
                    case SymbolKind.Object =>
                        // Companion class FQN: owner FQN + simple name without trailing "$".
                        // The simple name may or may not end in "$" depending on TASTy encoding;
                        // strip it and look up the class symbol by the plain dotted FQN.
                        val simpleName = name.asString.stripSuffix("$")
                        val companionFqn =
                            if isRootOwner then simpleName
                            else owner.fullName.asString + "." + simpleName
                        home.get().pureClass(companionFqn) match
                            case Present(s) if s.kind == SymbolKind.Class || s.kind == SymbolKind.Trait => Maybe(s)
                            case _                                                                      => Maybe.Absent
                    case _ => Maybe.Absent
                end match

        // Java-specific side door.
        def javaSpecific: Maybe[JavaMetadata] = javaMetadata

        /** The body tree of this symbol, decoded lazily from the TASTy body byte slice.
          *
          * Returns the decoded Tree on success. The result is memoized: two consecutive calls return reference-equal Tree values.
          *
          * Fails with:
          *   - `ReflectError.NotImplemented` for Java symbols (classfiles have no body AST).
          *   - `ReflectError.NotImplemented` for Package symbols and any symbol without a body slice (bodyStart == 0).
          *   - `ReflectError.ClasspathClosed` if the classpath has been closed.
          *   - `ReflectError.MalformedSection` if the body byte slice is truncated or contains an unknown tag sequence.
          */
        def body(using Frame): Tree < (Sync & Abort[ReflectError]) =
            import Name.asString
            origin match
                case Symbol.JavaOrigin =>
                    Abort.fail(ReflectError.NotImplemented("body not available for Java symbols"))
                case o: Symbol.TastyOrigin =>
                    if !home.isAssigned then stub("Symbol.body")
                    else
                        home.get().checkOpen.andThen:
                            if o.bodyStart == 0 || o.bodyEnd == 0 || kind == SymbolKind.Package then
                                Abort.fail(ReflectError.NotImplemented("body not available for this symbol kind"))
                            else
                                // Decode via OnceCell to cache; the OnceCell init lambda runs synchronously on first call.
                                // If the decode threw (corrupt bytes), convert to Abort.fail(MalformedSection).
                                // The try/catch runs before entering any kyo effect so exceptions become Either.
                                // Unsafe: OnceCell.get() is an unsafe-tier helper; AllowUnsafe is embraced here.
                                import AllowUnsafe.embrace.danger
                                // Unsafe: Reading classpath state under AllowUnsafe to detect closed classpath
                                // before body decode; state transitions are monotonic (Closed is terminal) so
                                // a stale read returns a conservative result.
                                if home.get().isClosed then
                                    Abort.fail(ReflectError.ClasspathClosed)
                                else
                                    val decoded: Either[ReflectError, Tree] =
                                        try Right(_bodyOnce.get())
                                        catch
                                            case ex: kyo.internal.reflect.tasty.TreeUnpickler.DecodeException =>
                                                Left(ReflectError.MalformedSection(
                                                    "ASTs",
                                                    s"body decode failed for '${name.asString}': ${ex.getMessage}"
                                                ))
                                            case ex: ArrayIndexOutOfBoundsException =>
                                                Left(ReflectError.MalformedSection(
                                                    "ASTs",
                                                    s"body truncated for '${name.asString}': ${ex.getMessage}"
                                                ))
                                            case _: IllegalStateException =>
                                                // Thrown when a mmap-backed ByteView is read after its arena was closed.
                                                Left(ReflectError.ClasspathClosed)
                                    decoded match
                                        case Right(t) => Sync.defer(t)
                                        case Left(e)  => Abort.fail(e)
                                end if
            end match
        end body
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

        /** Origin for a symbol decoded from a TASTy file.
          *
          * @param bodyStart
          *   Absolute byte offset into `sectionBytes` where this symbol's body payload begins. 0 for symbols without a body.
          * @param bodyEnd
          *   Absolute byte offset into `sectionBytes` where this symbol's body payload ends. 0 for symbols without a body.
          * @param sectionBytes
          *   The raw AST section bytes for this file. Shared (not copied) across all symbols from the same file. Empty array for synthetic
          *   symbols. Used by TreeUnpickler to create a ByteView for lazy body decode.
          * @param names
          *   The name table for this file, as decoded by NameUnpickler. Shared across all symbols from the same file. Empty array for
          *   synthetic symbols.
          *
          * The `addrMap` is stored as a write-once slot (`SingleAssign`) populated after Pass1 completes. It maps TASTy byte address to
          * symbol and is used by TreeUnpickler to resolve IDENT/SELECT tree references during lazy body decode. Always Map.empty for
          * synthetic (non-file) symbols.
          */
        final class TastyOrigin(
            val bodyStart: Int,
            val bodyEnd: Int,
            val sectionBytes: Array[Byte],
            val names: Array[Reflect.Name],
            val sectionOffset: Int,
            /** Non-null only for mmap-loaded snapshot origins. When set, TreeUnpickler reads from this view directly instead of
              * constructing a ByteView from sectionBytes. After the backing arena is closed, reads from this view throw
              * IllegalStateException which Symbol.body maps to ReflectError.ClasspathClosed.
              */
            val bodyView: kyo.internal.reflect.binary.ByteView | Null
        ) extends Origin:
            // Write-once: populated by AstUnpickler after pass1 completes. Unsafe: SingleAssign is unsafe-tier.
            private[kyo] val _addrMap: kyo.internal.reflect.symbol.SingleAssign[IntMap[Reflect.Symbol]] =
                new kyo.internal.reflect.symbol.SingleAssign

            def addrMap(using AllowUnsafe): IntMap[Reflect.Symbol] =
                if _addrMap.isSet then _addrMap.get()
                else IntMap.empty

            override def equals(other: Any): Boolean = other match
                case o: TastyOrigin =>
                    bodyStart == o.bodyStart && bodyEnd == o.bodyEnd
                case _ => false
            override def hashCode(): Int = bodyStart * 31 + bodyEnd
        end TastyOrigin

        object TastyOrigin:
            /** Convenience factory for synthetic symbols that have no file bytes or body. */
            def empty: TastyOrigin = new TastyOrigin(0, 0, Array.empty[Byte], Array.empty[Reflect.Name], 0, null)

            /** Pattern match extractor: `case TastyOrigin(bodyStart, bodyEnd)`. */
            def unapply(o: TastyOrigin): Some[(Int, Int)] =
                Some((o.bodyStart, o.bodyEnd))
        end TastyOrigin

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
                    errors = Chunk.empty,
                    moduleIndex = Map.empty
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
                            // Try to load from snapshot using mmap on JVM/Native, heap on JS.
                            kyo.internal.reflect.query.Classpath.allocate.flatMap: cp =>
                                Scope.ensure(Sync.defer(kyo.internal.reflect.query.Classpath.close(cp))).andThen:
                                    Abort.run[ReflectError](SnapshotReader.readMapped(snapshotPath, source, cp)).flatMap:
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

    end Classpath

    extension (cp: Classpath)
        /** Look up a class symbol by fully-qualified dotted name.
          *
          * Pure accessor: reads from the immutable fqnIndex HashMap in Ready state. Valid after `open` returns. After close, returns
          * whatever heap state is there (closed-state enforcement is Body-only, Phase 4).
          */
        def findClass(fqn: String): Maybe[Symbol] = cp.pureClass(fqn)

        /** Look up a package symbol by fully-qualified dotted name.
          *
          * Pure accessor: reads from the immutable packageIndex HashMap in Ready state. Valid after `open` returns.
          */
        def findPackage(fqn: String): Maybe[Symbol] = cp.purePackage(fqn)

        /** All package symbols in this classpath.
          *
          * Pure accessor: reads from the immutable packages Chunk in Ready state. Valid after `open` returns.
          */
        def packages: Chunk[Symbol] = cp.purePackages

        /** All top-level class symbols (not packages) in this classpath.
          *
          * Pure accessor: reads from the immutable topLevelClasses Chunk in Ready state. Valid after `open` returns.
          */
        def topLevelClasses: Chunk[Symbol] = cp.pureTopLevelClasses

        /** Errors accumulated during loading (soft-fail mode).
          *
          * Pure accessor: reads from immutable error state populated after Phase C. Empty for clean classpaths.
          */
        def errors: Chunk[ReflectError] = cp.accumulatedErrors

        /** Look up a JPMS module descriptor by module name (e.g., "java.base").
          *
          * Returns `Present(descriptor)` if a `module-info.class` with the given module name was found in the classpath roots. Returns
          * `Absent` if no matching module was found.
          *
          * Pure accessor: reads from the immutable moduleIndex HashMap in Ready state. Valid after `open` returns.
          */
        def findModule(name: String): Maybe[ModuleDescriptor] = cp.pureModule(name)

        /** Find a class symbol by JVM binary name (e.g., "com/example/Foo$Inner").
          *
          * Converts the binary name to a dotted FQN and delegates to `findClass`.
          *
          * Pure accessor: reads from the immutable fqnIndex HashMap in Ready state. Valid after `open` returns.
          */
        def findClassByBinary(binaryName: String): Maybe[Symbol] =
            val fqn = binaryName.replace('/', '.').replace('$', '.')
            cp.pureClass(fqn)

    end extension

    // ── Type subtyping extension ─────────────────────────────────────────────

    /** Extension method for subtype checking on `Reflect.Type` values.
      *
      * Checks whether `t` is a subtype of `other` using the structural covariant rules implemented in
      * `kyo.internal.reflect.type_.Subtyping`. Parent-chain lookups use the provided `cp` classpath (explicit, per
      * `feedback_no_implicit_handlers`).
      *
      * ==Rec depth budget==
      *
      * A `Rec` type contains a recursive back-reference (`RecThis`). To avoid infinite recursion, each `Rec` unfolding decrements an
      * internal budget counter that starts at 64. If the budget is exhausted before a definitive subtype verdict is reached, the method
      * returns `false` (conservative: not-a-subtype). Normal type hierarchies are nowhere near 64 levels deep; the budget is a safety net
      * for adversarial or machine-generated type structures.
      *
      * @param other
      *   the candidate supertype
      * @param cp
      *   the classpath used for transitive parent-chain resolution
      */
    extension (t: Type)
        /** Check whether `t` is a subtype of `other` using the structural covariant rules in `kyo.internal.reflect.type_.Subtyping`.
          *
          * Pure accessor: parent-chain lookups use the pre-populated `_parents` SingleAssign slots in each Symbol, which are set during
          * classpath open and are immutable thereafter. No classpath I/O is performed.
          *
          * @param other
          *   the candidate supertype
          * @param cp
          *   the classpath used for transitive parent-chain resolution (accessed via pure AllowUnsafe reads)
          */
        def isSubtypeOf(other: Type)(using cp: Classpath): Boolean =
            kyo.internal.reflect.type_.Subtyping.isSubtype(t, other, cp, budget = 64)
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
