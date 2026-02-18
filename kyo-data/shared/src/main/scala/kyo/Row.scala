package kyo

import kyo.internal.TypeIntersection
import scala.NamedTuple
import scala.NamedTuple.AnyNamedTuple
import scala.annotation.implicitNotFound
import scala.compiletime.*
import scala.compiletime.ops.int.S

/** A type-safe, ordered collection of named fields backed by Scala's NamedTuple.
  *
  * Scala's `NamedTuple` encodes names and values as two separate type-level tuples — `NamedTuple[("name", "age"), (String, Int)]`. This
  * makes generic code awkward: writing a method that accepts any named tuple requires threading two tuple type parameters everywhere, and
  * type-level operations must manipulate both in lockstep.
  *
  * Row wraps this into a single opaque type parameter with zero runtime overhead, so generic signatures stay clean:
  * {{{
  * def process[A <: AnyNamedTuple](row: Row[A]): Row[A] = ...
  * }}}
  *
  * Because Row is an opaque alias over NamedTuple, every `Row[A]` '''is''' a NamedTuple. All NamedTuple methods — named field access
  * (`row.name`), positional access (`row(0)`), `head`, `tail`, `take`, `drop`, `reverse`, `++`, `map`, `zip`, and more — are available
  * directly on Row values with no wrapping cost. Row then adds further operations: `add`, `update`, `fields`, `toRecord`, `mapFields`,
  * `toSpan`, and `Render` / `CanEqual` instances.
  *
  * =Row vs Record=
  * Row and [[Record]] both provide type-safe named fields, but represent different abstractions:
  *
  * '''Row''' is a '''labeled sequence''' — fields have a fixed order, each name is unique, and the type must match exactly. Use Row when
  * field order matters, when you need positional operations, or when working with case classes.
  *
  * '''Record''' is a '''labeled set''' — fields are unordered, a record with more fields can be used where fewer are expected (structural
  * subtyping), and the same name can appear with different types. Use Record when different consumers need different field subsets or when
  * composing fields from multiple sources.
  *
  * The two interconvert freely:
  * {{{
  * val row = Row((name = "Alice", age = 30))
  * val record = row.toRecord              // Row -> Record
  * val back   = Row.fromRecord(record)    // Record -> Row (rejects duplicate field names)
  * }}}
  *
  * =Creation=
  * {{{
  * // From a named tuple literal
  * val row = Row((name = "Alice", age = 30))
  *
  * // From a case class
  * case class Person(name: String, age: Int)
  * val row = Row.fromProduct(Person("Alice", 30))
  *
  * // Incrementally
  * val row = Row.empty.add("name", "Alice").add("age", 30)
  * }}}
  *
  * =Field Access and NamedTuple Operations=
  * Fields are accessed by name with compile-time type checking. All standard NamedTuple operations are also available:
  * {{{
  * row.name      // "Alice": String  (named access)
  * row.age       // 30: Int
  * row(0)        // "Alice": String  (positional access)
  * row.head      // first value
  * row.tail      // all but first
  * row.take(1)   // first n fields
  * row.drop(1)   // skip n fields
  * row.reverse   // reverse field order
  * row ++ other  // concatenate (duplicate names rejected)
  * }}}
  */
opaque type Row[A <: AnyNamedTuple] >: A <: A = A

