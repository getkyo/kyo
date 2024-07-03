package kyo.stats.internal

import java.util.concurrent.atomic.LongAdder

class UnsafeCounter {
    private var last  = 0L
    private val adder = new LongAdder

    def get(): Long        = adder.sum()
    def inc(): Unit        = adder.increment()
    def add(v: Long): Unit = adder.add(v)

    private[kyo] def delta() = {
        val curr  = get()
        val delta = if (curr >= last) curr - last else Long.MaxValue - last + curr
        last = curr
        delta
    }
}
