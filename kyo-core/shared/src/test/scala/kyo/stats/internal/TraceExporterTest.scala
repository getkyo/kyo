package kyo.stats.internal

import java.time.Instant
import kyo.*
import kyo.stats.*

class TraceExporterTest extends Test:

    given AllowUnsafe = AllowUnsafe.embrace.danger

    "TraceExporter.noop" in {
        val noopExporter = TraceExporter.noop
        val span         = noopExporter.startSpan(Nil, "noopSpan", Instant.now())
        assert(span eq UnsafeTraceSpan.noop)
    }

    "TraceExporter.all" in {
        val mockExporter1    = new TestTraceExporter
        val mockExporter2    = new TestTraceExporter
        val combinedExporter = TraceExporter.all(List(mockExporter1, mockExporter2))

        combinedExporter.startSpan(Nil, "combinedSpan", Instant.now())
        assert(mockExporter1.spanStarted && mockExporter2.spanStarted)
    }

    class TestTraceExporter extends TraceExporter:
        var spanStarted = false

        def startSpan(
            scope: List[String],
            name: String,
            now: Instant,
            parent: Option[UnsafeTraceSpan],
            attributes: Attributes
        )(using AllowUnsafe): UnsafeTraceSpan =
            spanStarted = true
            UnsafeTraceSpan.noop
        end startSpan
    end TestTraceExporter
end TraceExporterTest
