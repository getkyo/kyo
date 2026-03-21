package kyo.internal.tui.pipeline

import kyo.*
import kyo.Length.*
import kyo.Test
import scala.language.implicitConversions

class InteractionFocusTest extends Test:

    import AllowUnsafe.embrace.danger

    def screen(ui: UI, cols: Int, rows: Int) = Screen(ui, cols, rows)

    "focus diagnostics" - {
        "buttons are registered as focusable" in run {
            val s = screen(
                UI.div(
                    UI.button("B1"),
                    UI.button("B2")
                ),
                20,
                3
            )
            s.render.andThen {
                assert(s.layoutPresent, "layout should exist after render")
                assert(s.focusableCount >= 2, s"expected >= 2 focusables, got ${s.focusableCount}")
            }
        }

        "tab sets focusedId" in run {
            val s = screen(
                UI.div(
                    UI.button("B1"),
                    UI.button("B2")
                ),
                20,
                3
            )
            for
                _ <- s.render
                _ <- s.tab
            yield assert(s.focusedKey.nonEmpty, s"focusedKey should be set after tab, got ${s.focusedKey}")
            end for
        }

        "tab fires onFocus via direct dispatch" in run {
            var fired = false
            val s = screen(
                UI.div(
                    UI.button.onFocus { fired = true }("B1")
                ),
                15,
                3
            )
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.tab // second tab to re-focus same element (wraps)
            yield assert(fired, s"onFocus should have fired, focusedKey=${s.focusedKey}")
            end for
        }

        "focusable keys exist in layout tree" in run {
            val s = screen(
                UI.div(
                    UI.button("B1"),
                    UI.button("B2")
                ),
                20,
                3
            )
            s.render.andThen {
                val keys = s.focusableKeys
                assert(keys.size >= 2, s"expected 2+ focusable keys, got ${keys.size}")
                val missing = keys.filter(k => !s.findKeyInLayout(k))
                assert(missing.isEmpty, s"keys not found in layout: $missing")
            }
        }
    }

    "tab navigation" - {
        "tab cycles through focusable elements" in run {
            var focused = ""
            val s = screen(
                UI.div(
                    UI.button.onFocus { focused = "btn1" }("B1"),
                    UI.button.onFocus { focused = "btn2" }("B2"),
                    UI.button.onFocus { focused = "btn3" }("B3")
                ),
                20,
                3
            )
            for
                _ <- s.render
                _ <- s.tab
            yield assert(focused == "btn1", s"expected btn1 focused, got: $focused")
            end for
        }

        "second tab moves to next element" in run {
            var focused = ""
            val s = screen(
                UI.div(
                    UI.button.onFocus { focused = "btn1" }("B1"),
                    UI.button.onFocus { focused = "btn2" }("B2")
                ),
                20,
                3
            )
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.tab
            yield assert(focused == "btn2", s"expected btn2 focused, got: $focused")
            end for
        }

        "shift+tab cycles backward" in run {
            var focused = ""
            val s = screen(
                UI.div(
                    UI.button.onFocus { focused = "btn1" }("B1"),
                    UI.button.onFocus { focused = "btn2" }("B2"),
                    UI.button.onFocus { focused = "btn3" }("B3")
                ),
                20,
                3
            )
            for
                _ <- s.render
                _ <- s.tab      // → btn1
                _ <- s.tab      // → btn2
                _ <- s.shiftTab // → btn1
            yield assert(focused == "btn1", s"expected btn1 focused after shift+tab, got: $focused")
            end for
        }
    }

    "tab wrap-around" - {
        "tab past last wraps to first" in run {
            var focused = ""
            val s = screen(
                UI.div(
                    UI.button.onFocus { focused = "btn1" }("B1"),
                    UI.button.onFocus { focused = "btn2" }("B2")
                ),
                20,
                3
            )
            for
                _ <- s.render
                _ <- s.tab // → btn1
                _ <- s.tab // → btn2
                _ <- s.tab // → wraps to btn1
            yield assert(focused == "btn1", s"expected wrap to btn1, got: $focused")
            end for
        }

        "shift+tab from first wraps to last" in run {
            var focused = ""
            val s = screen(
                UI.div(
                    UI.button.onFocus { focused = "btn1" }("B1"),
                    UI.button.onFocus { focused = "btn2" }("B2")
                ),
                20,
                3
            )
            for
                _ <- s.render
                _ <- s.tab      // → btn1
                _ <- s.shiftTab // → wraps to btn2
            yield assert(focused == "btn2", s"expected wrap to btn2, got: $focused")
            end for
        }
    }

    "click focus" - {
        "click sets focus on button" in run {
            var focused = ""
            val s = screen(
                UI.div(
                    UI.button.onFocus { focused = "clicked" }("Click me")
                ),
                15,
                1
            )
            for
                _ <- s.render
                _ <- s.click(1, 0)
            yield assert(focused == "clicked", s"expected 'clicked' focused, got: $focused")
            end for
        }
    }

    "focus and blur events" - {
        "onFocus fires when focused" in run {
            var focusFired = false
            val s = screen(
                UI.div(
                    UI.button.onFocus { focusFired = true }("B1")
                ),
                10,
                1
            )
            for
                _ <- s.render
                _ <- s.tab
            yield assert(focusFired, "onFocus should have fired")
            end for
        }

        "onBlur fires when focus moves away" in run {
            var blurFired = false
            val s = screen(
                UI.div(
                    UI.button.onBlur { blurFired = true }("B1"),
                    UI.button("B2")
                ),
                15,
                1
            )
            for
                _ <- s.render
                _ <- s.tab // focus B1
                _ <- s.tab // focus B2 → blur B1
            yield assert(blurFired, "onBlur should have fired when focus moved away")
            end for
        }
    }

    "keyboard event on focused button" - {
        "space on focused button fires onClick" in run {
            var clicked = false
            val s = screen(
                UI.div(
                    UI.button.onClick { clicked = true }("B1")
                ),
                15,
                1
            )
            for
                _ <- s.render
                _ <- s.click(0, 0)            // Plain theme: no border, button text at row 0
                _ <- s.key(UI.Keyboard.Space) // press space on focused button
            yield assert(clicked, "space on focused button should fire onClick")
            end for
        }
    }

    "disabled elements" - {
        "disabled button skipped in tab order" in run {
            var focused = ""
            val s = screen(
                UI.div(
                    UI.button.onFocus { focused = "btn1" }("B1"),
                    UI.button.disabled(true).onFocus { focused = "btn2" }("B2"),
                    UI.button.onFocus { focused = "btn3" }("B3")
                ),
                20,
                3
            )
            for
                _ <- s.render
                _ <- s.tab // → btn1
                _ <- s.tab // → btn3 (skip btn2)
            yield assert(focused == "btn3", s"expected btn3 (skipping disabled btn2), got: $focused")
            end for
        }
    }

    "mouse interactions" - {
        "click on different buttons changes focus" in run {
            var focused = ""
            val s = screen(
                UI.div.style(Style.row)(
                    UI.button.onFocus { focused = "left" }("L"),
                    UI.button.onFocus { focused = "right" }("R")
                ),
                20,
                1
            )
            for
                _ <- s.render
                _ <- s.click(0, 0) // Plain theme: no border, "L" at col 0, row 0
                _ <- s.click(1, 0) // Plain theme: "R" at col 1, row 0
            yield assert(focused == "right", s"expected right focused, got: $focused")
            end for
        }
    }

end InteractionFocusTest
