package kyo

import kyo.*
import scala.annotation.tailrec
import scala.annotation.targetName

/** Runtime structural description of Scala types, used for introspection, generic programming, and bridging to dynamic formats.
  *
  * Structure provides two complementary trees: the Type tree describes the shape of a Scala type (its fields, variants, element types,
  * etc.), and the Value tree holds the actual data in a format-neutral representation. Together they let you traverse, compare, and
  * transform typed Scala values without knowledge of a specific serialization format.
  *
  * Common use cases include generic diffing (Changeset), structural navigation (Focus), cross-format serialization, and forwarding typed
  * values to dynamic systems such as scripting runtimes or document databases.
  *
  * @see
  *   [[kyo.Schema]] for the primary type-driven entry point
  * @see
  *   [[kyo.Changeset]] for serializable diffs built on Structure
  */
object Structure:

    /** Derives a Structure.Type for A at compile time.
      *
      * The returned Type tree describes the full shape of A, including fields for case classes, variants for sealed traits/enums, element
      * types for collections, and primitive tags for scalar values. Evaluated by an inline macro at each call site.
      *
      * @tparam A
      *   the type to describe
      * @return
      *   the compile-time structural type descriptor for A
      */
    inline def of[A](using s: Schema[A]): Structure.Type = s.structure

    /** Converts a typed value to its untyped Structure.Value representation.
      *
      * Uses the Schema[A] instance to serialize the value into the universal Value tree. The result can be inspected, modified, or decoded
      * back to a typed value via `decode`.
      *
      * @param value
      *   the typed value to encode
      * @return
      *   the untyped value tree representation
      */
    def encode[A](value: A)(using schema: Schema[A], frame: Frame): Structure.Value =
        schema.toStructureValue(value)

    /** Reconstructs a typed value from an untyped Structure.Value.
      *
      * Decodes the Value tree back to type A using the Schema[A] instance. Returns a failure if the dynamic value does not conform to the
      * expected shape.
      *
      * @param value
      *   the untyped value tree to decode
      * @return
      *   the decoded value, or a DecodeException if the value does not match the schema
      */
    def decode[A](value: Structure.Value)(using schema: Schema[A], frame: Frame): Result[DecodeException, A] =
        schema.fromStructureValue(value)

    /** Creates a TypedValue pairing the compile-time Structure.Type with the runtime Value for a given value.
      *
      * The Type half is resolved at compile time via `Structure.of[A]`; the Value half is produced at runtime via the Schema[A]. Useful
      * when you need to pass both type description and data to a generic receiver.
      *
      * @param value
      *   the typed value to wrap
      * @return
      *   a TypedValue carrying the compile-time type descriptor and the runtime value tree
      */
    inline def typedValue[A](value: A)(using schema: Schema[A], frame: Frame): Structure.TypedValue =
        Structure.TypedValue(schema.structure, schema.toStructureValue(value))

    // --- Type hierarchy ---

    /** Structural type descriptor for a Scala type.
      *
      * Each variant captures the shape of one category of Scala type. The tree is produced at compile time by `Structure.of[A]` and can be
      * inspected at runtime to drive generic algorithms.
      */
    sealed abstract class Type:
        def name: String

    /** The specific kind of a primitive (scalar) type.
      *
      * Enumerates the scalar types supported directly by Structure. Consumers (JSON, Protobuf, etc.) pattern-match exhaustively on this
      * enum to produce format-specific representations, so there is no silent fallback path.
      */
    enum PrimitiveKind derives CanEqual, Schema:
        case Int, Long, Short, Byte, Char
        case Float, Double
        case BigInt, BigDecimal
        case String, Boolean, Unit
    end PrimitiveKind

    object Type:
        /** A case class or tuple: named fields with individual types.
          *
          * @param name
          *   simple class name
          * @param tag
          *   runtime tag for the product type
          * @param typeParams
          *   type arguments if the class is generic
          * @param fields
          *   the fields in declaration order
          */
        case class Product(
            name: String,
            tag: Tag[Any],
            typeParams: Chunk[Structure.Type],
            fields: Chunk[Structure.Field]
        ) extends Type

        /** A sealed trait or enum: named variants each with their own type.
          *
          * @param name
          *   simple trait name
          * @param tag
          *   runtime tag for the sum type
          * @param typeParams
          *   type arguments if the trait is generic
          * @param variants
          *   the case sub-types
          * @param enumValues
          *   simple enum case names when the sum has no-arg cases
          */
        case class Sum(
            name: String,
            tag: Tag[Any],
            typeParams: Chunk[Structure.Type],
            variants: Chunk[Structure.Variant],
            enumValues: Chunk[String]
        ) extends Type

        /** A scalar type with no sub-structure (Int, String, Boolean, etc.).
          *
          * @param kind
          *   the specific primitive kind (Int, Long, String, ...)
          * @param tag
          *   runtime tag for the primitive type
          */
        case class Primitive(
            kind: PrimitiveKind,
            tag: Tag[Any]
        ) extends Type:
            def name: String = kind.toString
        end Primitive

        /** A homogeneous sequence type (List, Vector, Chunk, etc.).
          *
          * @param name
          *   simple collection type name
          * @param tag
          *   runtime tag for the collection type
          * @param elementType
          *   the element type
          */
        case class Collection(
            name: String,
            tag: Tag[Any],
            elementType: Structure.Type
        ) extends Type

        /** A key-value mapping type (Map).
          *
          * @param name
          *   simple map type name
          * @param tag
          *   runtime tag for the mapping type
          * @param keyType
          *   the key element type
          * @param valueType
          *   the value element type
          */
        case class Mapping(
            name: String,
            tag: Tag[Any],
            keyType: Structure.Type,
            valueType: Structure.Type
        ) extends Type

        /** An optional value type (Option or Maybe).
          *
          * @param name
          *   simple optional type name
          * @param tag
          *   runtime tag for the optional type
          * @param innerType
          *   the wrapped element type
          */
        case class Optional(
            name: String,
            tag: Tag[Any],
            innerType: Structure.Type
        ) extends Type

        /** Identity / open-shape projection: the carrying Schema accepts any wire shape and produces
          * any wire shape. No fixed structural projection exists at compile time. Used by
          * Schema[Structure.Value], Schema[Json.JsonSchema], and any future shape-dynamic Schema.
          *
          * Compatibility with another Open is determined by tag equality, not structural recursion.
          * An Open type is never compatible with any non-Open type.
          *
          * @param tag
          *   runtime tag for the open-shape type
          */
        case class Open(tag: Tag[Any]) extends Type:
            def name: String = "Open"
        end Open

        // --- Type structural checks ---

        /** Structural compatibility check -- true when both types have the same shape. */
        def compatible(a: Type, b: Type): Boolean = (a, b) match
            case (Product(_, _, _, fa), Product(_, _, _, fb)) =>
                fa.size == fb.size && fa.zip(fb).forall { (f1, f2) =>
                    f1.name == f2.name && compatible(f1.fieldType, f2.fieldType)
                }
            case (Sum(_, _, _, va, _), Sum(_, _, _, vb, _)) =>
                va.size == vb.size && va.zip(vb).forall { (v1, v2) =>
                    v1.name == v2.name && compatible(v1.variantType, v2.variantType)
                }
            case (Primitive(_, ta), Primitive(_, tb))           => ta =:= tb
            case (Collection(_, _, ea), Collection(_, _, eb))   => compatible(ea, eb)
            case (Optional(_, _, ia), Optional(_, _, ib))       => compatible(ia, ib)
            case (Mapping(_, _, ka, va), Mapping(_, _, kb, vb)) => compatible(ka, kb) && compatible(va, vb)
            case (Open(ta), Open(tb))                           => ta =:= tb
            case _                                              => false

        /** Walk all nodes depth-first. */
        def fold[R](tpe: Type)(init: R)(f: (R, Type) => R): R =
            val acc = f(init, tpe)
            tpe match
                case Product(_, _, _, fields)  => fields.foldLeft(acc)((r, field) => fold(field.fieldType)(r)(f))
                case Sum(_, _, _, variants, _) => variants.foldLeft(acc)((r, v) => fold(v.variantType)(r)(f))
                case Collection(_, _, elem)    => fold(elem)(acc)(f)
                case Optional(_, _, inner)     => fold(inner)(acc)(f)
                case Mapping(_, _, k, v)       => fold(v)(fold(k)(acc)(f))(f)
                case _: Primitive              => acc
                case _: Open                   => acc
            end match
        end fold

        /** Collect all field paths (for Product types). */
        def fieldPaths(tpe: Type): Chunk[Chunk[String]] = tpe match
            case Product(_, _, _, fields) =>
                fields.flatMap { f =>
                    val nested = fieldPaths(f.fieldType)
                    if nested.isEmpty then Chunk(Chunk(f.name))
                    else nested.map(f.name +: _)
                }
            case _ => Chunk.empty

        /** Schema instance for `Structure.Type`. Authors update this declaration when a new variant
          * is added to the sum, ensuring the wire shape changes only with explicit code review.
          *
          * `Schema.derived` emits the sum-Schema for `Structure.Type` via the FocusMacro path. The
          * explicit declaration ensures the wire shape changes only when authors update this given.
          */
        given Schema[Structure.Type] = Schema.derived

    end Type

    // --- Field, Variant, Value data types ---

    /** Descriptor for a single field in a product type.
      *
      * The structural type of the field is held by-name so a recursive or indirectly-recursive
      * type graph constructs without forcing the inner Schema's lazy structure mid-init.
      *
      * @param name
      *   the field name as declared in the case class
      * @param doc
      *   optional human-readable description
      * @param default
      *   optional default value expressed as a Structure.Value
      * @param optional
      *   true when the field's type is Option or Maybe
      */
    case class Field(
        name: String,
        private val _fieldType: () => Structure.Type,
        doc: Maybe[String],
        default: Maybe[Structure.Value],
        optional: Boolean
    ):
        def fieldType: Structure.Type = _fieldType()

        // Equality forces `_fieldType` so two Fields built from identical structural data
        // compare equal even though the by-name `apply` wraps each call's argument in a
        // fresh `() => fieldType` lambda. The auto-generated case-class equals would
        // otherwise compare function references and report `false` for structurally
        // identical Fields. Structure.Type has no CanEqual; reach for `.equals` directly.
        override def equals(other: Any): Boolean = other match
            case that: Field =>
                name == that.name &&
                doc == that.doc &&
                default == that.default &&
                optional == that.optional &&
                fieldType.equals(that.fieldType)
            case _ => false

        override def hashCode(): Int =
            var h = name.hashCode
            h = h * 31 + fieldType.hashCode
            h = h * 31 + doc.hashCode
            h = h * 31 + default.hashCode
            h = h * 31 + optional.hashCode
            h
        end hashCode
    end Field

    object Field:

        /** Constructs a Structure.Field with the structural type captured by-name.
          *
          * The `fieldType` parameter is by-name so a strict caller writes
          * `Structure.Field("x", someStructure, ...)` and the macro emission writes
          * `Structure.Field("x", summonInline[Schema[t]].structure, ...)`; both defer
          * the structure's evaluation until the first `field.fieldType` read. The
          * thunk is required for self-referential and indirectly-recursive
          * `Structure.Type.Product` graphs to construct without forcing the inner
          * Schema's `structure` lazy val mid-init.
          */
        @targetName("applyByName")
        def apply(
            name: String,
            fieldType: => Structure.Type,
            doc: Maybe[String] = Maybe.empty,
            default: Maybe[Structure.Value] = Maybe.empty,
            optional: Boolean = false
        ): Field =
            new Field(name, () => fieldType, doc, default, optional)

        /** Hand-rolled Schema for Structure.Field.
          *
          * The 5 wire keys are `name, fieldType, doc, default, optional`. The hand-roll is a
          * chosen byte-for-byte match of those 5 keys against what the pre-campaign
          * `derives Schema` emitted for the pre-campaign 5-positional case class; the
          * roundtrip leaf 2 of StructureTest below verifies it. This is not a mechanical
          * consequence of any compiler property; it is a chosen invariant the hand-roll
          * preserves.
          *
          * The auto-derived Schema would walk the case-class fields and emit the storage
          * member `_fieldType: Function0[Structure.Type]` as a wire field, which is wrong on
          * two counts: the wire field name would be the storage name (not the public
          * `fieldType`), and the wire field type would be `Function0[Structure.Type]` for
          * which no Schema can be summoned. The hand-roll mirrors the public 5-key face and
          * reads via the public `def fieldType` accessor; constructs via the by-name
          * companion `apply`.
          *
          * The Reader loop uses the canonical Codec.Reader contract at `Codec.scala:63-130`:
          * `hasNextField()` is the loop predicate (returns Boolean), `fieldParse()` advances
          * the cursor past the field name (returns Unit), and `lastFieldName()` returns the
          * just-parsed field name.
          */
        given structureFieldSchema: Schema[Field] =
            new Schema[Field](Seq.empty):
                import scala.annotation.publicInBinary
                // Frame.internal: required here because maybeSchema carries `using Frame`
                // but this given is a kyo-internal implementation with no user callsite frame.
                private given frame: Frame = Frame.internal
                @publicInBinary private[kyo] def serializeWrite(value: Field, writer: Codec.Writer): Unit =
                    writer.objectStart("Field", 5)
                    writer.field("name", 0); summon[Schema[String]].serializeWrite(value.name, writer)
                    writer.field("fieldType", 1); summon[Schema[Structure.Type]].serializeWrite(value.fieldType, writer)
                    writer.field("doc", 2); summon[Schema[Maybe[String]]].serializeWrite(value.doc, writer)
                    writer.field("default", 3); summon[Schema[Maybe[Structure.Value]]].serializeWrite(value.default, writer)
                    writer.field("optional", 4); summon[Schema[Boolean]].serializeWrite(value.optional, writer)
                    writer.objectEnd()
                end serializeWrite
                @publicInBinary private[kyo] def serializeRead(reader: Codec.Reader): Field =
                    discard(reader.objectStart())
                    var name: String                    = ""
                    var fieldType: Structure.Type       = Structure.Type.Open(Tag[Any])
                    var doc: Maybe[String]              = Maybe.empty
                    var default: Maybe[Structure.Value] = Maybe.empty
                    var optional: Boolean               = false
                    while reader.hasNextField() do
                        reader.fieldParse()
                        reader.lastFieldName() match
                            case "name"      => name = summon[Schema[String]].serializeRead(reader)
                            case "fieldType" => fieldType = summon[Schema[Structure.Type]].serializeRead(reader)
                            case "doc"       => doc = summon[Schema[Maybe[String]]].serializeRead(reader)
                            case "default"   => default = summon[Schema[Maybe[Structure.Value]]].serializeRead(reader)
                            case "optional"  => optional = summon[Schema[Boolean]].serializeRead(reader)
                            case _           => reader.skip()
                        end match
                    end while
                    reader.objectEnd()
                    Field(name, fieldType, doc, default, optional)
                end serializeRead
                @publicInBinary private[kyo] def getter(value: Field): Maybe[Any] = Maybe(value)
                @publicInBinary private[kyo] def setter(value: Field, next: Any): Field =
                    next match
                        case f: Field => f
                        case _        => value
                private lazy val _structure: Structure.Type =
                    Structure.Type.Product(
                        "Field",
                        Tag[Field].asInstanceOf[Tag[Any]],
                        Chunk.empty,
                        Chunk(
                            Structure.Field("name", summon[Schema[String]].structure, Maybe.empty, Maybe.empty, optional = false),
                            Structure.Field(
                                "fieldType",
                                summon[Schema[Structure.Type]].structure,
                                Maybe.empty,
                                Maybe.empty,
                                optional = false
                            ),
                            Structure.Field("doc", summon[Schema[Maybe[String]]].structure, Maybe.empty, Maybe.empty, optional = true),
                            Structure.Field(
                                "default",
                                summon[Schema[Maybe[Structure.Value]]].structure,
                                Maybe.empty,
                                Maybe.empty,
                                optional = true
                            ),
                            Structure.Field("optional", summon[Schema[Boolean]].structure, Maybe.empty, Maybe.empty, optional = false)
                        )
                    )
                override def structure: Structure.Type = _structure
            end new
        end structureFieldSchema
    end Field

    /** Descriptor for a single variant (case) in a sum type.
      *
      * @param name
      *   the variant name as declared in the sealed trait or enum
      * @param variantType
      *   the structural type of the variant's payload
      */
    case class Variant(
        name: String,
        variantType: Structure.Type
    ) derives Schema

    object Variant

    // --- Value tier: untyped value tree ---

    /** Untyped, format-neutral value tree produced by encoding a typed Scala value.
      *
      * Each variant corresponds to a structural category: Record for case classes, VariantCase for sealed trait instances, Sequence for
      * collections, MapEntries for maps, and scalar variants (Str, Bool, Integer, Decimal, BigNum, Null) for primitives.
      *
      * Values are produced by `Structure.encode` or `Schema.toStructureValue` and consumed by `Structure.decode` or navigation via `Path`.
      */
    enum Value derives CanEqual:
        /** Named fields of a product/case class, ordered by declaration. */
        case Record(fields: Chunk[(String, Value)])

        /** Active variant of a sum type, carrying the variant name and its payload. */
        case VariantCase(name: String, value: Value)

        /** Elements of a collection in original order. */
        case Sequence(elements: Chunk[Value])

        /** Key-value pairs of a map in original iteration order. */
        case MapEntries(entries: Chunk[(Value, Value)])

        /** A string scalar. */
        case Str(value: String)

        /** A boolean scalar. */
        case Bool(value: Boolean)

        /** An integer scalar stored as Long. */
        case Integer(value: Long)

        /** A floating-point scalar stored as Double. */
        case Decimal(value: Double)

        /** An arbitrary-precision numeric scalar. */
        case BigNum(value: BigDecimal)

        /** Represents null or an absent optional. */
        case Null
    end Value

    object Value:

        /** Identity [[Schema]] for [[Value]]: writes shape-aware (Record -> object, Sequence -> array, scalars unwrapped) and reads via
          * [[kyo.Codec.IntrospectingReader.readStructure]] which materializes the next wire value directly into the Value tree.
          *
          * The auto-derived enum-Schema would emit a tagged-union wrapper like `{"Record":{...}}` and refuse a plain JSON object like
          * `{"path":"."}` (raising [[UnknownVariantException]] because "path" is not a known discriminator). That tagged form is an internal
          * kyo-schema detail; Value is the universal "any-shape" type, so its Schema is the identity: writes preserve the shape Scala already
          * has, reads accept whatever shape the wire carries. The override of `fromStructureValue` keeps top-level `Structure.decode[Value]`
          * a zero-cost passthrough; `serializeRead` covers the case where Value is a field of an outer case class being decoded by the
          * macro-generated reader.
          *
          * Reading requires a [[kyo.Codec.IntrospectingReader]] (JSON or Structure source). Binary codecs without per-value type
          * tags cannot decode a `Value` and the type-mismatch is reported with a precise diagnostic instead of bubbling up as an
          * `UnknownVariantException` from the auto-derived shape.
          */
        given valueSchema: Schema[Value] =
            new Schema[Value](Seq.empty):
                import scala.annotation.publicInBinary
                @publicInBinary private[kyo] def serializeWrite(value: Value, writer: Codec.Writer): Unit =
                    Schema.writeStructureValue(writer, value)
                @publicInBinary private[kyo] def serializeRead(reader: Codec.Reader): Value =
                    reader match
                        case ir: Codec.IntrospectingReader => ir.readStructure()
                        case other =>
                            throw SchemaNotSerializableException(
                                s"Schema[Structure.Value] requires a self-describing reader (JSON or Structure source); got ${other.getClass.getSimpleName}"
                            )(using reader.frame)
                @publicInBinary private[kyo] def getter(value: Value): Maybe[Any] = Maybe(value)
                @publicInBinary private[kyo] def setter(value: Value, next: Any): Value =
                    next match
                        case sv: Value => sv
                        case _         => value
                private lazy val _structure: Structure.Type =
                    Structure.Type.Open(Tag[Structure.Value].asInstanceOf[Tag[Any]])
                override def structure: Structure.Type = _structure
                override private[kyo] def fromStructureValue(sv: Value)(using Frame): Result[DecodeException, Value] =
                    Result.Success(sv)

        /** Creates a typed Value from a primitive Scala value.
          *
          * Dispatches via `Tag[A]` to select the correct Value variant based on the static type. Runtime pattern matching is unreliable on
          * Scala.js where `Double` values with whole-number content are represented as `Int` at runtime.
          */
        def primitive[A](value: A)(using tag: Tag[A]): Value =
            if tag =:= Tag[String] then Str(value.asInstanceOf[String])
            else if tag =:= Tag[Boolean] then Bool(value.asInstanceOf[Boolean])
            else if tag =:= Tag[Int] then Integer(value.asInstanceOf[Int].toLong)
            else if tag =:= Tag[Long] then Integer(value.asInstanceOf[Long])
            else if tag =:= Tag[Short] then Integer(value.asInstanceOf[Short].toLong)
            else if tag =:= Tag[Byte] then Integer(value.asInstanceOf[Byte].toLong)
            else if tag =:= Tag[Double] then Decimal(value.asInstanceOf[Double])
            else if tag =:= Tag[Float] then Decimal(value.asInstanceOf[Float].toDouble)
            else if tag =:= Tag[BigDecimal] then BigNum(value.asInstanceOf[BigDecimal])
            else if tag =:= Tag[BigInt] then BigNum(BigDecimal(value.asInstanceOf[BigInt]))
            else if tag =:= Tag[Char] then Str(value.asInstanceOf[Char].toString)
            else Str(value.toString)

    end Value

    // --- TypedValue tier ---

    /** Pairs a compile-time Structure.Type descriptor with a runtime Structure.Value.
      *
      * Useful when passing a value and its type description together to a generic receiver, for example a dynamic scripting runtime or a
      * document store. Produced by `Structure.typedValue[A](value)`.
      *
      * @param tpe
      *   compile-time structural type descriptor
      * @param value
      *   runtime untyped value tree
      */
    case class TypedValue(tpe: Structure.Type, value: Structure.Value) derives Schema

    object TypedValue

    // --- PathSegment ---

    /** A single segment in a navigation path through a nested data structure.
      *
      * PathSegments are composed into ordered sequences that pinpoint specific locations within deeply nested records, sum types, and
      * sequences. They are used by codec error reporting to describe exactly where a decode failure occurred and by the Schema navigation
      * API to express structural queries.
      *
      *   - [[PathSegment.Field]]: navigate into a named field of a record or object
      *   - [[PathSegment.Variant]]: navigate into a named variant of a sealed trait / enum
      *   - [[PathSegment.Index]]: navigate into a specific zero-based index of a sequence
      *   - [[PathSegment.Each]]: wildcard that matches every element of a sequence
      */
    enum PathSegment derives CanEqual, Schema:

        /** Navigate into a named field of a record/object. */
        case Field(name: String)

        /** Navigate into a named variant of a sum type. */
        case Variant(name: String)

        /** Navigate into a specific index of a sequence. */
        case Index(i: Int)

        /** Navigate into every element of a sequence. */
        case Each
    end PathSegment

    object PathSegment

    // --- Path navigation on Value ---

    /** Navigates over Structure.Value trees using an ordered sequence of PathSegments.
      *
      * A Path is built by chaining segment constructors (`Path.field("x") / PathSegment.Index(0)`) or by using the convenience factories on
      * the companion object. `get` extracts matching values; `set` replaces them. For wildcard traversal use `PathSegment.Each`.
      *
      * @see
      *   [[PathSegment]] for the available navigation primitives
      */
    final class Path(val segments: Chunk[PathSegment]):

        def /(segment: PathSegment): Path = new Path(segments :+ segment)

        def get(v: Value)(using Frame): Result[NavigationException, Chunk[Value]] =
            getAll(Chunk(v), segments.toList)

        def set(v: Value, newValue: Value)(using Frame): Result[NavigationException, Value] =
            setAt(v, segments.toList, newValue)

        override def toString: String = segments.map {
            case PathSegment.Field(n)   => s".$n"
            case PathSegment.Variant(n) => s"<$n>"
            case PathSegment.Index(i)   => s"[$i]"
            case PathSegment.Each       => "[*]"
        }.mkString

        private def getAll(current: Chunk[Value], remaining: List[PathSegment])(using Frame): Result[NavigationException, Chunk[Value]] =
            remaining match
                case Nil => Result.succeed(current)
                case seg :: rest =>
                    val stepped = current.flatMap(v => stepGet(v, seg))
                    if stepped.isEmpty && current.nonEmpty then
                        Result.panic(PathNotFoundException(Seq.empty, seg.toString))
                    else
                        getAll(stepped, rest)
                    end if
        end getAll

        private def stepGet(v: Value, seg: PathSegment): Chunk[Value] =
            seg match
                case PathSegment.Field(name) =>
                    v match
                        case Value.Record(fields) =>
                            fields.collect { case (n, fv) if n == name => fv }
                        case _ => Chunk.empty
                case PathSegment.Variant(name) =>
                    v match
                        case Value.VariantCase(n, inner) if n == name => Chunk(inner)
                        case _                                        => Chunk.empty
                case PathSegment.Index(i) =>
                    v match
                        case Value.Sequence(elements) =>
                            if i >= 0 && i < elements.size then Chunk(elements(i))
                            else Chunk.empty
                        case _ => Chunk.empty
                case PathSegment.Each =>
                    v match
                        case Value.Sequence(elements) => elements
                        case _                        => Chunk.empty
        end stepGet

        private def setAt(v: Value, remaining: List[PathSegment], newValue: Value)(using Frame): Result[NavigationException, Value] =
            remaining match
                case Nil => Result.succeed(newValue)
                case seg :: rest =>
                    seg match
                        case PathSegment.Field(name) =>
                            v match
                                case Value.Record(fields) =>
                                    val (updated, found) = fields.foldLeft((Chunk.empty[(String, Value)], false)) {
                                        case ((acc, found), (n, fv)) =>
                                            if n == name then
                                                setAt(fv, rest, newValue) match
                                                    case Result.Success(nv) => (acc :+ (n, nv), true)
                                                    case _                  => (acc :+ (n, fv), true)
                                            else (acc :+ (n, fv), found)
                                    }
                                    if found then Result.succeed(Value.Record(updated))
                                    else Result.panic(PathNotFoundException(Seq.empty, name))
                                case _ =>
                                    Result.panic(TypeMismatchException(Seq.empty, "Record", v.toString))
                        case PathSegment.Variant(name) =>
                            v match
                                case Value.VariantCase(n, inner) if n == name =>
                                    setAt(inner, rest, newValue).map(nv => Value.VariantCase(n, nv))
                                case _ =>
                                    Result.panic(TypeMismatchException(Seq.empty, s"Variant '$name'", v.toString))
                        case PathSegment.Index(i) =>
                            v match
                                case Value.Sequence(elements) =>
                                    if i >= 0 && i < elements.size then
                                        setAt(elements(i), rest, newValue).map { nv =>
                                            val arr = elements.toArray[Value]
                                            arr(i) = nv
                                            Value.Sequence(Chunk.from(arr))
                                        }
                                    else
                                        Result.panic(SchemaIndexOutOfBoundsException(Seq.empty, i, elements.size))
                                case _ =>
                                    Result.panic(TypeMismatchException(Seq.empty, "Sequence", v.toString))
                        case PathSegment.Each =>
                            v match
                                case Value.Sequence(elements) =>
                                    val updated = elements.map(el =>
                                        setAt(el, rest, newValue) match
                                            case Result.Success(nv) => nv
                                            case _                  => el
                                    )
                                    Result.succeed(Value.Sequence(updated))
                                case _ =>
                                    Result.panic(TypeMismatchException(Seq.empty, "Sequence", v.toString))
            end match
        end setAt

    end Path

    object Path:
        val root: Path                  = new Path(Chunk.empty)
        def field(name: String): Path   = root / PathSegment.Field(name)
        def variant(name: String): Path = root / PathSegment.Variant(name)
        def index(i: Int): Path         = root / PathSegment.Index(i)
        val each: Path                  = root / PathSegment.Each
    end Path

end Structure
