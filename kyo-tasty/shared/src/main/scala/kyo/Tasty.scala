package kyo

import kyo.internal.tasty.binary.Utf8
import kyo.internal.tasty.query.Binding
import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.query.DecodeContext
import kyo.internal.tasty.query.PlatformFileSource
import kyo.internal.tasty.query.PlatformModuleOps
import kyo.internal.tasty.query.TastyStat
import kyo.internal.tasty.query.TastyState
import kyo.internal.tasty.snapshot.DigestComputer as SnapshotDigest
import kyo.internal.tasty.snapshot.SnapshotReader
import kyo.internal.tasty.snapshot.SnapshotWriter
import kyo.internal.tasty.symbol.SymbolKind
import kyo.stats.Attributes
import scala.collection.immutable.IntMap

/** kyo-tasty public entry object.
  *
  * The single public namespace for the runtime reflection library. Reading a classpath gives back a `Classpath`
  * snapshot; everything you can ask about a Scala 3 program is reachable through the types nested here.
  *
  * **What lives here.** `Symbol` (and its sealed subtypes) is the declaration model: classes, traits, objects,
  * methods, vals, vars, fields, type aliases, type parameters, parameters, packages. `Type` is the type model:
  * named references, applied constructors, function shapes, intersections and unions, refinements. `Tree` is the
  * AST model returned by `Symbol.bodyTree` for method and val bodies. `Annotation` / `Java.Annotation` cover Scala
  * and JVM annotations as parallel ADTs. `Classpath` is the in-memory index that resolves cross-symbol references
  * and answers subclass / annotation / FQN queries.
  *
  * **Cross-platform.** The library compiles for JVM, JS, and Native; JVM-only paths (mmap, JAR, `jrt:/` JDK
  * auto-discovery) are gated behind platform-specific source folders. The public API surface is identical across
  * platforms; platform-specific behaviour is in the loader, not the model.
  *
  * **Naming.** All types are nested in `object Tasty` (`Tasty.Type`, `Tasty.Symbol`, etc.) to avoid polluting
  * `kyo.*` and to keep separation from `kyo.Structure.Type` (kyo-schema's value-structure type tree). If both
  * `Structure` and `Tasty` are imported in the same file, reference the types qualified (`Structure.Type`,
  * `Tasty.Type`).
  */
