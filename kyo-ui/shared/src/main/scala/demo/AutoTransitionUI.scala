package demo

import kyo.*
import scala.language.implicitConversions

object AutoTransitionUI extends UIScope:

    import DemoStyles.app
    import DemoStyles.card
    import DemoStyles.content

    private val box = Style.padding(20).rounded(8).textAlign(_.center).fontSize(16).bold

    def build: UI < Async =
        for
            phase     <- Signal.initRef(0)
            items     <- Signal.initRef(Chunk.empty[String])
            showPanel <- Signal.initRef(false)
            counter   <- Signal.initRef(0)
            _ <- Fiber.initUnscoped {
                Loop.forever {
                    for
                        _ <- Async.sleep(500.millis)
                        _ <- phase.getAndUpdate(p => (p + 1) % 4)
                        _ <- counter.getAndUpdate(_ + 1)
                    yield ()
                }
            }
            _ <- Fiber.initUnscoped {
                for
                    _ <- Async.sleep(300.millis)
                    _ <- items.set(Chunk("Alpha"))
                    _ <- Async.sleep(400.millis)
                    _ <- items.set(Chunk("Alpha", "Beta"))
                    _ <- Async.sleep(400.millis)
                    _ <- items.set(Chunk("Alpha", "Beta", "Gamma"))
                    _ <- Async.sleep(400.millis)
                    _ <- showPanel.set(true)
                yield ()
            }
        yield div.style(app)(
            header.style(Style.bg("#7c3aed").color(Color.white).padding(16, 32))(
                h1("Auto Transitions")
            ),
            main.style(content)(
                section.style(card)(
                    h3("Color Cycling"),
                    p.style(Style.fontSize(13).color("#64748b"))("Background cycles every 500ms:"),
                    div.style(box).style(phase.map { p =>
                        val colors = Seq("#dbeafe", "#dcfce7", "#fef3c7", "#fee2e2")
                        s"background-color: ${colors(p)};"
                    })(
                        phase.map(p => s"Phase $p")
                    )
                ),
                section.style(card)(
                    h3("Auto-populating List"),
                    p.style(Style.fontSize(13).color("#64748b"))("Items appear over time:"),
                    div.style(Style.gap(4))(
                        items.foreach { item =>
                            div.style(Style.padding(8, 12).bg("#f0fdf4").rounded(4).border(1, "#bbf7d0"))(
                                span.style(Style.bold)(item),
                                span(" — tick: "),
                                span(counter.map(_.toString))
                            )
                        }
                    ),
                    UI.when(items.map(_.isEmpty))(
                        p.style(Style.color("#94a3b8").italic)("Loading items...")
                    )
                ),
                section.style(card)(
                    h3("Delayed Panel"),
                    p.style(Style.fontSize(13).color("#64748b"))("Panel appears after ~1.5s:"),
                    UI.when(showPanel)(
                        div.style(Style.bg("#ede9fe").padding(16).rounded(8).border(1, "#c4b5fd"))(
                            p.style(Style.bold)("Panel appeared!"),
                            p("This was revealed by a scheduled signal update.")
                        )
                    ),
                    UI.when(showPanel.map(!_))(
                        p.style(Style.color("#94a3b8").italic)("Waiting for panel...")
                    )
                ),
                section.style(card)(
                    h3("Live Counter"),
                    p.style(Style.fontSize(13).color("#64748b"))("Increments every 500ms:"),
                    div.style(Style.fontSize(48).bold.textAlign(_.center).color("#1e293b").padding(16))(
                        counter.map(_.toString)
                    )
                )
            )
        )

end AutoTransitionUI
