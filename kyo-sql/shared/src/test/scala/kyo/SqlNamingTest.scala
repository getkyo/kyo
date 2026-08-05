package kyo

class SqlNamingTest extends Test:

    /** Probe type for the derived-table-name leaves below; the macro reads its simple type name. */
    case class NamingProbeUserProfile(id: Long)

    // The derivation contract, pinned at the macro level: with no given the derived table name is the LOWERCASED
    // type name; installing Identity explicitly emits it verbatim (a different name, since identifiers render
    // quoted); SnakeCase derives the underscored form. SqlNaming's own methods are covered separately below.
    "the derived table name with no naming given is the lowercased type name" in {
        assert(kyo.internal.SqlMacros.tableName[NamingProbeUserProfile] == "namingprobeuserprofile")
    }

    "the derived table name under an explicit Identity given is the type name verbatim" in {
        given SqlNaming = SqlNaming.Identity
        assert(kyo.internal.SqlMacros.tableName[NamingProbeUserProfile] == "NamingProbeUserProfile")
    }

    "the derived table name under SnakeCase is the underscored, lowercased form" in {
        given SqlNaming = SqlNaming.SnakeCase
        assert(kyo.internal.SqlMacros.tableName[NamingProbeUserProfile] == "naming_probe_user_profile")
    }

    "identityTableNameIsIdentity" in {
        assert(SqlNaming.Identity.tableName("Country") == "Country")
    }

    "identityColumnNameIsIdentity" in {
        assert(SqlNaming.Identity.columnName("countryCode") == "countryCode")
    }

    "snakeCaseTableNameCountry" in {
        assert(SqlNaming.SnakeCase.tableName("Country") == "country")
    }

    "snakeCaseTableNameCamelCase" in {
        assert(SqlNaming.SnakeCase.tableName("CountryRegion") == "country_region")
    }

    "snakeCaseColumnSimple" in {
        assert(SqlNaming.SnakeCase.columnName("name") == "name")
    }

    "snakeCaseColumnCamel" in {
        assert(SqlNaming.SnakeCase.columnName("countryCode") == "country_code")
    }

    "snakeCaseColumnTripleSegment" in {
        assert(SqlNaming.SnakeCase.columnName("topLevelCategoryId") == "top_level_category_id")
    }

    "snakeCaseEmpty" in {
        assert(SqlNaming.SnakeCase.columnName("") == "")
    }

    "snakeCaseLeadingUpperNoUnderscore" in {
        assert(SqlNaming.SnakeCase.columnName("X") == "x")
    }

    // An acronym run is one segment: the segment boundary sits where the run's last upper is followed by a
    // lower, and where a lower or digit is followed by an upper. Chosen over the per-character split before
    // the first release froze the mapping.
    "snakeCaseKeepsAnEmbeddedAcronymWhole" in {
        assert(SqlNaming.SnakeCase.columnName("httpURLId") == "http_url_id")
    }

    "snakeCaseKeepsALeadingAcronymWhole" in {
        assert(SqlNaming.SnakeCase.tableName("HTTPRequest") == "http_request")
    }

    "snakeCaseKeepsATrailingAcronymWhole" in {
        assert(SqlNaming.SnakeCase.columnName("userID") == "user_id")
    }

    "snakeCaseKeepsDigitsAttachedToTheirSegment" in {
        assert(SqlNaming.SnakeCase.columnName("addressLine2") == "address_line2")
    }

    "snakeCaseStartsASegmentAfterADigit" in {
        assert(SqlNaming.SnakeCase.columnName("address2Line") == "address2_line")
    }

    "snakeCaseDoesNotDoubleAnExistingUnderscore" in {
        assert(SqlNaming.SnakeCase.columnName("already_snake") == "already_snake")
        assert(SqlNaming.SnakeCase.columnName("Mixed_Case") == "mixed_case")
    }

    "snakeCaseUpperIsTheSameSegmentationUpperCased" in {
        assert(SqlNaming.SnakeCaseUpper.columnName("signedUpAt") == "SIGNED_UP_AT")
        assert(SqlNaming.SnakeCaseUpper.tableName("HTTPServer") == "HTTP_SERVER")
    }

    "upperCaseAndLowerCaseFoldPerCharacter" in {
        assert(SqlNaming.UpperCase.columnName("signedUpAt") == "SIGNEDUPAT")
        assert(SqlNaming.UpperCase.tableName("Invoice") == "INVOICE")
        assert(SqlNaming.LowerCase.columnName("SignedUpAt") == "signedupat")
        assert(SqlNaming.LowerCase.tableName("Invoice") == "invoice")
    }

    "decapitalizeLowersOnlyANonAcronymHead" in {
        assert(SqlNaming.decapitalize("Invoice") == "invoice")
        assert(SqlNaming.decapitalize("HTTPServer") == "HTTPServer")
        assert(SqlNaming.decapitalize("X") == "x")
        assert(SqlNaming.decapitalize("already") == "already")
        assert(SqlNaming.decapitalize("") == "")
    }

    "bare from refuses a derived alias past the 63-byte identifier limit" in {
        val errors = scala.compiletime.testing.typeCheckErrors(
            "kyo.Sql.from[kyo.SqlNamingTest.A234567890123456789012345678901234567890123456789012345678901234]"
        )
        assert(errors.exists(_.message.contains("63-byte identifier limit")))
    }

end SqlNamingTest

object SqlNamingTest:
    /** 64-character type name, one byte past PostgreSQL's identifier limit, for the bare-from refusal leaf. */
    case class A234567890123456789012345678901234567890123456789012345678901234(id: Long) derives SqlSchema
