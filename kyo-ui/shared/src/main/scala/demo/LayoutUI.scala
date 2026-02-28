package demo

import kyo.*
import scala.language.implicitConversions

object LayoutUI extends UIScope:

    import DemoStyles.app
    import DemoStyles.card
    import DemoStyles.content

    private val box = Style.bg("#e0e7ff").padding(8, 16).rounded(4).textAlign(_.center)

    def build: UI < Async =
        for
            _ <- Signal.initRef(0)
        yield div.style(app)(
            header.style(Style.bg("#ea580c").color(Color.white).padding(16, 32))(
                h1("Layout Showcase")
            ),
            main.style(content)(
                // Explicit column
                section.style(card)(
                    h3("Explicit Column (default)"),
                    div.style(Style.column.gap(8))(
                        div.style(box)("Item 1"),
                        div.style(box)("Item 2"),
                        div.style(box)("Item 3")
                    )
                ),
                // Justify variants
                section.style(card)(
                    h3("Justify Content"),
                    label("justify(_.start):"),
                    div.style(Style.row.gap(8).justify(_.start).bg("#f9fafb").padding(8))(
                        div.style(box)("A"),
                        div.style(box)("B"),
                        div.style(box)("C")
                    ),
                    label("justify(_.center):"),
                    div.style(Style.row.gap(8).justify(_.center).bg("#f9fafb").padding(8))(
                        div.style(box)("A"),
                        div.style(box)("B"),
                        div.style(box)("C")
                    ),
                    label("justify(_.end):"),
                    div.style(Style.row.gap(8).justify(_.end).bg("#f9fafb").padding(8))(
                        div.style(box)("A"),
                        div.style(box)("B"),
                        div.style(box)("C")
                    ),
                    label("justify(_.spaceBetween):"),
                    div.style(Style.row.justify(_.spaceBetween).bg("#f9fafb").padding(8))(
                        div.style(box)("A"),
                        div.style(box)("B"),
                        div.style(box)("C")
                    ),
                    label("justify(_.spaceAround):"),
                    div.style(Style.row.justify(_.spaceAround).bg("#f9fafb").padding(8))(
                        div.style(box)("A"),
                        div.style(box)("B"),
                        div.style(box)("C")
                    ),
                    label("justify(_.spaceEvenly):"),
                    div.style(Style.row.justify(_.spaceEvenly).bg("#f9fafb").padding(8))(
                        div.style(box)("A"),
                        div.style(box)("B"),
                        div.style(box)("C")
                    )
                ),
                // Align variants
                section.style(card)(
                    h3("Align Items"),
                    label("align(_.start):"),
                    div.style(Style.row.gap(8).align(_.start).bg("#f9fafb").padding(8).minHeight(80))(
                        div.style(box.height(30))("Short"),
                        div.style(box.height(60))("Tall")
                    ),
                    label("align(_.center):"),
                    div.style(Style.row.gap(8).align(_.center).bg("#f9fafb").padding(8).minHeight(80))(
                        div.style(box.height(30))("Short"),
                        div.style(box.height(60))("Tall")
                    ),
                    label("align(_.end):"),
                    div.style(Style.row.gap(8).align(_.end).bg("#f9fafb").padding(8).minHeight(80))(
                        div.style(box.height(30))("Short"),
                        div.style(box.height(60))("Tall")
                    )
                ),
                // Overflow
                section.style(card)(
                    h3("Overflow"),
                    label("overflow(_.hidden) with height(60):"),
                    div.style(Style.height(60).overflow(_.hidden).border(1, "#ccc").padding(8))(
                        p("Line 1"),
                        p("Line 2"),
                        p("Line 3"),
                        p("Line 4"),
                        p("Line 5")
                    ),
                    label("overflow(_.scroll) with height(60):"),
                    div.style(Style.height(60).overflow(_.scroll).border(1, "#ccc").padding(8))(
                        p("Line 1"),
                        p("Line 2"),
                        p("Line 3"),
                        p("Line 4"),
                        p("Line 5")
                    )
                ),
                // Min/Max sizing
                section.style(card)(
                    h3("Min/Max Sizing"),
                    label("minHeight(100) with short content:"),
                    div.style(Style.minHeight(100).bg("#f3f4f6").padding(8).rounded(4))(
                        p("Short content")
                    ),
                    label("maxHeight(50) with tall content:"),
                    div.style(Style.maxHeight(50).overflow(_.hidden).bg("#f3f4f6").padding(8).rounded(4))(
                        p("Line 1"),
                        p("Line 2"),
                        p("Line 3"),
                        p("Line 4")
                    ),
                    label("minWidth(300):"),
                    div.style(Style.row.gap(8))(
                        div.style(box.minWidth(300))("minWidth=300")
                    )
                ),
                // Nested layouts
                section.style(card)(
                    h3("Nested Layouts"),
                    div.style(Style.row.gap(16))(
                        div.style(Style.column.gap(8).bg("#f3f4f6").padding(12).rounded(4))(
                            div.style(box)("Col 1 / Row 1"),
                            div.style(box)("Col 1 / Row 2")
                        ),
                        div.style(Style.column.gap(8).bg("#f3f4f6").padding(12).rounded(4))(
                            div.style(box)("Col 2 / Row 1"),
                            div.style(box)("Col 2 / Row 2"),
                            div.style(box)("Col 2 / Row 3")
                        )
                    )
                )
            )
        )

end LayoutUI
