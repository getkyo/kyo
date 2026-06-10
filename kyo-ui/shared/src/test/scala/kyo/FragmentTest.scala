package kyo

import kyo.Browser.*
import scala.language.implicitConversions

class FragmentTest extends UITest:

    "fragment transparent" in {
        withUI(UI.div(UI.fragment(UI.span("a").id("a"), UI.span("b").id("b"))).id("d")) {
            for
                _ <- Browser.assertText(Selector.id("a"), "a")
                _ <- Browser.assertText(Selector.id("b"), "b")
            yield ()
        }
    }

    "UI.empty no children" in {
        withUI(UI.div(UI.empty, UI.span("x").id("x")).id("d")) {
            Browser.assertText(Selector.id("x"), "x").unit
        }
    }

    "empty fragment" in {
        withUI(UI.div(UI.fragment()).id("d")) {
            Browser.assertExists(Selector.id("d")).unit
        }
    }

    "nested fragment" in {
        // A nested fragment is transparent: a fragment nested inside another fragment renders all
        // children as siblings in the parent div. The inner fragment(a, b) produces Fragment[SpanElement];
        // nesting it as fragment(fragment(a, b)) keeps a, b at the div level, and sibling c renders alongside.
        withUI(UI.div(UI.fragment(UI.fragment(UI.span("a").id("a"), UI.span("b").id("b"))), UI.span("c").id("c")).id("d")) {
            for
                _ <- Browser.assertText(Selector.id("a"), "a")
                _ <- Browser.assertText(Selector.id("b"), "b")
                _ <- Browser.assertText(Selector.id("c"), "c")
            yield ()
        }
    }

end FragmentTest
