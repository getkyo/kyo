package kyo.internal.tui.pipeline

import kyo.*
import kyo.Length.*
import kyo.Test
import scala.language.implicitConversions

class VisualFormTest extends Test:

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

    // ==== 8.1 Rendering ====

    "8.1 rendering" - {
        "form with children renders as column container" in run {
            assertRender(
                UI.form(
                    UI.span("first"),
                    UI.span("second")
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

        "form does not add visual decoration" in run {
            assertRender(
                UI.form(
                    UI.span("hello")
                ),
                10,
                1
            )(
                """
                |hello
                """
            )
        }

        "form with label + input + button — all visible in correct order" in run {
            assertRender(
                UI.form(
                    UI.label("Name:"),
                    UI.input.value("Jo"),
                    UI.button("Go")
                ),
                20,
                3
            )(
                """
                |Name:
                |Jo
                |Go
                """
            )
        }
    }

    // ==== 8.2 Submit via Enter in text input ====

    "8.2 submit via enter in text input" - {
        "enter in input fires onSubmit" in run {
            var submitted = false
            val ref       = SignalRef.Unsafe.init("")
            val s = screen(
                UI.form.onSubmit { submitted = true }(
                    UI.input.value(ref.safe)
                ),
                15,
                1
            )
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.typeChar('A')
                _ <- s.enter
            yield assert(submitted, "form onSubmit should fire on Enter in input")
            end for
        }

        "enter in second input also fires onSubmit" in run {
            var submitted = false
            val ref1      = SignalRef.Unsafe.init("")
            val ref2      = SignalRef.Unsafe.init("")
            val s = screen(
                UI.form.onSubmit { submitted = true }(
                    UI.input.value(ref1.safe),
                    UI.input.value(ref2.safe)
                ),
                15,
                2
            )
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.tab // focus second input
                _ <- s.enter
            yield assert(submitted, "form onSubmit should fire on Enter in second input")
            end for
        }

        "onSubmit fires exactly once per Enter" in run {
            var count = 0
            val ref   = SignalRef.Unsafe.init("")
            val s = screen(
                UI.form.onSubmit { count += 1 }(
                    UI.input.value(ref.safe)
                ),
                15,
                1
            )
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.enter
            yield assert(count == 1, s"onSubmit should fire exactly once, got $count")
            end for
        }
    }

    // ==== 8.3 Textarea does NOT submit ====

    "8.3 textarea does not submit" - {
        "enter in textarea inserts newline, does not fire onSubmit" in run {
            var submitted = false
            val ref       = SignalRef.Unsafe.init("hello")
            val s = screen(
                UI.form.onSubmit { submitted = true }(
                    UI.textarea.value(ref.safe)
                ),
                15,
                3
            )
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.enter
            yield assert(!submitted, "form onSubmit should NOT fire on Enter in textarea")
            end for
        }
    }

    // ==== 8.4 No form — no submit ====

    "8.4 no form — no submit" - {
        "enter in input NOT inside form does nothing" in run {
            var anyEffect = false
            val ref       = SignalRef.Unsafe.init("test")
            val s = screen(
                UI.div(
                    UI.input.value(ref.safe).onInput(_ => anyEffect = true)
                ),
                15,
                1
            )
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.enter
            yield
                // The enter key should not cause any text change
                s.assertFrame(
                    """
                    |test
                    """
                )
                // ref should still be "test" — enter doesn't alter text input value
                assert(ref.get() == "test", s"ref should still be 'test', got '${ref.get()}'")
            end for
        }
    }

    // ==== 8.5 Nested forms ====

    "8.5 nested forms" - {
        "enter in inner form input fires inner onSubmit only" in run {
            var innerSubmitted = false
            var outerSubmitted = false
            val innerRef       = SignalRef.Unsafe.init("")
            val s = screen(
                UI.form.onSubmit { outerSubmitted = true }(
                    UI.form.onSubmit { innerSubmitted = true }(
                        UI.input.value(innerRef.safe)
                    )
                ),
                20,
                2
            )
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.enter
            yield
                assert(innerSubmitted, "inner onSubmit should fire")
                assert(!outerSubmitted, "outer onSubmit should NOT fire when entering in inner form's input")
            end for
        }

        "enter in outer form input fires outer onSubmit" in run {
            var innerSubmitted = false
            var outerSubmitted = false
            val innerRef       = SignalRef.Unsafe.init("")
            val outerRef       = SignalRef.Unsafe.init("")
            val s = screen(
                UI.form.onSubmit { outerSubmitted = true }(
                    UI.form.onSubmit { innerSubmitted = true }(
                        UI.input.value(innerRef.safe)
                    ),
                    UI.input.value(outerRef.safe)
                ),
                20,
                3
            )
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.tab // skip inner input, focus outer input
                _ <- s.enter
            yield
                assert(outerSubmitted, "outer onSubmit should fire when entering in outer form's input")
                assert(!innerSubmitted, "inner onSubmit should NOT fire")
            end for
        }

        "inner and outer fire independently" in run {
            var innerCount = 0
            var outerCount = 0
            val innerRef   = SignalRef.Unsafe.init("")
            val outerRef   = SignalRef.Unsafe.init("")
            val s = screen(
                UI.form.onSubmit { outerCount += 1 }(
                    UI.form.onSubmit { innerCount += 1 }(
                        UI.input.value(innerRef.safe)
                    ),
                    UI.input.value(outerRef.safe)
                ),
                20,
                3
            )
            for
                _ <- s.render
                _ <- s.tab   // focus inner input
                _ <- s.enter // inner submit
                _ <- s.tab   // focus outer input
                _ <- s.enter // outer submit
            yield
                assert(innerCount == 1, s"inner should fire once, got $innerCount")
                assert(outerCount == 1, s"outer should fire once, got $outerCount")
            end for
        }
    }

    // ==== 8.6 Field values at submit time ====

    "8.6 field values at submit time" - {
        "onSubmit reads refs correctly after typing" in run {
            var nameAtSubmit  = ""
            var emailAtSubmit = ""
            val nameRef       = SignalRef.Unsafe.init("")
            val emailRef      = SignalRef.Unsafe.init("")
            val s = screen(
                UI.form.onSubmit {
                    nameAtSubmit = nameRef.get()
                    emailAtSubmit = emailRef.get()
                }(
                    UI.input.value(nameRef.safe),
                    UI.input.value(emailRef.safe)
                ),
                20,
                2
            )
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.typeChar('J')
                _ <- s.typeChar('o')
                _ <- s.typeChar('h')
                _ <- s.typeChar('n')
                _ <- s.tab
                _ <- s.typeChar('j')
                _ <- s.typeChar('@')
                _ <- s.typeChar('x')
                _ <- s.enter
            yield
                assert(nameAtSubmit == "John", s"name at submit: '$nameAtSubmit'")
                assert(emailAtSubmit == "j@x", s"email at submit: '$emailAtSubmit'")
            end for
        }

        "values are current, not stale" in run {
            var valAtSubmit = ""
            val ref         = SignalRef.Unsafe.init("")
            val s = screen(
                UI.form.onSubmit { valAtSubmit = ref.get() }(
                    UI.input.value(ref.safe)
                ),
                15,
                1
            )
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.typeChar('A')
                _ <- s.typeChar('B')
                _ <- s.typeChar('C')
                _ <- s.enter
            yield assert(valAtSubmit == "ABC", s"value at submit should be 'ABC', got '$valAtSubmit'")
            end for
        }
    }

    // ==== 8.7 Post-submit ====

    "8.7 post-submit" - {
        "onSubmit clears refs — next render shows empty inputs" in run {
            val nameRef = SignalRef.Unsafe.init("")
            val s = screen(
                UI.form.onSubmit {
                    nameRef.set("")
                }(
                    UI.input.value(nameRef.safe)
                ),
                15,
                1
            )
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.typeChar('A')
                _ <- s.typeChar('B')
                _ <- s.enter
            yield
                s.assertFrame(
                    """
                    |
                    """
                )
                assert(nameRef.get() == "", s"ref should be empty after clear, got '${nameRef.get()}'")
            end for
        }

        "placeholder hidden after clear while still focused" in run {
            val ref = SignalRef.Unsafe.init("")
            val s = screen(
                UI.form.onSubmit {
                    ref.set("")
                }(
                    UI.input.value(ref.safe).placeholder("Name")
                ),
                15,
                1
            )
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.typeChar('X')
                _ <- s.enter // submits and clears, but input stays focused
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

        "can submit again after clear" in run {
            var submitCount = 0
            val ref         = SignalRef.Unsafe.init("")
            val s = screen(
                UI.form.onSubmit {
                    submitCount += 1
                    ref.set("")
                }(
                    UI.input.value(ref.safe)
                ),
                15,
                1
            )
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.typeChar('A')
                _ <- s.enter
                _ <- s.typeChar('B')
                _ <- s.enter
            yield assert(submitCount == 2, s"should submit twice, got $submitCount")
            end for
        }
    }

    // ==== 8.8 Form with mixed widgets ====

    "8.8 form with mixed widgets" - {
        "enter in input fires onSubmit, not button onClick" in run {
            var formSubmitted = false
            var buttonClicked = false
            val ref           = SignalRef.Unsafe.init("")
            val s = screen(
                UI.form.onSubmit { formSubmitted = true }(
                    UI.input.value(ref.safe),
                    UI.select.value("a")(
                        UI.option.value("a")("Alpha"),
                        UI.option.value("b")("Beta")
                    ),
                    UI.checkbox.checked(false),
                    UI.button.onClick { buttonClicked = true }("Submit")
                ),
                20,
                6
            )
            for
                _ <- s.render
                _ <- s.tab // focus input
                _ <- s.enter
            yield
                assert(formSubmitted, "form onSubmit should fire on Enter in input")
                assert(!buttonClicked, "button onClick should NOT fire on Enter in input")
            end for
        }

        "click on button fires button onClick AND form onSubmit" in run {
            var formSubmitted = false
            var buttonClicked = false
            val ref           = SignalRef.Unsafe.init("")
            val s = screen(
                UI.form.onSubmit { formSubmitted = true }(
                    UI.input.value(ref.safe),
                    UI.button.onClick { buttonClicked = true }("Click")
                ),
                20,
                2
            )
            for
                _ <- s.render
                // Plain theme: input at row 0, button text at row 1 (no border)
                _ <- s.click(0, 1)
            yield
                assert(buttonClicked, "button onClick should fire on click")
                // Button inside form triggers form submit (like HTML submit button)
                assert(formSubmitted, "form onSubmit should fire when clicking button inside form")
            end for
        }

        "space on focused button fires button onClick AND form onSubmit" in run {
            var formSubmitted = false
            var buttonClicked = false
            val ref           = SignalRef.Unsafe.init("")
            val s = screen(
                UI.form.onSubmit { formSubmitted = true }(
                    UI.input.value(ref.safe),
                    UI.button.onClick { buttonClicked = true }("Go")
                ),
                20,
                2
            )
            for
                _ <- s.render
                _ <- s.tab                    // focus input
                _ <- s.tab                    // focus button
                _ <- s.key(UI.Keyboard.Space) // space on button
            yield
                assert(buttonClicked, "button onClick should fire on space")
                // Button inside form triggers form submit (like HTML submit button)
                assert(formSubmitted, "form onSubmit should fire when pressing space on button inside form")
            end for
        }
    }

    // ==== 8.9 Containment ====

    "8.9 containment" - {
        "form in bounded container — children within bounds" in run {
            assertRender(
                UI.div.style(Style.width(15.px).height(3.px))(
                    UI.form(
                        UI.span("field1"),
                        UI.span("field2"),
                        UI.span("field3")
                    )
                ),
                20,
                3
            )(
                """
                |field1
                |field2
                |field3
                """
            )
        }

        "form with many fields — stacked vertically, no overlap" in run {
            assertRender(
                UI.form(
                    UI.input.value("A"),
                    UI.input.value("B"),
                    UI.input.value("C"),
                    UI.input.value("D")
                ),
                10,
                4
            )(
                """
                |A
                |B
                |C
                |D
                """
            )
        }
    }

    // ==== 8.10 Button click submits form ====

    "8.10 button click submits form" - {
        "click submit button inside form fires onSubmit" in run {
            var submitted = false
            val ref       = SignalRef.Unsafe.init("Jo")
            val s = screen(
                UI.form.onSubmit { submitted = true; () }(
                    UI.input.value(ref.safe),
                    UI.button("Submit")
                ),
                15,
                3
            )
            for
                _ <- s.render
                // Plain theme: input at row 0, button text at row 1
                _ <- s.click(1, 1)
            yield assert(submitted, "clicking Submit button should fire form onSubmit")
            end for
        }
    }

end VisualFormTest
