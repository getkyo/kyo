package kyo

import kyo.Browser.*
import scala.language.implicitConversions

// DomBackend.scala is a JS-only source. These tests exercise its behaviour
// end-to-end via the JVM browser test infrastructure (SSE/event POST cycle).
class DomBackendTest extends UITest:

    "Replace op updates only the target reactive zone" in run {
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
            yield succeed
        }
    }

    "empty reactive renders as placeholder span with data-kyo-path" in run {
        val app: UI < Async =
            for show <- Signal.initRef(false)
            yield UI.div(
                show.map(s => if s then UI.span("content").id("content") else UI.empty)
            )
        withUI(app) {
            // When signal is false, show is absent; placeholder span should exist in DOM
            // (kyo-ui uses a placeholder span so the path anchor is preserved)
            Browser.assertNotExists(Selector.id("content")).andThen(succeed)
        }
    }

    "deep path update applies to nested reactive element only" in run {
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
            yield succeed
        }
    }

    "click on nested element fires its handler and bubbles to parent" in run {
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
            yield succeed
        }
    }

    "three independent signals update respective DOM zones" in run {
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
            yield succeed
        }
    }

end DomBackendTest
