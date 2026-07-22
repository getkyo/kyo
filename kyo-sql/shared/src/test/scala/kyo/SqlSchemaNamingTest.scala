package kyo

class SqlSchemaNamingTest extends Test:

    "identityTableNameIsIdentity" in {
        assert(SqlSchema.Naming.Identity.tableName("Country") == "Country")
    }

    "identityColumnNameIsIdentity" in {
        assert(SqlSchema.Naming.Identity.columnName("countryCode") == "countryCode")
    }

    "snakeCaseTableNameCountry" in {
        assert(SqlSchema.Naming.SnakeCase.tableName("Country") == "country")
    }

    "snakeCaseTableNameCamelCase" in {
        assert(SqlSchema.Naming.SnakeCase.tableName("CountryRegion") == "country_region")
    }

    "snakeCaseColumnSimple" in {
        assert(SqlSchema.Naming.SnakeCase.columnName("name") == "name")
    }

    "snakeCaseColumnCamel" in {
        assert(SqlSchema.Naming.SnakeCase.columnName("countryCode") == "country_code")
    }

    "snakeCaseColumnTripleSegment" in {
        assert(SqlSchema.Naming.SnakeCase.columnName("topLevelCategoryId") == "top_level_category_id")
    }

    "snakeCaseEmpty" in {
        assert(SqlSchema.Naming.SnakeCase.columnName("") == "")
    }

    "snakeCaseLeadingUpperNoUnderscore" in {
        assert(SqlSchema.Naming.SnakeCase.columnName("X") == "x")
    }

end SqlSchemaNamingTest
