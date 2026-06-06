package kyo

import kyo.Browser.*
import scala.language.implicitConversions

class RealtimeScenarioItTest extends UITest:

    "external signal set between keystrokes" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").value(ref),
                UI.button("External").id("ext").onClick(ref.set("external")),
                ref.map(v => UI.span(s"sig:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "typed")
                _ <- Browser.assertText(Selector.id("v"), "sig:typed")
                _ <- Browser.click(Selector.id("ext"))
                _ <- Browser.assertText(Selector.id("v"), "sig:external")
            yield ()
        }
    }

    "external signal replaces value" in {
        val app: UI < Async =
            for ref <- Signal.initRef("initial")
            yield UI.div(
                UI.input.id("i").value(ref),
                UI.button("Replace").id("replace").onClick(ref.set("replaced")),
                ref.map(v => UI.span(s"sig:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("v"), "sig:initial")
                _ <- Browser.click(Selector.id("replace"))
                _ <- Browser.assertText(Selector.id("v"), "sig:replaced")
            yield ()
        }
    }

    "rapid external updates final state correct" in {
        val app: UI < Async =
            for ref <- Signal.initRef(0)
            yield UI.div(
                UI.button("Inc").id("inc").onClick(ref.getAndUpdate(_ + 1).unit),
                ref.map(v => UI.span(s"val:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Kyo.foreachDiscard(0 until 50)(_ => Browser.click(Selector.id("inc")))
                _ <- Browser.assertText(Selector.id("v"), "val:50")
            yield ()
        }
    }

    "typing into a two-way-bound email input preserves character order" in {
        // Regression: email/number inputs do not support selectionStart, so echoing each keystroke back as a
        // Replace reset the caret to 0, reversing typed text (e.g. "abcdef" -> "fedcba"). The framework now skips
        // re-rendering the focused field. Type character by character (Browser.fill sets the value in one shot
        // and would not exercise the per-keystroke caret path).
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.emailInput.id("e").value(ref),
                ref.map(v => UI.span(s"sig:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("e"))
                _ <- Kyo.foreachDiscard("abcdef".toList)(c => Browser.press(Selector.id("e"), Browser.Key(c)))
                _ <- Browser.assertText(Selector.id("v"), "sig:abcdef")
                v <- Browser.value(Selector.id("e"))
            yield assert(v == "abcdef")
        }
    }

    "typing then external update external wins" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").value(ref),
                UI.button("Override").id("btn").onClick(ref.set("override")),
                ref.map(v => UI.span(s"sig:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "typed")
                _ <- Browser.click(Selector.id("btn"))
                _ <- Browser.assertText(Selector.id("v"), "sig:override")
            yield ()
        }
    }

    "external update then type appends to external" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").value(ref),
                UI.button("Ext").id("ext").onClick(ref.set("ext")),
                ref.map(v => UI.span(s"sig:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("ext"))
                _ <- Browser.assertText(Selector.id("v"), "sig:ext")
                _ <- Browser.fill(Selector.id("i"), "ext+more")
                _ <- Browser.assertText(Selector.id("v"), "sig:ext+more")
            yield ()
        }
    }

    "typing then external update external wins (variant)" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").value(ref),
                UI.button("Override").id("btn").onClick(ref.set("override")),
                ref.map(v => UI.span(s"sig:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "typed")
                _ <- Browser.assertText(Selector.id("v"), "sig:typed")
                _ <- Browser.click(Selector.id("btn"))
                _ <- Browser.assertText(Selector.id("v"), "sig:override")
            yield ()
        }
    }

    "signal updates from external source while typing both reflected" in {
        val app: UI < Async =
            for
                input   <- Signal.initRef("")
                counter <- Signal.initRef(0)
            yield UI.div(
                UI.input.id("i").value(input),
                UI.button("Ext").id("ext").onClick(counter.getAndUpdate(_ + 1).unit),
                input.map(v => UI.span(s"input:$v").id("vi")),
                counter.map(n => UI.span(s"ext:$n").id("ve"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "hello")
                _ <- Browser.assertText(Selector.id("vi"), "input:hello")
                _ <- Browser.click(Selector.id("ext"))
                _ <- Browser.assertText(Selector.id("ve"), "ext:1")
                _ <- Browser.assertText(Selector.id("vi"), "input:hello")
            yield ()
        }
    }

end RealtimeScenarioItTest
