package kyo

import kyo.Browser.*
import kyo.UI.*

// Non-blank iframe srcs here are fast-refused loopback URLs, not reachable external domains: an unreachable src stays
// pending, blocks the window `load` event, and times out withUI's settle on slow-DNS CI. Loopback port 1 refuses at once.
class IframeTest extends UITest:

    "iframe renders as an iframe element" in {
        withUI(UI.div(UI.iframe("about:blank").id("f"))) {
            for tag <- Browser.eval("document.getElementById('f').tagName")
            yield assert(tag == "IFRAME")
        }
    }

    "iframe src attribute" in {
        withUI(UI.div(UI.iframe("http://127.0.0.1:1/page").id("f"))) {
            Browser.assertAttributeSatisfies(Selector.id("f"), "src", "ignore")(_.contains("127.0.0.1:1/page")).unit
        }
    }

    "iframe title attribute" in {
        withUI(UI.div(UI.iframe("about:blank").title("Live preview").id("f"))) {
            Browser.assertAttribute(Selector.id("f"), "title", "Live preview").unit
        }
    }

    "iframe reactive src updates" in {
        val app: UI < Async =
            for sig <- Signal.initRef("http://127.0.0.1:1/old")
            yield UI.div(
                UI.button("Go").id("b").onClick(sig.set("http://127.0.0.1:1/new")),
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
