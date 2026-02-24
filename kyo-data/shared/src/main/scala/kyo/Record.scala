package kyo

export Record.`~`
import kyo.Record.*
import scala.language.dynamics
import scala.language.implicitConversions

/** A type-safe, immutable record that maps string field names to values using intersection types.
  *
  * Record encodes its schema as an intersection of `Name ~ Value` pairs in the type parameter `F`. For example,
  * `Record["name" ~ String & "age" ~ Int]` describes a record with a `name` field of type `String` and an `age` field of type `Int`. Fields
  * are created with the `~` extension on `String` and combined with `&`. Case classes can also be converted via `fromProduct`.
  *
  * =Subtyping via Implicit Conversion=
  * The type parameter `F` is '''invariant''', but an implicit `widen` conversion in the companion object allows a `Record[A]` to be used
  * wherever a `Record[B]` is expected, provided `A <: B`. Since `"name" ~ String & "age" ~ Int <: "name" ~ String` by Scala's intersection
  * subtyping rules, a record with more fields can be assigned where fewer are expected. This gives the effect of structural subtyping while
  * keeping the type parameter invariant, which avoids the unsoundness issues that a covariant parameter would introduce with `~`'s
  * contravariant `Value` position. After widening, the underlying data still contains all original fields; use `compact` to strip fields
  * not present in the declared type.
  *
  * =Duplicate Fields=
  * The same field name may appear with different types. Scala normalizes `"f" ~ Int & "f" ~ String` to `"f" ~ (Int | String)` because `~`
  * is contravariant in `Value`, so duplicates merge into a union at the type level.
  *
  * =Field Access=
  * Fields are accessed via `selectDynamic` with compile-time verification through `Fields.Have`. The return type is fully inferred, so no
  * type ascription is needed. For field names that are not valid Scala identifiers (e.g., `"user-name"`, `"&"`), use `getField` instead.
  *
  * =Equality=
  * A `CanEqual` given enables `==` and `!=` when `Fields.Comparable` is available (i.e., all value types have `CanEqual`). Field order does
  * not affect equality. Without `Comparable` evidence, `==` will not compile, preventing accidental comparisons on non-comparable types.
  *
  * @tparam F
  *   Intersection of `Name ~ Value` field types describing the record's schema
  */
// final class Record[F](private[kyo] val dict: Dict[String, Any]) extends Dynamic:

//     /** Retrieves a field value by name via dynamic method syntax. The return type is inferred from the field's declared type. Requires
//       * `Fields.Have` evidence that the field exists in `F`.
//       */
//     def selectDynamic[Name <: String & Singleton](name: Name)(using h: Fields.Have[F, Name]): h.Value =
//         dict(name).asInstanceOf[h.Value]

//     /** Retrieves a field value by name. Unlike `selectDynamic`, this method works with any string literal, including names that are not
//       * valid Scala identifiers (e.g., `"user-name"`, `"&"`, `""`).
//       */
//     def getField[Name <: String & Singleton, V](name: Name)(using h: Fields.Have[F, Name]): h.Value =
//         dict(name).asInstanceOf[h.Value]

//     /** Combines this record with another, producing a record whose type is the intersection of both field sets. If both records contain a
//       * field with the same name, the value from `other` takes precedence at runtime.
//       */
//     def &[A](other: Record[A]): Record[F & A] =
//         new Record(dict ++ other.dict)

//     /** Returns a new record with the specified field's value replaced. The field name and value type must match a field in `F`. */
//     def update[Name <: String & Singleton, V](name: Name, value: V)(using F <:< (Name ~ V)): Record[F] =
//         new Record(dict.update(name, value.asInstanceOf[Any]))

//     /** Returns a new record containing only the fields declared in `F`, removing any extra fields that may be present in the underlying
//       * storage due to widening. Requires a `Fields` instance for `F`.
//       */
//     def compact(using f: Fields[F]): Record[F] =
//         new Record(dict.filter((k, _) => f.names.contains(k)))

//     /** Returns the field names declared in `F` as a list. */
//     def fields(using f: Fields[F]): List[String] =
//         f.fields.map(_.name)

