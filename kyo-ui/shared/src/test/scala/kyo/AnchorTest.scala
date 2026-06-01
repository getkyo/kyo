package kyo

import kyo.Browser.*
import kyo.Length.*
import kyo.UI.*
import kyo.UI.Ast.*
import scala.language.implicitConversions

class AnchorTest extends UITest:

    "anchor renders with text" in run {
        withUI(UI.div(UI.a.href(Href.Absolute(HttpUrl.parse("https://example.com").getOrThrow))("Link").id("a"))) {
            Browser.assertText(Selector.id("a"), "Link").andThen(succeed)
        }
    }

    "anchor href attribute" in run {
        withUI(UI.div(UI.a.href(Href.Absolute(HttpUrl.parse("https://example.com").getOrThrow)).id("a"))) {
            Browser.assertAttributeSatisfies(Selector.id("a"), "href", "ignore")(_.contains("example.com")).andThen(succeed)
        }
    }

    "anchor target blank" in run {
        withUI(UI.div(UI.a.href(Href.Path("url"), UI.Target.Blank).id("a"))) {
            Browser.assertAttribute(Selector.id("a"), "target", "_blank").andThen(succeed)
        }
    }

    "anchor target self" in run {
        withUI(UI.div(UI.a.href(Href.Path("url")).target(UI.Target.Self).id("a"))) {
            Browser.assertAttribute(Selector.id("a"), "target", "_self").andThen(succeed)
        }
    }

    "anchor target parent" in run {
        withUI(UI.div(UI.a.href(Href.Path("url")).target(UI.Target.Parent).id("a"))) {
            Browser.assertAttribute(Selector.id("a"), "target", "_parent").andThen(succeed)
        }
    }

    "anchor target top" in run {
        withUI(UI.div(UI.a.href(Href.Path("url")).target(UI.Target.Top).id("a"))) {
            Browser.assertAttribute(Selector.id("a"), "target", "_top").andThen(succeed)
        }
    }

    "anchor no target" in run {
        withUI(UI.div(UI.a.href(Href.Path("url")).id("a"))) {
            for
                _ <- Browser.assertAttributeSatisfies(Selector.id("a"), "target", "no target attribute")(_.isEmpty)
            yield succeed
        }
    }

    "anchor onClick" in run {
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
            yield succeed
        }
    }

    "anchor focus" in run {
        withUI(UI.div(UI.a.href(Href.Fragment("")).id("a")("Link"))) {
            for
                _ <- Browser.click(Selector.id("a"))
                _ <- Browser.assertVisible(Selector.id("a"))
            yield succeed
        }
    }

    "anchor onFocus fires" in run {
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
            yield succeed
        }
    }

    "anchor onBlur fires" in run {
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
            yield succeed
        }
    }

    "pressKey Enter on anchor fires onClick" in run {
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
            yield succeed
        }
    }

end AnchorTest
