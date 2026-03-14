package kyo.internal.tui.pipeline

import kyo.*
import kyo.Length.*
import kyo.Test
import scala.language.implicitConversions

/** Theme, styling, layout visual, and edge case tests.
  *
  * Covers: 9 Layout Visual, 11 Theme & Styling, 12 Edge Cases
  */
class ThemeEdgeCaseTest extends Test:

    import AllowUnsafe.embrace.danger

    def screen(ui: UI, cols: Int, rows: Int) = Screen(ui, cols, rows)

    // ==== 9. Layout Visual ====

    "9 layout visual" - {

        "9.1 column stacking" - {
            "div with children stacks vertically" in run {
                val s = screen(
                    UI.div(
                        UI.div.style(Style.height(1.px))("top"),
                        UI.div.style(Style.height(1.px))("bot")
                    ),
                    10,
                    3
                )
                s.render.andThen {
                    val f       = s.frame
                    val lines   = f.linesIterator.toVector
                    val topLine = lines.indexWhere(_.contains("top"))
                    val botLine = lines.indexWhere(_.contains("bot"))
                    assert(topLine >= 0, s"top missing: $f")
                    assert(botLine >= 0, s"bot missing: $f")
                    assert(botLine > topLine, s"bot should be below top: top=$topLine, bot=$botLine")
                }
            }

            "form with children stacks vertically" in run {
                val s = screen(
                    UI.form(
                        UI.input.value("name"),
                        UI.input.value("email"),
                        UI.button("Send")
                    ),
                    20,
                    8
                )
                s.render.andThen {
                    val f         = s.frame
                    val lines     = f.linesIterator.toVector
                    val nameLine  = lines.indexWhere(_.contains("name"))
                    val emailLine = lines.indexWhere(_.contains("email"))
                    assert(nameLine >= 0 && emailLine >= 0, s"both inputs should be visible: $f")
                    assert(emailLine > nameLine, s"email should be below name: name=$nameLine, email=$emailLine")
                }
            }

            "no overlap between stacked children" in run {
                val s = screen(
                    UI.div(
                        UI.div.style(Style.height(1.px))("AAA"),
                        UI.div.style(Style.height(1.px))("BBB"),
                        UI.div.style(Style.height(1.px))("CCC")
                    ),
                    10,
                    5
                )
                s.render.andThen {
                    val f     = s.frame
                    val lines = f.linesIterator.toVector
                    val aLine = lines.indexWhere(_.contains("AAA"))
                    val bLine = lines.indexWhere(_.contains("BBB"))
                    val cLine = lines.indexWhere(_.contains("CCC"))
                    assert(aLine < bLine && bLine < cLine, s"children should not overlap: A=$aLine B=$bLine C=$cLine")
                }
            }
        }

        "9.2 row flow" - {
            "children side by side" in run {
                val s = screen(
                    UI.div.style(Style.row)(
                        UI.span.style(Style.width(3.px))("AB"),
                        UI.span.style(Style.width(3.px))("CD")
                    ),
                    10,
                    1
                )
                s.render.andThen {
                    val f = s.frame
                    assert(f.contains("AB"), s"AB missing: $f")
                    assert(f.contains("CD"), s"CD missing: $f")
                    assert(f.indexOf("AB") < f.indexOf("CD"), "AB should be left of CD")
                }
            }

            "gap between children when gap set" in run {
                val s = screen(
                    UI.div.style(Style.row.gap(2.px))(
                        UI.span.style(Style.width(2.px))("A"),
                        UI.span.style(Style.width(2.px))("B")
                    ),
                    10,
                    1
                )
                s.render.andThen {
                    val f      = s.frame
                    val aEnd   = f.indexOf("A") + 1
                    val bStart = f.indexOf("B")
                    assert(bStart - aEnd >= 2, s"gap too small: A ends at $aEnd, B starts at $bStart in: $f")
                }
            }

            "children do not overlap" in run {
                val s = screen(
                    UI.div.style(Style.row)(
                        UI.div.style(Style.width(5.px))("LEFT"),
                        UI.div.style(Style.width(5.px))("RIGHT")
                    ),
                    15,
                    1
                )
                s.render.andThen {
                    val f      = s.frame
                    val lEnd   = f.indexOf("LEFT") + 4
                    val rStart = f.indexOf("RIGHT")
                    assert(rStart >= lEnd, s"children overlap: LEFT ends at $lEnd, RIGHT starts at $rStart")
                }
            }
        }

        "9.3 border containment" - {
            "child content stays within parent border" in run {
                val s = screen(
                    UI.div.style(
                        Style.border(1.px, Style.Color.rgb(128, 128, 128))
                            .width(12.px).height(3.px)
                    )("a very long string that should be contained"),
                    20,
                    3
                )
                s.render.andThen {
                    val f     = s.frame
                    val lines = f.linesIterator.toVector
                    assert(lines.forall(_.length <= 20), s"content should not overflow viewport: $f")
                    assert(lines.forall(line => line.substring(12).trim.isEmpty), s"text overflowed border: $f")
                }
            }

            "nested borders render correctly" in run {
                val borderStyle = Style.border(1.px, Style.Color.rgb(128, 128, 128))
                val s = screen(
                    UI.div.style(borderStyle.width(20.px).height(5.px))(
                        UI.div.style(borderStyle.width(16.px).height(3.px))(
                            "inner"
                        )
                    ),
                    20,
                    5
                )
                s.render.andThen {
                    val f           = s.frame
                    val cornerCount = f.count(_ == '\u250c')
                    assert(cornerCount >= 2, s"expected 2+ border corners, got $cornerCount in:\n$f")
                    assert(f.contains("inner"), s"inner content missing: $f")
                }
            }

            "text inside bordered box truncates" in run {
                val s = screen(
                    UI.div.style(
                        Style.border(1.px, Style.Color.rgb(128, 128, 128))
                            .width(10.px).height(3.px)
                    )("this text is way too long to fit"),
                    15,
                    3
                )
                s.render.andThen {
                    val f     = s.frame
                    val lines = f.linesIterator.toVector
                    // Nothing should go past column 10
                    assert(lines.forall(line => line.substring(10).trim.isEmpty), s"text overflowed border: $f")
                }
            }
        }

        "9.4 cross-axis sizing" - {
            "child node does not exceed parent width" in run {
                val s = screen(
                    UI.div.style(Style.width(10.px))(
                        UI.div("this text is much wider than ten characters")
                    ),
                    20,
                    3
                )
                s.render.andThen {
                    val f     = s.frame
                    val lines = f.linesIterator.toVector
                    assert(lines.forall(line => line.substring(10).trim.isEmpty), s"child overflowed parent: $f")
                }
            }

            "percentage width respected" in run {
                val s = screen(
                    UI.div.style(Style.width(20.px))(
                        UI.div.style(Style.width(50.pct))("half")
                    ),
                    20,
                    1
                )
                s.render.andThen {
                    val f   = s.frame
                    val pos = f.indexOf("half")
                    assert(pos >= 0 && pos < 10, s"'half' should be within first 10 cols (50% of 20), at $pos")
                }
            }
        }
    }

    // ==== 11. Theme & Styling ====

    "11 theme and styling" - {

        "11.1 default theme" - {
            "text visible" in run {
                val s = screen(UI.div("hello"), 10, 1)
                s.render.andThen {
                    assert(s.frame.contains("hello"), s"text should be visible: ${s.frame}")
                }
            }

            "borders visible" in run {
                val s = screen(
                    UI.div.style(
                        Style.border(1.px, Style.Color.rgb(128, 128, 128))
                            .width(5.px).height(3.px)
                    ),
                    5,
                    3
                )
                s.render.andThen {
                    val f = s.frame
                    assert(f.contains("\u250c"), s"top-left border missing: $f")
                    assert(f.contains("\u2514"), s"bottom-left border missing: $f")
                    assert(f.contains("\u2500"), s"horizontal border missing: $f")
                }
            }

            "hr renders as horizontal line" in run {
                val s = screen(
                    UI.div(
                        UI.span("above"),
                        UI.hr,
                        UI.span("below")
                    ),
                    15,
                    5
                )
                s.render.andThen {
                    val f = s.frame
                    assert(f.contains("above"), s"above text missing: $f")
                    assert(f.contains("below"), s"below text missing: $f")
                    // Hr should render as a horizontal rule with dash-like characters
                    assert(
                        f.contains("\u2500") || f.contains("\u2504") || f.contains("-"),
                        s"hr should render as horizontal line: $f"
                    )
                }
            }
        }

        "11.2 element-specific styles" - {
            "h1 renders with bold-like appearance" in run {
                val s = screen(UI.h1("Title"), 20, 3)
                s.render.andThen {
                    assert(s.frame.contains("Title"), s"h1 text missing: ${s.frame}")
                }
            }

            "h2 renders" in run {
                val s = screen(UI.h2("Subtitle"), 20, 3)
                s.render.andThen {
                    assert(s.frame.contains("Subtitle"), s"h2 text missing: ${s.frame}")
                }
            }

            "button has bordered rendering" in run {
                val s = screen(UI.button("Press"), 12, 3)
                s.render.andThen {
                    val f     = s.frame
                    val lines = f.linesIterator.toVector
                    assert(lines.size >= 3, s"button should have 3 rows (border-top, content, border-bottom): $f")
                    assert(f.contains("Press"), s"button text missing: $f")
                }
            }

            "hr renders as horizontal dash characters" in run {
                val s = screen(UI.hr, 10, 1)
                s.render.andThen {
                    val f = s.frame
                    assert(
                        f.contains("\u2500") || f.contains("\u2504") || f.contains("-"),
                        s"hr should have dash characters: $f"
                    )
                }
            }

            "br causes line break" in run {
                val s = screen(
                    UI.div(
                        UI.span("A"),
                        UI.br,
                        UI.span("B")
                    ),
                    10,
                    5
                )
                s.render.andThen {
                    val f     = s.frame
                    val lines = f.linesIterator.toVector
                    val aLine = lines.indexWhere(_.contains("A"))
                    val bLine = lines.indexWhere(_.contains("B"))
                    assert(bLine > aLine, s"B should be below A after br: A=$aLine, B=$bLine in:\n$f")
                }
            }
        }

        "11.3 user styles override theme" - {
            "div with explicit color" in run {
                val s = screen(
                    UI.div.style(Style.color(Style.Color.rgb(255, 0, 0)))("red text"),
                    15,
                    1
                )
                s.render.andThen {
                    assert(s.frame.contains("red text"), s"styled text should render: ${s.frame}")
                }
            }

            "div with explicit border" in run {
                val s = screen(
                    UI.div.style(
                        Style.border(1.px, Style.Color.rgb(255, 0, 0))
                            .width(8.px).height(3.px)
                    )("box"),
                    10,
                    3
                )
                s.render.andThen {
                    val f = s.frame
                    assert(f.contains("box"), s"content missing: $f")
                    assert(f.contains("\u250c"), s"border missing: $f")
                }
            }
        }
    }

    // ==== 12. Edge Cases ====

    "12 edge cases" - {

        "12.1 zero-size viewport" - {
            "render with 0 cols - no crash" in run {
                val s = screen(UI.div("hello"), 0, 5)
                s.render.andThen(succeed)
            }

            "render with 0 rows - no crash" in run {
                val s = screen(UI.div("hello"), 5, 0)
                s.render.andThen(succeed)
            }

            "render with 0x0 - no crash" in run {
                val s = screen(UI.div("hello"), 0, 0)
                s.render.andThen(succeed)
            }
        }

        "12.2 single-cell viewport" - {
            "render with 1x1 - first character visible" in run {
                val s = screen(UI.div("hello"), 1, 1)
                s.render.andThen {
                    val f = s.frame
                    assert(f.length >= 1, s"should have at least 1 char: '$f'")
                    assert(f.head == 'h', s"first char should be 'h', got '${f.head}'")
                }
            }
        }

        "12.3 empty UI" - {
            "empty div - blank frame, no crash" in run {
                val s = screen(UI.div, 10, 3)
                s.render.andThen {
                    val f = s.frame
                    assert(f.trim.isEmpty, s"empty div should produce blank frame: '$f'")
                }
            }

            "fragment with no children - blank frame" in run {
                val s = screen(UI.fragment(), 10, 3)
                s.render.andThen(succeed)
            }
        }

        "12.4 unicode" - {
            "input with emoji renders" in run {
                val s = screen(UI.input.value("hi \ud83d\ude00"), 15, 1)
                s.render.andThen(succeed)
            }

            "input with CJK characters renders" in run {
                val s = screen(UI.input.value("\u4f60\u597d"), 15, 1)
                s.render.andThen(succeed)
            }

            "input with combining characters renders" in run {
                val s = screen(UI.input.value("e\u0301"), 15, 1)
                s.render.andThen(succeed)
            }
        }

        "12.5 very long content" - {
            "1000-char text in 10-col container - truncated" in run {
                val longText = "x" * 1000
                val s = screen(
                    UI.div.style(Style.width(10.px))(longText),
                    15,
                    3
                )
                s.render.andThen {
                    val f     = s.frame
                    val lines = f.linesIterator.toVector
                    assert(lines.forall(line => line.substring(10).trim.isEmpty), s"content overflowed: $f")
                }
            }

            "100 items in 5-row viewport - clipped" in run {
                val items = (1 to 100).map(i => UI.div(s"Item $i"): UI)
                val s     = screen(UI.div(items*), 15, 5)
                s.render.andThen {
                    val f     = s.frame
                    val lines = f.linesIterator.toVector
                    assert(lines.size <= 5, s"should not exceed 5 rows: ${lines.size}")
                }
            }
        }

        "12.6 rapid interactions" - {
            "type 20 characters rapidly - all in the input value" in run {
                val ref   = SignalRef.Unsafe.init("")
                val s     = screen(UI.input.value(ref.safe), 30, 1)
                val chars = "abcdefghijklmnopqrst"
                for
                    _ <- s.render
                    _ <- s.click(0, 0)
                    _ <- s.typeChar(chars(0))
                    _ <- s.typeChar(chars(1))
                    _ <- s.typeChar(chars(2))
                    _ <- s.typeChar(chars(3))
                    _ <- s.typeChar(chars(4))
                    _ <- s.typeChar(chars(5))
                    _ <- s.typeChar(chars(6))
                    _ <- s.typeChar(chars(7))
                    _ <- s.typeChar(chars(8))
                    _ <- s.typeChar(chars(9))
                    _ <- s.typeChar(chars(10))
                    _ <- s.typeChar(chars(11))
                    _ <- s.typeChar(chars(12))
                    _ <- s.typeChar(chars(13))
                    _ <- s.typeChar(chars(14))
                    _ <- s.typeChar(chars(15))
                    _ <- s.typeChar(chars(16))
                    _ <- s.typeChar(chars(17))
                    _ <- s.typeChar(chars(18))
                    _ <- s.typeChar(chars(19))
                yield assert(ref.get() == chars, s"expected '$chars', got '${ref.get()}'")
                end for
            }

            "tab through 10 elements - focus lands correctly, all visible" in run {
                val buttons = (1 to 10).map(i => UI.button(s"B$i"): UI)
                val s       = screen(UI.div(buttons*), 30, 15)
                for
                    _ <- s.render
                    _ <- s.tab
                    _ <- s.tab
                    _ <- s.tab
                    _ <- s.tab
                    _ <- s.tab
                    _ <- s.tab
                    _ <- s.tab
                    _ <- s.tab
                    _ <- s.tab
                    _ <- s.tab
                yield
                    val f = s.frame
                    assert(f.contains("B1"), s"B1 missing: $f")
                    assert(s.focusedKey.nonEmpty, "some element should be focused after 10 tabs")
                end for
            }
        }
    }

end ThemeEdgeCaseTest
