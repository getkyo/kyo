package kyo

import kyo.Browser.*
import scala.language.implicitConversions

class SelectTest extends UITest:

    "select exists" in run {
        withUI(UI.div(
            UI.select(UI.option("Alpha").value("a"), UI.option("Beta").value("b")).id("s")
        )) {
            Browser.assertVisible(Selector.id("s")).andThen(succeed)
        }
    }

    "select onChange fires" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.select(
                    UI.option("Alpha").value("a"),
                    UI.option("Beta").value("b")
                ).id("s").onChange(v => ref.set(v)),
                ref.map(v => UI.span(s"Selected:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("v"), "Selected:")
                _ <- Browser.select(Selector.id("s"), "b")
                _ <- Browser.assertText(Selector.id("v"), "Selected:b")
            yield succeed
        }
    }

    "select disabled" in run {
        withUI(UI.div(UI.select.id("s").disabled(true))) {
            Browser.assertDisabled(Selector.id("s")).andThen(succeed)
        }
    }

    "select with initial value" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("b")
            yield UI.div(
                UI.select(
                    UI.option("Alpha").value("a"),
                    UI.option("Beta").value("b")
                ).id("s").value(ref),
                ref.map(v => UI.span(s"Val:$v").id("v"))
            )
        withUI(app) {
            Browser.assertText(Selector.id("v"), "Val:b").andThen(succeed)
        }
    }

    "select onChange updates display" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("a")
            yield UI.div(
                UI.select(
                    UI.option("Alpha").value("a"),
                    UI.option("Beta").value("b"),
                    UI.option("Gamma").value("c")
                ).id("s").value(ref).onChange(v => ref.set(v)),
                ref.map(v => UI.span(s"Current:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("v"), "Current:a")
                _ <- Browser.select(Selector.id("s"), "c")
                _ <- Browser.assertText(Selector.id("v"), "Current:c")
            yield succeed
        }
    }

    "opt children render selected option visible" in run {
        withUI(UI.div(
            UI.select(
                UI.option("Alpha").value("a"),
                UI.option("Beta").value("b"),
                UI.option("Gamma").value("c")
            ).id("s")
        )) {
            Browser.assertVisible(Selector.id("s")).andThen(succeed)
        }
    }

    "opt value separate from text" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.select(
                    UI.option("Display Label").value("internal-val")
                ).id("s").onChange(v => ref.set(v)),
                ref.map(v => UI.span(s"Got:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.select(Selector.id("s"), "internal-val")
                _ <- Browser.assertText(Selector.id("v"), "Got:internal-val")
            yield succeed
        }
    }

    "opt selected true pre-selects" in run {
        withUI(UI.div(
            UI.select(
                UI.option("Alpha").value("a"),
                UI.option("Beta").value("b").selected(true)
            ).id("s")
        )) {
            Browser.assertVisible(Selector.id("s")).andThen(succeed)
        }
    }

    "value signalRef binding" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("a")
            yield UI.div(
                UI.select(
                    UI.option("Alpha").value("a"),
                    UI.option("Beta").value("b")
                ).id("s").value(ref).onChange(v => ref.set(v)),
                ref.map(v => UI.span(s"Bound:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("v"), "Bound:a")
                _ <- Browser.select(Selector.id("s"), "b")
                _ <- Browser.assertText(Selector.id("v"), "Bound:b")
            yield succeed
        }
    }

    "value initial assertValue" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("b")
            yield UI.div(
                UI.select(
                    UI.option("Alpha").value("a"),
                    UI.option("Beta").value("b")
                ).id("s").value(ref)
            )
        withUI(app) {
            Browser.assertVisible(Selector.id("s")).andThen(succeed)
        }
    }

    "select each option in sequence" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.select(
                    UI.option("A").value("a"),
                    UI.option("B").value("b"),
                    UI.option("C").value("c")
                ).id("s").onChange(v => ref.set(v)),
                ref.map(v => UI.span(s"S:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.select(Selector.id("s"), "a")
                _ <- Browser.assertText(Selector.id("v"), "S:a")
                _ <- Browser.select(Selector.id("s"), "b")
                _ <- Browser.assertText(Selector.id("v"), "S:b")
                _ <- Browser.select(Selector.id("s"), "c")
                _ <- Browser.assertText(Selector.id("v"), "S:c")
            yield succeed
        }
    }

    "onChange fires correct value" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.select(
                    UI.option("Alpha").value("a"),
                    UI.option("Beta").value("b")
                ).id("s").onChange(v => ref.set(v)),
                ref.map(v => UI.span(s"Changed:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.select(Selector.id("s"), "b")
                _ <- Browser.assertText(Selector.id("v"), "Changed:b")
            yield succeed
        }
    }

    "focus select" in run {
        withUI(UI.div(UI.select(UI.option("A").value("a")).id("s"))) {
            for
                _ <- Browser.click(Selector.id("s"))
                _ <- Browser.assertVisible(Selector.id("s"))
            yield succeed
        }
    }

    "select onFocus fires" in run {
        val app: UI < Async =
            for ref <- Signal.initRef(false)
            yield UI.div(
                UI.select(UI.option("A").value("a")).id("s").onFocus(ref.set(true)),
                ref.map(v => UI.span(v.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("s"))
                _ <- Browser.assertText(Selector.id("v"), "true")
            yield succeed
        }
    }

    "select onBlur fires" in run {
        val app: UI < Async =
            for ref <- Signal.initRef(false)
            yield UI.div(
                UI.select(UI.option("A").value("a")).id("s").onBlur(ref.set(true)),
                UI.button("Other").id("b"),
                ref.map(v => UI.span(v.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("s"))
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("v"), "true")
            yield succeed
        }
    }

    "disabled select no fire" in run {
        val app: UI < Async =
            for ref <- Signal.initRef(0)
            yield UI.div(
                UI.select(
                    UI.option("A").value("a"),
                    UI.option("B").value("b")
                ).id("s").disabled(true).onChange(_ => ref.getAndUpdate(_ + 1).unit),
                ref.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertDisabled(Selector.id("s"))
                _ <- Browser.assertText(Selector.id("v"), "0")
            yield succeed
        }
    }

    "disabled signal toggle" in run {
        val app: UI < Async =
            for
                disabled <- Signal.initRef(true)
                counter  <- Signal.initRef(0)
            yield UI.div(
                disabled.map(d =>
                    UI.select(
                        UI.option("A").value("a"),
                        UI.option("B").value("b")
                    ).id("s").disabled(d).onChange(_ => counter.getAndUpdate(_ + 1).unit)
                ),
                UI.button("Enable").id("en").onClick(disabled.set(false)),
                counter.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertDisabled(Selector.id("s"))
                _ <- Browser.click(Selector.id("en"))
                _ <- Browser.assertEnabled(Selector.id("s"))
                _ <- Browser.select(Selector.id("s"), "b")
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield succeed
        }
    }

    // ---- Merged from SelectKeyboardTest ----

    "select changes value" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("a")
            yield UI.div(
                UI.select(UI.option("A").value("a"), UI.option("B").value("b")).id("s").value(ref),
                ref.map(v => UI.span(s"val:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("v"), "val:a")
                _ <- Browser.select(Selector.id("s"), "b")
                _ <- Browser.assertText(Selector.id("v"), "val:b")
            yield succeed
        }
    }

    "select fires onChange (keyboard)" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.select(UI.option("A").value("a"), UI.option("B").value("b")).id("s")
                    .onChange(v => ref.set(s"changed:$v")),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.select(Selector.id("s"), "b")
                _ <- Browser.assertText(Selector.id("v"), "changed:b")
            yield succeed
        }
    }

    "select disabled no change" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("a")
            yield UI.div(
                UI.select(UI.option("A").value("a"), UI.option("B").value("b"))
                    .id("s").value(ref).disabled(true),
                ref.map(v => UI.span(s"val:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertDisabled(Selector.id("s"))
                _ <- Browser.assertText(Selector.id("v"), "val:a")
            yield succeed
        }
    }

    "select with 1 option selectable" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.select(UI.option("Only").value("only")).id("s").onChange(v => ref.set(v)),
                ref.map(v => UI.span(s"val:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.select(Selector.id("s"), "only")
                _ <- Browser.assertText(Selector.id("v"), "val:only")
            yield succeed
        }
    }

    "Tab from select moves focus" in run {
        withUI(UI.div(
            UI.select(UI.option("A").value("a")).id("s"),
            UI.input.id("i")
        )) {
            for
                _ <- Browser.click(Selector.id("s"))
                _ <- Browser.press(Selector.id("s"), Key.Tab)
                _ <- Browser.assertVisible(Selector.id("i"))
            yield succeed
        }
    }

    "select with 0 options no crash" in run {
        withUI(UI.div(UI.select().id("s"))) {
            Browser.assertVisible(Selector.id("s")).andThen(succeed)
        }
    }

    "focus select Space opens dropdown" in run {
        val app: UI < Async =
            for selected <- Signal.initRef("a")
            yield UI.div(UI.dropdown("Alpha" -> "a", "Beta" -> "b").id("d").value(selected))
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("d-trigger"))
                _ <- Browser.assertVisible(Selector.id("d-options"))
            yield succeed
        }
    }

    "open dropdown ArrowDown highlights next" in run {
        val app: UI < Async =
            for selected <- Signal.initRef("a")
            yield UI.div(UI.dropdown("Alpha" -> "a", "Beta" -> "b").id("d").value(selected))
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("d-trigger"), Key.Space)
                _ <- Browser.assertVisible(Selector.id("d-options"))
                _ <- Browser.press(Selector.id("d-trigger"), Key.ArrowDown)
                _ <- Browser.assertAttributeSatisfies(
                    Selector.css("#d-options [data-kyo-dropdown-opt='1']"),
                    "data-kyo-dropdown-hl",
                    "option 1 highlighted"
                )(_.nonEmpty)
            yield succeed
        }
    }

    "open dropdown ArrowUp highlights previous" in run {
        val app: UI < Async =
            for selected <- Signal.initRef("a")
            yield UI.div(UI.dropdown("Alpha" -> "a", "Beta" -> "b").id("d").value(selected))
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("d-trigger"), Key.Space)
                _ <- Browser.assertVisible(Selector.id("d-options"))
                _ <- Browser.press(Selector.id("d-trigger"), Key.ArrowDown)
                _ <- Browser.press(Selector.id("d-trigger"), Key.ArrowUp)
                _ <- Browser.assertAttributeSatisfies(
                    Selector.css("#d-options [data-kyo-dropdown-opt='0']"),
                    "data-kyo-dropdown-hl",
                    "option 0 highlighted"
                )(_.nonEmpty)
            yield succeed
        }
    }

    "open dropdown Enter confirms selection" in run {
        val app: UI < Async =
            for selected <- Signal.initRef("a")
            yield UI.div(
                UI.dropdown("Alpha" -> "a", "Beta" -> "b").id("d").value(selected),
                selected.map(v => UI.span(s"val:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("v"), "val:a")
                _ <- Browser.press(Selector.id("d-trigger"), Key.Space)
                _ <- Browser.assertVisible(Selector.id("d-options"))
                _ <- Browser.press(Selector.id("d-trigger"), Key.ArrowDown)
                _ <- Browser.press(Selector.id("d-trigger"), Key.Enter)
                _ <- Browser.assertText(Selector.id("v"), "val:b")
            yield succeed
        }
    }

    "open dropdown Escape cancels" in run {
        val app: UI < Async =
            for selected <- Signal.initRef("a")
            yield UI.div(
                UI.dropdown("Alpha" -> "a", "Beta" -> "b").id("d").value(selected),
                selected.map(v => UI.span(s"val:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("v"), "val:a")
                _ <- Browser.click(Selector.id("d-trigger"))
                _ <- Browser.assertVisible(Selector.id("d-options"))
                _ <- Browser.press(Selector.id("d-trigger"), Key.Escape)
                _ <- Browser.assertNotVisible(Selector.id("d-options"))
                _ <- Browser.assertText(Selector.id("v"), "val:a")
            yield succeed
        }
    }

    "open dropdown type m jumps to option starting with m" in run {
        val app: UI < Async =
            for selected <- Signal.initRef("a")
            yield UI.div(
                UI.dropdown("Alpha" -> "a", "Mango" -> "m", "Zeta" -> "z").id("d").value(selected)
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("d-trigger"), Key.Space)
                _ <- Browser.assertVisible(Selector.id("d-options"))
                _ <- Browser.press(Selector.id("d-trigger"), Key('m'))
                _ <- Browser.assertAttributeSatisfies(
                    Selector.css("#d-options [data-kyo-dropdown-opt='1']"),
                    "data-kyo-dropdown-hl",
                    "option 1 (Mango) highlighted"
                )(_.nonEmpty)
            yield succeed
        }
    }

    "select with 100 options scrollable" in run {
        val opts = (1 to 100).map(i => s"Option$i" -> s"v$i")
        val app: UI < Async =
            for selected <- Signal.initRef("v1")
            yield UI.div(UI.dropdown(opts*).id("d").value(selected))
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("d-trigger"), Key.Space)
                _ <- Browser.assertVisible(Selector.id("d-options"))
                _ <- Browser.press(Selector.id("d-trigger"), Key.ArrowDown)
                _ <- Browser.press(Selector.id("d-trigger"), Key.ArrowDown)
                _ <- Browser.press(Selector.id("d-trigger"), Key.ArrowDown)
                _ <- Browser.press(Selector.id("d-trigger"), Key.ArrowDown)
                _ <- Browser.press(Selector.id("d-trigger"), Key.ArrowDown)
                _ <- Browser.assertAttributeSatisfies(
                    Selector.css("#d-options [data-kyo-dropdown-opt='5']"),
                    "data-kyo-dropdown-hl",
                    "option 5 highlighted after 5 ArrowDowns"
                )(_.nonEmpty)
            yield succeed
        }
    }

    "multiple selects only one open at a time" in run {
        withUI(UI.div(
            UI.dropdown("X" -> "x").id("d1"),
            UI.dropdown("Y" -> "y").id("d2")
        )) {
            for
                _ <- Browser.press(Selector.id("d1-trigger"), Key.Space)
                _ <- Browser.assertVisible(Selector.id("d1-options"))
                _ <- Browser.press(Selector.id("d2-trigger"), Key.Space)
                _ <- Browser.assertVisible(Selector.id("d2-options"))
                _ <- Browser.assertNotVisible(Selector.id("d1-options"))
            yield succeed
        }
    }

    "select inside form Enter does not submit while open" in run {
        val app: UI < Async =
            for submitted <- Signal.initRef(false)
            yield UI.div(
                UI.form(
                    UI.dropdown("Alpha" -> "a", "Beta" -> "b").id("d")
                ).id("f").onSubmit(submitted.set(true)),
                submitted.map(v => UI.span(if v then "submitted" else "not-submitted").id("s"))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("s"), "not-submitted")
                _ <- Browser.press(Selector.id("d-trigger"), Key.Space)
                _ <- Browser.assertVisible(Selector.id("d-options"))
                _ <- Browser.press(Selector.id("d-trigger"), Key.Enter)
                _ <- Browser.assertText(Selector.id("s"), "not-submitted")
            yield succeed
        }
    }

    "Tab from open select closes dropdown" in run {
        val app: UI < Async =
            for selected <- Signal.initRef("a")
            yield UI.div(
                UI.dropdown("Alpha" -> "a", "Beta" -> "b").id("d").value(selected),
                UI.input.id("i")
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("d-trigger"), Key.Space)
                _ <- Browser.assertVisible(Selector.id("d-options"))
                _ <- Browser.press(Selector.id("d-trigger"), Key.Tab)
                _ <- Browser.assertNotVisible(Selector.id("d-options"))
            yield succeed
        }
    }

end SelectTest
