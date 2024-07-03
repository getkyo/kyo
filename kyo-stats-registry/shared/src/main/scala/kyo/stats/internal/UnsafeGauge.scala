package kyo.stats.internal

class UnsafeGauge(run: () => Double) {
    def collect(): Double = run()
}

class UnsafeCounterGauge(run: () => Long) {
    private var last    = 0L
    def collect(): Long = run()
    private[kyo] def delta() = {
        val curr  = collect()
        val delta = if (curr >= last) curr - last else Long.MaxValue - last + curr
        last = curr
        delta
    }
}
