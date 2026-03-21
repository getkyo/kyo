package kyo.internal.tui.pipeline

import kyo.*
import kyo.Length.*
import kyo.Test
import scala.language.implicitConversions

class RenderTest extends Test:

    def render(ui: UI, cols: Int, rows: Int): String < (Async & Scope) =
        RenderToString.render(ui, cols, rows)

    def assertRender(ui: UI, cols: Int, rows: Int)(expected: String) =
        render(ui, cols, rows).map { actual =>
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

    "plain text" - {
        "single word" in run {
            assertRender(UI.div("hello"), 10, 1) {
                """
                |hello
                """
            }
        }

        "truncated to viewport" in run {
            assertRender(UI.div("abcdefghij"), 5, 1) {
                """
                |abcde
                """
            }
        }

        "empty div" in run {
            assertRender(UI.div, 5, 1) {
                """
                |
                """
            }
        }

        "two words" in run {
            assertRender(UI.div("hello world"), 15, 1) {
                """
                |hello world
                """
            }
        }
    }

    "nested containers" - {
        "div in div" in run {
            assertRender(UI.div(UI.div("inner")), 10, 1) {
                """
                |inner
                """
            }
        }

        "span in div" in run {
            assertRender(UI.div(UI.span("hi")), 5, 1) {
                """
                |hi
                """
            }
        }
    }

    "column layout" - {
        "two divs stacked" in run {
            assertRender(
                UI.div.style(Style.column)(
                    UI.div.style(Style.height(1.px))("top"),
                    UI.div.style(Style.height(1.px))("bot")
                ),
                5,
                2
            ) {
                """
                |top
                |bot
                """
            }
        }
    }

    "padding" - {
        "all sides" in run {
            assertRender(UI.div.style(Style.padding(1.px))("X"), 5, 3) {
                """
                |
                | X
                |
                """
            }
        }

        "left only" in run {
            assertRender(UI.div.style(Style.padding(0.px, 0.px, 0.px, 2.px))("A"), 6, 1) {
                """
                |  A
                """
            }
        }
    }

    "border" - {
        "solid 5x3 box" in run {
            assertRender(
                UI.div.style(
                    Style.border(1.px, Style.Color.rgb(128, 128, 128))
                        .width(5.px).height(3.px)
                ),
                5,
                3
            ) {
                """
                |┌───┐
                |│   │
                |└───┘
                """
            }
        }

        "rounded corners" in run {
            assertRender(
                UI.div.style(
                    Style.border(1.px, Style.Color.rgb(128, 128, 128))
                        .rounded(1.px).width(5.px).height(3.px)
                ),
                5,
                3
            ) {
                """
                |╭───╮
                |│   │
                |╰───╯
                """
            }
        }

        "border with content" in run {
            assertRender(
                UI.div.style(
                    Style.border(1.px, Style.Color.rgb(128, 128, 128))
                        .width(7.px).height(3.px)
                )("hi"),
                7,
                3
            ) {
                """
                |┌─────┐
                |│hi   │
                |└─────┘
                """
            }
        }
    }

    "checkbox" - {
        "checked" in run {
            assertRender(UI.checkbox.checked(true), 5, 1) {
                """
                |[x]
                """
            }
        }

        "unchecked" in run {
            assertRender(UI.checkbox.checked(false), 5, 1) {
                """
                |[ ]
                """
            }
        }
    }

    "radio" - {
        "checked" in run {
            assertRender(UI.radio.checked(true), 5, 1) {
                """
                |(•)
                """
            }
        }

        "unchecked" in run {
            assertRender(UI.radio.checked(false), 5, 1) {
                """
                |( )
                """
            }
        }
    }

    "input" - {
        "with value" in run {
            assertRender(UI.input.value("hello"), 10, 1) {
                """
                |hello
                """
            }
        }

        "password masked" in run {
            assertRender(UI.password.value("abc"), 10, 1) {
                """
                |•••
                """
            }
        }
    }

    "hidden" - {
        "hidden element not rendered" in run {
            assertRender(
                UI.div(
                    UI.span("vis"),
                    UI.span.hidden(true)("hid")
                ),
                10,
                1
            ) {
                """
                |vis
                """
            }
        }
    }

    "text styling" - {
        "uppercase" in run {
            assertRender(
                UI.div.style(Style.textTransform(Style.TextTransform.uppercase))("hello"),
                10,
                1
            ) {
                """
                |HELLO
                """
            }
        }

        "lowercase" in run {
            assertRender(
                UI.div.style(Style.textTransform(Style.TextTransform.lowercase))("HELLO"),
                10,
                1
            ) {
                """
                |hello
                """
            }
        }
    }

    "select" - {
        "collapsed shows value and arrow" in run {
            assertRender(
                UI.select.value("b")(
                    UI.option.value("a")("Alpha"),
                    UI.option.value("b")("Beta")
                ),
                15,
                1
            ) {
                """
                |Beta ▼
                """
            }
        }
    }

    "table" - {
        "cells rendered" in run {
            render(
                UI.table(
                    UI.tr(UI.td("A"), UI.td("B")),
                    UI.tr(UI.td("C"), UI.td("D"))
                ),
                10,
                2
            ).map { s =>
                assert(s.contains("A"))
                assert(s.contains("B"))
                assert(s.contains("C"))
                assert(s.contains("D"))
                succeed
            }
        }
    }

    "background" - {
        "renders without crash" in run {
            assertRender(
                UI.div.style(Style.bg(Style.Color.rgb(0, 0, 255)))("blue"),
                10,
                1
            ) {
                """
                |blue
                """
            }
        }
    }

    "flex grow" - {
        "child fills remaining space" in run {
            assertRender(
                UI.div.style(Style.row.width(10.px))(
                    UI.div.style(Style.width(3.px))("L"),
                    UI.div.style(Style.flexGrow(1.0))("R")
                ),
                10,
                1
            ) {
                """
                |L  R
                """
            }
        }
    }

    "overflow hidden" - {
        "clips content" in run {
            assertRender(
                UI.div.style(Style.width(5.px).height(1.px).overflow(Style.Overflow.hidden))(
                    UI.span("hello world this is long")
                ),
                10,
                1
            ) {
                """
                |hello
                """
            }
        }
    }

end RenderTest
