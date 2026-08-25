package kyo

import kyo.internal.HtmlRenderer
import kyo.internal.InputMaskingTest

/** Holds the JavaScript half of the input filter and mask logic to the same case table as the Scala half.
  *
  * The feature ships twice: `kyo.internal.InputMasking` drives the SPA transport, and a hand-written JavaScript
  * mirror inside `HtmlRenderer.inputMaskJs` drives the server-push transport. Nothing but this suite makes the two
  * agree. It loads the mirror into a real page, runs `InputMaskingTest.parityCases` through it, and compares against
  * the expectations that `InputMaskingTest` holds the Scala implementation to. A row added there covers both.
  *
  * The whole table runs in a single evaluation: one round trip keeps a browser-backed suite cheap, and a mismatch
  * names the calls that disagreed rather than failing on the first one.
  */
class InputMaskingJsParityTest extends UITest:

    /** The mirror is emitted inside an IIFE, so its functions are not reachable from an evaluated expression. Load a
      * second copy and publish it explicitly instead of reaching into the page's own copy.
      */
    private val loadMirror =
        val names =
            Seq("kyoFilterStr", "kyoMaskParse", "kyoMaskClassAt", "kyoMaskOk", "kyoMaskFormat", "kyoMaskRaw", "kyoMaskNormalize")
        HtmlRenderer.inputMaskJs + ";" + names.map(n => s"window.$n=$n;").mkString
    end loadMirror

    "the JavaScript mirror agrees with the Scala implementation" in {
        val cases = InputMaskingTest.parityCases
        val table = cases.map(c => InputMaskingTest.runJs(c.call)).mkString("[", ",", "]")
        withUI(UI.div("parity")) {
            for
                _   <- Browser.evalDiscard(loadMirror)
                got <- Browser.evalJson[Seq[String]](table)
                _ = assert(got.length == cases.length, s"expected ${cases.length} results, got ${got.length}")
                mismatches = cases.zip(got).collect {
                    case (c, actual) if actual != c.expected =>
                        s"${c.call}: js=$actual expected=${c.expected}"
                }
            yield assert(mismatches.isEmpty, mismatches.mkString("; "))
        }
    }

end InputMaskingJsParityTest
