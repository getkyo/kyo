package demo

import kyo.*
import scala.language.implicitConversions

object TransformsUI extends UIScope:

    import DemoStyles.app
    import DemoStyles.card
    import DemoStyles.content

    private val box = Style.bg("#3b82f6").color(Color.white).padding(12, 20).rounded(6).textAlign(_.center)

    def build: UI < Async =
        for
            _ <- Signal.initRef(0)
        yield div.style(app)(
            header.style(Style.bg("#dc2626").color(Color.white).padding(16, 32))(
                h1("Transforms & Effects")
            ),
            main.style(content)(
                // Translate
                section.style(card)(
                    h3("Translate"),
                    div.style(Style.gap(12).padding(16))(
                        div.style(box)("No transform"),
                        div.style(box ++ Style.translate(20, 0))("translate(20, 0)"),
                        div.style(box ++ Style.translate(40, 10))("translate(40, 10)"),
                        div.style(box ++ Style.translate(0, 20))("translate(0, 20)")
                    )
                ),
                // Opacity
                section.style(card)(
                    h3("Opacity Levels"),
                    div.style(Style.row.gap(12))(
                        div.style(box ++ Style.opacity(1.0))("1.0"),
                        div.style(box ++ Style.opacity(0.8))("0.8"),
                        div.style(box ++ Style.opacity(0.6))("0.6"),
                        div.style(box ++ Style.opacity(0.4))("0.4"),
                        div.style(box ++ Style.opacity(0.2))("0.2")
                    )
                ),
                // Combined translate + opacity
                section.style(card)(
                    h3("Combined: Translate + Opacity"),
                    div.style(Style.gap(8).padding(16))(
                        div.style(box ++ Style.translate(0, 0) ++ Style.opacity(1.0))("origin, opacity=1.0"),
                        div.style(box ++ Style.translate(30, 0) ++ Style.opacity(0.7))("translate(30,0), opacity=0.7"),
                        div.style(box ++ Style.translate(60, 0) ++ Style.opacity(0.4))("translate(60,0), opacity=0.4")
                    )
                ),
                // Opacity on text
                section.style(card)(
                    h3("Opacity on Text Elements"),
                    p.style(Style.opacity(1.0).fontSize(16))("Full opacity text (1.0)"),
                    p.style(Style.opacity(0.7).fontSize(16))("Reduced opacity text (0.7)"),
                    p.style(Style.opacity(0.4).fontSize(16))("Faded opacity text (0.4)"),
                    p.style(Style.opacity(0.15).fontSize(16))("Nearly invisible text (0.15)")
                ),
                // Translate with overflow hidden
                section.style(card)(
                    h3("Translate + Overflow Hidden"),
                    p.style(Style.fontSize(13).color("#64748b"))("Translated element inside a clipped container:"),
                    div.style(Style.height(60).overflow(_.hidden).border(1, "#e2e8f0").rounded(4).padding(8))(
                        div.style(box ++ Style.translate(10, 30))("I'm shifted down and partially clipped")
                    )
                ),
                // Shadow + opacity
                section.style(card)(
                    h3("Shadow + Opacity"),
                    div.style(Style.row.gap(16))(
                        div.style(Style.bg(Color.white).padding(16).rounded(8)
                            .shadow(y = 4, blur = 12, c = Color.rgba(0, 0, 0, 0.2))
                            .opacity(1.0))(
                            p("opacity=1.0"),
                            p.style(Style.fontSize(12).color("#94a3b8"))("Full shadow")
                        ),
                        div.style(Style.bg(Color.white).padding(16).rounded(8)
                            .shadow(y = 4, blur = 12, c = Color.rgba(0, 0, 0, 0.2))
                            .opacity(0.5))(
                            p("opacity=0.5"),
                            p.style(Style.fontSize(12).color("#94a3b8"))("Faded shadow")
                        )
                    )
                )
            )
        )

end TransformsUI
