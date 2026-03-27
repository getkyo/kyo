package kyo.stats.internal

import kyo.AllowUnsafe

class UnsafeGauge(run: () => Double) extends Serializable {
    def collect()(implicit _au: AllowUnsafe): Double = run()
}

class UnsafeCounterGauge(run: () => Long) extends Serializable {
    private var last = 0L
    def collect()(implicit _au: AllowUnsafe): Long = {
        val value = run()
        if (value < 0) {
            (Long.MaxValue + value) + 2
        } else {
            value
        }
    }

    private def findDelta(a: Long, b: Long) = {
        (Long.MaxValue - a) + b
    }

    private[kyo] def delta()(implicit _au: AllowUnsafe) = {
        val curr  = collect()
        val delta = if (curr >= last) curr - last else findDelta(last, curr)
        last = curr
        delta
    }

    private[kyo] def getLast(): Long = last
}
