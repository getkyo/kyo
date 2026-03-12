package kyo.stats.internal

import java.lang.Float.floatToIntBits
import java.lang.Float.intBitsToFloat
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder
import kyo.AllowUnsafe
import scala.annotation.tailrec

/** A fixed-bucket histogram for recording value distributions, inspired by the OTel SDK's ExplicitBucketHistogramAggregation and
  * Prometheus's fixed-bucket histogram.
  *
  * Optimized for high-throughput observation: the hot path is a binary search followed by a single LongAdder.increment() plus an atomic CAS
  * update for min/max. Count is derived at read time from a single-pass bucket snapshot. Sum is not provided since exact values are not
  * retained and the OTLP histogram data model marks sum as optional. The snapshot is not fully atomic under concurrent writes but ensures
  * internal consistency by deriving count from the same snapshotted bucket counts. Bucket semantics use inclusive upper bounds (value <=
  * boundary), matching Prometheus and OTel conventions.
  *
  * Produces non-cumulative bucket counts compatible with the OTLP histogram data model (explicitBounds + bucketCounts).
  *
  * Note: min and max are packed as two 32-bit floats in a single AtomicLong to allow a single CAS per observation. This reduces precision
  * to ~7 significant digits (vs double's ~15), which is acceptable for observability where min/max serve as dashboard hints rather than
  * exact values. The OTLP spec itself marks min and max as optional.
  *
  * @param boundaries
  *   Strictly ascending bucket boundaries. Must not contain NaN or Infinity. Creates boundaries.length + 1 buckets (the last being the
  *   overflow bucket for values above the highest boundary).
  */
class UnsafeHistogram(boundaries: Array[Double]) extends Serializable {

    require(
        boundaries.forall(d => !d.isNaN),
        "Histogram boundaries must not contain NaN"
    )
    require(
        boundaries.forall(d => !d.isInfinite),
        "Histogram boundaries must not contain Infinity"
    )
    require(
        boundaries.length < 2 || {
            @tailrec def loop(i: Int): Boolean =
                if (i >= boundaries.length)
                    true
                else if (boundaries(i) <= boundaries(i - 1))
                    false
                else
                    loop(i + 1)
            loop(1)
        },
        "Histogram boundaries must be strictly sorted in ascending order, got: " +
            boundaries.toSeq.mkString("[", ", ", "]")
    )

    private val buckets = Array.fill(boundaries.length + 1)(new LongAdder)

    // Min (upper 32 bits) and max (lower 32 bits) packed as floats in a single AtomicLong
    private val minMaxBits = new AtomicLong(pack(Float.MaxValue, Float.MinValue))

    private def pack(min: Float, max: Float): Long =
        (floatToIntBits(min).toLong << 32) | (floatToIntBits(max).toLong & 0xffffffffL)

    def observe(v: Long)(using AllowUnsafe): Unit = observe(v.toDouble)

    def observe(v: Double)(using AllowUnsafe): Unit = {
        @tailrec def findBucket(lo: Int, hi: Int): Unit =
            if (lo >= hi)
                buckets(lo).increment()
            else {
                val mid = (lo + hi) / 2
                if (boundaries(mid) < v)
                    findBucket(mid + 1, hi)
                else
                    findBucket(lo, mid)
            }
        findBucket(0, boundaries.length)
        updateMinMax(v.toFloat)
    }

    private def updateMinMax(v: Float): Unit = {
        @tailrec def loop(): Unit = {
            val cur    = minMaxBits.get()
            val curMin = intBitsToFloat((cur >>> 32).toInt)
            val curMax = intBitsToFloat(cur.toInt)
            val newMin = if (v < curMin) v else curMin
            val newMax = if (v > curMax) v else curMax
            if (newMin != curMin || newMax != curMax) {
                if (!minMaxBits.compareAndSet(cur, pack(newMin, newMax)))
                    loop()
            }
        }
        loop()
    }

    def summary()(using AllowUnsafe): Summary = {
        val counts = new Array[Long](buckets.length)
        @tailrec def loop(i: Int, total: Long): Long =
            if (i >= counts.length)
                total
            else {
                counts(i) = buckets(i).sum()
                loop(i + 1, total + counts(i))
            }
        val total = loop(0, 0L)

        val cur = minMaxBits.get()
        val min = if (total == 0) 0.0 else intBitsToFloat((cur >>> 32).toInt).toDouble
        val max = if (total == 0) 0.0 else intBitsToFloat(cur.toInt).toDouble

        Summary(
            boundaries = boundaries,
            bucketCounts = counts,
            count = total,
            min = min,
            max = max
        )
    }

}

object UnsafeHistogram {
    // OTel SDK defaults (milliseconds)
    private[kyo] val defaultBoundaries: Array[Double] =
        Array(0, 5, 10, 25, 50, 75, 100, 250, 500, 750, 1000, 2500, 5000, 7500, 10000)
}
