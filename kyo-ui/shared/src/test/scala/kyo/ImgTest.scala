package kyo

import kyo.Browser.*
import kyo.Length.*
import kyo.UI.*
import kyo.UI.Ast.*

class ImgTest extends UITest:

    "img src attribute" in {
        withUI(UI.div(UI.img(ImgSrc.Path("photo.jpg"), "A photo").id("i"))) {
            Browser.assertAttributeSatisfies(Selector.id("i"), "src", "ignore")(_.contains("photo.jpg")).unit
        }
    }

    "img alt attribute" in {
        withUI(UI.div(UI.img(ImgSrc.Path("photo.jpg"), "A photo").id("i"))) {
            Browser.assertAttribute(Selector.id("i"), "alt", "A photo").unit
        }
    }

    "img src signal initial" in {
        val app: UI < Async =
            for sig <- Signal.initRef(ImgSrc.Path("initial.jpg"): ImgSrc)
            yield UI.div(
                sig.map(s => UI.img(s, "photo").id("i"))
            )
        withUI(app) {
            Browser.assertAttributeSatisfies(Selector.id("i"), "src", "ignore")(_.contains("initial.jpg")).unit
        }
    }

    "img src signal change" in {
        val app: UI < Async =
            for sig <- Signal.initRef(ImgSrc.Path("old.jpg"): ImgSrc)
            yield UI.div(
                sig.map(s => UI.img(s, "photo").id("i")),
                UI.button("Change").id("b").onClick(sig.set(ImgSrc.Path("new.jpg")))
            )
        withUI(app) {
            for
                _ <- Browser.assertAttributeSatisfies(Selector.id("i"), "src", "ignore")(_.contains("old.jpg"))
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertAttributeSatisfies(Selector.id("i"), "src", "ignore")(_.contains("new.jpg"))
            yield ()
        }
    }

    "img long alt text" in {
        val longAlt = "A" * 200
        withUI(UI.div(UI.img(ImgSrc.Path("photo.jpg"), longAlt).id("i"))) {
            Browser.assertAttributeSatisfies(Selector.id("i"), "alt", "ignore")(_.contains("AAAA")).unit
        }
    }

    "img inside anchor click bubbles" in {
        val app: UI < Async =
            for ref <- Signal.initRef(false)
            yield UI.div(
                UI.a.href(Href.Fragment("")).id("a").onClick(ref.set(true))(
                    UI.img(ImgSrc.Path("photo.jpg"), "photo").id("i")
                ),
                ref.map(v => UI.span(v.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("i"))
                _ <- Browser.assertText(Selector.id("v"), "true")
            yield ()
        }
    }

    "img no alt set" in {
        withUI(UI.div(UI.img(ImgSrc.Path("photo.jpg"), "").id("i"))) {
            Browser.assertAttributeSatisfies(Selector.id("i"), "src", "ignore")(_.contains("photo.jpg")).unit
        }
    }

    "img with style" in {
        withUI(UI.div(UI.img(ImgSrc.Path("photo.jpg"), "photo").id("i").style(Style.width(100.px)))) {
            Browser.assertAttributeSatisfies(Selector.id("i"), "style", "ignore")(_.contains("width")).unit
        }
    }

end ImgTest