object Tasty:

    // ── SymbolId ────────────────────────────────────────────────────────────

    /** Opaque integer handle used to reference a Symbol within a Classpath instance.
      *
      * Values are produced exclusively by Classpath Pass C construction and obtained by users from `Classpath.symbol`,
      * `Classpath.rootSymbolId`, `Classpath.topLevelClassIds`, `Classpath.packageIds`, or from any `SymbolId` field on `Symbol` or `Type`.
      * Outside of `object Tasty`, callers cannot construct a SymbolId from a raw Int.
      *
      * Two SymbolId values produced by the same Classpath compare equal via `==` iff they refer to the same Symbol. SymbolId values are NOT
      * stable across distinct Classpath instances (different `Tasty.withClasspath` calls produce independent id spaces).
      */
    opaque type SymbolId = Int

    object SymbolId:

        /** Internal smart constructor. Callable only from inside `kyo` (via `private[kyo]`); user code cannot invoke this. */
        private[kyo] def apply(i: Int): SymbolId = i

        extension (id: SymbolId)
            /** Internal accessor for the underlying integer value. Used by `Classpath.symbol(id)` to index the dense
              * `symbols: IndexedSeq[Symbol]` array.
              */
            private[kyo] def value: Int = id
        end extension

        given CanEqual[SymbolId, SymbolId] = CanEqual.canEqualAny

        /** Schema[SymbolId] delegates to Schema[Int], mirroring the Schema[Name] = Schema[String] precedent. */
        given schemaSymbolId: Schema[SymbolId] = summon[Schema[Int]]

    end SymbolId

    // Bring the `value` extension into scope for the entirety of `object Tasty` so that
    // internal code can write `id.value` without per-site imports.
    import SymbolId.value

    // ── Version ─────────────────────────────────────────────────────────────

    /** Three-part version number used by both the TASTy wire format and the kyo-tasty snapshot format.
      *
      * `major.minor.experimental` matches the layout that the dotty `TastyFormat` constants advertise: a TASTy
      * file declares its format version in the same shape, and the snapshot reader compares its embedded version
      * against the running kyo-tasty release. Two `Version` values are equal when all three components match.
      *
      * `Tasty.supportedTastyVersion` is the version this release targets. Pickles whose version falls outside
      * the supported range surface as `TastyError.UnsupportedVersion`, with the found and supported versions
      * carried as fields so the caller can report the mismatch.
      */
    final case class Version(major: Int, minor: Int, experimental: Int):
        /** Render the version as `"<major>.<minor>.<experimental>"` (e.g. `"28.8.0"`). */
        def show: String = s"$major.$minor.$experimental"

    /** The Scala 3 TASTy format version this kyo-tasty release targets.
      *
      * Pickles whose major version differs from this value fail to load with
      * `TastyError.UnsupportedVersion(found, supported)`. The minor version is the tail of a backwards-compatible
      * range: pickles with a minor at or below this number are accepted. Bump this value when picking up a new
      * Scala 3 minor release in CI; the `Version.show` rendering (e.g. `"28.8.0"`) is what `TastyError` carries
      * to the caller for human-readable diagnostics.
      */
    val supportedTastyVersion: Version = Version(28, 8, 0)

    // ── Names and flags ─────────────────────────────────────────────────────

    /** A name backed by a `String`.
      *
      * The opaque alias over `String` keeps `Name` distinct from raw `String` at the type level while
      * eliminating the per-name allocation and per-classpath intern table that the former `Interner.Entry`
      * representation required. Equality and ordering are `String` equality. `Schema[Name]` delegates to
      * `Schema[String]` so serialization round-trips byte-stably.
      */
    opaque type Name = String
    object Name:
        given CanEqual[Name, Name] = CanEqual.canEqualAny
        given Schema[Name]         = summon[Schema[String]]

        /** Internal factory: widen a raw `String` to `Name`. For use by kyo-internal unpicklers only. */
        private[kyo] def apply(s: String): Name = s

        extension (n: Name)
            /** Return the `String` form of this name. */
            def asString: String = n

            /** True when this name is the empty string. */
            def isEmpty: Boolean = n.isEmpty
        end extension
    end Name

    /** A packed set of `Flag` modifiers, treated as an immutable bitmask.
      *
      * Backed by an opaque `Long`: a single 64-bit word stores up to 64 distinct flag bits, so testing,
      * combining, and equality are all O(1) and allocate nothing. Each public modifier (`Flag.Inline`,
      * `Flag.Private`, ...) has a unique bit; `Flags` is the union of zero or more such bits.
      *
      * **Construction.** `Flags.empty` is the empty set. `Flags(flag, rest*)` constructs a set from one or
      * more `Flag` values. `flags1.union(flags2)` is the union of two sets. The underlying bits are exposed only
      * via `private[kyo]` accessors (`bits`, `Flags.fromBits`) for the internal unpicklers and snapshot
      * writer; user code should not depend on a specific bit layout because the layout is not stable across
      * kyo-tasty versions.
      *
      * **Querying.** `flags.contains(flag)` tests membership. `flags.isEmpty` returns true for the empty
      * set. `flags.show` renders a human-readable representation (`"Flags(Inline, Private)"`).
      *
      * **Equality.** Reference / value equality on the underlying `Long`; `CanEqual[Flags, Flags]` is
      * provided so `==` works without an import.
      */
    opaque type Flags = Long

    object Flags:
        /** The empty flag set (no modifiers). */
        val empty: Flags = 0L

        /** Combine one or more flags into a `Flags` value. */
        def apply(head: Flag, rest: Flag*): Flags =
            var b = Flag.bits(head)
            rest.foreach(f => b |= Flag.bits(f))
            b
        end apply

        /** Construct a `Flags` directly from its underlying bitmask. For use by kyo-internal
          * unpicklers and snapshot reader/writer that need to materialise an accumulated mask.
          */
        private[kyo] def fromBits(bits: Long): Flags = bits

        /** Reference equality on the underlying Long; safe because Flags is a pure bitmask. */
        given CanEqual[Flags, Flags] = CanEqual.canEqualAny
    end Flags

    /** Public operations on [[Flags]]. Defined at `Tasty` scope so they are in implicit scope
      * for any code that already references `Tasty.Flags`, mirroring how nested opaque-type
      * extensions are surfaced in this file.
      */
    extension (flags: Flags)
        /** True when `flag`'s bit is set in this flag set. */
        def contains(flag: Flag): Boolean = (flags & Flag.bits(flag)) != 0L

        /** Union of two flag sets. */
        def union(other: Flags): Flags = flags | other

        /** The raw bitmask. Used by the internal snapshot writer and any other kyo-internal
          * code that must persist the flag set; not part of the public API.
          */
        private[kyo] def bits: Long = flags

        /** True when no flag bits are set. Equivalent to `flags == Flags.empty`. */
        def isEmpty: Boolean = flags == 0L

        /** Human-readable representation: `Flags.empty.show == "Flags()"`. */
        @scala.annotation.targetName("flagsShow")
        def show: String =
            val sb       = new java.lang.StringBuilder("Flags(")
            var firstOne = true
            var i        = 0
            while i < Flag.all.length do
                val f = Flag.all(i)
                if (flags & Flag.bits(f)) != 0L then
                    if firstOne then firstOne = false
                    else discard(sb.append(", "))
                    discard(sb.append(Flag.name(f)))
                end if
                i += 1
            end while
            sb.append(')').toString
        end show
    end extension

    /** A single modifier flag declared on a `Symbol`.
      *
      * Each named `val` on the `Flag` companion (`Inline`, `Private`, `Protected`, `Final`, `Sealed`,
      * `Abstract`, `Implicit`, `Given`, `Opaque`, `Case`, `Module`, `Synthetic`, `JavaDefined`, ...) is a
      * distinct `Flag` value backed by a unique single-bit `Long`. The bit pattern is implementation
      * detail; only equality is meaningful. The full named-flag list is the union of every Scala 3 source
      * modifier plus the TASTy / JVM origin markers (`JavaDefined`, `JavaRecord`, `Scala2`, `Synthetic`,
      * `Tracked`, ...) so callers can faithfully reflect the original declaration.
      *
      * **Usage.** A `Flag` is rarely used in isolation; the typical pattern is
      * `flags.contains(Flag.X)` against a `Flags` set or `Flags(Flag.X, Flag.Y)` to construct one. The
      * `Flag.name(flag)` helper returns the printable name (e.g. `"Inline"`) used by `Flags.show`.
      *
      * **Equality.** `CanEqual[Flag, Flag]` is provided so `==` works without an import; two `Flag`
      * values are equal iff they share the same bit.
      */
    opaque type Flag = Long

    object Flag:
        // Core access flags (bits 0-15)
        val Inline: Flag      = 1L << 0
        val Private: Flag     = 1L << 1
        val Protected: Flag   = 1L << 2
        val Public: Flag      = 1L << 3
        val Final: Flag       = 1L << 4
        val Sealed: Flag      = 1L << 5
        val Abstract: Flag    = 1L << 6
        val Given: Flag       = 1L << 7
        val Implicit: Flag    = 1L << 8
        val Opaque: Flag      = 1L << 9
        val Case: Flag        = 1L << 10
        val Module: Flag      = 1L << 11
        val Synthetic: Flag   = 1L << 12
        val JavaDefined: Flag = 1L << 13
        val Enum: Flag        = 1L << 14
        val JavaRecord: Flag  = 1L << 15
        // Extended modifier flags (bits 16+)
        val Open: Flag          = 1L << 16
        val ParamAccessor: Flag = 1L << 17
        val Lazy: Flag          = 1L << 18
        val Override: Flag      = 1L << 19
        val Mutable: Flag       = 1L << 20
        val Erased: Flag        = 1L << 21
        val Tracked: Flag       = 1L << 22
        val Tailrec: Flag       = 1L << 23
        val Infix: Flag         = 1L << 24
        val Transparent: Flag   = 1L << 25
        val Trait: Flag         = 1L << 26
        val CaseAccessor: Flag  = 1L << 27
        val FieldAccessor: Flag = 1L << 28
        val Macro: Flag         = 1L << 29
        val InlineProxy: Flag   = 1L << 30
        val Extension: Flag     = 1L << 31
        val Exported: Flag      = 1L << 32
        val CoVariant: Flag     = 1L << 33
        val ContraVariant: Flag = 1L << 34
        val HasDefault: Flag    = 1L << 35
        val Stable: Flag        = 1L << 36
        val Local: Flag         = 1L << 37
        val Artifact: Flag      = 1L << 38
        val Invisible: Flag     = 1L << 39
        val Into: Flag          = 1L << 40
        val PARAMsetter: Flag   = 1L << 41
        val PARAMalias: Flag    = 1L << 42
        val Static: Flag        = 1L << 43
        // Scala 2 origin flag (bit 44): identifies symbols decoded from Scala 2 pickles embedded in classfiles.
        val Scala2: Flag = 1L << 44

        /** All declared flags in declaration order. Used by `Flags.show` and the
          * static `nameOf` table; not a stable part of the public API.
          */
        private[kyo] val all: IArray[Flag] = IArray(
            Inline,
            Private,
            Protected,
            Public,
            Final,
            Sealed,
            Abstract,
            Given,
            Implicit,
            Opaque,
            Case,
            Module,
            Synthetic,
            JavaDefined,
            Enum,
            JavaRecord,
            Open,
            ParamAccessor,
            Lazy,
            Override,
            Mutable,
            Erased,
            Tracked,
            Tailrec,
            Infix,
            Transparent,
            Trait,
            CaseAccessor,
            FieldAccessor,
            Macro,
            InlineProxy,
            Extension,
            Exported,
            CoVariant,
            ContraVariant,
            HasDefault,
            Stable,
            Local,
            Artifact,
            Invisible,
            Into,
            PARAMsetter,
            PARAMalias,
            Static,
            Scala2
        )

        private val nameOf: Dict[Long, String] = Dict(
            Inline        -> "Inline",
            Private       -> "Private",
            Protected     -> "Protected",
            Public        -> "Public",
            Final         -> "Final",
            Sealed        -> "Sealed",
            Abstract      -> "Abstract",
            Given         -> "Given",
            Implicit      -> "Implicit",
            Opaque        -> "Opaque",
            Case          -> "Case",
            Module        -> "Module",
            Synthetic     -> "Synthetic",
            JavaDefined   -> "JavaDefined",
            Enum          -> "Enum",
            JavaRecord    -> "JavaRecord",
            Open          -> "Open",
            ParamAccessor -> "ParamAccessor",
            Lazy          -> "Lazy",
            Override      -> "Override",
            Mutable       -> "Mutable",
            Erased        -> "Erased",
            Tracked       -> "Tracked",
            Tailrec       -> "Tailrec",
            Infix         -> "Infix",
            Transparent   -> "Transparent",
            Trait         -> "Trait",
            CaseAccessor  -> "CaseAccessor",
            FieldAccessor -> "FieldAccessor",
            Macro         -> "Macro",
            InlineProxy   -> "InlineProxy",
            Extension     -> "Extension",
            Exported      -> "Exported",
            CoVariant     -> "CoVariant",
            ContraVariant -> "ContraVariant",
            HasDefault    -> "HasDefault",
            Stable        -> "Stable",
            Local         -> "Local",
            Artifact      -> "Artifact",
            Invisible     -> "Invisible",
            Into          -> "Into",
            PARAMsetter   -> "PARAMsetter",
            PARAMalias    -> "PARAMalias",
            Static        -> "Static",
            Scala2        -> "Scala2"
        )

        /** The raw bit pattern. Used by `Flags.contains` and the internal unpicklers that
          * accumulate a Long mask before constructing a `Flags`; not part of the public API.
          */
        private[kyo] def bits(flag: Flag): Long = flag

        /** Human-readable flag name (e.g., "Inline"). */
        def name(flag: Flag): String = nameOf.getOrElse(flag, s"Flag($flag)")

        /** Multiversal equality: two `Flag` values are equal iff they share the same bit. */
        given CanEqual[Flag, Flag] = CanEqual.canEqualAny
    end Flag

    /** Public operations on [[Flag]]. */
    extension (flag: Flag)
        /** Human-readable flag name. */
        @scala.annotation.targetName("flagShow")
        def show: String = Flag.name(flag)
    end extension

    // ── Error mode ───────────────────────────────────────────────────────────

    /** Controls error handling during classpath open.
      *
      * Passed to `Tasty.withClasspath` (and the cached variant) to select between a tolerant load and an early
      * abort. The mode only governs decode errors found while walking the classpath; missing entries that
      * surface later (an FQN that resolves to no symbol, a subtype check that touches an unresolved parent)
      * are reported through their own return shapes (`Maybe`, `SubtypeVerdict.Indeterminate`) regardless of mode.
      *
      *   - `SoftFail`: decode errors accumulate in `cp.errors`; the classpath is returned regardless and
      *     all subsequent queries operate on the best-effort symbol set. Use this for IDE / tooling paths
      *     where progress is preferable to total failure.
      *   - `FailFast`: any decode error immediately raises `Abort[TastyError]` from `init`. Use this for
      *     batch tools (CI checks, codegen) where a malformed classpath should abort the run.
      *
      * Equality is structural via `derives CanEqual`.
      */
    enum ErrorMode derives CanEqual:
        case SoftFail
        case FailFast
    end ErrorMode

    // ── Subtype verdict ──────────────────────────────────────────────────────

    /** Three-valued lattice for the result of a subtype check.
      *
      * `Sub` and `NotSub` are the definitive verdicts; `Indeterminate` covers
      * the case where the check could not decide (recursion-budget exhaustion
      * or an irreducible `Or` / `And` shape with mixed `Sub` / `NotSub`
      * children). It is never a "branch we forgot" hole: unhandled
      * parent-walk shapes route through `TastyError.UnhandledSubtypingCase`
      * accumulated in `cp.errors`, NOT into `Indeterminate`.
      *
      * Lattice math:
      *   combineAnd:   any NotSub -> NotSub; any Indeterminate & no NotSub -> Indeterminate; all Sub -> Sub.
      *   combineOr:    any Sub -> Sub; any Indeterminate & no Sub -> Indeterminate; all NotSub -> NotSub.
      *
      * Pierce TAPL ch.26 ; semi-decidability of F-bounded recursion.
      */
    enum SubtypeVerdict derives Schema, CanEqual:
        case Sub, NotSub, Indeterminate
    end SubtypeVerdict

    // ── Constants and annotations ───────────────────────────────────────────

    /** Literal constant payload used inside `Type.ConstantType` and `Tree.Literal`.
      *
      * `Constant` is a sealed enum of typed literals: every Scala primitive (`IntConst`, `LongConst`,
      * `FloatConst`, `DoubleConst`, `BooleanConst`, `CharConst`, `ByteConst`, `ShortConst`), strings
      * (`StringConst`), `()` (`UnitConst`), `null` (`NullConst`), and class literals (`ClassConst(tpe)`).
      *
      * **Naming.** The `*Const` suffix is intentional and distinguishes these from `quoted.reflect.Constant`
      * cases (which use the `*Constant` suffix). Do not confuse the two; they cover different value spaces.
      * Literal values are not their own `Type` cases: a Scala constant type like `42` appears as
      * `Type.ConstantType(IntConst(42))`, not as a top-level `Type.IntConst`.
      *
      * **Rendering.** `show` returns a Scala-source-shaped representation: strings are quoted, longs get the
      * `L` suffix, floats get the `f` suffix, `()` and `null` render as themselves, class literals render as
      * `classOf[T]`. Equality and hashing are structural via `derives CanEqual`.
      */
    enum Constant derives Schema, CanEqual:
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
      * Carried by `Symbol.sourcePosition: Maybe[Position]`. `Absent` for classfile-sourced symbols and for
      * TASTy symbols loaded from a file without a Positions section; otherwise populated from the TASTy
      * Positions table during classpath open. Positions point at the symbol's declaration site, not at
      * every reference to it. There is no per-tree positional information; the public model deliberately
      * stops at the symbol level.
      *
      * `sourceFile` is the file name from the Attributes section, exactly as recorded in the pickle
      * (no path normalisation). A `Position` is only constructed when the SOURCEFILE attribute is present;
      * absence of source information is represented by `Symbol.sourcePosition == Maybe.Absent`, not by a
      * sentinel string inside this case class. `line` and `column` are 1-based (line 1 is the first line
      * of the file; column 1 is the first character of the line). A column of 0 is possible when the
      * underlying TASTy entry carried no column information.
      *
      * Equality is structural across all three fields (case class auto-generation).
      */
    final case class Position(sourceFile: String, line: Int, column: Int) derives Schema, CanEqual:
        /** Human-readable representation: `file:line:column`. */
        def show: String = s"$sourceFile:$line:$column"
    end Position

    /** A Scala annotation as it appears on a `Type.Annotated` (and, indirectly, on a `Symbol`).
      *
      * Annotations attach to types in the Scala 3 model; a symbol's annotations are reachable by walking
      * its `declaredType` and collecting the `Type.Annotated` wrappers. `annotationType` carries the
      * annotation class as a `Type` (typically `Type.Named` to the annotation class symbol). `arguments`
      * carries the argument trees in source order, each a `Tree.Literal`, `Tree.Apply`, `Tree.Select`, or
      * other AST shape consistent with what was written at the annotation call site.
      *
      * **Decode.** Both fields are populated during Classpath open Pass B. A decode failure produces
      * `arguments = Chunk.empty` and appends a `TastyError.MalformedSection` to the file-result error
      * list, which flows into `cp.errors`. `annotationType` is resolved best-effort during pass 1 and may
      * reference a placeholder symbol when the annotation class itself is not in the loaded classpath.
      *
      * Equality and hashing are structural over both fields (case class auto-generation).
      */
    final case class Annotation(annotationType: Type, arguments: Chunk[Tree]) derives Schema, CanEqual

    // ── Type ADT ────────────────────────────────────────────────────────────

    /** Structural representation of a Scala type as it appears in TASTy.
      *
      * `Type` is a sealed enum of around two dozen cases covering the full Scala 3 type language: nominal references
      * (`Named`, `TermRef`, `TypeRef`), type constructors (`Applied`, `TypeLambda`, `Function`, `ContextFunction`,
      * `Tuple`), composite shapes (`AndType`, `OrType`, `Refinement`, `Annotated`), self / super / this references
      * (`ThisType`, `SuperType`, `ParamRef`), bounds and wildcards (`Bounds`, `Wildcard`), and match-type machinery
      * (`MatchType`, `MatchCase`, `Skolem`, `FlexibleType`, `Rec`, `RecThis`). Constant payloads (`Type.ConstantType`)
      * carry a `Constant` value.
      *
      * **Hash-consing.** During Classpath construction every `Type` is interned through a `TypeArena`; after Pass C
      * all surviving `Type` values are structurally deduplicated. Structurally equal types produced at
      * different decode sites share the same reference, so reference equality is sound for cache keys. Construction
      * of an off-arena `Type` (for example, a synthetic `Named(id)` built by user code) is allowed but will not
      * benefit from interning.
      *
      * **Sentinels.** Three reserved cases (`Nothing`, `Any`, `Unknown`) stand in for missing bounds and
      * unresolvable types. They are distinct enum cases, not magic `Named` ids; pattern matching is the canonical
      * way to detect them.
      *
      * **Traversal.** `children` returns first-level structural children; `foreach` is a pre-order walk; `symbol`
      * resolves the head symbol of a `Named` (and returns `Absent` otherwise); `show(using cp)` renders a
      * Scala-source-shaped string. The subtype check `isSubtypeOf` is structural, uses the implicit `Classpath`
      * to resolve parents, and returns a three-way `SubtypeVerdict` rather than a `Boolean`.
      */
    enum Type derives Schema, CanEqual:

        /** Reference to a symbol by its id. Wire tag: `TYPEREFdirect` (105) and related forms.
          *
          * The most common type constructor: names a class, trait, object, type alias, or type
          * parameter by its `SymbolId`. Resolves via `cp.symbol(symbolId)`. A `Named` whose
          * `symbolId.value < 0` denotes an unresolvable reference that survived loading.
          *
          * Callers performing type comparison should use `Tasty.isSubtypeOf` rather than comparing
          * `symbolId` values directly, as parent-chain resolution requires the `Classpath`.
          */
        case Named(symbolId: SymbolId)

        /** Reference to a term-level path as a type. Wire tag: `TERMREF` (111) and variants.
          *
          * Appears where a stable term path is used as a type, most commonly in path-dependent
          * types (`p.type`) and singleton bounds. `prefix` is the qualifier type; `name` is the
          * term name. Semantically distinct from `TypeRef` (which carries a type-level reference).
          *
          * Callers that need to match annotation FQNs should use `typeFqnString`, which handles
          * both `TermRef` and `TypeRef` transparently.
          */
        case TermRef(prefix: Type, name: Name)

        /** Type application: `F[A1, ..., AN]`. Wire tag: `APPLIEDtype` (69).
          *
          * General type application after the constructor-folding in `TypeOps.applied`. `base` is
          * the constructor type (often a `Named`); `args` are the type arguments in declaration
          * order. The folding step converts `APPLIEDtype(scala.FunctionN, _)` to `Function`,
          * `APPLIEDtype(scala.Array, _)` to `Array`, and so on, so callers only see `Applied` for
          * forms that do not map to a dedicated case.
          *
          * Callers doing structural matching often want to recurse into `base` and `args` both.
          */
        case Applied(base: Type, args: Chunk[Type])

        /** Higher-kinded type lambda: `[X1, ..., XN] =>> body`. Wire tag: `TYPELAMBDAtype` (75).
          *
          * Represents a type-level function abstraction. `paramIds` holds the `SymbolId`s of the
          * lambda's parameter symbols; `body` is the result type, which may contain `ParamRef`
          * nodes pointing back into `paramIds`. Type lambdas arise from type members with type
          * parameters and from certain higher-kinded class definitions.
          *
          * Callers applying a `TypeLambda` should substitute its params via
          * `TypeOps.applyTypeLambda`.
          */
        case TypeLambda(paramIds: Chunk[SymbolId], body: Type)

        /** Plain function type: `(A1, ..., AN) => R`.
          *
          * Wire-level: `APPLIEDtype` whose constructor has FQN `scala.FunctionN`, collapsed by
          * `TypeOps.applied`. Distinct from `ContextFunction` (which carries `?=>` context-function
          * types). Callers can pattern-match on this case without testing a Boolean flag; the dedicated
          * `ContextFunction` case handles the `?=>` variant.
          *
          * `params` holds the argument types in declaration order; `result` is the return type. An
          * empty `params` represents a `Function0` (thunk). The TASTy wire path goes through
          * `APPLIEDtype(Named(negId: scala.FunctionN), [A1, ..., AN, R])` and is folded during Pass B.
          */
        case Function(params: Chunk[Type], result: Type)

        /** Context function type: `(A1, ..., AN) ?=> R`.
          *
          * Wire-level: `APPLIEDtype` whose constructor has FQN `scala.ContextFunctionN`. Dedicated case
          * so callers can pattern-match `?=>` against `=>` without testing a Boolean flag. Methods decoded
          * from `scala.ContextFunctionN` produce this case; methods decoded from `scala.FunctionN` produce
          * `Type.Function`. `params` holds the implicit parameter types; `result` is the return type.
          *
          * This case is structurally disjoint from `Type.Function`: a given wire-level type node decodes
          * to exactly one of the two cases based on whether its constructor FQN is `scala.ContextFunctionN`
          * or `scala.FunctionN`.
          *
          * The corresponding Scala source shape is `(A1, ..., AN) ?=> R`.
          */
        case ContextFunction(params: Chunk[Type], result: Type)

        /** Tuple type: `(A1, ..., AN)`. Wire tag: `APPLIEDtype(scala.TupleN, [A1, ..., AN])`.
          *
          * Collapsed by `TypeOps.applied` when the constructor FQN is `scala.TupleN` (N >= 2).
          * `elements` holds the component types in declaration order. Callers iterating over
          * tuple components should use `elements.iterator`.
          *
          * A `Tuple` with zero elements is not produced by the decoder; `Unit` decodes to
          * `Named(scala.Unit)` rather than `Tuple(Chunk.empty)`.
          *
          * The corresponding Scala source shape is `(A1, ..., AN)`.
          */
        case Tuple(elements: Chunk[Type])

        /** Call-by-name parameter type: `=> T`. Wire tag: `BYNAMEtpt`.
          *
          * Used for call-by-name parameters; the argument expression is not evaluated at the call site
          * but on each use. `underlying` is the parameter type `T`. Distinct from `Repeated` (varargs).
          *
          * Appears in method parameter positions encoded as `BYNAMEtpt(T)` in TASTy. The corresponding
          * Scala source shape is `def f(x: => T)`.
          */
        case ByName(underlying: Type)

        /** Varargs element type: `T*`. Wire tag: `REPEATEDtpt` via `ANNOTATEDtpt`.
          *
          * Represents the last parameter of a varargs method. `elem` is the element type `T`. The
          * primary decoding path is `ANNOTATEDtpt(@scala.annotation.internal.Repeated, T)` in
          * `TreeUnpickler.decodeTptAsType`; a secondary fallback applies for
          * `APPLIEDtype(scala.<repeated>, [T])` via `TypeOps.applied`.
          *
          * Distinct from `ByName` (call-by-name) and `Array` (Java arrays). The corresponding Scala
          * source shape is `def f(xs: T*)`.
          */
        case Repeated(elem: Type)

        /** Java array type: `Array[T]`. Wire tag: `APPLIEDtype(scala.Array, [T])`.
          *
          * Collapsed by `TypeOps.applied` when the constructor FQN is `scala.Array`. Distinguished
          * from `Applied` for structural matching without requiring FQN resolution at the use site.
          * `elem` is the element type. Java arrays also decode to this case via `TypeOps.mkArray`.
          *
          * The corresponding Scala source shape is `Array[T]`.
          */
        case Array(elem: Type)

        /** Structural refinement type: `P { def name: I }`. Wire tag: `REFINEDtype`.
          *
          * A structural type adding a member `name` with info type `I` to parent type `P`. Commonly
          * appears in type-class evidence derivation, anonymous structural types, and object literals.
          *
          * `parent` is the base type, `name` is the declared member name, `info` is the member's
          * declared type. For abstract member refinements, `info` is a `Type.Bounds` value; for
          * concrete member refinements, `info` is the member type directly.
          */
        case Refinement(parent: Type, name: Name, info: Type)

        /** Recursive type binding: `mu X. parent[X]`. Wire tag: `RECtype`.
          *
          * Marks the binding site of a recursive type variable. Always paired with one or more
          * `RecThis` references inside `parent` that point back to this `Rec` node. The pattern
          * `Rec(parent)` reads as "the type `mu X. parent[X]`" where `X` is any `RecThis` inside
          * `parent`. Hash-consing in `TypeArena` uses a depth counter to prevent infinite recursion on
          * `Rec`/`RecThis` cycles.
          *
          * Callers that unfold recursive types should use `Subtyping.substituteRecThis`.
          */
        case Rec(parent: Type)

        /** Back-reference inside a `Rec` type. Wire tag: `RECthis`.
          *
          * `rec` is the enclosing `Rec` node this reference points back to. Callers should treat
          * `RecThis` as an opaque back-pointer; traversal via `visit` includes the cycle guard. Direct
          * structural comparison of `RecThis` values requires the enclosing `Rec` context (see
          * `Subtyping.typeEquivAlpha`).
          *
          * `RecThis` never appears outside a `Rec.parent` subtree in well-formed TASTy.
          */
        case RecThis(rec: Type)

        /** Intersection type: `A & B`. Wire tag: `ANDtype`.
          *
          * Also produced from `APPLIEDtype(scala.&, [A, B])` by `TypeOps.andType`. `left` and `right`
          * are the operands. `AndType(scala.Singleton, X)` is normalized to `X` by `TypeOps.andType`
          * (the singleton bound collapses).
          *
          * `SubtypeVerdict` for an `AndType` on the supertype side uses three-valued logic: `Sub` only
          * when `Sub` for both components. The corresponding Scala source shape is `A & B`.
          */
        case AndType(left: Type, right: Type)

        /** Union type: `A | B`. Wire tag: `ORtype`.
          *
          * Also produced from `APPLIEDtype(scala.|, [A, B])` by `TypeOps.applied`. `left` and `right`
          * are the operands. `SubtypeVerdict` for an `OrType` on the subtype side uses three-valued
          * logic: `Sub` only when `Sub` for either component.
          *
          * The corresponding Scala source shape is `A | B`.
          */
        case OrType(left: Type, right: Type)

        /** Annotated type: `T @ann`. Wire tag: `ANNOTATEDtype` or `ANNOTATEDtpt`.
          *
          * `underlying` is the base type, `annotation` is the `Tasty.Annotation` carrying the
          * annotation class and arguments. Primary uses include
          * `@scala.annotation.internal.Repeated` on varargs (decoded to `Type.Repeated` before
          * reaching the user surface) and user-visible annotations like `@uncheckedVariance`.
          *
          * Callers interested only in the structural type should strip the `Annotated` wrapper.
          */
        case Annotated(underlying: Type, annotation: Annotation)

        /** Literal singleton type. Wire tag: `SINGLETONtpt` or `CONSTANTtype`.
          *
          * `value` is the `Tasty.Constant` payload. Represents a compile-time constant promoted to a
          * type. Examples: `42.type`, `"hello".type`, `true.type`. Appears as a type argument or
          * return type in code that uses literal types.
          *
          * Callers should extract the `Constant` via `value` and dispatch on `Constant` variants to
          * inspect the payload.
          */
        case ConstantType(value: Constant)

        /** Self-type reference `C.this` for class `C`. Wire tag: `THIStpe`.
          *
          * `clsId` is the `SymbolId` of the enclosing class. Resolves to the actual
          * `Symbol.ClassLike` via `cp.symbol(clsId)`. Distinct from `SuperType`, which carries both
          * self and mixin components.
          *
          * Appears inside the bodies of class members where `this` is used as a type (common in
          * self-referential type bounds and type members).
          */
        case ThisType(clsId: SymbolId)

        /** Super-type reference `C.super[M]`. Wire tag: `SUPERtype`.
          *
          * `self` is the `ThisType` of the enclosing class; `mixin` is the type of the linearized
          * parent being referenced (the explicit `[M]` in `super[M]`, or the first parent when no
          * explicit mixin is given). Used when a class overrides a method and calls
          * `super.method(...)`.
          *
          * Distinct from `ThisType` (which carries only the self reference).
          */
        case SuperType(self: Type, mixin: Type)

        /** Reference to a type-lambda or method type parameter by position. Wire tag: `PARAMtype`.
          *
          * `binderId` is the `SymbolId` of the enclosing binder's first parameter symbol;
          * `idx` is the zero-based index of the parameter within that binder. Used inside
          * `TypeLambda.body` to refer back to the lambda's parameters without creating a recursive
          * cycle.
          *
          * Callers matching inside a `TypeLambda` should use `paramIds(idx)` on the enclosing
          * `TypeLambda` to resolve the symbol the `ParamRef` points to.
          */
        case ParamRef(binderId: SymbolId, idx: Int)

        /** Bounded wildcard type: `_ >: lo <: hi`. Wire tag: `WILDCARDtype`.
          *
          * Used for existential type arguments and wildcard imports. `lo` and `hi` are the bounds;
          * `Type.Nothing` stands in for an absent lower bound and `Type.Any` for an absent upper
          * bound.
          *
          * Distinct from `Bounds` (a bounds declaration at an abstract-type site, not a wildcard
          * argument). The corresponding Scala source shape is `_ >: A <: B`.
          */
        case Wildcard(lo: Type, hi: Type)

        /** Skolem (existential witness) type. Wire tag: `SKOLEMtype`.
          *
          * `underlying` is the type being approximated. Introduced during type inference; represents
          * an existentially-bound type variable that has been given a concrete identity for
          * unification purposes. Rarely appears in decoded TASTy; mostly an artifact of the Scala 3
          * compiler's inference machinery leaking into serialized form for complex GADTs or
          * existential patterns.
          *
          * Callers should generally treat `Skolem` as opaque or unwrap via `underlying`.
          */
        case Skolem(underlying: Type)

        /** Match type: `scrutinee match { cases }`. Wire tag: `MATCHtype`.
          *
          * `bound` is the declared upper bound of the match type (`Type.Any` if absent), `scrutinee`
          * is the type being matched, `cases` is a `Chunk[Type]` where each element is a
          * `Type.MatchCase`. Exhaustive matches over `Type` must handle both `MatchType` and
          * `MatchCase`. The `MatchCase` sub-case carries one arm; `MatchType` carries the full match
          * expression.
          *
          * @see Type.MatchCase
          */
        case MatchType(bound: Type, scrutinee: Type, cases: Chunk[Type])

        /** Flexible (Java-nullable) type: `T!`. Wire tag: `FLEXIBLEtype`.
          *
          * `underlying` is the Scala type `T`. Flexible types appear when the Scala 3 compiler
          * decodes a Java class whose field or return type has no nullability annotation; the result
          * is `FlexibleType(T)` rather than `T | Null`. Callers doing Java interop that need to
          * handle Java-origin `null` should check for this wrapper and treat the value as nullable.
          *
          * Rarely seen in pure Scala codebases; mostly an artifact of Java class file decoding.
          */
        case FlexibleType(underlying: Type)

        /** Match-type case: `pat => rhs`. Wire-level TASTy tag MATCHCASEtype (192).
          *
          * First-class ADT case for a single match-type arm. Exhaustive matches over `Type` should add this
          * case; existing matches that traverse via `Type.children` remain total via the wildcard fallback.
          */
        case MatchCase(pat: Type, rhs: Type)

        /** Type-position reference. Wire tag TYPEREF (117).
          *
          * Semantically distinct from `TermRef` (term-position reference). Callers that need to distinguish
          * type references from term references should match on `TypeRef`. `typeFqnString` handles both
          * `TermRef` and `TypeRef` for annotation FQN matching.
          */
        case TypeRef(qual: Type, name: Name)

        /** Explicit type bounds. Wire tags TYPEBOUNDS (163) and TYPEBOUNDStpt (164).
          *
          * Represents `lo .. hi` as declared in source. Distinct from `Type.Wildcard`, which carries the
          * bounds of an unspecified type argument (`_ <: A`); `Type.Bounds` carries the bounds at an
          * explicit-bounds declaration site.
          */
        case Bounds(lo: Type, hi: Type)

        /** Sentinel lower-bound type used in `TypeBounds` when no concrete lower bound is known. */
        case Nothing

        /** Sentinel upper-bound type used in `TypeBounds` when no concrete upper bound is known. */
        case Any

        /** Visit each direct child of this Type without allocating an intermediate Chunk.
          *
          * Used internally by `children` and `foreach` so the hot traversal path does not materialize a
          * Chunk per node.
          */
        private[kyo] def visit(f: Type => Unit): Unit = this match
            case Applied(base, args) =>
                f(base); args.foreach(f)
            case TypeLambda(_, body) => f(body)
            case Function(params, ret) =>
                params.foreach(f); f(ret)
            case ContextFunction(params, ret) =>
                params.foreach(f); f(ret)
            case Tuple(elements) => elements.foreach(f)
            case ByName(t)       => f(t)
            case Repeated(t)     => f(t)
            case Array(t)        => f(t)
            case Refinement(p, _, i) =>
                f(p); f(i)
            case Rec(p)       => f(p)
            case RecThis(rec) => f(rec)
            case AndType(l, r) =>
                f(l); f(r)
            case OrType(l, r) =>
                f(l); f(r)
            case Annotated(u, _) => f(u)
            case SuperType(s, m) =>
                f(s); f(m)
            case Wildcard(lo, hi) =>
                f(lo); f(hi)
            case Skolem(u) => f(u)
            case MatchType(b, sc, cases) =>
                f(b); f(sc); cases.foreach(f)
            case FlexibleType(u) => f(u)
            case MatchCase(p, r) =>
                f(p); f(r)
            case TypeRef(qual, _) => f(qual)
            case Bounds(lo, hi) =>
                f(lo); f(hi)
            case _ => ()
        end visit

        /** First-level structural children of this Type. Leaf cases return an empty Chunk.
          *
          * Materializes a Chunk for callers that need an indexable structure. Internal traversals should
          * prefer `visit` (non-allocating).
          */
        def children: Chunk[Type] =
            val b = Chunk.newBuilder[Type]
            visit(b += _)
            b.result()
        end children

        /** Visit this type and every structural descendant in pre-order (self first). */
        private[kyo] def foreach(f: Type => Unit): Unit =
            f(this)
            visit(_.foreach(f))
        end foreach

        /** Collect all nodes matching `pf` in pre-order. Inline entry delegates to the non-inline body loop. */
        inline def collect[A](inline pf: PartialFunction[Type, A]): Chunk[A] =
            collectImpl(pf)

        private def collectImpl[A](pf: PartialFunction[Type, A]): Chunk[A] =
            val b = Chunk.newBuilder[A]
            foreach: t =>
                if pf.isDefinedAt(t) then b += pf(t)
            b.result()
        end collectImpl

        /** Find the first node satisfying `p` in pre-order. Inline entry delegates to the non-inline body loop. */
        inline def find(inline p: Type => Boolean): Maybe[Type] =
            findImpl(p)

        private def findImpl(p: Type => Boolean): Maybe[Type] =
            var found: Maybe[Type] = Maybe.Absent
            def go(t: Type): Boolean =
                if found.isDefined then true
                else if p(t) then
                    found = Maybe(t)
                    true
                else
                    var hit = false
                    t.visit: c =>
                        if !hit && go(c) then hit = true
                    hit
                end if
            end go
            discard(go(this))
            found
        end findImpl

        /** Left-fold over all nodes in pre-order. Inline entry delegates to the non-inline body loop. */
        inline def foldLeft[A](z: A)(inline f: (A, Type) => A): A =
            foldLeftImpl(z)(f)

        private def foldLeftImpl[A](z: A)(f: (A, Type) => A): A =
            var acc = z
            foreach((t: Type) => acc = f(acc, t))
            acc
        end foldLeftImpl

        /** True when any node in the subtree (including this node) satisfies `p`. Pre-order short-circuits on first match.
          * Inline entry delegates to the non-inline body loop.
          */
        inline def exists(inline p: Type => Boolean): Boolean =
            existsImpl(p)

        private def existsImpl(p: Type => Boolean): Boolean =
            if p(this) then true
            else
                var hit = false
                visit: c =>
                    if !hit && c.existsImpl(p) then hit = true
                hit
        end existsImpl

    end Type

    // ── Tree ADT ────────────────────────────────────────────────────────────

    /** Structural representation of a TASTy expression or definition body.
      *
      * Produced on demand by `Symbol.bodyTree` and `Classpath.decodeBody`; the result is memoised on the
      * `Classpath` keyed by `SymbolId`. Each case mirrors a TASTy AST tag; the case naming follows the dotty
      * `TastyFormat` layout so the dotty reference is the source of truth for the wire-level encoding.
      *
      * **Strict sub-trees.** All sub-trees are eagerly populated when a body is decoded; the laziness lives at
      * the body boundary, not inside the AST. Within a tree, cross-references to symbols travel as `Symbol`
      * values (already resolved through the implicit `Classpath`) and references to types travel as `Type`
      * values from the canonical arena.
      *
      * **Categories.** Term-level cases (`Apply`, `Select`, `Ident`, `Block`, `If`, `Match`, ...) cover expression
      * bodies. Definition cases (`ValDef`, `DefDef`, `TypeDef`, `ClassDef`, `PackageDef`, `Template`) appear
      * inside class templates. Type-position cases (`AppliedType`, `RefinedType`, `AnnotatedType`, `MatchType`,
      * `TypeBounds`, ...) appear inside type trees referenced by `tpt` slots. Pattern cases (`Bind`, `Unapply`,
      * `Alternative`) appear under `CaseDef`. `Shared` carries a back-reference address used to deduplicate
      * sub-trees within the source TASTy file.
      *
      * **Traversal.** `children`, `foreach`, `collect`, `find`, `foldLeft`, and `exists` cover the structural walks
      * the typical caller needs; `show(using cp)` renders a Scala-source-shaped string for debugging.
      */
    enum Tree derives Schema, CanEqual:
        /** Term reference by name (IDENT tag). */
        case Ident(name: Name, tpe: Type)

        /** Member selection (SELECT tag). */
        case Select(qualifier: Tree, name: Name, tpe: Type)

        /** Function application (APPLY tag). */
        case Apply(fun: Tree, args: Chunk[Tree])

        /** Type application (TYPEAPPLY tag). */
        case TypeApply(fun: Tree, args: Chunk[Type])

        /** Block of statements followed by an expression (BLOCK tag). */
        case Block(stats: Chunk[Tree], expr: Tree)

        /** Conditional expression (IF tag). */
        case If(cond: Tree, thenp: Tree, elsep: Tree)

        /** Pattern match (MATCH tag). */
        case Match(selector: Tree, cases: Chunk[Tree.CaseDef])

        /** Single case in a match (CASEDEF tag). */
        case CaseDef(pattern: Tree, guard: Maybe[Tree], body: Tree)

        /** Literal constant (various const tags). */
        case Literal(constant: Constant)

        /** Object allocation (NEW tag). */
        case New(tpe: Type)

        /** Assignment (ASSIGN tag). */
        case Assign(lhs: Tree, rhs: Tree)

        /** Return statement (RETURN tag). */
        case Return(expr: Maybe[Tree], from: Symbol)

        /** Throw expression (THROW tag). */
        case Throw(expr: Tree)

        /** Lambda / anonymous function (LAMBDA tag). */
        case Lambda(method: Tree, tpe: Maybe[Type])

        /** Type ascription (TYPED tag). */
        case Typed(expr: Tree, tpe: Type)

        /** Inlined call expansion (INLINED tag). */
        case Inlined(call: Maybe[Tree], bindings: Chunk[Tree], body: Tree)

        /** Try/catch/finally (TRY tag). */
        case Try(expr: Tree, cases: Chunk[Tree.CaseDef], finalizer: Maybe[Tree])

        /** While loop (WHILE tag). */
        case While(cond: Tree, body: Tree)

        /** Pattern binding (BIND tag). */
        case Bind(name: Name, pattern: Tree)

        /** Alternative patterns in a case (ALTERNATIVE tag). */
        case Alternative(patterns: Chunk[Tree])

        /** Unapply extractor call (UNAPPLY tag). */
        case Unapply(fun: Tree, implicits: Chunk[Tree], patterns: Chunk[Tree])

        /** Val or var definition (VALDEF tag). */
        case ValDef(sym: Symbol, tpt: Type, rhs: Maybe[Tree])

        /** Method definition (DEFDEF tag). */
        case DefDef(sym: Symbol, paramss: Chunk[Chunk[Tree]], tpt: Type, rhs: Maybe[Tree])

        /** Type alias or abstract type definition (TYPEDEF tag). */
        case TypeDef(sym: Symbol, rhs: Type)

        /** Package definition (PACKAGE tag). */
        case PackageDef(sym: Symbol, stats: Chunk[Tree])

        /** Class definition (TYPEDEF with TEMPLATE). */
        case ClassDef(sym: Symbol, template: Tree.Template)

        /** Class template body (TEMPLATE tag). */
        case Template(parents: Chunk[Tree], self: Maybe[Symbol], body: Chunk[Tree])

        /** Super reference (SUPER tag). */
        case Super(qual: Tree, mix: Maybe[Name])

        /** This reference (THIS tag). */
        case This(cls: Symbol)

        /** Named argument in an application (NAMEDARG tag). */
        case NamedArg(name: Name, value: Tree)

        /** Annotated tree (ANNOTATEDtpt/ANNOTATEDtype). */
        case Annotated(expr: Tree, annotation: Tree)

        /** Shared sub-tree back-reference (SHAREDtype or SHAREDterm tag). `addr` is the byte address of the original node. */
        case Shared(addr: Int)

        /** TASTy category-1 modifier tag (single-byte, no payload; tag in range [1, 59]). */
        case Modifier(flag: Flag)

        /** Recursive type wrapper (RECtype tag). */
        case RecType(parent: Tree)

        /** Super type pair (SUPERtype tag). */
        case SuperType(thistpe: Tree, supertpe: Tree)

        /** Structural refinement type (REFINEDtype tag). */
        case RefinedType(parent: Tree, name: Name, info: Tree)

        /** Type constructor applied to arguments (APPLIEDtype tag). */
        case AppliedType(tycon: Tree, args: Chunk[Tree])

        /** Type bounds (TYPEBOUNDS tag). */
        case TypeBounds(lo: Tree, hi: Tree)

        /** Annotated type (ANNOTATEDtype tag). */
        case AnnotatedType(parent: Tree, annot: Tree)

        /** Intersection type (ANDtype tag). */
        case AndType(left: Tree, right: Tree)

        /** Union type (ORtype tag). */
        case OrType(left: Tree, right: Tree)

        /** By-name type (BYNAMEtype tag). */
        case ByNameType(arg: Tree)

        /** Match type with scrutinee and cases (MATCHtype tag). */
        case MatchType(bound: Tree, scrutinee: Tree, cases: Chunk[Tree])

        /** Flexible (Java-nullable) type (FLEXIBLEtype tag). */
        case FlexibleType(arg: Tree)

        /** Type-position identifier (IDENTtpt tag): nameRef + type. */
        case IdentTpt(name: Name, tpe: Type)

        /** Type-position selection (SELECTtpt tag): qualifier + name. */
        case SelectTpt(qual: Tree, name: Name)

        /** Singleton type (SINGLETONtpt tag): ref tree. */
        case SingletonTpt(tpe: Tree)

        /** Package-level term reference (TERMREFpkg tag): package name only. */
        case TermRefPkg(name: Name)

        /** Package-level type reference (TYPEREFpkg tag): package name only. */
        case TypeRefPkg(name: Name)

        /** Symbol-addressed term reference (TERMREFsymbol tag): addr + qualifier. */
        case TermRefSymbol(addr: Int, qual: Tree)

        /** Symbol-addressed type reference (TYPEREFsymbol tag): addr + qualifier. */
        case TypeRefSymbol(addr: Int, qual: Tree)

        /** Direct-address term reference (TERMREFdirect tag): symbol address. */
        case TermRefDirect(addr: Int)

        /** Direct-address type reference (TYPEREFdirect tag): symbol address. */
        case TypeRefDirect(addr: Int)

        /** Owner-qualified selection (SELECTin tag): qualifier + name + owner. */
        case SelectIn(qual: Tree, name: Name, owner: Tree)

        /** Import statement (IMPORT tag): qualifier expression and selector trees. */
        case Import(qual: Tree, selectors: Chunk[Tree])

        /** Export clause (EXPORT tag): qualifier expression and selector trees. */
        case Export(qual: Tree, selectors: Chunk[Tree])

        /** In-tree annotation node (ANNOTATION tag): annotation class type tree and annotation argument tree. */
        case AnnotationNode(annotType: Tree, arg: Tree)

        /** Recursive-this reference (RECthis tag): address of the enclosing Rec frame. */
        case RecThisAddr(addr: Int)

        /** Import selector: the imported name (IMPORTED tag). */
        case Imported(qual: Tree)

        /** Import rename: the renamed-to name (RENAMED tag). */
        case Renamed(name: Name)

        /** By-name type annotation in type position (BYNAMEtpt tag). */
        case ByNameTpt(inner: Type)

        /** Bounded wildcard type (BOUNDED tag): the bound tree. */
        case Bounded(bound: Tree)

        /** Explicit type annotation in type position (EXPLICITtpt tag). */
        case ExplicitTpt(inner: Type)

        /** Elided (inferred) type position (ELIDED tag). */
        case Elided(inner: Type)

        /** Type-position reference by name and qualifier (TYPEREF tag). */
        case TypeRefTree(qual: Tree, name: Name)

        /** Term-position path-dependent reference. Wire tag TERMREFin (174).
          *
          * prefix is the qualifier tree (encoded as Tree.Ident(name, qualType)). name identifies the
          * referenced member.
          */
        case TermRef(prefix: Tree, name: Name)

        /** Repeated (varargs) sequence literal. Wire tag REPEATED (149).
          *
          * elems are the element trees. tpe is the static element type for typed callers; when the element
          * type is not statically known the placeholder `Type.Wildcard(Nothing, Any)` is carried.
          */
        case SeqLiteral(elems: Chunk[Tree], tpe: Type)

        /** Self type definition in a class template (SELFDEF tag). */
        case SelfDef(name: Name, tpe: Tree)

        /** Outer reference (SELECTouter tag): outer class at given level. */
        case SelectOuter(qual: Tree, name: Name, levels: Int, tpe: Type)

        /** Unknown tag: encountered a tag not covered by this ADT version. */
        case Unknown(tag: Int, length: Int)

        /** Visit each direct child of this Tree without allocating an intermediate Chunk.
          *
          * The pattern match dispatches once and calls `f` per child in source order. Used internally by
          * `children`, `foreach`, `collect`, `find`, `foldLeft`, and `exists` so the hot traversal path does
          * not materialize a Chunk per node.
          */
        def visit(f: Tree => Unit): Unit = this match
            case Tree.Ident(_, _) => ()
            case Tree.Select(qualifier, _, _) =>
                f(qualifier)
            case Tree.Apply(fun, args) =>
                f(fun); args.foreach(f)
            case Tree.TypeApply(fun, _) =>
                f(fun)
            case Tree.Block(stats, expr) =>
                stats.foreach(f); f(expr)
            case Tree.If(cond, thenp, elsep) =>
                f(cond); f(thenp); f(elsep)
            case Tree.Match(selector, cases) =>
                f(selector); cases.foreach(f)
            case Tree.CaseDef(pattern, guard, body) =>
                f(pattern)
                guard match
                    case Maybe.Present(t) => f(t)
                    case Maybe.Absent     => ()
                f(body)
            case Tree.Literal(_) => ()
            case Tree.New(_)     => ()
            case Tree.Assign(lhs, rhs) =>
                f(lhs); f(rhs)
            case Tree.Return(expr, _) =>
                expr match
                    case Maybe.Present(t) => f(t)
                    case Maybe.Absent     => ()
            case Tree.Throw(expr) =>
                f(expr)
            case Tree.Lambda(method, _) =>
                f(method)
            case Tree.Typed(expr, _) =>
                f(expr)
            case Tree.Inlined(call, bindings, body) =>
                call match
                    case Maybe.Present(t) => f(t)
                    case Maybe.Absent     => ()
                bindings.foreach(f)
                f(body)
            case Tree.Try(expr, cases, finalizer) =>
                f(expr)
                cases.foreach(f)
                finalizer match
                    case Maybe.Present(t) => f(t)
                    case Maybe.Absent     => ()
            case Tree.While(cond, body) =>
                f(cond); f(body)
            case Tree.Bind(_, pattern) =>
                f(pattern)
            case Tree.Alternative(patterns) =>
                patterns.foreach(f)
            case Tree.Unapply(fun, implicits, patterns) =>
                f(fun); implicits.foreach(f); patterns.foreach(f)
            case Tree.ValDef(_, _, rhs) =>
                rhs match
                    case Maybe.Present(t) => f(t)
                    case Maybe.Absent     => ()
            case Tree.DefDef(_, paramss, _, rhs) =>
                paramss.foreach(_.foreach(f))
                rhs match
                    case Maybe.Present(t) => f(t)
                    case Maybe.Absent     => ()
            case Tree.TypeDef(_, _) => ()
            case Tree.PackageDef(_, stats) =>
                stats.foreach(f)
            case Tree.ClassDef(_, template) =>
                f(template)
            case Tree.Template(parents, _, body) =>
                parents.foreach(f); body.foreach(f)
            case Tree.Super(qual, _) =>
                f(qual)
            case Tree.This(_) => ()
            case Tree.NamedArg(_, value) =>
                f(value)
            case Tree.Annotated(expr, annot) =>
                f(expr); f(annot)
            case Tree.Shared(_)       => ()
            case Tree.Modifier(_)     => ()
            case Tree.RecType(parent) => f(parent)
            case Tree.SuperType(t1, t2) =>
                f(t1); f(t2)
            case Tree.RefinedType(parent, _, info) =>
                f(parent); f(info)
            case Tree.AppliedType(tycon, args) =>
                f(tycon); args.foreach(f)
            case Tree.TypeBounds(lo, hi) =>
                f(lo); f(hi)
            case Tree.AnnotatedType(parent, a) =>
                f(parent); f(a)
            case Tree.AndType(l, r) =>
                f(l); f(r)
            case Tree.OrType(l, r) =>
                f(l); f(r)
            case Tree.ByNameType(arg) =>
                f(arg)
            case Tree.MatchType(bound, scrutinee, cases) =>
                f(bound); f(scrutinee); cases.foreach(f)
            case Tree.FlexibleType(arg) =>
                f(arg)
            case Tree.IdentTpt(_, _) => ()
            case Tree.SelectTpt(qual, _) =>
                f(qual)
            case Tree.SingletonTpt(tpe) =>
                f(tpe)
            case Tree.TermRefPkg(_) => ()
            case Tree.TypeRefPkg(_) => ()
            case Tree.TermRefSymbol(_, qual) =>
                f(qual)
            case Tree.TypeRefSymbol(_, qual) =>
                f(qual)
            case Tree.TermRefDirect(_) => ()
            case Tree.TypeRefDirect(_) => ()
            case Tree.SelectIn(qual, _, owner) =>
                f(qual); f(owner)
            case Tree.Import(qual, selectors) =>
                f(qual); selectors.foreach(f)
            case Tree.Export(qual, selectors) =>
                f(qual); selectors.foreach(f)
            case Tree.AnnotationNode(annotType, arg) =>
                f(annotType); f(arg)
            case Tree.RecThisAddr(_) => ()
            case Tree.Imported(qual) =>
                f(qual)
            case Tree.Renamed(_)   => ()
            case Tree.ByNameTpt(_) => ()
            case Tree.Bounded(bound) =>
                f(bound)
            case Tree.ExplicitTpt(_) => ()
            case Tree.Elided(_)      => ()
            case Tree.TypeRefTree(qual, _) =>
                f(qual)
            case Tree.TermRef(prefix, _) =>
                f(prefix)
            case Tree.SeqLiteral(elems, _) =>
                elems.foreach(f)
            case Tree.SelfDef(_, tpe) =>
                f(tpe)
            case Tree.SelectOuter(qual, _, _, _) =>
                f(qual)
            case Tree.Unknown(_, _) => ()
        end visit

        /** Direct structural child trees of this node. Leaf nodes return `Chunk.empty`.
          *
          * Materializes a Chunk for callers that need an indexable structure. Internal traversals should
          * prefer `visit` (non-allocating).
          */
        def children: Chunk[Tree] =
            val b = Chunk.newBuilder[Tree]
            visit(b += _)
            b.result()
        end children

        /** Pre-order traversal: visits this node then all descendants. */
        def foreach(f: Tree => Unit): Unit =
            f(this)
            visit(_.foreach(f))
        end foreach

        /** Collect all nodes matching `pf` in pre-order. Inline entry delegates to the non-inline body loop. */
        inline def collect[A](inline pf: PartialFunction[Tree, A]): Chunk[A] =
            collectImpl(pf)

        private def collectImpl[A](pf: PartialFunction[Tree, A]): Chunk[A] =
            val b = Chunk.newBuilder[A]
            foreach: t =>
                if pf.isDefinedAt(t) then b += pf(t)
            b.result()
        end collectImpl

        /** Find first node satisfying `p` in pre-order. Inline entry delegates to the non-inline body loop. */
        inline def find(inline p: Tree => Boolean): Maybe[Tree] =
            findImpl(p)

        private def findImpl(p: Tree => Boolean): Maybe[Tree] =
            var found: Maybe[Tree] = Maybe.Absent
            def go(t: Tree): Boolean =
                if found.isDefined then true
                else if p(t) then
                    found = Maybe(t)
                    true
                else
                    var hit = false
                    t.visit: c =>
                        if !hit && go(c) then hit = true
                    hit
                end if
            end go
            discard(go(this))
            found
        end findImpl

        /** Left-fold over all nodes in pre-order. Inline entry delegates to the non-inline body loop. */
        inline def foldLeft[A](z: A)(inline f: (A, Tree) => A): A =
            foldLeftImpl(z)(f)

        private def foldLeftImpl[A](z: A)(f: (A, Tree) => A): A =
            var acc = z
            foreach((t: Tree) => acc = f(acc, t))
            acc
        end foldLeftImpl

        /** True when any node in the subtree (including this node) satisfies `p`. Pre-order short-circuits on first match.
          * Inline entry delegates to the non-inline body loop.
          */
        inline def exists(inline p: Tree => Boolean): Boolean =
            existsImpl(p)

        private def existsImpl(p: Tree => Boolean): Boolean =
            if p(this) then true
            else
                var hit = false
                visit: c =>
                    if !hit && c.existsImpl(p) then hit = true
                hit
        end existsImpl

    end Tree

    // ── Supporting ADTs for the Symbol hierarchy ────────────────────────────

    /** Variance of a type parameter or abstract type member.
      *
      * Three cases: `Invariant` (no variance annotation), `Covariant` (declared with `+`), and
      * `Contravariant` (declared with `-`). Returned by `Symbol.TypeParam.variance` for every type parameter
      * in the model and by the bounds machinery wherever a variance position is meaningful.
      *
      * The decoder reads the variance directly from the TASTy flag set on the type parameter symbol (the
      * `CoVariant` and `ContraVariant` flag bits); the absence of either flag is `Invariant`. The convenience
      * accessor `Symbol.TypeParam.varianceLabel` returns the printable form (`""`, `"+"`, `"-"`) so callers
      * rendering signatures do not have to do their own dispatch.
      */
    enum Variance derives Schema, CanEqual:
        case Invariant, Covariant, Contravariant

    /** Lower / upper bounds on a type parameter or abstract type member.
      *
      * Carries the resolved `Type` values for both ends of the bound: `lower` is the lower bound (typically
      * `Type.Nothing` when unbounded), `upper` is the upper bound (typically `Type.Any` when unbounded). Used
      * by `Symbol.TypeParam.bounds`, `Symbol.AbstractType.bounds`, and `Symbol.OpaqueType.bounds`.
      *
      * **Naming overlap.** This is the Symbol-layer bounds type, distinct from `Tree.TypeBounds` which is the
      * wire-level AST node that appears inside a `Tree`. The Symbol layer is what user code typically wants:
      * `Tree.TypeBounds` only matters when traversing a decoded body tree. Both are kept for layer separation.
      *
      * Equality is structural via `derives CanEqual`; two `TypeBounds` values are equal when both bound
      * fields are equal.
      */
    final case class TypeBounds(lower: Type, upper: Type) derives Schema, CanEqual

    /** Source-level visibility of a symbol, derived from `Symbol.flags`.
      *
      * Five cases: `Public` (no `private` / `protected` modifier), `Private` (the `Private` flag is set),
      * `Protected` (the `Protected` flag is set), and the two scoped flavours `ScopedPrivate` /
      * `ScopedProtected` which indicate the `Local` flag is also set (Scala's `private[this]` /
      * `protected[this]` analogues).
      *
      * Returned by `Symbol.visibility`. The mapping from raw flag bits to this enum is in
      * `Symbol.visibility`; user code should match on the enum rather than poking at the underlying flags,
      * because the flag combinations that produce each case are not stable across kyo-tasty versions.
      */
    enum Visibility derives Schema, CanEqual:
        case Private, Protected, Public, ScopedPrivate, ScopedProtected

    /** Inheritance-openness level of a class-like symbol, derived from `Symbol.flags`.
      *
      * Four cases: `Final` (the `Final` flag is set; subclassing rejected), `Sealed` (the `Sealed` flag is
      * set; subclasses restricted to the same compilation unit and surfaced via `permittedSubclasses`),
      * `Open` (the `Open` flag is set; explicitly inheritable across compilation units), and `Default` (no
      * openness flag is set; subclassable but the compiler emits warnings under `-Wopen`).
      *
      * Returned by `Symbol.openLevel`. The order of precedence when multiple flags are set is
      * `Final > Sealed > Open > Default`, which is what `Symbol.openLevel` enforces.
      */
    enum OpenLevel derives Schema, CanEqual:
        case Open, Default, Sealed, Final

    /** Format selector for `Symbol.show(format: ShowFormat)`.
      *
      * Three cases controlling how a symbol prints when callers want a single string per symbol.
      * `FullyQualified` returns the dotted FQN (`example.Box`). `Simple` returns just the local name
      * (`Box`). `Code` returns the source-shaped declaration string (`class Box[T] extends Shape`) which
      * is what `Symbol.signature` produces.
      *
      * Equivalent to a function from `Symbol` to `String`; provided as an enum so callers can pattern-match
      * on the chosen format when rendering, log it, or pass it through a config. The default rendering used
      * by `Symbol.show()` (no argument) is `FullyQualified`.
      */
    enum ShowFormat derives Schema, CanEqual:
        case FullyQualified, Simple, Code

    // (public SymbolBody deleted; canonical lives at kyo.internal.tasty.symbol.SymbolBody.)

    // ── MemberScope enum ─────────────────────────────────────────────────────

    /** Scope selector for `Tasty.members` and `Tasty.findMember`.
      *
      * Three cases: `Declared` (only symbols directly declared on the receiver), `Inherited` (only symbols
      * inherited from parent types, not directly declared), and `All` (union of declared and inherited,
      * deduplicated by simple name keeping the most-specific occurrence).
      *
      * The default scope is `Declared`, matching the HEAD `declaredMembers` behavior and the most common
      * use case (checking what a class introduces, not what it inherits).
      */
    enum MemberScope derives Schema, CanEqual:
        case Declared, Inherited, All

    // ── Symbol ──────────────────────────────────────────────────────────────

    /** Sealed-trait root of the typed Symbol hierarchy. Every symbol is one of 14 final case classes; pattern matching is exhaustive under
      * `-Xfatal-warnings`.
      *
      * Constructed by `ClasspathOrchestrator.materializeSymbols` during Pass C of classpath loading. After construction, every field is
      * immutable. Cross-symbol references use `SymbolId` (an opaque Int) rather than direct `Symbol` pointers to avoid case-class cycles.
      *
      * Resolution methods (`owner`, `fullName`, `binaryName`, etc.) require a `Classpath` in scope and are pure data accessors with no
      * effect row.
      *
      * Equality contract: `derives CanEqual` enables `==` comparisons between any two `Symbol` values regardless of their concrete
      * subtype. Equality is implemented via a custom `equals` that compares `SymbolId` values: two symbols are equal if and only if
      * `id.value == other.id.value` and neither id is the sentinel (-1). Comparing a `Symbol.Class` to a `Symbol.Trait` always returns
      * `false` even when they represent the same named entity after a kind-change, because their `SymbolId` values differ. Use `id` for
      * cross-kind identity checks.
      */
    /** Sealed-trait root of the pure-data Symbol hierarchy.
      *
      * Every symbol is one of 14 final case classes (or one intermediate sealed trait); pattern matching is
      * exhaustive under `-Xfatal-warnings`. All fields are immutable data; every query operation (owner,
      * fullName, members, etc.) lives on `object Tasty.*` rather than on Symbol itself, so this type has
      * no dependency on `Classpath` and can be freely serialized via `derives Schema`.
      *
      * Flag-based predicates (isFinal, isAbstract, isPrivate, etc.) are pure bitmask checks on `flags`
      * and are retained on the sealed trait for ergonomics; they require no `Classpath`.
      *
      * For kind discrimination, use sealed pattern matching: `sym match { case _: Symbol.Class => ... }`
      * rather than the removed `sym.isClass` / `sym.kind` predicates.
      */
    sealed trait Symbol derives CanEqual:
        def id: SymbolId
        def name: Name
        def flags: Flags
        def ownerId: SymbolId
        def scaladoc: Maybe[String]
        def sourcePosition: Maybe[Position]

        // 40 flag predicates on base trait: pure bitmask checks, no Classpath dependency
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
          * Dotty emits Flag.Macro on enum-case synthetic methods (ordinal, productElement, etc.). These are NOT
          * user-defined macros. Excluding symbols that also carry Flag.Synthetic avoids false positives. Real macro methods
          * (defined with the `macro` keyword or `inline`) are not synthetic. The `isInstanceOf[Symbol.Method]` gate
          * prevents non-method symbols from matching even if they somehow carry Flag.Macro.
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
        def hasDefault: Boolean      = flags.contains(Flag.HasDefault)
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

        /** Symbol is both `inline` and `transparent`. */
        def isTransparentInline: Boolean = flags.contains(Flag.Inline) && flags.contains(Flag.Transparent)

        /** Symbol marked as a `given` instance (using-clause parameters excluded). */
        def isGiven: Boolean  = flags.contains(Flag.Given) && !this.isInstanceOf[Symbol.Parameter]
        def isOpaque: Boolean = flags.contains(Flag.Opaque)

        /** Simple name as a `String`. Mirrors `name.asString`. */
        def simpleName: String =
            import Name.asString
            name.asString

        /** Value-level discriminator for snapshot wire format (private[kyo]).
          *
          * The primary consumer is SnapshotWriter which writes a kind byte. User code must use
          * sealed pattern matching instead.
          */
        private[kyo] def kind: SymbolKind = this match
            case _: Symbol.Package => SymbolKind.Package
            // EnumCase check before Class so EnumCase takes priority.
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

        /** Typed grouped queries derived from flags (pure, no Classpath). */
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

    end Symbol

    /** Companion of `Symbol`; carries the intermediate sealed traits, the 14 final case classes, and internal factories used by the loader. */
    object Symbol:

        // ── Intermediate sealed traits ────────────────────────────────────────

        /** Common Class / Trait / Object / EnumCase contract: pure data fields for classlike symbols.
          *
          * Raw fields are the data as decoded from TASTy/classfile bytes. No query methods; use `object Tasty.*`
          * operations (e.g. `Tasty.parents(sym)`, `Tasty.members(sym)`) for classpath-dependent queries.
          *
          * `ClassLike` is the recommended pattern-match target when the caller wants to handle all four
          * classlike subtypes uniformly: `case c: Symbol.ClassLike => ...`.
          */
        sealed trait ClassLike extends Symbol:
            def javaMetadata: Maybe[Java.Metadata]
            def parentTypes: Chunk[Type]
            def typeParamIds: Chunk[SymbolId]
            def declarationIds: Chunk[SymbolId]
            def annotations: Chunk[Annotation]
            def javaAnnotations: Chunk[Java.Annotation]

        end ClassLike

        // ── 14 final case classes ─────────────────────────────────────────────

        /** A `class` declaration: Scala source `class`, Java `class`, the lifted backing class of a Scala 3 `enum`.
          *
          * `permittedSubclassIds` is `Present(ids)` for sealed parents; `Absent` for non-sealed classes.
          * `javaMetadata` is `Present` for symbols sourced from `.class` files.
          * Use `Tasty.bodyTree(sym)` to decode the AST body bytes for this symbol.
          * `EnumCase` is a peer of `Class` under `ClassLike`, not a subtype; pattern-match `Symbol.EnumCase` before
          * `Symbol.Class` if you need to discriminate enum cases.
          */
        final case class Class(
            id: SymbolId,
            name: Name,
            flags: Flags,
            ownerId: SymbolId,
            scaladoc: Maybe[String],
            sourcePosition: Maybe[Position],
            javaMetadata: Maybe[Java.Metadata],
            parentTypes: Chunk[Type],
            typeParamIds: Chunk[SymbolId],
            declarationIds: Chunk[SymbolId],
            permittedSubclassIds: Maybe[Chunk[SymbolId]],
            annotations: Chunk[Annotation],
            javaAnnotations: Chunk[Java.Annotation]
        ) extends ClassLike derives Schema, CanEqual

        /** A single case of a Scala 3 enum.
          *
          * `EnumCase` is now a peer of `Symbol.Class` under `Symbol.ClassLike`, not a subtype of `Class`.
          * Pattern-match on `Symbol.EnumCase` directly; it will NOT match a `Symbol.Class` arm.
          * The `Flag.Enum` and `Flag.Case` flags are always set on this symbol.
          */
        final case class EnumCase(
            id: SymbolId,
            name: Name,
            flags: Flags,
            ownerId: SymbolId,
            scaladoc: Maybe[String],
            sourcePosition: Maybe[Position],
            javaMetadata: Maybe[Java.Metadata],
            parentTypes: Chunk[Type],
            typeParamIds: Chunk[SymbolId],
            declarationIds: Chunk[SymbolId],
            permittedSubclassIds: Maybe[Chunk[SymbolId]],
            annotations: Chunk[Annotation],
            javaAnnotations: Chunk[Java.Annotation]
        ) extends ClassLike derives Schema, CanEqual

        /** A `trait` declaration: Scala source `trait` or a Java `interface` (which the loader normalizes to this
          * representation). Shares the `ClassLike` shape with `Class` and `Object`; the difference is that a Trait
          * cannot be `new`-ed directly and that Java interfaces collapse `default` and `static` methods into its
          * `declarationIds`.
          *
          * `parentTypes` carries the source-order `extends`/`with` types; for a Java interface the head is
          * `java.lang.Object` followed by the declared interface parents. `permittedSubclassIds` is `Present` for
          * sealed traits, `Absent` otherwise (use `isSealed` to discriminate). `javaMetadata` is `Present` for
          * interfaces sourced from `.class` files; use `Tasty.bodyTree(sym)` to decode the template envelope lazily.
          *
          * The narrow `ClassLike` accessors (`methods`, `vals`, `vars`, `fields`, `nestedTypes`, ...) work the same
          * here as on `Class`; `vars` and `fields` are typically empty for Scala-sourced traits and non-empty for
          * Java interfaces with `default` accessors.
          */
        final case class Trait(
            id: SymbolId,
            name: Name,
            flags: Flags,
            ownerId: SymbolId,
            scaladoc: Maybe[String],
            sourcePosition: Maybe[Position],
            javaMetadata: Maybe[Java.Metadata],
            parentTypes: Chunk[Type],
            typeParamIds: Chunk[SymbolId],
            declarationIds: Chunk[SymbolId],
            permittedSubclassIds: Maybe[Chunk[SymbolId]],
            annotations: Chunk[Annotation],
            javaAnnotations: Chunk[Java.Annotation]
        ) extends ClassLike derives Schema, CanEqual

        /** A Scala `object` declaration: the singleton companion of a class/trait, a top-level `object`, or the
          * lifted companion that holds an enum's generated members. Java has no equivalent; symbols decoded from
          * `.class` files never produce `Object`.
          *
          * Carries the same shape as `Class` minus `permittedSubclassIds` (objects are not sealable parents). Use
          * `companion(using cp)` (inherited from `ClassLike`) to walk from the object to its companion class or
          * trait, when one exists. `declarationIds` includes any nested objects, generated `values`/`valueOf` for
          * enum companions, and any user-declared `def`s, `val`s, or nested types.
          *
          * Note: the case-class identifier is `Object` and shadows `java.lang.Object` and `scala.Object` inside
          * this file; callers writing `import kyo.Tasty.Symbol.Object` should be aware of the same shadowing risk.
          */
        final case class Object(
            id: SymbolId,
            name: Name,
            flags: Flags,
            ownerId: SymbolId,
            scaladoc: Maybe[String],
            sourcePosition: Maybe[Position],
            javaMetadata: Maybe[Java.Metadata],
            parentTypes: Chunk[Type],
            typeParamIds: Chunk[SymbolId],
            declarationIds: Chunk[SymbolId],
            annotations: Chunk[Annotation],
            javaAnnotations: Chunk[Java.Annotation]
        ) extends ClassLike derives Schema, CanEqual

        /** A `def`: a Scala source `def`, a Scala constructor (`<init>`), an extension method, a `transparent inline
          * def`, or a Java method. `paramListIds` records parameter groups in source order; each inner `Chunk`
          * resolves through `paramLists(using cp)` into `Symbol.Parameter` entries. `typeParamIds` carries the
          * method's own type parameters; per-parameter-list type parameters appear under those parameter symbols.
          *
          * `declaredType` is the method's `MethodType` view (parameter types + result), `Present` for symbols with
          * a recorded signature and `Absent` for synthetics whose type the loader did not retain. `returnType`
          * unwraps the `Type.Function` or `Type.ContextFunction` result; for non-function shapes it returns the
          * declared type as-is. Use `Tasty.bodyTree(sym)` to decode the AST envelope for the implementation;
          * abstract methods (`flags.contains(Flag.Abstract)`) and methods sourced from `.class` files return Absent.
          *
          * `javaMetadata` is `Present` for Java methods and holds the throws clauses, JVM access flags, parameter
          * names from the JVM `MethodParameters` attribute, and runtime/compile-time annotations. `annotations` is
          * the Scala-side annotation list and is independent.
          */
        final case class Method(
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
            javaMetadata: Maybe[Java.Metadata]
        ) extends Symbol derives Schema, CanEqual

        /** A Scala `val`: an immutable value member of a class, trait, object, or top-level package. Also represents
          * a Scala 3 enum case that has no parameters (the case is lifted to a `Val` on the enum's companion with
          * `Flag.Case | Flag.Enum` set). Java has no Scala-shaped `val`; Java `final` fields surface as `Field`.
          *
          * `declaredType` is `Present` for any Scala-sourced `val`; the only `Absent` case is synthetic ValDefs the
          * loader has reason to keep without a recorded type. Use `Tasty.bodyTree(sym)` to decode the AST for the
          * right-hand side; returns Absent for abstract members and for cases where the loader did not retain the
          * initializer.
          */
        final case class Val(
            id: SymbolId,
            name: Name,
            flags: Flags,
            ownerId: SymbolId,
            scaladoc: Maybe[String],
            sourcePosition: Maybe[Position],
            declaredType: Maybe[Type],
            annotations: Chunk[Annotation]
        ) extends Symbol derives Schema, CanEqual

        /** A Scala `var`: a mutable value member. Carries `Flag.Mutable`. Same shape as `Val`; the distinction is
          * the semantics that callers expect from the symbol's flags, not the field layout. Synthetic getter/setter
          * `Method` symbols are emitted alongside the `Var` on the owning class.
          *
          * Java mutable fields surface as `Field` (with the JVM access-flag projection), not as `Var`; only Scala
          * sources produce `Var`.
          */
        final case class Var(
            id: SymbolId,
            name: Name,
            flags: Flags,
            ownerId: SymbolId,
            scaladoc: Maybe[String],
            sourcePosition: Maybe[Position],
            declaredType: Maybe[Type],
            annotations: Chunk[Annotation]
        ) extends Symbol derives Schema, CanEqual

        /** A Java field decoded from a `.class` file. Holds the field's declared type, the JVM access flags via
          * `javaMetadata`, and any class-retention annotations under `javaAnnotations`. Has no `body` slot because
          * the JVM does not carry field initializers as serialized bytes; constant-pool literal values are visible
          * via the field's owner's `bodyTree` if needed.
          *
          * Scala-sourced backing fields surface as `Val` or `Var`, not as `Field`; the `Field` case is the Java
          * side of the split. The `isJvmPublic` / `isJvmPrivate` / `isJvmProtected` / `isJvmStatic` / `isJvmFinal`
          * predicates project the access-flags bitmask without forcing callers to know the JVMS bit positions.
          */
        final case class Field(
            id: SymbolId,
            name: Name,
            flags: Flags,
            ownerId: SymbolId,
            scaladoc: Maybe[String],
            sourcePosition: Maybe[Position],
            declaredType: Maybe[Type],
            javaMetadata: Maybe[Java.Metadata],
            javaAnnotations: Chunk[Java.Annotation]
        ) extends Symbol derives Schema, CanEqual

        /** A Scala `type X[T] = Body` declaration: a transparent name for another type. `body` is the right-hand
          * side as a fully-resolved `Type`; pattern-match on its shape to follow the alias. `typeParamIds` records
          * any type parameters introduced by the alias and resolves via `typeParams(using cp)` into
          * `Symbol.TypeParam` entries.
          *
          * Unlike `OpaqueType`, type aliases do not introduce a new identity at the type level: the alias and its
          * body are interchangeable. Java has no source equivalent; type aliases come only from Scala sources.
          */
        final case class TypeAlias(
            id: SymbolId,
            name: Name,
            flags: Flags,
            ownerId: SymbolId,
            scaladoc: Maybe[String],
            sourcePosition: Maybe[Position],
            body: Maybe[Type],
            typeParamIds: Chunk[SymbolId],
            annotations: Chunk[Annotation]
        ) extends Symbol derives Schema, CanEqual

        /** A Scala 3 `opaque type X = Body` (with optional bounds and type parameters). Unlike a `TypeAlias`,
          * an opaque type carries its own identity outside the defining scope: callers see only the declared
          * `bounds` and cannot substitute `body` freely.
          *
          * `body` is the underlying type as visible inside the defining scope; `bounds` are the public upper and
          * lower bounds used by the outside world. `typeParamIds` carries any type parameters in the declaration.
          * `Flag.Opaque` is always set; carrying both the `bounds` and `body` lets a caller render either the
          * public or the private view without re-decoding.
          */
        final case class OpaqueType(
            id: SymbolId,
            name: Name,
            flags: Flags,
            ownerId: SymbolId,
            scaladoc: Maybe[String],
            sourcePosition: Maybe[Position],
            body: Maybe[Type],
            bounds: TypeBounds,
            typeParamIds: Chunk[SymbolId],
            annotations: Chunk[Annotation]
        ) extends Symbol derives Schema, CanEqual

        /** A Scala abstract type member: `type X` (with optional bounds) declared inside a class, trait, or object
          * without a right-hand side. Distinct from `TypeParam` (which appears as a parameter of a generic class
          * or method) and from `TypeAlias` (which carries a body).
          *
          * `bounds` is the declared `>: lower <: upper` bound pair; both default to `Type.Nothing` / `Type.Any`
          * when no bound was written. Java has no source equivalent; abstract type members come only from Scala.
          *
          * Annotation positions and source positions are preserved when present; flags carry the visibility and
          * other modifiers such as `Flag.Sealed` (rare but legal for abstract types in dotty).
          */
        final case class AbstractType(
            id: SymbolId,
            name: Name,
            flags: Flags,
            ownerId: SymbolId,
            scaladoc: Maybe[String],
            sourcePosition: Maybe[Position],
            bounds: TypeBounds,
            annotations: Chunk[Annotation]
        ) extends Symbol derives Schema, CanEqual

        /** A type parameter symbol of a generic class, trait, type alias, opaque type, or method. The owning symbol
          * is reachable via `ownerId`; the parameter's index in its owner's parameter list is its position in the
          * owner's `typeParams(using cp)`.
          *
          * `bounds` carries the declared `>: lower <: upper` constraints (defaulting to `Type.Nothing` and
          * `Type.Any` when absent). `variance` is one of `Invariant`, `Covariant`, `Contravariant`; the
          * `varianceLabel` convenience returns the matching source sigil (`""`, `"+"`, `"-"`) for printing.
          *
          * No `scaladoc` is recorded for type parameters (they are accessor-shadowed to `Absent`); flags carry
          * `Flag.TypeParameter` plus any user annotations such as `Flag.Sealed` on bounded type parameters.
          */
        final case class TypeParam(
            id: SymbolId,
            name: Name,
            flags: Flags,
            ownerId: SymbolId,
            sourcePosition: Maybe[Position],
            bounds: TypeBounds,
            variance: Variance
        ) extends Symbol derives Schema, CanEqual:
            def scaladoc: Maybe[String] = Maybe.Absent
        end TypeParam

        /** A value parameter of a method or constructor. Owned by the enclosing `Method`; its position in the
          * enclosing `paramLists` reflects the parameter's source order within its parameter group.
          *
          * `declaredType` is the parameter's declared type after by-name and repeated wrapping (so an `=> Foo`
          * parameter has `declaredType = Type.ByName(Type.Named(foo))` and a `Foo*` parameter has
          * `Type.Repeated(...)`). The `isByName` and `isRepeated` predicates inspect those wrappers without
          * forcing a manual pattern match. `isImplicit` returns true for `given` and `implicit` parameters
          * (`Flag.Given` is set in both cases).
          *
          * `defaultArgId` is `Present` for parameters that have a default value; the referenced symbol is the
          * synthetic accessor method emitted by dotty (`foo$default$1` and friends). `defaultArg(using cp)`
          * resolves the id without forcing the caller to deal with the indirection.
          *
          * No `scaladoc` is carried (parameters do not get their own scaladoc blocks); `annotations` covers any
          * source-level annotations on the parameter itself.
          */
        final case class Parameter(
            id: SymbolId,
            name: Name,
            flags: Flags,
            ownerId: SymbolId,
            sourcePosition: Maybe[Position],
            declaredType: Maybe[Type],
            defaultArgId: Maybe[SymbolId],
            annotations: Chunk[Annotation]
        ) extends Symbol derives Schema, CanEqual:
            def scaladoc: Maybe[String] = Maybe.Absent
        end Parameter

        /** A package symbol: the root package or any nested package. The root package has `name` equal to the empty
          * `Name` and `ownerId == id` (it is its own owner); descend through `members(using cp)` to walk a
          * classpath as a tree.
          *
          * `memberIds` carries direct children: every top-level `Class`, `Trait`, `Object`, top-level `Method` or
          * `Val` from a Scala package object, and any nested `Package`. The narrowed accessors (`classes`,
          * `traits`, `objects`, `classLike`, plus `subPackages(using cp)`) project this chunk by kind without
          * forcing the caller to pattern-match.
          *
          * Packages carry no `scaladoc`, no `sourcePosition`, no `annotations`, and no `body`: they are purely
          * structural anchors in the symbol graph. Equality is by `id`, as for every Symbol.
          */
        final case class Package(
            id: SymbolId,
            name: Name,
            flags: Flags,
            ownerId: SymbolId,
            memberIds: Chunk[SymbolId]
        ) extends Symbol derives Schema, CanEqual:
            def scaladoc: Maybe[String]         = Maybe.Absent
            def sourcePosition: Maybe[Position] = Maybe.Absent
        end Package

    end Symbol

    // Schema[Symbol] derived after object Symbol is fully defined.
    // Schema[Symbol.ClassLike] is provided via object Symbol.ClassLike companion (inside object Symbol)
    // which Scala 3 implicit lookup finds via companion object rules when expanding Schema[Symbol].
    given schemaSymbol: Schema[Symbol] = Schema.derived

    // ── Java namespace ──────────────────────────────────────────────────────

    /** Namespace for all Java/JVM-specific types decoded from `.class` files.
      *
      * All types that are specific to the JVM binary format and have no Scala-source equivalent live here.
      * `Java.Annotation` and `Java.Metadata` cover the JVM annotation and classfile metadata models;
      * `Java.Module.*` covers the JPMS module-info.class descriptors. Scala-source annotations on the
      * same symbols still live in the `Annotation` type (not here).
      */
    object Java:

        /** A single declared field of a Java record (JVMS `Record` attribute entry).
          *
          * Carried by `Java.Metadata.recordComponents: Chunk[RecordComponent]` on the symbol of the record
          * class itself. Java records expose their components in declaration order; the loader preserves that
          * order so the chunk index aligns with the canonical constructor parameter list.
          *
          * `name` is the component's source-level name; `tpe` is its declared type as resolved against the
          * classpath at load time. Equality is structural across both fields. Present only on JVM symbols
          * (the attribute is JVMS-defined); `Absent` on TASTy-sourced symbols even if they happen to be record
          * classes, because the record-ness lives in the JVM attribute and not in the TASTy ADT.
          */
        final case class RecordComponent(name: Name, tpe: Type) derives Schema, CanEqual

        /** Parameter-name table for one method overload: the method's name plus the names of its parameters in source order.
          *
          * Carried by `Java.Metadata.paramNames: Chunk[ParamGroup]` on the owning class symbol. Java classfiles
          * record parameter names in a `MethodParameters` attribute when compiled with `-parameters`; the loader
          * groups those entries by their owning method so each `ParamGroup` corresponds to one overload of
          * `methodName`. `parameterNames` is in source order and may be empty (no `MethodParameters` attribute or
          * a zero-arity method).
          *
          * Equality is structural across both fields (case class auto-generation).
          */
        final case class ParamGroup(methodName: Name, parameterNames: Chunk[Name]) derives Schema, CanEqual

        /** The enclosing-method context for a local or anonymous class (JVMS `EnclosingMethod` attribute).
          *
          * Carried by `Java.Metadata.enclosingMethod: Maybe[EnclosingMethod]` on the symbol of a local or
          * anonymous class. The JVMS records the immediately enclosing method for any class that was declared
          * inside one; absent otherwise. `owner` is the enclosing method's owner class symbol, and `methodName`
          * is the enclosing method's source-level name. Use this to walk back from an anonymous inner class
          * symbol to the method that declared it.
          *
          * Equality is structural across both fields (case class auto-generation).
          */
        final case class EnclosingMethod(owner: Symbol, methodName: Name) derives Schema, CanEqual

        /** JVM-only metadata attached to symbols sourced from `.class` files.
          *
          * Carried by `Symbol.javaMetadata: Maybe[Java.Metadata]` and `Absent` on symbols that come from TASTy
          * sources only (where the equivalent information lives in `Symbol.flags`, `Annotation`, etc.). This
          * companion exposes the JVM-specific attributes that have no clean TASTy analogue: the JVM access flag
          * word, the `throws` clause, the `EnclosingMethod` attribute for local / anonymous classes, the `Record`
          * component table for Java records, the bootstrap method table, the nest host / nest members for
          * Java 11+ nestmates, parameter-name groups, and runtime type annotations.
          *
          * **Annotations.** `annotations` carries `RuntimeVisibleAnnotations` and `RuntimeInvisibleAnnotations`
          * decoded into the `Java.Annotation` ADT; `runtimeTypeAnnotations` covers the type-annotation flavour
          * (`RuntimeVisibleTypeAnnotations` and its invisible sibling). Scala-side annotations on the same symbol
          * still live in the symbol's `annotations` field, not here.
          *
          * **Access flags.** `accessFlags` is the raw 16-bit access flag word; `isJvmPublic`, `isJvmPrivate`,
          * `isJvmProtected`, `isJvmStatic`, and `isJvmFinal` are the common predicates. For flags without a
          * predicate, mask `accessFlags` against the JVMS constants directly.
          */
        final case class Metadata(
            throwsTypes: Chunk[Type],
            annotations: Chunk[Annotation],
            enclosingMethod: Maybe[EnclosingMethod],
            accessFlags: Int,
            recordComponents: Chunk[RecordComponent],
            bootstrapMethods: Chunk[Chunk[Int]],
            nestHost: Maybe[Symbol],
            nestMembers: Chunk[Symbol],
            paramNames: Chunk[ParamGroup],
            runtimeTypeAnnotations: Chunk[Annotation]
        ) derives Schema, CanEqual

        /** A Java retention-class annotation decoded from a `.class` file's
          * `RuntimeVisibleAnnotations` / `RuntimeInvisibleAnnotations` attribute.
          *
          * Kept structurally separate from `Tasty.Annotation` (the Scala-source annotation type) because the value
          * spaces are different: a Java annotation's element values are primitive constants, class literals, enum
          * constants, nested annotations, and arrays thereof, while a Scala annotation carries arbitrary
          * `Tree.Apply` arguments. Mixing the two into a single ADT would require either lossy normalisation or
          * a sum type at every callsite; keeping them parallel keeps each side honest.
          *
          * `annotationClass` is the resolved `Symbol` for the annotation interface (e.g. the symbol of
          * `java.lang.SuppressWarnings`). `values` is the ordered list of `(elementName, value)` pairs as they
          * appeared in the classfile; element names are interned `Name` values and ordering matches the source
          * declaration. Element values are typed via the `Java.Annotation.Value` enum nested in the companion.
          *
          * **Querying.** `Symbol.hasAnnotation(fqn)` and `Symbol.findAnnotation(fqn)` walk both this list and the
          * Scala `annotations` list (where applicable), so the common "is this symbol annotated with X" question
          * does not need to branch on the value space.
          */
        final case class Annotation(annotationClass: Symbol, values: Chunk[(Name, Annotation.Value)])
            derives CanEqual
        object Annotation:
            /** Typed value space for a Java annotation element. Mirrors the `element_value` shapes defined by
              * JVMS §4.7.16.1: every primitive constant, string, class literal, enum constant, nested annotation,
              * and array of any of those. Cases nest recursively through `ArrayVal` and `AnnotationVal`.
              *
              * Note: the JVM annotation format collapses `char`, `byte`, and `short` element values into
              * `IntVal`, so there are no separate `CharVal`, `ByteVal`, or `ShortVal` cases.
              */
            enum Value derives CanEqual:
                case StringVal(s: String)
                case IntVal(i: Int)
                case LongVal(l: Long)
                case FloatVal(f: Float)
                case DoubleVal(d: Double)
                case BoolVal(b: Boolean)
                case ClassVal(tpe: Type)
                case EnumVal(enumType: Symbol, constant: Name)
                case ArrayVal(elements: Chunk[Value])
                case AnnotationVal(nested: Annotation)
            end Value
        end Annotation

        // Schema for Java.Annotation.Value: recursive type requires a lazy given.
        // Java.Annotation.Value.AnnotationVal contains Annotation which contains Value.
        // The lazy initialization breaks the compile-time recursion.
        // Unsafe: null.asInstanceOf is used here intentionally to break the mutual-recursion cycle
        // between Java.Annotation and Java.Annotation.Value at Schema derivation time (Q-004 / RI-004).
        private var _schemaAnnotationValue: Schema[Annotation.Value] =
            null.asInstanceOf[Schema[Annotation.Value]]
        given schemaAnnotationValue: Schema[Annotation.Value] =
            if _schemaAnnotationValue == null then
                _schemaAnnotationValue = Schema.derived[Annotation.Value]
            _schemaAnnotationValue
        end schemaAnnotationValue

        // Unsafe: null.asInstanceOf breaks the mutual-recursion cycle at Schema derivation time (same pattern as _schemaAnnotationValue above).
        private var _schemaAnnotation: Schema[Annotation] = null.asInstanceOf[Schema[Annotation]]
        given schemaAnnotation: Schema[Annotation] =
            if _schemaAnnotation == null then
                _schemaAnnotation = Schema.derived[Annotation]
            _schemaAnnotation
        end schemaAnnotation

        given canEqualAnnotationValue: CanEqual[Annotation.Value, Annotation.Value] = CanEqual.canEqualAny
        given canEqualAnnotation: CanEqual[Annotation, Annotation]                  = CanEqual.canEqualAny

        /** JPMS module-info.class types. */
        object Module:

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
            final case class Descriptor(
                name: String,
                version: Maybe[String],
                requires: Chunk[Requires],
                exports: Chunk[Exports],
                opens: Chunk[Opens],
                uses: Chunk[String],
                provides: Chunk[Provides]
            ) derives Schema, CanEqual

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
            final case class Requires(
                name: String,
                version: Maybe[String],
                isTransitive: Boolean,
                isStaticPhase: Boolean
            ) derives Schema, CanEqual

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
            final case class Exports(
                packageName: String,
                targets: Chunk[String],
                flags: Long
            ) derives Schema, CanEqual

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
            final case class Opens(
                packageName: String,
                targets: Chunk[String],
                flags: Long
            ) derives Schema, CanEqual

            /** Describes a JPMS `provides` directive (a service implementation).
              *
              * @param serviceName
              *   The service interface class name in dotted form.
              * @param implementations
              *   The implementation class names in dotted form.
              */
            final case class Provides(
                serviceName: String,
                implementations: Chunk[String]
            ) derives Schema, CanEqual

        end Module

    end Java

    // ── Pickle (in-memory TASTy + classfile bytes) ──────────────────────────

    /** In-memory TASTy pickle: header UUID, format version, and raw `.tasty` bytes.
      *
      * A `Pickle` is the smallest input unit the classpath loader accepts: one `.tasty` file decoded into
      * its header and body. `Tasty.withPickles` and `Classpath.fromPicklesWithSymbols` accept a `Chunk`
      * of `Pickle` values for tests and out-of-band classpath construction; the standard `Tasty.withClasspath`
      * path reads pickles from files / JARs and never exposes them to user code. `bytes` is the unmodified
      * `.tasty` payload (header included), captured as a `Span[Byte]` so the loader can slice subsections
      * without copying.
      *
      * **UUID representation.** The `uuid` field is a `String` because the TASTy header stores the UUID
      * inline as two little-endian longs; treating it as an opaque hex string avoids parsing it twice
      * (once to validate, once to render in diagnostics) on the hot path. `TastyError.InconsistentClasspath`
      * carries `expectedUuid` and `foundUuid` as `java.util.UUID` instead: those values are constructed at
      * the point a mismatch is detected and consumed at the API boundary, where the structured type is
      * preferable for equality and formatting. The dual representation is intentional; do not normalise
      * one to the other without measuring the allocation cost on the cold-load path.
      *
      * **Equality.** Structural over all three fields (case class auto-generation). Two `Pickle` values
      * are equal when their UUIDs match, their versions match, and their byte spans compare element-wise.
      */
    final case class Pickle(uuid: String, version: Version, bytes: Span[Byte]) derives Schema, CanEqual:
        /** Human-readable summary: `Pickle(<uuid> v<version> <n>B)`. */
        def show: String = s"Pickle($uuid v${version.show} ${bytes.size}B)"
    end Pickle

    // ── Classpath ───────────────────────────────────────────────────────────

    /** Immutable snapshot of a fully-loaded TASTy classpath.
      *
      * All fields are plain immutable values populated once during `init` and never mutated. Reading any
      * field after `init` returns is a pure operation with no effect row and no `AllowUnsafe`.
      *
      * Pure data: no mutable state. Body decoding (bodyTree) is now on `object Tasty` and reads
      * the body memo from the `DecodeContext` carried by the active `Binding` in `TastyState.bindingLocal`.
      *
      * All query operations live on `object Tasty.*` and accept an explicit `(using cp: Classpath)`.
      *
      * `derives Schema, CanEqual`: Schema derivation is available after `object Classpath` closes (see
      * `given schemaClasspath` below), once `Classpath.Indices` is in scope.
      *
      * Instances are obtained via `Tasty.withClasspath`, `Tasty.withPickles`, or constructed directly
      * from the case class for tests.
      */
    final case class Classpath(
        symbols: Chunk[Symbol],
        indices: Classpath.Indices,
        errors: Chunk[TastyError],
        modules: Chunk[Java.Module.Descriptor],
        rootSymbolId: SymbolId
    ):

        private def symbolsOfKind[A <: Symbol](k: SymbolKind): Chunk[A] =
            if symbols.isEmpty then Chunk.empty
            else
                val b = Chunk.newBuilder[A]
                symbols.foreach:
                    // flow-allow: asInstanceOf -- guarded by s.kind == k; the kind discriminant ensures the runtime type matches A.
                    case s if s.kind == k => b += s.asInstanceOf[A]
                    case _                => ()
                b.result()

        /** O(1) Symbol lookup by SymbolId. Returns the Symbol at index `id.value`, or `Maybe.Absent` for out-of-range or negative ids.
          *
          * SymbolIds are only valid within the Classpath that produced them. Passing a SymbolId from one classpath into another classpath's
          * `symbol(id)` returns whatever Symbol happens to sit at that index in the receiving classpath (usually an unrelated symbol),
          * not the originating one. Cross-classpath operations should resolve by FQN via `findSymbol` / `findClass` / `findObject`, not
          * by SymbolId.
          *
          * Returns `Maybe.Absent` for:
          *   - `id.value < 0`: any negative index including the canonical sentinel -1.
          *   - `id.value >= symbols.length`: out-of-range positive index.
          *   - Empty classpath (`symbols.isEmpty`, `rootSymbolId.value == -1`): `cp.symbol(cp.rootSymbolId)` returns `Maybe.Absent`.
          */
        def symbol(id: SymbolId): Maybe[Symbol] =
            val idx = SymbolId.value(id)
            if idx >= 0 && idx < symbols.length then Maybe.Present(symbols(idx))
            else Maybe.Absent
        end symbol

        /** Look up any symbol by fully-qualified dotted name.
          *
          * Pure O(1) lookup in the immutable `indices.byFqn` map. Returns `Absent` if the FQN is not registered. For typed lookups that
          * narrow to a specific subtype, use `findClass`, `findTrait`, `findObject`, `findClassLike`, or `findPackage`.
          *
          * Null safety: a `null` `fqn` argument resolves to `Maybe.Absent` (Scala Map.get(null) returns None). No NPE is raised.
          * Defensive null checks in call sites are unnecessary.
          */
        def findSymbol(fqn: String): Maybe[Symbol] =
            indices.byFqn.get(fqn) match
                case Maybe.Present(id) => symbol(id)
                case Maybe.Absent      => Maybe.Absent

        /** Look up a class symbol by fully-qualified dotted name.
          *
          * Pure O(1) lookup in the immutable `indices.byFqn` map. Returns `Absent` if the FQN is not registered or resolves to a non-Class
          * symbol (e.g., a Trait or Object). Use `findClassLike` to match any class-like symbol regardless of subtype.
          *
          * Includes sealed abstract classes (e.g. `scala.Option`); use `findConcreteClass` to restrict to non-abstract classes.
          *
          * Null safety: a `null` `fqn` argument returns `Maybe.Absent`; no NPE is raised. An empty string `""` also returns
          * `Maybe.Absent` because no symbol is registered under the empty key.
          *
          * Example:
          * ```scala
          *   val sym: Maybe[Symbol.Class] = cp.findClass("scala.collection.List")
          * ```
          */
        def findClass(fqn: String): Maybe[Symbol.Class] =
            indices.byFqn.get(fqn) match
                case Maybe.Present(id) => symbol(id).flatMap { case c: Symbol.Class => Maybe(c); case _ => Maybe.Absent }
                case Maybe.Absent      => Maybe.Absent

        /** Look up a concrete (non-abstract) class symbol by fully-qualified dotted name.
          *
          * Returns `Absent` when the FQN is not registered, when the symbol is not a Class, or when the matched Class has the Abstract
          * flag set (e.g. `scala.Option`, `scala.Either`). Use `findClass` when abstract classes are acceptable.
          *
          * `findClass` remains permissive (returns sealed abstract classes); this method is the narrow accessor for callers that
          * need a concrete, instantiable class.
          *
          * Example:
          * ```scala
          *   cp.findConcreteClass("scala.Some")    // Present(_)
          *   cp.findConcreteClass("scala.Option")  // Absent (abstract)
          * ```
          */
        def findConcreteClass(fqn: String): Maybe[Symbol.Class] =
            findClass(fqn).filter(!_.isAbstract)

        /** Count of type references that could not be resolved to a final SymbolId after all resolution passes.
          *
          * Nonzero values indicate cross-file TYPEREFsymbol targets not found in the loaded classpath
          * (e.g., JDK types when no JDK roots are passed to `Tasty.withClasspath`). This metric provides
          * visibility into how many Named(-1) sentinels remain in parentTypes after the cross-file
          * resolution pass.
          *
          * Note: a count > 0 is expected behavior when the classpath does not include all transitive
          * dependencies. It is not an error condition.
          *
          * Performance: O(symbols) per call; not cached. For repeated access, callers should cache the result.
          */
        def unresolvedTypeReferenceCount: Int =
            // Count parent-type references that point to a symbol not on this classpath.
            // Negative SymbolIds fall into two categories:
            //   (a) ids tracked in unresolvedFqnByNegId: FQN-tracked cross-classpath refs
            //       whose defining library was absent. These are "truly unresolved."
            //   (b) other negative ids (e.g., decode-phase TERMREFdirect misses, -1 sentinel):
            //       internal artifacts; not user-visible cross-classpath gaps.
            // Only category (a) is counted here.
            val tracked = indices.unresolvedFqnByNegId
            symbols.foldLeft(0): (acc, sym) =>
                sym match
                    case c: Symbol.ClassLike =>
                        acc + c.parentTypes.count:
                            case Type.Named(id) => id.value < 0 && tracked.contains(id)
                            case _              => false
                    case _ => acc
        end unresolvedTypeReferenceCount

        /** Look up a trait symbol by fully-qualified dotted name.
          *
          * Returns `Absent` when the FQN resolves to a non-Trait symbol. Use `findClassLike` for the broader case.
          */
        def findTrait(fqn: String): Maybe[Symbol.Trait] =
            indices.byFqn.get(fqn) match
                case Maybe.Present(id) => symbol(id).flatMap { case t: Symbol.Trait => Maybe(t); case _ => Maybe.Absent }
                case Maybe.Absent      => Maybe.Absent

        /** Look up an object symbol by fully-qualified dotted name.
          *
          * Accepts both source-form names (e.g. `"foo.Bar"`) and binary names with a trailing `$`
          * (e.g. `"foo.Bar$"`). When the source-form FQN resolves to a non-Object symbol (for example,
          * a case class whose companion Object is stored under the binary `"foo.Bar$"` key because
          * the source-form key is taken by the class), the method automatically falls back to looking
          * up `fqn + "$"`. This handles the Scala 3 case-class companion pattern where both the class
          * and its companion object share the same source-form name but the object is indexed under the
          * `$`-suffixed binary name.
          *
          * Returns `Absent` when neither key resolves to an Object symbol.
          */
        def findObject(fqn: String): Maybe[Symbol.Object] =
            def lookupObj(id: SymbolId): Maybe[Symbol.Object] =
                symbol(id).flatMap { case o: Symbol.Object => Maybe(o); case _ => Maybe.Absent }
            def tryDollar(f: String): Maybe[Symbol.Object] =
                if f.endsWith("$") then Maybe.Absent
                else
                    indices.byFqn.get(f + "$") match
                        case Maybe.Present(id2) => lookupObj(id2)
                        case Maybe.Absent       => Maybe.Absent
            indices.byFqn.get(fqn) match
                case Maybe.Present(id) =>
                    val direct = lookupObj(id)
                    if direct.isDefined then direct
                    // Source-form FQN is taken by a non-Object (e.g. the case class itself).
                    // Fall back to the binary $-suffixed key where the companion Object lives.
                    else tryDollar(fqn)
                    end if
                case Maybe.Absent =>
                    // No entry at the source-form key; try the binary $-suffixed key directly.
                    tryDollar(fqn)
            end match
        end findObject

        /** Look up a class-like symbol (Class, Trait, or Object) by fully-qualified dotted name.
          *
          * Returns `Absent` when the FQN resolves to a Package or other non-ClassLike symbol.
          */
        def findClassLike(fqn: String): Maybe[Symbol.ClassLike] =
            indices.byFqn.get(fqn) match
                case Maybe.Present(id) => symbol(id).flatMap { case c: Symbol.ClassLike => Maybe(c); case _ => Maybe.Absent }
                case Maybe.Absent      => Maybe.Absent

        /** Look up a package symbol by fully-qualified dotted name.
          *
          * Pure O(1) lookup in the immutable packageIndex. Returns `Absent` if the package is not in this classpath.
          */
        def findPackage(fqn: String): Maybe[Symbol.Package] =
            indices.packageIndex.get(fqn) match
                case Maybe.Present(id) => symbol(id).flatMap { case p: Symbol.Package => Maybe(p); case _ => Maybe.Absent }
                case Maybe.Absent      => Maybe.Absent

        /** Find all `Symbol.Class` instances whose simple name equals `simpleName`.
          *
          * Returns an empty Chunk when no match is found.
          *
          * Performance: O(1) lookup via `indices.bySimpleName`, a pre-built simple-name to SymbolId-chunk
          * index populated during classpath construction. The index is part of the immutable `Indices`
          * case class and is preserved across `cp.copy(...)` calls that do not replace the `indices` field.
          */
        def findClassesByName(simpleName: String): Chunk[Symbol.Class] =
            indices.bySimpleName.getOrElse(simpleName, Chunk.empty).flatMap: id =>
                symbol(id) match
                    case Maybe.Present(c: Symbol.Class) => Chunk(c)
                    case _                              => Chunk.empty
        end findClassesByName

        /** All package symbols in this classpath.
          *
          * Pure accessor over the immutable `packageIds` Chunk. Each id is resolved and narrowed to `Symbol.Package`; ids that resolve to
          * non-Package symbols are excluded.
          */
        def packages: Chunk[Symbol.Package] =
            indices.packageIds.flatMap: id =>
                symbol(id) match
                    case Maybe.Present(p: Symbol.Package) => Chunk(p)
                    case _                                => Chunk.empty

        /** All top-level class-like symbols (not packages) in this classpath.
          *
          * Pure accessor over the immutable `topLevelClassIds` Chunk. Each id is resolved and narrowed to `Symbol.ClassLike`; ids that
          * resolve to non-ClassLike symbols are excluded.
          */
        def topLevelClasses: Chunk[Symbol.ClassLike] =
            indices.topLevelClassIds.flatMap: id =>
                symbol(id) match
                    case Maybe.Present(c: Symbol.ClassLike) => Chunk(c)
                    case _                                  => Chunk.empty

        /** Look up a JPMS module descriptor by module name (e.g., "java.base").
          *
          * Returns `Present(descriptor)` if a `module-info.class` with the given module name was found in the classpath roots. Returns
          * `Absent` if no matching module was found.
          *
          * O(1) lookup via the modulesIndex in Indices.
          */
        def findModule(name: String): Maybe[Java.Module.Descriptor] =
            indices.modulesIndex.get(name)

        /** Find a class symbol by JVM binary name (e.g., "com/example/Foo$Inner").
          *
          * Converts the binary name to a dotted FQN and delegates to `findClass`. Returns `Maybe[Symbol.Class]`.
          *
          * Pure O(1) lookup; no I/O.
          *
          * Nested-class handling: the naive `'/' -> '.'` and `'$' -> '.'` translation fails for binary names that include
          * anonymous-class or local-class suffixes such as `com/example/Foo$1` (produces `com.example.Foo.1`) or
          * `com/example/Foo$$anon$1` (produces `com.example.Foo..anon.1` with a double dot). This method passes the translated dotted
          * name through `FqnNormalizer.canonicalSourceFqn` to apply the same normalization rules as the cold-load path, so that most
          * named inner classes resolve correctly. Truly anonymous classes (`$1`, `$anon$N`) remain unresolvable via this method because
          * they are excluded from user-facing indexes (they carry `isSyntheticName == true`).
          */
        def findClassByBinary(binaryName: String): Maybe[Symbol.Class] =
            // First translate slashes to dots (standard binary-to-FQN conversion).
            val dotted = binaryName.replace('/', '.')
            // Apply the same FQN normalization as the cold-load path. This handles named inner classes
            // encoded as Outer$Inner (produced by javac) and converts them to Outer.Inner.
            val fqn = kyo.internal.tasty.symbol.FqnNormalizer.canonicalSourceFqn(dotted)
            findClass(fqn)
        end findClassByBinary

        // ── require* throwing variants (INV-010: sole new effect-row additions in this phase) ──

        /** Require a class by FQN; fails with `TastyError.InvalidFqn` when `fqn` is empty, or `TastyError.NotFound` when absent.
          *
          * Empty-string behavior: an empty `fqn` is a caller-side programming error. Rather than returning
          * `TastyError.NotFound("")` (which looks like a normal lookup miss), this method now raises
          * `TastyError.InvalidFqn("", "fqn must be non-empty")` so the caller can distinguish a bad input from a genuine
          * not-found result.
          */
        def requireClass(fqn: String)(using Frame): Symbol.Class < Abort[TastyError] =
            if fqn.isEmpty then Abort.fail(TastyError.InvalidFqn(fqn, "fqn must be non-empty"))
            else
                findClass(fqn) match
                    case Maybe.Present(c) => c
                    case Maybe.Absent     => Abort.fail(TastyError.NotFound(fqn))

        /** Require a trait by FQN; fails with `TastyError.InvalidFqn` when `fqn` is empty, or `TastyError.NotFound` when absent. */
        def requireTrait(fqn: String)(using Frame): Symbol.Trait < Abort[TastyError] =
            if fqn.isEmpty then Abort.fail(TastyError.InvalidFqn(fqn, "fqn must be non-empty"))
            else
                findTrait(fqn) match
                    case Maybe.Present(t) => t
                    case Maybe.Absent     => Abort.fail(TastyError.NotFound(fqn))

        /** Require an object by FQN; fails with `TastyError.InvalidFqn` when `fqn` is empty, or `TastyError.NotFound` when absent. */
        def requireObject(fqn: String)(using Frame): Symbol.Object < Abort[TastyError] =
            if fqn.isEmpty then Abort.fail(TastyError.InvalidFqn(fqn, "fqn must be non-empty"))
            else
                findObject(fqn) match
                    case Maybe.Present(o) => o
                    case Maybe.Absent     => Abort.fail(TastyError.NotFound(fqn))

        /** Require a class-like by FQN; fails with `TastyError.InvalidFqn` when `fqn` is empty, or `TastyError.NotFound` when absent. */
        def requireClassLike(fqn: String)(using Frame): Symbol.ClassLike < Abort[TastyError] =
            if fqn.isEmpty then Abort.fail(TastyError.InvalidFqn(fqn, "fqn must be non-empty"))
            else
                findClassLike(fqn) match
                    case Maybe.Present(c) => c
                    case Maybe.Absent     => Abort.fail(TastyError.NotFound(fqn))

        /** Require a package by FQN; fails with `TastyError.InvalidFqn` when `fqn` is empty, or `TastyError.NotFound` when absent. */
        def requirePackage(fqn: String)(using Frame): Symbol.Package < Abort[TastyError] =
            if fqn.isEmpty then Abort.fail(TastyError.InvalidFqn(fqn, "fqn must be non-empty"))
            else
                findPackage(fqn) match
                    case Maybe.Present(p) => p
                    case Maybe.Absent     => Abort.fail(TastyError.NotFound(fqn))

        /** Require a JPMS module descriptor by name; fails with `TastyError.InvalidFqn` when `name` is empty, or `TastyError.NotFound`
          * when absent.
          */
        def requireModule(name: String)(using Frame): Java.Module.Descriptor < Abort[TastyError] =
            if name.isEmpty then Abort.fail(TastyError.InvalidFqn(name, "fqn must be non-empty"))
            else
                findModule(name) match
                    case Maybe.Present(m) => m
                    case Maybe.Absent     => Abort.fail(TastyError.NotFound(name))

        /** Require any symbol by fully-qualified dotted name; fails with `TastyError.InvalidFqn` when `fqn` is empty, or
          * `TastyError.NotFound` when absent.
          *
          * This accessor replaces the accidental `findSymbol(fqn).get` pattern that would throw a `NoSuchElementException` at runtime.
          * Unlike `requireClass` / `requireTrait` / `requireObject`, this method does not narrow the kind: any registered symbol satisfies
          * the lookup regardless of its `SymbolKind`. The absent case is funneled into the same `NotFound` variant the kind-specific
          * `requireX` methods use, so callers do not have to distinguish two near-identical absent shapes.
          */
        def requireSymbol(fqn: String)(using Frame): Symbol < Abort[TastyError] =
            if fqn.isEmpty then Abort.fail(TastyError.InvalidFqn(fqn, "fqn must be non-empty"))
            else
                findSymbol(fqn) match
                    case Maybe.Present(s) => s
                    case Maybe.Absent     => Abort.fail(TastyError.NotFound(fqn))

        /** Return all FQN collisions recorded during classpath initialization.
          *
          * A collision arises when two distinct source roots each define a symbol under the same fully-qualified name. Under
          * `ErrorMode.SoftFail`, every collision is recorded here; `findSymbol(fqn)` still returns a deterministic winner (last-write-wins by
          * root insertion order). Under `ErrorMode.FailFast`, initialization aborts with `TastyError.InconsistentClasspath` on the first
          * collision, so this chunk is always empty when FailFast is used.
          *
          * Returns an empty `Chunk` when no collisions occurred.
          */
        def collisionReport: Chunk[Classpath.FqnCollision] =
            indices.diagnostics.flatMap:
                case c: Classpath.FqnCollision => Chunk(c)
                case null                      => Chunk.empty

        // ── typed Classpath-wide all* aggregations ──

        /** All Trait symbols in the classpath. Linear scan over symbols. */
        def allTraits: Chunk[Symbol.Trait] = symbolsOfKind(SymbolKind.Trait)

        /** All Object symbols in the classpath. Linear scan over symbols. */
        def allObjects: Chunk[Symbol.Object] = symbolsOfKind(SymbolKind.Object)

        /** All ClassLike symbols (Class, Trait, Object, EnumCase) at any nesting depth.
          *
          * The invariant `allClassLike.size >= topLevelClasses.size` holds because the result includes
          * nested ClassLike symbols. Use `allTraits` / `allObjects` / `symbolsOfKind(SymbolKind.Class)`
          * to narrow to a specific subtype; the result here keeps the union of all four.
          */
        def allClassLike: Chunk[Symbol.ClassLike] =
            if symbols.isEmpty then Chunk.empty
            else
                val b = Chunk.newBuilder[Symbol.ClassLike]
                symbols.foreach:
                    case c: Symbol.ClassLike => b += c
                    case _                   => ()
                b.result()

        /** All Method symbols in the classpath. O(n) scan over `symbols`. */
        def allMethods: Chunk[Symbol.Method] = symbolsOfKind(SymbolKind.Method)

        /** All Val symbols in the classpath. O(n) scan over `symbols`. */
        def allVals: Chunk[Symbol.Val] = symbolsOfKind(SymbolKind.Val)

        /** All Var symbols in the classpath. O(n) scan over `symbols`. */
        def allVars: Chunk[Symbol.Var] = symbolsOfKind(SymbolKind.Var)

        /** All Field symbols (Java-level) in the classpath. O(n) scan over `symbols`. */
        def allFields: Chunk[Symbol.Field] = symbolsOfKind(SymbolKind.Field)

        /** All TypeAlias symbols in the classpath. O(n) scan over `symbols`. */
        def allTypeAliases: Chunk[Symbol.TypeAlias] = symbolsOfKind(SymbolKind.TypeAlias)

        /** All OpaqueType symbols in the classpath. O(n) scan over `symbols`. */
        def allOpaqueTypes: Chunk[Symbol.OpaqueType] = symbolsOfKind(SymbolKind.OpaqueType)

        /** All AbstractType symbols in the classpath. O(n) scan over `symbols`. */
        def allAbstractTypes: Chunk[Symbol.AbstractType] = symbolsOfKind(SymbolKind.AbstractType)

        /** All TypeParam symbols in the classpath. O(n) scan over `symbols`. */
        def allTypeParams: Chunk[Symbol.TypeParam] = symbolsOfKind(SymbolKind.TypeParam)

        /** All Parameter symbols in the classpath. O(n) scan over `symbols`. */
        def allParameters: Chunk[Symbol.Parameter] = symbolsOfKind(SymbolKind.Parameter)

        /** All Package symbols in the classpath. O(n) scan over `symbols`. */
        def allPackages: Chunk[Symbol.Package] = symbolsOfKind(SymbolKind.Package)

        /** All symbols carrying the Scala or Java annotation whose fully-qualified name is `annotationFqn`.
          *
          * Checks Scala `annotations` (via `Annotation.annotationType`: must be `Type.Named(id)` whose FQN matches `annotationFqn`) and
          * Java `javaAnnotations` (via `JavaAnnotation.annotationClass` FQN). Symbols that carry neither field (TypeParam, Package) are
          * excluded.
          */
        def symbolsAnnotatedWith(annotationFqn: String)(using Frame): Chunk[Symbol] < Sync =
            Sync.Unsafe.defer:
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
                        case c: Symbol.ClassLike =>
                            c.javaAnnotations.exists(ja => fullNameUnsafe(ja.annotationClass).asString == annotationFqn)
                        case f: Symbol.Field => f.javaAnnotations.exists(ja => fullNameUnsafe(ja.annotationClass).asString == annotationFqn)
                        case _               => false
                    scalaMatch || javaMatch
        end symbolsAnnotatedWith

        private def annotationFqnMatches(ann: Annotation, fqn: String)(using AllowUnsafe): Boolean =
            import Name.asString
            typeFqnStringUnsafe(ann.annotationType) == fqn
        end annotationFqnMatches

        /** Reconstruct a dotted FQN string from a Type.Named or Type.TermRef tycon, or empty string when unavailable.
          *
          * Used by annotation FQN matching to support both Type.Named(id) and Type.TermRef(qual, name) tycon forms.
          * Annotation tycons decoded from TYPEREF wire tag arrive as Type.TermRef.
          */
        private[Tasty] def typeFqnString(t: Type)(using Frame): String < Sync =
            Sync.Unsafe.defer:
                typeFqnStringUnsafe(t)

        /** Unsafe-tier kernel for `typeFqnString`. Performs name interning that requires an `AllowUnsafe` proof.
          * Used internally by Pass C orchestration paths that already hold `AllowUnsafe` (snapshot writer FQN inversion,
          * annotation FQN matching) where wrapping each call in `Sync.Unsafe.defer` would add unnecessary indirection.
          */
        private[kyo] def typeFqnStringUnsafe(t: Type)(using AllowUnsafe): String =
            import Name.asString
            t match
                case Type.Named(id) =>
                    symbol(id) match
                        case Maybe.Absent =>
                            // Out-of-range or negative id: check unresolvedFqnByNegId for annotation types
                            // that reference external symbols (e.g. scala.deprecated on JS/Native).
                            indices.unresolvedFqnByNegId.getOrElse(id, "")
                        case Maybe.Present(sym) => fullNameUnsafe(sym).asString
                    end match
                case Type.TermRef(qual, name) =>
                    val q = typeFqnStringUnsafe(qual)
                    if q.nonEmpty then q + "." + name.asString else name.asString
                case Type.TypeRef(qual, name) =>
                    // TYPEREF emits TypeRef; annotation FQN matching must handle both forms.
                    val q = typeFqnStringUnsafe(qual)
                    if q.nonEmpty then q + "." + name.asString else name.asString
                case Type.Applied(base, _) =>
                    // @Child[T] enrichment wraps the TermRef tycon in Applied(tycon, Chunk(T)).
                    // For FQN matching, use the unapplied base type.
                    typeFqnStringUnsafe(base)
                case _ => ""
            end match
        end typeFqnStringUnsafe

        /** Look up the companion symbol (companion object for a class/trait; companion class for an object).
          *
          * Pure O(1) lookup in the immutable `companionIndex`. Returns `Absent` when no companion is registered.
          */
        def companion(sym: Symbol): Maybe[Symbol] =
            indices.companionIndex.get(sym.id) match
                case Maybe.Present(cid) => symbol(cid)
                case Maybe.Absent       => Maybe.Absent

        /** Compute the fully-qualified dotted name of `sym` by walking the owner chain.
          *
          * Walks upward collecting non-empty segment names; stops when the symbol owns itself (root sentinel), when ownerId is -1, or when
          * the same symbol appears twice (cycle guard). Depth limit of 64 prevents unbounded loops.
          *
          * Returns `Name < Sync`: the result is computed inside a `Sync.Unsafe.defer` boundary for consistency with other Sync operations.
          */
        def fullName(sym: Symbol)(using Frame): Name < Sync =
            Sync.Unsafe.defer:
                fullNameUnsafe(sym)

        /** Pure kernel for `fullName`. Walks the owner chain and concatenates segment strings.
          * Used by internal Pass C paths and by the safe-tier `fullName` wrapper above.
          */
        private[kyo] def fullNameUnsafe(sym: Symbol): Name =
            import Name.asString
            val visited = new java.util.HashSet[Int]()
            @scala.annotation.tailrec
            def go(cur: Symbol, depth: Int, acc: List[String]): List[String] =
                if depth >= 64 || !visited.add(cur.id.value) then acc
                else
                    val n          = cur.name.asString
                    val nextAcc    = if n.nonEmpty then n :: acc else acc
                    val ownerIdCur = cur.ownerId
                    if ownerIdCur == cur.id || ownerIdCur.value == -1 then nextAcc
                    else
                        symbol(ownerIdCur) match
                            case Maybe.Present(ownerSym) if ownerSym.id != cur.id && ownerSym.name.asString.nonEmpty =>
                                go(ownerSym, depth + 1, nextAcc)
                            case _ => nextAcc
                    end if
            val parts = go(sym, 0, Nil)
            (parts.mkString("."): Name)
        end fullNameUnsafe

        /** All direct `ClassLike` subclasses of `sym` (one hop, from the subclass index).
          *
          * Pure O(k) lookup where k is the number of direct subclasses. Returns an empty Chunk when `sym` has no registered subclasses.
          * Non-ClassLike entries in the index are silently excluded (defensive; should not occur in well-formed classpath data).
          */
        def directSubclassesOf(sym: Symbol.ClassLike): Chunk[Symbol.ClassLike] =
            indices.subclassIndex.getOrElse(sym.id, Chunk.empty).flatMap: id =>
                symbol(id) match
                    case Maybe.Present(c: Symbol.ClassLike) => Chunk(c)
                    case _                                  => Chunk.empty

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
            @scala.annotation.tailrec
            def bfs(frontier: Chunk[SymbolId]): Unit =
                if frontier.isEmpty then ()
                else
                    val next = frontier.flatMap: curId =>
                        indices.subclassIndex.getOrElse(curId, Chunk.empty).flatMap: childId =>
                            if visited.add(childId) then
                                symbol(childId) match
                                    case Maybe.Present(c: Symbol.ClassLike) =>
                                        out += c
                                        Chunk(childId)
                                    case _ => Chunk.empty[SymbolId]
                            else Chunk.empty[SymbolId]
                    bfs(next)
            bfs(Chunk(root.id))
            out.result()
        end transitiveClassLikeSubclasses

    end Classpath

    object Classpath:

        /** Sealed hierarchy for structured build-time observations accumulated in `Classpath.Indices.diagnostics`.
          *
          * Unlike `TastyError` (which represents failures during decoding or classpath operations), `Diagnostic` represents observations
          * about the classpath shape that do not prevent a usable classpath from being returned. Currently the only concrete type is
          * `FqnCollision`.
          */
        sealed trait Diagnostic derives Schema, CanEqual

        /** Recorded when two or more source roots each provide a symbol under the same fully-qualified name.
          *
          * `fqn` is the colliding fully-qualified name. `ids` contains the `SymbolId` of every symbol that was registered under this FQN
          * across the input roots; the winning symbol (the one returned by `findSymbol(fqn)`) is the last entry in insertion order.
          *
          * This diagnostic is only populated under `ErrorMode.SoftFail`. Under `ErrorMode.FailFast`, a collision immediately raises
          * `TastyError.InconsistentClasspath` and initialization aborts.
          */
        final case class FqnCollision(fqn: String, ids: Chunk[SymbolId]) extends Diagnostic derives Schema, CanEqual

        /** All lookup indices for a Classpath. Immutable, populated once during `init` and never mutated.
          *
          * Fields:
          *   - `byFqn`: fully-qualified dotted name to SymbolId (replaces old `fqnIndex`).
          *   - `bySimpleName`: simple name to SymbolId chunk (for findClassesByName; replaces lazy `nameIndex`).
          *   - `packageIndex`: package FQN to SymbolId.
          *   - `subclassIndex`: parent SymbolId to direct subclass SymbolIds (for directSubclassesOf).
          *   - `companionIndex`: SymbolId to companion SymbolId.
          *   - `modulesIndex`: module name to ModuleDescriptor (O(1) findModule).
          *   - `topLevelClassIds`: top-level class-like SymbolIds.
          *   - `packageIds`: package SymbolIds.
          *   - `unresolvedFqnByNegId`: negative SymbolId to FQN string (annotation types not on classpath).
          *   - `diagnostics`: FQN collision records from initialization.
          *
          * `derives CanEqual`: Schema derivation is provided by the hand-written `given schemaIndices`
          * (placed after `symbolIdMapSchema`) so `Dict[SymbolId, V]` fields resolve correctly.
          */
        final case class Indices(
            byFqn: Dict[String, SymbolId],
            bySimpleName: Dict[String, Chunk[SymbolId]],
            packageIndex: Dict[String, SymbolId],
            subclassIndex: Dict[SymbolId, Chunk[SymbolId]],
            companionIndex: Dict[SymbolId, SymbolId],
            modulesIndex: Dict[String, Java.Module.Descriptor],
            topLevelClassIds: Chunk[SymbolId],
            packageIds: Chunk[SymbolId],
            unresolvedFqnByNegId: Dict[SymbolId, String],
            diagnostics: Chunk[Classpath.Diagnostic]
        ) derives CanEqual:
            // Dict is an opaque type without structural == ; override equals to use Dict.is
            // for each Dict field so that structural comparison works as expected for case class equality.
            override def equals(other: Any): Boolean = other match
                case i: Indices =>
                    byFqn.is(i.byFqn)(using CanEqual.canEqualAny, CanEqual.canEqualAny) &&
                    bySimpleName.is(i.bySimpleName)(using CanEqual.canEqualAny, CanEqual.canEqualAny) &&
                    packageIndex.is(i.packageIndex)(using CanEqual.canEqualAny, CanEqual.canEqualAny) &&
                    subclassIndex.is(i.subclassIndex)(using CanEqual.canEqualAny, CanEqual.canEqualAny) &&
                    companionIndex.is(i.companionIndex)(using CanEqual.canEqualAny, CanEqual.canEqualAny) &&
                    modulesIndex.is(i.modulesIndex)(using CanEqual.canEqualAny, CanEqual.canEqualAny) &&
                    topLevelClassIds == i.topLevelClassIds &&
                    packageIds == i.packageIds &&
                    unresolvedFqnByNegId.is(i.unresolvedFqnByNegId)(using CanEqual.canEqualAny, CanEqual.canEqualAny) &&
                    diagnostics == i.diagnostics
                case _ => false
            end equals

            // Override hashCode so that structurally-equal Indices instances produce the same hash.
            // The auto-generated case-class hashCode mixes each Dict's reference-based hashCode, which
            // violates the equals/hashCode contract established by the equals override above (WARN-1 fix).
            // XOR-based fold over (key, value) pairs is used for each Dict field because XOR is commutative
            // and iteration order over Dict is not guaranteed to be stable.
            override def hashCode(): Int =
                def dictHash[K, V](d: Dict[K, V]): Int =
                    d.foldLeft(0)((h, k, v) => h ^ (k.hashCode * 31 + v.hashCode))
                var h = 1
                h = 31 * h + dictHash(byFqn)
                h = 31 * h + dictHash(bySimpleName)
                h = 31 * h + dictHash(packageIndex)
                h = 31 * h + dictHash(subclassIndex)
                h = 31 * h + dictHash(companionIndex)
                h = 31 * h + dictHash(modulesIndex)
                h = 31 * h + topLevelClassIds.hashCode
                h = 31 * h + packageIds.hashCode
                h = 31 * h + dictHash(unresolvedFqnByNegId)
                h = 31 * h + diagnostics.hashCode
                h
            end hashCode
        end Indices

        object Indices:
            val empty: Indices = Indices(
                byFqn = Dict.empty[String, SymbolId],
                bySimpleName = Dict.empty[String, Chunk[SymbolId]],
                packageIndex = Dict.empty[String, SymbolId],
                subclassIndex = Dict.empty[SymbolId, Chunk[SymbolId]],
                companionIndex = Dict.empty[SymbolId, SymbolId],
                modulesIndex = Dict.empty[String, Java.Module.Descriptor],
                topLevelClassIds = Chunk.empty,
                packageIds = Chunk.empty,
                unresolvedFqnByNegId = Dict.empty[SymbolId, String],
                diagnostics = Chunk.empty
            )
        end Indices

        /** Schema for Dict[SymbolId, V]: encodes SymbolId keys as their String representation (Int value).
          *
          * kyo-schema provides Schema[Dict[String, V]] (stringDictSchema, JSON object) but not
          * Schema[Dict[K, V]] for non-String keys via the object-format path.
          * This given bridges the gap for all Dict[SymbolId, V] fields in Classpath.Indices.
          * Encoding: each SymbolId key is converted to its Int value rendered as a String; decoding
          * parses the String back to Int and wraps in SymbolId. Wire format is a JSON object,
          * preserving the existing snapshot encoding.
          */
        private[kyo] given symbolIdMapSchema[V](using vs: Schema[V]): Schema[Dict[SymbolId, V]] =
            summon[Schema[Dict[String, V]]].transform((d: Dict[String, V]) => d.map((k, v) => (SymbolId(k.toInt), v)))(
                (d: Dict[SymbolId, V]) => d.map((k, v) => (k.value.toString, v))
            )

        /** Schema for Classpath.Indices. Placed here (after symbolIdMapSchema is defined) so Dict[SymbolId, V] fields resolve. */
        given schemaIndices: Schema[Indices] = Schema.derived

        /** Init the classpath and additionally pre-load JDK `module-info.class` entries from the JDK module image.
          *
          * Opt-in JDK auto-discovery. On JVM, reads module-info.class files for all JDK modules from the `jrt:/`
          * virtual filesystem (mounted automatically by the JVM) and merges them into the returned classpath's `moduleIndex`. After the
          * call, `cp.findModule("java.base")` and other JDK modules resolve. On Scala.js and Scala Native, `jrt:/` is not available;
          * this method fails with `TastyError.UnsupportedPlatform`.
          *
          * Effect row: Async + Scope + Abort[TastyError].
          */
        def initWithPlatformModules(roots: Seq[String])(using Frame): Classpath < (Async & Scope & Abort[TastyError]) =
            initWithPlatformModulesFiltered(roots, Set.empty)

        /** Variant of `initWithPlatformModules` that walks only the specified JPMS modules from the `jrt:/` filesystem.
          *
          * When `moduleFilter` is non-empty, only the named modules (e.g. `Set("java.base")`) are scanned for classfiles. This reduces
          * decode time from ~27,000 classfiles (all JDK modules) to ~7,000 (java.base only), making it suitable for test fixtures. The
          * production `initWithPlatformModules` always passes an empty filter, which walks all modules.
          *
          * The returned Classpath is immutable; this overload does not weaken that invariant.
          */
        private[kyo] def initWithPlatformModulesFiltered(
            roots: Seq[String],
            moduleFilter: Set[String]
        )(using Frame): Classpath < (Async & Scope & Abort[TastyError]) =
            // Prepend every `.class` path under `jrt:/modules/<m>/...` to the user's roots so JDK class
            // symbols decode alongside user TASTy. The shape of `roots` is preserved (a Seq[String] of
            // file-system paths); the new entries use the `jrt:/` URI scheme that JvmFileSource already
            // handles. PlatformModuleOps.listJdkClassFiles is JVM-only; JS/Native
            // return Chunk.empty so this method degrades to the module-descriptor-only path.
            // Sync.Unsafe.defer supplies AllowUnsafe to just the listJdkClassFiles call, so the rest of
            // the for-comprehension cannot pick up the proof implicitly.
            Sync.Unsafe.defer(kyo.internal.tasty.query.PlatformModuleOps.listJdkClassFiles(moduleFilter)).map: jdkClassFiles =>
                for
                    cp         <- initImpl(jdkClassFiles.toSeq ++ roots, ErrorMode.SoftFail)
                    jdkModules <- PlatformModuleOps.readJdkModuleDescriptors
                yield
                    val newModulesIndex = cp.indices.modulesIndex ++ Dict.from(jdkModules)
                    val newModulesBuf   = Chunk.newBuilder[Java.Module.Descriptor]
                    newModulesIndex.foreach((_, v) => newModulesBuf += v)
                    val newModules = newModulesBuf.result()
                    cp.copy(
                        modules = newModules,
                        indices = cp.indices.copy(modulesIndex = newModulesIndex)
                    )
                end for
        end initWithPlatformModulesFiltered

        /** Create a test-only classpath from a pre-built symbols array.
          *
          * symbols(i).id.value must equal i for cp.symbol(id) to resolve correctly. Only callable from within package kyo (private[kyo]).
          */
        private[kyo] def fromPicklesWithSymbols(symbols: Chunk[Symbol])(using Frame): Classpath < Sync =
            Classpath(
                symbols = symbols,
                indices = Indices.empty,
                errors = Chunk.empty,
                modules = Chunk.empty,
                rootSymbolId = if symbols.nonEmpty then SymbolId(0) else SymbolId(-1)
            )

        /** Internal: init implementation, delegates to ClasspathOrchestrator. */
        private def initImpl(
            roots: Seq[String],
            mode: ErrorMode
        )(using Frame): Classpath < (Async & Scope & Abort[TastyError]) =
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
        )(using Frame): Classpath < (Async & Scope & Abort[TastyError]) =
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

        /** Internal factory for constructing a Tasty.Classpath from the finalized data produced by
          * ClasspathOrchestrator or SnapshotReader.
          *
          * Called from ClasspathOrchestrator.finalizeMerge and SnapshotReader.deserialize. The Classpath
          * constructor is now public so callers can also construct directly; this shim exists for
          * backward-compatible call sites that supply the 13-parameter expanded form.
          */
        private[kyo] def make(
            symbols: Chunk[Symbol],
            rootSymbolId: SymbolId,
            topLevelClassIds: Chunk[SymbolId],
            packageIds: Chunk[SymbolId],
            fqnIndex: Dict[String, SymbolId],
            packageIndex: Dict[String, SymbolId],
            subclassIndex: Dict[SymbolId, Chunk[SymbolId]],
            companionIndex: Dict[SymbolId, SymbolId],
            moduleIndex: Dict[String, Java.Module.Descriptor],
            errors: Chunk[TastyError],
            diagnostics: Chunk[Classpath.Diagnostic] = Chunk.empty,
            unresolvedFqnByNegId: Dict[SymbolId, String] = Dict.empty[SymbolId, String]
        ): Classpath =
            import Name.asString
            val bySimpleName: Dict[String, Chunk[SymbolId]] =
                val b = scala.collection.mutable.HashMap.empty[String, scala.collection.mutable.ArrayBuffer[SymbolId]]
                symbols.foreach: sym =>
                    val nm = sym.name.asString
                    if nm.nonEmpty then
                        b.getOrElseUpdate(nm, new scala.collection.mutable.ArrayBuffer()) += sym.id
                Dict.from(b.map((k, v) => k -> Chunk.from(v)).toMap)
            end bySimpleName
            val moduleValues =
                val buf = Chunk.newBuilder[Java.Module.Descriptor]
                moduleIndex.foreach((_, v) => buf += v)
                buf.result()
            end moduleValues
            Classpath(
                symbols = symbols,
                indices = Classpath.Indices(
                    byFqn = fqnIndex,
                    bySimpleName = bySimpleName,
                    packageIndex = packageIndex,
                    subclassIndex = subclassIndex,
                    companionIndex = companionIndex,
                    modulesIndex = moduleIndex,
                    topLevelClassIds = topLevelClassIds,
                    packageIds = packageIds,
                    unresolvedFqnByNegId = unresolvedFqnByNegId,
                    diagnostics = diagnostics
                ),
                errors = errors,
                modules = moduleValues,
                rootSymbolId = rootSymbolId
            )
        end make

        /** Internal helper: copy a Classpath with a replacement errors field. */
        private[kyo] def copyWithErrors(cp: Classpath, newErrors: Chunk[TastyError]): Classpath =
            cp.copy(errors = newErrors)

        /** Internal helper: prepend pre-errors (e.g., FileNotFound for missing roots under SoftFail) to cp.errors. */
        private[kyo] def copyWithPreErrors(cp: Classpath, preErrors: Chunk[TastyError]): Classpath =
            cp.copy(errors = preErrors ++ cp.errors)

        /** empty: canonical empty classpath, useful for tests and as a default value. */
        val empty: Classpath = Classpath(
            symbols = Chunk.empty,
            indices = Indices.empty,
            errors = Chunk.empty,
            modules = Chunk.empty,
            rootSymbolId = SymbolId(-1)
        )

    end Classpath

    /** Schema[Classpath] placed after object Classpath closes so Classpath.Indices and its Schema are in scope.
      *
      * Placed after object Classpath closes so Classpath.Indices and its Schema are in scope, avoiding the forward-reference
      * issue that arises when derives Schema is placed inline on the class.
      */
    given schemaClasspath: Schema[Classpath] = Schema.derived

    /** CanEqual[Classpath, Classpath] derived after the companion closes; same placement rationale as schemaClasspath. */
    given canEqualClasspath: CanEqual[Classpath, Classpath] = CanEqual.canEqualAny

    // ── Suspend / create ───────────────────────────────────────────────────

    /** Bind a fresh Classpath loaded from `roots` and run `f` in that scope.
      *
      * Loads the classpath from the given file-system roots using `ErrorMode.SoftFail`. When
      * `cacheDir` is `Present(dir)`, attempts to read a snapshot from `dir` first; on a miss,
      * cold-loads and writes the snapshot before returning. Resources (mmap arenas, JAR handles)
      * are released when the scope exits via the internal `Scope.run`.
      *
      * INV-009 site-1: init from file-system roots (cold-load) via `ClasspathOrchestrator.coldLoadBinding`.
      * This is the ONLY entry point that reads the file system during classpath construction. All
      * `Tasty.*` query methods called inside `f` are pure and perform no IO.
      *
      * Diagnostics accumulated during the scope by `Tasty.isSubtypeOf` (e.g.,
      * `TastyError.UnhandledSubtypingCase`) are folded into `cp.errors` on any call to
      * `Tasty.classpath` within `f` (Q-003 binding: errors appear in `cp.errors`).
      *
      * Effect row: `A < (Async & Abort[TastyError] & S)` -- `Scope` is consumed internally.
      */
    def withClasspath[A, S](
        roots: Seq[String],
        cacheDir: Maybe[String] = Maybe.Absent
    )(f: => A < S)(using Frame): A < (Async & Abort[TastyError] & S) =
        Scope.run:
            ClasspathOrchestrator.coldLoadBinding(roots, ErrorMode.SoftFail, cacheDir).map: binding =>
                TastyState.bindingLocal.let(Maybe.Present(binding))(f)

    /** Bind a pre-existing (deserialized) Classpath and run `f` in that scope.
      *
      * No filesystem access, no Scope overhead. The bound Binding carries a fresh DecodeContext so
      * that `Tasty.isSubtypeOf` can accumulate `TastyError.UnhandledSubtypingCase` diagnostics.
      * `Tasty.bodyTree` returns `Maybe.Absent` for every symbol inside `f` because the DecodeContext
      * carries no body source handle.
      *
      * Diagnostics accumulated during the scope are folded into `cp.errors` on any call to
      * `Tasty.classpath` within `f` (Q-003 binding: errors appear in `cp.errors`).
      *
      * Effect row: `A < S` -- identical to `f`'s row.
      */
    def withClasspath[A, S](cp: Classpath)(f: => A < S)(using Frame): A < S =
        TastyState.bindingLocal.let(Maybe.Present(Binding(cp, Maybe.Present(DecodeContext.fresh()))))(f)

    /** Bind a Classpath decoded from in-memory pickles and run `f` in that scope.
      *
      * Decodes the pickles sequentially using an in-memory FileSource. The resulting Binding carries
      * a fresh DecodeContext so `Tasty.bodyTree` can decode body bytes on demand.
      *
      * INV-009 site-1-related (in-memory alt-init; no real-FS contact): constructs an anonymous
      * in-memory FileSource from the pickle bytes map; never reads `PlatformFileSource.get` and
      * never touches the real file system. All `Tasty.*` query methods called inside `f` are pure and perform no IO.
      *
      * Effect row: `A < (Async & Abort[TastyError] & S)`.
      */
    def withPickles[A, S](pickles: Chunk[Pickle])(f: => A < S)(using Frame): A < (Async & Abort[TastyError] & S) =
        Scope.run:
            ClasspathOrchestrator.loadPickles(pickles).map: binding =>
                TastyState.bindingLocal.let(Maybe.Present(binding))(f)

    /** Delete snapshot files in `cacheDir` whose modification time is older than `maxAge`.
      *
      * Delegates to `Tasty.Snapshot.evictOlderThan`. Only deletes `*.krfl` files; does not recurse.
      * INV-009 site-4: the underlying file-deletion logic uses AllowUnsafe via the FileSource.
      */
    def evictOlderThan(cacheDir: String, maxAge: Duration)(using Frame): Unit < (Sync & Abort[TastyError]) =
        Snapshot.evictOlderThan(cacheDir, maxAge)

    // ── Access ─────────────────────────────────────────────────────────────

    /** Get the current Classpath from the active binding, falling back to the module-level JVM classpath.
      *
      * Returns the JVM classpath stub when called outside a `withClasspath` scope.
      *
      * When called inside a `withClasspath` scope, folds any `TastyError.UnhandledSubtypingCase`
      * diagnostics accumulated by `isSubtypeOf` calls during the scope into the returned
      * `Classpath.errors` field. This makes `cp.errors` the user-visible channel for
      * unhandled-shape diagnostics (Q-003 binding).
      *
      * Effect row: Sync, because reading the lazy val TastyState.global may trigger initialization.
      */
    def classpath(using Frame): Classpath < Sync =
        TastyState.bindingLocal.use: mbind =>
            val binding = mbind.getOrElse(TastyState.global)
            Sync.defer:
                binding.decodeCtx match
                    case Maybe.Present(ctx) if ctx.subtypingErrors.nonEmpty =>
                        Classpath.copyWithErrors(binding.cp, binding.cp.errors ++ Chunk.from(ctx.subtypingErrors))
                    case _ =>
                        binding.cp

    // ── Snapshot management ─────────────────────────────────────────────────

    /** Snapshot cache management utilities for the `Tasty.withClasspath(roots, cacheDir)` path.
      *
      * `withClasspath(roots, Present(cacheDir))` writes a binary snapshot file (extension `.krfl`) keyed by a
      * digest of the input roots, so repeat opens of the same classpath restore the in-memory state without
      * re-decoding the underlying `.tasty` / `.class` files. Over time the cache directory accumulates snapshots
      * for roots that are no longer relevant (different commits, different sbt projects, transient builds);
      * this companion exposes the maintenance operations a long-running process needs.
      *
      * **Eviction.** `evictOlderThan(cacheDir, maxAge)` deletes any `*.krfl` file in `cacheDir` whose
      * modification time is older than `maxAge`, returning silently. It does not recurse, and it does not look
      * at file contents; the policy is purely age-based. The operation carries `Sync & Abort[TastyError]`
      * because it touches the disk; expect `SnapshotIoError` when the directory cannot be read.
      *
      * **What lives elsewhere.** The snapshot read / write path is internal; the only public surface for
      * snapshot files is `Tasty.withClasspath` (writes on miss, reads on hit) and this object (eviction).
      * Errors produced by the snapshot path are all in `TastyError` (`SnapshotFormatError`,
      * `SnapshotVersionMismatch`, `SnapshotIoError`, `DigestMismatch`).
      */
    object Snapshot:

        /** Delete snapshot files in `cacheDir` whose modification time is older than `maxAge`.
          *
          * Only deletes files matching `*.krfl`. Does not recurse into subdirectories.
          *
          * Performance: stats all `.krfl` files, sorts them by mtime ascending (oldest first), deletes in order and stops at the
          * first file whose age is within `maxAge`. When most files are fresh this early-exit avoids unnecessary delete attempts on files
          * that are clearly within the retention window.
          *
          * @param cacheDir
          *   directory containing snapshot files
          * @param maxAge
          *   maximum age; files older than this are deleted
          */
        def evictOlderThan(cacheDir: String, maxAge: Duration)(using Frame): Unit < (Sync & Abort[TastyError]) =
            val source = PlatformFileSource.get
            evictOlderThanWithSource(cacheDir, maxAge.toMillis, source)
        end evictOlderThan

        /** Internal overload that accepts a custom FileSource for testing.
          *
          * Stats all files, sorts by mtime ascending (oldest first), deletes until the first non-stale entry, then stops.
          * This avoids unnecessary delete calls for files within the retention window and is O(N) stat syscalls + O(N log N) sort.
          */
        private[kyo] def evictOlderThanWithSource(
            cacheDir: String,
            maxAgeMs: Long,
            source: kyo.internal.tasty.query.FileSource
        )(using Frame): Unit < (Sync & Abort[TastyError]) =
            source.list(cacheDir, Chunk(".krfl")).flatMap: files =>
                // Collect (path, mtime) for each file that can be statted; skip files with stat errors.
                Kyo.collect(files): path =>
                    Abort.run[TastyError](source.stat(path)).map:
                        case Result.Success(st) => Maybe((path, st.mtimeMs))
                        case _                  => Maybe.Absent
                .flatMap: pairs =>
                    // Sort by mtime ascending so oldest files come first. Convert to Seq for sortBy.
                    val now    = java.lang.System.currentTimeMillis()
                    val sorted = pairs.toSeq.sortBy(_._2)
                    // Delete in order; stop at the first non-stale file (early exit).
                    Kyo.foreachDiscard(sorted.takeWhile { case (_, mtimeMs) => now - mtimeMs > maxAgeMs }):
                        case (path, _) =>
                            // Ignore errors: concurrent writers may have already replaced or removed the file.
                            Abort.run[TastyError](deleteFile(source, path)).andThen(Kyo.unit)
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

    // ── Tasty.* query operations ────────────────────────────────────────────
    // All query operations read the active binding from TastyState.bindingLocal. They carry
    // < Sync in their effect row because the lazy fallback TastyState.global may trigger
    // initialization on the first call (INV-009 site-2).

    /** Expand to the fully-qualified dotted name of `A` at compile time via the `Tag` machinery.
      *
      * Use when the type is known statically and the caller wants the FQN string for a `findClass` / `requireClass`
      * lookup without spelling out the literal. `classFqn[example.Circle]` evaluates to `"example.Circle"`;
      * `classFqn[scala.collection.immutable.List]` evaluates to `"scala.collection.immutable.List"`.
      *
      * The dotted form matches what `Classpath.findClass`, `findClassLike`, and `findSymbol` accept; the JVM
      * binary form (`example/Circle$Inner`) is reachable through `Classpath.findClassByBinary` instead.
      */
    def classFqn[A](using t: Tag[A]): String = t.show

    /** Look up a class symbol by fully-qualified dotted name.
      *
      * Delegates to `Classpath.findClass`, which performs a pure O(1) index lookup. Returns
      * `Maybe.Absent` when the FQN is not in the loaded classpath or when the resolved symbol
      * is not a `Symbol.Class` (e.g., a Trait or Object at the same FQN is silently absent).
      *
      * Abstract classes, sealed abstract classes (e.g. `scala.Option`), and concrete classes
      * are all included. Use `findConcreteClass` to restrict to non-abstract classes.
      *
      * Effect row: `< Sync`. The suspension arises because the first access may trigger lazy
      * classpath initialization through `TastyState.bindingLocal`.
      *
      * @param fqn dotted fully-qualified name (e.g. `"scala.collection.immutable.List"`)
      * @return the class symbol wrapped in `Maybe.Present`, or `Maybe.Absent` if not found
      */
    def findClass(fqn: String)(using Frame): Maybe[Symbol.Class] < Sync =
        classpath.map(_.findClass(fqn))

    /** Look up any class-like symbol by fully-qualified dotted name.
      *
      * Matches `Symbol.Class`, `Symbol.Trait`, `Symbol.Object`, and `Symbol.EnumCase` at the
      * given FQN. Use this when the caller does not know whether the target is a class, trait,
      * or object, or when any class-like shape is acceptable.
      *
      * Returns `Maybe.Absent` when the FQN is absent from the classpath or when the resolved
      * symbol is a non-class-like kind (e.g., a `Symbol.Package` or `Symbol.Method`).
      *
      * Effect row: `< Sync` (lazy classpath init on first access).
      *
      * @param fqn dotted fully-qualified name
      * @return the class-like symbol wrapped in `Maybe.Present`, or `Maybe.Absent`
      */
    def findClassLike(fqn: String)(using Frame): Maybe[Symbol.ClassLike] < Sync =
        classpath.map(_.findClassLike(fqn))

    /** Look up a Scala object (module) symbol by fully-qualified name.
      *
      * Accepts both the source form (`"mypackage.MyObject"`) and the binary `$`-suffix form
      * (`"mypackage.MyObject$"`). When given the source form, the lookup first tries a direct
      * FQN match and, on miss, appends `"$"` and retries against the binary-name index.
      *
      * Returns `Maybe.Absent` when neither variant resolves to a `Symbol.Object`. Use
      * `findClassLike` if you want to match objects together with classes and traits.
      *
      * Effect row: `< Sync` (lazy classpath init on first access).
      *
      * @param fqn dotted source-form or `$`-suffixed binary-form FQN of the object
      * @return the object symbol wrapped in `Maybe.Present`, or `Maybe.Absent`
      */
    def findObject(fqn: String)(using Frame): Maybe[Symbol.Object] < Sync =
        classpath.map(_.findObject(fqn))

    /** Look up any symbol by fully-qualified dotted name.
      *
      * The most permissive lookup: returns `Maybe.Present` for any registered symbol kind
      * (Class, Trait, Object, Method, Val, Package, etc.). Use the typed variants
      * (`findClass`, `findClassLike`, `findObject`, `findPackage`) when a specific kind
      * is expected; use this method when the kind is unknown or irrelevant.
      *
      * Returns `Maybe.Absent` when the FQN has no entry in the loaded classpath.
      *
      * Effect row: `< Sync` (lazy classpath init on first access).
      *
      * @param fqn dotted fully-qualified name of the target symbol
      * @return the symbol wrapped in `Maybe.Present`, or `Maybe.Absent`
      */
    def findSymbol(fqn: String)(using Frame): Maybe[Symbol] < Sync =
        classpath.map(_.findSymbol(fqn))

    /** Look up a package symbol by fully-qualified dotted name.
      *
      * Returns `Maybe.Present` only when the FQN resolves to a `Symbol.Package`. Any other
      * symbol kind at the same name (e.g. a companion object) produces `Maybe.Absent`.
      *
      * Packages are synthesized during classpath loading from the directory structure of
      * each root. The root package is registered under the empty string `""`.
      *
      * Effect row: `< Sync` (lazy classpath init on first access).
      *
      * @param fqn dotted package name (e.g. `"scala.collection.immutable"`), or `""` for root
      * @return the package symbol wrapped in `Maybe.Present`, or `Maybe.Absent`
      */
    def findPackage(fqn: String)(using Frame): Maybe[Symbol.Package] < Sync =
        classpath.map(_.findPackage(fqn))

    /** Look up a JPMS module descriptor by module name.
      *
      * Returns `Maybe.Present` only when a `module-info.class` entry for `name` was found
      * in the loaded classpath roots and successfully decoded. Module descriptors are populated
      * during classpath loading from JVM module roots (e.g. a JDK image passed via
      * `Classpath.Root.jrt`).
      *
      * Returns `Maybe.Absent` when no module with that name was loaded, or when the classpath
      * was opened without JDK/module roots.
      *
      * Effect row: `< Sync` (lazy classpath init on first access).
      *
      * @param name JPMS module name (e.g. `"java.base"`, `"com.example.mymodule"`)
      * @return the module descriptor wrapped in `Maybe.Present`, or `Maybe.Absent`
      */
    def findModule(name: String)(using Frame): Maybe[Java.Module.Descriptor] < Sync =
        classpath.map(_.findModule(name))

    /** Look up a concrete (non-abstract) class by fully-qualified dotted name.
      *
      * Equivalent to `findClass(fqn).filter(!_.isAbstract)`. Returns `Maybe.Absent` when the
      * FQN is missing from the classpath, when it resolves to a non-Class symbol, or when the
      * class has the Abstract modifier (e.g. `scala.Option`, `scala.collection.AbstractMap`).
      *
      * Use this when you need a class that can be instantiated or that carries concrete
      * implementations. Use `findClass` when abstract classes are acceptable.
      *
      * Effect row: `< Sync` (lazy classpath init on first access).
      *
      * @param fqn dotted fully-qualified name of the target class
      * @return the class symbol wrapped in `Maybe.Present`, or `Maybe.Absent`
      */
    def findConcreteClass(fqn: String)(using Frame): Maybe[Symbol.Class] < Sync =
        classpath.map(_.findConcreteClass(fqn))

    /** Find all class symbols whose simple (unqualified) name matches the given string.
      *
      * Unlike `findClass`, which requires the fully-qualified dotted name, this method searches
      * across all packages and returns every `Symbol.Class` whose `simpleName` equals
      * `simpleName`. The result may contain multiple symbols when the same name appears in
      * different packages (e.g. `"List"` in `scala.collection.immutable` and `java.util`).
      *
      * The search is backed by an O(1) index built during classpath loading.
      *
      * Effect row: `< Sync` (lazy classpath init on first access).
      *
      * @param simpleName unqualified class name (e.g. `"List"`, `"Option"`)
      * @return all matching class symbols; empty `Chunk` when none are found
      */
    def findClassesByName(simpleName: String)(using Frame): Chunk[Symbol.Class] < Sync =
        classpath.map(_.findClassesByName(simpleName))

    /** Find a method symbol by owner FQN and simple method name.
      *
      * Resolves the owner symbol by `ownerFqn`, expects it to be a `Symbol.ClassLike`, then
      * scans the owner's `declarationIds` for the first `Symbol.Method` whose `simpleName`
      * equals `methodName`. Returns `Maybe.Absent` when the owner FQN is not found, when the
      * owner is not a class-like, or when no declared method with that simple name exists.
      *
      * When multiple overloads share the same name, the first one in declaration order is
      * returned. Use `declarations` or `members` followed by manual filtering when overload
      * discrimination is needed.
      *
      * Effect row: `< Sync` (lazy classpath init on first access).
      *
      * @param ownerFqn   dotted FQN of the class-like that owns the method
      * @param methodName simple (unqualified) name of the method
      * @return the first matching method symbol, or `Maybe.Absent`
      */
    def findMethod(ownerFqn: String, methodName: String)(using Frame): Maybe[Symbol.Method] < Sync =
        classpath.map: cp =>
            cp.findSymbol(ownerFqn).flatMap:
                case cl: Symbol.ClassLike =>
                    import Name.asString
                    Maybe.fromOption(cl.declarationIds.flatMap: id =>
                        cp.symbol(id) match
                            case Maybe.Present(m: Symbol.Method) if m.name.asString == methodName => Chunk(m)
                            case _                                                                => Chunk.empty
                    .headOption)
                case _ => Maybe.Absent

    /** Require a class symbol by FQN, aborting when not found.
      *
      * Equivalent to `findClass(fqn)` followed by an explicit check: if the result is
      * `Maybe.Absent`, the computation aborts with `TastyError.NotFound(fqn)`.
      *
      * Use this in pipelines where the class is expected to be present and absence is an
      * unrecoverable error. Use `findClass` when the caller wants to handle absence explicitly.
      *
      * Effect row: `< Sync & Abort[TastyError]`. The `Abort` arm fires only on `NotFound`.
      *
      * @param fqn dotted fully-qualified name of the expected class
      * @return the class symbol, or `Abort.fail(TastyError.NotFound(fqn))`
      */
    def requireClass(fqn: String)(using Frame): Symbol.Class < (Sync & Abort[TastyError]) =
        findClass(fqn).map:
            case Maybe.Present(c) => c
            case Maybe.Absent     => Abort.fail(TastyError.NotFound(fqn))

    /** Require a class-like symbol by FQN, aborting when not found.
      *
      * Equivalent to `findClassLike(fqn)` followed by an explicit check: if the result is
      * `Maybe.Absent`, the computation aborts with `TastyError.NotFound(fqn)`.
      *
      * Matches Class, Trait, Object, and EnumCase at the given FQN. Use `requireClass` when
      * only `Symbol.Class` is acceptable.
      *
      * Effect row: `< Sync & Abort[TastyError]`. The `Abort` arm fires only on `NotFound`.
      *
      * @param fqn dotted fully-qualified name of the expected class-like symbol
      * @return the class-like symbol, or `Abort.fail(TastyError.NotFound(fqn))`
      */
    def requireClassLike(fqn: String)(using Frame): Symbol.ClassLike < (Sync & Abort[TastyError]) =
        findClassLike(fqn).map:
            case Maybe.Present(c) => c
            case Maybe.Absent     => Abort.fail(TastyError.NotFound(fqn))

    /** Require an object symbol by FQN, aborting when not found.
      *
      * Equivalent to `findObject(fqn)` followed by an explicit check: if the result is
      * `Maybe.Absent`, the computation aborts with `TastyError.NotFound(fqn)`.
      *
      * Accepts both source-form (`"mypackage.MyObject"`) and `$`-suffix binary-form FQNs.
      *
      * Effect row: `< Sync & Abort[TastyError]`. The `Abort` arm fires only on `NotFound`.
      *
      * @param fqn dotted source-form or `$`-suffixed binary-form FQN of the expected object
      * @return the object symbol, or `Abort.fail(TastyError.NotFound(fqn))`
      */
    def requireObject(fqn: String)(using Frame): Symbol.Object < (Sync & Abort[TastyError]) =
        findObject(fqn).map:
            case Maybe.Present(o) => o
            case Maybe.Absent     => Abort.fail(TastyError.NotFound(fqn))

    /** Require any symbol by FQN, aborting when not found.
      *
      * Equivalent to `findSymbol(fqn)` followed by an explicit check: if the result is
      * `Maybe.Absent`, the computation aborts with `TastyError.NotFound(fqn)`.
      *
      * The most permissive require variant: succeeds for any registered symbol kind. Use the
      * typed variants (`requireClass`, `requireClassLike`, `requireObject`) when a specific
      * symbol subtype is expected.
      *
      * Effect row: `< Sync & Abort[TastyError]`. The `Abort` arm fires only on `NotFound`.
      *
      * @param fqn dotted fully-qualified name of the expected symbol
      * @return the symbol, or `Abort.fail(TastyError.NotFound(fqn))`
      */
    def requireSymbol(fqn: String)(using Frame): Symbol < (Sync & Abort[TastyError]) =
        findSymbol(fqn).map:
            case Maybe.Present(s) => s
            case Maybe.Absent     => Abort.fail(TastyError.NotFound(fqn))

    /** Require a package symbol by FQN, aborting when not found.
      *
      * Equivalent to `findPackage(fqn)` followed by an explicit check: if the result is
      * `Maybe.Absent`, the computation aborts with `TastyError.NotFound(fqn)`.
      *
      * Useful in tools that assert that a specific package exists in the loaded classpath
      * (e.g., a static analysis pass that requires `scala.collection`).
      *
      * Effect row: `< Sync & Abort[TastyError]`. The `Abort` arm fires only on `NotFound`.
      *
      * @param fqn dotted package name, or `""` for the root package
      * @return the package symbol, or `Abort.fail(TastyError.NotFound(fqn))`
      */
    def requirePackage(fqn: String)(using Frame): Symbol.Package < (Sync & Abort[TastyError]) =
        findPackage(fqn).map:
            case Maybe.Present(p) => p
            case Maybe.Absent     => Abort.fail(TastyError.NotFound(fqn))

    /** Require a method symbol by owner FQN and simple name, aborting when not found.
      *
      * Equivalent to `findMethod(ownerFqn, methodName)` followed by an explicit check: if the
      * result is `Maybe.Absent`, the computation aborts with
      * `TastyError.NotFound("ownerFqn.methodName")`.
      *
      * When multiple overloads share the same name, the first one in declaration order is
      * returned (same behaviour as `findMethod`).
      *
      * Effect row: `< Sync & Abort[TastyError]`. The `Abort` arm fires only on `NotFound`.
      *
      * @param ownerFqn   dotted FQN of the class-like that owns the method
      * @param methodName simple (unqualified) name of the method
      * @return the first matching method symbol, or `Abort.fail(TastyError.NotFound(...))`
      */
    def requireMethod(ownerFqn: String, methodName: String)(using Frame): Symbol.Method < (Sync & Abort[TastyError]) =
        findMethod(ownerFqn, methodName).map:
            case Maybe.Present(m) => m
            case Maybe.Absent     => Abort.fail(TastyError.NotFound(s"$ownerFqn.$methodName"))

    /** All class-like symbols in the loaded classpath.
      *
      * Returns every `Symbol.ClassLike` regardless of sub-kind: includes `Symbol.Class`,
      * `Symbol.Trait`, `Symbol.Object`, and `Symbol.EnumCase`. The result spans all packages
      * and all classpath roots supplied to `Tasty.withClasspath`.
      *
      * Use the narrower variants (`allClasses`, `allObjects`, `allTraits`) when only a single
      * kind is needed.
      *
      * Effect row: `< Sync` (lazy classpath init on first access).
      *
      * @return all class-like symbols across the classpath; empty when the classpath is empty
      */
    def allClassLike(using Frame): Chunk[Symbol.ClassLike] < Sync =
        classpath.map(_.allClassLike)

    /** All class symbols in the loaded classpath.
      *
      * Returns every `Symbol.Class` across all packages and classpath roots. Does not include
      * `Symbol.Trait`, `Symbol.Object`, or `Symbol.EnumCase`; use `allClassLike` to include
      * those. Abstract classes are included; use `findConcreteClass` per-FQN when concreteness
      * matters.
      *
      * The result is computed by filtering `cp.symbols` on each call; for large classpaths
      * (tens of thousands of symbols) consider caching the result in the caller.
      *
      * Effect row: `< Sync` (lazy classpath init on first access).
      *
      * @return all `Symbol.Class` instances across the classpath; empty when none are found
      */
    def allClasses(using Frame): Chunk[Symbol.Class] < Sync =
        classpath.map(cp =>
            cp.symbols.flatMap:
                case c: Symbol.Class => Chunk(c)
                case _               => Chunk.empty
        )

    /** All object (module) symbols in the loaded classpath.
      *
      * Returns every `Symbol.Object` across all packages and classpath roots. Scala `object`
      * declarations and Java-style singleton patterns registered as objects are included.
      *
      * Effect row: `< Sync` (lazy classpath init on first access).
      *
      * @return all `Symbol.Object` instances across the classpath; empty when none are found
      */
    def allObjects(using Frame): Chunk[Symbol.Object] < Sync =
        classpath.map(_.allObjects)

    /** All trait symbols in the loaded classpath.
      *
      * Returns every `Symbol.Trait` across all packages and classpath roots. Sealed traits,
      * open traits, and abstract class-backed traits are all included.
      *
      * Effect row: `< Sync` (lazy classpath init on first access).
      *
      * @return all `Symbol.Trait` instances across the classpath; empty when none are found
      */
    def allTraits(using Frame): Chunk[Symbol.Trait] < Sync =
        classpath.map(_.allTraits)

    /** All method symbols in the loaded classpath.
      *
      * Returns every `Symbol.Method` across all owners and classpath roots. Includes
      * constructors (`<init>`), extension methods, and synthetic methods generated by the
      * compiler. Use `declarations` or `members` on a specific owner symbol to restrict
      * results to a single class or package.
      *
      * Effect row: `< Sync` (lazy classpath init on first access).
      *
      * @return all `Symbol.Method` instances across the classpath; empty when none are found
      */
    def allMethods(using Frame): Chunk[Symbol.Method] < Sync =
        classpath.map(_.allMethods)

    /** All val symbols in the loaded classpath.
      *
      * Returns every `Symbol.Val` (immutable value definition) across all owners and
      * classpath roots. Includes top-level vals, member vals, and lazy vals. Does not
      * include `Symbol.Var`, `Symbol.Field`, or parameters.
      *
      * Effect row: `< Sync` (lazy classpath init on first access).
      *
      * @return all `Symbol.Val` instances across the classpath; empty when none are found
      */
    def allVals(using Frame): Chunk[Symbol.Val] < Sync =
        classpath.map(_.allVals)

    /** All var symbols in the loaded classpath.
      *
      * Returns every `Symbol.Var` (mutable variable definition) across all owners and
      * classpath roots. Includes member vars but does not include `Symbol.Val` or
      * `Symbol.Field`. Java mutable fields are represented as `Symbol.Field`.
      *
      * Effect row: `< Sync` (lazy classpath init on first access).
      *
      * @return all `Symbol.Var` instances across the classpath; empty when none are found
      */
    def allVars(using Frame): Chunk[Symbol.Var] < Sync =
        classpath.map(_.allVars)

    /** All field symbols in the loaded classpath.
      *
      * Returns every `Symbol.Field` across all owners and classpath roots. Fields are the
      * Java-level representation: raw instance/static fields from `.class` files decoded via
      * the Java class reader. Scala `var` and `val` members compiled to JVM fields appear
      * as `Symbol.Field` in the Java class model; `Symbol.Var` and `Symbol.Val` are the
      * TASTy-model equivalents from Scala sources.
      *
      * Effect row: `< Sync` (lazy classpath init on first access).
      *
      * @return all `Symbol.Field` instances across the classpath; empty when none are found
      */
    def allFields(using Frame): Chunk[Symbol.Field] < Sync =
        classpath.map(_.allFields)

    /** All type declaration symbols in the loaded classpath.
      *
      * Aggregates `Symbol.TypeAlias`, `Symbol.OpaqueType`, and `Symbol.AbstractType` from
      * all owners and classpath roots. `Symbol.TypeParam` is excluded because type parameters
      * are part of their enclosing symbol's structure rather than independent declarations.
      *
      * The result is the union of `cp.allTypeAliases`, `cp.allOpaqueTypes`, and
      * `cp.allAbstractTypes`, returned as a flat `Chunk[Symbol]`.
      *
      * Effect row: `< Sync` (lazy classpath init on first access).
      *
      * @return all type declaration symbols across the classpath; empty when none are found
      */
    def allTypes(using Frame): Chunk[Symbol] < Sync =
        classpath.map(cp =>
            // flow-allow: asInstanceOf -- covariant upcast of Chunk[Symbol.TypeAlias/OpaqueType/AbstractType] to Chunk[Symbol]; safe because Chunk is covariant in its element type and all subtypes extend Symbol.
            cp.allTypeAliases.asInstanceOf[Chunk[Symbol]] ++
                cp.allOpaqueTypes.asInstanceOf[Chunk[Symbol]] ++
                cp.allAbstractTypes.asInstanceOf[Chunk[Symbol]]
        )

    /** All package symbols in the loaded classpath.
      *
      * Returns every `Symbol.Package` synthesized during classpath loading. Package symbols
      * are derived from the directory structure of each root; the root package itself is
      * included and is registered under the empty string `""`.
      *
      * Effect row: `< Sync` (lazy classpath init on first access).
      *
      * @return all `Symbol.Package` instances across the classpath; never empty (root is always present)
      */
    def allPackages(using Frame): Chunk[Symbol.Package] < Sync =
        classpath.map(_.allPackages)

    /** Return the lexically enclosing (owner) symbol of `sym`.
      *
      * Returns `Maybe.Absent` for root symbols whose `ownerId` is `-1` (the synthetic root
      * package and symbols loaded from roots that have no declared parent).
      *
      * Note: the result is the immediate owner one level up. For the full enclosing chain use
      * `ownersChain`, which walks up until reaching a root or a cycle.
      *
      * Effect row: `< Sync` (lazy classpath init on first access).
      *
      * @param sym the symbol whose owner is requested
      * @return the owner symbol wrapped in `Maybe.Present`, or `Maybe.Absent` for root symbols
      */
    def owner(sym: Symbol)(using Frame): Maybe[Symbol] < Sync =
        classpath.map(_.symbol(sym.ownerId))

    /** Compute the dotted fully-qualified name of `sym`.
      *
      * Walks the owner chain from `sym` up to the root package, joining each segment with
      * `"."`. The returned `Name` can be converted to a `String` with `import Name.asString`.
      *
      * For the human-readable code-form or simple-name alternatives, use `show(sym, format)`.
      * This method always returns the dotted FQN regardless of format.
      *
      * Effect row: `< Sync`. The computation is synchronous but suspends because `classpath`
      * access may trigger lazy initialization.
      *
      * @param sym the symbol whose FQN is computed
      * @return the fully-qualified dotted name
      */
    def fullName(sym: Symbol)(using Frame): Name < Sync =
        classpath.flatMap(cp => cp.fullName(sym))

    /** Render `sym` as a human-readable string using the given format.
      *
      * Three formats are supported via `ShowFormat`:
      * - `ShowFormat.Code` (default): the Scala source code signature, e.g. `"def foo[A](x: A): A"`.
      *   Computed by `SymbolSignature.compute`, which resolves type parameter names and parent types.
      * - `ShowFormat.FullyQualified`: the dotted FQN string, equivalent to
      *   `fullName(sym).map(_.asString)`.
      * - `ShowFormat.Simple`: the unqualified simple name, e.g. `"List"`, computed without
      *   classpath access.
      *
      * Effect row: `< Sync`. `ShowFormat.Simple` still suspends because `classpath` must be
      * obtained to satisfy the binding contract.
      *
      * @param sym    the symbol to render
      * @param format rendering format; defaults to `ShowFormat.Code`
      * @return the rendered string
      */
    def show(sym: Symbol, format: ShowFormat = ShowFormat.Code)(using Frame): String < Sync =
        classpath.flatMap: cp =>
            format match
                case ShowFormat.FullyQualified =>
                    import Name.asString
                    cp.fullName(sym).map(_.asString)
                case ShowFormat.Simple => sym.simpleName
                case ShowFormat.Code   => kyo.internal.tasty.symbol.SymbolSignature.compute(sym, cp)

    /** Compute the human-readable signature of a method symbol.
      *
      * Delegates to `SymbolSignature.compute`, producing a string in Scala source syntax,
      * e.g. `"def map[B](f: A => B): List[B]"`. Resolves type parameter and return-type
      * references to their names via the classpath.
      *
      * This is equivalent to `show(method, ShowFormat.Code)` but typed to accept only
      * `Symbol.Method`, providing a cleaner API at call sites where the method kind is
      * already known.
      *
      * Effect row: `< Sync` (lazy classpath init on first access).
      *
      * @param method the method symbol to render
      * @return the Scala-syntax signature string
      */
    def signature(method: Symbol.Method)(using Frame): String < Sync =
        classpath.flatMap(cp => kyo.internal.tasty.symbol.SymbolSignature.compute(method, cp))

    /** Compute the full owner chain of `sym`, from `sym` itself up to the root.
      *
      * The first element is `sym`; subsequent elements are its owner, the owner's owner, and
      * so on. The walk stops when `ownerId` is `-1` (root symbol with no owner) or when a
      * cycle is detected (visited set). The chain is capped at depth 64 to guard against
      * pathological inputs.
      *
      * Useful for determining nesting depth, computing enclosing packages, or checking whether
      * a symbol is a member of a specific owner chain (e.g. `ownersChain(sym).exists(_.simpleName == "MyClass")`).
      *
      * Effect row: `< Sync` (lazy classpath init on first access).
      *
      * @param sym the symbol whose owner chain is computed
      * @return ordered chain starting with `sym`; single-element when `sym` has no owner
      */
    def ownersChain(sym: Symbol)(using Frame): Chunk[Symbol] < Sync =
        classpath.map: cp =>
            val out     = Chunk.newBuilder[Symbol]
            val visited = new java.util.HashSet[Int]()
            @scala.annotation.tailrec
            def go(cur: Symbol, depth: Int): Unit =
                if depth >= 64 || !visited.add(cur.id.value) then ()
                else
                    out += cur
                    cp.symbol(cur.ownerId) match
                        case Maybe.Present(ownerSym) if ownerSym.id != cur.id => go(ownerSym, depth + 1)
                        case _                                                => ()
            go(sym, 0)
            out.result()

    /** Compute the JVM binary name of `sym`.
      *
      * The binary name uses `$` as the separator for nested and companion symbols rather than
      * `.`, following the JVM class-file convention. For example, `example.Outer.Inner` maps
      * to `"example/Outer$Inner"` and a companion object `example.MyObj` maps to
      * `"example/MyObj$"`.
      *
      * Computed by `BinaryName.compute` using the owner chain and module flags on each symbol.
      *
      * Effect row: `< Sync` (lazy classpath init on first access).
      *
      * @param sym the symbol whose binary name is computed
      * @return the JVM binary class name (slash-separated, `$` for nesting)
      */
    def binaryName(sym: Symbol)(using Frame): String < Sync =
        classpath.map(cp => kyo.internal.tasty.symbol.BinaryName.compute(sym, cp))

    /** Return the companion object or companion class of `sym`, if one exists.
      *
      * For a `Symbol.Class`, returns the associated `Symbol.Object` companion if present.
      * For a `Symbol.Object`, returns the associated `Symbol.Class` companion if present.
      * Returns `Maybe.Absent` when no companion exists, when `sym` is not a class or object,
      * or when the companion is not present in the loaded classpath.
      *
      * The lookup is O(1) via the `Classpath.companion` index built during loading.
      *
      * Effect row: `< Sync` (lazy classpath init on first access).
      *
      * @param sym the class or object symbol whose companion is requested
      * @return the companion symbol wrapped in `Maybe.Present`, or `Maybe.Absent`
      */
    def companion(sym: Symbol)(using Frame): Maybe[Symbol] < Sync =
        classpath.map(_.companion(sym))

    /** Return the type parameters declared on `sym`.
      *
      * Applies to `Symbol.ClassLike`, `Symbol.Method`, `Symbol.TypeAlias`, and
      * `Symbol.OpaqueType`. Returns an empty `Chunk` for all other symbol kinds (Val, Var,
      * Field, Package, etc.).
      *
      * Each element is a `Symbol.TypeParam` resolved from the classpath. `typeParamIds` that
      * do not resolve to a live symbol (e.g., from an incomplete classpath) are silently
      * dropped.
      *
      * Effect row: `< Sync` (lazy classpath init on first access).
      *
      * @param sym the symbol whose type parameters are requested
      * @return the type parameter symbols in declaration order; empty when none exist
      */
    def typeParams(sym: Symbol)(using Frame): Chunk[Symbol] < Sync =
        classpath.map: cp =>
            (sym match
                case c: Symbol.ClassLike   => c.typeParamIds
                case m: Symbol.Method      => m.typeParamIds
                case ta: Symbol.TypeAlias  => ta.typeParamIds
                case ot: Symbol.OpaqueType => ot.typeParamIds
                case _                     => Chunk.empty
            ).flatMap(id => cp.symbol(id).toChunk)

    /** Return the symbols declared directly on `sym`.
      *
      * For `Symbol.ClassLike`, reads `declarationIds` (methods, vals, vars, nested types,
      * and nested classes declared on the class body). For `Symbol.Package`, reads `memberIds`
      * (top-level classes and objects in the package). Returns an empty `Chunk` for all
      * other symbol kinds.
      *
      * Does not include inherited members. Use `members(sym, MemberScope.All)` to include
      * symbols inherited from parent types.
      *
      * Effect row: `< Sync` (lazy classpath init on first access).
      *
      * @param sym the symbol whose direct declarations are requested
      * @return the declared symbols in registration order; empty for non-container symbols
      */
    def declarations(sym: Symbol)(using Frame): Chunk[Symbol] < Sync =
        classpath.map: cp =>
            (sym match
                case c: Symbol.ClassLike => c.declarationIds
                case p: Symbol.Package   => p.memberIds
                case _                   => Chunk.empty
            ).flatMap(id => cp.symbol(id).toChunk)

    /** Return the permitted direct subclasses of a sealed class or trait.
      *
      * For sealed `Symbol.Class` or sealed `Symbol.Trait`, returns the symbols recorded in
      * `permittedSubclassIds`. Returns an empty `Chunk` for non-sealed symbols and for
      * symbol kinds other than `Symbol.Class` and `Symbol.Trait`.
      *
      * IMPORTANT: the result contains only the directly permitted subclasses as encoded in
      * the TASTy/classfile. Indirect subtypes (sub-subtypes) are not included; call this
      * method recursively on each element when a full sealed hierarchy is needed.
      *
      * Effect row: `< Sync` (lazy classpath init on first access).
      *
      * @param sym the sealed class or trait whose permitted subclasses are requested
      * @return the permitted subclass symbols; empty for non-sealed or non-class-like symbols
      */
    def permittedSubclasses(sym: Symbol)(using Frame): Chunk[Symbol] < Sync =
        classpath.map: cp =>
            (sym match
                case c: Symbol.Class => c.permittedSubclassIds
                case t: Symbol.Trait => t.permittedSubclassIds
                case _               => Maybe.Absent
            ).map(_.flatMap(id => cp.symbol(id).toChunk)).getOrElse(Chunk.empty)

    /** Return the direct parent class-like symbols of `cl`.
      *
      * Reads `cl.parentTypes`, filters for `Type.Named` entries, and resolves each `SymbolId`
      * to the corresponding symbol in the classpath. Parent types of other shapes (e.g.
      * `Type.Applied` for generic parents) are not resolved here; use `cl.parentTypes` directly
      * when raw type information is needed.
      *
      * IMPORTANT: only direct (first-generation) parents are returned. Use `ownersChain` or
      * recursive calls to `parents` for deeper ancestry traversal.
      *
      * Effect row: `< Sync` (lazy classpath init on first access).
      *
      * @param cl the class-like symbol whose direct parents are requested
      * @return the resolved parent symbols; may be empty for `AnyRef`-rooted classes
      */
    def parents(cl: Symbol.ClassLike)(using Frame): Chunk[Symbol] < Sync =
        classpath.map: cp =>
            cl.parentTypes.flatMap { case Type.Named(pid) => cp.symbol(pid).toChunk; case _ => Chunk.empty }

    /** Return the members of `sym` filtered by the given `MemberScope`.
      *
      * Three scopes are available:
      * - `MemberScope.Declared` (default): symbols declared directly on `sym` (same as
      *   `declarations`). O(n) in the number of declared members.
      * - `MemberScope.Inherited`: symbols inherited from parent types and not redeclared on
      *   `sym`. The parent walk deduplicates by `simpleName`; the first (most-specific)
      *   occurrence wins.
      * - `MemberScope.All`: union of Declared and Inherited, deduplicated by `simpleName`.
      *   Most-specific (nearest in the hierarchy) symbol wins on name clash.
      *
      * For Package symbols, `Declared` and `All` both read `memberIds`; `Inherited` returns
      * empty (packages do not inherit). Non-container symbols return empty for all scopes.
      *
      * Effect row: `< Sync` (lazy classpath init on first access).
      *
      * @param sym   the symbol whose members are requested
      * @param scope which members to include; defaults to `MemberScope.Declared`
      * @return the member symbols; empty when `sym` has no members in the given scope
      */
    def members(sym: Symbol, scope: MemberScope = MemberScope.Declared)(using Frame): Chunk[Symbol] < Sync =
        classpath.map: cp =>
            scope match
                case MemberScope.Declared =>
                    (sym match
                        case c: Symbol.ClassLike => c.declarationIds
                        case p: Symbol.Package   => p.memberIds
                        case _                   => Chunk.empty
                    ).flatMap(id => cp.symbol(id).toChunk)
                case MemberScope.Inherited =>
                    val declIds = sym match
                        case c: Symbol.ClassLike => c.declarationIds
                        case p: Symbol.Package   => p.memberIds
                        case _                   => Chunk.empty
                    val directNames = scala.collection.mutable.HashSet.empty[String]
                    declIds.foreach(id => cp.symbol(id).foreach(s => discard(directNames.add(s.simpleName))))
                    allMembersOf(sym, cp).filter(s => !directNames.contains(s.simpleName))
                case MemberScope.All => allMembersOf(sym, cp)

    /** Find a member of `sym` by simple name within the given scope.
      *
      * Calls `members(sym, scope)` and returns the first symbol whose `simpleName` equals
      * `name`. Returns `Maybe.Absent` when no member with that name is found in the given
      * scope.
      *
      * When multiple overloads exist under the same simple name, the first one in the
      * `members` result is returned. Use `members(sym, scope)` and filter manually when
      * overload discrimination is required.
      *
      * Effect row: `< Sync` (lazy classpath init on first access).
      *
      * @param sym   the owning symbol to search
      * @param name  the simple (unqualified) member name to look up
      * @param scope which members to search; defaults to `MemberScope.Declared`
      * @return the first matching member symbol, or `Maybe.Absent`
      */
    def findMember(sym: Symbol, name: String, scope: MemberScope = MemberScope.Declared)(using Frame): Maybe[Symbol] < Sync =
        members(sym, scope).map(ms => Maybe.fromOption(ms.find(_.simpleName == name)))

    /** Find a directly-declared member of `sym` by simple name.
      *
      * Shorthand for `findMember(sym, name, MemberScope.Declared)`. Searches only symbols
      * declared on `sym` itself, not symbols inherited from parent types.
      *
      * Returns `Maybe.Absent` when no declared member with that simple name exists. When
      * inherited members should be considered, use `findMember` with `MemberScope.All`.
      *
      * Effect row: `< Sync` (lazy classpath init on first access).
      *
      * @param sym  the owning symbol to search
      * @param name the simple (unqualified) member name to look up
      * @return the first matching declared member symbol, or `Maybe.Absent`
      */
    def findDeclaredMember(sym: Symbol, name: String)(using Frame): Maybe[Symbol] < Sync =
        findMember(sym, name, MemberScope.Declared)

    /** Return `true` when `sym` carries the Scala or Java annotation with the given FQN.
      *
      * Checks the annotation list of `sym` against `fqn`:
      * - For Scala annotations: resolves `annotationType` to its FQN string via
      *   `cp.typeFqnStringUnsafe` and compares to `fqn`.
      * - For Java annotations (`Symbol.Field` and `Symbol.ClassLike`): resolves
      *   `annotationClass` to its full name via `cp.fullNameUnsafe` and compares.
      *
      * Symbol kinds with no annotation storage (e.g. raw `Symbol.Package`) always return
      * `false`. The check is linear in the number of annotations on `sym`.
      *
      * Effect row: `< Sync`. The `Sync.Unsafe.defer` inside confines the unsafe FQN
      * resolution within a safe effect boundary.
      *
      * @param sym the symbol to inspect
      * @param fqn dotted FQN of the annotation class (e.g. `"scala.annotation.tailrec"`)
      * @return `true` if at least one matching annotation is present, `false` otherwise
      */
    def hasAnnotation(sym: Symbol, fqn: String)(using Frame): Boolean < Sync =
        classpath.flatMap: cp =>
            Sync.Unsafe.defer:
                def matchScala(a: Annotation): Boolean =
                    cp.typeFqnStringUnsafe(a.annotationType) == fqn
                def matchJava(a: Java.Annotation): Boolean =
                    import Name.asString
                    cp.fullNameUnsafe(a.annotationClass).asString == fqn
                sym match
                    case c: Symbol.ClassLike    => c.annotations.exists(matchScala) || c.javaAnnotations.exists(matchJava)
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

    /** Find the first Scala or Java annotation matching `fqn` on `sym`.
      *
      * Returns `Maybe.Present(annotation)` for the first annotation whose class FQN equals
      * `fqn`, where the union type `Annotation | Java.Annotation` carries either a decoded
      * Scala `Annotation` (with typed arguments) or a raw `Java.Annotation`.
      *
      * For `Symbol.ClassLike`, Scala annotations are checked first; if none match, Java
      * annotations are checked. For `Symbol.Field`, only Java annotations are checked.
      *
      * Returns `Maybe.Absent` when no matching annotation exists on `sym`, or when `sym`
      * is a kind that does not carry annotations (e.g., a bare `Symbol.Package`).
      *
      * Effect row: `< Sync`. The `Sync.Unsafe.defer` confines unsafe FQN resolution.
      *
      * @param sym the symbol to inspect
      * @param fqn dotted FQN of the annotation class
      * @return the first matching annotation, or `Maybe.Absent`
      */
    def findAnnotation(sym: Symbol, fqn: String)(using Frame): Maybe[Annotation | Java.Annotation] < Sync =
        classpath.flatMap: cp =>
            Sync.Unsafe.defer:
                def matchScala(a: Annotation): Boolean =
                    cp.typeFqnStringUnsafe(a.annotationType) == fqn
                def matchJava(a: Java.Annotation): Boolean =
                    import Name.asString
                    cp.fullNameUnsafe(a.annotationClass).asString == fqn
                sym match
                    case c: Symbol.ClassLike =>
                        Maybe.fromOption(c.annotations.find(matchScala).orElse(c.javaAnnotations.find(matchJava)))
                    case m: Symbol.Method       => Maybe.fromOption(m.annotations.find(matchScala))
                    case v: Symbol.Val          => Maybe.fromOption(v.annotations.find(matchScala))
                    case w: Symbol.Var          => Maybe.fromOption(w.annotations.find(matchScala))
                    case f: Symbol.Field        => Maybe.fromOption(f.javaAnnotations.find(matchJava))
                    case t: Symbol.TypeAlias    => Maybe.fromOption(t.annotations.find(matchScala))
                    case t: Symbol.OpaqueType   => Maybe.fromOption(t.annotations.find(matchScala))
                    case t: Symbol.AbstractType => Maybe.fromOption(t.annotations.find(matchScala))
                    case p: Symbol.Parameter    => Maybe.fromOption(p.annotations.find(matchScala))
                    case _                      => Maybe.Absent
                end match

    /** Return all symbols in the loaded classpath carrying the annotation with `fqn`.
      *
      * Delegates to `Classpath.symbolsAnnotatedWith`, which performs a full linear scan of
      * `cp.symbols` and checks each symbol against the given annotation FQN (same matching
      * logic as `hasAnnotation`). For large classpaths this is O(n) in the number of loaded
      * symbols; consider caching the result in the caller when querying the same annotation
      * repeatedly.
      *
      * Effect row: `< Sync`. The scan runs inside a `Sync`-suspended fiber step to confine
      * the unsafe annotation resolution.
      *
      * @param fqn dotted FQN of the annotation class to scan for
      * @return all symbols carrying at least one annotation of the given FQN; empty when none
      */
    def symbolsAnnotatedWith(fqn: String)(using Frame): Chunk[Symbol] < Sync =
        classpath.flatMap(_.symbolsAnnotatedWith(fqn))

    /** Decode the body tree of sym, memoizing the result in the active DecodeContext.
      *
      * Returns `Absent` for symbols whose `bodyRecord` slot is `Absent` (Package, Java, and symbols without an AST
      * body slice), and also returns `Absent` when called outside a `withClasspath(roots,...)` or `withPickles`
      * scope (i.e. when `TastyState.bindingLocal` holds a `Binding` with no `DecodeContext`).
      *
      * Memoization: the first call for a given `sym` decodes the bytes and stores the result (success or failure)
      * in the `DecodeContext`'s `bodyMemo`. All subsequent calls for the same `sym` return the stored result
      * without re-decoding. The memo is keyed by `sym.id` (SymbolId) and is per-`withClasspath` invocation;
      * a second `withClasspath` call produces a fresh `DecodeContext` with an empty memo.
      *
      * INV-009 site-3: `AllowUnsafe` is confined to `Sync.Unsafe.defer`; it does not appear on this signature.
      * INV-010: the top-level signature does not carry `AllowUnsafe`.
      */
    def bodyTree(sym: Symbol)(using frame: Frame): Maybe[Tree] < (Sync & Abort[TastyError]) =
        TastyState.bindingLocal.use: mbind =>
            val maybeCtx = mbind.flatMap(_.decodeCtx)
            if maybeCtx.isEmpty then Maybe.Absent
            else
                val ctx  = maybeCtx.get
                val blob = ctx.bodyStore.get(sym.id)
                if blob == null then Maybe.Absent
                else
                    val cp = mbind.get.cp
                    Sync.Unsafe.defer:
                        // INV-009 site-3: Sync.Unsafe.defer is the only AllowUnsafe in the public query layer.
                        val cached = ctx.bodyMemo.get(sym.id)
                        if cached != null then
                            cached match
                                case Result.Success(t) => Maybe(t)
                                case Result.Failure(e) => Abort.fail(e)
                                case Result.Panic(t)   => throw t
                        else
                            val result: Result[TastyError, Tree] =
                                try
                                    val syms = cp.symbols
                                    Result.Success(kyo.internal.tasty.reader.TreeUnpickler.decodeSync(
                                        blob,
                                        sym,
                                        idx => if idx >= 0 && idx < syms.size then syms(idx) else sym
                                    ))
                                catch
                                    case ex: kyo.internal.tasty.reader.TreeUnpickler.DecodeException =>
                                        Result.Failure(TastyError.MalformedSection("ASTs", ex.getMessage, ex.byteOffset))
                                    case _: ArrayIndexOutOfBoundsException =>
                                        Result.Failure(TastyError.MalformedSection("ASTs", "truncated body", 0L))
                                    case _: IllegalStateException =>
                                        // mmap arena closed before bodyTree ran; documented contract is ClasspathClosed.
                                        Result.Failure(TastyError.ClasspathClosed(s"bodyTree(sym.id=${sym.id.value})"))
                            ctx.bodyMemo.put(sym.id, result)
                            result match
                                case Result.Success(t) => Maybe(t)
                                case Result.Failure(e) => Abort.fail(e)
                                case Result.Panic(t)   => throw t
                            end match
                        end if
                end if
            end if
    end bodyTree

    /** Resolve the symbol referenced by a `Type.Named`.
      *
      * Returns `Maybe.Present(symbol)` when `tpe` is a `Type.Named` whose `SymbolId` resolves
      * to a live symbol in the loaded classpath. Returns `Maybe.Absent` for all other `Type`
      * shapes (e.g. `Type.Applied`, `Type.Array`, `Type.Function`).
      *
      * Effect row: `< Sync` (lazy classpath init on first access).
      *
      * @param tpe the type whose referenced symbol is requested
      * @return the symbol wrapped in `Maybe.Present`, or `Maybe.Absent` for non-Named types
      */
    def typeSymbol(tpe: Type)(using Frame): Maybe[Symbol] < Sync =
        classpath.map: cp =>
            tpe match
                case Type.Named(id) => cp.symbol(id)
                case _              => Maybe.Absent

    /** Structural subtype check between two `Type` values.
      *
      * Returns a `SubtypeVerdict`:
      * - `SubtypeVerdict.Sub`: `tpe` is a structural subtype of `other`.
      * - `SubtypeVerdict.NotSub`: `tpe` is not a subtype of `other`.
      * - `SubtypeVerdict.Indeterminate`: the walk exhausted the budget (depth 64) without
      *   a definitive answer, or the type shapes were unsupported.
      *
      * Unhandled parent-walk shapes are collected in `decodeCtx.subtypingErrors` during the
      * call and folded into `cp.errors` on the next `Tasty.classpath` read within the scope
      * (Q-003 binding). The verdict signature carries no `Abort[TastyError]` row; diagnostics
      * are surfaced through the error channel.
      *
      * Effect row: `< Sync`. The check runs inside `Sync.defer` rather than returning a
      * plain value because it reads the `TastyState.bindingLocal`.
      *
      * @param tpe   the candidate subtype
      * @param other the candidate supertype
      * @return the subtype verdict
      */
    def isSubtypeOf(tpe: Type, other: Type)(using Frame): SubtypeVerdict < Sync =
        TastyState.bindingLocal.use: mbind =>
            val binding = mbind.getOrElse(TastyState.global)
            val cp      = binding.cp
            val errAcc  = binding.decodeCtx.map(_.subtypingErrors).getOrElse(null)
            Sync.defer(kyo.internal.tasty.type_.Subtyping.isSubtype(tpe, other, cp, budget = 64, errAcc))

    /** Render a `Type` as a human-readable string.
      *
      * Recursively converts the `Type` ADT to a display string. `Type.Named` nodes are
      * resolved to their symbol's `simpleName` via the classpath; unresolved ids render as
      * `"<unresolved>"`. Composite types render in a Scala-like syntax (e.g.
      * `"List[String]"`, `"(Int, String) => Boolean"`).
      *
      * Effect row: `< Sync` (lazy classpath init on first access).
      *
      * @param tpe the type to render
      * @return a human-readable string representation
      */
    def typeShow(tpe: Type)(using Frame): String < Sync =
        classpath.map: cp =>
            import Name.asString
            def renderType(t: Type): String = t match
                case Type.Named(id)           => cp.symbol(id).map(_.name.asString).getOrElse("<unresolved>")
                case Type.Applied(base, args) => s"${renderType(base)}[${args.map(renderType).mkString(", ")}]"
                case Type.Array(elem)         => s"${renderType(elem)}[]"
                case Type.Function(ps, r) =>
                    s"(${ps.map(renderType).mkString(", ")}) => ${renderType(r)}"
                case Type.ContextFunction(ps, r) => s"(${ps.map(renderType).mkString(", ")}) ?=> ${renderType(r)}"
                case Type.Tuple(es)              => s"(${es.map(renderType).mkString(", ")})"
                case Type.Nothing                => "Nothing"
                case Type.Any                    => "Any"
                case other                       => other.toString
            renderType(tpe)

    /** Human-readable rendering of a Tree (resolves symbols and types via the Classpath). */
    def treeShow(tree: Tree)(using Frame): String < Sync =
        classpath.map(cp => kyo.internal.tasty.reader.TreeShow.show(tree, cp))

    // ── Private helpers ─────────────────────────────────────────────────────

    private def allMembersOf(sym: Symbol, cp: Classpath): Chunk[Symbol] =
        sym match
            case c: Symbol.ClassLike =>
                val seen = scala.collection.mutable.HashSet.empty[String]
                val out  = Chunk.newBuilder[Symbol]
                def visit(cl: Symbol.ClassLike): Unit =
                    cl.declarationIds.foreach: id =>
                        cp.symbol(id).foreach: d =>
                            val nm = d.simpleName
                            if seen.add(nm) then out += d
                    cl.parentTypes.foreach:
                        case Type.Named(pid) =>
                            cp.symbol(pid).foreach:
                                case pcl: Symbol.ClassLike => visit(pcl)
                                case _                     => ()
                        case _ => ()
                end visit
                visit(c)
                out.result()
            case _ => Chunk.empty

end Tasty
