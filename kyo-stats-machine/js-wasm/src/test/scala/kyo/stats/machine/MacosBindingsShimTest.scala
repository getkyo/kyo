package kyo.stats.machine

import scala.scalajs.js.annotation.JSImport
import scala.scalajs.js as sjs

/** JS/Wasm-axis guard for the machine_macos shim wiring.
  *
  * The generated MacosBindings impl loads the shim through NativeLoader.jsResolve, whose first step is the
  * KYO_FFI_MACHINE_MACOS_PATH env var that build.sbt's Test / jsEnv sets to the plugin-compiled library. If
  * that wiring breaks (the env var is unset, or the shim never compiled to the resolved path), koffi's load
  * throws the opaque "Failed to load shared library" JavaScriptException from the first host read off macOS.
  * This leaf fails first, with a plain message naming the missing path, so a wiring regression is legible
  * instead of surfacing as that raw exception on a CI leg. It cannot live in shared/src/test: the env-var
  * mechanism and node `fs` are JS/Wasm-only. It runs on both the JS and the Wasm backends.
  *
  * The `scala.scalajs.js` package is aliased to `sjs` so it does not collide with the `js` platform-selector
  * method the test base defines; `fs.existsSync` reaches Node through the `node:fs` `@JSImport` facade below
  * rather than `require`, which is absent under the ESModule the Wasm backend mandates.
  */
class MacosBindingsShimTest extends kyo.test.Test[Any]:

    "the KYO_FFI_MACHINE_MACOS_PATH env override is set and its shim file exists" in {
        val raw = sjs.Dynamic.global.process.env.selectDynamic("KYO_FFI_MACHINE_MACOS_PATH")
        assert(
            !sjs.isUndefined(raw) && raw != null,
            "KYO_FFI_MACHINE_MACOS_PATH is not set: build.sbt's Test / jsEnv must point it at the compiled machine_macos shim"
        )
        val path = raw.asInstanceOf[String]
        assert(path.nonEmpty, "KYO_FFI_MACHINE_MACOS_PATH is empty")
        assert(
            ShimNodeFs.existsSync(path),
            s"machine_macos shim missing at KYO_FFI_MACHINE_MACOS_PATH=$path: ffiCompile must produce it under <axis>/target/ffi before the JS/Wasm test run"
        )
    }

end MacosBindingsShimTest

/** Node `fs.existsSync`, imported via `node:fs` so the guard links under both module kinds. */
@sjs.native
@JSImport("node:fs", JSImport.Namespace)
private object ShimNodeFs extends sjs.Object:
    def existsSync(path: String): Boolean = sjs.native
end ShimNodeFs
