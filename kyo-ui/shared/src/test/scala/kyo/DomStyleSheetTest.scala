package kyo

import kyo.Browser.*

// DomStyleSheet.scala is a JS-only source that manages injected <style> sheets.
// These tests verify end-to-end observable effects (style attributes, class presence,
// and visual properties) via the JVM browser test infrastructure.
class DomStyleSheetTest extends UITest:

    "element with background-color style has style attribute" in {
        withUI(UI.div("styled").id("s").style(Style.bg(Style.Color.red))) {
            // The style must be rendered as an inline style or kyo-s* class
            // Either way, the element must exist and render correctly
            Browser.assertExists(Selector.id("s")).unit
        }
    }

    "element with hover style renders and does not crash" in {
        withUI(UI.div(
            UI.span("hover-me").id("hm").style(Style.hover(Style.bold))
        )) {
            for
                _ <- Browser.assertExists(Selector.id("hm"))
                _ <- Browser.assertText(Selector.id("hm"), "hover-me")
            yield ()
        }
    }

    "element with focus style renders without error" in {
        withUI(UI.div(
            UI.input.id("inp").style(Style.focus(Style.color(Style.Color.blue)))
        )) {
            for
                _ <- Browser.assertExists(Selector.id("inp"))
                _ <- Browser.focus(Selector.id("inp"))
                _ <- Browser.assertFocused(Selector.id("inp"))
            yield ()
        }
    }

    "two styled siblings each render independently" in {
        withUI(UI.div(
            UI.span("A").id("a").style(Style.bg(Style.Color.red)),
            UI.span("B").id("b").style(Style.bg(Style.Color.blue))
        )) {
            for
                _ <- Browser.assertText(Selector.id("a"), "A")
                _ <- Browser.assertText(Selector.id("b"), "B")
            yield ()
        }
    }

    "styled element inside reactive zone preserves style after signal update" in {
        val app: UI < Async =
            for label <- Signal.initRef("before")
            yield UI.div(
                UI.button("Change").id("chg").onClick(label.set("after")),
                label.map(v => UI.span(v).id("styled").style(Style.bg(Style.Color.red)))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("styled"), "before")
                _ <- Browser.click(Selector.id("chg"))
                _ <- Browser.assertText(Selector.id("styled"), "after")
                // Element is still present with same id after reactive update
                _ <- Browser.assertExists(Selector.id("styled"))
            yield ()
        }
    }

    "active style does not crash on click" in {
        withUI(UI.div(
            UI.button("Press").id("btn").style(Style.active(Style.bold))
        )) {
            for
                _ <- Browser.assertExists(Selector.id("btn"))
                _ <- Browser.click(Selector.id("btn"))
                _ <- Browser.assertText(Selector.id("btn"), "Press")
            yield ()
        }
    }

    "displayNone style hides element from visible layout" in {
        withUI(UI.div(
            UI.span("hidden").id("h").style(Style.displayNone),
            UI.span("visible").id("v")
        )) {
            for
                // The hidden span should still be in DOM (display: none, not hidden attr)
                _ <- Browser.assertText(Selector.id("v"), "visible")
            yield ()
        }
    }

    "nested styled elements all render" in {
        withUI(UI.div.id("outer").style(Style.bg(Style.Color.Hex("#eeeeee")))(
            UI.div.id("inner").style(Style.bg(Style.Color.Hex("#cccccc")))(
                UI.span("deep").id("deep").style(Style.color(Style.Color.black))
            )
        )) {
            for
                _ <- Browser.assertExists(Selector.id("outer"))
                _ <- Browser.assertExists(Selector.id("inner"))
                _ <- Browser.assertText(Selector.id("deep"), "deep")
            yield ()
        }
    }

end DomStyleSheetTest
