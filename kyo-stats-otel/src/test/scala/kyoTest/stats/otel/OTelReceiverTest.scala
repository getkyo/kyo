package kyoTest.stats.otel

import io.opentelemetry.api.trace.{Span => OSpan}
import io.opentelemetry.context.Context
import io.opentelemetry.api._
import io.opentelemetry.api.metrics._
import io.opentelemetry.sdk.metrics._
import io.opentelemetry.sdk.metrics.`export`._
import io.opentelemetry.sdk.trace._
import io.opentelemetry.sdk.trace.`export`._
import kyo._
import kyo.stats._
import kyo.stats.otel._
import kyoTest._
import io.opentelemetry.exporters.inmemory._
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.OpenTelemetrySdkBuilder

class OTelReceiverTest extends KyoTest {

  "metrics" in run {
    val stats     = Stats.initScope("test")
    val counter   = stats.initCounter("a")
    val histogram = stats.initHistogram("b")
    stats.initGauge("c")(99d)
    for {
      _ <- counter.inc
      _ <- counter.add(1)
      _ <- counter.add(2, Attributes.of("test", 3))
      _ <- histogram.observe(42d)
      _ <- histogram.observe(24d, Attributes.of("test", 3))
    } yield succeed
  }

  "traces" in run {
    val stats = Stats.initScope("test")
    stats.traceSpan("tspan") {
      42d
    }.map { r =>
      assert(r == 42d)
    }
  }
}
