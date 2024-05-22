package kyoTest.stats.otel

import kyo.*
import kyo.stats.*
import kyoTest.*

class OTelReceiverTest extends KyoTest:

    "metrics" in run {
        val stats     = Stats.initScope("test")
        val counter   = stats.initCounter("a")
        val histogram = stats.initHistogram("b")
        stats.initGauge("c")(99d)
        for
            _ <- counter.inc
            _ <- counter.add(1)
            _ <- counter.add(2, Attributes.add("test", 3))
            _ <- histogram.observe(42d)
            _ <- histogram.observe(24d, Attributes.add("test", 3))
        yield succeed
        end for
    }

    "traces" in run {
        val stats = Stats.initScope("test")
        stats.traceSpan("tspan") {
            42d
        }.map { r =>
            assert(r == 42d)
        }
    }
end OTelReceiverTest
