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

    "UILocation Sync-effect members compile to the expected effect types (type-level gate)" in {
        // Each val ascription is a compile-time witness: it fails if the member's return type
        // drifts. The lambdas are never called, so no DOM global is accessed at runtime.
        val _: String => Unit < Sync = uri => UILocation.assign(uri)
        val _: String => Unit < Sync = uri => UILocation.push(uri)
        val _: String => Unit < Sync = uri => UILocation.replace(uri)
        val _: Unit < Sync           = UILocation.back
        val _: Unit < Sync           = UILocation.forward
        val _: Int => Unit < Sync    = n => UILocation.go(n)
        succeed
    }

end UILocationTest