object Row:

    /** Creates an empty Row with no fields. */
    def empty: Row[Empty] = NamedTuple.Empty

    /** Creates a Row from a named tuple literal. */
    def apply[A <: AnyNamedTuple](value: A): Row[A] = value

    /** Creates a Row from a product type (case class or tuple). */
    def fromProduct[A <: Product](value: A): Row[From[A]] =
        Tuple.fromProduct(value).asInstanceOf[Row[From[A]]]

    /** Creates a Row from a Record. Fails at compile time if the record has duplicate field names. */
    inline def fromRecord[Fields](record: Record[Fields])(using ti: TypeIntersection[Fields]): Row[FromRecord[ti.AsTuple]] =
        checkNoDuplicateFieldNames[ti.AsTuple]
        record.values.asInstanceOf[Row[FromRecord[ti.AsTuple]]]
    end fromRecord

    /** Creates a Row where each field value is produced by a `StageAs` callback, useful for building rows of effects or references. */
    inline def stage[A <: AnyNamedTuple]: RowStageOps[A] = new RowStageOps[A](())

    class RowStageOps[A <: AnyNamedTuple](dummy: Unit) extends AnyVal:
        inline def apply[F[_, _]](as: Record.StageAs[F]): Row[NamedTuple.NamedTuple[Names[A], StageValues[Names[A], Values[A], F]]] =
            stageImpl[Names[A], Values[A], F](as)
                .asInstanceOf[Row[NamedTuple.NamedTuple[Names[A], StageValues[Names[A], Values[A], F]]]]
    end RowStageOps

    // --- Extensions ---
    // These extend Row beyond what NamedTuple provides. NamedTuple methods (head, tail, take,
    // drop, reverse, ++, apply, map, zip, etc.) are inherited automatically via the opaque alias.

    extension [A <: AnyNamedTuple](self: Row[A])

        /** Appends a new field to the end of this Row. */
        def add[N <: String & Singleton, V](name: N, value: V): Row[Append[A, N, V]] =
            (self.asInstanceOf[Tuple] :* value).asInstanceOf[Row[Append[A, N, V]]]

        /** Returns the field names as a list of strings. */
        inline def fields: List[String] =
            collectFieldNames[Names[A]]

        /** Returns the underlying value tuple, stripping field names. */
        def values: Values[A] =
            self.asInstanceOf[Values[A]]

        /** Returns the values as a Span. */
        def toSpan: Span[Any] =
            Span.fromUnsafe(self.asInstanceOf[Tuple].toArray.asInstanceOf[Array[Any]])

        /** Converts this Row to a `Map[Field, Any]` keyed by field name and type tag. */
        inline def toMap: Map[Record.Field[?, ?], Any] =
            collectFields[Names[A], Values[A]](self.asInstanceOf[Tuple].toArray, 0)

        /** Returns a new Row with the named field replaced by the given value. Rejects non-existent fields and type mismatches at compile
          * time.
          */
        inline def update[Name <: String & Singleton](name: Name, value: ValueAt[A, Name]): Row[A] =
            val idx = constValue[IndexOf[Names[A], Name, 0]]
            val arr = self.asInstanceOf[Tuple].toArray
            arr(idx) = value.asInstanceOf[Object]
            Tuple.fromArray(arr).asInstanceOf[Row[A]]
        end update

        /** Reinterprets the field names of this Row as `B`, keeping the same values. Fails at compile time if value types do not match. */
        def renameTo[B <: AnyNamedTuple](using
            @implicitNotFound("Cannot rename: value types of source and target rows do not match")
            ev: Values[A] =:= Values[B]
        ): Row[B] =
            self.asInstanceOf[Row[B]]

        /** Converts this Row to a [[Record]]. */
        inline def toRecord: Record[ToRecordFields[Names[A], Values[A]]] =
            Record.unsafeFrom[ToRecordFields[Names[A], Values[A]]](toMap)

        /** Transforms each field value using a polymorphic function that receives the field descriptor and value. */
        inline def mapFields[F[_]](f: [t] => (Record.Field[?, t], t) => F[t]): Row[NamedTuple.Map[A, F]] =
            mapFieldsImpl[Names[A], Values[A], F](self.asInstanceOf[Tuple].toArray, f, 0)
                .asInstanceOf[Row[NamedTuple.Map[A, F]]]

    end extension

    // --- Evidences ---

    given canEqual[A <: AnyNamedTuple](using CanEqual[A, A]): CanEqual[Row[A], Row[A]] =
        CanEqual.derived

    inline given render[A <: AnyNamedTuple]: Render[Row[A]] =
        val renders = collectRenders[Names[A], Values[A]]
        Render.from: (row: Row[A]) =>
            val arr = row.asInstanceOf[Tuple].toArray
            var idx = 0
            renders.map { case (name, r: Render[x]) =>
                val s = name + " = " + r.asText(arr(idx).asInstanceOf[x]).show
                idx += 1
                s
            }.mkString("(", ", ", ")")
    end render

    // --- Type aliases ---

    /** Derives the named tuple type from a product type (case class or tuple). */
    export NamedTuple.Concat
    export NamedTuple.From

    /** The type of a Row with no fields. */
    type Empty = NamedTuple.Empty

    type Init[N <: String, V] =
        NamedTuple.NamedTuple[N *: EmptyTuple, V *: EmptyTuple]

    /** The result of appending field `N: V` to the end of row type `A`. */
    type Append[A <: AnyNamedTuple, N <: String, V] =
        NamedTuple.Concat[A, Init[N, V]]

    /** Extracts the name tuple from a named tuple type. */
    type Names[A <: AnyNamedTuple] = NamedTuple.Names[A]

    /** Extracts the value tuple from a named tuple type. */
    type Values[A <: AnyNamedTuple] = NamedTuple.DropNames[A]

    /** Looks up the value type for a field name. Reduces to `Nothing` if the name is not present. */
    type ValueAt[A <: AnyNamedTuple, Name <: String] =
        ValueAtImpl[Names[A], Values[A], Name]

    type ValueAtImpl[Ns <: Tuple, Vs <: Tuple, Name <: String] = (Ns, Vs) match
        case (Name *: _, v *: _)      => v
        case (_ *: ns, _ *: vs)       => ValueAtImpl[ns, vs, Name]
        case (EmptyTuple, EmptyTuple) => Nothing

    /** Finds the zero-based position of `Name` in the name tuple `Ns`. */
    type IndexOf[Ns <: Tuple, Name <: String, I <: Int] <: Int =
        Ns match
            case Name *: _ => I
            case _ *: rest => IndexOf[rest, Name, S[I]]

    /** Converts a tuple of `Record.~` pairs to a named tuple type. */
    type FromRecord[T <: Tuple] = NamedTuple.NamedTuple[FieldNames[T], FieldValues[T]]

    type FieldNames[T <: Tuple] <: Tuple = T match
        case EmptyTuple               => EmptyTuple
        case Record.`~`[n, v] *: rest => n *: FieldNames[rest]

    type FieldValues[T <: Tuple] <: Tuple = T match
        case EmptyTuple               => EmptyTuple
        case Record.`~`[n, v] *: rest => v *: FieldValues[rest]

    type StageValues[Ns <: Tuple, Vs <: Tuple, F[_, _]] <: Tuple = (Ns, Vs) match
        case (EmptyTuple, EmptyTuple) => EmptyTuple
        case (n *: ns, v *: vs)       => F[n, v] *: StageValues[ns, vs, F]

    /** Converts name and value tuples to a Record field intersection type. */
    type ToRecordFields[Ns <: Tuple, Vs <: Tuple] = Record.FieldsOf[Ns, Vs]

    // --- Duplicate field detection ---

    /** Evidence that name `N` exists in tuple `Ns`. */
    // TODO can't we make UniqueField just NotGiven[HasName[...]]?
    sealed trait HasName[Ns <: Tuple, N <: String]
    given hasNameHead[N <: String, Ns <: Tuple]: HasName[N *: Ns, N]                          = HasName.instance.asInstanceOf
    given hasNameTail[H, N <: String, Ns <: Tuple](using HasName[Ns, N]): HasName[H *: Ns, N] = HasName.instance.asInstanceOf

    private object HasName:
        val instance: HasName[Nothing, Nothing] = new HasName[Nothing, Nothing] {}

    /** Evidence that field name `N` does not already exist in row type `A`. */
    @implicitNotFound("Duplicate field '${N}' in row")
    sealed trait UniqueField[A <: AnyNamedTuple, N <: String]
    given uniqueField[A <: AnyNamedTuple, N <: String](using scala.util.NotGiven[HasName[Names[A], N]]): UniqueField[A, N] =
        UniqueField.instance.asInstanceOf

    private object UniqueField:
        val instance: UniqueField[Nothing, Nothing] = new UniqueField[Nothing, Nothing] {}

    // --- Private helpers ---

    private inline def collectFieldNames[Ns <: Tuple]: List[String] =
        inline erasedValue[Ns] match
            case _: EmptyTuple => Nil
            case _: (n *: ns)  => constValue[n & String] :: collectFieldNames[ns]

    private inline def collectFields[Ns <: Tuple, Vs <: Tuple](
        arr: Array[Object],
        idx: Int
    ): Map[Record.Field[?, ?], Any] =
        inline erasedValue[(Ns, Vs)] match
            case _: (EmptyTuple, EmptyTuple) => Map.empty
            case _: (n *: ns, v *: vs) =>
                val name = constValue[n & String]
                val tag  = summonInline[Tag[v]]
                collectFields[ns, vs](arr, idx + 1).updated(Record.Field(name, tag), arr(idx))

    private inline def collectRenders[Ns <: Tuple, Vs <: Tuple]: List[(String, Render[?])] =
        inline erasedValue[(Ns, Vs)] match
            case _: (EmptyTuple, EmptyTuple) => Nil
            case _: (n *: ns, v *: vs) =>
                val name   = constValue[n & String]
                val render = summonInline[Render[v]]
                (name, render) :: collectRenders[ns, vs]

    private inline def checkNoDuplicateFieldNames[T <: Tuple]: Unit =
        inline erasedValue[T] match
            case _: EmptyTuple => ()
            case _: (Record.`~`[n, v] *: rest) =>
                inline if containsFieldName[rest, n & String] then
                    error("Record has duplicate field names, cannot convert to Row")
                else
                    checkNoDuplicateFieldNames[rest]

    private transparent inline def containsFieldName[T <: Tuple, N <: String]: Boolean =
        inline erasedValue[T] match
            case _: EmptyTuple                 => false
            case _: (Record.`~`[N, ?] *: _)    => true
            case _: (Record.`~`[?, ?] *: rest) => containsFieldName[rest, N]

    private inline def mapFieldsImpl[Ns <: Tuple, Vs <: Tuple, F[_]](
        arr: Array[Object],
        f: [t] => (Record.Field[?, t], t) => F[t],
        idx: Int
    ): Tuple =
        inline erasedValue[(Ns, Vs)] match
            case _: (EmptyTuple, EmptyTuple) => EmptyTuple
            case _: (n *: ns, v *: vs) =>
                val name  = constValue[n & String]
                val tag   = summonInline[Tag[v]]
                val field = Record.Field(name, tag)
                val value = arr(idx).asInstanceOf[v]
                f[v](field, value) *: mapFieldsImpl[ns, vs, F](arr, f, idx + 1)

    private inline def stageImpl[Ns <: Tuple, Vs <: Tuple, F[_, _]](
        as: Record.StageAs[F]
    ): Tuple =
        inline erasedValue[(Ns, Vs)] match
            case _: (EmptyTuple, EmptyTuple) => EmptyTuple
            case _: (n *: ns, v *: vs) =>
                val name = constValue[n & String]
                val tag  = summonInline[Tag[v]]
                as.stage[n & String, v](Record.Field(name, tag)) *: stageImpl[ns, vs, F](as)

end Row
