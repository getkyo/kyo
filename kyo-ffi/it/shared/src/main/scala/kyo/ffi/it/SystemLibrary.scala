package kyo.ffi.it

/** Platform initialization hook for system-library resolution.
  *
  * The binding traits all use literal `Ffi.Config.library` values (required by the codegen, which reads TASTy string constants). On JVM and
  * Native those literal short names ("c", "m") resolve at runtime via the platform's dynamic loader. On Scala.js (Node) a bare "c" does NOT
  * reliably resolve, macOS needs `/usr/lib/libSystem.B.dylib`.
  *
  * The JS-side `SystemLibraryInit` sets koffi's `KYO_FFI_*_PATH` environment variables so the `NativeLoader.jsResolve` cascade picks up the
  * right path without every binding hard-coding a different literal per platform. JVM and Native implementations are no-ops.
  *
  * Specs must call `SystemLibraryInit.force()` before the first `Ffi.load` to ensure the env-var wiring is in place.
  */
private[it] object SystemLibraryInit:
    /** Force-load the per-platform `SystemLibraryInitImpl` so its module initializer runs. On JVM/Native this is a no-op; on JS it
      * populates `process.env.KYO_FFI_*_PATH` once.
      */
    def force(): Unit = SystemLibraryInitImpl.ensureInitialized()
end SystemLibraryInit
