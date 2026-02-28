package demo

import kyo.*
import scala.language.implicitConversions

object SemanticElementsUI extends UIScope:

    import DemoStyles.app
    import DemoStyles.card
    import DemoStyles.content

    def build: UI < Async =
        for
            _ <- Signal.initRef(0)
        yield div.style(app)(
            header.style(Style.bg("#7c3aed").color(Color.white).padding(16, 32))(
                h1("Semantic Elements Showcase")
            ),
            main.style(content)(
                // Headings h1-h6
                section.style(card)(
                    h3("All Heading Levels"),
                    h1("Heading 1 (h1)"),
                    h2("Heading 2 (h2)"),
                    h3("Heading 3 (h3)"),
                    h4("Heading 4 (h4)"),
                    h5("Heading 5 (h5)"),
                    h6("Heading 6 (h6)")
                ),
                // Pre and Code
                section.style(card)(
                    h3("Preformatted & Code"),
                    p("Inline code-like text:"),
                    span.style(Style.fontFamily("monospace").bg("#f1f5f9").padding(2, 6).rounded(4).fontSize(13))(
                        "val x = 42"
                    ),
                    p.style(Style.margin(12, 0, 4, 0))("Code block:"),
                    code.style(Style.fontFamily("monospace").bg("#1e293b").color("#e2e8f0")
                        .padding(16).rounded(8).fontSize(13))(
                        pre(
                            "def fibonacci(n: Int): Int =\n  if n <= 1 then n\n  else fibonacci(n - 1) + fibonacci(n - 2)"
                        )
                    ),
                    p.style(Style.margin(12, 0, 4, 0))("Pre with whitespace preservation:"),
                    pre.style(Style.bg("#f8fafc").padding(12).rounded(4).border(1, "#e2e8f0")
                        .fontFamily("monospace").fontSize(13))(
                        "Name        Age    Role\nAlice       30     Engineer\nBob         25     Designer\nCharlie     35     Manager"
                    )
                ),
                // Horizontal rule
                section.style(card)(
                    h3("Horizontal Rules"),
                    p("Content above the rule"),
                    hr,
                    p("Content below the rule"),
                    hr.style(Style.border(2, "#2563eb").margin(16, 0)),
                    p("After a styled rule")
                ),
                // Line breaks
                section.style(card)(
                    h3("Line Breaks"),
                    p(
                        fragment(
                            span("Line one"),
                            br,
                            span("Line two (after br)"),
                            br,
                            span("Line three (after br)")
                        )
                    )
                ),
                // Ordered list
                section.style(card)(
                    h3("Ordered List (ol)"),
                    ol(
                        li("First item"),
                        li("Second item"),
                        li("Third item with longer text to show wrapping behavior"),
                        li("Fourth item")
                    ),
                    h3.style(Style.margin(16, 0, 4, 0))("Nested Lists"),
                    ul(
                        li(
                            fragment(
                                span("Fruits"),
                                ul(
                                    li("Apple"),
                                    li("Banana"),
                                    li("Cherry")
                                )
                            )
                        ),
                        li(
                            fragment(
                                span("Vegetables"),
                                ol(
                                    li("Carrot"),
                                    li("Broccoli")
                                )
                            )
                        )
                    )
                ),
                // Image
                section.style(card)(
                    h3("Image Element"),
                    p("Image with a data URI (1x1 blue pixel):"),
                    img(
                        "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPj/HwADBwIAMCbHYQAAAABJRU5ErkJggg==",
                        "Blue pixel"
                    ).style(Style.width(100).height(100).bg("#dbeafe").rounded(8)),
                    p.style(Style.fontSize(12).color("#94a3b8"))("(Scaled up from 1x1 pixel)")
                ),
                // Links with target
                section.style(card)(
                    h3("Anchor Variants"),
                    div.style(Style.gap(8))(
                        a.href("#")("Simple link"),
                        a.href("#").target("_blank")("Link with target=_blank"),
                        a.href("#").style(Style.color("#dc2626").bold)("Styled red link")
                    )
                )
            )
        )

end SemanticElementsUI
