package kyo.stats.internal

import org.HdrHistogram.ConcurrentDoubleHistogram as HdrHistogram

class UnsafeHistogram(numberOfSignificantValueDigits: Int, highestToLowestValueRatio: Long) {
    private val hdr =
        new HdrHistogram(
            highestToLowestValueRatio,
            numberOfSignificantValueDigits
        )

    hdr.setAutoResize(true)

    def observe(v: Long): Unit =
        hdr.recordValue(v.toDouble)

    def observe(v: Double): Unit =
        hdr.recordValue(v)

    def count(): Long =
        hdr.getTotalCount()

    def valueAtPercentile(v: Double): Double =
        hdr.getValueAtPercentile(v)

    def maxValue(): Double =
        hdr.getMaxValue()

    def minValue(): Double =
        hdr.getMinValue()

    def meanValue(): Double =
        hdr.getMean()

    def summary(): Summary =
        Summary(
            valueAtPercentile(0.50),
            valueAtPercentile(0.90),
            valueAtPercentile(0.99),
            valueAtPercentile(0.999),
            valueAtPercentile(0.9999),
            minValue(),
            maxValue(),
            meanValue(),
            count()
        )
}
