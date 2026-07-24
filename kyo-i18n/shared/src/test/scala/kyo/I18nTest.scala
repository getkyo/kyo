package kyo

class I18nTest extends kyo.test.Test[Any]:

    private val en = Locale("en")
    private val de = Locale("de")

    private val enFtl =
        """|# greetings
           |hello = Hello
           |greet = Hello, { $name }!
           |count = { $count } players
           |empty =
           |""".stripMargin

    private val deFtl =
        """|hello = Hallo
           |greet = Hallo, { $name }!
           |count = { $count } Spieler
           |""".stripMargin

    private val bundles = Map(en -> enFtl, de -> deFtl)

    "Locale" - {
        "parse normalizes code and tag" in {
            assert(Locale.parse("de-DE") == Present(de))
            assert(Locale.parse("EN") == Present(en))
            assert(Locale.parse("  de  ") == Present(de))
        }
        "parse rejects empty input" in {
            assert(Locale.parse("") == Absent)
            assert(Locale.parse("-x") == Absent)
        }
        "code round-trips" in {
            assert(en.code == "en" && de.code == "de")
        }
    }

    "at (explicit locale)" - {
        "resolves per locale" in {
            for
                h <- I18n.initInMemory(bundles, en)
                a <- I18n.let(h)(I18n.at(en, "hello"))
                b <- I18n.let(h)(I18n.at(de, "hello"))
            yield assert(a == "Hello" && b == "Hallo")
        }
        "interpolates a named argument" in {
            for
                h <- I18n.initInMemory(bundles, en)
                v <- I18n.let(h)(I18n.at(en, "greet", Map("name" -> "Sam")))
            yield assert(v == "Hello, Sam!")
        }
        "missing key renders the miss marker" in {
            for
                h <- I18n.initInMemory(bundles, en)
                v <- I18n.let(h)(I18n.at(en, "nope"))
            yield assert(v == "‹nope›")
        }
        "empty-valued key renders the miss marker" in {
            for
                h <- I18n.initInMemory(bundles, en)
                v <- I18n.let(h)(I18n.at(en, "empty"))
            yield assert(v == "‹empty›")
        }
    }

    "now (active locale, point-in-time)" - {
        "follows the active locale" in {
            for
                h <- I18n.initInMemory(bundles, en)
                result <- I18n.let(h) {
                    for
                        before <- I18n.now("hello")
                        _      <- I18n.setLocale(de)
                        after  <- I18n.now("hello")
                    yield (before, after)
                }
            yield assert(result == ("Hello", "Hallo"))
        }
    }

    "t (reactive leaf)" - {
        "current reflects the active locale, before and after a switch" in {
            for
                h <- I18n.initInMemory(bundles, en)
                result <- I18n.let(h) {
                    for
                        v1 <- I18n.t("hello").current
                        _  <- I18n.setLocale(de)
                        v2 <- I18n.t("hello").current
                    yield (v1, v2)
                }
            yield assert(result == ("Hello", "Hallo"))
        }
        "a forked next wakes on a locale switch" in {
            for
                h <- I18n.initInMemory(bundles, en)
                result <- I18n.let(h) {
                    for
                        f <- Fiber.initUnscoped(I18n.t("hello").next)
                        _ <- Async.sleep(100.millis)
                        _ <- I18n.setLocale(de)
                        v <- f.get
                    yield v
                }
            yield assert(result == "Hallo")
        }
        "outside any let, renders the miss marker" in {
            for v <- I18n.t("hello").current
            yield assert(v == "‹hello›")
        }
        "a Signal[String] argument is interpolated" in {
            for
                h <- I18n.initInMemory(bundles, en)
                v <- I18n.let(h) {
                    for
                        nameRef <- Signal.initRef("Sam")
                        out     <- I18n.t("greet", Map("name" -> nameRef)).current
                    yield out
                }
            yield assert(v == "Hello, Sam!")
        }
    }

    "i18n\"...\" interpolator" - {
        "composes literal text with a reactive leaf" in {
            for
                h <- I18n.initInMemory(bundles, en)
                v <- I18n.let(h) {
                    val hello = I18n.t("hello")
                    i18n"[$hello]".current
                }
            yield assert(v == "[Hello]")
        }
    }
end I18nTest
