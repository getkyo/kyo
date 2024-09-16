package kyo.stats.internal

import kyo.stats.internal.*
import org.scalatest.freespec.AnyFreeSpec

class StatsRegistryTest extends AnyFreeSpec {

    "exporter" in {
        class TestExporter extends StatsExporter {
            var counterCalls: Seq[(List[String], String, Long)]      = Seq.empty
            var histogramCalls: Seq[(List[String], String, Summary)] = Seq.empty
            var gaugeCalls: Seq[(List[String], String, Double)]      = Seq.empty

            override def counter(path: List[String], description: String, delta: Long): Unit =
                counterCalls :+= ((path, description, delta))

            override def histogram(path: List[String], description: String, summary: Summary): Unit =
                histogramCalls :+= ((path, description, summary))

            override def gauge(path: List[String], description: String, currentValue: Double): Unit =
                gaugeCalls :+= ((path, description, currentValue))
        }

        val scope = StatsRegistry.scope("exporter", "test")

        val counter = scope.counter("my_counter", "A test counter")
        counter.inc()
        counter.add(10)

        val histogram = scope.histogram("my_histogram", "A test histogram")
        histogram.observe(10)
        histogram.observe(20)
        histogram.observe(30)

        var gaugeValue = 100.0
        val gauge      = scope.gauge("my_gauge", "A test gauge")(gaugeValue)
        val _          = gauge
        val exporter   = new TestExporter
        StatsRegistry.addExporter(exporter)

        StatsRegistry.internal.refresh()

        assert(exporter.counterCalls.head == (List("exporter", "test", "my_counter"), "A test counter", 11))

        val (histogramPath, histogramDescription, histogramSummary) = exporter.histogramCalls.head
        assert(histogramPath == List("exporter", "test", "my_histogram"))
        assert(histogramDescription == "A test histogram")
        assert(histogramSummary.count == 3)
        assert(histogramSummary.min <= 10)
        assert(histogramSummary.max >= 30)

        assert(exporter.gaugeCalls.head == (List("exporter", "test", "my_gauge"), "A test gauge", 100.0))

        gaugeValue = 200.0
        StatsRegistry.internal.refresh()

        assert(exporter.gaugeCalls.last == (List("exporter", "test", "my_gauge"), "A test gauge", 200.0))

        StatsRegistry.removeExporter(exporter)
    }

    "scope" - {
        "create scope with path" in {
            val scope = StatsRegistry.scope("scope", "create", "path")
            assert(scope.path == List("scope", "create", "path"))
        }

        "create nested scope" in {
            val scope1 = StatsRegistry.scope("scope", "create", "nested")
            val scope2 = scope1.scope("nested", "scope")
            assert(scope2.path == List("scope", "create", "nested", "nested", "scope"))
        }
    }

    "counter" - {
        "create and update counter" in {
            val scope   = StatsRegistry.scope("counter", "create", "update")
            val counter = scope.counter("my_counter", "A test counter")
            counter.inc()
            counter.add(10)
            assert(counter.get() == 11)
        }

        "handle overflow" in {
            val scope   = StatsRegistry.scope("counter", "overflow")
            val counter = scope.counter("my_counter", "A test counter")
            counter.add(Long.MaxValue - 10)
            assert(counter.delta() == Long.MaxValue - 10)
            assert(counter.getLast() == Long.MaxValue - 10)
            counter.add(20)
            assert(counter.delta() == 20)
            assert(counter.getLast() == 10)
        }
    }

    "histogram" - {
        "create and record values" in {
            val scope     = StatsRegistry.scope("histogram", "create", "record")
            val histogram = scope.histogram("my_histogram", "A test histogram")
            histogram.observe(10)
            histogram.observe(20)
            histogram.observe(30)
            val summary = histogram.summary()
            assert(summary.count == 3)
            assert(summary.min <= 10)
            assert(summary.max >= 30)
        }
    }

    "gauge" - {
        "create and collect gauge" in {
            val scope = StatsRegistry.scope("gauge", "create", "collect")
            var value = 100
            val gauge = scope.gauge("my_gauge", "A test gauge")(value)
            assert(gauge.collect() == 100)
            value = 200
            assert(gauge.collect() == 200)
        }
    }

    "counterGauge" - {
        "create and collect counter gauge" in {
            val scope = StatsRegistry.scope("counterGauge", "create", "collect")
            var value = 0L
            val counterGauge = scope.counterGauge("my_counter_gauge", "A test counter gauge") {
                value += 1
                value
            }
            assert(counterGauge.collect() == 1)
            assert(counterGauge.collect() == 2)
        }

        "handle overflow" in {
            val scope = StatsRegistry.scope("counterGauge", "overflow")
            var value = Long.MaxValue - 30
            val counterGauge = scope.counterGauge("my_counter_gauge", "A test counter gauge") {
                value += 20
                value
            }
            assert(counterGauge.delta() == Long.MaxValue - 10)
            assert(counterGauge.getLast() == Long.MaxValue - 10)
            assert(counterGauge.delta() == 20)
            assert(counterGauge.getLast() == 10)
        }
    }
}
