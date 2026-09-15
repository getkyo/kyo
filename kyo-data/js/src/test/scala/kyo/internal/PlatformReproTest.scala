package kyo.internal

import scala.scalajs.LinkingInfo
import scala.scalajs.js

// Scratch reproduction for the Platform consolidation; folded into kyo-config's PlatformTest once Platform moves.
class PlatformReproTest extends kyo.test.Test[Any]:

    "exit does not throw on a host without a process global" in {
        val global = js.Dynamic.global.globalThis
        val saved  = js.Dynamic.global.process
        js.special.delete(global, "process")
        val outcome =
            try
                Platform.exit(0)
                "ok"
            catch case e: Throwable => e.toString
            finally global.updateDynamic("process")(saved)
        assert(outcome == "ok")
    }

    "isWasm agrees with the link" in {
        assert(Platform.isWasm == LinkingInfo.isWebAssembly)
    }

end PlatformReproTest
