package demo
import kyo.*
import kyo.UIDsl.*
import scala.language.implicitConversions

/** Shadow variants, maxWidth/minWidth/minHeight, overflow, opacity, borderStyle variants, textOverflow, justify+align. */
object ResponsiveCardUI:

    import DemoStyles.app
    import DemoStyles.content

    private val sectionTitle = Style.fontSize(18).bold.margin(0, 0, 8, 0)
    private val sectionDesc  = Style.fontSize(13).color("#64748b").margin(0, 0, 12, 0)

    def build: UI < Async =
        for
            expandedCard <- Signal.initRef("")
        yield div.style(app)(
            header.style(Style.bg("#dc2626").color(Color.white).padding(16, 32))(
                h1("Responsive Cards")
            ),
            main.style(content)(
                // Section 1: Shadow depth gallery
                section.cls("shadow-gallery").style(Style.gap(12))(
                    h3.style(sectionTitle)("Shadow Depths"),
                    p.style(sectionDesc)("Four shadow depth variants with hover lift:"),
                    div.style(Style.row.gap(16))(
                        div.cls("shadow-none").style(
                            Style.padding(24).bg(Color.white).rounded(8).minWidth(140).textAlign(_.center)
                                .border(1, "#e2e8f0")
                                .hover(Style.translate(0, -2).shadow(y = 4, blur = 8, c = Color.rgba(0, 0, 0, 0.1)))
                        )(
                            p.style(Style.bold)("None"),
                            p.style(Style.fontSize(12).color("#94a3b8"))("No shadow")
                        ),
                        div.cls("shadow-sm").style(
                            Style.padding(24).bg(Color.white).rounded(8).minWidth(140).textAlign(_.center)
                                .shadow(y = 1, blur = 2, c = Color.rgba(0, 0, 0, 0.05))
                                .hover(Style.translate(0, -2).shadow(y = 4, blur = 8, c = Color.rgba(0, 0, 0, 0.1)))
                        )(
                            p.style(Style.bold)("Subtle"),
                            p.style(Style.fontSize(12).color("#94a3b8"))("shadow(1,2)")
                        ),
                        div.cls("shadow-md").style(
                            Style.padding(24).bg(Color.white).rounded(8).minWidth(140).textAlign(_.center)
                                .shadow(y = 4, blur = 6, c = Color.rgba(0, 0, 0, 0.1))
                                .hover(Style.translate(0, -2).shadow(y = 8, blur = 16, c = Color.rgba(0, 0, 0, 0.15)))
                        )(
                            p.style(Style.bold)("Medium"),
                            p.style(Style.fontSize(12).color("#94a3b8"))("shadow(4,6)")
                        ),
                        div.cls("shadow-lg").style(
                            Style.padding(24).bg(Color.white).rounded(8).minWidth(140).textAlign(_.center)
                                .shadow(y = 10, blur = 25, c = Color.rgba(0, 0, 0, 0.2))
                                .hover(Style.translate(0, -4).shadow(y = 20, blur = 40, c = Color.rgba(0, 0, 0, 0.25)))
                        )(
                            p.style(Style.bold)("Dramatic"),
                            p.style(Style.fontSize(12).color("#94a3b8"))("shadow(10,25)")
                        )
                    )
                ),

                // Section 2: Pricing cards with justify + align + sizing
                section.cls("pricing-cards").style(Style.gap(12))(
                    h3.style(sectionTitle)("Pricing Cards"),
                    p.style(sectionDesc)("justify(spaceBetween) vertical layout with minHeight and maxWidth:"),
                    div.style(Style.row.gap(16).align(_.stretch))(
                        // Basic plan
                        div.cls("plan-basic").style(
                            Style.bg(Color.white).padding(24).rounded(12).border(1, "#e2e8f0")
                                .maxWidth(220).minHeight(250).justify(_.spaceBetween)
                                .shadow(y = 2, blur = 4, c = Color.rgba(0, 0, 0, 0.05))
                                .opacity(0.85).hover(Style.opacity(1.0))
                        )(
                            div(
                                h4.style(Style.fontSize(16).bold)("Basic"),
                                p.style(Style.fontSize(13).color("#64748b"))("For individuals")
                            ),
                            p.style(Style.fontSize(32).bold.textAlign(_.center).margin(16, 0))("$9"),
                            button.style(Style.bg("#e2e8f0").padding(8, 16).rounded(6).borderStyle(_.none)
                                .cursor(_.pointer).width(Size.pct(100)))("Get Started")
                        ),
                        // Pro plan (featured)
                        div.cls("plan-pro").style(
                            Style.bg("#1e293b").color(Color.white).padding(24).rounded(12)
                                .maxWidth(220).minHeight(250).justify(_.spaceBetween)
                                .shadow(y = 4, blur = 12, c = Color.rgba(0, 0, 0, 0.2))
                        )(
                            div(
                                h4.style(Style.fontSize(16).bold)("Pro"),
                                p.style(Style.fontSize(13).color("#94a3b8"))("For teams")
                            ),
                            p.style(Style.fontSize(32).bold.textAlign(_.center).margin(16, 0))("$29"),
                            button.style(Style.bg("#3b82f6").color(Color.white).padding(8, 16).rounded(6)
                                .borderStyle(_.none).cursor(_.pointer).width(Size.pct(100)))("Get Started")
                        ),
                        // Enterprise plan
                        div.cls("plan-enterprise").style(
                            Style.bg(Color.white).padding(24).rounded(12).border(1, "#e2e8f0")
                                .maxWidth(220).minHeight(250).justify(_.spaceBetween)
                                .shadow(y = 2, blur = 4, c = Color.rgba(0, 0, 0, 0.05))
                                .opacity(0.85).hover(Style.opacity(1.0))
                        )(
                            div(
                                h4.style(Style.fontSize(16).bold)("Enterprise"),
                                p.style(Style.fontSize(13).color("#64748b"))("Custom solutions")
                            ),
                            p.style(Style.fontSize(32).bold.textAlign(_.center).margin(16, 0))("$99"),
                            button.style(Style.bg("#e2e8f0").padding(8, 16).rounded(6).borderStyle(_.none)
                                .cursor(_.pointer).width(Size.pct(100)))("Contact Us")
                        )
                    )
                ),

                // Section 3: Notification cards with border style variants
                section.cls("notification-cards").style(Style.gap(12))(
                    h3.style(sectionTitle)("Notification Cards"),
                    p.style(sectionDesc)("Border style variants (solid, dashed, dotted) with severity colors:"),
                    div.style(Style.gap(8))(
                        div.cls("notif-info").style(
                            Style.padding(12, 16).bg("#eff6ff").rounded(4).borderLeft(4, "#3b82f6")
                                .borderStyle(_.solid)
                        )(
                            p.style(Style.bold.fontSize(13))("Info"),
                            p.style(Style.fontSize(12).color("#64748b"))("System update available. Solid left border.")
                        ),
                        div.cls("notif-warn").style(
                            Style.padding(12, 16).bg("#fffbeb").rounded(4).borderLeft(4, "#f59e0b")
                                .borderStyle(_.dashed)
                        )(
                            p.style(Style.bold.fontSize(13))("Warning"),
                            p.style(Style.fontSize(12).color("#64748b"))("Disk space running low. Dashed border style.")
                        ),
                        div.cls("notif-error").style(
                            Style.padding(12, 16).bg("#fef2f2").rounded(4).borderLeft(4, "#ef4444")
                                .borderStyle(_.dotted)
                        )(
                            p.style(Style.bold.fontSize(13))("Error"),
                            p.style(Style.fontSize(12).color("#64748b"))("Connection failed. Dotted border style.")
                        )
                    )
                ),

                // Section 4: Truncated content with text overflow
                section.cls("truncation").style(Style.gap(12))(
                    h3.style(sectionTitle)("Content Truncation"),
                    p.style(sectionDesc)("textOverflow(ellipsis) + overflow(hidden) + maxHeight, expandable via click:"),
                    div.style(Style.gap(8))(
                        div.cls("card-a").style(
                            Style.bg(Color.white).padding(16).rounded(8).border(1, "#e2e8f0").cursor(_.pointer)
                                .shadow(y = 1, blur = 3, c = Color.rgba(0, 0, 0, 0.1))
                        ).onClick(expandedCard.getAndUpdate(c => if c == "a" then "" else "a").unit)(
                            p.style(Style.bold.fontSize(14))("Long Article"),
                            div.style(Style.overflow(_.hidden))
                                .style(expandedCard.map(c => s"max-height: ${if c == "a" then "none" else "48px"}"))(
                                    p.style(Style.fontSize(13).color("#374151").lineHeight(1.6))(
                                        "Lorem ipsum dolor sit amet, consectetur adipiscing elit. " +
                                            "Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. " +
                                            "Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris. " +
                                            "Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore."
                                    )
                                ),
                            p.cls("expand-hint").style(Style.fontSize(12).color("#3b82f6").margin(4, 0, 0, 0))(
                                expandedCard.map(c => if c == "a" then "Click to collapse" else "Click to expand...")
                            )
                        ),
                        div.cls("card-b").style(
                            Style.bg(Color.white).padding(16).rounded(8).border(1, "#e2e8f0").cursor(_.pointer)
                                .shadow(y = 1, blur = 3, c = Color.rgba(0, 0, 0, 0.1))
                        ).onClick(expandedCard.getAndUpdate(c => if c == "b" then "" else "b").unit)(
                            p.style(Style.bold.fontSize(14))("Technical Summary"),
                            div.style(Style.overflow(_.hidden))
                                .style(expandedCard.map(c => s"max-height: ${if c == "b" then "none" else "48px"}"))(
                                    p.style(Style.fontSize(13).color("#374151").lineHeight(1.6))(
                                        "The reactive UI framework provides a declarative approach to building " +
                                            "user interfaces with automatic update propagation. Signals form the core " +
                                            "primitive, enabling fine-grained reactivity without manual subscriptions. " +
                                            "Components compose naturally through a typed DSL."
                                    )
                                ),
                            p.cls("expand-hint-b").style(Style.fontSize(12).color("#3b82f6").margin(4, 0, 0, 0))(
                                expandedCard.map(c => if c == "b" then "Click to collapse" else "Click to expand...")
                            )
                        )
                    )
                )
            )
        )

end ResponsiveCardUI
