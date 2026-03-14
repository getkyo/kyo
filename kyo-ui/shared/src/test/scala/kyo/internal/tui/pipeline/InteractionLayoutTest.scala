package kyo.internal.tui.pipeline

import kyo.*
import kyo.Length.*
import kyo.Test
import scala.language.implicitConversions

class InteractionLayoutTest extends Test:

    import AllowUnsafe.embrace.danger

    def screen(ui: UI, cols: Int, rows: Int) = Screen(ui, cols, rows)

    "simple text" - {
        "renders at top-left" in run {
            val s = screen(UI.div("hello"), 10, 1)
            s.renderAndAssert("hello     ")
        }

        "fills grid width" in run {
            val s = screen(UI.div("AB"), 5, 1)
            s.renderAndAssert("AB   ")
        }

        "truncated to viewport" in run {
            val s = screen(UI.div("abcdefghij"), 5, 1)
            s.renderAndAssert("abcde")
        }

        "empty div is spaces" in run {
            val s = screen(UI.div, 5, 1)
            s.renderAndAssert("     ")
        }
    }

    "row layout" - {
        "two children side by side" in run {
            val s = screen(
                UI.div.style(Style.row)(
                    UI.span.style(Style.width(3.px))("AB"),
                    UI.span.style(Style.width(3.px))("CD")
                ),
                10,
                1
            )
            s.renderAndAssert("AB CD     ")
        }

        "three children in row" in run {
            val s = screen(
                UI.div.style(Style.row)(
                    UI.span.style(Style.width(3.px))("A"),
                    UI.span.style(Style.width(3.px))("B"),
                    UI.span.style(Style.width(3.px))("C")
                ),
                12,
                1
            )
            s.render.andThen {
                val f = s.frame
                assert(f.contains("A") && f.contains("B") && f.contains("C"))
                assert(f.indexOf("A") < f.indexOf("B"))
                assert(f.indexOf("B") < f.indexOf("C"))
            }
        }

        "row with gap" in run {
            val s = screen(
                UI.div.style(Style.row.gap(2.px))(
                    UI.span.style(Style.width(2.px))("A"),
                    UI.span.style(Style.width(2.px))("B")
                ),
                10,
                1
            )
            s.render.andThen {
                val f = s.frame
                assert(f.contains("A"))
                assert(f.contains("B"))
                // Gap of 2 between A and B
                val aEnd   = f.indexOf("A") + 1
                val bStart = f.indexOf("B")
                assert(bStart - aEnd >= 2, s"gap too small: A ends at $aEnd, B starts at $bStart")
            }
        }

        "children wider than parent should shrink" in run {
            val s = screen(
                UI.div.style(Style.row)(
                    UI.div.style(Style.width(20.px))("AAAA"),
                    UI.div.style(Style.width(20.px))("BBBB")
                ),
                15,
                1
            )
            s.render.andThen {
                // Both children should fit within 15 cols
                val f = s.frame
                assert(f.length == 15)
                assert(f.contains("AAAA"))
                assert(f.contains("BBBB"))
                // They should not overlap — A's end < B's start
                val aEnd   = f.indexOf("AAAA") + 4
                val bStart = f.indexOf("BBBB")
                assert(bStart >= aEnd, s"children overlap: AAAA ends at $aEnd, BBBB starts at $bStart")
            }
        }
    }

    "column layout" - {
        "two children stacked" in run {
            val s = screen(
                UI.div.style(Style.column)(
                    UI.div.style(Style.height(1.px))("top"),
                    UI.div.style(Style.height(1.px))("bot")
                ),
                5,
                3
            )
            s.renderAndAssert("top  \nbot  \n     ")
        }

        "three children stacked" in run {
            val s = screen(
                UI.div.style(Style.column)(
                    UI.div.style(Style.height(1.px))("A"),
                    UI.div.style(Style.height(1.px))("B"),
                    UI.div.style(Style.height(1.px))("C")
                ),
                3,
                3
            )
            s.renderAndAssert("A  \nB  \nC  ")
        }
    }

    "padding" - {
        "all sides" in run {
            val s = screen(UI.div.style(Style.padding(1.px))("X"), 5, 3)
            s.renderAndAssert("     \n X   \n     ")
        }

        "left padding shifts text" in run {
            val s = screen(UI.div.style(Style.padding(0.px, 0.px, 0.px, 2.px))("A"), 5, 1)
            s.renderAndAssert("  A  ")
        }
    }

    "margin" - {
        "left margin shifts element" in run {
            val s = screen(UI.div.style(Style.margin(0.px, 0.px, 0.px, 3.px))("X"), 8, 1)
            s.render.andThen {
                val f    = s.frame
                val xPos = f.indexOf('X')
                assert(xPos >= 3, s"X at position $xPos, expected >= 3")
            }
        }
    }

    "border" - {
        "solid box" in run {
            val s = screen(
                UI.div.style(
                    Style.border(1.px, Style.Color.rgb(128, 128, 128))
                        .width(5.px).height(3.px)
                ),
                5,
                3
            )
            s.renderAndAssert(
                """
                    |┌───┐
                    |│   │
                    |└───┘"""
            )
        }

        "rounded corners" in run {
            val s = screen(
                UI.div.style(
                    Style.border(1.px, Style.Color.rgb(128, 128, 128))
                        .rounded(1.px).width(5.px).height(3.px)
                ),
                5,
                3
            )
            s.renderAndAssert(
                """
                    |╭───╮
                    |│   │
                    |╰───╯"""
            )
        }

        "border with content inside" in run {
            val s = screen(
                UI.div.style(
                    Style.border(1.px, Style.Color.rgb(128, 128, 128))
                        .width(7.px).height(3.px)
                )("hi"),
                7,
                3
            )
            s.renderAndAssert(
                """
                    |┌─────┐
                    |│hi   │
                    |└─────┘"""
            )
        }

        "nested border boxes in row" in run {
            val s = screen(
                UI.div.style(Style.row)(
                    UI.div.style(
                        Style.border(1.px, Style.Color.rgb(128, 128, 128))
                            .width(7.px).height(3.px)
                    )("L"),
                    UI.div.style(
                        Style.border(1.px, Style.Color.rgb(128, 128, 128))
                            .width(7.px).height(3.px)
                    )("R")
                ),
                14,
                3
            )
            s.render.andThen {
                val f = s.frame
                assert(f.contains("L"), "left box content missing")
                assert(f.contains("R"), "right box content missing")
                assert(f.contains("┌"), "border missing")
                // Both boxes should fit within 14 cols
                val lines = f.linesIterator.toVector
                assert(lines.forall(_.length == 14), s"line lengths: ${lines.map(_.length)}")
            }
        }
    }

    "border with padding" - {
        "border and padding combined" in run {
            val s = screen(
                UI.div.style(
                    Style.border(1.px, Style.Color.rgb(128, 128, 128))
                        .padding(1.px).width(9.px).height(5.px)
                )("X"),
                9,
                5
            )
            s.render.andThen {
                val f = s.frame
                assert(f.contains("X"), "content missing inside border+padding")
                assert(f.contains("┌"), "border missing")
                // X should be offset by border(1) + padding(1) = 2 from left edge
                val lines       = f.linesIterator.toVector
                val contentLine = lines.find(_.contains("X")).get
                val xPos        = contentLine.indexOf("X")
                assert(xPos >= 2, s"X at col $xPos, expected >= 2 (border+padding)")
            }
        }
    }

    "explicit width" - {
        "respected" in run {
            val s = screen(
                UI.div.style(Style.width(8.px))("test"),
                15,
                1
            )
            s.render.andThen {
                val f = s.frame
                assert(f.length == 15)
                assert(f.startsWith("test"))
            }
        }
    }

    "percentage width" - {
        "50% of parent" in run {
            val s = screen(
                UI.div.style(Style.width(100.pct))(
                    UI.div.style(Style.width(50.pct))("half")
                ),
                20,
                1
            )
            s.render.andThen {
                val f = s.frame
                assert(f.contains("half"))
            }
        }
    }

    "flex grow" - {
        "child fills remaining space" in run {
            val s = screen(
                UI.div.style(Style.row.width(10.px))(
                    UI.div.style(Style.width(3.px))("L"),
                    UI.div.style(Style.flexGrow(1.0))("R")
                ),
                10,
                1
            )
            s.render.andThen {
                val f = s.frame
                assert(f.startsWith("L"))
                assert(f.contains("R"))
            }
        }

        "two flex-grow children split equally" in run {
            val s = screen(
                UI.div.style(Style.row.width(10.px))(
                    UI.div.style(Style.flexGrow(1.0))("A"),
                    UI.div.style(Style.flexGrow(1.0))("B")
                ),
                10,
                1
            )
            s.render.andThen {
                val f = s.frame
                assert(f.contains("A"))
                assert(f.contains("B"))
                // Both should get ~5 cols each
                val aPos = f.indexOf("A")
                val bPos = f.indexOf("B")
                assert(bPos >= 4, s"B at $bPos, expected >= 4 for equal split")
            }
        }
    }

    "overflow hidden" - {
        "clips content" in run {
            val s = screen(
                UI.div.style(Style.width(5.px).height(1.px).overflow(Style.Overflow.hidden))(
                    UI.span("hello world")
                ),
                10,
                1
            )
            s.render.andThen {
                val f = s.frame
                // Content should be clipped
                assert(f.length == 10)
            }
        }
    }

    "deep nesting" - {
        "border inside padding inside border" in run {
            val s = screen(
                UI.div.style(
                    Style.border(1.px, Style.Color.rgb(128, 128, 128))
                        .width(15.px).height(5.px)
                )(
                    UI.div.style(Style.padding(1.px))(
                        UI.div.style(
                            Style.border(1.px, Style.Color.rgb(128, 128, 128))
                        )("X")
                    )
                ),
                15,
                5
            )
            s.render.andThen {
                val f = s.frame
                assert(f.contains("X"), "nested content missing")
                assert(f.contains("┌"), "outer border missing")
                val lines = f.linesIterator.toVector
                // Outer border at row 0, inner border should be inside
                assert(lines(0).startsWith("┌"))
                assert(lines.last.startsWith("└"))
            }
        }
    }

end InteractionLayoutTest
