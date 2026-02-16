package kyo

import Record.*
import kyo.internal.ForSome2
import kyo.internal.Inliner
import kyo.internal.TypeIntersection
import scala.annotation.implicitNotFound
import scala.compiletime.constValue
import scala.compiletime.erasedValue
import scala.compiletime.error
import scala.compiletime.summonInline
import scala.deriving.Mirror
import scala.language.dynamics
import scala.language.implicitConversions

/** A type-safe, immutable record structure that maps field names to values. Records solve the common need to work with flexible key-value
  * structures while maintaining type safety at compile time. Unlike traditional maps or case classes, Records allow dynamic field
  * combinations with static type checking, making them ideal for configuration, data transformation, and API integrations where the shape
  * of data needs to be flexible but still type-safe.
  *
  * =Record vs Row=
  * Record and [[Row]] both provide type-safe named fields, but represent different abstractions:
  *
  * '''Record''' is a '''labeled set''' — fields are unordered, a record with more fields can be used where fewer are expected (structural
  * subtyping), and the same name can appear with different types. Use Record when different consumers need different field subsets or when
  * composing fields from multiple sources.
  *
  * '''Row''' is a '''labeled sequence''' — fields have a fixed order, each name is unique, and the type must match exactly. Use Row when
  * field order matters, when you need positional operations (head/tail/take/drop), or when working with case classes.
  *
  * The two interconvert freely:
  * {{{
  * val record = "name" ~ "Alice" & "age" ~ 30
  * val row  = Row.fromRecord(record)    // Record -> Row (rejects duplicate field names)
  * val back = row.toRecord               // Row -> Record
  * }}}
  *
  * =Creation=
  * Records can be created through direct field construction using the `~` operator and combined with `&`:
  * {{{
  * val record: Record["name" ~ String & "age" ~ Int] = "name" ~ "Alice" & "age" ~ 30
  * }}}
  *
  * For existing data types, Records can be automatically derived from case classes and tuples:
  * {{{
  * case class Person(name: String, age: Int)
  * val record: Record["name" ~ String & "age" ~ Int] = Record.fromProduct(Person("Alice", 30))
  * }}}
  *
  * =Field Access=
  * Fields are accessed with compile-time verification of both existence and type:
  * {{{
  * record.name // Returns "Alice" as String
  * record.age  // Returns 30 as Int
  * record.nonexistent // Won't compile
  * (record.name: Int) // Won't compile
  * }}}
  *
  * =Field Subsetting=
  * Records are covariant in their fields, which means a record with more fields can be used anywhere a record with fewer fields is
  * expected:
  * {{{
  * // A record with name, age, and city
  * val full: Record["name" ~ String & "age" ~ Int & "city" ~ String] =
  *   "name" ~ "Alice" & "age" ~ 30 & "city" ~ "Paris"
  *
  * // Can be used as a record with just name and age
  * val nameAge: Record["name" ~ String & "age" ~ Int] = full
  *
  * // Or just name
  * val nameOnly: Record["name" ~ String] = full
  *
  * // While the above assignments work, the underlying record still contains all fields.
  * // Use compact to create a new record containing only the specified fields:
  * val nameOnlyCompact: Record["name" ~ String] = nameOnly.compact  // Contains only "name"
  * }}}
  *
  * This allows Records to be passed to functions that only need a subset of their fields, making them more flexible while maintaining type
  * safety. The `compact` method creates a new record that internally contains only the fields in its type signature, removing any
  * additional fields that may have been present in the original record.
  *
  * =Duplicate Field Support=
  * Records support duplicate field names with different types, enabling flexible data modeling:
  * {{{
  * val record = "value" ~ "string" & "value" ~ 42
  * (record.value: String) // Returns "string"
  * (record.value: Int)    // Returns 42
  * }}}
  */
final class Record[+Fields] private (val toMap: Map[Field[?, ?], Any]) extends AnyVal with Dynamic:

    /** Retrieves a value from the Record by field name.
      *
      * @param name
      *   The field name to look up
      */
    def selectDynamic[Name <: String & Singleton, Value](name: Name)(using
        @implicitNotFound("""
        Invalid field access: ${Name}

        Record[${Fields}]

        Possible causes:
          1. The field does not exist in this Record
          2. The field exists but has a different type than expected
        """)
        ev: Fields <:< Name ~ Value,
        tag: Tag[Value]
    ): Value =
        toMap(Field(name, tag)).asInstanceOf[Value]

    /** Retrieves a value from the Record by field name for any field name (even it's not a valid identifier).
      *
      * @param name
      *   The field name to look up
      */
    def getField[Name <: String & Singleton, Value](using
        @implicitNotFound("""
        Invalid field access: ${Name}

        Record[${Fields}]

        Possible causes:
          1. The field does not exist in this Record
          2. The field exists but has a different type than expected
        """)
        ev: Fields <:< Name ~ Value,
        tag: Tag[Value],
        name: ValueOf[Name]
    ): Value = toMap(Field(name.value, tag)).asInstanceOf[Value]

    /** Combines this Record with another Record.
      *
      * @param other
      *   The Record to combine with
      * @return
      *   A new Record containing all fields from both Records
      */
    def &[A](other: Record[A]): Record[Fields & A] =
        Record(toMap ++ other.toMap)
