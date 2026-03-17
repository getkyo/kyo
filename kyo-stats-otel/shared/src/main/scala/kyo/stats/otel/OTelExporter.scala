package kyo.stats.otel

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.Meter
import kyo.stats.internal.*

class OTelExporter extends StatsExporter {

    private val meter: Meter = GlobalOpenTelemetry.getMeter("kyo-stats")

    override def counter(path: List[String], description: String, delta: Long): Unit = {
        val counter = meter
            .counterBuilder(path.mkString("."))
            .setDescription(description)
            .setUnit("1")
            .build()
        counter.add(delta, Attributes.empty())
    }

    override def histogram(path: List[String], description: String, summary: Summary): Unit = {
        val name = path.mkString(".")

        meter.gaugeBuilder(name + ".p50")
            .setDescription(description)
            .buildWithCallback(_.record(summary.percentile(50.0), Attributes.empty()))

        meter.gaugeBuilder(name + ".p90")
            .setDescription(description)
            .buildWithCallback(_.record(summary.percentile(90.0), Attributes.empty()))

        meter.gaugeBuilder(name + ".p99")
            .setDescription(description)
            .buildWithCallback(_.record(summary.percentile(99.0), Attributes.empty()))

        meter.gaugeBuilder(name + ".p999")
            .setDescription(description)
            .buildWithCallback(_.record(summary.percentile(99.9), Attributes.empty()))

        meter.gaugeBuilder(name + ".p9999")
            .setDescription(description)
            .buildWithCallback(_.record(summary.percentile(99.99), Attributes.empty()))

        meter.gaugeBuilder(name + ".min")
            .setDescription(description)
            .buildWithCallback(_.record(summary.min, Attributes.empty()))

        meter.gaugeBuilder(name + ".max")
            .setDescription(description)
            .buildWithCallback(_.record(summary.max, Attributes.empty()))
        ()
    }

    override def gauge(path: List[String], description: String, currentValue: Double): Unit = {
        meter
            .gaugeBuilder(path.mkString("."))
            .setDescription(description)
            .setUnit("1")
            .buildWithCallback(_.record(currentValue, Attributes.empty()))
        ()
    }
}
