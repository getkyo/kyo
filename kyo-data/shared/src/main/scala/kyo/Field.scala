package kyo

import Record2.~

/** A reified field descriptor carrying the field's singleton string name, its value type's `Tag`, and optional nested field descriptors
  * (populated when the value type is itself a `Record2`).
  *
  * Field instances are typically obtained from `Fields.fields` or constructed via the companion's `apply` method. They serve as runtime
  * metadata for operations like `mapFields`, `stage`, and serialization, and also provide typed `get`/`set` accessors on records.
  *
  * @tparam Name
  *   The singleton string type of the field name
  * @tparam Value
  *   The field's value type
  */
case class Field[Name <: String, Value](
    name: Name,
    tag: Tag[Value],
    nested: List[Field[?, ?]] = Nil
):
    /** Extracts this field's value from a record. Requires evidence that `F` contains `Name ~ Value`. */
    def get[F](record: Record2[F])(using F <:< (Name ~ Value)): Value =
        record.toMap(name).asInstanceOf[Value]

    /** Returns a new record with this field's value replaced. Requires evidence that `F` contains `Name ~ Value`. */
    def set[F](record: Record2[F], value: Value)(using F <:< (Name ~ Value)): Record2[F] =
        Record2.make(record.toMap.updated(name, value))
end Field

object Field:
    /** Constructs a `Field` by summoning the singleton name value and `Tag` from implicit scope. */
    def apply[Name <: String & Singleton, Value](using name: ConstValue[Name], tag: Tag[Value]): Field[Name, Value] =
        Field(name, tag)
end Field
