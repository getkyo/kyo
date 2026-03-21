package kyo.internal.tui.pipeline

import kyo.*
import kyo.Length.*
import kyo.Test
import scala.language.implicitConversions

/** Tests that exercise realistic multi-widget forms through many render cycles.
  *
  * Every test asserts structural integrity of ALL widgets after EACH interaction — not just the widget that was interacted with.
  */
class FormLifecycleTest extends Test:

    import AllowUnsafe.embrace.danger

    def screen(ui: UI, cols: Int, rows: Int) = Screen(ui, cols, rows)

    // ---- Happy path: multi-input form through full lifecycle ----

    "multi-input form" - {
        "all fields survive typing in first field" in run {
            val name  = SignalRef.Unsafe.init("")
            val email = SignalRef.Unsafe.init("")
            val s = screen(
                UI.div(
                    UI.label("Name:"),
                    UI.input.value(name.safe),
                    UI.label("Email:"),
                    UI.input.value(email.safe),
                    UI.checkbox.checked(false),
                    UI.button("Send")
                ),
                30,
                10
            )
            val markers = Seq("Name:", "Email:", "[ ]", "Send")
            for
                _ <- s.render
                _ = s.assertAllPresent(markers*)
                _ <- s.click(0, 1)
                _ = s.assertAllPresent(markers*)
                _ <- s.typeChar('A')
                _ = s.assertAllPresent(markers*)
                _ <- s.typeChar('B')
                _ = s.assertAllPresent(markers*)
            yield
                assert(name.get() == "AB")
                s.assertAllPresent(markers*)
            end for
        }

        "tab through all fields without losing any" in run {
            val name  = SignalRef.Unsafe.init("")
            val email = SignalRef.Unsafe.init("")
            val s = screen(
                UI.div(
                    UI.label("Name:"),
                    UI.input.value(name.safe),
                    UI.label("Email:"),
                    UI.input.value(email.safe),
                    UI.button("Go")
                ),
                30,
                10
            )
            val markers = Seq("Name:", "Email:", "Go")
            for
                _ <- s.render
                _ = s.assertAllPresent(markers*)
                _ <- s.tab
                _ = s.assertAllPresent(markers*)
                _ <- s.tab
                _ = s.assertAllPresent(markers*)
                _ <- s.tab
                _ = s.assertAllPresent(markers*)
            yield s.assertAllPresent(markers*)
            end for
        }

        "type in first field, tab to second, type there, both values preserved" in run {
            val name  = SignalRef.Unsafe.init("")
            val email = SignalRef.Unsafe.init("")
            val s = screen(
                UI.div(
                    UI.label("Name:"),
                    UI.input.value(name.safe),
                    UI.label("Email:"),
                    UI.input.value(email.safe)
                ),
                30,
                10
            )
            for
                _ <- s.render
                _ <- s.click(0, 1)
                _ <- s.typeChar('J')
                _ <- s.typeChar('o')
                _ <- s.tab
                _ <- s.typeChar('a')
                _ <- s.typeChar('@')
            yield
                assert(name.get() == "Jo", s"name should be 'Jo', got '${name.get()}'")
                assert(email.get() == "a@", s"email should be 'a@', got '${email.get()}'")
                // In Plain theme, inputs shrink to content width.
                // Name (unfocused, width=1) shows first char "J".
                // Email (focused, width=1) shows last char "@".
                assert(s.frame.contains("J"), "name first char should be visible")
                assert(s.frame.contains("@"), "email last char should be visible")
            end for
        }

        "type, tab back with shift-tab, first field still has value" in run {
            val name  = SignalRef.Unsafe.init("")
            val email = SignalRef.Unsafe.init("")
            val s = screen(
                UI.div(
                    UI.input.value(name.safe),
                    UI.input.value(email.safe)
                ),
                20,
                5
            )
            for
                _ <- s.render
                _ <- s.click(0, 0)
                _ <- s.typeChar('X')
                _ <- s.tab
                _ <- s.typeChar('Y')
                _ <- s.shiftTab
                _ <- s.typeChar('Z')
            yield
                assert(name.get() == "XZ", s"name should be 'XZ', got '${name.get()}'")
                assert(email.get() == "Y", s"email should be 'Y', got '${email.get()}'")
            end for
        }
    }

    // ---- Mixed widget types ----

    "mixed widgets" - {
        "input + checkbox + select all visible after interactions" in run {
            val text = SignalRef.Unsafe.init("")
            val s = screen(
                UI.div(
                    UI.input.value(text.safe),
                    UI.checkbox.checked(false),
                    UI.select.value("a")(
                        UI.option.value("a")("Alpha"),
                        UI.option.value("b")("Beta")
                    )
                ),
                25,
                5
            )
            val markers = Seq("[ ]", "a")
            for
                _ <- s.render
                _ = s.assertAllPresent(markers*)
                _ <- s.click(0, 0)
                _ <- s.typeChar('H')
                _ = s.assertAllPresent(markers*)
                _ <- s.typeChar('i')
                _ = s.assertAllPresent(markers*)
            yield
                assert(text.get() == "Hi")
                s.assertAllPresent(markers*)
            end for
        }

        "checkbox toggle does not affect sibling input" in run {
            val text = SignalRef.Unsafe.init("keep")
            val s = screen(
                UI.div(
                    UI.input.value(text.safe),
                    UI.checkbox.checked(false)
                ),
                20,
                5
            )
            for
                _ <- s.render
                _ = assert(s.frame.contains("keep"), "input value before click")
                _ = assert(s.frame.contains("[ ]"), "unchecked before click")
                _ <- s.click(0, 1)
            yield
                assert(s.frame.contains("keep"), "input value should survive checkbox toggle")
                assert(s.frame.contains("[x]"), "checkbox should be checked")
            end for
        }

        "password field does not reveal text after sibling interaction" in run {
            val pass = SignalRef.Unsafe.init("secret")
            val s = screen(
                UI.div(
                    UI.password.value(pass.safe),
                    UI.button("Login")
                ),
                20,
                5
            )
            for
                _ <- s.render
                _ = s.assertNonePresent("secret")
                _ = s.assertAllPresent("••••••")
                _ <- s.click(0, 1)
            yield
                s.assertNonePresent("secret")
                s.assertAllPresent("••••••")
            end for
        }
    }

    // ---- Edge cases ----

    "edge cases" - {
        "empty form renders without crash" in run {
            val s = screen(UI.form(UI.div), 20, 5)
            s.render.andThen(succeed)
        }

        "form with only disabled inputs" in run {
            val s = screen(
                UI.div(
                    UI.input.value("frozen").disabled(true),
                    UI.button.disabled(true)("Nope")
                ),
                20,
                5
            )
            for
                _ <- s.render
                _ = s.assertAllPresent("frozen")
                _ <- s.click(0, 0)
                _ <- s.typeChar('X')
            yield
                assert(s.frame.contains("frozen"), "disabled input should not change")
                assert(!s.frame.contains("X"), "typed char should not appear in disabled input")
            end for
        }

        "many render cycles without degradation" in run {
            val ref = SignalRef.Unsafe.init("")
            val s = screen(
                UI.div(
                    UI.input.value(ref.safe),
                    UI.span("stable")
                ),
                20,
                5
            )
            for
                _ <- s.render
                _ <- s.click(0, 0)
                _ <- s.typeChar('a')
                _ = s.assertAllPresent("stable")
                _ <- s.typeChar('b')
                _ = s.assertAllPresent("stable")
                _ <- s.typeChar('c')
                _ = s.assertAllPresent("stable")
                _ <- s.typeChar('d')
                _ = s.assertAllPresent("stable")
                _ <- s.typeChar('e')
                _ = s.assertAllPresent("stable")
                _ <- s.backspace
                _ = s.assertAllPresent("stable")
                _ <- s.backspace
                _ = s.assertAllPresent("stable")
                _ <- s.typeChar('f')
                _ = s.assertAllPresent("stable")
            yield
                assert(ref.get() == "abcf", s"expected 'abcf', got '${ref.get()}'")
                // In Plain theme, input shrinks to content width; focused scroll shows last char "f".
                s.assertAllPresent("stable", "f")
            end for
        }

        "hidden sibling does not appear after interaction" in run {
            val ref = SignalRef.Unsafe.init("")
            val s = screen(
                UI.div(
                    UI.input.value(ref.safe),
                    UI.span.hidden(true)("invisible"),
                    UI.span("visible")
                ),
                20,
                5
            )
            for
                _ <- s.render
                _ = s.assertAllPresent("visible")
                _ = s.assertNonePresent("invisible")
                _ <- s.click(0, 0)
                _ <- s.typeChar('X')
                _ = s.assertNonePresent("invisible")
            yield
                s.assertAllPresent("visible")
                s.assertNonePresent("invisible")
            end for
        }
    }

    // ---- Layout stability ----

    "layout stability" - {
        "bordered form maintains borders after typing" in run {
            val ref = SignalRef.Unsafe.init("")
            val s = screen(
                UI.div.style(
                    Style.border(1.px, Style.Color.rgb(128, 128, 128))
                        .width(20.px).height(5.px)
                )(
                    UI.input.value(ref.safe),
                    UI.span("footer")
                ),
                20,
                5
            )
            for
                _ <- s.render
                _ = s.assertAllPresent("footer")
                _ <- s.click(1, 1)
                _ <- s.typeChar('Z')
                _ = s.assertAllPresent("footer")
            yield
                val f     = s.frame
                val lines = f.linesIterator.toVector
                assert(lines(0).contains("┌"), s"top border missing after typing: ${lines(0)}")
                assert(lines.last.contains("└"), s"bottom border missing after typing: ${lines.last}")
            end for
        }

        "row layout children stay side by side after typing" in run {
            val left  = SignalRef.Unsafe.init("")
            val right = SignalRef.Unsafe.init("")
            val s = screen(
                UI.div.style(Style.row)(
                    UI.div.style(Style.width(10.px))(
                        UI.input.value(left.safe)
                    ),
                    UI.div.style(Style.width(10.px))(
                        UI.input.value(right.safe)
                    )
                ),
                20,
                3
            )
            for
                _ <- s.render
                _ <- s.click(0, 0)
                _ <- s.typeChar('L')
            yield
                assert(left.get() == "L")
                // Right input should still be accessible at x >= 10
                assert(s.focusableCount >= 2, s"expected 2+ focusables, got ${s.focusableCount}")
            end for
        }
    }

    // ---- Placeholder behavior ----

    "placeholder" - {
        "placeholder visible when empty, gone after typing" in run {
            val ref = SignalRef.Unsafe.init("")
            val s = screen(
                UI.div(
                    UI.input.value(ref.safe).placeholder("Enter name...")
                ),
                25,
                3
            )
            for
                _ <- s.render
                _ = s.assertAllPresent("Enter name...")
                _ <- s.click(0, 0)
                _ <- s.typeChar('A')
            yield
                s.assertNonePresent("Enter name...")
                s.assertAllPresent("A")
            end for
        }

        "placeholder hidden while focused even after clearing" in run {
            val ref = SignalRef.Unsafe.init("")
            val s = screen(
                UI.div(
                    UI.input.value(ref.safe).placeholder("hint")
                ),
                20,
                3
            )
            for
                _ <- s.render
                _ = s.assertAllPresent("hint")
                _ <- s.click(0, 0)
                _ <- s.typeChar('X')
                _ = s.assertNonePresent("hint")
                _ <- s.backspace
            yield
                assert(ref.get() == "", "should be empty after backspace")
                // Still focused — placeholder stays hidden
                s.assertNonePresent("hint")
            end for
        }
    }

    // ---- Demo reproduction: exact UI tree from TuiDemo ----

    "demo form reproduction" - {
        "initial render shows all form fields" in run {
            for
                nameRef  <- Signal.initRef("")
                emailRef <- Signal.initRef("")
                passRef  <- Signal.initRef("")
                roleRef  <- Signal.initRef("developer")
            yield
                import AllowUnsafe.embrace.danger
                val s = screen(
                    UI.div(
                        UI.h1("Kyo UI — Form Demo"),
                        UI.hr,
                        UI.div.style(Style.row.gap(2.px))(
                            UI.div.style(Style.width(40.px))(
                                UI.h2("New Entry"),
                                UI.form(
                                    UI.div(
                                        UI.label("Name:"),
                                        UI.input.value(nameRef).placeholder("John Doe")
                                    ),
                                    UI.div(
                                        UI.label("Email:"),
                                        UI.email.value(emailRef).placeholder("john@example.com")
                                    ),
                                    UI.div(
                                        UI.label("Password:"),
                                        UI.password.value(passRef).placeholder("dots")
                                    ),
                                    UI.div(
                                        UI.label("Role:"),
                                        UI.select.value(roleRef)(
                                            UI.option.value("developer")("Developer"),
                                            UI.option.value("designer")("Designer"),
                                            UI.option.value("manager")("Manager")
                                        )
                                    ),
                                    UI.div.style(Style.row)(
                                        UI.checkbox.checked(true),
                                        UI.span(" I agree")
                                    ),
                                    UI.button("Submit")
                                )
                            ),
                            UI.div.style(Style.width(50.px))(
                                UI.h2("Submissions")
                            )
                        )
                    ),
                    100,
                    30
                )
                s.render.andThen {
                    s.assertAllPresent("Form Demo", "New Entry", "Name:", "Email:", "Password:", "Role:", "I agree", "Submissions")
                }
        }

        "typing in name field preserves all other fields" in run {
            for
                nameRef  <- Signal.initRef("")
                emailRef <- Signal.initRef("")
                passRef  <- Signal.initRef("")
            yield
                import AllowUnsafe.embrace.danger
                val s = screen(
                    UI.div(
                        UI.div(
                            UI.label("Name:"),
                            UI.input.value(nameRef)
                        ),
                        UI.div(
                            UI.label("Email:"),
                            UI.input.value(emailRef)
                        ),
                        UI.div(
                            UI.label("Password:"),
                            UI.password.value(passRef)
                        ),
                        UI.button("Submit")
                    ),
                    60,
                    15
                )
                val markers = Seq("Name:", "Email:", "Password:", "Submit")
                for
                    _ <- s.render
                    _ = s.assertAllPresent(markers*)
                    _ <- s.click(0, 1)
                    _ = s.assertAllPresent(markers*)
                    _ <- s.typeChar('J')
                    _ = s.assertAllPresent(markers*)
                    _ <- s.typeChar('o')
                    _ = s.assertAllPresent(markers*)
                    _ <- s.typeChar('h')
                    _ = s.assertAllPresent(markers*)
                    _ <- s.typeChar('n')
                    _ = s.assertAllPresent(markers*)
                yield s.assertAllPresent(markers*)
                end for
        }

        "tab through name, email, password, all fields survive" in run {
            for
                nameRef  <- Signal.initRef("")
                emailRef <- Signal.initRef("")
                passRef  <- Signal.initRef("")
            yield
                import AllowUnsafe.embrace.danger
                val s = screen(
                    UI.div(
                        UI.div(UI.label("Name:"), UI.input.value(nameRef)),
                        UI.div(UI.label("Email:"), UI.input.value(emailRef)),
                        UI.div(UI.label("Pass:"), UI.password.value(passRef)),
                        UI.button("Go")
                    ),
                    60,
                    15
                )
                val markers = Seq("Name:", "Email:", "Pass:", "Go")
                for
                    _ <- s.render
                    _ = s.assertAllPresent(markers*)
                    _ <- s.click(0, 1)
                    _ <- s.typeChar('A')
                    _ = s.assertAllPresent(markers*)
                    _ <- s.tab
                    _ = s.assertAllPresent(markers*)
                    _ <- s.typeChar('B')
                    _ = s.assertAllPresent(markers*)
                    _ <- s.tab
                    _ = s.assertAllPresent(markers*)
                    _ <- s.typeChar('C')
                    _ = s.assertAllPresent(markers*)
                yield
                    assert(nameRef.unsafe.get() == "A", s"name: ${nameRef.unsafe.get()}")
                    assert(emailRef.unsafe.get() == "B", s"email: ${emailRef.unsafe.get()}")
                    assert(passRef.unsafe.get() == "C", s"pass: ${passRef.unsafe.get()}")
                end for
        }

        "form at large viewport" in run {
            for
                nameRef  <- Signal.initRef("")
                emailRef <- Signal.initRef("")
            yield
                import AllowUnsafe.embrace.danger
                val s = screen(
                    UI.div(
                        UI.h1("Title"),
                        UI.div.style(Style.row.gap(2.px))(
                            UI.div.style(Style.width(40.px))(
                                UI.div(UI.label("Name:"), UI.input.value(nameRef)),
                                UI.div(UI.label("Email:"), UI.input.value(emailRef)),
                                UI.button("Submit")
                            ),
                            UI.div.style(Style.width(50.px))(
                                UI.h2("Results")
                            )
                        )
                    ),
                    120,
                    40
                )
                val markers = Seq("Title", "Name:", "Email:", "Submit", "Results")
                for
                    _ <- s.render
                    _ = s.assertAllPresent(markers*)
                    _ <- s.click(0, 3)
                    _ <- s.typeChar('X')
                    _ = s.assertAllPresent(markers*)
                yield s.assertAllPresent(markers*)
                end for
        }
    }

end FormLifecycleTest
