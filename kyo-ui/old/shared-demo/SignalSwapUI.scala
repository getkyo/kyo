package demo
import kyo.*
import kyo.UIDsl.*
import scala.language.implicitConversions

/** Signal[UI] that switches between different foreachIndexed views with different signals. Tests that old foreach subscriptions are cleaned
  * up when the parent Signal[UI] swaps.
  */
object SignalSwapUI:

    import DemoStyles.app
    import DemoStyles.card
    import DemoStyles.content

    private val btn = Style.bg("#7c3aed").color(Color.white).padding(8, 20).rounded(4)
        .borderStyle(_.none).cursor(_.pointer)
    private val btnSm = Style.padding(4, 12).rounded(4).border(1, "#ccc").cursor(_.pointer).fontSize(12)
    private val tag   = Style.bg("#ede9fe").padding(4, 12).rounded(12)

    def build: UI < Async =
        for
            viewMode  <- Signal.initRef("tasks") // "tasks", "notes", "tags"
            tasks     <- Signal.initRef(Chunk("Build UI", "Write tests", "Deploy"))
            notes     <- Signal.initRef(Chunk("Meeting at 3pm", "Review PR"))
            tags      <- Signal.initRef(Chunk("urgent", "bug", "feature", "docs"))
            newItem   <- Signal.initRef("")
            swapCount <- Signal.initRef(0)
        yield div.style(app)(
            header.style(Style.bg("#7c3aed").color(Color.white).padding(16, 32))(
                h1("Signal Swap Tests")
            ),
            main.style(content)(
                // Main swap area
                section.cls("swap-views").style(card)(
                    h3("Swap Between Views"),
                    p.style(
                        Style.fontSize(13).color("#64748b")
                    )("Each view has its own signal + foreachIndexed. Swapping should clean up old subscriptions:"),
                    div.style(Style.row.gap(8).margin(8, 0, 0, 0))(
                        button.cls("view-tasks-btn").style(btn).onClick {
                            for
                                _ <- viewMode.set("tasks")
                                _ <- swapCount.getAndUpdate(_ + 1)
                            yield ()
                        }("Tasks"),
                        button.cls("view-notes-btn").style(btn).onClick {
                            for
                                _ <- viewMode.set("notes")
                                _ <- swapCount.getAndUpdate(_ + 1)
                            yield ()
                        }("Notes"),
                        button.cls("view-tags-btn").style(btn).onClick {
                            for
                                _ <- viewMode.set("tags")
                                _ <- swapCount.getAndUpdate(_ + 1)
                            yield ()
                        }("Tags")
                    ),
                    div.cls("swap-counter").style(Style.fontSize(12).color("#94a3b8").margin(4, 0, 0, 0))(
                        swapCount.map(c => s"Swaps: $c")
                    ),
                    // Add item to current view
                    div.style(Style.row.gap(8).margin(8, 0, 0, 0))(
                        input.cls("new-item-input").value(newItem).onInput(newItem.set(_)).placeholder("New item..."),
                        button.cls("add-item-btn").style(btnSm).onClick {
                            for
                                text <- newItem.get
                                mode <- viewMode.get
                                _ <-
                                    if text.nonEmpty then
                                        mode match
                                            case "tasks" => tasks.getAndUpdate(_.append(text))
                                            case "notes" => notes.getAndUpdate(_.append(text))
                                            case "tags"  => tags.getAndUpdate(_.append(text))
                                            case _       => (Chunk.empty[String]: Chunk[String] < Sync)
                                    else (Chunk.empty[String]: Chunk[String] < Sync)
                                _ <- newItem.set("")
                            yield ()
                        }("Add to Current View")
                    ),
                    // The swappable view — this is a Signal[UI]
                    div.cls("swap-content").style(Style.margin(8, 0, 0, 0).padding(12).bg("#faf5ff").rounded(8).minHeight(100))(
                        viewMode.map {
                            case "tasks" =>
                                div.cls("tasks-view").style(Style.gap(4))(
                                    h3.style(Style.fontSize(14))("Tasks"),
                                    tasks.foreachIndexed { (idx, task) =>
                                        div.cls("task-item").style(Style.row.gap(8).align(_.center).padding(8).bg(
                                            if idx % 2 == 0 then "#f5f3ff" else "#ffffff"
                                        ).rounded(4))(
                                            input.typ("checkbox"),
                                            span(task),
                                            button.style(Style.color(
                                                "#ef4444"
                                            ).fontSize(12).cursor(_.pointer).borderStyle(_.none).bg(Color.transparent)).onClick(
                                                tasks.getAndUpdate(c => c.take(idx) ++ c.drop(idx + 1)).unit
                                            )("×")
                                        )
                                    }
                                ): UI
                            case "notes" =>
                                div.cls("notes-view").style(Style.gap(4))(
                                    h3.style(Style.fontSize(14))("Notes"),
                                    notes.foreachIndexed { (idx, note) =>
                                        div.cls("note-item").style(Style.padding(12).bg("#fffbeb").rounded(8).border(1, "#fde68a"))(
                                            span(note),
                                            button.style(Style.color("#ef4444").fontSize(12).cursor(_.pointer).borderStyle(_.none)
                                                .bg(Color.transparent).margin(0, 0, 0, 8)).onClick(
                                                notes.getAndUpdate(c => c.take(idx) ++ c.drop(idx + 1)).unit
                                            )("×")
                                        )
                                    }
                                ): UI
                            case "tags" =>
                                div.cls("tags-view").style(Style.gap(8))(
                                    h3.style(Style.fontSize(14))("Tags"),
                                    div.style(Style.row.gap(4))(
                                        tags.foreach { t =>
                                            span.style(tag)(t)
                                        }
                                    ),
                                    div.style(Style.fontSize(12).color("#64748b"))(
                                        tags.map(t => s"${t.size} tags total")
                                    )
                                ): UI
                            case _ =>
                                p("Unknown view"): UI
                        }
                    )
                ),
                // Rapid swap stress test
                section.cls("rapid-swap").style(card)(
                    h3("Rapid Swap Stress Test"),
                    p.style(Style.fontSize(13).color("#64748b"))("Rapidly cycle through all views:"),
                    button.cls("rapid-swap-btn").style(btn).onClick {
                        for
                            _ <- viewMode.set("tasks")
                            _ <- swapCount.getAndUpdate(_ + 1)
                            _ <- viewMode.set("notes")
                            _ <- swapCount.getAndUpdate(_ + 1)
                            _ <- viewMode.set("tags")
                            _ <- swapCount.getAndUpdate(_ + 1)
                            _ <- viewMode.set("tasks")
                            _ <- swapCount.getAndUpdate(_ + 1)
                        yield ()
                    }("Cycle: Tasks→Notes→Tags→Tasks"),
                    div.cls("current-view").style(Style.padding(8).margin(8, 0, 0, 0).bg("#f0f9ff").rounded(4).bold)(
                        viewMode.map(m => s"Current view: $m")
                    )
                )
            )
        )

end SignalSwapUI
