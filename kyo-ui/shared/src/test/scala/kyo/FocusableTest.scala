package kyo

import kyo.Browser.*
import kyo.UI.*
import kyo.UI.Ast.*
import kyo.UI.foreach

class FocusableTest extends UITest:

    "three buttons focus each" in {
        withUI(UI.div(
            UI.button("A").id("a"),
            UI.button("B").id("b"),
            UI.button("C").id("c")
        )) {
            for
                _ <- Browser.click(Selector.id("a"))
                _ <- Browser.assertVisible(Selector.id("a"))
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertVisible(Selector.id("b"))
                _ <- Browser.click(Selector.id("c"))
                _ <- Browser.assertVisible(Selector.id("c"))
            yield ()
        }
    }

    "button input button cycle" in {
        withUI(UI.div(
            UI.button("B1").id("b1"),
            UI.input.id("i"),
            UI.button("B2").id("b2")
        )) {
            for
                _ <- Browser.click(Selector.id("b1"))
                _ <- Browser.click(Selector.id("i"))
                _ <- Browser.click(Selector.id("b2"))
                _ <- Browser.assertVisible(Selector.id("b2"))
            yield ()
        }
    }

    "tabIndex 0 makes div focusable" in {
        withUI(UI.div(UI.div("X").tabIndex(0).id("d"))) {
            for
                _ <- Browser.assertText(Selector.id("d"), "X")
                _ <- Browser.click(Selector.id("d"))
                _ <- Browser.assertVisible(Selector.id("d"))
            yield ()
        }
    }

    "tabIndex attribute" in {
        withUI(UI.div(UI.div.tabIndex(0).id("d"))) {
            Browser.assertAttribute(Selector.id("d"), "tabindex", "0").unit
        }
    }

    "tabIndex -1 attribute" in {
        withUI(UI.div(UI.div.tabIndex(-1).id("d"))) {
            Browser.assertAttribute(Selector.id("d"), "tabindex", "-1").unit
        }
    }

    "focus non-existent retries" in {
        val app: UI < Async =
            for show <- Signal.initRef(false)
            yield UI.div(
                UI.button("Show").id("s").onClick(show.set(true)),
                UI.when(show)(UI.button("Target").id("t"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("s"))
                _ <- Browser.click(Selector.id("t"))
                _ <- Browser.assertVisible(Selector.id("t"))
            yield ()
        }
    }

    "mixed focusable types" in {
        withUI(UI.div(
            UI.button("Btn").id("btn"),
            UI.input.id("inp"),
            UI.checkbox.id("cb"),
            UI.select.id("sel")(UI.option.value("a")("A")),
            UI.a.href(Href.Fragment("")).id("anc")("Link")
        )) {
            for
                _ <- Browser.click(Selector.id("btn"))
                _ <- Browser.click(Selector.id("inp"))
                _ <- Browser.click(Selector.id("cb"))
                _ <- Browser.click(Selector.id("sel"))
                _ <- Browser.click(Selector.id("anc"))
                _ <- Browser.assertVisible(Selector.id("anc"))
            yield ()
        }
    }

    "focus after element removal" in {
        val app: UI < Async =
            for show <- Signal.initRef(true)
            yield UI.div(
                UI.button("Remove").id("r").onClick(show.set(false)),
                UI.when(show)(UI.button("Target").id("t"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("t"))
                _ <- Browser.click(Selector.id("r"))
                _ <- Browser.assertNotExists(Selector.id("t"))
            yield ()
        }
    }

    "two forms focus fields independently" in {
        withUI(UI.div(
            UI.form.id("f1")(UI.input.id("i1"), UI.button("S1").id("s1")),
            UI.form.id("f2")(UI.input.id("i2"), UI.button("S2").id("s2"))
        )) {
            for
                _ <- Browser.click(Selector.id("i1"))
                _ <- Browser.click(Selector.id("i2"))
                _ <- Browser.click(Selector.id("s1"))
                _ <- Browser.assertVisible(Selector.id("s1"))
            yield ()
        }
    }

    "focus then blur via different element" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("a").onBlur(ref.set("blurred-a")),
                UI.input.id("b"),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("a"))
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("v"), "blurred-a")
            yield ()
        }
    }

    // ---- TabNavigation ----

    "Tab on first of 3 buttons moves focus to second" in {
        withUI(UI.div(UI.button("A").id("a"), UI.button("B").id("b"), UI.button("C").id("c"))) {
            for
                _ <- Browser.click(Selector.id("a"))
                _ <- Browser.assertFocused(Selector.id("a"))
                _ <- Browser.press(Selector.id("a"), Key.Tab)
                _ <- Browser.assertFocused(Selector.id("b"))
            yield ()
        }
    }

    "Tab from last button wraps to first" in {
        withUI(UI.div(UI.button("A").id("a"), UI.button("B").id("b"), UI.button("C").id("c"))) {
            for
                _ <- Browser.click(Selector.id("c"))
                _ <- Browser.press(Selector.id("c"), Key.Tab)
                _ <- Browser.assertFocused(Selector.id("a"))
            yield ()
        }
    }

    "Shift+Tab moves focus backward" in {
        withUI(UI.div(UI.button("A").id("a"), UI.button("B").id("b"), UI.button("C").id("c"))) {
            for
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.press(Selector.id("b"), Key.Tab, KeyModifiers(shift = true))
                _ <- Browser.assertFocused(Selector.id("a"))
            yield ()
        }
    }

    "Tab skips disabled elements" in {
        withUI(UI.div(
            UI.button("A").id("a"),
            UI.button("B").id("b").disabled(true),
            UI.button("C").id("c")
        )) {
            for
                _ <- Browser.click(Selector.id("a"))
                _ <- Browser.press(Selector.id("a"), Key.Tab)
                _ <- Browser.assertFocused(Selector.id("c"))
            yield ()
        }
    }

    "Tab through mixed elements button input checkbox select" in {
        withUI(UI.div(
            UI.button("Btn").id("b"),
            UI.input.id("i"),
            UI.checkbox.id("c"),
            UI.select(UI.option("A").value("a")).id("s")
        )) {
            for
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.press(Selector.id("b"), Key.Tab)
                _ <- Browser.assertFocused(Selector.id("i"))
                _ <- Browser.press(Selector.id("i"), Key.Tab)
                _ <- Browser.assertFocused(Selector.id("c"))
                _ <- Browser.press(Selector.id("c"), Key.Tab)
                _ <- Browser.assertFocused(Selector.id("s"))
            yield ()
        }
    }

    "Tab into foreach items each focusable item reachable" in {
        val app: UI < Async =
            for items <- Signal.initRef(Chunk.from(Seq("a", "b", "c")))
            yield UI.div(items.foreach(s => UI.button(s).id(s"btn-$s")))
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("btn-a"))
                _ <- Browser.press(Selector.id("btn-a"), Key.Tab)
                _ <- Browser.assertFocused(Selector.id("btn-b"))
                _ <- Browser.press(Selector.id("btn-b"), Key.Tab)
                _ <- Browser.assertFocused(Selector.id("btn-c"))
            yield ()
        }
    }

    "Tab order matches DOM order" in {
        withUI(UI.div(
            UI.input.id("first"),
            UI.input.id("second"),
            UI.input.id("third")
        )) {
            for
                _ <- Browser.click(Selector.id("first"))
                _ <- Browser.press(Selector.id("first"), Key.Tab)
                _ <- Browser.assertFocused(Selector.id("second"))
                _ <- Browser.press(Selector.id("second"), Key.Tab)
                _ <- Browser.assertFocused(Selector.id("third"))
            yield ()
        }
    }

    "Single focusable element Tab stays on it" in {
        withUI(UI.div(UI.button("Only").id("only"))) {
            for
                _ <- Browser.click(Selector.id("only"))
                _ <- Browser.press(Selector.id("only"), Key.Tab)
                _ <- Browser.assertFocused(Selector.id("only"))
            yield ()
        }
    }

    "No focusable elements Tab does nothing" in {
        withUI(UI.div(UI.span("text").id("s"))) {
            for
                _ <- Browser.press(Selector.id("s"), Key.Tab)
                _ <- Browser.assertVisible(Selector.id("s"))
            yield ()
        }
    }

    "Tab from input to button fires onBlur on input and onFocus on button".flaky in {
        val app: UI < Async =
            for log <- Signal.initRef(Chunk.empty[String])
            yield UI.div(
                UI.input.id("i")
                    .onFocus(log.getAndUpdate(_.appended("focus:i")).unit)
                    .onBlur(log.getAndUpdate(_.appended("blur:i")).unit),
                UI.button("B").id("b")
                    .onFocus(log.getAndUpdate(_.appended("focus:b")).unit),
                log.map(es => UI.span(es.toSeq.mkString(",")).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("i"))
                _ <- Browser.press(Selector.id("i"), Key.Tab)
                _ <- Browser.assertText(Selector.id("v"), "focus:i,blur:i,focus:b")
            yield ()
        }
    }

    // ---- TabNavigationEdgeCase ----

    "Tab forward through 3 inputs" in {
        withUI(UI.div(UI.input.id("a"), UI.input.id("b"), UI.input.id("c"))) {
            for
                _ <- Browser.click(Selector.id("a"))
                _ <- Browser.press(Selector.id("a"), Key.Tab)
                _ <- Browser.assertFocused(Selector.id("b"))
                _ <- Browser.press(Selector.id("b"), Key.Tab)
                _ <- Browser.assertFocused(Selector.id("c"))
            yield ()
        }
    }

    "Tab from last wraps to first" in {
        withUI(UI.div(UI.input.id("a"), UI.input.id("b"))) {
            for
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.press(Selector.id("b"), Key.Tab)
                _ <- Browser.assertFocused(Selector.id("a"))
            yield ()
        }
    }

    "Shift Tab from first wraps to last" in {
        withUI(UI.div(UI.input.id("a"), UI.input.id("b"))) {
            for
                _ <- Browser.click(Selector.id("a"))
                _ <- Browser.press(Selector.id("a"), Key.Tab, KeyModifiers(shift = true))
                _ <- Browser.assertFocused(Selector.id("b"))
            yield ()
        }
    }

    "Tab skips disabled inputs" in {
        withUI(UI.div(
            UI.input.id("a"),
            UI.input.id("b").disabled(true),
            UI.input.id("c")
        )) {
            for
                _ <- Browser.click(Selector.id("a"))
                _ <- Browser.press(Selector.id("a"), Key.Tab)
                _ <- Browser.assertFocused(Selector.id("c"))
            yield ()
        }
    }

    "Tab skips elements inside UI when false" in {
        val app: UI < Async =
            for show <- Signal.initRef(false)
            yield UI.div(
                UI.input.id("a"),
                UI.when(show)(UI.input.id("hidden")),
                UI.input.id("c")
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("a"))
                _ <- Browser.press(Selector.id("a"), Key.Tab)
                _ <- Browser.assertFocused(Selector.id("c"))
            yield ()
        }
    }

    "Tab into dynamically added element" in {
        val app: UI < Async =
            for show <- Signal.initRef(false)
            yield UI.div(
                UI.input.id("a"),
                UI.button("Show").id("toggle").onClick(show.set(true)),
                UI.when(show)(UI.input.id("dyn"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("toggle"))
                _ <- Browser.click(Selector.id("a"))
                _ <- Browser.press(Selector.id("a"), Key.Tab)
                _ <- Browser.assertFocused(Selector.id("toggle"))
                _ <- Browser.press(Selector.id("toggle"), Key.Tab)
                _ <- Browser.assertFocused(Selector.id("dyn"))
            yield ()
        }
    }

    "Tab order after element removal" in {
        val app: UI < Async =
            for show <- Signal.initRef(true)
            yield UI.div(
                UI.input.id("a"),
                UI.button("Hide").id("hide").onClick(show.set(false)),
                UI.when(show)(UI.input.id("middle")),
                UI.input.id("c")
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("hide"))
                _ <- Browser.click(Selector.id("a"))
                _ <- Browser.press(Selector.id("a"), Key.Tab)
                _ <- Browser.assertFocused(Selector.id("hide"))
                _ <- Browser.press(Selector.id("hide"), Key.Tab)
                _ <- Browser.assertFocused(Selector.id("c"))
            yield ()
        }
    }

    "all focusable elements removed Tab no crash" in {
        withUI(UI.div("nothing").id("d")) {
            for
                _ <- Browser.press(Selector.id("d"), Key.Tab)
                _ <- Browser.assertVisible(Selector.id("d"))
            yield ()
        }
    }

    "only one focusable Tab stays on it" in {
        withUI(UI.div(UI.input.id("only"))) {
            for
                _ <- Browser.click(Selector.id("only"))
                _ <- Browser.press(Selector.id("only"), Key.Tab)
                _ <- Browser.assertFocused(Selector.id("only"))
            yield ()
        }
    }

    "Tab through mixed types input button checkbox select radio" in {
        withUI(UI.div(
            UI.input.id("inp"),
            UI.button("Btn").id("btn"),
            UI.checkbox.id("chk"),
            UI.select(UI.option("A").value("a")).id("sel"),
            UI.radio.id("rad")
        )) {
            for
                _ <- Browser.click(Selector.id("inp"))
                _ <- Browser.press(Selector.id("inp"), Key.Tab)
                _ <- Browser.assertFocused(Selector.id("btn"))
                _ <- Browser.press(Selector.id("btn"), Key.Tab)
                _ <- Browser.assertFocused(Selector.id("chk"))
                _ <- Browser.press(Selector.id("chk"), Key.Tab)
                _ <- Browser.assertFocused(Selector.id("sel"))
                _ <- Browser.press(Selector.id("sel"), Key.Tab)
                _ <- Browser.assertFocused(Selector.id("rad"))
            yield ()
        }
    }

    "Tab dispatches Blur on old and Focus on new".flaky in {
        val app: UI < Async =
            for log <- Signal.initRef(Chunk.empty[String])
            yield UI.div(
                UI.input.id("a")
                    .onFocus(log.getAndUpdate(_.appended("focus:a")).unit)
                    .onBlur(log.getAndUpdate(_.appended("blur:a")).unit),
                UI.input.id("b")
                    .onFocus(log.getAndUpdate(_.appended("focus:b")).unit),
                log.map(es => UI.span(es.toSeq.mkString(",")).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("a"))
                _ <- Browser.press(Selector.id("a"), Key.Tab)
                _ <- Browser.assertText(Selector.id("v"), "focus:a,blur:a,focus:b")
            yield ()
        }
    }

    "Tab between two forms" in {
        withUI(UI.div(
            UI.form.id("f1")(UI.input.id("a"), UI.input.id("b")),
            UI.form.id("f2")(UI.input.id("c"))
        )) {
            for
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.press(Selector.id("b"), Key.Tab)
                _ <- Browser.assertFocused(Selector.id("c"))
            yield ()
        }
    }

    "Tab into foreach items each reachable" in {
        val app: UI < Async =
            for items <- Signal.initRef(Chunk.from(Seq("a", "b", "c")))
            yield UI.div(items.foreach(s => UI.input.id(s"i-$s")))
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("i-a"))
                _ <- Browser.press(Selector.id("i-a"), Key.Tab)
                _ <- Browser.assertFocused(Selector.id("i-b"))
                _ <- Browser.press(Selector.id("i-b"), Key.Tab)
                _ <- Browser.assertFocused(Selector.id("i-c"))
            yield ()
        }
    }

    "Tab in empty UI no crash" in {
        withUI(UI.div("text").id("d")) {
            for
                _ <- Browser.press(Selector.id("d"), Key.Tab)
                _ <- Browser.assertVisible(Selector.id("d"))
            yield ()
        }
    }

    "tabIndex 1 vs tabIndex 2 ordering" in {
        withUI(UI.div(
            UI.input.id("third").tabIndex(3),
            UI.input.id("first").tabIndex(1),
            UI.input.id("second").tabIndex(2)
        )) {
            for
                _ <- Browser.click(Selector.id("first"))
                _ <- Browser.press(Selector.id("first"), Key.Tab)
                _ <- Browser.assertFocused(Selector.id("second"))
                _ <- Browser.press(Selector.id("second"), Key.Tab)
                _ <- Browser.assertFocused(Selector.id("third"))
            yield ()
        }
    }

    "tabIndex -1 skipped but programmatically focusable" in {
        withUI(UI.div(
            UI.input.id("a"),
            UI.input.id("skip").tabIndex(-1),
            UI.input.id("c")
        )) {
            for
                _ <- Browser.click(Selector.id("a"))
                _ <- Browser.press(Selector.id("a"), Key.Tab)
                _ <- Browser.assertFocused(Selector.id("c"))
                _ <- Browser.click(Selector.id("skip"))
                _ <- Browser.assertFocused(Selector.id("skip"))
            yield ()
        }
    }

    // focusAuto/focusRestore over the server-push transport in real Chrome. The DomBackend SPA mirror has
    // no browser harness (Node test env has no DOM); it is kept in sync by construction.
    "focusAuto and focusRestore emit their data attributes; absent by default" in {
        val app: UI < Async = UI.div(
            UI.div("seed").tabIndex(-1).focusAuto(true).focusRestore(true).id("seeded"),
            UI.div("plain").id("plain")
        )
        withUI(app) {
            for
                _ <- Browser.assertAttribute(Selector.id("seeded"), "data-kyo-focus-auto", "1")
                _ <- Browser.assertAttribute(Selector.id("seeded"), "data-kyo-focus-restore", "1")
                _ <- Browser.assertNoAttribute(Selector.id("plain"), "data-kyo-focus-auto")
                _ <- Browser.assertNoAttribute(Selector.id("plain"), "data-kyo-focus-restore")
            yield ()
        }
    }

    "opening a panel seeds focus into the focus-auto element (winning over restore-to-trigger)" in {
        val app: UI < Async =
            for showPanel <- Signal.initRef(false)
            yield UI.div(
                UI.button("Open").id("trigger").onClick(showPanel.set(true)),
                UI.when(showPanel)(
                    UI.div(
                        UI.button("Inside").id("inside")
                    ).id("panel").tabIndex(-1).focusAuto(true)
                )
            )
        withUI(app) {
            for
                // The trigger is focused when the panel appears; without seeding, focus restore would keep it there.
                _ <- Browser.click(Selector.id("trigger"))
                _ <- Browser.assertVisible(Selector.id("panel"))
                _ <- Browser.assertFocused(Selector.id("panel"))
            yield ()
        }
    }

    "a re-render of an already-open focus-auto element does not steal the user's focus" in {
        // The focus-auto panel is seeded on initial load. Bumping re-renders the whole region with
        // identical paths: the panel must NOT re-seed, and focus restore must keep focus on the inner button.
        val app: UI < Async =
            for n <- Signal.initRef(0)
            yield UI.div(
                n.map(i =>
                    UI.div(
                        UI.button(s"Inner").id("inner"),
                        UI.div("seed").tabIndex(-1).focusAuto(true).id("panel"),
                        UI.span(s"n=$i").id("status")
                    )
                ),
                UI.button("Bump").id("bump").onClick(n.getAndUpdate(_ + 1).unit)
            )
        withUI(app) {
            for
                _ <- Browser.assertFocused(Selector.id("panel"))
                _ <- Browser.click(Selector.id("inner"))
                _ <- Browser.assertFocused(Selector.id("inner"))
                // Synthetic JS click so the click itself does not move focus; the region re-renders with the same paths.
                _ <- Browser.evalDiscard("document.getElementById('bump').click()")
                _ <- Browser.assertText(Selector.id("status"), "n=1")
                // No re-seed: focus stays on the inner button.
                _ <- Browser.assertFocused(Selector.id("inner"))
                _ <- Browser.assertNotFocused(Selector.id("panel"))
            yield ()
        }
    }

    "closing a focus-restore panel returns focus to the previously focused element" in {
        val app: UI < Async =
            for showPanel <- Signal.initRef(false)
            yield UI.div(
                UI.button("Open").id("trigger").onClick(showPanel.set(true)),
                UI.when(showPanel)(
                    UI.div(
                        UI.button("Close").id("close").onClick(showPanel.set(false))
                    ).id("panel").tabIndex(-1).focusAuto(true).focusRestore(true)
                )
            )
        withUI(app) {
            for
                // Opening seeds focus into the panel and records the trigger as the return target.
                _ <- Browser.click(Selector.id("trigger"))
                _ <- Browser.assertFocused(Selector.id("panel"))
                // Closing removes the seeded element, so the sweep restores focus to the trigger.
                _ <- Browser.click(Selector.id("close"))
                _ <- Browser.assertNotExists(Selector.id("panel"))
                _ <- Browser.assertFocused(Selector.id("trigger"))
            yield ()
        }
    }

end FocusableTest
