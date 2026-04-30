package kyo

import kyo.*
import scala.annotation.tailrec

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
    inline def of[A]: Structure.Type = ${ kyo.internal.StructureMacro.deriveImpl[A] }

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
        Structure.TypedValue(Structure.of[A], schema.toStructureValue(value))

    // --- Type hierarchy ---

    /** Structural type descriptor for a Scala type.
      *
      * Each variant captures the shape of one category of Scala type. The tree is produced at compile time by `Structure.of[A]` and can be
      * inspected at runtime to drive generic algorithms.
      */
    sealed abstract class Type derives Schema:
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

    end Type

    // --- Field, Variant, Value data types ---

    /** Descriptor for a single field in a product type.
      *
      * @param name
      *   the field name as declared in the case class
      * @param fieldType
      *   the structural type of the field value
      * @param doc
      *   optional human-readable description
      * @param default
      *   optional default value expressed as a Structure.Value
      * @param optional
      *   true when the field's type is Option or Maybe
      */
    case class Field(
        name: String,
        fieldType: Structure.Type,
        doc: Maybe[String],
        default: Maybe[Structure.Value],
        optional: Boolean
    ) derives Schema

    object Field

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
    enum Value derives CanEqual, Schema:
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
