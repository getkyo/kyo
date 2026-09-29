package kyo.internal

import kyo.*

/** Runs in a fork whose default locale is Turkish (`locale-fork-settings` in build.sbt), where `"I".toLowerCase` is a dotless `ı` and
  * `"i".toUpperCase` is a dotted `İ`. A wire name is part of a protocol, so it must come out the same on that machine as on every other;
  * these leaves fail when any convention folds through the default locale. JVM only: Scala.js and Scala Native fold without a locale.
  */
class NameCaseConversionLocaleTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    "the fork's default locale is Turkish" in {
        assert(java.util.Locale.getDefault.getLanguage == "tr", s"expected a Turkish default locale, got ${java.util.Locale.getDefault}")
        assert("I".toLowerCase == "ı")
        assert("i".toUpperCase == "İ")
    }

    "snake_case folds without the default locale" in {
        assert(NameCaseConversion.convert(Schema.NameCase.SnakeCase)("userId") == "user_id")
        assert(NameCaseConversion.convert(Schema.NameCase.SnakeCase)("INIT") == "init")
    }

    "kebab-case folds without the default locale" in {
        assert(NameCaseConversion.convert(Schema.NameCase.KebabCase)("clientIp") == "client-ip")
    }

    "SCREAMING_SNAKE_CASE folds without the default locale" in {
        assert(NameCaseConversion.convert(Schema.NameCase.ScreamingSnakeCase)("clientIp") == "CLIENT_IP")
    }

    "camelCase folds without the default locale" in {
        assert(NameCaseConversion.convert(Schema.NameCase.CamelCase)("Item_id") == "itemId")
        assert(NameCaseConversion.convert(Schema.NameCase.CamelCase)("INIT_time") == "initTime")
    }

    "PascalCase folds without the default locale" in {
        assert(NameCaseConversion.convert(Schema.NameCase.PascalCase)("INIT_id") == "InitId")
    }

end NameCaseConversionLocaleTest
