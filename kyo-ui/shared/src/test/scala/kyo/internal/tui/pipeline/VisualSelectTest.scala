package kyo.internal.tui.pipeline

import kyo.*
import kyo.Length.*
import kyo.Test
import scala.language.implicitConversions

class VisualSelectTest extends Test:

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

    // ==== 4.1 Collapsed rendering ====

    "4.1 collapsed rendering" - {
        "select with value b, options (a=Alpha, b=Beta) — shows Beta ▼" in run {
            assertRender(
                UI.select.value("b")(
                    UI.option.value("a")("Alpha"),
                    UI.option.value("b")("Beta")
                ),
                10,
                1
            )(
                """
                |Beta ▼
                """
            )
        }

        "select with non-matching value x — shows x ▼" in run {
            assertRender(
                UI.select.value("x")(
                    UI.option.value("a")("Alpha"),
                    UI.option.value("b")("Beta")
                ),
                10,
                1
            )(
                """
                |x ▼
                """
            )
        }

        "select with empty value — shows space ▼" in run {
            assertRender(
                UI.select.value("")(
                    UI.option.value("a")("Alpha"),
                    UI.option.value("b")("Beta")
                ),
                10,
                1
            )(
                """
                | ▼
                """
            )
        }

        "select with no options — shows space ▼, no crash" in run {
            assertRender(
                UI.select.value(""),
                10,
                1
            )(
                """
                | ▼
                """
            )
        }
    }

    // ==== 4.2 Expand/collapse ====

    "4.2 expand/collapse" - {
        "collapsed — shows value and arrow only" in run {
            val s = screen(
                UI.select.value("a")(
                    UI.option.value("a")("Alpha"),
                    UI.option.value("b")("Beta")
                ),
                12,
                5
            )
            for
                _ <- s.render
            yield s.assertFrame(
                """
                    |Alpha ▼
                    |
                    |
                    |
                    |
                    """
            )
            end for
        }

        "click expands — shows bordered popup with options" in run {
            val s = screen(
                UI.select.value("a")(
                    UI.option.value("a")("Alpha"),
                    UI.option.value("b")("Beta")
                ),
                12,
                6
            )
            for
                _ <- s.render
                _ <- s.click(1, 0)
            yield s.assertFrame(
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

        "escape closes — back to collapsed" in run {
            val s = screen(
                UI.select.value("a")(
                    UI.option.value("a")("Alpha"),
                    UI.option.value("b")("Beta")
                ),
                12,
                6
            )
            for
                _ <- s.render
                _ <- s.click(1, 0)
                _ <- s.key(UI.Keyboard.Escape)
            yield s.assertFrame(
                """
                    |Alpha ▼
                    |
                    |
                    |
                    |
                    |
                    """
            )
            end for
        }

        "ArrowDown + enter selects second option" in run {
            val ref = SignalRef.Unsafe.init("a")
            val s = screen(
                UI.select.value(ref.safe)(
                    UI.option.value("a")("Alpha"),
                    UI.option.value("b")("Beta")
                ),
                12,
                5
            )
            for
                _ <- s.render
                _ <- s.click(1, 0)
                _ <- s.key(UI.Keyboard.ArrowDown)
                _ <- s.enter
            yield s.assertFrame(
                """
                    |Beta ▼
                    |
                    |
                    |
                    |
                    """
            )
            end for
        }

        "click → escape → click re-expands" in run {
            val s = screen(
                UI.select.value("a")(
                    UI.option.value("a")("Alpha"),
                    UI.option.value("b")("Beta")
                ),
                12,
                6
            )
            for
                _ <- s.render
                _ <- s.click(1, 0)
                _ <- s.key(UI.Keyboard.Escape)
                _ <- s.click(1, 0)
            yield s.assertFrame(
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

        "hover does NOT expand — only click does" in run {
            val s = screen(
                UI.select.value("a")(
                    UI.option.value("a")("Alpha"),
                    UI.option.value("b")("Beta")
                ),
                12,
                4
            )
            for
                _ <- s.render
                _ = s.assertFrame(
                    """
                    |Alpha ▼
                    |
                    |
                    |
                    """
                )
                _ <- s.dispatch(InputEvent.Mouse(MouseKind.Move, 1, 0))
            yield s.assertFrame(
                """
                    |Alpha ▼
                    |
                    |
                    |
                    """
            )
            end for
        }

        "expanded popup with sibling below — popup overlays sibling" in run {
            val s = screen(
                UI.div(
                    UI.select.value("a")(
                        UI.option.value("a")("Alpha"),
                        UI.option.value("b")("Beta")
                    ),
                    UI.span("Below")
                ),
                12,
                6
            )
            for
                _ <- s.render
                _ = s.assertFrame(
                    """
                    |Alpha ▼
                    |Below
                    |
                    |
                    |
                    |
                    """
                )
                _ <- s.click(1, 0)
            yield s.assertFrame(
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

        "three options expanded" in run {
            val s = screen(
                UI.select.value("a")(
                    UI.option.value("a")("Alpha"),
                    UI.option.value("b")("Beta"),
                    UI.option.value("c")("Gamma")
                ),
                12,
                7
            )
            for
                _ <- s.render
                _ <- s.click(1, 0)
            yield s.assertFrame(
                """
                    |Alpha ▼
                    |┌──────────┐
                    |│Alpha     │
                    |│Beta      │
                    |│Gamma     │
                    |└──────────┘
                    |
                    """
            )
            end for
        }

        "expanded popup does not push sibling — base layer preserved" in run {
            val s = screen(
                UI.div(
                    UI.span("Header"),
                    UI.select.value("a")(
                        UI.option.value("a")("Alpha"),
                        UI.option.value("b")("Beta")
                    ),
                    UI.span("Footer")
                ),
                12,
                8
            )
            for
                _ <- s.render
                _ = s.assertFrame(
                    """
                    |Header
                    |Alpha ▼
                    |Footer
                    |
                    |
                    |
                    |
                    |
                    """
                )
                _ <- s.click(1, 1) // click on select (row 1)
            yield
                // Header and Footer should stay in place, popup overlays below select
                s.assertFrame(
                    """
                    |Header
                    |Alpha ▼
                    |┌──────────┐
                    |│Alpha     │
                    |│Beta      │
                    |└──────────┘
                    |
                    |
                    """
                )
            end for
        }
    }

    // ==== 4.3 Expanded popup content ====

    "4.3 expanded popup content" - {
        "three options — popup shows all three texts on separate lines" in run {
            val s = screen(
                UI.select.value("a")(
                    UI.option.value("a")("Alpha"),
                    UI.option.value("b")("Beta"),
                    UI.option.value("c")("Gamma")
                ),
                12,
                7
            )
            for
                _ <- s.render
                _ <- s.click(1, 0)
            yield s.assertFrame(
                """
                |Alpha ▼
                |┌──────────┐
                |│Alpha     │
                |│Beta      │
                |│Gamma     │
                |└──────────┘
                |
                """
            )
            end for
        }

        "popup text is readable (not black-on-black) — KNOWN BUG" in run {
            // The popup uses Style.empty which renders as blank/black area.
            // This test verifies correct behavior: option text should be visible.
            val s = screen(
                UI.select.value("a")(
                    UI.option.value("a")("Alpha"),
                    UI.option.value("b")("Beta")
                ),
                12,
                6
            )
            for
                _ <- s.render
                _ <- s.click(1, 0)
            yield s.assertFrame(
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

        "popup uses overlay — does not push siblings down" in run {
            val s = screen(
                UI.div(
                    UI.select.value("a")(
                        UI.option.value("a")("Alpha"),
                        UI.option.value("b")("Beta")
                    ),
                    UI.span("Footer")
                ),
                12,
                7
            )
            for
                _ <- s.render
                _ = s.assertFrame(
                    """
                    |Alpha ▼
                    |Footer
                    |
                    |
                    |
                    |
                    |
                    """
                )
                _ <- s.click(1, 0) // expand
            yield
                // Popup overlays, Footer stays at its row (row 1 is now covered by popup border)
                s.assertFrame(
                    """
                    |Alpha ▼
                    |┌──────────┐
                    |│Alpha     │
                    |│Beta      │
                    |└──────────┘
                    |
                    |
                    """
                )
            end for
        }

        "popup positioned below the select row" in run {
            val s = screen(
                UI.select.value("a")(
                    UI.option.value("a")("Alpha"),
                    UI.option.value("b")("Beta")
                ),
                12,
                6
            )
            for
                _ <- s.render
                _ <- s.click(1, 0)
            yield s.assertFrame(
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

    // ==== 4.4 Keyboard navigation ====

    "4.4 keyboard navigation" - {
        "ArrowDown from first — highlight moves to second" in run {
            var selected = ""
            val s = screen(
                UI.select.value("a").onChange(v => selected = v)(
                    UI.option.value("a")("Alpha"),
                    UI.option.value("b")("Beta"),
                    UI.option.value("c")("Gamma")
                ),
                10,
                5
            )
            for
                _ <- s.render
                _ <- s.click(1, 0)
                _ <- s.key(UI.Keyboard.ArrowDown)
                _ <- s.enter
            yield assert(selected == "b", s"ArrowDown from first should select second, got: $selected")
            end for
        }

        "ArrowDown from last — stays on last" in run {
            var selected = ""
            val s = screen(
                UI.select.value("a").onChange(v => selected = v)(
                    UI.option.value("a")("Alpha"),
                    UI.option.value("b")("Beta")
                ),
                10,
                5
            )
            for
                _ <- s.render
                _ <- s.click(1, 0)
                _ <- s.key(UI.Keyboard.ArrowDown) // -> Beta (index 1)
                _ <- s.key(UI.Keyboard.ArrowDown) // stays at Beta (index 1, last)
                _ <- s.enter
            yield assert(selected == "b", s"ArrowDown past last should stay on last, got: $selected")
            end for
        }

        "ArrowUp from second — highlight moves to first" in run {
            var selected = ""
            val s = screen(
                UI.select.value("a").onChange(v => selected = v)(
                    UI.option.value("a")("Alpha"),
                    UI.option.value("b")("Beta"),
                    UI.option.value("c")("Gamma")
                ),
                10,
                5
            )
            for
                _ <- s.render
                _ <- s.click(1, 0)
                _ <- s.key(UI.Keyboard.ArrowDown) // -> Beta
                _ <- s.key(UI.Keyboard.ArrowUp)   // -> Alpha
                _ <- s.enter
            yield assert(selected == "a", s"ArrowUp from second should select first, got: $selected")
            end for
        }

        "ArrowUp from first — stays on first" in run {
            var selected = ""
            val s = screen(
                UI.select.value("a").onChange(v => selected = v)(
                    UI.option.value("a")("Alpha"),
                    UI.option.value("b")("Beta")
                ),
                10,
                5
            )
            for
                _ <- s.render
                _ <- s.click(1, 0)
                _ <- s.key(UI.Keyboard.ArrowUp) // stays at Alpha (index 0, first)
                _ <- s.enter
            yield assert(selected == "a", s"ArrowUp from first should stay on first, got: $selected")
            end for
        }

        "ArrowDown, ArrowDown, Enter — selects third option" in run {
            var selected = ""
            val s = screen(
                UI.select.value("a").onChange(v => selected = v)(
                    UI.option.value("a")("Alpha"),
                    UI.option.value("b")("Beta"),
                    UI.option.value("c")("Gamma")
                ),
                10,
                6
            )
            for
                _ <- s.render
                _ <- s.click(1, 0)
                _ <- s.key(UI.Keyboard.ArrowDown)
                _ <- s.key(UI.Keyboard.ArrowDown)
                _ <- s.enter
            yield assert(selected == "c", s"Two ArrowDowns + Enter should select third, got: $selected")
            end for
        }

        "Enter selects current highlight — onChange fires, display updates" in run {
            var selected = ""
            val ref      = SignalRef.Unsafe.init("a")
            val s = screen(
                UI.select.value(ref.safe).onChange(v => selected = v)(
                    UI.option.value("a")("Alpha"),
                    UI.option.value("b")("Beta")
                ),
                10,
                5
            )
            for
                _ <- s.render
                _ <- s.click(1, 0)
                _ <- s.key(UI.Keyboard.ArrowDown)
                _ <- s.enter
            yield
                assert(selected == "b", s"onChange should fire with 'b', got: $selected")
                s.assertFrame(
                    """
                    |Beta ▼
                    |
                    |
                    |
                    |
                    """
                )
            end for
        }
    }

    // ==== 4.5 Selection persistence ====

    "4.5 selection persistence" - {
        "select option B — display shows Beta ▼" in run {
            val ref = SignalRef.Unsafe.init("a")
            val s = screen(
                UI.select.value(ref.safe)(
                    UI.option.value("a")("Alpha"),
                    UI.option.value("b")("Beta")
                ),
                10,
                5
            )
            for
                _ <- s.render
                _ <- s.click(1, 0)
                _ <- s.key(UI.Keyboard.ArrowDown)
                _ <- s.enter
            yield s.assertFrame(
                """
                    |Beta ▼
                    |
                    |
                    |
                    |
                    """
            )
            end for
        }

        "re-expand — previously selected still indicated" in run {
            val ref = SignalRef.Unsafe.init("a")
            val s = screen(
                UI.select.value(ref.safe)(
                    UI.option.value("a")("Alpha"),
                    UI.option.value("b")("Beta")
                ),
                12,
                6
            )
            for
                _ <- s.render
                _ <- s.click(1, 0)                // expand
                _ <- s.key(UI.Keyboard.ArrowDown) // highlight Beta
                _ <- s.enter                      // select Beta, collapse
                _ <- s.click(1, 0)                // re-expand
            yield s.assertFrame(
                """
                |Beta ▼
                |┌──────────┐
                |│Alpha     │
                |│Beta      │
                |└──────────┘
                |
                """
            )
            end for
        }

        "onChange handler receives correct value string" in run {
            var received = ""
            val s = screen(
                UI.select.value("a").onChange(v => received = v)(
                    UI.option.value("a")("Alpha"),
                    UI.option.value("b")("Beta"),
                    UI.option.value("c")("Gamma")
                ),
                10,
                6
            )
            for
                _ <- s.render
                _ <- s.click(1, 0)
                _ <- s.key(UI.Keyboard.ArrowDown)
                _ <- s.key(UI.Keyboard.ArrowDown)
                _ <- s.enter
            yield assert(received == "c", s"onChange should receive 'c', got: $received")
            end for
        }
    }

    // ==== 4.6 Disabled ====

    "4.6 disabled" - {
        "disabled select — shows value, click does nothing" in run {
            val s = screen(
                UI.select.value("a").disabled(true)(
                    UI.option.value("a")("Alpha"),
                    UI.option.value("b")("Beta")
                ),
                10,
                5
            )
            for
                _ <- s.render
                _ <- s.click(1, 0)
            yield
                // Should still show collapsed state, no popup
                s.assertFrame(
                    """
                    |Alpha ▼
                    |
                    |
                    |
                    |
                    """
                )
            end for
        }

        "disabled select — tab skips it" in run {
            val s = screen(
                UI.div(
                    UI.select.value("a").disabled(true)(
                        UI.option.value("a")("Alpha")
                    ),
                    UI.input.value("next")
                ),
                10,
                3
            )
            for
                _ <- s.render
                _ <- s.tab
            yield
                // Tab should skip the disabled select and focus the input
                assert(s.hasCursor, "cursor should be on the input, not the disabled select")
                assert(s.cursorRow == 1, s"cursor should be on row 1 (input), got row ${s.cursorRow}")
            end for
        }

        "disabled select — keyboard ignored" in run {
            var selected = ""
            val s = screen(
                UI.select.value("a").disabled(true).onChange(v => selected = v)(
                    UI.option.value("a")("Alpha"),
                    UI.option.value("b")("Beta")
                ),
                10,
                5
            )
            for
                _ <- s.render
                _ <- s.click(1, 0)
                _ <- s.key(UI.Keyboard.ArrowDown)
                _ <- s.enter
            yield assert(selected == "", s"disabled select should ignore keyboard, got: $selected")
            end for
        }
    }

    // ==== 4.7 SignalRef binding ====

    "4.7 signalref binding" - {
        "select with value(ref) — reflects ref value" in run {
            val ref = SignalRef.Unsafe.init("b")
            val s = screen(
                UI.select.value(ref.safe)(
                    UI.option.value("a")("Alpha"),
                    UI.option.value("b")("Beta")
                ),
                10,
                1
            )
            s.render.andThen {
                s.assertFrame(
                    """
                    |Beta ▼
                    """
                )
            }
        }

        "selecting option updates ref" in run {
            val ref = SignalRef.Unsafe.init("a")
            val s = screen(
                UI.select.value(ref.safe)(
                    UI.option.value("a")("Alpha"),
                    UI.option.value("b")("Beta")
                ),
                10,
                5
            )
            for
                _ <- s.render
                _ <- s.click(1, 0)
                _ <- s.key(UI.Keyboard.ArrowDown)
                _ <- s.enter
            yield
                // After selecting Beta, the display should update
                s.assertFrame(
                    """
                    |Beta ▼
                    |
                    |
                    |
                    |
                    """
                )
            end for
        }
    }

    // ==== 4.8 Edge cases ====

    "4.8 edge cases" - {
        "one option only — shows it when expanded" in run {
            val s = screen(
                UI.select.value("only")(
                    UI.option.value("only")("Only")
                ),
                12,
                5
            )
            for
                _ <- s.render
                _ <- s.click(1, 0)
            yield s.assertFrame(
                """
                |Only ▼
                |┌──────────┐
                |│Only      │
                |└──────────┘
                |
                """
            )
            end for
        }

        "20 options in 5-row viewport — renders without crash" in run {
            val options = (1 to 20).map(i => UI.option.value(s"opt$i")(s"Option $i"))
            val s = screen(
                UI.select.value("opt1")(options*),
                15,
                5
            )
            for
                _ <- s.render
                _ <- s.click(1, 0)
            yield succeed
            end for
        }

        "option text longer than container — truncated" in run {
            assertRender(
                UI.select.value("long")(
                    UI.option.value("long")("VeryLongOptionText")
                ),
                10,
                1
            )(
                """
                |VeryLongOp
                """
            )
        }

        "rapidly opening/closing — no visual corruption" in run {
            val s = screen(
                UI.select.value("a")(
                    UI.option.value("a")("Alpha"),
                    UI.option.value("b")("Beta")
                ),
                10,
                5
            )
            for
                _ <- s.render
                _ <- s.click(1, 0)             // open
                _ <- s.key(UI.Keyboard.Escape) // close
                _ <- s.click(1, 0)             // open
                _ <- s.key(UI.Keyboard.Escape) // close
                _ <- s.click(1, 0)             // open
                _ <- s.key(UI.Keyboard.Escape) // close
            yield
                // After closing, should be back to collapsed state
                s.assertFrame(
                    """
                    |Alpha ▼
                    |
                    |
                    |
                    |
                    """
                )
            end for
        }
    }

    // ==== 4.9 Containment ====

    "4.9 containment" - {
        "select in narrow container — text and arrow share width" in run {
            // "Alpha" (5) + " ▼" (2) = 7 in 5-col container. Both shrink.
            assertRender(
                UI.div.style(Style.width(5.px))(
                    UI.select.value("a")(
                        UI.option.value("a")("Alpha")
                    )
                ),
                10,
                1
            )(
                """
                |Alph
                """
            )
        }

        "collapsed select beside other widgets in row — no overlap" in run {
            assertRender(
                UI.div.style(Style.row)(
                    UI.span.style(Style.width(5.px))("Tag: "),
                    UI.select.value("b")(
                        UI.option.value("a")("Alpha"),
                        UI.option.value("b")("Beta")
                    )
                ),
                15,
                1
            )(
                """
                |Tag: Beta ▼
                """
            )
        }

        "popup does not overflow viewport width" in run {
            val s = screen(
                UI.select.value("a")(
                    UI.option.value("a")("Alpha"),
                    UI.option.value("b")("BetaLongText")
                ),
                12,
                7
            )
            for
                _ <- s.render
                _ <- s.click(1, 0)
            yield s.assertFrame(
                """
                |Alpha ▼
                |┌──────────┐
                |│Alpha     │
                |│BetaLongTe│
                |│xt        │
                |└──────────┘
                |
                """
            )
            end for
        }

        "select in bordered container — popup renders outside border (overlay)" in run {
            val s = screen(
                UI.div.style(
                    Style.border(1.px, Style.Color.rgb(128, 128, 128))
                        .width(12.px).height(3.px)
                )(
                    UI.select.value("a")(
                        UI.option.value("a")("Alpha"),
                        UI.option.value("b")("Beta")
                    )
                ),
                12,
                7
            )
            for
                _ <- s.render
                _ = s.assertFrame(
                    """
                    |┌──────────┐
                    |│Alpha ▼   │
                    |└──────────┘
                    |
                    |
                    |
                    |
                    """
                )
                _ <- s.click(2, 1) // click inside the border on the select
            yield s.assertFrame(
                """
                |┌──────────┐
                |│Alpha ▼   │
                |└┌────────┐┘
                | │Alpha   │
                | │Beta    │
                | └────────┘
                |
                """
            )
            end for
        }
    }

    // ==== 4.10 Demo dropdown lifecycle — tests for correct overlay behavior ====
    // These tests define what CORRECT rendering looks like.
    // If the implementation is buggy, these tests SHOULD fail.

    "4.10 dropdown lifecycle with siblings" - {

        "collapsed — sibling below is visible" in run {
            val s = screen(
                UI.div(
                    UI.span("Header"),
                    UI.select.value("a")(
                        UI.option.value("a")("Alpha"),
                        UI.option.value("b")("Beta")
                    ),
                    UI.span("Footer")
                ),
                12,
                5
            )
            for
                _ <- s.render
            yield s.assertFrame(
                """
                    |Header
                    |Alpha ▼
                    |Footer
                    |
                    |
                    """
            )
            end for
        }

        "expanded — popup overlays footer, does NOT push it down" in run {
            val s = screen(
                UI.div(
                    UI.span("Header"),
                    UI.select.value("a")(
                        UI.option.value("a")("Alpha"),
                        UI.option.value("b")("Beta")
                    ),
                    UI.span("Footer")
                ),
                12,
                7
            )
            for
                _ <- s.render
                _ <- s.click(1, 1) // click on select at row 1
            yield
                // CORRECT: popup overlays below select, footer is covered by popup
                // Header stays at row 0, select at row 1, popup at rows 2-5
                s.assertFrame(
                    """
                    |Header
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

        "select option — popup closes, display updates, footer returns" in run {
            val ref = SignalRef.Unsafe.init("a")
            val s = screen(
                UI.div(
                    UI.span("Header"),
                    UI.select.value(ref.safe)(
                        UI.option.value("a")("Alpha"),
                        UI.option.value("b")("Beta")
                    ),
                    UI.span("Footer")
                ),
                12,
                5
            )
            for
                _ <- s.render
                _ <- s.click(1, 1)                // expand
                _ <- s.key(UI.Keyboard.ArrowDown) // highlight Beta
                _ <- s.enter                      // select Beta
            yield
                // CORRECT: after selection, popup closes, display shows Beta, footer visible again
                s.assertFrame(
                    """
                    |Header
                    |Beta ▼
                    |Footer
                    |
                    |
                    """
                )
            end for
        }

        "escape — popup closes, nothing changed" in run {
            val s = screen(
                UI.div(
                    UI.span("Header"),
                    UI.select.value("a")(
                        UI.option.value("a")("Alpha"),
                        UI.option.value("b")("Beta")
                    ),
                    UI.span("Footer")
                ),
                12,
                5
            )
            for
                _ <- s.render
                _ <- s.click(1, 1)             // expand
                _ <- s.key(UI.Keyboard.Escape) // close
            yield s.assertFrame(
                """
                    |Header
                    |Alpha ▼
                    |Footer
                    |
                    |
                    """
            )
            end for
        }

        "full cycle: collapse → expand → navigate → select → collapse" in run {
            val ref = SignalRef.Unsafe.init("a")
            val s = screen(
                UI.div(
                    UI.div("Role:"),
                    UI.select.value(ref.safe)(
                        UI.option.value("a")("Dev"),
                        UI.option.value("b")("Design"),
                        UI.option.value("c")("Manager")
                    ),
                    UI.div("---")
                ),
                12,
                8
            )
            for
                // Step 1: initial collapsed state
                _ <- s.render
                _ = s.assertFrame(
                    """
                    |Role:
                    |Dev ▼
                    |---
                    |
                    |
                    |
                    |
                    |
                    """
                )
                // Step 2: click to expand
                _ <- s.click(1, 1)
                _ = s.assertFrame(
                    """
                    |Role:
                    |Dev ▼
                    |┌──────────┐
                    |│Dev       │
                    |│Design    │
                    |│Manager   │
                    |└──────────┘
                    |
                    """
                )
                // Step 3: ArrowDown twice to highlight Manager
                _ <- s.key(UI.Keyboard.ArrowDown)
                _ <- s.key(UI.Keyboard.ArrowDown)
                // Step 4: Enter to select Manager
                _ <- s.enter
            yield
                // Step 5: collapsed with new value, separator visible
                s.assertFrame(
                    """
                    |Role:
                    |Manager ▼
                    |---
                    |
                    |
                    |
                    |
                    |
                    """
                )
            end for
        }

        "two selects — first expanded, popup covers second" in run {
            val s = screen(
                UI.div(
                    UI.select.value("a")(
                        UI.option.value("a")("Alpha"),
                        UI.option.value("b")("Beta")
                    ),
                    UI.select.value("x")(
                        UI.option.value("x")("X-ray"),
                        UI.option.value("y")("Yankee")
                    )
                ),
                12,
                7
            )
            for
                _ <- s.render
                _ = s.assertFrame(
                    """
                    |Alpha ▼
                    |X-ray ▼
                    |
                    |
                    |
                    |
                    |
                    """
                )
                _ <- s.click(1, 0) // click first select
            yield
                // Popup overlays rows 1-4, covering second select (at row 1 in base)
                s.assertFrame(
                    """
                    |Alpha ▼
                    |┌──────────┐
                    |│Alpha     │
                    |│Beta      │
                    |└──────────┘
                    |
                    |
                    """
                )
            end for
        }

        "select inside form with label — demo pattern" in run {
            val ref = SignalRef.Unsafe.init("dev")
            val s = screen(
                UI.form(
                    UI.div("Role:"),
                    UI.select.value(ref.safe)(
                        UI.option.value("dev")("Developer"),
                        UI.option.value("des")("Designer")
                    ),
                    UI.button("Submit")
                ),
                14,
                8
            )
            for
                _ <- s.render
                _ = s.assertFrame(
                    """
                    |Role:
                    |Developer ▼
                    |Submit
                    |
                    |
                    |
                    |
                    |
                    """
                )
                _ <- s.click(1, 1) // expand select
            yield
                // Popup overlays the button, button stays in base layer
                s.assertFrame(
                    """
                    |Role:
                    |Developer ▼
                    |┌────────────┐
                    |│Developer   │
                    |│Designer    │
                    |└────────────┘
                    |
                    |
                    """
                )
            end for
        }
    }

    // ==== 4.11 Popup width matches select width, not viewport ====

    "4.11 popup width" - {
        "popup width matches select container, not full viewport" in run {
            // Select in a 15-col container inside a 30-col viewport.
            // In Default theme, select has width(100.pct) so it fills the 15-col container.
            // Popup should be 15 cols wide (matching select container), not 30 (viewport).
            val s = Screen(
                UI.div.style(Style.row)(
                    UI.div.style(Style.width(15.px))(
                        UI.select.value("a")(
                            UI.option.value("a")("Alpha"),
                            UI.option.value("b")("Beta")
                        )
                    ),
                    UI.span("Right side")
                ),
                30,
                8,
                Theme.Default
            )
            for
                _ <- s.render
                _ <- s.click(2, 1) // click inside the bordered select
            yield
                // Default theme: select has border+padding, fills 15 cols.
                // Collapsed select = 3 rows (border top, content, border bottom).
                // Popup appears below, also 15 cols wide.
                s.assertFrame(
                    """
                    |┌─────────────┐Right side
                    |│ Alpha ▼     │
                    |└─────────────┘
                    |┌─────────────┐
                    |│Alpha        │
                    |│Beta         │
                    |└─────────────┘
                    |
                    """
                )
            end for
        }

        "popup in narrow container — width constrained" in run {
            // In Default theme, select has width(100.pct) so it fills the 10-col container.
            // Popup should be 10 cols wide (container width), not 20 (viewport).
            val s = Screen(
                UI.div.style(Style.width(10.px))(
                    UI.select.value("a")(
                        UI.option.value("a")("Alpha"),
                        UI.option.value("b")("Beta")
                    )
                ),
                20,
                8,
                Theme.Default
            )
            for
                _ <- s.render
                _ <- s.click(2, 1) // click inside the bordered select
            yield s.assertFrame(
                """
                    |┌────────┐
                    |│ Alpha ▼│
                    |└────────┘
                    |┌────────┐
                    |│Alpha   │
                    |│Beta    │
                    |└────────┘
                    |
                    """
            )
            end for
        }
    }

end VisualSelectTest
