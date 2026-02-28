package demo

import kyo.*
import scala.language.implicitConversions

object DynamicStyleUI extends UIScope:

    import DemoStyles.app
    import DemoStyles.card
    import DemoStyles.content

    private val btn = Style.padding(8, 16).rounded(4).border(1, "#ccc").cursor(_.pointer)

    def build: UI < Async =
        for
            bgColor     <- Signal.initRef("#dbeafe")
            textSize    <- Signal.initRef(14)
            padding_    <- Signal.initRef(12)
            isBold      <- Signal.initRef(false)
            isItalic    <- Signal.initRef(false)
            isUnderline <- Signal.initRef(false)
            borderW     <- Signal.initRef(1)
        yield div.style(app)(
            header.style(Style.bg("#9333ea").color(Color.white).padding(16, 32))(
                h1("Dynamic Styles Showcase")
            ),
            main.style(content)(
                // Dynamic background color
                section.style(card)(
                    h3("Dynamic Background"),
                    div.style(Style.row.gap(8).margin(0, 0, 8, 0))(
                        button.style(btn).onClick(bgColor.set("#dbeafe"))("Blue"),
                        button.style(btn).onClick(bgColor.set("#dcfce7"))("Green"),
                        button.style(btn).onClick(bgColor.set("#fef3c7"))("Yellow"),
                        button.style(btn).onClick(bgColor.set("#fee2e2"))("Red"),
                        button.style(btn).onClick(bgColor.set("#f3e8ff"))("Purple")
                    ),
                    div.style(Style.padding(20).rounded(8).textAlign(_.center)
                        .fontSize(16).bold).style(bgColor.map(c => s"background-color: $c;"))(
                        p("This box changes background color"),
                        p.style(Style.fontSize(12).color("#64748b"))(bgColor.map(c => s"Current: $c"))
                    )
                ),
                // Dynamic font size
                section.style(card)(
                    h3("Dynamic Font Size"),
                    div.style(Style.row.gap(8).align(_.center).margin(0, 0, 8, 0))(
                        button.style(btn).onClick(textSize.getAndUpdate(s => Math.max(8, s - 2)).unit)("A-"),
                        span.style(Style.minWidth(60).textAlign(_.center))(textSize.map(s => s"${s}px")),
                        button.style(btn).onClick(textSize.getAndUpdate(s => Math.min(48, s + 2)).unit)("A+")
                    ),
                    div.style(Style.padding(16).bg("#f8fafc").rounded(8))
                        .style(textSize.map(s => s"font-size: ${s}px;"))(
                            p("The quick brown fox jumps over the lazy dog.")
                        )
                ),
                // Dynamic padding
                section.style(card)(
                    h3("Dynamic Padding"),
                    div.style(Style.row.gap(8).align(_.center).margin(0, 0, 8, 0))(
                        button.style(btn).onClick(padding_.getAndUpdate(p => Math.max(0, p - 4)).unit)("Less"),
                        span.style(Style.minWidth(60).textAlign(_.center))(padding_.map(p => s"${p}px")),
                        button.style(btn).onClick(padding_.getAndUpdate(p => Math.min(48, p + 4)).unit)("More")
                    ),
                    div.style(Style.bg("#e0e7ff").rounded(8).border(1, "#93c5fd"))
                        .style(padding_.map(p => s"padding: ${p}px;"))(
                            p("Content with dynamic padding")
                        )
                ),
                // Toggle multiple styles
                section.style(card)(
                    h3("Style Toggles"),
                    div.style(Style.row.gap(8).margin(0, 0, 8, 0))(
                        button.style(btn).onClick(isBold.getAndUpdate(!_).unit)(
                            isBold.map(v => if v then "Bold: ON" else "Bold: OFF")
                        ),
                        button.style(btn).onClick(isItalic.getAndUpdate(!_).unit)(
                            isItalic.map(v => if v then "Italic: ON" else "Italic: OFF")
                        ),
                        button.style(btn).onClick(isUnderline.getAndUpdate(!_).unit)(
                            isUnderline.map(v => if v then "Underline: ON" else "Underline: OFF")
                        )
                    ),
                    div.style(Style.padding(16).bg("#f8fafc").rounded(8).fontSize(18))
                        .style(isBold.map(b => if b then "font-weight: bold;" else "font-weight: normal;"))
                        .style(isItalic.map(i => if i then "font-style: italic;" else "font-style: normal;"))
                        .style(isUnderline.map(u => if u then "text-decoration: underline;" else "text-decoration: none;"))(
                            p("This text responds to style toggles above.")
                        )
                ),
                // Dynamic border
                section.style(card)(
                    h3("Dynamic Border Width"),
                    div.style(Style.row.gap(8).align(_.center).margin(0, 0, 8, 0))(
                        button.style(btn).onClick(borderW.getAndUpdate(w => Math.max(0, w - 1)).unit)("Thinner"),
                        span.style(Style.minWidth(40).textAlign(_.center))(borderW.map(w => s"${w}px")),
                        button.style(btn).onClick(borderW.getAndUpdate(w => Math.min(10, w + 1)).unit)("Thicker")
                    ),
                    div.style(Style.padding(16).rounded(8).bg("#f0fdf4"))
                        .style(borderW.map(w => s"border: ${w}px solid #10b981;"))(
                            p("Box with dynamic border width")
                        )
                )
            )
        )

end DynamicStyleUI
