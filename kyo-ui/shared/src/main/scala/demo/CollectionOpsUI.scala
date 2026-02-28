package demo

import kyo.*
import scala.language.implicitConversions

object CollectionOpsUI extends UIScope:

    import DemoStyles.app
    import DemoStyles.card
    import DemoStyles.content

    private val btn = Style.bg("#2563eb").color(Color.white).padding(8, 20).rounded(4)
        .borderStyle(_.none).cursor(_.pointer)
    private val btnSm = Style.padding(4, 12).rounded(4).border(1, "#ccc").cursor(_.pointer).fontSize(12)
    private val tag   = Style.bg("#e0e7ff").padding(4, 12).rounded(12)

    case class Item(id: String, name: String, priority: Int) derives CanEqual

    def build: UI < Async =
        for
            items <- Signal.initRef(Chunk(
                Item("1", "Setup CI", 1),
                Item("2", "Write tests", 2),
                Item("3", "Deploy v1", 3)
            ))
            newName     <- Signal.initRef("")
            simpleItems <- Signal.initRef(Chunk("Red", "Green", "Blue"))
            counter     <- Signal.initRef(0)
        yield div.style(app)(
            header.style(Style.bg("#6d28d9").color(Color.white).padding(16, 32))(
                h1("Collection Ops Showcase")
            ),
            main.style(content)(
                // foreachKeyedIndexed
                section.style(card)(
                    h3("foreachKeyedIndexed"),
                    p.style(Style.fontSize(13).color("#64748b"))("Keyed list with index display:"),
                    div.style(Style.gap(4).margin(8, 0, 0, 0))(
                        items.foreachKeyedIndexed(_.id) { (idx, item) =>
                            div.style(Style.row.gap(8).align(_.center).padding(8).bg(
                                if idx % 2 == 0 then "#f8fafc" else "#ffffff"
                            ).rounded(4))(
                                span.style(Style.color("#94a3b8").fontSize(12).minWidth(20))(s"#${idx + 1}"),
                                span.style(Style.bold)(item.name),
                                span.style(Style.fontSize(12).color("#64748b"))(s"priority: ${item.priority}")
                            )
                        }
                    )
                ),
                // Add / Remove / Reorder
                section.style(card)(
                    h3("Add / Remove / Reorder"),
                    div.style(Style.row.gap(8).margin(0, 0, 8, 0))(
                        input.value(newName).onInput(newName.set(_)).placeholder("Task name..."),
                        button.style(btn).onClick {
                            for
                                name <- newName.get
                                cur  <- items.get
                                id = (cur.size + 1).toString
                                _ <- if name.nonEmpty then items.set(cur.append(Item(id, name, cur.size + 1)))
                                else ((): Unit < Sync)
                                _ <- newName.set("")
                            yield ()
                        }("Add"),
                        button.style(btnSm).onClick {
                            items.getAndUpdate(c => if c.nonEmpty then c.dropRight(1) else c).unit
                        }("Remove Last"),
                        button.style(btnSm).onClick {
                            items.getAndUpdate(_.reverse).unit
                        }("Reverse"),
                        button.style(btnSm).onClick {
                            items.set(Chunk.empty).unit
                        }("Clear")
                    ),
                    div.style(Style.gap(4))(
                        items.foreachKeyed(_.id) { item =>
                            div.style(Style.row.gap(8).align(_.center).padding(8, 12).bg("#f8fafc").rounded(4).border(1, "#e2e8f0"))(
                                span.style(Style.bold)(item.name),
                                span.style(Style.fontSize(11).color("#94a3b8"))(s"id=${item.id}")
                            )
                        }
                    ),
                    // Empty state
                    UI.when(items.map(_.isEmpty))(
                        div.style(Style.padding(16).textAlign(_.center).color("#94a3b8").bg("#f9fafb").rounded(8).margin(8, 0, 0, 0))(
                            p("No items. Add some above!")
                        )
                    )
                ),
                // foreachIndexed
                section.style(card)(
                    h3("foreachIndexed"),
                    p.style(Style.fontSize(13).color("#64748b"))("Simple indexed iteration:"),
                    ol.style(Style.margin(8, 0, 0, 0))(
                        simpleItems.foreachIndexed { (idx, item) =>
                            li.style(Style.padding(4, 0))(
                                span(s"[$idx] "),
                                span.style(Style.bold)(item)
                            )
                        }
                    )
                ),
                // Batch updates
                section.style(card)(
                    h3("Batch Updates"),
                    p.style(Style.fontSize(13).color("#64748b"))("Rapid updates to test diffing:"),
                    div.style(Style.row.gap(8).margin(0, 0, 8, 0))(
                        button.style(btn).onClick(counter.getAndUpdate(_ + 1).unit)("Tick"),
                        span.style(Style.padding(4, 8).bold)(counter.map(c => s"Count: $c"))
                    ),
                    div.style(Style.row.gap(4))(
                        simpleItems.foreach { item =>
                            span.style(tag)(
                                counter.map(c => s"$item ($c)")
                            )
                        }
                    )
                ),
                // Single item
                section.style(card)(
                    h3("Edge Case: Single Item"),
                    div.style(Style.gap(8))(
                        div.style(Style.row.gap(8))(
                            button.style(btnSm).onClick(simpleItems.set(Chunk("Only")))("Set Single"),
                            button.style(btnSm).onClick(simpleItems.set(Chunk("Red", "Green", "Blue")))("Reset")
                        ),
                        div.style(Style.row.gap(4))(
                            simpleItems.foreach(item => span.style(tag)(item))
                        )
                    )
                )
            )
        )

end CollectionOpsUI
