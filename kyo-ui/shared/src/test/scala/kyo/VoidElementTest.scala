package kyo

import kyo.Browser.*
import kyo.Length.*
import scala.language.implicitConversions

class VoidElementTest extends UITest:

    "hr exists" in run {
        withUI(UI.div(UI.span("above").id("a"), UI.hr.id("h"), UI.span("below").id("b"))) {
            for
                _ <- Browser.assertVisible(Selector.id("h"))
                _ <- Browser.assertText(Selector.id("a"), "above")
                _ <- Browser.assertText(Selector.id("b"), "below")
            yield succeed
        }
    }

    "br exists" in run {
        withUI(UI.div(UI.span("a").id("a"), UI.br.id("br"), UI.span("c").id("c"))) {
            for
                _ <- Browser.assertExists(Selector.id("br"))
                _ <- Browser.assertText(Selector.id("a"), "a")
                _ <- Browser.assertText(Selector.id("c"), "c")
            yield succeed
        }
    }

    "hidden input type" in run {
        withUI(UI.div(UI.hiddenInput.value("secret").id("h"))) {
            Browser.assertAttribute(Selector.id("h"), "type", "hidden").andThen(succeed)
        }
    }

    "hr with style attribute" in run {
        withUI(UI.div(UI.hr.id("h").style(Style.border(1.px, Style.Color.black)))) {
            Browser.assertExists(Selector.id("h")).andThen(succeed)
        }
    }

    "br between text nodes both visible" in run {
        withUI(UI.div(UI.span("above").id("a"), UI.br, UI.span("below").id("b"))) {
            for
                _ <- Browser.assertText(Selector.id("a"), "above")
                _ <- Browser.assertText(Selector.id("b"), "below")
            yield succeed
        }
    }

end VoidElementTest
