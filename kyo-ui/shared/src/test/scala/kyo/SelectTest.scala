package kyo

import kyo.Browser.*
import kyo.UI.foreach

class SelectTest extends UITest:

    "select exists" in {
        withUI(UI.div(
            UI.select(UI.option("Alpha").value("a"), UI.option("Beta").value("b")).id("s")
        )) {
            Browser.assertVisible(Selector.id("s")).unit
        }
    }

    "select onChange fires" in {
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
            yield ()
        }
    }

    "select disabled" in {
        withUI(UI.div(UI.select.id("s").disabled(true))) {
            Browser.assertDisabled(Selector.id("s")).unit
        }
    }

    "select with initial value" in {
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
            Browser.assertText(Selector.id("v"), "Val:b").unit
        }
    }

    "select onChange updates display" in {
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
            yield ()
        }
    }

    "opt children render selected option visible" in {
        withUI(UI.div(
            UI.select(
                UI.option("Alpha").value("a"),
                UI.option("Beta").value("b"),
                UI.option("Gamma").value("c")
            ).id("s")
        )) {
            Browser.assertVisible(Selector.id("s")).unit
        }
    }

    "opt value separate from text" in {
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
            yield ()
        }
    }

    "opt selected true pre-selects" in {
        withUI(UI.div(
            UI.select(
                UI.option("Alpha").value("a"),
                UI.option("Beta").value("b").selected(true)
            ).id("s")
        )) {
            Browser.assertVisible(Selector.id("s")).unit
        }
    }

    "value signalRef binding" in {
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
            yield ()
        }
    }

    // What the page shows as selected: the select's value and the selected option's text.
    private def selectedOption(using Frame) =
        Browser.value(Selector.id("s")).map(value =>
            Browser.eval("document.getElementById('s').selectedOptions[0].textContent").map(text => (value, text))
        )

    "value initial assertValue" in {
        val app: UI < Async =
            for ref <- Signal.initRef("b")
            yield UI.div(
                UI.select(
                    UI.option("Alpha").value("a"),
                    UI.option("Beta").value("b")
                ).id("s").value(ref)
            )
        withUI(app) {
            selectedOption.map(s => assert(s == ("b", "Beta")))
        }
    }

    "a constant value selects its option on the page" in {
        withUI(UI.div(
            UI.select(
                UI.option("Alpha").value("a"),
                UI.option("Beta").value("b"),
                UI.option("Gamma").value("c")
            ).id("s").value("c")
        )) {
            selectedOption.map(s => assert(s == ("c", "Gamma")))
        }
    }

    "a bound value set after mount selects its option on the page" in {
        val app: UI < Async =
            for ref <- Signal.initRef("a")
            yield UI.div(
                UI.select(
                    UI.option("Alpha").value("a"),
                    UI.option("Beta").value("b"),
                    UI.option("Gamma").value("c")
                ).id("s").value(ref),
                UI.button("Pick gamma").id("pick").onClick(ref.set("c"))
            )
        withUI(app) {
            for
                before <- selectedOption
                _      <- Browser.click(Selector.id("pick"))
                _      <- Browser.assertAttribute(Selector.id("s"), "value", "c")
                after  <- selectedOption
            yield assert((before, after) == (("a", "Alpha"), ("c", "Gamma")))
        }
    }

    "a value selects its option among options rendered from a signal" in {
        val app: UI < Async =
            for
                rows <- Signal.initRef(Chunk("a", "b", "c"))
                ref  <- Signal.initRef("b")
            yield UI.div(
                UI.select(rows.foreach(v => UI.option(v.toUpperCase).value(v))).id("s").value(ref)
            )
        withUI(app) {
            selectedOption.map(s => assert(s == ("b", "B")))
        }
    }

    "reactive option selection changes override the previous choice" in {
        val app: UI < Async =
            for flag <- Signal.initRef(false)
            yield UI.div(
                UI.select(
                    UI.option("Alpha").value("a"),
                    UI.option("Beta").value("b").id("beta").selected(flag)
                ).id("s"),
                UI.button("Pick beta").id("pick").onClick(flag.set(true)),
                UI.button("Clear beta").id("clear").onClick(flag.set(false))
            )
        withUI(app) {
            for
                _        <- Browser.click(Selector.id("pick"))
                _        <- Browser.assertAttribute(Selector.id("beta"), "selected", "")
                selected <- selectedOption
                _        <- Browser.click(Selector.id("clear"))
                _        <- Browser.assertNoAttribute(Selector.id("beta"), "selected")
                cleared  <- selectedOption
            yield assert((selected, cleared) == (("b", "Beta"), ("a", "Alpha")))
        }
    }

    "the selection survives the options being re-rendered" in {
        val app: UI < Async =
            for
                rows <- Signal.initRef(Chunk("a", "b", "c"))
                ref  <- Signal.initRef("b")
            yield UI.div(
                UI.select(rows.foreach(v => UI.option(v.toUpperCase).value(v))).id("s").value(ref),
                UI.button("Add").id("add").onClick(rows.set(Chunk("a", "b", "c", "d")))
            )
        withUI(app) {
            for
                _     <- Browser.click(Selector.id("add"))
                _     <- Browser.waitForText(Selector.css("#s option:last-child"), "D")
                after <- selectedOption
            yield assert(after == ("b", "B"))
        }
    }

    "a bound value follows the page when the re-rendered options no longer carry it" in {
        val app: UI < Async =
            for
                rows     <- Signal.initRef(Chunk("a", "b", "c"))
                ref      <- Signal.initRef("b")
                snapshot <- Signal.initRef("waiting")
            yield UI.div(
                UI.select(rows.foreach(v => UI.option(v.toUpperCase).value(v))).id("s").value(ref),
                ref.map(v => UI.span(s"bound:$v").id("bound")),
                UI.button("Replace").id("replace").onClick(rows.set(Chunk("x", "y"))),
                UI.button("Read binding").id("read").onClick(ref.get.map(v => snapshot.set(s"observed:$v"))),
                snapshot.map(v => UI.span(v).id("snapshot"))
            )
        withUI(app) {
            for
                before  <- Browser.text(Selector.id("bound"))
                _       <- Browser.click(Selector.id("replace"))
                _       <- Browser.waitForText(Selector.css("#s option:last-child"), "Y")
                _       <- Browser.click(Selector.id("read"))
                _       <- Browser.waitForText(Selector.id("snapshot"), _.startsWith("observed:"))
                binding <- Browser.text(Selector.id("snapshot"))
                after   <- selectedOption
            yield assert((before, binding, after) == ("bound:b", "observed:x", ("x", "X")))
        }
    }

    Chunk(("empty-valued", "", false), ("disabled", "b", true), ("ordinary", "b", false)).foreach {
        (label, initial, disabled) =>
            s"a $label selection removed by a whole-select update synchronizes the bound value" in {
                val app: UI < Async =
                    for
                        rows     <- Signal.initRef(Chunk("a", initial, "c"))
                        value    <- Signal.initRef(initial)
                        snapshot <- Signal.initRef("waiting")
                        calls    <- Signal.initRef(0)
                    yield UI.div(
                        rows.map(options =>
                            UI.select(options.map(v => UI.option(v.toUpperCase).value(v))*)
                                .id("s").value(value).disabled(disabled).onChange(_ => calls.getAndUpdate(_ + 1).unit)
                        ),
                        UI.button("Retain").id("retain").onClick(rows.getAndUpdate(_ :+ "d").unit),
                        UI.button("Replace").id("replace").onClick(snapshot.set("waiting").andThen(rows.set(Chunk("x", "y")))),
                        UI.button("Set value").id("set-value").onClick(value.set("y")),
                        UI.button("Read binding").id("read-binding").onClick(value.get.map(v => snapshot.set(s"observed:$v"))),
                        snapshot.map(v => UI.span(v).id("snapshot")),
                        calls.map(v => UI.span(v.toString).id("change-count"))
                    )
                withUI(app) {
                    for
                        _                 <- Browser.click(Selector.id("retain"))
                        _                 <- Browser.waitForText(Selector.css("#s option:last-child"), "D")
                        _                 <- Browser.click(Selector.id("read-binding"))
                        _                 <- Browser.waitForText(Selector.id("snapshot"), s"observed:$initial")
                        retainedPage      <- Browser.value(Selector.id("s"))
                        retainedCount     <- Browser.text(Selector.id("change-count"))
                        _                 <- Browser.click(Selector.id("replace"))
                        _                 <- Browser.waitForText(Selector.css("#s option:last-child"), "Y")
                        _                 <- Browser.click(Selector.id("read-binding"))
                        _                 <- Browser.waitForText(Selector.id("snapshot"), _.startsWith("observed:"))
                        binding           <- Browser.text(Selector.id("snapshot"))
                        page              <- Browser.value(Selector.id("s"))
                        count             <- Browser.text(Selector.id("change-count"))
                        _                 <- Browser.click(Selector.id("set-value"))
                        _                 <- Browser.waitFor("document.getElementById('s').value === 'y'")
                        _                 <- Browser.click(Selector.id("read-binding"))
                        _                 <- Browser.waitForText(Selector.id("snapshot"), "observed:y")
                        programmaticCount <- Browser.text(Selector.id("change-count"))
                    yield
                        assert((retainedPage, retainedCount) == (initial, "0"))
                        assert(
                            (binding, page, count) == ("observed:x", "x", if disabled then "0" else "1"),
                            s"binding=$binding page=$page count=$count"
                        )
                        assert(programmaticCount == count)
                }
            }
    }

    Chunk(("empty-valued", "", false), ("disabled", "b", true), ("ordinary", "b", false)).foreach {
        (label, initial, disabled) =>
            s"a $label selection removed by an option update synchronizes the bound value" in {
                val app: UI < Async =
                    for
                        rows     <- Signal.initRef(Chunk("a", initial, "c"))
                        value    <- Signal.initRef(initial)
                        snapshot <- Signal.initRef("waiting")
                    yield UI.div(
                        UI.select(rows.foreach(v => UI.option(v.toUpperCase).value(v))).id("s").value(value).disabled(disabled),
                        UI.button("Replace").id("replace").onClick(rows.set(Chunk("x", "y"))),
                        UI.button("Read binding").id("read-binding").onClick(value.get.map(v => snapshot.set(s"observed:$v"))),
                        snapshot.map(v => UI.span(v).id("snapshot"))
                    )
                withUI(app) {
                    for
                        _ <- Browser.click(Selector.id("replace"))
                        _ <- Browser.waitForText(Selector.css("#s option:last-child"), "Y")
                        // The session handles Change and Click in socket order. This click reads the binding after the patch's change.
                        _       <- Browser.click(Selector.id("read-binding"))
                        _       <- Browser.waitForText(Selector.id("snapshot"), _.startsWith("observed:"))
                        binding <- Browser.text(Selector.id("snapshot"))
                        page    <- Browser.value(Selector.id("s"))
                    yield assert((binding, page) == ("observed:x", "x"))
                }
            }
    }

    "select each option in sequence" in {
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
            yield ()
        }
    }

    "onChange fires correct value" in {
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
            yield ()
        }
    }

    "focus select" in {
        withUI(UI.div(UI.select(UI.option("A").value("a")).id("s"))) {
            for
                _ <- Browser.click(Selector.id("s"))
                _ <- Browser.assertVisible(Selector.id("s"))
            yield ()
        }
    }

    "select onFocus fires" in {
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
            yield ()
        }
    }

    "select onBlur fires" in {
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
            yield ()
        }
    }

    "disabled select no fire" in {
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
            yield ()
        }
    }

    "disabled signal toggle" in {
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
            yield ()
        }
    }

    // ---- Merged from SelectKeyboardTest ----

    "select changes value" in {
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
            yield ()
        }
    }

    "select fires onChange (keyboard)" in {
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
            yield ()
        }
    }

    "select disabled no change" in {
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
            yield ()
        }
    }

    "select with 1 option selectable" in {
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
            yield ()
        }
    }

    "Tab from select moves focus" in {
        withUI(UI.div(
            UI.select(UI.option("A").value("a")).id("s"),
            UI.input.id("i")
        )) {
            for
                _ <- Browser.click(Selector.id("s"))
                _ <- Browser.press(Selector.id("s"), Key.Tab)
                _ <- Browser.assertVisible(Selector.id("i"))
            yield ()
        }
    }

    "select with 0 options no crash" in {
        withUI(UI.div(UI.select().id("s"))) {
            Browser.assertVisible(Selector.id("s")).unit
        }
    }

    "focus select Space opens dropdown" in {
        val app: UI < Async =
            for selected <- Signal.initRef("a")
            yield UI.div(UI.dropdown("Alpha" -> "a", "Beta" -> "b").id("d").value(selected))
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("d-trigger"))
                _ <- Browser.assertVisible(Selector.id("d-options"))
            yield ()
        }
    }

    "open dropdown ArrowDown highlights next" in {
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
            yield ()
        }
    }

    "open dropdown ArrowUp highlights previous" in {
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
            yield ()
        }
    }

    "open dropdown Enter confirms selection" in {
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
            yield ()
        }
    }

    "open dropdown Escape cancels" in {
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
            yield ()
        }
    }

    "open dropdown type m jumps to option starting with m" in {
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
            yield ()
        }
    }

    "select with 100 options scrollable" in {
        val opts            = (1 to 100).map(i => s"Option$i" -> s"v$i")
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
            yield ()
        }
    }

    "multiple selects only one open at a time" in {
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
            yield ()
        }
    }

    "select inside form Enter does not submit while open" in {
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
            yield ()
        }
    }

    "Tab from open select closes dropdown" in {
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
            yield ()
        }
    }

end SelectTest
