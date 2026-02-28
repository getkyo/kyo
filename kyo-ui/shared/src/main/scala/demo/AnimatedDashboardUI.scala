package demo

import kyo.*
import scala.language.implicitConversions

object AnimatedDashboardUI extends UIScope:

    import DemoStyles.app
    import DemoStyles.content

    private val statCard = Style.bg(Color.white).padding(24).rounded(8)
        .shadow(y = 2, blur = 8, c = Color.rgba(0, 0, 0, 0.12))
    private val statNumber = Style.fontSize(36).bold.color("#1e293b")
    private val statLabel  = Style.fontSize(13).color("#64748b").margin(4, 0, 0, 0)
    private val badge      = Style.padding(2, 10).rounded(12).fontSize(11).bold

    def build: UI < Async =
        for
            users      <- Signal.initRef(0)
            revenue    <- Signal.initRef(0)
            uptime     <- Signal.initRef(0)
            statusIdx  <- Signal.initRef(0)
            logEntries <- Signal.initRef(Chunk.empty[String])
            viewMode   <- Signal.initRef(true)
            _ <- Fiber.initUnscoped {
                for
                    _ <- Async.sleep(200.millis)
                    _ <- users.set(342)
                    _ <- Async.sleep(300.millis)
                    _ <- revenue.set(12400)
                    _ <- Async.sleep(300.millis)
                    _ <- uptime.set(87)
                    _ <- Async.sleep(200.millis)
                    _ <- logEntries.set(Chunk("[10:01] Service started"))
                    _ <- Async.sleep(300.millis)
                    _ <- logEntries.set(Chunk("[10:01] Service started", "[10:02] Health check OK"))
                    _ <- Async.sleep(300.millis)
                    _ <- logEntries.set(Chunk("[10:01] Service started", "[10:02] Health check OK", "[10:03] 42 requests/s"))
                    _ <- Async.sleep(200.millis)
                    _ <- statusIdx.set(1)
                    _ <- viewMode.set(false)
                    _ <- Async.sleep(500.millis)
                    _ <- statusIdx.set(2)
                    _ <- users.set(1284)
                    _ <- revenue.set(48200)
                    _ <- uptime.set(99)
                yield ()
            }
        yield div.style(app)(
            header.style(Style.bg("#0f172a").color(Color.white).padding(20, 32))(
                h1("Animated Dashboard"),
                p.style(Style.color("#94a3b8").fontSize(14))("Metrics populate over time")
            ),
            main.style(content)(
                section(
                    h3.style(Style.margin(0, 0, 12, 0))("Live Metrics"),
                    div.style(Style.row.gap(16))(
                        div.style(statCard)(
                            div.style(statNumber)(users.map(u => if u == 0 then "—" else u.toString)),
                            div.style(statLabel)("Users")
                        ),
                        div.style(statCard)(
                            div.style(statNumber)(revenue.map(r => if r == 0 then "—" else s"$$${r / 1000}.${(r % 1000) / 100}K")),
                            div.style(statLabel)("Revenue")
                        ),
                        div.style(statCard)(
                            div.style(statNumber)(uptime.map(u => if u == 0 then "—" else s"$u%")),
                            div.style(statLabel)("Uptime")
                        )
                    )
                ),
                section(
                    h3.style(Style.margin(0, 0, 12, 0))("Status"),
                    div.style(Style.row.gap(8).align(_.center))(
                        span("System: "),
                        statusIdx.map { idx =>
                            val (label, bg, fg) = idx match
                                case 0 => ("Starting", "#fef3c7", "#92400e")
                                case 1 => ("Warming", "#dbeafe", "#1e40af")
                                case _ => ("Active", "#dcfce7", "#166534")
                            span.style(badge ++ Style.bg(bg).color(fg))(label)
                        }: Signal[UI]
                    )
                ),
                section(
                    h3.style(Style.margin(0, 0, 12, 0))("Event Log"),
                    div.style(Style.bg("#1e293b").color("#e2e8f0").padding(12).rounded(6)
                        .fontFamily("monospace").fontSize(12).minHeight(60))(
                        logEntries.foreach(entry => p(entry))
                    ),
                    UI.when(logEntries.map(_.isEmpty))(
                        p.style(Style.color("#94a3b8").italic)("Waiting for events...")
                    )
                ),
                section(
                    h3.style(Style.margin(0, 0, 12, 0))("View Toggle"),
                    p.style(Style.fontSize(13).color("#64748b"))("Switches from cards to table after 1.5s:"),
                    viewMode.map { isCards =>
                        if isCards then
                            div.style(Style.row.gap(12))(
                                div.style(Style.bg("#dbeafe").padding(16).rounded(8))("Service A"),
                                div.style(Style.bg("#dcfce7").padding(16).rounded(8))("Service B"),
                                div.style(Style.bg("#fef3c7").padding(16).rounded(8))("Service C")
                            )
                        else
                            table.style(Style.width(100.pct))(
                                tr(th("Service"), th("Status"), th("Latency")),
                                tr(td("Service A"), td("OK"), td("12ms")),
                                tr(td("Service B"), td("OK"), td("8ms")),
                                tr(td("Service C"), td("WARN"), td("142ms"))
                            )
                    }: Signal[UI]
                )
            )
        )

end AnimatedDashboardUI
