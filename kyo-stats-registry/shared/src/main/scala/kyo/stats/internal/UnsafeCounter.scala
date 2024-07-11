package kyo.stats.internal

import java.util.concurrent.atomic.LongAdder

class UnsafeCounter {
    private var last  = 0L
    private val adder = new LongAdder

    def get(): Long        = adder.sumThenReset()
    def inc(): Unit        = adder.increment()
    def add(v: Long): Unit = adder.add(v)

    def AddExact(a: Long, b: Long) = {
        val sum = a + b
        if (sum < 0) {
            (Long.MaxValue + sum) + 2
        } else {
            sum
        }
    }

    def FindDelta(a: Long, b: Long) = {
        (Long.MaxValue - a) + b
    }

    private[kyo] def delta() = {
        val curr  = AddExact(get(), last)
        val delta = if (curr >= last) curr - last else FindDelta(last, curr)
        last = curr
        delta
    }

    private[kyo] def getLast(): Long = last
}
