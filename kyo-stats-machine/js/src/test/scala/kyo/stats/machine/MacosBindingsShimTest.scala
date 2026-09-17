package kyo.stats.machine

import kyo.ffi.FfiLoadError
import kyo.ffi.internal.NativeLoader
import kyo.internal.PlatformJs
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
  * defines. Every host read goes through [[PlatformJs]]: `process` as a property of `globalThis`, and `fs` through
  * `process.getBuiltinModule`, so neither a bare identifier nor a hoisted `node:` import can fail this suite's link before a test runs.
  */
class MacosBindingsShimTest extends kyo.test.Test[Any]:

    "the machine_macos shim resolves from beside the linked test program on macOS, with no path variable, and is not built elsewhere".notBrowser in {
        val variable: sjs.UndefOr[sjs.Dynamic] =
            PlatformJs.jsGlobal("process").map(_.env.selectDynamic("KYO_FFI_MACHINE_MACOS_PATH"))
        assert(
            variable.fold(true)(sjs.isUndefined(_)),
            s"KYO_FFI_MACHINE_MACOS_PATH is set (${variable.getOrElse("")}), so this row would not resolve the shim as a program does"
        )
        val onMacOs = PlatformJs.processString("platform") == "darwin"
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
            assert(shimExists(path), s"machine_macos resolved to $path, which does not exist")
        else
            val missing =
                try
                    NativeLoader.jsResolve("machine_macos")
                    false
                catch case _: FfiLoadError.LibraryNotFound => true
            assert(missing, "machine_macos resolved on a non-darwin host: it is a Mach-only binding and no other platform can load it")
        end if
    }

    /** Node's `fs.existsSync`, and `false` on a host that has no `node:fs` to ask.
      *
      * Resolved at the call through `process.getBuiltinModule`, never a static `@JSImport("node:fs")`: a static import is hoisted and
      * resolved with the module graph, so a page that merely links this suite would fail to load before any test ran. It reaches `fs`
      * without `require`, which is absent under the ESModule the Wasm backend mandates.
      */
    private def shimExists(path: String): Boolean =
        PlatformJs.nodeBuiltin("node:fs").fold(false)(_.applyDynamic("existsSync")(path).asInstanceOf[Boolean])

end MacosBindingsShimTest