//     /** Extracts all field values as a typed tuple, ordered by the field declaration in `F`. */
//     inline def values(using f: Fields[F]): f.Values =
//         Record.collectValues[f.AsTuple](dict).asInstanceOf[f.Values]

//     /** Applies a polymorphic function to each field value, wrapping each value type in `G`. Returns a new record where every field
//       * `Name ~ V` becomes `Name ~ G[V]`.
//       */
//     def map[G[_]](using
//         f: Fields[F]
//     )(
//         fn: [t] => t => G[t]
//     ): Record[f.Map[~.MapValue[G]]] =
//         new Record(
//             dict
//                 .filter((k, _) => f.names.contains(k))
//                 .mapValues(v => fn(v))
//         )

//     /** Like `map`, but the polymorphic function also receives the `Field` descriptor for each field, providing access to the field name and
//       * tag.
//       */
//     def mapFields[G[_]](using
//         f: Fields[F]
//     )(
//         fn: [t] => (Field[?, t], t) => G[t]
//     ): Record[f.Map[~.MapValue[G]]] =
//         val result = DictBuilder.init[String, Any]
//         f.fields.foreach: field =>
//             dict.get(field.name) match
//                 case Present(v) =>
//                     discard(result.add(field.name, fn(field.asInstanceOf[Field[?, Any]], v)))
//                 case _ =>
//         new Record(result.result())
//     end mapFields

//     /** Pairs the values of this record with another record by field name. Both records must have the same field names (verified at compile
//       * time). For each field `Name ~ V1` in this record and `Name ~ V2` in `other`, the result contains `Name ~ (V1, V2)`.
//       */
//     inline def zip[F2](other: Record[F2])(using
//         f1: Fields[F],
//         f2: Fields[F2],
//         ev: Fields.SameNames[F, F2]
//     ): Record[f1.Zipped[f2.AsTuple]] =
//         val result = DictBuilder.init[String, Any]
//         f1.fields.foreach: field =>
//             discard(result.add(field.name, (dict(field.name), other.dict(field.name))))
//         new Record(result.result())
//     end zip

//     /** Returns the number of fields stored in this record. */
//     def size: Int = dict.size

//     /** Returns the record's contents as a `Dict[String, Any]`. */
//     def toDict: Dict[String, Any] = dict

//     override def equals(that: Any): Boolean =
//         that match
//             case other: Record[?] =>
//                 given CanEqual[Any, Any] = CanEqual.derived
//                 dict.is(other.dict)
//             case _ => false

//     override def hashCode(): Int =
//         var h = 0
//         dict.foreach((k, v) => h = h ^ (k.hashCode * 31 + v.##))
//         h
//     end hashCode

// end Record

opaque type Record[F] = Record.Impl[F]

