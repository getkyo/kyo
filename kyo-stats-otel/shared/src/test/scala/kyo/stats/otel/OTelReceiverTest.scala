package kyo.stats.otel

import kyo.*

class OTelReceiverTest extends Test {

    "traces" in run {
        val stats = Stat.initScope("test")
        stats.traceSpan("tspan") {
            42d
        }.map { r =>
            assert(r == 42d)
        }
    }
}
