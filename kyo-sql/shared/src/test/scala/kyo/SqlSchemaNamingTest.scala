package kyo

class SqlSchemaNamingTest extends Test:

    "identityTableNameIsIdentity" in {
        assert(SqlSchema.Naming.identity.tableName("Country") == "Country")
    }

    "identityColumnNameIsIdentity" in {
        assert(SqlSchema.Naming.identity.columnName("countryCode") == "countryCode")
    }

    "snakeCaseTableNameCountry" in {
        assert(SqlSchema.Naming.snakeCase.tableName("Country") == "country")
    }

    "snakeCaseTableNameCamelCase" in {
        assert(SqlSchema.Naming.snakeCase.tableName("CountryRegion") == "country_region")
    }

    "snakeCaseColumnSimple" in {
        assert(SqlSchema.Naming.snakeCase.columnName("name") == "name")
    }

    "snakeCaseColumnCamel" in {
        assert(SqlSchema.Naming.snakeCase.columnName("countryCode") == "country_code")
    }

    "snakeCaseColumnTripleSegment" in {
        assert(SqlSchema.Naming.snakeCase.columnName("topLevelCategoryId") == "top_level_category_id")
    }

    "snakeCaseEmpty" in {
        assert(SqlSchema.Naming.snakeCase.columnName("") == "")
    }

    "snakeCaseLeadingUpperNoUnderscore" in {
        assert(SqlSchema.Naming.snakeCase.columnName("X") == "x")
    }

end SqlSchemaNamingTest
