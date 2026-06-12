package kyo

import kyo.Browser.*

class ModalScenarioItTest extends UITest:

    "click trigger shows modal" in {
        val app: UI < Async =
            for showModal <- Signal.initRef(false)
            yield UI.div(
                UI.button("Delete").id("trigger").onClick(showModal.set(true)),
                UI.when(showModal)(
                    UI.div(
                        UI.span("Are you sure?").id("msg"),
                        UI.button("Cancel").id("cancel").onClick(showModal.set(false)),
                        UI.button("Confirm").id("confirm")
                    ).id("modal")
                )
            )
        withUI(app) {
            for
                _ <- Browser.assertNotExists(Selector.id("modal"))
                _ <- Browser.click(Selector.id("trigger"))
                _ <- Browser.assertVisible(Selector.id("modal"))
                _ <- Browser.assertText(Selector.id("msg"), "Are you sure?")
            yield ()
        }
    }

    "confirm fires action" in {
        val app: UI < Async =
            for
                showModal <- Signal.initRef(false)
                confirmed <- Signal.initRef(false)
            yield UI.div(
                UI.button("Delete").id("trigger").onClick(showModal.set(true)),
                UI.when(showModal)(
                    UI.div(
                        UI.button("Confirm").id("confirm").onClick {
                            confirmed.set(true).andThen(showModal.set(false))
                        }
                    ).id("modal")
                ),
                confirmed.map(v => UI.span(s"confirmed:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("trigger"))
                _ <- Browser.click(Selector.id("confirm"))
                _ <- Browser.assertText(Selector.id("v"), "confirmed:true")
                _ <- Browser.assertNotExists(Selector.id("modal"))
            yield ()
        }
    }

    "form values preserved during modal" in {
        val app: UI < Async =
            for
                name      <- Signal.initRef("")
                showModal <- Signal.initRef(false)
            yield UI.div(
                UI.input.id("name").value(name),
                UI.button("Delete").id("trigger").onClick(showModal.set(true)),
                UI.when(showModal)(
                    UI.div(
                        UI.button("Cancel").id("cancel").onClick(showModal.set(false))
                    ).id("modal")
                ),
                name.map(v => UI.span(s"name:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("name"), "Alice")
                _ <- Browser.click(Selector.id("trigger"))
                _ <- Browser.assertText(Selector.id("v"), "name:Alice")
                _ <- Browser.click(Selector.id("cancel"))
                _ <- Browser.assertText(Selector.id("v"), "name:Alice")
            yield ()
        }
    }

    "Tab trapped in modal" in {
        // Modal contains 3 focusable buttons; one button sits outside.
        // Tab must wrap within the trap and never advance to the outside button.
        val app: UI < Async =
            for showModal <- Signal.initRef(true)
            yield UI.div(
                UI.when(showModal)(
                    UI.div(
                        UI.button("Btn1").id("btn1"),
                        UI.button("Btn2").id("btn2"),
                        UI.button("Btn3").id("btn3")
                    ).id("modal").focusTrap(true)
                ),
                UI.button("Outside").id("outside")
            )
        withUI(app) {
            for
                // Step 1: click btn1 → it is focused
                _ <- Browser.click(Selector.id("btn1"))
                _ <- Browser.assertFocused(Selector.id("btn1"))
                // Step 2: Tab → btn2 (no leak to outside)
                _ <- Browser.press(Selector.id("btn1"), Key.Tab)
                _ <- Browser.assertFocused(Selector.id("btn2"))
                // Step 3: Tab → btn3
                _ <- Browser.press(Selector.id("btn2"), Key.Tab)
                _ <- Browser.assertFocused(Selector.id("btn3"))
                // Step 4: Tab wraps back to btn1 (not outside)
                _ <- Browser.press(Selector.id("btn3"), Key.Tab)
                _ <- Browser.assertFocused(Selector.id("btn1"))
                // Step 5: Shift+Tab from btn1 wraps to btn3 (last in trap)
                _ <- Browser.press(Selector.id("btn1"), Key.Tab, KeyModifiers(shift = true))
                _ <- Browser.assertFocused(Selector.id("btn3"))
            yield ()
        }
    }

    "Escape closes modal" in {
        // Modal carries focusTrap(true) and onKeyDown that sets showModal=false on Escape.
        // The focus-trap shim lets Escape fall through to the kyo-ui onKeyDown handler.
        val app: UI < Async =
            for showModal <- Signal.initRef(true)
            yield UI.div(
                UI.when(showModal)(
                    UI.div(
                        UI.button("Close").id("close-btn")
                    ).id("modal")
                        .focusTrap(true)
                        .onKeyDown { ev =>
                            if ev.key == UI.Keyboard.Escape then showModal.set(false)
                            else ()
                        }
                )
            )
        withUI(app) {
            for
                // Step 1: modal is visible
                _ <- Browser.assertVisible(Selector.id("modal"))
                // Step 2: click the button inside modal → focused
                _ <- Browser.click(Selector.id("close-btn"))
                _ <- Browser.assertFocused(Selector.id("close-btn"))
                // Step 3: press Escape → keydown delivered to onKeyDown → showModal=false → modal gone
                _ <- Browser.press(Selector.id("close-btn"), Key.Escape)
                // Step 4: modal is no longer in the DOM
                _ <- Browser.assertNotExists(Selector.id("modal"))
            yield ()
        }
    }

end ModalScenarioItTest
