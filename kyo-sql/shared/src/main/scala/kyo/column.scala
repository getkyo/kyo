package kyo

/** Names the SQL column a case-class field maps to, overriding the field's Scala name.
  *
  * A SQL-specific annotation, read by the [[kyo.SqlSchema]] derivation and the static-SQL macros; document-side annotations such as
  * `@kyo.schema.rename` are not consulted by SQL, so a type's SQL column names and its document wire names are independent. An explicit
  * `@column` wins over an in-scope [[kyo.SqlNaming]] casing, which in turn wins over the verbatim Scala name.
  *
  * {{{
  * case class Person(
  *     @column("person_id") id: Long,
  *     name: String
  * ) derives SqlSchema
  * }}}
  */
final class column(val name: String) extends scala.annotation.StaticAnnotation
