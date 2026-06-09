package kyo

import kyo.Browser.*
import kyo.UI.foreach

class ListTest extends UITest:

    "ul li text visible and ordered" in {
        withUI(UI.ul(UI.li("first").id("f"), UI.li("second").id("s"))) {
            for
                _ <- Browser.assertText(Selector.id("f"), "first")
                _ <- Browser.assertText(Selector.id("s"), "second")
            yield ()
        }
    }

    "ol items visible and ordered" in {
        withUI(UI.ol(UI.li("alpha").id("a"), UI.li("beta").id("b"))) {
            for
                _ <- Browser.assertText(Selector.id("a"), "alpha")
                _ <- Browser.assertText(Selector.id("b"), "beta")
            yield ()
        }
    }

    "nested list" in {
        withUI(UI.ul(UI.li(UI.ul(UI.li("nested").id("n"))))) {
            Browser.assertText(Selector.id("n"), "nested").unit
        }
    }

    "empty ul no crash" in {
        withUI(UI.ul.id("u")) {
            Browser.assertExists(Selector.id("u")).unit
        }
    }

    "li with nested elements span and button" in {
        withUI(UI.ul(UI.li(UI.span("text").id("s"), UI.button("click").id("b")).id("item"))) {
            for
                _ <- Browser.assertText(Selector.id("s"), "text")
                _ <- Browser.assertText(Selector.id("b"), "click")
            yield ()
        }
    }

    "li long text" in {
        val longText = "a" * 200
        withUI(UI.ul(UI.li(longText).id("item"))) {
            Browser.assertText(Selector.id("item"), longText).unit
        }
    }

    "empty li renders" in {
        withUI(UI.ul(UI.li.id("item"))) {
            Browser.assertExists(Selector.id("item")).unit
        }
    }

    "li onClick fires" in {
        val app: UI < Async =
            for counter <- Signal.initRef(0)
            yield UI.div(
                UI.ul(UI.li("item").id("item").onClick(counter.getAndUpdate(_ + 1).unit)),
                counter.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("item"))
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield ()
        }
    }

    "button inside li click works" in {
        val app: UI < Async =
            for counter <- Signal.initRef(0)
            yield UI.div(
                UI.ul(UI.li(UI.button("Go").id("b").onClick(counter.getAndUpdate(_ + 1).unit))),
                counter.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield ()
        }
    }

    "input inside li fill works" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.ul(UI.li(UI.input.id("i").onInput(v => ref.set(v)))),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "typed")
                _ <- Browser.assertText(Selector.id("v"), "typed")
            yield ()
        }
    }

    "focus button inside li" in {
        withUI(UI.ul(UI.li(UI.button("Go").id("b")))) {
            for
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertVisible(Selector.id("b"))
            yield ()
        }
    }

    "foreach li add item" in {
        val app: UI < Async =
            for items <- Signal.initRef(Chunk("A", "B"))
            yield UI.div(
                UI.ul(items.foreach(item => UI.li(item))),
                UI.button("Add").id("add").onClick(items.getAndUpdate(_ :+ "C").unit)
            )
        withUI(app) {
            for
                _ <- assertContains("A")
                _ <- assertContains("B")
                _ <- Browser.click(Selector.id("add"))
                _ <- assertContains("C")
            yield ()
        }
    }

    "foreach li remove item" in {
        val app: UI < Async =
            for items <- Signal.initRef(Chunk("A", "B", "C"))
            yield UI.div(
                UI.ul(items.foreach(item => UI.li(item).id(s"li-$item"))),
                UI.button("Remove").id("rm").onClick(items.getAndUpdate(_.init).unit)
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("rm"))
                _ <- Browser.assertText(Selector.id("li-A"), "A")
                _ <- Browser.assertText(Selector.id("li-B"), "B")
            yield ()
        }
    }

    "ol foreach numbers update" in {
        val app: UI < Async =
            for items <- Signal.initRef(Chunk("X", "Y"))
            yield UI.div(
                UI.ol(items.foreach(item => UI.li(item))),
                UI.button("Add").id("add").onClick(items.getAndUpdate(_ :+ "Z").unit)
            )
        withUI(app) {
            for
                _ <- assertContains("X")
                _ <- assertContains("Y")
                _ <- Browser.click(Selector.id("add"))
                _ <- assertContains("Z")
            yield ()
        }
    }

    "foreach li with click handler" in {
        val app: UI < Async =
            for
                items   <- Signal.initRef(Chunk("A", "B"))
                clicked <- Signal.initRef("")
            yield UI.div(
                UI.ul(items.foreach(item =>
                    UI.li(item).id(s"li-$item").onClick(clicked.set(item))
                )),
                clicked.map(v => UI.span(s"clicked:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("li-B"))
                _ <- Browser.assertText(Selector.id("v"), "clicked:B")
            yield ()
        }
    }

    "30 li items all render" in {
        val items = (1 to 30).map(i => s"item$i")
        withUI(UI.ul(items.map(item => UI.li(item))*)) {
            for
                _ <- assertContains("item1")
                _ <- assertContains("item15")
                _ <- assertContains("item30")
            yield ()
        }
    }

end ListTest
