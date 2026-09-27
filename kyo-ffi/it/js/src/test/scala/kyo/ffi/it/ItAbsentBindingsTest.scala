package kyo.ffi.it

import kyo.ffi.Ffi

class ItAbsentBindingsTest extends Test:

    // One process-wide failure, shared by both leaves: the override must be in place before the first load, and a failed load is final.
    // The override is honored only for a file that exists.
    private val notALibrary =
        val require = scala.scalajs.js.Dynamic.global.require
        val path    = require("path").join(require("os").tmpdir(), s"kyo-it-absent-${java.lang.System.nanoTime()}.txt")
        require("fs").writeFileSync(path, "not a shared library")
        path.asInstanceOf[String]
    end notALibrary
    scala.scalajs.js.Dynamic.global.process.env.updateDynamic("KYO_FFI_KYO_IT_ABSENT_PATH")(notALibrary)

    private def loadFailure()(using kyo.test.AssertScope): Throwable =
        val failure =
            try
                Ffi.load[ItAbsentBindings]
                kyo.Absent
            catch case e: Throwable => kyo.Present(e)
        failure.getOrElse(fail("Ffi.load returned a binding whose library does not open"))
    end loadFailure

    "Ffi.load of a binding whose library does not open fails the load itself, with the loader's exception" in {
        // koffi's message names the file on some hosts and not others (Windows reports only "Invalid or forbidden DLL file"),
        // so the check is on the error being koffi's own rather than on its text.
        val failure = loadFailure()
        assert(failure.isInstanceOf[scala.scalajs.js.JavaScriptException], s"not the loader's own error: $failure")
    }

    // A JS error crosses a Scala `catch` as a fresh JavaScriptException each time, so the same failure is the same wrapped error.
    private def thrown(t: Throwable): AnyRef =
        t match
            case scala.scalajs.js.JavaScriptException(e) => e.asInstanceOf[AnyRef]
            case t                                       => t

    "a second Ffi.load of the same binding rethrows the first failure" in {
        val first  = loadFailure()
        val second = loadFailure()
        assert(thrown(second) eq thrown(first))
    }

end ItAbsentBindingsTest
