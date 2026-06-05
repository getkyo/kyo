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

    // ── Binding local and entry points ─────────────────────────────────────

    /** Get the current Classpath from the active binding, falling back to the module-level JVM classpath.
      *
      * Returns the JVM classpath stub when called outside a `withClasspath` scope.
      *
      * Effect row: Sync, because reading the lazy val TastyState.global may trigger initialization.
      */
    def classpath(using Frame): Classpath < Sync =
        TastyState.bindingLocal.use: mbind =>
            Sync.defer(mbind.getOrElse(TastyState.global).cp)

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
      * No filesystem access, no Scope overhead. The bound Binding carries no DecodeContext so
      * `Tasty.bodyTree` returns `Maybe.Absent` for every symbol inside `f`.
      *
      * Effect row: `A < S` -- identical to `f`'s row.
      */
    def withClasspath[A, S](cp: Classpath)(f: => A < S)(using Frame): A < S =
        TastyState.bindingLocal.let(Maybe.Present(Binding(cp, Maybe.Absent)))(f)

    /** Bind a Classpath decoded from in-memory pickles and run `f` in that scope.
      *
      * Decodes the pickles sequentially using an in-memory FileSource. The resulting Binding carries
      * a fresh DecodeContext so `Tasty.bodyTree` can decode body bytes on demand.
      *
      * INV-009 site-2 (alt-init): constructs an anonymous in-memory FileSource from the pickle bytes
      * map; never reads `PlatformFileSource.get` and never touches the real file system. All
      * `Tasty.*` query methods called inside `f` are pure and perform no IO.
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
        modules: Chunk[ModuleDescriptor],
        rootSymbolId: SymbolId
    ):

        private def symbolsOfKind[A <: Symbol](k: SymbolKind): Chunk[A] =
            if symbols.isEmpty then Chunk.empty
            else
                val b = Chunk.newBuilder[A]
                symbols.foreach:
                    case s if s.kind == k => b += s.asInstanceOf[A]
                    case _                => ()
                b.result()

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
          * Pure O(1) lookup in the immutable `indices.byFqn` map. Returns `Absent` if the FQN is not registered. For typed lookups that
          * narrow to a specific subtype, use `findClass`, `findTrait`, `findObject`, `findClassLike`, or `findPackage`.
          *
          * Null safety: a `null` `fqn` argument resolves to `Maybe.Absent` (Scala Map.get(null) returns None). No NPE is raised.
          * Defensive null checks in call sites are unnecessary.
          */
        def findSymbol(fqn: String): Maybe[Symbol] =
            indices.byFqn.get(fqn) match
                case Maybe.Present(id) => Maybe(symbol(id))
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
                case Maybe.Present(id) =>
                    symbol(id) match
                        case c: Symbol.Class => Maybe(c)
                        case _               => Maybe.Absent
                case Maybe.Absent => Maybe.Absent

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
          * Performance: the result is computed once and cached. Repeated calls are O(1). The
          * cache is NOT a constructor parameter and is NOT preserved by `cp.copy(...)`; copying recomputes
          * it lazily on the next access of the new Classpath.
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
            indices.byFqn.get(fqn) match
                case Maybe.Present(id) =>
                    symbol(id) match
                        case t: Symbol.Trait => Maybe(t)
                        case _               => Maybe.Absent
                case Maybe.Absent => Maybe.Absent

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
            indices.byFqn.get(fqn) match
                case Maybe.Present(id) =>
                    symbol(id) match
                        case o: Symbol.Object => Maybe(o)
                        case _                =>
                            // Source-form FQN is taken by a non-Object (e.g. the case class itself).
                            // Fall back to the binary $-suffixed key where the companion Object lives.
                            if fqn.endsWith("$") then Maybe.Absent
                            else
                                indices.byFqn.get(fqn + "$") match
                                    case Maybe.Present(id2) =>
                                        symbol(id2) match
                                            case o: Symbol.Object => Maybe(o)
                                            case _                => Maybe.Absent
                                    case Maybe.Absent => Maybe.Absent
                case Maybe.Absent =>
                    // No entry at the source-form key; try the binary $-suffixed key directly.
                    if fqn.endsWith("$") then Maybe.Absent
                    else
                        indices.byFqn.get(fqn + "$") match
                            case Maybe.Present(id2) =>
                                symbol(id2) match
                                    case o: Symbol.Object => Maybe(o)
                                    case _                => Maybe.Absent
                            case Maybe.Absent => Maybe.Absent

        /** Look up a class-like symbol (Class, Trait, or Object) by fully-qualified dotted name.
          *
          * Returns `Absent` when the FQN resolves to a Package or other non-ClassLike symbol.
          */
        def findClassLike(fqn: String): Maybe[Symbol.ClassLike] =
            indices.byFqn.get(fqn) match
                case Maybe.Present(id) =>
                    symbol(id) match
                        case c: Symbol.ClassLike => Maybe(c)
                        case _                   => Maybe.Absent
                case Maybe.Absent => Maybe.Absent

        /** Look up a package symbol by fully-qualified dotted name.
          *
          * Pure O(1) lookup in the immutable packageIndex. Returns `Absent` if the package is not in this classpath.
          */
        def findPackage(fqn: String): Maybe[Symbol.Package] =
            indices.packageIndex.get(fqn) match
                case Maybe.Present(id) =>
                    symbol(id) match
                        case p: Symbol.Package => Maybe(p)
                        case _                 => Maybe.Absent
                case Maybe.Absent => Maybe.Absent

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
                    case c: Symbol.Class => Chunk(c)
                    case _               => Chunk.empty
        end findClassesByName

        /** All package symbols in this classpath.
          *
          * Pure accessor over the immutable `packageIds` Chunk. Each id is resolved and narrowed to `Symbol.Package`; ids that resolve to
          * non-Package symbols are excluded.
          */
        def packages: Chunk[Symbol.Package] =
            indices.packageIds.flatMap: id =>
                symbol(id) match
                    case p: Symbol.Package => Chunk(p)
                    case _                 => Chunk.empty

        /** All top-level class-like symbols (not packages) in this classpath.
          *
          * Pure accessor over the immutable `topLevelClassIds` Chunk. Each id is resolved and narrowed to `Symbol.ClassLike`; ids that
          * resolve to non-ClassLike symbols are excluded.
          */
        def topLevelClasses: Chunk[Symbol.ClassLike] =
            indices.topLevelClassIds.flatMap: id =>
                symbol(id) match
                    case c: Symbol.ClassLike => Chunk(c)
                    case _                   => Chunk.empty

        /** Look up a JPMS module descriptor by module name (e.g., "java.base").
          *
          * Returns `Present(descriptor)` if a `module-info.class` with the given module name was found in the classpath roots. Returns
          * `Absent` if no matching module was found.
          *
          * O(1) lookup via the modulesIndex in Indices.
          */
        def findModule(name: String): Maybe[ModuleDescriptor] =
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

        /** All Unresolved symbols in the classpath. O(n) scan over `symbols`. */
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
                        indices.unresolvedFqnByNegId.getOrElse(id, "")
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
            indices.companionIndex.get(sym.id) match
                case Maybe.Present(cid) => Maybe(symbol(cid))
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
                        val ownerSym = symbol(ownerIdCur)
                        if ownerSym.id == cur.id || ownerSym.name.asString.isEmpty then nextAcc
                        else go(ownerSym, depth + 1, nextAcc)
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
                        indices.subclassIndex.getOrElse(curId, Chunk.empty).flatMap: childId =>
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
            modulesIndex: Dict[String, ModuleDescriptor],
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
                modulesIndex = Dict.empty[String, ModuleDescriptor],
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

        /** Schema for Classpath.Indices. Placed here (after symbolIdMapSchema is defined) so Map[SymbolId, V] fields resolve. */
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
            // F-A3-001..004 fix: prepend every `.class` path under `jrt:/modules/<m>/...` to the user's
            // roots so JDK class symbols decode alongside user TASTy. The shape of `roots` is preserved
            // (a Seq[String] of file-system paths); the new entries use the `jrt:/` URI scheme that
            // JvmFileSource already handles. PlatformModuleOps.listJdkClassFiles is JVM-only; JS/Native
            // return Chunk.empty so this method degrades to the module-descriptor-only path.
            // Sync.Unsafe.defer supplies AllowUnsafe to just the listJdkClassFiles call, so the rest of
            // the for-comprehension cannot pick up the proof implicitly.
            Sync.Unsafe.defer(kyo.internal.tasty.query.PlatformModuleOps.listJdkClassFiles(moduleFilter)).map: jdkClassFiles =>
                for
                    cp         <- initImpl(jdkClassFiles.toSeq ++ roots, ErrorMode.SoftFail)
                    jdkModules <- PlatformModuleOps.readJdkModuleDescriptors
                yield
                    val newModulesIndex = cp.indices.modulesIndex ++ Dict.from(jdkModules)
                    val newModulesBuf   = Chunk.newBuilder[ModuleDescriptor]
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
            moduleIndex: Dict[String, ModuleDescriptor],
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
                val buf = Chunk.newBuilder[ModuleDescriptor]
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
      * Mirrors the schemaSymbol placement pattern (Decision 18 in Phase 03) that resolved the forward-reference
      * issue when derives Schema is placed inline on the class.
      */
    given schemaClasspath: Schema[Classpath] = Schema.derived

    /** CanEqual[Classpath, Classpath] derived after the companion closes; same placement rationale as schemaClasspath. */
    given canEqualClasspath: CanEqual[Classpath, Classpath] = CanEqual.canEqualAny

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

    /** Look up a class symbol by FQN. Returns Absent when not found. */
    def findClass(fqn: String)(using Frame): Maybe[Symbol.Class] < Sync =
        classpath.map(_.findClass(fqn))

    /** Look up a class-like symbol (Class, Trait, Object, EnumCase) by FQN. */
    def findClassLike(fqn: String)(using Frame): Maybe[Symbol.ClassLike] < Sync =
        classpath.map(_.findClassLike(fqn))

    /** Look up an object symbol by FQN (accepts source form or $-suffix binary form). */
    def findObject(fqn: String)(using Frame): Maybe[Symbol.Object] < Sync =
        classpath.map(_.findObject(fqn))

    /** Look up any symbol by FQN. */
    def findSymbol(fqn: String)(using Frame): Maybe[Symbol] < Sync =
        classpath.map(_.findSymbol(fqn))

    /** Look up a package symbol by FQN. */
    def findPackage(fqn: String)(using Frame): Maybe[Symbol.Package] < Sync =
        classpath.map(_.findPackage(fqn))

    /** Look up a JPMS module descriptor by module name. */
    def findModule(name: String)(using Frame): Maybe[ModuleDescriptor] < Sync =
        classpath.map(_.findModule(name))

    /** Look up a concrete (non-abstract) class by FQN. Returns Absent for abstract classes. */
    def findConcreteClass(fqn: String)(using Frame): Maybe[Symbol.Class] < Sync =
        classpath.map(_.findConcreteClass(fqn))

    /** Find all Symbol.Class instances whose simple name equals simpleName. */
    def findClassesByName(simpleName: String)(using Frame): Chunk[Symbol.Class] < Sync =
        classpath.map(_.findClassesByName(simpleName))

    /** Find a method symbol by owner FQN and simple name. Returns the first matching Method. */
    def findMethod(ownerFqn: String, methodName: String)(using Frame): Maybe[Symbol.Method] < Sync =
        classpath.map: cp =>
            cp.findSymbol(ownerFqn).flatMap:
                case cl: Symbol.ClassLike =>
                    import Name.asString
                    Maybe.fromOption(cl.declarationIds.flatMap: id =>
                        cp.symbol(id) match
                            case m: Symbol.Method if m.name.asString == methodName => Chunk(m)
                            case _                                                 => Chunk.empty
                    .headOption)
                case _ => Maybe.Absent

    /** Require a class by FQN; aborts with TastyError.NotFound when absent. */
    def requireClass(fqn: String)(using Frame): Symbol.Class < (Sync & Abort[TastyError]) =
        findClass(fqn).map:
            case Maybe.Present(c) => c
            case Maybe.Absent     => Abort.fail(TastyError.NotFound(fqn))

    /** Require a class-like by FQN; aborts with TastyError.NotFound when absent. */
    def requireClassLike(fqn: String)(using Frame): Symbol.ClassLike < (Sync & Abort[TastyError]) =
        findClassLike(fqn).map:
            case Maybe.Present(c) => c
            case Maybe.Absent     => Abort.fail(TastyError.NotFound(fqn))

    /** Require an object by FQN; aborts with TastyError.NotFound when absent. */
    def requireObject(fqn: String)(using Frame): Symbol.Object < (Sync & Abort[TastyError]) =
        findObject(fqn).map:
            case Maybe.Present(o) => o
            case Maybe.Absent     => Abort.fail(TastyError.NotFound(fqn))

    /** Require any symbol by FQN; aborts with TastyError.NotFound when absent. */
    def requireSymbol(fqn: String)(using Frame): Symbol < (Sync & Abort[TastyError]) =
        findSymbol(fqn).map:
            case Maybe.Present(s) => s
            case Maybe.Absent     => Abort.fail(TastyError.NotFound(fqn))

    /** Require a package by FQN; aborts with TastyError.NotFound when absent. */
    def requirePackage(fqn: String)(using Frame): Symbol.Package < (Sync & Abort[TastyError]) =
        findPackage(fqn).map:
            case Maybe.Present(p) => p
            case Maybe.Absent     => Abort.fail(TastyError.NotFound(fqn))

    /** Require a method by owner FQN and simple name; aborts with TastyError.NotFound when absent. */
    def requireMethod(ownerFqn: String, methodName: String)(using Frame): Symbol.Method < (Sync & Abort[TastyError]) =
        findMethod(ownerFqn, methodName).map:
            case Maybe.Present(m) => m
            case Maybe.Absent     => Abort.fail(TastyError.NotFound(s"$ownerFqn.$methodName"))

    /** All ClassLike symbols (Class, Trait, Object, EnumCase). */
    def allClassLike(using Frame): Chunk[Symbol.ClassLike] < Sync =
        classpath.map(_.allClassLike)

    /** All Class symbols. */
    def allClasses(using Frame): Chunk[Symbol.Class] < Sync =
        classpath.map(cp =>
            cp.symbols.flatMap:
                case c: Symbol.Class => Chunk(c)
                case _               => Chunk.empty
        )

    /** All Object symbols. */
    def allObjects(using Frame): Chunk[Symbol.Object] < Sync =
        classpath.map(_.allObjects)

    /** All Trait symbols. */
    def allTraits(using Frame): Chunk[Symbol.Trait] < Sync =
        classpath.map(_.allTraits)

    /** All Method symbols. */
    def allMethods(using Frame): Chunk[Symbol.Method] < Sync =
        classpath.map(_.allMethods)

    /** All Val symbols. */
    def allVals(using Frame): Chunk[Symbol.Val] < Sync =
        classpath.map(_.allVals)

    /** All Var symbols. */
    def allVars(using Frame): Chunk[Symbol.Var] < Sync =
        classpath.map(_.allVars)

    /** All Field symbols. */
    def allFields(using Frame): Chunk[Symbol.Field] < Sync =
        classpath.map(_.allFields)

    /** All type declaration symbols (TypeAlias, OpaqueType, AbstractType; TypeParam excluded). */
    def allTypes(using Frame): Chunk[Symbol] < Sync =
        classpath.map(cp =>
            cp.allTypeAliases.asInstanceOf[Chunk[Symbol]] ++
                cp.allOpaqueTypes.asInstanceOf[Chunk[Symbol]] ++
                cp.allAbstractTypes.asInstanceOf[Chunk[Symbol]]
        )

    /** All Package symbols. */
    def allPackages(using Frame): Chunk[Symbol.Package] < Sync =
        classpath.map(_.allPackages)

    /** Return the lexically enclosing symbol. Absent for root symbols (ownerId == -1). */
    def owner(sym: Symbol)(using Frame): Maybe[Symbol] < Sync =
        classpath.map: cp =>
            if sym.ownerId.value == -1 then Maybe.Absent
            else Maybe(cp.symbol(sym.ownerId))

    /** Compute the dotted fully-qualified name of sym. */
    def fullName(sym: Symbol)(using Frame): Name < Sync =
        classpath.flatMap(cp => cp.fullName(sym))

    /** Human-readable rendering of sym (format-selectable). */
    def show(sym: Symbol, format: ShowFormat = ShowFormat.Code)(using Frame): String < Sync =
        classpath.flatMap: cp =>
            format match
                case ShowFormat.FullyQualified =>
                    import Name.asString
                    cp.fullName(sym).map(_.asString)
                case ShowFormat.Simple => sym.simpleName
                case ShowFormat.Code   => kyo.internal.tasty.symbol.SymbolSignature.compute(sym, cp)

    /** Human-readable signature of a method symbol. */
    def signature(method: Symbol.Method)(using Frame): String < Sync =
        classpath.flatMap(cp => kyo.internal.tasty.symbol.SymbolSignature.compute(method, cp))

    /** Compute the owners chain of sym (sym itself, its owner, owner's owner, ...). */
    def ownersChain(sym: Symbol)(using Frame): Chunk[Symbol] < Sync =
        classpath.map: cp =>
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
            go(sym, 0)
            out.result()

    /** Compute the JVM binary name for sym. */
    def binaryName(sym: Symbol)(using Frame): String < Sync =
        classpath.map(cp => kyo.internal.tasty.symbol.BinaryName.compute(sym, cp))

    /** Return the companion object or companion class of sym, if any. */
    def companion(sym: Symbol)(using Frame): Maybe[Symbol] < Sync =
        classpath.map(_.companion(sym))

    /** Return the type parameters of sym (ClassLike, Method, TypeAlias, OpaqueType). */
    def typeParams(sym: Symbol)(using Frame): Chunk[Symbol] < Sync =
        classpath.map: cp =>
            (sym match
                case c: Symbol.ClassLike   => c.typeParamIds
                case m: Symbol.Method      => m.typeParamIds
                case ta: Symbol.TypeAlias  => ta.typeParamIds
                case ot: Symbol.OpaqueType => ot.typeParamIds
                case _                     => Chunk.empty
            ).map(cp.symbol)

    /** Return the declared members of sym (ClassLike: declarationIds; Package: memberIds; else empty). */
    def declarations(sym: Symbol)(using Frame): Chunk[Symbol] < Sync =
        classpath.map: cp =>
            (sym match
                case c: Symbol.ClassLike => c.declarationIds
                case p: Symbol.Package   => p.memberIds
                case _                   => Chunk.empty
            ).map(cp.symbol)

    /** Return the permitted subclasses of a sealed Class or Trait. Returns empty for non-sealed. */
    def permittedSubclasses(sym: Symbol)(using Frame): Chunk[Symbol] < Sync =
        classpath.map: cp =>
            (sym match
                case c: Symbol.Class => c.permittedSubclassIds
                case t: Symbol.Trait => t.permittedSubclassIds
                case _               => Maybe.Absent
            ).map(_.map(cp.symbol)).getOrElse(Chunk.empty)

    /** Direct parent ClassLike symbols of a ClassLike. */
    def parents(cl: Symbol.ClassLike)(using Frame): Chunk[Symbol] < Sync =
        classpath.map: cp =>
            cl.parentTypes.collect { case Type.Named(pid) => cp.symbol(pid) }

    /** Members of sym filtered by scope.
      *
      * MemberScope.Declared returns only symbols declared directly on sym.
      * MemberScope.Inherited returns only symbols inherited from parents (not declared on sym).
      * MemberScope.All returns declared and inherited, deduplicated by simple name (most-specific wins).
      */
    def members(sym: Symbol, scope: MemberScope = MemberScope.Declared)(using Frame): Chunk[Symbol] < Sync =
        classpath.map: cp =>
            scope match
                case MemberScope.Declared =>
                    (sym match
                        case c: Symbol.ClassLike => c.declarationIds
                        case p: Symbol.Package   => p.memberIds
                        case _                   => Chunk.empty
                    ).map(cp.symbol)
                case MemberScope.Inherited =>
                    val declIds = sym match
                        case c: Symbol.ClassLike => c.declarationIds
                        case p: Symbol.Package   => p.memberIds
                        case _                   => Chunk.empty
                    val directNames = scala.collection.mutable.HashSet.empty[String]
                    declIds.foreach(id => discard(directNames.add(cp.symbol(id).simpleName)))
                    allMembersOf(sym, cp).filter(s => !directNames.contains(s.simpleName))
                case MemberScope.All => allMembersOf(sym, cp)

    /** Find a member of sym by simple name within the given scope. */
    def findMember(sym: Symbol, name: String, scope: MemberScope = MemberScope.Declared)(using Frame): Maybe[Symbol] < Sync =
        members(sym, scope).map(ms => Maybe.fromOption(ms.find(_.simpleName == name)))

    /** Find a declared member of sym by simple name. */
    def findDeclaredMember(sym: Symbol, name: String)(using Frame): Maybe[Symbol] < Sync =
        findMember(sym, name, MemberScope.Declared)

    /** True when sym carries the Scala or Java annotation with the given FQN. */
    def hasAnnotation(sym: Symbol, fqn: String)(using Frame): Boolean < Sync =
        classpath.flatMap: cp =>
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
    def findAnnotation(sym: Symbol, fqn: String)(using Frame): Maybe[Annotation | JavaAnnotation] < Sync =
        classpath.flatMap: cp =>
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
        val maybeBody: Maybe[SymbolBody] = sym match
            case c: Symbol.Class  => c.body
            case t: Symbol.Trait  => t.body
            case o: Symbol.Object => o.body
            case m: Symbol.Method => m.body
            case v: Symbol.Val    => v.body
            case w: Symbol.Var    => w.body
            case _                => Maybe.Absent
        maybeBody match
            case Maybe.Absent => Maybe.Absent
            case Maybe.Present(blob) =>
                TastyState.bindingLocal.use: mbind =>
                    val maybeCtx = mbind.flatMap(_.decodeCtx)
                    if maybeCtx.isEmpty then Maybe.Absent
                    else
                        val ctx = maybeCtx.get
                        val cp  = mbind.get.cp
                        Sync.Unsafe.defer:
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
                                            // F-W2-2: mmap arena closed before bodyTree ran; documented contract is ClasspathClosed.
                                            Result.Failure(TastyError.ClasspathClosed(s"bodyTree(sym.id=${sym.id.value})"))
                                ctx.bodyMemo.put(sym.id, result)
                                result match
                                    case Result.Success(t) => Maybe(t)
                                    case Result.Failure(e) => Abort.fail(e)
                                    case Result.Panic(t)   => throw t
                                end match
                            end if
                    end if
        end match
    end bodyTree

    /** Resolve the symbol referenced by a Type.Named. Returns Absent for other Type shapes. */
    def typeSymbol(tpe: Type)(using Frame): Maybe[Symbol] < Sync =
        classpath.map: cp =>
            tpe match
                case Type.Named(id) => Maybe(cp.symbol(id))
                case _              => Maybe.Absent

    /** Structural subtype check. Returns Sub, NotSub, or Unknown. */
    def isSubtypeOf(tpe: Type, other: Type)(using Frame): SubtypeVerdict < Sync =
        classpath.map(cp => kyo.internal.tasty.type_.Subtyping.isSubtype(tpe, other, cp, budget = 64))

    /** Human-readable rendering of a Type. Resolves Named ids to symbol names via the Classpath. */
    def typeShow(tpe: Type)(using Frame): String < Sync =
        classpath.map: cp =>
            import Name.asString
            def renderType(t: Type): String = t match
                case Type.Named(id)           => cp.symbol(id).name.asString
                case Type.Applied(base, args) => s"${renderType(base)}[${args.map(renderType).mkString(", ")}]"
                case Type.Array(elem)         => s"${renderType(elem)}[]"
                case Type.Function(ps, r, isCtx) =>
                    s"(${ps.map(renderType).mkString(", ")}) ${if isCtx then "?=>" else "=>"} ${renderType(r)}"
                case Type.ContextFunction(ps, r) => s"(${ps.map(renderType).mkString(", ")}) ?=> ${renderType(r)}"
                case Type.Tuple(es)              => s"(${es.map(renderType).mkString(", ")})"
                case Type.Nothing                => "Nothing"
                case Type.Any                    => "Any"
                case Type.Unknown                => "<unknown>"
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
                        val d  = cp.symbol(id)
                        val nm = d.simpleName
                        if seen.add(nm) then out += d
                    cl.parentTypes.collect { case Type.Named(pid) => cp.symbol(pid) }.foreach:
                        case pcl: Symbol.ClassLike => visit(pcl)
                        case _                     => ()
                end visit
                visit(c)
                out.result()
            case _ => Chunk.empty

end Tasty
