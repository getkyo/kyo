package kyo.stats.internal

import org.HdrHistogram.ConcurrentDoubleHistogram as HdrHistogram

class UnsafeHistogram(numberOfSignificantValueDigits: Int, highestToLowestValueRatio: Long) {

    def observe(v: Long): Unit = {}

    def observe(v: Double): Unit = {}

    def count(): Long = 0

    def valueAtPercentile(v: Double): Double = 0

    def maxValue(): Double = 0

    def minValue(): Double = 0

    def meanValue(): Double = 0

    def summary(): Summary =
        Summary(0, 0, 0, 0, 0, 0, 0, 0, 0)
}
