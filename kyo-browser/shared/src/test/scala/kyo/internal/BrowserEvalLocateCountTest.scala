package kyo.internal

import kyo.*

/** Unit tests for [[BrowserEval.locateCount]] (FirstOf short-circuit) and [[BrowserEval.translateContextDestroyed]] (CDP error
  * translation).
  *
  * The FirstOf short-circuit test is live: it builds a page where only the first alternative has matches and asserts that
  * `locateCount(FirstOf)` returns the first alternative's count verbatim (regardless of subsequent alternatives' match counts).
  *
  * The `translateContextDestroyed` cases are pure (synthetic CDP wire) and verify that the `"Cannot find context"` error message routes to
  * `BrowserIFrameInvalidException(ContextDestroyed)`.
  */
class BrowserEvalLocateCountTest extends kyo.BrowserTest:

    override def timeout = 60.seconds

    // ── locateCount FirstOf short-circuit (live) ────────────────────

    "BrowserEval.locateCount FirstOf returns the first non-zero alternative's count (short-circuit)" in {
        // First alternative (#first.m) matches exactly 1; second (#second.m) matches 3. If the
        // short-circuit semantics hold, the returned count is 1 (the first alternative's count).
        // Without short-circuit the answer would be 4 (or some other concatenated combination).
        val html = page(
            """<div>
                <p id="first" class="m">one</p>
                <p id="s1" class="n">a</p>
                <p id="s2" class="n">b</p>
                <p id="s3" class="n">c</p>
            </div>"""
        )
        withBrowserOnLocalhost {
            Browser.goto(html).andThen {
                val first    = Selector.css(".m")
                val second   = Selector.css(".n")
                val combined = first.or(second)
                BrowserEval.locateCount(combined).map { n =>
                    assert(n == 1, s"FirstOf short-circuit expected count=1 (first alternative wins) but got $n")
                }
            }
        }
    }

    "BrowserEval.locateCount FirstOf falls through to subsequent alternatives when earlier ones miss" in {
        // First alternative misses (.miss matches nothing); second matches 2. The walk must visit
        // the second alternative and return 2.
        val html = page(
            """<div>
                <p class="m">a</p>
                <p class="m">b</p>
            </div>"""
        )
        withBrowserOnLocalhost {
            Browser.goto(html).andThen {
                val first    = Selector.css(".miss")
                val second   = Selector.css(".m")
                val combined = first.or(second)
                BrowserEval.locateCount(combined).map { n =>
                    assert(n == 2, s"FirstOf fall-through expected count=2 (second alternative wins) but got $n")
                }
            }
        }
    }

    "BrowserEval.locateCount FirstOf returns 0 when no alternative matches" in {
        val html = page("<div><p>nothing</p></div>")
        withBrowserOnLocalhost {
            Browser.goto(html).andThen {
                val combined = Selector.css(".missA").or(Selector.css(".missB"))
                BrowserEval.locateCount(combined).map { n =>
                    assert(n == 0, s"expected 0 when no alternative matches but got $n")
                }
            }
        }
    }

    // ── translateContextDestroyed (pure) ─────────────────────────────

    "translateContextDestroyed surfaces the CDP context-destroyed message as BrowserIFrameInvalidException(ContextDestroyed)" in {
        val wire =
            s"""{"id":1,"error":{"code":-32000,"message":"${CdpErrorStrings.ContextDestroyedErrorMessage}"}}"""
        Abort.run[BrowserReadException](BrowserEval.translateContextDestroyed(wire)).map {
            case Result.Failure(BrowserIFrameInvalidException(BrowserIFrameInvalidException.Reason.ContextDestroyed)) =>
                ()
            case other => fail(s"expected BrowserIFrameInvalidException(ContextDestroyed) but got $other")
        }
    }

    "translateContextDestroyed passes a successful eval reply through unchanged (no translation)" in {
        val wire = """{"id":1,"result":{"result":{"type":"string","value":"hello"}}}"""
        Abort.run[BrowserReadException](BrowserEval.translateContextDestroyed(wire)).map {
            case Result.Success(out) =>
                assert(out == wire, s"expected wire passed through verbatim but got '$out'")
            case other => fail(s"expected Success(wire passthrough) but got $other")
        }
    }

    "translateContextDestroyed passes an unrelated CDP error through unchanged (only ContextDestroyed is translated)" in {
        val wire = """{"id":1,"error":{"code":-32602,"message":"Invalid params"}}"""
        Abort.run[BrowserReadException](BrowserEval.translateContextDestroyed(wire)).map {
            case Result.Success(out) =>
                assert(out == wire, s"expected unrelated error wire passed through verbatim but got '$out'")
            case other => fail(s"expected Success(wire passthrough) for unrelated CDP error but got $other")
        }
    }

end BrowserEvalLocateCountTest
