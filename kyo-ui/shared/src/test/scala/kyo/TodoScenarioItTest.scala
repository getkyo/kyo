package kyo

import kyo.Browser.*
import kyo.UI.foreach
import scala.language.implicitConversions

class TodoScenarioItTest extends UITest:

    case class Todo(text: String, done: Boolean) derives CanEqual

    "add item via Enter appears" in {
        val app: UI < Async =
            for
                input <- Signal.initRef("")
                todos <- Signal.initRef(Chunk.empty[String])
            yield UI.div(
                UI.form.id("f").onSubmit {
                    for
                        v <- input.get
                        _ <- if v.nonEmpty then todos.getAndUpdate(_.appended(v)).andThen(input.set(""))
                        else Kyo.lift(())
                    yield ()
                }(
                    UI.input.id("inp").value(input),
                    UI.button("Add").id("add")
                ),
                todos.map(is => UI.span(s"count:${is.size}").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("inp"), "Buy milk")
                _ <- Browser.press(Selector.id("inp"), Key.Enter)
                _ <- Browser.assertText(Selector.id("v"), "count:1")
            yield ()
        }
    }

    "add 3 items counter 3" in {
        val app: UI < Async =
            for
                input <- Signal.initRef("")
                todos <- Signal.initRef(Chunk.empty[String])
            yield UI.div(
                UI.input.id("inp").value(input),
                UI.button("Add").id("add").onClick {
                    for
                        v <- input.get
                        _ <- if v.nonEmpty then todos.getAndUpdate(_.appended(v)).andThen(input.set(""))
                        else Kyo.lift(())
                    yield ()
                },
                todos.map(is => UI.span(s"count:${is.size}").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("inp"), "A")
                _ <- Browser.click(Selector.id("add"))
                _ <- Browser.assertText(Selector.id("v"), "count:1")
                _ <- Browser.fill(Selector.id("inp"), "B")
                _ <- Browser.click(Selector.id("add"))
                _ <- Browser.assertText(Selector.id("v"), "count:2")
                _ <- Browser.fill(Selector.id("inp"), "C")
                _ <- Browser.click(Selector.id("add"))
                _ <- Browser.assertText(Selector.id("v"), "count:3")
            yield ()
        }
    }

    "check item updates done count" in {
        val app: UI < Async =
            for doneCount <- Signal.initRef(0)
            yield UI.div(
                UI.checkbox.id("c1").onChange(v =>
                    if v then doneCount.getAndUpdate(_ + 1).unit else doneCount.getAndUpdate(_ - 1).unit
                ),
                UI.checkbox.id("c2").onChange(v =>
                    if v then doneCount.getAndUpdate(_ + 1).unit else doneCount.getAndUpdate(_ - 1).unit
                ),
                doneCount.map(n => UI.span(s"done:$n").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("c1"))
                _ <- Browser.assertText(Selector.id("v"), "done:1")
                _ <- Browser.click(Selector.id("c2"))
                _ <- Browser.assertText(Selector.id("v"), "done:2")
            yield ()
        }
    }

    "filter active shows only unchecked" in {
        val app: UI < Async =
            for
                items  <- Signal.initRef(Chunk.from(Seq(Todo("A", false), Todo("B", true), Todo("C", false))))
                filter <- Signal.initRef("all")
            yield UI.div(
                UI.button("All").id("all").onClick(filter.set("all")),
                UI.button("Active").id("active").onClick(filter.set("active")),
                UI.button("Done").id("done").onClick(filter.set("done")),
                filter.switchMap { f =>
                    items.map { is =>
                        val filtered = f match
                            case "active" => is.filter(!_.done)
                            case "done"   => is.filter(_.done)
                            case _        => is
                        UI.span(s"showing:${filtered.size}").id("v")
                    }
                }
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("v"), "showing:3")
                _ <- Browser.click(Selector.id("active"))
                _ <- Browser.assertText(Selector.id("v"), "showing:2")
                _ <- Browser.click(Selector.id("done"))
                _ <- Browser.assertText(Selector.id("v"), "showing:1")
                _ <- Browser.click(Selector.id("all"))
                _ <- Browser.assertText(Selector.id("v"), "showing:3")
            yield ()
        }
    }

    "clear completed removes checked items" in {
        val app: UI < Async =
            for items <- Signal.initRef(Chunk.from(Seq(Todo("A", true), Todo("B", false), Todo("C", true))))
            yield UI.div(
                UI.button("Clear done").id("clear").onClick(items.getAndUpdate(_.filter(!_.done)).unit),
                items.map(is => UI.span(s"count:${is.size}").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("v"), "count:3")
                _ <- Browser.click(Selector.id("clear"))
                _ <- Browser.assertText(Selector.id("v"), "count:1")
            yield ()
        }
    }

    "add empty text rejected" in {
        val app: UI < Async =
            for
                input <- Signal.initRef("")
                count <- Signal.initRef(0)
            yield UI.div(
                UI.input.id("inp").value(input),
                UI.button("Add").id("add").onClick {
                    for
                        v <- input.get
                        _ <- if v.nonEmpty then count.getAndUpdate(_ + 1).unit else Kyo.lift(())
                    yield ()
                },
                count.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("add"))
                _ <- Browser.assertText(Selector.id("v"), "0")
                _ <- Browser.fill(Selector.id("inp"), "valid")
                _ <- Browser.click(Selector.id("add"))
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield ()
        }
    }

    "check all clear completed empty list" in {
        val app: UI < Async =
            for items <- Signal.initRef(Chunk.from(Seq(Todo("A", false), Todo("B", false), Todo("C", false))))
            yield UI.div(
                UI.button("Check all").id("checkall").onClick(items.getAndUpdate(_.map(_.copy(done = true))).unit),
                UI.button("Clear done").id("clear").onClick(items.getAndUpdate(_.filter(!_.done)).unit),
                items.map(is => UI.span(s"count:${is.size}").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("v"), "count:3")
                _ <- Browser.click(Selector.id("checkall"))
                _ <- Browser.click(Selector.id("clear"))
                _ <- Browser.assertText(Selector.id("v"), "count:0")
            yield ()
        }
    }

    "counter correct at every step" in {
        val app: UI < Async =
            for
                items <- Signal.initRef(Chunk.empty[String])
                input <- Signal.initRef("")
            yield UI.div(
                UI.input.id("inp").value(input),
                UI.button("Add").id("add").onClick {
                    for
                        v <- input.get
                        _ <- if v.nonEmpty then items.getAndUpdate(_.appended(v)).andThen(input.set(""))
                        else Kyo.lift(())
                    yield ()
                },
                items.map(is => UI.span(s"count:${is.size}").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("inp"), "A")
                _ <- Browser.click(Selector.id("add"))
                _ <- Browser.assertText(Selector.id("v"), "count:1")
                _ <- Browser.fill(Selector.id("inp"), "B")
                _ <- Browser.click(Selector.id("add"))
                _ <- Browser.assertText(Selector.id("v"), "count:2")
                _ <- Browser.fill(Selector.id("inp"), "C")
                _ <- Browser.click(Selector.id("add"))
                _ <- Browser.assertText(Selector.id("v"), "count:3")
            yield ()
        }
    }

    "filter completed shows only done items" in {
        val app: UI < Async =
            for
                items  <- Signal.initRef(Chunk.from(Seq(Todo("A", true), Todo("B", false), Todo("C", true))))
                filter <- Signal.initRef("all")
            yield UI.div(
                UI.button("Completed").id("completed").onClick(filter.set("done")),
                UI.button("All").id("all").onClick(filter.set("all")),
                filter.switchMap { f =>
                    items.map { is =>
                        val filtered = if f == "done" then is.filter(_.done) else is
                        UI.span(s"showing:${filtered.size}").id("v")
                    }
                }
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("v"), "showing:3")
                _ <- Browser.click(Selector.id("completed"))
                _ <- Browser.assertText(Selector.id("v"), "showing:2")
            yield ()
        }
    }

    "edit item inline click saves" in {
        val app: UI < Async =
            for
                items   <- Signal.initRef(Chunk.from(Seq("Buy milk", "Walk dog")))
                editing <- Signal.initRef(-1)
                editVal <- Signal.initRef("")
            yield UI.div(
                items.switchMap { is =>
                    editing.map { editIdx =>
                        UI.div(is.toSeq.zipWithIndex.map { case (text, i) =>
                            if i == editIdx then
                                UI.div(
                                    UI.input.id("edit").value(editVal),
                                    UI.button("Save").id("save").onClick {
                                        for
                                            v <- editVal.get
                                            _ <- items.getAndUpdate(_.updated(i, v))
                                            _ <- editing.set(-1)
                                        yield ()
                                    }
                                )
                            else
                                UI.button(text).id(s"item-$i").onClick {
                                    editing.set(i).andThen(editVal.set(text))
                                }
                        }*)
                    }
                },
                items.map(is => UI.span(s"list:${is.toSeq.mkString(",")}").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("item-0"))
                _ <- Browser.fill(Selector.id("edit"), "Buy eggs")
                _ <- Browser.click(Selector.id("save"))
                _ <- Browser.assertText(Selector.id("v"), "list:Buy eggs,Walk dog")
            yield ()
        }
    }

    "cancel edit original restored" in {
        val app: UI < Async =
            for
                items   <- Signal.initRef(Chunk.from(Seq("Buy milk")))
                editing <- Signal.initRef(false)
                editVal <- Signal.initRef("")
            yield UI.div(
                editing.map { e =>
                    if e then
                        UI.div(
                            UI.input.id("edit").value(editVal),
                            UI.button("Cancel").id("cancel").onClick(editing.set(false))
                        )
                    else
                        UI.div(
                            UI.button("Buy milk").id("item").onClick(editing.set(true).andThen(editVal.set("Buy milk")))
                        )
                },
                items.map(is => UI.span(s"list:${is.toSeq.mkString(",")}").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("item"))
                _ <- Browser.fill(Selector.id("edit"), "Modified")
                _ <- Browser.click(Selector.id("cancel"))
                _ <- Browser.assertText(Selector.id("v"), "list:Buy milk")
            yield ()
        }
    }

    "edit while filtered item stays visible" in {
        val app: UI < Async =
            for
                items  <- Signal.initRef(Chunk.from(Seq(Todo("Active task", false), Todo("Done task", true))))
                filter <- Signal.initRef("active")
            yield UI.div(
                UI.button("Active").id("active").onClick(filter.set("active")),
                UI.button("All").id("all").onClick(filter.set("all")),
                filter.switchMap { f =>
                    items.map { is =>
                        val filtered = if f == "active" then is.filter(!_.done) else is
                        UI.div(filtered.toSeq.zipWithIndex.map { case (todo, i) =>
                            UI.span(todo.text).id(s"item-$i")
                        }*)
                    }
                },
                filter.map(f => UI.span(s"filter:$f").id("vf"))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("vf"), "filter:active")
                _ <- Browser.assertText(Selector.id("item-0"), "Active task")
                _ <- Browser.click(Selector.id("all"))
                _ <- Browser.assertText(Selector.id("item-0"), "Active task")
                _ <- Browser.assertText(Selector.id("item-1"), "Done task")
            yield ()
        }
    }

end TodoScenarioItTest
