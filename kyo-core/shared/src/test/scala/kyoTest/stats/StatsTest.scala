package kyoTest.stats

import kyo.*
import kyoTest.KyoTest

class StatsTest extends KyoTest:

    "scope" in runJVM {
        val stats        = Stats.initScope("test1")
        val counter      = stats.initCounter("a")
        val histogram    = stats.initHistogram("a")
        val gauge        = stats.initGauge("a")(1)
        val counterGauge = stats.initCounterGauge("a")(1)
        IOs.run(counter.add(1))
        IOs.run(histogram.observe(1))
        assert(IOs.run(counter.get) == 1)
        assert(IOs.run(histogram.count) == 1)
        assert(IOs.run(gauge.collect) == 1)
        assert(IOs.run(counterGauge.collect) == 1)
        val v = new Object
        assert(IOs.run(stats.traceSpan("a")(v)) eq v)
    }
end StatsTest
