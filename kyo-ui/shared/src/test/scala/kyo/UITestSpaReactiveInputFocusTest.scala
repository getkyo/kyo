package kyo

/** Regression test for reactive value-bound input focus and caret preservation on the JS DomBackend mount path.
  *
  * Drives the `runmount.reactive.input.focus` scenario registered by `kyo.internal.spa.SpaHarnessMain`: it mounts a
  * `UI.input.value(ref).id("focus-input")` via `UI.runMount`, focuses the element, then drives three successive keystrokes
  * ("k", "ky", "kyo") by writing `.value` + dispatching a real `input` event mirrored into the `SignalRef`. After each
  * keystroke the reactive region re-renders via `DomBackend.LocalExchange.onChange`; the scenario returns
  * `"<activeElement.id>:<selectionStart>"`.
  *
  * Pre-fix: every `outerHTML` replace destroys the focused `<input>`, `document.activeElement` falls back to `body`, and
  * `selectionStart` is `null` on `body`, so the scenario returns `"body:null"`.
  * Post-fix: the capture/restore guard in `LocalExchange.onChange` refocuses the input and restores the caret, so the
  * scenario returns `"focus-input:3"`.
  *
  * Cancels on JVM and Native; the SPA harness is JS-only by construction.
  */
class UITestSpaReactiveInputFocusTest extends UITestSpa:

    "reactive value-bound input keeps focus and caret across keystrokes (JS mount path)" in run {
        requireJsPlatform()
        withSpa("runmount.reactive.input.focus") { call =>
            for result <- Browser.evalJson[String](call)
            yield assert(result == "focus-input:3")
        }
    }

end UITestSpaReactiveInputFocusTest
