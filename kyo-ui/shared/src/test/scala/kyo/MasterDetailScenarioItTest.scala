package kyo

import kyo.Browser.*
import kyo.UI.foreach
import scala.language.implicitConversions

class MasterDetailScenarioItTest extends UITest:

    "click item shows detail" in {
        val app: UI < Async =
            for
                items    <- Signal.initRef(Chunk.from(Seq("Alice", "Bob", "Carol")))
                selected <- Signal.initRef("")
            yield UI.div(
                items.foreach(name => UI.button(name).id(s"item-$name").onClick(selected.set(name))),
                selected.map(v => UI.span(s"detail:$v").id("detail"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("item-Bob"))
                _ <- Browser.assertText(Selector.id("detail"), "detail:Bob")
                _ <- Browser.click(Selector.id("item-Alice"))
                _ <- Browser.assertText(Selector.id("detail"), "detail:Alice")
            yield ()
        }
    }

    "edit detail save updates list" in {
        val app: UI < Async =
            for
                items   <- Signal.initRef(Chunk.from(Seq("Alice", "Bob")))
                selIdx  <- Signal.initRef(-1)
                editVal <- Signal.initRef("")
            yield UI.div(
                items.foreach { name =>
                    UI.button(name).id(s"item-$name").onClick {
                        val idx = items.get.map(_.indexOf(name))
                        idx.map(i => selIdx.set(i).andThen(editVal.set(name)))
                    }
                },
                selIdx.map { idx =>
                    if idx >= 0 then
                        UI.div(
                            UI.input.id("edit").value(editVal),
                            UI.button("Save").id("save").onClick {
                                for
                                    i <- selIdx.get
                                    v <- editVal.get
                                    _ <- items.getAndUpdate(_.updated(i, v))
                                yield ()
                            }
                        )
                    else UI.span("Select an item")
                },
                items.map(is => UI.span(s"list:${is.toSeq.mkString(",")}").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("item-Alice"))
                _ <- Browser.fill(Selector.id("edit"), "Alicia")
                _ <- Browser.click(Selector.id("save"))
                _ <- Browser.assertText(Selector.id("v"), "list:Alicia,Bob")
            yield ()
        }
    }

    "add new item to list" in {
        val app: UI < Async =
            for
                items <- Signal.initRef(Chunk.from(Seq("A", "B")))
                input <- Signal.initRef("")
            yield UI.div(
                UI.input.id("new").value(input),
                UI.button("Add").id("add").onClick {
                    for
                        v <- input.get
                        _ <- items.getAndUpdate(_.appended(v))
                        _ <- input.set("")
                    yield ()
                },
                items.map(is => UI.span(s"count:${is.size}").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("new"), "C")
                _ <- Browser.click(Selector.id("add"))
                _ <- Browser.assertText(Selector.id("v"), "count:3")
            yield ()
        }
    }

    "delete item from list" in {
        val app: UI < Async =
            for items <- Signal.initRef(Chunk.from(Seq("A", "B", "C")))
            yield UI.div(
                UI.button("Remove B").id("rm").onClick(items.getAndUpdate(_.filter(_ != "B")).unit),
                items.map(is => UI.span(s"list:${is.toSeq.mkString(",")}").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("rm"))
                _ <- Browser.assertText(Selector.id("v"), "list:A,C")
            yield ()
        }
    }

    "20 items all clickable" in {
        val app: UI < Async =
            for
                items    <- Signal.initRef(Chunk.from(0 until 20))
                selected <- Signal.initRef(-1)
            yield UI.div(
                items.foreach(i => UI.button(s"$i").id(s"item-$i").onClick(selected.set(i))),
                selected.map(v => UI.span(s"sel:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("item-0"))
                _ <- Browser.assertText(Selector.id("v"), "sel:0")
                _ <- Browser.click(Selector.id("item-19"))
                _ <- Browser.assertText(Selector.id("v"), "sel:19")
                _ <- Browser.click(Selector.id("item-10"))
                _ <- Browser.assertText(Selector.id("v"), "sel:10")
            yield ()
        }
    }

    "edit without save switch item edits lost" in {
        val app: UI < Async =
            for
                selected <- Signal.initRef("")
                editVal  <- Signal.initRef("")
            yield UI.div(
                UI.button("Alice").id("item-a").onClick(selected.set("Alice").andThen(editVal.set("Alice"))),
                UI.button("Bob").id("item-b").onClick(selected.set("Bob").andThen(editVal.set("Bob"))),
                selected.map { s =>
                    if s.nonEmpty then UI.div(UI.input.id("edit").value(editVal))
                    else UI.span("Select one")
                },
                editVal.map(v => UI.span(s"editing:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("item-a"))
                _ <- Browser.fill(Selector.id("edit"), "Modified Alice")
                _ <- Browser.assertText(Selector.id("v"), "editing:Modified Alice")
                _ <- Browser.click(Selector.id("item-b"))
                _ <- Browser.assertText(Selector.id("v"), "editing:Bob")
            yield ()
        }
    }

    "fill new item save list shows new" in {
        val app: UI < Async =
            for
                items <- Signal.initRef(Chunk.from(Seq("Alice", "Bob")))
                input <- Signal.initRef("")
            yield UI.div(
                UI.input.id("new").value(input),
                UI.button("Add").id("add").onClick {
                    for
                        v <- input.get
                        _ <- if v.nonEmpty then items.getAndUpdate(_.appended(v)).andThen(input.set(""))
                        else Kyo.lift(())
                    yield ()
                },
                items.map(is => UI.span(s"list:${is.toSeq.mkString(",")}").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("new"), "Carol")
                _ <- Browser.click(Selector.id("add"))
                _ <- Browser.assertText(Selector.id("v"), "list:Alice,Bob,Carol")
            yield ()
        }
    }

    "select item after delete correct item" in {
        val app: UI < Async =
            for
                items    <- Signal.initRef(Chunk.from(Seq("A", "B", "C")))
                selected <- Signal.initRef("")
            yield UI.div(
                UI.button("Remove B").id("rm").onClick(items.getAndUpdate(_.filter(_ != "B")).unit),
                items.foreach(s => UI.button(s).id(s"item-$s").onClick(selected.set(s))),
                selected.map(v => UI.span(s"sel:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("rm"))
                _ <- Browser.click(Selector.id("item-C"))
                _ <- Browser.assertText(Selector.id("v"), "sel:C")
            yield ()
        }
    }

end MasterDetailScenarioItTest
