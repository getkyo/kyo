package kyo

class StatTest extends Test:

    "scope" in runJVM {
        import AllowUnsafe.embrace.danger
        val stat         = Stat.initScope("test1")
        val counter      = stat.initCounter("a")
        val histogram    = stat.initHistogram("a")
        val gauge        = stat.initGauge("a")(1)
        val counterGauge = stat.initCounterGauge("a")(1)
        Sync.Unsafe.evalOrThrow(counter.add(1))
        Sync.Unsafe.evalOrThrow(histogram.observe(1))
        assert(Sync.Unsafe.evalOrThrow(counter.get) == 1)
        assert(Sync.Unsafe.evalOrThrow(histogram.count) == 1)
        assert(Sync.Unsafe.evalOrThrow(gauge.collect) == 1)
        assert(Sync.Unsafe.evalOrThrow(counterGauge.collect) == 1)
        val v = new Object
        assert(Sync.Unsafe.evalOrThrow(stat.traceSpan("a")(v)) eq v)
    }
end StatTest
