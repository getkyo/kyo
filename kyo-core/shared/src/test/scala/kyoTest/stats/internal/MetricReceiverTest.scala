package kyoTest.stats.internal

import kyoTest.KyoTest
import kyo.stats.*
import kyo.stats.internal.*
import kyo.*

class MetricReceiverTest extends KyoTest:

    "MetricReceiver.noop" in run {
        val noop = MetricReceiver.noop
        assert(noop.counter(
            Nil,
            "testCounter",
            "Test Counter",
            "unit",
            Attributes.empty
        ) == Counter.noop)
        assert(noop.histogram(
            Nil,
            "testHistogram",
            "Test Histogram",
            "unit",
            Attributes.empty
        ) == Histogram.noop)
        assert(noop.gauge(
            Nil,
            "testGauge",
            "Test Gauge",
            "unit",
            Attributes.empty
        )(42.0) == Gauge.noop)
        assert(noop.startSpan(
            Nil,
            "testSpan",
            None,
            Attributes.empty
        ) == Span.noop)
    }

    "MetricReceiver.all" - {
        val mockReceiver1    = new TestMetricReceiver
        val mockReceiver2    = new TestMetricReceiver
        val combinedReceiver = MetricReceiver.all(List(mockReceiver1, mockReceiver2))

        "counter" in run {
            combinedReceiver.counter(Nil, "testCounter", "Test Counter", "unit", Attributes.empty)
            assert(mockReceiver1.counterCreated && mockReceiver2.counterCreated)
        }

        "histogram" in run {
            combinedReceiver.histogram(
                Nil,
                "testHistogram",
                "Test Histogram",
                "unit",
                Attributes.empty
            )
            assert(mockReceiver1.histogramCreated && mockReceiver2.histogramCreated)
        }

        "gauge" in run {
            combinedReceiver.gauge(Nil, "testGauge", "Test Gauge", "unit", Attributes.empty)(42.0)
            assert(mockReceiver1.gaugeCreated && mockReceiver2.gaugeCreated)
        }

        "startSpan" in run {
            combinedReceiver.startSpan(Nil, "testSpan", None, Attributes.empty)
            assert(mockReceiver1.spanStarted && mockReceiver2.spanStarted)
        }
    }

    class TestMetricReceiver extends MetricReceiver:

        var spanStarted = false

        def startSpan(
            scope: List[String],
            name: String,
            parent: Option[Span],
            attributes: Attributes
        ): Span < IOs =
            spanStarted = true
            Span.noop
        end startSpan

        var counterCreated   = false
        var histogramCreated = false
        var gaugeCreated     = false

        def counter(
            scope: List[String],
            name: String,
            description: String,
            unit: String,
            a: Attributes
        ) =
            counterCreated = true
            Counter.noop
        end counter

        def histogram(
            scope: List[String],
            name: String,
            description: String,
            unit: String,
            a: Attributes
        ) =
            histogramCreated = true
            Histogram.noop
        end histogram

        def gauge(
            scope: List[String],
            name: String,
            description: String,
            unit: String,
            a: Attributes
        )(f: => Double) =
            gaugeCreated = true
            Gauge.noop
        end gauge
    end TestMetricReceiver
end MetricReceiverTest
