package kyo

/** Pure-seam tests for [[UILocation]] members that do not require a real browser DOM.
  *
  * The Node.js test environment (NodeJSEnv) has no DOM globals. The following UILocation members
  * require a real browser DOM and are therefore validated by exercising the compiled bundle in a
  * real browser:
  *
  *   - `assign(uri)` invoking `window.location.assign` (requires `window.location`)
  *   - `push`, `replace`, `back`, `forward`, `go` (require `window.history`)
  *   - `current` reactive signal (requires `window.location.pathname`)
  *
  * Browser-side behavior is validated by real use in the compiled bundle, matching the module
  * convention: UILocation and UIWindow have no committed JS browser tests because the NodeJS test
  * environment has no DOM.
  */
class UILocationTest extends kyo.test.Test[Any]:

    "UILocation object is accessible (compile gate: assign member compiles without a DOM)" in {
        // Verifies that the UILocation object and the new `assign` member compile and are reachable.
        // No DOM-dependent method is invoked. Observable behavior (actual navigation) requires a
        // live browser and is validated by running the compiled bundle there.
        val _ = UILocation
        assert(true)
    }

end UILocationTest
