package kyoTest.stats

import kyo.*
import kyo.stats.*
import kyoTest.KyoTest

class HistogramTest extends KyoTest:

    "noop" in run {
        for
            _ <- Histogram.noop.observe(1.0)
            _ <- Histogram.noop.observe(1.0, Attributes.add("test", 1))
        yield succeed
    }

    "unsafe" in run {
        val unsafe    = new TestHistogram
        val histogram = Histogram(unsafe)
        for
            _ <- histogram.observe(1.0)
            _ <- histogram.observe(1.0, Attributes.add("test", 1))
        yield assert(unsafe.observations == 2)
        end for
    }

    "all" - {
        "empty" in run {
            assert(Histogram.all(Nil) == Histogram.noop)
        }
        "one" in run {
            val histogram = Histogram(new TestHistogram)
            assert(Histogram.all(List(histogram)) == histogram)
        }
        "multiple" in run {
            val unsafe1   = new TestHistogram
            val unsafe2   = new TestHistogram
            val histogram = Histogram.all(List(Histogram(unsafe1), Histogram(unsafe2)))
            for
                _ <- histogram.observe(1.0)
                _ <- histogram.observe(1.0, Attributes.add("test", 1))
            yield assert(unsafe1.observations == 2 && unsafe2.observations == 2)
            end for
        }
    }

    class TestHistogram extends Histogram.Unsafe:
        var observations = 0
        def observe(v: Double, b: Attributes) =
            observations += 1
        def observe(v: Double) =
            observations += 1
        def attributes(b: Attributes) = this
    end TestHistogram
end HistogramTest
