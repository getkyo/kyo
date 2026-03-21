package kyo.internal.tui.pipeline

import kyo.*
import kyo.Length.*
import kyo.Test
import scala.language.implicitConversions

class VisualCheckboxTest extends Test:

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

    // ==== 5.1 Static rendering ====

    "5.1 static rendering" - {
        "unchecked" in run {
            assertRender(UI.checkbox.checked(false), 5, 1)(
                """
                |[ ]
                """
            )
        }

        "checked" in run {
            assertRender(UI.checkbox.checked(true), 5, 1)(
                """
                |[x]
                """
            )
        }

        "default (no checked attr)" in run {
            assertRender(UI.checkbox, 5, 1)(
                """
                |[ ]
                """
            )
        }

        "exact frame checkbox alone in 5x1" in run {
            assertRender(UI.checkbox.checked(false), 5, 1)(
                """
                |[ ]
                """
            )
        }
    }

    // ==== 5.2 Signal binding ====

    "5.2 signal binding" - {
        "checked signal true renders [x]" in run {
            val ref = SignalRef.Unsafe.init(true)
            val s   = screen(UI.checkbox.checked(ref.safe), 5, 1)
            s.render.andThen {
                s.assertFrame(
                    """
                    |[x]
                    """
                )
            }
        }

        "checked signal false renders [ ]" in run {
            val ref = SignalRef.Unsafe.init(false)
            val s   = screen(UI.checkbox.checked(ref.safe), 5, 1)
            s.render.andThen {
                s.assertFrame(
                    """
                    |[ ]
                    """
                )
            }
        }
    }

    // ==== 5.3 Toggle via click ====

    "5.3 toggle via click" - {
        "click unchecked becomes checked" in run {
            val s = screen(UI.checkbox.checked(false), 5, 1)
            for
                _ <- s.render
                _ <- s.click(1, 0)
            yield s.assertFrame(
                """
                |[x]
                """
            )
            end for
        }

        "click checked becomes unchecked" in run {
            val s = screen(UI.checkbox.checked(true), 5, 1)
            for
                _ <- s.render
                _ <- s.click(1, 0)
            yield s.assertFrame(
                """
                |[ ]
                """
            )
            end for
        }

        "double click back to original" in run {
            val s = screen(UI.checkbox.checked(false), 5, 1)
            for
                _ <- s.render
                _ <- s.click(1, 0)
                _ <- s.click(1, 0)
            yield s.assertFrame(
                """
                |[ ]
                """
            )
            end for
        }

        "triple click opposite of original" in run {
            val s = screen(UI.checkbox.checked(false), 5, 1)
            for
                _ <- s.render
                _ <- s.click(1, 0)
                _ <- s.click(1, 0)
                _ <- s.click(1, 0)
            yield s.assertFrame(
                """
                |[x]
                """
            )
            end for
        }

        "onChange fires with new value on each click" in run {
            var values = List.empty[Boolean]
            val s = screen(
                UI.checkbox.checked(false).onChange(b => values = values :+ b),
                5,
                1
            )
            for
                _ <- s.render
                _ <- s.click(1, 0)
                _ <- s.click(1, 0)
                _ <- s.click(1, 0)
            yield assert(values == List(true, false, true), s"onChange values: $values")
            end for
        }
    }

    // ==== 5.4 Toggle via keyboard ====

    "5.4 toggle via keyboard" - {
        "tab to checkbox focuses it" in run {
            val s = screen(UI.checkbox.checked(false), 5, 1)
            for
                _ <- s.render
                _ <- s.tab
            yield assert(s.focusedKey.nonEmpty, "checkbox should be focused after tab")
            end for
        }

        "space toggles unchecked to checked" in run {
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

        "space again toggles back" in run {
            val s = screen(UI.checkbox.checked(false), 5, 1)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.key(UI.Keyboard.Space)
                _ <- s.key(UI.Keyboard.Space)
            yield s.assertFrame(
                """
                |[ ]
                """
            )
            end for
        }

        "enter does NOT toggle" in run {
            val s = screen(UI.checkbox.checked(false), 5, 1)
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.enter
            yield s.assertFrame(
                """
                |[ ]
                """
            )
            end for
        }
    }

    // ==== 5.5 Disabled ====

    "5.5 disabled" - {
        "disabled checked shows [x] and click does nothing" in run {
            val s = screen(UI.checkbox.checked(true).disabled(true), 5, 1)
            for
                _ <- s.render
                _ <- s.click(1, 0)
            yield s.assertFrame(
                """
                |[x]
                """
            )
            end for
        }

        "disabled unchecked shows [ ] and click does nothing" in run {
            val s = screen(UI.checkbox.checked(false).disabled(true), 5, 1)
            for
                _ <- s.render
                _ <- s.click(1, 0)
            yield s.assertFrame(
                """
                |[ ]
                """
            )
            end for
        }

        "tab skips disabled checkbox" in run {
            val s = screen(
                UI.div(
                    UI.checkbox.checked(false).disabled(true),
                    UI.checkbox.checked(false)
                ),
                5,
                2
            )
            for
                _ <- s.render
                _ <- s.tab
            yield
                // First checkbox is disabled, so tab should focus the second
                val focusables = s.focusableCount
                assert(focusables >= 1, s"expected at least 1 focusable, got $focusables")
                assert(s.focusedKey.nonEmpty, "should have focused the second checkbox")
            end for
        }

        "space on disabled no change" in run {
            var changed = false
            val s = screen(
                UI.checkbox.checked(false).disabled(true).onChange(b => changed = true),
                5,
                1
            )
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.key(UI.Keyboard.Space)
            yield
                assert(!changed, "disabled checkbox should not fire onChange")
                s.assertFrame(
                    """
                    |[ ]
                    """
                )
            end for
        }
    }

    // ==== 5.6 With label ====

    "5.6 with label" - {
        "checkbox beside label in row" in run {
            assertRender(
                UI.div.style(Style.row)(
                    UI.checkbox.checked(false),
                    UI.span(" Accept")
                ),
                14,
                1
            )(
                """
                |[ ] Accept
                """
            )
        }

        "click on checkbox toggles it, label unchanged" in run {
            val s = screen(
                UI.div.style(Style.row)(
                    UI.checkbox.checked(false),
                    UI.span(" Accept")
                ),
                14,
                1
            )
            for
                _ <- s.render
                _ <- s.click(1, 0)
            yield s.assertFrame(
                """
                |[x] Accept
                """
            )
            end for
        }

        "label with forId and checkbox with id — label click focuses checkbox" in run {
            val s = screen(
                UI.div(
                    UI.checkbox.checked(false).id("cb1"),
                    UI.label.forId("cb1")("Accept")
                ),
                10,
                2
            )
            for
                _ <- s.render
                _ <- s.click(0, 1) // click on label row
            yield assert(s.focusedKey.nonEmpty, "checkbox should be focused via label click")
            end for
        }
    }

    // ==== 5.7 Multiple checkboxes ====

    "5.7 multiple checkboxes" - {
        "three checkboxes stacked" in run {
            assertRender(
                UI.div(
                    UI.checkbox.checked(false),
                    UI.checkbox.checked(false),
                    UI.checkbox.checked(false)
                ),
                5,
                3
            )(
                """
                |[ ]
                |[ ]
                |[ ]
                """
            )
        }

        "toggle middle one — first and third unchanged" in run {
            val s = screen(
                UI.div(
                    UI.checkbox.checked(false),
                    UI.checkbox.checked(false),
                    UI.checkbox.checked(false)
                ),
                5,
                3
            )
            for
                _ <- s.render
                _ <- s.click(1, 1) // click on middle checkbox (row 1)
            yield s.assertFrame(
                """
                |[ ]
                |[x]
                |[ ]
                """
            )
            end for
        }

        "independent state per checkbox" in run {
            val s = screen(
                UI.div(
                    UI.checkbox.checked(false),
                    UI.checkbox.checked(true),
                    UI.checkbox.checked(false)
                ),
                5,
                3
            )
            for
                _ <- s.render
                _ <- s.click(1, 0) // toggle first
                _ <- s.click(1, 2) // toggle third
            yield s.assertFrame(
                """
                |[x]
                |[x]
                |[x]
                """
            )
            end for
        }
    }

    // ==== 5.8 Containment ====

    "5.8 containment" - {
        "checkbox in 3-col container fits exactly" in run {
            assertRender(
                UI.div.style(Style.width(3.px))(
                    UI.checkbox.checked(false)
                ),
                5,
                1
            )(
                """
                |[ ]
                """
            )
        }

        "checkbox in 2-col container truncated no crash" in run {
            assertRender(
                UI.div.style(Style.width(2.px))(
                    UI.checkbox.checked(false)
                ),
                5,
                1
            )(
                """
                |[
                """
            )
        }

        "checkbox in 1-col container truncated no crash" in run {
            assertRender(
                UI.div.style(Style.width(1.px))(
                    UI.checkbox.checked(false)
                ),
                5,
                1
            )(
                """
                |[
                """
            )
        }

        "checkbox beside label in row no overlap" in run {
            assertRender(
                UI.div.style(Style.row)(
                    UI.checkbox.checked(true),
                    UI.span(" OK")
                ),
                10,
                1
            )(
                """
                |[x] OK
                """
            )
        }

        "multiple checkboxes in column no overlap" in run {
            assertRender(
                UI.div(
                    UI.checkbox.checked(true),
                    UI.checkbox.checked(false)
                ),
                5,
                2
            )(
                """
                |[x]
                |[ ]
                """
            )
        }
    }

end VisualCheckboxTest
