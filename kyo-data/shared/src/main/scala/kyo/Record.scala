package kyo

import Record.*
import scala.annotation.implicitNotFound
import scala.compiletime.constValue
import scala.compiletime.summonInline
import scala.deriving.Mirror
import scala.language.dynamics
import scala.language.implicitConversions
import scala.util.NotGiven

/** A type-safe, immutable record structure that maps field names to values. Records solve the common need to work with flexible key-value
  * structures while maintaining type safety at compile time. Unlike traditional maps or case classes, Records allow dynamic field
  * combinations with static type checking, making them ideal for configuration, data transformation, and API integrations where the shape
  * of data needs to be flexible but still type-safe.
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
  *
  * =Known Limitations=
  *
  *   - Nested records cannot be created directly
  *   - Tag derivation for Records is not currently supported
  *   - Records with more than 22 fields have limited type-level support, though runtime support exists for larger structures
  *   - CanEqual and Render instances are not provided for Records
  */
class Record[+Fields](val toMap: Map[Field[?, ?], Any]) extends AnyVal with Dynamic:

    /** Retrieves a value from the Record by field name.
      *
      * @param name
      *   The field name to look up
      */
    def selectDynamic[Name <: String & Singleton, Unspecified](name: Name)(using
        @implicitNotFound(""" 
        Invalid field access: ${Name}
        
        Record[${Fields}]

        Possible causes:
          1. The field does not exist in this Record
          2. The field exists but has a different type than expected
        """)
        ev: Fields <:< Name ~ Unspecified,
        tag: Tag[Unspecified]
    ): Unspecified =
        toMap(Field(name, tag)).asInstanceOf[Unspecified]

    /** Combines this Record with another Record.
      *
      * @param other
      *   The Record to combine with
      * @return
      *   A new Record containing all fields from both Records
      */
    def &[A](other: Record[A]): Record[Fields & A] =
        Record(toMap ++ other.toMap)

    /** Returns the set of fields in this Record.
      *
      * @return
      *   A Set of Field instances
      */
    def fields: Set[Field[?, ?]] = toMap.keySet

    /** Returns the number of fields in this Record.
      */
    def size: Int = toMap.size

end Record

export Record.`~`

