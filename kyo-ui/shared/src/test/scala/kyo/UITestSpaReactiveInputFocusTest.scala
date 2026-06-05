package kyo

/** Regression test for reactive value-bound input focus and caret preservation on the JS DomBackend mount path.
  *
  * Drives the `runmount.reactive.input.focus` scenario registered by `kyo.internal.spa.SpaHarnessMain`: it mounts a
  * `UI.input.value(ref).id("focus-input")` via `UI.runMount`, establishes the typed pre-replace state in one shot
  * (`value="kyo"`, focus, caret at 3), then fires exactly one reactive re-render via `ref.set("kyo")`. That re-render runs
  * `DomBackend.LocalExchange.onChange`, whose `el.outerHTML = finalHtml` replace detaches the focused `<input>`. The scenario
  * then waits on DOM node identity (the new input node differs from the captured original, or the original is detached) so the
  * observation reads the post-replace state, and returns `"<activeElement.id>:<selectionStart>"`.
  *
  * The scenario discriminates the fix by construction: it never calls `setSelectionRange` or `focus` AFTER the replace
  * (Chrome's `setSelectionRange` auto-focuses an unfocused input, which would re-restore the focus the fix is responsible for
  * and mask the defect), and it settles on node identity rather than `.value` (the new node carries the same value, so a
  * value-poll would observe the pre-replace focus state).
  *
  * Pre-fix: the `outerHTML` replace destroys the focused `<input>`, `document.activeElement` falls back to `body` (whose `.id`
  * is the empty string), and `selectionStart` is not a number on `body`, so the scenario returns `":null"`.
  * Post-fix: the capture/restore guard in `LocalExchange.onChange` refocuses the new input and restores the caret, so the
  * scenario returns `"focus-input:3"`.
  *
  * Cancels on JVM and Native; the SPA harness is JS-only by construction.
  */
class UITestSpaReactiveInputFocusTest extends UITestSpa:

    "reactive value-bound input keeps focus and caret across a reactive re-render (JS mount path)" in run {
        requireJsPlatform()
        withSpa("runmount.reactive.input.focus") { call =>
            for result <- Browser.evalJson[String](call)
            yield assert(result == "focus-input:3")
        }
    }

end UITestSpaReactiveInputFocusTest
