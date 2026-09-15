package kyo.stats.internal

import kyo.AllowUnsafe
import org.scalatest.freespec.AnyFreeSpec
import scala.math.Ordering.Implicits.seqOrdering

class StatsRegistryTest extends AnyFreeSpec {

    implicit val _au: AllowUnsafe = AllowUnsafe.embrace.danger

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

    "find" - {
        "counter miss returns None and registers nothing" in {
            val scope = StatsRegistry.scope("find", "counter", "miss")
            assert(scope.findCounter("absent").isEmpty)
            assert(!StatsRegistry.internal.counters.map.containsKey(List("find", "counter", "miss", "absent")))
        }

        "histogram miss returns None and registers nothing" in {
            val scope = StatsRegistry.scope("find", "histogram", "miss")
            assert(scope.findHistogram("absent").isEmpty)
            assert(!StatsRegistry.internal.histograms.map.containsKey(List("find", "histogram", "miss", "absent")))
        }

        "gauge miss returns None and registers nothing" in {
            val scope = StatsRegistry.scope("find", "gauge", "miss")
            assert(scope.findGauge("absent").isEmpty)
            assert(!StatsRegistry.internal.gauges.map.containsKey(List("find", "gauge", "miss", "absent")))
        }

        "counterGauge miss returns None and registers nothing" in {
            val scope = StatsRegistry.scope("find", "counterGauge", "miss")
            assert(scope.findCounterGauge("absent").isEmpty)
            assert(!StatsRegistry.internal.counterGauges.map.containsKey(List("find", "counterGauge", "miss", "absent")))
        }

        "a misspelled path is not confusable with a registered one" in {
            val scope = StatsRegistry.scope("find", "misspelling")
            val real  = scope.histogram("available", "bytes")
            real.observe(7.0)
            assert(scope.findHistogram("avaliable").isEmpty)
            assert(scope.findHistogram("available").map(_.summary().count).contains(1L))
        }

        "returns the registered instrument, not a fresh one" in {
            val scope   = StatsRegistry.scope("find", "identity")
            val counter = scope.counter("hits", "count")
            counter.add(5)
            val found = scope.findCounter("hits")
            assert(found.isDefined)
            assert(found.get eq counter)
        }

        "finds an instrument registered through a differently split scope" in {
            val minted = StatsRegistry.scope("find", "split", "cpu").histogram("total.rate", "ns/s")
            minted.observe(3.0)
            val found = StatsRegistry.scope("find", "split").scope("cpu").findHistogram("total.rate")
            assert(found.map(_.summary().count).contains(1L))
        }
    }

    "gauge read-back does not poison the producer's value" - {
        "reader first, then producer" in {
            val scope = StatsRegistry.scope("poison", "reader", "first")
            assert(scope.findGauge("total").isEmpty)
            val produced = scope.gauge("total", "bytes")(42.0)
            assert(scope.findGauge("total").map(_.collect()).contains(42.0))
            assert(produced.collect() == 42.0)
        }

        "a reader that MINTS instead of finding takes the path, which is the hazard findGauge exists to close" in {
            // The registration contract this pins: a gauge's value is its thunk and the first registration
            // wins. A consumer that reaches a path before the producer therefore installs its own placeholder
            // permanently, and the producer's later registration silently hands that placeholder back.
            val scope             = StatsRegistry.scope("poison", "minting", "reader")
            val readerPlaceholder = scope.gauge("total", "bytes")(Double.NaN)
            val produced          = scope.gauge("total", "bytes")(42.0)
            assert(produced eq readerPlaceholder)
            assert(java.lang.Double.isNaN(produced.collect()))
            // findGauge is the read that does not take the path: it reports absence instead of creating it.
            assert(StatsRegistry.scope("poison", "minting", "unread").findGauge("total").isEmpty)
        }

        "producer first, then reader" in {
            val scope    = StatsRegistry.scope("poison", "producer", "first")
            val produced = scope.gauge("total", "bytes")(42.0)
            assert(scope.findGauge("total").map(_.collect()).contains(42.0))
            assert(produced.collect() == 42.0)
        }
    }

    "snapshot" - {
        "enumerates every registered instrument under a prefix" in {
            val scope = StatsRegistry.scope("snapshot", "enumerate")
            scope.counter("c", "a counter").inc()
            scope.histogram("h", "a histogram").observe(1.0)
            scope.gauge("g", "a gauge")(1.0)
            scope.counterGauge("cg", "a counter gauge")(1L)
            val paths = StatsRegistry.snapshot("snapshot", "enumerate").map(_.path)
            assert(paths.contains(List("snapshot", "enumerate", "c")))
            assert(paths.contains(List("snapshot", "enumerate", "h")))
            assert(paths.contains(List("snapshot", "enumerate", "g")))
            assert(paths.contains(List("snapshot", "enumerate", "cg")))
        }

        "carries the instrument kind, description and live handle" in {
            val scope = StatsRegistry.scope("snapshot", "kinds")
            val h     = scope.histogram("latency", "milliseconds")
            h.observe(12.0)
            val found = StatsRegistry.snapshot("snapshot", "kinds").filter(_.path.last == "latency")
            assert(found.size == 1)
            found.head.instrument match {
                case StatsRegistry.Instrument.Histogram(unsafe, description) =>
                    assert(description == "milliseconds")
                    assert(unsafe.summary().count == 1)
                    assert(unsafe eq h)
                case other => fail("expected a histogram, got " + other)
            }
        }

        "discovers dynamically named families without knowing the names" in {
            val disks = StatsRegistry.scope("snapshot", "disk")
            disks.scope("root").gauge("total", "bytes")(1.0)
            disks.scope("data_vol").gauge("total", "bytes")(2.0)
            val stores = StatsRegistry.snapshot("snapshot", "disk").map(_.path(2)).distinct.sorted
            assert(stores == List("data_vol", "root"))
        }

        "an empty prefix enumerates the whole registry" in {
            StatsRegistry.scope("snapshot", "whole").counter("c", "a counter").inc()
            assert(StatsRegistry.snapshot().exists(_.path == List("snapshot", "whole", "c")))
        }

        "is sorted by path" in {
            val paths = StatsRegistry.snapshot().map(_.path)
            assert(paths == paths.sorted)
        }
    }
}
