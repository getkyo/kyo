package kyo.internal.tui.pipeline

import kyo.*
import kyo.Length.*
import kyo.Test
import scala.language.implicitConversions

class VisualLayoutTest extends Test:

    import AllowUnsafe.embrace.danger

    def screen(ui: UI, cols: Int, rows: Int) = Screen(ui, cols, rows)

    def assertRender(ui: UI, cols: Int, rows: Int)(expected: String) =
        RenderToString.render(ui, cols, rows).map { actual =>
            val lines   = expected.stripMargin.stripPrefix("\n").linesIterator.toVector
            val trimmed = if lines.nonEmpty && lines.last.trim.isEmpty then lines.dropRight(1) else lines
            val exp     = trimmed.map(_.padTo(cols, ' ')).mkString("\n")
            if actual != exp then
                val msg = s"\nExpected:\n${exp.linesIterator.zipWithIndex.map((l, i) => f"$i%2d|$l|").mkString("\n")}" +
                    s"\nActual:\n${actual.linesIterator.zipWithIndex.map((l, i) => f"$i%2d|$l|").mkString("\n")}"
                fail(msg)
            else succeed
            end if
        }

    // ==== 10.1 Column layout (default) ====

    "10.1 column layout (default)" - {
        "div with two string children — stacked in column" in run {
            assertRender(
                UI.div.style(Style.width(5.px))("A", "B"),
                5,
                2
            )(
                """
                |A
                |B
                """
            )
        }

        "two child divs stacked vertically" in run {
            assertRender(
                UI.div(
                    UI.div.style(Style.height(1.px))("A"),
                    UI.div.style(Style.height(1.px))("B")
                ),
                5,
                2
            )(
                """
                |A
                |B
                """
            )
        }

        "three child divs stacked" in run {
            assertRender(
                UI.div(
                    UI.div.style(Style.height(1.px))("A"),
                    UI.div.style(Style.height(1.px))("B"),
                    UI.div.style(Style.height(1.px))("C")
                ),
                5,
                3
            )(
                """
                |A
                |B
                |C
                """
            )
        }

        "gap 1px between children" in run {
            assertRender(
                UI.div.style(Style.gap(1.px))(
                    UI.div.style(Style.height(1.px))("A"),
                    UI.div.style(Style.height(1.px))("B")
                ),
                5,
                3
            )(
                """
                |A
                |
                |B
                """
            )
        }

        "gap 2px between children" in run {
            assertRender(
                UI.div.style(Style.gap(2.px))(
                    UI.div.style(Style.height(1.px))("A"),
                    UI.div.style(Style.height(1.px))("B")
                ),
                5,
                4
            )(
                """
                |A
                |
                |
                |B
                """
            )
        }
    }

    // ==== 10.2 Row layout ====

    "10.2 row layout" - {
        "two spans side by side" in run {
            assertRender(
                UI.div.style(Style.row)(
                    UI.span.style(Style.width(3.px))("A"),
                    UI.span.style(Style.width(3.px))("B")
                ),
                10,
                1
            )(
                """
                |A  B
                """
            )
        }

        "with explicit widths" in run {
            assertRender(
                UI.div.style(Style.row)(
                    UI.span.style(Style.width(4.px))("AB"),
                    UI.span.style(Style.width(4.px))("CD")
                ),
                10,
                1
            )(
                """
                |AB  CD
                """
            )
        }

        "gap 2px between row children" in run {
            assertRender(
                UI.div.style(Style.row.gap(2.px))(
                    UI.span.style(Style.width(2.px))("A"),
                    UI.span.style(Style.width(2.px))("B")
                ),
                10,
                1
            )(
                """
                |A   B
                """
            )
        }

        "row children dont overflow parent width" in run {
            assertRender(
                UI.div.style(Style.row.width(6.px))(
                    UI.span.style(Style.width(4.px))("ABCD"),
                    UI.span.style(Style.width(4.px))("EFGH")
                ),
                10,
                1
            )(
                """
                |ABCEFG
                """
            )
        }
    }

    // ==== 10.3 Width ====

    "10.3 width" - {
        "width 10px" in run {
            assertRender(
                UI.div.style(Style.width(10.px).border(1.px, Style.Color.rgb(128, 128, 128)).height(3.px))(
                    "hello"
                ),
                12,
                3
            )(
                """
                |┌────────┐
                |│hello   │
                |└────────┘
                """
            )
        }

        "width 50 percent of 20 col parent" in run {
            assertRender(
                UI.div.style(Style.width(50.pct).border(1.px, Style.Color.rgb(128, 128, 128)).height(3.px))(
                    "hi"
                ),
                20,
                3
            )(
                """
                |┌────────┐
                |│hi      │
                |└────────┘
                """
            )
        }

        "minWidth 5px in 3 col parent" in run {
            assertRender(
                UI.div.style(Style.width(3.px))(
                    UI.div.style(Style.minWidth(5.px).height(1.px))("ABCDE")
                ),
                10,
                1
            )(
                """
                |ABCDE
                """
            )
        }

        "maxWidth 5px in 20 col parent" in run {
            assertRender(
                UI.div.style(Style.maxWidth(5.px).height(1.px))(
                    "ABCDEFGHIJ"
                ),
                20,
                1
            )(
                """
                |ABCDE
                """
            )
        }

        "width 0px — no content visible" in run {
            assertRender(
                UI.div.style(Style.width(0.px).height(1.px))(
                    "hello"
                ),
                10,
                1
            )(
                """
                |
                """
            )
        }
    }

    // ==== 10.4 Height ====

    "10.4 height" - {
        "height 3px" in run {
            assertRender(
                UI.div.style(Style.height(3.px).border(1.px, Style.Color.rgb(128, 128, 128)).width(6.px)),
                6,
                3
            )(
                """
                |┌────┐
                |│    │
                |└────┘
                """
            )
        }

        "minHeight 2px" in run {
            assertRender(
                UI.div.style(Style.minHeight(2.px).width(5.px))("A"),
                5,
                3
            )(
                """
                |A
                |
                |
                """
            )
        }

        "maxHeight 3px with 5 rows of content" in run {
            assertRender(
                UI.div.style(Style.maxHeight(3.px).overflow(Style.Overflow.hidden).width(5.px))(
                    UI.div.style(Style.height(1.px))("A"),
                    UI.div.style(Style.height(1.px))("B"),
                    UI.div.style(Style.height(1.px))("C"),
                    UI.div.style(Style.height(1.px))("D"),
                    UI.div.style(Style.height(1.px))("E")
                ),
                5,
                5
            )(
                """
                |A
                |B
                |C
                |
                |
                """
            )
        }
    }

    // ==== 10.5 Flex grow ====

    "10.5 flex grow" - {
        "child with flexGrow 1 fills remaining space" in run {
            assertRender(
                UI.div.style(Style.row.width(10.px))(
                    UI.span.style(Style.width(3.px))("A"),
                    UI.span.style(Style.flexGrow(1).border(1.px, Style.Color.rgb(128, 128, 128)).height(3.px))("B")
                ),
                10,
                3
            )(
                """
                |A  ┌─────┐
                |   │B    │
                |   └─────┘
                """
            )
        }

        "two children flexGrow 1 each — split equally" in run {
            assertRender(
                UI.div.style(Style.row.width(10.px).height(1.px))(
                    UI.span.style(Style.flexGrow(1))("A"),
                    UI.span.style(Style.flexGrow(1))("B")
                ),
                10,
                1
            )(
                """
                |A    B
                """
            )
        }

        "flexGrow 2 beside flexGrow 1 — 2:1 ratio" in run {
            assertRender(
                UI.div.style(Style.row.width(9.px).height(1.px))(
                    UI.span.style(Style.flexGrow(2))("A"),
                    UI.span.style(Style.flexGrow(1))("B")
                ),
                9,
                1
            )(
                """
                |A    B
                """
            )
        }

        "flexGrow 0 default — intrinsic width only" in run {
            assertRender(
                UI.div.style(Style.row.width(10.px).height(1.px))(
                    UI.span("AB"),
                    UI.span("CD")
                ),
                10,
                1
            )(
                """
                |ABCD
                """
            )
        }
    }

    // ==== 10.6 Flex shrink ====

    "10.6 flex shrink" - {
        "two 10-col children in 15-col parent — shrink proportionally" in run {
            assertRender(
                UI.div.style(Style.row.width(15.px).height(1.px))(
                    UI.span.style(Style.width(10.px))("ABCDEFGHIJ"),
                    UI.span.style(Style.width(10.px))("KLMNOPQRST")
                ),
                15,
                1
            )(
                """
                |ABCDEFGHKLMNOPQ
                """
            )
        }

        "default shrink — proportional reduction" in run {
            assertRender(
                UI.div.style(Style.row.width(12.px).height(1.px))(
                    UI.span.style(Style.width(8.px))("ABCDEFGH"),
                    UI.span.style(Style.width(8.px))("IJKLMNOP")
                ),
                12,
                1
            )(
                """
                |ABCDEFIJKLMN
                """
            )
        }

        "flexShrink 0 — doesnt shrink below intrinsic" in run {
            assertRender(
                UI.div.style(Style.row.width(10.px).height(1.px))(
                    UI.span.style(Style.width(8.px).flexShrink(0))("ABCDEFGH"),
                    UI.span.style(Style.width(8.px))("IJKLMNOP")
                ),
                10,
                1
            )(
                """
                |ABCDEFGHIJ
                """
            )
        }
    }

    // ==== 10.7 Justify ====

    "10.7 justify" - {
        "justify start (default)" in run {
            assertRender(
                UI.div.style(Style.row.width(10.px).height(1.px).justify(Style.Justification.start))(
                    UI.span.style(Style.width(2.px))("A"),
                    UI.span.style(Style.width(2.px))("B")
                ),
                10,
                1
            )(
                """
                |A B
                """
            )
        }

        "justify center" in run {
            assertRender(
                UI.div.style(Style.row.width(10.px).height(1.px).justify(Style.Justification.center))(
                    UI.span.style(Style.width(2.px))("A"),
                    UI.span.style(Style.width(2.px))("B")
                ),
                10,
                1
            )(
                """
                |   A B
                """
            )
        }

        "justify end" in run {
            assertRender(
                UI.div.style(Style.row.width(10.px).height(1.px).justify(Style.Justification.end))(
                    UI.span.style(Style.width(2.px))("A"),
                    UI.span.style(Style.width(2.px))("B")
                ),
                10,
                1
            )(
                """
                |      A B
                """
            )
        }

        "justify spaceBetween" in run {
            // 2 children, each 2px wide, in 10px. Remaining=6. Gap=6/(2-1)=6.
            // A at 0-1, B at 8-9.
            assertRender(
                UI.div.style(Style.row.width(10.px).height(1.px).justify(Style.Justification.spaceBetween))(
                    UI.span.style(Style.width(2.px))("A"),
                    UI.span.style(Style.width(2.px))("B")
                ),
                10,
                1
            )(
                """
                |A       B
                """
            )
        }

        "justify spaceAround" in run {
            assertRender(
                UI.div.style(Style.row.width(12.px).height(1.px).justify(Style.Justification.spaceAround))(
                    UI.span.style(Style.width(2.px))("A"),
                    UI.span.style(Style.width(2.px))("B")
                ),
                12,
                1
            )(
                """
                |  A     B
                """
            )
        }

        "justify spaceEvenly" in run {
            assertRender(
                UI.div.style(Style.row.width(12.px).height(1.px).justify(Style.Justification.spaceEvenly))(
                    UI.span.style(Style.width(2.px))("A"),
                    UI.span.style(Style.width(2.px))("B")
                ),
                12,
                1
            )(
                """
                |  A   B
                """
            )
        }
    }

    // ==== 10.8 Align ====

    "10.8 align" - {
        "align start (default)" in run {
            assertRender(
                UI.div.style(Style.row.width(10.px).height(3.px).align(Style.Alignment.start))(
                    UI.span.style(Style.width(3.px).height(1.px))("A"),
                    UI.span.style(Style.width(3.px).height(1.px))("B")
                ),
                10,
                3
            )(
                """
                |A  B
                |
                |
                """
            )
        }

        "align center" in run {
            assertRender(
                UI.div.style(Style.row.width(10.px).height(3.px).align(Style.Alignment.center))(
                    UI.span.style(Style.width(3.px).height(1.px))("A"),
                    UI.span.style(Style.width(3.px).height(1.px))("B")
                ),
                10,
                3
            )(
                """
                |
                |A  B
                |
                """
            )
        }

        "align end" in run {
            assertRender(
                UI.div.style(Style.row.width(10.px).height(3.px).align(Style.Alignment.end))(
                    UI.span.style(Style.width(3.px).height(1.px))("A"),
                    UI.span.style(Style.width(3.px).height(1.px))("B")
                ),
                10,
                3
            )(
                """
                |
                |
                |A  B
                """
            )
        }

        "align stretch" in run {
            assertRender(
                UI.div.style(Style.row.width(10.px).height(3.px).align(Style.Alignment.stretch))(
                    UI.span.style(Style.width(3.px).border(1.px, Style.Color.rgb(128, 128, 128))),
                    UI.span.style(Style.width(3.px).border(1.px, Style.Color.rgb(128, 128, 128)))
                ),
                10,
                3
            )(
                """
                |┌─┐┌─┐
                |│ ││ │
                |└─┘└─┘
                """
            )
        }
    }

    // ==== 10.9 Padding ====

    "10.9 padding" - {
        "padding 1px all sides" in run {
            assertRender(
                UI.div.style(Style.padding(1.px).width(5.px).height(3.px))(
                    "X"
                ),
                5,
                3
            )(
                """
                |
                | X
                |
                """
            )
        }

        "padding vertical 1 horizontal 2" in run {
            assertRender(
                UI.div.style(Style.padding(1.px, 2.px).width(7.px).height(3.px))(
                    "X"
                ),
                7,
                3
            )(
                """
                |
                |  X
                |
                """
            )
        }

        "padding asymmetric" in run {
            assertRender(
                UI.div.style(Style.padding(0.px, 0.px, 0.px, 3.px).width(6.px).height(1.px))(
                    "X"
                ),
                6,
                1
            )(
                """
                |   X
                """
            )
        }

        "padding plus content" in run {
            assertRender(
                UI.div.style(Style.padding(1.px).width(7.px).height(3.px))(
                    "hi"
                ),
                7,
                3
            )(
                """
                |
                | hi
                |
                """
            )
        }

        "padding plus border" in run {
            assertRender(
                UI.div.style(
                    Style.padding(1.px)
                        .border(1.px, Style.Color.rgb(128, 128, 128))
                        .width(8.px).height(5.px)
                )(
                    "X"
                ),
                8,
                5
            )(
                """
                |┌──────┐
                |│      │
                |│ X    │
                |│      │
                |└──────┘
                """
            )
        }
    }

    // ==== 10.10 Margin ====

    "10.10 margin" - {
        "margin 1px all sides — element offset from siblings" in run {
            assertRender(
                UI.div(
                    UI.div.style(Style.height(1.px))("A"),
                    UI.div.style(Style.margin(1.px).height(1.px))("B"),
                    UI.div.style(Style.height(1.px))("C")
                ),
                5,
                5
            )(
                """
                |A
                |
                | B
                |
                |C
                """
            )
        }

        "margin 1px between two divs in column" in run {
            assertRender(
                UI.div(
                    UI.div.style(Style.margin(0.px, 0.px, 1.px, 0.px).height(1.px))("A"),
                    UI.div.style(Style.height(1.px))("B")
                ),
                5,
                3
            )(
                """
                |A
                |
                |B
                """
            )
        }

        "margin between two spans in row" in run {
            assertRender(
                UI.div.style(Style.row)(
                    UI.span.style(Style.margin(0.px, 2.px, 0.px, 0.px))("A"),
                    UI.span("B")
                ),
                10,
                1
            )(
                """
                |A  B
                """
            )
        }
    }

    // ==== 10.11 Border ====

    "10.11 border" - {
        "solid border" in run {
            assertRender(
                UI.div.style(
                    Style.border(1.px, Style.Color.rgb(128, 128, 128))
                        .width(5.px).height(3.px)
                )(
                    "X"
                ),
                5,
                3
            )(
                """
                |┌───┐
                |│X  │
                |└───┘
                """
            )
        }

        "rounded border" in run {
            assertRender(
                UI.div.style(
                    Style.border(1.px, Style.Color.rgb(128, 128, 128))
                        .rounded(1.px)
                        .width(5.px).height(3.px)
                )(
                    "X"
                ),
                5,
                3
            )(
                """
                |╭───╮
                |│X  │
                |╰───╯
                """
            )
        }

        "dashed border" in run {
            assertRender(
                UI.div.style(
                    Style.border(1.px, Style.BorderStyle.dashed, Style.Color.rgb(128, 128, 128))
                        .width(5.px).height(3.px)
                )(
                    "X"
                ),
                5,
                3
            )(
                """
                |┌┄┄┄┐
                |┆X  ┆
                |└┄┄┄┘
                """
            )
        }

        "dotted border" in run {
            assertRender(
                UI.div.style(
                    Style.border(1.px, Style.BorderStyle.dotted, Style.Color.rgb(128, 128, 128))
                        .width(5.px).height(3.px)
                )(
                    "X"
                ),
                5,
                3
            )(
                """
                |┌···┐
                |·X  ·
                |└···┘
                """
            )
        }

        "border with content" in run {
            assertRender(
                UI.div.style(
                    Style.border(1.px, Style.Color.rgb(128, 128, 128))
                        .width(7.px).height(3.px)
                )(
                    "hi"
                ),
                7,
                3
            )(
                """
                |┌─────┐
                |│hi   │
                |└─────┘
                """
            )
        }

        "border plus padding" in run {
            assertRender(
                UI.div.style(
                    Style.border(1.px, Style.Color.rgb(128, 128, 128))
                        .padding(1.px)
                        .width(8.px).height(5.px)
                )(
                    "X"
                ),
                8,
                5
            )(
                """
                |┌──────┐
                |│      │
                |│ X    │
                |│      │
                |└──────┘
                """
            )
        }

        "left border only" in run {
            assertRender(
                UI.div.style(
                    Style.borderLeft(1.px, Style.Color.rgb(128, 128, 128))
                        .width(5.px).height(3.px)
                )(
                    "X"
                ),
                5,
                3
            )(
                """
                |│X
                |│
                |│
                """
            )
        }

        "top plus bottom border only" in run {
            assertRender(
                UI.div.style(
                    Style.borderTop(1.px, Style.Color.rgb(128, 128, 128))
                        .borderBottom(1.px, Style.Color.rgb(128, 128, 128))
                        .width(5.px).height(3.px)
                )(
                    "X"
                ),
                5,
                3
            )(
                """
                |─────
                |X
                |─────
                """
            )
        }
    }

    // ==== 10.12 Overflow ====

    "10.12 overflow" - {
        "overflow hidden clips tall content" in run {
            assertRender(
                UI.div.style(Style.overflow(Style.Overflow.hidden).width(5.px).height(2.px))(
                    UI.div.style(Style.height(1.px))("A"),
                    UI.div.style(Style.height(1.px))("B"),
                    UI.div.style(Style.height(1.px))("C")
                ),
                5,
                3
            )(
                """
                |A
                |B
                |
                """
            )
        }

        "overflow hidden clips wide content in row" in run {
            assertRender(
                UI.div.style(Style.overflow(Style.Overflow.hidden).row.width(5.px).height(1.px))(
                    UI.span.style(Style.width(10.px))("ABCDEFGHIJ")
                ),
                10,
                1
            )(
                """
                |ABCDE
                """
            )
        }

        "default visible — content at position, viewport clips" in run {
            assertRender(
                UI.div.style(Style.width(5.px).height(1.px))(
                    "ABCDEFGHIJ"
                ),
                5,
                1
            )(
                """
                |ABCDE
                """
            )
        }
    }

    // ==== 10.13 Nesting ====

    "10.13 nesting" - {
        "div in div in div — text visible" in run {
            assertRender(
                UI.div(UI.div(UI.div("hello"))),
                10,
                1
            )(
                """
                |hello
                """
            )
        }

        "nested borders — inner inside outer content" in run {
            assertRender(
                UI.div.style(
                    Style.border(1.px, Style.Color.rgb(128, 128, 128))
                        .width(10.px).height(5.px)
                )(
                    UI.div.style(
                        Style.border(1.px, Style.Color.rgb(128, 128, 128))
                            .width(8.px).height(3.px)
                    )(
                        "X"
                    )
                ),
                10,
                5
            )(
                """
                |┌────────┐
                |│┌──────┐│
                |││X     ││
                |│└──────┘│
                |└────────┘
                """
            )
        }

        "row in column in row" in run {
            assertRender(
                UI.div.style(Style.row.width(10.px).height(2.px))(
                    UI.div.style(Style.width(5.px))(
                        UI.div.style(Style.row.height(1.px))(
                            UI.span("A"),
                            UI.span("B")
                        )
                    ),
                    UI.span.style(Style.width(5.px).height(1.px))("C")
                ),
                10,
                2
            )(
                """
                |AB   C
                |
                """
            )
        }

        "parent padding plus child border" in run {
            assertRender(
                UI.div.style(Style.padding(1.px).width(8.px).height(5.px))(
                    UI.div.style(
                        Style.border(1.px, Style.Color.rgb(128, 128, 128))
                            .width(6.px).height(3.px)
                    )(
                        "X"
                    )
                ),
                8,
                5
            )(
                """
                |
                | ┌────┐
                | │X   │
                | └────┘
                |
                """
            )
        }

        "5 levels deep — no crash, content visible" in run {
            assertRender(
                UI.div(UI.div(UI.div(UI.div(UI.div("deep"))))),
                10,
                1
            )(
                """
                |deep
                """
            )
        }
    }

    // ==== 10.14 Hidden ====

    "10.14 hidden" - {
        "hidden true — not rendered" in run {
            assertRender(
                UI.div(
                    UI.div.hidden(true).style(Style.height(1.px))("HIDDEN"),
                    UI.div.style(Style.height(1.px))("VISIBLE")
                ),
                10,
                2
            )(
                """
                |VISIBLE
                |
                """
            )
        }

        "hidden false — visible" in run {
            assertRender(
                UI.div.hidden(false).style(Style.height(1.px))("VISIBLE"),
                10,
                1
            )(
                """
                |VISIBLE
                """
            )
        }

        "hidden div with children — children also hidden" in run {
            assertRender(
                UI.div(
                    UI.div.hidden(true)(
                        UI.div.style(Style.height(1.px))("A"),
                        UI.div.style(Style.height(1.px))("B")
                    ),
                    UI.div.style(Style.height(1.px))("C")
                ),
                5,
                3
            )(
                """
                |C
                |
                |
                """
            )
        }

        "sibling after hidden element takes hidden space" in run {
            assertRender(
                UI.div(
                    UI.div.hidden(true).style(Style.height(1.px))("A"),
                    UI.div.style(Style.height(1.px))("B"),
                    UI.div.style(Style.height(1.px))("C")
                ),
                5,
                3
            )(
                """
                |B
                |C
                |
                """
            )
        }
    }

    // ==== 10.15 Text properties ====

    "10.15 text properties" - {
        "textAlign center" in run {
            assertRender(
                UI.div.style(Style.textAlign(Style.TextAlign.center).width(10.px).height(1.px))(
                    "hi"
                ),
                10,
                1
            )(
                """
                |    hi
                """
            )
        }

        "textAlign right" in run {
            assertRender(
                UI.div.style(Style.textAlign(Style.TextAlign.right).width(10.px).height(1.px))(
                    "hi"
                ),
                10,
                1
            )(
                """
                |        hi
                """
            )
        }

        "textTransform uppercase" in run {
            assertRender(
                UI.div.style(Style.textTransform(Style.TextTransform.uppercase).width(10.px).height(1.px))(
                    "hello"
                ),
                10,
                1
            )(
                """
                |HELLO
                """
            )
        }

        "textTransform lowercase" in run {
            assertRender(
                UI.div.style(Style.textTransform(Style.TextTransform.lowercase).width(10.px).height(1.px))(
                    "HELLO"
                ),
                10,
                1
            )(
                """
                |hello
                """
            )
        }

        "textWrap noWrap" in run {
            assertRender(
                UI.div.style(Style.textWrap(Style.TextWrap.noWrap).width(5.px).height(1.px))(
                    "ABCDEFGHIJ"
                ),
                5,
                1
            )(
                """
                |ABCDE
                """
            )
        }

        "textOverflow ellipsis" in run {
            assertRender(
                UI.div.style(
                    Style.textOverflow(Style.TextOverflow.ellipsis)
                        .width(5.px).height(1.px)
                )(
                    "ABCDEFGHIJ"
                ),
                5,
                1
            )(
                """
                |ABCD…
                """
            )
        }

        "letterSpacing 1px" in run {
            assertRender(
                UI.div.style(Style.letterSpacing(1.px).width(10.px).height(1.px))(
                    "ABC"
                ),
                10,
                1
            )(
                """
                |A B C
                """
            )
        }

        "lineHeight 2" in run {
            assertRender(
                UI.div.style(Style.lineHeight(2).width(10.px).height(4.px))(
                    "AB\nCD"
                ),
                10,
                4
            )(
                """
                |AB
                |
                |CD
                |
                """
            )
        }
    }

    // ==== 10.16 Edge cases ====

    "10.16 edge cases" - {
        "empty div — no crash" in run {
            assertRender(
                UI.div,
                5,
                1
            )(
                """
                |
                """
            )
        }

        "viewport 1x1 — no crash" in run {
            assertRender(
                UI.div("A"),
                1,
                1
            )(
                """
                |A
                """
            )
        }

        "viewport 200x50 — correct rendering" in run {
            assertRender(
                UI.div.style(Style.width(200.px).height(1.px))("hello"),
                200,
                1
            )(
                """
                |hello
                """
            )
        }

        "10 levels of nesting — no crash" in run {
            assertRender(
                UI.div(UI.div(UI.div(UI.div(UI.div(UI.div(UI.div(UI.div(UI.div(UI.div("deep")))))))))),
                10,
                1
            )(
                """
                |deep
                """
            )
        }

        "all content in one row exceeding viewport — clipped" in run {
            assertRender(
                UI.div.style(Style.row)(
                    UI.span.style(Style.width(10.px))("ABCDEFGHIJ"),
                    UI.span.style(Style.width(10.px))("KLMNOPQRST")
                ),
                8,
                1
            )(
                """
                |ABCDKLMN
                """
            )
        }
    }

end VisualLayoutTest
