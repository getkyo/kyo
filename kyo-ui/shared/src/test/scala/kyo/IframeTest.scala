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

    "iframe sandbox attribute" in {
        withUI(UI.div(UI.iframe("about:blank").sandbox("allow-scripts", "allow-forms").id("f"))) {
            Browser.assertAttribute(Selector.id("f"), "sandbox", "allow-scripts allow-forms").unit
        }
    }

    "iframe srcdoc attribute" in {
        withUI(UI.div(UI.iframe("about:blank").srcdoc("<p>hello</p>").id("f"))) {
            Browser.assertAttribute(Selector.id("f"), "srcdoc", "<p>hello</p>").unit
        }
    }

    // The frame reports through postMessage rather than being read in place: Chrome may isolate a sandboxed frame in its own process,
    // where the page's frame tree does not reach it. The listener is installed before the click that sets the srcdoc, so the frame's one
    // message cannot arrive before anything listens.
    "a script in an allow-scripts sandbox runs and cannot read the parent document" in {
        val script =
            "<script>var r;try{r='read:'+parent.document.title}catch(e){r='blocked:'+e.name}parent.postMessage(r,'*')</script>"
        val app: UI < Async =
            for doc <- Signal.initRef("")
            yield UI.div(
                UI.button("Load").id("b").onClick(doc.set(script)),
                UI.iframe("about:blank").sandbox("allow-scripts").id("f").srcdoc(doc)
            )
        withUI(app) {
            for
                _ <- Browser.eval(
                    "window.addEventListener('message',function(e){document.body.setAttribute('data-frame',String(e.data))});'ok'"
                )
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertAttribute(Selector.css("body"), "data-frame", "blocked:SecurityError")
            yield ()
        }
    }

end IframeTest
