package kyo.ffi

import kyo.discard
import kyo.ffi.internal.CallbackRegistry
import kyo.ffi.internal.JsGuard
import scala.scalajs.js as sjs

/** JS-only tests for [[JsGuard]] callback unregister dispatch on close.
  *
  * The basic retain/close lifecycle tests (appends, stacks, clears on close, no-op after close) are in the shared `GuardRetainTest`. This
  * file covers JS-specific behavior: close() dispatching `CallbackRegistry.unregister` per retained handle.
  */
class JsGuardRetainTest extends Test:

    // Touches process-global state (global stderr/system property, or the shared CallbackRegistry pool/hooks) and so
    // must run alone: under the default parallel leaf execution a sibling leaf observes or mutates the same global.
    override def config = super.config.sequential

    /** Dummy koffi handle. The real runtime value returned by `KoffiFacade.register` is a `sjs.Dynamic`, but [[JsGuard.unsafeRetainJs]]
      * accepts any `sjs.Any` and stores it as `AnyRef`, so a plain `sjs.Object` suffices for structural tests.
      */
    private def fakeHandle(): sjs.Any = new sjs.Object()

    "close() dispatches one CallbackRegistry.unregister per retained handle" - {
        "counter-backed mock observes one call per retained handle, in LIFO order" in {
            // Install a counter mock; the default installed by KoffiFacade.load goes through koffi and is not available here, so we
            // overwrite unconditionally and restore on exit.
            val seen = scala.collection.mutable.ArrayBuffer.empty[sjs.Any]
            val prev = CallbackRegistry.setUnregister { (h: sjs.Any) =>
                seen += h
            }
            try
                val g   = Ffi.Guard.open()
                val jsG = g.asInstanceOf[JsGuard]
                val h1  = fakeHandle()
                val h2  = fakeHandle()
                val h3  = fakeHandle()
                jsG.unsafeRetainJs(h1)
                jsG.unsafeRetainJs(h2)
                jsG.unsafeRetainJs(h3)
                g.close()
                assert(seen.size == 3)
                // LIFO: the last retained handle is unregistered first. Compare by reference identity since `js.Any` has no
                // derived `CanEqual` for structural equality under strict equality.
                assert((seen(0) eq h3) == true)
                assert((seen(1) eq h2) == true)
                assert((seen(2) eq h1) == true)
                assert(g.retainedCount == 0)
            finally
                discard(CallbackRegistry.setUnregister(prev))
            end try
        }

        "a thrown unregister is swallowed, other retained handles still get their call" in {
            var callIndex = 0
            val seen      = scala.collection.mutable.ArrayBuffer.empty[Int]
            val prev = CallbackRegistry.setUnregister { (_: sjs.Any) =>
                val idx = callIndex
                callIndex += 1
                if idx == 1 then throw new RuntimeException("boom")
                else seen += idx
            }
            try
                val g   = Ffi.Guard.open()
                val jsG = g.asInstanceOf[JsGuard]
                jsG.unsafeRetainJs(fakeHandle())
                jsG.unsafeRetainJs(fakeHandle())
                jsG.unsafeRetainJs(fakeHandle())
                g.close()
                // All three handles dispatched; the thrown one leaves its slot out of `seen`.
                assert(callIndex == 3)
                assert(seen.toList == List(0, 2))
                assert(g.retainedCount == 0)
            finally
                discard(CallbackRegistry.setUnregister(prev))
            end try
        }
    }
end JsGuardRetainTest
