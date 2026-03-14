package kyo

import kyo.Length.*
import kyo.internal.tui.pipeline.*
import scala.language.implicitConversions

object TuiDebug extends KyoApp:

    given Frame = Frame.internal

    run {
        // Test 1: simple text in small grid
        RenderToString.render(UI.div("hello"), 10, 1).map { s =>
            println(s"Test 1 (simple text 10x1): |$s|")
        }.andThen {
            // Test 2: border box
            RenderToString.render(
                UI.div.style(Style.border(1.px, Style.Color.rgb(128, 128, 128)).width(10.px).height(3.px))("hi"),
                20,
                3
            ).map { s =>
                println(s"Test 2 (border 20x3):")
                s.linesIterator.foreach(l => println(s"  |$l|"))
            }
        }.andThen {
            // Test 3: row layout
            RenderToString.render(
                UI.div.style(Style.row)(
                    UI.div.style(Style.width(5.px))("AAA"),
                    UI.div.style(Style.width(5.px))("BBB")
                ),
                20,
                1
            ).map { s =>
                println(s"Test 3 (row 20x1): |$s|")
            }
        }.andThen {
            // Test 4: column layout
            RenderToString.render(
                UI.div.style(Style.column)(
                    UI.div.style(Style.height(1.px))("line1"),
                    UI.div.style(Style.height(1.px))("line2")
                ),
                10,
                3
            ).map { s =>
                println(s"Test 4 (column 10x3):")
                s.linesIterator.foreach(l => println(s"  |$l|"))
            }
        }.andThen {
            // Test 5.5: content wider than viewport — overflow
            RenderToString.render(
                UI.div.style(Style.row)(
                    UI.div.style(Style.width(20.px))("AAAA"),
                    UI.div.style(Style.width(20.px))("BBBB")
                ),
                15,
                1
            ).map { s =>
                println(s"Test 5.5 (overflow 15x1): |$s|")
            }
        }.andThen {
            // Test 5: nested border boxes — the demo scenario
            RenderToString.render(
                UI.div.style(Style.row)(
                    UI.div.style(Style.border(1.px, Style.Color.rgb(128, 128, 128)).width(12.px).height(5.px))(
                        UI.div("Left")
                    ),
                    UI.div.style(Style.border(1.px, Style.Color.rgb(128, 128, 128)).width(12.px).height(5.px))(
                        UI.div("Right")
                    )
                ),
                30,
                5
            ).map { s =>
                println(s"Test 5 (two bordered boxes 30x5):")
                s.linesIterator.foreach(l => println(s"  |$l|"))
            }
        }
    }
end TuiDebug
