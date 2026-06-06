package kyo

import kyo.Browser.*
import kyo.internal.NumberFormat
import scala.language.implicitConversions

class RangeInputTest extends UITest:

    "type range" in {
        withUI(UI.div(UI.rangeInput.id("r"))) {
            Browser.assertAttribute(Selector.id("r"), "type", "range").unit
        }
    }

    "min max attributes" in {
        withUI(UI.div(UI.rangeInput.min(0).max(100).id("r"))) {
            for
                _ <- Browser.assertAttribute(Selector.id("r"), "min", "0")
                _ <- Browser.assertAttribute(Selector.id("r"), "max", "100")
            yield ()
        }
    }

    "step attribute" in {
        withUI(UI.div(UI.rangeInput.step(5).id("r"))) {
            Browser.assertAttribute(Selector.id("r"), "step", "5").unit
        }
    }

    "all constraints" in {
        withUI(UI.div(UI.rangeInput.min(0).max(100).step(5).id("r"))) {
            for
                _ <- Browser.assertAttribute(Selector.id("r"), "min", "0")
                _ <- Browser.assertAttribute(Selector.id("r"), "max", "100")
                _ <- Browser.assertAttribute(Selector.id("r"), "step", "5")
            yield ()
        }
    }

    "disabled" in {
        withUI(UI.div(UI.rangeInput.disabled(true).id("r"))) {
            Browser.assertDisabled(Selector.id("r")).unit
        }
    }

    "disabled signal toggle" in {
        val app: UI < Async =
            for disabled <- Signal.initRef(true)
            yield UI.div(
                disabled.map(d => UI.rangeInput.id("r").disabled(d)),
                UI.button("Enable").id("en").onClick(disabled.set(false))
            )
        withUI(app) {
            for
                _ <- Browser.assertDisabled(Selector.id("r"))
                _ <- Browser.click(Selector.id("en"))
                _ <- Browser.assertEnabled(Selector.id("r"))
            yield ()
        }
    }

    "onChange fires" in {
        val app: UI < Async =
            for ref <- Signal.initRef(0.0)
            yield UI.div(
                UI.rangeInput.id("r").onChange(v => ref.set(v)),
                ref.map(v => UI.span(NumberFormat.double(v)).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("r"), "42")
                _ <- Browser.assertText(Selector.id("v"), "42")
            yield ()
        }
    }

    "onChange correct value" in {
        val app: UI < Async =
            for ref <- Signal.initRef(0.0)
            yield UI.div(
                UI.rangeInput.step(0.1).id("r").onChange(v => ref.set(v)),
                ref.map(v => UI.span(v.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("r"), "75.5")
                _ <- Browser.assertText(Selector.id("v"), "75.5")
            yield ()
        }
    }

    "focus" in {
        withUI(UI.div(UI.rangeInput.id("r"))) {
            for
                _ <- Browser.click(Selector.id("r"))
                _ <- Browser.assertVisible(Selector.id("r"))
            yield ()
        }
    }

    "onFocus fires" in {
        val app: UI < Async =
            for ref <- Signal.initRef(false)
            yield UI.div(
                UI.rangeInput.id("r").onFocus(ref.set(true)),
                ref.map(v => UI.span(v.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("r"))
                _ <- Browser.assertText(Selector.id("v"), "true")
            yield ()
        }
    }

    "onBlur fires" in {
        val app: UI < Async =
            for ref <- Signal.initRef(false)
            yield UI.div(
                UI.rangeInput.id("r").onBlur(ref.set(true)),
                UI.button("x").id("b"),
                ref.map(v => UI.span(v.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("r"))
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("v"), "true")
            yield ()
        }
    }

    "pressKey ArrowRight fires onKeyDown" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.rangeInput.id("r").onKeyDown(ke => ref.set(ke.key.toString)),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("r"), Key.ArrowRight)
                _ <- Browser.assertText(Selector.id("v"), "ArrowRight")
            yield ()
        }
    }

    "pressKey ArrowLeft fires onKeyDown" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.rangeInput.id("r").onKeyDown(ke => ref.set(ke.key.toString)),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("r"), Key.ArrowLeft)
                _ <- Browser.assertText(Selector.id("v"), "ArrowLeft")
            yield ()
        }
    }

end RangeInputTest
