package demo

import kyo.*
import scala.language.implicitConversions

object DashboardUI extends UIScope:

    import DemoStyles.app
    import DemoStyles.content

    private val statCard = Style.bg(Color.white).padding(24).rounded(8)
        .shadow(y = 2, blur = 8, c = Color.rgba(0, 0, 0, 0.12))
    private val statNumber = Style.fontSize(36).bold.color("#1e293b")
    private val statLabel  = Style.fontSize(13).color("#64748b").margin(4, 0, 0, 0)
    private val gridRow    = Style.row.gap(16)
    private val infoCard = Style.bg(Color.white).padding(20).rounded(12)
        .shadow(y = 4, blur = 16, c = Color.rgba(0, 0, 0, 0.08))
        .border(1, "#e2e8f0")
    private val badge        = Style.padding(2, 10).rounded(12).fontSize(11).bold
    private val progressBar  = Style.height(8).rounded(4).bg("#e2e8f0")
    private val progressFill = Style.height(8).rounded(4)

    def build: UI < Async =
        for
            _ <- Signal.initRef(0)
        yield div.style(app)(
            header.style(Style.bg("#0f172a").color(Color.white).padding(20, 32))(
                h1("Dashboard"),
                p.style(Style.color("#94a3b8").fontSize(14))("Overview of key metrics")
            ),
            main.style(content)(
                // Stat cards row
                section(
                    h3.style(Style.margin(0, 0, 12, 0))("Key Metrics"),
                    div.style(gridRow)(
                        div.style(statCard)(
                            div.style(statNumber)("1,284"),
                            div.style(statLabel)("Total Users")
                        ),
                        div.style(statCard)(
                            div.style(statNumber)("$48.2K"),
                            div.style(statLabel)("Revenue")
                        ),
                        div.style(statCard)(
                            div.style(statNumber)("92%"),
                            div.style(statLabel)("Uptime")
                        ),
                        div.style(statCard)(
                            div.style(statNumber)("3.2s"),
                            div.style(statLabel)("Avg Response")
                        )
                    )
                ),
                // Shadow depth comparison
                section(
                    h3.style(Style.margin(0, 0, 12, 0))("Shadow Depths"),
                    div.style(gridRow)(
                        div.style(Style.bg(Color.white).padding(16).rounded(8)
                            .shadow(y = 1, blur = 2, c = Color.rgba(0, 0, 0, 0.05)))(
                            p("shadow(y=1, blur=2)"),
                            p.style(Style.fontSize(12).color("#94a3b8"))("Subtle")
                        ),
                        div.style(Style.bg(Color.white).padding(16).rounded(8)
                            .shadow(y = 2, blur = 8, c = Color.rgba(0, 0, 0, 0.12)))(
                            p("shadow(y=2, blur=8)"),
                            p.style(Style.fontSize(12).color("#94a3b8"))("Medium")
                        ),
                        div.style(Style.bg(Color.white).padding(16).rounded(8)
                            .shadow(y = 4, blur = 16, c = Color.rgba(0, 0, 0, 0.2)))(
                            p("shadow(y=4, blur=16)"),
                            p.style(Style.fontSize(12).color("#94a3b8"))("Strong")
                        ),
                        div.style(Style.bg(Color.white).padding(16).rounded(8)
                            .shadow(y = 8, blur = 32, c = Color.rgba(0, 0, 0, 0.3)))(
                            p("shadow(y=8, blur=32)"),
                            p.style(Style.fontSize(12).color("#94a3b8"))("Heavy")
                        )
                    )
                ),
                // Info cards with badges and progress bars
                section(
                    h3.style(Style.margin(0, 0, 12, 0))("Project Status"),
                    div.style(Style.gap(12))(
                        div.style(infoCard)(
                            div.style(Style.row.align(_.center).gap(8))(
                                h4("API Gateway"),
                                span.style(badge ++ Style.bg("#dcfce7").color("#166534"))("Active")
                            ),
                            p.style(Style.color("#64748b").fontSize(13).margin(8, 0))("Processing 1.2K req/s with 99.9% success rate"),
                            div.style(progressBar)(
                                div.style(progressFill ++ Style.bg("#22c55e").width(Size.pct(85)))("")
                            )
                        ),
                        div.style(infoCard)(
                            div.style(Style.row.align(_.center).gap(8))(
                                h4("Database"),
                                span.style(badge ++ Style.bg("#fef3c7").color("#92400e"))("Warning")
                            ),
                            p.style(Style.color("#64748b").fontSize(13).margin(8, 0))("Storage at 78% capacity, consider scaling"),
                            div.style(progressBar)(
                                div.style(progressFill ++ Style.bg("#eab308").width(Size.pct(78)))("")
                            )
                        ),
                        div.style(infoCard)(
                            div.style(Style.row.align(_.center).gap(8))(
                                h4("CDN"),
                                span.style(badge ++ Style.bg("#fee2e2").color("#991b1b"))("Down")
                            ),
                            p.style(Style.color("#64748b").fontSize(13).margin(8, 0))("Outage detected in EU-west region"),
                            div.style(progressBar)(
                                div.style(progressFill ++ Style.bg("#ef4444").width(Size.pct(15)))("")
                            )
                        )
                    )
                ),
                // Width demo
                section(
                    h3.style(Style.margin(0, 0, 12, 0))("Explicit Widths"),
                    div.style(Style.gap(8))(
                        div.style(Style.bg("#dbeafe").padding(8).rounded(4).width(100))("width(100)"),
                        div.style(Style.bg("#dbeafe").padding(8).rounded(4).width(200))("width(200)"),
                        div.style(Style.bg("#dbeafe").padding(8).rounded(4).width(400))("width(400)")
                    )
                )
            )
        )

end DashboardUI
