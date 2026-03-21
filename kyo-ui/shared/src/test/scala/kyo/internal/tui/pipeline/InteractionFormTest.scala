package kyo.internal.tui.pipeline

import kyo.*
import kyo.Length.*
import kyo.Test
import scala.language.implicitConversions

class InteractionFormTest extends Test:

    import AllowUnsafe.embrace.danger

    def screen(ui: UI, cols: Int, rows: Int) = Screen(ui, cols, rows)

    "form submission" - {
        "enter in input fires onSubmit" in run {
            var submitted = false
            val s = screen(
                UI.form.onSubmit { submitted = true }(
                    UI.input.value("test")
                ),
                15,
                1
            )
            for
                _ <- s.render
                _ <- s.tab   // focus the input
                _ <- s.enter // submit
            yield assert(submitted, "form onSubmit should have fired on enter")
            end for
        }

        "enter in textarea does not fire onSubmit" in run {
            var submitted = false
            val s = screen(
                UI.form.onSubmit { submitted = true }(
                    UI.textarea.value("text")
                ),
                15,
                3
            )
            for
                _ <- s.render
                _ <- s.tab   // focus the textarea
                _ <- s.enter // should insert newline, not submit
            yield assert(!submitted, "form onSubmit should NOT fire on enter in textarea")
            end for
        }
    }

    "form with multiple inputs" - {
        "tab cycles through form inputs" in run {
            var focused = ""
            val s = screen(
                UI.form(
                    UI.input.value("first").onFocus { focused = "input1" },
                    UI.input.value("second").onFocus { focused = "input2" },
                    UI.button.onFocus { focused = "submit" }("Submit")
                ),
                20,
                3
            )
            for
                _ <- s.render
                _ <- s.tab // → input1
                _ <- s.tab // → input2
                _ <- s.tab // → submit button
            yield assert(focused == "submit", s"expected submit focused, got: $focused")
            end for
        }
    }

    "button renders text" - {
        "button shows its label" in run {
            val s = screen(
                UI.button("Save"),
                10,
                3
            )
            s.render.andThen {
                assert(s.frame.contains("Save"), s"button label missing: ${s.frame}")
            }
        }
    }

    "onClick bubbles from child" - {
        "click on child fires parent onClick" in run {
            var parentClicked = false
            val s = screen(
                UI.div.onClick { parentClicked = true }(
                    UI.span("child text")
                ),
                15,
                1
            )
            for
                _ <- s.render
                _ <- s.click(2, 0)
            yield assert(parentClicked, "parent onClick should fire via bubbling")
            end for
        }
    }

    "button click" - {
        "onClick fires on button" in run {
            var clicked = false
            val s = screen(
                UI.div(
                    UI.button.onClick { clicked = true }("Click")
                ),
                10,
                1
            )
            for
                _ <- s.render
                _ <- s.click(0, 0) // Plain theme: no border, button text at row 0
            yield assert(clicked, "button onClick should have fired")
            end for
        }

        "disabled button ignores click" in run {
            var clicked = false
            val s = screen(
                UI.div(
                    UI.button.disabled(true).onClick { clicked = true }("Click")
                ),
                10,
                1
            )
            for
                _ <- s.render
                _ <- s.click(0, 0) // Plain theme: no border, button text at row 0
            yield assert(!clicked, "disabled button should not fire onClick")
            end for
        }
    }

end InteractionFormTest
