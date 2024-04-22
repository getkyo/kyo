package kyo.scheduler.util

final private[kyo] class MovingStdDev(window: Int):
    private val mask   = window - 1
    private val values = new Array[Long](window)
    private var idx    = 0L

    def dev(): Double =
        val n = Math.min(idx, window.toLong)
        if n <= 1 then return 0L
        var sum   = 0L
        var sumSq = 0L
        var i     = 0
        while i < n do
            val value = values(i & mask)
            sum += value
            sumSq += value * value
            i += 1
        end while
        val mean     = sum.toDouble / n
        val variance = (sumSq.toDouble / n) - (mean * mean)
        if n > 1 then Math.sqrt(variance * n / (n - 1)) else 0.0
    end dev

    def avg(): Double =
        val n   = Math.min(idx, window.toLong)
        var sum = 0d
        var i   = 0
        while i < n do
            sum += values(i & mask)
            i += 1
        sum / n
    end avg

    def observe(v: Long): Unit =
        values((idx & mask).toInt) = v
        idx += 1

    override def toString = s"MovingStdDev(avg=${avg()},dev=${dev()})"
end MovingStdDev
