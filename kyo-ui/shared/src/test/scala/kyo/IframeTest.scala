package kyo

import kyo.Browser.*
import kyo.UI.*
import scala.language.implicitConversions

class IframeTest extends UITest:

    "iframe renders as an iframe element" in {
        withUI(UI.div(UI.iframe("about:blank").id("f"))) {
            for tag <- Browser.eval("document.getElementById('f').tagName")
            yield assert(tag == "IFRAME")
        }
    }

    "iframe src attribute" in {
        withUI(UI.div(UI.iframe("https://example.com/page").id("f"))) {
            Browser.assertAttributeSatisfies(Selector.id("f"), "src", "ignore")(_.contains("example.com/page")).unit
        }
    }

    "iframe title attribute" in {
        withUI(UI.div(UI.iframe("about:blank").title("Live preview").id("f"))) {
            Browser.assertAttribute(Selector.id("f"), "title", "Live preview").unit
        }
    }

    "iframe reactive src updates" in {
        val app: UI < Async =
            for sig <- Signal.initRef("https://a.test/old")
            yield UI.div(
                UI.button("Go").id("b").onClick(sig.set("https://a.test/new")),
                sig.map(s => UI.iframe(s).id("f"))
            )
        withUI(app) {
            for
                _ <- Browser.assertAttributeSatisfies(Selector.id("f"), "src", "ignore")(_.contains("old"))
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertAttributeSatisfies(Selector.id("f"), "src", "ignore")(_.contains("new"))
            yield ()
        }
    }

end IframeTest
