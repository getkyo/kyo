package kyo.ffi.it

import kyo.internal.Platform
import kyo.internal.PlatformJs
import scala.scalajs.js

/** Scala.js-side system library init.
  *
  * koffi loads shared libraries by path. Bare "c" works on Linux (dlopen finds `libc.so.6`) but fails on macOS (where libc is folded into
  * `libSystem`). To keep the binding traits' `Ffi.Config.library` literals identical across platforms ("c" and "m"), we prime the koffi
  * env-var override cascade at module-init time: `KYO_FFI_C_PATH` and `KYO_FFI_M_PATH` get the detected per-OS path.
  *
  * Specs must call `SystemLibraryInit.force()` before the first `Ffi.load` to ensure this module has been initialized.
  *
  * Windows is a documented gap, resolution throws until detection is extended.
  */
private[it] object SystemLibraryInitImpl:

    // Module-level `val`, evaluated lazily at first access (via
    // `ensureInitialized` below), guaranteed to run at most once.
    private val initialized: Unit =
        // macOS folds libm into libSystem, so KYO_FFI_M_PATH points at the same libSystem blob and `library = "m"` resolves. On Linux, libm
        // is a genuinely separate `libm.so.6`; pointing at libc.so.6 would miss math symbols like `sin`.
        val (libcPath, libmPath) = Platform.os match
            case Platform.Os.MacOS => ("/usr/lib/libSystem.B.dylib", "/usr/lib/libSystem.B.dylib")
            case Platform.Os.Linux => ("libc.so.6", "libm.so.6")
            case other             => throw new UnsupportedOperationException(s"Unsupported JS platform: $other")
        // The overrides live in `process.env`, where the loader reads them; a host without `process` has no such cascade to prime.
        PlatformJs.jsGlobal("process").foreach { process =>
            val env = process.env
            if js.isUndefined(env.selectDynamic("KYO_FFI_C_PATH")) then
                env.updateDynamic("KYO_FFI_C_PATH")(libcPath)
            if js.isUndefined(env.selectDynamic("KYO_FFI_M_PATH")) then
                env.updateDynamic("KYO_FFI_M_PATH")(libmPath)
        }
    end initialized

    def ensureInitialized(): Unit = initialized
end SystemLibraryInitImpl
