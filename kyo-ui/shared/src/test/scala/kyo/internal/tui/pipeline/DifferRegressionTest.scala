package kyo.internal.tui.pipeline

import kyo.*
import kyo.Length.*
import kyo.Test
import scala.language.implicitConversions

/** Tests that the Differ produces correct ANSI output across multiple render cycles. Verifies that content doesn't disappear after
  * dispatch+re-render.
  */
class DifferRegressionTest extends Test:

    import AllowUnsafe.embrace.danger

    "transparent fg does not emit color code" - {
        "Cell.Empty has transparent fg and bg" in {
            assert(Cell.Empty.fg == RGB.Transparent)
            assert(Cell.Empty.bg == RGB.Transparent)
        }

        "differ skips transparent fg" in {
            val empty = CellGrid.empty(5, 1)
            val curr  = CellGrid.empty(5, 1)
            // Set one cell with transparent fg — differ should NOT emit fg color
            val cells    = Array.fill(5)(Cell.Empty.copy(char = 'A'))
            val modified = CellGrid(5, 1, Span.from(cells), Chunk.empty)
            val bytes    = Differ.diff(empty, modified)
            val output   = new String(bytes, "UTF-8")
            // Should have 'A' but no 38;2 (fg color sequence) since fg is transparent
            assert(output.contains("A"), s"missing content: $output")
            assert(!output.contains("38;2"), s"should not emit fg color for transparent: $output")
        }

        "differ skips transparent bg" in {
            val empty    = CellGrid.empty(5, 1)
            val cells    = Array.fill(5)(Cell.Empty.copy(char = 'B'))
            val modified = CellGrid(5, 1, Span.from(cells), Chunk.empty)
            val bytes    = Differ.diff(empty, modified)
            val output   = new String(bytes, "UTF-8")
            assert(!output.contains("48;2"), s"should not emit bg color for transparent: $output")
        }
    }

    "multi-render stability" - {
        "second render after no changes produces empty diff" in run {
            for
                nameRef <- Signal.initRef("")
            yield
                val s = Screen(
                    UI.div(
                        UI.label("Name:"),
                        UI.input.value(nameRef)
                    ),
                    30,
                    5
                )
                for
                    _ <- s.render
                    firstFrame = s.frame
                    _ <- s.render // re-render without changes
                    secondFrame = s.frame
                yield assert(firstFrame == secondFrame, s"frames should be identical after no-change re-render")
                end for
        }

        "content preserved after typing" in run {
            for
                nameRef  <- Signal.initRef("")
                emailRef <- Signal.initRef("")
            yield
                val s = Screen(
                    UI.div(
                        UI.div(UI.label("Name:"), UI.input.value(nameRef)),
                        UI.div(UI.label("Email:"), UI.input.value(emailRef)),
                        UI.span("footer")
                    ),
                    40,
                    10
                )
                for
                    _ <- s.render
                    _           = s.assertAllPresent("Name:", "Email:", "footer")
                    beforeFrame = s.frame

                    // Click and type in first input
                    _ <- s.click(0, 1)
                    _ = s.assertAllPresent("Name:", "Email:", "footer")

                    _ <- s.typeChar('A')
                    _ = s.assertAllPresent("Name:", "Email:", "footer", "A")

                    _ <- s.typeChar('B')
                    afterFrame = s.frame
                    // In Plain theme, input shrinks to content width after typing.
                    // Focused scroll window shows last char "B", not full "AB".
                    _ = s.assertAllPresent("Name:", "Email:", "footer", "B")
                yield
                    // Verify the "Email:" label survived all renders
                    assert(afterFrame.contains("Email:"), s"Email label disappeared!\nBefore:\n$beforeFrame\nAfter:\n$afterFrame")
                end for
        }

        "all cells within viewport after demo-like form" in run {
            for
                nameRef  <- Signal.initRef("")
                emailRef <- Signal.initRef("")
                passRef  <- Signal.initRef("")
            yield
                val s = Screen(
                    UI.div(
                        UI.h1("Demo"),
                        UI.hr,
                        UI.div.style(Style.row.gap(2.px))(
                            UI.div.style(Style.width(30.px))(
                                UI.div(UI.label("Name:"), UI.input.value(nameRef)),
                                UI.div(UI.label("Email:"), UI.input.value(emailRef)),
                                UI.div(UI.label("Pass:"), UI.password.value(passRef)),
                                UI.button("Submit")
                            ),
                            UI.div.style(Style.width(30.px))(
                                UI.h2("Results")
                            )
                        )
                    ),
                    80,
                    20
                )
                val markers = Seq("Demo", "Name:", "Email:", "Pass:", "Results")
                for
                    _ <- s.render
                    _ = s.assertAllPresent(markers*)

                    // Tab to name input (first focusable)
                    _ <- s.tab
                    _ <- s.typeChar('J')
                    _ = s.assertAllPresent(markers*)
                    _ <- s.typeChar('o')
                    _ = s.assertAllPresent(markers*)

                    // Tab to email
                    _ <- s.tab
                    _ = s.assertAllPresent(markers*)
                    _ <- s.typeChar('x')
                    _ = s.assertAllPresent(markers*)

                    // Tab to password
                    _ <- s.tab
                    _ = s.assertAllPresent(markers*)
                    _ <- s.typeChar('p')
                    _ = s.assertAllPresent(markers*)
                yield
                    val n = nameRef.unsafe.get()
                    val e = emailRef.unsafe.get()
                    val p = passRef.unsafe.get()
                    assert(n == "Jo", s"name: '$n', email: '$e', pass: '$p', focusables: ${s.focusableCount}, frame:\n${s.frame}")
                    assert(e == "x", s"email: '$e'")
                    assert(p == "p", s"pass: '$p'")
                    s.assertAllPresent(markers*)
                end for
        }
    }

    "cursor visibility" - {
        "unfocused input has no cursor" in run {
            val s = Screen(UI.input.value(""), 10, 1)
            s.render.andThen {
                assert(!s.hasCursor, "unfocused input should not show cursor")
            }
        }

        "focused input shows cursor" in run {
            val s = Screen(UI.input.value(""), 10, 1)
            for
                _ <- s.render
                _ <- s.click(0, 0)
            yield assert(s.hasCursor, s"focused input should show cursor")
            end for
        }
    }

end DifferRegressionTest
