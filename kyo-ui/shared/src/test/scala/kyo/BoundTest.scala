package kyo

import kyo.Browser.*
import scala.language.implicitConversions

class BoundTest extends UITest:

    "input value signalRef typing auto updates signal" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").value(ref),
                ref.map(v => UI.span(s"sig:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "hello")
                _ <- Browser.assertText(Selector.id("v"), "sig:hello")
            yield succeed
        }
    }

    "input value signalRef external set updates display" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("initial")
            yield UI.div(
                UI.input.id("i").value(ref),
                UI.button("Set").id("b").onClick(ref.set("external")),
                ref.map(v => UI.span(s"sig:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("v"), "sig:initial")
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("v"), "sig:external")
            yield succeed
        }
    }

    "input value signalRef plus onInput both run" in run {
        val app: UI < Async =
            for
                ref <- Signal.initRef("")
                log <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").value(ref).onInput(v => log.set(s"custom:$v")),
                ref.map(v => UI.span(s"sig:$v").id("vs")),
                log.map(v => UI.span(v).id("vl"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "test")
                _ <- Browser.assertText(Selector.id("vs"), "sig:test")
                _ <- Browser.assertText(Selector.id("vl"), "custom:test")
            yield succeed
        }
    }

    "checkbox checked signalRef click auto updates" in run {
        val app: UI < Async =
            for ref <- Signal.initRef(false)
            yield UI.div(
                UI.checkbox.id("c").checked(ref),
                ref.map(v => UI.span(s"sig:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("c"))
                _ <- Browser.assertText(Selector.id("v"), "sig:true")
                _ <- Browser.click(Selector.id("c"))
                _ <- Browser.assertText(Selector.id("v"), "sig:false")
            yield succeed
        }
    }

    "checkbox checked signalRef external set updates visual" in run {
        val app: UI < Async =
            for ref <- Signal.initRef(false)
            yield UI.div(
                UI.checkbox.id("c").checked(ref),
                UI.button("Set").id("b").onClick(ref.set(true)),
                ref.map(v => UI.span(s"sig:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertChecked(Selector.id("c"))
                _ <- Browser.assertText(Selector.id("v"), "sig:true")
            yield succeed
        }
    }

    "select value signalRef selecting auto updates" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("a")
            yield UI.div(
                UI.select(UI.option("A").value("a"), UI.option("B").value("b")).id("s").value(ref),
                ref.map(v => UI.span(s"sig:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("v"), "sig:a")
                _ <- Browser.select(Selector.id("s"), "b")
                _ <- Browser.assertText(Selector.id("v"), "sig:b")
            yield succeed
        }
    }

    "select value signalRef external set changes selection" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("a")
            yield UI.div(
                UI.select(UI.option("A").value("a"), UI.option("B").value("b")).id("s").value(ref),
                UI.button("Set B").id("b").onClick(ref.set("b")),
                ref.map(v => UI.span(s"sig:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("v"), "sig:b")
            yield succeed
        }
    }

    "textarea value signalRef typing auto updates" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.textarea.id("t").value(ref),
                ref.map(v => UI.span(s"sig:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("t"), "multiline")
                _ <- Browser.assertText(Selector.id("v"), "sig:multiline")
            yield succeed
        }
    }

    "password value signalRef typing auto updates" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.passwordInput.id("p").value(ref),
                ref.map(v => UI.span(s"sig:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("p"), "secret")
                _ <- Browser.assertText(Selector.id("v"), "sig:secret")
            yield succeed
        }
    }

    "two inputs same signalRef typing in A updates B" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("a").value(ref),
                UI.input.id("b").value(ref),
                ref.map(v => UI.span(s"sig:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("a"), "from-a")
                _ <- Browser.assertText(Selector.id("v"), "sig:from-a")
                _ <- Browser.assertAttribute(Selector.id("b"), "value", "from-a")
            yield succeed
        }
    }

    "signal set empty clears input" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("initial")
            yield UI.div(
                UI.input.id("i").value(ref).placeholder("hint"),
                UI.button("Clear").id("clear").onClick(ref.set("")),
                ref.map(v => UI.span(s"sig:[$v]").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("v"), "sig:[initial]")
                _ <- Browser.click(Selector.id("clear"))
                _ <- Browser.assertText(Selector.id("v"), "sig:[]")
            yield succeed
        }
    }

    "signalRef binding plus disabled typing blocked" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("original")
            yield UI.div(
                UI.input.id("i").value(ref).disabled(true),
                ref.map(v => UI.span(s"sig:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertDisabled(Selector.id("i"))
                _ <- Browser.assertText(Selector.id("v"), "sig:original")
            yield succeed
        }
    }

    "rapid typing with signalRef final value correct" in run {
        // Use Browser.fill (CDP Input.insertText, single batched insertion) instead of
        // 20 separate Browser.press calls. The latter re-focuses before each press,
        // resetting cursor to start and reversing char order (see BROWSER-FEEDBACK.md).
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").value(ref),
                ref.map(v => UI.span(s"sig:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "abcdefghijklmnopqrst")
                _ <- Browser.assertText(Selector.id("v"), "sig:abcdefghijklmnopqrst")
            yield succeed
        }
    }

    "button sets signalRef input displays new value" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").value(ref),
                UI.button("Set hello").id("b").onClick(ref.set("hello")),
                ref.map(v => UI.span(s"sig:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("v"), "sig:hello")
                _ <- Browser.assertAttribute(Selector.id("i"), "value", "hello")
            yield succeed
        }
    }

    "signalRef binding plus readOnly typing blocked" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("original")
            yield UI.div(
                UI.input.id("i").value(ref).readOnly(true),
                ref.map(v => UI.span(s"sig:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertAttribute(Selector.id("i"), "readonly", "")
                _ <- Browser.assertText(Selector.id("v"), "sig:original")
            yield succeed
        }
    }

    "number input fill auto updates via two way" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.numberInput.id("n").value(ref),
                ref.map(v => UI.span(s"sig:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("n"), "42")
                _ <- Browser.assertText(Selector.id("v"), "sig:42")
            yield succeed
        }
    }

end BoundTest
