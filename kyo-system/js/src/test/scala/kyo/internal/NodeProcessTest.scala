package kyo.internal

import kyo.*
import kyo.test.HostFilter
import scala.scalajs.js as sjs

class NodeProcessTest extends kyo.test.Test[Any]:

    // Exercises the node:child_process backend, which a browser has not.
    override protected def hostFilters = Chunk(HostFilter.NotBrowser)

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

    /** `process.env` replaced by a proxy that throws from every trap, as Deno's does without `--allow-env`. */
    private def withEnvThatThrows[A](f: => A): A =
        val process                    = sjs.Dynamic.global.process
        val saved                      = process.env
        val refuse: sjs.Function0[Any] = () => sjs.special.`throw`(sjs.Dynamic.newInstance(sjs.Dynamic.global.Error)("NotCapable"))
        val traps = sjs.Dynamic.literal(get = refuse, has = refuse, ownKeys = refuse, getOwnPropertyDescriptor = refuse)
        process.updateDynamic("env")(sjs.Dynamic.newInstance(sjs.Dynamic.global.Proxy)(sjs.Dynamic.literal(), traps))
        try f
        finally process.updateDynamic("env")(saved)
        end try
    end withEnvThatThrows

    "when every process.env read throws (Deno without --allow-env)" - {
        "env is null" in {
            assert(withEnvThatThrows(NodeProcess.env("PATH")) == null)
        }

        "envCopy is empty" in {
            val copy = withEnvThatThrows(NodeProcess.envCopy())
            assert(sjs.Object.keys(copy.asInstanceOf[sjs.Object]).length == 0)
        }
    }

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
