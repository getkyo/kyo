package demo

import kyo.*
import kyo.Length.*
import kyo.internal.tui.pipeline.*
import scala.language.implicitConversions

object TuiDemo extends KyoApp:

    val cols = 60
    val rows = 20

    run {
        RenderToString.render(
            UI.div(
                UI.h1("Welcome to Kyo UI"),
                UI.div.style(Style.row)(
                    UI.span("A "),
                    UI.span.style(Style.bold)("pure"),
                    UI.span(" rendering pipeline")
                ),
                UI.hr,
                UI.div(
                    UI.div.style(Style.row.gap(2.px))(
                        UI.div.style(Style.border(1.px, Style.Color.rgb(100, 100, 255)).padding(1.px).width(25.px))(
                            UI.h2("Features"),
                            UI.ul(
                                UI.li("Typed lengths"),
                                UI.li("RGB colors"),
                                UI.li("Flex layout"),
                                UI.li("Immutable IR")
                            )
                        ),
                        UI.div.style(Style.border(1.px, Style.Color.rgb(100, 255, 100)).padding(1.px).width(25.px))(
                            UI.h2("Pipeline"),
                            UI.p("Lower → Style → Layout → Paint → Composite → Diff")
                        )
                    )
                )
            ),
            cols,
            rows
        ).map { output =>
            println(output)
        }
    }
end TuiDemo
