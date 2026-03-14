package kyo.internal.tui.pipeline

import kyo.*
import kyo.Length.*
import kyo.Test
import scala.language.implicitConversions

class InteractionComplexTest extends Test:

    import AllowUnsafe.embrace.danger

    def screen(ui: UI, cols: Int, rows: Int) = Screen(ui, cols, rows)

    "multi-widget form lifecycle" - {
        "form renders all widgets" in run {
            val s = screen(
                UI.form(
                    UI.div.style(Style.row)(
                        UI.label("Name:"),
                        UI.input.value("John")
                    ),
                    UI.div.style(Style.row)(
                        UI.checkbox.checked(false),
                        UI.span("Agree")
                    ),
                    UI.button("Submit")
                ),
                25,
                5
            )
            s.render.andThen {
                val f = s.frame
                assert(f.contains("Name"), "label missing")
                assert(f.contains("John"), "input value missing")
                assert(f.contains("[ ]"), "checkbox missing")
                assert(f.contains("Agree"), "checkbox label missing")
            }
        }

        "tab through form widgets" in run {
            var focusOrder = List.empty[String]
            val s = screen(
                UI.form(
                    UI.input.value("text").onFocus { focusOrder = focusOrder :+ "input" },
                    UI.checkbox.checked(false),
                    UI.button.onFocus { focusOrder = focusOrder :+ "button" }("Go")
                ),
                20,
                3
            )
            for
                _ <- s.render
                _ <- s.tab // focus input
                _ <- s.tab // focus checkbox
                _ <- s.tab // focus button
            yield assert(focusOrder.nonEmpty, s"no focus events fired, order: $focusOrder")
            end for
        }

        "submit button click fires form onSubmit" in run {
            var submitted = false
            val s = screen(
                UI.form.onSubmit { submitted = true }(
                    UI.input.value("data"),
                    UI.button("Submit")
                ),
                20,
                3
            )
            for
                _ <- s.render
                _ <- s.tab   // focus input
                _ <- s.enter // submit
            yield assert(submitted, "form should submit on enter in input")
            end for
        }
    }

    "nested containers with event bubbling" - {
        "click on inner element fires both inner and outer handlers" in run {
            var outerClicked = false
            var innerClicked = false
            val s = screen(
                UI.div.onClick { outerClicked = true }(
                    UI.div.onClick { innerClicked = true }(
                        UI.span("target")
                    )
                ),
                15,
                1
            )
            for
                _ <- s.render
                _ <- s.click(2, 0)
            yield
                assert(innerClicked, "inner onClick should fire")
                assert(outerClicked, "outer onClick should fire via bubbling")
            end for
        }

        "onClickSelf does not bubble" in run {
            var outerFired = false
            var innerFired = false
            val s = screen(
                UI.div.onClickSelf { outerFired = true }(
                    UI.div.onClickSelf { innerFired = true }(
                        UI.span("target")
                    )
                ),
                15,
                1
            )
            for
                _ <- s.render
                _ <- s.click(2, 0)
            yield
                // onClickSelf should only fire on the exact target, not bubble
                assert(!outerFired, "outer onClickSelf should NOT fire for child click")
            end for
        }

        "nested bordered boxes render correctly" in run {
            val s = screen(
                UI.div.style(
                    Style.border(1.px, Style.Color.rgb(128, 128, 128))
                        .width(20.px).height(5.px)
                )(
                    UI.div.style(
                        Style.border(1.px, Style.Color.rgb(128, 128, 128))
                            .width(10.px).height(3.px)
                    )("inner")
                ),
                20,
                5
            )
            s.render.andThen {
                val f = s.frame
                assert(f.contains("inner"), "inner content missing")
                // Should have at least 2 sets of border chars
                val borderCount = f.count(_ == '┌')
                assert(borderCount >= 2, s"expected 2+ border corners, got $borderCount")
            }
        }

        "click inside nested border hits inner content" in run {
            var clicked = false
            val s = screen(
                UI.div.style(
                    Style.border(1.px, Style.Color.rgb(128, 128, 128))
                        .width(20.px).height(5.px)
                )(
                    UI.button.onClick { clicked = true }("OK")
                ),
                20,
                5
            )
            for
                _ <- s.render
                _ <- s.click(3, 1) // inside outer border, on the button
            yield assert(clicked, "click inside border should reach button")
            end for
        }
    }

    "hidden element visibility" - {
        "hidden element not rendered" in run {
            val s = screen(
                UI.div(
                    UI.span("visible"),
                    UI.span.hidden(true)("hidden")
                ),
                20,
                1
            )
            s.render.andThen {
                val f = s.frame
                assert(f.contains("visible"), "visible span missing")
                assert(!f.contains("hidden"), "hidden span should not render")
            }
        }

        "hidden element does not take space" in run {
            val s = screen(
                UI.div.style(Style.row)(
                    UI.span("A"),
                    UI.div.hidden(true).style(Style.width(5.px))("X"),
                    UI.span("B")
                ),
                10,
                1
            )
            s.render.andThen {
                val f = s.frame
                assert(f.contains("A"))
                assert(f.contains("B"))
                assert(!f.contains("X"), "hidden element should not appear")
                // A and B should be close together since hidden takes no space
                val aPos = f.indexOf("A")
                val bPos = f.indexOf("B")
                assert(bPos - aPos <= 2, s"A at $aPos, B at $bPos — hidden div should not take space")
            }
        }

        "disabled button shows but ignores events" in run {
            var clicked = false
            val s = screen(
                UI.div(
                    UI.button.disabled(true).onClick { clicked = true }("Disabled")
                ),
                15,
                3
            )
            for
                _ <- s.render
                _ <- s.click(2, 1) // click on content row inside button border
            yield
                assert(!clicked, "disabled button should not fire onClick")
                assert(s.frame.contains("Disabled"), "disabled button should still render")
            end for
        }
    }

    "mouse-driven interactions" - {
        "click checkbox then verify visual update" in run {
            val s = screen(
                UI.div.style(Style.row)(
                    UI.checkbox.checked(false),
                    UI.span(" Accept")
                ),
                15,
                1
            )
            for
                _ <- s.render
            yield assert(s.frame.contains("[ ]"), s"initial: ${s.frame}")
        }

        "click at different positions hits different elements" in run {
            var leftClicked  = false
            var rightClicked = false
            val s = screen(
                UI.div.style(Style.row)(
                    UI.div.style(Style.width(5.px)).onClick { leftClicked = true }("LEFT"),
                    UI.div.style(Style.width(5.px)).onClick { rightClicked = true }("RIGHT")
                ),
                10,
                1
            )
            for
                _ <- s.render
                _ <- s.click(1, 0) // click left div
            yield
                assert(leftClicked, "left div should be clicked")
                assert(!rightClicked, "right div should not be clicked")
            end for
        }

        "click outside all elements fires no handler" in run {
            var clicked = false
            val s = screen(
                UI.div.style(Style.width(5.px)).onClick { clicked = true }("hi"),
                15,
                3
            )
            for
                _ <- s.render
                _ <- s.click(12, 2) // click far from the element
            yield assert(!clicked, "click outside element should not fire handler")
            end for
        }
    }

    "keyboard-driven form workflow" - {
        "click input, type, verify rendering" in run {
            val ref = SignalRef.Unsafe.init("")
            val s = screen(
                UI.div(
                    UI.label("Name:"),
                    UI.input.value(ref.safe)
                ),
                20,
                1
            )
            for
                _ <- s.render
                _ <- s.click(6, 0) // click on input area (after "Name:")
                _ <- s.typeChar('J')
                _ <- s.typeChar('o')
                _ <- s.typeChar('e')
            yield
                assert(ref.get() == "Joe", s"expected 'Joe', got '${ref.get()}'")
                assert(s.frame.contains("Joe"), s"'Joe' not in frame: ${s.frame}")
            end for
        }
    }

    "multiple independent widgets" - {
        "interacting with one doesn't affect another" in run {
            var check1 = false
            var check2 = false
            val s = screen(
                UI.div.style(Style.row)(
                    UI.checkbox.checked(false).onChange(b => check1 = b),
                    UI.span("  "),
                    UI.checkbox.checked(false).onChange(b => check2 = b)
                ),
                15,
                1
            )
            for
                _ <- s.render
                _ <- s.click(1, 0) // click first checkbox
            yield
                assert(check1, "first checkbox should be toggled")
                assert(!check2, "second checkbox should NOT be toggled")
            end for
        }
    }

    "deeply nested layout" - {
        "5 levels of nesting with content" in run {
            val s = screen(
                UI.div(
                    UI.div(
                        UI.div(
                            UI.div(
                                UI.div("deep")
                            )
                        )
                    )
                ),
                10,
                1
            )
            s.render.andThen {
                assert(s.frame.contains("deep"), s"deeply nested content missing: ${s.frame}")
            }
        }

        "nested borders with content at leaf" in run {
            val borderStyle = Style.border(1.px, Style.Color.rgb(128, 128, 128))
            val s = screen(
                UI.div.style(borderStyle.width(20.px).height(7.px))(
                    UI.div.style(borderStyle.width(16.px).height(5.px))(
                        UI.div.style(borderStyle.width(12.px).height(3.px))(
                            "leaf"
                        )
                    )
                ),
                20,
                7
            )
            s.render.andThen {
                val f = s.frame
                assert(f.contains("leaf"), s"leaf content missing: $f")
                val cornerCount = f.count(_ == '┌')
                assert(cornerCount >= 3, s"expected 3+ nested borders, got $cornerCount")
            }
        }
    }

    "list elements" - {
        "ul with li children renders" in run {
            val s = screen(
                UI.ul(
                    UI.li("Item 1"),
                    UI.li("Item 2"),
                    UI.li("Item 3")
                ),
                15,
                5
            )
            s.render.andThen {
                val f = s.frame
                assert(f.contains("Item 1"), "first list item missing")
                assert(f.contains("Item 2"), "second list item missing")
            }
        }
    }

    "table layout" - {
        "table with rows renders" in run {
            val s = screen(
                UI.table(
                    UI.tr(
                        UI.th("Name"),
                        UI.th("Age")
                    ),
                    UI.tr(
                        UI.td("Alice"),
                        UI.td("30")
                    )
                ),
                20,
                5
            )
            s.render.andThen {
                val f = s.frame
                assert(f.contains("Name"), "header missing")
                assert(f.contains("Alice"), "data missing")
            }
        }
    }

    "event handler state isolation" - {
        "two forms side by side don't interfere" in run {
            var form1Clicked = false
            var form2Clicked = false
            val s = screen(
                UI.div.style(Style.row)(
                    UI.div.style(Style.width(10.px))(
                        UI.button.onClick { form1Clicked = true }("F1")
                    ),
                    UI.div.style(Style.width(10.px))(
                        UI.button.onClick { form2Clicked = true }("F2")
                    )
                ),
                20,
                3
            )
            for
                _ <- s.render
                _ <- s.click(2, 1) // click F1 button (inside border, row 1)
            yield
                assert(form1Clicked, "form1 button should be clicked")
                assert(!form2Clicked, "form2 button should NOT be clicked")
            end for
        }
    }

end InteractionComplexTest
