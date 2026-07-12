package kyo.internal

import org.scalatest.freespec.AnyFreeSpec
import scala.scalajs.js

/** Scala.js env resolution.
  *
  * `java.lang.System.getenv` always returns null under Scala.js, so only a real Node `process.env` read can
  * resolve an environment-backed flag here. Node's `process.env` is mutable at runtime (unlike the JVM's
  * process environment), so this leaf sets the variable it reads.
  */
class FlagPlatformTest extends AnyFreeSpec {

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
    }

}
