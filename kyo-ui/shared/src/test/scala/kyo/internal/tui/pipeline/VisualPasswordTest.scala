package kyo.internal.tui.pipeline

import kyo.*
import kyo.Length.*
import kyo.Test
import scala.language.implicitConversions

class VisualPasswordTest extends Test:

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

    // ==== 2.1 Masking ====

    "2.1 masking" - {
        "value abc unfocused — shows three dots" in run {
            assertRender(UI.password.value("abc"), 10, 1)(
                """
                |•••
                """
            )
        }

        "value secret — six dots" in run {
            assertRender(UI.password.value("secret"), 10, 1)(
                """
                |••••••
                """
            )
        }

        "empty value — blank" in run {
            assertRender(UI.password.value(""), 10, 1)(
                """
                |
                """
            )
        }

        "value a — one dot" in run {
            assertRender(UI.password.value("a"), 10, 1)(
                """
                |•
                """
            )
        }

        "dots use bullet character U+2022" in run {
            assertRender(UI.password.value("xy"), 10, 1)(
                """
                |••
                """
            )
        }

        "plaintext never appears in frame" in run {
            val ref = SignalRef.Unsafe.init("secret")
            val s   = screen(UI.password.value(ref.safe), 10, 1)
            for
                _ <- s.render
                _ <- s.tab
            yield s.assertFrame(
                """
                    |••••••
                    """
            )
            end for
        }
    }

    // ==== 2.2 Typing and masking ====

    "2.2 typing and masking" - {
        "type A — frame shows one dot, cursor 1" in run {
            val ref = SignalRef.Unsafe.init("")
            val s   = screen(UI.password.value(ref.safe), 10, 1)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.typeChar('A')
            yield
                s.assertFrame(
                    """
                    |•
                    """
                )
                assert(s.cursorCol == 1)
            end for
        }

        "type AB — frame shows two dots, cursor 2" in run {
            val ref = SignalRef.Unsafe.init("")
            val s   = screen(UI.password.value(ref.safe), 10, 1)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.typeChar('A')
                _ <- s.typeChar('B')
            yield
                s.assertFrame(
                    """
                    |••
                    """
                )
                assert(s.cursorCol == 2)
            end for
        }

        "type ABC — frame shows three dots, cursor 3" in run {
            val ref = SignalRef.Unsafe.init("")
            val s   = screen(UI.password.value(ref.safe), 10, 1)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.typeChar('A')
                _ <- s.typeChar('B')
                _ <- s.typeChar('C')
            yield
                s.assertFrame(
                    """
                    |•••
                    """
                )
                assert(s.cursorCol == 3)
            end for
        }

        "backspace decreases dot count by 1" in run {
            val ref = SignalRef.Unsafe.init("")
            val s   = screen(UI.password.value(ref.safe), 10, 1)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.typeChar('A')
                _ <- s.typeChar('B')
                _ <- s.typeChar('C')
                _ <- s.backspace
            yield
                s.assertFrame(
                    """
                    |••
                    """
                )
                assert(s.cursorCol == 2)
            end for
        }

        "type AB backspace — frame one dot, cursor 1" in run {
            val ref = SignalRef.Unsafe.init("")
            val s   = screen(UI.password.value(ref.safe), 10, 1)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.typeChar('A')
                _ <- s.typeChar('B')
                _ <- s.backspace
            yield
                s.assertFrame(
                    """
                    |•
                    """
                )
                assert(s.cursorCol == 1)
            end for
        }

        "all backspace to empty — blank frame" in run {
            val ref = SignalRef.Unsafe.init("")
            val s   = screen(UI.password.value(ref.safe), 10, 1)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.typeChar('A')
                _ <- s.typeChar('B')
                _ <- s.backspace
                _ <- s.backspace
            yield
                s.assertFrame(
                    """
                    |
                    """
                )
                assert(s.cursorCol == 0)
            end for
        }
    }

    // ==== 2.3 Cursor position ====

    "2.3 cursor position" - {
        "focused abc — cursor at 3 (after dots)" in run {
            val ref = SignalRef.Unsafe.init("abc")
            val s   = screen(UI.password.value(ref.safe), 10, 1)
            for
                _ <- s.render
                _ <- s.tab
            yield
                s.assertFrame(
                    """
                    |•••
                    """
                )
                assert(s.hasCursor)
                assert(s.cursorCol == 3)
            end for
        }

        "type A ArrowLeft — cursor at 0, frame still one dot" in run {
            val ref = SignalRef.Unsafe.init("")
            val s   = screen(UI.password.value(ref.safe), 10, 1)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.typeChar('A')
                _ <- s.arrowLeft
            yield
                s.assertFrame(
                    """
                    |•
                    """
                )
                assert(s.cursorCol == 0)
            end for
        }

        "Home — cursor at 0" in run {
            val ref = SignalRef.Unsafe.init("abc")
            val s   = screen(UI.password.value(ref.safe), 10, 1)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.key(UI.Keyboard.Home)
            yield assert(s.cursorCol == 0)
            end for
        }

        "End — cursor at dot count" in run {
            val ref = SignalRef.Unsafe.init("abc")
            val s   = screen(UI.password.value(ref.safe), 10, 1)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.key(UI.Keyboard.Home)
                _ <- s.key(UI.Keyboard.End)
            yield assert(s.cursorCol == 3)
            end for
        }
    }

    // ==== 2.4 Placeholder ====

    "2.4 placeholder" - {
        "empty password with placeholder unfocused — shows placeholder not masked" in run {
            assertRender(UI.password.value("").placeholder("Enter password"), 20, 1)(
                """
                |Enter password
                """
            )
        }

        "empty with placeholder focused — placeholder hidden" in run {
            val ref = SignalRef.Unsafe.init("")
            val s   = screen(UI.password.value(ref.safe).placeholder("Enter password"), 20, 1)
            for
                _ <- s.render
                _ <- s.tab
            yield
                // Focused: placeholder disappears
                s.assertFrame(
                    """
                    |
                    """
                )
                assert(s.hasCursor)
                assert(s.cursorCol == 0)
            end for
        }

        "type one char — placeholder disappears, one dot" in run {
            val ref = SignalRef.Unsafe.init("")
            val s   = screen(UI.password.value(ref.safe).placeholder("Enter password"), 20, 1)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.typeChar('x')
            yield s.assertFrame(
                """
                    |•
                    """
            )
            end for
        }

        "backspace to empty while focused — placeholder stays hidden" in run {
            val ref = SignalRef.Unsafe.init("")
            val s   = screen(UI.password.value(ref.safe).placeholder("hint"), 10, 1)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.typeChar('x')
                _ <- s.backspace
            yield
                // Still focused — placeholder hidden
                s.assertFrame(
                    """
                    |
                    """
                )
                assert(s.hasCursor)
            end for
        }
    }

    // ==== 2.5 Disabled ====

    "2.5 disabled" - {
        "disabled with value — dots visible, no cursor" in run {
            val s = screen(UI.password.value("abc").disabled(true), 10, 1)
            for
                _ <- s.render
                _ <- s.tab
            yield
                s.assertFrame(
                    """
                    |•••
                    """
                )
                assert(!s.hasCursor)
            end for
        }

        "tab to disabled password — skipped" in run {
            val s = screen(
                UI.div(
                    UI.password.value("secret").disabled(true),
                    UI.input.value("ok")
                ),
                10,
                2
            )
            for
                _ <- s.render
                _ <- s.tab
            yield
                // Tab should skip disabled password, focus the input "ok"
                assert(s.hasCursor)
                assert(s.cursorRow == 1)
            end for
        }

        "type on disabled — no change" in run {
            val ref = SignalRef.Unsafe.init("AB")
            val s   = screen(UI.password.value(ref.safe).disabled(true), 10, 1)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.typeChar('X')
            yield s.assertFrame(
                """
                    |••
                    """
            )
            end for
        }
    }

    // ==== 2.6 Event handlers ====

    "2.6 event handlers" - {
        "onInput fires with actual plaintext value not dots" in run {
            var received = ""
            val ref      = SignalRef.Unsafe.init("")
            val s        = screen(UI.password.value(ref.safe).onInput(v => received = v), 10, 1)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.typeChar('A')
            yield
                assert(received == "A", s"onInput should receive plaintext 'A', got: '$received'")
                assert(!received.contains("•"), "onInput must not receive dots")
            end for
        }

        "onChange fires with actual plaintext value" in run {
            var received = ""
            val ref      = SignalRef.Unsafe.init("")
            val s        = screen(UI.password.value(ref.safe).onChange(v => received = v), 10, 1)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.typeChar('B')
            yield
                assert(received == "B", s"onChange should receive plaintext 'B', got: '$received'")
                assert(!received.contains("•"), "onChange must not receive dots")
            end for
        }

        "onInput after multiple chars — receives full plaintext" in run {
            var received = ""
            val ref      = SignalRef.Unsafe.init("")
            val s        = screen(UI.password.value(ref.safe).onInput(v => received = v), 10, 1)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.typeChar('s')
                _ <- s.typeChar('e')
                _ <- s.typeChar('c')
            yield assert(received == "sec", s"onInput should receive 'sec', got: '$received'")
            end for
        }

        "onInput after backspace — receives reduced plaintext" in run {
            var received = ""
            val ref      = SignalRef.Unsafe.init("")
            val s        = screen(UI.password.value(ref.safe).onInput(v => received = v), 10, 1)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.typeChar('A')
                _ <- s.typeChar('B')
                _ <- s.backspace
            yield assert(received == "A", s"onInput after backspace should receive 'A', got: '$received'")
            end for
        }
    }

    // ==== 2.7 Containment ====

    "2.7 containment" - {
        "password in narrow container — dots truncated" in run {
            assertRender(
                UI.div.style(Style.width(5.px))(
                    UI.password.value("abcdefghij")
                ),
                10,
                1
            )(
                """
                |•••••
                """
            )
        }

        "password beside label in row — no overlap" in run {
            assertRender(
                UI.div.style(Style.row)(
                    UI.span.style(Style.width(5.px))("Pass:"),
                    UI.password.value("abc")
                ),
                12,
                1
            )(
                """
                |Pass:•••
                """
            )
        }

        "long password 20 chars in 10-col container — 10 dots visible" in run {
            assertRender(
                UI.div.style(Style.width(10.px))(
                    UI.password.value("12345678901234567890")
                ),
                10,
                1
            )(
                """
                |••••••••••
                """
            )
        }
    }

end VisualPasswordTest
