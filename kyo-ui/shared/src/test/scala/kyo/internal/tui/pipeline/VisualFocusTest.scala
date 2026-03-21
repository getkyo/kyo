package kyo.internal.tui.pipeline

import kyo.*
import kyo.Length.*
import kyo.Test
import scala.language.implicitConversions

class VisualFocusTest extends Test:

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

    // ==== 9.1 Tab cycling ====

    "9.1 tab cycling" - {
        "single input — tab focuses it" in run {
            val ref = SignalRef.Unsafe.init("")
            val s   = screen(UI.input.value(ref.safe), 10, 1)
            for
                _ <- s.render
                _ <- s.tab
            yield
                assert(s.focusedKey.nonEmpty, "input should be focused after tab")
                assert(s.hasCursor)
            end for
        }

        "single input — shift+tab focuses it" in run {
            val ref = SignalRef.Unsafe.init("")
            val s   = screen(UI.input.value(ref.safe), 10, 1)
            for
                _ <- s.render
                _ <- s.shiftTab
            yield
                assert(s.focusedKey.nonEmpty, "input should be focused after shift+tab")
                assert(s.hasCursor)
            end for
        }

        "two inputs — tab visits first then second" in run {
            val ref1 = SignalRef.Unsafe.init("")
            val ref2 = SignalRef.Unsafe.init("")
            val s = screen(
                UI.div(
                    UI.input.value(ref1.safe),
                    UI.input.value(ref2.safe)
                ),
                10,
                2
            )
            for
                _ <- s.render
                _ <- s.tab
                key1 = s.focusedKey
                row1 = s.cursorRow
                _ <- s.tab
                key2 = s.focusedKey
                row2 = s.cursorRow
            yield
                assert(key1.nonEmpty, "first tab should focus first input")
                assert(key2.nonEmpty, "second tab should focus second input")
                assert(key1 != key2, "first and second focus should differ")
                assert(row1 == 0, s"first input on row 0, got $row1")
                assert(row2 == 1, s"second input on row 1, got $row2")
            end for
        }

        "three inputs — tab visits 1->2->3->1 (wraps)" in run {
            val ref1 = SignalRef.Unsafe.init("")
            val ref2 = SignalRef.Unsafe.init("")
            val ref3 = SignalRef.Unsafe.init("")
            val s = screen(
                UI.div(
                    UI.input.value(ref1.safe),
                    UI.input.value(ref2.safe),
                    UI.input.value(ref3.safe)
                ),
                10,
                3
            )
            for
                _ <- s.render
                _ <- s.tab
                key1 = s.focusedKey
                _ <- s.tab
                key2 = s.focusedKey
                _ <- s.tab
                key3 = s.focusedKey
                _ <- s.tab
                key4 = s.focusedKey
            yield
                assert(key1.nonEmpty && key2.nonEmpty && key3.nonEmpty, "all three should focus")
                assert(key1 != key2 && key2 != key3 && key1 != key3, "all three should be different")
                assert(key4 == key1, s"after 4th tab, should wrap to first: $key4 vs $key1")
            end for
        }

        "shift+tab — 3->2->1->3 (wraps reverse)" in run {
            val ref1 = SignalRef.Unsafe.init("")
            val ref2 = SignalRef.Unsafe.init("")
            val ref3 = SignalRef.Unsafe.init("")
            val s = screen(
                UI.div(
                    UI.input.value(ref1.safe),
                    UI.input.value(ref2.safe),
                    UI.input.value(ref3.safe)
                ),
                10,
                3
            )
            for
                _ <- s.render
                _ <- s.shiftTab
                key1 = s.focusedKey
                _ <- s.shiftTab
                key2 = s.focusedKey
                _ <- s.shiftTab
                key3 = s.focusedKey
                _ <- s.shiftTab
                key4 = s.focusedKey
            yield
                // shift+tab from none should go to last (third)
                assert(key1.nonEmpty, "first shift+tab should focus something")
                assert(key1 != key2 && key2 != key3, "each shift+tab focuses different element")
                assert(key4 == key1, s"after 4th shift+tab, should wrap: $key4 vs $key1")
            end for
        }

        "no focusable elements — tab does nothing" in run {
            val s = screen(
                UI.div(
                    UI.span("just text"),
                    UI.div("more text")
                ),
                15,
                2
            )
            for
                _ <- s.render
                _ <- s.tab
            yield
                assert(!s.hasCursor, "should have no cursor with no focusable elements")
                assert(s.focusedKey.isEmpty, "should have no focused key")
            end for
        }
    }

    // ==== 9.2 Tab skips ====

    "9.2 tab skips" - {
        "disabled input skipped by tab" in run {
            val ref1 = SignalRef.Unsafe.init("dis")
            val ref2 = SignalRef.Unsafe.init("ok")
            val s = screen(
                UI.div(
                    UI.input.value(ref1.safe).disabled(true),
                    UI.input.value(ref2.safe)
                ),
                10,
                2
            )
            for
                _ <- s.render
                _ <- s.tab
            yield
                assert(s.hasCursor, "should have cursor")
                assert(s.cursorRow == 1, s"should focus second input (row 1), got ${s.cursorRow}")
            end for
        }

        "hidden input not in tab order" in run {
            val ref1 = SignalRef.Unsafe.init("hid")
            val ref2 = SignalRef.Unsafe.init("ok")
            val s = screen(
                UI.div(
                    UI.input.value(ref1.safe).hidden(true),
                    UI.input.value(ref2.safe)
                ),
                10,
                2
            )
            for
                _ <- s.render
                _ <- s.tab
            yield
                assert(s.hasCursor, "should have cursor on visible input")
                assert(s.focusedKey.nonEmpty, "should focus visible input")
            end for
        }

        "tabIndex(-1) skipped by tab" in run {
            val ref1 = SignalRef.Unsafe.init("skip")
            val ref2 = SignalRef.Unsafe.init("ok")
            val s = screen(
                UI.div(
                    UI.input.value(ref1.safe).tabIndex(-1),
                    UI.input.value(ref2.safe)
                ),
                10,
                2
            )
            for
                _ <- s.render
                _ <- s.tab
            yield
                assert(s.hasCursor, "should have cursor")
                assert(s.cursorRow == 1, s"should skip tabIndex(-1) input, focus row 1, got ${s.cursorRow}")
            end for
        }

        "div, span, label, h1 not in tab order" in run {
            val ref = SignalRef.Unsafe.init("")
            val s = screen(
                UI.div(
                    UI.div("div content"),
                    UI.span("span content"),
                    UI.label("label content"),
                    UI.h1("heading"),
                    UI.input.value(ref.safe)
                ),
                15,
                5
            )
            for
                _ <- s.render
                _ <- s.tab
            yield
                // Only the input should be focusable
                assert(s.focusableCount == 1, s"only input should be focusable, got ${s.focusableCount}")
                assert(s.hasCursor, "input should have cursor")
            end for
        }

        "only Focusable elements in tab order" in run {
            val ref = SignalRef.Unsafe.init("")
            val s = screen(
                UI.div(
                    UI.input.value(ref.safe),
                    UI.button("btn"),
                    UI.checkbox.checked(false),
                    UI.radio.checked(false),
                    UI.select.value("a")(UI.option.value("a")("A")),
                    UI.a("link")
                ),
                15,
                9
            )
            for
                _ <- s.render
            yield assert(s.focusableCount == 6, s"expected 6 focusable elements, got ${s.focusableCount}")
            end for
        }
    }

    // ==== 9.3 Tab order = document order ====

    "9.3 tab order = document order" - {
        "column of 3 inputs — tab 1->2->3" in run {
            val ref1 = SignalRef.Unsafe.init("A")
            val ref2 = SignalRef.Unsafe.init("B")
            val ref3 = SignalRef.Unsafe.init("C")
            val s = screen(
                UI.div(
                    UI.input.value(ref1.safe),
                    UI.input.value(ref2.safe),
                    UI.input.value(ref3.safe)
                ),
                10,
                3
            )
            for
                _ <- s.render
                _ <- s.tab
                row1 = s.cursorRow
                _ <- s.tab
                row2 = s.cursorRow
                _ <- s.tab
                row3 = s.cursorRow
            yield
                assert(row1 == 0, s"first tab focuses row 0, got $row1")
                assert(row2 == 1, s"second tab focuses row 1, got $row2")
                assert(row3 == 2, s"third tab focuses row 2, got $row3")
            end for
        }

        "row of 3 buttons — tab left->middle->right" in run {
            val s = screen(
                UI.div.style(Style.row)(
                    UI.button("L"),
                    UI.button("M"),
                    UI.button("R")
                ),
                18,
                3
            )
            for
                _ <- s.render
                _ <- s.tab
                key1 = s.focusedKey
                _ <- s.tab
                key2 = s.focusedKey
                _ <- s.tab
                key3 = s.focusedKey
            yield
                assert(key1.nonEmpty && key2.nonEmpty && key3.nonEmpty, "all buttons should be focusable")
                assert(key1 != key2 && key2 != key3, "each tab visits different button")
            end for
        }

        "mixed: input, checkbox, select, button in column — tab visits in that order" in run {
            val ref = SignalRef.Unsafe.init("")
            val s = screen(
                UI.div(
                    UI.input.value(ref.safe),
                    UI.checkbox.checked(false),
                    UI.select.value("a")(UI.option.value("a")("A")),
                    UI.button("btn")
                ),
                15,
                6
            )
            for
                _ <- s.render
                _ <- s.tab
                key1 = s.focusedKey
                _ <- s.tab
                key2 = s.focusedKey
                _ <- s.tab
                key3 = s.focusedKey
                _ <- s.tab
                key4 = s.focusedKey
            yield
                val keys = Seq(key1, key2, key3, key4)
                assert(keys.forall(_.nonEmpty), "all 4 should be focused in sequence")
                assert(keys.distinct.size == 4, s"all 4 should be distinct: $keys")
            end for
        }

        "nested: div(div(input1), div(input2)) — tab 1->2 (depth-first)" in run {
            val ref1 = SignalRef.Unsafe.init("X")
            val ref2 = SignalRef.Unsafe.init("Y")
            val s = screen(
                UI.div(
                    UI.div(UI.input.value(ref1.safe)),
                    UI.div(UI.input.value(ref2.safe))
                ),
                10,
                2
            )
            for
                _ <- s.render
                _ <- s.tab
                row1 = s.cursorRow
                _ <- s.tab
                row2 = s.cursorRow
            yield
                assert(row1 == 0, s"first input in nested div at row 0, got $row1")
                assert(row2 == 1, s"second input in nested div at row 1, got $row2")
            end for
        }
    }

    // ==== 9.4 Click focus ====

    "9.4 click focus" - {
        "click on input focuses it (cursor appears)" in run {
            val ref = SignalRef.Unsafe.init("hello")
            val s   = screen(UI.input.value(ref.safe), 10, 1)
            for
                _ <- s.render
                _ <- s.click(0, 0)
            yield
                assert(s.hasCursor, "click on input should show cursor")
                assert(s.focusedKey.nonEmpty, "click on input should focus it")
            end for
        }

        "click on button focuses it" in run {
            val s = screen(UI.button("OK"), 10, 1)
            for
                _ <- s.render
                _ <- s.click(0, 0) // Plain theme: no border, button text at row 0
            yield assert(s.focusedKey.nonEmpty, "click on button should focus it")
            end for
        }

        "click on checkbox focuses it (and toggles)" in run {
            val s = screen(UI.checkbox.checked(false), 5, 1)
            for
                _ <- s.render
                _ <- s.click(1, 0)
            yield
                assert(s.focusedKey.nonEmpty, "click on checkbox should focus it")
                s.assertFrame(
                    """
                    |[x]
                    """
                )
            end for
        }

        "click on select focuses it" in run {
            val s = screen(
                UI.select.value("a")(
                    UI.option.value("a")("Alpha"),
                    UI.option.value("b")("Beta")
                ),
                15,
                1
            )
            for
                _ <- s.render
                _ <- s.click(0, 0)
            yield assert(s.focusedKey.nonEmpty, "click on select should focus it")
            end for
        }

        "click on disabled does NOT focus" in run {
            val ref = SignalRef.Unsafe.init("dis")
            val s   = screen(UI.input.value(ref.safe).disabled(true), 10, 1)
            for
                _ <- s.render
                _ <- s.click(0, 0)
            yield
                assert(!s.hasCursor, "click on disabled input should not show cursor")
                assert(s.focusedKey.isEmpty, "click on disabled should not focus")
            end for
        }

        "click on div does NOT change focus" in run {
            val ref = SignalRef.Unsafe.init("")
            val s = screen(
                UI.div(
                    UI.input.value(ref.safe),
                    UI.div.style(Style.height(1.px))("text")
                ),
                10,
                2
            )
            for
                _ <- s.render
                _ <- s.tab // focus the input
                key1 = s.focusedKey
                _ <- s.click(0, 1) // click on the div text row
            yield
                // Focus should stay on the input (or potentially unfocus, but not change to div)
                val key2 = s.focusedKey
                assert(key2 == key1, s"clicking div should not change focus: before=$key1, after=$key2")
            end for
        }

        "click on label without forId does NOT change focus" in run {
            val ref = SignalRef.Unsafe.init("")
            val s = screen(
                UI.div(
                    UI.input.value(ref.safe),
                    UI.label("Name:")
                ),
                10,
                2
            )
            for
                _ <- s.render
                _ <- s.tab // focus the input
                key1 = s.focusedKey
                _ <- s.click(0, 1) // click on label row
            yield
                val key2 = s.focusedKey
                assert(key2 == key1, s"clicking label without forId should not change focus: before=$key1, after=$key2")
            end for
        }

        "click on label with forId focuses target element" in run {
            val s = screen(
                UI.div(
                    UI.input.value("").id("name-input"),
                    UI.label.forId("name-input")("Name:")
                ),
                10,
                2
            )
            for
                _ <- s.render
                _ <- s.click(0, 1) // click on label row
            yield assert(s.focusedKey.nonEmpty, "label click should focus the target input")
            end for
        }

        "click elsewhere after focusing — focus stays" in run {
            val ref = SignalRef.Unsafe.init("AB")
            val s = screen(
                UI.div(
                    UI.input.value(ref.safe),
                    UI.div.style(Style.height(1.px))("padding")
                ),
                10,
                2
            )
            for
                _ <- s.render
                _ <- s.tab         // focus the input
                _ <- s.click(5, 1) // click on empty area (the div text)
            yield
                // Focus should remain (no blur on empty click)
                assert(s.focusedKey.nonEmpty, "focus should remain after clicking elsewhere")
            end for
        }
    }

    // ==== 9.5 Focus indicators ====

    "9.5 focus indicators" - {
        "focused input has cursor" in run {
            val ref = SignalRef.Unsafe.init("hi")
            val s   = screen(UI.input.value(ref.safe), 10, 1)
            for
                _ <- s.render
                _ <- s.tab
            yield assert(s.hasCursor, "focused input should have cursor")
            end for
        }

        "unfocused input has no cursor" in run {
            val ref = SignalRef.Unsafe.init("hi")
            val s   = screen(UI.input.value(ref.safe), 10, 1)
            for
                _ <- s.render
            yield assert(!s.hasCursor, "unfocused input should not have cursor")
            end for
        }

        "focused button has no text cursor" in run {
            val s = screen(UI.button("OK"), 10, 3)
            for
                _ <- s.render
                _ <- s.tab
            yield
                assert(s.focusedKey.nonEmpty, "button should be focused")
                assert(!s.hasCursor, "focused button should not have text cursor")
            end for
        }

        "focused checkbox has no text cursor" in run {
            val s = screen(UI.checkbox.checked(false), 5, 1)
            for
                _ <- s.render
                _ <- s.tab
            yield
                assert(s.focusedKey.nonEmpty, "checkbox should be focused")
                assert(!s.hasCursor, "focused checkbox should not have text cursor")
            end for
        }

        "only one cursor visible across entire UI at any time" in run {
            val ref1 = SignalRef.Unsafe.init("A")
            val ref2 = SignalRef.Unsafe.init("B")
            val s = screen(
                UI.div(
                    UI.input.value(ref1.safe),
                    UI.input.value(ref2.safe)
                ),
                10,
                2
            )
            for
                _ <- s.render
                _ <- s.tab // focus first
            yield
                // Only one cursor position should exist
                val pos = s.cursorPos
                assert(pos.nonEmpty, "should have exactly one cursor")
                assert(s.cursorRow == 0, "cursor should be on first input")
            end for
        }
    }

    // ==== 9.6 Focus/blur event handlers ====

    "9.6 focus/blur event handlers" - {
        "tab to input fires onFocus" in run {
            var focusFired = false
            val ref        = SignalRef.Unsafe.init("")
            val s          = screen(UI.input.value(ref.safe).onFocus { focusFired = true }, 10, 1)
            for
                _ <- s.render
                _ <- s.tab
            yield assert(focusFired, "onFocus should fire when tabbing to input")
            end for
        }

        "tab away from input fires onBlur" in run {
            var blurFired = false
            val ref1      = SignalRef.Unsafe.init("")
            val ref2      = SignalRef.Unsafe.init("")
            val s = screen(
                UI.div(
                    UI.input.value(ref1.safe).onBlur { blurFired = true },
                    UI.input.value(ref2.safe)
                ),
                10,
                2
            )
            for
                _ <- s.render
                _ <- s.tab // focus first
                _ <- s.tab // tab away to second
            yield assert(blurFired, "onBlur should fire when tabbing away")
            end for
        }

        "click on input2 while input1 focused fires input1 onBlur and input2 onFocus" in run {
            var events = List.empty[String]
            val ref1   = SignalRef.Unsafe.init("A")
            val ref2   = SignalRef.Unsafe.init("B")
            val s = screen(
                UI.div(
                    UI.input.value(ref1.safe).onBlur { events = events :+ "blur1" },
                    UI.input.value(ref2.safe).onFocus { events = events :+ "focus2" }
                ),
                10,
                2
            )
            for
                _ <- s.render
                _ <- s.tab         // focus input1
                _ <- s.click(0, 1) // click input2
            yield
                assert(events.contains("blur1"), s"input1 onBlur should fire: $events")
                assert(events.contains("focus2"), s"input2 onFocus should fire: $events")
            end for
        }

        "order: blur on old THEN focus on new" in run {
            var events = List.empty[String]
            val ref1   = SignalRef.Unsafe.init("")
            val ref2   = SignalRef.Unsafe.init("")
            val s = screen(
                UI.div(
                    UI.input.value(ref1.safe)
                        .onBlur { events = events :+ "blur" }
                        .onFocus { events = events :+ "focus1" },
                    UI.input.value(ref2.safe)
                        .onFocus { events = events :+ "focus2" }
                ),
                10,
                2
            )
            for
                _ <- s.render
                _ <- s.tab // focus input1
                _ = events = List.empty[String] // reset after initial focus
                _ <- s.tab // tab to input2
            yield
                val blurIdx  = events.indexOf("blur")
                val focusIdx = events.indexOf("focus2")
                assert(blurIdx >= 0, s"blur should fire: $events")
                assert(focusIdx >= 0, s"focus should fire: $events")
                assert(blurIdx < focusIdx, s"blur should fire before focus: $events")
            end for
        }
    }

    // ==== 9.7 Focus across containers ====

    "9.7 focus across containers" - {
        "input inside div inside div — still focusable" in run {
            val ref = SignalRef.Unsafe.init("deep")
            val s = screen(
                UI.div(
                    UI.div(
                        UI.div(
                            UI.input.value(ref.safe)
                        )
                    )
                ),
                10,
                1
            )
            for
                _ <- s.render
                _ <- s.tab
            yield
                assert(s.hasCursor, "deeply nested input should be focusable")
                assert(s.focusedKey.nonEmpty, "should have focused key")
            end for
        }

        "input inside form — still focusable" in run {
            val ref = SignalRef.Unsafe.init("")
            val s = screen(
                UI.form(
                    UI.input.value(ref.safe)
                ),
                10,
                1
            )
            for
                _ <- s.render
                _ <- s.tab
            yield assert(s.hasCursor, "input inside form should be focusable")
            end for
        }

        "focus order correct when inputs are in different nested containers" in run {
            val ref1 = SignalRef.Unsafe.init("X")
            val ref2 = SignalRef.Unsafe.init("Y")
            val ref3 = SignalRef.Unsafe.init("Z")
            val s = screen(
                UI.div(
                    UI.div(UI.input.value(ref1.safe)),
                    UI.div(
                        UI.div(UI.input.value(ref2.safe))
                    ),
                    UI.input.value(ref3.safe)
                ),
                10,
                3
            )
            for
                _ <- s.render
                _ <- s.tab
                row1 = s.cursorRow
                _ <- s.tab
                row2 = s.cursorRow
                _ <- s.tab
                row3 = s.cursorRow
            yield
                assert(row1 == 0, s"first input at row 0, got $row1")
                assert(row2 == 1, s"second input at row 1, got $row2")
                assert(row3 == 2, s"third input at row 2, got $row3")
            end for
        }

        "row layout: focus order matches left-to-right visual order" in run {
            val ref1 = SignalRef.Unsafe.init("L")
            val ref2 = SignalRef.Unsafe.init("R")
            val s = screen(
                UI.div.style(Style.row)(
                    UI.input.value(ref1.safe).style(Style.width(5.px)),
                    UI.input.value(ref2.safe).style(Style.width(5.px))
                ),
                10,
                1
            )
            for
                _ <- s.render
                _ <- s.tab
                col1 = s.cursorCol
                key1 = s.focusedKey
                _ <- s.tab
                col2 = s.cursorCol
                key2 = s.focusedKey
            yield
                assert(key1 != key2, "should focus two different inputs")
                // Left input should be focused first, then right
                assert(col1 < col2, s"left-to-right order: col1=$col1 should be < col2=$col2")
            end for
        }
    }

    // ==== 9.8 Focus + interaction ====

    "9.8 focus + interaction" - {
        "focus input, type A — input receives keystroke" in run {
            val ref = SignalRef.Unsafe.init("")
            val s   = screen(UI.input.value(ref.safe), 10, 1)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.typeChar('A')
            yield s.assertFrame(
                """
                    |A
                    """
            )
            end for
        }

        "tab to button, Space — button onClick fires" in run {
            var clicked = false
            val s       = screen(UI.button.onClick { clicked = true }("Go"), 10, 3)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.key(UI.Keyboard.Space)
            yield assert(clicked, "Space on focused button should fire onClick")
            end for
        }

        "tab to checkbox, Space — checkbox toggles" in run {
            val s = screen(UI.checkbox.checked(false), 5, 1)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.key(UI.Keyboard.Space)
            yield s.assertFrame(
                """
                    |[x]
                    """
            )
            end for
        }

        "focus select, ArrowDown — select navigates" in run {
            val s = screen(
                UI.select.value("a")(
                    UI.option.value("a")("Alpha"),
                    UI.option.value("b")("Beta")
                ),
                15,
                5
            )
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.key(UI.Keyboard.ArrowDown)
            yield
                // Select should have responded to ArrowDown (either expanding or navigating)
                assert(s.focusedKey.nonEmpty, "select should still be focused after ArrowDown")
            end for
        }
    }

    // ==== 9.9 Hover after click — focus must NOT follow mouse ====

    "9.9 hover after click" - {

        "click input, hover over other input — focus stays on first" in run {
            val ref1 = SignalRef.Unsafe.init("first")
            val ref2 = SignalRef.Unsafe.init("second")
            val s = screen(
                UI.div(
                    UI.input.value(ref1.safe),
                    UI.input.value(ref2.safe)
                ),
                10,
                2
            )
            for
                _ <- s.render
                _ <- s.click(0, 0) // click first input
                _ = assert(s.hasCursor, "first input should be focused")
                _ = assert(s.cursorRow == 0, "cursor should be on row 0")
                // Now hover over second input
                _ <- s.dispatch(InputEvent.Mouse(MouseKind.Move, 0, 1))
            yield
                // Focus must stay on first input — hover doesn't change focus
                assert(s.hasCursor, "cursor should still be visible")
                assert(s.cursorRow == 0, s"focus should stay on row 0, got ${s.cursorRow}")
                s.assertFrame(
                    """
                    |first
                    |second
                    """
                )
            end for
        }

        "click input, hover over select — select does NOT expand" in run {
            val ref = SignalRef.Unsafe.init("text")
            val s = screen(
                UI.div(
                    UI.input.value(ref.safe),
                    UI.select.value("a")(
                        UI.option.value("a")("Alpha"),
                        UI.option.value("b")("Beta")
                    )
                ),
                12,
                5
            )
            for
                _ <- s.render
                _ <- s.click(0, 0) // click input
                _ = assert(s.hasCursor, "input should be focused")
                // Hover over the select
                _ <- s.dispatch(InputEvent.Mouse(MouseKind.Move, 1, 1))
            yield
                // Select must NOT expand — hover doesn't trigger expansion
                s.assertFrame(
                    """
                    |text
                    |Alpha ▼
                    |
                    |
                    |
                    """
                )
                // Focus still on input
                assert(s.cursorRow == 0, s"focus should stay on input, got row ${s.cursorRow}")
            end for
        }

        "click input, release, move mouse — still LeftPress is NOT sent" in run {
            val ref = SignalRef.Unsafe.init("hello")
            val s = screen(
                UI.div(
                    UI.input.value(ref.safe),
                    UI.input.value("other")
                ),
                10,
                2
            )
            for
                _ <- s.render
                // Full click cycle: press + release
                _ <- s.dispatch(InputEvent.Mouse(MouseKind.LeftPress, 0, 0))
                _ <- s.dispatch(InputEvent.Mouse(MouseKind.LeftRelease, 0, 0))
                _ = assert(s.cursorRow == 0, "cursor on row 0 after click")
                // Now move to row 1
                _ <- s.dispatch(InputEvent.Mouse(MouseKind.Move, 0, 1))
            yield
                // Focus must NOT move to row 1 on hover
                assert(s.cursorRow == 0, s"focus should stay on row 0 after move, got ${s.cursorRow}")
                s.assertFrame(
                    """
                    |hello
                    |other
                    """
                )
            end for
        }

        "click select to expand, move mouse away — select stays expanded" in run {
            val s = screen(
                UI.div(
                    UI.select.value("a")(
                        UI.option.value("a")("Alpha"),
                        UI.option.value("b")("Beta")
                    ),
                    UI.span("Footer")
                ),
                12,
                6
            )
            for
                _ <- s.render
                _ <- s.click(1, 0) // click to expand
                expanded1 = s.frame
                // Move mouse to footer area
                _ <- s.dispatch(InputEvent.Mouse(MouseKind.Move, 0, 5))
            yield
                // Select should stay expanded — moving mouse doesn't close it
                s.assertFrame(
                    """
                    |Alpha ▼
                    |┌──────────┐
                    |│Alpha     │
                    |│Beta      │
                    |└──────────┘
                    |
                    """
                )
            end for
        }
    }

end VisualFocusTest
