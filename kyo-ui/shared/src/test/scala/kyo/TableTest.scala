package kyo

import kyo.Browser.*
import kyo.UI.foreach
import scala.language.implicitConversions

class TableTest extends UITest:

    "table exists" in {
        withUI(UI.div(UI.table(UI.tr(UI.td("A"))).id("t"))) {
            Browser.assertVisible(Selector.id("t")).unit
        }
    }

    "table cell text" in {
        withUI(UI.table(UI.tr(UI.td("Hello").id("c")))) {
            Browser.assertText(Selector.id("c"), "Hello").unit
        }
    }

    "th text" in {
        withUI(UI.table(UI.tr(UI.th("Header").id("h")))) {
            Browser.assertText(Selector.id("h"), "Header").unit
        }
    }

    "td colspan attribute" in {
        withUI(UI.div(UI.table(UI.tr(UI.td("W").colspan(2).id("c"))))) {
            Browser.assertAttribute(Selector.id("c"), "colspan", "2").unit
        }
    }

    "td rowspan attribute" in {
        withUI(UI.div(UI.table(UI.tr(UI.td("T").rowspan(3).id("c"))))) {
            Browser.assertAttribute(Selector.id("c"), "rowspan", "3").unit
        }
    }

    "td colspan and rowspan" in {
        withUI(UI.div(UI.table(UI.tr(UI.td("X").colspan(2).rowspan(3).id("c"))))) {
            for
                _ <- Browser.assertAttribute(Selector.id("c"), "colspan", "2")
                _ <- Browser.assertAttribute(Selector.id("c"), "rowspan", "3")
            yield ()
        }
    }

    "header + data rows visible" in {
        val table = UI.table(
            UI.tr(UI.th("Name").id("hN"), UI.th("Age").id("hA")),
            UI.tr(UI.td("Alice").id("a"), UI.td("30").id("a30")),
            UI.tr(UI.td("Bob").id("b"), UI.td("25").id("b25"))
        )
        withUI(table) {
            for
                _ <- Browser.assertText(Selector.id("hN"), "Name")
                _ <- Browser.assertText(Selector.id("hA"), "Age")
                _ <- Browser.assertText(Selector.id("a"), "Alice")
                _ <- Browser.assertText(Selector.id("a30"), "30")
                _ <- Browser.assertText(Selector.id("b"), "Bob")
                _ <- Browser.assertText(Selector.id("b25"), "25")
            yield ()
        }
    }

    "label for attribute" in {
        withUI(UI.div(UI.label("Name:").forId("inp").id("l"), UI.input.id("inp"))) {
            Browser.assertAttribute(Selector.id("l"), "for", "inp").unit
        }
    }

    "empty cells no crash" in {
        withUI(UI.table(UI.tr(UI.td.id("c1"), UI.td.id("c2")))) {
            for
                _ <- Browser.assertExists(Selector.id("c1"))
                _ <- Browser.assertExists(Selector.id("c2"))
            yield ()
        }
    }

    "td onClick fires" in {
        val app: UI < Async =
            for counter <- Signal.initRef(0)
            yield UI.div(
                UI.table(UI.tr(UI.td("Click").id("c").onClick(counter.getAndUpdate(_ + 1).unit))),
                counter.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("c"))
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield ()
        }
    }

    "tr onClick click on td bubbles" in {
        val app: UI < Async =
            for counter <- Signal.initRef(0)
            yield UI.div(
                UI.table(UI.tr.id("r").onClick(counter.getAndUpdate(_ + 1).unit)(UI.td("Cell").id("c"))),
                counter.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("c"))
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield ()
        }
    }

    "button inside td click works" in {
        val app: UI < Async =
            for counter <- Signal.initRef(0)
            yield UI.div(
                UI.table(UI.tr(UI.td(UI.button("Go").id("b").onClick(counter.getAndUpdate(_ + 1).unit)))),
                counter.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield ()
        }
    }

    "input inside td fill works" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.table(UI.tr(UI.td(UI.input.id("i").onInput(v => ref.set(v))))),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "data")
                _ <- Browser.assertText(Selector.id("v"), "data")
            yield ()
        }
    }

    "foreach rows add remove" in {
        val app: UI < Async =
            for rows <- Signal.initRef(Chunk("A", "B"))
            yield UI.div(
                UI.table(rows.foreach(r => UI.tr(UI.td(r)))),
                UI.button("Add").id("add").onClick(rows.getAndUpdate(_ :+ "C").unit),
                UI.button("Remove").id("rm").onClick(rows.getAndUpdate(_.init).unit)
            )
        withUI(app) {
            for
                _ <- assertContains("A")
                _ <- assertContains("B")
                _ <- Browser.click(Selector.id("add"))
                _ <- assertContains("C")
                _ <- Browser.click(Selector.id("rm"))
                _ <- assertContains("A")
                _ <- assertContains("B")
            yield ()
        }
    }

    "reactive cell content signal updates" in {
        val app: UI < Async =
            for ref <- Signal.initRef("before")
            yield UI.div(
                UI.table(UI.tr(UI.td(ref.map(v => UI.span(v).id("cell"))))),
                UI.button("Change").id("b").onClick(ref.set("after"))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("cell"), "before")
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("cell"), "after")
            yield ()
        }
    }

    "label click focuses input" in {
        withUI(UI.div(UI.label("Name:").forId("inp").id("l"), UI.input.id("inp"))) {
            for
                _ <- Browser.click(Selector.id("l"))
                _ <- Browser.assertVisible(Selector.id("inp"))
            yield ()
        }
    }

end TableTest
