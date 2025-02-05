package kyo.scheduler.util

/** Moving standard deviation calculator optimized for high-throughput measurements.
  *
  * IMPORTANT: This implementation is not thread-safe and is only be accessed by its owning worker thread.
  *
  * This implementation maintains a fixed-size window of values and efficiently calculates rolling statistics without allocation or array
  * copying. It uses a circular buffer approach to update values in place, making it suitable for high-frequency measurement scenarios like
  * scheduler instrumentation.
  *
  * To use the MovingStdDev, create an instance with a specified window size, then call observe() to record measurements. The current
  * standard deviation and mean can be retrieved at any time using dev() and avg() respectively. The implementation automatically maintains
  * the sliding window as new values arrive.
  *
  * @param window
  *   Size of the measurement window
  */
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
