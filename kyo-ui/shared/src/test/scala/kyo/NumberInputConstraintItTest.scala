package kyo

import kyo.Browser.*
import kyo.internal.NumberFormat

class NumberInputConstraintItTest extends UITest:

    "fillNumeric sets value" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.numberInput.id("n").value(ref),
                ref.map(v => UI.span(s"val:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("n"), "42")
                _ <- Browser.assertText(Selector.id("v"), "val:42")
            yield ()
        }
    }

    "fillNumeric with decimal" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.numberInput.id("n").value(ref),
                ref.map(v => UI.span(s"val:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("n"), "3.14")
                _ <- Browser.assertText(Selector.id("v"), "val:3.14")
            yield ()
        }
    }

    "fillNumeric fires onInput" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.numberInput.id("n").onInput(v => ref.set(v)),
                ref.map(v => UI.span(s"changed:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("n"), "99")
                _ <- Browser.assertText(Selector.id("v"), "changed:99")
            yield ()
        }
    }

    "fillNumeric 0 shows zero" in {
        val app: UI < Async =
            for ref <- Signal.initRef("1")
            yield UI.div(
                UI.numberInput.id("n").value(ref),
                ref.map(v => UI.span(s"val:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("v"), "val:1")
                _ <- Browser.fill(Selector.id("n"), "0")
                _ <- Browser.assertText(Selector.id("v"), "val:0")
            yield ()
        }
    }

    "disabled number input typing blocked" in {
        val app: UI < Async =
            for ref <- Signal.initRef("50")
            yield UI.div(
                UI.numberInput.id("n").value(ref).disabled(true),
                ref.map(v => UI.span(s"val:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertDisabled(Selector.id("n"))
                _ <- Browser.assertText(Selector.id("v"), "val:50")
            yield ()
        }
    }

    "min max step attributes present" in {
        withUI(UI.div(UI.numberInput.id("n").min(0).max(100).step(5))) {
            for
                _ <- Browser.assertAttribute(Selector.id("n"), "min", "0")
                _ <- Browser.assertAttribute(Selector.id("n"), "max", "100")
                _ <- Browser.assertAttribute(Selector.id("n"), "step", "5")
            yield ()
        }
    }

    "ArrowUp increments by step" in {
        val app: UI < Async =
            for
                display <- Signal.initRef("0")
            yield UI.div(
                UI.numberInput.id("n").min(0).max(100).step(5).onChangeNumeric(v => display.set(NumberFormat.double(v))),
                display.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("n"))
                _ <- Browser.press(Selector.id("n"), Key.ArrowUp)
                _ <- Browser.assertText(Selector.id("v"), "5")
            yield ()
        }
    }

    "ArrowUp at max clamps" in {
        val app: UI < Async =
            for
                display <- Signal.initRef("10")
            yield UI.div(
                UI.numberInput.id("n").min(0).max(10).step(1).value(display).onChangeNumeric(v => display.set(NumberFormat.double(v))),
                display.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("v"), "10")
                _ <- Browser.click(Selector.id("n"))
                _ <- Browser.press(Selector.id("n"), Key.ArrowUp)
                _ <- Browser.assertText(Selector.id("v"), "10")
            yield ()
        }
    }

    "ArrowDown at min clamps" in {
        val app: UI < Async =
            for
                display <- Signal.initRef("0")
            yield UI.div(
                UI.numberInput.id("n").min(0).max(10).step(1).value(display).onChangeNumeric(v => display.set(NumberFormat.double(v))),
                display.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("v"), "0")
                _ <- Browser.click(Selector.id("n"))
                _ <- Browser.press(Selector.id("n"), Key.ArrowDown)
                _ <- Browser.assertText(Selector.id("v"), "0")
            yield ()
        }
    }

end NumberInputConstraintItTest
