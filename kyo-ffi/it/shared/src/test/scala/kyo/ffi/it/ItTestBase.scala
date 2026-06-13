package kyo.ffi.it

/** Base for FFI integration suites. Forces the per-platform `SystemLibraryInit` at suite instantiation, before any leaf body runs its first
  * `Ffi.load`, so the JS-side `KYO_FFI_*_PATH` env wiring is in place (a no-op on JVM and Native).
  */
trait ItTestBase extends Test:
    SystemLibraryInit.force()
end ItTestBase
