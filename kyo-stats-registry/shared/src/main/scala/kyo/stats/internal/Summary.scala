package kyo.stats.internal

import scala.annotation.tailrec

case class Summary(
    boundaries: Array[Double],
    bucketCounts: Array[Long],
    count: Long,
    min: Double,
    max: Double
) {
    def percentile(v: Double): Double = {
        val target = (v * count / 100.0).toLong
        @tailrec def loop(i: Int, cumulative: Long): Double =
            if (i >= bucketCounts.length) {
                if (boundaries.nonEmpty)
                    boundaries.last
                else
                    0.0
            } else {
                val next = cumulative + bucketCounts(i)
                if (next < target)
                    loop(i + 1, next)
                else if (i == 0) {
                    if (boundaries.nonEmpty)
                        boundaries(0) / 2.0
                    else
                        0.0
                } else if (i >= boundaries.length)
                    boundaries.last
                else {
                    val bucketCount = bucketCounts(i)
                    if (bucketCount == 0)
                        boundaries(i - 1)
                    else {
                        val fraction = (target - cumulative).toDouble / bucketCount
                        boundaries(i - 1) + fraction * (boundaries(i) - boundaries(i - 1))
                    }
                }
            }
        loop(0, 0L)
    }
}
