package kyo

import kyo.Browser.*
import kyo.internal.NumberFormat

class NumberInputTest extends UITest:

    "number type" in {
        withUI(UI.div(UI.numberInput.id("n"))) {
            Browser.assertAttribute(Selector.id("n"), "type", "number").unit
        }
    }

    "number min attr" in {
        withUI(UI.div(UI.numberInput.min(0).id("n"))) {
            Browser.assertAttribute(Selector.id("n"), "min", "0").unit
        }
    }

    "number max attr" in {
        withUI(UI.div(UI.numberInput.max(100).id("n"))) {
            Browser.assertAttribute(Selector.id("n"), "max", "100").unit
        }
    }

    "number step attr" in {
        withUI(UI.div(UI.numberInput.step(5).id("n"))) {
            Browser.assertAttribute(Selector.id("n"), "step", "5").unit
        }
    }

    "number all three attrs" in {
        withUI(UI.div(UI.numberInput.min(0).max(100).step(5).id("n"))) {
            for
                _ <- Browser.assertAttribute(Selector.id("n"), "min", "0")
                _ <- Browser.assertAttribute(Selector.id("n"), "max", "100")
                _ <- Browser.assertAttribute(Selector.id("n"), "step", "5")
            yield ()
        }
    }

    "number onInput string" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.numberInput.id("n").onInput(v => ref.set(v)),
                ref.map(v => UI.span(s"v:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("n"), "7")
                _ <- Browser.assertText(Selector.id("v"), "v:7")
            yield ()
        }
    }

    "number disabled" in {
        withUI(UI.div(UI.numberInput.disabled(true).id("n"))) {
            Browser.assertDisabled(Selector.id("n")).unit
        }
    }

    // ---- Range ----

    "range min max step" in {
        withUI(UI.div(UI.rangeInput.min(0).max(100).step(1).id("r"))) {
            for
                _ <- Browser.assertAttribute(Selector.id("r"), "min", "0")
                _ <- Browser.assertAttribute(Selector.id("r"), "max", "100")
                _ <- Browser.assertAttribute(Selector.id("r"), "step", "1")
            yield ()
        }
    }

    // ---- Numeric events ----

    "onChangeNumeric fires" in {
        val app: UI < Async =
            for ref <- Signal.initRef(0.0)
            yield UI.div(
                UI.numberInput.id("n").onChangeNumeric(v => ref.set(v)),
                ref.map(v => UI.span(s"n:${NumberFormat.double(v)}").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("n"), "42")
                _ <- Browser.assertText(Selector.id("v"), "n:42")
            yield ()
        }
    }

    "onInput + onChangeNumeric both fire" in {
        val app: UI < Async =
            for
                strRef <- Signal.initRef("")
                numRef <- Signal.initRef(0.0)
            yield UI.div(
                UI.numberInput.id("n").onInput(v => strRef.set(v)).onChangeNumeric(v => numRef.set(v)),
                strRef.map(v => UI.span(s"s:$v").id("sv")),
                numRef.map(v => UI.span(s"n:${NumberFormat.double(v)}").id("nv"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("n"), "7")
                _ <- Browser.assertText(Selector.id("nv"), "n:7")
            yield ()
        }
    }

    "fill float 3.14" in {
        val app: UI < Async =
            for ref <- Signal.initRef(0.0)
            yield UI.div(
                UI.numberInput.id("n").onChangeNumeric(v => ref.set(v)),
                ref.map(v => UI.span(s"n:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("n"), "3.14")
                _ <- Browser.assertText(Selector.id("v"), "n:3.14")
            yield ()
        }
    }

    "value signalRef binding" in {
        val app: UI < Async =
            for ref <- Signal.initRef("10")
            yield UI.div(
                UI.numberInput.id("n").value(ref).onInput(v => ref.set(v)),
                ref.map(v => UI.span(s"v:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("v"), "v:10")
                _ <- Browser.fill(Selector.id("n"), "20")
                _ <- Browser.assertText(Selector.id("v"), "v:20")
            yield ()
        }
    }

    "number placeholder" in {
        withUI(UI.div(UI.numberInput.placeholder("Enter number").id("n"))) {
            Browser.assertAttribute(Selector.id("n"), "placeholder", "Enter number").unit
        }
    }

    "number focus" in {
        withUI(UI.div(UI.numberInput.id("n"))) {
            for
                _ <- Browser.click(Selector.id("n"))
                _ <- Browser.assertVisible(Selector.id("n"))
            yield ()
        }
    }

end NumberInputTest
