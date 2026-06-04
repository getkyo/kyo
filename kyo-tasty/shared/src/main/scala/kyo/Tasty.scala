package kyo

import kyo.internal.tasty.binary.Utf8
import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.query.PlatformFileSource
import kyo.internal.tasty.query.PlatformModuleOps
import kyo.internal.tasty.query.TastyStat
import kyo.internal.tasty.snapshot.DigestComputer as SnapshotDigest
import kyo.internal.tasty.snapshot.SnapshotReader
import kyo.internal.tasty.snapshot.SnapshotWriter
import kyo.internal.tasty.type_.TypeArena
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
  * AST model returned by `Symbol.bodyTree` for method and val bodies. `Annotation` / `JavaAnnotation` cover Scala
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
      * stable across distinct Classpath instances (different `Classpath.init` calls produce independent id spaces).
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

        private val nameOf: Map[Long, String] = Map(
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

    // ── Symbol kinds (internal only) ────────────────────────────────────────
    // SymbolKind is kept as private[kyo] for the snapshot wire format
    // (SnapshotWriter uses sym.kind.ordinal.toByte as the discriminator byte).
    // It is NOT part of the public API surface. User code should use sealed
    // pattern matching on Symbol subtypes instead.
    private[kyo] enum SymbolKind derives CanEqual:
        case Package, Class, Trait, Object, Method, Field, Val, Var,
            TypeAlias, OpaqueType, AbstractType, TypeParam, Parameter,
            EnumCase, Unresolved
    end SymbolKind

    // ── Error mode ───────────────────────────────────────────────────────────

    /** Controls error handling during classpath open.
      *
      * Passed to `Classpath.init` (and its cached variant) to select between a tolerant load and an early
      * abort. The mode only governs decode errors found while walking the classpath; missing entries that
      * surface later (an FQN that resolves to no symbol, a subtype check that touches an unresolved parent)
      * are reported through their own return shapes (`Maybe`, `SubtypeVerdict.Unknown`) regardless of mode.
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

    /** Three-way result of a subtype check.
      *
      * Returned by `Type.isSubtypeOf` to encode the difference between "decided no" and "could not decide".
      * Subtype resolution walks the parent chain of `Type.Named` references through the classpath; the
      * classpath may not contain every transitive parent (typical when a host application links against a
      * subset of the runtime libraries), so the check is partial by construction.
      *
      *   - `Sub`: the subtype relation definitively holds (`t <: other`).
      *   - `NotSub`: the subtype relation definitively does not hold.
      *   - `Unknown`: the check could not reach a definitive verdict (recursion budget exhausted, or a
      *     parent chain referenced an `Unresolved` symbol not present in the loaded classpath). Treat
      *     `Unknown` as "neither yes nor no"; do not collapse it to either side.
      *
      * Equality is structural via `derives CanEqual`.
      */
    enum SubtypeVerdict derives CanEqual:
        case Sub, NotSub, Unknown
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
      * Carried by `Symbol.position: Maybe[Position]`. `Absent` for classfile-sourced symbols and for
      * TASTy symbols loaded from a file without a Positions section; otherwise populated from the TASTy
      * Positions table during classpath open. Positions point at the symbol's declaration site, not at
      * every reference to it. There is no per-tree positional information; the public model deliberately
      * stops at the symbol level.
      *
      * `sourceFile` is the file name from the Attributes section (if present), exactly as recorded in the
      * pickle (no path normalisation). `line` and `column` are 1-based (line 1 is the first line of the
      * file; column 1 is the first character of the line). A column of 0 is possible when the underlying
      * TASTy entry carried no column information.
      *
      * Equality is structural across all three fields (case class auto-generation).
      */
    final case class Position(sourceFile: Maybe[String], line: Int, column: Int) derives Schema, CanEqual:
        /** Human-readable representation: `file:line:column`, or `<unknown>:line:column` when `sourceFile` is absent. */
        def show: String =
            val file = sourceFile match
                case Maybe.Present(f) => f
                case Maybe.Absent     => "<unknown>"
            s"$file:$line:$column"
        end show
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

    /** A single declared field of a Java record (JVMS `Record` attribute entry).
      *
      * Carried by `JavaMetadata.recordComponents: Chunk[RecordComponent]` on the symbol of the record
      * class itself. Java records expose their components in declaration order; the loader preserves that
      * order so the chunk index aligns with the canonical constructor parameter list.
      *
      * `name` is the component's source-level name; `tpe` is its declared type as resolved against the
      * classpath at load time. Equality is structural across both fields. Present only on JVM symbols
      * (the attribute is JVMS-defined); `Absent` on TASTy-sourced symbols even if they happen to be record
      * classes, because the record-ness lives in the JVM attribute and not in the TASTy ADT.
      */
    final case class RecordComponent(name: Name, tpe: Type) derives CanEqual

    /** Parameter-name table for one method overload: the method's name plus the names of its parameters in source order.
      *
      * Carried by `JavaMetadata.parameterNames: Chunk[ParamGroup]` on the owning class symbol. Java classfiles
      * record parameter names in a `MethodParameters` attribute when compiled with `-parameters`; the loader
      * groups those entries by their owning method so each `ParamGroup` corresponds to one overload of
      * `methodName`. `parameterNames` is in source order and may be empty (no `MethodParameters` attribute or
      * a zero-arity method).
      *
      * Equality is structural across both fields (case class auto-generation).
      */
    final case class ParamGroup(methodName: Name, parameterNames: Chunk[Name]) derives CanEqual

    /** The enclosing-method context for a local or anonymous class (JVMS `EnclosingMethod` attribute).
      *
      * Carried by `JavaMetadata.enclosingMethod: Maybe[EnclosingMethod]` on the symbol of a local or
      * anonymous class. The JVMS records the immediately enclosing method for any class that was declared
      * inside one; absent otherwise. `owner` is the enclosing method's owner class symbol, and `methodName`
      * is the enclosing method's source-level name. Use this to walk back from an anonymous inner class
      * symbol to the method that declared it.
      *
      * Equality is structural across both fields (case class auto-generation).
      */
    final case class EnclosingMethod(owner: Symbol, methodName: Name) derives CanEqual

    /** JVM-only metadata attached to symbols sourced from `.class` files.
      *
      * Carried by `Symbol.javaMetadata: Maybe[JavaMetadata]` and `Absent` on symbols that come from TASTy
      * sources only (where the equivalent information lives in `Symbol.flags`, `Annotation`, etc.). This
      * companion exposes the JVM-specific attributes that have no clean TASTy analogue: the JVM access flag
      * word, the `throws` clause, the `EnclosingMethod` attribute for local / anonymous classes, the `Record`
      * component table for Java records, the bootstrap method table, the nest host / nest members for
      * Java 11+ nestmates, parameter-name groups, and runtime type annotations.
      *
      * **Annotations.** `annotations` carries `RuntimeVisibleAnnotations` and `RuntimeInvisibleAnnotations`
      * decoded into the `JavaAnnotation` ADT; `runtimeTypeAnnotations` covers the type-annotation flavour
      * (`RuntimeVisibleTypeAnnotations` and its invisible sibling). Scala-side annotations on the same symbol
      * still live in the symbol's `annotations` field, not here.
      *
      * **Access flags.** `accessFlags` is the raw 16-bit access flag word; `isJvmPublic`, `isJvmPrivate`,
      * `isJvmProtected`, `isJvmStatic`, and `isJvmFinal` are the common predicates. For flags without a
      * predicate, mask `accessFlags` against the JVMS constants directly.
      */
    final case class JavaMetadata(
        throwsTypes: Chunk[Type],
        annotations: Chunk[JavaAnnotation],
        enclosingMethod: Maybe[EnclosingMethod],
        accessFlags: Int,
        recordComponents: Chunk[RecordComponent],
        bootstrapMethods: Chunk[Chunk[Int]],
        nestHost: Maybe[Symbol],
        nestMembers: Chunk[Symbol],
        paramNames: Chunk[ParamGroup],
        runtimeTypeAnnotations: Chunk[JavaAnnotation]
    ) derives Schema, CanEqual

    /** A Java retention-class annotation decoded from a `.class` file's
      * `RuntimeVisibleAnnotations` / `RuntimeInvisibleAnnotations` attribute.
      *
      * Kept structurally separate from `Annotation` (the Scala-source annotation type) because the value
      * spaces are different: a Java annotation's element values are primitive constants, class literals, enum
      * constants, nested annotations, and arrays thereof, while a Scala annotation carries arbitrary
      * `Tree.Apply` arguments. Mixing the two into a single ADT would require either lossy normalisation or
      * a sum type at every callsite; keeping them parallel keeps each side honest.
      *
      * `annotationClass` is the resolved `Symbol` for the annotation interface (e.g. the symbol of
      * `java.lang.SuppressWarnings`). `values` is the ordered list of `(elementName, value)` pairs as they
      * appeared in the classfile; element names are interned `Name` values and ordering matches the source
      * declaration. Element values are typed via the `JavaAnnotation.Value` enum nested in the companion.
      *
      * **Querying.** `Symbol.hasAnnotation(fqn)` and `Symbol.findAnnotation(fqn)` walk both this list and the
      * Scala `annotations` list (where applicable), so the common "is this symbol annotated with X" question
      * does not need to branch on the value space.
      */
    final case class JavaAnnotation(annotationClass: Symbol, values: Chunk[(Name, JavaAnnotation.Value)])
        derives CanEqual
    object JavaAnnotation:
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
            case AnnotationVal(nested: JavaAnnotation)
        end Value

        // Schema for JavaAnnotation.Value: recursive type requires a lazy given.
        // JavaAnnotation.Value.AnnotationVal contains JavaAnnotation which contains Value.
        // The lazy initialization breaks the compile-time recursion.
        private var _schemaValue: Schema[JavaAnnotation.Value] = null.asInstanceOf[Schema[JavaAnnotation.Value]]
        given schemaValue: Schema[JavaAnnotation.Value] =
            if _schemaValue == null then
                _schemaValue = Schema.derived[JavaAnnotation.Value]
            _schemaValue
        end schemaValue

        private var _schema: Schema[JavaAnnotation] = null.asInstanceOf[Schema[JavaAnnotation]]
        given schema: Schema[JavaAnnotation] =
            if _schema == null then
                _schema = Schema.derived[JavaAnnotation]
            _schema
        end schema
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
    ) derives Schema, CanEqual

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
      * all surviving `Type` values are canonical within `Classpath.canonical`. Structurally equal types produced at
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
    enum Type derives Schema:
        case Named(symbolId: SymbolId)
        case TermRef(prefix: Type, name: Name)
        case Applied(base: Type, args: Chunk[Type])
        case TypeLambda(paramIds: Chunk[SymbolId], body: Type)
        case Function(params: Chunk[Type], result: Type, isContext: Boolean)

        /** Context function type: `(A1, ..., AN) ?=> R`.
          *
          * Wire-level: `APPLIEDtype` whose constructor has FQN `scala.ContextFunctionN`. Dedicated case so callers
          * can pattern-match `?=>` against `=>` without testing a Boolean flag. Methods decoded from
          * `scala.ContextFunctionN` produce this case; methods decoded from `scala.FunctionN` produce
          * `Type.Function(_, _, isContext = false)`.
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

        /** Sentinel unknown type used when `declaredType` is absent but a concrete `Type` field is required. */
        case Unknown

        /** Visit each direct child of this Type without allocating an intermediate Chunk.
          *
          * Used internally by `children` and `foreach` so the hot traversal path does not materialize a
          * Chunk per node.
          */
        def visit(f: Type => Unit): Unit = this match
            case Applied(base, args) =>
                f(base); args.foreach(f)
            case TypeLambda(_, body) => f(body)
            case Function(params, ret, _) =>
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
        def foreach(f: Type => Unit): Unit =
            f(this)
            visit(_.foreach(f))
        end foreach
    end Type

    object Type:
        given CanEqual[Type, Type] = CanEqual.canEqualAny
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

        /** Term-position path-dependent reference (F-B-004). Wire tag TERMREFin (174).
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
      *   The raw AST section bytes for this file. Shared (not copied) across all symbols from the same file. Stored as Span[Byte] for
      *   structural equality and zero-overhead immutable-view semantics (no boxing; Span[Byte] is opaque over Array[Byte]).
      * @param names
      *   The name table for this file, as decoded by NameUnpickler. Shared across all symbols from the same file. Stored as Span[Name]
      *   for structural equality (default case-class equality used reference identity on Array, which made two loads of the same file
      *   compare unequal).
      * @param sectionOffset
      *   Absolute byte offset where the AST section starts in the original TASTy file. Used to convert section-relative addrs to absolute.
      * @param addrMap
      *   Maps TASTy byte address to SymbolId for IDENT/SELECT tree references during lazy body decode.
      */
    final case class SymbolBody private[kyo] (
        bodyStart: Int,
        bodyEnd: Int,
        sectionBytes: Span[Byte],
        names: Span[Name],
        sectionOffset: Int,
        private[kyo] val addrMap: IntMap[SymbolId]
    ):
        override def equals(other: Any): Boolean = other match
            case that: SymbolBody =>
                bodyStart == that.bodyStart &&
                bodyEnd == that.bodyEnd &&
                sectionOffset == that.sectionOffset &&
                addrMap == that.addrMap &&
                sectionBytes.is(that.sectionBytes) &&
                namesEqual(names, that.names)
            case _ => false

        // Compare two Span[Name] structurally by string content.
        // Name is opaque over String; equality is String equality, which is content-based.
        private def namesEqual(a: Span[Name], b: Span[Name]): Boolean =
            // Pure: Name.asString returns the underlying String; no side effect, no AllowUnsafe proof required.
            import Name.asString
            val len = a.size
            if len != b.size then false
            else
                var i  = 0
                var ok = true
                while i < len && ok do
                    if a(i).asString != b(i).asString then ok = false
                    i += 1
                ok
            end if
        end namesEqual

        override def hashCode(): Int =
            // Pure: Name.asString returns the underlying String; referentially transparent.
            import Name.asString
            var h = 1
            h = 31 * h + bodyStart
            h = 31 * h + bodyEnd
            h = 31 * h + sectionOffset
            h = 31 * h + addrMap.hashCode
            h = 31 * h + sectionBytes.hash
            // Hash names by string content for cross-classpath consistency.
            val namesLen = names.size
            var i        = 0
            while i < namesLen do
                h = 31 * h + names(i).asString.hashCode
                i += 1
            h
        end hashCode

        /** Render this SymbolBody without leaking array identity hashes.
          *
          * The default case-class toString prints `sectionBytes` and `names` as `[B@<hash>` and
          * `[Lkyo.Tasty$Name;@<hash>` respectively, which is useless for debugging. This override renders
          * `sectionBytes` as `len=<N>` and `names` as `names=[<N> entries]` so assertion failure messages and
          * debug logs contain actionable information.
          */
        override def toString: String =
            s"SymbolBody(bodyStart=$bodyStart, bodyEnd=$bodyEnd, sectionBytes=len=${sectionBytes.size}, " +
                s"names=[${names.size} entries], sectionOffset=$sectionOffset, addrMap=${addrMap.size} entries)"
    end SymbolBody

    // ── SymbolBody Schema (opaque placeholder for Schema derivation) ─────────
    // SymbolBody carries mmap byte slices and decode context; it is not
    // serializable via Schema in a meaningful way. This private[kyo] given
    // satisfies derives Schema on Symbol subtypes that carry body fields.
    // Encoding writes empty bytes; decoding returns a sentinel SymbolBody.
    private[kyo] given schemaSymbolBody: Schema[SymbolBody] =
        Schema.init[SymbolBody](
            writeFn = (_, w) => w.bytes(Span.empty[Byte]),
            readFn = r =>
                discard(r.bytes())
                // Return sentinel SymbolBody with empty byte slices
                SymbolBody(0, 0, Span.empty[Byte], Span.empty[Name], 0, scala.collection.immutable.IntMap.empty)
        )

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
      * Constructed by `ClasspathOrchestrator.materializeSymbols` during Pass C of `Classpath.init`. After construction, every field is
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
          * F-E-006 fix: dotty emits Flag.Macro on enum-case synthetic methods (ordinal, productElement, etc.). These are NOT
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
            case _: Symbol.Unresolved   => SymbolKind.Unresolved

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

        // Resolution accessors still needed by some internal callers for backward compat
        def owner(using cp: Classpath): Maybe[Symbol] =
            if ownerId.value == -1 then Maybe.Absent
            else Maybe(cp.symbol(ownerId))

        def companion(using cp: Classpath): Maybe[Symbol] = cp.companion(this)

        def fullName(using frame: Frame, cp: Classpath): Name < Sync = cp.fullName(this)

        def binaryName(using cp: Classpath): String =
            kyo.internal.tasty.symbol.BinaryName.compute(this, cp)

        def show(using frame: Frame, cp: Classpath): String < Sync = kyo.internal.tasty.symbol.SymbolShow.show(this, cp)

        def fullNameString(using frame: Frame, cp: Classpath): String < Sync =
            import Name.asString
            fullName.map(_.asString)

        def ownersChain(using cp: Classpath): Chunk[Symbol] =
            val out     = Chunk.newBuilder[Symbol]
            val visited = new java.util.HashSet[Int]()
            @scala.annotation.tailrec
            def go(cur: Symbol, depth: Int): Unit =
                if depth >= 64 || !visited.add(cur.id.value) then ()
                else
                    out += cur
                    val ownerSym = cp.symbol(cur.ownerId)
                    if ownerSym.id == cur.id || ownerSym.id.value == -1 then ()
                    else go(ownerSym, depth + 1)
            go(this, 0)
            out.result()
        end ownersChain

        def signature(using frame: Frame, cp: Classpath): String < Sync =
            kyo.internal.tasty.symbol.SymbolSignature.compute(this, cp)

        def show(format: ShowFormat)(using frame: Frame, cp: Classpath): String < Sync = format match
            case ShowFormat.FullyQualified => fullNameString
            case ShowFormat.Simple         => Sync.defer(simpleName)
            case ShowFormat.Code           => signature

        def declaredMembers(using cp: Classpath): Chunk[Symbol] = this match
            case c: Symbol.ClassLike => c.declarations
            case _                   => Chunk.empty

        def allMembers(using cp: Classpath): Chunk[Symbol] = this match
            case c: Symbol.ClassLike =>
                val seen = scala.collection.mutable.HashSet.empty[String]
                val out  = Chunk.newBuilder[Symbol]
                def visit(cl: Symbol.ClassLike): Unit =
                    cl.declarations.foreach: d =>
                        val nm = d.simpleName
                        if seen.add(nm) then out += d
                    cl.parents.foreach:
                        case pcl: Symbol.ClassLike => visit(pcl)
                        case _                     => ()
                end visit
                visit(c)
                out.result()
            case _ => Chunk.empty

        def findDeclaredMember(name: String)(using cp: Classpath): Maybe[Symbol] =
            Maybe.fromOption(declaredMembers.find(_.simpleName == name))

        def findInheritedMember(name: String)(using cp: Classpath): Maybe[Symbol] = this match
            case c: Symbol.ClassLike =>
                val seen     = scala.collection.mutable.HashSet.empty[String]
                val directs  = declaredMembers
                var i        = 0
                val directLn = directs.size
                while i < directLn do
                    discard(seen.add(directs(i).simpleName))
                    i += 1
                var found: Maybe[Symbol] = Maybe.Absent
                def visit(cl: Symbol.ClassLike): Boolean =
                    if found.isDefined then true
                    else
                        val decls   = cl.declarations
                        var j       = 0
                        val declsLn = decls.size
                        while j < declsLn && found.isEmpty do
                            val d  = decls(j)
                            val nm = d.simpleName
                            if seen.add(nm) && nm == name then found = Maybe(d)
                            j += 1
                        end while
                        if found.isDefined then true
                        else
                            val ps   = cl.parents
                            var k    = 0
                            val psLn = ps.size
                            var done = false
                            while k < psLn && !done do
                                ps(k) match
                                    case pcl: Symbol.ClassLike => done = visit(pcl)
                                    case _                     => ()
                                k += 1
                            end while
                            done
                        end if
                end visit
                val ps    = c.parents
                var pi    = 0
                val psLn  = ps.size
                var done0 = false
                while pi < psLn && !done0 do
                    ps(pi) match
                        case pcl: Symbol.ClassLike => done0 = visit(pcl)
                        case _                     => ()
                    pi += 1
                end while
                found
            case _ => Maybe.Absent

        def findAnyMember(name: String)(using cp: Classpath): Maybe[Symbol] =
            Maybe.fromOption(allMembers.find(_.simpleName == name))

        def parents(using cp: Classpath): Chunk[Symbol] =
            (this match
                case c: Symbol.ClassLike => c.parentTypes
                case _                   => Chunk.empty
            ).collect { case Type.Named(pid) => cp.symbol(pid) }

        def typeParams(using cp: Classpath): Chunk[Symbol] =
            (this match
                case c: Symbol.ClassLike   => c.typeParamIds
                case m: Symbol.Method      => m.typeParamIds
                case ta: Symbol.TypeAlias  => ta.typeParamIds
                case ot: Symbol.OpaqueType => ot.typeParamIds
                case _                     => Chunk.empty
            ).map(cp.symbol)

        def declarations(using cp: Classpath): Chunk[Symbol] =
            (this match
                case c: Symbol.ClassLike => c.declarationIds
                case p: Symbol.Package   => p.memberIds
                case _                   => Chunk.empty
            ).map(cp.symbol)

        def permittedSubclasses(using cp: Classpath): Chunk[Symbol] =
            (this match
                case c: Symbol.Class => c.permittedSubclassIds
                case t: Symbol.Trait => t.permittedSubclassIds
                case _               => Maybe.Absent
            ).map(_.map(cp.symbol)).getOrElse(Chunk.empty)

        def methods(using cp: Classpath): Chunk[Symbol] =
            declarations.filter(_.isInstanceOf[Symbol.Method])

        def vals(using cp: Classpath): Chunk[Symbol] =
            declarations.filter(_.isInstanceOf[Symbol.Val])

        def vars(using cp: Classpath): Chunk[Symbol] =
            declarations.filter(_.isInstanceOf[Symbol.Var])

        def fields(using cp: Classpath): Chunk[Symbol] =
            declarations.filter(_.isInstanceOf[Symbol.Field])

        def nestedTypes(using cp: Classpath): Chunk[Symbol] =
            declarations.filter(s =>
                s.isInstanceOf[Symbol.Class] || s.isInstanceOf[Symbol.Trait] || s.isInstanceOf[Symbol.Object]
            )

        def membersByKind(k: SymbolKind)(using cp: Classpath): Chunk[Symbol] =
            declarations.filter(_.kind == k)

        def bodyTree(using frame: Frame, cp: Classpath): Maybe[Tree] < (Sync & Abort[TastyError]) =
            cp.bodyTree(this)

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
            def javaMetadata: Maybe[JavaMetadata]
            def parentTypes: Chunk[Type]
            def typeParamIds: Chunk[SymbolId]
            def declarationIds: Chunk[SymbolId]
            def annotations: Chunk[Annotation]
            def javaAnnotations: Chunk[JavaAnnotation]
            def body: Maybe[SymbolBody]

            // Override declarations/parents/etc. for ClassLike-typed callers (narrowing)
            override def declarations(using cp: Classpath): Chunk[Symbol] =
                if declarationIds.isEmpty then Chunk.empty
                else declarationIds.map(cp.symbol)

            override def parents(using cp: Classpath): Chunk[Symbol] =
                parentTypes.collect { case Type.Named(pid) => cp.symbol(pid) }

            override def companion(using cp: Classpath): Maybe[Symbol] = cp.companion(this)

        end ClassLike

        // ── 14 final case classes ─────────────────────────────────────────────

        /** A `class` declaration: Scala source `class`, Java `class`, the lifted backing class of a Scala 3 `enum`.
          *
          * `permittedSubclassIds` is `Present(ids)` for sealed parents; `Absent` for non-sealed classes.
          * `javaMetadata` is `Present` for symbols sourced from `.class` files.
          * `body` is the AST bytes envelope decoded lazily via `Tasty.bodyTree`.
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
            javaMetadata: Maybe[JavaMetadata],
            parentTypes: Chunk[Type],
            typeParamIds: Chunk[SymbolId],
            declarationIds: Chunk[SymbolId],
            permittedSubclassIds: Maybe[Chunk[SymbolId]],
            annotations: Chunk[Annotation],
            javaAnnotations: Chunk[JavaAnnotation],
            body: Maybe[SymbolBody]
        ) extends ClassLike derives Schema, CanEqual

        /** A single case of a Scala 3 enum (F-E-007).
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
            javaMetadata: Maybe[JavaMetadata],
            parentTypes: Chunk[Type],
            typeParamIds: Chunk[SymbolId],
            declarationIds: Chunk[SymbolId],
            permittedSubclassIds: Maybe[Chunk[SymbolId]],
            annotations: Chunk[Annotation],
            javaAnnotations: Chunk[JavaAnnotation],
            body: Maybe[SymbolBody]
        ) extends ClassLike derives Schema, CanEqual

        /** A `trait` declaration: Scala source `trait` or a Java `interface` (which the loader normalizes to this
          * representation). Shares the `ClassLike` shape with `Class` and `Object`; the difference is that a Trait
          * cannot be `new`-ed directly and that Java interfaces collapse `default` and `static` methods into its
          * `declarationIds`.
          *
          * `parentTypes` carries the source-order `extends`/`with` types; for a Java interface the head is
          * `java.lang.Object` followed by the declared interface parents. `permittedSubclassIds` is `Present` for
          * sealed traits, `Absent` otherwise (use `isSealed` to discriminate). `javaMetadata` is `Present` for
          * interfaces sourced from `.class` files; `body` is the template envelope decoded lazily via `bodyTree`.
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
            javaMetadata: Maybe[JavaMetadata],
            parentTypes: Chunk[Type],
            typeParamIds: Chunk[SymbolId],
            declarationIds: Chunk[SymbolId],
            permittedSubclassIds: Maybe[Chunk[SymbolId]],
            annotations: Chunk[Annotation],
            javaAnnotations: Chunk[JavaAnnotation],
            body: Maybe[SymbolBody]
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
            javaMetadata: Maybe[JavaMetadata],
            parentTypes: Chunk[Type],
            typeParamIds: Chunk[SymbolId],
            declarationIds: Chunk[SymbolId],
            annotations: Chunk[Annotation],
            javaAnnotations: Chunk[JavaAnnotation],
            body: Maybe[SymbolBody]
        ) extends ClassLike derives Schema, CanEqual

        /** A `def`: a Scala source `def`, a Scala constructor (`<init>`), an extension method, a `transparent inline
          * def`, or a Java method. `paramListIds` records parameter groups in source order; each inner `Chunk`
          * resolves through `paramLists(using cp)` into `Symbol.Parameter` entries. `typeParamIds` carries the
          * method's own type parameters; per-parameter-list type parameters appear under those parameter symbols.
          *
          * `declaredType` is the method's `MethodType` view (parameter types + result), `Present` for symbols with
          * a recorded signature and `Absent` for synthetics whose type the loader did not retain. `returnType`
          * unwraps the `Type.Function` or `Type.ContextFunction` result; for non-function shapes it returns the
          * declared type as-is. `body` is the AST envelope for the implementation, decoded lazily via `bodyTree`;
          * abstract methods (`flags.contains(Flag.Abstract)`) and methods sourced from `.class` files carry
          * `Absent` here.
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
            body: Maybe[SymbolBody],
            javaMetadata: Maybe[JavaMetadata]
        ) extends Symbol derives Schema, CanEqual

        /** A Scala `val`: an immutable value member of a class, trait, object, or top-level package. Also represents
          * a Scala 3 enum case that has no parameters (the case is lifted to a `Val` on the enum's companion with
          * `Flag.Case | Flag.Enum` set). Java has no Scala-shaped `val`; Java `final` fields surface as `Field`.
          *
          * `declaredType` is `Present` for any Scala-sourced `val`; the only `Absent` case is synthetic ValDefs the
          * loader has reason to keep without a recorded type. `body` is the AST for the right-hand side, decoded
          * lazily through `bodyTree`; `Absent` for abstract members and for cases where the loader did not retain
          * the initializer.
          */
        final case class Val(
            id: SymbolId,
            name: Name,
            flags: Flags,
            ownerId: SymbolId,
            scaladoc: Maybe[String],
            sourcePosition: Maybe[Position],
            declaredType: Maybe[Type],
            annotations: Chunk[Annotation],
            body: Maybe[SymbolBody]
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
            annotations: Chunk[Annotation],
            body: Maybe[SymbolBody]
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
            javaMetadata: Maybe[JavaMetadata],
            javaAnnotations: Chunk[JavaAnnotation]
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
            body: Type,
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
            body: Type,
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
            declaredType: Type,
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

        /** An unresolved or placeholder symbol.
          *
          * `flags` always equals `Flags.empty` in practice; the default is intentional so that call sites that construct a minimal sentinel
          * (id, name, ownerId) do not need to supply flags explicitly. The `copy` method will preserve `Flags.empty` when flags is omitted,
          * which is the correct behavior for unresolved symbols.
          */
        final case class Unresolved(
            id: SymbolId,
            name: Name,
            ownerId: SymbolId,
            flags: Flags = Flags.empty
        ) extends Symbol derives Schema, CanEqual:
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

    // Schema[Symbol] derived after object Symbol is fully defined.
    // Schema[Symbol.ClassLike] is provided via object Symbol.ClassLike companion (inside object Symbol)
    // which Scala 3 implicit lookup finds via companion object rules when expanding Schema[Symbol].
    given schemaSymbol: Schema[Symbol] = Schema.derived

    // ── Pickle (in-memory TASTy + classfile bytes) ──────────────────────────

    /** In-memory TASTy pickle: header UUID, format version, and raw `.tasty` bytes.
      *
      * A `Pickle` is the smallest input unit the classpath loader accepts: one `.tasty` file decoded into
      * its header and body. `Classpath.fromPickles` and `Classpath.fromPicklesWithSymbols` accept a `Chunk`
      * of `Pickle` values for tests and out-of-band classpath construction; the standard `Classpath.init`
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
        private[kyo] val fqnIndex: Map[String, SymbolId],
        private[kyo] val packageIndex: Map[String, SymbolId],
        private[kyo] val subclassIndex: Map[SymbolId, Chunk[SymbolId]],
        private[kyo] val companionIndex: Map[SymbolId, SymbolId],
        private[kyo] val moduleIndex: Map[String, ModuleDescriptor],
        errors: Chunk[TastyError],
        canonical: kyo.internal.tasty.type_.TypeArena,
        /** Structured diagnostics accumulated during classpath initialization.
          *
          * Currently populated with `Classpath.FqnCollision` entries when two roots each define a symbol under the same fully-qualified
          * name. Under `ErrorMode.SoftFail` these are recorded here rather than raising `Abort[TastyError]`. Under `ErrorMode.FailFast`
          * the first collision raises `TastyError.InconsistentClasspath` and initialization aborts.
          *
          * Unlike `errors` (which carries decode-time failures such as `MalformedSection`), `diagnostics` carries build-time observations
          * about the classpath shape itself.
          */
        diagnostics: Chunk[Classpath.Diagnostic],
        /** Map from negative SymbolId values to their fully-qualified name string for annotation types that could not be resolved
          * because the defining library (e.g. scala-library) is not on the classpath.
          *
          * Used by `typeFqnString` as a fallback when `symbol(id)` returns `sentinelUnresolved` for a negative id. This enables
          * `symbolsAnnotatedWith` to find symbols annotated with `scala.deprecated` or `scala.annotation.tailrec` even on JS/Native
          * where the embedded fixture set does not include scala-library.
          */
        private[kyo] val unresolvedFqnByNegId: Map[Int, String]
    ):
        // NOT constructor parameters -- excluded from auto-generated equals / hashCode / copy / unapply.
        // A cp.copy(...) call produces a new Classpath with fresh empty memos; this is correct because
        // memoized results are an optimization, not observable state.
        private lazy val bodyMemo: java.util.concurrent.ConcurrentHashMap[SymbolId, Result[TastyError, Tree]] =
            new java.util.concurrent.ConcurrentHashMap()

        // F-W2-7: cached unresolvedTypeReferenceCount -- linear scan performed once and memoized.
        // A cp.copy(...) call recomputes this lazily on the new Classpath.
        private lazy val cachedUnresolvedTypeReferenceCount: Int =
            val sentinelId = Classpath.sentinelUnresolved.id.value
            symbols.foldLeft(0): (acc, sym) =>
                sym match
                    case c: Symbol.ClassLike =>
                        acc + c.parentTypes.count:
                            case Type.Named(id) => id.value == sentinelId
                            case _              => false
                    case _ => acc
        end cachedUnresolvedTypeReferenceCount

        // F-W2-24: simple-name index for O(1) findClassesByName. Keyed by String content; built once.
        // Name is opaque over String so equality is content-based across all classpaths.
        // A cp.copy(...) call rebuilds this lazily on the new Classpath.
        private lazy val nameIndex: Map[String, Chunk[Symbol.Class]] =
            import Name.asString
            val builder = scala.collection.mutable.HashMap.empty[String, scala.collection.mutable.ArrayBuffer[Symbol.Class]]
            symbols.foreach:
                case c: Symbol.Class =>
                    builder.getOrElseUpdate(c.name.asString, new scala.collection.mutable.ArrayBuffer()) += c
                case _ => ()
            builder.map((k, v) => k -> Chunk.from(v)).toMap
        end nameIndex

        /** Bucketed view of `symbols` keyed by `SymbolKind`. Built once on first access; each `allXxx`
          * accessor becomes an O(1) map lookup that reuses the cached Chunk for its kind. Empty classpaths
          * yield an empty map; missing kinds return `Chunk.empty`. The aggregated ClassLike view
          * (`Class ∪ Trait ∪ Object ∪ EnumCase`) is materialized once in `cachedAllClassLike`.
          */
        private lazy val symbolsByKind: Map[SymbolKind, Chunk[Symbol]] =
            if symbols.isEmpty then Map.empty
            else
                val buckets = scala.collection.mutable.HashMap.empty[SymbolKind, scala.collection.mutable.ArrayBuffer[Symbol]]
                symbols.foreach: s =>
                    buckets.getOrElseUpdate(s.kind, new scala.collection.mutable.ArrayBuffer()) += s
                buckets.map((k, v) => k -> Chunk.from(v)).toMap
            end if
        end symbolsByKind

        private def symbolsOfKind[A <: Symbol](k: SymbolKind): Chunk[A] =
            // safe: `symbolsByKind` is built by Pass C so that the kind key matches the runtime class of
            // every contained Symbol; callers (allTraits/allObjects/...) pick `A` to align with `k`.
            symbolsByKind.getOrElse(k, Chunk.empty).asInstanceOf[Chunk[A]]

        /** Cached ClassLike aggregate: every Class, Trait, Object, and EnumCase symbol. Materialized once
          * on first access by `allClassLike`. Empty when no such symbols exist.
          */
        private lazy val cachedAllClassLike: Chunk[Symbol.ClassLike] =
            if symbols.isEmpty then Chunk.empty
            else
                val b = Chunk.newBuilder[Symbol.ClassLike]
                symbols.foreach:
                    case c: Symbol.ClassLike => b += c
                    case _                   => ()
                b.result()
            end if
        end cachedAllClassLike

        /** O(1) Symbol lookup by SymbolId. Returns the Symbol at index `id.value`. Returns a sentinel Unresolved symbol for out-of-range or
          * unassigned ids.
          *
          * SymbolIds are only valid within the Classpath that produced them. Passing a SymbolId from one classpath into another classpath's
          * `symbol(id)` returns whatever Symbol happens to sit at that index in the receiving classpath (usually an unrelated symbol),
          * not the originating one. Cross-classpath operations should resolve by FQN via `findSymbol` / `findClass` / `findObject`, not
          * by SymbolId.
          *
          * Sentinel cases:
          *   - `id.value == -1`: the canonical sentinel value (SymbolId.sentinel); returns `sentinelUnresolved`.
          *   - `id.value < 0` (including `Int.MinValue`): any negative index is treated identically to -1 and returns `sentinelUnresolved`.
          *     Only -1 is documented as the sentinel by convention, but all negative values are safe.
          *   - `id.value >= symbols.length`: out-of-range positive index; returns `sentinelUnresolved`.
          *   - Empty classpath (`symbols.isEmpty`, `rootSymbolId.value == -1`): `cp.symbol(cp.rootSymbolId)` returns `sentinelUnresolved`
          *     because `rootSymbolId` is -1 when no symbols were loaded. Callers that expect a synthetic root Package should check
          *     `symbols.nonEmpty` first.
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
          *
          * Null safety: a `null` `fqn` argument resolves to `Maybe.Absent` (Scala Map.get(null) returns None). No NPE is raised.
          * Defensive null checks in call sites are unnecessary.
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
          * Null safety: a `null` `fqn` argument returns `Maybe.Absent`; no NPE is raised. An empty string `""` also returns
          * `Maybe.Absent` because no symbol is registered under the empty key.
          *
          * Example:
          * ```scala
          *   val sym: Maybe[Symbol.Class] = cp.findClass("scala.collection.List")
          * ```
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
          * (e.g., JDK types when no JDK roots are passed to Classpath.init). This metric provides
          * visibility into how many Named(-1) sentinels remain in parentTypes after the cross-file
          * resolution pass.
          *
          * Note: a count > 0 is expected behavior when the classpath does not include all transitive
          * dependencies. It is not an error condition.
          *
          * Performance: the result is computed once and cached. Repeated calls are O(1). The
          * cache is NOT a constructor parameter and is NOT preserved by `cp.copy(...)`; copying recomputes
          * it lazily on the next access of the new Classpath.
          */
        def unresolvedTypeReferenceCount: Int = cachedUnresolvedTypeReferenceCount

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
            fqnIndex.get(fqn) match
                case Some(id) =>
                    symbol(id) match
                        case o: Symbol.Object => Maybe(o)
                        case _                =>
                            // Source-form FQN is taken by a non-Object (e.g. the case class itself).
                            // Fall back to the binary $-suffixed key where the companion Object lives.
                            if fqn.endsWith("$") then Maybe.Absent
                            else
                                fqnIndex.get(fqn + "$") match
                                    case Some(id2) =>
                                        symbol(id2) match
                                            case o: Symbol.Object => Maybe(o)
                                            case _                => Maybe.Absent
                                    case None => Maybe.Absent
                case None =>
                    // No entry at the source-form key; try the binary $-suffixed key directly.
                    if fqn.endsWith("$") then Maybe.Absent
                    else
                        fqnIndex.get(fqn + "$") match
                            case Some(id2) =>
                                symbol(id2) match
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
          * Returns an empty Chunk when no match is found.
          *
          * Performance: O(1) lookup via an internal name index built lazily on first access.
          * The index maps interned `Name` values to `Chunk[Symbol.Class]`. Subsequent calls on the same
          * `Classpath` instance are O(1). The index is NOT preserved by `cp.copy(...)`; copying rebuilds
          * it lazily on the next `findClassesByName` call of the new Classpath.
          */
        def findClassesByName(simpleName: String): Chunk[Symbol.Class] =
            nameIndex.getOrElse(simpleName, Chunk.empty)
        end findClassesByName

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
            Maybe.fromOption(moduleIndex.get(name))

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
        def requireModule(name: String)(using Frame): ModuleDescriptor < Abort[TastyError] =
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
            diagnostics.flatMap:
                case c: Classpath.FqnCollision => Chunk(c)
                case null                      => Chunk.empty

        // ── typed Classpath-wide all* aggregations ──

        /** All Trait symbols in the classpath. O(1) lookup via `symbolsByKind`. */
        def allTraits: Chunk[Symbol.Trait] = symbolsOfKind(SymbolKind.Trait)

        /** All Object symbols in the classpath. O(1) lookup via `symbolsByKind`. */
        def allObjects: Chunk[Symbol.Object] = symbolsOfKind(SymbolKind.Object)

        /** All ClassLike symbols (Class, Trait, Object, EnumCase) at any nesting depth.
          *
          * The invariant `allClassLike.size >= topLevelClasses.size` holds because the result includes
          * nested ClassLike symbols. Use `allTraits` / `allObjects` / `symbolsOfKind(SymbolKind.Class)`
          * to narrow to a specific subtype; the result here keeps the union of all four.
          *
          * O(1) after first call: backed by `cachedAllClassLike`, populated lazily.
          */
        def allClassLike: Chunk[Symbol.ClassLike] = cachedAllClassLike

        /** All Method symbols in the classpath. O(1) lookup via `symbolsByKind`. */
        def allMethods: Chunk[Symbol.Method] = symbolsOfKind(SymbolKind.Method)

        /** All Val symbols in the classpath. O(1) lookup via `symbolsByKind`. */
        def allVals: Chunk[Symbol.Val] = symbolsOfKind(SymbolKind.Val)

        /** All Var symbols in the classpath. O(1) lookup via `symbolsByKind`. */
        def allVars: Chunk[Symbol.Var] = symbolsOfKind(SymbolKind.Var)

        /** All Field symbols (Java-level) in the classpath. O(1) lookup via `symbolsByKind`. */
        def allFields: Chunk[Symbol.Field] = symbolsOfKind(SymbolKind.Field)

        /** All TypeAlias symbols in the classpath. O(1) lookup via `symbolsByKind`. */
        def allTypeAliases: Chunk[Symbol.TypeAlias] = symbolsOfKind(SymbolKind.TypeAlias)

        /** All OpaqueType symbols in the classpath. O(1) lookup via `symbolsByKind`. */
        def allOpaqueTypes: Chunk[Symbol.OpaqueType] = symbolsOfKind(SymbolKind.OpaqueType)

        /** All AbstractType symbols in the classpath. O(1) lookup via `symbolsByKind`. */
        def allAbstractTypes: Chunk[Symbol.AbstractType] = symbolsOfKind(SymbolKind.AbstractType)

        /** All TypeParam symbols in the classpath. O(1) lookup via `symbolsByKind`. */
        def allTypeParams: Chunk[Symbol.TypeParam] = symbolsOfKind(SymbolKind.TypeParam)

        /** All Parameter symbols in the classpath. O(1) lookup via `symbolsByKind`. */
        def allParameters: Chunk[Symbol.Parameter] = symbolsOfKind(SymbolKind.Parameter)

        /** All Package symbols in the classpath. O(1) lookup via `symbolsByKind`. */
        def allPackages: Chunk[Symbol.Package] = symbolsOfKind(SymbolKind.Package)

        /** All Unresolved symbols in the classpath. O(1) lookup via `symbolsByKind`. */
        def allUnresolved: Chunk[Symbol.Unresolved] = symbolsOfKind(SymbolKind.Unresolved)

        /** All symbols carrying the Scala or Java annotation whose fully-qualified name is `annotationFqn`.
          *
          * Checks Scala `annotations` (via `Annotation.annotationType`: must be `Type.Named(id)` whose FQN matches `annotationFqn`) and
          * Java `javaAnnotations` (via `JavaAnnotation.annotationClass` FQN). Symbols that carry neither field (TypeParam, Package,
          * Unresolved) are excluded.
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
          * F-G-001 fix: annotation tycons decoded from TYPEREF wire tag arrive as Type.TermRef.
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
                    val sym = symbol(id)
                    if sym eq Classpath.sentinelUnresolved then
                        // The type resolved to the sentinel. For annotation types that reference
                        // external symbols (e.g. scala.deprecated when scala-library is absent),
                        // the negative SymbolId may have a known FQN in unresolvedFqnByNegId.
                        unresolvedFqnByNegId.getOrElse(SymbolId.value(id), "")
                    else fullNameUnsafe(sym).asString
                    end if
                case Type.TermRef(qual, name) =>
                    val q = typeFqnStringUnsafe(qual)
                    if q.nonEmpty then q + "." + name.asString else name.asString
                case Type.TypeRef(qual, name) =>
                    // F-A-009: TYPEREF now emits TypeRef; annotation FQN matching must handle both forms.
                    val q = typeFqnStringUnsafe(qual)
                    if q.nonEmpty then q + "." + name.asString else name.asString
                case Type.Applied(base, _) =>
                    // F-I-003 fix: @Child[T] enrichment wraps the TermRef tycon in Applied(tycon, Chunk(T)).
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
            companionIndex.get(sym.id) match
                case Some(cid) => Maybe(symbol(cid))
                case None      => Maybe.Absent

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
                        val ownerSym = symbol(ownerIdCur)
                        if ownerSym.id == cur.id || ownerSym.name.asString.isEmpty then nextAcc
                        else go(ownerSym, depth + 1, nextAcc)
                    end if
            val parts = go(sym, 0, Nil)
            (parts.mkString("."): Name)
        end fullNameUnsafe

        /** Decode the body bytes of `sym` into a `Tree`, memoizing the result.
          *
          * Returns `Absent` for symbols whose `bodyRecord` slot is `Absent` (Package, Java, and symbols without an AST body slice). Fails
          * with `TastyError.MalformedSection` on corrupt body bytes.
          *
          * Memoization: the first call for a given `sym` decodes the bytes and stores the result (success or failure) in `bodyMemo`. All
          * subsequent calls for the same `sym` return the stored result without re-decoding. The memo is keyed by `sym.id` (SymbolId) and
          * is per-Classpath instance; `cp.copy(...)` produces a fresh memo.
          *
          * Called by `Symbol.bodyTree(using frame, cp)`. INV-010: AllowUnsafe does not appear on this signature.
          */
        def bodyTree(sym: Symbol)(using Frame): Maybe[Tree] < (Sync & Abort[TastyError]) =
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
                        if cached != null then
                            cached match
                                case Result.Success(t) => Maybe(t)
                                case Result.Failure(e) => Abort.fail(e)
                                case Result.Panic(t)   => throw t
                        else
                            val result: Result[TastyError, Tree] =
                                try
                                    val syms = symbols
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
                                        // F-W2-2: mmap arena closed before bodyTree ran; documented contract is ClasspathClosed.
                                        Result.Failure(TastyError.ClasspathClosed(s"bodyTree(sym.id=${sym.id.value})"))
                            bodyMemo.put(sym.id, result)
                            result match
                                case Result.Success(t) => Maybe(t)
                                case Result.Failure(e) => Abort.fail(e)
                                case Result.Panic(t)   => throw t
                            end match
                        end if
            end match
        end bodyTree

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
            @scala.annotation.tailrec
            def bfs(frontier: Chunk[SymbolId]): Unit =
                if frontier.isEmpty then ()
                else
                    val next = frontier.flatMap: curId =>
                        subclassIndex.getOrElse(curId, Chunk.empty).flatMap: childId =>
                            if visited.add(childId) then
                                symbol(childId) match
                                    case c: Symbol.ClassLike =>
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

        /** Sentinel symbol returned by `Classpath.symbol` for out-of-range or unassigned ids. */
        val sentinelUnresolved: Symbol =
            Symbol.Unresolved(SymbolId(-1), ("<unresolved>": Name), SymbolId(-1))
        end sentinelUnresolved

        /** Sealed hierarchy for structured build-time observations accumulated in `Classpath.diagnostics`.
          *
          * Unlike `TastyError` (which represents failures during decoding or classpath operations), `Diagnostic` represents observations
          * about the classpath shape that do not prevent a usable classpath from being returned. Currently the only concrete type is
          * `FqnCollision`.
          */
        sealed trait Diagnostic derives CanEqual

        /** Recorded when two or more source roots each provide a symbol under the same fully-qualified name.
          *
          * `fqn` is the colliding fully-qualified name. `ids` contains the `SymbolId` of every symbol that was registered under this FQN
          * across the input roots; the winning symbol (the one returned by `findSymbol(fqn)`) is the last entry in insertion order.
          *
          * This diagnostic is only populated under `ErrorMode.SoftFail`. Under `ErrorMode.FailFast`, a collision immediately raises
          * `TastyError.InconsistentClasspath` and initialization aborts.
          */
        final case class FqnCollision(fqn: String, ids: Chunk[SymbolId]) extends Diagnostic

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

        /** Initialize a classpath and pass it to `f`, returning `f`'s result.
          *
          * Mirrors the `init`/`initWith` pattern used elsewhere in Kyo: the classpath is built via `init(roots)`, then `f` runs with it as
          * its first argument. Scope semantics are inherited from `init`; the classpath remains open for the duration of `f` and is closed
          * when the enclosing `Scope` exits.
          */
        def initWith[A, S](roots: Seq[String])(f: Classpath => A < S)(using Frame): A < (Async & Scope & Abort[TastyError] & S) =
            init(roots).map(f)

        /** Initialize a classpath, run `f` with it, and discard the classpath when `f` completes.
          *
          * Equivalent to `Scope.run(initWith(roots)(f))`: the classpath is created inside a fresh scope so that finalizers (JAR pool
          * closures, mmap arena releases) fire as soon as `f` returns, instead of leaking into the caller's scope.
          */
        def use[A, S](roots: Seq[String])(f: Classpath => A < S)(using Frame): A < (Async & Abort[TastyError] & S) =
            Scope.run(initWith(roots)(f))

        /** Init the classpath and additionally pre-load JDK `module-info.class` entries from the JDK module image.
          *
          * Opt-in JDK auto-discovery. On JVM, reads module-info.class files for all JDK modules from the `jrt:/`
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
          * The returned Classpath is immutable; this overload does not weaken that invariant.
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
            // Sync.Unsafe.defer supplies AllowUnsafe to just the listJdkClassFiles call, so the rest of
            // the for-comprehension cannot pick up the proof implicitly.
            Sync.Unsafe.defer(kyo.internal.tasty.query.PlatformModuleOps.listJdkClassFiles(moduleFilter)).map: jdkClassFiles =>
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
        def initCached(roots: Seq[String], cacheDir: String)(using Frame): Classpath < (Async & Scope & Abort[TastyError]) =
            initCachedImpl(roots, cacheDir)

        /** Create a classpath from pre-parsed in-memory pickles.
          *
          * Each `Pickle`'s `.bytes` are treated as a TASTy file. Pickles are decoded sequentially using the same pipeline as `init`, but
          * without filesystem access. The effect row matches `init` because decoding uses the same parallel pipeline.
          *
          * An empty `pickles` sequence returns an empty `Classpath` with no symbols and no errors.
          *
          * Each
          * pickle's bytes are decoded and merged into the returned classpath.
          */
        def fromPickles(pickles: Seq[Pickle])(using Frame): Classpath < (Async & Scope & Abort[TastyError]) =
            if pickles.isEmpty then
                // Empty pickles: build an empty Classpath without touching the orchestrator pipeline.
                ClasspathOrchestrator.init(Seq.empty, ErrorMode.SoftFail, PlatformFileSource.get, concurrency = 1)
            else
                // Build synthetic paths for each pickle so ClasspathOrchestrator can route them.
                val indexed: Seq[(String, Array[Byte])] =
                    pickles.zipWithIndex.map: (p, i) =>
                        (s"pickle://${p.uuid.replace(':', '_')}/$i.tasty", p.bytes.toArray)
                val roots                              = indexed.map(_._1)
                val bytesMap: Map[String, Array[Byte]] = indexed.toMap
                // In-memory FileSource backed by the pickle byte arrays.
                val source = new kyo.internal.tasty.query.FileSource:
                    def read(path: String)(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
                        bytesMap.get(path) match
                            case Some(b) => Sync.defer(b)
                            case None    => Abort.fail(TastyError.FileNotFound(path))
                    def write(path: String, bytes: Array[Byte])(using Frame): Unit < (Sync & Abort[TastyError]) =
                        Abort.fail(TastyError.SnapshotIoError("fromPickles source is read-only"))
                    def rename(from: String, to: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
                        Abort.fail(TastyError.SnapshotIoError("fromPickles source is read-only"))
                    def mkdirs(path: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
                        Abort.fail(TastyError.SnapshotIoError("fromPickles source is read-only"))
                    def list(dir: String, suffixes: Chunk[String])(using Frame): Chunk[String] < (Sync & Abort[TastyError]) =
                        // Each pickle path is itself the "root" so list is not used by the orchestrator in single-file mode.
                        Sync.defer(Chunk.empty[String])
                    def exists(path: String)(using Frame): Boolean < Sync =
                        Sync.defer(bytesMap.contains(path))
                    def stat(path: String)(using Frame): kyo.internal.tasty.query.FileSource.FileStat < (Sync & Abort[TastyError]) =
                        bytesMap.get(path) match
                            case Some(b) => Sync.defer(kyo.internal.tasty.query.FileSource.FileStat(mtimeMs = 0L, size = b.length.toLong))
                            case None    => Abort.fail(TastyError.FileNotFound(path))
                ClasspathOrchestrator.init(roots, ErrorMode.SoftFail, source, concurrency = 1)

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
                    canonical = TypeArena.canonical(),
                    diagnostics = Chunk.empty,
                    unresolvedFqnByNegId = Map.empty
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
            canonical: kyo.internal.tasty.type_.TypeArena,
            diagnostics: Chunk[Classpath.Diagnostic] = Chunk.empty,
            unresolvedFqnByNegId: Map[Int, String] = Map.empty
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
                canonical = canonical,
                diagnostics = diagnostics,
                unresolvedFqnByNegId = unresolvedFqnByNegId
            )
        end make

        /** CanEqual instance for structural equality comparisons in tests. */
        given CanEqual[Classpath, Classpath] = CanEqual.canEqualAny

        /** Internal helper: copy a Classpath with a replacement errors field.
          *
          * Equivalent to `cp.copy(errors = newErrors)` but accessible from `kyo.internal.*` callers via `private[kyo]`.
          *
          * The Classpath case class is `final case class Classpath private[Tasty](...)`, so `cp.copy(...)` is
          * inaccessible from outside `object Tasty` because the constructor is `private[Tasty]`. This helper
          * is the correct bridge for internal callers that must adjust the errors field.
          */
        private[kyo] def copyWithErrors(cp: Classpath, newErrors: Chunk[TastyError]): Classpath =
            cp.copy(errors = newErrors)

        /** Internal helper: prepend pre-errors (e.g., FileNotFound for missing roots under SoftFail) to cp.errors.
          *
          * Called from `ClasspathOrchestrator.init` after running the decode pipeline with only the valid roots. Accessible from
          * `kyo.internal.*` callers via `private[kyo]`.
          */
        private[kyo] def copyWithPreErrors(cp: Classpath, preErrors: Chunk[TastyError]): Classpath =
            cp.copy(errors = preErrors ++ cp.errors)

    end Classpath

    // ── FQN helper ──────────────────────────────────────────────────────────

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

    // ── Snapshot management ─────────────────────────────────────────────────

    /** Snapshot cache management utilities for the `Classpath.initCached` path.
      *
      * `initCached` writes a binary snapshot file (extension `.krfl`) keyed by a digest of the input roots,
      * so repeat opens of the same classpath restore the in-memory state without re-decoding the underlying
      * `.tasty` / `.class` files. Over time the cache directory accumulates snapshots for roots that are no
      * longer relevant (different commits, different sbt projects, transient builds); this companion exposes
      * the maintenance operations a long-running process needs.
      *
      * **Eviction.** `evictOlderThan(cacheDir, maxAge)` deletes any `*.krfl` file in `cacheDir` whose
      * modification time is older than `maxAge`, returning silently. It does not recurse, and it does not look
      * at file contents; the policy is purely age-based. The operation carries `Sync & Abort[TastyError]`
      * because it touches the disk; expect `SnapshotIoError` when the directory cannot be read.
      *
      * **What lives elsewhere.** The snapshot read / write path is internal; the only public surface for
      * snapshot files is `Classpath.initCached` (writes on miss, reads on hit) and this object (eviction).
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
    // These operations wrap the corresponding Classpath.* methods and accept
    // an explicit (using cp: Classpath) context. Phase 06 will switch them to
    // read from a Local-bound binding; for Phase 03 explicit cp is required.

    /** Look up a class symbol by FQN. Returns Absent when not found. */
    def findClass(fqn: String)(using cp: Classpath): Maybe[Symbol.Class] =
        cp.findClass(fqn)

    /** Look up a class-like symbol (Class, Trait, Object, EnumCase) by FQN. */
    def findClassLike(fqn: String)(using cp: Classpath): Maybe[Symbol.ClassLike] =
        cp.findClassLike(fqn)

    /** Look up an object symbol by FQN (accepts source form or $-suffix binary form). */
    def findObject(fqn: String)(using cp: Classpath): Maybe[Symbol.Object] =
        cp.findObject(fqn)

    /** Look up any symbol by FQN. */
    def findSymbol(fqn: String)(using cp: Classpath): Maybe[Symbol] =
        cp.findSymbol(fqn)

    /** Look up a package symbol by FQN. */
    def findPackage(fqn: String)(using cp: Classpath): Maybe[Symbol.Package] =
        cp.findPackage(fqn)

    /** Look up a JPMS module descriptor by module name. */
    def findModule(name: String)(using cp: Classpath): Maybe[ModuleDescriptor] =
        cp.findModule(name)

    /** Look up a concrete (non-abstract) class by FQN. Returns Absent for abstract classes. */
    def findConcreteClass(fqn: String)(using cp: Classpath): Maybe[Symbol.Class] =
        cp.findConcreteClass(fqn)

    /** Find all Symbol.Class instances whose simple name equals simpleName. */
    def findClassesByName(simpleName: String)(using cp: Classpath): Chunk[Symbol.Class] =
        cp.findClassesByName(simpleName)

    /** Find a method symbol by owner FQN and simple name. Returns the first matching Method. */
    def findMethod(ownerFqn: String, methodName: String)(using cp: Classpath): Maybe[Symbol.Method] =
        findSymbol(ownerFqn).flatMap:
            case cl: Symbol.ClassLike =>
                import Name.asString
                Maybe.fromOption(cl.declarationIds.flatMap: id =>
                    cp.symbol(id) match
                        case m: Symbol.Method if m.name.asString == methodName => Chunk(m)
                        case _                                                 => Chunk.empty
                .headOption)
            case _ => Maybe.Absent

    /** Require a class by FQN; aborts with TastyError.SymbolNotFound when absent. */
    def requireClass(fqn: String)(using frame: Frame, cp: Classpath): Symbol.Class < Abort[TastyError] =
        findClass(fqn) match
            case Maybe.Present(c) => c
            case Maybe.Absent     => Abort.fail(TastyError.NotFound(fqn))

    /** Require a class-like by FQN; aborts with TastyError.NotFound when absent. */
    def requireClassLike(fqn: String)(using frame: Frame, cp: Classpath): Symbol.ClassLike < Abort[TastyError] =
        findClassLike(fqn) match
            case Maybe.Present(c) => c
            case Maybe.Absent     => Abort.fail(TastyError.NotFound(fqn))

    /** Require an object by FQN; aborts with TastyError.NotFound when absent. */
    def requireObject(fqn: String)(using frame: Frame, cp: Classpath): Symbol.Object < Abort[TastyError] =
        findObject(fqn) match
            case Maybe.Present(o) => o
            case Maybe.Absent     => Abort.fail(TastyError.NotFound(fqn))

    /** Require any symbol by FQN; aborts with TastyError.NotFound when absent. */
    def requireSymbol(fqn: String)(using frame: Frame, cp: Classpath): Symbol < Abort[TastyError] =
        findSymbol(fqn) match
            case Maybe.Present(s) => s
            case Maybe.Absent     => Abort.fail(TastyError.NotFound(fqn))

    /** Require a package by FQN; aborts with TastyError.NotFound when absent. */
    def requirePackage(fqn: String)(using frame: Frame, cp: Classpath): Symbol.Package < Abort[TastyError] =
        findPackage(fqn) match
            case Maybe.Present(p) => p
            case Maybe.Absent     => Abort.fail(TastyError.NotFound(fqn))

    /** Require a method by owner FQN and simple name; aborts with TastyError.NotFound when absent. */
    def requireMethod(ownerFqn: String, methodName: String)(using frame: Frame, cp: Classpath): Symbol.Method < Abort[TastyError] =
        findMethod(ownerFqn, methodName) match
            case Maybe.Present(m) => m
            case Maybe.Absent     => Abort.fail(TastyError.NotFound(s"$ownerFqn.$methodName"))

    /** All ClassLike symbols (Class, Trait, Object, EnumCase). */
    def allClassLike(using cp: Classpath): Chunk[Symbol.ClassLike] =
        cp.allClassLike

    /** All Class symbols. */
    def allClasses(using cp: Classpath): Chunk[Symbol.Class] =
        cp.symbols.flatMap:
            case c: Symbol.Class => Chunk(c)
            case _               => Chunk.empty

    /** All Object symbols. */
    def allObjects(using cp: Classpath): Chunk[Symbol.Object] =
        cp.allObjects

    /** All Trait symbols. */
    def allTraits(using cp: Classpath): Chunk[Symbol.Trait] =
        cp.allTraits

    /** All Method symbols. */
    def allMethods(using cp: Classpath): Chunk[Symbol.Method] =
        cp.allMethods

    /** All Val symbols. */
    def allVals(using cp: Classpath): Chunk[Symbol.Val] =
        cp.allVals

    /** All Var symbols. */
    def allVars(using cp: Classpath): Chunk[Symbol.Var] =
        cp.allVars

    /** All Field symbols. */
    def allFields(using cp: Classpath): Chunk[Symbol.Field] =
        cp.allFields

    /** All type declaration symbols (TypeAlias, OpaqueType, AbstractType; TypeParam excluded). */
    def allTypes(using cp: Classpath): Chunk[Symbol] =
        (cp.allTypeAliases.asInstanceOf[Chunk[Symbol]] ++
            cp.allOpaqueTypes.asInstanceOf[Chunk[Symbol]] ++
            cp.allAbstractTypes.asInstanceOf[Chunk[Symbol]])

    /** All Package symbols. */
    def allPackages(using cp: Classpath): Chunk[Symbol.Package] =
        cp.allPackages

    /** Return the lexically enclosing symbol. Absent for root symbols (ownerId == -1). */
    def owner(sym: Symbol)(using cp: Classpath): Maybe[Symbol] =
        sym.owner

    /** Compute the dotted fully-qualified name of sym. */
    def fullName(sym: Symbol)(using frame: Frame, cp: Classpath): Name < Sync =
        cp.fullName(sym)

    /** Human-readable rendering of sym (format-selectable). */
    def show(sym: Symbol, format: ShowFormat = ShowFormat.Code)(using frame: Frame, cp: Classpath): String < Sync =
        sym.show(format)

    /** Human-readable signature of a method symbol. */
    def signature(method: Symbol.Method)(using frame: Frame, cp: Classpath): String < Sync =
        method.signature

    /** Direct parent ClassLike symbols of a ClassLike. */
    def parents(cl: Symbol.ClassLike)(using cp: Classpath): Chunk[Symbol] =
        cl.parents

    /** Members of sym filtered by scope.
      *
      * MemberScope.Declared returns only symbols declared directly on sym.
      * MemberScope.Inherited returns only symbols inherited from parents (not declared on sym).
      * MemberScope.All returns declared and inherited, deduplicated by simple name (most-specific wins).
      */
    def members(sym: Symbol, scope: MemberScope = MemberScope.Declared)(using cp: Classpath): Chunk[Symbol] =
        scope match
            case MemberScope.Declared => sym.declaredMembers
            case MemberScope.Inherited =>
                val directs     = sym.declaredMembers
                val directNames = scala.collection.mutable.HashSet.empty[String]
                directs.foreach(d => discard(directNames.add(d.simpleName)))
                sym.allMembers.filter(s => !directNames.contains(s.simpleName))
            case MemberScope.All => sym.allMembers

    /** Find a member of sym by simple name within the given scope. */
    def findMember(sym: Symbol, name: String, scope: MemberScope = MemberScope.Declared)(using cp: Classpath): Maybe[Symbol] =
        Maybe.fromOption(members(sym, scope).find(_.simpleName == name))

    /** True when sym carries the Scala or Java annotation with the given FQN. */
    def hasAnnotation(sym: Symbol, fqn: String)(using frame: Frame, cp: Classpath): Boolean < Sync =
        Sync.Unsafe.defer:
            def matchScala(a: Annotation): Boolean =
                cp.typeFqnStringUnsafe(a.annotationType) == fqn
            def matchJava(a: JavaAnnotation): Boolean =
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

    /** Find the first Scala or Java annotation matching the given FQN on sym. */
    def findAnnotation(sym: Symbol, fqn: String)(using frame: Frame, cp: Classpath): Maybe[Annotation | JavaAnnotation] < Sync =
        Sync.Unsafe.defer:
            def matchScala(a: Annotation): Boolean =
                cp.typeFqnStringUnsafe(a.annotationType) == fqn
            def matchJava(a: JavaAnnotation): Boolean =
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

    /** All symbols in the classpath carrying the annotation with the given FQN. */
    def symbolsAnnotatedWith(fqn: String)(using frame: Frame, cp: Classpath): Chunk[Symbol] < Sync =
        cp.symbolsAnnotatedWith(fqn)

    /** Decode the body tree of sym. Returns Absent for symbols without a body or decode context. */
    def bodyTree(sym: Symbol)(using frame: Frame, cp: Classpath): Maybe[Tree] < (Sync & Abort[TastyError]) =
        cp.bodyTree(sym)

    /** Resolve the symbol referenced by a Type.Named. Returns Absent for other Type shapes. */
    def typeSymbol(tpe: Type)(using cp: Classpath): Maybe[Symbol] = tpe match
        case Type.Named(id) => Maybe(cp.symbol(id))
        case _              => Maybe.Absent

    /** Structural subtype check. Returns Sub, NotSub, or Unknown. */
    def isSubtypeOf(tpe: Type, other: Type)(using cp: Classpath): SubtypeVerdict =
        kyo.internal.tasty.type_.Subtyping.isSubtype(tpe, other, cp, budget = 64)

    /** Human-readable rendering of a Type. Resolves Named ids to symbol names via the Classpath. */
    def typeShow(tpe: Type)(using cp: Classpath): String =
        import Name.asString
        tpe match
            case Type.Named(id)              => cp.symbol(id).name.asString
            case Type.Applied(base, args)    => s"${typeShow(base)}[${args.map(typeShow).mkString(", ")}]"
            case Type.Array(elem)            => s"${typeShow(elem)}[]"
            case Type.Function(ps, r, isCtx) => s"(${ps.map(typeShow).mkString(", ")}) ${if isCtx then "?=>" else "=>"} ${typeShow(r)}"
            case Type.ContextFunction(ps, r) => s"(${ps.map(typeShow).mkString(", ")}) ?=> ${typeShow(r)}"
            case Type.Tuple(es)              => s"(${es.map(typeShow).mkString(", ")})"
            case Type.Nothing                => "Nothing"
            case Type.Any                    => "Any"
            case Type.Unknown                => "<unknown>"
            case other                       => other.toString
        end match
    end typeShow

    /** Human-readable rendering of a Tree (resolves symbols and types via the Classpath). */
    def treeShow(tree: Tree)(using cp: Classpath): String =
        kyo.internal.tasty.reader.TreeShow.show(tree, cp)

end Tasty
