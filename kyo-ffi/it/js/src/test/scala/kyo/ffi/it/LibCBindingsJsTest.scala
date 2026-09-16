package kyo.ffi.it

import kyo.ffi.Ffi
import kyo.ffi.internal.NativeLoader
import scala.scalajs.js as sjs

/** libc on Scala.js resolves the way it does for an application: to the symbols already loaded into the Node process (koffi's process
  * scope, which carries libc on every POSIX host), or to the universal C runtime on Windows, with no path anywhere.
  *
  * A `KYO_FFI_C_PATH` in this row's environment would load libc through that override instead, and the shared `LibCTest` would pass without
  * touching the resolution an application gets, so the first leaf holds the row to having none.
  */
class LibCBindingsJsTest extends Test:

    "no KYO_FFI_C_PATH is set, so libc goes through the loader's own resolution" in {
        val variable = sjs.Dynamic.global.process.env.selectDynamic("KYO_FFI_C_PATH")
        assert(sjs.isUndefined(variable), s"KYO_FFI_C_PATH is set ($variable), so this row does not resolve libc as a program does")
    }

    "libc resolves to the process's own symbols, and a call through the binding answers" in {
        val windows = sjs.Dynamic.global.process.platform.asInstanceOf[String] == "win32"
        assert(NativeLoader.jsResolve("c") == (if windows then "ucrtbase.dll" else null))
        assert(Ffi.load[LibCBindings].strlen("kyo") == 3L)
    }

end LibCBindingsJsTest
