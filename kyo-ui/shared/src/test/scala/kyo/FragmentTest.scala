package kyo

import kyo.Browser.*
import scala.language.implicitConversions

class FragmentTest extends UITest:

    "fragment transparent" in run {
        withUI(UI.div(UI.fragment(UI.span("a").id("a"), UI.span("b").id("b"))).id("d")) {
            for
                _ <- Browser.assertText(Selector.id("a"), "a")
                _ <- Browser.assertText(Selector.id("b"), "b")
            yield succeed
        }
    }

    "UI.empty no children" in run {
        withUI(UI.div(UI.empty, UI.span("x").id("x")).id("d")) {
            Browser.assertText(Selector.id("x"), "x").andThen(succeed)
        }
    }

    "empty fragment" in run {
        withUI(UI.div(UI.fragment()).id("d")) {
            Browser.assertExists(Selector.id("d")).andThen(succeed)
        }
    }

    "nested fragment" in run {
        withUI(UI.div(UI.fragment(UI.fragment(UI.span("a").id("a"), UI.span("b").id("b")), UI.span("c").id("c"))).id("d")) {
            for
                _ <- Browser.assertText(Selector.id("a"), "a")
                _ <- Browser.assertText(Selector.id("b"), "b")
                _ <- Browser.assertText(Selector.id("c"), "c")
            yield succeed
        }
    }

end FragmentTest
