package kyo.internal.tui.pipeline

import kyo.*
import kyo.Length.*
import kyo.Test
import scala.language.implicitConversions

class VisualTextareaTest extends Test:

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

    // ==== 3.1 Static rendering ====

    "3.1 static rendering" - {
        "empty textarea — blank" in run {
            assertRender(UI.textarea.value(""), 10, 3)(
                """
                |
                |
                |
                """
            )
        }

        "textarea with value hello — hello on first row" in run {
            assertRender(UI.textarea.value("hello"), 10, 3)(
                """
                |hello
                |
                |
                """
            )
        }
    }

    // ==== 3.2 Enter inserts newline ====

    "3.2 enter inserts newline" - {
        "type A, Enter, type B — A on row 0, B on row 1" in run {
            val ref = SignalRef.Unsafe.init("")
            val s   = screen(UI.textarea.value(ref.safe), 10, 3)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.typeChar('A')
                _ <- s.enter
                _ <- s.typeChar('B')
            yield s.assertFrame(
                """
                    |A
                    |B
                    |
                    """
            )
            end for
        }

        "multiple Enter — multiple blank rows" in run {
            val ref = SignalRef.Unsafe.init("")
            val s   = screen(UI.textarea.value(ref.safe), 10, 4)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.typeChar('A')
                _ <- s.enter
                _ <- s.enter
                _ <- s.typeChar('B')
            yield s.assertFrame(
                """
                    |A
                    |
                    |B
                    |
                    """
            )
            end for
        }

        "Enter does NOT fire form onSubmit even inside form" in run {
            var submitted = false
            val ref       = SignalRef.Unsafe.init("")
            val s = screen(
                UI.form.onSubmit { submitted = true; () }(
                    UI.textarea.value(ref.safe)
                ),
                10,
                3
            )
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.typeChar('A')
                _ <- s.enter
                _ <- s.typeChar('B')
            yield
                assert(!submitted, "Enter in textarea must NOT fire form onSubmit")
                s.assertFrame(
                    """
                    |A
                    |B
                    |
                    """
                )
            end for
        }
    }

    // ==== 3.3 Multi-line display ====

    "3.3 multi-line display" - {
        "value line1 newline line2 — two rows" in run {
            assertRender(UI.textarea.value("line1\nline2"), 10, 3)(
                """
                |line1
                |line2
                |
                """
            )
        }

        "value with three lines — three rows" in run {
            assertRender(UI.textarea.value("aaa\nbbb\nccc"), 10, 3)(
                """
                |aaa
                |bbb
                |ccc
                """
            )
        }

        "long single line — wraps or truncates within width" in run {
            assertRender(UI.textarea.value("abcdefghijklmno"), 10, 3)(
                """
                |abcdefghij
                |klmno
                |
                """
            )
        }
    }

    // ==== 3.4 Cursor in multi-line ====

    "3.4 cursor in multi-line" - {
        "type A, Enter, type B — cursor on row 1 at column 1" in run {
            val ref = SignalRef.Unsafe.init("")
            val s   = screen(UI.textarea.value(ref.safe), 10, 3)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.typeChar('A')
                _ <- s.enter
                _ <- s.typeChar('B')
            yield
                assert(s.hasCursor)
                assert(s.cursorRow == 1, s"expected cursorRow=1, got ${s.cursorRow}")
                assert(s.cursorCol == 1, s"expected cursorCol=1, got ${s.cursorCol}")
            end for
        }

        "Home — cursor at column 0" in run {
            val ref = SignalRef.Unsafe.init("")
            val s   = screen(UI.textarea.value(ref.safe), 10, 3)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.typeChar('A')
                _ <- s.enter
                _ <- s.typeChar('B')
                _ <- s.key(UI.Keyboard.Home)
            yield
                assert(s.hasCursor)
                assert(s.cursorCol == 0, s"expected cursorCol=0, got ${s.cursorCol}")
            end for
        }
    }

    // ==== 3.5 Disabled and readonly ====

    "3.5 disabled and readonly" - {
        "disabled textarea shows value but no cursor" in run {
            val s = screen(UI.textarea.value("hello").disabled(true), 10, 3)
            for
                _ <- s.render
                _ <- s.tab
            yield
                s.assertFrame(
                    """
                    |hello
                    |
                    |
                    """
                )
                assert(!s.hasCursor)
            end for
        }

        "disabled textarea blocks typing" in run {
            val ref = SignalRef.Unsafe.init("AB")
            val s   = screen(UI.textarea.value(ref.safe).disabled(true), 10, 3)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.typeChar('X')
            yield s.assertFrame(
                """
                    |AB
                    |
                    |
                    """
            )
            end for
        }

        "disabled textarea blocks Enter" in run {
            val ref = SignalRef.Unsafe.init("AB")
            val s   = screen(UI.textarea.value(ref.safe).disabled(true), 10, 3)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.enter
            yield s.assertFrame(
                """
                    |AB
                    |
                    |
                    """
            )
            end for
        }

        "tab skips disabled textarea" in run {
            val s = screen(
                UI.div(
                    UI.textarea.value("dis").disabled(true),
                    UI.input.value("ok")
                ),
                10,
                4
            )
            for
                _ <- s.render
                _ <- s.tab
            yield
                assert(s.hasCursor)
                // The input "ok" is below the textarea, cursor should be on its row
                assert(s.cursorRow >= 1, s"expected cursor on input row, got row ${s.cursorRow}")
            end for
        }

        "readonly textarea shows value and cursor when focused" in run {
            val ref = SignalRef.Unsafe.init("AB")
            val s   = screen(UI.textarea.value(ref.safe).readOnly(true), 10, 3)
            for
                _ <- s.render
                _ <- s.tab
            yield
                s.assertFrame(
                    """
                    |AB
                    |
                    |
                    """
                )
                assert(s.hasCursor)
                assert(s.cursorCol == 2)
            end for
        }

        "readonly textarea blocks typing" in run {
            val ref = SignalRef.Unsafe.init("AB")
            val s   = screen(UI.textarea.value(ref.safe).readOnly(true), 10, 3)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.typeChar('X')
            yield s.assertFrame(
                """
                    |AB
                    |
                    |
                    """
            )
            end for
        }

        "readonly textarea allows cursor movement" in run {
            val ref = SignalRef.Unsafe.init("AB")
            val s   = screen(UI.textarea.value(ref.safe).readOnly(true), 10, 3)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.arrowLeft
            yield assert(s.cursorCol == 1, s"expected cursorCol=1, got ${s.cursorCol}")
            end for
        }

        "readonly textarea blocks backspace" in run {
            val ref = SignalRef.Unsafe.init("AB")
            val s   = screen(UI.textarea.value(ref.safe).readOnly(true), 10, 3)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.backspace
            yield s.assertFrame(
                """
                    |AB
                    |
                    |
                    """
            )
            end for
        }

        "readonly textarea blocks delete" in run {
            val ref = SignalRef.Unsafe.init("AB")
            val s   = screen(UI.textarea.value(ref.safe).readOnly(true), 10, 3)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.key(UI.Keyboard.Home)
                _ <- s.key(UI.Keyboard.Delete)
            yield s.assertFrame(
                """
                    |AB
                    |
                    |
                    """
            )
            end for
        }
    }

    // ==== 3.6 Containment ====

    "3.6 containment" - {
        "textarea in bounded height container — content clipped" in run {
            assertRender(
                UI.div.style(Style.height(3.px))(
                    UI.textarea.value("line1\nline2\nline3\nline4\nline5")
                ),
                10,
                3
            )(
                """
                |line1
                |line2
                |line3
                """
            )
        }

        "textarea in 10x3 viewport with 5 lines — only 3 visible" in run {
            assertRender(
                UI.textarea.value("aaa\nbbb\nccc\nddd\neee"),
                10,
                3
            )(
                """
                |aaa
                |bbb
                |ccc
                """
            )
        }
    }

end VisualTextareaTest
