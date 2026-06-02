package kyo

import kyo.Browser.*
import kyo.UI.foreach
import scala.language.implicitConversions

class StyleBrowserTest extends UITest:

    // ---- Merged from HoverTest ----

    "hover on button does not crash" in run {
        withUI(UI.div(UI.button("Go").id("b"))) {
            for
                _ <- Browser.hover(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("b"), "Go")
            yield succeed
        }
    }

    "hover on div does not crash" in run {
        withUI(UI.div(UI.div("content").id("d"))) {
            for
                _ <- Browser.hover(Selector.id("d"))
                _ <- Browser.assertText(Selector.id("d"), "content")
            yield succeed
        }
    }

    "element with Style.hover bold renders" in run {
        withUI(UI.div(
            UI.span("text").style(Style.hover(Style.bold)).id("s")
        )) {
            Browser.assertText(Selector.id("s"), "text").andThen(succeed)
        }
    }

    "hover element with Style.hover bold renders" in run {
        withUI(UI.div(
            UI.span("text").style(Style.hover(Style.bold)).id("s")
        )) {
            for
                _ <- Browser.hover(Selector.id("s"))
                _ <- Browser.assertText(Selector.id("s"), "text")
            yield succeed
        }
    }

    "hover then unhover does not crash" in run {
        withUI(UI.div(UI.button("Go").id("b"), UI.span("away").id("a"))) {
            for
                _ <- Browser.hover(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("b"), "Go")
                // Browser unhover = move mouse off; hover a different element to clear hover state
                _ <- Browser.hover(Selector.id("a"))
                _ <- Browser.assertText(Selector.id("b"), "Go")
            yield succeed
        }
    }

    "unhover does not crash" in run {
        withUI(UI.div(UI.button("Go").id("b"), UI.span("away").id("a"))) {
            for
                // Direct unhover (without prior hover): moving mouse to another element.
                _ <- Browser.hover(Selector.id("a"))
                _ <- Browser.assertText(Selector.id("b"), "Go")
            yield succeed
        }
    }

    "element with Style.hover bg red renders" in run {
        withUI(UI.div(
            UI.span("text").style(Style.hover(Style.bg(Style.Color.red))).id("s")
        )) {
            Browser.assertText(Selector.id("s"), "text").andThen(succeed)
        }
    }

    "hover then click both work" in run {
        val app: UI < Async =
            for counter <- Signal.initRef(0)
            yield UI.div(
                UI.button("Go").id("b").onClick(counter.getAndUpdate(_ + 1).unit),
                counter.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.hover(Selector.id("b"))
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield succeed
        }
    }

    "hover then pressKey both work" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").onKeyDown(ke => ref.set(ke.key.toString)),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.hover(Selector.id("i"))
                _ <- Browser.press(Selector.id("i"), Key.Enter)
                _ <- Browser.assertText(Selector.id("v"), "Enter")
            yield succeed
        }
    }

    "hover disabled element does not crash" in run {
        withUI(UI.div(UI.button("Go").id("b").disabled(true))) {
            for
                _ <- Browser.hover(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("b"), "Go")
            yield succeed
        }
    }

    "hover element inside foreach works" in run {
        val app: UI < Async =
            for items <- Signal.initRef(Chunk.from(Seq("A", "B")))
            yield UI.div(
                items.foreach(s => UI.button(s).id(s"btn-$s"))
            )
        withUI(app) {
            for
                _ <- Browser.hover(Selector.id("btn-A"))
                _ <- Browser.hover(Selector.id("btn-B"))
                _ <- Browser.assertText(Selector.id("btn-B"), "B")
            yield succeed
        }
    }

    "hover then element disappears via signal does not crash" in run {
        val app: UI < Async =
            for show <- Signal.initRef(true)
            yield UI.div(
                UI.button("Hide").id("hide").onClick(show.set(false)),
                UI.when(show)(UI.span("target").id("target"))
            )
        withUI(app) {
            for
                _ <- Browser.hover(Selector.id("target"))
                _ <- Browser.click(Selector.id("hide"))
                _ <- Browser.assertNotExists(Selector.id("target"))
            yield succeed
        }
    }

    // ---- Merged from HiddenElementTest ----

    "hidden input not visible" in run {
        withUI(UI.div(
            UI.input.id("i").hidden(true).value("secret"),
            UI.span("visible").id("s")
        )) {
            for
                _ <- Browser.assertText(Selector.id("s"), "visible")
                _ <- Browser.assertAttribute(Selector.id("i"), "hidden", "")
            yield succeed
        }
    }

    "hidden button not actionable" in run {
        val app: UI < Async =
            for counter <- Signal.initRef(0)
            yield UI.div(
                UI.button("Go").id("b").hidden(true).onClick(counter.getAndUpdate(_ + 1).unit),
                counter.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertAttribute(Selector.id("b"), "hidden", "")
                _ <- Browser.assertText(Selector.id("v"), "0")
            yield succeed
        }
    }

    "hidden element Tab skips" in run {
        withUI(UI.div(
            UI.input.id("a"),
            UI.input.id("hidden").hidden(true),
            UI.input.id("c")
        )) {
            for
                _ <- Browser.click(Selector.id("a"))
                _ <- Browser.press(Selector.id("a"), Key.Tab)
                _ <- Browser.assertFocused(Selector.id("c"))
            yield succeed
        }
    }

    // PENDING: kyo-ui doesn't auto-advance focus when the focused element becomes hidden; the
    // browser leaves document.activeElement on the now-hidden element until something else moves it.
    /*
    "toggle hidden on focused element focus auto advances" in run {
        // Requires UIControlSession to detect when the focused element becomes hidden
        // and automatically advance focus to the next visible focusable element.
    }
     */

    "toggle hidden element appears disappears" in run {
        val app: UI < Async =
            for hidden <- Signal.initRef(true)
            yield UI.div(
                UI.button("Toggle").id("tog").onClick(hidden.getAndUpdate(!_).unit),
                hidden.map(h => UI.span("content").id("c").hidden(h))
            )
        withUI(app) {
            for
                _ <- Browser.assertAttribute(Selector.id("c"), "hidden", "")
                _ <- Browser.click(Selector.id("tog"))
                _ <- Browser.assertText(Selector.id("c"), "content")
                _ <- Browser.click(Selector.id("tog"))
                _ <- Browser.assertAttribute(Selector.id("c"), "hidden", "")
            yield succeed
        }
    }

    "hidden form submit does nothing" in run {
        val app: UI < Async =
            for counter <- Signal.initRef(0)
            yield UI.div(
                UI.form.id("f").hidden(true).onSubmit(counter.getAndUpdate(_ + 1).unit)(
                    UI.button("Sub").id("sub")
                ),
                counter.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertAttribute(Selector.id("f"), "hidden", "")
                _ <- Browser.assertText(Selector.id("v"), "0")
            yield succeed
        }
    }

end StyleBrowserTest
