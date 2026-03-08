package kyo.stats.internal

import org.scalatest.freespec.AnyFreeSpec

class UnsafeHistogramTest extends AnyFreeSpec {

    def newHistogram() = new UnsafeHistogram(UnsafeHistogram.defaultBoundaries)

    "observe and count" - {
        "empty histogram" in {
            val h = newHistogram()
            assert(h.summary().count == 0)
            val s = h.summary()
            assert(s.min == 0.0)
            assert(s.max == 0.0)
        }

        "single value" in {
            val h = newHistogram()
            h.observe(42.0)
            assert(h.summary().count == 1)
            val s = h.summary()
            assert(s.min == 25.0)
            assert(s.max == 50.0)
        }

        "multiple values" in {
            val h = newHistogram()
            h.observe(10.0)
            h.observe(20.0)
            h.observe(30.0)
            assert(h.summary().count == 3)
            val s = h.summary()
            assert(s.min <= 10.0)
            assert(s.max >= 30.0)
        }

        "long values" in {
            val h = newHistogram()
            h.observe(100L)
            h.observe(200L)
            assert(h.summary().count == 2)
            val s = h.summary()
            assert(s.min <= 100.0)
            assert(s.max >= 200.0)
        }

        "fractional values" in {
            val h = newHistogram()
            h.observe(0.5)
            h.observe(0.7)
            h.observe(0.3)
            assert(h.summary().count == 3)
        }

        "same value multiple times" in {
            val h = newHistogram()
            for (_ <- 1 to 10) h.observe(42.0)
            assert(h.summary().count == 10)
        }

        "negative values" in {
            val h = newHistogram()
            h.observe(-100.0)
            h.observe(-0.5)
            h.observe(-0.001)
            assert(h.summary().count == 3)
            val s = h.summary()
            assert(s.bucketCounts(0) == 3)
        }

        "zero value" in {
            val h = newHistogram()
            h.observe(0.0)
            assert(h.summary().count == 1)
            val s = h.summary()
            assert(s.bucketCounts(0) == 1)
        }

        "Double.MaxValue" in {
            val h = newHistogram()
            h.observe(Double.MaxValue)
            assert(h.summary().count == 1)
            val s       = h.summary()
            val lastIdx = s.bucketCounts.length - 1
            assert(s.bucketCounts(lastIdx) == 1)
        }

        "Double.MinValue (negative)" in {
            val h = newHistogram()
            h.observe(Double.MinValue)
            assert(h.summary().count == 1)
            val s = h.summary()
            // MinValue is a tiny positive number (5e-324), goes in first bucket
            assert(s.bucketCounts(0) == 1)
        }

        "NaN" in {
            val h = newHistogram()
            h.observe(Double.NaN)
            assert(h.summary().count == 1)
            // NaN should land somewhere without crashing
        }

        "positive infinity" in {
            val h = newHistogram()
            h.observe(Double.PositiveInfinity)
            assert(h.summary().count == 1)
            val s       = h.summary()
            val lastIdx = s.bucketCounts.length - 1
            assert(s.bucketCounts(lastIdx) == 1)
        }

        "negative infinity" in {
            val h = newHistogram()
            h.observe(Double.NegativeInfinity)
            assert(h.summary().count == 1)
            val s = h.summary()
            assert(s.bucketCounts(0) == 1)
        }
    }

