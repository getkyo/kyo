package kyo

import kyo.Browser.*

/** `<fieldset>` and `<legend>` in a real browser: the accessible name the browser computes from the legend, and the disabled cascade.
  * Neither is visible in the emitted markup, which is why these run in Chrome rather than against rendered HTML.
  */
class FieldsetTest extends UITest:

    "a fieldset is a group named by its legend" in {
        withUI(UI.div(UI.fieldset(UI.legend("Local settings"), UI.input.id("i")).id("fs"))) {
            for
                _ <- Browser.assertRole(Selector.id("fs"), "group")
                _ <- Browser.assertAccessibleName(Selector.id("fs"), "Local settings")
            yield ()
        }
    }

    "a legend whose text is a signal renames the group when it changes" in {
        val app: UI < Async =
            for title <- Signal.initRef("Before")
            yield UI.div(
                UI.fieldset(UI.legend(title.map(t => UI.span(t))), UI.input.id("i")).id("fs"),
                UI.button("Rename").id("rename").onClick(title.set("After"))
            )
        withUI(app) {
            for
                _ <- Browser.assertAccessibleName(Selector.id("fs"), "Before")
                _ <- Browser.click(Selector.id("rename"))
                _ <- Browser.assertAccessibleName(Selector.id("fs"), "After")
            yield ()
        }
    }

    // Not `assertDisabled`: it reads `element.disabled`, which reflects only the control's own attribute, so an input inside a
    // disabled fieldset reports `false`. The effective state is what `:disabled` matches.
    private def disabledCount(id: String) = Selector.css(s"#$id:disabled")

    "a disabled fieldset disables the controls inside it" in {
        withUI(
            UI.div(
                UI.fieldset(UI.legend("Off"), UI.div(UI.input.id("inner"))).disabled(true).id("fs")
            )
        ) {
            // The input declares nothing and sits a level below the fieldset's own children.
            Browser.assertCount(disabledCount("inner"), 1).unit
        }
    }

    "a fieldset that is not disabled leaves its controls alone" in {
        withUI(UI.div(UI.fieldset(UI.legend("On"), UI.input.id("inner")).id("fs"))) {
            for
                _ <- Browser.assertEnabled(Selector.id("inner"))
                _ <- Browser.assertCount(disabledCount("inner"), 0)
            yield ()
        }
    }

    "the disabled cascade follows a signal" in {
        val app: UI < Async =
            for off <- Signal.initRef(true)
            yield UI.div(
                UI.fieldset(UI.legend("Group"), UI.input.id("i")).id("fs").disabled(off),
                UI.button("Enable").id("en").onClick(off.set(false))
            )
        withUI(app) {
            for
                _ <- Browser.assertCount(disabledCount("i"), 1)
                _ <- Browser.click(Selector.id("en"))
                _ <- Browser.assertCount(disabledCount("i"), 0)
            yield ()
        }
    }
end FieldsetTest
