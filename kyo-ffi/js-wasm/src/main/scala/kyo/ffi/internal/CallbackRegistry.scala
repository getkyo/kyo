package kyo.ffi.internal

import scala.scalajs.js

/** Internal seam between [[JsGuard]] and [[KoffiFacade]] for callback unregistration.
  *
  * Why this exists: the kyo-ffi JS test environment does NOT have the koffi npm package installed. Any `@JSImport("koffi", ...)` call is
  * reachability-tracked by the Scala.js linker: if [[JsGuard.close]] directly called [[KoffiFacade.unregister]], every reachable test that
  * exercises `close()` (including the shared [[kyo.ffi.GuardSpec]]) would transitively attempt to link the koffi module and fail.
  *
  * The indirection through `var unregister` breaks that reachability chain, the linker sees the initial value of the var (a no-op lambda)
  * and can prove that only the mutator is reached by non-koffi users. Real-world (end-user) deployments replace the mutator via
  * [[CallbackRegistry.installKoffi]]; the [[KoffiFacade.load]] call does this as part of its static block so generated impl companions
  * inherit the wiring automatically. Tests may install a counter-backed mock via [[CallbackRegistry.setUnregister]] directly.
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
