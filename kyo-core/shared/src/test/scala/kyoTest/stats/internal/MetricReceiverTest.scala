package kyoTest.stats.internal

import kyo.*
import kyo.stats.*
import kyo.stats.internal.*
import kyoTest.KyoTest

class MetricReceiverTest extends KyoTest:

    "MetricReceiver.noop" in IOs.run {
        val noop = MetricReceiver.noop
        assert(noop.counter(
            Nil,
            "testCounter",
            "Test Counter",
            "unit",
            Attributes.empty
        ) eq UnsafeCounter.noop)
        assert(noop.histogram(
            Nil,
            "testHistogram",
            "Test Histogram",
            "unit",
            Attributes.empty
        ) eq UnsafeHistogram.noop)
        assert(noop.gauge(
            Nil,
            "testGauge",
            "Test Gauge",
            "unit",
            Attributes.empty
        )(42.0) eq UnsafeGauge.noop)
    }

    "MetricReceiver.all" - {
        val mockReceiver1    = new TestMetricReceiver
        val mockReceiver2    = new TestMetricReceiver
        val combinedReceiver = MetricReceiver.all(List(mockReceiver1, mockReceiver2))

        "counter" in IOs.run {
            combinedReceiver.counter(Nil, "testCounter", "Test Counter", "unit", Attributes.empty)
            assert(mockReceiver1.counterCreated && mockReceiver2.counterCreated)
        }

        "histogram" in IOs.run {
            combinedReceiver.histogram(
                Nil,
                "testHistogram",
                "Test Histogram",
                "unit",
                Attributes.empty
            )
            assert(mockReceiver1.histogramCreated && mockReceiver2.histogramCreated)
        }

        "gauge" in IOs.run {
            combinedReceiver.gauge(Nil, "testGauge", "Test Gauge", "unit", Attributes.empty)(42.0)
            assert(mockReceiver1.gaugeCreated && mockReceiver2.gaugeCreated)
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
            UnsafeCounter.noop
        end counter

        def histogram(
            scope: List[String],
            name: String,
            description: String,
            unit: String,
            a: Attributes
        ) =
            histogramCreated = true
            UnsafeHistogram.noop
        end histogram

        def gauge(
            scope: List[String],
            name: String,
            description: String,
            unit: String,
            a: Attributes
        )(f: => Double) =
            gaugeCreated = true
            UnsafeGauge.noop
        end gauge
    end TestMetricReceiver
end MetricReceiverTest
