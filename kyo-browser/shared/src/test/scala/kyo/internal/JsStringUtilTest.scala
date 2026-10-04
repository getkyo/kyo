package kyo.internal

import kyo.*

/** [[JsStringUtil.escapeJsString]] against what its callers need: the escaped text, placed between single quotes in JavaScript, evaluates
  * back to the original string.
  *
  * ==LOAD-BEARING==
  * The navigation-watcher leaf must not be deleted or weakened. [[NavigationWatcher]] decides whether a trigger navigated by evaluating
  * `location.href !== '<escaped snapshot url>'`. If the escape stopped round-tripping a character a URL can carry, that comparison would
  * report every poll as a URL change, and every click would wait out a settle it never needed.
  */
class JsStringUtilTest extends kyo.BrowserTest:

    // Built from code points so the source stays ASCII: these characters are invisible or ambiguous in an editor.
    private val nbsp     = 0xa0.toChar.toString
    private val lineSep  = 0x2028.toChar.toString
    private val paraSep  = 0x2029.toChar.toString
    private val grinning = new String(Character.toChars(0x1f600))

    "escapeJsString output" - {

        "leaves a string with nothing to escape unchanged" in {
            assert(JsStringUtil.escapeJsString("hello") == "hello")
        }

        "escapes backslash, single quote, newline, carriage return and tab, and nothing else" in {
            val raw = s"tab=\twindows=\r\nquote='back\\slash \"double\" nbsp=$nbsp"
            assert(JsStringUtil.escapeJsString(raw) == s"tab=\\twindows=\\r\\nquote=\\'back\\\\slash \"double\" nbsp=$nbsp")
        }
    }

    "a single-quoted JS literal of the escaped text evaluates to the original" - {

        val inputs = Seq(
            "empty"                            -> "",
            "plain"                            -> "plain",
            "a single quote"                   -> "it's",
            "two single quotes"                -> "''",
            "a backslash"                      -> "\\",
            "backslashes before a quote"       -> "\\\\'",
            "a newline"                        -> "line\nbreak",
            "CRLF"                             -> "crlf\r\nend",
            "a tab"                            -> "tab\there",
            "double quotes"                    -> "\"double\"",
            "a no-break space"                 -> s"nbsp${nbsp}sep",
            "line and paragraph separators"    -> s"line${lineSep}para${paraSep}sep",
            "a supplementary-plane code point" -> s"emoji $grinning"
        )

        inputs.foreach { (label, input) =>
            label in {
                withBrowser {
                    Browser.eval(s"'${JsStringUtil.escapeJsString(input)}'").map { out =>
                        assert(out == input, s"expected '$input' back but got '$out'")
                    }
                }
            }
        }
    }

    // LOAD-BEARING: see the class scaladoc.
    "a URL carrying a quote and a backslash compares unchanged in NavigationWatcher's check" in {
        withBrowser {
            for
                _    <- Browser.goto(page("<p>nav</p>"))
                _    <- Browser.eval("""(() => { history.pushState({}, '', "#it's\\here"); return 'ok'; })()""")
                href <- Browser.eval("location.href")
                same <- Browser.eval(s"location.href !== '${JsStringUtil.escapeJsString(href)}' ? 'changed' : 'same'")
            yield
                assert(href.endsWith("#it's\\here"), s"the fragment must keep the quote and the backslash raw, got '$href'")
                assert(same == "same", s"an unchanged URL compared as changed: '$href'")
        }
    }

end JsStringUtilTest
