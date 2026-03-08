package kyo.stats.internal

import java.util.concurrent.atomic.LongAdder
import scala.annotation.tailrec

/** A fixed-bucket histogram for recording value distributions, inspired by the OTel SDK's ExplicitBucketHistogramAggregation and
  * Prometheus's fixed-bucket histogram.
  *
  * Optimized for high-throughput observation: the hot path is a binary search followed by a single LongAdder.increment(). Count, min, and
  * max are derived at read time from a single-pass bucket snapshot. The snapshot is not fully atomic under concurrent writes but ensures
  * internal consistency by deriving all fields from the same snapshotted counts. Sum is not provided since exact values are not retained
  * and the OTLP histogram data model marks sum as optional. Bucket semantics use inclusive upper bounds (value <= boundary), matching
  * Prometheus and OTel conventions.
  *
  * Produces non-cumulative bucket counts compatible with the OTLP histogram data model (explicitBounds + bucketCounts).
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

    def observe(v: Long): Unit = observe(v.toDouble)

    def observe(v: Double): Unit = {
        @tailrec def loop(lo: Int, hi: Int): Unit =
            if (lo >= hi)
                buckets(lo).increment()
            else {
                val mid = (lo + hi) / 2
                if (boundaries(mid) < v)
                    loop(mid + 1, hi)
                else
                    loop(lo, mid)
            }
        loop(0, boundaries.length)
    }

    def summary(): Summary = {
        // Snapshot all buckets once for consistency
        val counts = new Array[Long](buckets.length)
        @tailrec def snapshot(i: Int): Unit =
            if (i < buckets.length) {
                counts(i) = buckets(i).sum()
                snapshot(i + 1)
            }
        snapshot(0)

        @tailrec def total(i: Int, acc: Long): Long =
            if (i >= counts.length)
                acc
            else
                total(i + 1, acc + counts(i))

        @tailrec def min(i: Int): Double =
            if (i >= counts.length)
                0.0
            else if (counts(i) > 0) {
                if (i == 0)
                    0.0
                else
                    boundaries(i - 1)
            } else
                min(i + 1)

        @tailrec def max(i: Int): Double =
            if (i < 0)
                0.0
            else if (counts(i) > 0) {
                if (i >= boundaries.length) {
                    if (boundaries.nonEmpty)
                        boundaries.last
                    else
                        0.0
                } else
                    boundaries(i)
            } else
                max(i - 1)

        Summary(
            boundaries = boundaries,
            bucketCounts = counts.clone(),
            count = total(0, 0L),
            min = min(0),
            max = max(counts.length - 1)
        )
    }

}

object UnsafeHistogram {
    // OTel SDK defaults (milliseconds)
    private[kyo] val defaultBoundaries: Array[Double] =
        Array(0, 5, 10, 25, 50, 75, 100, 250, 500, 750, 1000, 2500, 5000, 7500, 10000)
}
