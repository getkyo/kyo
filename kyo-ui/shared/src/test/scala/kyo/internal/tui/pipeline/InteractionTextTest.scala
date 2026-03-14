package kyo.internal.tui.pipeline

import kyo.*
import kyo.Length.*
import kyo.Test
import scala.language.implicitConversions

class InteractionTextTest extends Test:

    given Frame = Frame.internal

    import AllowUnsafe.embrace.danger

    def screen(ui: UI, cols: Int, rows: Int) = Screen(ui, cols, rows)

    "text rendering" - {
        "renders string content" in run {
            val s = screen(UI.span("hello"), 10, 1)
            s.renderAndAssert("hello     ")
        }

        "empty string renders spaces" in run {
            val s = screen(UI.span(""), 5, 1)
            s.renderAndAssert("     ")
        }

        "unicode characters" in run {
            val s = screen(UI.span("café"), 10, 1)
            s.render.andThen {
                assert(s.frame.contains("café"))
            }
        }

        "multiple spans render inline" in run {
            val s = screen(
                UI.div(UI.span("A"), UI.span("B")),
                10,
                1
            )
            s.render.andThen {
                val f = s.frame
                assert(f.contains("A"), "first span missing")
                assert(f.contains("B"), "second span missing")
                assert(f.indexOf("A") < f.indexOf("B"), s"A should precede B in: $f")
            }
        }
    }

    "text truncation" - {
        "long text truncated to container width" in run {
            val s = screen(
                UI.div.style(Style.width(5.px))("abcdefghij"),
                10,
                1
            )
            s.render.andThen {
                val f = s.frame
                assert(!f.contains("fghij"), s"text not truncated: $f")
            }
        }
    }

    "text overflow ellipsis" - {
        "shows ellipsis when text overflows" in run {
            val s = screen(
                UI.div.style(Style.width(5.px).textWrap(Style.TextWrap.ellipsis))("abcdefghij"),
                10,
                1
            )
            s.render.andThen {
                val f = s.frame
                assert(f.contains("…"), s"no ellipsis in: $f")
            }
        }
    }

    "text align" - {
        "center" in run {
            val s = screen(
                UI.div.style(Style.width(10.px).textAlign(Style.TextAlign.center))("hi"),
                10,
                1
            )
            s.render.andThen {
                val f   = s.frame
                val pos = f.indexOf("hi")
                assert(pos >= 3, s"'hi' at position $pos, expected centered")
            }
        }

        "right" in run {
            val s = screen(
                UI.div.style(Style.width(10.px).textAlign(Style.TextAlign.right))("hi"),
                10,
                1
            )
            s.render.andThen {
                val f   = s.frame
                val pos = f.indexOf("hi")
                assert(pos >= 7, s"'hi' at position $pos, expected right-aligned")
            }
        }
    }

    "text transform" - {
        "uppercase" in run {
            val s = screen(
                UI.div.style(Style.textTransform(Style.TextTransform.uppercase))("hello"),
                10,
                1
            )
            s.render.andThen {
                assert(s.frame.contains("HELLO"), s"no uppercase in: ${s.frame}")
            }
        }

        "lowercase" in run {
            val s = screen(
                UI.div.style(Style.textTransform(Style.TextTransform.lowercase))("HELLO"),
                10,
                1
            )
            s.render.andThen {
                assert(s.frame.contains("hello"), s"no lowercase in: ${s.frame}")
            }
        }
    }

    "heading elements" - {
        "h1 renders text" in run {
            val s = screen(UI.h1("Title"), 20, 3)
            s.render.andThen {
                assert(s.frame.contains("Title"), "h1 content missing")
            }
        }

        "h2 renders text" in run {
            val s = screen(UI.h2("Subtitle"), 20, 3)
            s.render.andThen {
                assert(s.frame.contains("Subtitle"), "h2 content missing")
            }
        }
    }

    "br element" - {
        "creates line break" in run {
            val s = screen(
                UI.div(
                    UI.span("top"),
                    UI.br,
                    UI.span("bot")
                ),
                5,
                3
            )
            s.render.andThen {
                val f       = s.frame
                val lines   = f.linesIterator.toVector
                val topLine = lines.indexWhere(_.contains("top"))
                val botLine = lines.indexWhere(_.contains("bot"))
                assert(topLine >= 0, s"'top' not found in: $f")
                assert(botLine >= 0, s"'bot' not found in: $f")
                assert(botLine > topLine, s"'bot' (line $botLine) should be below 'top' (line $topLine)")
            }
        }
    }

    "hr element" - {
        "renders horizontal rule" in run {
            val s = screen(
                UI.div(
                    UI.span("above"),
                    UI.hr,
                    UI.span("below")
                ),
                10,
                5
            )
            s.render.andThen {
                val f = s.frame
                assert(f.contains("above"), "content above hr missing")
                assert(f.contains("below"), "content below hr missing")
                assert(f.contains("─") || f.contains("-"), s"no horizontal rule in: $f")
            }
        }
    }

    "p element" - {
        "renders paragraph text" in run {
            val s = screen(UI.p("paragraph"), 15, 1)
            s.render.andThen {
                assert(s.frame.contains("paragraph"), "p content missing")
            }
        }
    }

    "pre element" - {
        "renders preformatted text" in run {
            val s = screen(UI.pre("code"), 10, 1)
            s.render.andThen {
                assert(s.frame.contains("code"), "pre content missing")
            }
        }
    }

    "code element" - {
        "renders code text" in run {
            val s = screen(UI.code("x = 1"), 10, 1)
            s.render.andThen {
                assert(s.frame.contains("x = 1"), "code content missing")
            }
        }
    }

    "nav element" - {
        "renders navigation content" in run {
            val s = screen(UI.nav(UI.span("Menu")), 10, 1)
            s.render.andThen {
                assert(s.frame.contains("Menu"), "nav content missing")
            }
        }
    }

    "clickable text in button" - {
        "button text renders" in run {
            val s = screen(
                UI.button("Click me"),
                15,
                3
            )
            s.render.andThen {
                assert(s.frame.contains("Click"), s"button text missing: ${s.frame}")
            }
        }

        "click on button with text fires handler" in run {
            var clicked = false
            val s = screen(
                UI.button.onClick { clicked = true }("Press"),
                10,
                3
            )
            for
                _ <- s.render
                _ <- s.click(2, 1) // click on content row inside border
            yield assert(clicked, "button onClick should have fired")
            end for
        }
    }

    "anchor element" - {
        "renders link text" in run {
            val s = screen(UI.a("Link text"), 15, 1)
            s.render.andThen {
                assert(s.frame.contains("Link"), "anchor text missing")
            }
        }

        "click on anchor fires onClick" in run {
            var clicked = false
            val s = screen(
                UI.a.onClick { clicked = true }("Click link"),
                15,
                1
            )
            for
                _ <- s.render
                _ <- s.click(2, 0)
            yield assert(clicked, "anchor onClick should have fired")
            end for
        }
    }

    "text in nested containers" - {
        "text in bordered box" in run {
            val s = screen(
                UI.div.style(
                    Style.border(1.px, Style.Color.rgb(128, 128, 128))
                        .width(10.px).height(3.px)
                )("inner"),
                10,
                3
            )
            s.render.andThen {
                val f = s.frame
                assert(f.contains("inner"), s"nested text missing: $f")
                assert(f.contains("┌"), s"border missing: $f")
            }
        }

        "click on text inside bordered container" in run {
            var clicked = false
            val s = screen(
                UI.div.style(
                    Style.border(1.px, Style.Color.rgb(128, 128, 128))
                        .width(12.px).height(3.px)
                )(
                    UI.button.onClick { clicked = true }("OK")
                ),
                12,
                3
            )
            for
                _ <- s.render
                _ <- s.click(2, 1) // click inside the border on the button
            yield assert(clicked, "click on button inside border should fire")
            end for
        }
    }

    "label element" - {
        "renders label text" in run {
            val s = screen(UI.label("Name:"), 10, 1)
            s.render.andThen {
                assert(s.frame.contains("Name:"), "label text missing")
            }
        }
    }

    "section element" - {
        "renders section content" in run {
            val s = screen(UI.section(UI.span("content")), 15, 1)
            s.render.andThen {
                assert(s.frame.contains("content"), "section content missing")
            }
        }
    }

    "header and footer" - {
        "header renders" in run {
            val s = screen(UI.header(UI.span("top")), 10, 1)
            s.render.andThen {
                assert(s.frame.contains("top"), "header content missing")
            }
        }

        "footer renders" in run {
            val s = screen(UI.footer(UI.span("bottom")), 10, 1)
            s.render.andThen {
                assert(s.frame.contains("bottom"), "footer content missing")
            }
        }
    }

    "main element" - {
        "renders main content" in run {
            val s = screen(UI.main(UI.span("main")), 10, 1)
            s.render.andThen {
                assert(s.frame.contains("main"), "main content missing")
            }
        }
    }

end InteractionTextTest
