package kyo.stats.machine

import kyo.ffi.FfiLoadError
import kyo.ffi.internal.NativeLoader
import scala.scalajs.js.annotation.JSImport
import scala.scalajs.js as sjs

/** JS/Wasm-axis guard for the machine_macos shim wiring.
  *
  * The generated MacosBindings impl loads the shim through NativeLoader.jsResolve, which finds it staged beside the linked test program:
  * build.sbt wraps each JS test link with the kyo FFI plugin's `ffiWithJsNatives`, which copies the plugin-compiled shim from the test
  * classpath into `kyo-ffi/native/<os>-<arch>/`, as an application's link does, and no `KYO_FFI_MACHINE_MACOS_PATH` is set. If that
  * wiring breaks, koffi's load throws the opaque "Failed to load shared library" JavaScriptException from the first host read off macOS.
  * This leaf fails first, with a plain message naming what the loader looked for, so a wiring regression is legible instead of
  * surfacing as that raw exception on a CI leg. It cannot live in shared/src/test: staging beside a linked program and node `fs` are
  * JS/Wasm-only. It runs on both the JS and the Wasm backends.
  *
  * The shim exists only where it is built. `machine_macos` is a Mach-only binding declared for darwin (`osTargets` in build.sbt), so a
  * Linux or Windows build compiles nothing for it and stages nothing, which is the correct outcome there, not a wiring break: the
  * loader finds no shim and says so with `LibraryNotFound`.
  *
  * The `scala.scalajs.js` package is aliased to `sjs` so it does not collide with the `js` platform-selector method the test base
  * defines; `fs.existsSync` reaches Node through the `node:fs` `@JSImport` facade below rather than `require`, which is absent under the
  * ESModule the Wasm backend mandates.
  */
class MacosBindingsShimTest extends kyo.test.Test[Any]:

    "the machine_macos shim resolves from beside the linked test program on macOS, with no path variable, and is not built elsewhere" in {
        val variable = sjs.Dynamic.global.process.env.selectDynamic("KYO_FFI_MACHINE_MACOS_PATH")
        assert(
            sjs.isUndefined(variable),
            s"KYO_FFI_MACHINE_MACOS_PATH is set ($variable), so this row would not resolve the shim as a program does"
        )
        val onMacOs = sjs.Dynamic.global.process.platform.asInstanceOf[String] == "darwin"
        if onMacOs then
            val path =
                try NativeLoader.jsResolve("machine_macos")
                catch
                    case e: FfiLoadError.LibraryNotFound =>
                        throw new AssertionError(
                            s"machine_macos is not staged beside the linked test program: build.sbt's kyoJsTestNatives must wrap this row's link " +
                                s"with ffiWithJsNatives, and ffiCompile must produce the shim before it. ${e.getMessage}"
                        )
            assert(path.contains("/kyo-ffi/native/darwin-"), s"machine_macos resolved to $path, not to the staged tree")
            assert(ShimNodeFs.existsSync(path), s"machine_macos resolved to $path, which does not exist")
        else
            val missing =
                try
                    NativeLoader.jsResolve("machine_macos")
                    false
                catch case _: FfiLoadError.LibraryNotFound => true
            assert(missing, "machine_macos resolved on a non-darwin host: it is a Mach-only binding and no other platform can load it")
        end if
    }

end MacosBindingsShimTest

/** Node `fs.existsSync`, imported via `node:fs` so the guard links under both module kinds. */
@sjs.native
@JSImport("node:fs", JSImport.Namespace)
private object ShimNodeFs extends sjs.Object:
    def existsSync(path: String): Boolean = sjs.native
end ShimNodeFs
