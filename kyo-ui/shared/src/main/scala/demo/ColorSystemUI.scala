package demo

import kyo.*
import scala.language.implicitConversions

object ColorSystemUI extends UIScope:

    import DemoStyles.app
    import DemoStyles.card
    import DemoStyles.content

    private val swatch = Style.width(60).height(60).rounded(8).textAlign(_.center)
        .fontSize(10).color(Color.white).padding(4).bold

    def build: UI < Async =
        for
            _ <- Signal.initRef(0)
        yield div.style(app)(
            header.style(Style.bg(Color.indigo).color(Color.white).padding(16, 32))(
                h1("Color System Showcase")
            ),
            main.style(content)(
                // Predefined colors
                section.style(card)(
                    h3("Predefined Colors"),
                    div.style(Style.row.gap(8))(
                        div.style(swatch ++ Style.bg(Color.red))("red"),
                        div.style(swatch ++ Style.bg(Color.orange))("orange"),
                        div.style(swatch ++ Style.bg(Color.yellow) ++ Style.color(Color.black))("yellow"),
                        div.style(swatch ++ Style.bg(Color.green))("green"),
                        div.style(swatch ++ Style.bg(Color.blue))("blue"),
                        div.style(swatch ++ Style.bg(Color.indigo))("indigo"),
                        div.style(swatch ++ Style.bg(Color.purple))("purple"),
                        div.style(swatch ++ Style.bg(Color.pink))("pink")
                    ),
                    div.style(Style.row.gap(8).margin(8, 0, 0, 0))(
                        div.style(swatch ++ Style.bg(Color.gray))("gray"),
                        div.style(swatch ++ Style.bg(Color.slate))("slate"),
                        div.style(swatch ++ Style.bg(Color.black))("black"),
                        div.style(swatch ++ Style.bg(Color.white) ++ Style.color(Color.black) ++ Style.border(1, "#ccc"))("white")
                    )
                ),
                // RGB colors
                section.style(card)(
                    h3("Color.rgb()"),
                    div.style(Style.row.gap(8))(
                        div.style(swatch ++ Style.bg(Color.rgb(255, 0, 0)))("255,0,0"),
                        div.style(swatch ++ Style.bg(Color.rgb(0, 255, 0)) ++ Style.color(Color.black))("0,255,0"),
                        div.style(swatch ++ Style.bg(Color.rgb(0, 0, 255)))("0,0,255"),
                        div.style(swatch ++ Style.bg(Color.rgb(255, 165, 0)))("255,165,0"),
                        div.style(swatch ++ Style.bg(Color.rgb(128, 0, 128)))("128,0,128")
                    )
                ),
                // RGBA with alpha
                section.style(card)(
                    h3("Color.rgba() — Alpha Channel"),
                    p.style(Style.fontSize(13).color("#64748b").margin(0, 0, 8, 0))("Same blue with decreasing alpha on gray background:"),
                    div.style(Style.bg("#e2e8f0").padding(16).rounded(8))(
                        div.style(Style.row.gap(8))(
                            div.style(swatch ++ Style.bg(Color.rgba(37, 99, 235, 1.0)))("a=1.0"),
                            div.style(swatch ++ Style.bg(Color.rgba(37, 99, 235, 0.8)))("a=0.8"),
                            div.style(swatch ++ Style.bg(Color.rgba(37, 99, 235, 0.6)))("a=0.6"),
                            div.style(swatch ++ Style.bg(Color.rgba(37, 99, 235, 0.4)))("a=0.4"),
                            div.style(swatch ++ Style.bg(Color.rgba(37, 99, 235, 0.2)))("a=0.2")
                        )
                    )
                ),
                // Hex colors
                section.style(card)(
                    h3("Color.hex()"),
                    div.style(Style.row.gap(8))(
                        div.style(swatch ++ Style.bg(Color.hex("#ef4444")))("#ef4444"),
                        div.style(swatch ++ Style.bg(Color.hex("#f97316")))("#f97316"),
                        div.style(swatch ++ Style.bg(Color.hex("#eab308")) ++ Style.color(Color.black))("#eab308"),
                        div.style(swatch ++ Style.bg(Color.hex("#22c55e")))("#22c55e"),
                        div.style(swatch ++ Style.bg(Color.hex("#3b82f6")))("#3b82f6"),
                        div.style(swatch ++ Style.bg(Color.hex("#8b5cf6")))("#8b5cf6")
                    )
                ),
                // Text color contrast
                section.style(card)(
                    h3("Text Color on Backgrounds"),
                    div.style(Style.gap(4))(
                        div.style(Style.bg("#1e293b").color(Color.white).padding(12).rounded(4))("White on dark slate"),
                        div.style(Style.bg("#fefce8").color("#854d0e").padding(12).rounded(4))("Amber text on light yellow"),
                        div.style(Style.bg("#f0fdf4").color("#166534").padding(12).rounded(4))("Green text on light green"),
                        div.style(Style.bg("#eff6ff").color("#1e40af").padding(12).rounded(4))("Blue text on light blue"),
                        div.style(Style.bg("#fef2f2").color("#991b1b").padding(12).rounded(4))("Red text on light red")
                    )
                ),
                // Transparent
                section.style(card)(
                    h3("Transparent Background"),
                    div.style(Style.bg("#3b82f6").padding(16).rounded(8))(
                        div.style(Style.bg(Color.transparent).border(2, Color.white).color(Color.white)
                            .padding(12).rounded(4))(
                            "transparent bg with white border"
                        ),
                        div.style(Style.bg(Color.rgba(255, 255, 255, 0.3)).color(Color.white)
                            .padding(12).rounded(4).margin(8, 0, 0, 0))(
                            "rgba(255,255,255,0.3) semi-transparent overlay"
                        )
                    )
                )
            )
        )

end ColorSystemUI
