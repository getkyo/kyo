package demo

import kyo.*
import scala.language.implicitConversions

object TableAdvancedUI extends UIScope:

    import DemoStyles.app
    import DemoStyles.card
    import DemoStyles.content

    private val cellStyle   = Style.padding(8, 12).border(1, "#e2e8f0")
    private val headerCell  = cellStyle ++ Style.bg("#f8fafc").bold
    private val stripedEven = cellStyle ++ Style.bg("#f9fafb")
    private val stripedOdd  = cellStyle ++ Style.bg(Color.white)

    def build: UI < Async =
        for
            rows <- Signal.initRef(Chunk(
                ("Alice", "Engineer", "Active", "$95K"),
                ("Bob", "Designer", "On Leave", "$88K"),
                ("Charlie", "Manager", "Active", "$110K"),
                ("Diana", "Engineer", "Active", "$92K")
            ))
            newName <- Signal.initRef("")
        yield div.style(app)(
            header.style(Style.bg("#1e40af").color(Color.white).padding(16, 32))(
                h1("Table Advanced Showcase")
            ),
            main.style(content)(
                // Basic styled table
                section.style(card)(
                    h3("Styled Table"),
                    table.style(Style.width(Size.pct(100)))(
                        tr(
                            th.style(headerCell)("Name"),
                            th.style(headerCell)("Role"),
                            th.style(headerCell)("Status"),
                            th.style(headerCell)("Salary")
                        ),
                        rows.foreachIndexed { (idx, row) =>
                            val cs = if idx % 2 == 0 then stripedEven else stripedOdd
                            tr(
                                td.style(cs)(row._1),
                                td.style(cs)(row._2),
                                td.style(cs)(row._3),
                                td.style(cs)(row._4)
                            )
                        }
                    )
                ),
                // Colspan and Rowspan
                section.style(card)(
                    h3("Colspan & Rowspan"),
                    table.style(Style.width(Size.pct(100)))(
                        tr(
                            th.style(headerCell).colspan(4)("Q1 2024 Report")
                        ),
                        tr(
                            th.style(headerCell).rowspan(2)("Team"),
                            th.style(headerCell).colspan(2)("Performance"),
                            th.style(headerCell).rowspan(2)("Budget")
                        ),
                        tr(
                            th.style(headerCell)("Tasks"),
                            th.style(headerCell)("Score")
                        ),
                        tr(
                            td.style(cellStyle)("Frontend"),
                            td.style(cellStyle)("42"),
                            td.style(cellStyle)("A+"),
                            td.style(cellStyle)("$25K")
                        ),
                        tr(
                            td.style(cellStyle)("Backend"),
                            td.style(cellStyle)("38"),
                            td.style(cellStyle)("A"),
                            td.style(cellStyle)("$30K")
                        ),
                        tr(
                            td.style(cellStyle)("DevOps"),
                            td.style(cellStyle)("15"),
                            td.style(cellStyle)("B+"),
                            td.style(cellStyle)("$20K")
                        ),
                        tr(
                            td.style(headerCell).colspan(2)("Total"),
                            td.style(headerCell)("95"),
                            td.style(headerCell)("$75K")
                        )
                    )
                ),
                // Dynamic table with add/remove
                section.style(card)(
                    h3("Dynamic Table"),
                    div.style(Style.row.gap(8).margin(0, 0, 8, 0))(
                        input.value(newName).onInput(newName.set(_)).placeholder("Name..."),
                        button.style(Style.bg("#2563eb").color(Color.white).padding(8, 20).rounded(4)
                            .borderStyle(_.none).cursor(_.pointer)).onClick {
                            for
                                name <- newName.get
                                _ <- if name.nonEmpty then rows.getAndUpdate(_.append((name, "New", "Pending", "TBD")))
                                else ((): Unit < Sync)
                                _ <- newName.set("")
                            yield ()
                        }("Add Row"),
                        button.style(Style.padding(4, 12).rounded(4).border(1, "#ccc").cursor(_.pointer))
                            .onClick(rows.getAndUpdate(c => if c.nonEmpty then c.dropRight(1) else c).unit)("Remove Last")
                    ),
                    table.style(Style.width(Size.pct(100)))(
                        tr(
                            th.style(headerCell)("#"),
                            th.style(headerCell)("Name"),
                            th.style(headerCell)("Role"),
                            th.style(headerCell)("Status")
                        ),
                        rows.foreachIndexed { (idx, row) =>
                            tr(
                                td.style(cellStyle ++ Style.color("#94a3b8"))((idx + 1).toString),
                                td.style(cellStyle ++ Style.bold)(row._1),
                                td.style(cellStyle)(row._2),
                                td.style(cellStyle)(row._3)
                            )
                        }
                    ),
                    UI.when(rows.map(_.isEmpty))(
                        p.style(Style.padding(16).textAlign(_.center).color("#94a3b8"))("No rows. Add some above!")
                    )
                ),
                // Colored cells
                section.style(card)(
                    h3("Colored Status Cells"),
                    table.style(Style.width(Size.pct(100)))(
                        tr(
                            th.style(headerCell)("Service"),
                            th.style(headerCell)("Status"),
                            th.style(headerCell)("Uptime")
                        ),
                        tr(
                            td.style(cellStyle)("API"),
                            td.style(cellStyle ++ Style.bg("#dcfce7").color("#166534").bold)("Healthy"),
                            td.style(cellStyle)("99.9%")
                        ),
                        tr(
                            td.style(cellStyle)("Database"),
                            td.style(cellStyle ++ Style.bg("#fef3c7").color("#92400e").bold)("Degraded"),
                            td.style(cellStyle)("98.5%")
                        ),
                        tr(
                            td.style(cellStyle)("CDN"),
                            td.style(cellStyle ++ Style.bg("#fee2e2").color("#991b1b").bold)("Down"),
                            td.style(cellStyle)("85.2%")
                        )
                    )
                )
            )
        )

end TableAdvancedUI
