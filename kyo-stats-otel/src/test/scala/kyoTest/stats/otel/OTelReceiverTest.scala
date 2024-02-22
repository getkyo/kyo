package kyoTest.stats.otel

import io.opentelemetry.api.trace.{Span as OSpan}
import io.opentelemetry.context.Context
import io.opentelemetry.api.*
import io.opentelemetry.api.metrics.*
import io.opentelemetry.sdk.metrics.*
import io.opentelemetry.sdk.metrics.`export`.*
import io.opentelemetry.sdk.trace.*
import io.opentelemetry.sdk.trace.`export`.*
import kyo.*
import kyo.stats.*
import kyo.stats.otel.*
import kyoTest.*
import io.opentelemetry.exporters.inmemory.*
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.OpenTelemetrySdkBuilder

class OTelReceiverTest extends KyoTest:

    "metrics" in run {
        val stats     = Stats.initScope("test")
        val counter   = stats.initCounter("a")
        val histogram = stats.initHistogram("b")
        stats.initGauge("c")(99d)
        for
            _ <- counter.inc
            _ <- counter.add(1)
            _ <- counter.add(2, Attributes.add("test", 3))
            _ <- histogram.observe(42d)
            _ <- histogram.observe(24d, Attributes.add("test", 3))
        yield succeed
        end for
    }

    "traces" in run {
        val stats = Stats.initScope("test")
        stats.traceSpan("tspan") {
            42d
        }.map { r =>
            assert(r == 42d)
        }
    }
end OTelReceiverTest
