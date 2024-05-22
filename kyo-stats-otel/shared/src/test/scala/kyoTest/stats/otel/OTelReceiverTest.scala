package kyoTest.stats.otel

import kyo.*
import kyoTest.*

class OTelReceiverTest extends KyoTest {

    "traces" in run {
        val stats = Stats.initScope("test")
        stats.traceSpan("tspan") {
            42d
        }.map { r =>
            assert(r == 42d)
        }
    }
}