object Record:
    given [Fields]: Flat[Record[Fields]] = Flat.unsafe.bypass

    inline given [Fields]: Tag[Record[Fields]] =
        scala.compiletime.error(
            "Cannot derive Tag for Record type. This commonly occurs when trying to nest Records, " +
                "which is not currently supported by the Tag implementation."
        )

    infix opaque type ~[Name <: String, Value] = Map[Field[?, ?], Any]

    /** Creates a Record from a product type (case class or tuple).
      */
    def fromProduct[A](value: A)(using ar: AsRecord[A]): Record[ar.Fields] = ar.asRecord(value)

    /** A field in a Record, containing a name and associated type information.
      *
      * @param name
      *   The name of the field
      * @param tag
      *   Type evidence for the field's value type
      */
    case class Field[Name <: String, Value](name: Name, tag: Tag[Value])

    extension [Fields](self: Record[Fields])
        /** Creates a new Record containing only the fields specified in the type parameter Fields.
          *
          * @return
          *   A new Record with only the specified fields
          */
        def compact(using AsFields[Fields]): Record[Fields] =
            Record(self.toMap.view.filterKeys(AsFields[Fields].contains(_)).toMap)

    extension (self: String)
        /** Creates a single-field Record with the string as the field name.
          *
          * @param value
          *   The value to associate with the field
          */
        def ~[Value](value: Value)(using tag: Tag[Value]): Record[self.type ~ Value] =
            Record(Map(Field(self, tag) -> value))

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

        type FieldsOf[Names <: Tuple, Values <: Tuple] = Names match
            case nHead *: EmptyTuple => Values match
                    case vHead *: _ => (nHead ~ vHead)
            case nHead *: nTail => Values match
                    case vHead *: vTail => (nHead ~ vHead) & FieldsOf[nTail, vTail]
            case _ => Any

        type RMirror[A, Names <: Tuple, Values <: Tuple] = Mirror.ProductOf[A] {
            type MirroredElemLabels = Names
            type MirroredElemTypes  = Values
        }

        trait RecordContents[Names <: Tuple, Values <: Tuple]:
            def values(product: Tuple): List[(Field[?, ?], Any)]

        object RecordContents:
            given empty: RecordContents[EmptyTuple, EmptyTuple] with
                def values(product: Tuple): List[(Field[?, ?], Any)] = Nil

            given nonEmpty[NH <: (String & Singleton), NT <: Tuple, VH, VT <: Tuple](
                using
                tag: Tag[VH],
                vo: ValueOf[NH],
                next: RecordContents[NT, VT]
            ): RecordContents[NH *: NT, VH *: VT] with
                def values(product: Tuple): List[(Field[?, ?], Any)] =
                    (Field[NH, VH](vo.value, tag), product.head) +: next.values(product.tail)
            end nonEmpty
        end RecordContents

        given [A <: Product, Names <: Tuple, Values <: Tuple](using
            mir: RMirror[A, Names, Values],
            rc: RecordContents[Names, Values]
        ): AsRecord[A] with
            type Fields = FieldsOf[Names, Values]

            def asRecord(value: A): Record[Fields] =
                val record_contents = rc.values(Tuple.fromProduct(value))
                val map             = Map(record_contents*)
                Record(map).asInstanceOf[Record[Fields]]
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
    opaque type AsField[+A] = Field[?, ?]

    object AsField:
        inline given [N <: String, V](using tag: Tag[V]): AsField[N ~ V] =
            Field(constValue[N], tag)

        def apply[A](using af: AsField[A]): Field[?, ?] = af
    end AsField

    /** Type class for working with sets of Record fields.
      *
      * AsFields provides type-safe field set operations and is used primarily for the `compact` operation on Records.
      *
      * @tparam A
      *   The combined type of all fields in the set
      */
    opaque type AsFields[+A] <: Set[Field[?, ?]] = Set[Field[?, ?]]

    trait AsFields22:
        given [A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, A21, A22](using
            a1: AsField[A1],
            a2: AsField[A2],
            a3: AsField[A3],
            a4: AsField[A4],
            a5: AsField[A5],
            a6: AsField[A6],
            a7: AsField[A7],
            a8: AsField[A8],
            a9: AsField[A9],
            a10: AsField[A10],
            a11: AsField[A11],
            a12: AsField[A12],
            a13: AsField[A13],
            a14: AsField[A14],
            a15: AsField[A15],
            a16: AsField[A16],
            a17: AsField[A17],
            a18: AsField[A18],
            a19: AsField[A19],
            a20: AsField[A20],
            a21: AsField[A21],
            a22: AsField[A22]
        ): AsFields[
            A1 & A2 & A3 & A4 & A5 & A6 & A7 & A8 & A9 & A10 & A11 & A12 & A13 & A14 & A15 & A16 & A17 & A18 & A19 & A20 & A21 & A22
        ] =
            Set(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18, a19, a20, a21, a22)
    end AsFields22

    trait AsFields21 extends AsFields22:
        given [A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, A21](using
            a1: AsField[A1],
            a2: AsField[A2],
            a3: AsField[A3],
            a4: AsField[A4],
            a5: AsField[A5],
            a6: AsField[A6],
            a7: AsField[A7],
            a8: AsField[A8],
            a9: AsField[A9],
            a10: AsField[A10],
            a11: AsField[A11],
            a12: AsField[A12],
            a13: AsField[A13],
            a14: AsField[A14],
            a15: AsField[A15],
            a16: AsField[A16],
            a17: AsField[A17],
            a18: AsField[A18],
            a19: AsField[A19],
            a20: AsField[A20],
            a21: AsField[A21]
        ): AsFields[A1 & A2 & A3 & A4 & A5 & A6 & A7 & A8 & A9 & A10 & A11 & A12 & A13 & A14 & A15 & A16 & A17 & A18 & A19 & A20 & A21] =
            Set(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18, a19, a20, a21)
    end AsFields21

    trait AsFields20 extends AsFields21:
        given [A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20](using
            a1: AsField[A1],
            a2: AsField[A2],
            a3: AsField[A3],
            a4: AsField[A4],
            a5: AsField[A5],
            a6: AsField[A6],
            a7: AsField[A7],
            a8: AsField[A8],
            a9: AsField[A9],
            a10: AsField[A10],
            a11: AsField[A11],
            a12: AsField[A12],
            a13: AsField[A13],
            a14: AsField[A14],
            a15: AsField[A15],
            a16: AsField[A16],
            a17: AsField[A17],
            a18: AsField[A18],
            a19: AsField[A19],
            a20: AsField[A20]
        ): AsFields[A1 & A2 & A3 & A4 & A5 & A6 & A7 & A8 & A9 & A10 & A11 & A12 & A13 & A14 & A15 & A16 & A17 & A18 & A19 & A20] =
            Set(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18, a19, a20)
    end AsFields20

    trait AsFields19 extends AsFields20:
        given [A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19](using
            a1: AsField[A1],
            a2: AsField[A2],
            a3: AsField[A3],
            a4: AsField[A4],
            a5: AsField[A5],
            a6: AsField[A6],
            a7: AsField[A7],
            a8: AsField[A8],
            a9: AsField[A9],
            a10: AsField[A10],
            a11: AsField[A11],
            a12: AsField[A12],
            a13: AsField[A13],
            a14: AsField[A14],
            a15: AsField[A15],
            a16: AsField[A16],
            a17: AsField[A17],
            a18: AsField[A18],
            a19: AsField[A19]
        ): AsFields[A1 & A2 & A3 & A4 & A5 & A6 & A7 & A8 & A9 & A10 & A11 & A12 & A13 & A14 & A15 & A16 & A17 & A18 & A19] =
            Set(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18, a19)
    end AsFields19

    trait AsFields18 extends AsFields19:
        given [A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18](using
            a1: AsField[A1],
            a2: AsField[A2],
            a3: AsField[A3],
            a4: AsField[A4],
            a5: AsField[A5],
            a6: AsField[A6],
            a7: AsField[A7],
            a8: AsField[A8],
            a9: AsField[A9],
            a10: AsField[A10],
            a11: AsField[A11],
            a12: AsField[A12],
            a13: AsField[A13],
            a14: AsField[A14],
            a15: AsField[A15],
            a16: AsField[A16],
            a17: AsField[A17],
            a18: AsField[A18]
        ): AsFields[A1 & A2 & A3 & A4 & A5 & A6 & A7 & A8 & A9 & A10 & A11 & A12 & A13 & A14 & A15 & A16 & A17 & A18] =
            Set(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18)
    end AsFields18

    trait AsFields17 extends AsFields18:
        given [A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17](using
            a1: AsField[A1],
            a2: AsField[A2],
            a3: AsField[A3],
            a4: AsField[A4],
            a5: AsField[A5],
            a6: AsField[A6],
            a7: AsField[A7],
            a8: AsField[A8],
            a9: AsField[A9],
            a10: AsField[A10],
            a11: AsField[A11],
            a12: AsField[A12],
            a13: AsField[A13],
            a14: AsField[A14],
            a15: AsField[A15],
            a16: AsField[A16],
            a17: AsField[A17]
        ): AsFields[A1 & A2 & A3 & A4 & A5 & A6 & A7 & A8 & A9 & A10 & A11 & A12 & A13 & A14 & A15 & A16 & A17] =
            Set(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17)
    end AsFields17

    trait AsFields16 extends AsFields17:
        given [A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16](using
            a1: AsField[A1],
            a2: AsField[A2],
            a3: AsField[A3],
            a4: AsField[A4],
            a5: AsField[A5],
            a6: AsField[A6],
            a7: AsField[A7],
            a8: AsField[A8],
            a9: AsField[A9],
            a10: AsField[A10],
            a11: AsField[A11],
            a12: AsField[A12],
            a13: AsField[A13],
            a14: AsField[A14],
            a15: AsField[A15],
            a16: AsField[A16]
        ): AsFields[A1 & A2 & A3 & A4 & A5 & A6 & A7 & A8 & A9 & A10 & A11 & A12 & A13 & A14 & A15 & A16] =
            Set(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16)
    end AsFields16

    trait AsFields15 extends AsFields16:
        given [A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15](using
            a1: AsField[A1],
            a2: AsField[A2],
            a3: AsField[A3],
            a4: AsField[A4],
            a5: AsField[A5],
            a6: AsField[A6],
            a7: AsField[A7],
            a8: AsField[A8],
            a9: AsField[A9],
            a10: AsField[A10],
            a11: AsField[A11],
            a12: AsField[A12],
            a13: AsField[A13],
            a14: AsField[A14],
            a15: AsField[A15]
        ): AsFields[A1 & A2 & A3 & A4 & A5 & A6 & A7 & A8 & A9 & A10 & A11 & A12 & A13 & A14 & A15] =
            Set(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15)
    end AsFields15

    trait AsFields14 extends AsFields15:
        given [A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14](using
            a1: AsField[A1],
            a2: AsField[A2],
            a3: AsField[A3],
            a4: AsField[A4],
            a5: AsField[A5],
            a6: AsField[A6],
            a7: AsField[A7],
            a8: AsField[A8],
            a9: AsField[A9],
            a10: AsField[A10],
            a11: AsField[A11],
            a12: AsField[A12],
            a13: AsField[A13],
            a14: AsField[A14]
        ): AsFields[A1 & A2 & A3 & A4 & A5 & A6 & A7 & A8 & A9 & A10 & A11 & A12 & A13 & A14] =
            Set(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14)
    end AsFields14

    trait AsFields13 extends AsFields14:
        given [A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13](using
            a1: AsField[A1],
            a2: AsField[A2],
            a3: AsField[A3],
            a4: AsField[A4],
            a5: AsField[A5],
            a6: AsField[A6],
            a7: AsField[A7],
            a8: AsField[A8],
            a9: AsField[A9],
            a10: AsField[A10],
            a11: AsField[A11],
            a12: AsField[A12],
            a13: AsField[A13]
        ): AsFields[A1 & A2 & A3 & A4 & A5 & A6 & A7 & A8 & A9 & A10 & A11 & A12 & A13] =
            Set(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13)
    end AsFields13

    trait AsFields12 extends AsFields13:
        given [A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12](using
            a1: AsField[A1],
            a2: AsField[A2],
            a3: AsField[A3],
            a4: AsField[A4],
            a5: AsField[A5],
            a6: AsField[A6],
            a7: AsField[A7],
            a8: AsField[A8],
            a9: AsField[A9],
            a10: AsField[A10],
            a11: AsField[A11],
            a12: AsField[A12]
        ): AsFields[A1 & A2 & A3 & A4 & A5 & A6 & A7 & A8 & A9 & A10 & A11 & A12] =
            Set(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12)
    end AsFields12

    trait AsFields11 extends AsFields12:
        given [A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11](using
            a1: AsField[A1],
            a2: AsField[A2],
            a3: AsField[A3],
            a4: AsField[A4],
            a5: AsField[A5],
            a6: AsField[A6],
            a7: AsField[A7],
            a8: AsField[A8],
            a9: AsField[A9],
            a10: AsField[A10],
            a11: AsField[A11]
        ): AsFields[A1 & A2 & A3 & A4 & A5 & A6 & A7 & A8 & A9 & A10 & A11] =
            Set(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11)
    end AsFields11

    trait AsFields10 extends AsFields11:
        given [A1, A2, A3, A4, A5, A6, A7, A8, A9, A10](using
            a1: AsField[A1],
            a2: AsField[A2],
            a3: AsField[A3],
            a4: AsField[A4],
            a5: AsField[A5],
            a6: AsField[A6],
            a7: AsField[A7],
            a8: AsField[A8],
            a9: AsField[A9],
            a10: AsField[A10]
        ): AsFields[A1 & A2 & A3 & A4 & A5 & A6 & A7 & A8 & A9 & A10] =
            Set(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10)
    end AsFields10

    trait AsFields9 extends AsFields10:
        given [A1, A2, A3, A4, A5, A6, A7, A8, A9](using
            a1: AsField[A1],
            a2: AsField[A2],
            a3: AsField[A3],
            a4: AsField[A4],
            a5: AsField[A5],
            a6: AsField[A6],
            a7: AsField[A7],
            a8: AsField[A8],
            a9: AsField[A9]
        ): AsFields[A1 & A2 & A3 & A4 & A5 & A6 & A7 & A8 & A9] =
            Set(a1, a2, a3, a4, a5, a6, a7, a8, a9)
    end AsFields9

    trait AsFields8 extends AsFields9:
        given [A1, A2, A3, A4, A5, A6, A7, A8](using
            a1: AsField[A1],
            a2: AsField[A2],
            a3: AsField[A3],
            a4: AsField[A4],
            a5: AsField[A5],
            a6: AsField[A6],
            a7: AsField[A7],
            a8: AsField[A8]
        ): AsFields[A1 & A2 & A3 & A4 & A5 & A6 & A7 & A8] =
            Set(a1, a2, a3, a4, a5, a6, a7, a8)
    end AsFields8

    trait AsFields7 extends AsFields8:
        given [A1, A2, A3, A4, A5, A6, A7](using
            a1: AsField[A1],
            a2: AsField[A2],
            a3: AsField[A3],
            a4: AsField[A4],
            a5: AsField[A5],
            a6: AsField[A6],
            a7: AsField[A7]
        ): AsFields[A1 & A2 & A3 & A4 & A5 & A6 & A7] =
            Set(a1, a2, a3, a4, a5, a6, a7)
    end AsFields7

    trait AsFields6 extends AsFields7:
        given [A1, A2, A3, A4, A5, A6](using
            a1: AsField[A1],
            a2: AsField[A2],
            a3: AsField[A3],
            a4: AsField[A4],
            a5: AsField[A5],
            a6: AsField[A6]
        ): AsFields[A1 & A2 & A3 & A4 & A5 & A6] =
            Set(a1, a2, a3, a4, a5, a6)
    end AsFields6

    trait AsFields5 extends AsFields6:
        given [A1, A2, A3, A4, A5](using
            a1: AsField[A1],
            a2: AsField[A2],
            a3: AsField[A3],
            a4: AsField[A4],
            a5: AsField[A5]
        ): AsFields[A1 & A2 & A3 & A4 & A5] =
            Set(a1, a2, a3, a4, a5)
    end AsFields5

    trait AsFields4 extends AsFields5:
        given [A1, A2, A3, A4](using
            a1: AsField[A1],
            a2: AsField[A2],
            a3: AsField[A3],
            a4: AsField[A4]
        ): AsFields[A1 & A2 & A3 & A4] =
            Set(a1, a2, a3, a4)
    end AsFields4

    trait AsFields3 extends AsFields4:
        given [A1, A2, A3](using
            a1: AsField[A1],
            a2: AsField[A2],
            a3: AsField[A3]
        ): AsFields[A1 & A2 & A3] =
            Set(a1, a2, a3)
    end AsFields3

    trait AsFields2 extends AsFields3:
        given [A1, A2](using
            a1: AsField[A1],
            a2: AsField[A2]
        ): AsFields[A1 & A2] =
            Set(a1, a2)
    end AsFields2

    object AsFields extends AsFields2:

        given [A1](using
            a1: AsField[A1]
        ): AsFields[A1] =
            Set(a1)

        def apply[A](using af: AsFields[A]): Set[Field[?, ?]] = af
    end AsFields

end Record
