package kyo

import kyo.Browser.*
import scala.language.implicitConversions

class LabelTest extends UITest:

    "label text" in {
        withUI(UI.div(UI.label("Name:").id("l"))) {
            Browser.assertText(Selector.id("l"), "Name:").unit
        }
    }

    "label forId" in {
        withUI(UI.div(UI.label("Name:").forId("inp").id("l"))) {
            for
                _ <- Browser.assertText(Selector.id("l"), "Name:")
                _ <- Browser.assertAttribute(Selector.id("l"), "for", "inp")
            yield ()
        }
    }

    "label for backtick" in {
        withUI(UI.div(UI.label("Name:").`for`("inp").id("l"))) {
            Browser.assertAttribute(Selector.id("l"), "for", "inp").unit
        }
    }

    "label with style" in {
        withUI(UI.div(UI.label("X").style(Style.bold).id("l"))) {
            for
                _ <- Browser.assertText(Selector.id("l"), "X")
                _ <- Browser.assertAttributeSatisfies(Selector.id("l"), "style", "ignore")(_.contains("font-weight"))
            yield ()
        }
    }

    "label onClick" in {
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
            yield ()
        }
    }

    "label exists" in {
        withUI(UI.div(UI.label("X").id("l"))) {
            for
                _ <- Browser.assertExists(Selector.id("l"))
                _ <- Browser.assertText(Selector.id("l"), "X")
            yield ()
        }
    }

    "label hidden" in {
        withUI(UI.div(UI.label("X").hidden(true).id("l"))) {
            Browser.assertAttribute(Selector.id("l"), "hidden", "").unit
        }
    }

    "label wrapping input" in {
        withUI(UI.div(UI.label(UI.input.id("i"))("Wrapped").id("l"))) {
            for
                _ <- Browser.assertVisible(Selector.id("l"))
                _ <- Browser.assertVisible(Selector.id("i"))
            yield ()
        }
    }

end LabelTest
