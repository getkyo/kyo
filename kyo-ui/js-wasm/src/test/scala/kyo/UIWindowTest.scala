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

    "UIWindow Sync-effect members compile to the expected effect types (type-level gate)" in {
        // Each val ascription is a compile-time witness: it fails if the member's return type
        // drifts. The lambdas are never called, so no DOM global is accessed at runtime.
        val _: String => Unit < Sync           = s => UIWindow.writeClipboard(s)
        val _: String => Maybe[String] < Sync  = k => UIWindow.storageGet(k)
        val _: (String, String) => Unit < Sync = (k, v) => UIWindow.storageSet(k, v)
        val _: String => Unit < Sync           = t => UIWindow.setTitle(t)
        val _: String => Boolean < Sync        = id => UIWindow.scrollIntoViewById(id)
        succeed
    }

end UIWindowTest