end Record

export Record.`~`

object Record:
    /** Creates an empty Record
      */
    val empty: Record[Any] = Record[Any](Map.empty)

    private[kyo] def unsafeFrom[Fields](map: Map[Field[?, ?], Any]): Record[Fields] = Record(map)

    inline def stage[Fields]: StageOps[Fields] = new StageOps[Fields](())

    class StageOps[Fields](dummy: Unit) extends AnyVal:
        /** Applies `StageAs` logic to each field. Called on a record type `n1 ~ v1 & ... & nk ~ vk`, returns a new record of type
          * `n1 ~ F[n1, v1] & ... & nk ~ F[nk, vk]`.
          */
        inline def apply[F[_, _]](as: Record.StageAs[F])(using
            initFields: AsFields[Fields],
            ev: TypeIntersection[Fields],
            targetFields: AsFields[ev.Map[~.Map[F]]]
        ): Record[ev.Map[~.Map[F]]] =
            Record.unsafeFrom(TypeIntersection.inlineAll[Fields](as).view.map {
                case (f, g) => (AsFieldAny.toField(f), g.unwrap)
            }.toMap)
    end StageOps

    final infix class ~[Name <: String, Value] private () extends Serializable

    object `~`:
        given [Name <: String, Value](using CanEqual[Value, Value]): CanEqual[Name ~ Value, Name ~ Value] =
            CanEqual.derived

        type Map[F[_, _]] = [x] =>> x match
            case n ~ v => n ~ F[n, v]

        type MapValue[F[_]] = Map[[n, v] =>> F[v]]

    end `~`

    type FieldsOf[Names <: Tuple, Values <: Tuple] = (Names, Values) match
        case (EmptyTuple, EmptyTuple)           => Any
        case (n *: EmptyTuple, v *: EmptyTuple) => n ~ v
        case (n *: ns, v *: vs)                 => (n ~ v) & FieldsOf[ns, vs]

    type ZipLookup[N <: String, T <: Tuple] = T match
        case (N ~ v) *: _ => v
        case _ *: rest    => ZipLookup[N, rest]

    type ZipFields[T1 <: Tuple, T2 <: Tuple] = T1 match
        case EmptyTuple             => Any
        case (n ~ v1) *: EmptyTuple => n ~ (v1, ZipLookup[n, T2])
        case (n ~ v1) *: rest       => (n ~ (v1, ZipLookup[n, T2])) & ZipFields[rest, T2]

    /** Creates a Record from a product type (case class or tuple).
      */
    def fromProduct[A](value: A)(using ar: AsRecord[A]): Record[ar.Fields] = ar.asRecord(value)

    /** Creates a Record from a Row.
      */
    inline def fromRow[A <: NamedTuple.AnyNamedTuple](row: Row[A]): Record[Row.ToRecordFields[Row.Names[A], Row.Values[A]]] =
        row.toRecord

    /** A field in a Record, containing a name and associated type information.
      *
      * @param name
      *   The name of the field
      * @param tag
      *   Type evidence for the field's value type
      */
    case class Field[Name <: String, Value](name: Name, tag: Tag[Value])

    extension [Fields](self: Record[Fields])

        def size: Int = self.toMap.size

        inline def fields(using ti: TypeIntersection[Fields]): List[String] =
            collectFieldNames[ti.AsTuple]

        inline def values(using ti: TypeIntersection[Fields]): Row.FieldValues[ti.AsTuple] =
            collectValues[ti.AsTuple](self.toMap).asInstanceOf[Row.FieldValues[ti.AsTuple]]

        def update[Name <: String & Singleton, Value](name: Name, value: Value)(using
            @implicitNotFound("""
        Invalid field update: ${Name}

        Record[${Fields}]

        Possible causes:
          1. The field does not exist in this Record
          2. The field exists but has a different type than expected
            """)
            ev: Fields <:< Name ~ Value,
            tag: Tag[Value]
        ): Record[Fields] =
            Record.unsafeFrom(self.toMap.updated(Field(name, tag), value))

        inline def map[F[_]](using
            ti: TypeIntersection[Fields]
        )(
            f: [t] => t => F[t]
        ): Record[ti.Map[~.MapValue[F]]] =
            mapFields([t] => (_: Field[?, t], v: t) => f[t](v))

        inline def mapFields[F[_]](using
            ti: TypeIntersection[Fields]
        )(
            f: [t] => (Field[?, t], t) => F[t]
        ): Record[ti.Map[~.MapValue[F]]] =
            Record.unsafeFrom[ti.Map[~.MapValue[F]]](
                mapFieldsImpl[ti.AsTuple, F](self.toMap, f)
            )

        inline def zip[Fields2](other: Record[Fields2])(using
            ti1: TypeIntersection[Fields],
            ti2: TypeIntersection[Fields2]
        ): Record[ZipFields[ti1.AsTuple, ti2.AsTuple]] =
            Record.unsafeFrom[ZipFields[ti1.AsTuple, ti2.AsTuple]](
                zipImpl[ti1.AsTuple, ti2.AsTuple](self.toMap, other.toMap)
            )

        def compact(using AsFields[Fields]): Record[Fields] =
            Record(self.toMap.view.filterKeys(AsFields[Fields].contains(_)).toMap)
    end extension

    extension (self: String)
        /** Creates a single-field Record with the string as the field name.
          *
          * @param value
          *   The value to associate with the field
          */
        def ~[Value](value: Value)(using tag: Tag[Value]): Record[self.type ~ Value] =
            Record(Map.empty.updated(Field(self, tag), value))
    end extension

    /** Type class for converting types to Records.
      *
      * This type class enables automatic derivation of Records from product types (case classes and tuples). It maintains type information
      * about the fields and their values during conversion.
      *
      * @tparam A
      *   The type to convert to a Record
      */
    trait AsRecord[A]:
        /** The field structure of the converted Record */
        type Fields

        /** Converts a value to a Record */
        def asRecord(value: A): Record[Fields]
    end AsRecord

    object AsRecord:

        type RMirror[A, Names <: Tuple, Values <: Tuple] = Mirror.ProductOf[A] {
            type MirroredElemLabels = Names
            type MirroredElemTypes  = Values
        }

        trait RecordContents[Names <: Tuple, Values <: Tuple]:
            def addTo(product: Product, idx: Int, map: Map[Field[?, ?], Any]): Map[Field[?, ?], Any]

        object RecordContents:
            given empty: RecordContents[EmptyTuple, EmptyTuple] with
                def addTo(product: Product, idx: Int, map: Map[Field[?, ?], Any]): Map[Field[?, ?], Any] = map

            given nonEmpty[NH <: (String & Singleton), NT <: Tuple, VH, VT <: Tuple](
                using
                tag: Tag[VH],
                vo: ValueOf[NH],
                next: RecordContents[NT, VT]
            ): RecordContents[NH *: NT, VH *: VT] with
                def addTo(product: Product, idx: Int, map: Map[Field[?, ?], Any]): Map[Field[?, ?], Any] =
                    next.addTo(product, idx + 1, map.updated(Field[NH, VH](vo.value, tag), product.productElement(idx)))
            end nonEmpty
        end RecordContents

        given [A <: Product, Names <: Tuple, Values <: Tuple](using
            mir: RMirror[A, Names, Values],
            rc: RecordContents[Names, Values]
        ): AsRecord[A] with
            type Fields = Record.FieldsOf[Names, Values]

            def asRecord(value: A): Record[Fields] =
                Record(rc.addTo(value, 0, Map.empty)).asInstanceOf[Record[Fields]]
            end asRecord
        end given
    end AsRecord

    /** Represents a field in a Record type at the type level.
      *
      * AsField is used to convert Record field types into concrete Field instances, maintaining type safety for field names and their
      * associated values.
      *
      * @tparam A
      *   The field type, typically in the form of `"fieldName" ~ ValueType`
      */
    type AsField[Name <: String, Value] = AsField.Type[Name, Value]
    object AsField:
        opaque type Type[Name <: String, Value] = Field[Name, Value]

        inline given [N <: String, V](using tag: Tag[V]): AsField[N, V] =
            Field(constValue[N], tag)

        private[kyo] def fromField[Name <: String, Value](field: Field[Name, Value]): AsField[Name, Value] = field
        private[kyo] def toField[Name <: String, Value](field: AsField[Name, Value]): Field[Name, Value]   = field
    end AsField

    private[kyo] type AsFieldAny[n, v] = AsField[n & String, v]
    private[kyo] object AsFieldAny:
        def toField(as: ForSome2[AsFieldAny]): Field[?, ?] = AsField.toField(as.unwrap)

    /** Type class for working with sets of Record fields.
      *
      * AsFields provides type-safe field set operations and is used primarily for the `compact` operation on Records.
      *
      * @tparam A
      *   The combined type of all fields in the set
      */
    type AsFields[+A] = AsFields.Type[A]

    object AsFields:
        opaque type Type[+A] <: Set[Field[?, ?]] = Set[Field[?, ?]]

        def apply[A](using af: AsFields[A]): Set[Field[?, ?]] = af

        inline given [Fields](using ev: TypeIntersection[Fields]): AsFields[Fields] =
            AsFieldsInternal.summonAsField
    end AsFields

    given [Fields, T](using TypeIntersection.Aux[Fields, T], CanEqual[T, T]): CanEqual[Record[Fields], Record[Fields]] =
        CanEqual.derived

    private object RenderInliner extends Inliner[(String, Render[?])]:
        inline def apply[T]: (String, Render[?]) =
            inline erasedValue[T] match
                case _: (n ~ v) =>
                    constValue[n & String] -> summonInline[Render[v]]
    end RenderInliner

    inline given [Fields: TypeIntersection]: Render[Record[Fields]] =
        val insts = TypeIntersection.inlineAll[Fields](RenderInliner).toMap
        Render.from: (value: Record[Fields]) =>
            value.toMap.iterator.collect {
                case (field, v) if insts.contains(field.name) =>
                    val r = insts(field.name).asInstanceOf[Render[Any]]
                    field.name + " ~ " + r.asText(v)
            }.mkString(" & ")
    end given

    trait StageAs[F[_, _]] extends Inliner[(ForSome2[AsFieldAny], ForSome2[F])]:
        inline def stage[Name <: String, Value](field: Field[Name, Value]): F[Name, Value]

        override inline def apply[T]: (ForSome2[AsFieldAny], ForSome2[F]) =
            inline erasedValue[T] match
                case _: (n ~ v) =>
                    val name    = constValue[n]
                    val prevTag = summonInline[Tag[v]]
                    val nextTag = summonInline[Tag[F[n, v]]]

                    (
                        ForSome2.of[AsFieldAny](AsField.fromField(Field(name, nextTag))),
                        ForSome2(stage[n, v](Field(name, prevTag)))
                    )
    end StageAs

    private inline def collectFieldNames[T <: Tuple]: List[String] =
        inline erasedValue[T] match
            case _: EmptyTuple => Nil
            case _: ((n ~ v) *: rest) =>
                constValue[n & String] :: collectFieldNames[rest]
            case _: (_ *: rest) =>
                collectFieldNames[rest]

    private inline def collectValues[T <: Tuple](
        map: Map[Field[?, ?], Any]
    ): Tuple =
        inline erasedValue[T] match
            case _: EmptyTuple => EmptyTuple
            case _: ((n ~ v) *: rest) =>
                val name = constValue[n & String]
                val tag  = summonInline[Tag[v]]
                map(Field(name, tag)) *: collectValues[rest](map)

    private inline def mapFieldsImpl[T <: Tuple, F[_]](
        map: Map[Field[?, ?], Any],
        f: [t] => (Field[?, t], t) => F[t]
    ): Map[Field[?, ?], Any] =
        inline erasedValue[T] match
            case _: EmptyTuple => Map.empty
            case _: ((n ~ v) *: rest) =>
                val name    = constValue[n & String]
                val prevTag = summonInline[Tag[v]]
                val nextTag = summonInline[Tag[F[v]]]
                val field   = Field(name, prevTag)
                val value   = map(field).asInstanceOf[v]
                val result  = f[v](field, value)
                mapFieldsImpl[rest, F](map, f).updated(Field(name, nextTag), result)

    private inline def zipImpl[T1 <: Tuple, T2 <: Tuple](
        map1: Map[Field[?, ?], Any],
        map2: Map[Field[?, ?], Any]
    ): Map[Field[?, ?], Any] =
        inline erasedValue[T1] match
            case _: EmptyTuple => Map.empty
            case _: ((n ~ v1) *: rest) =>
                val name    = constValue[n & String]
                val tag1    = summonInline[Tag[v1]]
                val tag2    = summonInline[Tag[ZipLookup[n, T2]]]
                val pairTag = summonInline[Tag[(v1, ZipLookup[n, T2])]]
                val value1  = map1(Field(name, tag1))
                val value2  = map2(Field(name, tag2))
                zipImpl[rest, T2](map1, map2).updated(Field(name, pairTag), (value1, value2))
end Record

object AsFieldsInternal:
    private object AsFieldInliner extends Inliner[ForSome2[AsFieldAny]]:
        inline def apply[T]: ForSome2[AsFieldAny] =
            inline erasedValue[T] match
                case _: (n ~ v) =>
                    ForSome2.of[AsFieldAny](summonInline[AsField[n, v]])
                case _ => error("Given type doesn't match to expected field shape: Name ~ Value")
    end AsFieldInliner

    inline def summonAsField[Fields](using ev: TypeIntersection[Fields]): Set[Field[?, ?]] =
        TypeIntersection.inlineAll[Fields](AsFieldInliner).map(Record.AsFieldAny.toField).toSet
    end summonAsField
end AsFieldsInternal
