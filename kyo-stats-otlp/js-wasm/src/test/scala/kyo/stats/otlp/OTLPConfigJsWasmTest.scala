package kyo.stats.otlp

import kyo.*
import scala.scalajs.js as sjs

/** OTLP configuration on Scala.js, where `java.lang.System.getenv` always returns `null`: the `OTEL_*` variables come from `process.env` on
  * Node, and from the `globalThis.KYO_CONFIG` seed on a host with no environment of its own.
  */
class OTLPConfigJsWasmTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    // The leaves set process-global state (process.env, the seed) around synchronous reads.
    override def config = super.config.sequential

    private def withEnv[A](vars: (String, String)*)(f: => A): A =
        val env = sjs.Dynamic.global.process.env
        vars.foreach((k, v) => env.updateDynamic(k)(v))
        try f
        finally vars.foreach((k, _) => discard(sjs.special.delete(env, k)))
    end withEnv

    "loadIfEnabled" - {
        "enables export from OTEL_EXPORTER_OTLP_ENDPOINT set in process.env, and reads the other variables there" in {
            val config = withEnv(
                "OTEL_EXPORTER_OTLP_ENDPOINT" -> "http://collector:4318",
                "OTEL_SERVICE_NAME"           -> "checkout",
                "OTEL_BSP_MAX_QUEUE_SIZE"     -> "64"
            )(OTLPConfig.loadIfEnabled())
            assert(config.map(_.endpoint) == Present("http://collector:4318"))
            assert(config.map(_.tracesEndpoint) == Present("http://collector:4318/v1/traces"))
            assert(config.map(_.serviceName) == Present("checkout"))
            assert(config.map(_.bspMaxQueueSize) == Present(64))
        }

        "enables export from a seeded endpoint" in {
            val global = sjs.Dynamic.global.globalThis
            global.updateDynamic("KYO_CONFIG")(sjs.Dynamic.literal(env =
                sjs.Dynamic.literal(OTEL_EXPORTER_OTLP_ENDPOINT = "http://seeded:4318")
            ))
            val config =
                try OTLPConfig.loadIfEnabled()
                finally discard(sjs.special.delete(global, "KYO_CONFIG"))
            assert(config.map(_.endpoint) == Present("http://seeded:4318"))
        }
    }

end OTLPConfigJsWasmTest
