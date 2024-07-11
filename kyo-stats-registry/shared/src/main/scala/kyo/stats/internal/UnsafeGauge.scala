package kyo.stats.internal

class UnsafeGauge(run: () => Double) {
    def collect(): Double = run()
}

class UnsafeCounterGauge(run: () => Long) {
    private var last = 0L
    def collect(): Long = {
        val value = run()
        if (value < 0) {
            (Long.MaxValue + value) + 2
        } else {
            value
        }
    }

    def FindDelta(a: Long, b: Long) = {
        (Long.MaxValue - a) + b
    }

    private[kyo] def delta() = {
        val curr  = collect()
        val delta = if (curr >= last) curr - last else FindDelta(last, curr)
        last = curr
        delta
    }

    private[kyo] def getLast(): Long = last
}
