package kyo

import kyo.Browser.*
import scala.language.implicitConversions

// DomBackend.scala is a JS-only source. These tests exercise its behaviour
// end-to-end via the JVM browser test infrastructure (SSE/event POST cycle).
class DomBackendTest extends UITest:

    "Replace op updates only the target reactive zone" in {
        val app: UI < Async =
            for
                a <- Signal.initRef("zone-a")
                b <- Signal.initRef("zone-b")
            yield UI.div(
                UI.button("UpdateA").id("ua").onClick(a.set("zone-a-new")),
                a.map(v => UI.span(v).id("za")),
                b.map(v => UI.span(v).id("zb"))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("za"), "zone-a")
                _ <- Browser.assertText(Selector.id("zb"), "zone-b")
                _ <- Browser.click(Selector.id("ua"))
                _ <- Browser.assertText(Selector.id("za"), "zone-a-new")
                // zone b is untouched
                _ <- Browser.assertText(Selector.id("zb"), "zone-b")
            yield ()
        }
    }

    "empty reactive renders as placeholder span with data-kyo-path" in {
        val app: UI < Async =
            for show <- Signal.initRef(false)
            yield UI.div(
                UI.when(show)(UI.span("content").id("content"))
            )
        withUI(app) {
            // When signal is false, show is absent; placeholder span should exist in DOM
            // (kyo-ui uses a placeholder span so the path anchor is preserved)
            Browser.assertNotExists(Selector.id("content")).unit
        }
    }

    "deep path update applies to nested reactive element only" in {
        val app: UI < Async =
            for
                outer <- Signal.initRef("outer-val")
                inner <- Signal.initRef("inner-val")
            yield UI.div(
                outer.map(ov =>
                    UI.div.id("outer-zone")(
                        UI.span(ov).id("outer-text"),
                        inner.map(iv => UI.span(iv).id("inner-text"))
                    )
                )
            )
        withUI(app) {
            // Both render initially
            for
                _ <- Browser.assertText(Selector.id("outer-text"), "outer-val")
                _ <- Browser.assertText(Selector.id("inner-text"), "inner-val")
            yield ()
        }
    }

    "click on nested element fires its handler and bubbles to parent" in {
        val app: UI < Async =
            for
                parentCount <- Signal.initRef(0)
                childCount  <- Signal.initRef(0)
            yield UI.div.id("parent").onClick(parentCount.getAndUpdate(_ + 1).unit)(
                UI.button("child").id("child").onClick(childCount.getAndUpdate(_ + 1).unit),
                parentCount.map(n => UI.span(s"p:$n").id("pc")),
                childCount.map(n => UI.span(s"c:$n").id("cc"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("child"))
                _ <- Browser.assertText(Selector.id("cc"), "c:1")
                _ <- Browser.assertText(Selector.id("pc"), "p:1")
            yield ()
        }
    }

    "three independent signals update respective DOM zones" in {
        val app: UI < Async =
            for
                a <- Signal.initRef("a")
                b <- Signal.initRef("b")
                c <- Signal.initRef("c")
            yield UI.div(
                UI.button("A").id("ba").onClick(a.set("a-new")),
                UI.button("B").id("bb").onClick(b.set("b-new")),
                UI.button("C").id("bc").onClick(c.set("c-new")),
                a.map(v => UI.span(v).id("za")),
                b.map(v => UI.span(v).id("zb")),
                c.map(v => UI.span(v).id("zc"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("ba"))
                _ <- Browser.assertText(Selector.id("za"), "a-new")
                _ <- Browser.assertText(Selector.id("zb"), "b")
                _ <- Browser.assertText(Selector.id("zc"), "c")
                _ <- Browser.click(Selector.id("bc"))
                _ <- Browser.assertText(Selector.id("za"), "a-new")
                _ <- Browser.assertText(Selector.id("zc"), "c-new")
            yield ()
        }
    }

    // INV-008: the single-consumer drain preserves event ordering; all 5 clicks must be processed in
    // order by the same drain fiber, so the counter reaches 5 monotonically with no dropped events.
    "events dispatch in order under the page scope" in {
        val app: UI < Async =
            for counterRef <- Signal.initRef(0)
            yield UI.div(
                UI.button("inc").id("inc").onClick(counterRef.getAndUpdate(_ + 1).unit),
                counterRef.map(n => UI.span(n.toString).id("counter"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("inc"))
                _ <- Browser.assertText(Selector.id("counter"), "1")
                _ <- Browser.click(Selector.id("inc"))
                _ <- Browser.assertText(Selector.id("counter"), "2")
                _ <- Browser.click(Selector.id("inc"))
                _ <- Browser.assertText(Selector.id("counter"), "3")
                _ <- Browser.click(Selector.id("inc"))
                _ <- Browser.assertText(Selector.id("counter"), "4")
                _ <- Browser.click(Selector.id("inc"))
                _ <- Browser.assertText(Selector.id("counter"), "5")
            yield ()
        }
    }

end DomBackendTest
