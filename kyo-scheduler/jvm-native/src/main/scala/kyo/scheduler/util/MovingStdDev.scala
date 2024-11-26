package kyo.scheduler.util

final private[kyo] class MovingStdDev(window: Int) {
    private val values = new Array[Long](window)
    private var idx    = 0L

    def dev(): Double = {
        val n = Math.min(idx, window.toLong)
        if (n <= 1) return 0L
        var sum   = 0L
        var sumSq = 0L
        var i     = 0
        while (i < n) {
            val value = values(i % window)
            sum += value
            sumSq += value * value
            i += 1
        }
        val mean     = sum.toDouble / n
        val variance = (sumSq.toDouble / n) - (mean * mean)
        if (n > 1) Math.sqrt(variance * n / (n - 1)) else 0.0
    }

    def avg(): Double = {
        val n   = Math.min(idx, window.toLong)
        var sum = 0d
        var i   = 0
        while (i < n) {
            sum += values(i % window)
            i += 1
        }
        sum / n
    }

    def observe(v: Long): Unit = {
        values((idx % window).toInt) = v
        idx += 1
    }

}
