package kyo

import kyo.Browser.*
import scala.language.implicitConversions

/** Genuine guard: a focused, caret-positioned input survives a structural re-render of the region that
  * contains it, keeping both its focus and its caret.
  *
  * The app nests a value-bound input inside an OUTER reactive region (driven by a separate signal). The
  * input is typed into (a real input event, so the bound signal holds "kyo") and the caret is placed at
  * position 2. Bumping the outer signal then re-renders the WHOLE region: the input's `data-kyo-path`
  * differs from the region's path, so the client's morph-in-place fast path (which only fires for an
  * input echoing its OWN value, where the paths match) does NOT apply. The region is therefore replaced
  * via `outerHTML`, detaching the focused input. This is the structural variant of the originating bug
  * (a reactive input losing focus and caret on each surrounding re-render).
  *
  * Without the focus capture/restore around the `outerHTML` replace, `document.activeElement` falls back
  * to `body` and the observation reads `":null"`. With the fix, the new input is re-focused with the
  * caret restored, and the observation reads `"focus-input:2"`.
  *
  * The observation uses `Browser.evalJson[String]` (a bare CDP `Runtime.evaluate`), which has no focus
  * side effect. No `focus()` or `setSelectionRange()` runs after the replace in the observation: any
  * such call would re-focus the input and mask the defect.
  */
class UITestSpaReactiveInputFocusTest extends UITest:

    "a focused input keeps focus and caret across a structural re-render of its region" in {
        val app: UI < Async =
            for
                text  <- Signal.initRef[String]("")
                outer <- Signal.initRef[Int](0)
            yield UI.div(
                outer.map(o =>
                    UI.div(
                        UI.input.value(text).id("focus-input"),
                        // Status span mirrors the outer signal: assertText on it settles the re-render.
                        UI.span(s"o=$o").id("status")
                    )
                ),
                UI.button("bump").id("bump-btn").onClick(outer.getAndUpdate(_ + 1).unit)
            )
        withUI(app) {
            for
                // Type into the input via a real input event so the bound signal holds "kyo", then place
                // the caret at position 2. This is legitimate pre-replace setup of the focused state.
                _ <- Browser.fill(Selector.id("focus-input"), "kyo")
                _ <- Browser.evalDiscard(
                    "var el = document.getElementById('focus-input'); el.focus(); el.setSelectionRange(2,2);"
                )
                // Bump the outer signal via a programmatic click that does NOT steal focus. CDP's
                // Browser.click dispatches a real mouse event that moves focus to the button; JavaScript's
                // HTMLElement.click() fires the synthetic click (triggering the kyo-ui delegated listener
                // and the onClick callback) without changing the active element.
                _ <- Browser.evalDiscard("document.getElementById('bump-btn').click()")
                // Settle on the structural re-render having applied: wait until the status span shows "o=1".
                _ <- Browser.assertText(Selector.id("status"), "o=1")
                // Read the post-replace active-element state WITHOUT any focus/setSelectionRange call.
                result <- Browser.evalJson[String](
                    "document.activeElement.id + ':' + " +
                        "(typeof document.activeElement.selectionStart === 'number' ? " +
                        "document.activeElement.selectionStart : 'null')"
                )
            yield assert(result == "focus-input:2")
        }
    }

end UITestSpaReactiveInputFocusTest
