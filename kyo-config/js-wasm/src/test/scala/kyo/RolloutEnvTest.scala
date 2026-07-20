package kyo

import org.scalatest.freespec.AnyFreeSpec
import scala.scalajs.js

object RolloutEnvTestFlags {
    object mode extends StaticFlag[String]("off")
}

/** Rollout topology resolution on Scala.js-Node.
  *
  * The instance path every rollout expression is evaluated against comes from `KYO_ROLLOUT_PATH` (or the
  * cloud-topology variables), read once when `Rollout` initializes. `java.lang.System.getenv` always returns
  * null under Scala.js, so a stdlib read leaves that path empty on Node: a path-selected choice then loses to
  * the terminal one and the flag resolves to the WRONG value rather than failing to resolve.
  *
  * `Rollout` can initialize before any test body runs, so the build puts `KYO_ROLLOUT_PATH=prod/us-east-1` in
  * the JS test process environment (`kyo-config` jsSettings) instead of a test writing it.
  */
class RolloutEnvTest extends AnyFreeSpec {

    "Rollout on Node" - {
        "reads the topology path from process.env" in {
            // The stdlib read is the control: it returns null on Node even though the variable is set.
            assert(java.lang.System.getenv("KYO_ROLLOUT_PATH") eq null)
            assert(Rollout.path.mkString("/") == "prod/us-east-1")
        }

        "a StaticFlag rollout expression selects the choice matching the process.env topology path" in {
            js.Dynamic.global.process.env.updateDynamic("KYO_ROLLOUTENVTESTFLAGS_MODE")("rollout:on@prod;off")

            val flag = RolloutEnvTestFlags.mode
            assert(flag.source == Flag.Source.EnvironmentVariable)
            assert(flag.initialExpression == "rollout:on@prod;off")
            // With an empty path the `prod` selector cannot match and the terminal choice `off` wins.
            assert(flag() == "on")
        }
    }

}
