package kyo.internal

import kyo.Chunk
import kyo.Maybe
import kyo.SqlSchema
import kyo.SqlSchema.Naming

/** Centralised SQL name resolution for columns and tables.
  *
  * All three resolution sites (Column construction in buildColumns/buildRowColumns, tableNameImpl[T], columnNamesImpl[T]) call these
  * helpers instead of duplicating the lookup chain.
  *
  * Resolution order for column names:
  *   1. renamedFields override (Schema.rename applied before the given was built).
  *   2. namingStrategy (e.g. SqlSchema.Naming.SnakeCase).
  *   3. Verbatim Scala field name (fallback, preserves pre-change behaviour byte-for-byte).
  *
  * Resolution order for table names:
  *   1. tableNameOverride (Schema.withTableName).
  *   2. namingStrategy.tableName applied to the simple Scala type name.
  *   3. Verbatim lower-cased simple type name (fallback, matches the prior macro behaviour).
  */
object SqlNameResolver:

    /** Returns the SQL column name for a Scala field, consulting the attached [[SqlSchema]]. */
    def columnName[A](scalaName: String, schema: SqlSchema[A]): String =
        val renamedFields    = SqlSchema.readRenamedFields(schema)
        val namingStrategyOp = SqlSchema.readNaming(schema)
        renamedFields
            .toSeq
            .find(_._1 == scalaName)
            .map(_._2)
            .getOrElse(
                namingStrategyOp match
                    case Maybe.Present(strategy) => strategy.columnName(scalaName)
                    case Maybe.Absent            => scalaName
            )
    end columnName

    /** Returns the SQL table name for a Scala type, consulting the attached [[SqlSchema]]. */
    def tableName[A](typeName: String, schema: SqlSchema[A]): String =
        val overrideOp       = SqlSchema.readTableNameOverride(schema)
        val namingStrategyOp = SqlSchema.readNaming(schema)
        overrideOp match
            case Maybe.Present(overrideName) => overrideName
            case Maybe.Absent =>
                namingStrategyOp match
                    case Maybe.Present(strategy) => strategy.tableName(typeName)
                    case Maybe.Absent            => typeName.toLowerCase
        end match
    end tableName

end SqlNameResolver
