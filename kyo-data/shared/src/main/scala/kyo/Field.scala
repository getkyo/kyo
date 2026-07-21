package kyo

import Record.~

/** A reified field descriptor carrying the field's singleton string name, its value type's `Tag`, and optional nested field descriptors
  * (populated when the value type is itself a `Record`).
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
    nested: List[Field[?, ?]] = Nil,
    default: Maybe[Value] = Maybe.empty
):
    /** Extracts this field's value from a record. Requires evidence that `F` contains `Name ~ Value`.
      *
      * Only typechecks when `Name` is a String singleton (`Name <: String & Singleton`), because `~` requires its left parameter to be a
      * singleton. Fields whose `Name` is the un-narrowed `String` type — typically constructed at runtime from a `String` value — can't use
      * this accessor; use `Record.toDict(name)` directly on the underlying `Dict`.
      */
    def get[F, N <: Name & String & scala.Singleton](record: Record[F])(using F <:< (N ~ Value)): Value =
        record.toDict(name).asInstanceOf[Value]

    /** Returns a new record with this field's value replaced. Requires evidence that `F` contains `Name ~ Value`.
      *
      * Only typechecks when `Name` is a String singleton (see [[get]]).
      */
    def set[F, N <: Name & String & scala.Singleton](record: Record[F], value: Value)(using F <:< (N ~ Value)): Record[F] =
        Record.init(record.toDict.update(name, value.asInstanceOf[Any]))
end Field

object Field:
    /** Constructs a `Field` by summoning the singleton name value and `Tag` from implicit scope. */
    def apply[Name <: String & Singleton, Value](using name: ConstValue[Name], tag: Tag[Value]): Field[Name, Value] =
        Field(name, tag)
end Field
