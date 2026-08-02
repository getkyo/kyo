package kyo

import org.scalatest.freespec.AnyFreeSpec
import scala.scalajs.js

/** Scala.js env resolution.
  *
  * `java.lang.System.getenv` always returns null under Scala.js, so only a real Node `process.env` read can
  * resolve an environment-backed flag here. Node's `process.env` is mutable at runtime (unlike the JVM's
  * process environment), so this leaf sets the variable it reads.
  */
class FlagPlatformTest extends AnyFreeSpec {

    /** Runs `f` with `process` deleted from the global object, so it is an UNDECLARED identifier — the state a
      * browser is in, and the one where a bare read throws while `typeof process` still answers "undefined".
      * Restored in a `finally` because the test runner talks over `process.stdout`.
      */
    private def withoutProcessGlobal[A](f: => A): A = {
        // `globalThis`, not `js.Dynamic.global`, which Scala.js allows only left of a `.`-selection.
        val global = js.Dynamic.global.globalThis
        val saved  = js.Dynamic.global.process
        js.special.delete(global, "process")
        try f
        finally global.updateDynamic("process")(saved)
    }

    "env" - {
        "reads a variable set in Node process.env" in {
            js.Dynamic.global.process.env.updateDynamic("KYO_FLAGPLATFORM_PROBE")("enabled")
            // The stdlib read is the control: it returns null on Scala.js even though the variable is set,
            // which is exactly the defect the platform-specific resolver exists to fix.
            assert(java.lang.System.getenv("KYO_FLAGPLATFORM_PROBE") eq null)
            assert(FlagPlatform.env("KYO_FLAGPLATFORM_PROBE") == "enabled")
        }

        "returns null for a name that is not set in Node process.env" in {
            assert(FlagPlatform.env("KYO_FLAGPLATFORM_UNSET") eq null)
        }

        "falls back to the stdlib read with no process global, instead of throwing ReferenceError" in {
            assert(withoutProcessGlobal(FlagPlatform.env("KYO_FLAGPLATFORM_PROBE")) eq null)
        }
    }

    "envNames" - {
        "lists the names Node process.env carries" in {
            js.Dynamic.global.process.env.updateDynamic("KYO_FLAGPLATFORM_NAMES_PROBE")("1")
            assert(FlagPlatform.envNames.exists(_ == "KYO_FLAGPLATFORM_NAMES_PROBE"))
        }

        "is empty with no process global, instead of throwing ReferenceError" in {
            assert(withoutProcessGlobal(FlagPlatform.envNames).isEmpty)
        }
    }

}
