package kyo

import kyo.Browser.*
import kyo.UI.foreach

class DataTableScenarioItTest extends UITest:

    case class Row(name: String, email: String) derives CanEqual

    "click header sorts ascending" in {
        val app: UI < Async =
            for
                rows    <- Signal.initRef(Chunk.from(Seq(Row("Charlie", "c@"), Row("Alice", "a@"), Row("Bob", "b@"))))
                sortAsc <- Signal.initRef(true)
            yield UI.div(
                UI.button("Sort Name").id("sort").onClick {
                    for
                        asc <- sortAsc.get
                        _   <- rows.getAndUpdate(r => if asc then r.sortBy(_.name) else r.sortBy(_.name).reverse)
                        _   <- sortAsc.getAndUpdate(!_)
                    yield ()
                },
                rows.map { rs =>
                    UI.span(rs.toSeq.map(_.name).mkString(",")).id("v")
                }
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("v"), "Charlie,Alice,Bob")
                _ <- Browser.click(Selector.id("sort"))
                _ <- Browser.assertText(Selector.id("v"), "Alice,Bob,Charlie")
                _ <- Browser.click(Selector.id("sort"))
                _ <- Browser.assertText(Selector.id("v"), "Charlie,Bob,Alice")
            yield ()
        }
    }

    "click cell to edit inline" in {
        val app: UI < Async =
            for
                rows    <- Signal.initRef(Chunk.from(Seq(Row("Alice", "a@"), Row("Bob", "b@"))))
                editing <- Signal.initRef(-1)
                editVal <- Signal.initRef("")
            yield UI.div(
                rows.switchMap { rs =>
                    editing.map { editIdx =>
                        UI.div(rs.toSeq.zipWithIndex.map { case (row, i) =>
                            if i == editIdx then
                                UI.div(
                                    UI.input.id("edit").value(editVal),
                                    UI.button("Save").id("save").onClick {
                                        for
                                            v <- editVal.get
                                            _ <- rows.getAndUpdate(_.updated(i, row.copy(name = v)))
                                            _ <- editing.set(-1)
                                        yield ()
                                    }
                                )
                            else
                                UI.button(row.name).id(s"cell-$i").onClick {
                                    editing.set(i).andThen(editVal.set(row.name))
                                }
                        }*)
                    }
                },
                rows.map(rs => UI.span(s"names:${rs.toSeq.map(_.name).mkString(",")}").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("cell-0"))
                _ <- Browser.fill(Selector.id("edit"), "Alicia")
                _ <- Browser.click(Selector.id("save"))
                _ <- Browser.assertText(Selector.id("v"), "names:Alicia,Bob")
            yield ()
        }
    }

    "sort then edit correct row" in {
        val app: UI < Async =
            for
                rows   <- Signal.initRef(Chunk.from(Seq(Row("Charlie", "c@"), Row("Alice", "a@"))))
                result <- Signal.initRef("")
            yield UI.div(
                UI.button("Sort").id("sort").onClick(rows.getAndUpdate(_.sortBy(_.name)).unit),
                rows.map { rs =>
                    UI.div(rs.toSeq.zipWithIndex.map { case (row, i) =>
                        UI.button(row.name).id(s"row-$i").onClick(result.set(s"clicked:${row.name}"))
                    }*)
                },
                result.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("sort"))
                _ <- Browser.click(Selector.id("row-0"))
                _ <- Browser.assertText(Selector.id("v"), "clicked:Alice")
                _ <- Browser.click(Selector.id("row-1"))
                _ <- Browser.assertText(Selector.id("v"), "clicked:Charlie")
            yield ()
        }
    }

    "sort by different column clears previous" in {
        val app: UI < Async =
            for
                rows    <- Signal.initRef(Chunk.from(Seq(Row("Charlie", "z@"), Row("Alice", "a@"))))
                sortCol <- Signal.initRef("none")
            yield UI.div(
                UI.button("Sort Name").id("sn")
                    .onClick(rows.getAndUpdate(_.sortBy(_.name)).andThen(sortCol.set("name")).unit),
                UI.button("Sort Email").id("se")
                    .onClick(rows.getAndUpdate(_.sortBy(_.email)).andThen(sortCol.set("email")).unit),
                rows.map(rs => UI.span(rs.toSeq.map(_.name).mkString(",")).id("v")),
                sortCol.map(v => UI.span(s"sort:$v").id("sc"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("sn"))
                _ <- Browser.assertText(Selector.id("v"), "Alice,Charlie")
                _ <- Browser.assertText(Selector.id("sc"), "sort:name")
                _ <- Browser.click(Selector.id("se"))
                _ <- Browser.assertText(Selector.id("sc"), "sort:email")
                _ <- Browser.assertText(Selector.id("v"), "Alice,Charlie")
            yield ()
        }
    }

    "cancel cancels edit" in {
        val app: UI < Async =
            for
                rows    <- Signal.initRef(Chunk.from(Seq(Row("Alice", "a@"))))
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
                            UI.button("Alice").id("cell").onClick(editing.set(true).andThen(editVal.set("Alice")))
                        )
                },
                rows.map(rs => UI.span(s"names:${rs.toSeq.map(_.name).mkString(",")}").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("cell"))
                _ <- Browser.fill(Selector.id("edit"), "Modified")
                _ <- Browser.click(Selector.id("cancel"))
                _ <- Browser.assertText(Selector.id("v"), "names:Alice")
            yield ()
        }
    }

    "20 rows all cells clickable" in {
        val app: UI < Async =
            for
                rows    <- Signal.initRef(Chunk.from((0 until 20).map(i => Row(s"name$i", s"e$i@"))))
                clicked <- Signal.initRef("")
            yield UI.div(
                rows.map { rs =>
                    UI.div(rs.toSeq.zipWithIndex.map { case (row, i) =>
                        UI.button(row.name).id(s"row-$i").onClick(clicked.set(row.name))
                    }*)
                },
                clicked.map(v => UI.span(s"clicked:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("row-0"))
                _ <- Browser.assertText(Selector.id("v"), "clicked:name0")
                _ <- Browser.click(Selector.id("row-19"))
                _ <- Browser.assertText(Selector.id("v"), "clicked:name19")
            yield ()
        }
    }

    "sorted data remains sorted after edit" in {
        val app: UI < Async =
            for
                rows    <- Signal.initRef(Chunk.from(Seq(Row("Charlie", "c@"), Row("Alice", "a@"), Row("Bob", "b@"))))
                editVal <- Signal.initRef("")
            yield UI.div(
                UI.button("Sort").id("sort").onClick(rows.getAndUpdate(_.sortBy(_.name)).unit),
                UI.button("Edit first").id("edit").onClick {
                    rows.get.map { rs =>
                        if rs.nonEmpty then editVal.set(rs.head.name)
                        else Kyo.lift(())
                    }
                },
                UI.input.id("editField").value(editVal),
                UI.button("Save").id("save").onClick {
                    for
                        v <- editVal.get
                        _ <- rows.getAndUpdate(rs => if rs.nonEmpty then rs.updated(0, rs.head.copy(name = v)) else rs)
                    yield ()
                },
                rows.map(rs => UI.span(s"names:${rs.toSeq.map(_.name).mkString(",")}").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("sort"))
                _ <- Browser.assertText(Selector.id("v"), "names:Alice,Bob,Charlie")
                _ <- Browser.click(Selector.id("edit"))
                _ <- Browser.fill(Selector.id("editField"), "Zara")
                _ <- Browser.click(Selector.id("save"))
                _ <- Browser.assertText(Selector.id("v"), "names:Zara,Bob,Charlie")
            yield ()
        }
    }

end DataTableScenarioItTest
