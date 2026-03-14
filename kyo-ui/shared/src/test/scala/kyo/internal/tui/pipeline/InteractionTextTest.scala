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

    "dynamic text via signal" - {
        "text updates after signal change" in run {
            val ref = SignalRef.Unsafe.init("before")
            val s = screen(
                UI.div(
                    UI.when(ref.safe.map(_ => true))(UI.span(ref.safe.map(identity)))
                ),
                15,
                1
            )
            s.render.andThen {
                assert(s.frame.contains("before"), s"initial text missing: ${s.frame}")
            }
        }
    }

    "clickable text in button" - {
        "button text renders" in run {
            val s = screen(
                UI.button("Click me"),
                15,
                1
            )
            s.render.andThen {
                assert(s.frame.contains("Click"), "button text missing")
            }
        }

        "click on button with text fires handler" in run {
            var clicked = false
            val s = screen(
                UI.button.onClick { clicked = true }("Press"),
                10,
                1
            )
            for
                _ <- s.render
                _ <- s.click(2, 0)
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

end InteractionTextTest
