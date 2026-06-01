package kyo

import kyo.Browser.*
import scala.language.implicitConversions

class CheckboxTest extends UITest:

    // ---- Rendering ----

    "type checkbox" in run {
        withUI(UI.div(UI.checkbox.id("c"))) {
            Browser.assertAttribute(Selector.id("c"), "type", "checkbox").andThen(succeed)
        }
    }

    "checked true" in run {
        withUI(UI.div(UI.checkbox.checked(true).id("c"))) {
            Browser.assertChecked(Selector.id("c")).andThen(succeed)
        }
    }

    "checked false" in run {
        withUI(UI.div(UI.checkbox.checked(false).id("c"))) {
            Browser.assertVisible(Selector.id("c")).andThen(succeed)
        }
    }

    "default unchecked" in run {
        withUI(UI.div(UI.checkbox.id("c"))) {
            Browser.assertVisible(Selector.id("c")).andThen(succeed)
        }
    }

    "exists by id" in run {
        withUI(UI.div(UI.checkbox.id("c"))) {
            Browser.assertExists(Selector.id("c")).andThen(succeed)
        }
    }

    // ---- Interaction (pending due to kyo-browser double-click bug) ----

    "check sets checked" in run {

        val app: UI < Async =
            for ref <- Signal.initRef(false)
            yield UI.div(
                ref.map(v => UI.checkbox.checked(v).id("c").onChange(b => ref.set(b))),
                ref.map(v => UI.span(v.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("c"))
                _ <- Browser.assertChecked(Selector.id("c"))
                _ <- Browser.assertText(Selector.id("v"), "true")
            yield succeed
        }
    }

    "uncheck clears checked" in run {

        val app: UI < Async =
            for ref <- Signal.initRef(true)
            yield UI.div(
                ref.map(v => UI.checkbox.checked(v).id("c").onChange(b => ref.set(b))),
                ref.map(v => UI.span(v.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("c"))
                _ <- Browser.assertText(Selector.id("v"), "false")
            yield succeed
        }
    }

    "check fires onChange true" in run {

        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.checkbox.id("c").onChange(v => ref.set(v.toString)),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("c"))
                _ <- Browser.assertText(Selector.id("v"), "true")
            yield succeed
        }
    }

    "onChange updates signal" in run {

        val app: UI < Async =
            for counter <- Signal.initRef(0)
            yield UI.div(
                UI.checkbox.id("c").onChange(_ => counter.getAndUpdate(_ + 1).unit),
                counter.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("c"))
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield succeed
        }
    }

    "disabled check no fire" in run {

        val app: UI < Async =
            for ref <- Signal.initRef(0)
            yield UI.div(
                UI.checkbox.id("c").disabled(true).onChange(_ => ref.getAndUpdate(_ + 1).unit),
                ref.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertDisabled(Selector.id("c"))
                _ <- Browser.assertText(Selector.id("v"), "0")
            yield succeed
        }
    }

    "checked signal true" in run {
        val app: UI < Async =
            for ref <- Signal.initRef(true)
            yield UI.div(ref.map(v => UI.checkbox.checked(v).id("c")))
        withUI(app) {
            Browser.assertChecked(Selector.id("c")).andThen(succeed)
        }
    }

    "check uncheck check cycle" in run {

        val app: UI < Async =
            for ref <- Signal.initRef(0)
            yield UI.div(
                UI.checkbox.id("c").onChange(_ => ref.getAndUpdate(_ + 1).unit),
                ref.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("c"))
                _ <- Browser.assertText(Selector.id("v"), "1")
                _ <- Browser.click(Selector.id("c"))
                _ <- Browser.assertText(Selector.id("v"), "2")
                _ <- Browser.click(Selector.id("c"))
                _ <- Browser.assertText(Selector.id("v"), "3")
            yield succeed
        }
    }

    "uncheck fires onChange false" in run {

        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.checkbox.checked(true).id("c").onChange(v => ref.set(v.toString)),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("c"))
                _ <- Browser.assertText(Selector.id("v"), "false")
            yield succeed
        }
    }

    // ---- Keyboard ----

    "pressKey Enter toggles" in run {
        val app: UI < Async =
            for ref <- Signal.initRef(false)
            yield UI.div(
                UI.checkbox.id("c").onChange(v => ref.set(v)),
                ref.map(v => UI.span(v.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("c"), Key.Enter)
                _ <- Browser.assertText(Selector.id("v"), "true")
            yield succeed
        }
    }

    "pressKey Space toggles" in run {
        val app: UI < Async =
            for ref <- Signal.initRef(false)
            yield UI.div(
                UI.checkbox.id("c").onChange(v => ref.set(v)),
                ref.map(v => UI.span(v.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("c"), Key.Space)
                _ <- Browser.assertText(Selector.id("v"), "true")
            yield succeed
        }
    }

    // ---- Focus ----

    "focus assertFocused" in run {

        withUI(UI.div(UI.checkbox.id("c"))) {
            for
                _ <- Browser.click(Selector.id("c"))
                _ <- Browser.assertVisible(Selector.id("c"))
            yield succeed
        }
    }

    "onFocus fires" in run {

        val app: UI < Async =
            for ref <- Signal.initRef(false)
            yield UI.div(
                UI.checkbox.id("c").onFocus(ref.set(true)),
                ref.map(v => UI.span(v.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("c"))
                _ <- Browser.assertText(Selector.id("v"), "true")
            yield succeed
        }
    }

    "onBlur fires" in run {

        val app: UI < Async =
            for ref <- Signal.initRef(false)
            yield UI.div(
                UI.checkbox.id("c").onBlur(ref.set(true)),
                UI.button("x").id("b"),
                ref.map(v => UI.span(v.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("c"))
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("v"), "true")
            yield succeed
        }
    }

    // ---- Disabled ----

    "disabled signal toggle" in run {

        val app: UI < Async =
            for
                disabled <- Signal.initRef(true)
                counter  <- Signal.initRef(0)
            yield UI.div(
                disabled.map(d => UI.checkbox.id("c").disabled(d).onChange(_ => counter.getAndUpdate(_ + 1).unit)),
                UI.button("Enable").id("en").onClick(disabled.set(false)),
                counter.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertDisabled(Selector.id("c"))
                _ <- Browser.assertText(Selector.id("v"), "0")
                _ <- Browser.click(Selector.id("en"))
                _ <- Browser.assertEnabled(Selector.id("c"))
                _ <- Browser.click(Selector.id("c"))
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield succeed
        }
    }

    "checked signal toggle via button" in run {

        val app: UI < Async =
            for flag <- Signal.initRef(true)
            yield UI.div(
                UI.button("Toggle").id("t").onClick(flag.getAndUpdate(!_).unit),
                flag.map(v => UI.checkbox.checked(v).id("c"))
            )
        withUI(app) {
            for
                _ <- Browser.assertChecked(Selector.id("c"))
                _ <- Browser.click(Selector.id("t"))
                _ <- Browser.assertExists(Selector.id("c"))
            yield succeed
        }
    }

    // ---- Merged from CheckboxRadioTest (checkbox scenarios) ----

    "checkbox check fires onChange true (merged)" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("none")
            yield UI.div(
                UI.checkbox.id("c").onChange(b => ref.set(b.toString)),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("c"))
                _ <- Browser.assertText(Selector.id("v"), "true")
            yield succeed
        }
    }

    "checkbox uncheck fires onChange false (merged)" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("none")
            yield UI.div(
                UI.checkbox.id("c").checked(true).onChange(b => ref.set(b.toString)),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("c"))
                _ <- Browser.assertText(Selector.id("v"), "false")
            yield succeed
        }
    }

    "checkbox toggle updates span (merged)" in run {
        val app: UI < Async =
            for ref <- Signal.initRef(false)
            yield UI.div(
                UI.checkbox.id("c").onChange(b => ref.set(b)),
                ref.map(v => UI.span(v.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("c"))
                _ <- Browser.assertText(Selector.id("v"), "true")
                _ <- Browser.click(Selector.id("c"))
                _ <- Browser.assertText(Selector.id("v"), "false")
            yield succeed
        }
    }

    "three checkboxes independent" in run {
        val app: UI < Async =
            for
                a <- Signal.initRef(false)
                b <- Signal.initRef(false)
                c <- Signal.initRef(false)
            yield UI.div(
                UI.checkbox.id("a").onChange(v => a.set(v)),
                UI.checkbox.id("b").onChange(v => b.set(v)),
                UI.checkbox.id("c").onChange(v => c.set(v)),
                a.map(v => UI.span(s"a:$v").id("va")),
                b.map(v => UI.span(s"b:$v").id("vb")),
                c.map(v => UI.span(s"c:$v").id("vc"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("a"))
                _ <- Browser.assertText(Selector.id("va"), "a:true")
                _ <- Browser.assertText(Selector.id("vb"), "b:false")
                _ <- Browser.assertText(Selector.id("vc"), "c:false")
            yield succeed
        }
    }

    "checkbox disabled (merged)" in run {
        withUI(UI.div(UI.checkbox.id("c").disabled(true))) {
            Browser.assertDisabled(Selector.id("c")).andThen(succeed)
        }
    }

end CheckboxTest
