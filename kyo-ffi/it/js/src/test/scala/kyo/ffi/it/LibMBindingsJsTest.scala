package kyo.ffi.it

import kyo.ffi.Ffi
import kyo.ffi.internal.NativeLoader
import scala.scalajs.js as sjs

/** libm on Scala.js resolves the way it does for an application: to the symbols already loaded into the Node process (koffi's process
  * scope, which carries libm on every POSIX host because Node links it), or to the universal C runtime on Windows, with no path anywhere.
  *
  * A `KYO_FFI_M_PATH` in this row's environment would load libm through that override instead, and the shared `LibMTest` would pass without
  * touching the resolution an application gets, so the first leaf holds the row to having none.
  */
class LibMBindingsJsTest extends Test:

    "no KYO_FFI_M_PATH is set, so libm goes through the loader's own resolution" in {
        val variable = sjs.Dynamic.global.process.env.selectDynamic("KYO_FFI_M_PATH")
        assert(sjs.isUndefined(variable), s"KYO_FFI_M_PATH is set ($variable), so this row does not resolve libm as a program does")
    }

    "libm resolves to the process's own symbols, and a call through the binding answers" in {
        val windows = sjs.Dynamic.global.process.platform.asInstanceOf[String] == "win32"
        assert(NativeLoader.jsResolve("m") == (if windows then "ucrtbase.dll" else null))
        assert(Ffi.load[LibMBindings].sqrt(16.0) == 4.0)
    }

end LibMBindingsJsTest
