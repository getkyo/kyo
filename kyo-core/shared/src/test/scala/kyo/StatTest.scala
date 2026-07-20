package kyo

class StatTest extends kyo.test.Test[Any]:

    "scope".onlyJvm in {
        import AllowUnsafe.embrace.danger
        val stat         = Stat.initScope("test1")
        val counter      = stat.initCounter("a")
        val histogram    = stat.initHistogram("a")
        val gauge        = stat.initGauge("a")(1)
        val counterGauge = stat.initCounterGauge("a")(1)
        Sync.Unsafe.evalOrThrow(counter.add(1))
        Sync.Unsafe.evalOrThrow(histogram.observe(1))
        assert(Sync.Unsafe.evalOrThrow(counter.get) == 1)
        assert(histogram.unsafe.summary().count == 1)
        assert(Sync.Unsafe.evalOrThrow(gauge.collect) == 1)
        assert(Sync.Unsafe.evalOrThrow(counterGauge.collect) == 1)
        val v = new Object
        assert(Sync.Unsafe.evalOrThrow(stat.traceSpan("a")(v)) eq v)
    }

    // The leaves below exercise the eager ExporterFactory scan hook in object Stat.
    // They are JVM-only: discovery runs through java.util.ServiceLoader over the test-classpath
    // META-INF/services resource, the JVM discovery mechanism. On JS/Native the shimmed loader reads
    // JSServiceLoaderRegistry, which is populated only by an @JSExportTopLevel registration object (a
    // main-source construct), not a test resource, so a cross-platform leaf would observe an
    // unregistered factory. Referencing kyo.Stat.kyoScope forces object Stat's class initializer,
    // which runs the eager scan exactly once per test-runner fork.

    "eager scan constructs a classpath-present ExporterFactory referencing only Stat.kyoScope, no trace call".onlyJvm in {
        // Touch only kyo.Stat (its class-init runs the eager scan); never call traceSpan/traceListen.
        val _ = Stat.kyoScope
        assert(StatTestExporterFactory.constructed.get())
    }

    "a factory constructed at Stat class-init reads a fully-initialized kyoScope, never a null later field".onlyJvm in {
        val _ = Stat.kyoScope
        assert(StatTestExporterFactory.constructed.get())
        assert(StatTestExporterFactory.kyoScopeWasNonNull.get())
    }

    "a throwing factory in the eager scan is isolated: the sibling good factory still constructs, Stat is not bricked".onlyJvm in {
        // StatTestThrowingFactory is registered FIRST in META-INF/services and throws at construction.
        // Per-factory isolation must skip it and still construct StatTestExporterFactory (listed after it),
        // and forcing object Stat's class initializer must not itself throw. Any Stat use here reaching
        // this line proves the class initializer completed rather than raising ExceptionInInitializerError.
        val _ = Stat.kyoScope
        assert(StatTestThrowingFactory.constructionAttempted.get(), "discovery never reached the throwing factory")
        assert(StatTestExporterFactory.constructed.get(), "the throwing factory prevented the good factory from constructing")
        // Stat is fully usable after the isolated failure.
        import AllowUnsafe.embrace.danger
        val counter = Stat.initScope("isolated-scan").initCounter("k")
        Sync.Unsafe.evalOrThrow(counter.inc)
        assert(Sync.Unsafe.evalOrThrow(counter.get) == 1)
    }

    "the eager scan and the first trace use share ONE TraceExporter construction; no second export loop".onlyJvm in {
        import AllowUnsafe.embrace.danger
        // Force object Stat's class initializer (runs the eager scan, which builds the single
        // TraceExporter by reading scannedExporter).
        val _ = Stat.kyoScope
        // The class-init scan has constructed the factory and its exporter exactly once. The Local
        // default reuses that same scanned instance, so a first trace call must NOT construct a second
        // exporter: a value of 2 here is the double-construction defect (two background export loops
        // draining the same destructive counters).
        assert(
            StatTestExporterFactory.exporterConstructions.get() == 1,
            s"class-init constructed the exporter ${StatTestExporterFactory.exporterConstructions.get()} times; expected exactly 1"
        )
        val v = new Object
        val r = Sync.Unsafe.evalOrThrow(Stat.initScope("trace-once").traceSpan("s")(v))
        assert(r eq v)
        assert(
            StatTestExporterFactory.exporterConstructions.get() == 1,
            s"a first trace use constructed a second exporter (count now ${StatTestExporterFactory.exporterConstructions.get()}); the Local default did not reuse the scanned instance"
        )
        // Repeated metrics-path and trace references after class-init trigger no further construction.
        val _c = Stat.initScope("trace-once-2").initCounter("x")
        val _r = Sync.Unsafe.evalOrThrow(Stat.initScope("trace-once-3").traceSpan("s2")(v))
        assert(
            StatTestExporterFactory.exporterConstructions.get() == 1,
            s"a later reference re-constructed the exporter (count now ${StatTestExporterFactory.exporterConstructions.get()})"
        )
    }
end StatTest
