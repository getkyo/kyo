package kyo

class StatTest extends Test:

    "scope" in runJVM {
        import AllowUnsafe.embrace.danger
        val stat         = Stat.initScope("test1")
        val counter      = stat.initCounter("a")
        val histogram    = stat.initHistogram("a")
        val gauge        = stat.initGauge("a")(1)
        val counterGauge = stat.initCounterGauge("a")(1)
        IO.run(counter.add(1))
        IO.run(histogram.observe(1))
        assert(IO.run(counter.get).eval == 1)
        assert(IO.run(histogram.count).eval == 1)
        assert(IO.run(gauge.collect).eval == 1)
        assert(IO.run(counterGauge.collect).eval == 1)
        val v = new Object
        assert(IO.run(stat.traceSpan("a")(v)).eval eq v)
    }
end StatTest
