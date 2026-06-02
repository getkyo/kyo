package kyo

import kyo.Browser.*
import scala.language.implicitConversions

class HiddenInputTest extends UITest:

    "type hidden" in run {
        withUI(UI.div(UI.hiddenInput.id("h"))) {
            Browser.assertAttribute(Selector.id("h"), "type", "hidden").andThen(succeed)
        }
    }

    "value set" in run {
        withUI(UI.div(UI.hiddenInput.value("secret").id("h"))) {
            Browser.assertAttribute(Selector.id("h"), "value", "secret").andThen(succeed)
        }
    }

    "value signalRef" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("initial")
            yield UI.div(
                ref.map(v => UI.hiddenInput.value(v).id("h")),
                UI.button("Change").id("b").onClick(ref.set("updated"))
            )
        withUI(app) {
            for
                _ <- Browser.assertAttribute(Selector.id("h"), "value", "initial")
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertAttribute(Selector.id("h"), "value", "updated")
            yield succeed
        }
    }

    "hidden input inside form" in run {
        val app: UI < Async =
            for
                ref      <- Signal.initRef("token123")
                captured <- Signal.initRef("")
            yield UI.div(
                UI.form.id("f").onSubmit(ref.get.map(v => captured.set(v)))(
                    UI.hiddenInput.value(ref).id("h"),
                    UI.button("Submit").id("s")
                ),
                captured.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("s"))
                _ <- Browser.assertText(Selector.id("v"), "token123")
            yield succeed
        }
    }

    "not visually rendered" in run {
        withUI(UI.div(UI.hiddenInput.value("secret").id("h"), UI.span("visible").id("s"))) {
            for
                _ <- Browser.assertExists(Selector.id("h"))
                _ <- Browser.assertText(Selector.id("s"), "visible")
                _ <- Browser.assertTextSatisfies(Selector.css("body"), "no 'secret' in body text")(!_.contains("secret"))
            yield succeed
        }
    }

    "exists by id" in run {
        withUI(UI.div(UI.hiddenInput.id("h"))) {
            Browser.assertExists(Selector.id("h")).andThen(succeed)
        }
    }

end HiddenInputTest
