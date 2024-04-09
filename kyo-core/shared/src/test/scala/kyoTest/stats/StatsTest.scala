package kyoTest.stats

import kyo.*
import kyo.stats.*
import kyoTest.KyoTest

class StatsTest extends KyoTest:

    "noop" in {
        val stats = Stats.noop
        assert(stats.scope("test") eq stats)
        assert(stats.initCounter("a").unsafe eq Counter.noop.unsafe)
        assert(stats.initHistogram("a").unsafe eq Histogram.noop.unsafe)
        assert(stats.initGauge("a")(1).unsafe eq Gauge.noop.unsafe)
        val v = new Object
        assert(IOs.run(stats.traceSpan("a")(v)) eq v)
    }

    "scope" in {
        val stats = Stats.initScope("test")
        assert(stats.initCounter("a").unsafe eq Counter.noop.unsafe)
        assert(stats.initHistogram("a").unsafe eq Histogram.noop.unsafe)
        assert(stats.initGauge("a")(1).unsafe eq Gauge.noop.unsafe)
        val v = new Object
        assert(IOs.run(stats.traceSpan("a")(v)) eq v)
    }
end StatsTest
