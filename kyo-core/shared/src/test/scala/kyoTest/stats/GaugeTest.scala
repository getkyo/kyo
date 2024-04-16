package kyoTest.stats

import kyo.*
import kyo.stats.*
import kyo.stats.internal.UnsafeGauge
import kyoTest.KyoTest

class GaugeTest extends KyoTest:

    "noop" in IOs.run {
        for
            _ <- Gauge.noop.close
        yield succeed
    }

    "unsafe" in IOs.run {
        val unsafe = new TestGauge
        val gauge  = Gauge(unsafe)
        for
            _ <- gauge.close
        yield assert(unsafe.isClosed)
    }

    "all" - {
        "empty" in IOs.run {
            assert(Gauge.all(Nil).unsafe eq Gauge.noop.unsafe)
        }
        "one" in IOs.run {
            val gauge = Gauge(new TestGauge)
            assert(Gauge.all(List(gauge)).unsafe eq gauge.unsafe)
        }
        "multiple" in IOs.run {
            val unsafe1        = new TestGauge
            val unsafe2        = new TestGauge
            val compositeGauge = Gauge.all(List(Gauge(unsafe1), Gauge(unsafe2)))
            for
                _ <- compositeGauge.close
            yield assert(unsafe1.isClosed && unsafe2.isClosed)
        }
    }

    class TestGauge extends UnsafeGauge:
        var isClosed = false
        def close()  = isClosed = true
end GaugeTest
