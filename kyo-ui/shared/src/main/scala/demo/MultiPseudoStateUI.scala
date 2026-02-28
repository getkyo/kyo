package demo

import kyo.*
import scala.language.implicitConversions

object MultiPseudoStateUI extends UIScope:

    import DemoStyles.app
    import DemoStyles.card
    import DemoStyles.content

    def build: UI < Async =
        for
            _ <- Signal.initRef(0)
        yield div.style(app)(
            header.style(Style.bg("#0891b2").color(Color.white).padding(16, 32))(
                h1("Pseudo States & Borders")
            ),
            main.style(content)(
                // Combined hover + focus + active
                section.style(card)(
                    h3("Combined Pseudo States"),
                    p.style(Style.fontSize(13).color("#64748b").margin(0, 0, 8, 0))(
                        "Each button has hover + active states. Inputs have focus states."
                    ),
                    div.style(Style.row.gap(12))(
                        button.style(
                            Style.bg("#3b82f6").color(Color.white).padding(10, 24).rounded(6)
                                .borderStyle(_.none).cursor(_.pointer)
                                .hover(Style.bg("#2563eb"))
                                .active(Style.bg("#1d4ed8"))
                        )("Hover + Active"),
                        button.style(
                            Style.bg(Color.white).color("#374151").padding(10, 24).rounded(6)
                                .border(1, "#d1d5db").cursor(_.pointer)
                                .hover(Style.bg("#f9fafb").borderColor("#9ca3af"))
                                .active(Style.bg("#f3f4f6"))
                        )("Outlined"),
                        button.style(
                            Style.bg("#10b981").color(Color.white).padding(10, 24).rounded(6)
                                .borderStyle(_.none).cursor(_.pointer)
                                .shadow(y = 2, blur = 4, c = Color.rgba(16, 185, 129, 0.4))
                                .hover(Style.shadow(y = 4, blur = 8, c = Color.rgba(16, 185, 129, 0.4)))
                                .active(Style.shadow(y = 1, blur = 2, c = Color.rgba(16, 185, 129, 0.4)))
                        )("Shadow Shift")
                    ),
                    div.style(Style.gap(8).margin(12, 0, 0, 0))(
                        input.placeholder("Focus me for blue border").style(
                            Style.padding(8, 12).rounded(6).border(1, "#d1d5db")
                                .focus(Style.border(2, "#3b82f6"))
                        ),
                        input.placeholder("Focus me for green border").style(
                            Style.padding(8, 12).rounded(6).border(1, "#d1d5db")
                                .focus(Style.border(2, "#10b981"))
                        )
                    )
                ),
                // Style.++ composition
                section.style(card)(
                    h3("Style.++ Composition"),
                    p.style(Style.fontSize(13).color("#64748b").margin(0, 0, 8, 0))(
                        "Styles combined with ++ operator:"
                    ),
                    div.style(Style.gap(8))(
                        div.style(
                            Style.bg("#fef3c7") ++ Style.padding(12) ++ Style.rounded(6) ++ Style.bold
                        )("bg ++ padding ++ rounded ++ bold"),
                        div.style(
                            Style.border(2, "#3b82f6") ++ Style.color("#3b82f6") ++ Style.padding(12) ++ Style.rounded(6)
                        )("border ++ color ++ padding ++ rounded"),
                        div.style(
                            Style.italic ++ Style.underline ++ Style.fontSize(18) ++ Style.padding(12)
                        )("italic ++ underline ++ fontSize(18)")
                    )
                ),
                // Border styles: dashed, dotted, solid
                section.style(card)(
                    h3("Border Styles"),
                    div.style(Style.gap(12))(
                        div.style(Style.border(2, BorderStyle.solid, Color.hex("#3b82f6")).padding(12).rounded(4))(
                            "border(2, solid, blue)"
                        ),
                        div.style(Style.border(2, BorderStyle.dashed, Color.hex("#10b981")).padding(12).rounded(4))(
                            "border(2, dashed, green)"
                        ),
                        div.style(Style.border(2, BorderStyle.dotted, Color.hex("#f59e0b")).padding(12).rounded(4))(
                            "border(2, dotted, amber)"
                        ),
                        div.style(Style.borderStyle(_.none).bg("#f3f4f6").padding(12).rounded(4))(
                            "borderStyle(none) with bg"
                        )
                    )
                ),
                // Individual border sides
                section.style(card)(
                    h3("Individual Border Sides"),
                    div.style(Style.gap(12))(
                        div.style(Style.borderTop(3, "#ef4444").padding(12).bg("#fef2f2"))(
                            "borderTop(3, red)"
                        ),
                        div.style(Style.borderRight(3, "#3b82f6").padding(12).bg("#eff6ff"))(
                            "borderRight(3, blue)"
                        ),
                        div.style(Style.borderBottom(3, "#10b981").padding(12).bg("#f0fdf4"))(
                            "borderBottom(3, green)"
                        ),
                        div.style(Style.borderLeft(3, "#f59e0b").padding(12).bg("#fffbeb"))(
                            "borderLeft(3, amber)"
                        ),
                        div.style(
                            Style.borderTop(2, "#ef4444")
                                .borderRight(2, "#3b82f6")
                                .borderBottom(2, "#10b981")
                                .borderLeft(2, "#f59e0b")
                                .padding(12)
                        )("All four sides different colors")
                    )
                ),
                // Hover on containers
                section.style(card)(
                    h3("Hoverable Cards"),
                    div.style(Style.row.gap(12))(
                        div.style(
                            Style.bg(Color.white).padding(16).rounded(8)
                                .border(1, "#e2e8f0")
                                .shadow(y = 1, blur = 3, c = Color.rgba(0, 0, 0, 0.1))
                                .cursor(_.pointer)
                                .hover(Style.shadow(y = 4, blur = 12, c = Color.rgba(0, 0, 0, 0.15)).border(1, "#93c5fd"))
                        )(
                            h4("Card A"),
                            p.style(Style.fontSize(13).color("#64748b"))("Hover for elevated shadow")
                        ),
                        div.style(
                            Style.bg(Color.white).padding(16).rounded(8)
                                .border(1, "#e2e8f0")
                                .cursor(_.pointer)
                                .hover(Style.bg("#f8fafc").borderColor("#6366f1"))
                        )(
                            h4("Card B"),
                            p.style(Style.fontSize(13).color("#64748b"))("Hover for bg + border change")
                        )
                    )
                )
            )
        )

end MultiPseudoStateUI
