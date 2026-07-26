package kyo

import kyo.Browser.*
import kyo.UI.foreachKeyed

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

    // The sibling `tick` signal bumps the reactive region containing `host`, so morphAttrs runs against server HTML
    // that lacks the owned class, proving the ownership guard shields the client-set class from reconciliation.
    "imperatively-bound class survives a re-render morph of its element" in {
        val app: UI < Async =
            for
                tick <- Signal.initRef(0)
                on   <- Signal.initRef(true)
            yield UI.div(
                UI.button("tick").id("tick").onClick(tick.getAndUpdate(_ + 1).unit),
                tick.map(t => UI.div.id("host")(UI.span(s"t:$t").id("txt"))),
                UI.mounted {
                    for
                        cmds <- UI.commands
                        _    <- cmds.bindClassById("host", "owned-cls", on)
                    yield UI.empty
                }.placeholder(UI.empty)
            )
        withUI(app) {
            for
                _ <- Browser.waitForAttribute(Selector.id("host"), "class", "owned-cls")
                _ <- Browser.click(Selector.id("tick"))
                _ <- Browser.assertText(Selector.id("txt"), "t:1") // the element was morphed
                _ <- Browser.assertAttribute(Selector.id("host"), "class", "owned-cls")
            yield ()
        }
    }

    "imperatively-bound attribute survives a re-render morph of its element" in {
        val app: UI < Async =
            for
                tick <- Signal.initRef(0)
                v    <- Signal.initRef("yes")
            yield UI.div(
                UI.button("tick").id("atick").onClick(tick.getAndUpdate(_ + 1).unit),
                tick.map(t => UI.div.id("ahost")(UI.span(s"t:$t").id("atxt"))),
                UI.mounted {
                    for
                        cmds <- UI.commands
                        _    <- cmds.bindAttrById("ahost", "data-owned", v)
                    yield UI.empty
                }.placeholder(UI.empty)
            )
        withUI(app) {
            for
                _ <- Browser.waitForAttribute(Selector.id("ahost"), "data-owned", "yes")
                _ <- Browser.click(Selector.id("atick"))
                _ <- Browser.assertText(Selector.id("atxt"), "t:1")
                _ <- Browser.assertAttribute(Selector.id("ahost"), "data-owned", "yes") // owned attr survives the morph
            yield ()
        }
    }

    "declarative reactive attribute patches a const element in place (no re-render), survives a sibling morph" in {
        val app: UI < Async =
            for
                tick <- Signal.initRef(0)
                lbl  <- Signal.initRef("first")
            yield UI.div(
                UI.button("tick").id("dtick").onClick(tick.getAndUpdate(_ + 1).unit),
                // dhost is CONST (only a reactive aria-label, no ref-bound value), so its label can change only
                // via the in-place attr patch, proving no re-render/re-mount.
                UI.div.id("dhost").aria("label", lbl)(UI.span("x").id("dchild")),
                tick.map(t => UI.span(s"t:$t").id("dtxt")),
                UI.button("relabel").id("drelabel").onClick(lbl.set("second"))
            )
        withUI(app) {
            for
                _ <- Browser.waitForAttribute(Selector.id("dhost"), "aria-label", "first")
                _ <- Browser.click(Selector.id("drelabel"))
                _ <- Browser.assertAttribute(Selector.id("dhost"), "aria-label", "second") // patched in place on a const node
                _ <- Browser.click(Selector.id("dtick"))
                _ <- Browser.assertText(Selector.id("dtxt"), "t:1")
                _ <- Browser.assertAttribute(Selector.id("dhost"), "aria-label", "second") // owned attr unaffected by the morph
            yield ()
        }
    }

    "declarative reactive attribute on an element produced INSIDE a UI.mounted block patches in place" in {
        // Mirrors the DatePicker/AutoComplete/Select topology: the reactive-attr element is produced by an
        // effectful UI.mounted body, NOT at the top level. Pins that the attr observer forks while walking
        // mounted content, so the aria-label still patches in place (no re-mount) on emission.
        val app: UI < Async =
            for lbl <- Signal.initRef("m-first")
            yield UI.div(
                UI.button("relabel").id("mrelabel").onClick(lbl.set("m-second")),
                UI.mounted {
                    Signal.initRef(0).map(_ => UI.div.id("mhost").aria("label", lbl)(UI.span("y").id("mchild")))
                }.placeholder(UI.empty)
            )
        withUI(app) {
            for
                _ <- Browser.waitForAttribute(Selector.id("mhost"), "aria-label", "m-first")
                _ <- Browser.click(Selector.id("mrelabel"))
                _ <- Browser.assertAttribute(Selector.id("mhost"), "aria-label", "m-second")
                _ <- Browser.assertText(Selector.id("mchild"), "y") // child untouched, no re-mount
            yield ()
        }
    }

    "declarative reactive boolean attribute (disabled) toggles in place, survives a sibling morph" in {
        // bhost is a CONST button (only a reactive `disabled`), so its disabled attribute can change only via
        // the presence-based in-place patch (proving no re-render) and must survive a sibling morph.
        val app: UI < Async =
            for
                tick <- Signal.initRef(0)
                off  <- Signal.initRef(false)
            yield UI.div(
                UI.button("tick").id("btick").onClick(tick.getAndUpdate(_ + 1).unit),
                UI.button("target").id("bhost").disabled(off),
                tick.map(t => UI.span(s"t:$t").id("btxt")),
                UI.button("toggle").id("btoggle").onClick(off.getAndUpdate(b => !b).unit)
            )
        withUI(app) {
            for
                _ <- Browser.assertNoAttribute(Selector.id("bhost"), "disabled")
                _ <- Browser.click(Selector.id("btoggle"))
                _ <- Browser.assertAttribute(Selector.id("bhost"), "disabled", "") // patched present in place
                _ <- Browser.click(Selector.id("btick"))
                _ <- Browser.assertText(Selector.id("btxt"), "t:1")
                _ <- Browser.assertAttribute(Selector.id("bhost"), "disabled", "") // owned attr survived the morph
                _ <- Browser.click(Selector.id("btoggle"))
                _ <- Browser.assertNoAttribute(Selector.id("bhost"), "disabled")   // toggled back off, removed in place
            yield ()
        }
    }

    "declarative reactive class toggles in place, survives a sibling morph" in {
        // chost is a CONST div with a static class plus a reactive class, so the reactive class can change only
        // via the in-place classList.toggle patch (proving no re-render), and must survive a sibling morph.
        val app: UI < Async =
            for
                tick <- Signal.initRef(0)
                on   <- Signal.initRef(false)
            yield UI.div(
                UI.button("tick").id("ctick").onClick(tick.getAndUpdate(_ + 1).unit),
                UI.div.id("chost").cssClass("base").cssClass("hot", on)(UI.span("z").id("cchild")),
                tick.map(t => UI.span(s"t:$t").id("ctxt")),
                UI.button("toggle").id("ctoggle").onClick(on.getAndUpdate(b => !b).unit)
            )
        withUI(app) {
            for
                _ <- Browser.assertAttributeSatisfies(Selector.id("chost"), "class", "no hot initially")(c => !c.contains("hot"))
                _ <- Browser.click(Selector.id("ctoggle"))
                _ <- Browser.assertAttributeSatisfies(Selector.id("chost"), "class", "hot added in place")(_.contains("hot"))
                _ <- Browser.click(Selector.id("ctick"))
                _ <- Browser.assertText(Selector.id("ctxt"), "t:1")
                _ <- Browser.assertAttributeSatisfies(Selector.id("chost"), "class", "hot survives morph")(_.contains("hot"))
                _ <- Browser.click(Selector.id("ctoggle"))
                _ <- Browser.assertAttributeSatisfies(Selector.id("chost"), "class", "hot removed in place")(c => !c.contains("hot"))
            yield ()
        }
    }

    "keyed foreach row whose root is a reactive region patches at the row path" in {
        // A row rendered AS a Reactive (signal.map) is a fragment child whose root is itself a region: the
        // walk must subscribe it at the row path (matching where the renderer paints its anchor), not at
        // rowPath :+ "$r", or every patch targets a path the painted DOM does not have.
        val app: UI < Async =
            for
                rows   <- Signal.initRef(Chunk("a", "b"))
                status <- Signal.initRef("s0")
            yield UI.div(
                UI.ul(
                    rows.foreachKeyed(identity) { item =>
                        status.map(s => UI.li.id(s"rrow-$item")(s"$item:$s"))
                    }
                ),
                UI.button("bump").id("rbump").onClick(status.set("s1"))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("rrow-a"), "a:s0")
                _ <- Browser.assertText(Selector.id("rrow-b"), "b:s0")
                _ <- Browser.click(Selector.id("rbump"))
                _ <- Browser.assertText(Selector.id("rrow-a"), "a:s1")
                _ <- Browser.assertText(Selector.id("rrow-b"), "b:s1")
            yield ()
        }
    }

    "reactive class on a keyed foreach row root patches in place" in {
        // The row ROOT itself carries the reactive class channel. A row is a fragment child of the
        // Foreach region, so the fragment walk must promote it to a ReactiveUI node, or the channel
        // observer never starts and selecting paints nothing (silently: the setter is accepted).
        val app: UI < Async =
            for
                rows     <- Signal.initRef(Chunk("a", "b", "c"))
                selected <- Signal.initRef("")
            yield UI.div(
                UI.ul(
                    rows.foreachKeyed(identity) { item =>
                        UI.li.id(s"krow-$item").cssClass("item").cssClass("sel", selected.map(_ == item))(item)
                    }
                ),
                UI.button("select b").id("kselb").onClick(selected.set("b")),
                UI.button("deselect").id("kdesel").onClick(selected.set("")),
                UI.button("reverse").id("krev").onClick(rows.getAndUpdate(c => Chunk.from(c.toSeq.reverse)))
            )
        withUI(app) {
            for
                _ <- Browser.assertAttributeSatisfies(Selector.id("krow-b"), "class", "no sel initially")(c => !c.contains("sel"))
                _ <- Browser.click(Selector.id("kselb"))
                _ <- Browser.assertAttributeSatisfies(Selector.id("krow-b"), "class", "sel patched on the row root in place")(
                    _.contains("sel")
                )
                _ <- Browser.click(Selector.id("krev"))
                _ <- Browser.assertText(Selector.id("krow-a"), "a")
                _ <- Browser.assertAttributeSatisfies(Selector.id("krow-b"), "class", "sel survives the keyed reorder")(
                    _.contains("sel")
                )
                // Fresh observers must be re-subscribed after the list re-render: deselect patches in place.
                _ <- Browser.click(Selector.id("kdesel"))
                _ <- Browser.assertAttributeSatisfies(Selector.id("krow-b"), "class", "sel removed in place after reorder")(c =>
                    !c.contains("sel")
                )
            yield ()
        }
    }

end DomBackendTest
