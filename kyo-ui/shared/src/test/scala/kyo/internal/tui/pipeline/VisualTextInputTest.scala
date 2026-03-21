package kyo.internal.tui.pipeline

import kyo.*
import kyo.Length.*
import kyo.Test
import scala.language.implicitConversions

class VisualTextInputTest extends Test:

    import AllowUnsafe.embrace.danger

    def screen(ui: UI, cols: Int, rows: Int) = Screen(ui, cols, rows)

    def assertRender(ui: UI, cols: Int, rows: Int, theme: Theme = Theme.Plain)(expected: String) =
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

    // ==== 1.1 Rendering — empty state ====

    "1.1 rendering empty" - {
        "empty input unfocused — blank" in run {
            assertRender(UI.input.value(""), 10, 1)(
                """
                |
                """
            )
        }

        "empty input with placeholder unfocused" in run {
            assertRender(UI.input.value("").placeholder("Name"), 10, 1)(
                """
                |Name
                """
            )
        }

        "empty input with placeholder focused — placeholder disappears" in run {
            val ref = SignalRef.Unsafe.init("")
            val s   = screen(UI.input.value(ref.safe).placeholder("Name"), 10, 1)
            for
                _ <- s.render
                _ <- s.tab
            yield
                s.assertFrame(
                    """
                    |
                    """
                )
                assert(s.hasCursor)
                assert(s.cursorCol == 0)
            end for
        }
    }

    // ==== 1.2 Rendering — with value ====

    "1.2 rendering with value" - {
        "unfocused shows value" in run {
            assertRender(UI.input.value("hello"), 10, 1)(
                """
                |hello
                """
            )
        }

        "focused shows value with cursor at end" in run {
            val ref = SignalRef.Unsafe.init("hello")
            val s   = screen(UI.input.value(ref.safe), 10, 1)
            for
                _ <- s.render
                _ <- s.tab
            yield
                s.assertFrame(
                    """
                    |hello
                    """
                )
                assert(s.hasCursor)
                assert(s.cursorCol == 5)
            end for
        }

        "value with spaces preserved" in run {
            assertRender(UI.input.value("a b c"), 10, 1)(
                """
                |a b c
                """
            )
        }
    }

    // ==== 1.3 SignalRef binding ====

    "1.3 signalref binding" - {
        "initial empty ref" in run {
            val ref = SignalRef.Unsafe.init("")
            val s   = screen(UI.input.value(ref.safe), 10, 1)
            s.render.andThen {
                s.assertFrame(
                    """
                    |
                    """
                )
            }
        }

        "type AB — shows AB" in run {
            val ref = SignalRef.Unsafe.init("")
            val s   = screen(UI.input.value(ref.safe), 10, 1)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.typeChar('A')
                _ <- s.typeChar('B')
            yield s.assertFrame(
                """
                    |AB
                    """
            )
            end for
        }

        "type AB then backspace — shows A" in run {
            val ref = SignalRef.Unsafe.init("")
            val s   = screen(UI.input.value(ref.safe), 10, 1)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.typeChar('A')
                _ <- s.typeChar('B')
                _ <- s.backspace
            yield
                s.assertFrame(
                    """
                    |A
                    """
                )
                assert(s.cursorCol == 1)
            end for
        }
    }

    // ==== 1.4 Cursor position ====

    "1.4 cursor position" - {
        "empty input focused — cursor at 0" in run {
            val ref = SignalRef.Unsafe.init("")
            val s   = screen(UI.input.value(ref.safe), 10, 1)
            for
                _ <- s.render
                _ <- s.tab
            yield
                assert(s.hasCursor)
                assert(s.cursorCol == 0)
            end for
        }

        "hello focused — cursor at 5" in run {
            val ref = SignalRef.Unsafe.init("hello")
            val s   = screen(UI.input.value(ref.safe), 10, 1)
            for
                _ <- s.render
                _ <- s.tab
            yield assert(s.cursorCol == 5)
            end for
        }

        "type AB — cursor at 2" in run {
            val ref = SignalRef.Unsafe.init("")
            val s   = screen(UI.input.value(ref.safe), 10, 1)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.typeChar('A')
                _ <- s.typeChar('B')
            yield assert(s.cursorCol == 2)
            end for
        }

        "type AB, ArrowLeft — cursor at 1" in run {
            val ref = SignalRef.Unsafe.init("")
            val s   = screen(UI.input.value(ref.safe), 10, 1)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.typeChar('A')
                _ <- s.typeChar('B')
                _ <- s.arrowLeft
            yield assert(s.cursorCol == 1)
            end for
        }

        "type AB, Home — cursor at 0" in run {
            val ref = SignalRef.Unsafe.init("")
            val s   = screen(UI.input.value(ref.safe), 10, 1)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.typeChar('A')
                _ <- s.typeChar('B')
                _ <- s.key(UI.Keyboard.Home)
            yield assert(s.cursorCol == 0)
            end for
        }

        "type AB, End — cursor at 2" in run {
            val ref = SignalRef.Unsafe.init("")
            val s   = screen(UI.input.value(ref.safe), 10, 1)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.typeChar('A')
                _ <- s.typeChar('B')
                _ <- s.key(UI.Keyboard.Home)
                _ <- s.key(UI.Keyboard.End)
            yield assert(s.cursorCol == 2)
            end for
        }

        "type AB, Home, ArrowRight — cursor at 1" in run {
            val ref = SignalRef.Unsafe.init("")
            val s   = screen(UI.input.value(ref.safe), 10, 1)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.typeChar('A')
                _ <- s.typeChar('B')
                _ <- s.key(UI.Keyboard.Home)
                _ <- s.arrowRight
            yield assert(s.cursorCol == 1)
            end for
        }

        "ArrowLeft at 0 — stays at 0" in run {
            val ref = SignalRef.Unsafe.init("")
            val s   = screen(UI.input.value(ref.safe), 10, 1)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.arrowLeft
            yield assert(s.cursorCol == 0)
            end for
        }

        "ArrowRight at end — stays at end" in run {
            val ref = SignalRef.Unsafe.init("AB")
            val s   = screen(UI.input.value(ref.safe), 10, 1)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.arrowRight
            yield assert(s.cursorCol == 2)
            end for
        }
    }

    // ==== 1.5 Cursor movement across renders ====

    "1.5 cursor across renders" - {
        "type A, type B, backspace — sequential cursor check" in run {
            val ref = SignalRef.Unsafe.init("")
            val s   = screen(UI.input.value(ref.safe), 10, 1)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.typeChar('A')
                pos1 = s.cursorCol
                _ <- s.typeChar('B')
                pos2 = s.cursorCol
                _ <- s.backspace
                pos3 = s.cursorCol
            yield
                assert(pos1 == 1, s"after A: $pos1")
                assert(pos2 == 2, s"after AB: $pos2")
                assert(pos3 == 1, s"after backspace: $pos3")
            end for
        }

        "tab between two inputs — cursor moves rows" in run {
            val s = screen(
                UI.div(
                    UI.input.value("first"),
                    UI.input.value("second")
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
            yield assert(row2 > row1, s"cursor should move down: $row1 → $row2")
            end for
        }
    }

    // ==== 1.6 Editing ====

    "1.6 editing" - {
        "insert at middle" in run {
            val ref = SignalRef.Unsafe.init("")
            val s   = screen(UI.input.value(ref.safe), 10, 1)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.typeChar('A')
                _ <- s.typeChar('C')
                _ <- s.arrowLeft
                _ <- s.typeChar('B')
            yield
                s.assertFrame(
                    """
                    |ABC
                    """
                )
                assert(s.cursorCol == 2)
            end for
        }

        "backspace at middle" in run {
            val ref = SignalRef.Unsafe.init("")
            val s   = screen(UI.input.value(ref.safe), 10, 1)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.typeChar('A')
                _ <- s.typeChar('B')
                _ <- s.typeChar('C')
                _ <- s.arrowLeft
                _ <- s.backspace
            yield
                s.assertFrame(
                    """
                    |AC
                    """
                )
                assert(s.cursorCol == 1)
            end for
        }

        "delete at middle" in run {
            val ref = SignalRef.Unsafe.init("")
            val s   = screen(UI.input.value(ref.safe), 10, 1)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.typeChar('A')
                _ <- s.typeChar('B')
                _ <- s.typeChar('C')
                _ <- s.key(UI.Keyboard.Home)
                _ <- s.arrowRight
                _ <- s.key(UI.Keyboard.Delete)
            yield
                s.assertFrame(
                    """
                    |AC
                    """
                )
                assert(s.cursorCol == 1)
            end for
        }

        "backspace at position 0 — no change" in run {
            val ref = SignalRef.Unsafe.init("AB")
            val s   = screen(UI.input.value(ref.safe), 10, 1)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.key(UI.Keyboard.Home)
                _ <- s.backspace
            yield
                s.assertFrame(
                    """
                    |AB
                    """
                )
                assert(s.cursorCol == 0)
            end for
        }

        "delete at end — no change" in run {
            val ref = SignalRef.Unsafe.init("AB")
            val s   = screen(UI.input.value(ref.safe), 10, 1)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.key(UI.Keyboard.Delete)
            yield
                s.assertFrame(
                    """
                    |AB
                    """
                )
                assert(s.cursorCol == 2)
            end for
        }
    }

    // ==== 1.7 Placeholder behavior ====

    "1.7 placeholder" - {
        "visible when empty unfocused" in run {
            assertRender(UI.input.value("").placeholder("Type here"), 15, 1)(
                """
                |Type here
                """
            )
        }

        "hidden when empty focused" in run {
            val ref = SignalRef.Unsafe.init("")
            val s   = screen(UI.input.value(ref.safe).placeholder("Type here"), 15, 1)
            for
                _ <- s.render
                _ <- s.tab
            yield
                s.assertFrame(
                    """
                    |
                    """
                )
                assert(s.hasCursor)
                assert(s.cursorCol == 0)
            end for
        }

        "disappears when value non-empty" in run {
            val ref = SignalRef.Unsafe.init("")
            val s   = screen(UI.input.value(ref.safe).placeholder("Type here"), 15, 1)
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

        "hidden while focused even after backspace to empty" in run {
            val ref = SignalRef.Unsafe.init("")
            val s   = screen(UI.input.value(ref.safe).placeholder("hint"), 10, 1)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.typeChar('X')
                _ <- s.backspace
            yield
                // Still focused — placeholder stays hidden
                s.assertFrame(
                    """
                    |
                    """
                )
                assert(s.hasCursor)
            end for
        }
    }

    // ==== 1.8 Disabled state ====

    "1.8 disabled" - {
        "shows value but no cursor" in run {
            val s = screen(UI.input.value("hello").disabled(true), 10, 1)
            for
                _ <- s.render
                _ <- s.tab
            yield
                s.assertFrame(
                    """
                    |hello
                    """
                )
                assert(!s.hasCursor)
            end for
        }

        "ignores typing" in run {
            val ref = SignalRef.Unsafe.init("AB")
            val s   = screen(UI.input.value(ref.safe).disabled(true), 10, 1)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.typeChar('X')
            yield s.assertFrame(
                """
                    |AB
                    """
            )
            end for
        }

        "not focusable by tab" in run {
            val s = screen(
                UI.div(
                    UI.input.value("dis").disabled(true),
                    UI.input.value("ok")
                ),
                10,
                2
            )
            for
                _ <- s.render
                _ <- s.tab
            yield
                // Tab should skip disabled, focus "ok"
                assert(s.hasCursor)
                assert(s.cursorRow == 1)
            end for
        }
    }

    // ==== 1.9 ReadOnly state ====

    "1.9 readonly" - {
        "shows value and cursor when focused" in run {
            val ref = SignalRef.Unsafe.init("AB")
            val s   = screen(UI.input.value(ref.safe).readOnly(true), 10, 1)
            for
                _ <- s.render
                _ <- s.tab
            yield
                s.assertFrame(
                    """
                    |AB
                    """
                )
                assert(s.hasCursor)
                assert(s.cursorCol == 2)
            end for
        }

        "ignores typing" in run {
            val ref = SignalRef.Unsafe.init("AB")
            val s   = screen(UI.input.value(ref.safe).readOnly(true), 10, 1)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.typeChar('X')
            yield s.assertFrame(
                """
                    |AB
                    """
            )
            end for
        }

        "allows cursor movement" in run {
            val ref = SignalRef.Unsafe.init("AB")
            val s   = screen(UI.input.value(ref.safe).readOnly(true), 10, 1)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.arrowLeft
            yield assert(s.cursorCol == 1)
            end for
        }
    }

    // ==== 1.10 Event handlers ====

    "1.10 event handlers" - {
        "onInput fires with new value" in run {
            var received = ""
            val ref      = SignalRef.Unsafe.init("")
            val s        = screen(UI.input.value(ref.safe).onInput(v => received = v), 10, 1)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.typeChar('A')
            yield assert(received == "A", s"onInput received: '$received'")
            end for
        }

        "onChange fires with new value" in run {
            var received = ""
            val ref      = SignalRef.Unsafe.init("")
            val s        = screen(UI.input.value(ref.safe).onChange(v => received = v), 10, 1)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.typeChar('B')
            yield assert(received == "B", s"onChange received: '$received'")
            end for
        }

        "handler receives correct value after backspace" in run {
            var received = ""
            val ref      = SignalRef.Unsafe.init("")
            val s        = screen(UI.input.value(ref.safe).onInput(v => received = v), 10, 1)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.typeChar('A')
                _ <- s.typeChar('B')
                _ <- s.backspace
            yield assert(received == "A", s"after backspace onInput received: '$received'")
            end for
        }
    }

    // ==== 1.11 Containment ====

    "1.11 containment" - {
        "long value in narrow container" in run {
            assertRender(
                UI.div.style(Style.width(8.px))(
                    UI.input.value("abcdefghijklmnop")
                ),
                12,
                1
            )(
                """
                |abcdefgh
                """
            )
        }

        "input beside label in row" in run {
            assertRender(
                UI.div.style(Style.row)(
                    UI.span.style(Style.width(5.px))("Name:"),
                    UI.input.value("Jo")
                ),
                12,
                1
            )(
                """
                |Name:Jo
                """
            )
        }

        "input below label in column" in run {
            assertRender(
                UI.div(
                    UI.div.style(Style.height(1.px))("Name:"),
                    UI.input.value("Jo")
                ),
                10,
                2
            )(
                """
                |Name:
                |Jo
                """
            )
        }

        "input inside bordered container" in run {
            assertRender(
                UI.div.style(
                    Style.border(1.px, Style.Color.rgb(128, 128, 128))
                        .width(10.px).height(3.px)
                )(
                    UI.input.value("hi")
                ),
                10,
                3
            )(
                """
                |┌────────┐
                |│hi      │
                |└────────┘
                """
            )
        }

        "two inputs stacked — no overlap" in run {
            assertRender(
                UI.div(
                    UI.input.value("first"),
                    UI.input.value("second")
                ),
                10,
                2
            )(
                """
                |first
                |second
                """
            )
        }

        "zero-width container — no crash" in run {
            assertRender(
                UI.div.style(Style.width(0.px))(
                    UI.input.value("hello")
                ),
                10,
                1
            )(
                """
                |
                """
            )
        }
    }

    // ==== 1.16 Click-to-position cursor ====

    "1.16 click to position cursor" - {
        "click at column 3 in hello — cursor at 3" in run {
            val ref = SignalRef.Unsafe.init("hello")
            val s   = screen(UI.input.value(ref.safe), 10, 1)
            for
                _ <- s.render
                _ <- s.click(3, 0)
            yield
                s.assertFrame(
                    """
                    |hello
                    """
                )
                assert(s.hasCursor)
                assert(s.cursorCol == 3, s"cursor should be at 3, got ${s.cursorCol}")
            end for
        }

        "click at column 0 in hello — cursor at 0" in run {
            val ref = SignalRef.Unsafe.init("hello")
            val s   = screen(UI.input.value(ref.safe), 10, 1)
            for
                _ <- s.render
                _ <- s.click(0, 0)
            yield assert(s.cursorCol == 0, s"cursor should be at 0, got ${s.cursorCol}")
            end for
        }

        "click past end of text — cursor at end" in run {
            val ref = SignalRef.Unsafe.init("hi")
            val s   = screen(UI.input.value(ref.safe), 10, 1)
            for
                _ <- s.render
                _ <- s.click(8, 0)
            yield assert(s.cursorCol == 2, s"cursor should be at end (2), got ${s.cursorCol}")
            end for
        }

        "click at column 2, then type X — inserts at position 2" in run {
            val ref = SignalRef.Unsafe.init("abcd")
            val s   = screen(UI.input.value(ref.safe), 10, 1)
            for
                _ <- s.render
                _ <- s.click(2, 0)
                _ <- s.typeChar('X')
            yield
                s.assertFrame(
                    """
                    |abXcd
                    """
                )
                assert(s.cursorCol == 3)
            end for
        }
    }

    // ==== 1.17 Placeholder lifecycle ====

    "1.17 placeholder lifecycle" - {
        "unfocused empty input — placeholder visible" in run {
            assertRender(UI.input.value("").placeholder("Name"), 10, 1)(
                """
                |Name
                """
            )
        }

        "focused empty input — placeholder disappears" in run {
            val ref = SignalRef.Unsafe.init("")
            val s   = screen(UI.input.value(ref.safe).placeholder("Name"), 10, 1)
            for
                _ <- s.render
                _ <- s.tab
            yield
                // Placeholder must disappear when focused — cursor at 0 in empty input
                s.assertFrame(
                    """
                    |
                    """
                )
                assert(s.hasCursor)
                assert(s.cursorCol == 0)
            end for
        }

        "click on placeholder — placeholder disappears, cursor at 0" in run {
            val ref = SignalRef.Unsafe.init("")
            val s   = screen(UI.input.value(ref.safe).placeholder("Name"), 10, 1)
            for
                _ <- s.render
                _ <- s.click(2, 0) // click in the middle of placeholder text
            yield
                // Placeholder must disappear on focus
                s.assertFrame(
                    """
                    |
                    """
                )
                assert(s.hasCursor)
                assert(s.cursorCol == 0)
            end for
        }

        "type then clear — placeholder reappears when unfocused" in run {
            val ref = SignalRef.Unsafe.init("")
            val s = screen(
                UI.div(
                    UI.input.value(ref.safe).placeholder("Name"),
                    UI.input.value("other")
                ),
                10,
                2
            )
            for
                _ <- s.render
                _ <- s.tab       // focus first input
                _ <- s.typeChar('A')
                _ <- s.backspace // clear to empty
                _ <- s.tab       // tab to second input — first loses focus
            yield
                // First input is unfocused and empty — placeholder should be back
                s.assertFrame(
                    """
                    |Name
                    |other
                    """
                )
            end for
        }

        "unfocused with value — no placeholder" in run {
            assertRender(UI.input.value("Jo").placeholder("Name"), 10, 1)(
                """
                |Jo
                """
            )
        }
    }

    // ==== 1.18 Text color ====

    "1.18 text color" - {
        "typed text has explicit white fg, not Transparent" in run {
            val ref = SignalRef.Unsafe.init("hello")
            val s   = screen(UI.input.value(ref.safe), 10, 1)
            s.render.andThen {
                val fg = s.fgAt(0, 0)
                assert(fg != RGB.Transparent, s"typed text fg should not be Transparent, got ${fg.raw}")
            }
        }

        "label text has explicit white fg" in run {
            val s = screen(UI.div(UI.label("Name:")), 10, 1)
            s.render.andThen {
                val fg = s.fgAt(0, 0)
                assert(fg != RGB.Transparent, s"label fg should not be Transparent, got ${fg.raw}")
            }
        }

        "placeholder text is dimmed compared to label" in run {
            val s = screen(
                UI.div(
                    UI.label("Label:"),
                    UI.input.value("").placeholder("hint")
                ),
                10,
                2
            )
            s.render.andThen {
                val labelFg       = s.fgAt(0, 0) // "L" of "Label:"
                val placeholderFg = s.fgAt(0, 1) // "h" of "hint"
                // Placeholder should be dimmer (lower brightness) than label
                assert(labelFg != RGB.Transparent, s"label should have explicit color")
                assert(placeholderFg != RGB.Transparent, s"placeholder should have explicit color")
                assert(
                    placeholderFg != labelFg,
                    s"placeholder ($placeholderFg) should be different (dimmer) than label ($labelFg)"
                )
            }
        }
    }

    // ==== 1.19 Input has border in Default theme (like web) ====

    "1.19 input border" - {
        "text input has border and padding in Default theme" in run {
            // 14 cols: border(2) + padding(2) + content(10)
            assertRender(UI.input.value("hello"), 14, 3, Theme.Default)(
                """
                |┌────────────┐
                |│ hello      │
                |└────────────┘
                """
            )
        }

        "empty input with placeholder has border" in run {
            assertRender(UI.input.value("").placeholder("Name"), 14, 3, Theme.Default)(
                """
                |┌────────────┐
                |│ Name       │
                |└────────────┘
                """
            )
        }

        "password input has border" in run {
            assertRender(UI.password.value("abc"), 14, 3, Theme.Default)(
                """
                |┌────────────┐
                |│ •••        │
                |└────────────┘
                """
            )
        }

        "input in Plain theme has no border" in run {
            assertRender(UI.input.value("hello"), 10, 1, Theme.Plain)(
                """
                |hello
                """
            )
        }
    }

    // ==== 1.20 Input fixed height — text does not wrap or expand box ====

    "1.20 input fixed height" - {
        "long text in bordered input — single row, no wrap" in run {
            // In Default theme, input has border. Long text should NOT wrap to second content row.
            // The input should stay 3 rows (border top + 1 content row + border bottom).
            assertRender(
                UI.div.style(Style.width(14.px))(
                    UI.input.value("abcdefghijklmnopqrstuvwxyz")
                ),
                14,
                3,
                Theme.Default
            )(
                """
                |┌────────────┐
                |│ abcdefghij │
                |└────────────┘
                """
            )
        }

        "input height stays fixed regardless of text length" in run {
            // Two inputs with different text lengths should have same height
            assertRender(
                UI.div.style(Style.width(14.px))(
                    UI.input.value("short"),
                    UI.input.value("this is a very long text that should not expand")
                ),
                14,
                6,
                Theme.Default
            )(
                """
                |┌────────────┐
                |│ short      │
                |└────────────┘
                |┌────────────┐
                |│ this is a  │
                |└────────────┘
                """
            )
        }
    }

    // ==== 1.21 Input horizontal scroll — web behavior ====
    // Web <input>: fixed 1-line height, text scrolls horizontally.
    // Unfocused: shows start of text, right part clipped.
    // Focused at end: shows end of text, left part clipped.
    // Cursor always visible within the scroll window.

    "1.21 input horizontal scroll" - {
        "unfocused — shows start of text, clipped on right" in run {
            // 8-col viewport, text "abcdefghijklmno" (15 chars). Shows "abcdefgh".
            assertRender(UI.input.value("abcdefghijklmno"), 8, 1)(
                """
                |abcdefgh
                """
            )
        }

        "tab to focus — cursor at end, shows end of text" in run {
            val ref = SignalRef.Unsafe.init("abcdefghijklmno")
            val s   = screen(UI.input.value(ref.safe), 8, 1)
            for
                _ <- s.render
                _ <- s.tab // focus → cursor at end (position 15)
            yield
                // Visible window scrolls to show cursor at end.
                // 8-col viewport shows last 8 chars: "hijklmno"
                s.assertFrame(
                    """
                    |hijklmno
                    """
                )
                assert(s.cursorCol == 8) // cursor at end-of-text position (one past last visible char)
            end for
        }

        "type past visible width — text scrolls left" in run {
            val ref = SignalRef.Unsafe.init("")
            val s   = screen(UI.input.value(ref.safe), 6, 1)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.typeChar('a')
                _ <- s.typeChar('b')
                _ <- s.typeChar('c')
                _ <- s.typeChar('d')
                _ <- s.typeChar('e')
                _ <- s.typeChar('f')
                _ <- s.typeChar('g') // 7th char, exceeds 6-col viewport
            yield
                // Scroll so cursor (at position 7) is visible
                s.assertFrame(
                    """
                    |bcdefg
                    """
                )
                assert(s.cursorCol == 6) // cursor at end-of-text position (one past last visible char)
            end for
        }

        "arrow left scrolls right to keep cursor visible" in run {
            val ref = SignalRef.Unsafe.init("abcdefghij")
            val s   = screen(UI.input.value(ref.safe), 6, 1)
            for
                _ <- s.render
                _ <- s.tab                   // cursor at end (10), shows "efghij"
                _ <- s.key(UI.Keyboard.Home) // cursor at 0, scroll to start
            yield
                s.assertFrame(
                    """
                    |abcdef
                    """
                )
                assert(s.cursorCol == 0)
            end for
        }

        "click at position in long text — scroll to show click point" in run {
            val ref = SignalRef.Unsafe.init("abcdefghijklmno")
            val s   = screen(UI.input.value(ref.safe), 8, 1)
            for
                _ <- s.render
                _ <- s.click(3, 0) // click at col 3 (on "d")
            yield
                // Unfocused showed "abcdefgh". Click at col 3 → cursor at 3.
                // Start of text is visible, cursor at 3 is within viewport.
                s.assertFrame(
                    """
                    |abcdefgh
                    """
                )
                assert(s.cursorCol == 3)
            end for
        }

        "Default theme — bordered input, long text clipped, no wrap" in run {
            // 14-col container. Border(2) + padding(2) = 4. Content width = 10.
            // Text "abcdefghijklmno" (15 chars) → first 10 visible: "abcdefghij"
            assertRender(
                UI.div.style(Style.width(14.px))(
                    UI.input.value("abcdefghijklmno")
                ),
                14,
                3,
                Theme.Default
            )(
                """
                |┌────────────┐
                |│ abcdefghij │
                |└────────────┘
                """
            )
        }

        "Default theme — two inputs same height regardless of text length" in run {
            assertRender(
                UI.div.style(Style.width(14.px))(
                    UI.input.value("short"),
                    UI.input.value("this is a very long text that should not expand")
                ),
                14,
                6,
                Theme.Default
            )(
                """
                |┌────────────┐
                |│ short      │
                |└────────────┘
                |┌────────────┐
                |│ this is a  │
                |└────────────┘
                """
            )
        }
    }

    // ==== 1.22 Demo pattern: input in bounded container, Default theme ====

    "1.22 demo input pattern" - {
        "input in 20-col container — click to focus, type, box stays full width" in run {
            val ref = SignalRef.Unsafe.init("")
            val s = Screen(
                UI.div.style(Style.width(20.px))(
                    UI.label("Name:"),
                    UI.input.value(ref.safe).placeholder("John Doe")
                ),
                20,
                5,
                Theme.Default
            )
            for
                _ <- s.render
                _ = s.assertFrame(
                    """
                    |Name:
                    |┌──────────────────┐
                    |│ John Doe         │
                    |└──────────────────┘
                    |
                    """
                )
                _ <- s.click(5, 2) // click inside the input content area
                _ = s.assertFrame(
                    """
                    |Name:
                    |┌──────────────────┐
                    |│                  │
                    |└──────────────────┘
                    |
                    """
                )
                _ <- s.typeChar('A')
                _ <- s.typeChar('B')
                _ = s.assertFrame(
                    """
                    |Name:
                    |┌──────────────────┐
                    |│ AB               │
                    |└──────────────────┘
                    |
                    """
                )
                // Type more — box should NEVER shrink
                _ <- s.typeChar('C')
                _ <- s.typeChar('D')
                _ <- s.typeChar('E')
                _ <- s.typeChar('F')
                _ <- s.typeChar('G')
                _ <- s.typeChar('H')
                _ <- s.typeChar('I')
                _ <- s.typeChar('J')
                _ <- s.typeChar('K')
                _ <- s.typeChar('L')
                _ <- s.typeChar('M')
                _ <- s.typeChar('N')
                _ <- s.typeChar('O')
                _ <- s.typeChar('P')
            yield
                // 16 chars typed. Content width = 16 (border 2 + padding 2 eat 4 from 20).
                // All 16 chars fit exactly in 16-col content area. No scroll needed.
                s.assertFrame(
                    """
                    |Name:
                    |┌──────────────────┐
                    |│ ABCDEFGHIJKLMNOP │
                    |└──────────────────┘
                    |
                    """
                )
            end for
        }

        "two inputs stacked in Default theme — both full width after interaction" in run {
            val ref1 = SignalRef.Unsafe.init("")
            val ref2 = SignalRef.Unsafe.init("")
            val s = Screen(
                UI.div.style(Style.width(20.px))(
                    UI.label("Name:"),
                    UI.input.value(ref1.safe).placeholder("Name"),
                    UI.label("Email:"),
                    UI.input.value(ref2.safe).placeholder("Email")
                ),
                20,
                9,
                Theme.Default
            )
            for
                _ <- s.render
                _ = s.assertFrame(
                    """
                    |Name:
                    |┌──────────────────┐
                    |│ Name             │
                    |└──────────────────┘
                    |Email:
                    |┌──────────────────┐
                    |│ Email            │
                    |└──────────────────┘
                    |
                    """
                )
                // Click first input, type
                _ <- s.click(5, 2)
                _ <- s.typeChar('J')
                _ <- s.typeChar('o')
                _ = s.assertFrame(
                    """
                    |Name:
                    |┌──────────────────┐
                    |│ Jo               │
                    |└──────────────────┘
                    |Email:
                    |┌──────────────────┐
                    |│ Email            │
                    |└──────────────────┘
                    |
                    """
                )
                // Tab to second input, type
                _ <- s.tab
                _ <- s.typeChar('a')
                _ <- s.typeChar('@')
                _ <- s.typeChar('b')
            yield
                // Both boxes must remain full width
                s.assertFrame(
                    """
                    |Name:
                    |┌──────────────────┐
                    |│ Jo               │
                    |└──────────────────┘
                    |Email:
                    |┌──────────────────┐
                    |│ a@b              │
                    |└──────────────────┘
                    |
                    """
                )
            end for
        }
    }

    // ==== 1.23 Exact demo form reproduction ====

    "1.23 demo form" - {
        "full demo form — click name, type, tab to email, type — all boxes stay full width" in run {
            for
                nameRef  <- Signal.initRef("")
                emailRef <- Signal.initRef("")
            yield
                val s = Screen(
                    UI.div.style(Style.width(30.px))(
                        UI.div(
                            UI.label("Name:"),
                            UI.input.value(nameRef).placeholder("John Doe")
                        ),
                        UI.div(
                            UI.label("Email:"),
                            UI.input.value(emailRef).placeholder("john@example.com")
                        )
                    ),
                    30,
                    9,
                    Theme.Default
                )
                for
                    _ <- s.render
                    // Initial: both inputs show placeholders, full width
                    _ =
                        val f     = s.frame
                        val lines = f.linesIterator.toVector
                        // Name input border should span full 30 cols
                        assert(lines(1).startsWith("┌"), s"Name border top should be at col 0: ${lines(1)}")
                        assert(lines(1).endsWith("┐"), s"Name border should end with ┐: ${lines(1)}")
                    // Click on name input (row 2 = content row inside border)
                    _ <- s.click(5, 2)
                    _ =
                        val f     = s.frame
                        val lines = f.linesIterator.toVector
                        // After click: name input focused (placeholder gone), border still full width
                        assert(lines(1).startsWith("┌"), s"After click: border should start with ┌: ${lines(1)}")
                        assert(lines(1).length == 30, s"After click: border should be 30 chars: ${lines(1).length}")
                    // Type "Alice"
                    _ <- s.typeChar('A')
                    _ <- s.typeChar('l')
                    _ <- s.typeChar('i')
                    _ <- s.typeChar('c')
                    _ <- s.typeChar('e')
                    _ =
                        val f     = s.frame
                        val lines = f.linesIterator.toVector
                        // Border must still be full width
                        assert(lines(1).length == 30, s"After typing: border should be 30 chars: ${lines(1).length}")
                        assert(lines(2).contains("Alice"), s"After typing: content should contain Alice: ${lines(2)}")
                    // Tab to email
                    _ <- s.tab
                    _ <- s.typeChar('a')
                    _ <- s.typeChar('@')
                    _ <- s.typeChar('b')
                yield
                    val f     = s.frame
                    val lines = f.linesIterator.toVector
                    // BOTH borders must be full width (30 chars)
                    assert(lines(1).length == 30, s"Name border: ${lines(1).length} chars, expected 30")
                    assert(lines(5).length == 30, s"Email border: ${lines(5).length} chars, expected 30")
                    // Name still shows "Alice"
                    assert(lines(2).contains("Alice"), s"Name content missing: ${lines(2)}")
                    // Email shows "a@b"
                    assert(lines(6).contains("a@b"), s"Email content missing: ${lines(6)}")
                end for
        }
    }

end VisualTextInputTest
