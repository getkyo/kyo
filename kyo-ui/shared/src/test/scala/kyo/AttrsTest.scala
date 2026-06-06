package kyo

import kyo.Browser.*
import kyo.Length.*
import kyo.UI.*
import kyo.UI.Ast.*
import scala.language.implicitConversions

class AttrsTest extends UITest:

    // ---- id ----

    "id findable" in {
        withUI(UI.div("X").id("myDiv")) {
            for
                _ <- Browser.assertVisible(Selector.id("myDiv"))
                _ <- Browser.assertText(Selector.id("myDiv"), "X")
            yield ()
        }
    }

    "two ids both findable" in {
        withUI(UI.div(UI.button("A").id("a"), UI.span("B").id("b"))) {
            for
                _ <- Browser.assertText(Selector.id("a"), "A")
                _ <- Browser.assertText(Selector.id("b"), "B")
            yield ()
        }
    }

    // ---- hidden ----

    "hidden true" in {
        withUI(UI.div(UI.div("content").hidden(true).id("d"))) {
            for
                _ <- Browser.assertAttribute(Selector.id("d"), "hidden", "")
            yield ()
        }
    }

    "hidden false" in {
        withUI(UI.div(UI.div("content").hidden(false).id("d"))) {
            for
                _ <- Browser.assertText(Selector.id("d"), "content")
            yield ()
        }
    }

    "no hidden set" in {
        withUI(UI.div(UI.div("content").id("d"))) {
            for
                _ <- Browser.assertText(Selector.id("d"), "content")
            yield ()
        }
    }

    "hidden signal toggle" in {
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
            yield ()
        }
    }

    // ---- style ----

    "style bold" in {
        withUI(UI.div(UI.div("X").style(Style.bold).id("d"))) {
            Browser.assertAttributeSatisfies(Selector.id("d"), "style", "ignore")(_.contains("font-weight")).unit
        }
    }

    "style italic" in {
        withUI(UI.div(UI.div("X").style(Style.italic).id("d"))) {
            Browser.assertText(Selector.id("d"), "X").unit
        }
    }

    "style bg color" in {
        withUI(UI.div(UI.div("X").style(Style.bg(Style.Color.red)).id("d"))) {
            Browser.assertText(Selector.id("d"), "X").unit
        }
    }

    "style width" in {
        withUI(UI.div(UI.div.style(Style.width(Length.Px(200))).id("d"))) {
            Browser.assertAttributeSatisfies(Selector.id("d"), "style", "ignore")(_.contains("200px")).unit
        }
    }

    "style row" in {
        withUI(UI.div(UI.div.style(Style.row).id("d"))) {
            Browser.assertAttributeSatisfies(Selector.id("d"), "style", "ignore")(_.contains("flex-direction: row")).unit
        }
    }

    "style composed bold + italic" in {
        withUI(UI.div(UI.div("X").style(Style.bold ++ Style.italic).id("d"))) {
            for
                _ <- Browser.assertAttributeSatisfies(Selector.id("d"), "style", "ignore")(_.contains("font-weight"))
                _ <- Browser.assertAttributeSatisfies(Selector.id("d"), "style", "ignore")(_.contains("font-style"))
            yield ()
        }
    }

    "empty style no attribute" in {
        withUI(UI.div(UI.div.id("d"))) {
            for
                _ <- Browser.assertAttributeSatisfies(Selector.id("d"), "style", "no style attribute")(_.isEmpty)
            yield ()
        }
    }

    // ---- Merged from ReactiveAttrsTest ----

    "style signal initial" in {
        val app: UI < Async =
            for ref <- Signal.initRef(Style.bold)
            yield UI.div(ref.map(s => UI.div("X").style(s).id("d")))
        withUI(app) {
            for
                _ <- Browser.assertAttributeSatisfies(Selector.id("d"), "style", "ignore")(_.contains("font-weight"))
                _ <- Browser.assertText(Selector.id("d"), "X")
            yield ()
        }
    }

    "style signal toggle bold to italic" in {
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
            yield ()
        }
    }

    "style signal toggle on off" in {
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
            yield ()
        }
    }

    "style lambda" in {
        withUI(UI.div(UI.div("X").style(s => s.bold ++ s.italic).id("d"))) {
            for
                _ <- Browser.assertAttributeSatisfies(Selector.id("d"), "style", "ignore")(_.contains("font-weight"))
                _ <- Browser.assertAttributeSatisfies(Selector.id("d"), "style", "ignore")(_.contains("font-style"))
            yield ()
        }
    }

    "radio checked signal" in {
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
            yield ()
        }
    }

    "hidden signal true not rendered" in {
        val app: UI < Async =
            for ref <- Signal.initRef(true)
            yield UI.div(ref.map(h => UI.div("hidden content").id("d").hidden(h)))
        withUI(app) {
            Browser.assertAttribute(Selector.id("d"), "hidden", "").unit
        }
    }

    "hidden signal toggle (reactive)" in {
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
            yield ()
        }
    }

    "hidden signal with interactive child" in {
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
            yield ()
        }
    }

    "anchor href signal initial" in {
        val app: UI < Async =
            for ref <- Signal.initRef(Href.Absolute(HttpUrl.parse("https://example.com").getOrThrow): Href)
            yield UI.div(ref.map(url => UI.a.href(url).id("a")("Link")))
        withUI(app) {
            for
                _ <- Browser.assertAttributeSatisfies(Selector.id("a"), "href", "ignore")(_.contains("example.com"))
                _ <- Browser.assertText(Selector.id("a"), "Link")
            yield ()
        }
    }

    "anchor href signal change" in {
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
            yield ()
        }
    }

    "img src signal initial" in {
        val app: UI < Async =
            for ref <- Signal.initRef(ImgSrc.Path("img1.png"): ImgSrc)
            yield UI.div(ref.map(s => UI.img(s, "photo").id("i")))
        withUI(app) {
            Browser.assertAttributeSatisfies(Selector.id("i"), "src", "ignore")(_.contains("img1.png")).unit
        }
    }

    "img src signal change" in {
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
            yield ()
        }
    }

    "opt selected true" in {
        withUI(UI.div(UI.select.id("s")(
            UI.option.value("a")("A"),
            UI.option.value("b").selected(true)("B")
        ))) {
            Browser.assertAttribute(Selector.id("s"), "value", "b").unit
        }
    }

    "opt selected signal toggle" in {
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
            yield ()
        }
    }

end AttrsTest
