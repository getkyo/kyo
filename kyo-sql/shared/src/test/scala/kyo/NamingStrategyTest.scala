package kyo

class NamingStrategyTest extends Test:

    "identityTableNameIsIdentity" in {
        assert(NamingStrategy.identity.tableName("Country") == "Country")
    }

    "identityColumnNameIsIdentity" in {
        assert(NamingStrategy.identity.columnName("countryCode") == "countryCode")
    }

    "snakeCaseTableNameCountry" in {
        assert(NamingStrategy.snakeCase.tableName("Country") == "country")
    }

    "snakeCaseTableNameCamelCase" in {
        assert(NamingStrategy.snakeCase.tableName("CountryRegion") == "country_region")
    }

    "snakeCaseColumnSimple" in {
        assert(NamingStrategy.snakeCase.columnName("name") == "name")
    }

    "snakeCaseColumnCamel" in {
        assert(NamingStrategy.snakeCase.columnName("countryCode") == "country_code")
    }

    "snakeCaseColumnTripleSegment" in {
        assert(NamingStrategy.snakeCase.columnName("topLevelCategoryId") == "top_level_category_id")
    }

    "snakeCaseEmpty" in {
        assert(NamingStrategy.snakeCase.columnName("") == "")
    }

    "snakeCaseLeadingUpperNoUnderscore" in {
        assert(NamingStrategy.snakeCase.columnName("X") == "x")
    }

end NamingStrategyTest
