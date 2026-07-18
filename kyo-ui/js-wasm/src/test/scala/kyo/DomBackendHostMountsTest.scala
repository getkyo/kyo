package kyo

import kyo.internal.DomBackend

/** Headless guard for `DomBackend.fireHostMounts`'s "no skipped mount is silent" promise.
  *
  * A backend node that sits inside a reactive region is deliberately NOT mounted, because a re-render may
  * replace the subtree, and the scaladoc promises that skip is reported rather than silent. This runs under
  * NodeJSEnv with no `document`: the under-reactive arm warns before any `querySelector`, so it is the one
  * skipped-mount arm reachable from a headless mount. The no-element-at-path and unregistered-backend arms
  * both resolve an element through `document.querySelector` first, so they need a real browser and are
  * covered where a DOM exists, not here. The end-to-end DomBackend behaviour lives in the browser-driven
  * `DomBackendTest`; this file is the headless aspect that suite cannot reach.
  */
class DomBackendHostMountsTest extends kyo.test.Test[Any]:

    "a backend node inside a reactive region is reported skipped, not silently left unmounted" in {
        // FakeBackendNode carries real mount work (a `canvas` placeholder), so without the reactive wrapper
        // it would be fired. Wrapping it in a signal-driven region is what makes it a skipped mount, and the
        // warning is the only trace that skip leaves; a silent skip reads exactly like a page with nothing
        // to mount. The under-reactive arm fires before touching the DOM, so this holds under NodeJSEnv.
        val log = new CapturingLog(Log.Level.warn)
        Log.let(Log(log)) {
            Scope.run {
                for
                    gate <- Signal.initRef(true)
                    node = FakeBackendNode()
                    ui   = UI.div(gate.map(_ => node: UI))
                    _ <- DomBackend.fireHostMounts(ui, Seq.empty)
                yield
                    assert(log.warnings.size == 1, "exactly one skipped-mount warning for the node under a reactive region")
                    assert(log.warnings.head.contains("reactive region"))
                    assert(log.warnings.head.contains("canvas"), "the warning names the node by its placeholder tag")
                end for
            }
        }
    }

end DomBackendHostMountsTest
