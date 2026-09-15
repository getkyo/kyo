package kyo.internal

import kyo.*
import scala.scalajs.js as sjs

class NodeProcessTest extends kyo.test.Test[Any]:

    /** Runs `f` with `process` deleted from the global object, the state a browser is in. Restored in a `finally` because the test runner
      * talks over `process.stdout`.
      */
    private def withoutProcessGlobal[A](f: => A): A =
        val global = sjs.Dynamic.global.globalThis
        val saved  = sjs.Dynamic.global.process
        sjs.special.delete(global, "process")
        try f
        finally global.updateDynamic("process")(saved)
        end try
    end withoutProcessGlobal

    "on Node" - {
        "env reads a variable set in process.env and is null for one that is not" in {
            sjs.Dynamic.global.process.env.updateDynamic("KYO_NODEPROCESS_PROBE")("set")
            assert(NodeProcess.env("KYO_NODEPROCESS_PROBE") == "set")
            assert(NodeProcess.env("KYO_NODEPROCESS_UNSET") == null)
        }

        "envCopy is a copy, so changing it leaves process.env alone" in {
            sjs.Dynamic.global.process.env.updateDynamic("KYO_NODEPROCESS_COPY")("original")
            val copy = NodeProcess.envCopy()
            assert(copy.selectDynamic("KYO_NODEPROCESS_COPY").asInstanceOf[String] == "original")
            copy.updateDynamic("KYO_NODEPROCESS_COPY")("changed")
            assert(NodeProcess.env("KYO_NODEPROCESS_COPY") == "original")
        }

        "require returns process" in {
            assert(NodeProcess.require("probe").pid.asInstanceOf[Int] > 0)
        }
    }

    "with no process global" - {
        "env is null" in {
            assert(withoutProcessGlobal(NodeProcess.env("PATH")) == null)
        }

        "envCopy is empty" in {
            val copy = withoutProcessGlobal(NodeProcess.envCopy())
            assert(sjs.Object.keys(copy.asInstanceOf[sjs.Object]).length == 0)
        }

        "require fails naming the operation and the host" in {
            val error = intercept[UnsupportedOperationException](withoutProcessGlobal(NodeProcess.require("Probe.operation")))
            assert(error.getMessage.contains("Probe.operation"))
            assert(error.getMessage.contains("Node-like host"))
        }
    }

end NodeProcessTest