    "bucket assignment" - {
        "value at boundary goes into that bucket" in {
            val h = newHistogram()
            h.observe(5.0)
            val s = h.summary()
            assert(s.bucketCounts(1) == 1)
        }

        "value exactly at first boundary (0.0)" in {
            val h = newHistogram()
            h.observe(0.0)
            val s = h.summary()
            assert(s.bucketCounts(0) == 1)
            assert(s.count == 1)
        }

        "value exactly at last boundary (10000.0)" in {
            val h = newHistogram()
            h.observe(10000.0)
            val s           = h.summary()
            val boundaryIdx = UnsafeHistogram.defaultBoundaries.length - 1
            assert(s.bucketCounts(boundaryIdx) == 1)
        }

        "value at each default boundary" in {
            val h      = newHistogram()
            val bounds = UnsafeHistogram.defaultBoundaries
            for (i <- 0 until bounds.length)
                h.observe(bounds(i))
            val s = h.summary()
            assert(s.count == bounds.length)
            for (i <- 0 until bounds.length)
                assert(s.bucketCounts(i) >= 1, s"boundary ${bounds(i)} at index $i should have count >= 1")
        }

        "value just below boundary" in {
            val h = new UnsafeHistogram(Array(10.0, 20.0))
            h.observe(9.999)
            val s = h.summary()
            assert(s.bucketCounts(0) == 1)
        }

        "value just above boundary" in {
            val h = new UnsafeHistogram(Array(10.0, 20.0))
            h.observe(10.001)
            val s = h.summary()
            assert(s.bucketCounts(1) == 1)
        }

        "value between boundaries" in {
            val h = newHistogram()
            h.observe(3.0)
            val s = h.summary()
            assert(s.bucketCounts(1) == 1)
        }

        "value below first boundary" in {
            val h = newHistogram()
            h.observe(-1.0)
            val s = h.summary()
            assert(s.bucketCounts(0) == 1)
        }

        "value above last boundary" in {
            val h = newHistogram()
            h.observe(20000.0)
            val s       = h.summary()
            val lastIdx = s.bucketCounts.length - 1
            assert(s.bucketCounts(lastIdx) == 1)
        }

        "all values in overflow bucket" in {
            val h = newHistogram()
            for (_ <- 1 to 5) h.observe(50000.0)
            val s       = h.summary()
            val lastIdx = s.bucketCounts.length - 1
            assert(s.bucketCounts(lastIdx) == 5)
            assert(s.bucketCounts.take(lastIdx).forall(_ == 0))
        }

        "values across multiple buckets" in {
            val h = newHistogram()
            h.observe(3.0)
            h.observe(50.0)
            h.observe(500.0)
            h.observe(15000.0)
            val s = h.summary()
            assert(s.count == 4)
            assert(s.bucketCounts.sum == 4)
        }

        "same value in same bucket" in {
            val h = newHistogram()
            for (_ <- 1 to 100) h.observe(42.0)
            val s = h.summary()
            assert(s.bucketCounts(4) == 100)
            assert(s.count == 100)
        }

        "inclusive upper bound semantics" in {
            val h = new UnsafeHistogram(Array(10.0, 20.0, 30.0))
            h.observe(10.0) // exactly at boundary -> bucket 0 (<=10)
            h.observe(20.0) // exactly at boundary -> bucket 1 (<=20)
            h.observe(30.0) // exactly at boundary -> bucket 2 (<=30)
            val s = h.summary()
            assert(s.bucketCounts(0) == 1)
            assert(s.bucketCounts(1) == 1)
            assert(s.bucketCounts(2) == 1)
            assert(s.bucketCounts(3) == 0) // overflow empty
        }

        "multiple negative values in first bucket" in {
            val h = new UnsafeHistogram(Array(0.0, 10.0))
            h.observe(-100.0)
            h.observe(-0.001)
            val s = h.summary()
            assert(s.bucketCounts(0) == 2)
        }
    }

    "summary" - {
        "boundaries match default" in {
            val h = newHistogram()
            val s = h.summary()
            assert(s.boundaries.toSeq == UnsafeHistogram.defaultBoundaries.toSeq)
        }

        "bucketCounts length is boundaries + 1" in {
            val h = newHistogram()
            val s = h.summary()
            assert(s.bucketCounts.length == s.boundaries.length + 1)
        }

        "min inferred from lowest non-empty bucket" in {
            val h = newHistogram()
            h.observe(42.0)
            h.observe(500.0)
            val s = h.summary()
            assert(s.min == 25.0)
        }

        "max inferred from highest non-empty bucket" in {
            val h = newHistogram()
            h.observe(42.0)
            h.observe(500.0)
            val s = h.summary()
            assert(s.max == 500.0)
        }

        "min/max for values in first bucket" in {
            val h = newHistogram()
            h.observe(-5.0)
            val s = h.summary()
            assert(s.min == 0.0)
            assert(s.max == 0.0)
        }

        "min/max for values in overflow bucket" in {
            val h = newHistogram()
            h.observe(50000.0)
            val s = h.summary()
            assert(s.min == 10000.0)
            assert(s.max == 10000.0)
        }

        "count matches total observations" in {
            val h = newHistogram()
            for (i <- 1 to 100) h.observe(i.toDouble)
            val s = h.summary()
            assert(s.count == 100)
            assert(s.bucketCounts.sum == 100)
        }

        "count equals bucketCounts sum" in {
            val h = newHistogram()
            h.observe(1.0)
            h.observe(50.0)
            h.observe(500.0)
            h.observe(50000.0)
            val s = h.summary()
            assert(s.count == s.bucketCounts.sum)
        }

        "empty summary" in {
            val h = newHistogram()
            val s = h.summary()
            assert(s.count == 0)
            assert(s.min == 0.0)
            assert(s.max == 0.0)
            assert(s.bucketCounts.forall(_ == 0))
        }
    }

