package kyo.ffi.internal

import kyo.*
import kyo.discard
import kyo.ffi.Ffi
import kyo.ffi.FfiLoadError
import kyo.ffi.Test
import scala.scalajs.js as sjs

/** Validates the browser gate.
  *
  * [[NativeLoader.detectBrowser]] returns true when the host is not Node-like, which includes any host without a `process` global. On that
  * outcome:
  *
  *   - [[NativeLoader.load]] throws [[FfiLoadError.Unsupported]] directly.
  *   - `Ffi.load[T]` throws [[FfiLoadError.Unsupported]] before it constructs the impl, and before it reports a missing one.
  *
  * On the Node row the test simulates a browser by temporarily deleting `process` and `require` from `sjs.Dynamic.global`, runs the checks,
  * then restores the originals so subsequent specs see a normal Node environment. The leaves that assert the Node side of the gate carry
  * `.notBrowser`, since a page cannot restore what it never had.
  *
  * The `.onlyBrowser` group at the end asserts the same gate on the browser row with nothing deleted, so its subject is the page rather
  * than a fabricated global. That is the only place the claim about the impl's companion can be made honestly: what a deleted `process`
  * does to a companion initializer is not what a page does to it.
  */
class BrowserDetectionTest extends Test:

    // Every leaf mutates process-global `process`/`require` and relies on the per-leaf save/restore in `aroundLeaf` to
    // see the baseline globals. Under the default parallel leaf pool those mutations interleave across concurrent leaves:
    // one leaf's `deleteGlobal("require")` removes the global while another leaf is mid-body, so the latter observes a
    // global that was deleted out from under it (surfacing as `ReferenceError: require is not defined` from the CommonJS
    // bare-`require` emitted by Scala.js, or as a wrong `detectBrowser()` result). Run this suite's leaves sequentially so
    // the save/delete/restore cycle is atomic with respect to other leaves; this is isolation, not a weakened assertion.
    override def config = super.config.sequential

    // Scala.js forbids passing `js.Dynamic.global` itself as a value, only `.`-selections are allowed. `updateDynamic` and
    // `selectDynamic` on `js.Dynamic.global` are valid (they are `.`-selections), but `js.special.delete(js.Dynamic.global, …)` is
    // not. We work around the delete restriction by routing through `globalThis`, which is a *property* on `js.Dynamic.global` and
    // therefore may be passed as a value.
    private def globalThis: sjs.Dynamic = sjs.Dynamic.global.globalThis

    private def deleteGlobal(name: String): Unit =
        sjs.special.delete(globalThis, name)

    private def setGlobal(name: String, value: sjs.Dynamic): Unit =
        globalThis.updateDynamic(name)(value)

    // Stash the live references on a dedicated JS object (populated via `.`-selection to avoid the global-scope restriction).
    private val saved: sjs.Dynamic = sjs.Dynamic.literal()

    // Each leaf simulates a browser by deleting `process`/`require` from the JS global; stash the live references before
    // the body and restore them after, isolating leaves (the kyo-test equivalent of the old beforeEach/afterEach pair).
    override def aroundLeaf[A](body: A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) =
        Sync.defer {
            // Read off `globalThis`, not off the global scope: a bare `process` is a ReferenceError on a host that does not declare one,
            // thrown before `isUndefined` can answer, which would fail every leaf in a page including the ones that delete nothing.
            val p = globalThis.selectDynamic("process")
            if !sjs.isUndefined(p) then saved.updateDynamic("process")(p)
            val r = globalThis.selectDynamic("require")
            if !sjs.isUndefined(r) then saved.updateDynamic("require")(r)
            Scope.ensure {
                val sp = saved.selectDynamic("process")
                if !sjs.isUndefined(sp) then setGlobal("process", sp)
                else deleteGlobal("process")
                val sr = saved.selectDynamic("require")
                if !sjs.isUndefined(sr) then setGlobal("require", sr)
                else deleteGlobal("require")
                sjs.special.delete(saved, "process")
                sjs.special.delete(saved, "require")
            }.andThen(body)
        }

    "detectBrowser" - {
        "returns false under Node (process + require are defined)".notBrowser in {
            // The Node test runner always has `process` defined, so the host is Node-like whether or not `require` is.
            assert(NativeLoader.detectBrowser() == false)
        }

        "returns true when both process and require are undefined" in {
            deleteGlobal("process")
            deleteGlobal("require")
            assert(NativeLoader.detectBrowser() == true)
        }

        "returns false when process is defined but require is not".notBrowser in {
            deleteGlobal("require")
            assert(NativeLoader.detectBrowser() == false)
        }

        "returns true when require is defined but process is not" in {
            // The loader reads `process` (its environment, platform, and architecture) on every path, so a host without it cannot load a
            // native library. Answering false here sent the load on to a ReferenceError instead of the typed rejection.
            deleteGlobal("process")
            assert(NativeLoader.detectBrowser() == true)
        }
    }

    "NativeLoader.load" - {
        "throws FfiLoadError.Unsupported in a simulated browser" in {
            deleteGlobal("process")
            deleteGlobal("require")
            val ex = intercept[FfiLoadError.Unsupported] {
                discard(NativeLoader.load("any_lib"))
            }
            assert(ex.getMessage.contains("browser"))
        }

        "throws FfiLoadError.Unsupported, not a ReferenceError, when process is absent" in {
            deleteGlobal("process")
            discard(intercept[FfiLoadError.Unsupported] {
                discard(NativeLoader.load("any_lib"))
            })
        }

        "does not raise the browser gate in Node (process defined)".notBrowser in {
            // In Node the browser gate is off, so load does not raise the browser FfiLoadError.Unsupported. An arbitrary
            // unresolvable id still fails with LibraryNotFound (real path resolution is covered by NativeLoaderJsSpec); that
            // is expected and tolerated here, the point is only that the browser gate does not trigger.
            try discard(NativeLoader.load("any_lib_id"))
            catch case _: FfiLoadError.LibraryNotFound => ()
            succeed
        }
    }

    "Ffi.load" - {
        "throws FfiLoadError.Unsupported in a simulated browser and never constructs the impl" in {
            deleteGlobal("process")
            deleteGlobal("require")
            Ffi.unload[BrowserDetectionTest.ImplementedBinding]
            val before = BrowserDetectionTest.constructed
            val ex     = intercept[FfiLoadError.Unsupported] {
                discard(Ffi.load[BrowserDetectionTest.ImplementedBinding])
            }
            assert(ex.getMessage.contains("browser"))
            // The impl's companion is where a generated binding loads koffi, so a page must be turned away before it is built.
            assert(BrowserDetectionTest.constructed == before)
        }

        "throws FfiLoadError.Unsupported in a simulated browser for a binding with no impl, ahead of ImplNotFound" in {
            deleteGlobal("process")
            deleteGlobal("require")
            // Evict any stale cache entry so the load actually reaches instantiate.
            Ffi.unload[BrowserDetectionTest.FakeBinding]
            val ex = intercept[FfiLoadError.Unsupported] {
                discard(Ffi.load[BrowserDetectionTest.FakeBinding])
            }
            assert(ex.getMessage.contains("browser"))
        }

        "no longer throws FfiLoadError.Unsupported once process is restored".notBrowser in {
            // First, ensure the cache is empty.
            Ffi.unload[BrowserDetectionTest.FakeBinding]
            // With process+require present, the browser gate passes; the load then fails with FfiLoadError.ImplNotFound because
            // `FakeBindingImpl` does not exist. The key assertion is that the exception is NOT FfiLoadError.Unsupported.
            val ex = intercept[Exception] {
                discard(Ffi.load[BrowserDetectionTest.FakeBinding])
            }
            assert(ex.isInstanceOf[FfiLoadError.ImplNotFound])
        }
    }

    // The groups above fabricate a browser by deleting globals from a host that has them. These assert the same gate with nothing deleted:
    // `aroundLeaf` stashes nothing and restores nothing here, because neither global is there to begin with. The messages are compared in
    // full rather than by substring, since nothing in them depends on the host.
    "in a page".onlyBrowser - {
        "detectBrowser answers true with no global removed" in {
            assert(NativeLoader.detectBrowser() == true)
        }

        "NativeLoader.load fails with the loader's browser message" in {
            val ex = intercept[FfiLoadError.Unsupported] {
                discard(NativeLoader.load("any_lib"))
            }
            assert(ex.getMessage == FfiPlatformErrors.BrowserUnsupportedLoader)
        }

        "Ffi.load fails with the load's browser message and never constructs the impl" in {
            Ffi.unload[BrowserDetectionTest.ImplementedBinding]
            val before = BrowserDetectionTest.constructed
            val ex     = intercept[FfiLoadError.Unsupported] {
                discard(Ffi.load[BrowserDetectionTest.ImplementedBinding])
            }
            assert(ex.getMessage == FfiPlatformErrors.BrowserUnsupportedLoad)
            // The impl's companion is where a generated binding loads koffi. A page has to be turned away before it is built, and only a
            // page can say so: deleting `process` on Node leaves the companion loadable, while here it genuinely is not.
            assert(BrowserDetectionTest.constructed == before)
        }

        "Ffi.load reports the browser ahead of a missing impl" in {
            Ffi.unload[BrowserDetectionTest.FakeBinding]
            val ex = intercept[FfiLoadError.Unsupported] {
                discard(Ffi.load[BrowserDetectionTest.FakeBinding])
            }
            assert(ex.getMessage == FfiPlatformErrors.BrowserUnsupportedLoad)
        }
    }
end BrowserDetectionTest

object BrowserDetectionTest:
    /** Fixture trait used to exercise `Ffi.load`, no impl class is provided, so the load is expected to fail with `ImplNotFound` when the
      * browser gate is disabled. The test only relies on the *type of exception* thrown.
      */
    trait FakeBinding extends kyo.ffi.Ffi

    /** How many times [[ImplementedBindingImpl]] has been constructed. */
    var constructed: Int = 0

    /** Fixture trait with an impl, named the way the generator names one, whose construction is counted. */
    trait ImplementedBinding extends kyo.ffi.Ffi

    final class ImplementedBindingImpl extends ImplementedBinding:
        constructed += 1
end BrowserDetectionTest
