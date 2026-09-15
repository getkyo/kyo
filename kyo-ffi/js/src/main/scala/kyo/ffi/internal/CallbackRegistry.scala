package kyo.ffi.internal

import scala.scalajs.js

/** Internal seam between [[JsGuard]] and [[KoffiFacade]] for callback unregistration.
  *
  * Why this exists: a guard whose native transport never loaded koffi, a koffi-less JS/Wasm host that degraded to the Node floor, or a test
  * that never opened a native socket (including the shared [[kyo.ffi.GuardSpec]]), must still `close()` cheaply, without resolving koffi.
  * If [[JsGuard.close]] called [[KoffiFacade.unregister]] directly, every `close()` would force koffi to be resolved even when no koffi
  * callback was ever retained.
  *
  * The indirection through `var unregister` keeps the default a no-op lambda, so a koffi-less `close()` stays a no-op. Only once
  * [[KoffiFacade.load]] actually resolves koffi (koffi is loaded DYNAMICALLY on first use, not via a static `@JSImport`, so this is a runtime
  * concern, not the link-time reachability it originally guarded) does it swap in the real koffi-backed unregister via
  * [[CallbackRegistry.installKoffi]], so generated impl companions inherit the wiring automatically. Tests may install a counter-backed mock
  * via [[CallbackRegistry.setUnregister]] directly.
  *
  * No synchronization: Scala.js is single-threaded.
  */
private[ffi] object CallbackRegistry:

    private var _unregister: js.Any => Unit = (_: js.Any) => ()

    /** Invoke the currently-installed unregister hook against `handle`. Exceptions propagate to the caller. */
    def unregister(handle: js.Any): Unit = _unregister(handle)

    /** Install a custom unregister hook, overrides the default no-op. The previous hook is returned so callers may chain or restore. */
    def setUnregister(fn: js.Any => Unit): js.Any => Unit =
        val prev = _unregister
        _unregister = fn
        prev
    end setUnregister

    /** Install the real koffi-backed unregister hook. Called from [[KoffiFacade.load]]'s static block; idempotent, re-installing the real
      * hook over itself is a no-op at the observable level.
      */
    def installKoffi(): Unit =
        _unregister = (h: js.Any) => KoffiFacade.unregister(h)
end CallbackRegistry
