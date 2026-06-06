package kyo

import kyo.Browser.*
import kyo.Length.*
import kyo.UI.*
import kyo.UI.Ast.*
import scala.language.implicitConversions

class AnchorTest extends UITest:

    "anchor renders with text" in {
        withUI(UI.div(UI.a.href(Href.Absolute(HttpUrl.parse("https://example.com").getOrThrow))("Link").id("a"))) {
            Browser.assertText(Selector.id("a"), "Link").unit
        }
    }

    "anchor href attribute" in {
        withUI(UI.div(UI.a.href(Href.Absolute(HttpUrl.parse("https://example.com").getOrThrow)).id("a"))) {
            Browser.assertAttributeSatisfies(Selector.id("a"), "href", "ignore")(_.contains("example.com")).unit
        }
    }

    "anchor target blank" in {
        withUI(UI.div(UI.a.href(Href.Path("url"), UI.Target.Blank).id("a"))) {
            Browser.assertAttribute(Selector.id("a"), "target", "_blank").unit
        }
    }

    "anchor target self" in {
        withUI(UI.div(UI.a.href(Href.Path("url")).target(UI.Target.Self).id("a"))) {
            Browser.assertAttribute(Selector.id("a"), "target", "_self").unit
        }
    }

    "anchor target parent" in {
        withUI(UI.div(UI.a.href(Href.Path("url")).target(UI.Target.Parent).id("a"))) {
            Browser.assertAttribute(Selector.id("a"), "target", "_parent").unit
        }
    }

    "anchor target top" in {
        withUI(UI.div(UI.a.href(Href.Path("url")).target(UI.Target.Top).id("a"))) {
            Browser.assertAttribute(Selector.id("a"), "target", "_top").unit
        }
    }

    "anchor no target" in {
        withUI(UI.div(UI.a.href(Href.Path("url")).id("a"))) {
            for
                _ <- Browser.assertAttributeSatisfies(Selector.id("a"), "target", "no target attribute")(_.isEmpty)
            yield ()
        }
    }

    "anchor onClick" in {
        val app: UI < Async =
            for ref <- Signal.initRef(false)
            yield UI.div(
                UI.a.href(Href.Fragment("")).id("a").onClick(ref.set(true))("Click"),
                ref.map(v => UI.span(v.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("a"))
                _ <- Browser.assertText(Selector.id("v"), "true")
            yield ()
        }
    }

    "anchor focus" in {
        withUI(UI.div(UI.a.href(Href.Fragment("")).id("a")("Link"))) {
            for
                _ <- Browser.click(Selector.id("a"))
                _ <- Browser.assertVisible(Selector.id("a"))
            yield ()
        }
    }

    "anchor onFocus fires" in {
        val app: UI < Async =
            for ref <- Signal.initRef(false)
            yield UI.div(
                UI.a.href(Href.Fragment("")).id("a").onFocus(ref.set(true))("Link"),
                ref.map(v => UI.span(v.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("a"))
                _ <- Browser.assertText(Selector.id("v"), "true")
            yield ()
        }
    }

    "anchor onBlur fires" in {
        val app: UI < Async =
            for ref <- Signal.initRef(false)
            yield UI.div(
                UI.a.href(Href.Fragment("")).id("a").onBlur(ref.set(true))("Link"),
                UI.button("Other").id("b"),
                ref.map(v => UI.span(v.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("a"))
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("v"), "true")
            yield ()
        }
    }

    "pressKey Enter on anchor fires onClick" in {
        val app: UI < Async =
            for ref <- Signal.initRef(false)
            yield UI.div(
                UI.a.href(Href.Fragment("")).id("a").onClick(ref.set(true))("Click"),
                ref.map(v => UI.span(v.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("a"), Key.Enter)
                _ <- Browser.assertText(Selector.id("v"), "true")
            yield ()
        }
    }

end AnchorTest
