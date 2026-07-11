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

    // The four leaves below exercise the eager ExporterFactory scan hook in object Stat.
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

    "the eager scan val is the last val declared in object Stat (source-shape)".onlyJvm in {
        val source    = StatTest.readStatSource()
        val startMark = "object Stat:"
        val startIdx  = source.indexOf(startMark)
        assert(startIdx >= 0, "object Stat: declaration not found in Stat.scala")
        // Bound the scan to the body of `object Stat` (from its header to its `end Stat`).
        val endStatIdx = source.indexOf("\nend Stat", startIdx)
        assert(endStatIdx >= 0, "end Stat not found for object Stat")
        val body = source.substring(startIdx, endStatIdx)

        // A val DECLARATION in the object body starts a line at the 4-space object-body indent,
        // optionally with a `private[...]` / `final` modifier, then `val <name>`. This excludes
        // body-internal vals (deeper indent, e.g. the `val _ =` inside eagerExporterScan's own body).
        val declRegex = """(?m)^    (?:(?:private\[[^\]]+\]|final|lazy)\s+)*val\s+(\w+)""".r
        val valNames  = declRegex.findAllMatchIn(body).map(_.group(1)).toList
        assert(valNames.nonEmpty, "no object-body val declarations found in object Stat")
        assert(
            valNames.last == "eagerExporterScan",
            s"eagerExporterScan is not the last val declared in object Stat; declaration order was: ${valNames.mkString(", ")}"
        )
    }

    "eager scan is idempotent-safe: repeated metrics-only Stat references trigger no further construction".onlyJvm in {
        import AllowUnsafe.embrace.danger
        // object Stat's class initializer runs the SPI scan; force it and snapshot the construction count.
        val _        = Stat.kyoScope
        val baseline = StatTestExporterFactory.constructions.get()
        // The scan constructs the factory at least once at class-init. object Stat forces TraceExporter.get
        // from two independent vals (traceExporter's Local.init and the eagerExporterScan hook), so the
        // class-init construction count is 2, not 1; a duplicate construction is tolerated because a
        // side-effecting factory constructor (a collector-starting one) gates its start on a CAS, so a
        // second construction starts nothing new. What this leaf pins is that class-init runs ONCE: no
        // subsequent metrics-path reference re-scans and re-constructs the factory.
        assert(baseline >= 1, s"eager scan constructed the factory at least once at class-init, got $baseline")
        val _c = Stat.initScope("test-idempotent").initCounter("x")
        val _s = Stat.kyoScope
        val _t = Stat.initScope("test-idempotent-2").initHistogram("y")
        // No new construction happened: repeated references after class-init do not re-run the scan.
        assert(
            StatTestExporterFactory.constructions.get() == baseline,
            s"a metrics-path reference re-constructed the factory: baseline $baseline, now ${StatTestExporterFactory.constructions.get()}"
        )
    }
end StatTest

object StatTest:

    /** Reads the committed source of kyo/Stat.scala for the source-shape leaf. Resolves the shared source
      * from the JVM subproject's working directory (sbt runs kyo-core JVM tests with `kyo-core/jvm` as cwd),
      * trying the shared-source candidates so the read is robust to the exact cwd sbt uses.
      */
    def readStatSource(): String =
        val candidates = Seq(
            "../shared/src/main/scala/kyo/Stat.scala",
            "shared/src/main/scala/kyo/Stat.scala",
            "kyo-core/shared/src/main/scala/kyo/Stat.scala",
            "../../kyo-core/shared/src/main/scala/kyo/Stat.scala"
        )
        val found = candidates.map(new java.io.File(_)).find(_.isFile)
        found match
            case Some(f) => new String(java.nio.file.Files.readAllBytes(f.toPath), java.nio.charset.StandardCharsets.UTF_8)
            case None =>
                throw new AssertionError(
                    s"could not locate kyo/Stat.scala from cwd ${new java.io.File(".").getAbsolutePath}; tried ${candidates.mkString(", ")}"
                )
        end match
    end readStatSource
end StatTest
