package kyo

class StatTest extends kyo.test.Test[Any]:

    // Cross-platform on purpose: the safe tier is what a caller reaches for on every target, and the whole
    // point of this method is that reading a histogram back no longer costs an AllowUnsafe import. There is
    // deliberately no `import AllowUnsafe.embrace.danger` in this leaf.
    "Histogram.summary reads the distribution through the safe tier, with no AllowUnsafe at the call site" in {
        val histogram = Stat.initScope("stattest-safe-summary").initHistogram("latency")
        for
            _     <- histogram.observe(10L)
            _     <- histogram.observe(20L)
            _     <- histogram.observe(30L)
            first <- histogram.summary
            again <- histogram.summary
        yield
            assert(first.count == 3)
            assert(first.sum == 60.0)
            assert(first.min <= 10.0)
            assert(first.max >= 30.0)
            // Non-destructive, unlike Counter.get: buckets and sum describe the whole lifetime, so a
            // dashboard can poll this without disturbing an exporter reading the same instrument.
            assert(again.count == first.count)
            assert(again.sum == first.sum)
        end for
    }

    // The hazard these exist for: the registering accessors are first-writer-wins, so a consumer that
    // calls initCounter for a metric it does not own registers its own instrument at that path and
    // permanently shadows the producer's value. Reading someone else's metric has to ask, not mint. No
    // AllowUnsafe here either: this is the safe tier's answer, and StatsRegistry's own find* stay the
    // unsafe-tier equivalents an exporter uses on the hot path.
    "Stat.findCounter returns the producer's counter and reads it through the safe tier" in {
        val scope    = Stat.initScope("stattest-find-counter")
        val produced = scope.initCounter("hits")
        for
            _     <- produced.inc
            _     <- produced.add(4L)
            found <- scope.findCounter("hits")
            seen <- found match
                case Present(c) => c.get
                case Absent     => Kyo.lift(-1L)
        yield
            assert(found.isDefined)
            assert(seen == 5L)
        end for
    }

    "Stat.findCounter never registers, so a misspelled name stays Absent and the real one is untouched" in {
        val scope = Stat.initScope("stattest-find-typo")
        val real  = scope.initCounter("requests")
        for
            _       <- real.inc
            missing <- scope.findCounter("requsts")
            // The discriminating part: if the miss had registered, this second look would find the thing
            // the first look created, and the producer's counter would have been shadowed.
            again <- scope.findCounter("requsts")
            found <- scope.findCounter("requests")
            value <- found match
                case Present(c) => c.get
                case Absent     => Kyo.lift(-1L)
        yield
            assert(missing.isEmpty)
            assert(again.isEmpty)
            assert(value == 1L)
        end for
    }

    "Stat.findGauge reads the producer's thunk rather than replacing it, which initGauge would" in {
        val scope = Stat.initScope("stattest-find-gauge")
        val _     = scope.initGauge("temperature")(42.0)
        for
            found <- scope.findGauge("temperature")
            v <- found match
                case Present(g) => g.collect
                case Absent     => Kyo.lift(-1.0)
        yield
            assert(found.isDefined)
            assert(v == 42.0)
        end for
    }

    "Stat.findHistogram reads the distribution non-destructively" in {
        val scope    = Stat.initScope("stattest-find-histogram")
        val produced = scope.initHistogram("latency")
        for
            _     <- produced.observe(10L)
            _     <- produced.observe(20L)
            found <- scope.findHistogram("latency")
            first <- found match
                case Present(h) => h.summary.map(s => (s.count, s.sum))
                case Absent     => Kyo.lift((-1L, -1.0))
            second <- found match
                case Present(h) => h.summary.map(s => (s.count, s.sum))
                case Absent     => Kyo.lift((-1L, -1.0))
        yield
            assert(first._1 == 2L)
            assert(first._2 == 30.0)
            // Re-reading sees the same lifetime totals: a draining read would report 0 here, which is what
            // makes this safe for a dashboard polling alongside an exporter.
            assert(second == first)
        end for
    }

    "Histogram.summary sees observations made through the same registered path" in {
        // The singleton-per-path dedup is what lets a reader and a writer share an instrument; the safe read
        // has to observe that, not a fresh empty one.
        val writer = Stat.initScope("stattest-safe-summary-shared").initHistogram("latency")
        val reader = Stat.initScope("stattest-safe-summary-shared").initHistogram("latency")
        for
            _ <- writer.observe(5.0)
            s <- reader.summary
        yield assert(s.count == 1)
        end for
    }

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
