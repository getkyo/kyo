package kyo

import kyo.Browser.*

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

    // The expando probe: a JS property no markup carries survives a sibling-driven re-render only if the node
    // itself is reused (an outerHTML replace would recreate it and lose it).
    "morph reuses an inner element (preserving its DOM-local state) across a re-render of its region" in {
        val app: UI < Async =
            for outer <- Signal.initRef[Int](0)
            yield UI.div(
                outer.map(o =>
                    UI.div(
                        UI.div("keep-me").id("inner"),
                        UI.span(s"o=$o").id("status")
                    )
                ),
                UI.button("bump").id("bump").onClick(outer.getAndUpdate(_ + 1).unit)
            )
        withUI(app) {
            for
                _      <- Browser.assertText(Selector.id("status"), "o=0")
                _      <- Browser.evalDiscard("document.getElementById('inner').__kyoMark = 4242;")
                before <- Browser.evalJson[Int]("document.getElementById('inner').__kyoMark || 0")
                _      <- Browser.evalDiscard("document.getElementById('bump').click()")
                _      <- Browser.assertText(Selector.id("status"), "o=1")
                after  <- Browser.evalJson[Int]("document.getElementById('inner').__kyoMark || 0")
            yield
                assert(before == 4242)
                assert(after == 4242) // node reused; an outerHTML replace would recreate it (mark gone)
        }
    }

    "nested reactive directly inside a reactive patches independently (no path collision)" in {
        // Regression for the same-data-kyo-path collision (a reactive whose value is ITSELF a reactive, e.g.
        // `open.render(hi.render(...))`). The `: UI` ascription lifts the inner Signal into a Reactive so the outer value is itself reactive.
        val app: UI < Async =
            for
                outer <- Signal.initRef("o0")
                inner <- Signal.initRef("i0")
            yield UI.div(
                UI.button("set-inner").id("set-inner").onClick(inner.set("i1")),
                UI.button("set-outer").id("set-outer").onClick(outer.set("o1")),
                outer.map(o => (inner.map(i => UI.span(s"$o/$i").id("cell")): UI))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("cell"), "o0/i0")
                // change ONLY inner: before the fix this stayed "o0/i0".
                _ <- Browser.click(Selector.id("set-inner"))
                _ <- Browser.assertText(Selector.id("cell"), "o0/i1")
                _ <- Browser.click(Selector.id("set-outer"))
                _ <- Browser.assertText(Selector.id("cell"), "o1/i1")
            yield ()
        }
    }

    // Mount-slot reconciliation: a keyless UI.mounted and a Reactive share the reactiveContentSegment key, so
    // swapping one for the other collides on the same data-kyo-path. The live mount is preserved ONLY when the
    // incoming top-down node is itself a mount slot (data-kyo-mount-slot); otherwise the stale mount is removed.

    "a region swapping a keyless mount for colliding reactive content removes the stale mount DOM" in {
        val app: UI < Async =
            for
                sel   <- Signal.initRef("a")
                inner <- Signal.initRef("B-content")
            yield UI.div(
                UI.button("swap").id("swap").onClick(sel.set("b")),
                sel.map {
                    // both branches are reactive content -> they reconcile at the SAME positional key
                    case "a" =>
                        UI.mounted {
                            Signal.initRef(0).map(_ => UI.span("A-content").id("mount-a"))
                        }.placeholder(UI.empty): UI
                    case _ =>
                        inner.map(v => UI.span(v).id("plain-b")): UI
                }
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("mount-a"), "A-content") // mount painted (data-kyo-mount)
                _ <- Browser.click(Selector.id("swap"))
                _ <- Browser.assertText(Selector.id("plain-b"), "B-content")
                _ <- Browser.assertNotExists(Selector.id("mount-a"))         // stale mount DOM gone
            yield ()
        }
    }

    "a region closing a gate over a mount removes the mount DOM" in {
        // Empty-slot path, keyed on the ABSENCE of a mount-slot marker: open -> false yields no mount, so the morph empties it.
        val app: UI < Async =
            for open <- Signal.initRef(true)
            yield UI.div(
                UI.button("gclose").id("gclose").onClick(open.set(false)),
                open.map {
                    case true  => UI.mounted { Signal.initRef(0).map(_ => UI.span("panel").id("gpanel")) }.placeholder(UI.empty): UI
                    case false => UI.empty: UI
                }
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("gpanel"), "panel")
                _ <- Browser.click(Selector.id("gclose"))
                _ <- Browser.assertNotExists(Selector.id("gpanel"))
            yield ()
        }
    }

    "a re-rendered region keeps a mount that is still present (no wipe)" in {
        // Safety companion: the SAME mount stays across a region re-render. Its placeholder carries the mount-slot
        // marker, so the guard preserves the live mount (the legitimate case the opaque-mount guard exists for).
        val app: UI < Async =
            for tick <- Signal.initRef(0)
            yield UI.div(
                UI.button("ktick").id("ktick").onClick(tick.getAndUpdate(_ + 1).unit),
                tick.map { t =>
                    UI.div(
                        UI.span(s"t:$t").id("ktxt"),
                        UI.mounted { Signal.initRef(0).map(_ => UI.span("kept").id("kpanel")) }.placeholder(UI.empty)
                    ): UI
                }
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("kpanel"), "kept")
                _ <- Browser.click(Selector.id("ktick"))
                _ <- Browser.assertText(Selector.id("ktxt"), "t:1")
                _ <- Browser.assertText(Selector.id("kpanel"), "kept") // mount preserved across the morph
            yield ()
        }
    }

end DomBackendTest
