package kyo

import AllowUnsafe.embrace.danger
import org.scalatest.freespec.AnyFreeSpec
import scala.scalajs.js

object DynamicFlagEnvTestFlags {
    object nodeEnv extends DynamicFlag[String]("fallback")
}

/** Environment-variable resolution for a [[DynamicFlag]] on Scala.js-Node.
  *
  * A dynamic flag resolves its initial value through the inherited [[Flag]] constructor and re-reads the
  * variable on `reload()`. Both reads go through the per-platform resolver, so both must see Node's real
  * `process.env`: with the stdlib read they would see null and the flag would report no source at all.
  */
class DynamicFlagEnvTest extends AnyFreeSpec {

    "DynamicFlag on Node" - {
        "resolves its initial value from process.env and reloads a changed value" in {
            val envName = "KYO_DYNAMICFLAGENVTESTFLAGS_NODEENV"
            js.Dynamic.global.process.env.updateDynamic(envName)("enabled")

            val flag = DynamicFlagEnvTestFlags.nodeEnv
            assert(flag.envName == envName)
            assert(flag.source == Flag.Source.EnvironmentVariable)
            assert(flag("user1") == "enabled")

            js.Dynamic.global.process.env.updateDynamic(envName)("disabled")
            assert(flag.reload() == Flag.ReloadResult.Updated("disabled"))
            assert(flag("user1") == "disabled")
        }
    }

}
