package kyo

import kyo.Browser.*

class PickerInputTest extends UITest:

    "date type" in {
        withUI(UI.div(UI.dateInput.id("d"))) {
            Browser.assertAttribute(Selector.id("d"), "type", "date").unit
        }
    }

    "date disabled" in {
        withUI(UI.div(UI.dateInput.disabled(true).id("d"))) {
            Browser.assertDisabled(Selector.id("d")).unit
        }
    }

    "time type" in {
        withUI(UI.div(UI.timeInput.id("t"))) {
            Browser.assertAttribute(Selector.id("t"), "type", "time").unit
        }
    }

    "color type" in {
        withUI(UI.div(UI.colorInput.id("c"))) {
            Browser.assertAttribute(Selector.id("c"), "type", "color").unit
        }
    }

    "color disabled" in {
        withUI(UI.div(UI.colorInput.disabled(true).id("c"))) {
            Browser.assertDisabled(Selector.id("c")).unit
        }
    }

    // ---- DateInput ----

    "date onChange fires" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.dateInput.id("d").onChange(v => ref.set(v)),
                ref.map(v => UI.span(s"D:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("d"), "2024-01-15")
                _ <- Browser.assertText(Selector.id("v"), "D:2024-01-15")
            yield ()
        }
    }

    "date value signalRef binding" in {
        val app: UI < Async =
            for ref <- Signal.initRef("2024-06-01")
            yield UI.div(
                UI.dateInput.id("d").value(ref).onChange(v => ref.set(v)),
                ref.map(v => UI.span(s"D:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("v"), "D:2024-06-01")
                _ <- Browser.fill(Selector.id("d"), "2025-01-01")
                _ <- Browser.assertText(Selector.id("v"), "D:2025-01-01")
            yield ()
        }
    }

    "date disabled signal toggle" in {
        val app: UI < Async =
            for
                disabled <- Signal.initRef(true)
                counter  <- Signal.initRef(0)
            yield UI.div(
                disabled.map(d => UI.dateInput.id("d").disabled(d).onChange(_ => counter.getAndUpdate(_ + 1).unit)),
                UI.button("Enable").id("en").onClick(disabled.set(false)),
                counter.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertDisabled(Selector.id("d"))
                _ <- Browser.click(Selector.id("en"))
                _ <- Browser.assertEnabled(Selector.id("d"))
            yield ()
        }
    }

    "date focus" in {
        withUI(UI.div(UI.dateInput.id("d"))) {
            for
                _ <- Browser.click(Selector.id("d"))
                _ <- Browser.assertVisible(Selector.id("d"))
            yield ()
        }
    }

    "date onFocus fires" in {
        val app: UI < Async =
            for ref <- Signal.initRef(false)
            yield UI.div(
                UI.dateInput.id("d").onFocus(ref.set(true)),
                ref.map(v => UI.span(v.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("d"))
                _ <- Browser.assertText(Selector.id("v"), "true")
            yield ()
        }
    }

    "date onBlur fires" in {
        val app: UI < Async =
            for ref <- Signal.initRef(false)
            yield UI.div(
                UI.dateInput.id("d").onBlur(ref.set(true)),
                UI.button("Other").id("b"),
                ref.map(v => UI.span(v.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("d"))
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("v"), "true")
            yield ()
        }
    }

    // ---- TimeInput ----

    "time onChange fires" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.timeInput.id("t").onChange(v => ref.set(v)),
                ref.map(v => UI.span(s"T:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("t"), "14:30")
                _ <- Browser.assertText(Selector.id("v"), "T:14:30")
            yield ()
        }
    }

    "time value signalRef binding" in {
        val app: UI < Async =
            for ref <- Signal.initRef("09:00")
            yield UI.div(
                UI.timeInput.id("t").value(ref).onChange(v => ref.set(v)),
                ref.map(v => UI.span(s"T:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("v"), "T:09:00")
                _ <- Browser.fill(Selector.id("t"), "18:45")
                _ <- Browser.assertText(Selector.id("v"), "T:18:45")
            yield ()
        }
    }

    "time disabled" in {
        withUI(UI.div(UI.timeInput.disabled(true).id("t"))) {
            Browser.assertDisabled(Selector.id("t")).unit
        }
    }

    "time disabled signal toggle" in {
        val app: UI < Async =
            for
                disabled <- Signal.initRef(true)
                counter  <- Signal.initRef(0)
            yield UI.div(
                disabled.map(d => UI.timeInput.id("t").disabled(d).onChange(_ => counter.getAndUpdate(_ + 1).unit)),
                UI.button("Enable").id("en").onClick(disabled.set(false)),
                counter.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertDisabled(Selector.id("t"))
                _ <- Browser.click(Selector.id("en"))
                _ <- Browser.assertEnabled(Selector.id("t"))
            yield ()
        }
    }

    "time focus" in {
        withUI(UI.div(UI.timeInput.id("t"))) {
            for
                _ <- Browser.click(Selector.id("t"))
                _ <- Browser.assertVisible(Selector.id("t"))
            yield ()
        }
    }

    // ---- ColorInput ----

    "color onChange fires" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.colorInput.id("c").onChange(v => ref.set(v)),
                ref.map(v => UI.span(s"C:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("c"), "#ff0000")
                _ <- Browser.assertText(Selector.id("v"), "C:#ff0000")
            yield ()
        }
    }

    "color value signalRef binding" in {
        val app: UI < Async =
            for ref <- Signal.initRef("#00ff00")
            yield UI.div(
                UI.colorInput.id("c").value(ref).onChange(v => ref.set(v)),
                ref.map(v => UI.span(s"C:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("v"), "C:#00ff00")
                _ <- Browser.fill(Selector.id("c"), "#0000ff")
                _ <- Browser.assertText(Selector.id("v"), "C:#0000ff")
            yield ()
        }
    }

    "color disabled signal toggle" in {
        val app: UI < Async =
            for
                disabled <- Signal.initRef(true)
                counter  <- Signal.initRef(0)
            yield UI.div(
                disabled.map(d => UI.colorInput.id("c").disabled(d).onChange(_ => counter.getAndUpdate(_ + 1).unit)),
                UI.button("Enable").id("en").onClick(disabled.set(false)),
                counter.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertDisabled(Selector.id("c"))
                _ <- Browser.click(Selector.id("en"))
                _ <- Browser.assertEnabled(Selector.id("c"))
            yield ()
        }
    }

    "date value initial" in {
        withUI(UI.div(UI.dateInput.value("2024-01-15").id("d"))) {
            Browser.assertAttribute(Selector.id("d"), "value", "2024-01-15").unit
        }
    }

    "time value initial" in {
        withUI(UI.div(UI.timeInput.value("14:30").id("t"))) {
            Browser.assertAttribute(Selector.id("t"), "value", "14:30").unit
        }
    }

end PickerInputTest
