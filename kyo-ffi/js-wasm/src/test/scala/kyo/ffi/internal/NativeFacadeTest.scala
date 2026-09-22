package kyo.ffi.internal

import kyo.ffi.FfiLoadError
import kyo.ffi.Test

/** Which failure a load reports when the runtime and the native are both missing.
  *
  * koffi is absent from this module's test env and the id below names no native, so a load here has two things
  * wrong at once. The report has to name koffi: every native needs it, and naming the native package instead sends
  * the reader to install something that still cannot load.
  */
class NativeFacadeTest extends Test:

    "a host without koffi is told about koffi before any native" in {
        val ex = intercept[FfiLoadError.LibraryNotFound](NativeFacade.load("kyo_ffi_facade_absent_native", Seq.empty))
        assert(ex.libraryId == KoffiRuntime.LibraryId, s"expected koffi to be named first, got '${ex.libraryId}': ${ex.getMessage}")
        assert(ex.getMessage.contains("npm i koffi"))
    }

end NativeFacadeTest
