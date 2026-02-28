package demo

import kyo.*
import scala.language.implicitConversions

object SizingUnitsUI extends UIScope:

    import DemoStyles.app
    import DemoStyles.card
    import DemoStyles.content

    private val box    = Style.bg("#dbeafe").padding(8).rounded(4).textAlign(_.center)
    private val label_ = Style.fontSize(12).color("#64748b").margin(0, 0, 4, 0)

    def build: UI < Async =
        for
            _ <- Signal.initRef(0)
        yield div.style(app)(
            header.style(Style.bg("#0d9488").color(Color.white).padding(16, 32))(
                h1("Sizing & Units Showcase")
            ),
            main.style(content)(
                // Pixel widths
                section.style(card)(
                    h3("Width in Pixels"),
                    div.style(Style.gap(8))(
                        div.style(box ++ Style.width(50))("50px"),
                        div.style(box ++ Style.width(100))("100px"),
                        div.style(box ++ Style.width(200))("200px"),
                        div.style(box ++ Style.width(400))("400px")
                    )
                ),
                // Percentage widths
                section.style(card)(
                    h3("Width in Percent"),
                    div.style(Style.gap(8))(
                        p.style(label_)("width(25.pct):"),
                        div.style(box ++ Style.width(25.pct))("25%"),
                        p.style(label_)("width(50.pct):"),
                        div.style(box ++ Style.width(50.pct))("50%"),
                        p.style(label_)("width(75.pct):"),
                        div.style(box ++ Style.width(75.pct))("75%"),
                        p.style(label_)("width(100.pct):"),
                        div.style(box ++ Style.width(100.pct))("100%")
                    )
                ),
                // Em units
                section.style(card)(
                    h3("Em Units"),
                    div.style(Style.gap(8))(
                        p.style(label_)("fontSize(1.em) — default:"),
                        p.style(Style.fontSize(1.em))("Text at 1em"),
                        p.style(label_)("fontSize(1.5.em):"),
                        p.style(Style.fontSize(1.5.em))("Text at 1.5em"),
                        p.style(label_)("fontSize(2.em):"),
                        p.style(Style.fontSize(2.em))("Text at 2em"),
                        p.style(label_)("padding(1.em):"),
                        div.style(Style.bg("#e0e7ff").padding(1.em).rounded(4))("Padded with 1em")
                    )
                ),
                // Margin auto centering
                section.style(card)(
                    h3("Margin Auto Centering"),
                    p.style(label_)("margin(0, auto) centers the box:"),
                    div.style(Style.bg("#fef3c7").padding(12).rounded(4).width(200)
                        .margin(Size.Px(0), Size.auto).textAlign(_.center))(
                        "Centered (200px)"
                    ),
                    div.style(Style.bg("#dcfce7").padding(12).rounded(4).width(300)
                        .margin(Size.Px(8), Size.auto).textAlign(_.center))(
                        "Centered (300px)"
                    )
                ),
                // Height variants
                section.style(card)(
                    h3("Height Variants"),
                    div.style(Style.row.gap(12).align(_.start))(
                        div.style(Style.bg("#e0e7ff").padding(8).rounded(4).width(120).height(40).textAlign(_.center))(
                            "height(40)"
                        ),
                        div.style(Style.bg("#e0e7ff").padding(8).rounded(4).width(120).height(80).textAlign(_.center))(
                            "height(80)"
                        ),
                        div.style(Style.bg("#e0e7ff").padding(8).rounded(4).width(120).height(120).textAlign(_.center))(
                            "height(120)"
                        )
                    )
                ),
                // Min/Max width
                section.style(card)(
                    h3("Min/Max Width"),
                    div.style(Style.gap(8))(
                        p.style(label_)("minWidth(200) on narrow content:"),
                        div.style(box ++ Style.minWidth(200))("Hi"),
                        p.style(label_)("maxWidth(150) on wide content:"),
                        div.style(box ++ Style.maxWidth(150))("This text is constrained to 150px max width"),
                        p.style(label_)("minWidth(100) + maxWidth(300):"),
                        div.style(box ++ Style.minWidth(100) ++ Style.maxWidth(300))("Bounded width")
                    )
                ),
                // Mixed units
                section.style(card)(
                    h3("Mixed Units in Padding"),
                    div.style(Style.gap(8))(
                        div.style(Style.bg("#fef3c7").padding(8).rounded(4))("padding(8) — pixels"),
                        div.style(Style.bg("#fef3c7").padding(1.em).rounded(4))("padding(1.em) — em"),
                        div.style(Style.bg("#fef3c7").padding(Size.Px(4), Size.Px(32), Size.Px(4), Size.Px(32)).rounded(4))(
                            "padding(4, 32, 4, 32) — px"
                        )
                    )
                )
            )
        )

end SizingUnitsUI
