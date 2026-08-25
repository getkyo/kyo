package kyo

import kyo.internal.Bundle

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

    private val bundles = Dict(en -> enFtl, de -> deFtl)

    private val withUnsupported =
        """|hello = Hello
           |-brand = Kyo
           |.attribute = nope
           |bye = Bye
           |""".stripMargin

    /** A handle over a locale ref the caller owns, so a switch can be sequenced against the leaf's subscription
      * (`ref.waiters`) rather than a sleep. Mirrors what [[I18n.initInMemory]] builds internally.
      */
    private def handleOver(ref: SignalRef[Locale])(using Frame): I18n < Sync =
        Kyo.foreach(bundles.toChunk)((loc, ftl) => Bundle.parse(ftl).map(loc -> _)).map { parsed =>
            new I18n(parsed.foldLeft(Dict.empty[Locale, Bundle])((acc, p) => acc.update(p._1, p._2)), ref, loc => ref.set(loc))
        }

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
        "Undetermined is the BCP-47 und subtag" in {
            assert(Locale.Undetermined.code == "und")
        }
        "default reports a normalized subtag" in {
            // The host language varies per machine, so assert the shape the module guarantees: a
            // normalized primary subtag, falling back to Undetermined when the host names none.
            for host <- Locale.default
            yield assert(host.code.nonEmpty && host.code == host.code.toLowerCase && !host.code.contains("-"))
        }
        "preferred picks the host locale when it is available" in {
            for
                host   <- Locale.default
                picked <- Locale.preferred(Seq(host), en)
            yield assert(picked == host)
        }
        "preferred falls back when the host locale has no bundle" in {
            for picked <- Locale.preferred(Seq.empty, en)
            yield assert(picked == en)
        }
    }

    "init" - {
        "loads each locale through the supplied function" in {
            for
                h <- I18n.init(Seq(en, de), en)(loc => Sync.defer(s"hello = from ${loc.code}"))
                a <- I18n.let(h)(I18n.at(en, "hello"))
                b <- I18n.let(h)(I18n.at(de, "hello"))
            yield assert(a == "from en" && b == "from de")
        }
        // The directory overload is covered by I18nPathTest, which is jvm-native: reaching Path from the
        // JS test bundle would require the node:fs module import, and these tests link as NoModule.
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
                v <- I18n.let(h)(I18n.at(en, "greet", Dict("name" -> "Sam")))
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
                ref <- Signal.initRef(en)
                h   <- handleOver(ref)
                f   <- Fiber.initUnscoped(I18n.let(h)(I18n.t("hello").next))
                // Wait for the leaf to arm its waiter on the locale ref; setting before that would
                // race the subscription and the fiber would never wake.
                _ <- assertEventually(ref.waiters.map(_ == 1))
                _ <- ref.set(de)
                v <- f.get
            yield assert(v == "Hallo")
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
                        out     <- I18n.t("greet", Dict("name" -> nameRef)).current
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

    "parsing outside the supported subset" - {
        "keeps the messages it understands" in {
            for
                h <- I18n.initInMemory(Dict(en -> withUnsupported), en)
                a <- I18n.let(h)(I18n.at(en, "hello"))
                b <- I18n.let(h)(I18n.at(en, "bye"))
            yield assert(a == "Hello" && b == "Bye")
        }

        "warns rather than dropping them silently" in {
            // Unsafe: an AtomicRef observes the warn emitted from the parser's Log side-effect.
            val warned = AtomicRef.Unsafe.init(Maybe.empty[String])(using AllowUnsafe.embrace.danger)

            // Override emit, not warn: the async drain dispatches through event.sink.emit.
            class CapturingLog extends Log.Unsafe.ConsoleLogger("test", Log.Level.warn):
                override def emit(event: Log.Event)(using allow: AllowUnsafe): Unit =
                    if event.level == Log.Level.warn then warned.set(Present(event.message))(using allow)

            Log.let(Log(new CapturingLog)) {
                for
                    _   <- I18n.initInMemory(Dict(en -> withUnsupported), en)
                    _   <- Log.flush
                    saw <- Sync.Unsafe.defer(warned.get())
                yield
                    assert(saw.isDefined, "an ignored line must be reported")
                    assert(saw.get.contains("-brand") && saw.get.contains(".attribute"))
            }
        }
    }
end I18nTest
