package kyo.internal.tui.pipeline

import kyo.*
import kyo.Length.*
import kyo.Test
import scala.language.implicitConversions

class VisualButtonTest extends Test:

    import AllowUnsafe.embrace.danger

    // Button tests use Default theme since they test button appearance (borders, padding)
    def screen(ui: UI, cols: Int, rows: Int) = Screen(ui, cols, rows, Theme.Default)

    def assertRender(ui: UI, cols: Int, rows: Int)(expected: String) =
        RenderToString.render(ui, cols, rows, Theme.Default).map { actual =>
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

    def assertRenderTheme(ui: UI, cols: Int, rows: Int, theme: Theme)(expected: String) =
        RenderToString.render(ui, cols, rows, theme).map { actual =>
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

    // ==== 7.1 Rendering — Default theme ====

    "7.1 rendering default theme" - {
        "button OK bordered box" in run {
            assertRender(UI.button("OK"), 6, 3)(
                """
                |┌────┐
                |│ OK │
                |└────┘
                """
            )
        }

        "button with padding from theme — text has space around it" in run {
            assertRender(UI.button("Go"), 6, 3)(
                """
                |┌────┐
                |│ Go │
                |└────┘
                """
            )
        }

        "empty button — border only, no crash" in run {
            assertRender(UI.button(), 4, 3)(
                """
                |┌──┐
                |│  │
                |└──┘
                """
            )
        }

        "button X — minimal border" in run {
            assertRender(UI.button("X"), 5, 3)(
                """
                |┌───┐
                |│ X │
                |└───┘
                """
            )
        }
    }

    // ==== 7.2 Rendering — other themes ====

    "7.2 rendering other themes" - {
        "plain theme — no border, just text OK" in run {
            assertRenderTheme(UI.button("OK"), 5, 1, Theme.Plain)(
                """
                |OK
                """
            )
        }

        "minimal theme — no border, just text" in run {
            assertRenderTheme(UI.button("OK"), 5, 1, Theme.Minimal)(
                """
                |OK
                """
            )
        }
    }

    // ==== 7.3 Click handler ====

    "7.3 click handler" - {
        "click fires onClick once" in run {
            var count = 0
            val s = screen(
                UI.div(UI.button.onClick { count += 1 }("OK")),
                6,
                3
            )
            for
                _ <- s.render
                _ <- s.click(1, 1) // click inside the button content area
            yield assert(count == 1, s"expected 1 click, got $count")
            end for
        }

        "click twice fires twice" in run {
            var count = 0
            val s = screen(
                UI.div(UI.button.onClick { count += 1 }("OK")),
                6,
                3
            )
            for
                _ <- s.render
                _ <- s.click(1, 1)
                _ <- s.click(1, 1)
            yield assert(count == 2, s"expected 2 clicks, got $count")
            end for
        }

        "onClick handler receives Unit" in run {
            var fired = false
            val s = screen(
                UI.div(UI.button.onClick { fired = true }("OK")),
                6,
                3
            )
            for
                _ <- s.render
                _ <- s.click(1, 1)
            yield assert(fired, "onClick should have fired")
            end for
        }
    }

    // ==== 7.4 Keyboard ====

    "7.4 keyboard" - {
        "tab to button focuses it" in run {
            val s = screen(UI.button("OK"), 6, 3)
            for
                _ <- s.render
                _ <- s.tab
            yield assert(s.focusedKey.nonEmpty, "button should be focused after tab")
            end for
        }

        "space on focused fires onClick" in run {
            var fired = false
            val s = screen(
                UI.div(UI.button.onClick { fired = true }("OK")),
                6,
                3
            )
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.key(UI.Keyboard.Space)
            yield assert(fired, "space on focused button should fire onClick")
            end for
        }

        "enter on focused does NOT fire onClick" in run {
            var fired = false
            val s = screen(
                UI.div(UI.button.onClick { fired = true }("OK")),
                6,
                3
            )
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.enter
            yield assert(!fired, "enter on focused button should NOT fire onClick")
            end for
        }
    }

    // ==== 7.5 Focus ====

    "7.5 focus" - {
        "focused button has no text cursor" in run {
            val s = screen(UI.button("OK"), 6, 3)
            for
                _ <- s.render
                _ <- s.tab
            yield
                assert(s.focusedKey.nonEmpty, "button should be focused")
                assert(!s.hasCursor, "focused button should not have a text cursor")
            end for
        }

        "tab away fires blur handler" in run {
            var blurred = false
            val s = screen(
                UI.div(
                    UI.button.onBlur { blurred = true }("B1"),
                    UI.button("B2")
                ),
                15,
                3
            )
            for
                _ <- s.render
                _ <- s.tab // focus B1
                _ <- s.tab // focus B2, blur B1
            yield assert(blurred, "onBlur should fire when tabbing away")
            end for
        }

        "tab to fires focus handler" in run {
            var focused = false
            val s = screen(
                UI.div(UI.button.onFocus { focused = true }("OK")),
                6,
                3
            )
            for
                _ <- s.render
                _ <- s.tab
            yield assert(focused, "onFocus should fire when tabbing to button")
            end for
        }

        "only one element focused at a time" in run {
            var focus1 = false
            var focus2 = false
            val s = screen(
                UI.div(
                    UI.button.onFocus { focus1 = true }.onBlur { focus1 = false }("B1"),
                    UI.button.onFocus { focus2 = true }.onBlur { focus2 = false }("B2")
                ),
                15,
                3
            )
            for
                _ <- s.render
                _ <- s.tab // focus B1
                first = focus1 && !focus2
                _ <- s.tab // focus B2
            yield
                assert(first, "after first tab, only B1 should be focused")
                assert(!focus1 && focus2, "after second tab, only B2 should be focused")
            end for
        }
    }

    // ==== 7.6 Disabled ====

    "7.6 disabled" - {
        "disabled button renders text and border" in run {
            assertRender(UI.button.disabled(true)("OK"), 6, 3)(
                """
                |┌────┐
                |│ OK │
                |└────┘
                """
            )
        }

        "click on disabled — no effect" in run {
            var clicked = false
            val s = screen(
                UI.div(UI.button.disabled(true).onClick { clicked = true }("OK")),
                6,
                3
            )
            for
                _ <- s.render
                _ <- s.click(1, 1)
            yield assert(!clicked, "disabled button should not fire onClick on click")
            end for
        }

        "space on disabled — no effect" in run {
            var clicked = false
            val s = screen(
                UI.div(UI.button.disabled(true).onClick { clicked = true }("OK")),
                6,
                3
            )
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.key(UI.Keyboard.Space)
            yield assert(!clicked, "disabled button should not fire onClick on space")
            end for
        }

        "tab skips disabled" in run {
            var focused = ""
            val s = screen(
                UI.div(
                    UI.button.disabled(true).onFocus { focused = "dis" }("Dis"),
                    UI.button.onFocus { focused = "ok" }("Ok")
                ),
                15,
                3
            )
            for
                _ <- s.render
                _ <- s.tab
            yield assert(focused == "ok", s"tab should skip disabled, got: $focused")
            end for
        }

        "click doesn't focus disabled" in run {
            var focused = false
            val s = screen(
                UI.div(UI.button.disabled(true).onFocus { focused = true }("No")),
                6,
                3
            )
            for
                _ <- s.render
                _ <- s.click(1, 1)
            yield assert(!focused, "click should not focus disabled button")
            end for
        }
    }

    // ==== 7.7 Long text ====

    "7.7 long text" - {
        "button Submit Application in 12-col — text wraps within border" in run {
            // Border takes 2 cols, padding takes 2 cols, leaving 8 for text
            // "Submit Application" = 18 chars, wraps to 3 lines of 8 chars each
            assertRender(
                UI.div.style(Style.width(12.px))(
                    UI.button("Submit Application")
                ),
                12,
                5
            )(
                """
                |┌──────────┐
                |│ Submit A │
                |│ pplicati │
                |│ on       │
                |└──────────┘
                """
            )
        }

        "button text shorter than border — text left-aligned within border" in run {
            // Button "AB" in a wider container: text should be left-aligned inside padding
            assertRender(UI.button("AB"), 6, 3)(
                """
                |┌────┐
                |│ AB │
                |└────┘
                """
            )
        }
    }

    // ==== 7.8 Containment ====

    "7.8 containment" - {
        "button beside input in row — no overlap" in run {
            val s = screen(
                UI.div.style(Style.row)(
                    UI.button("Go"),
                    UI.input.value("text")
                ),
                20,
                3
            )
            s.render.andThen {
                s.assertFrame(
                    """
                    |┌─┐┌───────────────┐
                    |│ ││ text          │
                    |└─┘└───────────────┘
                    """
                )
            }
        }

        "button in narrow container — text clipped by bounds" in run {
            // 4-col: border 2 + padding 2 = 0 for text. Text is clipped.
            assertRender(
                UI.div.style(Style.width(4.px).height(3.px))(
                    UI.button("OK")
                ),
                6,
                3
            )(
                """
                |┌──┐
                |│  │
                |└──┘
                """
            )
        }

        "multiple buttons stacked — no overlap" in run {
            assertRender(
                UI.div(
                    UI.button("A"),
                    UI.button("B")
                ),
                5,
                6
            )(
                """
                |┌───┐
                |│ A │
                |└───┘
                |┌───┐
                |│ B │
                |└───┘
                """
            )
        }

        "multiple buttons in row — side by side" in run {
            assertRender(
                UI.div.style(Style.row)(
                    UI.button("A"),
                    UI.button("B")
                ),
                10,
                3
            )(
                """
                |┌───┐┌───┐
                |│ A ││ B │
                |└───┘└───┘
                """
            )
        }

        "button in bordered container — button border inside container border" in run {
            assertRender(
                UI.div.style(
                    Style.border(1.px, Style.Color.rgb(128, 128, 128))
                        .width(10.px).height(5.px)
                )(
                    UI.button("OK")
                ),
                10,
                5
            )(
                """
                |┌────────┐
                |│┌──────┐│
                |││ OK   ││
                |│└──────┘│
                |└────────┘
                """
            )
        }
    }

end VisualButtonTest
