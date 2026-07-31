package kyo

/** Covers the directory overload of [[I18n.init]].
  *
  * It lives here rather than in the shared suite because reaching `Path` from the Scala.js test bundle would
  * pull in the `node:fs` module import, and those tests link as `NoModule`. The overload itself is documented
  * as the JVM, Scala Native, and Node path.
  */
class I18nPathTest extends kyo.test.Test[Any]:

    private val en = Locale("en")
    private val de = Locale("de")

    "init from a directory" - {
        "reads <locale>.ftl per locale" in {
            for
                dir <- Path.tempDir("kyo-i18n")
                _   <- (dir / "en.ftl").write("hello = Hello")
                _   <- (dir / "de.ftl").write("hello = Hallo")
                h   <- I18n.init(Seq(en, de), en)(dir)
                a   <- I18n.let(h)(I18n.at(en, "hello"))
                b   <- I18n.let(h)(I18n.at(de, "hello"))
                _   <- dir.removeAll
            yield assert(a == "Hello" && b == "Hallo")
        }
    }
end I18nPathTest