    "percentile" - {
        "returns 0 for empty histogram" in {
            val h = newHistogram()
            assert(h.summary().percentile(50.0) == 0.0)
        }

        "single value" in {
            val h = newHistogram()
            h.observe(42.0)
            val p50 = h.summary().percentile(50.0)
            assert(p50 >= 0.0 && p50 <= 50.0)
        }

        "percentile ordering" in {
            val h = newHistogram()
            for (i <- 1 to 1000) h.observe(i.toDouble)
            val p50 = h.summary().percentile(50.0)
            val p90 = h.summary().percentile(90.0)
            val p99 = h.summary().percentile(99.0)
            assert(p50 < p90)
            assert(p90 < p99)
        }

        "p0 returns low value" in {
            val h = newHistogram()
            for (i <- 1 to 100) h.observe(i.toDouble)
            val p0 = h.summary().percentile(0.0)
            assert(p0 <= 5.0)
        }

        "p100 returns high value" in {
            val h = newHistogram()
            for (i <- 1 to 100) h.observe(i.toDouble)
            val p100 = h.summary().percentile(100.0)
            assert(p100 >= 75.0)
        }

        "all values in one bucket interpolates within range" in {
            val h = newHistogram()
            for (_ <- 1 to 100) h.observe(30.0)
            val p50 = h.summary().percentile(50.0)
            assert(p50 >= 25.0 && p50 <= 50.0)
        }

        "all values in overflow bucket" in {
            val h = newHistogram()
            for (_ <- 1 to 100) h.observe(50000.0)
            val p50 = h.summary().percentile(50.0)
            assert(p50 == 10000.0)
        }

        "values only below first boundary" in {
            val h = newHistogram()
            for (_ <- 1 to 100) h.observe(-5.0)
            val p50 = h.summary().percentile(50.0)
            assert(p50 == 0.0)
        }

        "empty boundaries" in {
            val h = new UnsafeHistogram(Array.empty[Double])
            h.observe(42.0)
            val p50 = h.summary().percentile(50.0)
            assert(p50 == 0.0)
        }
    }

    "custom boundaries" - {
        "simple 3-bucket histogram" in {
            val h = new UnsafeHistogram(Array(10.0, 20.0, 30.0))
            h.observe(5.0)
            h.observe(15.0)
            h.observe(25.0)
            h.observe(50.0)
            val s = h.summary()
            assert(s.boundaries.toSeq == Seq(10.0, 20.0, 30.0))
            assert(s.bucketCounts.length == 4)
            assert(s.bucketCounts(0) == 1)
            assert(s.bucketCounts(1) == 1)
            assert(s.bucketCounts(2) == 1)
            assert(s.bucketCounts(3) == 1)
        }

        "single boundary" in {
            val h = new UnsafeHistogram(Array(100.0))
            h.observe(50.0)
            h.observe(150.0)
            val s = h.summary()
            assert(s.bucketCounts.length == 2)
            assert(s.bucketCounts(0) == 1)
            assert(s.bucketCounts(1) == 1)
        }

        "empty boundaries" in {
            val h = new UnsafeHistogram(Array.empty[Double])
            h.observe(1.0)
            h.observe(100.0)
            val s = h.summary()
            assert(s.boundaries.isEmpty)
            assert(s.bucketCounts.length == 1)
            assert(s.bucketCounts(0) == 2)
            assert(s.count == 2)
        }

        "percentile with custom boundaries" in {
            val h = new UnsafeHistogram(Array(10.0, 100.0, 1000.0))
            for (_ <- 1 to 50) h.observe(5.0)
            for (_ <- 1 to 50) h.observe(500.0)
            val p50 = h.summary().percentile(50.0)
            val p90 = h.summary().percentile(90.0)
            assert(p50 < p90)
        }

        "min/max with custom boundaries" in {
            val h = new UnsafeHistogram(Array(10.0, 100.0, 1000.0))
            h.observe(50.0)
            h.observe(500.0)
            val s = h.summary()
            assert(s.min == 10.0)
            assert(s.max == 1000.0)
        }

        "wide boundaries" in {
            val h = new UnsafeHistogram(Array(0.001, 0.01, 0.1, 1.0, 10.0, 100.0, 1000.0, 10000.0, 100000.0))
            h.observe(0.005)
            h.observe(5.0)
            h.observe(50000.0)
            val s = h.summary()
            assert(s.count == 3)
            assert(s.bucketCounts.sum == 3)
        }
    }

