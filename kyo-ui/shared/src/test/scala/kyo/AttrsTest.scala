package kyo

import kyo.Browser.*
import kyo.Length.*
import kyo.UI.*
import kyo.UI.Ast.*
import scala.language.implicitConversions

class AttrsTest extends UITest:

    // ---- id ----

    "id findable" in run {
        withUI(UI.div("X").id("myDiv")) {
            for
                _ <- Browser.assertVisible(Selector.id("myDiv"))
                _ <- Browser.assertText(Selector.id("myDiv"), "X")
            yield succeed
        }
    }

    "two ids both findable" in run {
        withUI(UI.div(UI.button("A").id("a"), UI.span("B").id("b"))) {
            for
                _ <- Browser.assertText(Selector.id("a"), "A")
                _ <- Browser.assertText(Selector.id("b"), "B")
            yield succeed
        }
    }

    // ---- hidden ----

    "hidden true" in run {
        withUI(UI.div(UI.div("content").hidden(true).id("d"))) {
            for
                _ <- Browser.assertAttribute(Selector.id("d"), "hidden", "")
            yield succeed
        }
    }

    "hidden false" in run {
        withUI(UI.div(UI.div("content").hidden(false).id("d"))) {
            for
                _ <- Browser.assertText(Selector.id("d"), "content")
            yield succeed
        }
    }

    "no hidden set" in run {
        withUI(UI.div(UI.div("content").id("d"))) {
            for
                _ <- Browser.assertText(Selector.id("d"), "content")
            yield succeed
        }
    }

    "hidden signal toggle" in run {
        val app: UI < Async =
            for flag <- Signal.initRef(false)
            yield UI.div(
                UI.button("Toggle").id("t").onClick(flag.getAndUpdate(!_).unit),
                UI.div("content").id("d").hidden(flag)
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("d"), "content")
                _ <- Browser.click(Selector.id("t"))
                _ <- Browser.assertAttribute(Selector.id("d"), "hidden", "")
            yield succeed
        }
    }

    // ---- style ----

    "style bold" in run {
        withUI(UI.div(UI.div("X").style(Style.bold).id("d"))) {
            Browser.assertAttributeSatisfies(Selector.id("d"), "style", "ignore")(_.contains("font-weight")).andThen(succeed)
        }
    }

    "style italic" in run {
        withUI(UI.div(UI.div("X").style(Style.italic).id("d"))) {
            Browser.assertText(Selector.id("d"), "X").andThen(succeed)
        }
    }

    "style bg color" in run {
        withUI(UI.div(UI.div("X").style(Style.bg(Style.Color.red)).id("d"))) {
            Browser.assertText(Selector.id("d"), "X").andThen(succeed)
        }
    }

    "style width" in run {
        withUI(UI.div(UI.div.style(Style.width(Length.Px(200))).id("d"))) {
            Browser.assertAttributeSatisfies(Selector.id("d"), "style", "ignore")(_.contains("200px")).andThen(succeed)
        }
    }

    "style row" in run {
        withUI(UI.div(UI.div.style(Style.row).id("d"))) {
            Browser.assertAttributeSatisfies(Selector.id("d"), "style", "ignore")(_.contains("flex-direction: row")).andThen(succeed)
        }
    }

    "style composed bold + italic" in run {
        withUI(UI.div(UI.div("X").style(Style.bold ++ Style.italic).id("d"))) {
            for
                _ <- Browser.assertAttributeSatisfies(Selector.id("d"), "style", "ignore")(_.contains("font-weight"))
                _ <- Browser.assertAttributeSatisfies(Selector.id("d"), "style", "ignore")(_.contains("font-style"))
            yield succeed
        }
    }

    "empty style no attribute" in run {
        withUI(UI.div(UI.div.id("d"))) {
            for
                _ <- Browser.assertAttributeSatisfies(Selector.id("d"), "style", "no style attribute")(_.isEmpty)
            yield succeed
        }
    }

    // ---- Merged from ReactiveAttrsTest ----

    "style signal initial" in run {
        val app: UI < Async =
            for ref <- Signal.initRef(Style.bold)
            yield UI.div(ref.map(s => UI.div("X").style(s).id("d")))
        withUI(app) {
            for
                _ <- Browser.assertAttributeSatisfies(Selector.id("d"), "style", "ignore")(_.contains("font-weight"))
                _ <- Browser.assertText(Selector.id("d"), "X")
            yield succeed
        }
    }

    "style signal toggle bold to italic" in run {
        val app: UI < Async =
            for ref <- Signal.initRef(Style.bold)
            yield UI.div(
                UI.button("Toggle").id("t").onClick(ref.set(Style.italic)),
                ref.map(s => UI.div("X").style(s).id("d"))
            )
        withUI(app) {
            for
                _ <- Browser.assertAttributeSatisfies(Selector.id("d"), "style", "ignore")(_.contains("font-weight"))
                _ <- Browser.click(Selector.id("t"))
                _ <- Browser.assertAttributeSatisfies(Selector.id("d"), "style", "ignore")(_.contains("font-style"))
            yield succeed
        }
    }

    "style signal toggle on off" in run {
        val app: UI < Async =
            for ref <- Signal.initRef(Style.bold)
            yield UI.div(
                UI.button("Off").id("t").onClick(ref.set(Style.empty)),
                ref.map(s => UI.div("X").style(s).id("d"))
            )
        withUI(app) {
            for
                _ <- Browser.assertAttributeSatisfies(Selector.id("d"), "style", "ignore")(_.nonEmpty)
                _ <- Browser.click(Selector.id("t"))
                _ <- Browser.assertAttributeSatisfies(Selector.id("d"), "style", "ignore")(_.isEmpty)
            yield succeed
        }
    }

    "style lambda" in run {
        withUI(UI.div(UI.div("X").style(s => s.bold ++ s.italic).id("d"))) {
            for
                _ <- Browser.assertAttributeSatisfies(Selector.id("d"), "style", "ignore")(_.contains("font-weight"))
                _ <- Browser.assertAttributeSatisfies(Selector.id("d"), "style", "ignore")(_.contains("font-style"))
            yield succeed
        }
    }

    "radio checked signal" in run {
        val app: UI < Async =
            for flag <- Signal.initRef(false)
            yield UI.div(
                UI.button("Check").id("t").onClick(flag.set(true)),
                flag.map(v => UI.radio.checked(v).id("r"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("t"))
                _ <- Browser.assertChecked(Selector.id("r"))
            yield succeed
        }
    }

    "hidden signal true not rendered" in run {
        val app: UI < Async =
            for ref <- Signal.initRef(true)
            yield UI.div(ref.map(h => UI.div("hidden content").id("d").hidden(h)))
        withUI(app) {
            Browser.assertAttribute(Selector.id("d"), "hidden", "").andThen(succeed)
        }
    }

    "hidden signal toggle (reactive)" in run {
        val app: UI < Async =
            for flag <- Signal.initRef(false)
            yield UI.div(
                UI.button("Toggle").id("t").onClick(flag.getAndUpdate(!_).unit),
                flag.map(h => UI.div("content").id("d").hidden(h))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("d"), "content")
                _ <- Browser.click(Selector.id("t"))
                _ <- Browser.assertAttribute(Selector.id("d"), "hidden", "")
                _ <- Browser.click(Selector.id("t"))
                _ <- Browser.assertText(Selector.id("d"), "content")
            yield succeed
        }
    }

    "hidden signal with interactive child" in run {
        val app: UI < Async =
            for
                hidden  <- Signal.initRef(true)
                counter <- Signal.initRef(0)
            yield UI.div(
                UI.button("Show").id("show").onClick(hidden.set(false)),
                UI.when(hidden.map(!_))(UI.button("Click").id("btn").onClick(counter.getAndUpdate(_ + 1).unit)),
                counter.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("show"))
                _ <- Browser.click(Selector.id("btn"))
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield succeed
        }
    }

    "anchor href signal initial" in run {
        val app: UI < Async =
            for ref <- Signal.initRef(Href.Absolute(HttpUrl.parse("https://example.com").getOrThrow): Href)
            yield UI.div(ref.map(url => UI.a.href(url).id("a")("Link")))
        withUI(app) {
            for
                _ <- Browser.assertAttributeSatisfies(Selector.id("a"), "href", "ignore")(_.contains("example.com"))
                _ <- Browser.assertText(Selector.id("a"), "Link")
            yield succeed
        }
    }

    "anchor href signal change" in run {
        val app: UI < Async =
            for ref <- Signal.initRef(Href.Absolute(HttpUrl.parse("https://old.com").getOrThrow): Href)
            yield UI.div(
                UI.button("Change").id("t").onClick(ref.set(Href.Absolute(HttpUrl.parse("https://new.com").getOrThrow))),
                ref.map(url => UI.a.href(url).id("a")("Link"))
            )
        withUI(app) {
            for
                _ <- Browser.assertAttributeSatisfies(Selector.id("a"), "href", "ignore")(_.contains("old.com"))
                _ <- Browser.click(Selector.id("t"))
                _ <- Browser.assertAttributeSatisfies(Selector.id("a"), "href", "ignore")(_.contains("new.com"))
            yield succeed
        }
    }

    "img src signal initial" in run {
        val app: UI < Async =
            for ref <- Signal.initRef(ImgSrc.Path("img1.png"): ImgSrc)
            yield UI.div(ref.map(s => UI.img(s, "photo").id("i")))
        withUI(app) {
            Browser.assertAttributeSatisfies(Selector.id("i"), "src", "ignore")(_.contains("img1.png")).andThen(succeed)
        }
    }

    "img src signal change" in run {
        val app: UI < Async =
            for ref <- Signal.initRef(ImgSrc.Path("img1.png"): ImgSrc)
            yield UI.div(
                UI.button("Change").id("t").onClick(ref.set(ImgSrc.Path("img2.png"))),
                ref.map(s => UI.img(s, "photo").id("i"))
            )
        withUI(app) {
            for
                _ <- Browser.assertAttributeSatisfies(Selector.id("i"), "src", "ignore")(_.contains("img1.png"))
                _ <- Browser.click(Selector.id("t"))
                _ <- Browser.assertAttributeSatisfies(Selector.id("i"), "src", "ignore")(_.contains("img2.png"))
            yield succeed
        }
    }

    "opt selected true" in run {
        withUI(UI.div(UI.select.id("s")(
            UI.option.value("a")("A"),
            UI.option.value("b").selected(true)("B")
        ))) {
            Browser.assertAttribute(Selector.id("s"), "value", "b").andThen(succeed)
        }
    }

    "opt selected signal toggle" in run {
        val app: UI < Async =
            for flag <- Signal.initRef(false)
            yield UI.div(
                UI.button("Select").id("t").onClick(flag.set(true)),
                flag.map { v =>
                    UI.select.id("s")(
                        UI.option.value("a")("A"),
                        UI.option.value("b").selected(v)("B")
                    )
                }
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("t"))
                _ <- Browser.assertAttribute(Selector.id("s"), "value", "b")
            yield succeed
        }
    }

end AttrsTest
