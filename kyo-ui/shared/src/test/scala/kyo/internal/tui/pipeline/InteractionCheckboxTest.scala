package kyo.internal.tui.pipeline

import kyo.*
import kyo.Length.*
import kyo.Test
import scala.language.implicitConversions

class InteractionCheckboxTest extends Test:

    import AllowUnsafe.embrace.danger

    "checkbox" - {
        "unchecked renders [ ]" in run {
            val s = Screen(UI.checkbox.checked(false), 5, 1)
            s.render.andThen(assert(s.frame.contains("[ ]")))
        }

        "checked renders [x]" in run {
            val s = Screen(UI.checkbox.checked(true), 5, 1)
            s.render.andThen(assert(s.frame.contains("[x]")))
        }

        "click toggles from unchecked to checked" in run {
            var toggled = false
            val s = Screen(
                UI.checkbox.checked(false).onChange(b => toggled = b),
                5,
                1
            )
            for
                _ <- s.render
                _ <- s.click(1, 0)
            yield
                assert(toggled)
                assert(s.frame.contains("[x]"))
            end for
        }

        "click toggles from checked to unchecked" in run {
            var toggled = true
            val s = Screen(
                UI.checkbox.checked(true).onChange(b => toggled = b),
                5,
                1
            )
            for
                _ <- s.render
                _ <- s.click(1, 0)
            yield
                assert(!toggled)
                assert(s.frame.contains("[ ]"))
            end for
        }
    }

    "radio" - {
        "unchecked renders ( )" in run {
            val s = Screen(UI.radio.checked(false), 5, 1)
            s.render.andThen(assert(s.frame.contains("( )")))
        }

        "checked renders (•)" in run {
            val s = Screen(UI.radio.checked(true), 5, 1)
            s.render.andThen(assert(s.frame.contains("(•)")))
        }
    }

end InteractionCheckboxTest
