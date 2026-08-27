package kyo.internal

import kyo.*
import kyo.AllowUnsafe.embrace.danger
import scala.scalajs.js as sjs

class SystemPlatformSpecificJsWasmTest extends kyo.test.Test[Any]:

    /** Runs `f` with `process` deleted from the global object, so it is an UNDECLARED identifier — the state a
      * browser is in, and the one where a bare read throws while `typeof process` still answers "undefined".
      * Restored in a `finally` because the test runner talks over `process.stdout`.
      */
    private def withoutProcessGlobal[A](f: => A): A =
        // `globalThis`, not `sjs.Dynamic.global`, which Scala.js allows only left of a `.`-selection.
        val global = sjs.Dynamic.global.globalThis
        val saved  = sjs.Dynamic.global.process
        sjs.special.delete(global, "process")
        try f
        finally global.updateDynamic("process")(saved)
        end try
    end withoutProcessGlobal

    "with no process global" - {
        "env returns null instead of throwing ReferenceError" in {
            assert(withoutProcessGlobal(SystemPlatformSpecific.env("PATH")) == null)
        }

        "osName falls back to the empty string instead of throwing ReferenceError" in {
            assert(withoutProcessGlobal(SystemPlatformSpecific.osName()) == "")
        }

        "osArch falls back to the empty string instead of throwing ReferenceError" in {
            assert(withoutProcessGlobal(SystemPlatformSpecific.osArch()) == "")
        }
    }

    "on Node" - {
        "env resolves a variable set in process.env" in {
            sjs.Dynamic.global.process.env.updateDynamic("KYO_SYSTEMPLATFORM_PROBE")("enabled")
            assert(SystemPlatformSpecific.env("KYO_SYSTEMPLATFORM_PROBE") == "enabled")
        }

        "env returns null for a name that is not set" in {
            assert(SystemPlatformSpecific.env("KYO_SYSTEMPLATFORM_UNSET") == null)
        }
    }

end SystemPlatformSpecificJsWasmTest
