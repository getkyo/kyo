package kyo.internal

import kyo.Chunk
import kyo.Maybe
import kyo.SqlNaming

/** Centralised SQL column-name resolution.
  *
  * The dynamic render path (`buildColumns` / `buildRowColumns`) calls this so the runtime column name matches the one the static macro
  * folds. Both read the same facts: the field's `@column` name, lifted from the type's annotations at the DSL construction site,
  * and the in-scope [[SqlNaming]] casing given threaded from the query site. Table names come from the query method's table-name
  * parameter, never from here.
  *
  * Resolution order for column names:
  *   1. the explicit `@column` name for the field.
  *   2. the [[SqlNaming]] casing convention applied to the field name.
  *   3. the verbatim Scala field name.
  */
object SqlNameResolver:

    /** Returns the SQL column name for a Scala field of `T`. `renames` are `T`'s `(scalaName, columnName)` annotation pairs, lifted at the
      * DSL construction site; the type parameter is what lets the static-lift reconstruction re-read them from `T` itself.
      */
    def columnName[T](scalaName: String, renames: Chunk[(String, String)], naming: Maybe[SqlNaming]): String =
        renames
            .find(_._1 == scalaName)
            .map(_._2)
            .getOrElse(
                naming match
                    case Maybe.Present(n) => n.columnName(scalaName)
                    case Maybe.Absent     => scalaName
            )
    end columnName

end SqlNameResolver
