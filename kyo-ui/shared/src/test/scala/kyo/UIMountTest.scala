package kyo

import kyo.Browser.*

// UIMount.scala lives in the JS source tree; it is exercised end-to-end when the JVM
// UIServer serves the bootstrap HTML and the JS runtime calls DomBackend.mount on page
// load. These tests verify observable DOM effects of the mount entry point via the
// JVM browser test infrastructure.
class UIMountTest extends UITest:

    "mounted div is present in body" in {
        withUI(UI.div("mounted").id("m")) {
            for
                _ <- Browser.assertExists(Selector.id("m"))
                _ <- Browser.assertText(Selector.id("m"), "mounted")
            yield ()
        }
    }

    "signal update propagates to DOM via mount" in {
        val app: UI < Async =
            for ref <- Signal.initRef("before")
            yield UI.div(
                UI.button("Change").id("chg").onClick(ref.set("after")),
                ref.map(v => UI.span(v).id("out"))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("out"), "before")
                _ <- Browser.click(Selector.id("chg"))
                _ <- Browser.assertText(Selector.id("out"), "after")
            yield ()
        }
    }

    "mounted component preserves interactivity" in {
        val app: UI < Async =
            for counter <- Signal.initRef(0)
            yield UI.div(
                UI.button("Inc").id("inc").onClick(counter.getAndUpdate(_ + 1).unit),
                counter.map(n => UI.span(n.toString).id("cnt"))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("cnt"), "0")
                _ <- Browser.click(Selector.id("inc"))
                _ <- Browser.assertText(Selector.id("cnt"), "1")
                _ <- Browser.click(Selector.id("inc"))
                _ <- Browser.assertText(Selector.id("cnt"), "2")
            yield ()
        }
    }

    "multiple independent reactive islands after mount" in {
        val app: UI < Async =
            for
                a <- Signal.initRef("a0")
                b <- Signal.initRef("b0")
            yield UI.div(
                UI.button("UpdA").id("ua").onClick(a.set("a1")),
                UI.button("UpdB").id("ub").onClick(b.set("b1")),
                a.map(v => UI.span(v).id("va")),
                b.map(v => UI.span(v).id("vb"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("ua"))
                _ <- Browser.assertText(Selector.id("va"), "a1")
                _ <- Browser.assertText(Selector.id("vb"), "b0")
                _ <- Browser.click(Selector.id("ub"))
                _ <- Browser.assertText(Selector.id("va"), "a1")
                _ <- Browser.assertText(Selector.id("vb"), "b1")
            yield ()
        }
    }

    "re-render does not unmount existing listeners" in {
        val app: UI < Async =
            for
                toggle  <- Signal.initRef(false)
                counter <- Signal.initRef(0)
            yield UI.div(
                UI.button("Flip").id("flip").onClick(toggle.getAndUpdate(!_).unit),
                UI.button("Inc").id("inc").onClick(counter.getAndUpdate(_ + 1).unit),
                toggle.map(t => if t then UI.span("on").id("tog") else UI.span("off").id("tog")),
                counter.map(n => UI.span(n.toString).id("cnt"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("flip"))
                _ <- Browser.assertText(Selector.id("tog"), "on")
                _ <- Browser.click(Selector.id("inc"))
                _ <- Browser.assertText(Selector.id("cnt"), "1")
                _ <- Browser.click(Selector.id("flip"))
                _ <- Browser.assertText(Selector.id("tog"), "off")
                _ <- Browser.click(Selector.id("inc"))
                _ <- Browser.assertText(Selector.id("cnt"), "2")
            yield ()
        }
    }

end UIMountTest