/** Companion object providing record construction, field type definitions, implicit conversions, and compile-time staging. */
object Record:
    opaque type Impl[F] = Dict[String, Any]

    extension [F](record: Record[F])
        def toDict: Dict[String, Any] = record
        def &[A](other: Record[A]): Record[F & A] =
            (record: Dict[String, Any]) ++ other
    end extension

    given [F, T, S](using c: Fields.Aux[F, T, S]): Conversion[Record[F], S] =
        new Conversion[Record[F], S]:
            def apply(r: Record[F]): S = Fields.Structural.from(r).asInstanceOf[S]

    /** Phantom type representing a field binding from a singleton string name to a value type. Contravariant in `Value` so that duplicate
      * field names with different types are normalized to a union: `"f" ~ Int & "f" ~ String =:= "f" ~ (Int | String)`.
      */
    final infix class ~[Name <: String, -Value] private () extends Serializable

    object `~`:
        /** Type-level function that wraps the value component of a `Name ~ Value` pair in `G`. */
        type MapValue[G[_]] = [x] =>> x match
            case n ~ v => n ~ G[v]
    end `~`

    /** Match type that looks up the value type for `Name` in a tuple of `Name ~ Value` pairs. */
    type FieldValue[T <: Tuple, Name <: String] = T match
        case (Name ~ v) *: _ => v
        case _ *: rest       => FieldValue[rest, Name]

    /** An empty record with type `Record[Any]`, which is the identity element for `&`. */
    val empty: Record[Any] = Dict.empty[String, Any]

    /** Implicit conversion that enables structural subtyping for Record. Since `F` is invariant, this conversion allows a `Record[A]` to be
      * used where a `Record[B]` is expected whenever `A <: B`. This is safe because the underlying `Dict` storage is read-only, and the `~`
      * type's contravariance ensures that field type relationships are correctly preserved through intersection subtyping.
      */
    implicit def widen[A <: B, B](r: Record[A]): Record[B] =
        r.asInstanceOf[Record[B]]

    /** Creates a single-field record from a string literal name and a value. */
    extension (self: String)
        def ~[Value](value: Value): Record[self.type ~ Value] =
            Dict[String, Any](self -> value)

    /** Provides `CanEqual` for records whose field types are all comparable, enabling `==` and `!=`. */
    given [F](using Fields.Comparable[F]): CanEqual[Record[F], Record[F]] =
        CanEqual.derived

    /** Provides a `Render` instance for records, rendering each field as `"name ~ value"` joined by `" & "`. Requires `Render` instances
      * for all field value types.
      */
    given render[F](using f: Fields[F], renders: Fields.SummonAll[F, Render]): Render[Record[F]] =
        Render.from: (value: Record[F]) =>
            val sb    = new StringBuilder
            var first = true
            value.toDict.foreach: (name, v) =>
                if renders.contains(name) then
                    if !first then discard(sb.append(" & "))
                    discard(sb.append(name).append(" ~ ").append(renders.get(name).asText(v)))
                    first = false
            sb.toString

    import scala.compiletime.*

    /** Begins compile-time staging for a field type `A`. Staging iterates over the fields at compile time (via inline expansion) and
      * applies a polymorphic function to each, producing a new record. This is useful for deriving metadata, default values, or type class
      * instances per field. Call the returned `StageOps` directly to stage without a type class, or chain `.using[TC]` to require a type
      * class instance for each field's value type.
      */
    inline def stage[A](using f: Fields[A]): StageOps[A, f.AsTuple] = new StageOps(())

    /** Intermediate builder for staging without a type class constraint. Apply a polymorphic function `Field[?, v] => G[v]` to produce a
      * record where each field `Name ~ V` becomes `Name ~ G[V]`.
      */
    class StageOps[A, T <: Tuple](dummy: Unit) extends AnyVal:
        inline def apply[G[_]](fn: [v] => Field[?, v] => G[v])(using f: Fields[A]): Record[f.Map[~.MapValue[G]]] =
            stageLoop[f.AsTuple, G](fn).asInstanceOf[Record[f.Map[~.MapValue[G]]]]

        /** Adds a type class constraint `TC` that must be available for each field's value type. Produces a `StageWith` that accepts a
          * function receiving both the `Field` descriptor and the `TC` instance.
          */
        inline def using[TC[_]]: StageWith[A, T, TC] = new StageWith(())
    end StageOps

    /** Intermediate builder for staging with a type class constraint `TC`. Apply a polymorphic function `(Field[?, v], TC[v]) => G[v]` to
      * produce a record where each field `Name ~ V` becomes `Name ~ G[V]`. Fails at compile time if any field's value type lacks a `TC`
      * instance.
      */
    class StageWith[A, T <: Tuple, TC[_]](dummy: Unit) extends AnyVal:
        inline def apply[G[_]](fn: [v] => (Field[?, v], TC[v]) => G[v])(using f: Fields[A]): Record[f.Map[~.MapValue[G]]] =
            stageLoopWith[f.AsTuple, TC, G](fn).asInstanceOf[Record[f.Map[~.MapValue[G]]]]

    // Note: stageLoop/stageLoopWith use Dict here but SummonAll uses Map. Dict works in these methods
    // but causes the compiler to hang in SummonAll, likely due to how the opaque type interacts with
    // inline expansion in certain patterns.
    private[kyo] inline def stageLoop[T <: Tuple, G[_]](fn: [v] => Field[?, v] => G[v]): Dict[String, Any] =
        inline erasedValue[T] match
            case _: EmptyTuple => Dict.empty[String, Any]
            case _: ((n1 ~ v1) *: (n2 ~ v2) *: (n3 ~ v3) *: (n4 ~ v4) *: rest) =>
                val name1 = constValue[n1 & String]
                val name2 = constValue[n2 & String]
                val name3 = constValue[n3 & String]
                val name4 = constValue[n4 & String]
                stageLoop[rest, G](fn)
                    ++ Dict[String, Any](name1 -> fn[v1](Field(name1, summonInline[Tag[v1]])))
                    ++ Dict[String, Any](name2 -> fn[v2](Field(name2, summonInline[Tag[v2]])))
                    ++ Dict[String, Any](name3 -> fn[v3](Field(name3, summonInline[Tag[v3]])))
                    ++ Dict[String, Any](name4 -> fn[v4](Field(name4, summonInline[Tag[v4]])))
            case _: ((n ~ v) *: rest) =>
                val name  = constValue[n & String]
                val value = fn[v](Field(name, summonInline[Tag[v]]))
                stageLoop[rest, G](fn) ++ Dict[String, Any](name -> value)

    private[kyo] inline def stageLoopWith[T <: Tuple, TC[_], G[_]](fn: [v] => (Field[?, v], TC[v]) => G[v]): Dict[String, Any] =
        inline erasedValue[T] match
            case _: EmptyTuple => Dict.empty[String, Any]
            case _: ((n1 ~ v1) *: (n2 ~ v2) *: (n3 ~ v3) *: (n4 ~ v4) *: rest) =>
                val name1 = constValue[n1 & String]
                val name2 = constValue[n2 & String]
                val name3 = constValue[n3 & String]
                val name4 = constValue[n4 & String]
                stageLoopWith[rest, TC, G](fn)
                    ++ Dict[String, Any](name1 -> fn[v1](Field(name1, summonInline[Tag[v1]]), summonInline[TC[v1]]))
                    ++ Dict[String, Any](name2 -> fn[v2](Field(name2, summonInline[Tag[v2]]), summonInline[TC[v2]]))
                    ++ Dict[String, Any](name3 -> fn[v3](Field(name3, summonInline[Tag[v3]]), summonInline[TC[v3]]))
                    ++ Dict[String, Any](name4 -> fn[v4](Field(name4, summonInline[Tag[v4]]), summonInline[TC[v4]]))
            case _: ((n ~ v) *: rest) =>
                val name  = constValue[n & String]
                val value = fn[v](Field(name, summonInline[Tag[v]]), summonInline[TC[v]])
                stageLoopWith[rest, TC, G](fn) ++ Dict[String, Any](name -> value)

    /** Creates a record from a case class or other `Product` type. Each product element becomes a field whose name matches the element
      * label. The return type is a `Record` with the appropriate field intersection, inferred transparently by the macro. Fails at compile
      * time if `A` is not a `Product`.
      */
    transparent inline def fromProduct[A <: Product](value: A): Any =
        ${ internal.FieldsMacros.fromProductImpl[A]('value) }

    private[kyo] inline def collectValues[T <: Tuple](dict: Dict[String, Any]): Tuple =
        inline erasedValue[T] match
            case _: EmptyTuple => EmptyTuple
            case _: ((n1 ~ v1) *: (n2 ~ v2) *: (n3 ~ v3) *: (n4 ~ v4) *: rest) =>
                dict(constValue[n1 & String]) *: dict(constValue[n2 & String]) *:
                    dict(constValue[n3 & String]) *: dict(constValue[n4 & String]) *:
                    collectValues[rest](dict)
            case _: ((n ~ v) *: rest) =>
                dict(constValue[n & String]) *: collectValues[rest](dict)

    private[kyo] def init[F](dict: Dict[String, Any]): Record[F] =
        dict

end Record
