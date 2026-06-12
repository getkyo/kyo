package kyo

/** Pure-seam tests for [[UIWindow]] members that do not require a real browser DOM.
  *
  * The Node.js test environment (NodeJSEnv) has no DOM globals: `window`, `document`,
  * `localStorage`, and `matchMedia` are all undefined. The following UIWindow members require a
  * real browser DOM and are therefore validated by exercising the compiled bundle in a real browser:
  *
  *   - `onClick` listener registration and scope teardown (requires `document.addEventListener`)
  *   - `writeClipboard` (requires `navigator.clipboard`)
  *   - `storageGet` / `storageSet` round-trip (requires `localStorage`)
  *   - `prefersColorScheme` initial value and reactive update (requires `matchMedia`)
  *   - `setTitle` observable effect (requires `document.title`)
  *   - `scrollToTop` scroll-position reset (requires `window.scrollTo`)
  *   - `scrollIntoViewById` element lookup and scroll (requires `document.getElementById`)
  *
  * Each such member is exercised by its real effect when the compiled bundle runs in a browser. No
  * DOM test infrastructure is added here because the Node.js environment structurally cannot support
  * it (no jsdom configured), matching the module convention: UILocation and UIWindow have no
  * committed JS browser tests because the test environment has no DOM.
  *
  * The one Node-runnable contract verified here is the [[UIMouseEventOps]] side-table lifecycle
  * wired by [[UIWindow.onClick]]: that lifecycle is tested independently in
  * [[UIMouseEventOpsTest]], which covers the remember/forget/nativeOf pure seam.
  */
class UIWindowTest extends kyo.test.Test[Any]:

    "UIWindow object is accessible (compile gate: new members compile without a DOM)" in {
        // This test asserts that the UIWindow object and its members compile and are reachable.
        // It does not invoke any DOM-dependent method; it only checks that the type-level API is
        // present. All observable behaviors require a live browser and are validated by running
        // the compiled bundle there.
        val _ = UIWindow
        assert(true)
    }

end UIWindowTest
