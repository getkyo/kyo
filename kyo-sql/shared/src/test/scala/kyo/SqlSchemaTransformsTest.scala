package kyo

// Top-level case class: `derives SqlSchema` is unsupported on opaque types so we use an explicit given.
// Tests call `SqlSchema.derived[Country]` inline to match the plan; the given here ensures summoning also works.
case class Country(code: String, name: String)

object Country:
    given SqlSchema[Country] = SqlSchema.derived

/** Tests for the `.withNaming`, `.withTableName`, and `.rename` transforms on [[SqlSchema]], plus the `SqlSchema.naming` companion
  * accessor.
  */
class SqlSchemaTransformsTest extends Test:

    given CanEqual[Any, Any] = CanEqual.derived

    "withNamingAttachesStrategy" in {
        val schema = SqlSchema.derived[Country].withNaming(NamingStrategy.snakeCase)
        assert(schema.namingStrategy == Maybe(NamingStrategy.snakeCase))
    }

    "withTableNameAttachesOverride" in {
        val schema = SqlSchema.derived[Country].withTableName("countries")
        assert(schema.tableNameOverride == Maybe("countries"))
    }

    "withNamingThenWithTableNameComposes" in {
        val schema = SqlSchema.derived[Country]
            .withNaming(NamingStrategy.snakeCase)
            .withTableName("countries")
        assert(schema.namingStrategy == Maybe(NamingStrategy.snakeCase))
        assert(schema.tableNameOverride == Maybe("countries"))
    }

    "withTableNameThenWithNamingComposes" in {
        val schema = SqlSchema.derived[Country]
            .withTableName("countries")
            .withNaming(NamingStrategy.snakeCase)
        assert(schema.tableNameOverride == Maybe("countries"))
        assert(schema.namingStrategy == Maybe(NamingStrategy.snakeCase))
    }

    "sugarNamingAccessorReturnsCompanion" in {
        assert(SqlSchema.naming.snakeCase eq NamingStrategy.snakeCase)
    }

end SqlSchemaTransformsTest
