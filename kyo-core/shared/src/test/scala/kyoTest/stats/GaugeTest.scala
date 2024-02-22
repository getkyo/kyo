package kyoTest.stats

import kyo.*
import kyo.stats.*
import kyoTest.KyoTest

class GaugeTest extends KyoTest:

    "noop" in run {
        for
            _ <- Gauge.noop.close
        yield succeed
    }

    "unsafe" in run {
        val unsafe = new TestGauge
        val gauge  = Gauge(unsafe)
        for
            _ <- gauge.close
        yield assert(unsafe.isClosed)
    }

    "all" - {
        "empty" in run {
            assert(Gauge.all(Nil) == Gauge.noop)
        }
        "one" in run {
            val gauge = Gauge(new TestGauge)
            assert(Gauge.all(List(gauge)) == gauge)
        }
        "multiple" in run {
            val unsafe1        = new TestGauge
            val unsafe2        = new TestGauge
            val compositeGauge = Gauge.all(List(Gauge(unsafe1), Gauge(unsafe2)))
            for
                _ <- compositeGauge.close
            yield assert(unsafe1.isClosed && unsafe2.isClosed)
        }
    }

    class TestGauge extends Gauge.Unsafe:
        var isClosed = false
        def close()  = isClosed = true
end GaugeTest