    "large volume" in {
        val h = newHistogram()
        for (i <- 0 until 100000) h.observe(i.toDouble)
        val s = h.summary()
        assert(s.count == 100000)
        assert(s.bucketCounts.sum == 100000)
        assert(s.min == 0.0)
        assert(s.max == 10000.0)
    }

    "defaultBoundaries" in {
        val b = UnsafeHistogram.defaultBoundaries
        assert(b.length == 15)
        assert(b.head == 0.0)
        assert(b.last == 10000.0)
        assert(b.toSeq == b.sorted.toSeq)
    }

    "invalid boundary configs" - {
        "rejects unsorted boundaries" in {
            val e = intercept[IllegalArgumentException] {
                new UnsafeHistogram(Array(30.0, 10.0, 20.0))
            }
            assert(e.getMessage.contains("strictly sorted"))
        }

        "rejects duplicate boundaries" in {
            val e = intercept[IllegalArgumentException] {
                new UnsafeHistogram(Array(10.0, 10.0, 20.0))
            }
            assert(e.getMessage.contains("strictly sorted"))
        }

        "rejects NaN in boundaries" in {
            val e = intercept[IllegalArgumentException] {
                new UnsafeHistogram(Array(10.0, Double.NaN, 30.0))
            }
            assert(e.getMessage.contains("NaN"))
        }

        "rejects positive infinity in boundaries" in {
            val e = intercept[IllegalArgumentException] {
                new UnsafeHistogram(Array(10.0, Double.PositiveInfinity))
            }
            assert(e.getMessage.contains("Infinity"))
        }

        "rejects negative infinity in boundaries" in {
            val e = intercept[IllegalArgumentException] {
                new UnsafeHistogram(Array(Double.NegativeInfinity, 10.0))
            }
            assert(e.getMessage.contains("Infinity"))
        }

        "rejects all-same boundaries" in {
            val e = intercept[IllegalArgumentException] {
                new UnsafeHistogram(Array(5.0, 5.0, 5.0))
            }
            assert(e.getMessage.contains("strictly sorted"))
        }

        "accepts very close boundaries" in {
            val h = new UnsafeHistogram(Array(1.0, 1.0 + 1e-15, 1.0 + 2e-15))
            h.observe(1.0 + 1e-15)
            val s = h.summary()
            assert(s.count == 1)
        }

        "accepts negative boundaries when sorted" in {
            val h = new UnsafeHistogram(Array(-20.0, -10.0, 0.0, 10.0))
            h.observe(-15.0)
            h.observe(-5.0)
            h.observe(5.0)
            val s = h.summary()
            assert(s.count == 3)
            assert(s.bucketCounts(1) == 1) // -15 <= -10 is true (bucket 1)
            assert(s.bucketCounts(2) == 1) // -5 <= 0 is true (bucket 2)
            assert(s.bucketCounts(3) == 1) // 5 <= 10 is true (bucket 3)
        }

        "rejects descending boundaries" in {
            val e = intercept[IllegalArgumentException] {
                new UnsafeHistogram(Array(100.0, 50.0, 10.0))
            }
            assert(e.getMessage.contains("strictly sorted"))
        }

        "rejects single duplicate pair" in {
            val e = intercept[IllegalArgumentException] {
                new UnsafeHistogram(Array(10.0, 20.0, 20.0, 30.0))
            }
            assert(e.getMessage.contains("strictly sorted"))
        }

        "accepts single boundary" in {
            val h = new UnsafeHistogram(Array(10.0))
            h.observe(5.0)
            h.observe(15.0)
            val s = h.summary()
            assert(s.bucketCounts(0) == 1)
            assert(s.bucketCounts(1) == 1)
        }

        "accepts empty boundaries" in {
            val h = new UnsafeHistogram(Array.empty[Double])
            h.observe(42.0)
            assert(h.summary().count == 1)
        }
    }

}
