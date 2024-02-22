package kyoTest.stats

import kyoTest.KyoTest
import kyo.stats.*
import kyo.*

class StatsTest extends KyoTest:

    "noop" in {
        val stats = Stats.noop
        assert(stats.scope("test") == stats)
        assert(stats.initCounter("a") == Counter.noop)
        assert(stats.initHistogram("a") == Histogram.noop)
        assert(stats.initGauge("a")(1) == Gauge.noop)
        val v = new Object
        assert(stats.traceSpan("a")(v) == v)
    }

    "scope" in {
        val stats = Stats.initScope("test")
        assert(stats.initCounter("a") == Counter.noop)
        assert(stats.initHistogram("a") == Histogram.noop)
        assert(stats.initGauge("a")(1) == Gauge.noop)
        val v = new Object
        assert(stats.traceSpan("a")(v) != v)
    }
end StatsTest
