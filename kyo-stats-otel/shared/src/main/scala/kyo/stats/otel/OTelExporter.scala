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

        meter.gaugeBuilder(path.mkString(".") + ".p50")
            .setDescription(description)
            .buildWithCallback(_.record(summary.p50, Attributes.empty()))

        meter.gaugeBuilder(path.mkString(".") + ".p90")
            .setDescription(description)
            .buildWithCallback(_.record(summary.p90, Attributes.empty()))

        meter.gaugeBuilder(path.mkString(".") + ".p99")
            .setDescription(description)
            .buildWithCallback(_.record(summary.p99, Attributes.empty()))

        meter.gaugeBuilder(path.mkString(".") + ".p999")
            .setDescription(description)
            .buildWithCallback(_.record(summary.p999, Attributes.empty()))

        meter.gaugeBuilder(path.mkString(".") + ".p9999")
            .setDescription(description)
            .buildWithCallback(_.record(summary.p9999, Attributes.empty()))

        meter.gaugeBuilder(path.mkString(".") + ".min")
            .setDescription(description)
            .buildWithCallback(_.record(summary.min, Attributes.empty()))

        meter.gaugeBuilder(path.mkString(".") + ".max")
            .setDescription(description)
            .buildWithCallback(_.record(summary.max, Attributes.empty()))

        meter.gaugeBuilder(path.mkString(".") + ".mean")
            .setDescription(description)
            .buildWithCallback(_.record(summary.mean, Attributes.empty()))

        meter.gaugeBuilder(path.mkString(".") + ".count")
            .setDescription(description)
            .buildWithCallback(_.record(summary.count.toDouble, Attributes.empty()))
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
