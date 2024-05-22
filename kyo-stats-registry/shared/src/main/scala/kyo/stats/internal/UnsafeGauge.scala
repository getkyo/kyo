package kyo.stats.internal

class UnsafeGauge(run: () => Double) {
    def collect(): Double = run()
}

class UnsafeCounterGauge(run: () => Long) {
    private var last    = 0L
    def collect(): Long = run()
    private[kyo] def delta() = {
        val curr  = collect()
        val delta = curr - last
        last = curr
        delta
    }
}
