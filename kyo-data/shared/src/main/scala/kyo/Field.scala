package kyo

import Record2.~

/** A reified field descriptor carrying the field name, its value type's Tag, and any nested fields (when the value is itself a Record2).
  */
case class Field[Name <: String, Value](
    name: Name,
    tag: Tag[Value],
    nested: List[Field[?, ?]] = Nil
):
    def get[F](record: Record2[F])(using F <:< (Name ~ Value)): Value =
        record.toMap(name).asInstanceOf[Value]

    def set[F](record: Record2[F], value: Value)(using F <:< (Name ~ Value)): Record2[F] =
        Record2.make(record.toMap.updated(name, value))
end Field

object Field:
    def apply[Name <: String & Singleton, Value](using name: ConstValue[Name], tag: Tag[Value]): Field[Name, Value] =
        Field(name, tag)
end Field
