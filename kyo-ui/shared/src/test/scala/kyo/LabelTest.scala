package kyo

import kyo.Browser.*
import scala.language.implicitConversions

class LabelTest extends UITest:

    "label text" in run {
        withUI(UI.div(UI.label("Name:").id("l"))) {
            Browser.assertText(Selector.id("l"), "Name:").andThen(succeed)
        }
    }

    "label forId" in run {
        withUI(UI.div(UI.label("Name:").forId("inp").id("l"))) {
            for
                _ <- Browser.assertText(Selector.id("l"), "Name:")
                _ <- Browser.assertAttribute(Selector.id("l"), "for", "inp")
            yield succeed
        }
    }

    "label for backtick" in run {
        withUI(UI.div(UI.label("Name:").`for`("inp").id("l"))) {
            Browser.assertAttribute(Selector.id("l"), "for", "inp").andThen(succeed)
        }
    }

    "label with style" in run {
        withUI(UI.div(UI.label("X").style(Style.bold).id("l"))) {
            for
                _ <- Browser.assertText(Selector.id("l"), "X")
                _ <- Browser.assertAttributeSatisfies(Selector.id("l"), "style", "ignore")(_.contains("font-weight"))
            yield succeed
        }
    }

    "label onClick" in run {
        val app: UI < Async =
            for ref <- Signal.initRef(0)
            yield UI.div(
                UI.label("Click").id("l").onClick(ref.getAndUpdate(_ + 1).unit),
                ref.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("l"))
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield succeed
        }
    }

    "label exists" in run {
        withUI(UI.div(UI.label("X").id("l"))) {
            for
                _ <- Browser.assertExists(Selector.id("l"))
                _ <- Browser.assertText(Selector.id("l"), "X")
            yield succeed
        }
    }

    "label hidden" in run {
        withUI(UI.div(UI.label("X").hidden(true).id("l"))) {
            Browser.assertAttribute(Selector.id("l"), "hidden", "").andThen(succeed)
        }
    }

    "label wrapping input" in run {
        withUI(UI.div(UI.label(UI.input.id("i"))("Wrapped").id("l"))) {
            for
                _ <- Browser.assertVisible(Selector.id("l"))
                _ <- Browser.assertVisible(Selector.id("i"))
            yield succeed
        }
    }

end LabelTest
