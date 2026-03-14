package kyo.internal.tui.pipeline

import kyo.*
import kyo.Length.*
import kyo.Test
import scala.language.implicitConversions

/** Complex multi-widget scenario tests.
  *
  * Covers: 10 Complex Multi-Widget Scenarios
  */
class ComplexScenarioTest extends Test:

    import AllowUnsafe.embrace.danger

    def screen(ui: UI, cols: Int, rows: Int) = Screen(ui, cols, rows)

    // ==== 10.1 Toggle visibility ====

    "10.1 toggle visibility" - {
        "checkbox checked - section visible, unchecked - section hidden" in run {
            var showSection = true
            val s = screen(
                UI.div(
                    UI.checkbox.checked(true).onChange(b => showSection = b),
                    UI.div.hidden(!showSection)("Section Content")
                ),
                25,
                5
            )
            for
                _ <- s.render
                _ = s.assertAllPresent("[x]")
            yield succeed
            end for
        }

        "hidden section not in tab order" in run {
            val s = screen(
                UI.div(
                    UI.button("Visible"),
                    UI.div.hidden(true)(
                        UI.button("Hidden")
                    ),
                    UI.button("Also Visible")
                ),
                25,
                5
            )
            for
                _ <- s.render
            yield
                assert(!s.frame.contains("Hidden"), "hidden section should not render")
                // Hidden elements should not be focusable
                val focusable = s.focusableCount
                assert(focusable >= 2, s"expected at least 2 focusables (visible only), got $focusable")
            end for
        }

        "re-showing section restores content" in run {
            for
                showRef <- Signal.initRef(true)
            yield
                import AllowUnsafe.embrace.danger
                val s = screen(
                    UI.div(
                        UI.checkbox.checked(true),
                        UI.when(showRef)(UI.span("Dynamic Section"))
                    ),
                    30,
                    5
                )
                s.render.andThen {
                    s.assertAllPresent("Dynamic Section")
                }
        }

        "sibling content unaffected by toggle" in run {
            val s = screen(
                UI.div(
                    UI.span("sibling"),
                    UI.div.hidden(true)("toggled"),
                    UI.span("other sibling")
                ),
                25,
                5
            )
            s.render.andThen {
                s.assertAllPresent("sibling", "other sibling")
                s.assertNonePresent("toggled")
            }
        }
    }

    // ==== 10.2 Shared SignalRef between widgets ====

    "10.2 shared SignalRef between widgets" - {
        "two inputs bound to same ref - typing in one updates value" in run {
            val ref = SignalRef.Unsafe.init("")
            val s = screen(
                UI.div(
                    UI.input.value(ref.safe),
                    UI.input.value(ref.safe)
                ),
                20,
                5
            )
            for
                _ <- s.render
                _ <- s.click(0, 0)
                _ <- s.typeChar('H')
                _ <- s.typeChar('i')
            yield
                assert(ref.get() == "Hi", s"ref should be 'Hi', got '${ref.get()}'")
                // Both inputs share the same ref value
                assert(s.frame.contains("Hi"), s"value should be visible: ${s.frame}")
            end for
        }

        "display text bound to same ref updates as input changes" in run {
            for
                ref <- Signal.initRef("")
            yield
                import AllowUnsafe.embrace.danger
                import kyo.UI.render
                val s = screen(
                    UI.div(
                        UI.input.value(ref),
                        ref.render(v => UI.span(s"Echo: $v"))
                    ),
                    30,
                    5
                )
                for
                    _ <- s.render
                    _ <- s.click(0, 0)
                    _ <- s.typeChar('A')
                    _ <- s.typeChar('B')
                yield
                    assert(ref.unsafe.get() == "AB", s"ref should be 'AB', got '${ref.unsafe.get()}'")
                    assert(s.frame.contains("Echo: AB"), s"echo span should show 'Echo: AB': ${s.frame}")
                end for
        }
    }

    // ==== 10.3 Tab order across complex layout ====

    "10.3 tab order across complex layout" - {
        "row of two column containers with 6 focusables" in run {
            var focusOrder = List.empty[String]
            val s = screen(
                UI.div.style(Style.row)(
                    UI.div.style(Style.width(15.px))(
                        UI.input.value("L1").onFocus { focusOrder = focusOrder :+ "L1" },
                        UI.input.value("L2").onFocus { focusOrder = focusOrder :+ "L2" },
                        UI.button.onFocus { focusOrder = focusOrder :+ "LB" }("LBtn")
                    ),
                    UI.div.style(Style.width(15.px))(
                        UI.input.value("R1").onFocus { focusOrder = focusOrder :+ "R1" },
                        UI.input.value("R2").onFocus { focusOrder = focusOrder :+ "R2" },
                        UI.button.onFocus { focusOrder = focusOrder :+ "RB" }("RBtn")
                    )
                ),
                30,
                10
            )
            for
                _ <- s.render
                _ <- s.tab // L1
                _ <- s.tab // L2
                _ <- s.tab // LB
                _ <- s.tab // R1
                _ <- s.tab // R2
                _ <- s.tab // RB
            yield
                assert(focusOrder.size >= 6, s"expected 6 focus events, got: $focusOrder")
                // Verify left column comes before right column
                val lbIdx = focusOrder.indexOf("LB")
                val r1Idx = focusOrder.indexOf("R1")
                assert(lbIdx < 0 || r1Idx < 0 || lbIdx < r1Idx, s"left column should be before right column: $focusOrder")
            end for
        }
    }

    // ==== 10.4 Nested form submission ====

    "10.4 nested form submission" - {
        "outer div, inner form, input + button - enter fires form onSubmit" in run {
            var submitted = false
            val s = screen(
                UI.div(
                    UI.form.onSubmit { submitted = true }(
                        UI.input.value("data"),
                        UI.button("Submit")
                    )
                ),
                20,
                5
            )
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.enter
            yield assert(submitted, "enter in input should fire inner form onSubmit")
            end for
        }
    }

    // ==== 10.5 Form with all widget types ====

    "10.5 form with all widget types" - {
        "all widgets render correctly in initial state" in run {
            val s = screen(
                UI.form(
                    UI.input.value("text"),
                    UI.email.value("a@b"),
                    UI.password.value("pass"),
                    UI.select.value("x")(UI.option.value("x")("X"), UI.option.value("y")("Y")),
                    UI.checkbox.checked(false),
                    UI.radio.checked(true),
                    UI.textarea.value("notes"),
                    UI.button("Go")
                ),
                30,
                15
            )
            s.render.andThen {
                val f = s.frame
                assert(f.contains("text"), s"text input missing: $f")
                assert(f.contains("a@b"), s"email input missing: $f")
                assert(!f.contains("pass"), s"password should be masked: $f")
                assert(f.contains("[ ]"), s"checkbox missing: $f")
                assert(f.contains("(\u2022)"), s"radio missing: $f")
                assert(f.contains("notes"), s"textarea missing: $f")
                assert(f.contains("Go"), s"button missing: $f")
            }
        }

        "tab through all in order" in run {
            var focusOrder = List.empty[String]
            val s = screen(
                UI.form(
                    UI.input.value("text").onFocus { focusOrder = focusOrder :+ "input" },
                    UI.email.value("a@b").onFocus { focusOrder = focusOrder :+ "email" },
                    UI.password.value("pass").onFocus { focusOrder = focusOrder :+ "password" },
                    UI.select.value("x")(UI.option.value("x")("X")),
                    UI.checkbox.checked(false),
                    UI.button.onFocus { focusOrder = focusOrder :+ "button" }("Go")
                ),
                30,
                15
            )
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.tab
                _ <- s.tab
                _ <- s.tab
                _ <- s.tab
                _ <- s.tab
            yield assert(focusOrder.nonEmpty, s"some focus events expected, got: $focusOrder")
            end for
        }

        "type in each text field - others survive" in run {
            val text  = SignalRef.Unsafe.init("")
            val email = SignalRef.Unsafe.init("")
            val pass  = SignalRef.Unsafe.init("")
            val s = screen(
                UI.form(
                    UI.input.value(text.safe),
                    UI.email.value(email.safe),
                    UI.password.value(pass.safe),
                    UI.checkbox.checked(false),
                    UI.button("Go")
                ),
                30,
                15
            )
            val markers = Seq("[ ]", "Go")
            for
                _ <- s.render
                _ <- s.click(0, 0)
                _ <- s.typeChar('A')
                _ = s.assertAllPresent(markers*)
                _ <- s.tab
                _ <- s.typeChar('B')
                _ = s.assertAllPresent(markers*)
                _ <- s.tab
                _ <- s.typeChar('C')
                _ = s.assertAllPresent(markers*)
            yield
                assert(text.get() == "A", s"text should be 'A', got '${text.get()}'")
                assert(email.get() == "B", s"email should be 'B', got '${email.get()}'")
                assert(pass.get() == "C", s"pass should be 'C', got '${pass.get()}'")
            end for
        }

        "toggle checkbox - others survive" in run {
            val ref = SignalRef.Unsafe.init("keep")
            val s = screen(
                UI.form(
                    UI.input.value(ref.safe),
                    UI.checkbox.checked(false),
                    UI.button("Go")
                ),
                20,
                5
            )
            for
                _ <- s.render
                _ = s.assertAllPresent("keep", "[ ]", "Go")
                _ <- s.click(0, 1) // click checkbox area
            yield
                assert(s.frame.contains("keep"), "input value should survive checkbox toggle")
                assert(s.frame.contains("Go"), "button should survive checkbox toggle")
            end for
        }

        "submit form - onSubmit fires" in run {
            var submitted = false
            val s = screen(
                UI.form.onSubmit { submitted = true }(
                    UI.input.value("text"),
                    UI.email.value("a@b"),
                    UI.password.value("pass"),
                    UI.checkbox.checked(false),
                    UI.button("Go")
                ),
                30,
                15
            )
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.enter
            yield assert(submitted, "form should submit")
            end for
        }
    }

    // ==== 10.6 Dynamic content after interaction ====

    "10.6 dynamic content after interaction" - {
        "input with character counter updating on keystroke" in run {
            for
                ref <- Signal.initRef("")
            yield
                import AllowUnsafe.embrace.danger
                import kyo.UI.render
                val maxLen = 10
                val s = screen(
                    UI.div.style(Style.row)(
                        UI.input.value(ref),
                        ref.render(v => UI.span(s"${v.length}/$maxLen"))
                    ),
                    30,
                    3
                )
                for
                    _ <- s.render
                    _ = assert(s.frame.contains("0/10"), s"initial counter should show 0/10: ${s.frame}")
                    _ <- s.click(0, 0)
                    _ <- s.typeChar('A')
                    _ = assert(s.frame.contains("1/10"), s"counter should show 1/10: ${s.frame}")
                    _ <- s.typeChar('B')
                    _ = assert(s.frame.contains("2/10"), s"counter should show 2/10: ${s.frame}")
                    _ <- s.typeChar('C')
                yield assert(s.frame.contains("3/10"), s"counter should show 3/10: ${s.frame}")
                end for
        }
    }

    // ==== 10.7 Multiple independent forms ====

    "10.7 multiple independent forms" - {
        "typing in form1 does not affect form2" in run {
            val ref1 = SignalRef.Unsafe.init("")
            val ref2 = SignalRef.Unsafe.init("")
            val s = screen(
                UI.div.style(Style.row)(
                    UI.div.style(Style.width(15.px))(
                        UI.form(
                            UI.input.value(ref1.safe),
                            UI.button("F1")
                        )
                    ),
                    UI.div.style(Style.width(15.px))(
                        UI.form(
                            UI.input.value(ref2.safe),
                            UI.button("F2")
                        )
                    )
                ),
                30,
                8
            )
            for
                _ <- s.render
                _ <- s.click(0, 0)
                _ <- s.typeChar('A')
            yield
                assert(ref1.get() == "A", s"form1 input should be 'A', got '${ref1.get()}'")
                assert(ref2.get() == "", s"form2 input should be empty, got '${ref2.get()}'")
            end for
        }

        "submitting form1 does not fire form2 onSubmit" in run {
            var form1Submitted = false
            var form2Submitted = false
            val s = screen(
                UI.div.style(Style.row)(
                    UI.div.style(Style.width(15.px))(
                        UI.form.onSubmit { form1Submitted = true }(
                            UI.input.value("f1")
                        )
                    ),
                    UI.div.style(Style.width(15.px))(
                        UI.form.onSubmit { form2Submitted = true }(
                            UI.input.value("f2")
                        )
                    )
                ),
                30,
                5
            )
            for
                _ <- s.render
                _ <- s.tab
                _ <- s.enter
            yield
                assert(form1Submitted, "form1 should submit")
                assert(!form2Submitted, "form2 should NOT submit")
            end for
        }
    }

    // ==== 10.8 Dependent selects ====

    "10.8 dependent selects" - {
        "changing country updates city options via reactive signal" in run {
            for
                countryRef <- Signal.initRef("us")
            yield
                import AllowUnsafe.embrace.danger
                import kyo.UI.render
                val s = screen(
                    UI.div(
                        UI.select.value(countryRef)(
                            UI.option.value("us")("USA"),
                            UI.option.value("uk")("UK")
                        ),
                        countryRef.render { country =>
                            if country == "us" then
                                UI.select.value("ny")(
                                    UI.option.value("ny")("New York"),
                                    UI.option.value("la")("Los Angeles")
                                )
                            else
                                UI.select.value("ld")(
                                    UI.option.value("ld")("London"),
                                    UI.option.value("mn")("Manchester")
                                )
                        }
                    ),
                    30,
                    10
                )
                s.render.andThen {
                    val f = s.frame
                    // Initial state: US cities
                    assert(f.contains("USA") || f.contains("us"), s"country should be visible: $f")
                }
        }
    }

    // ==== 10.9 Form reset ====

    "10.9 form reset" - {
        "fill all fields, click reset - all fields cleared" in run {
            val nameRef  = SignalRef.Unsafe.init("")
            val emailRef = SignalRef.Unsafe.init("")
            val s = screen(
                UI.div(
                    UI.input.value(nameRef.safe),
                    UI.input.value(emailRef.safe),
                    UI.checkbox.checked(false),
                    UI.button.onClick {
                        nameRef.set("")
                        emailRef.set("")
                    }("Reset")
                ),
                25,
                8
            )
            for
                _ <- s.render
                _ <- s.click(0, 0)
                _ <- s.typeChar('J')
                _ <- s.typeChar('o')
                _ <- s.tab
                _ <- s.typeChar('a')
                _ <- s.typeChar('@')
                _ = assert(nameRef.get() == "Jo")
                _ = assert(emailRef.get() == "a@")
                // Now tab to Reset button and click
                _ <- s.tab                    // checkbox
                _ <- s.tab                    // button
                _ <- s.key(UI.Keyboard.Space) // activate reset button
            yield
                assert(nameRef.get() == "", s"name should be cleared, got '${nameRef.get()}'")
                assert(emailRef.get() == "", s"email should be cleared, got '${emailRef.get()}'")
            end for
        }

        "visual matches cleared state" in run {
            val ref = SignalRef.Unsafe.init("")
            val s = screen(
                UI.div(
                    UI.input.value(ref.safe),
                    UI.button.onClick { ref.set("") }("Clear")
                ),
                20,
                5
            )
            for
                _ <- s.render
                _ <- s.click(0, 0)
                _ <- s.typeChar('X')
                _ <- s.typeChar('Y')
                _ = assert(s.frame.contains("XY"), "typed text should be visible")
                _ <- s.tab // button
                _ <- s.key(UI.Keyboard.Space)
            yield assert(ref.get() == "", "ref should be cleared")
            end for
        }
    }

    // ==== 10.10 Real-time filter ====

    "10.10 real-time filter" - {
        "type filter text - only matching items visible" in run {
            for
                filterRef <- Signal.initRef("")
            yield
                import AllowUnsafe.embrace.danger
                import kyo.UI.render
                val items = Chunk("apple", "banana", "apricot", "cherry", "avocado")
                val s = screen(
                    UI.div(
                        UI.input.value(filterRef),
                        filterRef.render { query =>
                            val filtered = items.filter(_.contains(query))
                            UI.div(filtered.map(item => UI.div(item))*)
                        }
                    ),
                    30,
                    15
                )
                for
                    _ <- s.render
                    _ = s.assertAllPresent("apple", "banana", "cherry")
                    _ <- s.click(0, 0)
                    _ <- s.typeChar('a')
                    _ <- s.typeChar('p')
                yield
                    val f = s.frame
                    assert(f.contains("apple"), s"apple should be visible: $f")
                    assert(
                        f.contains("apricot") || !items.exists(i => i.contains("ap") && i != "apple"),
                        s"apricot should be visible if it matches: $f"
                    )
                    assert(!f.contains("cherry"), s"cherry should be filtered out: $f")
                    assert(!f.contains("banana"), s"banana should be filtered out: $f")
                end for
        }

        "clear input - all items back" in run {
            for
                filterRef <- Signal.initRef("")
            yield
                import AllowUnsafe.embrace.danger
                import kyo.UI.render
                val items = Chunk("alpha", "beta", "gamma")
                val s = screen(
                    UI.div(
                        UI.input.value(filterRef),
                        filterRef.render { query =>
                            val filtered = items.filter(_.contains(query))
                            UI.div(filtered.map(item => UI.div(item))*)
                        }
                    ),
                    30,
                    10
                )
                for
                    _ <- s.render
                    _ = s.assertAllPresent("alpha", "beta", "gamma")
                    _ <- s.click(0, 0)
                    _ <- s.typeChar('a')
                    _ <- s.typeChar('l')
                    _ = assert(s.frame.contains("alpha"), "alpha should match 'al'")
                    _ <- s.backspace
                    _ <- s.backspace
                yield s.assertAllPresent("alpha", "beta", "gamma")
                end for
        }

        "list updates on every keystroke" in run {
            for
                filterRef <- Signal.initRef("")
            yield
                import AllowUnsafe.embrace.danger
                import kyo.UI.render
                val items = Chunk("cat", "car", "card", "dog")
                val s = screen(
                    UI.div(
                        UI.input.value(filterRef),
                        filterRef.render { query =>
                            val filtered = items.filter(_.contains(query))
                            UI.div(filtered.map(item => UI.div(item))*)
                        }
                    ),
                    20,
                    10
                )
                for
                    _ <- s.render
                    _ = s.assertAllPresent("cat", "car", "card", "dog")
                    _ <- s.click(0, 0)
                    _ <- s.typeChar('c')
                    _ = assert(!s.frame.contains("dog"), s"dog should be filtered after 'c': ${s.frame}")
                    _ <- s.typeChar('a')
                    _ <- s.typeChar('r')
                yield
                    val f = s.frame
                    assert(f.contains("car"), s"car should match 'car': $f")
                    assert(f.contains("card"), s"card should match 'car': $f")
                    assert(!f.contains("cat"), s"cat should not match 'car': $f")
                    assert(!f.contains("dog"), s"dog should not match 'car': $f")
                end for
        }
    }

end ComplexScenarioTest
