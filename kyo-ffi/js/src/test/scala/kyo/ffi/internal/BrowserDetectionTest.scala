package kyo.ffi.internal

import kyo.*
import kyo.discard
import kyo.ffi.Ffi
import kyo.ffi.FfiLoadError
import kyo.ffi.Test
import scala.scalajs.js as sjs

/** Validates the browser gate.
  *
  * [[NativeLoader.detectBrowser]] returns true when neither `process` nor `require` is defined on the JS global. On that outcome:
  *
  *   - [[NativeLoader.load]] throws [[FfiLoadError.Unsupported]] directly.
  *   - [[FfiReflect.instantiate]] throws [[FfiLoadError.Unsupported]] before attempting any `scalajs-reflect` lookup, so `Ffi.load[T]` surfaces the
  *     gate with the same exception type.
  *
  * The test simulates a browser by temporarily deleting `process` and `require` from `sjs.Dynamic.global`, runs the checks, then restores
  * the originals so subsequent specs see a normal Node environment.
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
            val p = sjs.Dynamic.global.selectDynamic("process")
            if !sjs.isUndefined(p) then saved.updateDynamic("process")(p)
            val r = sjs.Dynamic.global.selectDynamic("require")
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
        "returns false under Node (process + require are defined)" in {
            // Node test runner always has `process` defined; `require` may or may not be. The heuristic requires *both* to be absent.
            assert(NativeLoader.detectBrowser() == false)
        }

        "returns true when both process and require are undefined" in {
            deleteGlobal("process")
            deleteGlobal("require")
            assert(NativeLoader.detectBrowser() == true)
        }

        "returns false when process is defined but require is not" in {
            deleteGlobal("require")
            assert(NativeLoader.detectBrowser() == false)
        }

        "returns false when require is defined but process is not" in {
            deleteGlobal("process")
            assert(NativeLoader.detectBrowser() == false)
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

        "does not throw in Node (process defined)" in {
            // Just confirm no FfiLoadError.Unsupported is raised, actual path resolution is covered by NativeLoaderJsSpec.
            discard(NativeLoader.load("any_lib_id"))
            succeed
        }
    }

    "FfiReflect.instantiate" - {
        "throws FfiLoadError.Unsupported in a simulated browser" in {
            deleteGlobal("process")
            deleteGlobal("require")
            val ex = intercept[FfiLoadError.Unsupported] {
                discard(FfiReflect.instantiate(
                    "kyo.ffi.internal.BrowserDetectionTest$DoesNotExist",
                    "kyo.ffi.internal.BrowserDetectionTest.DoesNotExist"
                ))
            }
            assert(ex.getMessage.contains("browser"))
        }
    }

    "Ffi.load" - {
        "throws FfiLoadError.Unsupported in a simulated browser before any reflection is attempted" in {
            deleteGlobal("process")
            deleteGlobal("require")
            // Evict any stale cache entry so the load actually reaches instantiate.
            Ffi.unload[BrowserDetectionTest.FakeBinding]
            val ex = intercept[FfiLoadError.Unsupported] {
                discard(Ffi.load[BrowserDetectionTest.FakeBinding])
            }
            assert(ex.getMessage.contains("browser"))
        }

        "no longer throws FfiLoadError.Unsupported once process is restored" in {
            // First, ensure the cache is empty.
            Ffi.unload[BrowserDetectionTest.FakeBinding]
            // With process+require present, the browser gate passes; we expect the scalajs-reflect lookup to then fail with
            // FfiLoadError.ImplNotFound because `FakeBindingImpl` does not exist (and is not annotated). The key assertion is that the
            // exception is NOT FfiLoadError.Unsupported.
            val ex = intercept[Exception] {
                discard(Ffi.load[BrowserDetectionTest.FakeBinding])
            }
            assert(ex.isInstanceOf[FfiLoadError.ImplNotFound])
        }
    }
end BrowserDetectionTest

object BrowserDetectionTest:
    /** Fixture trait used to exercise `Ffi.load`, no impl class is provided, so the reflective lookup is expected to fail when the browser
      * gate is disabled. The test only relies on the *type of exception* thrown before the lookup runs.
      */
    trait FakeBinding extends kyo.ffi.Ffi
end BrowserDetectionTest
