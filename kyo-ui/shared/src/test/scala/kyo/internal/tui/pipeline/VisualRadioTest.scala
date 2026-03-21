package kyo.internal.tui.pipeline

import kyo.*
import kyo.Length.*
import kyo.Test
import scala.language.implicitConversions

class VisualRadioTest extends Test:

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

    // ==== 6.1 Static rendering ====

    "6.1 static rendering" - {
        "unchecked radio" in run {
            assertRender(UI.radio.checked(false), 5, 1)(
                """
                |( )
                """
            )
        }

        "checked radio" in run {
            assertRender(UI.radio.checked(true), 5, 1)(
                """
                |(•)
                """
            )
        }

        "default radio (no checked attr)" in run {
            assertRender(UI.radio, 5, 1)(
                """
                |( )
                """
            )
        }
    }

    // ==== 6.2 Toggle ====

    "6.2 toggle" - {
        "click unchecked becomes checked" in run {
            val ref = SignalRef.Unsafe.init(false)
            val s   = screen(UI.radio.checked(ref.safe), 5, 1)
            for
                _ <- s.render
                _ <- s.click(1, 0)
            yield s.assertFrame(
                """
                    |(•)
                    """
            )
            end for
        }

        "click checked becomes unchecked" in run {
            val ref = SignalRef.Unsafe.init(true)
            val s   = screen(UI.radio.checked(ref.safe), 5, 1)
            for
                _ <- s.render
                _ <- s.click(1, 0)
            yield s.assertFrame(
                """
                    |( )
                    """
            )
            end for
        }

        "onChange fires on click" in run {
            var received: Option[Boolean] = None
            val ref                       = SignalRef.Unsafe.init(false)
            val s = screen(
                UI.radio.checked(ref.safe).onChange(b => received = Some(b)),
                5,
                1
            )
            for
                _ <- s.render
                _ <- s.click(1, 0)
            yield assert(received == Some(true), s"onChange received: $received")
            end for
        }
    }

    // ==== 6.3 Keyboard ====

    "6.3 keyboard" - {
        "space on focused radio toggles" in run {
            val ref = SignalRef.Unsafe.init(false)
            val s   = screen(UI.radio.checked(ref.safe), 5, 1)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.key(UI.Keyboard.Space)
            yield s.assertFrame(
                """
                    |(•)
                    """
            )
            end for
        }
    }

    // ==== 6.4 Disabled ====

    "6.4 disabled" - {
        "disabled radio click has no effect" in run {
            val ref = SignalRef.Unsafe.init(false)
            val s   = screen(UI.radio.checked(ref.safe).disabled(true), 5, 1)
            for
                _ <- s.render
                _ <- s.click(1, 0)
            yield s.assertFrame(
                """
                    |( )
                    """
            )
            end for
        }

        "disabled radio space has no effect" in run {
            val ref = SignalRef.Unsafe.init(false)
            val s = screen(
                UI.div(
                    UI.radio.checked(ref.safe).disabled(true),
                    UI.input.value("ok")
                ),
                5,
                2
            )
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.key(UI.Keyboard.Space)
            yield
                // Space should not toggle disabled radio; tab should skip it
                assert(ref.get() == false, s"disabled radio should not toggle, ref=${ref.get()}")
            end for
        }

        "tab skips disabled radio" in run {
            val s = screen(
                UI.div(
                    UI.radio.checked(false).disabled(true),
                    UI.input.value("ok")
                ),
                5,
                2
            )
            for
                _ <- s.render
                _ <- s.tab
            yield
                // Tab should skip disabled radio and focus the input
                assert(s.hasCursor)
                assert(s.cursorRow == 1)
            end for
        }
    }

    // ==== 6.5 With label ====

    "6.5 with label" - {
        "radio beside label text" in run {
            assertRender(
                UI.div.style(Style.row)(
                    UI.radio.checked(false),
                    UI.span(" Option A")
                ),
                15,
                1
            )(
                """
                |( ) Option A
                """
            )
        }
    }

    // ==== 6.6 Multiple radios ====

    "6.6 multiple radios" - {
        "three radios stacked" in run {
            assertRender(
                UI.div(
                    UI.radio.checked(false),
                    UI.radio.checked(false),
                    UI.radio.checked(false)
                ),
                5,
                3
            )(
                """
                |( )
                |( )
                |( )
                """
            )
        }

        "toggle is independent per radio" in run {
            val ref1 = SignalRef.Unsafe.init(false)
            val ref2 = SignalRef.Unsafe.init(false)
            val ref3 = SignalRef.Unsafe.init(false)
            val s = screen(
                UI.div(
                    UI.radio.checked(ref1.safe),
                    UI.radio.checked(ref2.safe),
                    UI.radio.checked(ref3.safe)
                ),
                5,
                3
            )
            for
                _ <- s.render
                _ <- s.click(1, 1) // click middle radio
            yield s.assertFrame(
                """
                    |( )
                    |(•)
                    |( )
                    """
            )
            end for
        }
    }

    // ==== 6.7 Containment ====

    "6.7 containment" - {
        "radio in 3-col container fits" in run {
            assertRender(
                UI.div.style(Style.width(3.px))(
                    UI.radio.checked(false)
                ),
                5,
                1
            )(
                """
                |( )
                """
            )
        }

        "radio in 2-col container truncated" in run {
            assertRender(
                UI.div.style(Style.width(2.px))(
                    UI.radio.checked(false)
                ),
                5,
                1
            )(
                """
                |(
                """
            )
        }

        "multiple radios in column no overlap" in run {
            assertRender(
                UI.div(
                    UI.radio.checked(true),
                    UI.radio.checked(false)
                ),
                5,
                2
            )(
                """
                |(•)
                |( )
                """
            )
        }
    }

end VisualRadioTest
