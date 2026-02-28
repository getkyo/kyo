package demo

import kyo.*
import scala.language.implicitConversions

object TypographyUI extends UIScope:

    import DemoStyles.app
    import DemoStyles.card
    import DemoStyles.content

    def build: UI < Async =
        for _ <- Signal.initRef(0) // just to satisfy `< Async`
        yield div.style(app)(
            header.style(Style.bg("#0891b2").color(Color.white).padding(16, 32))(
                h1("Typography Showcase")
            ),
            main.style(content)(
                // Font styles
                section.style(card)(
                    h3("Font Styles"),
                    p.style(Style.italic)("This text is italic"),
                    p.style(Style.bold)("This text is bold"),
                    p.style(Style.bold.italic)("This text is bold italic"),
                    p.style(Style.fontFamily("monospace"))("This text uses monospace font"),
                    p.style(Style.fontSize(24))("This text is 24px"),
                    p.style(Style.fontSize(12))("This text is 12px")
                ),
                // Text decoration
                section.style(card)(
                    h3("Text Decoration"),
                    p.style(Style.underline)("This text is underlined"),
                    p.style(Style.strikethrough)("This text has strikethrough"),
                    span.style(Style.underline)("Underlined span"),
                    span(" — "),
                    span.style(Style.strikethrough)("Struck-through span")
                ),
                // Text transform
                section.style(card)(
                    h3("Text Transform"),
                    p.style(Style.textTransform(_.uppercase))("this should be uppercase"),
                    p.style(Style.textTransform(_.lowercase))("THIS SHOULD BE LOWERCASE"),
                    p.style(Style.textTransform(_.capitalize))("this should be capitalized")
                ),
                // Line height & letter spacing
                section.style(card)(
                    h3("Spacing"),
                    p.style(Style.lineHeight(2.0))(
                        "This paragraph has double line height. " +
                            "It should have more space between lines than normal text. " +
                            "Adding more text to ensure it wraps to demonstrate the effect."
                    ),
                    p.style(Style.letterSpacing(4))(
                        "Wide letter spacing"
                    ),
                    p.style(Style.letterSpacing(0))(
                        "Normal letter spacing"
                    )
                ),
                // Text overflow
                section.style(card)(
                    h3("Text Overflow"),
                    div.style(Style.maxWidth(200).border(1, "#ccc").padding(8).overflow(_.hidden))(
                        p.style(Style.wrapText(false).textOverflow(_.ellipsis))(
                            "This is a very long text that should be truncated with an ellipsis when it overflows"
                        )
                    ),
                    div.style(Style.maxWidth(200).border(1, "#ccc").padding(8).margin(8, 0, 0, 0))(
                        p.style(Style.wrapText(true))(
                            "This text wraps normally inside a narrow container so it flows to multiple lines"
                        )
                    )
                ),
                // Text alignment
                section.style(card)(
                    h3("Text Alignment"),
                    p.style(Style.textAlign(_.left).bg("#f3f4f6").padding(8))("Left aligned"),
                    p.style(Style.textAlign(_.center).bg("#f3f4f6").padding(8).margin(4, 0))("Center aligned"),
                    p.style(Style.textAlign(_.right).bg("#f3f4f6").padding(8))("Right aligned")
                ),
                // Effects
                section.style(card)(
                    h3("Effects"),
                    div.style(Style.row.gap(24).align(_.center).padding(16))(
                        div.style(Style.opacity(0.5).bg("#2563eb").color(Color.white).padding(12, 16).rounded(4))(
                            "50% opacity"
                        ),
                        div.style(Style.translate(10, 5).bg("#7c3aed").color(Color.white).padding(12, 16).rounded(4))(
                            "Translated"
                        )
                    )
                )
            )
        )

end TypographyUI
