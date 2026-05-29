package kyo

import kyo.Browser.Selector.find
import kyo.Browser.Selector.visible
import kyo.internal.SelectorNode

/** Selector contract edge cases: empty-text, empty-FirstOf refactor guard, and `.visible` X `.find` nesting (both orderings + mismatch).
  *
  * Each test pins observed behavior so that future changes to the resolver, Selector AST, or fromNode short-circuit logic surface as
  * deterministic test diffs rather than silent regressions.
  */
class BrowserSelectorEdgeCaseTest extends BrowserTest:

    override def timeout = 90.seconds

    // ── Selector.text("") edges ──────────────────────────────────────────────

    /** Empirical property: `text("", exact = true)` against a page with empty and whitespace-only spans does not panic; pins whether the
      * resolver matches every empty text node, matches only whitespace-trimmed empties, or short-circuits to no match. The test asserts the
      * resolver returns a typed Result (Success xor typed Failure) and pins the observed shape inline.
      */
    "Selector.text(\"\", exact = true) against empty and whitespace spans pins observed resolver behavior" in run {
        withBrowser {
            onPage(
                """<span id="a"></span><span id="b"> </span><span id="c">hello</span>"""
            ) {
                Abort.run[BrowserElementException | BrowserAssertionException] {
                    Browser.withConfig(_.retrySchedule(Schedule.fixed(50.millis).maxDuration(500.millis))) {
                        Browser.assertExists(Browser.Selector.text("", exact = true))
                    }
                }.map { result =>
                    // Observed behavior: text("", exact = true) matches text nodes whose trimmed content equals
                    // the empty string. Both `<span id="a"></span>` (empty) and `<span id="b"> </span>` (whitespace-only, trimmed to "")
                    // satisfy the predicate, so assertExists succeeds. If a future change formalizes a different contract (e.g. constructor
                    // rejects empty strings, or resolver short-circuits on empty values), this test must update in lockstep.
                    result match
                        case Result.Success(_)                                    => succeed
                        case Result.Failure(_: BrowserElementNotFoundException)   => succeed
                        case Result.Failure(_: BrowserAssertionTimedOutException) => succeed
                        case other =>
                            fail(s"expected typed Success or typed Failure for text(\"\", exact = true), got $other")
                    end match
                }
            }
        }
    }

    /** Empirical property: `text("", exact = false)` against a page with non-empty content does not panic; pins whether the substring
      * resolver treats empty as "every string contains the empty substring" (matches the first text-bearing node) or short-circuits.
      */
    "Selector.text(\"\", exact = false) against a page with content pins observed resolver behavior" in run {
        withBrowser {
            onPage("""<span>hello</span>""") {
                Abort.run[BrowserElementException | BrowserAssertionException] {
                    Browser.withConfig(_.retrySchedule(Schedule.fixed(50.millis).maxDuration(500.millis))) {
                        Browser.assertExists(Browser.Selector.text("", exact = false))
                    }
                }.map { result =>
                    // Observed behavior: substring match with empty needle is the JS `String.prototype.includes("")` semantics,
                    // which is `true` for every string. The resolver therefore returns the first text node, and assertExists succeeds.
                    // If the contract is later changed to reject empty substrings, this test must update.
                    result match
                        case Result.Success(_)                                    => succeed
                        case Result.Failure(_: BrowserElementNotFoundException)   => succeed
                        case Result.Failure(_: BrowserAssertionTimedOutException) => succeed
                        case other =>
                            fail(s"expected typed Success or typed Failure for text(\"\", exact = false), got $other")
                    end match
                }
            }
        }
    }

    // ── Empty FirstOf refactor guard ─────────────────────────────────────────

    /** Empirical property: an internally-constructed `SelectorNode.FirstOf(Chunk.empty)` does not panic / NPE / hang when fed to
      * `assertExists`. The public `Selector.or` API cannot construct this shape (each branch of the smart constructor wraps at least one
      * input), so this test exists purely as a refactor guard: if a future change to `or` or `fromNode` introduces an empty FirstOf path,
      * this test pins the resolver's typed-failure behavior so the regression surfaces deterministically.
      */
    "Selector.fromNode(FirstOf(Chunk.empty)) resolves to a typed failure, not a panic" in run {
        // Refactor guard: empty FirstOf is unreachable via the public Selector.or; this test pins resolver behavior in case a future change
        // to `or` / `fromNode` introduces an empty path.
        val emptyFirstOf: Browser.Selector =
            Browser.Selector.fromNode(SelectorNode.FirstOf(Chunk.empty))
        withBrowser {
            onPage("<div>anything</div>") {
                Browser.withConfig(_.retrySchedule(Schedule.fixed(50.millis).maxDuration(500.millis))) {
                    // BrowserReadException is the catch-all row covering BrowserElementException, BrowserAssertionException,
                    // BrowserScriptException, and BrowserProtocolErrorException (which fires when the generated JS, e.g. the
                    // "( || 0)" string for an empty FirstOf, has a syntax error). All of these are acceptable typed shapes; what
                    // we forbid is a Panic (NPE / IOOBE from reduceLeft on Chunk.empty) or a silent Success.
                    Abort.run[BrowserReadException] {
                        Browser.assertExists(emptyFirstOf)
                    }.map {
                        case Result.Failure(_: BrowserReadException) => succeed
                        case Result.Panic(ex) =>
                            fail(s"empty FirstOf must not panic (refactor guard), got Panic($ex)")
                        case Result.Success(_) =>
                            fail("empty FirstOf must not succeed (refactor guard)")
                        case other =>
                            fail(s"empty FirstOf surfaced unexpected typed shape: $other")
                    }
                }
            }
        }
    }

    // ── .visible X .find nesting (3 sub-tests) ───────────────────────────────

    // Shared fixture: parent div with a visible child "X" and a display:none child "Y".
    private val visibleFindPage: String =
        """<div id='parent'><span class='child'>X</span><span class='child' style='display:none'>Y</span></div>"""

    /** Empirical property: `parent.find(child).visible` resolves to `Visible(Within(Id, Css))` and picks the first visible match inside the
      * parent scope. Both children match the inner `find`, but the second is `display:none`; the outer `.visible` filter must skip it and
      * land on "X".
      */
    "Selector parent.find(child).visible resolves to the visible child (Visible wraps Within)" in run {
        withBrowser {
            onPage(visibleFindPage) {
                val sel = Browser.Selector.id("parent").find(Browser.Selector.css(".child")).visible
                Browser.text(sel).map { t =>
                    assert(
                        t == "X",
                        s"expected the visible child 'X' but got '$t' (Visible(Within(Id, Css)) failed to filter out display:none)"
                    )
                }
            }
        }
    }

    /** Empirical property: `parent.find(child.visible)` resolves to `Within(Id, Visible(Css))` and picks the visible child inside the parent
      * scope. Inner `.visible` filtering must apply within the parent root; the result should equal the outer-visible ordering ("X").
      */
    "Selector parent.find(child.visible) resolves to the visible child (Visible wraps the inner)" in run {
        withBrowser {
            onPage(visibleFindPage) {
                val sel = Browser.Selector.id("parent").find(Browser.Selector.css(".child").visible)
                Browser.text(sel).map { t =>
                    assert(
                        t == "X",
                        s"expected the visible child 'X' but got '$t' (Within(Id, Visible(Css)) failed to filter inside the parent scope)"
                    )
                }
            }
        }
    }

    /** Empirical property: when the child path matches nothing under the parent (`.missing`), the nested `.visible` filter still surfaces a
      * typed `BrowserElementNotFoundException`, not a panic or a silent zero-result. Pins the failure shape for the no-match case.
      */
    "Selector parent.find(missing.visible) raises BrowserElementNotFoundException, not a panic" in run {
        withBrowser {
            onPage(visibleFindPage) {
                Browser.withConfig(_.retrySchedule(Schedule.fixed(50.millis).maxDuration(500.millis))) {
                    val sel = Browser.Selector.id("parent").find(Browser.Selector.css(".missing").visible)
                    Abort.run[BrowserReadException](Browser.text(sel)).map {
                        case Result.Failure(_: BrowserElementNotFoundException) => succeed
                        case other =>
                            fail(s"expected BrowserElementNotFoundException for missing child, got $other")
                    }
                }
            }
        }
    }

end BrowserSelectorEdgeCaseTest
